# T19 — `--serve` HTTP server (3 SP)

- **Phase:** 4 — HTTP & cross-repo
- **Depends on:** T07, T13
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap --serve [port]` (R90, R96, R101; DESIGN §7, D20–D21, gotcha 6): validate and
snapshot the repository at startup (snapshot = canonical serializer bytes, fixed once);
bind `127.0.0.1` only; default port 8765, `0` → OS-selected; print + flush the plain
URL line; `GET /repository.json` → 200 with `Content-Type: application/json;
charset=utf-8`; HEAD same status/headers, zero body bytes; any other raw request target
(including `?query`) → 404; other methods → 405 + `Allow: GET, HEAD`; SIGINT/SIGTERM →
exit 0 via `sun.misc.Signal`.

## Scope
`snap/scala/src/main/scala/snap/http/Server.scala`,
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (serve), tests in
`snap/scala/src/test/scala/snap/http/`.

## Acceptance criteria
- [x] Provided test `12-http-server` passes (`--filter http-server`), including the
      byte-pinned GET body, HEAD-with-no-body over a raw socket, snapshot immunity to a
      later commit, and SIGTERM/SIGINT exiting 0.
- [x] Unit tests (own HTTP client against an ephemeral port): 404 for `/other` and for
      `/repository.json?x=1`; 405 + `Allow: GET, HEAD` for POST/PUT/DELETE; headers
      identical between GET and HEAD.
- [x] Bare `snap --serve` binds port 8765 (untested by the suite — R90 holdout gap,
      unit-tested here with a skip-if-occupied guard).
- [x] Invalid repository at startup → exit 1, no URL printed, no port bound; the URL
      line is written and flushed before the accept loop starts (no buffering).

## Notes / decisions

## Pointer for this task's review (phase-2 review triage, holdout exposure 2)
- `snap --serve badport` in a **non-repository** directory currently reports
  `snap: not a Snap repository`, not `snap: invalid port: badport`, because repository
  discovery runs before `CommandsServe`'s port-*value* check. Both provided tests (14, 24)
  run `--serve` inside a valid repository, so neither discriminates. Phase-1 finding CR7
  established that argument validation precedes filesystem IO, and a port value is pure
  argument validation — which suggests the value check belongs ahead of discovery. T13
  chose the current order by analogy to D10. **T19's reviewer rules on this**; if it moves
  ahead of discovery, `Grammar`'s `--serve` rule is the natural home and the change is
  behavior-visible only in a non-repository directory.
- **Architecture:** `snap/http/Server.scala` is pure infrastructure — `start(snapshot, port)`
  binds and returns an `Instance` (port + `stop()`), `installShutdownHandlers()` wires
  `sun.misc.Signal` INT/TERM to `System.exit(0)` (D21), `blockForever()` parks the calling
  thread, `readyLine(port)` formats the SPEC §7.9 URL text. `CommandsServe.handler` composes
  these: `requireRoot` → `parsePort` (unchanged from T13) → `Commands.readRepository` (loads
  + fully validates, §4.5 steps 1–6) → `RepoCodec.encodeBytes` (D7's one canonical
  serializer, snapshotted once) → `Server.start`. On success it installs the signal
  handlers, prints+flushes the ready line via `Presentation.Plain` (R96: always plain,
  named directly rather than looked up, since no per-stream renderer exists yet), then
  calls `Server.blockForever()` (type `Nothing`, so the `for`-yield's `Either[SnapError,
  String]` typechecks with no dead-code fallback value needed).

- **Ordering bug found and fixed during verification (real, not environmental):** the
  first implementation installed the signal handlers *after* printing the ready line.
  Test 12's `interruptible` case starts the server and sends SIGINT with *no* intervening
  step, so there is effectively no time between "ready line visible" and "signal sent" —
  a real race that hit the JVM's default SIGINT disposition (exit 130) essentially every
  time, while `origin`'s SIGTERM (many steps later) almost always won the race by
  accident. Root-caused by writing a minimal standalone `sun.misc.Signal`+`HttpServer`
  reproduction outside the project and bisecting instruction order — not a JDK/OS/sandbox
  quirk. Fixed by installing the handlers *before* the ready line is printed
  (`CommandsServe.handler`); documented in the handler's doc comment so the ordering isn't
  "fixed" back by a future edit.

- **`Connection: close` on every response (Server.scala) — found and fixed during
  verification, not spec-mandated but empirically load-bearing:** `com.sun.net.httpserver`
  closes the TCP connection server-side after any response it can't frame as persistent
  (every `sendResponseHeaders(code, -1)` call here — HEAD, 404, 405), but without an
  explicit `Connection: close` response header, a client that pools HTTP/1.1 connections
  (Node's default `http.Agent`, which is exactly what the harness's non-HEAD
  `http_request` step uses) doesn't find out until it tries to reuse the now-dead socket
  for its next request — surfaced as "socket hang up" on the request immediately
  following a 404/405 in test 12's sequence (reproduced independently with a standalone
  `com.sun.net.httpserver` server, confirmed unrelated to this project's own routing
  logic). Fixed by sending `Connection: close` on every response, including the 200s, for
  one uniform policy rather than reasoning per status code about which lengths the
  framework happens to frame safely today. SPEC §9 says nothing about persistent
  connections either way, so this is a "most spec-consistent reading" call, not a
  core-semantics one.

- **New error case `SnapError.CannotBindServer(detail)`** (Errors.scala, `// T19
  additions` blocks at the three documented insertion points: enum case list, `message`
  match, `Messages` catalog at EOF): SPEC §7.9 doesn't name a bind-failure diagnostic, but
  every other filesystem/network effect boundary in this codebase converts a thrown I/O
  failure to a typed `SnapError` (D4) rather than letting it surface as R107's generic
  exit-2 "internal error" — kept consistent for the one new failure mode this task
  introduces (e.g. the default port already being in use). Untested wording, per D5's
  convention for untested diagnostics.

- **`Server.start`'s executor:** JDK's `HttpServer.setExecutor(null)` (the "use the
  framework's own default" idiom) needs a literal `null`, which `DisableSyntax.noNulls`
  forbids project-wide. Used an explicit `Executors.newSingleThreadExecutor()` instead,
  owned by `Instance` and shut down in `stop()` — ample for a read-only, two-route
  snapshot server (DESIGN D2 rationale) and avoids the `null` outright rather than adding
  a `scalafix:ok` suppression. Confirmed by a standalone repro that the SIGINT/`Connection:
  close` findings above are independent of this choice (both reproduced identically with
  the JDK's own default `null` executor too).

- **Port-parse vs. repository-load order:** kept T13's existing order (root, then port,
  then — new in this task — repository load, then bind). SPEC §7.9's own bullet order only
  fixes "validate/snapshot the repository" *before* "bind"; it says nothing about the
  relative order of the two upstream value checks (root discovery, port parsing) against
  each other, so neither test 14 nor 24 (which only ever supply syntactically-absent
  repositories together with the bad port) can distinguish an ordering here. Most
  spec-consistent reading: keep the cheaper, already-reviewed T13 checks first.

- **Own test coverage boundary (`CommandsServeSuite`/`snap.http.ServerSuite`):** a
  successfully-bound `--serve` handler installs real SIGINT/SIGTERM handlers and then
  blocks the calling thread until one fires. Driving that success path through
  `Cli.run`/`CommandsServe.handler` from a project unit test would either hang the test
  suite forever (nothing in-process ever signals it) or, if a real signal were raised to
  unblock it, `System.exit` the whole `sbt test` JVM. So: routing/headers/binding are
  unit-tested directly against `snap.http.Server` (bind port 0, own raw-socket HTTP
  client, `stop()` in a `finally`); the one handler-level case that *is* safe to drive
  through `Cli.run` synchronously (invalid repository at startup — fails before
  `Server.start` is ever reached) is tested there. The full success path (ready line +
  SIGINT/SIGTERM + process exit) is exercised only by provided test 12, run as a real
  subprocess — recorded explicitly in both suites' class docs so a future reader doesn't
  "fix" the gap by making a test block forever.

- **Test-suite fix required by this task (pre-existing, not new scope creep):**
  `CommandsServeSuite`'s two "reaches 'not implemented'" cases and `CliSuite`'s
  `--serve` entry in the unimplemented-commands table both asserted the T13-era stub
  behavior; left unchanged they would now either hang (a real `--serve` bind + block) or
  fail (repo-not-found error text differs from "not implemented"). Updated in the same
  spirit as T10/T12's prior removals from that same `CliSuite` table.

- **Gates:** `sbt test` 598/598 project tests green (10 new in `snap.http.ServerSuite`,
  `CommandsServeSuite`/`CliSuite` updated); `sbt scalafmtCheckAll` and
  `sbt "scalafixAll --check"` both green (no `scalafix:ok` suppressions needed — the
  `null`/`throw` findings above were designed around, not suppressed); provided suite
  `--filter http-server` → 1/1; full suite → 16/28 (baseline 15 + this task's 12; the
  other 12 failures are the pre-existing `merge`-command gap — tests 09, 10, 11, 13, 16,
  17, 18, 20, 21, 22, 26 — plus 28's unrelated terminal-presentation gap (T22), none of
  them a regression from this task).

- **Integration onto `main` after T17/T18/phase-2 review (orchestrator, 2026-09-05):**
  T19's diff (base: before T17) was staged onto `main`, which had since gained T17
  (`merge`), T18 part 1 (test-only property suites), and the phase-2 review fixes
  (uniform `--`-prefixed-operand grammar rule / D28, plus a `revert` invariant check).
  Three conflicts were pre-resolved by the orchestrator before this verification pass;
  all three checked out as semantically correct:
  1. `Errors.scala` — `CannotBindServer`'s three `// T19 additions` insertion points
     (enum case, `message` match, `Messages` entry) land cleanly after T13's `InvalidPort`
     additions with no duplicate case names and no reordering of the existing catalog.
  2. `CliSuite`'s "known-but-unimplemented commands" table was correctly deleted (T17 +
     T19 left it with no subjects); the comment's claim that the two remaining
     `not implemented` seams are pinned in `CommandsDiffSuite` (`diff … --repo`,
     line ~145) and `CommandsMergeSuite` (`merge http://…`, line ~276) is verified true —
     both exist and pass.
  3. `tasks/T19-http-server.md` — both the phase-2-review pointer and T19's own notes
     were kept; confirmed no content was silently dropped.
  - **D28/`parsePort` interaction:** confirmed no disagreement. `Grammar.serveRule`
    (`Grammar.scala`) rejects any `--`-prefixed `--serve` operand as an unknown-option
    grammar error *before* `CommandsServe.parsePort` ever runs, so `parsePort`'s own
    canonical-decimal check (which would also reject a `--`-prefixed string, just with a
    different error — `invalid port: …` vs. `invalid command or arguments`) is unreachable
    through `Cli.run` for that input class, exactly as documented in both files'
    doc comments. `GrammarSuite` pins the grammar-layer case; `CommandsServeSuite` pins
    `parsePort`'s own unit behavior and the `65536`/`0 extra` cases that do reach
    `Cli.run`. No test exercises the two layers disagreeing, because they can't for any
    input reachable via `Cli.run`.
  - **`Cli.stub` val (`Cli.scala:86`, was `:79` pre-edit):** still referenced — it seeds
    `defaultCommands`' initial map (`.map(c => c -> stub)`, `Cli.scala:101`) before every
    one of the 9 non-`Version` commands is immediately overwritten via `.updated(...)`.
    Since it's referenced, `-Wunused`/`-Werror` does not flag it (confirmed: `sbt -batch
    clean assembly` compiles clean both before and after this integration's edits) — it
    is functionally dead (no map entry resolves to it anymore, now that `Merge` and
    `Serve` are both real) but not syntactically unreferenced, so it does not need to be
    removed on that basis. Left in place per the task instructions ("if it is still
    referenced, leave it and say by what"), but its stale doc comment — which said
    "`Merge` stays on the stub until T17" and the server "is still `NotImplemented` until
    T19" — was rewritten (`Cli.scala:79–86` and `:88–97`) to describe the current,
    fully-landed state instead of dangling forward references to now-completed tasks.
  - **Gates re-run after integration:** `sbt -batch clean assembly` clean; `sbt -batch
    test` 631/631 (main's 623 + this integration's net +8: +10 `ServerSuite`, −1
    `CommandsServeSuite` [2 removed "not implemented" cases, 1 new invalid-repository
    case], −1 `CliSuite` [deleted unimplemented-commands table]); `sbt -batch
    scalafmtCheckAll` and `sbt -batch "scalafixAll --check"` both green (one
    `scalafmtAll` pass was needed on `Cli.scala` after the doc-comment edit above, no
    scalafix findings); `PATH=<java17>:$PATH ./snap/verify --lang scala` → 24/28, exactly
    the expected set (12-http-server plus main's prior 23; the 4 remaining failures are
    13/`13-http-client.yaml` (T20), 16/`16-dot-collision.yaml`, 26/
    `26-portability-and-failure-safety.yaml`, 28/`28-terminal-presentation.yaml` (T22) —
    all pre-existing gaps, none a regression from this integration). Test 12
    (`--filter http-server`) additionally re-run 5 more times in isolation to check the
    documented signal-handler race: 6/6 green overall, no flakiness observed. No bound
    port or live process left behind (`lsof -i :8765` and a `ps` scan both empty after
    the run).

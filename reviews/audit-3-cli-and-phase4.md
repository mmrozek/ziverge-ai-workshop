# Post-completion audit — lens 3: CLI surface, HTTP/cross-repo layer, final gate

**Verdict: `release-with-fixes`** — 0 Major, 3 Minor, 7 Nit.

Scope: phase 4 (T19 `--serve`, T20 HTTP client, T21 cross-repo) — never reviewed by anyone
but its author — plus phase 5 (T22 presentation, T23 hardening), the whole CLI surface, and
the duties of the skipped phase-5 gate (antipattern audit, full suite, lint gate,
`scalafix:ok` justification).

Method: every finding below was reproduced with a real command against a real server or
repository, not inferred from reading. Findings are marked CONFIRMED (reproduced) or
PLAUSIBLE (traced through code, not reproduced).

## Calibration note

No finding is marked Major. The two most serious findings (1 and 2) are genuine deviations
from spec prose and from the project's own D15 design claim, but both require a hostile or
broken remote server, neither can be expressed in the public harness's step vocabulary
(`start_http` always sends a complete response — `snap/test-harness/src/http-server.ts:15-25`),
and neither can produce a wrong merge result: the HTTP path is read-only and idempotent.
Shipping them risks a spec-prose deviation and a hang against a malicious URL, not
incorrect version-control behaviour. The orchestrator may reasonably escalate finding 1 to
Major on the strength of §9's literal "one GET"; I record the reasoning so that call can be
made deliberately.

---

## Findings

### 1. Minor — the HTTP client issues a **second** GET when the connection drops with no response bytes (R102, §9)

**Spec:** §9 / R102 — "Snap performs **one** GET of that exact URL". The code asserts the same
invariant: `Client.scala:23` "Exactly one GET of the exact URL"; `Client.scala:71-74` "The one
GET (SPEC §9)"; `ClientSuite.scala:128` "also fails, with no retry".

**File:** `snap/scala/src/main/scala/snap/http/Client.scala:90-100` (the `sendAsync` call) —
the retry is performed inside the JDK's `HttpClient`, which retries idempotent requests once
when a connection yields zero response bytes.

**Status: CONFIRMED.** A raw-socket server that accepts the connection, reads the request, and
closes without writing anything receives two identical requests:

```
$ bash /tmp/snapaudit/probe.sh closeimmediately /repository.json merge
exit=1  stderr: snap: cannot fetch remote repository: HTTP/1.1 header parser received no bytes
server log:
CONN 1 from ('127.0.0.1', 64980)
REQ 1: GET /repository.json HTTP/1.1
CONN 2 from ('127.0.0.1', 64981)
REQ 2: GET /repository.json HTTP/1.1
```

Both request lines are byte-identical (same `Upgrade: h2c` header block), so this is a retry,
not an HTTP/2 fallback. The worst shape — a **successful** command built on two GETs:

```
$ bash /tmp/snapaudit/retry.sh closefirstthenok     # server closes conn 1, serves conn 2
--- BEFORE files: a.txt   frontier: [["alice@example.com",1
(alice@example.com->1,bob@example.com->1)
exit=0
--- AFTER files: a.txt b.txt
--- server saw ---
CONN 1 / REQ 1: GET /repository.json HTTP/1.1
CONN 2 / REQ 2: GET /repository.json HTTP/1.1
```

`snap merge` exits 0, imports bob's patch, and installs `b.txt` — having performed two GETs
and used the second response. The retry count is bounded at exactly one (verified with a
server that always closes: 2 connections, then failure).

**Why no test caught it:** `ClientSuite` asserts `stub.requests.size == 1` for 302 and 500, but
every stub always sends a complete response. The connection-drop shape is untested, and the
provided harness cannot express it.

**Direction:** the JDK 17 `HttpClient` exposes no property to disable retry for idempotent
methods (`jdk.httpclient.enableAllMethodRetry` only *adds* retries for non-idempotent ones), so
a literal fix needs a wire-level single-request client. The minimum acceptable fix is to stop
claiming the opposite: correct `Client.scala:23`, `:71-74` and `ClientSuite.scala:128`, and
record the deviation in DESIGN D15 so a future reader does not trust an invariant that does not
hold.

---

### 2. Minor — D15's timeout and body cap bound neither memory nor time for an unbounded response body; the process **hangs forever**

**Spec/design:** D15 as documented at `Client.scala:44-51` — "A hanging server must fail with an
error, not hang past the timeout, in BOTH shapes of hang". The audit brief asks specifically
"whether a slow-but-progressing body can exceed the cap before it is noticed". It can, and the
consequence is worse than exceeding the cap.

**File:** `snap/scala/src/main/scala/snap/http/Client.scala:123-138` (`cappingHandler`) —
`BodySubscribers.ofByteArray()` accumulates the entire body with no bound, and the cap is only
checked in the `mapping` function *after* the body completes. `Client.scala:99` (`orTimeout`)
is scheduled on `CompletableFutureDelayScheduler`, which is itself a JVM thread.

**Status: CONFIRMED.** A server sending an endless chunked body (no `Content-Length`):

```
$ bash /tmp/snapaudit/cap.sh chunkedforever -Xmx256m
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "HttpClient-1-Worker-2"
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "HttpClient-1-SelectorManager"
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "CompletableFutureDelayScheduler"

$ ps -o pid,etime,rss -p 55094
  PID ELAPSED    RSS
55094   05:19 324864        # still alive at 5m19s, no exit code, 30s timeout never fired
```

The OOM kills the delay scheduler — the very thread `orTimeout` needs — so the 30 s deadline
never fires and the command never terminates. Note the third failure mode is not the documented
one: neither "fails with an error" nor "hangs past the timeout", but "hangs forever with no
diagnostic". `Main.run`'s `catch t: Throwable` never runs because nothing ever returns to it.

Both *terminating* oversize shapes work correctly, so the cap itself is sound for well-behaved
servers:

```
70 MB declared Content-Length: snap: cannot fetch remote repository: remote response declares
  70000000 bytes, over the 67108864-byte limit          (exit 1, 1s, one GET)
70 MB chunked, no length:      snap: cannot fetch remote repository: remote response is
  70000000 bytes, over the 67108864-byte limit          (exit 1, 1s, one GET)
```

**Direction:** enforce the cap on *in-flight* bytes — a custom `BodySubscriber` that counts in
`onNext` and calls `subscription.cancel()` the moment the running total exceeds `maxBodyBytes`
— rather than checking the length of an already-materialised array. That bounds memory, which
in turn keeps the timeout thread alive. Also correct `Client.scala:44-51`'s "BOTH shapes"
claim: there are three.

---

### 3. Minor — one non-reading client wedges `--serve` for every other client

**File:** `snap/scala/src/main/scala/snap/http/Server.scala:74-82` — `Executors.newSingleThreadExecutor()`,
justified by the comment at `:76-80`: *"no request ever blocks on I/O beyond writing an in-memory
byte array"*. That premise is false: `Server.scala:170`
(`exchange.getResponseBody.write(snapshot)`) is a blocking socket write, and it blocks as soon
as the kernel send buffer fills against a client that does not read.

**Status: CONFIRMED.** Repository with one 4 MiB file (`repository.json` = 5 592 751 bytes):

```
ready=http://127.0.0.1:49426/repository.json
baseline request: received 5592898 bytes                      # healthy client works
second client while first stalls: received 0 bytes -> WEDGED (no response)
```

Client 1 sends `GET /repository.json` and never reads. Client 2 connects 3 s later and receives
**zero bytes**, waiting indefinitely. The server is unusable until restarted (it still exits 0
on SIGTERM). One idle socket takes the whole server down.

There is also no write deadline, so the stall persists as long as client 1 holds the socket.

**Direction:** a small fixed thread pool (`Executors.newFixedThreadPool(n)`) bounds the damage
to n stalled clients instead of one; a `setSoTimeout`-equivalent write deadline removes it. If
the single-thread choice is deliberate for determinism, replace the comment at `:76-80` with an
accurate statement of the trade-off — the current text asserts the opposite of the measured
behaviour.

---

### 4. Nit — `OPTIONS *` (any non-`/`-prefixed request target) bypasses the handler and returns the JDK's branded HTML 404

**Spec:** §9 / R101 — "Other paths return 404". The status is right; nothing else is.

**File:** `snap/scala/src/main/scala/snap/http/Server.scala:73` — `createContext("/")` only
matches targets beginning with `/`, so `com.sun.net.httpserver` answers before `handler` runs.

**Status: CONFIRMED.**

```
@@@ OPTIONS *
HTTP/1.1 404 Not Found
Content-Length: 50
Content-Type: text/html
=== BODY BYTES: 50 ===
<h1>404 Not Found</h1>No context found for request
```

Every other 404 from this server is empty-bodied with `Connection: close`; this one has an HTML
body, no `Connection: close` (defeating the connection-pooling defence documented at
`Server.scala:136-145`), and leaks the framework's identity. Low impact — no real client sends
`OPTIONS *` to a snap server.

**Direction:** accept and document, or route the unmatched-target case explicitly.

---

### 5. Nit — duplicate `Connection: close` header on HTTP/1.0 responses

**File:** `snap/scala/src/main/scala/snap/http/Server.scala:162-163` — `getResponseHeaders.add(...)`
where `set(...)` is meant; the framework adds its own `Connection: close` for HTTP/1.0.

**Status: CONFIRMED.**

```
@@@ GET /repository.json HTTP/1.0
HTTP/1.1 200 OK
Connection: close
Connection: close        <-- twice
Content-type: application/json; charset=utf-8
Content-length: 838
```

Legal (list-valued header) but sloppy; a strict client, or the harness's own `parseRawResponse`
(`http-server.ts:120`, which joins duplicates as `"close, close"`), would see a different value
than on HTTP/1.1. The harness never sends HTTP/1.0, so nothing currently observes it.

**Direction:** `set` instead of `add`.

---

### 6. Nit — a Java class name leaks into a user-facing diagnostic, and the two hang shapes report differently

**File:** `snap/scala/src/main/scala/snap/http/Client.scala:140-141` — `describe` falls back to
`e.getClass.getSimpleName` when `getMessage` is null, which is exactly the case for
`java.util.concurrent.TimeoutException`.

**Status: CONFIRMED.** Same user-visible condition (server hangs, 30 s elapsed), two diagnostics:

```
headers-then-stall:  snap: cannot fetch remote repository: TimeoutException
never responds:      snap: cannot fetch remote repository: request timed out
```

R107's shape (`snap: <detail>`, one line, exit 1) is satisfied either way, and no test pins the
wording. **Direction:** map `TimeoutException`/`HttpTimeoutException` to one phrase.

---

### 7. Nit — the "one canonical shape parser" invariant is not global

**Files:** `snap/scala/src/main/scala/snap/cli/Grammar.scala:96-99` and `:117-120` versus
`snap/scala/src/main/scala/snap/cli/CommandsServe.scala:72-75`,
`snap/scala/src/main/scala/snap/cli/CommandsMerge.scala:94-96`,
`CommandsRevert.parseOperand`.

T23 removed the drift risk for `init`/`config`/`diff` by having `Grammar` call
`CommandsInit.parsePath` / `CommandsConfig.parseOperands` / `CommandsDiff.parseShape`. It did
not for `--serve`, `commit`, `revert`, `merge`, which still declare their arity twice.

**Status: CONFIRMED by reading; harmless today.** In each case `Grammar`'s rule is strictly
stricter (it additionally rejects `--`-prefixed operands) and runs first (`Cli.scala:162`), so a
one-sided edit cannot change an outcome — but the doc comments at `Grammar.scala:61-63` and
`:104-108` present the delegation as the file's policy, which it is only for three of seven.

---

### 8. Nit — `Main`'s exit-2 line is a second, undocumented bypass of `Cli.emit`

**File:** `snap/scala/src/main/scala/Main.scala:31` — `env.stderr.print(s"snap: …\n")` prints
plain unconditionally. §7.11 says a plain error line `<error>` becomes `S(31,"✗ " + <error>)`,
with only two documented exemptions (the `SNAP_COLOR` validation error and the `--serve` URL).
Under `SNAP_COLOR=always`, an internal error would be unstyled.

**Status: PLAUSIBLE only — I could not reach exit 2 from the CLI at all.** Every hostile input I
tried produced a clean exit-1 error:

```
unreadable repository.json        exit=1  snap: cannot read repository: …
repository.json is a directory    exit=1  snap: cannot read repository: Is a directory
200k-deep nested JSON arrays      exit=1  snap: invalid JSON at line 1 column 4097
100k-deep unterminated objects    exit=1  snap: invalid JSON at line 1 column 39
```

(No `StackOverflowError`: jawn's parser is iterative and depth-bounded.) The two `throw` sites
are the documented internal-invariant guards, both proven unreachable. So this is a latent
inconsistency, not an observable one.

---

### 9. Nit — the TTY probe can turn any command into exit 2 on a host without `/bin/sh`

**File:** `snap/scala/src/main/scala/snap/cli/Env.scala:38-47` — `Tty.Real.probe` runs
`new ProcessBuilder("/bin/sh", "-c", "test -t N").start()`. `Presentation.select` is called
before parse/grammar/dispatch (`Cli.scala:155`), so in `auto` mode with `NO_COLOR` absent every
invocation — including `--version`, which touches nothing else — spawns two subprocesses. An
`IOException` from `start()` escapes `select` into `Main.run`'s catch-all → exit 2 with
`snap: internal error: …`.

**Status: PLAUSIBLE (traced, not reproduced).** Measured cost is negligible and not itself a
concern: 20 × `--version` took 2.58 s with `NO_COLOR` set (no probe) vs 2.82 s in `auto`
(≈12 ms per invocation). The child's stdin pipe is also never closed (one fd per probe), which
is immaterial for short-lived processes.

**Direction:** wrap the probe so a failed spawn degrades to "not a TTY" (plain mode) rather than
aborting the command.

---

### 10. Nit — the remote-operand prefix test is case-sensitive

**File:** `snap/scala/src/main/scala/snap/cli/Commands.scala:41-42` —
`operand.startsWith("http://") || operand.startsWith("https://")`.

**Status: CONFIRMED by reading.** `snap merge HTTP://host/repository.json` is treated as a
*local path* and fails with `cannot read repository: <cwd>/HTTP:/host/…/.snap/repository.json`.
RFC 3986 schemes are case-insensitive; R78's "an explicit `http://` or `https://` URL" is
written lowercase, so the literal reading is defensible and no test exercises it. Recorded so
the reading is a decision rather than an accident.

---

## Anti-pattern audit (`scala-antipatterns`, search root `snap/scala/src/main/scala`)

All four scans run; every hit read in context before classification.

| File:line | Pattern | Severity | Action | Reason |
|---|---|---|---|---|
| — | 1. `var` | — | — | **Zero hits** across all 42 main sources. |
| `Main.scala:25-26` | 2. `try`/`catch` | low | NOFIX | The single designated boundary the skill explicitly exempts (R107/D4 exit-2 catch-all). No value-returning control flow. |
| `http/Server.scala:157` | 2. `try`/`finally` | low | NOFIX | Not a match (no `catch`). Side-effecting cleanup only (`exchange.close()`), no return value. Recorded as a skip. |
| `json/RepoCodec.scala:44-303` | 3. hand-rolled codec | medium | NOFIX | Skill rule: "**Always NOFIX** if the spec pins an exact byte format." §4.1 pins the wire shape and §4.5 requires rejecting unknown fields; derivation would lose both. Also shape-transforming (version as `[id,rev]` pairs, canonical base64). |
| `json/ConfigCodec.scala:24-77` | 3. hand-rolled codec | medium | NOFIX | Same: §8 pins `{"contributor":{"id":…}}` exactly and requires unknown-field rejection. |
| `core/TextTokens.scala:19,29` | 3. `def decode` | low | NOFIX | UTF-8 validation, not serialization. False positive on the grep. |
| `core/Replay.scala:608` | 3. `def encode` | low | NOFIX | Token vector → bytes; §4.4 semantics, not a wire codec. |
| `cli/Env.scala:85-86` | 4. env/property read | critical→low | NOFIX | `System.getProperty("user.dir")` + `sys.env`, inside `Env.real()` — the one boundary DESIGN §2 designates. Verified: no other `sys.env`/`System.getenv`/`getProperty` anywhere in main. Recorded per the skill's "no silent passes" rule. |
| `core/Replay.scala:99` | 4. unordered `Set` | critical→low | NOFIX | `dots` is probed with `.contains` only (`:100`), never iterated. Read and verified. |
| `core/Repo.scala:89` | 4. unordered `Set` | critical→low | NOFIX | Passed to `checkBaseClosure`/`checkFrontierClosure`, which use `.contains` only (`Repo.scala:156,171`). Never iterated. |
| `core/Path.scala:87` | 4. unordered `Set` | critical→low | NOFIX | `present` used only in `!present.contains(...)`; the doc at `:82-85` states the order-independence argument and it holds. |
| `cli/CommandsLog.scala:34` | 4. unordered `Map` | critical→low | NOFIX | `byDot` probed by key only; output order comes from `Replay.integrationOrder` (`:40`). Comment at `:31-33` states this and is accurate. |
| `cli/Cli.scala:18,117`, `cli/Grammar.scala:123` | 4. unordered `Map` | critical→low | NOFIX | Dispatch tables indexed by key; never iterated for output. |
| `json/RepoCodec.scala:30-34`, `json/ConfigCodec.scala:19-20` | 4. unordered `Set` | critical→low | NOFIX | Known-field sets, membership only. |
| `json/AstFacade.scala:6` | 4. `mutable.ListBuffer` | critical→low | NOFIX | Documented single-parse, single-thread mutability boundary (`:8-16`); appended strictly in document order, read only after the parse. Result is a pure function of the input. |
| `http/Server.scala` (framework) | 4. clock in output | low | NOFIX | `com.sun.net.httpserver` emits a `Date:` header from the wall clock. RFC-required for an origin server, framework-generated, not part of any asserted contract. Recorded as a skip. |

**16 findings: 0 FIX, 16 NOFIX.** No `var`, no `try`/`catch` outside the designated boundary,
no clock/random/env read below `Env.real()`, and no unordered collection feeding output or a
merge decision.

Positively verified (not just absence of hits): `Tree` is a `TreeMap` with structural
byte-equality (`Tree.scala:17,49-61`); replay warnings are a `SortedSet`; `WorkTree.children`
(`WorkTree.scala:106-112`) and `Materialize.childNames` (`Materialize.scala:154-161`) both sort
directory listings with `Utf8Order` explicitly, because raw listing order is filesystem-
dependent. Confirmed empirically — with three symlinks planted at `aaa.link`, `zzz.link` and
`mid/bbb.link`, eight consecutive runs all reported the same one:

```
unsupported-entry choice over 8 runs: {'snap: unsupported working tree entry: aaa.link'}
```

### `scalafix:ok` suppressions

Exactly two, both justified; no others have crept in (`grep -rn "scalafix:ok\|scalafix:off\|nowarn\|@SuppressWarnings" snap/scala/src/` returns only these):

- `cli/CommandsRevert.scala:130` — `DisableSyntax.throw`, the internal-invariant guard routing to
  the exit-2 channel. Doc at `:102-124` states the reachability argument (a revert patch is a
  serial append on a fully integrated frontier, so §6.2 rule 1 makes the two computations
  provably equal). Verified: nothing between it and `Main.run` catches `Throwable`, so this is
  not `try`/`catch`-as-control-flow.
- `cli/CommandsCommit.scala:186` — same pattern, same argument, doc at `:159-180`.

Both are genuinely the only sanctioned route to R107's exit-2 channel: returning
`Left(SnapError…)` would surface as an exit-1 diagnostic a user could trigger, which would be
wrong.

---

## Final gate — reproduced

Run from this worktree with Java 17 first on `PATH`
(`~/.sdkman/candidates/java/current/bin`, version 17.0.12).

| Gate | Command | Result |
|---|---|---|
| Project suite | `sbt -batch test` | `Passed: Total 693, Failed 0, Errors 0, Passed 693` — `[success] Total time: 11 s` |
| Format | `sbt -batch scalafmtCheckAll` | `[success]`, 42 + 54 sources checked, exit 0 |
| Lint | `sbt -batch "scalafixAll --check"` | `[success]`, 42 + 54 sources, exit 0 |
| Acceptance | `./snap/verify --lang scala` | **28 passed** in 77 295 ms, 0 failed |

Working tree left clean; no port bound and no process left running (verified with
`lsof -nP -iTCP -sTCP:LISTEN` and `ps`). All scratch repositories and probe servers live under
`/tmp/snapaudit/`.

---

## Checked and found correct

**HTTP client (R78, R102, D15)**

- Request target preserved **byte-for-byte**, including query string and percent-encoding, with
  exactly one GET each: `/repository.json`, `/repository.json?x=1&y=%20z`, `/a%2Fb/repository.json`
  all appeared on the wire verbatim.
- Redirects never followed: 302 → `snap: remote repository request failed: HTTP 302`, exit 1,
  **one** request; the `Location` header is not fetched.
- Non-200 → `HTTP <status>` and no second request (302 and 500 both verified with a recording
  server).
- Both documented hang shapes are bounded at 30 s with one request each: headers-then-stall
  → error in 30 s; no response at all → error in 31 s. The reasoning in `Client.scala:44-51`
  (that `HttpRequest.Builder.timeout` alone does not bound the mid-body stall, hence the
  `orTimeout` layer) is empirically correct — the two shapes surface through different
  mechanisms (`TimeoutException` from `orTimeout` vs `request timed out` from the builder).
- The abandoned exchange does not block JVM exit; the process terminates normally in every
  bounded case.
- Malformed 200 body → the same `invalid JSON` diagnostic class a local file would produce.
- Both terminating oversize shapes rejected at ~1 s with the correct cap diagnostic (see
  finding 2).

**Server (R90, R96, R101, D20/D21)**

- Snapshot immutability: served bytes unchanged across a subsequent `commit` (served frontier
  `[["alice@example.com",1]]` while on-disk frontier had advanced to `2`) and even after
  `repository.json` was deleted from under the running server.
- Binds `127.0.0.1` only — `lsof` shows a single `TCP 127.0.0.1:<port> (LISTEN)`.
- The socket is listening *before* the ready line is printed (`Server.start` precedes the
  `Presentation.Plain.result` call), so a client that reacts to the line can always connect.
- `GET` and `HEAD` return identical status and headers (`Connection: close`,
  `Content-type: application/json; charset=utf-8`, `Content-length: 838`); HEAD sends
  **0 body bytes**. My concern that `sendResponseHeaders(200, -1)` might override the explicit
  HEAD `Content-Length` is disproved by measurement.
- `/repository.json?x=1` → 404; `POST /repository.json` → 405 with exactly `Allow: GET, HEAD`;
  `POST /other` → 404 (path before method, matching §9's own bullet order); `TRACE` → 405;
  lowercase `get` → 405 (methods correctly case-sensitive); `/Repository.json`,
  `/repository.json/`, `/../repository.json` and absolute-form all → 404 with no normalization
  or traversal.
- Signals: SIGINT → exit 0, SIGTERM → exit 0, and **SIGINT delivered immediately at the ready
  line** → exit 0. The handlers-before-ready-line ordering (`CommandsServe.scala:54` before
  `:59`) is load-bearing and correct; the race it was written for does not reproduce.
- Bare `--serve` (R90's default port, which the provided suite never exercises) binds 8765,
  serves a parseable snapshot, and exits 0 on SIGTERM.
- Invalid repository at startup → exit 1, empty stdout, no URL, no port bound.
- The `Connection: close` policy is correct and necessary as documented, with the two
  exceptions in findings 4 and 5.

**Cross-repo safety (R38, R47, R86, R103, §3.5, D11)**

Fourteen combinations run against a local repository, each comparing a full SHA-1 snapshot of
every file (working tree + `.snap/`) before and after. **Zero mutations** outside the one
intended success case:

| Case | exit | local state | first diagnostic |
|---|---|---|---|
| merge malformed-JSON remote | 1 | UNCHANGED | `invalid JSON at line 1 column 3` |
| merge nonexistent remote | 1 | UNCHANGED | `cannot read repository: …` |
| merge collision remote | 1 | UNCHANGED | `patch collision: alice@example.com revision 1` |
| `diff --repo` malformed remote | 1 | UNCHANGED | `invalid JSON …` |
| `diff --repo` collision remote | 1 | UNCHANGED | `patch collision: …` |
| `diff --repo` good remote | 0 | UNCHANGED | (diff output; read-only, as required) |
| dirty + malformed remote | 1 | UNCHANGED | `working tree is dirty` |
| dirty + collision remote | 1 | UNCHANGED | `working tree is dirty` |
| dirty + good remote | 1 | UNCHANGED | `working tree is dirty` |
| dirty + nonexistent remote | 1 | UNCHANGED | `working tree is dirty` |
| symlink + malformed remote | 1 | UNCHANGED | `unsupported working tree entry: link` |
| symlink + good remote | 1 | UNCHANGED | `unsupported working tree entry: link` |
| symlink, `diff --repo` (no scan) | 0 | UNCHANGED | (diff output — correct: §7.6's cross-repo form does not scan the working tree) |
| corrupt local + good remote | 1 | UNCHANGED | `invalid JSON …` |

D11's precedence holds in every combination: local parse+validate → unsupported entry → dirty →
remote load+validate → dot cross-check → replay → write. The dirty and unsupported-entry
outcomes beat the remote failure, and the local-repository failure beats everything, exactly as
`CommandsMerge.scala:56-79` composes them. No path mutates local state before a remote failure
is known — structurally guaranteed, since every step is a link in one `for`-comprehension whose
first write (`Materialize.install`) is the second-to-last link.

`CommandsMerge.unionPatches` (`:115-133`) compares only dots present in both sides, structurally:
`Patch` is a case class over `Vector[Change]`, and `Change.Put` overrides `equals`/`hashCode` to
compare bytes (`Patch.scala:28-34`) rather than array references — so test 26's "duplicate repos
merge as a no-op" premise holds for binary content too, which the default case-class equality
would have broken silently. The leftmost collision in dot order decides, making the reported
error direction-independent.

R78's cwd-relative resolution verified from a subdirectory: `cd repo/sub && snap merge ../../remote`
resolves against the process working directory (not the discovered repository root) and installs
into the repository root.

**CLI surface (R79, R92–R97, R103–R108)**

- 25 grammar/exit-channel cases: every unknown option, extra operand, duplicate option,
  misplaced option and missing option value is exit 1 with `snap: invalid command or arguments`,
  except `diff`, which correctly uses its distinct channel
  (`snap: usage: snap diff <old> <new> [--repo <repository>]`). `--serve 65536` / `08` / `-1` →
  `invalid port: <arg>`. Nothing confuses exit 1 with exit 2; `--version` is the only exit 0.
- Grammar runs before repository discovery: `snap status x` outside any repository reports the
  grammar error, not `not a Snap repository` (phase-1 CR7 still holds).
- `SNAP_COLOR` validation precedes everything, including `--version` and repository discovery
  (`SNAP_COLOR=zzz snap status` outside a repository → the `SNAP_COLOR` error), and the error is
  itself plain, per R95.
- `auto`/`always`/`never`/unset/`AUTO`/`Always`/empty all behave per R93–R95; `NO_COLOR` (even
  empty) forces plain in `auto` and is overridden by `always`.
- **R92 verified by effect comparison, not by reading:** the same `merge` run under plain,
  `never`, `always`, and `auto`+`NO_COLOR` produced identical exit codes, an identical
  `repository.json` SHA-1 (`1eaa748394eb8939`), an identical merged file SHA-1
  (`468df36d08e878d7`), and the same single warning in the same position. Only the stream bytes
  differ.
- **R108 verified against a real PTY** (`script -q /dev/null`), which is exactly what the shared
  harness cannot do: stdout on a TTY with `SNAP_COLOR` unset → terminal mode; `NO_COLOR` → plain;
  `never` → plain; stdout redirected inside a PTY session → plain. Per-stream independence
  confirmed in both directions — stdout=PTY/stderr=file gave a styled result line with a plain
  `warning:` line, and stdout=file/stderr=PTY gave the exact inverse.
- `Cli.emit` is the only place any result, warning or error reaches a stream. Exhaustive grep
  for `print`/`println`/`System.out`/`System.err`/`Console.` across `src/main/scala` returns
  exactly: `Presentation.Plain` (3 sites), `Presentation.Terminal` (3 sites), `Env.real()`'s
  stream construction, and `Main.scala:31`. `CommandsServe.scala:59` is the one documented R96
  exemption and reaches `Presentation.Plain` directly rather than through `Presenters` — verified
  live: the ready line is plain even under `SNAP_COLOR=always`. `Main.scala:31` is the second,
  undocumented one (finding 8).
- `Presentation.Terminal.renderDiff`'s prefix precedence (`Presentation.scala:191-198`) follows
  §7.11's literal byte-based rule, including the case where a deleted token's own text makes the
  line start with `--- ` — the spec defines the styling as a prefix match on the plain line, so
  bold there is correct, not a bug.
- `Cli.discoverRepo` walks with `NOFOLLOW_LINKS`; a symlinked `.snap` is walked past
  (`not a Snap repository`), per D25.

**R107 exit-channel robustness:** unreadable `repository.json`, a directory where the file
should be, 200 000-deep nested JSON arrays and 100 000-deep unterminated objects all produce a
clean exit 1 with a one-line `snap: <detail>` — no stack traces, no exit 2, no
`StackOverflowError`.

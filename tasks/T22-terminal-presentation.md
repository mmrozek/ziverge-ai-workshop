# T22 — Terminal renderer, SNAP_COLOR/NO_COLOR, TTY (3 SP)

- **Phase:** 5 — Terminal presentation & holdout hardening
- **Depends on:** T13, T17, T19
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
The complete terminal presentation (R92–R97, R108; DESIGN §8, D20, gotcha 8): per-stream
renderer selection (`SNAP_COLOR` unset/`auto`/`always`/`never` × `NO_COLOR` × TTY-ness),
exact ANSI layouts for init/commit/revert/merge success lines, status (U+2212 for
deleted, double space in header), log (blank line between entries), diff line styling
(incl. dim `\ ` and yellow `Binary files `), `--version`, warning `⚠`/error `✗`;
`config` stays silent; the `--serve` URL stays plain; presentation provably changes no
execution/effects/exit codes (R92). Real TTY probe per D20 behind the `Tty` trait.

## Scope
`snap/scala/src/main/scala/snap/cli/Presentation.scala` (+ `Env` TTY impl), tests in
`snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [x] Provided test `28-terminal-presentation` passes (`--filter terminal`), byte-exact.
- [x] R108 unit tests: the full selection matrix — {unset, auto, always, never,
      invalid} × {NO_COLOR present (incl. empty), absent} × {stdout TTY, not} ×
      {stderr TTY, not} — against injected fake `Tty` values, asserting per-stream mode
      independently.
- [x] A same-command pair test (terminal vs plain) asserting identical exit code,
      identical repository/filesystem effects, and identical warning order (R92).
- [x] Empty plain output stays empty in terminal mode (no stray SGR/reset bytes); the
      probe subprocess is spawned only when mode is auto-and-NO_COLOR-absent (negative
      constraint — assert no probe under `NO_COLOR=1`).

## Notes / decisions
- **Call-site count revised from 2 to 3, then collapsed back to 2.** The task brief (and
  T17-review nit 3) named `Cli.emit` and `CommandsMerge` as the two known sites; T19 added
  a third, `CommandsServe`'s `--serve` ready line. All three are enumerated in the report
  below. Resolution: `CommandHandler`'s success type widened from a bare `String` to
  `CommandOutput(kind: ResultKind, text: String, warnings: Vector[String] = Vector.empty)`
  (`Cli.scala`) — every handler now RETURNS its warnings as data instead of printing them,
  so `CommandsMerge` no longer touches `Presentation` at all; it is no longer a
  presentation call site. `Cli.emit` became the ONLY generic seam (selects both streams
  once per invocation via `Presentation.select`, prints warnings then the result).
  `CommandsServe` remains the one deliberate, spec-mandated exception (R96): it still
  calls `Presentation.Plain.result` directly, bypassing `Presenters` entirely, so the
  ready line is plain in every mode. Net result: 2 real call sites, one of them
  intentionally exempt — recorded here since DESIGN's locked-decision table doesn't cover
  this (implementation-detail level, not a locked decision).
- **`ResultKind` carries handler-supplied structure, not a `Command`→kind lookup table in
  `Cli.run`, and Terminal never reparses `text` except for `diff`.** Each handler tags its
  own `CommandOutput` with the `ResultKind` it produces (`Success(label)`,
  `Status(version, deltas)`, `LogEntries(entries)`, `Diff`, `VersionLine`, `Raw`).
  `Status`/`LogEntries` carry the same `Delta`/`LogLine` values the handler already
  computed to build the plain string, in the same pass — so terminal rendering is a
  second view of that data, never a partial reparse of the plain bytes needing an
  "impossible" fallback branch. `diff` is the deliberate exception: SPEC §7.11 defines its
  terminal styling AS a transform of the plain bytes ("preserves every plain byte
  except..."), so `Presentation.Terminal.renderDiff` reparses `text` line-by-line by
  design, matching the spec's own framing.
- **Real `Tty` probe (D20) implemented in `Env.scala`** as `Tty.Real`: a child
  `/bin/sh -c "test -t N"` process with `Redirect.INHERIT` on exactly the fd under test
  (stdout→INHERIT+stderr→DISCARD, or vice versa) so the probe never echoes its own output
  onto our streams. `Env.real()` now uses `Tty.Real`; `Tty.Stub` remains for tests that
  don't care about TTY selection. Each probe call spawns fresh (no memoization), which is
  what keeps it lazy: `Presentation.select` only calls `env.tty.isStdoutTty`/`isStderrTty`
  from the `auto`/unset-and-`NO_COLOR`-absent branch, so `always`/`never`/`NO_COLOR`-present
  spawn zero subprocesses (verified by unit tests with a call-counting fake `Tty`, and
  confirmed empirically: the full provided suite sets `NO_COLOR=1` by default per
  SPEC-NOTES §2, so the probe subprocess never runs in 27 of the 28 provided tests; only
  test 28's explicit `SNAP_COLOR: null, NO_COLOR: null` step exercises it for real, and it
  correctly reports non-TTY for the harness's piped streams).
- **`SNAP_COLOR`-invalid-value error bypasses `Presenters` entirely** (`Cli.run`): R95's
  own wording — "this error itself is plain because no valid presentation was selected" —
  means `Presentation.select` is never even callable yet (it assumes a validated value),
  so this one error path still calls `Presentation.Plain.error` directly, exactly as the
  pre-T22 code did for every error. Verified by test 28's `SNAP_COLOR: sometimes` step
  (plain line, no ANSI) even though the suite's own top-level env is `SNAP_COLOR: always`.
- **Scala 3 lexer gotcha found while writing `sgr`:** a bare `$identifier` interpolation
  immediately followed by a literal ESC (0x1B) byte inside an `s"..."` string produces a
  bogus `Not found: text - did you mean text?` compile error (Scala 3.3.8) — the simple-
  interpolation grammar apparently misparses the boundary. Fix: use braced `${identifier}`
  interpolation whenever it directly precedes a raw control-character byte. Documented
  here since it isn't specific to this codebase and could bite a future edit to
  `Presentation.Terminal`'s renderers.
- **`Errors.scala` untouched.** No new `SnapError` case or `Messages` entry was needed —
  `InvalidSnapColor`/`Messages.invalidSnapColor` already existed from T08. The task's
  "confine changes to `// T22 additions` blocks" guidance therefore didn't apply; noted so
  the absence isn't mistaken for an oversight.
- **Left alone, noticed in passing:** `snap/scala/src/main/scala/snap/http/Server.scala`'s
  doc comment on `blockForever` (lines ~106–108) still says `CommandHandler`'s
  `Either[SnapError, String]` result type; it's now `Either[SnapError, CommandOutput]`.
  Cosmetic (prose only, the code itself still type-checks since `Nothing` conforms to any
  type), and `snap/http/` is explicitly off-limits for this task (T20 is concurrently
  active there) — left for a future touch of that file rather than edited here.
- **`Main.scala`'s exit-2 catch-all stays plain, unstyled, out of scope.** It bypasses
  `Presentation` entirely today (`env.stderr.print(s"snap: ...")` directly) and the task's
  brief enumerates exactly three call sites to examine (`Cli.emit`, `CommandsMerge`,
  `CommandsServe`), none of which is `Main.scala`; its file is also outside this task's
  declared `Scope:` line. SPEC §10's grouping of "errors" under §7.11 doesn't explicitly
  carve out the exit-2 path, so this is a documented, deliberate scope boundary rather
  than a spec reading — flagging it as a candidate holdout-audit item rather than fixing
  it here.

## Pre-implementation pointers
- From `reviews/T17-review.md` nit 3 (deferred here at triage): there are **two**
  `Presentation` call sites, not one. `Cli.emit` is the usual seam, but
  `snap/cli/CommandsMerge.scala:80` reaches `Presentation.Plain` directly to print the
  `warning: auto-resolved …` lines. No behavioral difference today — every site is
  hardcoded to `Plain` until this task introduces selection — but T22 must update both
  and decide how a command handler (whose return type currently carries only one stdout
  string) obtains the correctly selected **stderr** presentation for warnings. Routing
  merge's warnings back through `Cli.emit` is the obvious direction; whichever way it
  goes, R92 requires the warning order to be identical in plain and terminal mode.
- T19 adds a **third** presentation call site: `CommandsServe` prints the `--serve` ready
  line through `Presentation.Plain` directly. Per this task's own scope line that URL
  **stays plain** in every mode, so the correct outcome is a deliberate plain rendering,
  not an oversight to be "fixed" by routing it through the selected renderer. Enumerate
  all three sites (`Cli.emit`, `CommandsMerge`, `CommandsServe`) before restructuring, and
  state in the notes which of them is intentionally exempt.

## T20-integration verification (post-staging, no code changes needed)
Verified T22's staged diff against `main`'s T20 (HTTP client/remote operands) after both
orchestrator-resolved conflicts in `CommandsDiff.scala`/`CommandsMerge.scala`. Both
resolutions are correct as staged: `CommandsDiff`'s `--repo` branch (T20) is intact —
parses both version operands first, loads local then remote via
`Commands.loadRemoteRepository`, runs `CommandsMerge.unionPatches` purely for its
cross-repository dot cross-check, discards the union, and both `yield` sites in that file
now wrap their result in `CommandOutput(ResultKind.Diff, ...)` like every other handler.
`CommandsMerge`'s merged doc comment covers both T20's remote-operand resolution and
T22's warnings-as-data framing; the code returns
`CommandOutput(ResultKind.Success("Merged"), merged.frontier.canonicalText + "\n", warnings)`
and makes no `Presentation` call at all.

Grepped every `main`-scala source for `Presentation\.` outside `Cli.scala`: the only
code-level hit is `CommandsServe.scala:59` (`Presentation.Plain.result(...)`, the
documented R96 exception for the `--serve` ready line); the remaining hits (`Env.scala`,
`Presentation.scala` itself) are scaladoc `[[...]]` cross-references, not calls. Also
checked every other T20-touched path for a stray direct-print: `snap/http/Client.scala`
(`Client.fetchRepository`/`get`) returns only typed `Either[SnapError, ...]`, never
touches a stream; `Commands.loadRemoteRepository` (`Commands.scala:52-54`) likewise
returns `Either[SnapError, Repo.Valid]`. Grepped all of `snap/scala/src/main/scala` for
`.print`/`println`/`env.stdout`/`env.stderr`: the only hit outside
`Cli.scala`/`CommandsServe.scala`/`Env.scala`/`Presentation.scala` is `Main.scala`'s
pre-existing exit-2 catch-all (`env.stderr.print` for `snap: internal error: ...`),
already noted above as an out-of-scope, always-plain path untouched by T22.

Gates run in order from a clean state (`sbt -batch clean assembly` first, per the jar-
staleness workflow trap): `sbt -batch test` → 669 total (651 pre-T22 + 18
`PresentationSuite`), 0 failed; `sbt -batch scalafmtCheckAll` and
`sbt -batch "scalafixAll --check"` both clean; full provided suite
(`PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala`) —
**28/28 passed**, test 28 (`terminal presentation is colorful readable and explicitly
controllable`) green for the first time alongside all 27 previously-passing cases, byte-
exact (no `status`/`log`/`diff` styling regression). No code edits were needed — the
orchestrator's two conflict resolutions were already correct.

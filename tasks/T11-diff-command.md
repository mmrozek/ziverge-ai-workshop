# T11 — `diff` command & rendering (3 SP)

- **Phase:** 2 — Diff, revert & validation matrices
- **Depends on:** T10
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap diff` in its local forms (R86 without `--repo`'s remote resolution — grammar
accepts it, remote support lands in T20/T21): no-args current-vs-working;
`diff <old> <new>` between locally known versions (known-version check via T07,
`snap: unknown version: <v>` otherwise). Rendering per R87/D8: path-sorted blocks,
whole-file unified headers with `/dev/null` for absent sides,
`@@ -1,<old> +1,<new> @@`, §5 script ops, `\ No newline at end of file`, empty-text-file
block `@@ -1,0 +1,0 @@` with no ops (test 06 pin), binary line when any present side is
non-text (D8), empty stdout + exit 0 when equal. DESIGN §8.

## Scope
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (diff), a `DiffRender` module,
tests in `snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [x] Provided tests `05-diff-goldens` and `06-binary-and-empty` pass
      (`--filter diff-goldens`, `--filter binary-and-empty`).
- [x] Unit goldens: missing-final-LF marker on both deleted and inserted sides; CRLF
      tokens render verbatim (`+a\r\n` — test 26's byte expectation, asserted early);
      binary↔text transition renders the binary line (D8 — untested by the suite).
- [x] `diff <old> <new>` validates the repository and both versions before any output
      (R86); unknown but canonical vector → exact `snap: unknown version: (…)`.
- [x] Rendering is a pure function of the two trees (no env/time access); output for
      equal trees is zero bytes.

## Notes / decisions

- **`DiffRender` reuses `WorkingChanges.compute`** (T10, `snap/scala/.../cli/WorkingChanges.scala`)
  for the sorted-merge walk over the two trees: it already yields path-sorted, non-equal
  `(path, before, after)` deltas in `Utf8Order`, which is exactly R87's "changed paths sort by
  path" plus the equal-trees-produce-nothing rule. No separate union/sort/filter was written.
- **`invalid version: <arg>` echoes the raw operand text**, not the specific `VersionError`/id/
  revision reason. Read: `Version.parse`'s own doc comment says the CLI layer owns the
  `snap: invalid version: <arg>` wording, and D9's sibling case (`invalid port: <arg>`) is
  literally the raw offending text. Neither test 19 (`stderr_contains: invalid version`) nor test
  25 (`stderr_matches: '^snap: invalid version: .+\n$'`) pins a specific reason — only the class.
  New catalog entries: `SnapError.InvalidVersionArgument(raw)` / `Messages.invalidVersionArgument`
  (`Errors.scala`, `// T11 additions` blocks at the enum-case, `message`-match, and `Messages`
  end-of-file locations, following the existing T07/T09/T10 convention of chronological blocks
  rather than a single literal end-of-file location — that convention makes `Messages`'s block the
  new physical end of file, which is what "end-of-file block" describes).
- **New `SnapError.DiffUsage`** for the distinct usage channel (DESIGN §8; tests 14/24): message
  `usage: snap diff <old> <new> [--repo <repository>]`, matching SPEC §7.6's fenced grammar block
  verbatim.
- **Grammar dispatch** in `CommandsDiff.handler` is a 4-way match: `Nil` (no-arg), exactly two
  operands (local `<old> <new>`), exactly four operands with the third literally `"--repo"`
  (grammar-valid remote form — returns `NotImplemented` per this task's scope; T20/T21 wire actual
  remote resolution), else `DiffUsage`. Verified against every provided grammar case in tests
  14/24/26 (test 26's own `--repo` *behavior* is out of scope here, as expected — only its
  grammar shape was checked).
- **Validation order** for `<old> <new>`: parse both version operands first (pure syntax, R31),
  then load+validate the repository, then `Replay.materialize` old-then-new (R45's known-version
  check runs inside `materialize`). No provided test combines a bad-version-syntax operand with a
  broken repository, so this order is a minor, non-core ambiguity — recorded per the task's
  ambiguity policy rather than escalated.
- **Full-suite count went to 11/28, not the anticipated 10/28.** Test
  `25-config-version-path-boundaries.yaml` also exercises `diff <old> <new>`'s invalid-version
  handling extensively (5 bad-version diff invocations) and was failing pre-T11 (`snap: not
  implemented` in place of `snap: invalid version: …`) — confirmed by stashing this task's changes
  and re-running `--filter 25-config` (fails) vs. unstashed (passes). This is an in-scope
  consequence of implementing R31 for `diff`, not scope creep; no code outside `diff`'s own files
  was touched to make it pass.
- Also updated `Cli.scala`'s `defaultCommands` doc comment (one sentence) to stop saying `Diff`'s
  rendering is stubbed, since it no longer is.
- **Post-integration fix (phase-1 review finding PR1/CR3, applied during T11's cherry-pick onto
  `main`):** `Messages.invalidVersionArgument(raw)` interpolated the raw `diff <old> <new>` operand
  (untrusted argv text, read before `Version.parse` succeeds) directly into the diagnostic, so a
  hostile operand containing a control character or newline could break the one-line `snap: <detail>`
  contract. Fixed by routing it through the existing `sanitizeControlChars` helper, matching the
  `duplicateJsonKey`/`unsupportedWorkTreeEntry` pattern (`Errors.scala`: `Messages.invalidVersionArgument`
  and its scaladoc). Audited the rest of T11's files (`DiffRender.scala`, `CommandsDiff.scala`) for
  the same gap: `DiffRender.scala` interpolates `SnapPath.value` into diff *content* (headers, binary
  line) rather than a `snap: <detail>` diagnostic — those paths are already validated (`SnapPath`
  instances only ever come from a validated `Tree`, never raw filesystem/argv text) and the rendered
  diff is intentionally multi-line file content, not the one-line error channel — so no further
  sanitization applies there. No provided test pins a control-character-bearing operand, so this
  changes no test-asserted string (`sanitizeControlChars` is identity on text without control
  characters).

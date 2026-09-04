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
- [ ] Provided tests `05-diff-goldens` and `06-binary-and-empty` pass
      (`--filter diff-goldens`, `--filter binary-and-empty`).
- [ ] Unit goldens: missing-final-LF marker on both deleted and inserted sides; CRLF
      tokens render verbatim (`+a\r\n` — test 26's byte expectation, asserted early);
      binary↔text transition renders the binary line (D8 — untested by the suite).
- [ ] `diff <old> <new>` validates the repository and both versions before any output
      (R86); unknown but canonical vector → exact `snap: unknown version: (…)`.
- [ ] Rendering is a pure function of the two trees (no env/time access); output for
      equal trees is zero bytes.

## Notes / decisions

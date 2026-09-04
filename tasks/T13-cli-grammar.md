# T13 — CLI grammar matrix & port validation (2 SP)

- **Phase:** 2 — Diff, revert & validation matrices
- **Depends on:** T12 (parallel-safe with T14 — disjoint files)
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Exhaustive argument grammar per command (R79): exact positions, at-most-once options,
unknown options never treated as paths, extra operands rejected — every violation →
`snap: invalid command or arguments`, **except** `diff` grammar errors → the distinct
`snap: usage: snap diff …` channel (exact wording matched to tests 14/24's regex).
`--serve` port validation per D9 (`snap: invalid port: <arg>` — the server itself is
T19; grammar and port parsing land now). DESIGN §8.

## Scope
`snap/scala/src/main/scala/snap/cli/Cli.scala`, tests in
`snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [ ] Provided tests `14-cli-errors` and `24-cli-grammar-matrix` pass
      (`--filter cli-errors`, `--filter cli-grammar`).
- [ ] Table-driven unit test covering every command × {extra operand, unknown option,
      duplicate option, misplaced option, missing option value}.
- [ ] Port parsing: canonical decimal 0–65535 accepted; `65536`, `-1`, `08`, `abc`,
      empty → `snap: invalid port: <arg>` (D9), exit 1, no server startup attempt.
- [ ] Grammar evaluation happens before repository discovery/IO for every command
      (negative constraint: a grammar error in a non-repo directory still prints the
      grammar error, not `not a Snap repository`).

## Notes / decisions

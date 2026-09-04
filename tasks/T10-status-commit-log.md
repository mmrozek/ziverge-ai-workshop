# T10 — Worktree scanner, `status`, `commit`, `log` (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T07, T09
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/fs/WorkTree.scala`: scan the repository root into a `Tree` — regular files only,
root `.snap/` excluded (R16–R21), any unsupported entry fails with
`snap: unsupported working tree entry: <path>` before any other outcome (R104);
clean/dirty determination (R26–R27). Commands: `status` (R83), `log` (R84 — reverse
canonical integration order, TSV, escape order `\\`→`\t`→`\n`), `commit` (R85: config
required, dirty required, ≤4096-byte message per D16, tree diff → changes with
text-vs-put selection, one patch on the current frontier, atomic metadata replace,
prints new version; clean tree / invalid message / overflow / dot collision errors).
DESIGN §7, §8; gotcha 7 (é/😀 filenames — verify jnu encoding on this JVM).

## Scope
`snap/scala/src/main/scala/snap/fs/WorkTree.scala`,
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (status/commit/log), tests in
`snap/scala/src/test/scala/snap/{fs,cli}/`.

## Acceptance criteria
- [ ] Provided tests `04-commit-status-log` and `08-unsupported-entries` pass; phase-1
      gate: full suite shows 01,02,03,04,08 green.
- [ ] Commit change-kind selection unit tests: new text + old absent-or-text → `text`
      edit (via T05 diff); new non-text or old non-text → `put` (canonical base64);
      removed → `delete`; changes sorted by path, one per path (R49).
- [ ] Scanner unit tests: symlink and FIFO each fail with the exact pinned message and
      leave `repository.json` untouched; empty directories and `.snap/untracked`
      invisible (test 25's premise); paths sorted by `Utf8Order` (é/😀 round-trip —
      gotcha 7 verified in a test that writes and rescans such filenames).
- [ ] `status`/`log`/`diff`-adjacent output is byte-exact per plain mode: `version <v>`
      first, `A`/`M`/`D` rows sorted; log escaping order test with a message containing
      backslash+tab+LF together.

## Notes / decisions

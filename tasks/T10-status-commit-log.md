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
- [x] Provided tests `04-commit-status-log` and `08-unsupported-entries` pass; phase-1
      gate: full suite shows 01,02,03,04,08 green.
      (Verified 2026-09-04: filters 03/04/08 each pass; full suite 8 passed —
      01,02,03,04,08 plus 15,23,27 already green from the T06/T07 validation core.)
- [x] Commit change-kind selection unit tests: new text + old absent-or-text → `text`
      edit (via T05 diff); new non-text or old non-text → `put` (canonical base64);
      removed → `delete`; changes sorted by path, one per path (R49).
      (`CommandsCommitSuite` change-kind matrix; sortedness comes from the merge walk
      and is re-checked by `Patch.make`.)
- [x] Scanner unit tests: symlink and FIFO each fail with the exact pinned message and
      leave `repository.json` untouched; empty directories and `.snap/untracked`
      invisible (test 25's premise); paths sorted by `Utf8Order` (é/😀 round-trip —
      gotcha 7 verified in a test that writes and rescans such filenames).
      (`WorkTreeSuite`; no-mutation asserted at the commit-handler level in
      `CommandsCommitSuite`. Gotcha 7 verified on this machine's JVM: é/😀 filenames
      round-trip through `Files.write` + directory listing and sort
      `nested/file < z < é < 😀`.)
- [x] `status`/`log`/`diff`-adjacent output is byte-exact per plain mode: `version <v>`
      first, `A`/`M`/`D` rows sorted; log escaping order test with a message containing
      backslash+tab+LF together.
      (`CommandsStatusSuite` / `CommandsLogSuite`; escape-order test uses
      `x\y<TAB>z<LF>w` and asserts `x\\y\tz\nw` plus a no-raw-control property.)

## Notes / decisions
- **Check order in `commit`** (spec lists requirements unordered): repository
  load+validate → contributor id (R100) → message rules → working-tree scan → dirty
  check → revision/collision → validate-then-write. Pinned constraints honored: message
  beats clean-tree (test 25 runs `commit ""` on a CLEAN tree and expects
  `invalid commit message`), config errors surface before scan outcomes (test 25's
  duplicate-key case), D11's repo-before-scan precedence. Config-vs-message relative
  order is untested; contributor id first, matching §7.5's bullet order.
- **`invalid commit message` covers the whole input-rule class** (empty, R48 character
  violations, >4096 bytes): only the empty case is pinned (test 25); one wording keeps
  the class coherent. Implemented by reusing `Patch.checkMessage` — made public in
  `snap/core/Patch.scala` (outside the declared scope; one-line visibility change so
  R48's character rules keep a single canonical implementation) — plus the D16 byte
  check, remapped to `SnapError.InvalidCommitMessage`.
- **Commit revision overflow** (frontier(author) = 2^53−1) reports the existing
  `revision must be a positive safe integer` catalog line via `Revision.check`
  (untested wording; R30 is the violated rule). Unreachable through a readable
  repository (contiguity would require 2^53−1 patches on disk), so it is unit-tested
  on the pure `nextRevision` helper directly.
- **Dot collision on commit** is provably unreachable after §4.5 validation (patches
  are exactly the frontier's closure), but §7.5 names it, so it is checked defensively,
  reusing the pinned `patch collision: <dot>` shape (D5).
- **Defensive re-validation before write**: `commit` runs `Repo.validateFully` on the
  would-be repository before the atomic replace — the repository file can never receive
  a value that would not read back valid (R103 spirit). Cost: one extra replay per
  commit; correctness first (D19 spirit).
- **Scanner precedence**: the walk classifies entries depth-first with children in
  `Utf8Order` of their names, fails fast on the first unsupported entry, and only then
  validates paths and reads bytes — so an unsupported entry anywhere beats an invalid
  path anywhere (R104 "before any other outcome"). Which of several coexisting errors
  is reported is untested but deterministic (pure function of filesystem state).
- **Invalid working-tree path** (regular file whose name violates R23, e.g. a
  backslash): unspecified outcome; error `invalid working tree path: <path>` (new
  untested catalog entry) — silently skipping would violate the "tracks every regular
  file" contract.
- **Root `.snap` is excluded whatever its kind** (file/symlink/dir — invariant 8:
  metadata is never part of the tracked tree); nested `sub/.snap` is tracked (D13).
- **`log` does not scan the working tree** (§10 applies to commands that scan; log
  needs only the repository) — asserted by a unit test committing a symlink and
  expecting `log` to succeed.
- **Scope additions** (recorded per procedure):
  - `snap/core/Errors.scala` — five T10 cases + messages, appended in a
    `// T10 additions` block at the END of `SnapError`/`Messages` (T15-parallel
    convention).
  - `snap/core/Patch.scala` — `checkMessage` made public (see above).
  - `snap/cli/CommandsDiff.scala` — NOT in the declared scope, but test 08's `diff`
    step requires the no-arg form's failure-precedence prefix (repo load → scan) in
    this task; rendering stays `NotImplemented` for T11 to replace.
  - `snap/cli/Commands.scala` (shared root/read/arity plumbing) and
    `snap/cli/WorkingChanges.scala` (pure current-vs-working delta walk shared by
    status and commit) — new `cli` helpers within the task's spirit; file names match
    the declared `Commands*.scala` glob.
  - `MainSuite`/`CliSuite` stub-era tests updated: the exit-1 mapping test now injects
    a failing handler instead of relying on `status` being a stub; the
    "unimplemented commands" list shrank to `revert`/`merge`/`--serve`.
- **Commit operand grammar kept coarse** (exactly one operand, any shape, accepted as
  the message — a `--`-prefixed single operand is currently a message): T13 owns the
  exhaustive R79 matrix and test 24's `--unknown` cases.
- **Environment note for verification**: the harness inherits only `PATH` (drops
  `JAVA_HOME`), so `./snap/verify` must see a JDK without startup stderr noise on
  `PATH`; the machine's default JDK 24 prints a `sun.misc.Unsafe` deprecation warning
  that breaks every `stderr_equals ""` assertion. Verified with sdkman Java 17
  (`PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala`).
  No code change involved — worth a CLAUDE.md note later so nobody chases phantom
  failures.

# T12 — Filesystem install & `revert` (2 SP)

- **Phase:** 2 — Diff, revert & validation matrices
- **Depends on:** T11
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/fs/Materialize.scala`: install a target tree over the working directory per R70 +
§10 mutation order (R103, R105–R106): full target computed first, working files updated
(remove blockers, create dirs, write files, prune empty dirs), `repository.json`
replaced atomically only afterwards. `snap revert <version>` (R88): config + clean tree
+ known target required; authors one patch `revert to <version>` diffing current →
target (message exempt from the 4096 limit — D16); prints the **new** version; equal
trees → exact `snap: target tree is already current`. DESIGN §7.

## Scope
`snap/scala/src/main/scala/snap/fs/Materialize.scala`,
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (revert), tests in
`snap/scala/src/test/scala/snap/{fs,cli}/`.

## Acceptance criteria
- [x] Provided test `07-revert` passes (`--filter revert`), including file→directory
      and directory→file transitions.
- [x] Mutation-order unit test: on a simulated working-file write failure, the old
      `repository.json` is intact (metadata replace never precedes tree update — R106).
- [x] Dirty tree → exact `snap: working tree is dirty`; missing config → exact R100
      message; both checked before any filesystem change (R103).
- [x] Revert never removes patches and never moves the frontier backward — unit test
      asserts patch count grows by one and the new frontier strictly dominates.

## Notes / decisions

- **Check order (ambiguity, non-core — SPEC §7.7 vs §10/R103 vs test evidence).** §7.7 lists
  revert's preconditions as "contributor configuration, a clean working tree, and a locally
  known target version" (that literal order); §10/R103 separately groups "parsing, repository
  validation, replay, dirty-tree checks, and target-tree construction" before any write. The two
  disagree on whether "clean tree"/"contributor id" or "known target" (replay) comes first.
  Resolved by test evidence per the ambiguity policy (tests win, SPEC-NOTES §5 conflict-
  resolution habit): test 14 (`14-cli-errors.yaml`) reverts to an unrecognized version with NO
  contributor configured at all and asserts `unknown version` wins, not the R100 message — so the
  target-known/replay check must run before the contributor-id requirement. Implemented order in
  `CommandsRevert.handler`: repository load+validate → parse version operand → target
  known/materialize (R45, `Replay.materialize`) → contributor id (R100) → working-tree scan
  (R104) → dirty requirement (R27) → equal-trees short-circuit (R88) → diff current→target → one
  patch → defensive full validation → install → atomic write. Everything through the defensive
  validation step is read-only, satisfying R103's "before any write" grouping regardless of the
  finer-grained order within it.
- **Invalid-version-syntax rendering for `revert`'s operand (non-core).** No provided test
  exercises a syntactically invalid version literal for `revert` specifically (only `diff`, owned
  by T11, is pinned to the `snap: invalid version: <detail>` wrapper — test 25). Since T11's own
  error type for that wrapper isn't visible in this worktree (T11 not yet merged) and inventing a
  competing one risks diverging from T13's eventual exhaustive grammar matrix, `revert` surfaces
  `Version.parse` failures as-is through the existing `SnapError.InvalidVersionValue`/
  `RevisionNotSafeInteger` cases (no extra wrapper). Recorded for T13's attention if it wants one
  consistent wrapper across every version-taking command.
- **`Materialize.install`'s "remove blockers" step also removes blocking directories, not only
  blocking files (non-core reading of R70).** R70's text ("removes files blocking required
  directories") names only the file→directory direction; the directory→file direction (test 07's
  second transition) needs the reverse. Implemented as one mechanism: every path present in
  `current` but absent from `target` is deleted first (this alone clears a file blocking a needed
  directory), then a single empty-directory sweep runs before any directory is created or file
  written — by construction (both trees are prefix-free, R25) a directory now blocking a target
  file has already had every tracked descendant deleted in the first step, so it is empty and the
  sweep removes it. A second sweep runs after writing, matching R70's literal 4-step wording for
  parity/audit even though it is a no-op in practice (writing bytes never empties a directory).
- **`Materialize.install` only rewrites paths whose content actually changed** (`target` bytes
  differ from `current`'s, or the path is new), not every target path unconditionally — fewer
  filesystem writes, and it keeps the install's failure surface limited to paths that truly need
  touching. `Tree` equality/byte-comparison already exists elsewhere in the codebase (`Tree`,
  `Delta`, `Change.Put`) — this mirrors that idiom rather than introducing a new one.
- **Scope deviation, instructed:** `Errors.scala` needed three insertion points, not literally one
  end-of-file block — `enum SnapError`'s cases must live inside the enum, so the new cases are
  inserted at the end of the case list (immediately before `def message`), the corresponding
  match arms are appended at the end of `def message`'s pattern match, and the new `Messages`
  catalog entries are appended at the true end of file. All three are delimited with matching
  `// T12 additions: ...` comments, mirroring the T07/T09/T10 precedent already in the file. Noted
  per the launching instructions since this isn't literally "a single end-of-file block."
- New `SnapError` cases (Errors.scala, T12 block): `WorkingTreeDirty` (test 07, R27),
  `TargetTreeAlreadyCurrent` (test 07, R88), `CannotUpdateWorkingTree(detail)` (untested wording,
  R105–R106 filesystem boundary, mirrors `CannotReadWorkTree`/`CannotWriteRepository`).
- `CommandsRevert` reuses `CommandsCommit`'s `private[cli]` helpers (`nextRevision`,
  `checkNoCollision`, `buildChanges`, `insertSorted`) and `WorkingChanges.compute` rather than
  duplicating them — "one canonical implementation per concept" (DESIGN §1). `buildChanges` is
  reused unchanged: it was already a pure `Vector[Delta] => Vector[Change]` function, and
  `WorkingChanges.compute(current, target)` is exactly the same current-vs-other-tree diff
  `status`/`commit` use against the working tree, just applied to two `Tree` values instead of a
  tree and a filesystem scan.
- `Materialize.scala` is written as the shared install primitive `install(root, current, target)`
  (not revert-specific) per DESIGN §7's naming and the plan's note that `merge` (T17) will reuse
  it — no revert-only assumptions are baked in.
- Unit tests added: `snap/scala/src/test/scala/snap/fs/MaterializeSuite.scala` (10 tests — basic
  install, deletion, unchanged-path no-op-content check, both file/directory transition
  directions, deep-deletion directory pruning, empty-tree revert, determinism under permuted
  `Tree` construction order, idempotence of re-installing an already-installed target, and the
  partial-mutation/typed-error shape of a write failure) and
  `snap/scala/src/test/scala/snap/cli/CommandsRevertSuite.scala` (10 tests — happy path incl.
  both transitions, generated log message, additive/frontier-strictly-dominates guarantee,
  already-current short-circuit incl. "mutates nothing", dirty-tree short-circuit incl. "mutates
  nothing", missing-contributor-id R100 message, the test-14 order regression guard, the
  mutation-order/write-failure guarantee via a POSIX-permission-based blocker that stays invisible
  to the working-tree scan, and coarse grammar arity). `CliSuite.scala`'s "known-but-unimplemented
  commands" test updated to drop `revert` from the stub list (T10's established pattern for each
  task that replaces a stub).
- Gates: `sbt -batch test` 469/469 project tests green; `sbt -batch scalafmtCheckAll` and
  `sbt -batch "scalafixAll --check"` both green (full clean run, 38 sources); provided suite
  `--filter revert` 1/1 green; full provided suite 9/28 green (baseline 8 — 01, 02, 03, 04, 08,
  15, 23, 27 — plus 07-revert), no regressions.
- **Fix (post-completion audit, finding 1 — Major, "silent data loss").** `Materialize.install`'s
  `pruneEmptyDirectories(root)` swept the *entire* tree under `root`, unconditionally, twice —
  deleting any untracked, pre-existing empty directory even on a no-op `merge`/`revert`, contrary
  to SPEC §6.2/R70's "removes **newly** empty directories" and §7.8's "changes nothing" for an
  already-contained merge. Replaced with `pruneEmptiedAncestors(root, removed)`: candidates are
  only the proper segment-prefix ancestors of paths this install actually deleted
  (`SnapPath.ancestors`), deduplicated and processed deepest-first (ties broken by
  `SnapPath.ordering`/`Utf8Order` for determinism, though ties can never be ancestor/descendant of
  one another so they can't affect the result) so a directory is only checked for emptiness after
  every nested candidate has already been resolved. `removed` empty (the common already-contained
  case) now short-circuits to zero filesystem access in this step. The directory-blocks-file
  direction of the file/directory transition (test 07's second shape) is still covered by the same
  mechanism, not a separate case: every tracked descendant such a blocking directory holds is, by
  prefix-freeness (R25), necessarily also in `removed`, so the directory is exactly an ancestor of
  one of those deletions. **Dropped the second (post-write) prune pass**: once pruning is scoped to
  `removed`'s ancestors, writing bytes to `written` paths can neither delete nor empty a directory,
  so a repeat pass after `writePaths` is a provable no-op, not a safety net — kept it out rather
  than leave dead code the reader has to re-verify is harmless. `MetadataDirName`/the `.snap` guard
  was removed too: no valid `SnapPath` can have `.snap` as an ancestor
  (`SnapPath.parse`'s `ReservedFirstSegment` forbids it as a *first* segment, and ancestors preserve
  the first segment), so the new candidate set can never reach `.snap` — the explicit guard was
  protecting against a case the old global sweep created for itself. Order preserved: delete →
  prune → create parents → write (pruning must still run before `ensureParents`/`writePaths`, since
  the directory-blocks-file case needs the blocker gone before the target path can be created as a
  file). New regression tests: `MaterializeSuite` (no-op install with a pre-existing nested
  untracked empty directory touches nothing; a directory this install *does* empty is still pruned
  while an unrelated untracked empty directory survives) and CLI-level end-to-end reproductions of
  the audit's own repro in `CommandsMergeSuite` (already-contained-history merge) and
  `CommandsRevertSuite` (a real, mutating revert), plus a namespace-winner
  directory→file merge test (`a/b` superseded by `a`) with an unrelated untracked directory
  alongside, to pin that the file/directory transition and the scoped pruning coexist correctly.
  Gates: `sbt -batch test` 698/698 (baseline 693 + 5 new); `scalafmtCheckAll`/`scalafixAll --check`
  green; full provided suite 28/28 (including test 07 `revert` and test 11
  `namespace-conflicts`); manually reproduced the audit's exact `mkdir -p myEmptyDir/nested docs`
  + `snap merge .` and a real `snap revert` scenario against the rebuilt jar — the untracked
  directories survive in both cases now.
- **Integration note (T11/T16 rebase onto `main`, orchestrator).** This task's worktree predated
  both T11 (`diff`, `snap/cli/DiffRender.scala`) and T16 (the concurrent replay engine). After
  cherry-picking onto `main`, `CommandsRevert.scala`'s one call site written against the OLD
  `Replay.materialize(structure, version, Replay.LinearOnly): Either[SnapError, Tree]` was updated
  to the new `Replay.materialize(structure, version): Either[SnapError, (Tree, SortedSet[Warning])]`
  (`Replay.LinearOnly` and `SnapError.ConcurrentHistoryUnsupported` no longer exist post-T16):
  `targetTree <- Replay.materialize(valid.structure, targetVersion).map(_._1)`, with an inline
  comment noting the discarded warning half is intentional — SPEC §7.7 gives `revert` no
  warning-reporting obligation of its own (only `merge` prints warnings, R75/T17). (A tuple
  pattern in the generator, `(targetTree, _) <- ...`, does not compile — `Either` has no
  `withFilter` — so `.map(_._1)` is used instead.) No other T12 file needed a change: `Errors.scala`
  carries T11's, T16's, and T12's additions as three cleanly delimited insertion points and compiles
  as-is; `Materialize.scala`/`MaterializeSuite.scala` never touched the replay API. Check order is
  unchanged (target-known/replay before contributor-id, per test 14 — see above). Post-integration
  gates: `sbt -batch test` 530/530 (510 on `main` + this task's 20); `scalafmtCheckAll` and
  `scalafixAll --check` both green with no reformatting needed; provided suite 13/28 (the 11 on
  `main` — 01, 02, 03, 04, 05, 06, 08, 15, 23, 25, 27 — plus 07-revert, plus a bonus 19
  (`version-boundaries`) that now passes courtesy of T16's replay engine; no regressions). All
  four acceptance criteria re-verified green after integration.

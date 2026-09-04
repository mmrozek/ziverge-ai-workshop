# T17 — `merge` command (3 SP)

- **Phase:** 3 — Merge & OT
- **Depends on:** T12, T16
- **Risk:** **core** (merge behavior — formal pre-commit review, saved as
  `reviews/T17-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap merge <repository>` for local-path operands (R5, R14, R38, R75–R76, R89; HTTP
operands land in T20): failure precedence per D11 (local validate → worktree scan →
remote load+validate → dot cross-check → replay → write); union patch sets with
structural dedupe per dot, different value → `patch collision: <id> revision <n>`;
join frontiers; **two replays** (pre-merge local + joined) and warning set-subtraction,
new warnings to stderr as `warning: auto-resolved <path>: <reason>` sorted, joined
version to stdout; install via T12's materializer; no patch created. No-op merge of
equal/contained history: succeeds, changes nothing, no warnings, prints unchanged
version. DESIGN §5 (step 4), §7, §8.

## Scope
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (merge), repository-operand
resolution (local part), tests in `snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [ ] Provided tests `09-merge-text`, `10-merge-conflicts`, `11-namespace-conflicts`,
      `17-concurrent-creates`, `21-version-algebra` pass (filters: `merge-text`,
      `merge-conflicts`, `namespace`, `concurrent-creates`, `version-algebra`).
- [ ] Direction independence asserted in a unit test: merging A→B and B→A yields
      byte-identical trees and identical joined frontiers (R76).
- [ ] Re-merge of the same repository: exit 0, unchanged version on stdout, empty
      stderr, `repository.json` byte-identical (no-op path).
- [ ] Warning subtraction unit test: a warning present in the pre-merge local replay is
      NOT re-printed by merge (R75) — construct a local history that already warns.
- [ ] Dirty tree → exact `snap: working tree is dirty` before the remote is even read
      (D11 order, observable via a nonexistent remote path + dirty tree).

## Notes / decisions

## Pre-implementation pointers (from the T12/T16 integrations)

The engine and the materializer are done — T17 is composition, not new semantics. Do
not re-derive merge behavior; consume these seams as they are.

- **Replay's API (T16).** `Replay.materialize(structure, version)` returns
  `Either[SnapError, (Tree, SortedSet[Warning])]`; `Repo.validateFully` returns
  `Repo.Valid(structure, tree, warnings)`. Both `Repo.Valid` and
  `Repo.StructurallyValid` have `private[core]` constructors — outside `snap.core` the
  only way to obtain a proof is `Repo.validate` / `Repo.validateFully`. Don't widen
  them; if a `snap.cli` test needs a defective history, drive it through the real
  producer.
- **The two replays are already available as values.** The pre-merge local set is the
  `warnings` of the local `Repo.Valid` you loaded during the validate step; the joined
  set is the `warnings` of the union repository's `validateFully`. R75's subtraction is
  set difference over `SortedSet[Warning]` — nothing to recompute.
- **Do not re-add sub-replay warnings.** `reviews/T16-review.md` ruling 5: warnings
  raised while materializing a patch's *base* are discarded by design — a base is
  materialized from its own closed patch set (R65), so such a warning is an artifact of
  the sub-context and folding it in would over-report. R75's subtraction consumes only
  the two `Repo.Valid.warnings` sets.
- **Warning ordering is not yours to define.** Reuse `Warning`'s given `Ordering`
  (path by `Utf8Order`, then reason). Never `String.compareTo`, never re-sort the
  rendered lines. `WarningReason.text` yields the spec's tokens
  (`delete-wins`, `later-create-wins`, `later-put-wins`, `namespace-wins`, `put-wins`);
  test 11 pins the stderr bytes exactly: `warning: auto-resolved <path>: <reason>\n`.
- **Namespace semantics are locked as D27** (`S` = paths the patch *creates*), confirmed
  by the user. Merge consumes the engine's result; it must not add its own path-conflict
  reasoning.
- **Filesystem mutation goes through T12's `Materialize.install(root, current, target)`
  only**, and `repository.json` is written strictly afterwards (R106) — the same order
  `revert` uses. Merge creates no patch.
- **Cost.** Replay is Θ(n²) in patch count (accepted trade-off, T23 owns any
  optimization). Merge already pays two full replays: do exactly two, and don't
  re-validate the same repository more than once per run.
- **Stack.** `Replay.loop` is `@tailrec` (phase-1 CR1 was a real overflow). Any list
  recursion you add over patches must be tail-recursive or a fold.
- **`Errors.scala` convention.** New cases go in three clearly delimited
  `// T17 additions` insertion points — enum case list, the `message` match, and the
  `Messages` catalog at EOF — since Scala `enum` cases can't be appended after the
  companion. Keep test-pinned fragments at the END of each message.

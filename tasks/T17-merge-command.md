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
- [x] Provided tests `09-merge-text`, `10-merge-conflicts`, `11-namespace-conflicts`,
      `17-concurrent-creates`, `21-version-algebra` pass (filters: `merge-text`,
      `merge-conflicts`, `namespace`, `concurrent-creates`, `version-algebra`).
- [x] Direction independence asserted in a unit test: merging A→B and B→A yields
      byte-identical trees and identical joined frontiers (R76).
- [x] Re-merge of the same repository: exit 0, unchanged version on stdout, empty
      stderr, `repository.json` byte-identical (no-op path).
- [x] Warning subtraction unit test: a warning present in the pre-merge local replay is
      NOT re-printed by merge (R75) — construct a local history that already warns.
- [x] Dirty tree → exact `snap: working tree is dirty` before the remote is even read
      (D11 order, observable via a nonexistent remote path + dirty tree).

## Notes / decisions

- **Composition as pinned.** `CommandsMerge` is one `for`-comprehension in D11 order:
  local `Commands.readRepository` (pre-merge replay = `Repo.Valid.warnings`) →
  `WorkTree.scan` → dirty check (`WorkingChanges.compute`, same predicate as
  status/commit/revert) → operand resolution → `Store.readRepository` on the remote →
  sorted-merge patch union with structural dedupe per dot (`patch collision: <dot>` on a
  difference, leftmost dot decides — deterministic and direction-independent) →
  `Repository(joinedFrontier, union)` → `Repo.validateFully` (the joined replay AND the
  revert-style defensive gate) → `Materialize.install` → `Store.writeRepository`
  (strictly after install, R106). Warnings print only after the write succeeds
  (§7.8 lists install/update before printing); a failed merge emits only its error.
- **Replay count.** Three materializations happen per run — local load, remote load,
  joined — each a different repository validated exactly once. The remote's replay is
  part of R89's "loads and validates the other repository" (Store.readRepository is the
  one read pipeline, §4.5 steps 1–6) and D11 places remote validation BEFORE the dot
  cross-check, which is observable when a remote is both internally defective and
  colliding. R75's subtraction consumes exactly the two `Repo.Valid.warnings` values
  (pre-merge local, joined union); nothing is recomputed.
- **No-op path has no special case.** For equal/contained history the union collapses to
  the local patch vector, the join to the local frontier, `install` to a no-op, and the
  metadata write to byte-identical canonical bytes (D7) — asserted byte-for-byte in the
  suite. Chosen over an explicit skip: fewer branches, trivially deterministic, and
  "changes nothing" holds observably (R89).
- **HTTP operands (R78) resolve at the remote-load step**: `http://`/`https://` prefixes
  return `NotImplemented` (T20's seam, mirroring `CommandsDiff --repo`) — checked at
  D11's remote position so a dirty tree already wins over a URL operand today, exactly
  as it must once T20 lands (unit-pinned). Local operands resolve against `env.cwd`
  (never the discovered root), unit-pinned with a `../b` operand.
- **Scope addition — `Presentation.warning`** (file outside the declared scope): the
  warning line is presentation-rendered per §7.11 ("a plain warning `warning: <detail>`
  becomes ⚠ …"), so the `warning: ` prefix lives next to `error`'s `snap: ` prefix in
  the `Presentation` trait + `Plain`, ready for T22's per-stream Terminal renderer. The
  detail half (`auto-resolved <path>: <reason>`) is a pinned diagnostic string and lives
  in the `Messages` catalog (one `// T17 additions` block at catalog EOF; no new
  `SnapError` cases were needed — collision/dirty/read errors all pre-exist, so the enum
  and `message` match are untouched, keeping the T14 merge surface minimal).
- **Warning order** is `Warning.ordering` end to end: the R75 difference is
  `SortedSet -- SortedSet` (ordering preserved) iterated directly — nothing re-sorts or
  re-renders downstream.
- **CliSuite stub-list shrink**: removed `merge` from the "known-but-unimplemented"
  list, per that test's own comment ("the remainder shrinks further as T17/T19 land") —
  the same edit T10/T12 made for their commands.
- **Suite delta**: full provided run went 13 → 21 of 28. Besides the five target tests,
  merge also turned 18 (three-way convergence), 20 (dirty merge), and 22 (OT matrix)
  green — they only needed the command. Test 16 stays red solely on its `diff --repo`
  step (T20/T21); its merge-collision step is unit-pinned in `CommandsMergeSuite`.
  Remaining reds are owned elsewhere: 12/13 (T19/T20), 14/24 (T13), 26 (HTTP + diff
  --repo), 28 (T22).
- **No core-semantics ambiguity encountered**: the spec text, D11/D27, and the engine's
  pinned behavior composed without a choice point that changes observable merge results.
- **Integration onto `main` (post-T13/T14 rebase, verified by a separate pass).** All
  three orchestrator-resolved conflicts checked out semantically, not just
  syntactically: (1) `Cli.defaultCommands` carries both T13's `Serve` and this task's
  `Merge` entries; (2) `Errors.scala`'s `Messages` catalog carries both T13's
  `invalidPort` and this task's `autoResolved` blocks, each still reachable through its
  own three insertion points (enum case / `message` match / catalog) with no leftover
  merge markers; (3) `CliSuite`'s "known-but-unimplemented" loop correctly shrank to
  `--serve` only (a bare `merge` now has a real handler, so it can no longer sit in that
  list). Fixed one thing found during this pass: that same test's comment claimed "an
  invalid port is a grammar error" — false, `Grammar.serveRule` is arity-only (SPEC
  §7.9), and an invalid port *value* is rejected by `CommandsServe.parsePortValue`
  instead (unit-pinned in `CommandsServeSuite`, not `CliSuite`); reworded the comment in
  `snap/scala/src/test/scala/snap/cli/CliSuite.scala` to say so without touching the
  test's behavior. Also confirmed the T13/T17 seam it was designed for holds: every
  `CommandsMergeSuite` case that drives `Cli.run` uses exactly one operand (the shape
  `Grammar.rules(Command.Merge)` — `oneFreeTextOperandRule` — requires), so none of them
  regressed to `invalid command or arguments`; the one test that exercises zero/two
  operands already expected that exact message from `CommandsMerge`'s own arity check,
  so Grammar now producing the identical error first is not observable. D11's
  dirty-before-remote-read precedence (test at line ~225) is untouched by the grammar
  layer, since a nonexistent remote path is still one syntactically valid operand.
  Gates after the fix: `sbt test` 603/603 (589 base + this task's 14), `scalafmtCheckAll`
  and `scalafixAll --check` both clean, and `./snap/verify --lang scala` 23/28 with the
  failing 5 exactly {12-http-server, 13-http-client, 16-dot-collision (its `diff --repo`
  step only), 26-portability-and-failure-safety, 28-terminal-presentation} — matching
  the plan's prediction with no deviation.

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

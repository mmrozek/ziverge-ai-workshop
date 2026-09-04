# T07 — Replay ready-loop, materialization, validation steps 5–6 (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T05, T06
- **Risk:** **core** (the replay ordering keys are the central tie-break — formal
  pre-commit review, saved as `reviews/T07-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/Replay.scala`: patch selection for a version (R65, known-version predicate
R45), the ready-loop with all three ordering keys verbatim (R66, D14), integration of
one patch in the **linear** case (R69 case 1: path identical in `B` and `C` → apply the
authored change directly; concurrent cases are typed hooks until T16), per-change
base-tree checks completing validation §4.5 steps 5–6 (R51–R52, R59–R60), and memoized
`materialize` (D19). After this task a valid linear repository materializes to its tree.
DESIGN §5 (steps 1–3a exercised linearly), §7.

## Scope
`snap/scala/src/main/scala/snap/core/Replay.scala`, extension of `Repo.validate`,
tests in `snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [x] Ready-loop ordering unit tests: patches ordered by snap order of result versions;
      directed tests for keys 2 (author `Utf8Order`) and 3 (numeric revision) using
      hand-built (invalid-in-practice) ties — all three keys live (D14).
- [x] Validation rejects: change against wrong base (text create over present path,
      edit/delete of absent path — `delete of absent path: f`), no-op change
      (`no-op change`), non-canonical result tokens; a history whose ready set empties
      early fails with `cyclic or incomplete patch history` (R60).
- [x] Property test: materializing a generated **linear** history is insensitive to the
      input order of the `patches` array (after validation's sort check is bypassed for
      generation, replay itself never reads input order).
- [x] Known-version predicate: accepts `()` always and any per-contributor prefix
      closure; rejects vectors selecting a patch whose base is not contained
      (`unknown version` at the CLI layer).
- [x] No wall-clock/env access; iteration over patches/paths only via sorted structures.

## Notes / decisions

- **T16 extension point shape:** `Replay.Integration` — a trait with one method
  `integrate(patch, base, authored, canonical): Either[SnapError, Tree]`, passed
  explicitly to `Replay.materialize` / `integrationOrder` (no default parameter).
  T07 ships `Replay.LinearOnly`: it applies R69 rule 1 when every changed path is
  identical in `B` and `C` **and** no R68 namespace conflict exists (detection only —
  `S` vs `C'` ancestor/descendant probe), in which case its result provably equals the
  full engine's; any genuinely concurrent case returns the typed
  `SnapError.ConcurrentHistoryUnsupported(dot)` — never a silently wrong tree. The
  namespace guard matters even under rule 1: concurrent creates of `a` and `a/b` pass
  the per-path rule-1 check but would otherwise build a non-prefix-free tree (directed
  test included). T16 replaces `LinearOnly`, removes the staging error case, and widens
  the integration result to carry warnings.
- **Warnings deferred to T16 (DESIGN §5 deviation, staged by PLAN):** DESIGN §5 gives
  `materialize` the shape `(Tree, SortedSet[Warning])`; rule 1 emits no warnings and
  `LinearOnly` errors on every case that could, so T07's `materialize` returns
  `Either[SnapError, Tree]` and T16 (which owns warning semantics R74/R75) widens it.
- **Step-5/6 pipeline shape:** steps 5–6 run as one replay — the ready-loop integrates
  each patch after computing `authoredResult(baseTree, patch)` (step 5: presence rules
  R51, no-op R52, EditScript typed errors R54–R57, authored-tree prefix-freeness R25 →
  `tree paths conflict`). Error ordering is therefore ready-loop order, then
  path-sorted change order, then a fixed per-change check order (documented on
  `authoredResult`) — deterministic; each provided fixture has a single defect so no
  pinned expectation depends on cross-patch precedence.
- **R51 reading (recorded, minor):** a text/put change *is* a creation iff its path is
  absent in the exact base tree — there is no intent flag, so the "creation requires
  absent" clause cannot fail for text/put; only `delete` can hit the absence rule
  (matching the only pinned message, `delete of absent path: f`). Test 27's
  "create present" fixture (empty edit over a present path) consequently fails as
  `edit does not consume old content` (shape-only pin `^snap: .+\n$` — satisfied).
  An insert-only edit over a present *empty* text file is a valid edit, not a create.
- **New diagnostics** (appended at the END of `SnapError`/`Messages` in one
  `// T07 additions` block for clean merging with parallel T09): `UnknownVersion`
  (`unknown version: <v>`, full line pinned by test 19), `DeleteOfAbsentPath`
  (`delete of absent path: <p>`, full line pinned by test 23), `NoOpChange`
  (`change <p> is a no-op change` — pinned fragment at line end), `TreePathsConflict`
  (`<p>: tree paths conflict` — pinned fragment at line end, carries the first
  offending path in `Utf8Order`), `TextEditOverNonText` (untested wording),
  `ConcurrentHistoryUnsupported` (temporary staging case, removed by T16).
- **Memoized materialize (D19):** immutable `Map[Version, Tree]` threaded through the
  replay fold, per run; base trees via recursive `materialize(patch.base)`. No
  seeding/shortcut from the canonical-so-far tree (correctness first — proving
  `C == materialize(joinSoFar)` for arbitrary induced orders was not attempted).
  O(n²) integrations for a linear chain, fine at repository scale.
- **`integrationOrder` exposed:** the canonical integration order is a specified
  observable (R66; §6.4's "later"), so it is a public function rather than a
  test-only hook — the ordering-key tests assert it, and keys 2/3 are tested directly
  on the public `Replay.readyOrdering`.
- **Scope deviation — `snap/fs/Store.scala`:** `readRepository` now calls the composed
  `Repo.validateFully` (steps 1–6) and returns the new `Repo.Valid` (structural proof +
  materialized frontier tree), per this task's instruction that the Store read
  pipeline runs all six steps. Only project tests consumed the old return type; they
  compile unchanged via `Valid.repository`.
- Until T16, `Repo.validateFully` rejects spec-valid genuinely concurrent histories
  with the typed staging error — deliberate staging (PLAN phase 3), never wrong bytes.
- Tests: `ReplaySuite` (34 directed) + `ReplayLawsSuite` (4 properties). Full project
  suite 295 passed; `scalafmtCheckAll` and `scalafixAll --check` green. No provided
  test is runnable yet (they exercise `status`, T10); fixtures from tests 15/23/27 are
  lifted into unit tests with their pinned strings asserted verbatim.

# T18 — Convergence hardening & property suite (2 SP)

- **Phase:** 3 — Merge & OT
- **Depends on:** T17
- **Risk:** **core** (any fix lands in clock/merge/tie-break code — formal pre-commit
  review, saved as `reviews/T18-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Turn the OT/convergence matrix tests green and land the R109 property suite as a
permanent regression net: a scalacheck generator of valid causal patch graphs
(multi-contributor, text/put/delete mixes, namespace collisions) and properties that
import permutations produce the same joined frontier, patch set, warnings, and tree —
byte-identical across repeated runs. Fix whatever divergence the matrix tests expose
(fixes go here, not silently into done tasks). DESIGN §5; R109; CLAUDE.md testing rules.

## Scope
`snap/scala/src/test/scala/snap/props/` (generators + properties); fixes limited to
`snap/scala/src/main/scala/snap/core/{Ot,Replay,Diff}.scala` if the matrix tests fail.

## Acceptance criteria
- [ ] Provided tests `18-three-way-convergence` (all 6 association orders) and
      `22-ot-matrix` pass (filters: `three-way`, `ot-matrix`).
- [ ] Property: for generated causal graphs split across 2–3 replicas and merged in
      every permutation of pairwise merges, final `(frontier, patchSet, tree,
      warnings)` are identical; runs with a fixed seed in CI mode (deterministic
      failure reproduction) plus unfixed seed locally.
- [ ] Property: merge idempotence (`merge(R, R) == R`) and commutativity of import
      order over generated graphs.
- [ ] Phase-3 gate: full suite shows 22 tests green (all except 12, 13, 16, 20, 26, 28).

## Notes / decisions

**First-half split (orchestrator, 2026-09-05):** T18's scope was split into two passes because
T17's `merge` command was still being integrated/reviewed on `main` and was not available in this
worktree's base. This entry covers only the CORE-level half:

1. The four deferred review pointers below — all landed, all test-only:
   - T05 finding 1: `snap/scala/src/test/scala/snap/core/DiffSuite.scala`, test
     `"golden (reviews/T05-review.md finding 1): equality-before-tie on trailing repeated lines"` —
     pins `diff([a\n,a\n],[a\n]) == [retain 1, delete 1]` and the symmetric insert case. Confirmed
     the current implementation already produces exactly this (hand-traced, matching the T05
     review's own independent probe) — no code change, additive golden only.
   - T15 finding 2: `snap/scala/src/test/scala/snap/core/OtSuite.scala`, test
     `"reviews/T15-review.md finding 2: P insert row fires against a pending Q delete head"` — a
     directed case (`p = insert[x], retain 1`, `q = delete 1`) that was previously only
     probabilistically reachable through the generated properties.
   - T16 nit 1: `snap/scala/src/test/scala/snap/core/ConcurrentReplaySuite.scala`, test
     `"reviews/T16-review.md nit 1: a warning raised only while materializing a patch's base is
     discarded..."` — hand-built the exact interposition shape the review's ruling 5 constructed
     (a same-path branch `d` integrates between same-path branches `p1`/`p2` in the OUTER ready
     order, while `p1`/`p2` alone compose a third patch `gamma`'s declared base). Verified by hand
     that the sub-replay used to materialize `gamma`'s base would independently resolve `p2` as
     `later-put-wins`, a DIFFERENT reason than the outer walk's actual `delete-wins` — proving the
     discard is load-bearing, not merely deduping. The test asserts the exact integration order
     first (so the engineered shape is confirmed, not assumed) then the final `(tree, warnings)`.
   - T16 nit 2: `snap/scala/src/test/scala/snap/core/ConcurrentReplayLawsSuite.scala` — replaced the
     unverifiable scaladoc claim with a real fixed-seed (`7L`) coverage test (last test in the
     file). Measured over that exact run: 227/300 (76%) generated histories contain a genuinely
     concurrent pair, 148/300 (49%) produce at least one warning, and all five `WarningReason`
     values fire — thresholds are set with margin below those exact counts.
2. The core-level property suite: new package `snap/scala/src/test/scala/snap/props/`
   (`CausalGraphGens.scala` + `ConvergencePropsSuite.scala`). Built exclusively against
   `snap.core`'s PUBLIC surface (`Repo.validate`/`Repo.validateFully`, `Replay.materialize`) —
   never the `private[core]` proof-value constructors `ConcurrentReplayLawsSuite` uses, and never
   the `merge` command. Recombination is exactly "union the patch vectors, join the frontiers, then
   `Repo.validateFully`" per the brief. See the final agent report for the generator's design,
   measured coverage evidence, and an explicit note on what this suite's permutation property does
   and does not prove (transparency: `Repo.validate` requires pre-sorted input, so the recombined
   input to it is byte-identical regardless of split/combination order — the property's genuine
   value is validating the join/union recombination algebra and `Repo.validateFully`'s own
   determinism across hundreds of diverse generated graphs, not bypassing engine-internal
   processing order the way `ConcurrentReplayLawsSuite` can from inside `snap.core`).

**Not done here (second pass's job):** the `merge`-command-level properties, the acceptance
criteria checkboxes above (all reference either the provided merge-dependent tests or are
ambiguous between core/command level), and the phase-3 gate. No main/scala production code was
touched — no divergence was found in `Ot`/`Replay`/`Diff` by this pass's properties or directed
tests.

## Pre-implementation pointers
- From `reviews/T05-review.md` finding 1: add the script-shape golden pinning
  equality-before-tie on trailing repeated lines — `diff([a\n,a\n],[a\n])` must be
  exactly `[retain 1, delete 1]` (guards R64 against any future diff refactor).
- From `reviews/T15-review.md` finding 2: directed OT test — P-insert row when Q's
  pending head is a delete (currently only probabilistically covered).
- From `reviews/T16-review.md` nit 1 (deferred here at triage): directed regression test
  for the sub-replay interposition shape — an unrelated same-path concurrent patch
  interposing, in the outer ready order, between two patches that also compose a third
  patch's declared base. The invariant to pin: warnings raised while materializing a
  patch's base are **discarded** (they belong to the sub-context, R65), so the frontier's
  warning set is unaffected by them. Without this test, a future edit that folds
  `materializeMemo`'s warnings into the outer accumulator — a plausible "surely additive"
  refactor — would pass every existing test while over-reporting in `merge`.
- From `reviews/T17-review.md` finding 1 (deferred here at triage — **part 2**, the
  `merge`-level half): R76 direction independence is currently asserted only against a
  fixture exercising OT and later-create-wins. The generator must merge in **both
  directions** across all five warning reasons (delete-wins, later-create-wins,
  later-put-wins, namespace-wins, put-wins), comparing version, warnings, the full
  working-tree byte map and `repository.json` bytes. The risk this closes: a change that
  breaks symmetry only in the presence of one specific reason — e.g. an accidental
  dependency on which side's `Patch` reference survives a colliding-but-equal dot — would
  pass every currently committed test.
- From `reviews/T17-review.md` finding 2 (deferred here at triage — **part 2**): dot
  collision reporting is pinned only by a single-collision fixture, so both directions
  trivially agree. Generate histories with **multiple simultaneous colliding dots** and
  assert the smallest in dot order is the one reported, in both directions.
- From `reviews/T16-review.md` nit 2 (deferred here at triage): replace the prose
  generator-coverage claim in `ConcurrentReplayLawsSuite`'s scaladoc ("200 samples, 96
  with warnings, all five reasons…") with an assertion, so the property suite cannot
  silently go vacuous. The test should fail if generated histories stop being genuinely
  concurrent or stop covering all five warning reasons. Delete the unverifiable comment
  once the test exists.

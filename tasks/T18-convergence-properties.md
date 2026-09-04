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
- From `reviews/T16-review.md` nit 2 (deferred here at triage): replace the prose
  generator-coverage claim in `ConcurrentReplayLawsSuite`'s scaladoc ("200 samples, 96
  with warnings, all five reasons…") with an assertion, so the property suite cannot
  silently go vacuous. The test should fail if generated histories stop being genuinely
  concurrent or stop covering all five warning reasons. Delete the unverifiable comment
  once the test exists.

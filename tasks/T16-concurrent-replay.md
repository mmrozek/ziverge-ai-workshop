# T16 — Concurrent replay: namespace, path rules, warnings (5 SP)

- **Phase:** 3 — Merge & OT
- **Depends on:** T07, T15
- **Risk:** **core** (this IS the merge semantics — formal pre-commit review, saved as
  `reviews/T16-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Complete `snap/core/Replay.scala` for concurrent histories (DESIGN §5, R67–R75): per
patch, materialize exact base `B` (memoized), then (a) namespace pre-pass — `S`, `C'`,
ancestor/descendant conflicts, install `T` + remove conflictors + `namespace-wins`,
overriding per-path rules; (b) per-path dispatch in spec order: `B`=`C` direct apply /
`C`=`T` collapse / all-text OT through aggregate `Q = diff(B,C)` applied to `C` /
path-level winner rules 1–6 in order with their warnings; (c) all of one patch's
changes applied together. Warning accumulation as a sorted set of unique
`(path, reason)` pairs (R74). Replay's public result: `(Tree, SortedSet[Warning])`.

## Scope
`snap/scala/src/main/scala/snap/core/Replay.scala` (+ small helpers in `Tree.scala`),
tests in `snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [x] Directed unit tests, one per path-level rule (R73 rules 1–6) asserting both the
      winning bytes and the exact warning reason, plus: namespace `a` vs `a/b` in both
      canonical orders (warning names the **removed** path — test 11's pin), identical
      concurrent changes collapse with **no** warning before OT (R69 case 2), aggregate
      `Q` is `diff(B,C)` once — a three-patch chain where per-patch chaining would give
      a different result (R72).
      (`ConcurrentReplaySuite`: rules 2/3/4/5/6 each with bytes + exact warning; rule 1
      = the identical-collapse tests, provably the same predicate as R69 case 2 — see
      note 4; namespace both orders + `C'`-exclusion + R70 combined-application; the
      delete-then-reinsert chain where chaining would keep the deleted token.)
- [x] Replay fixtures reproduce the merged bytes pinned by tests 09 (`base\nright\nleft\n`),
      18 (`B\nA\nend\n`), 21 (`base\nB1\nB2\nA2\n`) and the warning sets of tests 10/17
      — lifted from the YAML as unit tests (the commands arrive in T17; the engine is
      proven first).
      (`ConcurrentReplayFixturesSuite`: 09 incl. integration order + re-merge no-op; 10
      exact warning vector in pinned order + winner bytes + warning-free pre-merge
      replay (the R75 seam); 11 both directions; 17 both patch orders; 18 full +
      all three pairwise intermediates + all 24 array permutations; 21 incl. pinned
      integration order; 22 all four OT-matrix sub-cases, all silent.)
- [x] Property tests (mandatory, CLAUDE.md): for generated valid concurrent histories —
      same `(tree, warnings)` under permuted patch-array order and permuted import
      order; replay of a frontier already replayed is idempotent; OT paths emit no
      warnings. (`ConcurrentReplayLawsSuite`; generator coverage measured, not assumed:
      200 fixed-seed samples exercise all five warning reasons — see the suite doc.
      Plus: merged tree stays prefix-free; text-only permutation invariance.)
- [x] Negative constraints: no wall-clock/env/randomness; every iteration over
      paths/patches/warnings goes through sorted structures (`TreeMap` trees, path-sorted
      changes, `SortedSet` warnings/removals, key-probed memo); no `String.compareTo`
      on paths (`SnapPath.ordering`/`Utf8Order` only, incl. `Warning.ordering`'s
      reason-text key).

## Notes / decisions

1. **`S` = paths the patch *makes* present (creates), not all paths present in the
   authored result** (§6.2 namespace pre-pass, R68). "Makes present" is read as a
   transition: absent in the exact base `B`, present in the authored result `T` —
   whether by `put` or by a text edit over an absent path. Three convergent grounds:
   (a) the spec's wording — an edit of a path already present in `B` does not *make*
   it present; (b) rule coherence — under the wide reading an edit/replace would LOSE
   to a plain concurrent delete (§6.4 rule 3) yet WIN against a concurrent
   delete-plus-conflicting-create, an incoherent precedence, and §6.4 rule 3's
   delete-wins already resolves that shape to a consistent prefix-free tree; (c) the
   T07 review's on-record characterization of the spec's `S` as "newly-present paths"
   (LinearOnly's superset-S was flagged as such). No provided test discriminates (all
   namespace fixtures are creates); surfacing for the pre-commit review as the one
   load-bearing interpretation of the task.
2. **Sub-replay warnings are discarded** (R74): the replay's warning set is the outer
   ready-loop's — each selected patch integrated exactly once. Base materialization
   (`materializeMemo`) is §6.2's subroutine for obtaining `B`; it re-integrates subsets
   of the same patches in smaller contexts, and counting those would double- or
   mis-report pairs. Only the base *tree* is memoized.
3. **The `Integration` seam was collapsed, not re-implemented behind the trait**: the
   trait existed solely to stage T07 without concurrent logic; DESIGN §5's signature
   (`materialize(patches, V) → (Tree, SortedSet[Warning])`) has no strategy parameter,
   and a one-implementation trait is dead indirection. `integrate` is now a private
   method of `Replay`; `LinearOnly` and `SnapError.ConcurrentHistoryUnsupported` (plus
   its catalog entry) are deleted as the task directs.
4. **§6.4 rule 1 is checked once, as §6.2 case 2**: both are the predicate "C and T
   identical". The dispatch tests it before entering the path rules, so `pathRules`
   starts at rule 2 with the invariant `C != T` documented — re-testing it there would
   be provably dead code. Order of evaluation is otherwise verbatim.
5. **Pointer 2 (proof-type hardening) — done via `private[core]` constructors** on
   `Repo.StructurallyValid` and `Repo.Valid`: unforgeable outside `snap.core` (the
   command/fs layers can only obtain proofs from `validate`/`validateFully`), while
   core-package test suites keep their `handBuilt` ergonomics unchanged — the
   package-private hook the T07 review suggested, with zero test churn.
6. **Pointer 4 (OT application)**: transformed scripts apply through the new
   `EditScript.applyTransformed` — exact consumption enforced (R56), canonical-result
   check omitted (R57 governs patch scripts; §6.5 forces a merge result) — and the
   merged token sequence is rendered to bytes at the single call site
   (`Replay.transformAndApply`); the transient non-canonical list never escapes
   (T15 review finding 1). OT emits no warnings.
7. **Scope deviations**: `EditScript.scala` (outside the declared scope) gained
   `applyTransformed` plus a shared private `run` walk — the alternative was
   duplicating the consumption-checked application inside `Replay`, a worse drift
   hazard. `Repo.scala` (`Valid` widened with `warnings`, `validateFully` on the new
   engine, constructor hardening) and `Errors.scala` (deletions only) are the fallout
   the task itself names; `Store.scala` changed by one doc comment. No new
   `SnapError` cases were needed, so there is no `// T16 additions` block — a marker
   comment in `Errors.scala` records the removal instead. No `Tree.scala` helpers were
   needed (T04's ancestor/descendant queries sufficed).
8. **Sub-replay fallibility (pointer 1) honored and now pinned by a test**: a
   structurally valid history whose declared base is not self-contained fails with the
   pinned `cyclic or incomplete patch history` from inside `materializeMemo`
   (`ConcurrentReplaySuite`, the T07-review scenario).
9. **Integration onto post-phase-1 `main` (T11 `diff` + phase-1 review fixes had landed
   in T16's worktree's absence)**: `Repo.Valid`/`StructurallyValid`'s new
   `private[core]` constructors broke every out-of-package construction site. Fixed by
   updating the two CLI call sites of the widened `Replay.materialize`/`integrationOrder`
   API (no `Repo.Valid`/`StructurallyValid` construction happens in `cli`/`fs`, only type
   references, so the constructor hardening itself needed no follow-up there):
   `CommandsLog.scala` drops the removed `Replay.LinearOnly` argument to
   `integrationOrder`; `CommandsDiff.scala` drops it from both `materialize` calls and
   destructures the widened `(Tree, SortedSet[Warning])` result, discarding the warning
   half with a comment — `diff <old> <new>`'s two ad hoc historical materializations are
   not the `Repo.Valid.warnings` seam T17 needs (SPEC §7.6 has no warning output for this
   form), so nothing is silently dropped from a place that needs it.
10. **Stack-safety (CR1, pre-commit review gate) — found broken, fixed**: a 5000-patch
    linear-history probe reproduced the phase-1-review-predicted `StackOverflowError` in
    `Replay.loop` (the `for`-comprehension over `Either#flatMap` put the recursive call
    inside a lambda, never in tail position). Fixed by rewriting `loop`'s body as an
    explicit `match` with the self-call as the last expression on every path, annotated
    `@tailrec` — the compiler now verifies the tail call, so one invocation's iteration is
    O(1) stack regardless of selection size; `materializeMemo`'s own (non-self, so
    invisible to `@tailrec`) call into `loop` for a cache-miss base benefits the same way,
    since it is `loop` again. No behavior change — same `Either` short-circuiting, same
    warning/memo threading, verified by the unchanged 510/510 unit total and unchanged
    11/28 harness result before and after.
    Orchestrator-directed correction mid-review: the original "≥5000 patches" probe
    depth was based on stack risk, but replay is Θ(n²) in patch count (D19/phase-1 review
    PR5, an accepted, T23-deferred trade-off) — 5000/2500 depths take *minutes* of CPU,
    not stack. Rescoped to `ReplayStackSafetySlowSuite` (1500-patch linear, 750-diamond /
    1501-patch concurrent — still ~1.5x past the review's measured ~1k-patch crash
    threshold), given a 5-minute `munitTimeout` override (munit's 30s default is too
    short at this depth), and excluded from the default `sbt test` task via a
    `Test / test / testOptions` name filter in `build.sbt` (scoped to the `test` task
    specifically, not the `Test` config, so `testOnly`/the new `sbt slowTest` alias still
    reach it) — phase-gate material, not per-task material, confirmed by running both
    `sbt test` (510/510, suite absent) and `sbt slowTest` (2/2, ~60s wall clock) after the
    change.

## Pre-implementation pointers
- `reviews/T07-review.md` nit 1: `Replay.materializeMemo`'s sub-replays CAN fail
  (`CyclicHistory` on a non-self-contained base) — don't build on the infallibility
  assumption the original comment made.
- `reviews/T07-review.md` nit 2: consider private constructors for
  `Repo.StructurallyValid`/`Repo.Valid` (unforgeable proofs, DESIGN §1.4) if feasible
  without gutting test ergonomics; otherwise record why not.
- T07's `Replay.Integration` trait is your seam: replace `Replay.LinearOnly`, delete
  `SnapError.ConcurrentHistoryUnsupported`, widen results to carry warnings
  (`(Tree, SortedSet[Warning])` per DESIGN §5).
- `reviews/T15-review.md`: apply transformed scripts WITHOUT the canonical-result check
  (exact consumption still enforced) — reviewer-confirmed spec-forced (§6.5) — and
  render merged output to BYTES, re-tokenizing for downstream use; never let the
  non-canonical token list escape the transform site (finding 1).
- Phase-1 review CR1 (stack half): the T07-era replay loop was non-tail and could
  StackOverflow on ~1k+ patch valid histories. Your rewritten engine must be verified
  stack-safe at the pre-commit review (deep linear-history probe); fix in-place if not.

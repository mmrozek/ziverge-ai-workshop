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
- [x] Provided tests `18-three-way-convergence` (all 6 association orders) and
      `22-ot-matrix` pass (filters: `three-way`, `ot-matrix`).
- [x] Property: for generated causal graphs split across 2–3 replicas and merged in
      every permutation of pairwise merges, final `(frontier, patchSet, tree,
      warnings)` are identical; runs with a fixed seed in CI mode (deterministic
      failure reproduction) plus unfixed seed locally.
- [x] Property: merge idempotence (`merge(R, R) == R`) and commutativity of import
      order over generated graphs.
- [x] Phase-3 gate: full suite shows 22 tests green (all except 12, 13, 16, 20, 26, 28).

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

**Second-half completion (implementer, 2026-09-05):** the `merge`-command-level half, on top of
the first pass's core-level work (T17's `merge` command was available in this pass's worktree).
New file `snap/scala/src/test/scala/snap/cli/CommandsMergeConvergenceSuite.scala` (4 tests,
package `snap.cli` — a scope reading, see below); extended (not duplicated)
`snap/scala/src/test/scala/snap/props/CausalGraphGens.scala` with `buildSerialBranch`,
`NWayCase`/`genNWay` (a shared-seed history forked into 2-3 independently-authored serial
branches — each branch alone is standalone causally-closed and warning-free by construction,
which is what lets it be installed as a real, independently loadable on-disk repository, unlike
`splitIntoReplicas`'s shards) and `CollidingCase`/`genColliding` (multiple simultaneous colliding
dots, plus side-exclusive filler dots — see below for why the fillers are load-bearing). No
`snap/main` production code was touched; no divergence was found.

1. **Finding #1 (direction independence, all five reasons):** `CommandsMergeConvergenceSuite`'s
   first test draws 200 `genNWay(2)` samples, measures each pair's warning reasons at the cheap
   core level (`Repo.validateFully` on the union, no disk I/O), and asserts the union of reasons
   seen across the sample equals all five `WarningReason` values — the suite fails outright if any
   reason stops appearing, rather than merely asserting it once. Measured over ~15 fresh-seed runs
   during implementation: delete-wins 64-96/200, later-create-wins 26-44/200, later-put-wins
   20-36/200, namespace-wins 23-41/200, put-wins 5-17/200 (the rarest, but present every run) — all
   five fired in every run observed. One witness per reason (first occurrence; a case producing
   several reasons serves as a witness for all of them, so only 3-5 actual `Cli.run` merges happen
   per run) is then driven through the real command in both directions, asserting stdout, stderr,
   full working-tree bytes, and `repository.json` bytes agree, and that the target reason's token
   literally appears in stderr.
2. **Finding #2 (multi-dot collision):** `genColliding` generates 2-3 simultaneous colliding
   authors (same author+revision, deliberately different content by construction — never left to
   chance) mixed with 1-2 agreeing authors (identical `Patch` values on both sides). **A real bug
   was found and fixed in this generator during implementation, not in production code**: the
   first version gave both sides the exact same author set at the exact same vector positions, so
   `CommandsMerge.unionPatches`'s merge-join never had to advance one side past a dot the other
   side lacked — confirmed by deliberately reversing the author comparator's sign in
   `unionPatches` (mutation test, reverted immediately after) and observing the collision property
   still passed. Fixed by adding `leftOnlyFillers`/`rightOnlyFillers` — side-exclusive dots at
   fixed relative positions (before, between, and after the colliding pool) — after which the same
   mutation makes this property fail, confirming real falsifying power. 12 generated cases per
   run, both directions, asserting the reported line is exactly
   `snap: patch collision: <smallest colliding author> revision 1` and that no side's bytes
   changed.
3. **Idempotence and import-order commutativity through the CLI:** two tests. `merge(R, R)` over
   12 generated full multi-author graphs (`CausalGraphGens.genSeeds`/`buildGraph`, the
   general-purpose generator — one standalone repo suffices), checked against both a
   byte-identical independent copy and the literal same path (`merge .`); a targeted mutation
   (dropping R75's `-- local.warnings` subtraction) was confirmed to make this fail reliably
   across 4 fresh runs, since generated multi-author graphs routinely carry pre-existing
   warnings a naive self-merge would wrongly re-print. Import-order commutativity drives 6
   two-replica and 3 three-replica `genNWay` cases through every permutation of pairwise CLI
   merges (fresh per-shard copies each ordering), comparing final `repository.json` bytes,
   working-tree bytes, frontier, and warnings across all permutations and against a core-level
   oracle; the same author-comparator mutation was confirmed to make this fail too.
4. **What these properties can and cannot falsify** (stated in the suite's own scaladoc, per the
   brief's instruction not to ship reassurance): `Repo.validate` requires pre-sorted input and
   `unionPatches`'s output is canonically sorted, so — exactly as the core-level suite's own
   scaladoc says — none of these properties can catch a bug where the replay engine itself
   depends on array/collection processing order; that needs `snap.core`'s own package-private
   `ConcurrentReplayLawsSuite`. What driving the real command newly exercises, and the four
   mutation tests above confirm it genuinely can fail on: `WorkTree`/`Materialize`/`Store`
   plumbing depending on the current on-disk shape rather than only the target (installer
   path-independence across different intermediate merge-chain states), and any accidental
   left/right asymmetry in `CommandsMerge`'s own composition (the two review findings).
5. **Scope reading (non-core ambiguity, recorded per policy):** the new suite lives in
   `snap/scala/src/test/scala/snap/cli/` (alongside `CommandsMergeSuite`), not literally under
   `snap/scala/src/test/scala/snap/props/` as this task's original "Scope" line names — it needs
   `Cli.run`/`TestEnv`/`Env`, which live in `snap.cli`, and the original Scope line predates the
   T17/T18 split (it names only the core-level generator's home). The generators themselves
   (`NWayCase`/`genNWay`/`CollidingCase`/`genColliding`) stay in `CausalGraphGens.scala`, per the
   brief's explicit "reuse and extend it; do not write a second generator." Also: the acceptance
   criteria's "runs with a fixed seed in CI mode... plus unfixed seed locally" is not implemented
   as a literal two-mode switch — no such CI/local distinction exists anywhere else in this test
   suite (the core-level `ConvergencePropsSuite` doesn't have one either; only its separate
   generator-coverage sanity check uses one fixed seed, for exact reproducibility of a documented
   count, not for the convergence property itself). This pass's properties always run unfixed, per
   this delegation's explicit instruction ("a property suite that passes only on one seed is worse
   than none") and were verified across many fresh-seed runs during implementation (not just the
   three formally reported).
6. **`CommandsMergeSuite.scala` was not modified or reused**: its small filesystem helpers
   (`copyRepo`, `workingFiles`, `repoBytes`) are duplicated (not imported) into the new suite, so
   this task never touches an already-reviewed, committed T17 file. Both suites' helpers are
   `private`, so there is no dead-code/unused-import risk from the duplication.
7. **Verification:** `sbt test` 623 → 627 (4 new), 0 failed, three separate fresh-JVM full-suite
   runs (plus ~15 additional isolated runs of just the new suite during implementation) all green;
   `CommandsMergeConvergenceSuite` alone adds ~1.1-1.3s per full-suite run. Both lint gates
   (`scalafmtCheckAll`, `scalafixAll --check`) green. `sbt clean assembly` then
   `./snap/verify --lang scala` (Java 17 first on PATH): 23/28, exactly {01-11, 14, 15, 17-25, 27}
   passing and {12, 13, 16, 26, 28} failing (all four owned by T19-T22, none a regression); the
   two provided tests this task names (`18-three-way-convergence`, `22-ot-matrix`) both pass.
   `sbt slowTest` was not run — no suite here is named `*SlowSuite` (none needed it: the heaviest
   case count, property 3b's 3-replica permutations, still finishes in well under 2 seconds).

**Pre-commit review fixes (implementer, 2026-09-05):** applied both accepted findings from
`reviews/T18-review.md` before commit. Test-only; `git diff -- snap/scala/src/main` confirmed
empty throughout (re-checked after every mutation-and-revert cycle and again at the end).

1. **Finding #1 (Major) — insertion-only generator, fixed by mutation-proof.** The review found
   that `CausalGraphGens.chooseChange`'s "edit" branch (and the structurally identical
   `ConcurrentReplayLawsSuite.chooseChange`, which it mirrors) only ever authored pure insertions
   (`old.patch(pos, Vector(newLine), 0)` — zero tokens removed), so no `Change.Text` script any
   property ever exercises could contain a `Delete` op, leaving R62's diff tie-break and OT's three
   delete-consuming rows (`Ot.scala:69-93`) unreachable by any property in the codebase. Fixed both
   `chooseChange` methods (`CausalGraphGens.scala`, `ConcurrentReplayLawsSuite.scala`) with a shared
   `textEdit` helper: given the seed-picked position `pos` and `maxRemove = old.size - pos`, mode 0
   (only mode available when `maxRemove == 0`, i.e. appending past the last token) is the original
   pure insert; mode 1 is a pure delete of 1..`maxRemove` tokens; mode 2 replaces 1..`maxRemove`
   tokens with one new unique line. Also fixed `ConcurrentReplayLawsSuite.buildTextOnly`'s own
   separate inline duplicate of the same insertion-only pattern (not literally named by the review
   as one of the "two `chooseChange` functions," but it is the structurally identical mistake in the
   suite's guaranteed-clean-OT, no-warnings text-only path — the single best place to exercise OT's
   delete rows without warning noise — so left unpatched it would have perpetuated the exact blind
   spot being fixed; recorded here as a scope reading, not silently expanded). All three call sites
   now share `textEdit`, so there is one place to keep correct.

   Extended the coverage-sanity tests (not just reachability, per the brief: "the suites should fail
   if generated edits stop containing deletions") — added a `hasTextDelete`/count assertion to both
   fixed-seed "generator coverage" tests (`ConcurrentReplayLawsSuite`, seed `7L`;
   `ConvergencePropsSuite`, seed `42L`), each requiring at least a quarter of sampled histories to
   contain a `Change.Text` script with a `Delete` op — both passed comfortably on the first run with
   the new generator, alongside the pre-existing concurrency/warning-reason assertions (unaffected).

   **Mutation-testing proof (the acceptance criterion), run from `/Users/mmrozek/work/AI`:**
   reaching the delete/replace code paths turned out to be NECESSARY but NOT SUFFICIENT — the
   existing properties (permutation-invariance, idempotence, core-merge/CLI-merge recombination
   algebra, direction independence) all compare a computation against ITSELF or against a reference
   built through the identical code path on the identical input, so a Diff/OT bug that is
   deterministic-but-wrong (not order-sensitive) survives every one of them regardless of how rich
   the generator is. Confirmed empirically: with the generator fix alone (before adding any new
   property), both required mutations —
   `Diff.scala:32` `d(i+1)(j) <= d(i)(j+1)` → `<`, and `Ot.scala`'s row 4
   (`P delete`/`Q retain`, ~line 70-76) emitting `Retain` instead of `Delete` — left
   `ConcurrentReplayLawsSuite`, `ConvergencePropsSuite`, and `CommandsMergeConvergenceSuite` all
   green; only pre-existing hand-written suites caught them (`DiffSuite`/`DiffRenderSuite` for the
   tie-break; `OtSuite`/`ConcurrentReplaySuite`/`ConcurrentReplayFixturesSuite` for the OT row),
   exactly reproducing the review's own finding even after the generator fix.

   Added one new, genuinely content-correctness-sensitive property to `ConcurrentReplayLawsSuite`
   ("a concurrent replace and a concurrent pure insert at the same cursor converge with the
   insert's tag immediately before the replace's tag, and every replaced token gone"): two
   concurrent single-change patches share a seeded base file — one replaces `removed` existing
   tokens at a seed-picked `pos` with a distinct `"R\n"` tag (any disjoint delete(k>=1)+insert(m>=1)
   ties the diff DP table at every step of that sub-block, per hand-derivation against R61's
   recurrence, so this always hits R62's tie-break), the other purely inserts a distinct `"I\n"` tag
   at the exact same `pos`. The property asserts, against an independently-derived expectation
   (not a re-run of the same code): every replaced token is absent, both tags survive exactly once,
   and the insert's tag sits immediately before the replace's tag — this specific ordering was
   hand-traced against the current (independently-verified-correct) `Ot`/`Diff` for both possible
   `Q`/`P` role assignments and found to hold either way (an instance of R76 direction
   independence), so the property does not need to know or assume which side the ready-loop
   integrates first. Because `Replay.readyOrdering` decides that role assignment by comparing
   RESULT VERSIONS (not by which edit is "the replace"), a fixed pair of author literals pins the
   role assignment to one fixed outcome — the property therefore runs the same case under **both**
   author-role assignments (`(d1@x, d2@x)` and `(d2@x, d1@x)` as replacer/inserter) so the
   delete-bearing script is fed through `Ot.transform` as `P` in at least one of the two, reliably
   hitting row 4 regardless of the tie-break rule's specifics. This was itself discovered by
   mutation testing: the first version of this property (fixed author names) failed to catch the OT
   row-4 mutation because the fixed names always placed the replacer on the `Q` side.

   Final mutation results, both reproduced from a clean `git diff -- snap/scala/src/main`:
   - `Diff.scala:32` `<=`→`<`: **`snap.core.ConcurrentReplayLawsSuite` FAILS** (the new cursor
     property; obtained token order `[R, I]` instead of the expected `[I, R]`).
     `ConvergencePropsSuite`/`CommandsMergeConvergenceSuite` stay green (as their own scaladoc
     already discloses they cannot see engine-internal Diff/Ot behavior).
   - `Ot.scala` row 4 `Delete`→`Retain`: **`snap.core.ConcurrentReplayLawsSuite` FAILS** (the same
     new property; a replaced token, e.g. `L2\n`, survives in the merged output).
   - Both reverted via `git checkout -- <file>`; `git diff -- snap/scala/src/main` empty after each
     revert and at the end of the task.

   No production-code defect was found — both failures are exactly the injected mutations
   reproducing cleanly; the current `Diff`/`Ot` implementations are correct against this property.

2. **Finding #2 (Nit) — DiffSuite wording.** Renamed and reworded the T05-finding-1 golden
   (`DiffSuite.scala`) from "equality-before-tie" to "equal-token retain wins over the
   exhausted-side rule," and rewrote its comment to state plainly that `diff([a\n,a\n],[a\n])`
   resolves via the exhausted-side branch (`j == m`), never reaching the R62 tie-break comparator at
   `Diff.scala:32` — doc-only, the assertion is unchanged (still pins
   `[Retain(1), Delete(1)]`/`[Retain(1), Insert(...)]`).

**Cost:** `ConcurrentReplayLawsSuite` (now 7 tests, +1) ~0.7-1.0s; `ConvergencePropsSuite`
(unchanged test count, +1 assertion) ~1.0-1.2s; `CommandsMergeConvergenceSuite` (unchanged)
~1.4-1.7s — consistent with the pre-existing ~1-1.6s figures, no material regression.

**Final gates (from `/Users/mmrozek/work/AI`):** `sbt -batch test`: 635 → **636** (1 new property),
0 failed. Property suites re-run in a fresh JVM with unfixed seeds: 15/15 green (a second and third
fresh-JVM repeat were explicitly waived by the orchestrator under a time constraint; the one run
performed was clean). `sbt -batch scalafmtCheckAll` and `sbt -batch "scalafixAll --check"`: both
green (one file needed `scalafmtAll` reformatting first, applied). `sbt -batch clean assembly`:
jar hash `b258747839fb67fc574ba1eaae9eef2e9317ac9e`, byte-identical to the pre-fix baseline (40 main
sources, unchanged). `PATH=".../java/current/bin:$PATH" ./snap/verify --lang scala`: **24 passed, 4
failed** — exactly the predicted unchanged split (13, 16, 26, 28; T20/T22 in-flight elsewhere), no
movement. `git diff -- snap/scala/src/main` confirmed empty at the end.

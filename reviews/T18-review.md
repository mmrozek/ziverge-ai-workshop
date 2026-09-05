# T18 pre-commit review — convergence hardening & property suite (both passes)

**Verdict: approve-with-fixes**

0 Critical, 1 Major, 2 Nit. T18 changes no production code — its entire value is whether
the tests it adds would actually catch something. I verified falsifying power by mutation
(six distinct production-code mutations, each applied, tested, and reverted with the
production tree confirmed byte-identical afterward — see the Mutation-testing section).
Four of the six are genuinely caught by tests this task added or reinforced. The Major
finding is that the entire generator family this task extends (and the sibling generator
in T16's already-committed `ConcurrentReplayLawsSuite`, which T18 explicitly mirrors)
authors only insertion-only text edits, so neither the diff tie-break (R62/D18) nor the
three delete-consuming rows of the OT table (rows 4–6, R71) can ever be exercised by any
property suite in the codebase — confirmed empirically by two of the six mutations, both
of which slip through every property suite while being caught by pre-existing hand-written
unit suites. This is a real, previously undisclosed limitation, not a decorative test —
the suite has substantial genuine value elsewhere — but it leaves a spec-critical area
(SPEC-NOTES risk notes 1 and 2 name this pair as the top implementation risks) with zero
regression protection from the very property suite whose stated purpose (R109, "convergence
hardening") is to protect it.

- **Date / scope:** 2026-09-05. Part 1 committed as `720f7e5` (inspected via `git show
  720f7e5`); part 2 staged and uncommitted (inspected via `git diff --cached HEAD --
  snap/scala tasks`). Files: `snap/scala/src/test/scala/snap/props/{CausalGraphGens,
  ConvergencePropsSuite}.scala` (part 1, new), `snap/scala/src/test/scala/snap/core/
  {ConcurrentReplayLawsSuite,ConcurrentReplaySuite,DiffSuite,OtSuite}.scala` (part 1,
  additive), `snap/scala/src/test/scala/snap/cli/CommandsMergeConvergenceSuite.scala`
  (part 2, new) and further extensions to `CausalGraphGens.scala` (part 2),
  `tasks/T18-convergence-properties.md` (both passes' notes). No `snap/scala/src/main`
  file touched by either pass — confirmed by `git show 720f7e5 --stat`, `git diff --cached
  HEAD --stat -- snap/scala tasks`, and a stable main-source compile count (40 sources,
  unchanged) across every build in this review.
- **Reviewed against:** SPEC §5, §6.1–§6.5; `docs/plan/SPEC-NOTES.md` R61–R76, R109, risk
  notes 1–2; `docs/plan/DESIGN.md` §5, D18, D27, D28; `docs/SCALA-CONVENTIONS.md`;
  `tasks/T18-convergence-properties.md` (both passes' Notes, read in full); the four
  deferred findings' source reviews (`reviews/T05-review.md` finding 1, `reviews/
  T15-review.md` finding 2, `reviews/T16-review.md` nits 1–2, `reviews/T17-review.md`
  findings 1–2).

## Findings

**#1 [Major]** `snap/scala/src/test/scala/snap/props/CausalGraphGens.scala:132–171`
(`chooseChange`, reused verbatim by part 2's `buildSerialBranch` at `:276–292`, and
structurally identical to the pre-existing `ConcurrentReplayLawsSuite.chooseChange` at
`snap/scala/src/test/scala/snap/core/ConcurrentReplayLawsSuite.scala:101–142`, which T18's
own scaladoc says it mirrors "house style, per the T18 task brief") — every `Change.Text`
this generator family ever authors is a **pure insertion**. The "create" branches
(`:141`, and `ConcurrentReplayLawsSuite:110`) do `Diff.diff(Vector.empty, Vector(newLine))`
(insert into nothing). The "edit" branch (`:160`, and `ConcurrentReplayLawsSuite:129`) does
`Diff.diff(old, old.patch(pos, Vector(newUniqueLine), 0))` — the trailing `0` means "0
tokens removed," so it is *always* an insertion of one brand-new, globally-unique line,
never a replacement or deletion of existing content. The only content-shrinking operations
are `Change.Delete` (removes a whole tracked path, taking it out of the text-OT branch
entirely — `Replay.resolvePath`'s `textCase` requires `B`, `C`, and `T` all present and
text) and `Change.Put` (`:163–170`), which this generator only ever applies to a `present`
path (a replacement, `bEntry` defined) rather than a creation. Because the base tree `B`
of every path that stays text-classified is therefore always an ordered subsequence of the
current canonical tree `C` with all-distinct token content, the aggregate context edit
`Q = Diff.diff(B, C)` computed live during replay (`Replay.scala:509`) is *also* always
insertion-only in every case this generator's properties actually exercise — confirmed
empirically, not just by this argument (see Mutation-testing #2 and #5 below):

- **R62's diff tie-break** (`Diff.scala:32`, `D(i+1,j) <= D(i,j+1)`) is never exercised: a
  genuine tie requires an actual content replacement (delete-then-insert at unequal cost
  candidates), which this generator's `Change.Text` scripts never contain. Flipping the
  comparator to `<` is caught by two **pre-existing** (not T18-added) `DiffSuite` tests
  (`deletion-on-tie`, `trailing-newline change…`) but leaves `ConvergencePropsSuite`,
  `CommandsMergeConvergenceSuite`, `ConcurrentReplayLawsSuite`, `ConcurrentReplaySuite`,
  and `OtSuite` all green (Mutation #2).
- **OT rows 4–6** (`Ot.scala:69–93`, the three "P delete" / "Q delete" rows) are never
  exercised: `P` (the incoming patch's own script) is always insertion-only when it's a
  text change, and `Q` is always insertion-only for the reason above, so neither script
  ever contains a `Delete` op when passed to `Ot.transform`. Corrupting row 4's output
  (emit `Retain` instead of `Delete`, silently keeping content that should have been
  deleted) is caught by the pre-existing `OtSuite`, `ConcurrentReplaySuite`, and
  `ConcurrentReplayFixturesSuite`, but slips through `ConcurrentReplayLawsSuite` (T16's own
  property suite), `ConvergencePropsSuite`, and `CommandsMergeConvergenceSuite` with **zero
  failures across all three** (Mutation #5).

Concrete failure scenario: a future refactor of `Ot.transform`'s delete rows, or of
`Diff`'s tie-break, that is wrong exactly for delete-vs-insert interactions (the single
most spec-flagged risk area — SPEC-NOTES risk notes 1 and 2 name precisely these two
mechanisms as "where implementations diverge") would pass all ~635 tests including every
property suite in the repository, and only be caught if a maintainer happened to also touch
one of the ~6 pre-existing directed unit tests that hard-code a real replacement. Both
suites' own "what these properties can and cannot falsify" scaladoc sections
(`ConvergencePropsSuite.scala:19–34`, `CommandsMergeConvergenceSuite.scala:24–42`) disclose
only the `Repo.validate`-sortedness/processing-order limitation — accurate as far as it
goes, but silent on this second, independent, and arguably more consequential gap. This is
squarely the "further limitation they did not disclose" the review brief asked me to look
for.

This is a coverage gap, not a shipped defect — I did not find any evidence the production
`Diff`/`Ot`/`Replay` code is actually wrong here (all pre-existing directed and lifted
fixture tests pass, and I traced the delete rows and tie-break by hand against the spec
text with no divergence). It also does not invalidate the property suite's other,
genuinely-confirmed value (see Mutation-testing #1, #3, #4, #6). But as delivered, R109's
mandate ("generate valid causal patch graphs… verify import-permutation convergence") is
met for the create/namespace/whole-file-conflict dimension and *not* met for the
token-level OT dimension, which is the dimension SPEC-NOTES flags as riskiest.

Suggested direction (no patch — reviewer does not fix code): extend `chooseChange` (or add
a new step kind used by both this task's generators and, ideally, T16's) to author a
genuine in-place replacement within an existing text file — delete `k` existing tokens and
insert `m` different tokens at the same position — so that `B` and `C` can diverge by
removal as well as by pure growth. At minimum, disclose the limitation explicitly in both
suites' "what these properties can and cannot falsify" sections so a future reader does not
credit them with OT-delete or tie-break coverage they do not have.

**#2 [Nit]** `snap/scala/src/test/scala/snap/core/DiffSuite.scala:40–56` (the T05-review
golden, part 1) — the test and its comment are framed as pinning "equality-before-tie"
(and the underlying T05 review finding used the same phrase), but tracing
`diff([a\n,a\n],[a\n])` through `Diff.walk` (`Diff.scala:27–33`) shows the scenario is
resolved by the `j == m` exhausted-side branch (`:31`), never reaching the tie-break
comparison at `:32`. Because the walk's guards are mutually exclusive by index bounds
(`i < n && j < m` for the equality check vs. `i == n`/`j == m` for the exhausted-side
checks), no reordering of those checks in the *current* code shape can even change behavior
for this input — I confirmed this is not just an assertion but a structural fact by
tracing all four guards. The golden is still a legitimate protection against a wholesale
algorithm replacement (a Myers/common-suffix-anchored rewrite could plausibly get this
wrong), matching R64's intent, but its docstring overstates what it guards against in the
current implementation: nothing in the present code can violate it other than a full
algorithm swap, so it has zero mutation-testing power against small changes to the existing
walk. This wording is inherited verbatim from the original T05 review finding, not
introduced by T18, and the underlying test is correct and worth keeping — doc-only,
no code or test change required to unblock the commit. Suggested direction: reword the
comment to say it guards against a hypothetical algorithm replacement rather than implying
it exercises the R62 tie-break comparator.

**#3 [Nit]** Cost/hygiene housekeeping, no defect found — recorded because the brief asked
for an explicit hygiene check. `CommandsMergeConvergenceSuite` creates 3–4 temp directories
per test iteration (up to a few dozen `Files.createTempDirectory` calls across the suite's
four tests) and cleans every one via `try/finally { deleteRecursively(...) }`
(`CommandsMergeConvergenceSuite.scala:118–121, 178–205, 229–254, 271–290, 317–360`) —
cleanup runs even when an assertion inside the `try` throws. No server, socket, or thread
is started by either pass. Neither new file is named `*SlowSuite`, and neither should be:
measured per-suite wall time in this review's runs was ~0.96–1.18 s
(`ConvergencePropsSuite`) and ~1.26–1.59 s (`CommandsMergeConvergenceSuite`) out of a
~9 s full `sbt test` run — consistent with the task notes' own ~1 s / ~1.1–1.3 s figures
(this machine runs slightly higher but the same order of magnitude), well within the
project's "tens of cases, not hundreds" CLI-property cost norm.

No Critical or Minor findings beyond what is folded into #1 above.

## Mutation testing

All six mutations were applied directly to the relevant `snap/scala/src/main` file via
`perl`/`python3` text substitution (never via the Edit tool, to keep the change visible in
plain `git diff`), then reverted with `git checkout -- <file>`. After every single revert I
confirmed `git diff -- <file>` produced no output and `git status --porcelain` listed
nothing for that path. A final full-suite rebuild after all six mutations reproduced the
exact same jar hash (`b258747839fb67fc574ba1eaae9eef2e9317ac9e`) as the pre-mutation
baseline build, and `sbt test` was 635/635 green both before the mutation round and after
the final revert — the production tree is confirmed byte-identical throughout.

| # | Target (required by brief) | Mutation | Caught by (T18/T16 property suites) | Slips through |
|---|---|---|---|---|
| 1 | Merge-join (`CommandsMerge.unionPatches`) | `CommandsMerge.scala:131`: flip author comparator to `compare(b.author, a.author)` | `CommandsMergeConvergenceSuite` 3/4 tests (direction independence, multi-dot collision, permutation commutativity); pre-existing `CommandsMergeSuite` 5/14 | `ConvergencePropsSuite` (core-level, never calls `unionPatches` — exactly as its own scaladoc discloses); property 3a (self-merge idempotence, unaffected because `byAuthor` is always 0 when both sides are the identical vector) |
| 2 | Diff tie-break (D18) | `Diff.scala:32`: `<=` → `<` | Two **pre-existing** `DiffSuite` tests (`deletion-on-tie`, `trailing-newline change…`) | `DiffSuite`'s own **T18-added golden** (unaffected — see Finding #2), `OtSuite`, `ConcurrentReplayLawsSuite`, `ConvergencePropsSuite`, `CommandsMergeConvergenceSuite`, `ConcurrentReplaySuite` — **all green** (Finding #1) |
| 3 | Replay's rule ordering (R73) | `Replay.scala:544–550` (`pathRules`): check `Change.Put ⇒ later-put-wins` before the `bEntry.isEmpty && cEntry.isDefined ⇒ later-create-wins` guard, demoting rule 4 below rule 5 | **Nothing.** Full `sbt test` (all 635 tests, every suite in the project, including the entire provided-test-mirroring unit suite) stayed green | Every suite in the repository — this specific scenario (a concurrent *create* authored via `Put` racing another concurrent create) is not exercised by any test, provided or project-authored; see explanation below |
| 4 (supplementary) | T16 nit 1 load-bearing check | `Replay.scala`: thread `materializeMemo`'s sub-replay warnings into the outer accumulator instead of discarding them (`:262–264, 294–310`) | `ConcurrentReplaySuite`'s T16-nit-1 test fails with **exactly** the predicted extra pair: `f: later-put-wins` alongside the correct `f: delete-wins` | — |
| 5 (supplementary) | OT delete rows, direct confirmation of Finding #1 | `Ot.scala:75` (row 4): emit `Retain(n)` instead of `Delete(n)` | Pre-existing `OtSuite` (7/30 tests), `ConcurrentReplaySuite`, `ConcurrentReplayFixturesSuite` | `ConcurrentReplayLawsSuite` (T16's own property suite), `ConvergencePropsSuite`, `CommandsMergeConvergenceSuite` — **all green**, confirming Finding #1 directly at the OT layer |
| 6 (supplementary) | Part 2's own claimed mutation (R75 subtraction) | `CommandsMerge.scala:79`: drop the `-- local.warnings` subtraction | `CommandsMergeConvergenceSuite`'s idempotence property (fails on its very first sample); pre-existing `CommandsMergeSuite` 2/14 | — |

**Mutation #3 deserves its own explanation, since it is a genuine, project-wide,
undetected violation of R73's stated rule order** (not merely a property-suite gap like
#2/#5): rule 4 ("`B` absent ∧ `C`,`T` present → `later-create-wins`") and rule 5
("incoming is `put` → `later-put-wins`") are meant to apply in that priority order
regardless of the incoming change's kind — R73's own text gates rule 4 only on
presence/absence, not on `Change` type. Demoting rule 4 below rule 5 only changes behavior
for the narrow, legitimate scenario of a concurrent creation authored via `Put` racing
another concurrent creation of the same path — SPEC-pinned test 17
(`snap/tests/17-concurrent-creates.yaml`) exercises `later-create-wins` only via
`write_file` + `commit`, which always classifies the content as `Change.Text` (both
`"alice\n"` and `"bob\n"` are valid UTF-8 text), never `Change.Put`. Neither T16's original
`ConcurrentReplaySuite`/`ConcurrentReplayFixturesSuite`/`ConcurrentReplayLawsSuite` nor
T18's new suites construct this case either — `CausalGraphGens.chooseChange`'s `Change.Put`
branch (`:163–170`) only ever replaces an already-`present` path (confirmed by reading the
`if present.isEmpty then create else … Change.Put(path, content)` guard), never creates
one. I traced this by hand against the production code before mutating (`Replay.pathRules`
correctly checks rule 4 before rule 5 today), so **current production behavior is
spec-correct** — this is reported as context for Finding #1 (an undetected-by-any-test
scenario), not as a new, separate Major finding against shipped behavior, since it was
discovered as a side effect of probing the OT-generator gap rather than a targeted defect
hunt across the whole rule table. It is, however, additional evidence that this generator
family's `Change.Put` is never used as a *creation* mechanism, reinforcing Finding #1's
scope.

## Verification (reproduced independently)

All commands run in the foreground from the repo root / `snap/scala`, Java 17 first on
`PATH` where the harness is involved. No `run_in_background`, no Monitor.

1. **`cd snap/scala && sbt -batch clean assembly`** → `[success]`; 40 main sources compiled
   clean (unchanged from the pre-T18-part-2 baseline — confirms no `src/main` file was
   touched by either pass), jar `snap-assembly-1.0.0.jar`, hash
   `b258747839fb67fc574ba1eaae9eef2e9317ac9e`. Reproduced identically at the end of the
   review after all six mutation-and-revert cycles.
2. **`cd snap/scala && sbt -batch test`** → `Passed: Total 635, Failed 0, Errors 0` —
   exactly the expected count (631 on `main` after T19 + part 2's 4 new tests).
   `CommandsMergeConvergenceSuite` reported `0 failed, 0 ignored, 4 total` at ~1.3–1.6 s;
   `ConvergencePropsSuite` `0 failed, 0 ignored, 4 total` at ~1.0–1.2 s. `-Werror`/
   `-Wunused:all` are on (`build.sbt`), so this also confirms zero compiler warnings.
3. **Property suites re-run three times in fresh JVMs with unfixed seeds**
   (`testOnly snap.props.ConvergencePropsSuite snap.cli.CommandsMergeConvergenceSuite
   snap.core.ConcurrentReplayLawsSuite`) → `14 total, 0 failed` all three runs, distinct
   scalacheck seeds each time (no fixed-seed override in these classes other than the two
   dedicated, intentionally-fixed generator-coverage sanity checks).
4. **`cd snap/scala && sbt -batch scalafmtCheckAll`** → `[success]` (40 main + 51 test
   sources).
5. **`cd snap/scala && sbt -batch "scalafixAll --check"`** → `[success]` (40 + 51 sources,
   no findings).
6. **`PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala`**
   (Java 17 confirmed first on `PATH` via `java -version`) → **24 passed, 4 failed**,
   exactly the predicted split: `13` (HTTP client, T20), `16` (`diff --repo` HTTP step,
   T20/T21), `26` (`diff --repo` HTTP step, T20/T21), `28` (terminal presentation, T22) —
   all four owned by in-flight later tasks, none a T18 regression. This task's own two
   named provided tests both pass: `18-three-way-convergence` (`✓ three-way text history
   converges across different merge association orders`) and `22-ot-matrix`
   (`✓ text OT covers overlapping deletes split counts insert priority and trailing
   inserts`).
7. **Independent re-derivation of a measured coverage claim** (point 2 of the brief): I
   wrote a throwaway probe (`/tmp/snap-probe-src/Probe.scala`, outside the repo, added to
   `Test/unmanagedSourceDirectories` only for one interactive `sbt` session — never
   persisted to any repo file, deleted afterward, confirmed via `git status --porcelain`
   showing no change) that re-ran `ConvergencePropsSuite`'s exact fixed-seed (`42L`,
   300 samples) generator-coverage procedure independently. Result:
   `concurrentCount=284/300 multiShardCount=296/300
   reasonsSeen=HashSet(DeleteWins, LaterPutWins, LaterCreateWins, NamespaceWins, PutWins)`
   — an **exact match** to the scaladoc's claimed "284 graphs (95%)… 296 splits (99%)… all
   five reasons" (`ConvergencePropsSuite.scala:166–169`). CONFIRMED, not PLAUSIBLE. For
   part 2's per-run reason counts (necessarily unfixed-seed, so not exactly reproducible),
   I instead independently confirmed the *assertion* holds by running
   `CommandsMergeConvergenceSuite` in three separate fresh JVMs (step 3 above) — the union
   of observed reasons across 200 fresh-seed `genNWay(2)` samples equaled all five
   `WarningReason` values in every run, matching the claim that "all five fired in every
   run observed."
8. **Generator validity — no silent-discard mechanism.** Grepped both property test files
   and `CommandsMergeConvergenceSuite.scala` for ScalaCheck's `==>`/`suchThat`/`classify`/
   `discard` combinators — none present. Every validation call
   (`CausalGraphGens.validateState`, `Repo.validateFully`) is wired to `.fold(e =>
   fail(...), ...)`, so a generator that produced invalid causal graphs would surface as
   loud test failures, not silently-skipped cases. Across every run in this review
   (baseline + 3 fresh-JVM repeats + all mutation-round re-compiles), zero such failures
   occurred, confirming the "valid by construction" claim holds in practice, not just in
   the doc comment.
9. **`sbt slowTest` not run** — neither new file is a `*SlowSuite`, and the brief scopes
   that alias to phase gates; see Finding #3 for the cost figures that justify this.

## What I checked and found correct

- No `snap/scala/src/main` file is touched by either pass (`git show 720f7e5 --stat`,
  `git diff --cached HEAD --stat -- snap/scala tasks`, and a stable 40-file main-source
  compile count throughout).
- `ConvergencePropsSuite`'s (part 1) core-merge permutation, idempotence, and repeated-run
  determinism properties are genuinely exercised over hundreds of generated multi-author,
  namespace-colliding graphs, confirmed both by independent re-derivation of the exact
  fixed-seed coverage numbers (step 7) and by mutation (#1: appropriately *unaffected*
  since it never touches `unionPatches`, exactly as its own scaladoc discloses).
- `CommandsMergeConvergenceSuite`'s (part 2) four properties are wired to the right
  observables for R76 (stdout, stderr, full working-tree byte map, full `repository.json`
  bytes) and genuinely drive the real `Cli.run` command end to end, including installer/
  plumbing path-independence across different intermediate merge-chain shapes (property
  3b) — confirmed by mutation #1 (caught by 3 of its 4 tests) and #6 (caught by its
  idempotence property on the first sample).
- The `genColliding` generator's `leftOnlyFillers`/`rightOnlyFillers` fix (added after the
  implementer's own first-draft generator was found to have zero falsifying power) is
  correctly positioned: I independently verified by byte-level comparison that
  `"aa-only@x" < "agree-0@x" < "col-b@x" < "col-bz-only@x" < "col-c@x" < "zz-only@x"` under
  `Utf8Order`, so the fillers genuinely force the merge-join to interleave-advance both
  sides around the colliding pool rather than compare index-for-index — matching the
  generator's own doc comment.
- The four deferred review findings are genuinely closed, not merely superficially
  addressed:
  - **T05 finding 1** (`DiffSuite.scala:40–56`): the exact golden the finding asked for is
    present and passes; see Finding #2 above for a wording-precision nit that does not
    block the commit.
  - **T15 finding 2** (`OtSuite.scala:122–132`): the directed `P insert` vs. pending
    `Q delete` case is pinned exactly as specified (`p = insert[x], retain 1`;
    `q = delete 1` → `[insert[x]]`); hand-traced correct against the OT table. Being a
    hand-written directed test (not generator-dependent), it is immune to Finding #1's
    generator gap.
  - **T16 nit 1** (`ConcurrentReplaySuite.scala:345–401`): I independently confirmed this
    test is load-bearing, not just plausible, via mutation #4 — folding sub-replay
    warnings into the outer accumulator produces *exactly* the predicted spurious
    `f: later-put-wins` pair alongside the correct `f: delete-wins`, proving the discard
    is genuinely necessary and the fixture genuinely constructs the documented
    interposition shape (the integration order is itself asserted first, before the final
    result, so the engineered shape is confirmed rather than assumed).
  - **T16 nit 2** (`ConcurrentReplayLawsSuite.scala:268–276` and surrounding): the prose
    coverage claim is now a real, fixed-seed (`7L`), 300-sample assertion that fails if the
    generator stops producing concurrent histories or stops covering all five reasons —
    confirmed present and passing.
- **T17's two deferred findings are closed at or above the level requested:**
  - Finding 1 (direction independence, all five reasons): the suite measures at the cheap
    core level first (200 samples, no I/O) and asserts the *union* of observed reasons
    equals all five — an outright failure if any reason stops appearing, not a
    once-and-done assertion — then drives one witness per reason through the real command
    in both directions on the right observables. Reproduced 3/3 fresh-JVM runs green.
  - Finding 2 (multi-dot collision): `genColliding` produces 2–3 simultaneous colliding
    dots plus 1–2 agreeing dots plus side-exclusive fillers, and asserts the smallest
    colliding author (by `ContributorId.ordering`) is reported in both directions with
    exact stderr text and zero mutation on either side (R103) — confirmed by mutation #1,
    which breaks exactly this property.
- **Honesty of recorded limitations — partially accurate, with the gap now documented as
  Finding #1.** Both suites correctly and honestly disclose that `Repo.validate`'s
  sortedness requirement means neither can catch engine-internal processing-order bugs
  (that is `ConcurrentReplayLawsSuite`'s job, confirmed correct) — I found no
  overstatement in that specific claim. What neither suite discloses is the
  insertion-only-generator limitation (Finding #1), which I judge the more consequential
  of the two, since it affects a spec-flagged top-risk area rather than an already-covered
  one.
- Lint gates and the full project suite are green; the provided harness shows exactly the
  predicted 24/28 split with no unexpected regression, reproduced independently. Both
  provided tests this task names (`18-three-way-convergence`, `22-ot-matrix`) pass.
- Determinism: no `now()`/env/randomness/hash-order dependence in either new file (grepped
  for `System.`, `Random`, `.hashCode`, unsorted `Set`/`Map` construction — none present
  outside already-reviewed engine internals); all generator draws go through ScalaCheck's
  `Gen`/`Seed` machinery, and the two fixed-seed sanity checks are explicitly documented as
  fixed for reproducibility, not as a substitute for the unfixed-seed properties.

## Triage (orchestrator)

Verdict accepted: **approve-with-fixes**. Finding #1 is fixed before T18 commits — it is a
Major against the central deliverable of a `Risk: core` task, and deferring it would leave
the regression net decorative over exactly the two mechanisms SPEC-NOTES names as the
likeliest places for an implementation to diverge.

This is the finding I asked the review to hunt for: a limitation the implementers did not
disclose. Both passes honestly documented the `Repo.validate` sortedness caveat; neither
noticed that the generator family authors **insertion-only** text edits, so `Q = diff(B,C)`
is insertion-only in every generated case, and therefore R62's tie-break and OT's three
delete-consuming rows are unreachable by any property in the repository. Two of the
reviewer's six mutations — the `Diff.scala:32` comparator flip and OT row 4 emitting
`Retain` instead of `Delete` — passed every property suite, including T16's, and were
caught only by pre-existing hand-written tests.

| # | Severity | Decision | Action |
|---|---|---|---|
| 1 | Major | **accepted — fixed now** | Extend the generator family so text changes also delete and replace existing tokens, making `P` and the derived `Q` capable of carrying `Delete` ops. Fix `CausalGraphGens.chooseChange` **and** the structurally identical `ConcurrentReplayLawsSuite.chooseChange` it was mirrored from — T16's suite has the same blind spot, so fixing only T18's would leave the older one decorative. Proof of the fix is mutation-based, not argumentative: the two mutations that slipped through must now fail a property suite. |
| 2 | Nit | **accepted — fixed now** | The T05-golden's "equality-before-tie" framing describes a different, mutually exclusive code path than the one it guards. Doc-only wording correction; cheap to do in the same pass. |
| 3 | Nit | **accepted, no action** | Cost and hygiene checked clean: temp dirs cleaned, no bound ports or live threads, nothing warranting a `*SlowSuite`, ~1–1.6 s added per full run. |

Method note worth carrying to the post-completion audit: mutation testing found in one pass
what three prior reviews and two implementers missed by reading. The audit brief should use
it — a suite's value is what it *fails* on, and that is cheap to measure directly.



# T16 pre-commit review — concurrent replay: namespace, path rules, warnings

**Verdict: approve**

0 Critical, 0 Major, 0 Minor, 2 Nit. This is a `Risk: core` task and I traced the whole
concurrent-integration engine (namespace pre-pass, per-path dispatch, aggregate OT, path-level
rules, warning accumulation, sub-replay semantics, stack safety) against SPEC §6.2–§6.5 line by
line, not just against the diff. I did not find a spec divergence. The two Nits are coverage/
documentation suggestions, not defects, and do not block the commit.

- **Date / scope:** 2026-09-04, uncommitted working tree on `main` (base `20f3896` T11 / `78f961a`
  phase-1 close; `9b7cbd0`… → current `HEAD`). Diff reviewed: `git diff HEAD -- snap/scala tasks`
  (staged + unstaged together) — `snap/core/{Replay,EditScript,Errors,Repo}.scala`,
  `snap/fs/Store.scala`, `snap/cli/{CommandsDiff,CommandsLog}.scala`, `build.sbt`, three new test
  suites (`ConcurrentReplaySuite`, `ConcurrentReplayFixturesSuite`, `ConcurrentReplayLawsSuite`),
  `ReplayLawsSuite`/`ReplaySuite` updates, the new `ReplayStackSafetySlowSuite`,
  `tasks/T16-concurrent-replay.md`, `docs/plan/DESIGN.md` (D27 row).
- **Reviewed against:** SPEC §5, §6.1–§6.5, §4.5; `docs/plan/SPEC-NOTES.md` R65–R76; `DESIGN.md`
  §5, D14, D19, D27; `docs/SCALA-CONVENTIONS.md`; `tasks/T16-concurrent-replay.md` (acceptance
  criteria + Notes 1–10); `reviews/T07-review.md`, `reviews/T15-review.md`, phase-1 review CR1.

## Rulings on the required review points

**1. §6.2 integration order fidelity — CONFIRMED.**
`Replay.integrate` (`Replay.scala:361-385`) runs the namespace pre-pass first
(`namespacePrePass`, `:407-432`), builds `remaining = patch.changes.filterNot(settled)`, and
passes the *same* immutable `base`/`authored`/`canonical` values into every `resolvePath` call
(`:369-373`) — nothing threads an evolving accumulator between paths, so every path within one
patch is judged against one fixed `(B, C)` snapshot, exactly as R69 requires ("evaluate every path
against the same B and C"). `resolvePath` (`:448-465`) dispatches in the spec's literal order:
`B=C` direct apply → `C=T` collapse → all-text OT (`textCase`, `:472-488`, `transformAndApply`,
`:503-513`) → `pathRules` (§6.4, `:533-551`). All of one patch's resulting entries are merged into
the next canonical tree in a single step at the end of `integrate` (`:374-385`): removals, then
per-path outcomes, then pre-pass installs. I checked disjointness of these three path-sets by
hand: a namespace removal can never coincide with one of `P`'s own changed paths (if it did, `T`
would contain both the removed path and its conflicting `S`-member as ancestor/descendant, which
step 5's prefix-free check on `authoredResult` would already have rejected — Replay.scala:157-161
via `authoredResult`); a settled `S`-path is excluded from `remaining` before `resolvePath` ever
sees it; two `S`-paths can never conflict with each other because `T` is prefix-free. So the
sequential fold (removals → outcomes → installs) is behaviorally a disjoint union, matching the
spec's "simultaneously" even though it's coded as three sequential folds — the code's own comment
at `:352-356` states this reasoning and it holds up. **No interleaving between patches is
possible**: `integrate` is a pure function of `(patch, base, authored, canonical)` called once per
ready-loop step in `loop` (`:246-279`); the next patch is only processed after `loop`'s tail call
receives the fully-formed next canonical tree.

**2. Aggregate `Q` — CONFIRMED, computed once per path per patch, never chained.**
`transformAndApply` (`Replay.scala:503-513`) computes `Diff.diff(bTokens, cTokens)` fresh from the
two *trees* on every call; there is no historical-Q cache and no code path that composes multiple
past transforms. I hand-verified the discriminating test (`ConcurrentReplaySuite.scala:275-314`,
"aggregate Q collapses a concurrent delete-then-reinsert chain that chaining would not") by tracing
all five integrations by hand (seed, carol, bob1, bob2, alice) using the aggregate rule at each
step: seed creates `f="x\ny\n"`; carol appends `z` (direct apply, B=C); bob1's delete of `x`
transforms through `Q=diff("x\ny\n","x\ny\nz\n")=[retain2,insert z]` → `[delete1,retain2]` → `C`
becomes `"y\nz\n"`; bob2's re-insert of `x` transforms through `Q=diff("y\n","y\nz\n")=[retain1,
insert z]` → `[insert x,retain2]` → `C` becomes `"x\ny\nz\n"`; alice's delete of `x` (base=seed)
transforms through `Q=diff("x\ny\n","x\ny\nz\n")=[retain2,insert z]` → `[delete1,retain2]` → final
`"y\nz\n"` — matching the test's asserted result exactly. A per-historical-patch-chaining
implementation would instead cancel alice's delete against bob2's *reinsert* directly (rather than
against the aggregate, which has already collapsed bob's delete+reinsert to a no-op) and yield
`"x\ny\nz\n"` — a different result, confirmed by the test's own worked comment and my independent
derivation. **The test genuinely discriminates**, it is not vacuous.

**3. §6.4 rules 1–6 order, reachability, rule 5/6 asymmetry — CONFIRMED total and non-overlapping.**
`pathRules` (`Replay.scala:533-551`) starts at rule 2 because rule 1 (`C=T`) is `resolvePath`'s
R69-case-2 test (`:459`), checked before `pathRules` is ever invoked — so `pathRules`'s invariant
`C != T` on entry is real, not assumed, and re-testing it would be dead code (Note 4,
`tasks/T16-concurrent-replay.md:83-86` — I verified this by exhaustive case analysis, not just by
reading the claim: on entry to `pathRules`, `C != T` is guaranteed and `T` is never absent unless
`change` is `Delete`, since `Put`/`Text` always leave an entry present in the authored result). I
enumerated all four `(B present/absent, C present/absent)` combinations against R69's own
upstream filtering (case 1 catches `B=C`, leaving only `B present/C absent`, `B absent/C present`,
and `B present/C present`) and confirmed each of rules 2–6 fires in exactly the state the spec
assigns and no other: rule 2 (`T` absent) is checked first and unconditionally, so it correctly
pre-empts rules 3/4 regardless of `B`/`C`; rule 3 (`B` present, `C` absent) can only be reached with
`T` present, matching "the earlier concurrent delete beats an incoming edit"; rule 4 (`B` absent,
`C` present) can only be reached with `T` present (again pre-empted by rule 2), matching
"later-create-wins"; the final `else` branch is reachable only when `B` and `C` are *both* present
(the `B`/`C`-both-absent case is R69 case 1, already excluded) and `T` is present — and by tracing
`authoredResult`'s own step-5 validation (`Replay.scala:153-162`), if `change` is `Text` at this
point `B` cannot be non-text-and-present (a `Text` change against a non-text base would already
have failed `authoredResult` with `TextEditOverNonText` before `integrate` is ever called), so a
`Text` change reaching the final `else` implies `C` — not `B` — is the non-text side, exactly
matching rule 6's "`C` is non-text" (not "`B` is non-text"). Rule 5 fires only for `Change.Put`,
rule 6 only for the residual `Change.Text` (a `Delete` can never reach the final `else`, since `T`
absent is already caught by rule 2). Every rule is directly, individually tested with both bytes
and the exact warning reason (`ConcurrentReplaySuite.scala:80-172`). No rule is dead code; none
fires in a state assigned to an earlier rule.

**4. Warning-set semantics (R74/R75) — CONFIRMED.**
`Warning.ordering` (`Replay.scala:47-50`) sorts by `SnapPath.ordering` then
`Utf8Order.compare(reason.text, reason.text)` — both keys are `Utf8Order`-derived
(`SnapPath.ordering` is `Ordering.by(_.value)(using Utf8Order)`, `Path.scala:65`), never
`String.compareTo`. `SortedSet[Warning]` deduplicates by construction, matching "the set of unique
warning pairs." I directly verified sort order with a probe test
(`ConcurrentReplaySuite.scala:317-340`, mixed-path/mixed-reason insertion, asserting the exact
sorted, deduplicated `Vector`) — collapsing a literal duplicate correctly, and ordering ties on
reason lexically as `delete-wins < later-create-wins < later-put-wins < namespace-wins < put-wins`,
matching test 10's pinned stderr order (cross-checked against the actual YAML,
`snap/tests/10-merge-conflicts.yaml:79-81`, and the lifted fixture,
`ConcurrentReplayFixturesSuite.scala:91-152`, byte for byte). Line OT never produces a `Warning`
(`transformAndApply` always returns `warning = None`, `:513`); rule 1/R69-case-2 also never
produces one (`resolvePath:459`). Since the `SortedSet`'s own iteration order already *is*
"path then reason," T17's `merge` can render the warning lines directly off the set without any
extra sort — the ordering the spec needs for output is exactly the ordering the data structure
gives for free.

**5. Sub-replay warning discarding — RULED CORRECT AND REQUIRED, not merely acceptable.**
This is the point the task flagged as most likely to matter, so I worked it through from the spec
text rather than trusting the implementer's comment. `materializeMemo` (`Replay.scala:294-310`)
discards the `warnings` field of any sub-replay it runs to obtain a patch's base tree `B`, keeping
only the tree. My reasoning, checked against R65 and the ready-loop's own structure:

- R65 requires "the set must contain every selected patch's base" — recursively, so *every* patch
  that ever appears inside any other patch's base closure is *itself* a top-level member of the
  selection for the version actually being materialized, `V`. Consequently every such patch is
  *also* integrated directly, exactly once, by the **outer** ready-loop (`loop`'s own tail-recursive
  pass, not any sub-replay) — and it is that direct integration, against the *true* running
  `canonical` (which may include other concurrent effects a smaller sub-replay context cannot see),
  that is the spec-correct evaluation counted in the outer `warnings` accumulator (`:277`).
- A sub-replay computed inside `materializeMemo` is a **different, smaller** `materialize` call —
  for `patch.base`, not for `V` — used solely to reconstruct the byte content of `B`. Its own
  internal integration decisions can legitimately differ from the *true* global integration of the
  same underlying patches: I constructed a concrete scenario (three branches off one seed, where an
  unrelated concurrent patch touching the same path can interpose between two patches in the
  **outer** walk's actual sequence but is absent from a smaller base-only sub-replay) where the
  sub-replay's conflict resolution at a shared path is not the one the outer walk's own direct
  integration of those same two patches produces. Including the sub-replay's warning there would
  either double-report a pair already correctly captured by the affected patch's own direct outer
  turn (harmless, since the `SortedSet` would dedupe an *identical* pair) or, worse, report a pair
  that does **not** correspond to the true global resolution (a pair the smaller context believes
  fired but the actual outer integration — with more concurrent context present — resolved
  differently, e.g. via collapse instead of a warning-worthy rule).
- Given R65's closure guarantee, **no warning is ever lost**: any patch whose integration could
  produce a reportable conflict is unconditionally revisited by the outer loop itself.

Net: discarding sub-replay warnings is not a convenience, it is the only choice consistent with
R74 describing "the [one] replay['s]" warning set for `V`, and mixing in sub-replay warnings would
risk incorrect (not just redundant) output in exactly the interposition shape above. I did not find
a hand-crafted directed test that specifically exercises this interposition shape (see Nit #1
below), but the property suite's permutation/idempotence checks (`ConcurrentReplayLawsSuite`,
200+ generated histories across three independent fresh-seed runs — see Verification) would very
plausibly have caught a regression here, since an inconsistent accumulation scheme tends to produce
order-sensitive results under permutation.

**6. Determinism — CONFIRMED, no wall-clock/env/randomness, all iteration sorted, memo
order-insensitive.**
Grepped `Replay.scala` for `now()`/`System.`/`getenv`/`Random`/`.hashCode` — none present. The only
non-sorted collections are `Namespace.settled: Set[SnapPath]` (`:322`, probed only via `.contains`
at `:368`, never iterated) and `Memo = Map[Version, Tree]` (`:177`, probed only via `.get`/
`.updated` at `:299`/`:310`, never iterated) — confirmed by grep, matching the doc comments'
claims. `Tree` iterates via `TreeMap[SnapPath, _]` keyed by `SnapPath.ordering` (`Tree.scala:18`),
so its content is sorted by construction; `patch.changes` is guaranteed sorted by `Patch.make`'s
R49 check (`Patch.scala:114-117`, unchanged, `private` constructor). I hand-traced that the memo
cannot introduce order-dependence: `materializeMemo` is a pure function of `(valid, version)` alone
(`select(valid, version)` reads only the static repository plus `version`, never "who's asking"),
so a memo hit and a memo miss are observationally identical (same tree, same eventual warning set,
since sub-replay warnings are discarded either way). I additionally traced (not just assumed) why
`Replay`'s `@tailrec` architecture stays O(1) JVM stack depth *and* the documented Θ(n²) cost is a
work bound, not a nesting-depth bound: in `deepLinearHistory`-style historie, by induction the
memo already contains every intermediate base version by the time a later patch's own base becomes
a cache miss (each `v(a->k)` is first requested, and thus first cached, when patch `k+1` is
processed by the **outer** loop itself), so a "cache-miss" sub-replay's own internal base lookups
are *always* hits — nesting of `materializeMemo`→`loop`→`materializeMemo` never goes more than one
level deep, regardless of history length; the Θ(n²) cost is the sub-replay's own flat, `@tailrec`
re-walk of a growing prefix, which is O(1) stack per invocation by construction of the annotation.
`ReplayStackSafetySlowSuite.scala` (reviewed, not run — see Verification) targets exactly this: a
1500-patch single-author chain (worst case for the re-walk pattern above) and a 750-diamond/1501-
patch two-author concurrent history (forcing real rule-5 resolutions at every generation); both
would have reproduced the pre-fix `StackOverflowError` (the suite's own doc comment records this
was confirmed pre-fix) and both are excluded from the default `sbt test` task via a `test`-task-
scoped (not `Test`-config-scoped) `testOptions` filter (`build.sbt:9-12,26`), so `testOnly`/
`slowTest` still reach them — I verified the filter is scoped correctly by inspecting `build.sbt`
and by confirming the suite is genuinely absent from the 510-test default run (see Verification).
I did not find any other helper that recurses proportionally to patch count or tree size:
`authoredResult`'s fold is bounded by one patch's own change count (`:154-156`), `namespacePrePass`'s
fold by one patch's own changes (`:420-432`), `integrate`'s fold by one patch's `remaining` changes
(`:369-373`) — none scale with total history length.

On the property suites' generator coverage (asked to assess whether they could pass vacuously):
`ConcurrentReplayLawsSuite`'s `buildConcurrent` generator (`:1182-1213` in the diff / actual file
`ConcurrentReplayLawsSuite.scala:66-97`) picks among 3 authors per step and randomly excludes
peers' results from each step's base via `baseMask`, so genuine concurrency (bases that do not
causally dominate one another) is a first-class, frequently-generated shape, not an edge case; the
suite's own doc comment records a measured, non-vacuous outcome (200 fixed-seed samples, 96 with
warnings, all five reasons observed) which I did not re-derive byte-for-byte (see Findings #2) but
is consistent with the generator's structure. The `genPermutation` helper (`:170-173` in the actual
file) sorts by independently-drawn random `Long` keys, so the identity permutation is astronomically
unlikely for `n>1` — the permutation-invariance properties are not silently testing the identity
case. The text-only generator (`buildTextOnly`) always edits one always-present file with a
position-varying, per-step-unique inserted line, so identical-content collapse (R69 case 2) is
structurally near-impossible and the "OT emits no warnings" property genuinely exercises the OT
branch, not a degenerate direct-apply/collapse path. I ran the full property suite three times in
separate fresh JVMs (fresh ScalaCheck seeds each time, no fixed-seed override found in the code) —
0 failures across all runs (see Verification).

**7. Deferred pointers from earlier reviews — all honored, verified independently:**

- **T07-review nit 1** (sub-replays CAN fail): `materializeMemo`'s doc comment
  (`Replay.scala:281-286`) states this explicitly, and it is now pinned by a test
  (`ConcurrentReplaySuite.scala:342-354`, a `c1` whose declared base selects `a2` but not `a2`'s own
  dependency `b1` — fails with the exact pinned `cyclic or incomplete patch history` message). I
  re-derived the scenario by hand against `checkBaseClosure`/`checkAcyclic` — it is a genuine
  steps-1-4-valid, step-5/6-invalid history, exactly the T07-review's scenario.
- **T07-review nit 2** (proof-type hardening): `Repo.StructurallyValid`/`Repo.Valid` constructors
  are now `private[core]` (`Repo.scala:47,59`). I did not take this on faith — I compiled a
  throwaway probe file in a package outside `snap.core` against the project's own compiled classes
  (`sbt`, `unmanagedSourceDirectories` session-only, no repo files touched) attempting
  `Repo.Valid(...)`, `Repo.StructurallyValid(...)`, and `someValid.copy(tree = ...)`. All three
  failed to compile: `object Valid in object Repo does not take parameters`, `private[core] method
  copy can only be accessed from package snap.core`. This **empirically confirms** Scala 3's
  synthesized `copy`/`apply` inherit the constructor's access modifier here — the proof really is
  unforgeable from outside `snap.core`. I grepped for any production construction site outside
  `Repo.scala` — none exists (`CommandsDiff`/`CommandsLog` only reference the widened
  `materialize`/`integrationOrder` return types, per Note 9). All four test suites' `handBuilt`
  helpers that construct `Repo.StructurallyValid` directly live inside package `snap.core`
  (`ConcurrentReplayFixturesSuite.scala:56`, `ConcurrentReplayLawsSuite.scala:139`,
  `ReplaySuite.scala:53`, `ReplayLawsSuite.scala:124`), so no test route re-widens the constraint.
- **T15-review finding 1** (transformed scripts, no canonical check, byte re-tokenization):
  `EditScript.applyTransformed` (`EditScript.scala:86-87`) delegates to the shared `run` helper
  (`:92-109`), which still enforces exact consumption (`Underconsumption`/`Overconsumption`) but
  omits the `isCanonical` gate that only `applyTo` layers on top (`:72-76`). `transformAndApply`
  (`Replay.scala:503-513`) calls `applyTransformed` and immediately calls `encode(merged)`
  (`:513`, `encode` at `:592-593`), which renders to UTF-8 bytes via `TextTokens.render` — the
  transient, possibly non-canonical token list is never stored, returned, or reused; `Tree` only
  ever holds `IArray[Byte]` (`Tree.scala:18`), so no consumer can observe the raw list.
- **Phase-1 review CR1** (stack safety, stack half): `loop` is `@tailrec` (`Replay.scala:246`) with
  the self-call as the sole expression in every branch of the final `match` (`:262-279`) — verified
  by the compiler accepting the annotation (a non-tail self-call would be a compile error under
  `@tailrec`) and by my own trace in point 6 above showing `materializeMemo`'s call into `loop` adds
  at most one extra, bounded level of nesting regardless of history size.

**8. D27 (the set `S`) — not reopened.** `namespacePrePass` computes `makesPresent` as paths
"absent in `base`, present in `authored`" (`Replay.scala:415-419`), matching D27's locked text
exactly; I reviewed only the code's fidelity to that definition, not the definition itself, per
instruction.

## Findings

**#1 [Nit]** `snap/scala/src/test/scala/snap/core/ConcurrentReplaySuite.scala` (no directed test) —
no hand-crafted test specifically exercises the "sub-replay resolves a conflict differently than
the outer walk's own direct pass over the same patches" shape I constructed under point 5 (an
unrelated same-path concurrent patch interposing, in the outer ready order, between two patches
that also happen to compose a *third* patch's declared base). The property suite's permutation/
idempotence checks plausibly cover this indirectly (200+ generated histories with multi-level base
joins, across three independently-seeded runs, all green), but a directed regression test would
make the "discard sub-replay warnings" invariant harder to accidentally break in a future edit
(e.g. someone "helpfully" merging `materializeMemo`'s sub-`warnings` into the outer accumulator,
believing it strictly additive). CONFIRMED as a coverage gap by reading the test suites; not a
behavioral defect — my own proof under point 5 shows the current code is correct.

**#2 [Nit]** `snap/scala/src/test/scala/snap/core/ConcurrentReplayLawsSuite.scala:75-77` (scaladoc)
— the claimed generator-coverage measurement ("200 fixed-seed samples, 96 with warnings, all five
reasons: delete-wins 42, later-create-wins 32, namespace-wins 22, later-put-wins 16, put-wins 6") is
an implementer's claim I did not independently re-derive (would require instrumenting the private
generator methods or a throwaway harness beyond this review's scope). The generator's *structure*
(3-author random `baseMask` exclusion, biased-binary puts, position-varying edits) is consistent
with the claim and I have no reason to doubt it, but I record this as PLAUSIBLE rather than
CONFIRMED, per the review's own evidentiary standard.

No Critical, Major, or Minor findings.

## Verification (reproduced independently)

All commands run in the foreground from the repo root / `snap/scala`, Java 17 first on `PATH`
where the harness is involved.

1. **`cd snap/scala && sbt -batch clean assembly`** → `[success]`, jar built
   (`snap-assembly-1.0.0.jar`, hash `8365067b…`), 34 sources compiled clean.

2. **`cd snap/scala && sbt -batch test`** →
   ```
   [info] Passed: Total 510, Failed 0, Errors 0, Passed 510
   [success] Total time: 7 s
   ```
   Exactly the expected 510. `ReplayStackSafetySlowSuite` correctly absent from this run (not in
   the started-suite list), confirming the `build.sbt` test-task filter works.

3. **`cd snap/scala && sbt -batch scalafmtCheckAll`** → `[success]` (both main/test source sets
   check clean, 34 + 42 sources).

4. **`cd snap/scala && sbt -batch "scalafixAll --check"`** → `[success]` (34 + 42 sources, no
   findings).

5. **`PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala`** →
   **17 failed, 11 passed**, exactly the expected pass list: 01, 02, 03, 04, 05, 06, 08, 15, 23,
   25, 27 (mapped explicitly against `snap/tests/*.yaml` names — see below). All 17 failures are
   `snap: not implemented` (or the corresponding stdout/stderr mismatch it causes) at the `merge`
   step or a version-arithmetic step downstream of a skipped merge — the expected T17 gap; no
   regression, no unexpected `stderr_equals ""` breakage from the JDK-24 `sun.misc.Unsafe` warning
   (Java 17 was first on `PATH`).
   ```
   ✓ 01 init creates an empty repository
   ✓ 02 initialization preserves files and rejects nested or existing repositories
   ✓ 03 local and global contributor configuration have strict precedence
   ✓ 04 commit status and log expose exact deterministic history
   ✓ 05 diff renders canonical repeated-line edits and missing final newlines
   ✓ 06 binary and empty files are versioned byte exactly
   ✗ 07 revert is additive and restores file-directory transitions
   ✓ 08 working tree scans reject symlinks and special files without mutation
   ✗ 09,10,11,12,13,14,16,17,18,19,20,21,22,24,26,28 (merge-dependent or downstream of it)
   ✓ 15 repository reader rejects malformed schemas histories paths and edits
   ✓ 23 repository validation rejects every malformed layer before mutation
   ✓ 25 configuration versions paths and text use their exact canonical boundaries
   ✓ 27 patch histories require exact schemas canonical order and valid base transitions
   ```

6. **Repeated-run determinism** (not required verbatim by the brief's numbered list, but performed
   per the skill's "verify determinism concretely" instruction):
   `sbt -batch "testOnly snap.core.ConcurrentReplayLawsSuite snap.core.ConcurrentReplaySuite
   snap.core.ConcurrentReplayFixturesSuite snap.core.ReplayLawsSuite snap.core.ReplaySuite"` →
   74/74 passed. Then `testOnly snap.core.ConcurrentReplayLawsSuite` run twice more in fresh JVMs
   (fresh ScalaCheck seeds each time, no fixed-seed override in the suite) → 5/5 both times, 0
   failures across all three runs of the property suite.

7. **`sbt slowTest` was NOT run**, per the brief's explicit instruction (phase-gate material only;
   the stack probe was already executed for this task at 1500/1501-patch depths, ~63s, both
   green). I reviewed `ReplayStackSafetySlowSuite.scala`'s code instead (point 6 above and the
   dedicated trace of the `@tailrec`/`materializeMemo` nesting depth) and am satisfied it targets
   the real risk and would catch a regression if the `@tailrec` annotation were removed or the
   `loop`/`materializeMemo` call structure were changed to reintroduce non-tail recursion.

8. **Proof-type unforgeability probe** (ad hoc, not repo-mutating): compiled a throwaway file under
   `/tmp` in package `outside` via a session-scoped `sbt` `unmanagedSourceDirectories` addition
   (never persisted, no repo files touched), attempting `Repo.Valid(...)`, `.copy(...)`, and
   `Repo.StructurallyValid(...)` from outside `snap.core`. All three failed to compile with
   `private[core]` access errors — see point 7 above for the exact compiler output.

## What I checked and found correct

- Namespace pre-pass overrides per-path rules; removals, installs, and per-path outcomes are
  pairwise disjoint by construction, so single-step application is safe regardless of fold order.
- Every path within one patch is judged against the same, unmutated `B`/`C`; no cross-patch or
  cross-path interleaving is possible.
- Aggregate `Q = diff(B, C)` is computed once per path per patch from the two trees directly, never
  chained through historical patches; the discriminating three-patch test was hand-verified to
  actually distinguish correct from chained-incorrect behavior.
- §6.4 rules 1–6 fire in the spec's exact order, none dead, none reachable out of turn; rule 5/6's
  put-vs-text asymmetry is enforced indirectly but correctly via `authoredResult`'s own upstream
  validation.
- `Warning` sort key is `Utf8Order` throughout (path, then reason text) — never `String.compareTo`
  — matching R74/R75 and test 10's pinned stderr order exactly.
- OT and R69-case-2 collapse never emit warnings.
- Sub-replay (`materializeMemo`) warning discarding is spec-correct and necessary, not just
  convenient, given R65's recursive base-closure requirement.
- No wall-clock/env/randomness; every order-sensitive structure (`Tree`, `patch.changes` (R49,
  unchanged upstream invariant), `SortedSet[Warning]`/`SortedSet[SnapPath]` removals, memo probing)
  is sorted or probe-only by construction.
- `@tailrec` on `loop` is on the method that actually scales with history length; nesting through
  `materializeMemo` stays bounded regardless of history size (independently traced, not just
  read); no other helper recurses proportionally to patch count or tree size.
- All four deferred pointers from T07/T15/phase-1 reviews are honored, three of them independently
  re-verified beyond reading the code (compiler probe for constructor hardening, hand-traced
  scenario for sub-replay fallibility, direct code read for the OT/canonical-check finding).
- D27 was not reopened; the code matches its locked text.
- Lint gate and full project suite are green; the provided harness shows exactly the expected
  11-pass/17-fail split with no unexpected regressions.

## Triage (orchestrator)

Verdict accepted: **approve**, commit as-is. No finding blocks the commit — both are
test-coverage/documentation nits on an engine the reviewer traced line-by-line against
the spec and independently re-verified (510/510, both lint gates, harness 11/28 with the
expected pass list, property suites re-run three times in fresh JVMs).

| # | Severity | Decision | Where it goes |
|---|---|---|---|
| 1 | Nit | **deferred → T18** | A directed regression test for the sub-replay interposition shape. T18 (*Convergence hardening & property suite*) is the task that owns exactly this kind of guard, and the reviewer's own proof under ruling 5 establishes the current code is correct — so this protects against a *future* edit, not a present defect. Pointer added to `tasks/T18-convergence-properties.md`. |
| 2 | Nit | **deferred → T18** | The generator-coverage figures in `ConcurrentReplayLawsSuite`'s scaladoc are an unverified claim (reviewer recorded PLAUSIBLE, not CONFIRMED). The right fix is to stop asserting it in prose: T18 turns it into a test that fails if the generator stops producing genuinely concurrent histories or stops covering all five warning reasons. Until then the comment stands as written. Pointer added to `tasks/T18-convergence-properties.md`. |

Notable ruling worth carrying forward (report point 5): discarding the warnings raised
while materializing a patch's *base* is not merely acceptable but **required** — a base
is materialized from its own closed patch set (R65), so any warning arising there is an
artifact of the sub-context, and folding it into the frontier's set would over-report.
T17's R75 subtraction therefore consumes only `Repo.Valid.warnings`. Recorded in T17's
pre-implementation pointers so `merge` does not "helpfully" re-add them.

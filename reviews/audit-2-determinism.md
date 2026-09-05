# Post-completion audit — lens 2: determinism and merge semantics (adversarial)

**Auditor:** reviewer agent (Opus), independent worktree
`/Users/mmrozek/work/AI/.claude/worktrees/agent-aadd1a5bf110046bc`
**Date:** 2026-09-05 · **Commit under audit:** `232ecfe` (T23, all 23 tasks done)
**Method:** mutation testing (primary), direct code audit, CLI-level differential reproduction

---

## Verdict: `release-with-fixes`

**No Major.** Nothing I could construct makes the engine produce a wrong or
order-dependent merge result. 21 mutations of the comparison/merge/tie-break core: **18 caught,
3 uncaught**. All three uncaught mutations are **test-coverage gaps, not defects** — in each case
I built the divergent input, ran it through the real CLI on both the mutated and the shipped
build, and the shipped build produced the spec-correct answer. The risk they carry is holdout
disagreement and future regression, not a bug in what ships.

| Severity | Count |
| --- | --- |
| Major | 0 |
| Minor | 3 |
| Nit | 2 |

**Baseline, re-executed by me (not taken on trust):**

| Gate | Result |
| --- | --- |
| `sbt -batch test` | **693 passed**, 0 failed (11 s) |
| `sbt -batch slowTest` | **2 passed**, 0 failed |
| `sbt -batch scalafmtCheckAll` | success (54 sources) |
| `sbt -batch "scalafixAll --check"` | success (54 sources) |
| `./snap/verify --lang scala` (Java 17 first on PATH) | **28/28 passed** (76 s) |
| Worktree after all mutation work | `git status --porcelain` empty; `git diff HEAD` empty; assembly jar hash `b23d146032cbca9004cddd3294a0898dbfa3eb27` identical to the pre-mutation build |

---

## Mutation table

Every mutation was applied to production code in this worktree, verified against
`sbt -batch test`, then reverted with `git checkout --` and confirmed clean before the next one.
Uncaught mutations were additionally run against the provided acceptance suite
(`sbt clean assembly` + `./snap/verify --lang scala`) and reproduced through the real CLI.

| # | Mutation | Site | Suites that failed (tests) | Suites that passed | Verdict |
| --- | --- | --- | --- | --- | --- |
| M01 | `Diff` tie-break `<=` → `<` (deletion-on-tie, R62) | `Diff.scala:32` | `DiffSuite`(2), `DiffRenderSuite`(2), `ConcurrentReplayLawsSuite`(1) | all others | caught |
| M02 | Ot row 1 (`Q insert` priority) demoted below row 2 | `Ot.scala:54–58` | `OtSuite`(4), `ConcurrentReplayFixturesSuite`(4), `CommandsMergeSuite`(1) | `ConcurrentReplayLawsSuite`, `ConvergencePropsSuite` | caught |
| M03 | Ot row 3 (`P retain`/`Q retain`) emits nothing | `Ot.scala:62–68` | 8 suites, 41 tests | — | caught |
| M04 | Ot row 4 (`P delete`/`Q retain`) emits `Retain` instead of `Delete` — **the T18 regression mutation** | `Ot.scala:70–76` | `OtSuite`(7), `ConcurrentReplayFixturesSuite`(3), `ConcurrentReplaySuite`(1), **`ConcurrentReplayLawsSuite`(1)** | others | caught — **T18's oracle fix verified to hold** |
| M05 | Ot row 5 (`P retain`/`Q delete`) emits `Retain` instead of nothing | `Ot.scala:78–84` | 5 suites, 23 tests | — | caught |
| M06 | Ot row 6 (`P delete`/`Q delete`) emits `Delete` instead of nothing | `Ot.scala:87–93` | 5 suites, 18 tests | — | caught |
| M07 | Ot row 1 retains `1` instead of `length(Q insert)` | `Ot.scala:55` | 5 suites, 12 tests | — | caught |
| M08 | Snap order counter tie-break direction reversed | `Version.scala:123` | `VersionAlgebraSuite`(1), `VersionLawsSuite`(1), `ReplaySuite`(1), `ConcurrentReplayFixturesSuite`(1) | `ConcurrentReplayLawsSuite`, `ConvergencePropsSuite`, `CommandsMergeConvergenceSuite` | caught |
| M09 | Snap order one-sided-id branches flipped | `Version.scala:119–120` | 6 suites, 26 tests | — | caught |
| M10 | `compareCausal` `Before`/`After` swapped | `Version.scala:43–44` | `VersionLawsSuite`(3), `VersionAlgebraSuite`(1), `CommandsRevertSuite`(1) | — | caught |
| **M11** | **§6.4 rule 5 (`later-put-wins`) evaluated before rule 4 (`later-create-wins`)** | `Replay.scala:560–567` | **none — 693/693 pass** | everything, **and 28/28 provided** | **UNCAUGHT — finding 1** |
| **M12** | **Namespace pre-pass `S` = every changed path present in `T` (drops the `!base.contains(p)` creates-only filter, D27)** | `Replay.scala:433` | **none — 693/693 pass** | everything, **and 28/28 provided** | **UNCAUGHT — finding 2** |
| M13 | Memo keyed by `next.result` instead of `newProgress` | `Replay.scala:294` | `ConcurrentReplayLawsSuite`(6), `ConvergencePropsSuite`(4), `CommandsMergeConvergenceSuite`(2), `ConcurrentReplaySuite`(1) | — | caught |
| M14 | Memo stores `authored` instead of `nextCanonical` | `Replay.scala:294` | `ConcurrentReplayLawsSuite`(6), `ConvergencePropsSuite`(4), `CommandsMergeConvergenceSuite`(1) | — | caught |
| **INSTR** | **Self-checking instrumentation: every memo HIT re-verified against a fresh, memo-free sub-replay** (re-entrancy guarded) | `Replay.scala:315–316` | only `ReplaySuite`'s **performance guard** (7068 ms > 5000 ms — expected, the check restores Θ(n²)); **zero `MEMO INCONSISTENT` across all 692 other tests and all 28 provided tests** | — | **memo proven consistent on every input either suite reaches** |
| M15 | `readyOrdering` key 1 (Snap order of result versions) dropped | `Replay.scala:81–82` | 4 suites, 25 tests | — | caught |
| M16 | `readyOrdering` keys 2 and 3 swapped (revision before author) | `Replay.scala:84–86` | `ReplaySuite`(1) | — | caught (thin, but the key is documented-unreachable in valid histories) |
| M17 | `unionPatches` dedupes by dot instead of structural equality | `CommandsMerge.scala:131` | `CommandsMergeSuite`(1), `CommandsMergeConvergenceSuite`(1), `CommandsDiffSuite`(1) | — | caught |
| **M18** | **`SnapPath.ordering` → `String.compareTo` (UTF-16 code-unit order) instead of `Utf8Order`** | `Path.scala:65` | **none — 693/693 pass** | everything, **and 28/28 provided** | **UNCAUGHT — finding 3** |
| M19 | `Warning.ordering` sorts by reason before path | `Replay.scala:49–50` | `ConcurrentReplayFixturesSuite`(1), `ConcurrentReplaySuite`(1) | — | caught |
| M20 | Namespace pre-pass `C'` keeps authored deletions | `Replay.scala:430` | `ConcurrentReplaySuite`(1) | — | caught (thin — nit 1) |
| M21 | R69 case 2 (identical-concurrent-change collapse) disabled | `Replay.scala:475` | `ConcurrentReplayFixturesSuite`(1), `ConcurrentReplaySuite`(3) | — | caught |

**Revert confirmation.** After the final mutation: `git status --porcelain` → empty,
`git diff HEAD --stat` → empty, and a fresh `sbt clean assembly` reproduced jar hash
`b23d146032cbca9004cddd3294a0898dbfa3eb27`, byte-identical to the hash produced before any
mutation was applied. Nothing tracked or untracked was left behind. Scratch repositories live
only under `/tmp/snap-audit/`.

---

## Findings

### Finding 1 — Minor: §6.4 rule-4 / rule-5 precedence has no covering test (M11)

**Site:** `snap/scala/src/main/scala/snap/core/Replay.scala:560–567`

Swapping the evaluation order of §6.4 rule 4 (`later-create-wins`) and rule 5
(`later-put-wins`) leaves **693/693 project tests and 28/28 provided tests green**. The shipped
order is spec-correct; nothing pins it.

**Reachable, observable divergence — reproduced through the real CLI.** Two repositories,
each creating path `f` from the empty tree, concurrently: `a@x` commits binary content
(`snap commit` selects `put`), `b@x` commits text. Snap order puts `(b@x->1)` first, so `a@x`'s
`put` integrates second against `B` absent / `C` present.

```
shipped build:  warning: auto-resolved f: later-create-wins   (spec-correct — rule 4)
M11 build:      warning: auto-resolved f: later-put-wins      (rule 5 reached first)
```

Merged bytes are identical in both (`78 00 79`); only the warning reason differs. Because
`merge` prints only *new* warnings and §6.5 makes the warning set part of the contract, a holdout
test of the kind SPEC §11.5 mandates ("every path-level winner rule … and exact warning order")
would distinguish these. Today a regression here is silent.

**Suggested fix:** one fixture in `ConcurrentReplayFixturesSuite` — concurrent `put`-create vs
text-create of the same path, asserting `later-create-wins`.

### Finding 2 — Minor: the namespace pre-pass `S`-membership reading is untested, and it is a core-semantics ambiguity resolved without the user (M12)

**Site:** `snap/scala/src/main/scala/snap/core/Replay.scala:433`

`S` is implemented as *creates only* — changed paths **absent in `B`** and present in `T`
(decision D27, argued at `Replay.scala:414–420`). Dropping the `!base.contains(p)` filter, so
that `S` becomes every changed path present in `T`, leaves **693/693 and 28/28 green**.

**This is the most consequential uncaught mutation: it changes merged bytes, not just a
warning.** Reproduced end-to-end:

- `seed@x` commits file `s` (`"one\n"`).
- Concurrently: `a@x` edits `s` → `"one\ntwo\n"`; `b@x` deletes `s` and creates `s/x`.
- Merge `B` into `A`:

```
shipped build:  warning: auto-resolved s: delete-wins       tree = { s/x: "inner\n" }
M12 build:      warning: auto-resolved s/x: namespace-wins  tree = { s:   "one\ntwo\n" }
```

Different tree, different warning, different reason. **I believe the shipped reading is
correct**: SPEC §6.2's "the paths that `P` makes present" reads naturally as an absent→present
transition, and the implementation's own argument is sound — under the alternative reading an
edit to `s` would resurrect a concurrently deleted file and delete `s/x`, contradicting §6.4
rule 3 ("If `B` is present and `C` is absent, the earlier concurrent delete wins"). So this is
CONFIRMED as a coverage gap, not a defect.

Two things nonetheless deserve the orchestrator's attention:

1. **No test distinguishes the readings.** The scenario above is exactly SPEC §11.5's
   "namespace collisions such as concurrent `a` and `a/b`" in its delete-and-recreate form,
   and it is untested.
2. **Process:** CLAUDE.md ground rule 1 requires core-semantics ambiguity (merge behavior,
   tie-break rules) to be **escalated to the user, never guessed**. `S`-membership is squarely
   core merge semantics and was settled as an implementer decision (D27) recorded in task notes.
   Worth a one-line user confirmation before release, given the reading determines merged bytes.

**Suggested fix:** the scenario above as a fixture, plus surfacing D27 to the user for
confirmation.

### Finding 3 — Minor: `SnapPath.ordering`'s delegation to `Utf8Order` is untested; the path generator cannot falsify it (M18)

**Site:** `snap/scala/src/main/scala/snap/core/Path.scala:65`; generator at
`snap/scala/src/test/scala/snap/core/CoreGens.scala:10–11`

Replacing `Utf8Order` with plain `String.compareTo` in `SnapPath.ordering` leaves
**693/693 and 28/28 green** — including
`SnapPathSuite.scala:128` `"ordering on SnapPath agrees with Utf8Order on the raw value"`, the
property specifically written to protect this.

**Why the property cannot fire.** `CoreGens.segmentPool` is
`Seq("a","b","z","ab","file","nested","sub","x1","é","😀",".hidden","...","data")`. UTF-16
code-unit order and UTF-8 byte order diverge **only** when a supplementary character is compared
against a BMP character in **U+E000..U+FFFF**. The pool has a supplementary character (`😀`,
U+1F600) but **no character in U+E000..U+FFFF** — `é` is U+00E9, far below the surrogate range,
so `compareTo` and `Utf8Order` agree on every pair the generator can draw. `Utf8OrderSuite:29–33`
tests the U+FFFD / U+10000 pair directly, but only on `Utf8Order` itself, not on the path
ordering that wires it in.

**Reproduced through the real CLI** — two untracked files, `U+FFFD.txt` and `U+10000.txt`,
`snap status` output in hex:

```
shipped build:  A ef bf bd .txt   then   A f0 90 80 80 .txt    (UTF-8 byte order — correct, SPEC §2)
M18 build:      A f0 90 80 80 .txt   then   A ef bf bd .txt    (UTF-16 order — wrong)
```

This ordering is load-bearing well beyond `status`: tree iteration, warning sort order (§6.4),
`diff` path order, `changes` sortedness validation (§4.2), and `patches` sortedness (§4.5 step 2).

**Suggested fix (one line):** add a `U+E000..U+FFFF` segment (e.g. `"�"`) to
`CoreGens.segmentPool`. That single change makes `SnapPathSuite:128` falsify M18, and raises
coverage for every other property that draws paths.

### Nit 1 — thin margin on the namespace `C'` rule (M20)

`Replay.scala:430`. Removing the "`C'` is `C` with every path `P` authored as a deletion removed"
step is caught by exactly **one** test in `ConcurrentReplaySuite`. The rule is explicit spec text
and deserves more than a single fixture's margin — a second case (patch deletes `a` and creates
`a/b` in one commit, against a concurrent tree that still holds `a`) would harden it.

### Nit 2 — `ConcurrentReplayLawsSuite`'s cursor-collision oracle has a blind spot (M02)

The T18 oracle property (`ConcurrentReplayLawsSuite:366–399`) is explicitly about *concurrent
inserts at one cursor appearing in canonical integration order* — yet it stayed **green** under
M02, which demotes the `Q insert` priority row below the `P insert` row, i.e. inverts exactly
that ordering. M02 was caught only by `OtSuite`'s unit rows and the replay fixtures. The property
asserts `replIdx == insIdx + 1` for a replace-vs-insert collision; it does not cover the
insert-vs-insert collision that the priority row actually decides. Adding a second concurrent
*pure* insert at the same cursor would close it.

---

## Assessment: the property suites' real falsifying power

**`snap.core.ConcurrentReplayLawsSuite` — genuinely falsifying, and its scaladoc's self-criticism
is accurate.** Three of its five properties (permutation-invariance, idempotence, prefix-freeness)
are **metamorphic**: both sides of the comparison run the same code on the same input, so a
*consistently wrong but deterministic* implementation survives them. The suite says this at
lines 277–288 and I confirmed it empirically — M08 (Snap-order tie-break reversed) and M11/M12
passed every metamorphic property. The suite's answer, the T18 cursor-collision **oracle**
(lines 366–399), compares against an independently derived expected token sequence and **does
real work**: it was the only property to catch M01 (diff tie-break) and it caught M04 (OT row 4),
the exact pair that four prior reviews missed by reading. **T18's fix holds — verified, not
assumed.** Its residual blind spot is nit 2.

Its `"generator coverage"` test (lines 410–481) is not decorative: it asserts concurrency,
delete-op coverage, and all five warning reasons over a fixed seed, so the invariance properties
cannot silently go vacuous. Verified as claimed.

**`snap.props.ConvergencePropsSuite` — honest about being weaker than it looks, and the honesty is
warranted.** Its scaladoc (lines 25–36) concedes that `Repo.validate` requires a pre-sorted patch
vector, so after `sortedForValidate` the recombined input is byte-identical regardless of how
replicas were split — raw JVM collection order genuinely cannot leak through the public API. What
it really tests is the *recombination algebra* (join/union completeness, commutativity and
associativity over 2–3 shards) and end-to-end determinism of `validateFully`. That claim is
accurate and complete. Empirically it earns its keep: it caught M13 and M14 (memo poisoning) and
four OT rows. It cannot catch a consistently-wrong rule — M11, M12 and M18 all passed it.

**`snap.cli.CommandsMergeConvergenceSuite`** — same character at the command level; caught M03,
M05, M06, M07, M13, M14, M17.

**What none of them can catch, stated plainly:** any rule that is deterministic and uniformly
applied but *wrong per spec*. The suites' only defence against that class is (a) the T18 oracle,
(b) the golden fixtures in `ConcurrentReplayFixturesSuite` / `ConcurrentReplaySuite` /
`OtSuite` / `DiffSuite`, and (c) the provided YAML suite. All three of my uncaught mutations fall
in this class, and all three are gaps in (b) — the fixture layer, not the property layer. The
property suites are doing what property suites can do; the fixtures are where the three holes are.

---

## Replay memoization (T23) — the newest change, attacked directly

The brief flagged `Replay.scala:294`'s `memo1.updated(newProgress, nextCanonical)` as the
least-reviewed code in the engine. I attacked it three ways and it holds.

**1. Proof sketch (traced through the code, CONFIRMED).** Storing the outer loop's canonical tree
under `newProgress` is sound because:

- `progress` is always the join of integrated results, and readiness (`contained`,
  `Replay.scala:329–330`) forbids a patch from raising any component except its own dot — so by
  induction the integrated set is **exactly** `select(valid, progress)`.
- `newProgress <= version` componentwise (each selected patch's base is contained in `version` by
  `checkKnown`), so `select(valid, newProgress)` never reaches outside the outer selection.
- At step *j* the outer loop picks `ready.min(selOrdering)` over a **superset** of what a fresh
  replay of `select(valid, newProgress)` would see; since that minimum provably lies inside the
  subset, it is also the subset's minimum. By induction the fresh replay makes the identical
  choices in the identical order, hence produces the identical tree.
- The error path is unaffected too: if `newProgress` equals some patch's declared base `V`, then
  `select(valid, V)` is the integrated set, which is base-closed — so the `CyclicHistory` a
  non-self-contained base would have raised (the `T07-review.md` nit 1 hazard) cannot be
  suppressed by a hit.

**2. Mutation (M13, M14) — the memo key and value both matter and both are caught.** Keying by
`next.result` instead of `newProgress` (the natural mistake: it looks equivalent and is not, as
soon as concurrent patches have been integrated) fails 13 tests across four suites. Good.

**3. Self-checking instrumentation (INSTR) — the strongest evidence.** I rewrote
`materializeMemo`'s hit branch to recompute the value from a fresh, memo-free sub-replay and
abort on mismatch, with a re-entrancy guard so the verification does not recurse. Under that
build: **693 tests → 692 pass, the single failure being `ReplaySuite`'s own 5000 ms performance
guard** (it took 7068 ms, exactly as expected once Θ(n²) is restored — which also proves the
instrumentation was firing heavily), and the **provided suite 28/28 with zero
`MEMO INCONSISTENT` output**. Across every history either suite generates — hundreds of
multi-author, namespace-colliding, concurrent graphs — the cached tree always equalled what a
fresh sub-replay produces.

I could not construct an input where the memo diverges, and the structural argument says none
exists. **No finding against T23's memoization.**

---

## What I checked directly and found correct

**Determinism smells — clean.**

- **No wall-clock, no randomness, no environment read anywhere in `snap/core/` or `snap/json/`.**
  Greps for `currentTimeMillis`, `nanoTime`, `Instant.now`, `Random`, `getenv`, `sys.env`,
  `getProperty`, `Locale`, `TimeZone`, `toLowerCase`, `toUpperCase` return nothing but one
  comment in `Writer.scala:67`.
- The only `System.getProperty` / `sys.env` in the whole implementation is `Env.scala:85–86`, the
  single composition root that captures them into an `Env` value threaded downward — exactly
  CLAUDE.md ground rule 3's "thread time and configuration in as values". `Cli.scala` reads only
  the passed-in `Env`.
- **No unordered iteration reaches output.** Every `.toSet` on contract data
  (`Replay.scala:99`, `Repo.scala:89`, `Path.scala:87`) is probed by membership only, never
  iterated. Both `.toMap` sites (`CommandsLog.scala:30–34`, `Cli.scala:113–117`) are key-probed
  dispatch/lookup tables. `Tree` is a `TreeMap` keyed by `SnapPath.ordering`, so every iterator it
  exposes is sorted by construction; warnings live in a `SortedSet` with an explicit total order;
  `Version` is an id-sorted `Vector` with zero entries unrepresentable.
- **Filesystem non-determinism is neutralised at both scan and install:** directory listings are
  explicitly `.sorted(Utf8Order)` (`WorkTree.scala:103`, `Materialize.scala:157`) rather than
  trusted in OS order.
- **No `String.compareTo` on contract-relevant data.** `ContributorId.ordering` and
  `SnapPath.ordering` both route through `Utf8Order`; ids are constrained to printable ASCII
  (`Ids.scala:81–82`) where the two coincide anyway, and the path case is finding 3's coverage
  gap — the code itself is right.

**Byte-content equality is content-based everywhere it matters.** `ByteArrays` is the single
implementation; `Change.Put` (`Patch.scala:28–34`) and `Tree` (`Tree.scala:49–61`) override
`equals`/`hashCode` to use it, so `IArray[Byte]` reference equality never leaks into a decision.
This makes `unionPatches`' `a == b` dedupe (`CommandsMerge.scala:131`) genuinely structural —
M17 confirms the distinction is tested.

**Merge symmetry and convergence.** `unionPatches` is a linear merge over two canonically sorted
vectors with structural per-dot dedupe and leftmost-collision reporting; `join` is componentwise
`max`; `Replay.materialize` is a pure function of `(patch set, version)`. Direction-independence
and association-order independence are additionally pinned by the provided suite's *"concurrent
creates choose the canonical later value independent of merge direction"* and *"three-way text
history converges across different merge association orders"*, both passing, and by
`CommandsMergeConvergenceSuite` — which M03/M05/M06/M07/M13/M14/M17 confirm is load-bearing, not
decorative.

**Stack safety.** `sbt slowTest` (2 probes: deep linear and deep concurrent histories) passes.
The `@tailrec` ready-loop and the documented O(1)-stack property of `materializeMemo`'s non-self
call check out by reading.

**The OT table and the diff walk are transcribed verbatim and every row is load-bearing.** All
six OT rows plus the priority rule and the count-splitting `remainder` were mutated individually;
every one is caught, most by several suites at once.

---

## Recommendation

Ship, with the three Minors fixed first — all three are **additive test work plus one generator
line**, none touch production code:

1. Fixture: concurrent `put`-create vs text-create of one path → assert `later-create-wins`
   (finding 1).
2. Fixture: concurrent edit vs delete-and-recreate-as-directory → assert
   `s: delete-wins` and tree `{s/x}` (finding 2), **and** confirm D27's `S`-membership reading
   with the user, per CLAUDE.md ground rule 1.
3. Add a `U+E000..U+FFFF` segment to `CoreGens.segmentPool` (finding 3) — one line, and it
   activates an existing property.

Nits 1 and 2 are safe to defer with a recorded rationale.

The engine itself is solid. I tried 21 ways to break it and the three that got through were
holes in the *tests*, not in the code.

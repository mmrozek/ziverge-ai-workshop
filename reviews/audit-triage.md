# Post-completion audit — combined triage

Three independent Opus lenses audited the finished tree (`232ecfe`, 28/28 provided tests,
693 project tests), report-only, with a Major blocking the release per the user's terms.

| Lens | Verdict | Findings |
|---|---|---|
| 1 — spec conformance (`audit-1-spec-conformance.md`) | **block** → resolved | 1 Major, 2 Minor, 2 Nit |
| 2 — adversarial determinism & merge (`audit-2-determinism.md`) | release-with-fixes | 0 Major, 3 Minor, 2 Nit |
| 3 — CLI / HTTP / phase 4 (`audit-3-cli-and-phase4.md`) | release-with-fixes | 0 Major, 3 Minor, 7 Nit |

## Decision

**The Major was fixed** and committed as `ff1a335`: `merge` and `revert` deleted every
empty directory under the repository root rather than only the ones they emptied, so a
no-op merge silently removed a user's untracked empty directories — against §10's "removes
**newly** empty directories" and §7.8's "changes nothing".

**Every Minor and Nit is deferred, not fixed** (user, 2026-09-05: "won't address minors").
This is within the audit terms agreed beforehand — a Major blocks, Minors and Nits are
triaged and may be deferred with a recorded rationale. The rationale here is schedule: the
contract is met at 28/28 and none of the remaining findings can produce a wrong merge
result. They are recorded below so the decision is visible rather than implicit, and none
of them was rejected on merit.

## Deferred — real, unfixed, listed by what a future maintainer should know

| Source | Severity | Item | Why it matters if revisited |
|---|---|---|---|
| L2 #3 | Minor | Substituting `String.compareTo` for `Utf8Order` in `SnapPath.ordering` passes every test **including `SnapPathSuite`'s property written to catch exactly that**, because `CoreGens.segmentPool` lacks any character in U+E000..U+FFFF — the only range where UTF-16 and UTF-8 order diverge | The highest-value item on this list: a guard that has silently stopped guarding. One line in the generator re-arms it. Until then a refactor could break path ordering with every test green. |
| L3 #2 | Minor | D15's body cap bounds neither memory nor time: `ofByteArray()` accumulates without limit, the cap is checked only after completion, and the resulting OOM kills the scheduler thread `orTimeout` depends on. Measured still alive at 5m19s | A hostile or broken remote hangs the CLI indefinitely. The code's own comment claims to cover "BOTH" hang shapes; this is a third. |
| L3 #3 | Minor | One non-reading client wedges `--serve` for every other client — single-threaded executor plus a blocking socket write at `Server.scala:170` | Denial of service against a local server. The comment at `:76–80` states a false premise: writing to a socket blocks when the peer stops reading. |
| L3 #1 | Minor | The JDK's `HttpClient` retries an idempotent request once, so a connection dropped with no response bytes produces a **second GET**, against §9's "one GET" | Read-only and idempotent, so it cannot corrupt a merge, but it is a literal deviation from the spec wording. Unresolved whether the retry can be disabled without hand-rolling HTTP. |
| L1 #2 | Minor | `snap diff --repo /path` reports `invalid version: --repo` instead of `diff`'s usage channel | Cosmetic mis-routing of one diagnostic; the one place D28 is not applied. |
| L1 #3 | Minor | **D28 is over-broad.** `snap commit "--wip"` is unreachable although §4.2 permits such a message and `commit`'s operand is mandatory and positional | My own over-correction: I upgraded this from a Nit to a uniform behavior change during the phase-2 triage, further than the contract supports. The contract only pins the *optional*-operand case (test 24's `init --unknown` plus its `path_not_exists` assertion). **D28 stands as implemented and is knowingly wider than the spec requires.** |
| L2 #1 | Minor | §6.4's rule-4 vs rule-5 precedence is unpinned: swapping them passes 693/693 and 28/28. A concurrent binary-`put` create vs text create yields the same bytes but a different warning, and the warning set is contract (§6.5) | Coverage gap, not a defect — the shipped build emits the correct `later-create-wins`. |
| L2 nit | Nit | T18's cursor oracle stayed green under the mutation inverting OT's `Q insert` priority row: it covers replace-vs-insert but not insert-vs-insert | Same shape as the gap T18's review originally found. |
| L1, L3 | Nit ×11 | Listed in their respective reports | — |

## Accepted — recorded, no code change needed

| Source | Item | Decision |
|---|---|---|
| L2 #2 | Namespace pre-pass `S` membership (D27): dropping the creates-only filter passes every test **and changes merged bytes**, so the suite does not pin the reading. The lens notes ground rule 1 requires core merge semantics to go to the user | Already satisfied: D27 was escalated and **confirmed by the user on 2026-09-05** before T16 was committed, with the discriminating scenario shown. The lens independently reached the same reading. Recorded because the lens could not see that exchange. |

## Rulings the audit was asked for

- **D27 — upheld** by lens 1 on the argument that the wide reading makes §6.4 rule 3
  unreachable in exactly the case it names, and independently reproduced by lens 2.
- **D28 — partially upheld**, and knowingly left wider than the spec requires (above).
- **T18's oracle fix — verified, not assumed.** Lens 2 re-ran the two mutations that
  originally slipped through every property suite; both now fail `ConcurrentReplayLawsSuite`.
- **T23's memoization — sound.** Lens 2 instrumented `materializeMemo` so every cache hit
  re-verified itself against a fresh memo-free sub-replay: zero inconsistencies across 693
  project and 28 provided tests, the only failure being the performance guard itself, which
  proves the check was firing.

## Method note

Lens 2 ran 21 mutations of the comparison/merge/tie-break core: **18 caught, 3 uncaught**,
and for every uncaught one it built the divergent input and ran it through the real CLI on
both builds — the shipped build gave the spec-correct answer each time, which is what makes
those coverage gaps rather than defects. All three gaps are in the fixture layer, not the
property layer. Worth keeping: metamorphic properties compare a computation against itself
and cannot detect a consistently-wrong implementation, so oracles and goldens are where
correctness actually gets pinned.

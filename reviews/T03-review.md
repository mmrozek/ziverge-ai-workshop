# T03 pre-commit review — version algebra (compare, join, snap order)

- **Date / scope:** 2026-09-04, uncommitted working tree on `main` (HEAD 9b7cbd0 = T01+T02):
  untracked `snap/scala/src/main/scala/snap/core/{Ids,Version}.scala`, five suites under
  `snap/scala/src/test/scala/snap/core/`, modified `tasks/T03-version-algebra.md`.
- **Reviewed against:** SPEC §3.1–3.4 (R28–R36), DESIGN §3 + D12/D17/D23 + §10 gotchas 1/3,
  SPEC-NOTES §4.1–4.2, task acceptance criteria.
- **Independent verification (reviewer-executed, first compile of the T02+T03 union):**
  - `cd snap/scala && sbt -batch scalafmtCheckAll "scalafixAll --check" test` →
    scalafmt **pass** (7 main + 8 test sources), scalafix **pass**, tests
    **Passed: Total 107, Failed 0, Errors 0** (T03: ContributorIdSuite 7,
    VersionAlgebraSuite 7, VersionTextSuite 16, VersionLawsSuite 13 properties,
    Utf8OrderSuite 4 = 47; T02 json suites 59; T01 smoke 1). Zero compiler warnings
    under `-Wunused:all`. The union compiles cleanly — no clash with T02's
    `snap/core/Errors.scala` or `snap/json/`.
  - Determinism re-runs: `testOnly snap.core.VersionLawsSuite snap.core.Utf8OrderSuite`
    executed twice more with fresh ScalaCheck seeds → 17/17 both runs.
  - 22 reviewer-authored adversarial probes compiled against the built classes and
    executed (details under "Evidence" below) — all behaved per spec.
  - Provided YAML suite (`./snap/verify --lang scala`) not run: it drives the CLI, which
    does not exist yet mid-phase-1; it is the phase-gate check, not this task's.

## Findings

### Spec compliance — no Critical/Major findings

Verified directly (see Evidence): four-outcome comparison with absent=0 (R33/R35),
componentwise join (R34), snap-order direction and totality (R36), R31 parse strictness
per error class, R28–R30 id/revision validation, canonical print∘parse identity.

### Correctness & determinism — no Critical/Major findings

- No `var`, no wall-clock/env/`Random`, no `String.compareTo`, no throw/null in the
  module (grep CONFIRMED; also mechanically enforced by scalafix `DisableSyntax`
  noVars/noThrows/noNulls in `snap/scala/.scalafix.conf`). The only `Map` iteration
  (`Version.fromMap`, Version.scala:131) is immediately sorted by the total
  `ContributorId.ordering` over distinct keys — deterministic. `canonicalText`
  (Version.scala:84–86) uses `Long.toString` and interpolation only — locale-free.

### Design drift

**#1 [Minor] snap/scala/src/main/scala/snap/core/Ids.scala:42 (also 66–71),
Version.scala:133,143,166,174–179,191–192 — error channel deviates from DESIGN D4/D5.**
T03 factories return `Either[String, A]` with diagnostic phrases built inline, while
DESIGN D4/D5 (and T02's committed `Errors.scala`) require `Either[SnapError, A]` with
all diagnostic text in the `Messages` catalog. The test-pinned substrings
`positive safe integer` and `canonical` (test 23) now live outside the catalog.
Concrete failure scenario: T06 wires the CLI to the catalog, adds its own
`positive safe integer` message there, and a later reword of `Revision.check`'s
`Left` (Ids.scala:42) silently diverges from the catalog copy — two sources of pinned
wording. Mitigations already in place: the task file's Notes record this as a
deliberate integration seam (T03 was built in a worktree without T02), and
VersionTextSuite:124–132 pins both substrings locally. CONFIRMED (code read; no
behavioral defect today). Needs a tracked migration into `SnapError`/`Messages` at
integration (T06 or a `T03-fix`), not a pre-commit change.

**#2 [Minor] snap/scala/src/main/scala/snap/core/Ids.scala:26 — `Utf8Order` diverges
from encoded-byte order for strings containing unpaired surrogates; the pinning
property deliberately cannot see it.** `codePointAt` on a lone surrogate returns the
surrogate value (0xD800–0xDFFF), so e.g. `compare("\uD800", "A")` > 0, while
`"\uD800".getBytes(UTF_8)` replaces the surrogate with `?` (0x3F), giving byte order
< 0. Utf8OrderSuite's generator (Utf8OrderSuite:36–42) excludes surrogates, so the
"sign equals unsigned byte-compare" property is silent on exactly this class — yet the
suite is declared "the shared semantic definition" for T04's path comparator, and JSON
`\uD800` escapes in repository files can produce lone-surrogate path strings. Concrete
failure scenario: a crafted `repository.json` contains a path with an unpaired
surrogate escape; path sort order (and hence status/replay output) depends on which
"UTF-8 byte order" the comparator implements, and no test pins the choice. No T03
behavior is wrong — contributor ids are validated ASCII, so the divergence is
unreachable here. Divergence mechanism CONFIRMED by trace (Java UTF-8 encoder
replacement semantics); reachability via T04 paths PLAUSIBLE, unverified. Forward
requirement for T04/T06: reject lone surrogates at path validation or explicitly pin
the ordering choice.

**#3 [Nit] snap/scala/src/main/scala/snap/core/Version.scala:91–92,154–160 — R32 JSON
codec landed as a plain-data seam (`toPairs`/`fromPairs`), not the `snap/json/` AST
codec the task scope line names.** Orchestrator-directed (worktree predates T02) and
recorded in the task Notes; all R32 validation (ids, bounds, canonical order,
duplicates, pinned reason substrings) is present and tested at the seam. Residual risk:
R32 has no end-to-end AST→`Version` coverage until the integration task wires it —
the phase gate should confirm that wiring plus a test exists before phase 1 closes.
CONFIRMED as a recorded scope deviation, not a defect.

### Test coverage — no findings

Every R31 error class has a directed test (VersionTextSuite:42–108) including the exact
inputs tests 19/25 will use; round-trip and law properties cover R33–R36; the 254/255
byte boundary is tested (ContributorIdSuite:28–31) — notable because SPEC-NOTES §2.1
lists R28's byte limit as having no provided-suite coverage. The snap-order directed
test derives the expected result from the spec's wording (union {alice@x, bob@x},
counters 0 vs 1), independent of the implementation — it is not merely
implementation-echoing.

### Evidence (what was traced/executed)

- **Snap-order direction (gotcha 3), verified three independent ways:** (a) hand
  derivation from SPEC §3.4's exact wording — sorted union of ids, lexicographic
  compare of counters, absent=0: `(bob@x->1)` → (0,1), `(alice@x->1)` → (1,0), first
  unequal counter 0<1 → bob earlier; (b) code trace of `snapOrdering`
  (Version.scala:104–124): id-merge walk where the side *lacking* the first union id
  sorts earlier (lines 113–114, 119–120 — comments and signs agree with (a));
  (c) executed probe: `snapOrdering.compare(parse("(bob@x->1)"), parse("(alice@x->1)"))
  = -1`, and `compare((a@x->1,b@x->9), (a@x->2)) = -1` (first unequal id decides
  despite the larger later counter). CONFIRMED.
- **Snap order totality/consistency:** antisymmetry, transitivity, zero-iff-equal, and
  extension of causal order are property-tested (VersionLawsSuite:98–119) over a
  generator with a small id pool (collisions frequent, Revision.Max included); re-run
  twice with fresh seeds. Zero-iff-equal holds because the representation is canonical
  by construction (sorted, zero-free, private constructor — D17). CONFIRMED.
- **compareCausal (R33/R35):** traced the two-pointer merge (Version.scala:38–57) —
  ids present on one side contribute a strict inequality in the correct direction
  (absent=0, lines 46–47/52–53); early `Concurrent` exit is sound (flags are
  monotone). Independently property-checked against a brute-force componentwise oracle
  over the id union (VersionLawsSuite:82–94). CONFIRMED.
- **Join (R34):** sorted zero-free merge with `max`; commutativity, associativity,
  idempotence, identity, upper-bound properties green; probe confirmed
  `(a@x->2,b@x->1) ⊔ (a@x->1,c@x->3) = (a@x->2,b@x->1,c@x->3)` both directions.
  CONFIRMED.
- **R31 strictness — attempted slip-throughs, all rejected (executed probes):**
  overflow by one `9007199254740992`; 16-digit `9999999999999999` (> Max but ≤ 16
  digits — caught by `Revision.check`, not the digit-count guard); zero-padded
  16-digit `0000000000000001` (leading zero); `01`/`0`; NUL embedded in id, in
  revision, and after the closing paren; Devanagari digit `१` (only ASCII `0-9`
  accepted — no `Character.isDigit` locale trap); `+1`; U+2192 arrow instead of `->`;
  `(a@x->1,)`, `(,)`; 255-byte id rejected / 254-byte accepted. Trailing-garbage and
  whitespace classes additionally covered by VersionTextSuite:71–108. No overflow risk
  in `toLong`: length ≤ 16 digits bounds the value below 10^16 « Long.MaxValue.
  CONFIRMED.
- **First-`->`-is-separator claim (task Notes):** proved — a validated id cannot
  contain `->`, and the character pair straddling the id/separator boundary is
  (`id.last`, `'-'`), which can never spell `->`; so the first occurrence is exactly
  the printed separator. Probe: `(a@x-->1)` parses to id `a@x-` and round-trips.
  CONFIRMED.
- **254-byte reasoning (task Notes):** sound. UTF-8 bytes-per-char ≥ UTF-16 units for
  every char (BMP 1 unit → 1–3 bytes; supplementary 2 units → 4 bytes), so the
  `length > 254` pre-check only rejects strings that genuinely exceed 254 bytes; any
  ≤ 254-unit string that exceeds 254 bytes must contain non-ASCII and is rejected by
  the 0x21–0x7E scan; on the accept path units == bytes exactly. Boundary executed:
  254 accepted, 255 rejected. CONFIRMED.
- **ContributorId validation vs SPEC §3.1:** exactly one `@` with nonempty sides
  (`head`/`last` safe — the `@`-count check rejects the empty string first), printable
  ASCII 0x21–0x7E (excludes control 0x00–0x1F, DEL per D12, all whitespace,
  non-ASCII), explicit `,`/`(`/`)` and `->` rejection, spelling preserved (R29 —
  asserted in ContributorIdSuite:10). CONFIRMED.
- **Utf8Order on well-formed strings:** property "sign equals unsigned byte-compare of
  UTF-8 encodings" over full-Unicode strings (surrogate-free generator) green on three
  seed runs; directed test pins the UTF-16-vs-UTF-8 divergence case (U+FFFD vs
  U+10000) and test 25's `nested/file < z < é < 😀` order. CONFIRMED (see #2 for the
  lone-surrogate boundary).
- **Insertion-order independence:** `fromMap` and incremental `updated` builds agree
  with the parsed value and print byte-identically under permuted orders
  (VersionLawsSuite:137–151). CONFIRMED.
- **Out-of-scope files untouched:** working-tree delta is exactly the T03 files, the
  T03 task file's Notes/verification appendix, and the orchestrator's one-line
  status flip in `tasks/TASKS.md` (in-progress → review). Contract paths unmodified.
  CONFIRMED.

## Status

**Verdict: approve.** 0 Critical, 0 Major, 2 Minor, 1 Nit. No pre-commit code change
required: #1 and #3 are recorded integration seams whose completion belongs to
T06/integration (the phase-1 gate must verify the `SnapError` migration and the R32
AST-codec wiring actually happen), #2 is a forward requirement on T04/T06 path
handling. Lint gate green, 107/107 project tests green on the first T02+T03 union
build, property suites stable across three seed runs, all acceptance criteria verified
independently. Safe to commit as `T03: <summary>` together with this report.

## Triage (orchestrator, 2026-09-04)

| # | Severity | Decision |
|---|---|---|
| 1 | Minor | **Defer to T06** — the codec task owns migrating the `Either[String, A]` seams into `SnapError`/`Messages`; pinned substrings (`positive safe integer`, `canonical`) move into the catalog there. Pointer added to T06's task-file notes. |
| 2 | Minor | **Accepted as boundary-handled** — T04's `SnapPath.parse` rejects unpaired surrogates and ids are ASCII-validated, so no lone-surrogate string reaches `Utf8Order` from the domain; T06 must route every JSON-decoded path through `SnapPath.parse` (its R23 validation already requires that). Re-check at the phase-1 review. |
| 3 | Nit | **Defer to T06** — R32 AST↔`Version` wiring is T06's by plan; the `toPairs`/`fromPairs` seam is the intended handoff. |

No pre-commit code changes; verdict **approve** stands. Code and this report commit together.

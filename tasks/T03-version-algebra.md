# T03 — Version algebra: compare, join, snap order (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T01
- **Risk:** **core** (clock comparison and the snap-order tie-break — formal pre-commit
  review, saved as `reviews/T03-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/`: `ContributorId` (validating factory, R28–R29, D12), revision bounds (R30),
`Version` (D17 — zero entries unrepresentable, id-sorted by `Utf8Order`), four-outcome
causal comparison (R33, R35), componentwise join (R34), Snap total order (R36), canonical
text parse/print (R31), JSON pair-array codec (R32). DESIGN §3; gotcha 3.

## Scope
`snap/scala/src/main/scala/snap/core/{Ids,Version}.scala`, version codec in
`snap/json/`, tests in `snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [x] Canonical parser rejects every R31 error class (duplicate ids, explicit/leading
      zeroes, overflow, invalid ids, whitespace, wrong order) and print∘parse = identity
      on valid input — unit tests per class + round-trip property.
- [x] Property tests: join is commutative/associative/idempotent; compare returns
      `Equal` iff maps equal, `Before`/`After` antisymmetric, `Concurrent` symmetric;
      snap order is a total order that extends causal order (if `V < W` causally then
      `V` precedes `W` in snap order).
- [x] Directed unit test: `(bob@x->1)` precedes `(alice@x->1)` in snap order (gotcha 3 —
      lower counter at the first differing sorted id wins).
- [x] Negative constraints: no `var`, no wall-clock/env access, no unordered-collection
      iteration in this module; comparison of ids/paths uses `Utf8Order` only, never
      `String.compareTo` semantics by accident.

## Notes / decisions

- **Error seam (T02 not present in this worktree):** did not create
  `snap/core/Errors.scala` (T02 owns the `SnapError` ADT). All validating factories
  return `Either[String, A]` where the `Left` is a lowercase reason phrase; the
  user-facing wording (`snap: invalid version: <arg>`, `snap: invalid contributor
  id: <arg>`) is assembled by the CLI/error-catalog layer at integration. Reason
  phrases for pair-array validation deliberately contain the test-23-pinned
  substrings — `positive safe integer` (bad revision) and `canonical` (noncanonical
  order) — so T06 can reuse them directly.
- **`Utf8Order` duplication (T04 coordination):** defined in `Ids.scala` as
  `snap.core.Utf8Order` — unsigned UTF-8 byte order implemented as code-point
  comparison (equivalent, no allocation; never `String.compareTo`, which is UTF-16
  code-unit order — DESIGN gotcha 1). T04 defines the same comparator; identical
  semantics are pinned by a property test (`Utf8OrderSuite`: sign equals unsigned
  byte-compare of the UTF-8 encodings over full-Unicode strings). Integration
  dedupes to a single definition.
- **JSON codec scope deviation (orchestrator-directed):** the task scope line names
  "version codec in `snap/json/`", but `snap/json/` is created by T02 (parallel
  worktree). Implemented the R32 form as a plain-data seam instead:
  `Version.toPairs: Vector[(String, Long)]` / `Version.fromPairs` (validates ids,
  bounds, canonical order, duplicates). AST codec wiring happens at integration.
- **Strictness choices (non-core ambiguity, spec-consistent reading):**
  `Version.fromMap` rejects zero/out-of-bounds counters rather than silently
  dropping zeroes — zero entries stay unrepresentable (D17) and bugs cannot hide
  behind normalization. The increment rule (R46) itself is T06's; the seam provided
  here is `Version.updated(id, rev)` which enforces R30 bounds.
- **Parse grammar unambiguity:** the first `->` occurrence in an entry is the
  id/revision separator. Safe because ids cannot contain `->` and revisions are bare
  digits, so print∘parse is exact even for ids ending in `-` (directed test on
  `(a@x-->1)`, id `a@x-`).
- **Naming per DESIGN §3:** comparison outcome enum is `Ord`
  (`Equal/Before/After/Concurrent`), method `Version.compareCausal`; snap total
  order exposed as explicit `Version.snapOrdering: Ordering[Version]` (no givens —
  orderings are always passed explicitly).
- **ContributorId byte-length check:** `value.length > 254` (UTF-16 units) is checked
  before the ASCII scan; sound because UTF-8 bytes >= UTF-16 units always, and after
  the ASCII check units == bytes exactly (comment in code).
- **Worktree mode:** `tasks/TASKS.md` / `CURRENT.md` / `AGENTS-STATUS.md` deliberately
  not touched (orchestrator owns the board for parallel worktrees); no commit made.
- **Verification (2026-09-04, clean rebuild):** `sbt test` — 48 passed, 0 failed
  (47 new across 5 suites + 1 T01 smoke; suites: ContributorIdSuite 7,
  VersionTextSuite 16, VersionAlgebraSuite 7, VersionLawsSuite 13 properties,
  Utf8OrderSuite 4); `sbt scalafmtCheckAll` green; `sbt "scalafixAll --check"`
  green; zero compiler warnings under `-Wunused:all`. No `scalafix:ok`
  suppressions used.

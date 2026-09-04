# T15 — OT transform (3 SP)

- **Phase:** 3 — Merge & OT
- **Depends on:** T05 (parallel-safe — touches only `core/Ot.scala`; may run during phase 2)
- **Risk:** **core** (merge tie-break semantics — formal pre-commit review, saved as
  `reviews/T15-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/Ot.scala`: transform incoming edit `P` through aggregate context edit `Q`
per the spec's 6-row table, verbatim (R71–R72, DESIGN §5): `Q insert` row has priority
(emits `retain(tokenCount)`), deletion consumes only base tokens, counts split as
needed, both streams consumed fully, trailing insertions processed, output coalesced.
Pure function `transform(p: EditScript, q: EditScript): Either[SnapError, EditScript]`.

## Scope
`snap/scala/src/main/scala/snap/core/Ot.scala`, tests in
`snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [x] Directed unit tests for all six table rows plus: Q-insert-before-P-delete
      (inserted text survives deletion), retain/delete count splitting across op
      boundaries, trailing P insert after Q exhausted, trailing Q insert after P
      exhausted, concurrent inserts at one cursor (Q's lands first — priority row).
- [x] Property tests: for generated base `A` and scripts `p` (A→B), `q` (A→C):
      `apply(transform(p,q), apply(q,A))` succeeds (transformed P applies to Q's
      result); transform errors iff `p` and `q` consume different base token counts;
      output has no adjacent same-kind ops.
- [x] The convergence property that test 18 relies on:
      `apply(transform(p,q), C) == apply(transform'(…))` — encoded as: transforming the
      spec's worked pairs reproduces the merged bytes pinned by tests 09/18/22 (lifted
      as unit fixtures).
- [x] Negative constraints: no `var` (fold/recursion), no wall-clock, output depends
      only on the two scripts.

## Notes / decisions

- **Shape:** `Ot.transform(p, q): Either[SnapError, EditScript]` — one tail-recursive
  row dispatch over the two op streams; a partially consumed retain/delete is re-headed
  with its remaining count (the table's "splitting counts as needed"); output coalesced
  at the accumulator head (local `push` — `Diff`'s coalescing is private to its walk, so
  not shared). No `var`, no throw, no clock/env access.
- **Row priority, verbatim:** the `Q insert` row applies whenever Q's next unconsumed op
  is an insert, regardless of P's next op (including P exhausted or P's next being an
  insert/delete); the `P insert` row applies when Q's next op is not an insert. Directed
  tests pin both nuances (concurrent inserts at one cursor → Q's text first; Q-insert
  before P-delete → inserted text survives).
- **New diagnostic (untested wording, D5):** `SnapError.OtBaseMismatch` /
  `Messages.otBaseMismatch` = `edit scripts consume different base token counts`, added
  in `// T15 additions` blocks at the end of `SnapError`/`Messages` in `Errors.scala`
  (declared scope was `Ot.scala` + tests; `Errors.scala` is touched because D4/D5 place
  every error case and message string there — additive only, end-of-file blocks for
  clean merging with in-flight tasks). Detected when one stream ends while the other
  still holds a retain/delete (spec: "No unmatched retain or delete can remain") — an
  internal invariant for replay, which derives both scripts from one base tree.
- **Precondition, recorded:** `transform` does not re-run structural validation (R54–
  R55) on its inputs; replay guarantees them (`q` is a `Diff.diff` output, `p` comes
  from a validated patch). Documented in the scaladoc.
- **Non-canonical merged sequences (finding for T16, not an ambiguity in T15):** the
  table's output applied to `C` can yield a token sequence with a LF-less token in
  non-final position — e.g. concurrent LF-less appends to one file: base empty,
  P=`insert["x"]`, Q=`insert["y"]` → transformed `retain 1, insert["x"]` applied to
  `["y"]` gives `["y","x"]`, i.e. merged bytes `yx`. R57's canonical-result rule binds
  *patch* scripts (§4.4 validity); §6.3 imposes no such constraint on the transformed
  application, and §6.5 both requires merge to produce bytes for every valid history and
  disclaims desirable merged text — so the merged bytes are the plain token
  concatenation. Consequence for **T16**: replay must apply the transformed script
  *without* `EditScript.applyTo`'s canonical-result check (exact consumption still
  enforced). Pinned by a directed test; the general property tests use a structural
  applier, plus a stricter property over all-LF-terminated sequences where the full
  canonical `applyTo` must succeed.
- **Fixtures lifted:** test 09 (append/append, canonical order `base/right/left`), test
  22 all four sub-cases (dd, split, rd, survive), test 18 modeled as two aggregate
  transforms (`Q = diff(base, canonical-so-far)` per integrated patch, R72 — never
  chained per historical patch) reproducing the pinned `B\nA\nend\n`. Each fixture test
  cites its YAML source and integration-order reasoning (snap order: bob/context before
  alice/incoming).
- **Verification (2026-09-04):** `sbt test` → 342 passed, 0 failed (OtSuite: 29 — 16
  directed, 6 fixtures, 7 properties at 300 samples each); `sbt scalafmtCheckAll` and
  `sbt "scalafixAll --check"` both pass. Provided tests 09/18/22 need `merge`
  (T16/T17) and are phase-3 gates; they are represented here as the lifted fixtures.
- **Amendment (T15 review finding 1, orchestrator):** the non-canonical merged token
  list is TRANSIENT — T16 renders merged output to bytes and re-tokenizes for all
  downstream use (otherwise a patch authored on the merged tree, whose script consumes
  `tokenize(bytes)`, would spuriously fail validation). See `reviews/T15-review.md` #1.

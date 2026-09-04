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
- [ ] Directed unit tests for all six table rows plus: Q-insert-before-P-delete
      (inserted text survives deletion), retain/delete count splitting across op
      boundaries, trailing P insert after Q exhausted, trailing Q insert after P
      exhausted, concurrent inserts at one cursor (Q's lands first — priority row).
- [ ] Property tests: for generated base `A` and scripts `p` (A→B), `q` (A→C):
      `apply(transform(p,q), apply(q,A))` succeeds (transformed P applies to Q's
      result); transform errors iff `p` and `q` consume different base token counts;
      output has no adjacent same-kind ops.
- [ ] The convergence property that test 18 relies on:
      `apply(transform(p,q), C) == apply(transform'(…))` — encoded as: transforming the
      spec's worked pairs reproduces the merged bytes pinned by tests 09/18/22 (lifted
      as unit fixtures).
- [ ] Negative constraints: no `var` (fold/recursion), no wall-clock, output depends
      only on the two scripts.

## Notes / decisions

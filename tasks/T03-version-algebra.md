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
- [ ] Canonical parser rejects every R31 error class (duplicate ids, explicit/leading
      zeroes, overflow, invalid ids, whitespace, wrong order) and print∘parse = identity
      on valid input — unit tests per class + round-trip property.
- [ ] Property tests: join is commutative/associative/idempotent; compare returns
      `Equal` iff maps equal, `Before`/`After` antisymmetric, `Concurrent` symmetric;
      snap order is a total order that extends causal order (if `V < W` causally then
      `V` precedes `W` in snap order).
- [ ] Directed unit test: `(bob@x->1)` precedes `(alice@x->1)` in snap order (gotcha 3 —
      lower counter at the first differing sorted id wins).
- [ ] Negative constraints: no `var`, no wall-clock/env access, no unordered-collection
      iteration in this module; comparison of ids/paths uses `Utf8Order` only, never
      `String.compareTo` semantics by accident.

## Notes / decisions

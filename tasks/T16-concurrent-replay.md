# T16 — Concurrent replay: namespace, path rules, warnings (5 SP)

- **Phase:** 3 — Merge & OT
- **Depends on:** T07, T15
- **Risk:** **core** (this IS the merge semantics — formal pre-commit review, saved as
  `reviews/T16-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Complete `snap/core/Replay.scala` for concurrent histories (DESIGN §5, R67–R75): per
patch, materialize exact base `B` (memoized), then (a) namespace pre-pass — `S`, `C'`,
ancestor/descendant conflicts, install `T` + remove conflictors + `namespace-wins`,
overriding per-path rules; (b) per-path dispatch in spec order: `B`=`C` direct apply /
`C`=`T` collapse / all-text OT through aggregate `Q = diff(B,C)` applied to `C` /
path-level winner rules 1–6 in order with their warnings; (c) all of one patch's
changes applied together. Warning accumulation as a sorted set of unique
`(path, reason)` pairs (R74). Replay's public result: `(Tree, SortedSet[Warning])`.

## Scope
`snap/scala/src/main/scala/snap/core/Replay.scala` (+ small helpers in `Tree.scala`),
tests in `snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [ ] Directed unit tests, one per path-level rule (R73 rules 1–6) asserting both the
      winning bytes and the exact warning reason, plus: namespace `a` vs `a/b` in both
      canonical orders (warning names the **removed** path — test 11's pin), identical
      concurrent changes collapse with **no** warning before OT (R69 case 2), aggregate
      `Q` is `diff(B,C)` once — a three-patch chain where per-patch chaining would give
      a different result (R72).
- [ ] Replay fixtures reproduce the merged bytes pinned by tests 09 (`base\nright\nleft\n`),
      18 (`B\nA\nend\n`), 21 (`base\nB1\nB2\nA2\n`) and the warning sets of tests 10/17
      — lifted from the YAML as unit tests (the commands arrive in T17; the engine is
      proven first).
- [ ] Property tests (mandatory, CLAUDE.md): for generated valid concurrent histories —
      same `(tree, warnings)` under permuted patch-array order and permuted import
      order; replay of a frontier already replayed is idempotent; OT paths emit no
      warnings.
- [ ] Negative constraints: no wall-clock/env/randomness; every iteration over
      paths/patches/warnings goes through sorted structures; no `String.compareTo` on
      paths (Utf8Order only).

## Notes / decisions

## Pre-implementation pointers
- `reviews/T07-review.md` nit 1: `Replay.materializeMemo`'s sub-replays CAN fail
  (`CyclicHistory` on a non-self-contained base) — don't build on the infallibility
  assumption the original comment made.
- `reviews/T07-review.md` nit 2: consider private constructors for
  `Repo.StructurallyValid`/`Repo.Valid` (unforgeable proofs, DESIGN §1.4) if feasible
  without gutting test ergonomics; otherwise record why not.
- T07's `Replay.Integration` trait is your seam: replace `Replay.LinearOnly`, delete
  `SnapError.ConcurrentHistoryUnsupported`, widen results to carry warnings
  (`(Tree, SortedSet[Warning])` per DESIGN §5).
- `reviews/T15-review.md`: apply transformed scripts WITHOUT the canonical-result check
  (exact consumption still enforced) — reviewer-confirmed spec-forced (§6.5) — and
  render merged output to BYTES, re-tokenizing for downstream use; never let the
  non-canonical token list escape the transform site (finding 1).
- Phase-1 review CR1 (stack half): the T07-era replay loop was non-tail and could
  StackOverflow on ~1k+ patch valid histories. Your rewritten engine must be verified
  stack-safe at the pre-commit review (deep linear-history probe); fix in-place if not.

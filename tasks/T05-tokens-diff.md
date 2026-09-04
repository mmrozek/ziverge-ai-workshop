# T05 — Text tokens, edit scripts, canonical diff (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T01
- **Risk:** **core** (the diff's deletion-on-tie rule is a tie-break that feeds OT and
  merge — formal pre-commit review, saved as `reviews/T05-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/`: text detection + LF-retaining tokenization (R53), canonical token-sequence
predicate (R57), `EditScript` (one-key retain/delete/insert ops, structural validation
R54–R55/R58, application with full-consumption check R56), and the canonical diff —
literal DP + walk + coalesce per DESIGN §4 (R61–R64, D18, gotcha 2).

## Scope
`snap/scala/src/main/scala/snap/core/{TextTokens,EditScript,Diff}.scala`, tests in
`snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [ ] Tokenization goldens: `"a\r\nb"` → `["a\r\n", "b"]`; empty file → no tokens;
      bytes with NUL or invalid UTF-8 → not text; script application errors:
      underconsumption (`does not consume old content`), overconsumption (`consumes
      beyond old content`), adjacent same-kind ops, empty insert, non-positive counts,
      non-canonical result tokens.
- [ ] Diff golden: `[a,b,a] → [b,a,a(no final LF)]` yields exactly
      `[delete 1, retain 2, insert ["a"]]` (test 05's pinned script — deletion-on-tie).
- [ ] Property tests: `apply(diff(A,B), A) == B` for generated token sequences
      (including repeated-line-heavy ones); diff output never has adjacent same-kind
      ops; empty script only for empty→empty.
- [ ] Negative constraints: no `var` outside a locally-encapsulated DP array (justify
      any `scalafix:ok` in notes), no randomness, output depends only on the two token
      sequences.

## Notes / decisions

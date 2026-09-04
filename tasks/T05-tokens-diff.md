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
- [x] Tokenization goldens: `"a\r\nb"` → `["a\r\n", "b"]`; empty file → no tokens;
      bytes with NUL or invalid UTF-8 → not text; script application errors:
      underconsumption (`does not consume old content`), overconsumption (`consumes
      beyond old content`), adjacent same-kind ops, empty insert, non-positive counts,
      non-canonical result tokens.
- [x] Diff golden: `[a,b,a] → [b,a,a(no final LF)]` yields exactly
      `[delete 1, retain 2, insert ["a"]]` (test 05's pinned script — deletion-on-tie).
- [x] Property tests: `apply(diff(A,B), A) == B` for generated token sequences
      (including repeated-line-heavy ones); diff output never has adjacent same-kind
      ops; empty script only for empty→empty.
- [x] Negative constraints: no `var` outside a locally-encapsulated DP array (justify
      any `scalafix:ok` in notes), no randomness, output depends only on the two token
      sequences.

## Notes / decisions

- **DP table (D18):** implemented as a local `Array.ofDim[Int](n+1, m+1)` filled by
  `for`-comprehensions — a literal transcription of the spec recurrence, chosen over an
  immutable `scanLeft` formulation for reviewability of a core-risk module. No `var`,
  no `while`, so **no `scalafix:ok` suppression was needed**; the array is write-once
  per cell, never escapes `Diff.table`, and carries the invariant comment the
  conventions require for a mutable boundary.
- **Local error type (scope deviation, planned):** T02/T03/T04 ran in parallel
  worktrees, so `snap/core/Errors.scala` did not exist here. Edit-script failures live
  in a task-local `enum EditError(val message: String)` in `EditScript.scala`; each
  case's message carries the exact test-pinned fragment (tests 15/23) *at the end* of
  the message — test 23's regexes are anchored `…<fragment>\n$`, so later layers
  (T06 codec / T07 validation / error catalog) may prefix context but must never append
  after the fragment. `EditError.NotOneOperation` (`must have one operation`) is
  unrepresentable in the `EditOp` ADT and exists solely for the JSON codec layer to
  raise (test 23's `{"retain":1,"delete":1}` case).
- **Untested messages (D5):** non-canonical applied result → `edit result is not a
  canonical token sequence`; invalid insert token (empty / interior LF / NUL / unpaired
  surrogate) → `edit insert token is not a text token`. No provided test pins these.
- **Insert-token validity (non-core reading, recorded per ambiguity policy):** R54's
  "nonempty text tokens" is enforced structurally in `validate` as: nonempty, no NUL,
  LF at most as the final character, no unpaired UTF-16 surrogate (such a string has no
  UTF-8 encoding, hence can never be file text). This never rejects a script that could
  apply successfully — any such token always fails R57's canonical-result check anyway;
  the position-dependent rule (non-final token must end in LF) is checked only at
  application, where it belongs.
- **UTF-8 validity is hand-rolled** (RFC 3629 acceptance: rejects overlong forms,
  encoded surrogates, > U+10FFFF, truncated/stray bytes) so text detection uses no
  exception control flow and no platform decoder behavior. `TextTokens` imports
  `java.nio.charset.StandardCharsets` — a pure charset constant for `new String(bytes,
  UTF_8)` after validation; DESIGN §2's "no io/nio in core" note is read as "no I/O
  effects" (recorded for phase review).
- **Validation determinism:** `validate` reports the leftmost offending op (an op's own
  defect before its adjacency with the previous op); `applyTo` validates structurally
  first, then checks consumption during the walk and result canonicality at the end.
- **Verification (in T05 worktree):** `sbt test` → 33 passed, 0 failed (TextTokens 11,
  EditScript 12, Diff 9 incl. 6 scalacheck properties at 300 samples, +1 T01 smoke);
  `sbt scalafmtCheckAll` and `sbt "scalafixAll --check"` both green. Provided test 05
  needs the full CLI (phase 2); its pinned script and application result are asserted
  as unit goldens here.
- **Correction (T05 review finding 2, orchestrator):** the Notes above overstate the
  redundancy of the NUL/unpaired-surrogate checks in `isTextToken` — `isCanonical`
  checks only emptiness and LF placement, so those checks are **load-bearing**. Do not
  remove them as "redundant"; see `reviews/T05-review.md` #2.

package snap.core

import scala.annotation.tailrec

/** OT transform (SPEC §6.3, R71; DESIGN §5): transforms an incoming text edit `P` so it applies
  * after the aggregate context edit `Q = diff(B, C)`. Pure: the output depends only on the two
  * scripts. Replay calls this once per integrated patch against the aggregate context edit, never
  * once per historical patch (R72 — that discipline lives in the caller, T16).
  */
object Ot:

  /** Transforms incoming edit `p` through context edit `q`, processing both streams left to right
    * and splitting counts as needed — the spec's six-row table verbatim (R71):
    *
    * | Next operations        | Output in transformed `P`  | Consumption |
    * |:-----------------------|:---------------------------|:------------|
    * | `Q insert`             | `retain(length(Q insert))` | Q only      |
    * | `P insert`             | same `P insert`            | P only      |
    * | `P retain`, `Q retain` | `retain(min)`              | both        |
    * | `P delete`, `Q retain` | `delete(min)`              | both        |
    * | `P retain`, `Q delete` | nothing                    | both        |
    * | `P delete`, `Q delete` | nothing                    | both        |
    *
    * `length(Q insert)` is its token count. The `Q insert` row has priority: it applies whenever
    * `q`'s next unconsumed operation is an insert, regardless of `p`'s next operation — including
    * when `p` is exhausted. The `P insert` row applies when `q`'s next operation is not an insert.
    * Concurrent inserts at one cursor therefore appear in canonical integration order: `Q`'s
    * earlier-integrated text lands first and transformed `P` retains over it before its own insert
    * is emitted (tests 09/18 pin the merged bytes). Deletion consumes only base tokens — a
    * `P delete` pairs only against `q`'s retains and deletes, while `q`-inserted tokens are always
    * covered by the priority row's retain — so concurrently inserted text survives an incoming
    * deletion (test 22).
    *
    * Both scripts must consume the same base token count: if one stream ends while the other still
    * holds a retain or delete, the transform fails with [[SnapError.OtBaseMismatch]] — an internal
    * invariant for replay, which derives both scripts from the same base tree. Processing continues
    * until both streams end; a trailing insertion after the other stream's end is handled by its
    * applicable row. Adjacent same-kind output operations are coalesced (counts add, insert token
    * arrays concatenate), so the result satisfies R55.
    *
    * Precondition: `p` and `q` are structurally valid edit scripts (R54–R55). Replay guarantees
    * this — `q` is a [[Diff.diff]] output and `p` comes from a validated patch — so structural
    * validation is not repeated here.
    */
  def transform(p: EditScript, q: EditScript): Either[SnapError, EditScript] =
    // Row dispatch, tail-recursive. A partially consumed retain/delete is re-headed with its
    // remaining count ("splitting counts as needed"); the accumulator holds the output in reverse
    // order and coalesces at its head via `push`.
    @tailrec
    def go(ps: List[EditOp], qs: List[EditOp], acc: List[EditOp]): Either[SnapError, List[EditOp]] =
      (ps, qs) match
        // Row 1 — `Q insert` (the priority row, even when `ps` is empty or starts with an
        // insert): retain over Q's inserted tokens; consume Q only.
        case (_, EditOp.Insert(tokens) :: qt) =>
          go(ps, qt, push(acc, EditOp.Retain(tokens.length.toLong)))
        // Row 2 — `P insert` (Q's next operation is not an insert): same insert; consume P only.
        case (EditOp.Insert(tokens) :: pt, _) =>
          go(pt, qs, push(acc, EditOp.Insert(tokens)))
        // Both streams ended together: every base token was matched.
        case (Nil, Nil) => Right(acc.reverse)
        // Row 3 — `P retain`, `Q retain`: `retain(min)`; consume both.
        case (EditOp.Retain(pn) :: pt, EditOp.Retain(qn) :: qt) =>
          val n = math.min(pn, qn)
          go(
            remainder(pn - n, EditOp.Retain.apply, pt),
            remainder(qn - n, EditOp.Retain.apply, qt),
            push(acc, EditOp.Retain(n))
          )
        // Row 4 — `P delete`, `Q retain`: `delete(min)`; consume both.
        case (EditOp.Delete(pn) :: pt, EditOp.Retain(qn) :: qt) =>
          val n = math.min(pn, qn)
          go(
            remainder(pn - n, EditOp.Delete.apply, pt),
            remainder(qn - n, EditOp.Retain.apply, qt),
            push(acc, EditOp.Delete(n))
          )
        // Row 5 — `P retain`, `Q delete`: nothing; consume both.
        case (EditOp.Retain(pn) :: pt, EditOp.Delete(qn) :: qt) =>
          val n = math.min(pn, qn)
          go(
            remainder(pn - n, EditOp.Retain.apply, pt),
            remainder(qn - n, EditOp.Delete.apply, qt),
            acc
          )
        // Row 6 — `P delete`, `Q delete`: nothing; consume both (each base token is deleted
        // once; deletion never reaches concurrently inserted text).
        case (EditOp.Delete(pn) :: pt, EditOp.Delete(qn) :: qt) =>
          val n = math.min(pn, qn)
          go(
            remainder(pn - n, EditOp.Delete.apply, pt),
            remainder(qn - n, EditOp.Delete.apply, qt),
            acc
          )
        // One stream ended while the other still holds a retain or delete (its inserts would
        // have been consumed by rows 1–2): the scripts consume different base token counts.
        case _ => Left(SnapError.OtBaseMismatch)

    go(p.ops.toList, q.ops.toList, List.empty).map(ops => EditScript(ops.toVector))

  /** Re-heads a partially consumed retain/delete with its remaining count — the table's "splitting
    * counts as needed". `left` is zero when the operation was consumed exactly.
    */
  private def remainder(left: Long, mk: Long => EditOp, tail: List[EditOp]): List[EditOp] =
    if left == 0L then tail else mk(left) :: tail

  /** SPEC §6.3 "Coalesce adjacent output operations": merges `op` into the head of the reversed
    * accumulator when the kinds match — counts add, insert token arrays concatenate — so the output
    * never holds two adjacent operations of one kind (R55). Local rather than shared with [[Diff]]:
    * its coalescing is private to the diff walk.
    */
  private def push(acc: List[EditOp], op: EditOp): List[EditOp] =
    (acc, op) match
      case (EditOp.Retain(x) :: t, EditOp.Retain(y))   => EditOp.Retain(x + y) :: t
      case (EditOp.Delete(x) :: t, EditOp.Delete(y))   => EditOp.Delete(x + y) :: t
      case (EditOp.Insert(xs) :: t, EditOp.Insert(ys)) => EditOp.Insert(xs :++ ys) :: t
      case _                                           => op :: acc

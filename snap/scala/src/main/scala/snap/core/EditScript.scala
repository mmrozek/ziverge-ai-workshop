package snap.core

import scala.annotation.tailrec

/** One edit-script operation (SPEC §4.4, R54): `retain n` copies `n` old tokens, `delete n`
  * consumes and removes `n` old tokens, `insert [s…]` inserts one or more nonempty text tokens.
  * Counts are positive safe integers (hence `Long`).
  */
enum EditOp:
  case Retain(count: Long)
  case Delete(count: Long)
  case Insert(tokens: Vector[String])

/** Typed edit-script failures — pure reasons; the rendered text lives in [[Messages.editError]] (D5
  * — migrated from T05's inline messages in T06). Each rendered message carries the exact fragment
  * the provided suite pins (test 15 via `stderr_contains`, test 23 via regexes anchored at the line
  * end) — later layers may prefix context but must never append after the fragment. The codec wraps
  * these into [[SnapError.InvalidEdit]].
  */
enum EditError:
  /** R56 — the script ends before consuming the complete old token sequence (test 15). */
  case Underconsumption

  /** R56 — a retain/delete reaches past the end of the old token sequence (test 23). */
  case Overconsumption

  /** R55 — adjacent operations of one kind; `kind` is `retain`/`delete`/`insert` (test 15). */
  case Adjacent(kind: String)

  /** R54 — an `insert` with an empty token array (test 23). */
  case EmptyInsert

  /** R54 — an insert token that is not a nonempty text token (untested message, D5). */
  case BadInsertToken

  /** R54 — a count outside `1 .. 2^53−1` (test 23). */
  case BadCount

  /** R54 — an edit-operation object without exactly one key. Unrepresentable in [[EditOp]]; exposed
    * for the JSON codec layer, which owns the check (test 23).
    */
  case NotOneOperation

  /** R57 — the applied result is not a canonical token sequence (untested message, D5). */
  case NonCanonicalResult

  /** Rendered one-line reason; text lives only in the [[Messages]] catalog (D5). */
  def message: String = Messages.editError(this)

/** An edit script (SPEC §4.4): the ordered operations transforming one canonical token sequence
  * into another. Structural rules (R54–R55) live in [[validate]]; application rules (R56–R57) in
  * [[applyTo]]. The empty script is structurally valid but applies only to the empty token sequence
  * (R58 — empty-text-file creation).
  */
final case class EditScript(ops: Vector[EditOp]):

  /** Structural validation (R54–R55): counts are positive safe integers, insert arrays are nonempty
    * and hold only nonempty text tokens, and no two adjacent operations share a kind.
    * Deterministic: the leftmost offending operation decides (its own defect before its adjacency
    * with the previous operation).
    */
  def validate: Either[EditError, Unit] =
    ops.iterator.zipWithIndex
      .map { case (op, k) => opError(op).orElse(adjacencyError(k)) }
      .collectFirst { case Some(e) => e }
      .toLeft(())

  /** Applies the script to `old` (R56–R58): validates structurally first, then MUST consume the
    * complete old token sequence — no implicit trailing retain, no reading past its end — and MUST
    * produce exactly a canonical token sequence.
    */
  def applyTo(old: Vector[String]): Either[EditError, Vector[String]] =
    @tailrec
    def go(k: Int, pos: Int, acc: Vector[String]): Either[EditError, Vector[String]] =
      if k == ops.length then
        if pos < old.length then Left(EditError.Underconsumption)
        else if !TextTokens.isCanonical(acc) then Left(EditError.NonCanonicalResult)
        else Right(acc)
      else
        ops(k) match
          case EditOp.Retain(n) =>
            if n > (old.length - pos).toLong then Left(EditError.Overconsumption)
            else go(k + 1, pos + n.toInt, acc :++ old.slice(pos, pos + n.toInt))
          case EditOp.Delete(n) =>
            if n > (old.length - pos).toLong then Left(EditError.Overconsumption)
            else go(k + 1, pos + n.toInt, acc)
          case EditOp.Insert(tokens) => go(k + 1, pos, acc :++ tokens)
    validate match
      case Left(e)   => Left(e)
      case Right(()) => go(0, 0, Vector.empty)

  private def opError(op: EditOp): Option[EditError] = op match
    case EditOp.Retain(n)      => countError(n)
    case EditOp.Delete(n)      => countError(n)
    case EditOp.Insert(tokens) =>
      if tokens.isEmpty then Some(EditError.EmptyInsert)
      else Option.unless(tokens.forall(TextTokens.isTextToken))(EditError.BadInsertToken)

  private def countError(n: Long): Option[EditError] =
    Option.when(n < 1L || n > EditScript.MaxSafeInteger)(EditError.BadCount)

  private def adjacencyError(k: Int): Option[EditError] =
    Option.when(k > 0 && kind(ops(k - 1)) == kind(ops(k)))(EditError.Adjacent(kind(ops(k))))

  private def kind(op: EditOp): String = op match
    case EditOp.Retain(_) => "retain"
    case EditOp.Delete(_) => "delete"
    case EditOp.Insert(_) => "insert"

object EditScript:
  /** Largest safe integer, 2^53 − 1 (SPEC §4.4 "positive safe integers"). */
  val MaxSafeInteger: Long = 9007199254740991L

  /** The empty script — valid only for empty-text-file creation (R58). */
  val empty: EditScript = EditScript(Vector.empty)

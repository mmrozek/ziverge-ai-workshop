package snap.core

import scala.annotation.tailrec

/** Canonical token diff (SPEC §5; DESIGN §4, D18): the literal DP recurrence, the literal walk with
  * the deletion-on-tie rule, and coalescing. No Myers/Hirschberg or other optimization — the spec
  * permits them only if the script is identical, and D18 locks the literal form. Pure: the output
  * depends only on the two token sequences.
  */
object Diff:

  /** Diff of old tokens `a` against new tokens `b`; applying the result to `a` yields exactly `b`.
    * Walk from `(0, 0)` (SPEC §5 steps 1–4; primitive ops coalesced by [[coalesce]], step 5):
    *
    *   1. equal tokens produce `retain 1`;
    *   1. otherwise `delete 1` when `D(i+1, j) <= D(i, j+1)` — the `<=` is the normative
    *      deletion-on-tie rule (R62, DESIGN gotcha 2);
    *   1. otherwise `insert [B[j]]`;
    *   1. at an exhausted side, insert or delete the remaining tokens.
    */
  def diff(a: Vector[String], b: Vector[String]): EditScript =
    val n = a.length
    val m = b.length
    val d = table(a, b)

    @tailrec
    def walk(i: Int, j: Int, acc: List[EditOp]): List[EditOp] =
      if i == n && j == m then acc.reverse
      else if i < n && j < m && a(i) == b(j) then walk(i + 1, j + 1, EditOp.Retain(1L) :: acc)
      else if i == n then (EditOp.Insert(b.drop(j)) :: acc).reverse
      else if j == m then (EditOp.Delete((n - i).toLong) :: acc).reverse
      else if d(i + 1)(j) <= d(i)(j + 1) then walk(i + 1, j, EditOp.Delete(1L) :: acc)
      else walk(i, j + 1, EditOp.Insert(Vector(b(j))) :: acc)

    EditScript(coalesce(walk(0, 0, List.empty)))

  /** The full (n+1)×(m+1) table of `D(i, j)` — the minimum inserts/deletes needed to transform
    * `A[i..]` into `B[j..]` — filled bottom-up by the spec recurrence (R61):
    *
    * {{{
    * D(n, m) = 0
    * D(i, m) = n - i
    * D(n, j) = m - j
    * D(i, j) = D(i+1, j+1)                      if A[i] == B[j]
    *         = 1 + min(D(i+1, j), D(i, j+1))    otherwise
    * }}}
    *
    * The array is a local, write-once-per-cell buffer that never escapes `Diff` (the walk above
    * reads it, nothing mutates after fill) — pure from the outside. This is the named mutable
    * boundary the conventions allow; DESIGN §4 pins the full table.
    */
  private def table(a: Vector[String], b: Vector[String]): Array[Array[Int]] =
    val n = a.length
    val m = b.length
    val d = Array.ofDim[Int](n + 1, m + 1)
    for j <- 0 to m do d(n)(j) = m - j // D(n, j) = m - j; covers D(n, m) = 0
    for i <- (n - 1) to 0 by -1 do
      d(i)(m) = n - i // D(i, m) = n - i
      for j <- (m - 1) to 0 by -1 do
        d(i)(j) =
          if a(i) == b(j) then d(i + 1)(j + 1)
          else 1 + math.min(d(i + 1)(j), d(i)(j + 1))
    d

  /** SPEC §5 step 5: merge adjacent same-kind operations — counts add, insert token arrays
    * concatenate — so the result never holds two adjacent ops of one kind (R55).
    */
  private def coalesce(ops: List[EditOp]): Vector[EditOp] =
    ops.foldLeft(Vector.empty[EditOp]) { (acc, op) =>
      (acc.lastOption, op) match
        case (Some(EditOp.Retain(x)), EditOp.Retain(y))   => acc.init :+ EditOp.Retain(x + y)
        case (Some(EditOp.Delete(x)), EditOp.Delete(y))   => acc.init :+ EditOp.Delete(x + y)
        case (Some(EditOp.Insert(xs)), EditOp.Insert(ys)) => acc.init :+ EditOp.Insert(xs :++ ys)
        case _                                            => acc :+ op
    }

package snap.cli

import snap.core.SnapPath
import snap.core.Tree

import scala.annotation.tailrec

/** One differing path between the current tree and the working tree (SPEC §2, §7.3): `before` is
  * the current tree's content, `after` the working tree's; absent side = `None`. By construction
  * ([[WorkingChanges.compute]]) the two sides are never both absent and never both present with
  * equal bytes — so `(None, Some)` is an addition, `(Some, Some)` a byte modification, and
  * `(Some, None)` a deletion.
  */
private[cli] final case class Delta(
    path: SnapPath,
    before: Option[IArray[Byte]],
    after: Option[IArray[Byte]]
)

/** The pure current-vs-working comparison shared by `status` (A/M/D rows, R83) and `commit` (change
  * construction, R85).
  */
private[cli] object WorkingChanges:

  /** Sorted merge walk of the two trees — both iterate in `Utf8Order` by construction — yielding
    * the deltas in path order. Empty iff the working tree is clean (R26: exact path/byte equality
    * with the current tree; unsupported entries have already failed the scan itself).
    */
  def compute(current: Tree, working: Tree): Vector[Delta] =
    val a = current.toVector
    val b = working.toVector
    @tailrec
    def go(i: Int, j: Int, acc: Vector[Delta]): Vector[Delta] =
      if i >= a.length && j >= b.length then acc
      else if i >= a.length then
        val (path, bytes) = b(j)
        go(i, j + 1, acc :+ Delta(path, None, Some(bytes)))
      else if j >= b.length then
        val (path, bytes) = a(i)
        go(i + 1, j, acc :+ Delta(path, Some(bytes), None))
      else
        val (pathA, bytesA) = a(i)
        val (pathB, bytesB) = b(j)
        val c = SnapPath.ordering.compare(pathA, pathB)
        if c < 0 then go(i + 1, j, acc :+ Delta(pathA, Some(bytesA), None))
        else if c > 0 then go(i, j + 1, acc :+ Delta(pathB, None, Some(bytesB)))
        else if bytesEqual(bytesA, bytesB) then go(i + 1, j + 1, acc)
        else go(i + 1, j + 1, acc :+ Delta(pathA, Some(bytesA), Some(bytesB)))
    go(0, 0, Vector.empty)

  // Byte-content equality, module-local like `Tree`'s and `Change`'s (kept private per file rather
  // than exposing one file's internals across packages).
  private def bytesEqual(a: IArray[Byte], b: IArray[Byte]): Boolean =
    a.length == b.length && (0 until a.length).forall(i => a(i) == b(i))

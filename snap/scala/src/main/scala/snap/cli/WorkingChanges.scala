package snap.cli

import snap.core.SnapPath
import snap.core.Tree

import scala.annotation.tailrec

/** One differing path between the current tree and the working tree (SPEC §2, §7.3): `before` is
  * the current tree's content, `after` the working tree's; absent side = `None`. By construction
  * ([[WorkingChanges.compute]]) the two sides are never both absent and never both present with
  * equal bytes — so `(None, Some)` is an addition, `(Some, Some)` a byte modification, and
  * `(Some, None)` a deletion.
  *
  * Equality is structural over content bytes (CR12a, mirroring [[Tree]] and [[Change.Put]]):
  * `IArray[Byte]` is an array at runtime, whose default equality would be by reference, and that
  * would leak through `Option`'s own structural equality unless overridden here.
  */
private[cli] final case class Delta(
    path: SnapPath,
    before: Option[IArray[Byte]],
    after: Option[IArray[Byte]]
):
  override def equals(that: Any): Boolean = that match
    case other: Delta =>
      path == other.path &&
      Delta.bytesEqual(before, other.before) &&
      Delta.bytesEqual(after, other.after)
    case _ => false

  /** Deterministic across runs: folds content bytes; no identity hashes. */
  override def hashCode: Int =
    31 * (31 * path.hashCode + Delta.bytesHash(before)) + Delta.bytesHash(after)

private[cli] object Delta:
  // Byte-content helpers mirroring `Tree`'s/`Change.Put`'s private ones, lifted to `Option` since
  // either side of a delta may be absent (kept module-local rather than exposing internals across
  // files).
  private def bytesEqual(a: Option[IArray[Byte]], b: Option[IArray[Byte]]): Boolean = (a, b) match
    case (None, None)       => true
    case (Some(x), Some(y)) => x.length == y.length && bytesEqualFrom(x, y, 0)
    case _                  => false

  @tailrec
  private def bytesEqualFrom(a: IArray[Byte], b: IArray[Byte], i: Int): Boolean =
    i >= a.length || (a(i) == b(i) && bytesEqualFrom(a, b, i + 1))

  private def bytesHash(a: Option[IArray[Byte]]): Int = a match
    case None        => 0
    case Some(bytes) => bytesHashFrom(bytes, 0, 1)

  @tailrec
  private def bytesHashFrom(bytes: IArray[Byte], i: Int, acc: Int): Int =
    if i >= bytes.length then acc else bytesHashFrom(bytes, i + 1, 31 * acc + bytes(i))

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

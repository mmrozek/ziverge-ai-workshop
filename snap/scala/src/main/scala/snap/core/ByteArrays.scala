package snap.core

import scala.annotation.tailrec

/** Deterministic, content-based equality and hashing for `IArray[Byte]` (T23, phase-1 review
  * CR12b): file content and `put`/`Delta` bytes must compare and hash by VALUE, never by array
  * reference, so this exact logic used to be hand-copied into four places independently — [[Tree]]
  * (tree entries), `Change.Put` in `Patch.scala` (patch content), [[Replay]]'s
  * `applyChange`/`sameEntry` (no-op detection and identical-entry comparison), and
  * `cli.WorkingChanges`' `Delta` (current-vs-working diffing) — each with its own private
  * `bytesEqual`/`bytesHash` pair. Consolidated here as the one canonical implementation; the four
  * call sites now delegate instead of re-declaring the walk.
  */
object ByteArrays:

  /** Content equality: same length, then every byte equal in order. */
  def equal(a: IArray[Byte], b: IArray[Byte]): Boolean =
    a.length == b.length && equalFrom(a, b, 0)

  @tailrec
  private def equalFrom(a: IArray[Byte], b: IArray[Byte], i: Int): Boolean =
    i >= a.length || (a(i) == b(i) && equalFrom(a, b, i + 1))

  /** Content hash: a left fold over the bytes (Java's `Arrays.hashCode` recurrence), so equal
    * content always hashes equal regardless of which array instance holds it.
    */
  def hash(bytes: IArray[Byte]): Int = hashFrom(bytes, 0, 1)

  @tailrec
  private def hashFrom(bytes: IArray[Byte], i: Int, acc: Int): Int =
    if i >= bytes.length then acc else hashFrom(bytes, i + 1, 31 * acc + bytes(i))

  /** [[equal]] lifted to `Option`, for comparisons where either side may be absent (e.g. a
    * [[cli.WorkingChanges.Delta]]'s before/after content) — absent compares equal only to absent.
    */
  def equalOption(a: Option[IArray[Byte]], b: Option[IArray[Byte]]): Boolean = (a, b) match
    case (None, None)       => true
    case (Some(x), Some(y)) => equal(x, y)
    case _                  => false

  /** [[hash]] lifted to `Option`; `None` hashes to `0` (the convention every duplicated helper
    * already used before consolidation).
    */
  def hashOption(a: Option[IArray[Byte]]): Int = a match
    case None        => 0
    case Some(bytes) => hash(bytes)

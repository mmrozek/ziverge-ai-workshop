package snap.core

import scala.collection.immutable.TreeMap

/** An immutable tracked tree: a path → content-bytes map (SPEC §2), iterated exclusively in
  * `Utf8Order` of the paths. Ordering is structural, not a promise: entries live in a `TreeMap`
  * keyed by `SnapPath.ordering`, so every iterator this class exposes is sorted by construction,
  * independent of insertion order.
  *
  * Content is `IArray[Byte]` — immutable by type; `Tree` equality and hashing are by byte content,
  * not array reference.
  *
  * A `Tree` value does not itself enforce prefix-freeness: replay and validation (SPEC §6.2/§6.4)
  * need to represent and then resolve namespace conflicts. Use [[isPrefixFree]] (R25) at validation
  * points, and the ancestor/descendant queries for namespace-conflict resolution.
  */
final class Tree private (private val entries: TreeMap[SnapPath, IArray[Byte]]):
  def size: Int = entries.size
  def isEmpty: Boolean = entries.isEmpty
  def contains(path: SnapPath): Boolean = entries.contains(path)
  def get(path: SnapPath): Option[IArray[Byte]] = entries.get(path)

  /** All paths, in `Utf8Order`. */
  def paths: Vector[SnapPath] = entries.keysIterator.toVector

  /** All entries, in `Utf8Order` of the path. */
  def toVector: Vector[(SnapPath, IArray[Byte])] = entries.iterator.toVector

  def updated(path: SnapPath, bytes: IArray[Byte]): Tree = new Tree(entries.updated(path, bytes))
  def removed(path: SnapPath): Tree = new Tree(entries.removed(path))

  /** Tree paths that are proper segment-prefix ancestors of `path`, root-first. (`path` itself need
    * not be in the tree.)
    */
  def ancestorsOf(path: SnapPath): Vector[SnapPath] = path.ancestors.filter(entries.contains)

  /** Tree paths that have `path` as a proper segment prefix, in `Utf8Order`. (`path` itself need
    * not be in the tree.)
    */
  def descendantsOf(path: SnapPath): Vector[SnapPath] =
    entries.keysIterator.filter(path.isAncestorOf).toVector

  /** R25: no tree path is a proper segment prefix of another. */
  def isPrefixFree: Boolean = SnapPath.prefixFree(entries.keys)

  /** Structural equality: same paths bound to the same bytes. Well-defined because both iterators
    * are sorted by the same total order.
    */
  override def equals(that: Any): Boolean = that match
    case other: Tree =>
      entries.size == other.entries.size &&
      entries.iterator.zip(other.entries.iterator).forall { case ((pa, ba), (pb, bb)) =>
        pa == pb && ByteArrays.equal(ba, bb)
      }
    case _ => false

  /** Deterministic across runs: folds sorted entries; no identity hashes. */
  override def hashCode: Int =
    entries.iterator.foldLeft(7) { case (acc, (p, b)) =>
      31 * (31 * acc + p.hashCode) + ByteArrays.hash(b)
    }

  override def toString: String =
    entries.keysIterator.map(_.value).mkString("Tree(", ", ", ")")

object Tree:
  val empty: Tree = new Tree(TreeMap.empty)

  /** Builds a tree from entries; on duplicate paths the last one wins (map semantics). The result
    * is independent of the input's order up to that rule.
    */
  def from(entries: Iterable[(SnapPath, IArray[Byte])]): Tree =
    entries.foldLeft(empty) { case (tree, (path, bytes)) => tree.updated(path, bytes) }

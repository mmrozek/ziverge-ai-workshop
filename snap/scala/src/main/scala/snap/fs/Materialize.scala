package snap.fs

import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Tree
import snap.core.Utf8Order

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** Installs a target [[Tree]] onto the working directory (SPEC §6.2/§10, R70/R105; DESIGN §7): the
  * shared filesystem-mutation primitive behind `revert` (T12) and `merge` (T17) — "one canonical
  * implementation per concept" (DESIGN §1). Given the [[Tree]] value that currently matches the
  * working directory on disk (every caller requires a clean tree first — SPEC §2/§10, R27) and the
  * fully computed target tree, brings the filesystem to match `target` exactly, in ordered steps
  * (R70's wording):
  *
  *   1. **Remove blockers**: delete every tracked file present in `current` but absent from
  *      `target`. A file that occupies a path the target needs as a directory is removed here
  *      because it is exactly such a current-only path (its own tracked entry disappears in the
  *      target — R70's "removes files blocking required directories").
  *   1. **Remove newly empty directories**: prune only the directories this install's own removals
  *      just emptied — the ancestor chains of the paths removed in step 1, walked deepest-first, a
  *      chain stopping at the first directory still holding an entry (R70's "removes newly empty
  *      directories" — ''newly'', not every pre-existing empty directory under `root`; a merge or
  *      revert that removes nothing must not touch the filesystem here at all, matching §7.8's
  *      "changes nothing" for an already-contained history). A *directory* that occupies a path the
  *      target needs as a plain file is pruned here too: every tracked file that direction leaves
  *      behind is, by prefix-freeness (R25), necessarily also current-only and thus already in
  *      `removed`, so the directory is exactly an ancestor of one of step 1's deletions and is
  *      caught by the same walk once its last tracked descendant is gone (see
  *      [[pruneEmptiedAncestors]] and Notes / decisions). Both directions of the file/directory
  *      transition therefore collapse to the same mechanism.
  *   1. **Create directories**: for every path about to be written, ensure its parent chain exists.
  *   1. **Write targets**: write the new/changed bytes for every path whose target content differs
  *      from (or is new relative to) `current`.
  *
  * A second "remove newly empty directories" pass after writing (R70's literal step order) is
  * deliberately not run: writing bytes to a path already present in `target` never deletes or
  * empties a directory, and step 2 already pruned every directory this install could have emptied,
  * so a repeat pass would be a provable no-op — dead code, not a safety net (Notes / decisions).
  *
  * `repository.json` is never touched here — SPEC §10/R105 requires the metadata replacement to
  * happen only after this returns `Right`; every caller enforces that ordering itself by calling
  * [[Store.writeRepository]] afterwards (mirrors [[Store]]'s own atomic-write boundary one level up
  * — a failure partway through leaves a dirty working tree with the OLD `repository.json` still
  * intact, R106).
  *
  * This object sits at the filesystem effect boundary (same pattern as [[Store]]/[[WorkTree]]): NIO
  * failures become typed errors here via `Try`/`Using`; nothing throws as control flow (D4).
  */
object Materialize:

  /** Brings the working directory at `root` from `current` to `target` (see the class doc for the
    * step order). Every step folds over a [[Tree]]-derived, `Utf8Order`-sorted `Vector` — including
    * [[pruneEmptiedAncestors]]'s candidate ordering — so the sequence of filesystem operations is a
    * deterministic function of the two tree values alone.
    */
  def install(root: Path, current: Tree, target: Tree): Either[SnapError, Unit] =
    val removed = current.paths.filterNot(target.contains)
    val written = target.paths.filter(p => !sameContent(current.get(p), target.get(p)))
    for
      _ <- deletePaths(root, removed)
      _ <- pruneEmptiedAncestors(root, removed)
      _ <- ensureParents(root, written)
      _ <- writePaths(root, target, written)
    yield ()

  private def sameContent(a: Option[IArray[Byte]], b: Option[IArray[Byte]]): Boolean = (a, b) match
    case (Some(x), Some(y)) => x.length == y.length && (0 until x.length).forall(i => x(i) == y(i))
    case (None, None)       => true
    case _                  => false

  /** Deletes every path in `paths` (a current-only path is, by construction, a tracked regular file
    * — [[WorkTree]] never scans anything else into a [[Tree]]). Uses `Files.delete` rather than a
    * defensive existence check: a missing file here would mean the working tree changed out from
    * under a single-process command, which SPEC §10 places out of scope, so any such inconsistency
    * is reported rather than silently absorbed.
    */
  private def deletePaths(root: Path, paths: Vector[SnapPath]): Either[SnapError, Unit] =
    paths.foldLeft[Either[SnapError, Unit]](Right(())) { (acc, path) =>
      acc.flatMap(_ => attempt(Files.delete(resolve(root, path))).map(_ => ()))
    }

  private def ensureParents(root: Path, paths: Vector[SnapPath]): Either[SnapError, Unit] =
    paths.foldLeft[Either[SnapError, Unit]](Right(())) { (acc, path) =>
      acc.flatMap(_ => attempt(Files.createDirectories(resolve(root, path).getParent)).map(_ => ()))
    }

  private def writePaths(
      root: Path,
      target: Tree,
      paths: Vector[SnapPath]
  ): Either[SnapError, Unit] =
    paths.foldLeft[Either[SnapError, Unit]](Right(())) { (acc, path) =>
      acc.flatMap { _ =>
        // `path` is drawn from `target.paths`, so `target.get(path)` is always defined; the
        // fallback is unreachable and exists only so this stays total without an unsafe `.get`.
        val bytes = target.get(path).fold(Array.emptyByteArray)(toArray)
        attempt(
          Files.write(
            resolve(root, path),
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
        ).map(_ => ())
      }
    }

  /** Removes only the directories this install's own deletions could have emptied — the proper
    * segment-prefix ancestors of `removed` (SPEC §6.2/§10, R70's "removes newly empty directories";
    * see the class doc and audit finding 1: the prior implementation swept the *entire* tree under
    * `root`, silently deleting untracked, pre-existing empty directories on a no-op `merge`/
    * `revert`, which contradicts §7.8's "changes nothing"). `removed` being empty (the common
    * already-contained-history case) short-circuits to no filesystem access at all.
    *
    * [[SnapPath.ancestors]] gives each removed path's proper prefixes root-first (`a/b/c` → `a`,
    * `a/b`); the candidate set is the union across all of `removed`, deduplicated, then ordered
    * deepest-first (by segment count) so a directory is only ever checked for emptiness after every
    * candidate nested inside it has already been resolved — e.g. `a/b` is pruned before `a` is
    * reconsidered, so a chain empties correctly in one pass. Ties at equal depth cannot be
    * ancestor-descendant of one another, so their relative order cannot change the result; they are
    * still broken by [[SnapPath.ordering]] (`Utf8Order`) so the operation sequence is fixed by the
    * two `Tree` values alone, never by `removed`'s incidental construction order (mirrors
    * [[install]]'s determinism note). A candidate directory that still holds any entry — a tracked
    * file, an untracked file, or a non-empty subdirectory — is left untouched, so a pre-existing
    * empty directory unrelated to `removed` is never a candidate and a partially emptied one is
    * never deleted.
    */
  private def pruneEmptiedAncestors(
      root: Path,
      removed: Vector[SnapPath]
  ): Either[SnapError, Unit] =
    val depthDescending =
      Ordering.by[SnapPath, Int](_.segments.length).reverse.orElse(SnapPath.ordering)
    val candidates = removed.flatMap(_.ancestors).distinct.sorted(depthDescending)
    candidates.foldLeft[Either[SnapError, Unit]](Right(())) { (acc, dir) =>
      acc.flatMap(_ => pruneIfEmpty(resolve(root, dir)))
    }

  /** Deletes `dir` iff it currently exists as a directory with zero entries; otherwise a no-op.
    * Never recurses — [[pruneEmptiedAncestors]]'s deepest-first candidate order already guarantees
    * any prunable descendant was resolved first.
    */
  private def pruneIfEmpty(dir: Path): Either[SnapError, Unit] =
    if Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) then
      childNames(dir).flatMap { names =>
        if names.isEmpty then attempt(Files.delete(dir)).map(_ => ()) else Right(())
      }
    else Right(())

  /** One directory's entry names, sorted by `Utf8Order` (directory-listing order is
    * filesystem-dependent and must never feed a decision — mirrors [[WorkTree.children]], kept
    * separate since that method is package-private to [[WorkTree]] and this object's failure case
    * is typed differently).
    */
  private def childNames(dir: Path): Either[SnapError, Vector[String]] =
    Using(Files.newDirectoryStream(dir)) { stream =>
      stream.iterator.asScala.map(_.getFileName.toString).toVector.sorted(Utf8Order)
    } match
      case Success(names) => Right(names)
      case Failure(e)     => Left(updateFailure(e))

  /** [[SnapPath]] segments resolved one at a time — never a single `/`-joined string — mirroring
    * [[WorkTree]]'s reverse construction, so this works regardless of the platform separator.
    */
  private def resolve(root: Path, path: SnapPath): Path =
    path.segments.foldLeft(root)(_.resolve(_))

  /** The freshly read tree content is never aliased or mutated afterwards, so the zero-copy wrap is
    * safe (mirrors [[WorkTree.readBytes]]'s converse).
    */
  private def toArray(bytes: IArray[Byte]): Array[Byte] = IArray.genericWrapArray(bytes).toArray

  private def updateFailure(e: Throwable): SnapError =
    SnapError.CannotUpdateWorkingTree(describe(e))

  /** One-line failure detail: the exception message when present (NIO messages are the offending
    * path), else the class name — mirroring [[Store]]'s/[[WorkTree]]'s boundary.
    */
  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  /** The named effect boundary (conventions §"Language & style"): converts a thrown NIO failure
    * into a typed error exactly once, at the edge.
    */
  private def attempt[A](thunk: => A): Either[SnapError, A] =
    Try(thunk) match
      case Success(value) => Right(value)
      case Failure(e)     => Left(updateFailure(e))

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
  * shared filesystem-mutation primitive behind `revert` (T12) and, later, `merge` (T17) — "one
  * canonical implementation per concept" (DESIGN §1). Given the [[Tree]] value that currently
  * matches the working directory on disk (every caller requires a clean tree first — SPEC §2/§10,
  * R27) and the fully computed target tree, brings the filesystem to match `target` exactly, in
  * four ordered steps (R70's wording, verbatim order):
  *
  *   1. **Remove blockers**: delete every tracked file present in `current` but absent from
  *      `target`, then prune any directory this leaves empty. A file that occupies a path the
  *      target needs as a directory is removed here because it is exactly such a current-only path
  *      (its own tracked entry disappears in the target — R70's "removes files blocking required
  *      directories"); a *directory* that occupies a path the target needs as a plain file is
  *      removed here too, as a now-empty leftover, once every tracked file beneath it — necessarily
  *      also current-only, since both trees are prefix-free (R25) — has been deleted. Both
  *      directions of the file/directory transition therefore collapse to the same two-step
  *      operation (Notes / decisions).
  *   1. **Create directories**: for every path about to be written, ensure its parent chain exists.
  *   1. **Write targets**: write the new/changed bytes for every path whose target content differs
  *      from (or is new relative to) `current`.
  *   1. **Remove newly empty directories**: a final sweep, matching R70's wording verbatim — a
  *      no-op in practice once step 1 has run (writing bytes never empties a directory), kept as an
  *      explicit step for parity with the spec text and as a defensive safety net.
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

  /** Root-level metadata directory, never walked into or deleted here (SPEC §2/§10, R16) — mirrors
    * [[WorkTree]]'s exclusion. Every [[SnapPath]] already excludes it by construction
    * ([[SnapPath.parse]]'s `ReservedFirstSegment`), so this guard only matters for
    * [[pruneEmptyDirectories]]'s directory walk, which otherwise has no reason to visit it.
    */
  private val MetadataDirName = ".snap"

  /** Brings the working directory at `root` from `current` to `target` (see the class doc for the
    * four-step order). Every step folds over a [[Tree]]-derived, `Utf8Order`-sorted `Vector`, so
    * the sequence of filesystem operations is a deterministic function of the two tree values
    * alone.
    */
  def install(root: Path, current: Tree, target: Tree): Either[SnapError, Unit] =
    val removed = current.paths.filterNot(target.contains)
    val written = target.paths.filter(p => !sameContent(current.get(p), target.get(p)))
    for
      _ <- deletePaths(root, removed)
      _ <- pruneEmptyDirectories(root)
      _ <- ensureParents(root, written)
      _ <- writePaths(root, target, written)
      _ <- pruneEmptyDirectories(root)
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

  /** Removes every directory under `root` with no remaining entries, deepest first, skipping `root`
    * itself and the `.snap` metadata directory. A directory still holding any entry — a tracked
    * file or a non-empty subdirectory — is never touched, so this is safe to run at any point and
    * idempotent (running it twice in a row, as [[install]] does, is a no-op the second time).
    */
  private def pruneEmptyDirectories(root: Path): Either[SnapError, Unit] =
    def prune(dir: Path): Either[SnapError, Boolean] =
      childNames(dir).flatMap { names =>
        val kept = names.foldLeft[Either[SnapError, Vector[String]]](Right(Vector.empty)) {
          (acc, name) =>
            acc.flatMap { keep =>
              val entry = dir.resolve(name)
              if Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) then
                prune(entry).map(isEmpty => if isEmpty then keep else keep :+ name)
              else Right(keep :+ name)
            }
        }
        kept.flatMap { remaining =>
          if remaining.isEmpty then attempt(Files.delete(dir)).map(_ => true) else Right(false)
        }
      }
    childNames(root).flatMap { names =>
      names.filterNot(_ == MetadataDirName).foldLeft[Either[SnapError, Unit]](Right(())) {
        (acc, name) =>
          acc.flatMap { _ =>
            val entry = root.resolve(name)
            if Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) then prune(entry).map(_ => ())
            else Right(())
          }
      }
    }

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

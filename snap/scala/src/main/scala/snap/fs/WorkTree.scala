package snap.fs

import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Tree
import snap.core.Utf8Order

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** Working-tree scanner (SPEC §2, §10; DESIGN §7; R16–R21, R26, R104): reads the repository root
  * into a [[Tree]] — every regular file below the root except the root-level `.snap` namespace.
  *
  * Rules, in precedence order:
  *
  *   1. Any symlink, FIFO, socket, device, or other non-regular entry anywhere in the walk fails
  *      with the pinned `unsupported working tree entry: <path>` **before any other outcome** (R21,
  *      R104 — never followed, never silently ignored). The whole walk completes classification
  *      before any path validation or content read, so an unsupported entry wins even over an
  *      invalid path that sorts earlier.
  *   1. Every regular file's root-relative path must be a valid tracked path ([[SnapPath.parse]],
  *      R23) — segments are joined with `/` explicitly, never with the platform separator.
  *   1. Directories are implicit: they are recursed into but never tracked, so empty directories
  *      are invisible (R19; test 25's premise). The root-level entry named `.snap` is excluded when
  *      it is a real directory (`.snap/` metadata is never part of the tracked tree, R16, invariant
  *      8) or a regular file (T10: a regular file cannot collide with metadata creation, so it is
  *      excluded rather than tracked); a symlink or other non-directory, non-regular root-level
  *      `.snap` is reported as an unsupported entry (rule 1 above) rather than silently skipped —
  *      §2's MUST-report/MUST-NOT-follow wins over the metadata exclusion for anything that isn't
  *      genuinely the metadata directory (D25). A *nested* `sub/.snap` IS tracked regardless of
  *      kind (D13).
  *
  * Determinism: children of every directory are visited in `Utf8Order` of their names — never in
  * directory-listing order — so the walk order (and therefore which failure is reported when
  * several exist) is a pure function of the filesystem state. Content is read byte-level
  * (`Files.readAllBytes`), never through a charset (gotcha 7).
  *
  * This object sits at the filesystem effect boundary (same pattern as [[Store]]): NIO failures are
  * converted to typed errors here via `Try`/`Using`; no exception escapes as control flow (D4).
  * Scanning performs no filesystem mutation (R103).
  */
object WorkTree:

  /** Scans the repository rooted at `root` into a tree. Read-only. */
  def scan(root: Path): Either[SnapError, Tree] =
    walk(root).flatMap { files =>
      traverse(files) { (rel, abs) =>
        for
          path <- SnapPath.parse(rel).left.map(_ => SnapError.InvalidWorkTreePath(rel))
          bytes <- readBytes(abs)
        yield (path, bytes)
      }.map(Tree.from)
    }

  /** How one directory entry participates in the scan, judged WITHOUT following links
    * (`LinkOption.NOFOLLOW_LINKS` — a symlink is unsupported even when its target is a regular
    * file, R21).
    */
  private enum Kind:
    case Regular, Directory, Unsupported

  /** Depth-first walk from `root`, children in `Utf8Order` of their names: the regular files as
    * `(root-relative path text, absolute path)` in walk order, or the first unsupported entry (in
    * walk order) as the typed error.
    */
  private def walk(root: Path): Either[SnapError, Vector[(String, Path)]] =
    def go(dir: Path, prefix: String): Either[SnapError, Vector[(String, Path)]] =
      children(dir).flatMap { names =>
        names.foldLeft[Either[SnapError, Vector[(String, Path)]]](Right(Vector.empty)) {
          (acc, name) =>
            acc.flatMap { out =>
              val entry = dir.resolve(name)
              val rel = if prefix.isEmpty then name else s"$prefix/$name"
              val isRootSnap = prefix.isEmpty && name == ".snap"
              classify(entry).flatMap {
                // Root-level metadata: excluded whatever its kind, provided it IS the real
                // directory or a regular file (T10) — never a symlink or other special entry, which
                // falls through to the ordinary Unsupported case below and is reported (D25; §2's
                // MUST-report/MUST-NOT-follow wins over the metadata exclusion).
                case Kind.Directory if isRootSnap => Right(out) // root-level metadata dir (R16)
                case Kind.Regular if isRootSnap   => Right(out) // regular file named .snap (T10)
                case Kind.Regular                 => Right(out :+ (rel, entry))
                case Kind.Directory               => go(entry, rel).map(out ++ _)
                case Kind.Unsupported             => Left(SnapError.UnsupportedWorkTreeEntry(rel))
              }
            }
        }
      }
    go(root, "")

  /** One directory's entry names, sorted by `Utf8Order` (directory-listing order is
    * filesystem-dependent and must never feed a decision).
    */
  private def children(dir: Path): Either[SnapError, Vector[String]] =
    Using(Files.newDirectoryStream(dir)) { stream =>
      stream.iterator.asScala.map(_.getFileName.toString).toVector.sorted(Utf8Order)
    } match
      case Success(names) => Right(names)
      case Failure(e)     => Left(readFailure(e))

  private def classify(entry: Path): Either[SnapError, Kind] =
    attempt(Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))
      .map { attrs =>
        if attrs.isRegularFile then Kind.Regular
        else if attrs.isDirectory then Kind.Directory
        else Kind.Unsupported
      }

  /** Byte-level content read (gotcha 7). The freshly read array is never aliased or mutated
    * afterwards, so the zero-copy wrap is safe.
    */
  private def readBytes(file: Path): Either[SnapError, IArray[Byte]] =
    attempt(Files.readAllBytes(file)).map(IArray.unsafeFromArray)

  private def readFailure(e: Throwable): SnapError =
    SnapError.CannotReadWorkTree(describe(e))

  /** One-line failure detail: the exception message when present (NIO messages are the offending
    * path), else the class name — mirroring [[Store]]'s boundary.
    */
  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  /** The named effect boundary (conventions §"Language & style"): converts a thrown NIO failure
    * into a typed error exactly once, at the edge.
    */
  private def attempt[A](thunk: => A): Either[SnapError, A] =
    Try(thunk) match
      case Success(value) => Right(value)
      case Failure(e)     => Left(readFailure(e))

  private def traverse[A, B](items: Vector[A])(
      f: A => Either[SnapError, B]
  ): Either[SnapError, Vector[B]] =
    items.foldLeft[Either[SnapError, Vector[B]]](Right(Vector.empty)) { (acc, item) =>
      acc.flatMap(out => f(item).map(out :+ _))
    }

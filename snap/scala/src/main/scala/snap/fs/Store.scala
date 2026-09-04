package snap.fs

import snap.core.Repo
import snap.core.Repository
import snap.core.SnapError
import snap.core.TextTokens
import snap.json.JsonParser
import snap.json.RepoCodec

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** `repository.json` persistence (DESIGN §7, R105, gotcha 10).
  *
  * This object is the filesystem effect boundary: NIO failures are converted to typed errors here
  * via `Try` — exceptions never act as control flow above this layer (D4; same pattern as
  * `JsonParser`'s jawn boundary). Everything else stays pure: reading composes bytes → strict parse
  * → typed decode → structural validation, writing serializes through the one canonical writer.
  *
  * Writes are atomic (R105): canonical bytes go to a fixed-name temp file in the *same directory*
  * as the target, then an `ATOMIC_MOVE` rename replaces the target. Same-directory placement makes
  * a cross-device move impossible by construction (gotcha 10); a crash before the move leaves the
  * target untouched — only the temp file can ever hold partial content.
  */
object Store:

  /** The repository file name inside `.snap/` (SPEC §4.1, R39). */
  val RepositoryFileName: String = "repository.json"

  /** Fixed temp name — deterministic, same directory as the target; single-process access is the
    * spec's model (no concurrent writers), so a constant name is safe.
    */
  private val TempFileName: String = "repository.json.tmp"

  /** Reads and fully loads a repository file: bytes → UTF-8 check → strict JSON parse → typed
    * decode → structural validation (§4.5 steps 1–4). Steps 5–6 are T07's and consume the returned
    * [[Repo.StructurallyValid]]. Performs no filesystem mutation (R103).
    */
  def readRepository(file: Path): Either[SnapError, Repo.StructurallyValid] =
    for
      bytes <- attempt(Files.readAllBytes(file))(readFailure)
      // A valid repository document is UTF-8 text without NUL; `TextTokens.decode`
      // is exactly that gate and never falls back to the platform charset (gotcha 7).
      text <- TextTokens.decode(bytes).toRight(SnapError.RepositoryNotUtf8)
      json <- JsonParser.parse(text)
      repository <- RepoCodec.decode(json)
      valid <- Repo.validate(repository)
    yield valid

  /** Serializes `repository` canonically (D7) and writes it atomically to `file`. */
  def writeRepository(file: Path, repository: Repository): Either[SnapError, Unit] =
    atomicWrite(file, RepoCodec.encodeBytes(repository))

  /** Atomic byte write: stage to the same-directory temp file, then rename over the target. */
  def atomicWrite(target: Path, bytes: Array[Byte]): Either[SnapError, Unit] =
    stage(target, bytes).flatMap(temp => commit(temp, target))

  /** Step 1 of the atomic write: the full content lands in `<dir>/repository.json.tmp`. The target
    * is not touched. Exposed to the package so tests can assert the crash-window invariant (a
    * failure after staging leaves the target byte-identical).
    */
  private[fs] def stage(target: Path, bytes: Array[Byte]): Either[SnapError, Path] =
    val temp = tempPathFor(target)
    attempt(
      Files.write(
        temp,
        bytes,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
    )(writeFailure).map(_ => temp)

  /** Step 2: atomically rename the staged temp file over the target (same directory, so the move
    * can never cross devices).
    */
  private[fs] def commit(temp: Path, target: Path): Either[SnapError, Unit] =
    attempt(
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    )(writeFailure).map(_ => ())

  private[fs] def tempPathFor(target: Path): Path =
    target.resolveSibling(TempFileName)

  private def readFailure(e: Throwable): SnapError =
    SnapError.CannotReadRepository(describe(e))

  private def writeFailure(e: Throwable): SnapError =
    SnapError.CannotWriteRepository(describe(e))

  /** One-line failure detail: the exception message when present (NIO messages are the offending
    * path), else the class name. Deterministic for a given filesystem state.
    */
  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  /** The named effect boundary (conventions §"Language & style"): converts a thrown NIO failure
    * into a typed error exactly once, at the edge.
    */
  private def attempt[A](thunk: => A)(onError: Throwable => SnapError): Either[SnapError, A] =
    Try(thunk) match
      case Success(value) => Right(value)
      case Failure(e)     => Left(onError(e))

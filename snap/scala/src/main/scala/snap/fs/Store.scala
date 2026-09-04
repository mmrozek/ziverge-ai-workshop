package snap.fs

import snap.core.ContributorId
import snap.core.Repo
import snap.core.Repository
import snap.core.SnapError
import snap.core.TextTokens
import snap.json.ConfigCodec
import snap.json.JsonParser
import snap.json.RepoCodec

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** `repository.json` and contributor-configuration persistence (DESIGN §7, R105, gotcha 10; config
  * IO added T09).
  *
  * This object is the filesystem effect boundary: NIO failures are converted to typed errors here
  * via `Try` — exceptions never act as control flow above this layer (D4; same pattern as
  * `JsonParser`'s jawn boundary). Everything else stays pure: reading composes bytes → strict parse
  * → typed decode → structural validation, writing serializes through the one canonical writer.
  *
  * Writes are atomic (R105): canonical bytes go to a `<target-filename>.tmp` temp file in the *same
  * directory* as the target (PR4/CR11 — the name is derived from the target, so the repository
  * writer and either configuration writer never share a temp path), then an `ATOMIC_MOVE` rename
  * replaces the target. Same-directory placement makes a cross-device move impossible by
  * construction (gotcha 10); a crash before the move leaves the target untouched — only the temp
  * file can ever hold partial content.
  */
object Store:

  /** The repository file name inside `.snap/` (SPEC §4.1, R39). */
  val RepositoryFileName: String = "repository.json"

  /** Temp file suffix — deterministic, same directory as the target (PR4/CR11); single-process
    * access is the spec's model (no concurrent writers), so `<target-filename>.tmp` is safe and
    * cannot collide between the repository writer and either configuration writer, unlike a single
    * shared name.
    */
  private val TempFileSuffix: String = ".tmp"

  /** Reads and fully loads a repository file: bytes → UTF-8 check → strict JSON parse → typed
    * decode → full validation (§4.5 steps 1–6, [[Repo.validateFully]] — T07). The returned
    * [[Repo.Valid]] carries the materialized frontier tree. Performs no filesystem mutation (R103).
    */
  def readRepository(file: Path): Either[SnapError, Repo.Valid] =
    for
      bytes <- attempt(Files.readAllBytes(file))(readFailure)
      // UTF-8 validity only (CR-NUL) — never falls back to the platform charset (gotcha 7). A raw
      // NUL is valid UTF-8 and falls through to the JSON parser, which reports its own positioned
      // `invalid JSON` diagnostic rather than being pre-empted here.
      text <- TextTokens.decodeUtf8(bytes).toRight(SnapError.RepositoryNotUtf8)
      json <- JsonParser.parse(text)
      repository <- RepoCodec.decode(json)
      valid <- Repo.validateFully(repository)
    yield valid

  /** Serializes `repository` canonically (D7) and writes it atomically to `file`. */
  def writeRepository(file: Path, repository: Repository): Either[SnapError, Unit] =
    atomicWrite(file, RepoCodec.encodeBytes(repository))

  /** Atomic byte write: stage to the same-directory temp file, then rename over the target.
    * `onError` lets other atomic writers in this object (config — T09) report their own diagnostic
    * class instead of [[SnapError.CannotWriteRepository]]; it defaults to the repository failure so
    * every existing call site is unaffected.
    */
  def atomicWrite(
      target: Path,
      bytes: Array[Byte],
      onError: Throwable => SnapError = writeFailure
  ): Either[SnapError, Unit] =
    stage(target, bytes, onError).flatMap(temp => commit(temp, target, onError))

  /** Step 1 of the atomic write: the full content lands in `<dir>/<target-filename>.tmp`
    * (PR4/CR11). The target is not touched. Exposed to the package so tests can assert the
    * crash-window invariant (a failure after staging leaves the target byte-identical).
    */
  private[fs] def stage(
      target: Path,
      bytes: Array[Byte],
      onError: Throwable => SnapError = writeFailure
  ): Either[SnapError, Path] =
    val temp = tempPathFor(target)
    attempt(
      Files.write(
        temp,
        bytes,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
    )(onError).map(_ => temp)

  /** Step 2: atomically rename the staged temp file over the target (same directory, so the move
    * can never cross devices).
    */
  private[fs] def commit(
      temp: Path,
      target: Path,
      onError: Throwable => SnapError = writeFailure
  ): Either[SnapError, Unit] =
    attempt(
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    )(onError).map(_ => ())

  private[fs] def tempPathFor(target: Path): Path =
    target.resolveSibling(target.getFileName.toString + TempFileSuffix)

  private def readFailure(e: Throwable): SnapError =
    SnapError.CannotReadRepository(describe(e))

  private def writeFailure(e: Throwable): SnapError =
    SnapError.CannotWriteRepository(describe(e))

  // ---------------------------------------------------------------- config (T09 additions)

  /** `.snap/config.json` file name (SPEC §8, R99). */
  val ConfigFileName: String = "config.json"

  /** `$HOME/.snapconfig.json` file name (SPEC §8, R99). */
  val GlobalConfigFileName: String = ".snapconfig.json"

  /** Reads and validates one contributor-configuration file (SPEC §8, R99): `None` when `file` is
    * absent (not an error), the validated id when a present file decodes cleanly, or the
    * read/parse/ decode error otherwise — the same bytes → UTF-8 → strict JSON → typed decode
    * pipeline as [[readRepository]], so a malformed file, an unknown/duplicate field, or an invalid
    * id is reported exactly when R99 requires it (only for a file that is actually read). Performs
    * no filesystem mutation.
    *
    * Absence is detected by attempting the read, not a prior `Files.exists` gate (CR10): a
    * `NoSuchFileException` is "no value" (R99), while any other I/O failure (e.g. a permission
    * error) is [[SnapError.CannotReadConfig]] rather than being silently folded into "absent" — the
    * two outcomes are observably different (an unreadable local config must not fall back to
    * global, or an unreadable global config to "no value").
    */
  def readConfig(file: Path): Either[SnapError, Option[ContributorId]] =
    Try(Files.readAllBytes(file)) match
      case Failure(_: NoSuchFileException) => Right(None)
      case Failure(e)                      => Left(readConfigFailure(e))
      case Success(bytes)                  =>
        for
          // UTF-8 validity only (CR-NUL) — see readRepository.
          text <- TextTokens.decodeUtf8(bytes).toRight(SnapError.ConfigNotUtf8)
          json <- JsonParser.parse(text)
          id <- ConfigCodec.decode(json)
        yield Some(id)

  /** Serializes `id` canonically (D7) and atomically overwrites `file` completely (SPEC §8:
    * "preserves no unknown fields") — the previous content, if any, is never read first.
    */
  def writeConfig(file: Path, id: ContributorId): Either[SnapError, Unit] =
    atomicWrite(file, ConfigCodec.encodeBytes(id), writeConfigFailure)

  /** Creates `dir` and any missing parent directories (SPEC §7.1: `init`'s target `path` "is
    * created if absent"); a no-op when `dir` already exists, and existing files inside are
    * untouched (test 02's premise).
    */
  def createDirectories(dir: Path): Either[SnapError, Unit] =
    attempt(Files.createDirectories(dir))(createDirectoryFailure).map(_ => ())

  private def readConfigFailure(e: Throwable): SnapError =
    SnapError.CannotReadConfig(describe(e))

  private def writeConfigFailure(e: Throwable): SnapError =
    SnapError.CannotWriteConfig(describe(e))

  private def createDirectoryFailure(e: Throwable): SnapError =
    SnapError.CannotCreateDirectory(describe(e))

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

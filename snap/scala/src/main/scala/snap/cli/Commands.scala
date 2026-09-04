package snap.cli

import snap.core.Repo
import snap.core.SnapError
import snap.fs.Store

import java.nio.file.Path

/** Shared plumbing for repository-backed commands (T10; `status`/`log`/`commit`/`diff` all start
  * the same way): unwrap the discovered root and load the fully validated repository. Kept tiny —
  * command semantics live in the per-command objects.
  */
private[cli] object Commands:

  /** The discovered repository root. `None` is unreachable for a command with
    * `needsRepoDiscovery = true` ([[Cli.run]] resolves the root first), but the type is `Option`,
    * so it is handled rather than forced (same treatment as [[CommandsConfig]]).
    */
  def requireRoot(repoRoot: Option[Path]): Either[SnapError, Path] =
    repoRoot.toRight(SnapError.NotASnapRepository)

  /** `<root>/.snap/repository.json` (SPEC §4.1). */
  def repositoryFile(root: Path): Path =
    root.resolve(".snap").resolve(Store.RepositoryFileName)

  /** Loads and fully validates the repository (§4.5 steps 1–6 via [[Store.readRepository]]); the
    * result carries the materialized current tree. Read-only (R103). Per D11's failure precedence,
    * commands run this BEFORE scanning the working tree.
    */
  def readRepository(root: Path): Either[SnapError, Repo.Valid] =
    Store.readRepository(repositoryFile(root))

  /** Coarse R79 arity for the zero-operand commands (`status`, `log`); T13 owns the exhaustive
    * per-command grammar matrix.
    */
  def expectNoOperands(operands: List[String]): Either[SnapError, Unit] =
    if operands.isEmpty then Right(()) else Left(SnapError.InvalidCommand)

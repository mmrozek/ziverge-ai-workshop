package snap.cli

import snap.core.Repo
import snap.core.SnapError
import snap.fs.Store
import snap.http.Client

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

  /** R78: an explicit `http://`/`https://` prefix marks a repository operand as remote. */
  def isRemoteOperand(operand: String): Boolean =
    operand.startsWith("http://") || operand.startsWith("https://")

  /** R78/R102 (T20): resolves and fully loads a repository operand — `merge <repository>` and
    * `diff --repo <repository>` both route through this one function so a URL is handled
    * identically at either call site (DESIGN §1 "one canonical implementation per concept"). An
    * `http(s)://` operand triggers exactly one GET via [[snap.http.Client]] (redirects never
    * followed, status must be 200, body validated like a local file); anything else is a local path
    * resolved against the process working directory (`env.cwd`, never the discovered repository
    * root — SPEC §7 "Local repository operands resolve against the process working directory").
    */
  def loadRemoteRepository(env: Env, operand: String): Either[SnapError, Repo.Valid] =
    if isRemoteOperand(operand) then Client.fetchRepository(operand)
    else Store.readRepository(repositoryFile(env.cwd.resolve(operand).toAbsolutePath.normalize()))

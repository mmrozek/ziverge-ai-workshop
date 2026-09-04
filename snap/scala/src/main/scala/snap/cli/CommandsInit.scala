package snap.cli

import snap.core.Repository
import snap.core.SnapError
import snap.fs.Store

import java.nio.file.Files
import java.nio.file.Path

/** `snap init [path]` (SPEC §7.1, R80–R81). No repository discovery precedes it ([[Command
  * .needsRepoDiscovery]]) — `init`'s job is to create one, not find one — so `repoRoot` is always
  * `None` here.
  */
object CommandsInit:

  val handler: CommandHandler = (env, _, operands) =>
    for
      rawPath <- parsePath(operands)
      target = resolveTarget(env, rawPath)
      _ <- checkNotInsideRepository(target)
      _ <- Store.createDirectories(target)
      _ <- Store.createDirectories(snapDir(target))
      _ <- Store.writeRepository(repositoryFile(target), Repository.empty)
    yield "()\n"

  /** `init`'s only operand is an optional path (SPEC §7.1: "`path` defaults to `.`"); it takes no
    * options at all, so a single `--`-shaped operand is rejected here rather than accepted as a
    * (legal but bizarre) directory name — the coarse reading of R79's "unknown options... are
    * errors" for a command with zero defined options (T13 owns the exhaustive matrix; recorded in
    * the T09 task notes).
    */
  private def parsePath(operands: List[String]): Either[SnapError, String] = operands match
    case Nil                                   => Right(".")
    case path :: Nil if !path.startsWith("--") => Right(path)
    case _                                     => Left(SnapError.InvalidCommand)

  /** Local repository operands resolve against the process working directory (SPEC §7 preamble). */
  private def resolveTarget(env: Env, rawPath: String): Path =
    env.cwd.toAbsolutePath.normalize().resolve(rawPath).normalize()

  private def snapDir(target: Path): Path = target.resolve(".snap")

  private def repositoryFile(target: Path): Path = snapDir(target).resolve(Store.RepositoryFileName)

  /** Walks up from `target` (SPEC §7.1: reinitializing is an error; initializing inside an existing
    * repository is an error). The first `.snap` found decides which of the two applies: at `target`
    * itself it's a reinit, at any ancestor it's nesting. Read-only — never creates anything, so a
    * failure here leaves the filesystem untouched (both errors require "no `.snap` is created").
    */
  private def checkNotInsideRepository(target: Path): Either[SnapError, Unit] =
    @annotation.tailrec
    def loop(dir: Path): Either[SnapError, Unit] =
      if Files.isDirectory(dir.resolve(".snap")) then
        if dir == target then Left(SnapError.RepositoryAlreadyExists(target.toString))
        else Left(SnapError.CannotInitializeInsideRepository(dir.toString))
      else
        Option(dir.getParent) match
          case Some(parent) => loop(parent)
          case None         => Right(())
    loop(target)

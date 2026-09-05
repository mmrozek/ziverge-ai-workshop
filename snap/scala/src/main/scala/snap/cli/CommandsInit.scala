package snap.cli

import snap.core.Repository
import snap.core.SnapError
import snap.fs.Store

import java.nio.file.Files
import java.nio.file.LinkOption
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
    yield CommandOutput(ResultKind.Success("Initialized repository"), "()\n")

  /** `init`'s only operand is an optional path (SPEC §7.1: "`path` defaults to `.`"); it takes no
    * options at all, so a single `--`-shaped operand is rejected here rather than accepted as a
    * (legal but bizarre) directory name — the coarse reading of R79's "unknown options... are
    * errors" for a command with zero defined options. An explicit empty-string operand is rejected
    * too (T13/CR14: `snap init ""` must not silently default to `.` the way a genuinely absent
    * operand does) — [[Grammar.initRule]] already rejects both shapes before this handler is ever
    * reached from [[Cli.run]]; the same guard is kept here so the handler is correct on its own,
    * not only behind that gate (e.g. a test or future caller invoking [[handler]] directly).
    */
  private def parsePath(operands: List[String]): Either[SnapError, String] = operands match
    case Nil                                                    => Right(".")
    case path :: Nil if path.nonEmpty && !path.startsWith("--") => Right(path)
    case _                                                      => Left(SnapError.InvalidCommand)

  /** Local repository operands resolve against the process working directory (SPEC §7 preamble). */
  private def resolveTarget(env: Env, rawPath: String): Path =
    env.cwd.toAbsolutePath.normalize().resolve(rawPath).normalize()

  private def snapDir(target: Path): Path = target.resolve(".snap")

  private def repositoryFile(target: Path): Path = snapDir(target).resolve(Store.RepositoryFileName)

  /** Walks up from `target` (SPEC §7.1: reinitializing is an error; initializing inside an existing
    * repository is an error). The first `.snap` found decides which of the two applies: at `target`
    * itself it's a reinit, at any ancestor it's nesting. Read-only — never creates anything, so a
    * failure here leaves the filesystem untouched (both errors require "no `.snap` is created").
    * `.snap` must be a real directory — checked with `NOFOLLOW_LINKS` (D25) — so a symlinked
    * `.snap` is neither treated as an existing repository to reinit nor as an ancestor to nest
    * inside; `init` then proceeds by its normal rules and fails naturally when it tries to create a
    * directory where the symlink already sits (least-surprising: neither silently follows nor
    * silently succeeds).
    */
  private def checkNotInsideRepository(target: Path): Either[SnapError, Unit] =
    @annotation.tailrec
    def loop(dir: Path): Either[SnapError, Unit] =
      if Files.isDirectory(dir.resolve(".snap"), LinkOption.NOFOLLOW_LINKS) then
        if dir == target then Left(SnapError.RepositoryAlreadyExists(target.toString))
        else Left(SnapError.CannotInitializeInsideRepository(dir.toString))
      else
        Option(dir.getParent) match
          case Some(parent) => loop(parent)
          case None         => Right(())
    loop(target)

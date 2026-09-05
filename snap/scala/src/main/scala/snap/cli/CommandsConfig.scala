package snap.cli

import snap.core.ContributorId
import snap.core.SnapError
import snap.fs.Store

import java.nio.file.Path

/** `snap config [--global] contributor.id <id>` (SPEC §7.2, R82, R98–R100, D10).
  *
  * Grammar (SPEC §7.2's exact positional shape — `--global` optional in its documented position,
  * then the literal `contributor.id`, then the id operand): two shapes are accepted, everything
  * else is [[SnapError.InvalidCommand]] (T13 owns the exhaustive matrix; this is the coarse T09
  * reading).
  *
  * The id is validated *before* anything is written (SPEC §7.2: "Validates the ID before writing"):
  * [[ContributorId.parse]] runs first, so a bad id never touches the filesystem — not even to check
  * whether `HOME` is set for `--global`.
  */
object CommandsConfig:

  private val ContributorIdLiteral = "contributor.id"

  val handler: CommandHandler = (env, repoRoot, operands) =>
    for
      shape <- parseOperands(operands)
      (isGlobal, rawId) = shape
      id <- ContributorId.parse(rawId)
      target <- targetFile(env, repoRoot, isGlobal)
      _ <- Store.writeConfig(target, id)
    yield CommandOutput(ResultKind.Raw, "")

  private def parseOperands(operands: List[String]): Either[SnapError, (Boolean, String)] =
    operands match
      case ContributorIdLiteral :: id :: Nil               => Right((false, id))
      case "--global" :: ContributorIdLiteral :: id :: Nil => Right((true, id))
      case _                                               => Left(SnapError.InvalidCommand)

  /** Without `--global`: `.snap/config.json` in the already-discovered nearest repository (D10 made
    * discovery mandatory for this branch — see [[Command.needsRepoDiscovery]] — so `repoRoot` is
    * always `Some` here; the `None` arm is unreachable but the type is `Option`, so it is handled
    * rather than forced). With `--global`: `$HOME/.snapconfig.json`, needing no repository at all —
    * only a `HOME` entry in the environment (R99's read-side "unavailable, not an error" doesn't
    * apply to a *write*, which has nowhere to go without `HOME`).
    */
  private def targetFile(
      env: Env,
      repoRoot: Option[Path],
      isGlobal: Boolean
  ): Either[SnapError, Path] =
    if isGlobal then Config.globalFile(env).toRight(SnapError.GlobalConfigUnavailable)
    else repoRoot.map(Config.localFile).toRight(SnapError.NotASnapRepository)

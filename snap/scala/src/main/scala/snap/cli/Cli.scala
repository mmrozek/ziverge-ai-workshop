package snap.cli

import snap.core.SnapError

import java.nio.file.Files
import java.nio.file.Path

/** The recognized command surface (SPEC §7). One case per command; `--serve` and `--version` are
  * flag-shaped tokens but behave as commands (SPEC-NOTES §4.1). T08 seeds every case except
  * `Version` as an unimplemented stub (see [[Cli.defaultCommands]]); T09 onward replace stubs
  * command-by-command without touching this enum or the dispatch below.
  */
enum Command:
  case Init, Config, Status, Log, Commit, Diff, Revert, Merge, Serve, Version

object Command:
  private val byToken: Map[String, Command] = Map(
    "init" -> Init,
    "config" -> Config,
    "status" -> Status,
    "log" -> Log,
    "commit" -> Commit,
    "diff" -> Diff,
    "revert" -> Revert,
    "merge" -> Merge,
    "--serve" -> Serve,
    "--version" -> Version
  )

  def fromToken(token: String): Option[Command] = byToken.get(token)

  /** Commands resolved against the nearest repository before dispatch (R77). `init` creates a
    * repository rather than requiring one to already exist, and `--version` never touches the
    * filesystem at all (R91, DESIGN §10 gotcha 9) — every other command needs one, **except**
    * `config --global`, which needs no repository (D10; SPEC §7.2). That exemption depends on the
    * actual operands, not just the command, so this takes the whole [[ParsedCommand]] (T09 — see
    * T08's Notes / decisions for the coarse treatment this replaces).
    */
  def needsRepoDiscovery(parsed: ParsedCommand): Boolean = parsed.command match
    case Init | Version => false
    case Config         => !isGlobalConfig(parsed.operands)
    case _              => true

  /** D10's `--global` exemption is decided purely positionally (SPEC §7.2: `--global` is the first
    * token when present) — good enough to gate repository discovery; [[CommandsConfig]] itself does
    * the exhaustive grammar check (T13 tightens both further).
    */
  private def isGlobalConfig(operands: List[String]): Boolean =
    operands.headOption.contains("--global")

/** A handler for one command: given the effect boundary, the discovered repository root (`None`
  * when the command doesn't need one, per [[Command.needsRepoDiscovery]]), and the command's own
  * operands (unvalidated past [[Cli.parse]]'s coarse check), produce either the command's stdout
  * text or a [[SnapError]]. Every command in the map has this exact shape, which is what lets later
  * tasks swap a stub for a real implementation without touching [[Cli.run]]. Operands were added in
  * T09: `init`/`config` are the first handlers that need their own arguments (T08's stub never
  * did).
  */
type CommandHandler = (Env, Option[Path], List[String]) => Either[SnapError, String]

/** A recognized command line, past the coarse grammar checks [[Cli.parse]] can make on its own.
  * `operands` are the remaining tokens, unvalidated — each command's exact arity/option grammar
  * (R79) is T13's exhaustive matrix, not T08's.
  */
final case class ParsedCommand(command: Command, operands: List[String])

/** CLI grammar core and dispatch (DESIGN §2/§8, R79). `Main` builds an [[Env]] and calls
  * [[Cli.run]] exactly once; every other module in this file is a pure function of its arguments —
  * no `sys.env`/`System.getenv` call exists here, only reads of the `Env` passed in.
  */
object Cli:

  /** Exact plain output for `--version` (SPEC §7.10, D6): hardcoded `snap 1.0.0`, pinned by test
    * 14's regex and test 28's byte-exact case. No repository discovery precedes it (R91, gotcha 9).
    */
  private val versionOutput = "snap 1.0.0\n"

  private val stub: CommandHandler = (_, _, _) => Left(SnapError.NotImplemented)

  /** Every non-`Version` command dispatches to the "not implemented" stub until its owning task
    * lands (T09 `init`/`config`, T10 `status`/`log`/`commit`, T11 `diff`, T12 `revert`, T17
    * `merge`, T19 `--serve`). `run`'s `commands` parameter defaults to this map; tests override
    * individual entries (e.g. to simulate an unexpected exception for the exit-2 path) without
    * touching `Cli`. T09 replaced the `Init`/`Config` entries with their real handlers; T10
    * replaced `Status`/`Log`/`Commit` and gave `Diff` its scan-precedence seam ([[CommandsDiff]] —
    * rendering is still stubbed until T11).
    */
  val defaultCommands: Map[Command, CommandHandler] =
    Command.values.iterator
      .filterNot(_ == Command.Version)
      .map(c => c -> stub)
      .toMap
      .updated(Command.Init, CommandsInit.handler)
      .updated(Command.Config, CommandsConfig.handler)
      .updated(Command.Status, CommandsStatus.handler)
      .updated(Command.Log, CommandsLog.handler)
      .updated(Command.Commit, CommandsCommit.handler)
      .updated(Command.Diff, CommandsDiff.handler)

  /** Runs one CLI invocation to completion and returns the process exit code (0/1 — R107; a
    * top-level catch-all for exit 2 lives in `Main`, not here, since it must also catch anything
    * `run` itself fails to anticipate). Order, per the task and R95/gotcha 9:
    *   1. validate `SNAP_COLOR` (cheap — reads the already-captured `Env.env` map, so it's not the
    *      "eager work" gotcha 9 forbids) — before *any* command, including `--version` (test 28);
    *   2. parse the command line;
    *   3. `--version` short-circuits with no repo discovery;
    *   4. every other command resolves a repository first when it needs one (R77), then dispatches.
    */
  def run(
      env: Env,
      args: List[String],
      commands: Map[Command, CommandHandler] = defaultCommands
  ): Int =
    validateSnapColor(env) match
      case Left(err) => emit(env, Left(err))
      case Right(()) =>
        parse(args) match
          case Left(err)                                    => emit(env, Left(err))
          case Right(ParsedCommand(Command.Version, _))     => emit(env, Right(versionOutput))
          case Right(parsed @ ParsedCommand(cmd, operands)) =>
            val outcome =
              (if Command.needsRepoDiscovery(parsed) then discoverRepo(env).map(Some.apply)
               else Right(None))
                .flatMap(root => commands(cmd)(env, root, operands))
            emit(env, outcome)

  /** Coarse command-line grammar (R79): recognizes the command surface and the one arity rule T08
    * itself needs (`--version` takes no operands). Everything else — per-command arity, option
    * positions, `diff`'s distinct usage channel — is T13's exhaustive matrix; unrecognized shapes
    * fall through to the same [[SnapError.InvalidCommand]] that matrix will keep using.
    */
  private[cli] def parse(args: List[String]): Either[SnapError, ParsedCommand] =
    args match
      case Nil           => Left(SnapError.InvalidCommand)
      case token :: rest =>
        Command.fromToken(token) match
          case None                                   => Left(SnapError.InvalidCommand)
          case Some(Command.Version) if rest.nonEmpty => Left(SnapError.InvalidCommand)
          case Some(cmd)                              => Right(ParsedCommand(cmd, rest))

  /** `SNAP_COLOR` value validation (R95), independent of terminal-mode *selection* (T22): unset,
    * `auto`, `always`, and `never` are the only legal values. Reads only `env.env` — never
    * `sys.env`/`System.getenv` directly (negative constraint, T08 acceptance criteria).
    */
  private[cli] def validateSnapColor(env: Env): Either[SnapError, Unit] =
    env.env.get("SNAP_COLOR") match
      case None | Some("auto") | Some("always") | Some("never") => Right(())
      case Some(_)                                              => Left(SnapError.InvalidSnapColor)

  /** Repository discovery (R77): walk from `cwd` to the filesystem root looking for a `.snap`
    * directory. Stops (fails) once `getParent` is exhausted — the filesystem root's parent is
    * `null`, wrapped by `Option` rather than compared directly (no `null` literal, DisableSyntax
    * `noNulls`).
    */
  private[cli] def discoverRepo(env: Env): Either[SnapError, Path] =
    @annotation.tailrec
    def loop(dir: Path): Either[SnapError, Path] =
      if Files.isDirectory(dir.resolve(".snap")) then Right(dir)
      else
        Option(dir.getParent) match
          case Some(parent) => loop(parent)
          case None         => Left(SnapError.NotASnapRepository)
    loop(env.cwd.toAbsolutePath.normalize())

  private def emit(env: Env, outcome: Either[SnapError, String]): Int =
    outcome match
      case Left(err) =>
        Presentation.Plain.error(env, err.message)
        1
      case Right(text) =>
        Presentation.Plain.result(env, text)
        0

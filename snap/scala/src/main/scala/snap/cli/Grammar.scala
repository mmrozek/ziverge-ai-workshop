package snap.cli

import snap.core.SnapError

/** Exhaustive per-command argument grammar (SPEC §7 preamble, R79): "Options occur exactly in the
  * positions shown below and may appear at most once. Unknown options, extra operands, and missing
  * option values are errors." One rule per [[Command]], evaluated by [[Cli.run]] directly on the
  * raw operand list — BEFORE repository discovery or any other filesystem IO (phase-1 review CR7: a
  * grammar error in a non-repository directory must print the grammar error, never `not a Snap
  * repository`; [[CliSuite]] pins this negative constraint).
  *
  * Declarative and per-command by design (T13 Notes / decisions): [[rules]] is a plain
  * `Map[Command, Rule]`, so a later command slots in its own entry without touching any other
  * command's rule or this file's dispatch shape. `merge`'s row here is arity-only, per SPEC §7.8's
  * `snap merge <repository>` — the command's own semantics stay [[SnapError.NotImplemented]] until
  * T17 replaces [[Cli.defaultCommands]]'s stub entry; this file never needs to change when that
  * lands.
  *
  * This is pure syntax only: arity, option positions, and known literal tokens. A command's own
  * semantic value checks (a bad contributor id, an unparsable version, an out-of-range `--serve`
  * port) run afterward, inside its handler, once this gate has already guaranteed the shape is
  * legal — so those checks never need to anticipate a malformed shape.
  *
  * Every command but `diff` reports a grammar violation as the single shared
  * [[SnapError.InvalidCommand]] (`snap: invalid command or arguments`, tests 14/24). `diff` alone
  * uses its own distinct usage channel ([[SnapError.DiffUsage]], DESIGN §8) — carved out by giving
  * its rule a different failure value, not a different mechanism.
  */
object Grammar:

  /** One command's grammar: given its operands (the tokens after the command word itself), decide
    * whether they form a legal invocation.
    */
  type Rule = List[String] => Either[SnapError, Unit]

  private val ok: Either[SnapError, Unit] = Right(())

  private val invalidCommand: Either[SnapError, Unit] = Left(SnapError.InvalidCommand)

  /** `snap --version` (SPEC §7.10): no operands, no options. [[Cli.parse]] already enforces this
    * inline before a [[ParsedCommand]] is even built — `--version` short-circuits ahead of every
    * other command (R91) — so [[Cli.run]] never calls this rule in practice. It is included so
    * [[rules]] stays total over every [[Command]] and the table-driven grammar-matrix test
    * ([[GrammarSuite]]) can exercise every command, including this one, uniformly.
    */
  private def versionRule(operands: List[String]): Either[SnapError, Unit] = operands match
    case Nil => ok
    case _   => invalidCommand

  /** `snap init [path]` (SPEC §7.1): at most one operand, no options at all — a token starting with
    * `--` is an unknown option, never a path (R79: "unknown options... never silently treated as
    * paths"). CR14: an explicit empty-string operand is also rejected here, rather than reaching
    * [[CommandsInit]]'s "defaults to `.`" substitution, which SPEC §7.1 only licenses when the
    * operand is genuinely absent — `snap init ""` must not silently initialize the cwd.
    */
  private def initRule(operands: List[String]): Either[SnapError, Unit] = operands match
    case Nil                                                    => ok
    case path :: Nil if path.nonEmpty && !path.startsWith("--") => ok
    case _                                                      => invalidCommand

  private val ContributorIdLiteral = "contributor.id"

  /** `snap config [--global] contributor.id <id>` (SPEC §7.2): exactly the two documented shapes.
    * Mirrors [[CommandsConfig.parseOperands]]'s own pattern (kept there too, for the command's
    * internal typed parse) — duplicated intentionally so this gate runs before repository discovery
    * (CR7): a non-`--global` `config` command currently discovers a repository before its handler
    * is ever invoked ([[Command.needsRepoDiscovery]]), so without this earlier gate a
    * grammar-invalid `config` outside any repository would wrongly report `not a Snap repository`.
    */
  private def configRule(operands: List[String]): Either[SnapError, Unit] = operands match
    case ContributorIdLiteral :: _ :: Nil               => ok
    case "--global" :: ContributorIdLiteral :: _ :: Nil => ok
    case _                                              => invalidCommand

  /** `snap status` / `snap log` (SPEC §7.3–§7.4): no operands, no options. */
  private def noOperandsRule(operands: List[String]): Either[SnapError, Unit] = operands match
    case Nil => ok
    case _   => invalidCommand

  /** `snap commit <message>` / `snap revert <version>` / `snap merge <repository>` (SPEC
    * §7.5/§7.7/§7.8): exactly one operand, no options at all. A `--`-prefixed operand is treated as
    * an unknown option, matching [[initRule]] — NOT free text, even though none of these three
    * commands defines any option syntax of its own. `merge`'s arity comes straight from SPEC §7.8;
    * its semantics are T17's, not this rule's.
    *
    * Ambiguity resolution (orchestrator, 2026-09-05, phase-2 review finding #2 — see
    * `tasks/T13-cli-grammar.md` Notes / decisions for the full record): SPEC §7's shared preamble
    * ("Unknown options... are errors") is unqualified and applies to every command, and test
    * `24-cli-grammar-matrix.yaml`'s `init --unknown` case additionally asserts `path_not_exists:
    * --unknown` — i.e. a `--`-shaped token must never be consumed as a free-text operand, even for
    * a command whose only operand is free-form text. No provided test anywhere in `snap/tests/`
    * uses a `--`-prefixed free-text operand for `commit`/`revert`/`merge`, so this reading cannot
    * regress the suite. Accepted cost: a commit message or repository path that legitimately begins
    * with `--` is no longer reachable from the CLI (the spec provides no `--` separator) — the same
    * cost the contract already accepts for `init`'s path operand.
    */
  private def oneFreeTextOperandRule(operands: List[String]): Either[SnapError, Unit] =
    operands match
      case value :: Nil if !value.startsWith("--") => ok
      case _                                       => invalidCommand

  /** `snap diff [<old> <new> [--repo <repository>]]` (SPEC §7.6): the three documented shapes.
    * Mirrors [[CommandsDiff]]'s own match exactly, but returns [[SnapError.DiffUsage]] instead of
    * the generic [[SnapError.InvalidCommand]] on failure — `diff`'s distinct usage channel (DESIGN
    * §8; tests 14/24).
    */
  private def diffRule(operands: List[String]): Either[SnapError, Unit] = operands match
    case Nil                            => ok
    case _ :: _ :: Nil                  => ok
    case _ :: _ :: "--repo" :: _ :: Nil => ok
    case _                              => Left(SnapError.DiffUsage)

  /** `snap --serve [port]` (SPEC §7.9): at most one operand, no options. A `--`-prefixed operand is
    * an unknown-option grammar error, matching [[initRule]]/[[oneFreeTextOperandRule]] (phase-2
    * review finding #2 — see [[oneFreeTextOperandRule]]'s doc comment for the full rationale). The
    * operand's actual port *value* (D9: canonical decimal 0–65535) is [[CommandsServe]]'s job,
    * evaluated only after this shape check passes — so [[CommandsServe.parsePort]] never sees a
    * `--`-prefixed argument through [[Cli.run]].
    */
  private def serveRule(operands: List[String]): Either[SnapError, Unit] = operands match
    case Nil                                   => ok
    case port :: Nil if !port.startsWith("--") => ok
    case _                                     => invalidCommand

  /** One entry per [[Command]] case — total, so [[check]] can index it directly. */
  val rules: Map[Command, Rule] = Map(
    Command.Version -> versionRule,
    Command.Init -> initRule,
    Command.Config -> configRule,
    Command.Status -> noOperandsRule,
    Command.Log -> noOperandsRule,
    Command.Commit -> oneFreeTextOperandRule,
    Command.Diff -> diffRule,
    Command.Revert -> oneFreeTextOperandRule,
    Command.Merge -> oneFreeTextOperandRule,
    Command.Serve -> serveRule
  )

  /** Evaluates `command`'s grammar rule against `operands` (SPEC §7 preamble, R79). `rules` has one
    * entry per case of the [[Command]] enum, so this direct map index never misses.
    */
  def check(command: Command, operands: List[String]): Either[SnapError, Unit] =
    rules(command)(operands)

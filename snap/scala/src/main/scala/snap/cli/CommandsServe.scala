package snap.cli

import snap.core.SnapError

/** `snap --serve [port]` (SPEC §7.9, D9). Only the grammar (T13, [[Grammar]]) and the port *value*
  * land now — the actual repository snapshot, HTTP binding, and SIGINT/SIGTERM handling are T19's
  * job ([[SnapError.NotImplemented]] until then, matching every other not-yet-landed command).
  *
  * [[Grammar]] already enforces the shape (at most one operand, no options) before repository
  * discovery ever runs (CR7); this handler's job is D9's port value plus the repository check that
  * SPEC §7.9's own first bullet lists before binding: "Validates and snapshots the current
  * repository at startup." Repository discovery ([[Command.needsRepoDiscovery]] is `true` for
  * `--serve`) already ran before this handler is invoked, so `repoRoot` is always `Some` here —
  * checked with [[Commands.requireRoot]] rather than forced, per this module's `Option`/`Either`
  * convention, before the port value is even parsed. An invalid port therefore still fails before
  * any network call is attempted — this handler makes no other filesystem or network calls at all.
  */
object CommandsServe:

  /** SPEC §7.9: "port defaults to 8765". */
  val DefaultPort: Int = 8765

  /** D9 / SPEC §7.9: the highest 16-bit TCP port number. */
  private val MaxPort: Int = 65535

  val handler: CommandHandler = (_, repoRoot, operands) =>
    val checked =
      for
        root <- Commands.requireRoot(repoRoot)
        port <- parsePort(operands)
      yield (root, port)
    checked match
      case Left(err) => Left(err)
      case Right(_)  => Left(SnapError.NotImplemented)

  /** SPEC §7.9 / D9: the operand defaults to 8765 when absent; otherwise it must be a canonical
    * decimal integer in `0..65535` (`0` asks the OS to select a port). [[Grammar.check]] has
    * already limited `operands` to zero or one element by the time this runs, so the multi-operand
    * branch is unreachable — handled, not forced, per this codebase's `Option`/`Either` convention.
    */
  private[cli] def parsePort(operands: List[String]): Either[SnapError, Int] = operands match
    case Nil        => Right(DefaultPort)
    case raw :: Nil => parsePortValue(raw)
    case _          => Left(SnapError.InvalidCommand)

  /** D9's canonical-decimal rule, mirroring [[Version.parseRevisionText]]'s ASCII-digit/no-leading-
    * zero idiom: every character must be an ASCII digit, and `0` is the only value allowed to start
    * with `0` — so `08` is rejected even though it names an in-range port. `-1`/`abc`/the empty
    * string fail the digit check outright; `65536` passes the shape check but fails the range test.
    * Renders as `invalid port: <arg>`, echoing the raw operand rather than a specific reason —
    * mirroring [[SnapError.InvalidVersionArgument]]'s established pattern for the same class of CLI
    * argument-value error (T11 Notes / decisions; neither test 14 nor 24 pins more than the class).
    */
  private def parsePortValue(raw: String): Either[SnapError, Int] =
    val isCanonicalDecimal =
      raw.nonEmpty && raw.forall(c => c >= '0' && c <= '9') && (raw == "0" || raw.charAt(0) != '0')
    val inRange =
      for
        _ <- Option.when(isCanonicalDecimal)(())
        value <- raw.toIntOption
        bounded <- Option.when(value >= 0 && value <= MaxPort)(value)
      yield bounded
    inRange.toRight(SnapError.InvalidPort(raw))

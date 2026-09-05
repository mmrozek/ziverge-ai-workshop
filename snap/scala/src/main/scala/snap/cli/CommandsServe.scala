package snap.cli

import snap.core.SnapError
import snap.http.Server
import snap.json.RepoCodec

/** `snap --serve [port]` (SPEC §7.9/§9, D9, D20–D21). [[Grammar]] already enforces the shape (at
  * most one operand, no options) before repository discovery ever runs (CR7); this handler's job is
  * everything SPEC §7.9 lists, in its own bullet order:
  *
  *   1. validate and snapshot the repository (`Commands.readRepository`, then
  *      [[RepoCodec.encodeBytes]] — the same canonical serializer that writes `repository.json`,
  *      D7);
  *   2. bind `127.0.0.1:<port>` ([[Server.start]]) — only once step 1 succeeds, so an invalid
  *      repository never binds a port (test 12's final case: exit 1, no URL, no port bound);
  *   3. install the SIGINT/SIGTERM handlers (D21) — BEFORE the ready line, not after (see below);
  *   4. print the plain ready line and flush it (R96 — always plain, regardless of any future
  *      terminal presentation), then block forever ([[Server.blockForever]]) — the process now
  *      exits only via `System.exit(0)` from a signal handler, never by this function returning
  *      normally.
  *
  * Steps 3 and 4 are in this order, not the reverse, because the ready line is precisely the signal
  * the harness (or any caller) uses to decide it may now send SIGINT/SIGTERM (SPEC-NOTES §3.3:
  * "`--serve` readiness is detected by regex on accumulated stdout"). Printing it before the
  * handlers are installed opens a real race — a signal arriving in that window hits the JVM's
  * default disposition (exit 130/143) instead of ours — observed directly: test 12's very next step
  * sends SIGINT immediately after the ready line with no intervening work, which reproduced this
  * every time until the order was fixed (T19 Notes / decisions).
  *
  * Repository discovery ([[Command.needsRepoDiscovery]] is `true` for `--serve`) already ran before
  * this handler is invoked, so `repoRoot` is always `Some` here — checked with
  * [[Commands.requireRoot]] rather than forced, per this module's `Option`/`Either` convention.
  * Port value parsing (D9) runs before the repository is loaded (T13's existing order, kept
  * unchanged): neither ordering is spec-mandated between the two value checks themselves, only that
  * both run before binding (T19 Notes / decisions).
  */
object CommandsServe:

  /** SPEC §7.9: "port defaults to 8765". */
  val DefaultPort: Int = 8765

  /** D9 / SPEC §7.9: the highest 16-bit TCP port number. */
  private val MaxPort: Int = 65535

  val handler: CommandHandler = (env, repoRoot, operands) =>
    for
      root <- Commands.requireRoot(repoRoot)
      port <- parsePort(operands)
      valid <- Commands.readRepository(root)
      instance <- Server.start(RepoCodec.encodeBytes(valid.repository), port)
    yield
      // Installed BEFORE the ready line is printed (see the class doc's race note): once the line
      // is visible, a caller may signal us at any moment, so the handlers must already be live.
      Server.installShutdownHandlers()
      // R96: the ready line is always plain, regardless of the per-stream terminal presentation
      // (T22) `Cli.emit` would otherwise choose for this invocation — bypassing it entirely, by
      // construction (`Presentation.Plain` is named directly, never looked up via `Presenters`).
      // `ResultKind.Raw` is inert either way: `Plain.result` ignores its `kind` argument completely.
      Presentation.Plain.result(env, ResultKind.Raw, Server.readyLine(instance.port))
      // Explicit flush, independent of the sink's own autoFlush setting (Env.real's PrintStream
      // already autoflushes on the embedded LF, but this must hold for any Env, including test
      // fixtures) — SPEC-NOTES §3.3: "the URL line must be flushed unbuffered".
      env.stdout.flush()
      // Never returns in production (see the class doc); type `Nothing` conforms to `CommandOutput`.
      Server.blockForever()

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

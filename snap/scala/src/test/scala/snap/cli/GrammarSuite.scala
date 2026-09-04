package snap.cli

import snap.core.SnapError

/** T13 acceptance criterion: "Table-driven unit test covering every command × {extra operand,
  * unknown option, duplicate option, misplaced option, missing option value}". [[Grammar]] is pure
  * (no filesystem, no [[Env]]), so this drives [[Grammar.check]] directly rather than through
  * [[Cli.run]] — the full pipeline (grammar + repository discovery + handler dispatch) is already
  * covered end to end by [[CliSuite]] and the various `Commands*Suite`s.
  *
  * Not every category applies to every command: `status`, `log`, `commit`, `revert`, `merge`,
  * `--serve`, and `--version` define no option syntax at all (SPEC §7), so "duplicate option" /
  * "misplaced option" / "missing option value" have nothing to exercise for them — those cells are
  * omitted rather than faked. Only `config` (`--global`) and `diff` (`--repo`) define real options,
  * so they are the two rows that exercise every category in the acceptance criterion.
  */
class GrammarSuite extends munit.FunSuite:

  private val invalidCommand: Either[SnapError, Unit] = Left(SnapError.InvalidCommand)
  private val diffUsage: Either[SnapError, Unit] = Left(SnapError.DiffUsage)
  private val ok: Either[SnapError, Unit] = Right(())

  /** One row: a human label, the command, its operands, and the expected [[Grammar.check]] result.
    */
  private final case class Case(
      label: String,
      command: Command,
      operands: List[String],
      expected: Either[SnapError, Unit]
  )

  private def invalid(label: String, command: Command, operands: List[String]): Case =
    Case(label, command, operands, invalidCommand)

  private def diffInvalid(label: String, operands: List[String]): Case =
    Case(label, Command.Diff, operands, diffUsage)

  private def valid(label: String, command: Command, operands: List[String]): Case =
    Case(label, command, operands, ok)

  private val cases: Vector[Case] = Vector(
    // --- --version (SPEC §7.10): no operands, no options ---
    invalid("--version: extra operand", Command.Version, List("extra")),
    valid("--version: no operands", Command.Version, Nil),

    // --- init (SPEC §7.1): 0 or 1 operand, no options ---
    invalid("init: extra operand", Command.Init, List("a", "b")),
    invalid("init: unknown option", Command.Init, List("--unknown")),
    invalid("init: empty operand (CR14)", Command.Init, List("")),
    valid("init: no operand", Command.Init, Nil),
    valid("init: one path operand", Command.Init, List("repo")),

    // --- config (SPEC §7.2): the only command besides diff with real options ---
    invalid("config: extra operand", Command.Config, List("contributor.id", "a@x", "extra")),
    invalid("config: unknown option", Command.Config, List("--bogus", "contributor.id", "a@x")),
    invalid(
      "config: duplicate option",
      Command.Config,
      List("--global", "--global", "contributor.id", "a@x")
    ),
    invalid("config: misplaced option", Command.Config, List("contributor.id", "a@x", "--global")),
    invalid("config: missing option value", Command.Config, List("--global", "contributor.id")),
    valid("config: local shape", Command.Config, List("contributor.id", "a@x")),
    valid("config: --global shape", Command.Config, List("--global", "contributor.id", "a@x")),

    // --- status / log (SPEC §7.3-7.4): no operands, no options ---
    invalid("status: extra operand", Command.Status, List("extra")),
    invalid("status: unknown option", Command.Status, List("--unknown")),
    valid("status: no operands", Command.Status, Nil),
    invalid("log: extra operand", Command.Log, List("extra")),
    invalid("log: unknown option", Command.Log, List("--unknown")),
    valid("log: no operands", Command.Log, Nil),

    // --- commit (SPEC §7.5): exactly one free-text operand, no options ---
    invalid("commit: missing operand", Command.Commit, Nil),
    invalid("commit: extra operand", Command.Commit, List("message", "extra")),
    valid("commit: one operand", Command.Commit, List("message")),
    valid(
      "commit: a '--'-shaped message is free text, not an unknown option (commit has no options)",
      Command.Commit,
      List("--not-an-option")
    ),

    // --- diff (SPEC §7.6): the other command with real options ---
    diffInvalid("diff: extra operand (three bare operands)", List("a", "b", "c")),
    diffInvalid("diff: unknown option", List("a", "b", "--unknown", "c")),
    diffInvalid("diff: duplicate option", List("a", "b", "--repo", "r", "--repo", "r2")),
    diffInvalid("diff: misplaced option", List("--repo", "r", "a", "b")),
    diffInvalid("diff: missing option value", List("a", "b", "--repo")),
    valid("diff: no operands", Command.Diff, Nil),
    valid("diff: two versions", Command.Diff, List("a", "b")),
    valid("diff: two versions + --repo", Command.Diff, List("a", "b", "--repo", "r")),

    // --- revert (SPEC §7.7): exactly one free-text operand, no options ---
    invalid("revert: missing operand", Command.Revert, Nil),
    invalid("revert: extra operand", Command.Revert, List("()", "extra")),
    valid("revert: one operand", Command.Revert, List("()")),

    // --- merge (SPEC §7.8): exactly one free-text operand, no options (arity-only here — T17
    // owns the semantics; this rule never changes when that handler replaces the stub) ---
    invalid("merge: missing operand", Command.Merge, Nil),
    invalid("merge: extra operand", Command.Merge, List("repo", "extra")),
    valid("merge: one operand", Command.Merge, List("repo")),

    // --- --serve (SPEC §7.9): 0 or 1 operand, no options; the port *value* is CommandsServe's
    // job, not Grammar's ---
    invalid("--serve: extra operand", Command.Serve, List("0", "extra")),
    valid("--serve: no operand (default port)", Command.Serve, Nil),
    valid("--serve: one operand", Command.Serve, List("0"))
  )

  cases.foreach { c =>
    test(s"Grammar.check(${c.command}, ${c.operands}) — ${c.label}") {
      assertEquals(Grammar.check(c.command, c.operands), c.expected)
    }
  }

  test("Grammar.rules has exactly one entry per Command case") {
    assertEquals(Grammar.rules.keySet, Command.values.toSet)
  }

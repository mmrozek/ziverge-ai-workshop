// `Main` lives in the default package (build.sbt's `mainClass := Some("Main")` requires it there,
// matching src/main/scala/Main.scala) — Scala's empty package can only be referenced from other
// code that is itself in the empty package, so this suite lives here rather than under snap.cli.
import snap.cli.Cli
import snap.cli.Command
import snap.cli.CommandHandler
import snap.cli.CommandOutput
import snap.cli.ResultKind
import snap.cli.TestEnv

import java.nio.file.Files
import java.nio.file.Path

/** Unit tests for `Main`'s thin wrapper around [[Cli.run]] (DESIGN §2, §10 gotcha 9): the happy
  * path is a pure delegation, and any `Throwable` [[Cli.run]] doesn't anticipate maps to exit 2
  * with an `internal error` line (R107, D4) rather than crashing the process — exercised here via
  * `Main.run`, which is `main` minus the actual `System.exit` call.
  */
class MainSuite extends munit.FunSuite:

  private def repoDir(): Path =
    val root = Files.createTempDirectory("snap-main-test")
    Files.createDirectory(root.resolve(".snap"))
    root

  test("delegates to Cli.run and returns its exit code unchanged on the happy path") {
    val fx = TestEnv(cwd = Path.of("/does/not/matter/for/version"))
    val exit = Main.run(fx.env, List("--version"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "snap 1.0.0\n")
    assertEquals(fx.stderr, "")
  }

  test("an expected SnapError from a command handler maps to exit 1, not exit 2") {
    // Injected handler (T10): the assertion is about Main's exit mapping, not about which
    // commands still dispatch to the stub — status itself gained a real handler in T10.
    val failing: CommandHandler = (_, _, _) => Left(snap.core.SnapError.NotImplemented)
    val commands = Cli.defaultCommands.updated(Command.Status, failing)
    val fx = TestEnv(cwd = repoDir())
    val exit = Main.run(fx.env, List("status"), commands)
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: not implemented\n")
  }

  test("an unanticipated exception from a command handler maps to exit 2 (R107/D4)") {
    // No `throw` keyword used (DisableSyntax noThrows): String.toInt on non-numeric input raises
    // NumberFormatException on its own, standing in for "a bug slipped past every Either/SnapError
    // check".
    val throwing: CommandHandler =
      (_, _, _) => Right(CommandOutput(ResultKind.Raw, "not-a-number".toInt.toString))
    val commands = Cli.defaultCommands.updated(Command.Status, throwing)
    val fx = TestEnv(cwd = repoDir())
    val exit = Main.run(fx.env, List("status"), commands)
    assertEquals(exit, 2)
    assertEquals(fx.stdout, "")
    assert(fx.stderr.startsWith("snap: internal error: "), fx.stderr)
    assert(fx.stderr.contains("not-a-number"), fx.stderr)
  }

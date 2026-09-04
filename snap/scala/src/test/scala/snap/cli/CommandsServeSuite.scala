package snap.cli

import snap.core.SnapError

import java.nio.file.Files
import java.nio.file.Path

/** `snap --serve [port]` (SPEC §7.9, D9). T13 lands only the shape ([[Grammar]]) and the port
  * *value* ([[CommandsServe.parsePort]]) — the actual server (repository snapshot, HTTP binding,
  * SIGINT/SIGTERM handling) is T19's, still [[SnapError.NotImplemented]] here.
  */
class CommandsServeSuite extends munit.FunSuite:

  private def repoDir(): Path =
    val root = Files.createTempDirectory("snap-serve-test")
    Files.createDirectory(root.resolve(".snap"))
    root

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  // --------------------------------------------------------------------- parsePort (unit, D9)

  test("parsePort: absent operand defaults to 8765 (SPEC §7.9)") {
    assertEquals(CommandsServe.parsePort(Nil), Right(CommandsServe.DefaultPort))
  }

  test("parsePort: canonical decimal 0..65535 is accepted") {
    for raw <- List("0", "1", "8765", "65535") do
      assertEquals(CommandsServe.parsePort(List(raw)), Right(raw.toInt), s"port $raw")
  }

  test("parsePort: 65536 is out of range (D9, test 14)") {
    assertEquals(CommandsServe.parsePort(List("65536")), Left(SnapError.InvalidPort("65536")))
  }

  test("parsePort: -1 is not a canonical decimal integer") {
    assertEquals(CommandsServe.parsePort(List("-1")), Left(SnapError.InvalidPort("-1")))
  }

  test("parsePort: 08 has a leading zero, so it's rejected even though it names an in-range port") {
    assertEquals(CommandsServe.parsePort(List("08")), Left(SnapError.InvalidPort("08")))
  }

  test("parsePort: abc is not decimal at all") {
    assertEquals(CommandsServe.parsePort(List("abc")), Left(SnapError.InvalidPort("abc")))
  }

  test("parsePort: an empty-string operand is invalid, not a default") {
    assertEquals(CommandsServe.parsePort(List("")), Left(SnapError.InvalidPort("")))
  }

  test("parsePort: a digit string that overflows Int is rejected, not thrown") {
    val huge = "999999999999999999999"
    assertEquals(CommandsServe.parsePort(List(huge)), Left(SnapError.InvalidPort(huge)))
  }

  // -------------------------------------------------------------- handler / Cli.run (tests 14/24)

  test("--serve 65536 fails with the exact pinned line, exit 1, no server startup (test 14)") {
    assertEquals(run(repoDir(), "--serve", "65536"), (1, "", "snap: invalid port: 65536\n"))
  }

  test("--serve with no operand uses the default port and reaches 'not implemented' (T19)") {
    assertEquals(run(repoDir(), "--serve"), (1, "", "snap: not implemented\n"))
  }

  test("--serve 0 (a valid, explicit port) also reaches 'not implemented' (T19)") {
    assertEquals(run(repoDir(), "--serve", "0"), (1, "", "snap: not implemented\n"))
  }

  test("--serve 0 extra is a grammar error, even though '0' alone is a valid port (test 24)") {
    assertEquals(
      run(repoDir(), "--serve", "0", "extra"),
      (1, "", "snap: invalid command or arguments\n")
    )
  }

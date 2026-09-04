package snap.cli

import snap.core.SnapError

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** `snap --serve [port]` (SPEC §7.9/§9, D9, D20–D21). Port-value parsing
  * ([[CommandsServe.parsePort]], T13) and the grammar shape (T13, [[GrammarSuite]]) are unit-tested
  * here directly; so is the one success-adjacent case that is safe to drive through the full
  * [[Cli.run]] pipeline synchronously — an invalid repository at startup, which fails before
  * [[snap.http.Server.start]] is ever called.
  *
  * The actual serving behavior (routing, headers, the default-port bind) is
  * [[snap.http.ServerSuite]]'s job, exercised directly against [[snap.http.Server]] rather than
  * through this handler: a successfully-bound `--serve` handler installs real SIGINT/SIGTERM
  * handlers and then blocks the calling thread until one fires (T19 design) — calling it from
  * `Cli.run` in-process would either hang the test suite forever (nothing ever signals it) or, if a
  * real signal were raised to unblock it, `System.exit` the whole test JVM. Test 12 (the provided
  * suite, run as a real subprocess) is what actually exercises that full success path end to end.
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

  /** A real repository created by the real `init` handler. */
  private def initRepo(): Path =
    val root = Files.createTempDirectory("snap-serve-test")
    val (exit, _, _) = run(root, "init")
    assertEquals(exit, 0)
    root

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

  test("--serve 0 extra is a grammar error, even though '0' alone is a valid port (test 24)") {
    assertEquals(
      run(repoDir(), "--serve", "0", "extra"),
      (1, "", "snap: invalid command or arguments\n")
    )
  }

  // ------------------------------------------------------------- invalid repository at startup (12)

  test(
    "an invalid repository.json fails before any port is bound: exit 1, no URL printed (test 12)"
  ) {
    val root = initRepo()
    val badRepo =
      """{
        |  "format": 1,
        |  "frontier": [],
        |  "patches": [],
        |  "bad": true
        |}
        |""".stripMargin
    Files.write(
      root.resolve(".snap").resolve("repository.json"),
      badRepo.getBytes(StandardCharsets.UTF_8)
    )
    val (exit, stdout, stderr) = run(root, "--serve", "0")
    assertEquals(exit, 1)
    assertEquals(stdout, "")
    assert(stderr.startsWith("snap: "), s"expected a 'snap: ' error line, got: $stderr")
  }

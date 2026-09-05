package snap.cli

import java.nio.file.Files
import java.nio.file.Path

/** Unit tests for T08's acceptance criteria (tasks/T08-cli-dispatch.md): `--version`'s no-discovery
  * fast path, the coarse grammar core (R79), repository discovery (R77), and `SNAP_COLOR` value
  * validation (R95) — all against fake `Env` values, per the task's testing requirement.
  */
class CliSuite extends munit.FunSuite:

  private def tempDir(): Path = Files.createTempDirectory("snap-cli-test")

  test("--version prints exactly 'snap 1.0.0\\n', exit 0, empty stderr") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("--version"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "snap 1.0.0\n")
    assertEquals(fx.stderr, "")
  }

  test("--version performs no repository discovery: a nonexistent cwd still succeeds") {
    // If discovery ran, walking up from a nonexistent path would eventually reach the real
    // filesystem root and fail with "not a Snap repository" instead of succeeding here.
    val fx = TestEnv(cwd = Path.of("/does/not/exist/at/all/snap-t08"))
    val exit = Cli.run(fx.env, List("--version"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "snap 1.0.0\n")
    assertEquals(fx.stderr, "")
  }

  test("--version rejects extra operands as invalid command or arguments") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("--version", "extra"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("unknown command is invalid command or arguments, exit 1 (test 14 wording)") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("unknown"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("an unrecognized flag-shaped token is also invalid command or arguments") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("--unknown"))
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("no arguments at all is invalid command or arguments") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, Nil)
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("a repo-needing command outside any repository fails with the exact wording (test 14)") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("status"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: not a Snap repository\n")
  }

  // The "known-but-unimplemented commands" table that lived here is gone: T10 took
  // status/log/commit/diff, T12 revert, T17 merge and T19 `--serve`, so every command now has a
  // real handler and the table had no subjects left. The two `not implemented` seams that do
  // remain are remote-operand ones, pinned where they live: `diff … --repo` in
  // [[CommandsDiffSuite]] and `merge http://…` in [[CommandsMergeSuite]]. Both go in T20.

  test("init never requires a pre-existing repository (it creates one instead, T09)") {
    // A nonexistent-until-now cwd would fail discovery with "not a Snap repository" if init
    // ran it (as every other command does); succeeding here proves init skips discovery
    // entirely and creates the repository itself (T08's stub-era assertion updated for T09's
    // real `init`, which no longer returns "not implemented").
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("init"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "()\n")
    assertEquals(fx.stderr, "")
  }

  test("discovery finds .snap from a nested cwd and resolves to that ancestor") {
    val root = tempDir()
    Files.createDirectory(root.resolve(".snap"))
    val nested = Files.createDirectories(root.resolve("sub").resolve("deep"))
    val fx = TestEnv(cwd = nested)
    val commands = Cli.defaultCommands.updated(
      Command.Status,
      (_: Env, repoRoot: Option[Path], _: List[String]) =>
        Right(CommandOutput(ResultKind.Raw, repoRoot.map(_.toString).getOrElse("NONE") + "\n"))
    )
    val exit = Cli.run(fx.env, List("status"), commands)
    assertEquals(exit, 0)
    assertEquals(fx.stdout, root.toAbsolutePath.normalize().toString + "\n")
    assertEquals(fx.stderr, "")
  }

  test("discovery stops at the filesystem root when no .snap exists anywhere above cwd") {
    val root = tempDir()
    val nested = Files.createDirectories(root.resolve("a").resolve("b").resolve("c"))
    val fx = TestEnv(cwd = nested)
    val exit = Cli.run(fx.env, List("status"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: not a Snap repository\n")
  }

  test("SNAP_COLOR=sometimes is rejected before any command logic, even for --version") {
    val fx = TestEnv(cwd = tempDir(), envMap = Map("SNAP_COLOR" -> "sometimes"))
    val exit = Cli.run(fx.env, List("--version"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: SNAP_COLOR must be auto, always, or never\n")
  }

  test("an invalid SNAP_COLOR is reported even when the command would also fail on its own") {
    // Outside a repository, "status" would otherwise fail with "not a Snap repository" — the
    // SNAP_COLOR error must win because R95 validation happens before command execution.
    val fx = TestEnv(cwd = tempDir(), envMap = Map("SNAP_COLOR" -> "loud"))
    val exit = Cli.run(fx.env, List("status"))
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: SNAP_COLOR must be auto, always, or never\n")
  }

  test("SNAP_COLOR accepts unset, auto, always, and never") {
    for value <- List(None, Some("auto"), Some("always"), Some("never")) do
      val envMap = value.fold(Map.empty[String, String])(v => Map("SNAP_COLOR" -> v))
      val fx = TestEnv(cwd = tempDir(), envMap = envMap)
      val exit = Cli.run(fx.env, List("--version"))
      assertEquals(exit, 0, s"value $value")
      assertEquals(fx.stderr, "", s"value $value")
  }

  test("Cli.parse rejects an empty argument list directly") {
    assertEquals(Cli.parse(Nil), Left(snap.core.SnapError.InvalidCommand))
  }

  test("Cli.discoverRepo returns the ancestor directory containing .snap") {
    val root = tempDir()
    Files.createDirectory(root.resolve(".snap"))
    val nested = Files.createDirectories(root.resolve("x").resolve("y"))
    val fx = TestEnv(cwd = nested)
    assertEquals(Cli.discoverRepo(fx.env), Right(root.toAbsolutePath.normalize()))
  }

  test(
    "Cli.discoverRepo walks past a symlinked .snap: it is not a real repository (D25)"
  ) {
    val root = tempDir()
    val realDir = Files.createDirectory(tempDir().resolve("elsewhere"))
    Files.createSymbolicLink(root.resolve(".snap"), realDir)
    val fx = TestEnv(cwd = root)
    assertEquals(Cli.discoverRepo(fx.env), Left(snap.core.SnapError.NotASnapRepository))
  }

  test(
    "Cli.discoverRepo prefers a real ancestor repository over a closer symlinked .snap (D25)"
  ) {
    val root = tempDir()
    Files.createDirectory(root.resolve(".snap"))
    val nested = Files.createDirectories(root.resolve("x"))
    val realDir = Files.createDirectory(tempDir().resolve("elsewhere"))
    Files.createSymbolicLink(nested.resolve(".snap"), realDir)
    val fx = TestEnv(cwd = nested)
    assertEquals(Cli.discoverRepo(fx.env), Right(root.toAbsolutePath.normalize()))
  }

  // ------------------------------------------------------------ T13: grammar before discovery

  test(
    "CR7: a grammar error in a non-repository directory prints the grammar error, " +
      "never 'not a Snap repository'"
  ) {
    // `status` needs repository discovery (Command.needsRepoDiscovery) and this cwd has no
    // `.snap` anywhere above it, so if Grammar ran after discovery (the pre-T13 bug) this would
    // wrongly report "not a Snap repository" instead of the grammar violation.
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("status", "extra"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test(
    "CR7: config's grammar error outside any repository still wins over " +
      "'not a Snap repository' (D10 governs only value-valid config, not shape)"
  ) {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("config", "not-a-recognized-shape"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("CR7: diff's own usage channel also wins over 'not a Snap repository'") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("diff", "()"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assert(fx.stderr.contains("usage: snap diff"), fx.stderr)
  }

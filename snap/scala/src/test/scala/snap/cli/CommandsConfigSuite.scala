package snap.cli

import munit.FunSuite
import snap.fs.Store

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ListBuffer

/** `snap config [--global] contributor.id <id>` (SPEC §7.2, R82, R98–R100, D10). Exercised through
  * [[Cli.run]] so discovery's `--global` exemption (D10) is covered end to end, not just
  * [[CommandsConfig]] in isolation.
  */
class CommandsConfigSuite extends FunSuite:

  private val createdDirs = ListBuffer.empty[Path]

  override def afterAll(): Unit =
    createdDirs.foreach { dir =>
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }

  private def tempDir(): Path =
    val dir = Files.createTempDirectory("snap-commands-config-suite")
    createdDirs += dir
    dir

  private def repo(): Path =
    val root = tempDir()
    Files.createDirectory(root.resolve(".snap"))
    root

  test("writes .snap/config.json in the nearest repository, silent on success") {
    val root = repo()
    val fx = TestEnv(cwd = root)
    val exit = Cli.run(fx.env, List("config", "contributor.id", "a@example.com"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "")
    assertEquals(Config.resolve(fx.env, root), Right(Some(idOf("a@example.com"))))
  }

  test("resolves the nearest repository from a nested cwd (R77)") {
    val root = repo()
    val nested = Files.createDirectories(root.resolve("sub").resolve("deep"))
    val fx = TestEnv(cwd = nested)
    val exit = Cli.run(fx.env, List("config", "contributor.id", "a@x"))
    assertEquals(exit, 0)
    assert(Files.exists(Config.localFile(root)))
  }

  test("--global writes $HOME/.snapconfig.json and needs no repository (D10)") {
    val home = tempDir()
    val cwd = tempDir() // no .snap anywhere
    val fx = TestEnv(cwd = cwd, envMap = Map("HOME" -> home.toString))
    val exit = Cli.run(fx.env, List("config", "--global", "contributor.id", "global@example.com"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "")
    assert(Files.exists(home.resolve(Store.GlobalConfigFileName)))
    assertEquals(Config.resolve(fx.env, repo()), Right(Some(idOf("global@example.com"))))
  }

  test("overwrite replaces the file completely, dropping any unknown field (test 25)") {
    val root = repo()
    Files.write(
      Config.localFile(root),
      """{"contributor":{"id":"old@x"},"unknown":true}""".getBytes()
    )
    val fx = TestEnv(cwd = root)
    val exit = Cli.run(fx.env, List("config", "contributor.id", "new@x"))
    assertEquals(exit, 0)
    val text = new String(Files.readAllBytes(Config.localFile(root)))
    assert(!text.contains("unknown"), text)
    assert(!text.contains("old@x"), text)
  }

  test("an invalid id is rejected before writing anything (SPEC §7.2: validates before writing)") {
    val root = repo()
    val fx = TestEnv(cwd = root)
    val exit = Cli.run(fx.env, List("config", "contributor.id", "bad-id"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assert(fx.stderr.contains("invalid contributor id"), fx.stderr)
    assert(!Files.exists(Config.localFile(root)))
  }

  test("config without a repository and without --global fails with the D10 message") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("config", "contributor.id", "a@x"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: not a Snap repository\n")
  }

  test("--global with no HOME in the environment is an error, not a silent no-op") {
    val fx = TestEnv(cwd = tempDir(), envMap = Map.empty)
    val exit = Cli.run(fx.env, List("config", "--global", "contributor.id", "a@x"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assert(fx.stderr.startsWith("snap: "), fx.stderr)
  }

  test("--global with HOME=\"\" is the same GlobalConfigUnavailable error as no HOME (D24/CR2)") {
    val cwd = tempDir()
    val fx = TestEnv(cwd = cwd, envMap = Map("HOME" -> ""))
    val exit = Cli.run(fx.env, List("config", "--global", "contributor.id", "a@x"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: global configuration is unavailable: HOME is not set\n")
    // Never silently resolves against cwd: no `.snapconfig.json` is created anywhere under it.
    assert(!Files.exists(cwd.resolve(Store.GlobalConfigFileName)))
  }

  test("--global contributor.id with a missing id operand is a grammar error") {
    val fx = TestEnv(cwd = repo())
    val exit = Cli.run(fx.env, List("config", "--global", "contributor.id"))
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("--global out of its documented position is a grammar error (test 24)") {
    val fx = TestEnv(cwd = repo())
    val exit = Cli.run(fx.env, List("config", "contributor.id", "a@x", "--global"))
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("a duplicated --global flag is a grammar error (test 24)") {
    val fx = TestEnv(cwd = repo())
    val exit = Cli.run(fx.env, List("config", "--global", "--global", "contributor.id", "a@x"))
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  private def idOf(raw: String) =
    snap.core.ContributorId.parse(raw).fold(e => fail(s"expected valid id: ${e.message}"), identity)

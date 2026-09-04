package snap.cli

import munit.FunSuite
import snap.core.ContributorId
import snap.fs.Store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ListBuffer

/** Contributor-configuration precedence (SPEC §8, R99–R100): local `.snap/config.json` wins over
  * `$HOME/.snapconfig.json` and, when it provides an id, the global file is never even read. Every
  * test in this suite is deterministic and order-independent by construction (each builds its own
  * fresh directories) — repeated runs and any test-execution order must agree (task acceptance:
  * "precedence matrix" determinism).
  */
class ConfigSuite extends FunSuite:

  // Test-boundary mutability: created directories are only appended here and read once in
  // afterAll for cleanup — no test logic depends on this buffer (same pattern as StoreSuite).
  private val createdDirs = ListBuffer.empty[Path]

  override def afterAll(): Unit =
    createdDirs.foreach { dir =>
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }

  private def tempDir(): Path =
    val dir = Files.createTempDirectory("snap-config-suite")
    createdDirs += dir
    dir

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  /** A fresh repository root with `.snap/config.json` present iff `text` is `Some`. */
  private def repoWithLocalConfig(text: Option[String]): Path =
    val root = tempDir()
    Files.createDirectories(root.resolve(".snap"))
    text.foreach(t => Files.write(Config.localFile(root), t.getBytes(StandardCharsets.UTF_8)))
    root

  private def envWithHome(home: Option[Path]): Env =
    TestEnv(
      cwd = tempDir(),
      envMap = home.fold(Map.empty[String, String])(h => Map("HOME" -> h.toString))
    ).env

  private def writeGlobal(home: Path, text: String): Unit =
    Files.write(home.resolve(Store.GlobalConfigFileName), text.getBytes(StandardCharsets.UTF_8))

  // ------------------------------------------------------------ precedence matrix

  test("local id wins; a malformed global file is never read (R99, test 03's premise)") {
    val home = tempDir()
    writeGlobal(home, "not json")
    val repo = repoWithLocalConfig(Some("""{"contributor":{"id":"local@example.com"}}"""))
    val env = envWithHome(Some(home))
    assertEquals(Config.resolve(env, repo), Right(Some(id("local@example.com"))))
  }

  test("no local id falls back to a valid global file, validated (R99)") {
    val home = tempDir()
    writeGlobal(home, """{"contributor":{"id":"global@x"}}""")
    val repo = repoWithLocalConfig(None)
    val env = envWithHome(Some(home))
    assertEquals(Config.resolve(env, repo), Right(Some(id("global@x"))))
  }

  test("a malformed global file is an error when local provides no id") {
    val home = tempDir()
    writeGlobal(home, "not json")
    val repo = repoWithLocalConfig(None)
    val env = envWithHome(Some(home))
    assert(Config.resolve(env, repo).left.exists(_.message.contains("invalid JSON")))
  }

  test("an invalid local id is an error even though a valid global file exists (test 25)") {
    val home = tempDir()
    writeGlobal(home, """{"contributor":{"id":"global@x"}}""")
    val repo = repoWithLocalConfig(Some("""{"contributor":{"id":"not-an-id"}}"""))
    val env = envWithHome(Some(home))
    assert(Config.resolve(env, repo).left.exists(_.message.contains("invalid contributor id")))
  }

  test("absent HOME makes global unavailable, not an error, when local provides no id (R99)") {
    val repo = repoWithLocalConfig(None)
    val env = envWithHome(None)
    assertEquals(Config.resolve(env, repo), Right(None))
  }

  test("absent HOME never matters when local already provides an id") {
    val repo = repoWithLocalConfig(Some("""{"contributor":{"id":"local@x"}}"""))
    val env = envWithHome(None)
    assertEquals(Config.resolve(env, repo), Right(Some(id("local@x"))))
  }

  test("no configuration anywhere resolves to no value") {
    val home = tempDir()
    val repo = repoWithLocalConfig(None)
    val env = envWithHome(Some(home))
    assertEquals(Config.resolve(env, repo), Right(None))
  }

  test("a missing local file falls through to global without error (missing file = no value)") {
    val home = tempDir()
    writeGlobal(home, """{"contributor":{"id":"global@x"}}""")
    val repo = tempDir() // no .snap at all
    val env = envWithHome(Some(home))
    assertEquals(Config.resolve(env, repo), Right(Some(id("global@x"))))
  }

  // ------------------------------------------------------------ R100 requirement

  test("requireContributorId reports the exact R100 message when nothing is configured (test 19)") {
    val repo = repoWithLocalConfig(None)
    val env = envWithHome(None)
    assertEquals(
      Config.requireContributorId(env, repo).left.map(_.message),
      Left("contributor.id is required; configure it locally or globally")
    )
  }

  test("requireContributorId succeeds when local provides an id") {
    val repo = repoWithLocalConfig(Some("""{"contributor":{"id":"a@x"}}"""))
    val env = envWithHome(None)
    assertEquals(Config.requireContributorId(env, repo), Right(id("a@x")))
  }

  // ------------------------------------------------------------ determinism

  test("resolve is a pure function of the files on disk: repeated calls agree") {
    val home = tempDir()
    writeGlobal(home, """{"contributor":{"id":"global@x"}}""")
    val repo = repoWithLocalConfig(None)
    val env = envWithHome(Some(home))
    val first = Config.resolve(env, repo)
    val second = Config.resolve(env, repo)
    assertEquals(first, second)
    assertEquals(first, Right(Some(id("global@x"))))
  }

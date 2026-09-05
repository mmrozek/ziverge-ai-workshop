package snap.cli

import munit.FunSuite
import snap.core.Repository
import snap.fs.Store

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ListBuffer

/** `snap init [path]` (SPEC §7.1, R80–R81). Exercised through [[Cli.run]] so the whole dispatch
  * (grammar, no-discovery, plain rendering) is covered, not just [[CommandsInit]] in isolation.
  */
class CommandsInitSuite extends FunSuite:

  private val createdDirs = ListBuffer.empty[Path]

  override def afterAll(): Unit =
    createdDirs.foreach { dir =>
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }

  private def tempDir(): Path =
    val dir = Files.createTempDirectory("snap-commands-init-suite")
    createdDirs += dir
    dir

  test("default path '.' creates an empty repository, prints '()', exit 0 (test 01)") {
    val root = tempDir()
    val fx = TestEnv(cwd = root)
    val exit = Cli.run(fx.env, List("init"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "()\n")
    assertEquals(fx.stderr, "")
    assertEquals(
      Store.readRepository(root.resolve(".snap").resolve("repository.json")).map(_.repository),
      Right(Repository.empty)
    )
  }

  test("the written repository.json is the canonical writer's exact bytes (D7)") {
    val root = tempDir()
    val fx = TestEnv(cwd = root)
    Cli.run(fx.env, List("init"))
    val bytes = Files.readAllBytes(root.resolve(".snap").resolve("repository.json"))
    assert(bytes.sameElements(snap.json.RepoCodec.encodeBytes(Repository.empty)))
  }

  test("a relative path is created recursively and resolved against cwd (test 02)") {
    val root = tempDir()
    val fx = TestEnv(cwd = root)
    val exit = Cli.run(fx.env, List("init", "new/repository"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "()\n")
    assert(Files.isRegularFile(root.resolve("new/repository/.snap/repository.json")))
  }

  test("existing files at the target path are preserved (test 02)") {
    val root = tempDir()
    Files.write(root.resolve("existing.txt"), "keep me\n".getBytes())
    val fx = TestEnv(cwd = root)
    Cli.run(fx.env, List("init"))
    assertEquals(new String(Files.readAllBytes(root.resolve("existing.txt"))), "keep me\n")
  }

  test("reinitializing an existing repository fails without creating anything new (test 02)") {
    val root = tempDir()
    val fx1 = TestEnv(cwd = root)
    assertEquals(Cli.run(fx1.env, List("init")), 0)
    val before = Files.readAllBytes(root.resolve(".snap").resolve("repository.json"))
    val fx2 = TestEnv(cwd = root)
    val exit = Cli.run(fx2.env, List("init"))
    assertEquals(exit, 1)
    assertEquals(fx2.stdout, "")
    assert(fx2.stderr.contains("repository already exists"), fx2.stderr)
    assert(
      Files.readAllBytes(root.resolve(".snap").resolve("repository.json")).sameElements(before)
    )
  }

  test("initializing inside an existing repository fails and creates no nested .snap (test 02)") {
    val root = tempDir()
    val fx1 = TestEnv(cwd = root)
    assertEquals(Cli.run(fx1.env, List("init")), 0)
    Files.createDirectory(root.resolve("child"))
    val fx2 = TestEnv(cwd = root.resolve("child"))
    val exit = Cli.run(fx2.env, List("init"))
    assertEquals(exit, 1)
    assertEquals(fx2.stdout, "")
    assert(fx2.stderr.contains("cannot initialize inside repository"), fx2.stderr)
    assert(!Files.exists(root.resolve("child").resolve(".snap")))
  }

  test("nesting is detected from a target several levels below the repository root") {
    val root = tempDir()
    val fx1 = TestEnv(cwd = root)
    assertEquals(Cli.run(fx1.env, List("init")), 0)
    val fx2 = TestEnv(cwd = root)
    val exit = Cli.run(fx2.env, List("init", "a/b/c"))
    assertEquals(exit, 1)
    assert(fx2.stderr.contains("cannot initialize inside repository"), fx2.stderr)
    assert(!Files.exists(root.resolve("a")))
  }

  test(
    "init over a symlinked .snap target neither follows the link nor succeeds silently (D25)"
  ) {
    // `checkNotInsideRepository`'s NOFOLLOW_LINKS check (D25) does not see a repository here, so
    // `init` proceeds by its normal rules — and then fails naturally when `Files.createDirectories`
    // finds a non-directory entry already sitting at `.snap`: neither following the symlink into
    // `realTarget` (least-surprising: a bare `.snap` symlink is not "reinitializing" a repository)
    // nor silently succeeding without creating real metadata.
    val root = tempDir()
    val realTarget = tempDir().resolve("elsewhere")
    Files.createDirectory(realTarget)
    Files.createSymbolicLink(root.resolve(".snap"), realTarget)
    val fx = TestEnv(cwd = root)
    val exit = Cli.run(fx.env, List("init"))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assert(fx.stderr.startsWith("snap: cannot create directory:"), fx.stderr)
    // Never followed: no repository.json ever lands inside the symlink's real target.
    assert(!Files.exists(realTarget.resolve("repository.json")))
    // The symlink itself is untouched — still a symlink, not replaced by a real directory.
    assert(Files.isSymbolicLink(root.resolve(".snap")))
  }

  test("more than one operand is a grammar error") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("init", "a", "b"))
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("a '--'-shaped operand is rejected as an unknown option (init takes none)") {
    val fx = TestEnv(cwd = tempDir())
    val exit = Cli.run(fx.env, List("init", "--unknown"))
    assertEquals(exit, 1)
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
  }

  test("CR14: an explicit empty-string operand is a grammar error, not a silent '.' default") {
    val root = tempDir()
    val fx = TestEnv(cwd = root)
    val exit = Cli.run(fx.env, List("init", ""))
    assertEquals(exit, 1)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "snap: invalid command or arguments\n")
    assert(!Files.exists(root.resolve(".snap")), "must not silently initialize the cwd")
  }

  test("init directly via CommandsInit.handler ignores any repoRoot it is given") {
    // Cli.run never resolves a repository for init (Command.needsRepoDiscovery), but the handler's
    // own logic must not depend on that argument either.
    val root = tempDir()
    val fx = TestEnv(cwd = root)
    val result = CommandsInit.handler(fx.env, Some(Path.of("/irrelevant")), Nil)
    assertEquals(
      result,
      Right(CommandOutput(ResultKind.Success("Initialized repository"), "()\n"))
    )
  }

package snap.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** `snap status` (SPEC §7.3, R83) through the full [[Cli.run]] pipeline: version line first, A/M/D
  * rows sorted by path, scan failures (R104), and the exclusions of test 25's premise.
  */
class CommandsStatusSuite extends munit.FunSuite:

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  /** A real repository created by the real `init` handler. */
  private def initRepo(): Path =
    val root = Files.createTempDirectory("snap-status-test")
    val (exit, _, _) = run(root, "init")
    assertEquals(exit, 0)
    root

  private def write(root: Path, rel: String, text: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    ()

  test("clean empty repository prints only the version line (test 04)") {
    val root = initRepo()
    assertEquals(run(root, "status"), (0, "version ()\n", ""))
  }

  test("added files are A rows sorted by path (test 04)") {
    val root = initRepo()
    write(root, "z.txt", "z\n")
    write(root, "a.txt", "a\n")
    assertEquals(run(root, "status"), (0, "version ()\nA a.txt\nA z.txt\n", ""))
  }

  test("M, A, and D rows are interleaved in one path-sorted listing (test 04)") {
    val root = initRepo()
    run(root, "config", "contributor.id", "alice@example.com")
    write(root, "z.txt", "z\n")
    write(root, "a.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    write(root, "a.txt", "changed\n")
    Files.delete(root.resolve("z.txt"))
    write(root, "m.txt", "middle\n")
    assertEquals(
      run(root, "status"),
      (0, "version (alice@example.com->1)\nM a.txt\nA m.txt\nD z.txt\n", "")
    )
  }

  test("empty dirs and root .snap contents are invisible; unicode paths sort per Utf8Order (25)") {
    val root = initRepo()
    Files.createDirectories(root.resolve("empty"))
    Files.createDirectories(root.resolve("deep").resolve("empty"))
    write(root, ".snap/untracked", "metadata\n")
    assertEquals(run(root, "status"), (0, "version ()\n", ""))
    write(root, "z", "z\n")
    write(root, "é", "accent\n")
    write(root, "😀", "emoji\n")
    write(root, "nested/file", "nested\n")
    assertEquals(
      run(root, "status"),
      (0, "version ()\nA nested/file\nA z\nA é\nA 😀\n", "")
    )
  }

  test("status fails on an unsupported entry with the exact line and empty stdout (test 08)") {
    val root = initRepo()
    Files.createSymbolicLink(root.resolve("link"), Path.of("missing"))
    assertEquals(run(root, "status"), (1, "", "snap: unsupported working tree entry: link\n"))
  }

  test("status resolves the repository from a nested cwd and scans the ROOT (test 19)") {
    val root = initRepo()
    write(root, "file.txt", "one\n")
    val nested = Files.createDirectories(root.resolve("sub").resolve("deep"))
    assertEquals(run(nested, "status"), (0, "version ()\nA file.txt\n", ""))
  }

  test("status takes no operands (coarse R79)") {
    val root = initRepo()
    assertEquals(run(root, "status", "extra"), (1, "", "snap: invalid command or arguments\n"))
  }

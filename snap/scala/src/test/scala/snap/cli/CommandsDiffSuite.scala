package snap.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** `snap diff` through the full [[Cli.run]] pipeline (SPEC §7.6; DESIGN §8, D8; R31, R45, R79,
  * R86–R87): the no-arg and `<old> <new>` forms end to end, `diff`'s distinct usage channel, the
  * `invalid version` / `unknown version` classes, `--repo`'s grammar-accepted-but-not-implemented
  * shape, and R86's validate-before-output guarantee.
  */
class CommandsDiffSuite extends munit.FunSuite:

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  private def initRepo(id: String = "a@x"): Path =
    val root = Files.createTempDirectory("snap-diff-test")
    assertEquals(run(root, "init")._1, 0)
    assertEquals(run(root, "config", "contributor.id", id)._1, 0)
    root

  private def write(root: Path, rel: String, text: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    ()

  // ------------------------------------------------------------------ no-arg form

  test("no-arg diff compares the current tree with the working tree") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first"), (0, "(a@x->1)\n", ""))
    write(root, "f.txt", "a\nb\n")
    write(root, "g.txt", "new")
    assertEquals(
      run(root, "diff"),
      (
        0,
        "--- a/f.txt\n+++ b/f.txt\n@@ -1,1 +1,2 @@\n a\n+b\n" +
          "--- /dev/null\n+++ b/g.txt\n@@ -1,0 +1,1 @@\n+new\n\\ No newline at end of file\n",
        ""
      )
    )
  }

  test("no-arg diff on a clean tree is empty stdout (R87)") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    assertEquals(run(root, "diff"), (0, "", ""))
  }

  // ------------------------------------------------------------------ <old> <new> form

  test("diff <old> <new> compares two locally known versions") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first"), (0, "(a@x->1)\n", ""))
    write(root, "f.txt", "a\nb\n")
    assertEquals(run(root, "commit", "second"), (0, "(a@x->2)\n", ""))
    assertEquals(
      run(root, "diff", "(a@x->1)", "(a@x->2)"),
      (0, "--- a/f.txt\n+++ b/f.txt\n@@ -1,1 +1,2 @@\n a\n+b\n", "")
    )
  }

  test("diff between equal versions is empty stdout, exit 0") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    assertEquals(run(root, "diff", "(a@x->1)", "(a@x->1)"), (0, "", ""))
  }

  test("diff () () on an empty repository is empty stdout (the empty tree is always known)") {
    val root = initRepo()
    assertEquals(run(root, "diff", "()", "()"), (0, "", ""))
  }

  test("an unknown but canonical version renders the exact pinned line (R45, test 19)") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    assertEquals(
      run(root, "diff", "(a@x->2)", "(a@x->1)"),
      (1, "", "snap: unknown version: (a@x->2)\n")
    )
  }

  test("a noncanonical version literal is the 'invalid version' class, not 'unknown version'") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    val (exit, out, err) = run(root, "diff", "(a@x->01)", "(a@x->1)")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: invalid version: "), s"unexpected message: $err")
  }

  // ------------------------------------------------------------------ grammar (tests 14/24)

  test("wrong arity uses the distinct usage channel, not 'invalid command or arguments'") {
    val root = initRepo()
    val (exit, out, err) = run(root, "diff", "()")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: usage: snap diff"), s"unexpected message: $err")
  }

  test("an unknown flag in the --repo position is a usage error (test 24)") {
    val root = initRepo()
    val (exit, out, err) = run(root, "diff", "()", "()", "--unknown", "repo")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: usage: snap diff"), s"unexpected message: $err")
  }

  test("a misplaced --repo (wrong position) is a usage error (test 14)") {
    val root = initRepo()
    val (exit, out, err) = run(root, "diff", "()", "()", "../repo", "--repo")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: usage: snap diff"), s"unexpected message: $err")
  }

  test("a doubled --repo is a usage error (test 24)") {
    val root = initRepo()
    val (exit, out, err) = run(root, "diff", "()", "()", "--repo", "repo", "--repo", "repo")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: usage: snap diff"), s"unexpected message: $err")
  }

  // ------------------------------------------------------------------ --repo grammar accepted, NotImplemented

  test("a grammar-valid --repo invocation is accepted syntax but not yet implemented (T20/T21)") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    assertEquals(
      run(root, "diff", "()", "(a@x->1)", "--repo", "../elsewhere"),
      (1, "", "snap: not implemented\n")
    )
  }

  // ------------------------------------------------------------------ R86: validate before output

  test("an invalid repository fails before any diff output is produced (R86)") {
    val root = Files.createTempDirectory("snap-diff-badrepo")
    Files.createDirectory(root.resolve(".snap"))
    Files.writeString(
      root.resolve(".snap").resolve("repository.json"),
      """{"format": 1, "frontier": [], "patches": [], "bad": true}"""
    )
    val (exit, out, err) = run(root, "diff", "()", "()")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: "), s"unexpected message: $err")
  }

  test("an unsupported working-tree entry wins over rendering, no-arg form (D11, test 08)") {
    val root = initRepo()
    write(root, "f.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    Files.createSymbolicLink(root.resolve("link"), Path.of("missing"))
    assertEquals(run(root, "diff"), (1, "", "snap: unsupported working tree entry: link\n"))
  }

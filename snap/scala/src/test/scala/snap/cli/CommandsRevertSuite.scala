package snap.cli

import snap.core.Ord
import snap.fs.Store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** `snap revert` (SPEC §7.7, R88) through the full [[Cli.run]] pipeline: the additive/never-
  * regresses guarantee (patch count +1, frontier strictly dominates — tasks/T12's acceptance
  * criteria), file/directory transitions (test 07's shape), the already-current and dirty-tree
  * short-circuits, the check-order finding from test 14 (an unknown target version is reported even
  * with no contributor configured), and the mutation-order guarantee that a working-file write
  * failure never reaches `repository.json` (SPEC §10, R103/R105–R106).
  */
class CommandsRevertSuite extends munit.FunSuite:

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  private def initRepo(id: String = "a@x"): Path =
    val root = Files.createTempDirectory("snap-revert-test")
    assertEquals(run(root, "init")._1, 0)
    assertEquals(run(root, "config", "contributor.id", id)._1, 0)
    root

  private def write(root: Path, rel: String, text: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    ()

  private def textAt(root: Path, rel: String): String =
    new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8)

  private def repoBytes(root: Path): Vector[Byte] =
    Files.readAllBytes(root.resolve(".snap").resolve("repository.json")).toVector

  // ------------------------------------------------------------------------------- happy path

  test("revert restores an older version's content, printing the NEW version (test 07's shape)") {
    val root = initRepo()
    write(root, "node", "file\n")
    assertEquals(run(root, "commit", "file")._1, 0) // (a@x->1)
    Files.delete(root.resolve("node"))
    write(root, "node/child", "child\n")
    assertEquals(run(root, "commit", "directory")._1, 0) // (a@x->2)

    assertEquals(run(root, "revert", "(a@x->1)"), (0, "(a@x->3)\n", ""))
    assertEquals(textAt(root, "node"), "file\n")
    assert(!Files.exists(root.resolve("node/child")))

    assertEquals(run(root, "revert", "(a@x->2)"), (0, "(a@x->4)\n", ""))
    assertEquals(textAt(root, "node/child"), "child\n")
  }

  test("the generated patch message is 'revert to <version>' (SPEC §7.7)") {
    val root = initRepo()
    write(root, "f", "one\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    write(root, "f", "two\n")
    assertEquals(run(root, "commit", "second")._1, 0)
    assertEquals(run(root, "revert", "(a@x->1)")._1, 0)
    assertEquals(
      run(root, "log"),
      (
        0,
        "(a@x->3)\ta@x\trevert to (a@x->1)\n" +
          "(a@x->2)\ta@x\tsecond\n" +
          "(a@x->1)\ta@x\tfirst\n",
        ""
      )
    )
  }

  // ------------------------------------------------------------------------ additive guarantee

  test("revert is additive: patch count grows by exactly one, the frontier strictly dominates") {
    val root = initRepo()
    write(root, "f", "one\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    write(root, "f", "two\n")
    assertEquals(run(root, "commit", "second")._1, 0)

    val before = Store.readRepository(Commands.repositoryFile(root)).toOption.get
    assertEquals(run(root, "revert", "(a@x->1)")._1, 0)
    val after = Store.readRepository(Commands.repositoryFile(root)).toOption.get

    assertEquals(after.repository.patches.length, before.repository.patches.length + 1)
    assertEquals(before.repository.frontier.compareCausal(after.repository.frontier), Ord.Before)
  }

  // ------------------------------------------------------------------------ already-current

  test("reverting to the current version fails with the exact pinned line (R88)") {
    val root = initRepo()
    write(root, "f", "one\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    assertEquals(
      run(root, "revert", "(a@x->1)"),
      (1, "", "snap: target tree is already current\n")
    )
  }

  test("an already-current revert authors no patch and mutates nothing") {
    val root = initRepo()
    write(root, "f", "one\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    val before = repoBytes(root)
    assertEquals(run(root, "revert", "(a@x->1)")._1, 1)
    assertEquals(repoBytes(root), before)
  }

  // ----------------------------------------------------------------------------- dirty tree

  test("a dirty working tree fails with the exact pinned line, before any mutation (R27/R103)") {
    val root = initRepo()
    write(root, "f", "one\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    write(root, "f", "two\n")
    assertEquals(run(root, "commit", "second")._1, 0)
    write(root, "dirty", "dirty")
    val before = repoBytes(root)
    assertEquals(run(root, "revert", "(a@x->1)"), (1, "", "snap: working tree is dirty\n"))
    assertEquals(repoBytes(root), before)
    assertEquals(textAt(root, "dirty"), "dirty") // untouched
  }

  // -------------------------------------------------------------------- contributor id (R100)

  test("revert without contributor configuration fails with the pinned R100 line") {
    val root = Files.createTempDirectory("snap-revert-noconfig")
    assertEquals(run(root, "init")._1, 0)
    assertEquals(run(root, "config", "contributor.id", "a@x")._1, 0)
    write(root, "f", "one\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    write(root, "f", "two\n")
    assertEquals(run(root, "commit", "second")._1, 0)
    Files.delete(root.resolve(".snap").resolve("config.json"))
    assertEquals(
      run(root, "revert", "(a@x->1)"),
      (1, "", "snap: contributor.id is required; configure it locally or globally\n")
    )
  }

  test(
    "an unknown target version is reported even with no contributor configured at all " +
      "(test 14: the known-version check precedes the contributor-id requirement)"
  ) {
    val root = Files.createTempDirectory("snap-revert-noconfig-unknown")
    assertEquals(run(root, "init")._1, 0)
    // No `config contributor.id` at all — R100 would fire if it were checked first.
    val (exit, out, err) = run(root, "revert", "(unknown@x->1)")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.contains("unknown version"), err)
  }

  // -------------------------------------------------------------------------- mutation order

  test(
    "a working-file write failure during install never reaches repository.json " +
      "(SPEC §10, R103/R105-R106)"
  ) {
    val root = initRepo()
    // version 1: "keep" holds two files; version 2 drops "keep/y" — reverting to version 1 must
    // WRITE "keep/y" back into the still-present "keep" directory.
    write(root, "keep/x", "1\n")
    write(root, "keep/y", "2\n")
    assertEquals(run(root, "commit", "both")._1, 0) // (a@x->1)
    Files.delete(root.resolve("keep/y"))
    assertEquals(run(root, "commit", "drop y")._1, 0) // (a@x->2)

    // Permissions are untracked (R22): removing "keep"'s write bit changes nothing WorkTree scans,
    // so the dirty check still passes — the install's WRITE of the new "keep/y" is what fails.
    val keepDir = root.resolve("keep")
    val readOnly = PosixFilePermissions.fromString("r-xr-xr-x")
    Files.setPosixFilePermissions(keepDir, readOnly)
    try
      val before = repoBytes(root)
      val (exit, out, err) = run(root, "revert", "(a@x->1)")
      assertEquals(exit, 1)
      assertEquals(out, "")
      assert(err.startsWith("snap: cannot update working tree"), err)
      // The metadata replace must never have run: repository.json is byte-identical to before.
      assertEquals(repoBytes(root), before)
      // The untouched sibling file is exactly as committed — no rollback, no corruption either.
      assertEquals(textAt(root, "keep/x"), "1\n")
    finally
      // Restore write permission so temp-directory cleanup can remove the directory.
      Files.setPosixFilePermissions(keepDir, PosixFilePermissions.fromString("rwxr-xr-x"))
  }

  // ------------------------------------------------------------------------------- grammar

  test("revert requires exactly one operand (coarse R79; T13 owns the exhaustive matrix)") {
    val root = initRepo()
    assertEquals(run(root, "revert"), (1, "", "snap: invalid command or arguments\n"))
    assertEquals(
      run(root, "revert", "()", "extra"),
      (1, "", "snap: invalid command or arguments\n")
    )
  }

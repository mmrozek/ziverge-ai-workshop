package snap.cli

import snap.core.Change
import snap.core.ContributorId
import snap.core.Dot
import snap.core.Patch
import snap.core.Revision
import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Version

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** `snap commit` (SPEC §7.5, R85/D16): the full pipeline through [[Cli.run]] (mirroring tests
  * 04/08/25), the change-kind selection matrix, the message byte boundary, revision overflow, dot
  * collision, and determinism of the written repository under permuted file-creation order.
  */
class CommandsCommitSuite extends munit.FunSuite:

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  private def initRepo(id: String = "alice@example.com"): Path =
    val root = Files.createTempDirectory("snap-commit-test")
    assertEquals(run(root, "init")._1, 0)
    assertEquals(run(root, "config", "contributor.id", id)._1, 0)
    root

  private def write(root: Path, rel: String, text: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    ()

  private def repoBytes(root: Path): Vector[Byte] =
    Files.readAllBytes(root.resolve(".snap").resolve("repository.json")).toVector

  private def id(value: String): ContributorId = ContributorId.parse(value).toOption.get
  private def path(value: String): SnapPath = SnapPath.parse(value).toOption.get
  private def bytes(text: String): IArray[Byte] =
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  // ------------------------------------------------------------------ full pipeline (tests 04/25)

  test("commit prints the new version and increments across commits (test 04)") {
    val root = initRepo()
    write(root, "z.txt", "z\n")
    write(root, "a.txt", "a\n")
    assertEquals(
      run(root, "commit", "first\tline\nsecond\\tail"),
      (0, "(alice@example.com->1)\n", "")
    )
    write(root, "a.txt", "changed\n")
    Files.delete(root.resolve("z.txt"))
    write(root, "m.txt", "middle\n")
    assertEquals(run(root, "commit", "second"), (0, "(alice@example.com->2)\n", ""))
  }

  test("commit on a clean tree fails with the exact pinned line (test 04)") {
    val root = initRepo()
    write(root, "a.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    assertEquals(run(root, "commit", "clean"), (1, "", "snap: working tree is clean\n"))
  }

  test("an empty message is 'invalid commit message' even on a CLEAN tree (test 25 order)") {
    val root = initRepo()
    write(root, "a.txt", "a\n")
    assertEquals(run(root, "commit", "first")._1, 0)
    // Tree is clean now — the message check must still win.
    assertEquals(run(root, "commit", ""), (1, "", "snap: invalid commit message\n"))
  }

  test("commit without contributor configuration fails with the pinned R100 line") {
    val root = Files.createTempDirectory("snap-commit-noconfig")
    assertEquals(run(root, "init")._1, 0)
    write(root, "a.txt", "a\n")
    assertEquals(
      run(root, "commit", "no-config"),
      (1, "", "snap: contributor.id is required; configure it locally or globally\n")
    )
  }

  test("commit with an unsupported entry fails and leaves repository.json untouched (test 08)") {
    val root = initRepo("a@x")
    val before = repoBytes(root)
    Files.createSymbolicLink(root.resolve("link"), Path.of("missing"))
    assertEquals(
      run(root, "commit", "link"),
      (1, "", "snap: unsupported working tree entry: link\n")
    )
    assertEquals(repoBytes(root), before)
  }

  // T14 (R103, tests 15/23's own `keep.txt`/`local/keep` premise lifted into a project unit
  // test): `commit`'s first step is `Commands.readRepository` (repository load+validate) — a
  // corrupt on-disk repository must fail there, before the working-tree scan or ANY write, so
  // every working file AND `repository.json` itself stay byte-for-byte exactly as they were.
  test(
    "a corrupt repository.json blocks commit before touching anything else " +
      "(R103, tests 15/23's validation-before-mutation pattern)"
  ) {
    val root = initRepo("a@x")
    // Working files present before the corrupt repository is ever read — commit must never
    // reach the working-tree scan, so neither of these can move by even one byte.
    write(root, "keep.txt", "working bytes stay untouched\n")
    write(root, "nested/keep", "nested bytes stay untouched\n")
    // Test 15's own duplicate-key fixture, verbatim.
    val corrupt = """{"format":1,"format":1,"frontier":[],"patches":[]}"""
    Files.write(
      root.resolve(".snap").resolve("repository.json"),
      corrupt.getBytes(StandardCharsets.UTF_8)
    )
    val beforeRepo = repoBytes(root)
    val beforeKeep = Files.readAllBytes(root.resolve("keep.txt")).toVector
    val beforeNested = Files.readAllBytes(root.resolve("nested/keep")).toVector

    val (exit, out, err) = run(root, "commit", "should never be authored")

    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.contains("duplicate JSON key"), err)
    assertEquals(repoBytes(root), beforeRepo)
    assertEquals(Files.readAllBytes(root.resolve("keep.txt")).toVector, beforeKeep)
    assertEquals(Files.readAllBytes(root.resolve("nested/keep")).toVector, beforeNested)
  }

  test("committed repository bytes are independent of file creation order (determinism)") {
    def committed(names: List[String]): (String, Vector[Byte]) =
      val root = initRepo()
      names.foreach(n => write(root, n, s"content of $n\n"))
      val (exit, out, err) = run(root, "commit", "same message")
      assertEquals((exit, err), (0, ""))
      (out, repoBytes(root))
    val names = List("b.txt", "a/x", "z", "é", "😀")
    assertEquals(committed(names), committed(names.reverse))
  }

  // ------------------------------------------------------------------ message boundary (D16)

  test("message of exactly 4096 UTF-8 bytes is accepted; 4097 is rejected") {
    assertEquals(CommandsCommit.checkCommitMessage("a" * 4096), Right(()))
    assertEquals(
      CommandsCommit.checkCommitMessage("a" * 4097),
      Left(SnapError.InvalidCommitMessage)
    )
  }

  test("the limit counts BYTES: a two-byte é straddling the boundary is rejected") {
    // 4094 ASCII bytes + 2-byte é = 4096 → accepted; 4095 + 2 = 4097 → rejected even though
    // the CHARACTER count (4096) equals the accepted case's byte count.
    assertEquals(CommandsCommit.checkCommitMessage(("a" * 4094) + "é"), Right(()))
    assertEquals(
      CommandsCommit.checkCommitMessage(("a" * 4095) + "é"),
      Left(SnapError.InvalidCommitMessage)
    )
  }

  test("empty and control-character messages are the same pinned error (R48 via commit)") {
    assertEquals(CommandsCommit.checkCommitMessage(""), Left(SnapError.InvalidCommitMessage))
    assertEquals(
      CommandsCommit.checkCommitMessage("bad\u0001control"),
      Left(SnapError.InvalidCommitMessage)
    )
    // Tab and LF are allowed by R48.
    assertEquals(CommandsCommit.checkCommitMessage("tab\tand\nlf"), Right(()))
  }

  test("an oversized message through the CLI renders the pinned line (D16)") {
    val root = initRepo()
    write(root, "a.txt", "a\n")
    assertEquals(run(root, "commit", "a" * 4097), (1, "", "snap: invalid commit message\n"))
  }

  // ------------------------------------------------------------------ change-kind matrix (R85)

  private def onlyChange(deltas: Vector[Delta]): Change =
    val changes = CommandsCommit.buildChanges(deltas)
    assertEquals(changes.length, 1)
    changes(0)

  test("text over text is a text edit whose script rebuilds the new tokens") {
    val delta = Delta(path("f"), Some(bytes("a\nb\n")), Some(bytes("b\nc\n")))
    onlyChange(Vector(delta)) match
      case Change.Text(p, edit) =>
        assertEquals(p, path("f"))
        assertEquals(edit.applyTo(Vector("a\n", "b\n")), Right(Vector("b\n", "c\n")))
      case other => fail(s"expected a text change, got $other")
  }

  test("new text over absent is a creation edit from the empty token sequence (R58)") {
    val delta = Delta(path("f"), None, Some(bytes("hello\n")))
    onlyChange(Vector(delta)) match
      case Change.Text(_, edit) =>
        assertEquals(edit.applyTo(Vector.empty), Right(Vector("hello\n")))
      case other => fail(s"expected a text change, got $other")
  }

  test("a new EMPTY file is a text change with the empty script (R58)") {
    val delta = Delta(path("f"), None, Some(bytes("")))
    onlyChange(Vector(delta)) match
      case Change.Text(_, edit) => assertEquals(edit.ops, Vector.empty)
      case other                => fail(s"expected a text change, got $other")
  }

  test("text -> binary is a put with the exact new bytes") {
    val binary = IArray[Byte](0x00, 0x01, 0x02)
    val delta = Delta(path("f"), Some(bytes("text\n")), Some(binary))
    onlyChange(Vector(delta)) match
      case put: Change.Put => assertEquals(put, Change.Put(path("f"), binary))
      case other           => fail(s"expected a put change, got $other")
  }

  test("binary -> text is a put (the old side has no token sequence)") {
    val delta = Delta(path("f"), Some(IArray(0x00.toByte)), Some(bytes("now text\n")))
    onlyChange(Vector(delta)) match
      case put: Change.Put => assertEquals(put, Change.Put(path("f"), bytes("now text\n")))
      case other           => fail(s"expected a put change, got $other")
  }

  test("a new binary file is a put") {
    val binary = IArray[Byte](0x7f, 0x00, -1)
    val delta = Delta(path("f"), None, Some(binary))
    onlyChange(Vector(delta)) match
      case put: Change.Put => assertEquals(put, Change.Put(path("f"), binary))
      case other           => fail(s"expected a put change, got $other")
  }

  test("a removed path is a delete, whatever the old content was") {
    assertEquals(
      CommandsCommit.buildChanges(
        Vector(
          Delta(path("bin"), Some(IArray(0x00.toByte)), None),
          Delta(path("txt"), Some(bytes("text\n")), None)
        )
      ),
      Vector(Change.Delete(path("bin")), Change.Delete(path("txt")))
    )
  }

  // ------------------------------------------------------------------ revision + collision

  test("nextRevision is frontier(author) + 1, with absent = 0 (R46)") {
    val alice = id("a@x")
    val bob = id("b@x")
    assertEquals(CommandsCommit.nextRevision(Version.empty, alice), Right(1L))
    val five = Version.fromMap(Map(alice -> 5L)).toOption.get
    assertEquals(CommandsCommit.nextRevision(five, alice), Right(6L))
    assertEquals(CommandsCommit.nextRevision(five, bob), Right(1L))
  }

  test("a frontier already at 2^53−1 overflows: the next revision is out of bounds (R30/R85)") {
    val alice = id("a@x")
    val max = Version.fromMap(Map(alice -> Revision.Max)).toOption.get
    assertEquals(
      CommandsCommit.nextRevision(max, alice),
      Left(SnapError.RevisionNotSafeInteger)
    )
  }

  test("an existing dot for the new revision is a collision (R85, defensive)") {
    val alice = id("a@x")
    val patch = Patch
      .make(alice, 1L, Version.empty, "m", Vector(Change.Put(path("f"), bytes("x"))))
      .toOption
      .get
    assertEquals(
      CommandsCommit.checkNoCollision(Vector(patch), Dot(alice, 1L)),
      Left(SnapError.PatchCollision(Dot(alice, 1L)))
    )
    assertEquals(CommandsCommit.checkNoCollision(Vector(patch), Dot(alice, 2L)), Right(()))
  }

  test("insertSorted keeps author (Utf8Order) then numeric revision order (R44)") {
    def mk(author: String, revision: Long, base: Version): Patch =
      Patch
        .make(
          id(author),
          revision,
          base,
          "m",
          Vector(Change.Put(path("f"), bytes(revision.toString)))
        )
        .toOption
        .get
    val b1 = mk("b@x", 1L, Version.empty)
    val b2 = mk("b@x", 2L, Version.fromMap(Map(id("b@x") -> 1L)).toOption.get)
    val a1 = mk("a@x", 1L, Version.empty)
    assertEquals(
      CommandsCommit.insertSorted(Vector(b1, b2), a1).map(_.dot),
      Vector(Dot(id("a@x"), 1L), Dot(id("b@x"), 1L), Dot(id("b@x"), 2L))
    )
    assertEquals(
      CommandsCommit.insertSorted(Vector(a1, b1), b2).map(_.dot),
      Vector(Dot(id("a@x"), 1L), Dot(id("b@x"), 1L), Dot(id("b@x"), 2L))
    )
  }

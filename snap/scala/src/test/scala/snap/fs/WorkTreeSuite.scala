package snap.fs

import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Tree

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** [[WorkTree.scan]] (SPEC §2, R16–R21, R104; DESIGN §7, gotcha 7): exclusions, unsupported-entry
  * precedence with the exact pinned message, deterministic order, and the é/😀 filename round-trip
  * this JVM must support under the harness's scrubbed environment.
  */
class WorkTreeSuite extends munit.FunSuite:

  private def repoRoot(): Path =
    val root = Files.createTempDirectory("snap-worktree-test")
    Files.createDirectory(root.resolve(".snap"))
    root

  private def write(root: Path, rel: String, text: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    ()

  private def mkfifo(path: Path): Unit =
    val exit = new ProcessBuilder("mkfifo", path.toString).inheritIO().start().waitFor()
    assertEquals(exit, 0, s"mkfifo $path")

  private def scanPaths(root: Path): Vector[String] =
    WorkTree.scan(root).fold(e => fail(s"scan failed: ${e.message}"), _.paths.map(_.value))

  test("empty repository scans to the empty tree") {
    val root = repoRoot()
    assertEquals(WorkTree.scan(root).map(_.isEmpty), Right(true))
  }

  test("root .snap contents are excluded; a nested sub/.snap IS tracked (R16, D13)") {
    val root = repoRoot()
    write(root, ".snap/untracked", "metadata\n")
    write(root, "sub/.snap/inner", "tracked\n")
    write(root, "a.txt", "a\n")
    assertEquals(scanPaths(root), Vector("a.txt", "sub/.snap/inner"))
  }

  test(
    "a root-level .snap that is a symlink is reported as unsupported, not silently skipped (D25)"
  ) {
    val root = Files.createTempDirectory("snap-worktree-test")
    val realDir = Files.createTempDirectory("snap-worktree-test-target")
    Files.createSymbolicLink(root.resolve(".snap"), realDir)
    write(root, "a.txt", "a\n")
    val result = WorkTree.scan(root)
    assertEquals(result, Left(SnapError.UnsupportedWorkTreeEntry(".snap")))
    assertEquals(result.left.map(_.message), Left("unsupported working tree entry: .snap"))
  }

  test("a root-level .snap that is a regular file stays excluded, not reported (T10)") {
    val root = Files.createTempDirectory("snap-worktree-test")
    write(root, ".snap", "not metadata\n")
    write(root, "a.txt", "a\n")
    assertEquals(scanPaths(root), Vector("a.txt"))
  }

  test("empty directories are invisible, including nested ones (R19; test 25's premise)") {
    val root = repoRoot()
    Files.createDirectories(root.resolve("empty"))
    Files.createDirectories(root.resolve("deep").resolve("empty"))
    assertEquals(WorkTree.scan(root).map(_.isEmpty), Right(true))
  }

  test("a symlink fails with the exact pinned message, even when its target is a regular file") {
    val root = repoRoot()
    write(root, "target.txt", "content\n")
    Files.createSymbolicLink(root.resolve("link"), root.resolve("target.txt"))
    val result = WorkTree.scan(root)
    assertEquals(result, Left(SnapError.UnsupportedWorkTreeEntry("link")))
    assertEquals(result.left.map(_.message), Left("unsupported working tree entry: link"))
  }

  test(
    "an unsupported entry named with a control character renders as one line (PR1/CR3)"
  ) {
    val root = repoRoot()
    Files.createSymbolicLink(root.resolve("bad\nname"), Path.of("missing"))
    val result = WorkTree.scan(root)
    assertEquals(result, Left(SnapError.UnsupportedWorkTreeEntry("bad\nname")))
    val message = result.left.map(_.message)
    assertEquals(message, Left("unsupported working tree entry: bad\\nname"))
    assert(message.left.exists(!_.contains("\n")), message)
  }

  test("a broken symlink is unsupported too, never followed (test 08's shape)") {
    val root = repoRoot()
    Files.createSymbolicLink(root.resolve("link"), Path.of("missing"))
    assertEquals(WorkTree.scan(root), Left(SnapError.UnsupportedWorkTreeEntry("link")))
  }

  test("a FIFO fails with the exact pinned message (test 08)") {
    val root = repoRoot()
    mkfifo(root.resolve("pipe"))
    val result = WorkTree.scan(root)
    assertEquals(result.left.map(_.message), Left("unsupported working tree entry: pipe"))
  }

  test("an unsupported entry in a subdirectory renders its /-separated relative path") {
    val root = repoRoot()
    Files.createDirectories(root.resolve("sub"))
    mkfifo(root.resolve("sub").resolve("pipe"))
    assertEquals(WorkTree.scan(root), Left(SnapError.UnsupportedWorkTreeEntry("sub/pipe")))
  }

  test("an unsupported entry wins over an invalid path that sorts earlier (R104 precedence)") {
    val root = repoRoot()
    // "a\\b" (backslash in the name — invalid tracked path) sorts before "z" in every walk
    // order; the unsupported entry must still be the reported failure.
    write(root, "a\\b", "invalid\n")
    Files.createSymbolicLink(root.resolve("z"), Path.of("missing"))
    assertEquals(WorkTree.scan(root), Left(SnapError.UnsupportedWorkTreeEntry("z")))
  }

  test("a regular file with an invalid tracked path is a typed error (untested wording)") {
    val root = repoRoot()
    write(root, "a\\b", "invalid\n")
    val result = WorkTree.scan(root)
    assertEquals(result, Left(SnapError.InvalidWorkTreePath("a\\b")))
    assertEquals(result.left.map(_.message), Left("invalid working tree path: a\\b"))
  }

  test("é and 😀 filenames round-trip through write + rescan (gotcha 7, test 25's sort)") {
    val root = repoRoot()
    write(root, "z", "z\n")
    write(root, "é", "accent\n")
    write(root, "😀", "emoji\n")
    write(root, "nested/file", "nested\n")
    // Utf8Order: 'n' < 'z' < é (U+00E9) < 😀 (U+1F600) — test 25 pins exactly this order.
    assertEquals(scanPaths(root), Vector("nested/file", "z", "é", "😀"))
    val tree = WorkTree.scan(root).toOption.get
    val accent = tree.get(SnapPath.parse("é").toOption.get).get
    assertEquals(
      new String(IArray.genericWrapArray(accent).toArray, StandardCharsets.UTF_8),
      "accent\n"
    )
    val emoji = tree.get(SnapPath.parse("😀").toOption.get).get
    assertEquals(
      new String(IArray.genericWrapArray(emoji).toArray, StandardCharsets.UTF_8),
      "emoji\n"
    )
  }

  test("scan result is independent of file creation order and stable across rescans") {
    def populate(root: Path, names: List[String]): Tree =
      names.foreach(n => write(root, n, s"content of $n\n"))
      WorkTree.scan(root).toOption.get
    val names = List("b.txt", "a/x", "a/y", "z", "é")
    val first = populate(repoRoot(), names)
    val second = populate(repoRoot(), names.reverse)
    assertEquals(first, second)
    // Rescan of the same root is byte-identical too.
    val root = repoRoot()
    names.foreach(n => write(root, n, s"content of $n\n"))
    assertEquals(WorkTree.scan(root), WorkTree.scan(root))
  }

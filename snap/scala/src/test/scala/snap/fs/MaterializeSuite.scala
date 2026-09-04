package snap.fs

import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Tree

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** [[Materialize.install]] (SPEC §6.2/§10, R70/R105–R106; DESIGN §7): the working-directory
  * mutation primitive behind `revert` (T12). Covers the four-step mutation order — including both
  * directions of the file/directory transition test 07 exercises — determinism under permuted
  * `Tree` construction order, idempotence of re-installing an already-installed target, and the
  * partial-mutation shape an I/O failure leaves behind (R106).
  */
class MaterializeSuite extends munit.FunSuite:

  private def tempRoot(): Path = Files.createTempDirectory("snap-materialize-test")

  private def path(value: String): SnapPath =
    SnapPath.parse(value).fold(e => fail(s"expected valid path '$value': $e"), identity)

  private def bytes(text: String): IArray[Byte] =
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  private def tree(entries: (String, String)*): Tree =
    Tree.from(entries.map((p, text) => path(p) -> bytes(text)))

  private def write(root: Path, rel: String, text: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    ()

  private def scanPaths(root: Path): Vector[String] =
    WorkTree.scan(root).fold(e => fail(s"scan failed: ${e.message}"), _.paths.map(_.value))

  private def textAt(root: Path, rel: String): String =
    new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8)

  // ------------------------------------------------------------------------- basic install shape

  test("installing over an empty tree creates every target file and directory") {
    val root = tempRoot()
    val target = tree("a" -> "A\n", "b/c" -> "C\n")
    assertEquals(Materialize.install(root, Tree.empty, target), Right(()))
    assertEquals(scanPaths(root), Vector("a", "b/c"))
    assertEquals(textAt(root, "a"), "A\n")
    assertEquals(textAt(root, "b/c"), "C\n")
  }

  test("paths absent from the target are deleted; unrelated paths are untouched") {
    val root = tempRoot()
    write(root, "keep", "K\n")
    write(root, "drop", "D\n")
    val current = tree("keep" -> "K\n", "drop" -> "D\n")
    val target = tree("keep" -> "K\n")
    assertEquals(Materialize.install(root, current, target), Right(()))
    assertEquals(scanPaths(root), Vector("keep"))
    assertEquals(textAt(root, "keep"), "K\n")
  }

  test("changed bytes at an unchanged path are overwritten") {
    val root = tempRoot()
    write(root, "f", "old\n")
    val current = tree("f" -> "old\n")
    val target = tree("f" -> "new\n")
    assertEquals(Materialize.install(root, current, target), Right(()))
    assertEquals(textAt(root, "f"), "new\n")
  }

  // ------------------------------------------------------------------------- file/dir transitions (test 07)

  test("a file blocking a required directory is removed before the directory is created") {
    val root = tempRoot()
    write(root, "node", "file\n")
    val current = tree("node" -> "file\n")
    val target = tree("node/child" -> "child\n")
    assertEquals(Materialize.install(root, current, target), Right(()))
    assertEquals(scanPaths(root), Vector("node/child"))
    assert(Files.isDirectory(root.resolve("node")))
    assertEquals(textAt(root, "node/child"), "child\n")
  }

  test("a directory emptied by removal is pruned before its path is written as a file") {
    val root = tempRoot()
    write(root, "node/child", "child\n")
    val current = tree("node/child" -> "child\n")
    val target = tree("node" -> "file\n")
    assertEquals(Materialize.install(root, current, target), Right(()))
    assertEquals(scanPaths(root), Vector("node"))
    assert(Files.isRegularFile(root.resolve("node")))
    assertEquals(textAt(root, "node"), "file\n")
  }

  test("deleting a deep path prunes every emptied ancestor directory, not just the leaf") {
    val root = tempRoot()
    write(root, "a/b/c", "deep\n")
    write(root, "a/sibling", "kept\n")
    val current = tree("a/b/c" -> "deep\n", "a/sibling" -> "kept\n")
    val target = tree("a/sibling" -> "kept\n")
    assertEquals(Materialize.install(root, current, target), Right(()))
    assertEquals(scanPaths(root), Vector("a/sibling"))
    assert(!Files.exists(root.resolve("a/b")), "emptied intermediate directory must be pruned")
    assert(Files.isDirectory(root.resolve("a")), "'a' is still needed for the sibling")
  }

  test("reverting to the empty tree removes every file and leaves no directories behind") {
    val root = tempRoot()
    write(root, "a/b/c", "deep\n")
    val current = tree("a/b/c" -> "deep\n")
    assertEquals(Materialize.install(root, current, Tree.empty), Right(()))
    assertEquals(scanPaths(root), Vector.empty)
    assert(Files.list(root).toList.isEmpty, "root should hold no leftover entries")
  }

  // ------------------------------------------------------------------------------ determinism

  test("the on-disk result is independent of the target Tree's construction/insertion order") {
    def installed(entries: List[(String, String)]): (Vector[String], Map[String, String]) =
      val root = tempRoot()
      val target = Tree.from(entries.map((p, text) => path(p) -> bytes(text)))
      assertEquals(Materialize.install(root, Tree.empty, target), Right(()))
      val paths = scanPaths(root)
      (paths, paths.map(p => p -> textAt(root, p)).toMap)
    val entries =
      List("z" -> "Z\n", "a/x" -> "X\n", "a/y" -> "Y\n", "b" -> "B\n", "é" -> "accent\n")
    assertEquals(installed(entries), installed(entries.reverse))
  }

  test("installing an already-installed target is idempotent (no changes, no error)") {
    val root = tempRoot()
    val target = tree("a" -> "A\n", "b/c" -> "C\n")
    assertEquals(Materialize.install(root, Tree.empty, target), Right(()))
    val before = scanPaths(root).map(p => p -> textAt(root, p))
    // Re-installing the SAME target starting from itself must touch nothing observable.
    assertEquals(Materialize.install(root, target, target), Right(()))
    val after = scanPaths(root).map(p => p -> textAt(root, p))
    assertEquals(after, before)
  }

  // -------------------------------------------------------------------- partial-mutation on failure (R106)

  test(
    "a write failure part-way through leaves earlier writes in place and reports a typed error"
  ) {
    val root = tempRoot()
    // "blocked" sorts after "a" in Utf8Order, so the fold reaches "a" first; pre-seed "blocked" as
    // a directory (never a member of the `current` Tree Materialize is told about) so the later
    // `Files.write` for a plain file there fails without tripping any tree-content check.
    Files.createDirectories(root.resolve("blocked"))
    write(root, "blocked/occupied", "x\n")
    val target = tree("a" -> "A\n", "blocked" -> "B\n")
    Materialize.install(root, Tree.empty, target) match
      case Left(_: SnapError.CannotUpdateWorkingTree) => ()
      case other => fail(s"expected CannotUpdateWorkingTree, got $other")
    // The earlier write in Utf8Order succeeded before the failure — a partially updated, dirty
    // working tree is the spec's documented outcome (R106), not a rollback.
    assertEquals(textAt(root, "a"), "A\n")
  }

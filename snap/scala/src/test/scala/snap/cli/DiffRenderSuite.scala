package snap.cli

import snap.core.SnapPath
import snap.core.Tree

import java.nio.charset.StandardCharsets

/** [[DiffRender.render]] (SPEC §7.6, R87; DESIGN §8, D8): rendering goldens lifted from the
  * provided tests 05/06/26, the missing-final-LF marker on both sides, CRLF verbatim rendering, and
  * the binary↔text transition (untested by the provided suite, D8). Pure function of two [[Tree]]
  * values — no `Env`/filesystem access anywhere in this suite.
  */
class DiffRenderSuite extends munit.FunSuite:

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw': $e"), identity)

  private def utf8(text: String): IArray[Byte] =
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  private def tree(entries: (String, String)*): Tree =
    Tree.from(entries.map((path, content) => (p(path), utf8(content))))

  private def treeBytes(entries: (String, IArray[Byte])*): Tree =
    Tree.from(entries.map((path, content) => (p(path), content)))

  // ------------------------------------------------------------------ test 05 golden

  test("golden: repeated-line edits and a created file, both missing a final newline (test 05)") {
    val oldTree = tree("repeated.txt" -> "a\nb\na\n")
    val newTree = tree("repeated.txt" -> "b\na\na", "added.txt" -> "new")
    assertEquals(
      DiffRender.render(oldTree, newTree),
      """--- /dev/null
        |+++ b/added.txt
        |@@ -1,0 +1,1 @@
        |+new
        |\ No newline at end of file
        |--- a/repeated.txt
        |+++ b/repeated.txt
        |@@ -1,3 +1,3 @@
        |-a
        | b
        | a
        |+a
        |\ No newline at end of file
        |""".stripMargin
    )
  }

  test("equal trees render as empty stdout (test 05, R87)") {
    val t = tree("repeated.txt" -> "b\na\na", "added.txt" -> "new")
    assertEquals(DiffRender.render(t, t), "")
  }

  // ------------------------------------------------------------------ test 06 golden

  test("golden: binary create and empty-text-file create, blocks in path order (test 06)") {
    val oldTree = Tree.empty
    val newTree = treeBytes(
      "data.bin" -> IArray[Byte](0x00, 0xff.toByte, 0x80.toByte, 0x41, 0x42),
      "empty" -> utf8("")
    )
    assertEquals(
      DiffRender.render(oldTree, newTree),
      "Binary files /dev/null and b/data.bin differ\n" +
        "--- /dev/null\n+++ b/empty\n@@ -1,0 +1,0 @@\n"
    )
  }

  test("golden: binary delete (test 06)") {
    val oldTree = treeBytes("data.bin" -> IArray[Byte](0x00, 0xff.toByte, 0x80.toByte, 0x41, 0x42))
    assertEquals(
      DiffRender.render(oldTree, Tree.empty),
      "Binary files a/data.bin and /dev/null differ\n"
    )
  }

  // ------------------------------------------------------------------ missing-final-LF (both sides)

  test("a missing final LF on BOTH the deleted and the inserted side each get their own marker") {
    val oldTree = tree("f" -> "old")
    val newTree = tree("f" -> "new")
    assertEquals(
      DiffRender.render(oldTree, newTree),
      """--- a/f
        |+++ b/f
        |@@ -1,1 +1,1 @@
        |-old
        |\ No newline at end of file
        |+new
        |\ No newline at end of file
        |""".stripMargin
    )
  }

  test("a retained final token missing LF renders once (both sides share it)") {
    val oldTree = tree("f" -> "a\nb")
    val newTree = tree("f" -> "c\nb")
    assertEquals(
      DiffRender.render(oldTree, newTree),
      """--- a/f
        |+++ b/f
        |@@ -1,2 +1,2 @@
        |-a
        |+c
        | b
        |\ No newline at end of file
        |""".stripMargin
    )
  }

  // ------------------------------------------------------------------ CRLF verbatim (test 26)

  test("CRLF-bearing tokens render verbatim, including the literal CR byte (test 26)") {
    val newTree = tree("crlf.txt" -> "a\r\nb")
    assertEquals(
      DiffRender.render(Tree.empty, newTree),
      "--- /dev/null\n+++ b/crlf.txt\n@@ -1,0 +1,2 @@\n+a\r\n+b\n\\ No newline at end of file\n"
    )
  }

  // ------------------------------------------------------------------ binary <-> text transition (D8)

  test("text -> binary is a binary line, not a text block (D8)") {
    val oldTree = tree("f" -> "hello\n")
    val newTree = treeBytes("f" -> IArray[Byte](0x00, 0x01))
    assertEquals(DiffRender.render(oldTree, newTree), "Binary files a/f and b/f differ\n")
  }

  test("binary -> text is a binary line, not a text block (D8)") {
    val oldTree = treeBytes("f" -> IArray[Byte](0x00, 0x01))
    val newTree = tree("f" -> "hello\n")
    assertEquals(DiffRender.render(oldTree, newTree), "Binary files a/f and b/f differ\n")
  }

  test("any present non-text side forces the binary line even when the other side is absent") {
    val newTree = treeBytes("f" -> IArray(0x00.toByte))
    assertEquals(DiffRender.render(Tree.empty, newTree), "Binary files /dev/null and b/f differ\n")
  }

  // ------------------------------------------------------------------ path ordering & determinism

  test("multiple changed paths render in Utf8Order, independent of tree-build order") {
    def build(names: List[String]): Tree =
      Tree.from(names.map(n => (p(n), utf8(s"content of $n\n"))))
    val oldTree = Tree.empty
    val names = List("z.txt", "a.txt", "nested/file.txt")
    val forward = DiffRender.render(oldTree, build(names))
    val backward = DiffRender.render(oldTree, build(names.reverse))
    assertEquals(forward, backward)
    // a.txt < nested/file.txt < z.txt in Utf8Order.
    val headers = forward.linesIterator.filter(_.startsWith("+++ ")).toList
    assertEquals(headers, List("+++ b/a.txt", "+++ b/nested/file.txt", "+++ b/z.txt"))
  }

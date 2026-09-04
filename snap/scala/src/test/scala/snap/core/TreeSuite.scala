package snap.core

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class TreeSuite extends ScalaCheckSuite:

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw', got $e"), identity)

  private def bytes(s: String): IArray[Byte] =
    IArray.unsafeFromArray(s.getBytes("UTF-8")) // fresh array, never aliased

  private def treeOf(raws: String*): Tree =
    Tree.from(raws.map(raw => p(raw) -> bytes(raw)))

  private val entriesGen: Gen[List[(SnapPath, IArray[Byte])]] =
    Gen
      .listOf(Gen.zip(CoreGens.pathGen, CoreGens.bytesGen))
      .map(_.distinctBy(_._1)) // duplicate paths would make order matter (last wins)

  /** Deterministic permutation that is neither identity nor plain reverse. */
  private def interleave[A](xs: List[A]): List[A] =
    val (evens, odds) = xs.zipWithIndex.partition(_._2 % 2 == 0)
    odds.map(_._1) ::: evens.map(_._1).reverse

  test("empty tree") {
    assertEquals(Tree.empty.size, 0)
    assert(Tree.empty.isEmpty)
    assertEquals(Tree.empty.paths, Vector.empty)
    assert(Tree.empty.isPrefixFree)
  }

  test("updated / get / contains / removed") {
    val tree = Tree.empty.updated(p("a"), bytes("one"))
    assert(tree.contains(p("a")))
    assert(!tree.contains(p("b")))
    assertEquals(tree.get(p("a")).map(_.toSeq), Some(bytes("one").toSeq))
    assertEquals(tree.get(p("b")), None)
    val overwritten = tree.updated(p("a"), bytes("two"))
    assertEquals(overwritten.size, 1)
    assertEquals(overwritten.get(p("a")).map(_.toSeq), Some(bytes("two").toSeq))
    assertEquals(tree.removed(p("a")), Tree.empty)
    assertEquals(tree.removed(p("missing")), tree)
  }

  test("iteration follows Utf8Order (test 25's pinned order), not insertion order") {
    val tree = treeOf("😀", "z", "é", "nested/file")
    assertEquals(tree.paths.map(_.value), Vector("nested/file", "z", "é", "😀"))
    assertEquals(tree.toVector.map(_._1.value), Vector("nested/file", "z", "é", "😀"))
  }

  property("iteration order and equality are insertion-order-independent") {
    forAll(entriesGen) { (entries: List[(SnapPath, IArray[Byte])]) =>
      val once = Tree.from(entries)
      val reversed = Tree.from(entries.reverse)
      val interleaved = Tree.from(interleave(entries))
      val again = Tree.from(entries)
      assertEquals(reversed.paths, once.paths)
      assertEquals(interleaved.paths, once.paths)
      assertEquals(once.paths, once.paths.sortBy(_.value)(using Utf8Order))
      // Full structural agreement: equal values, equal hashes, identical entries.
      assertEquals(reversed, once)
      assertEquals(interleaved, once)
      assertEquals(again, once)
      assertEquals(reversed.hashCode, once.hashCode)
      assertEquals(interleaved.hashCode, once.hashCode)
    }
  }

  test("equality is by byte content, not array identity") {
    val a = Tree.from(List(p("f") -> bytes("same")))
    val b = Tree.from(List(p("f") -> bytes("same")))
    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
    assertNotEquals(a, Tree.from(List(p("f") -> bytes("other"))))
    assertNotEquals(a, Tree.from(List(p("g") -> bytes("same"))))
    assertNotEquals(a, a.updated(p("g"), bytes("extra")))
  }

  test("ancestorsOf returns tree paths that are proper ancestors, root-first") {
    val tree = treeOf("a", "a/b", "x")
    assertEquals(tree.ancestorsOf(p("a/b/c/d")), Vector(p("a"), p("a/b")))
    assertEquals(tree.ancestorsOf(p("a/b")), Vector(p("a"))) // proper only
    assertEquals(tree.ancestorsOf(p("ab")), Vector.empty) // 'a' is not a segment prefix
    assertEquals(tree.ancestorsOf(p("x")), Vector.empty)
  }

  test("descendantsOf returns tree paths below the query, in Utf8Order") {
    val tree = treeOf("a/b/c", "a/bd", "ab", "b")
    assertEquals(tree.descendantsOf(p("a")), Vector(p("a/b/c"), p("a/bd")))
    assertEquals(tree.descendantsOf(p("a/b")), Vector(p("a/b/c")))
    assertEquals(tree.descendantsOf(p("ab")), Vector.empty)
    assertEquals(tree.descendantsOf(p("a/b/c")), Vector.empty) // not proper descendant of itself
  }

  test("isPrefixFree matches R25 on the tree's path set") {
    assert(treeOf("a", "ab", "b/c").isPrefixFree)
    assert(!treeOf("a", "a/b").isPrefixFree)
    assert(!treeOf("a/b/c", "a").isPrefixFree)
    assert(treeOf("a/b", "a/c").isPrefixFree)
  }

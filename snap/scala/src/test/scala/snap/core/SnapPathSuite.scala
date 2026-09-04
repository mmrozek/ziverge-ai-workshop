package snap.core

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class SnapPathSuite extends ScalaCheckSuite:

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw', got $e"), identity)

  // --- validation: one test per rule (R23, D12, D13) ---

  test("rejects the empty path") {
    assertEquals(SnapPath.parse(""), Left(PathError.Empty))
  }

  test("rejects ASCII control characters 0x00–0x1F") {
    assertEquals(SnapPath.parse("a\u0000b"), Left(PathError.IllegalCharacter))
    assertEquals(SnapPath.parse("a\tb"), Left(PathError.IllegalCharacter))
    assertEquals(SnapPath.parse("a\nb"), Left(PathError.IllegalCharacter))
    assertEquals(SnapPath.parse("a\u001fb"), Left(PathError.IllegalCharacter))
  }

  test("rejects DEL 0x7F (D12: it is an ASCII control character)") {
    assertEquals(SnapPath.parse("a\u007fb"), Left(PathError.IllegalCharacter))
  }

  test("rejects backslash") {
    assertEquals(SnapPath.parse("a\\b"), Left(PathError.IllegalCharacter))
    assertEquals(SnapPath.parse("\\"), Left(PathError.IllegalCharacter))
  }

  test("rejects unpaired surrogates (not UTF-8)") {
    assertEquals(SnapPath.parse("a\ud800b"), Left(PathError.MalformedUnicode))
    assertEquals(SnapPath.parse("a\udc00"), Left(PathError.MalformedUnicode))
  }

  test("rejects empty segments: leading, trailing, doubled separator") {
    assertEquals(SnapPath.parse("/a"), Left(PathError.EmptySegment))
    assertEquals(SnapPath.parse("a/"), Left(PathError.EmptySegment))
    assertEquals(SnapPath.parse("a//b"), Left(PathError.EmptySegment))
    assertEquals(SnapPath.parse("/"), Left(PathError.EmptySegment))
  }

  test("rejects '.' and '..' segments anywhere") {
    assertEquals(SnapPath.parse("."), Left(PathError.DotSegment))
    assertEquals(SnapPath.parse(".."), Left(PathError.DotSegment))
    assertEquals(SnapPath.parse("./a"), Left(PathError.DotSegment))
    assertEquals(SnapPath.parse("a/./b"), Left(PathError.DotSegment))
    assertEquals(SnapPath.parse("a/.."), Left(PathError.DotSegment))
    assertEquals(SnapPath.parse("../a"), Left(PathError.DotSegment))
  }

  test("rejects first segment '.snap'") {
    assertEquals(SnapPath.parse(".snap"), Left(PathError.ReservedFirstSegment))
    assertEquals(SnapPath.parse(".snap/x"), Left(PathError.ReservedFirstSegment))
    assertEquals(SnapPath.parse(".snap/x/y"), Left(PathError.ReservedFirstSegment))
  }

  test("accepts nested .snap segments (D13): sub/.snap/x and x/.snap") {
    assertEquals(p("sub/.snap/x").value, "sub/.snap/x")
    assertEquals(p("x/.snap").value, "x/.snap")
  }

  test("accepts non-ASCII UTF-8 and ordinary edge names") {
    List("a", "nested/file", "é", "😀/file", ".snapx", ".hidden", "...", "a b", "a.b/c.d")
      .foreach(raw => assertEquals(p(raw).value, raw))
  }

  test("no Unicode normalization: NFC é and NFD e+combining acute stay distinct") {
    val nfc = p("\u00e9") // e-acute as one code point
    val nfd = p("e\u0301") // e + combining acute
    assertNotEquals(nfc, nfd)
    assert(Utf8Order.compare(nfc.value, nfd.value) != 0)
  }

  // --- segment access ---

  test("segments splits on '/'") {
    assertEquals(p("a/b/c").segments, Vector("a", "b", "c"))
    assertEquals(p("a").segments, Vector("a"))
  }

  test("ancestors are the proper segment prefixes, root-first") {
    assertEquals(p("a/b/c").ancestors, Vector(p("a"), p("a/b")))
    assertEquals(p("a").ancestors, Vector.empty)
  }

  test("isAncestorOf is by segment, proper: a ~ a/b yes, a ~ ab no, a ~ a no") {
    assert(p("a").isAncestorOf(p("a/b")))
    assert(p("a").isAncestorOf(p("a/b/c")))
    assert(!p("a").isAncestorOf(p("ab")))
    assert(!p("a").isAncestorOf(p("a")))
    assert(p("a/b").isDescendantOf(p("a")))
    assert(!p("ab").isDescendantOf(p("a")))
  }

  // --- prefix-free predicate (R25) ---

  test("prefix-free: 'a' + 'a/b' rejected") {
    assert(!SnapPath.prefixFree(List(p("a"), p("a/b"))))
  }

  test("prefix-free: 'a' + 'ab' accepted") {
    assert(SnapPath.prefixFree(List(p("a"), p("ab"))))
  }

  test("prefix-free: siblings 'a/b' + 'a/c' accepted") {
    assert(SnapPath.prefixFree(List(p("a/b"), p("a/c"))))
  }

  test("prefix-free: empty and singleton sets, and a depth-2 violation") {
    assert(SnapPath.prefixFree(Nil))
    assert(SnapPath.prefixFree(List(p("a"))))
    assert(!SnapPath.prefixFree(List(p("b/c/d"), p("a"), p("b"))))
  }

  property("prefix-free is independent of input order and of duplication") {
    forAll(Gen.listOf(CoreGens.pathGen)) { (paths: List[SnapPath]) =>
      val expected = SnapPath.prefixFree(paths)
      assertEquals(SnapPath.prefixFree(paths.reverse), expected)
      assertEquals(SnapPath.prefixFree(paths.sortBy(_.value)(using Utf8Order)), expected)
      assertEquals(SnapPath.prefixFree(paths ++ paths), expected)
    }
  }

  property("ordering on SnapPath agrees with Utf8Order on the raw value") {
    forAll(CoreGens.pathGen, CoreGens.pathGen) { (a: SnapPath, b: SnapPath) =>
      assertEquals(
        Integer.signum(SnapPath.ordering.compare(a, b)),
        Integer.signum(Utf8Order.compare(a.value, b.value))
      )
    }
  }

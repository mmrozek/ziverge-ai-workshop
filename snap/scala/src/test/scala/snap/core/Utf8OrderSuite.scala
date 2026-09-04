package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.charset.StandardCharsets
import java.util.Arrays

/** Pins Utf8Order's semantics: unsigned lexicographic order of the UTF-8 encodings (SPEC §2/§3.2,
  * DESIGN D23). T04 defines a comparator with the same contract — this suite is the shared semantic
  * definition.
  */
class Utf8OrderSuite extends munit.ScalaCheckSuite:

  test("basic ASCII ordering and prefix rule") {
    assert(Utf8Order.compare("a", "b") < 0)
    assert(Utf8Order.compare("b", "a") > 0)
    assertEquals(Utf8Order.compare("abc", "abc"), 0)
    assert(Utf8Order.compare("", "a") < 0) // empty precedes everything nonempty
    assert(Utf8Order.compare("ab", "abc") < 0) // strict prefix sorts first
    assert(Utf8Order.compare("Z", "a") < 0) // uppercase before lowercase in ASCII
  }

  test("test-25 path order: nested/file < z < é < 😀") {
    val sorted = Vector("é", "z", "😀", "nested/file").sorted(Utf8Order)
    assertEquals(sorted, Vector("nested/file", "z", "é", "😀"))
  }

  test("supplementary characters sort above U+E000..U+FFFF (unlike String.compareTo)") {
    val bmp = "\ufffd" // U+FFFD
    val supplementary = "\ud800\udc00" // U+10000 as a surrogate pair
    assert(Utf8Order.compare(bmp, supplementary) < 0) // correct UTF-8 byte order
    assert(bmp.compareTo(supplementary) > 0) // UTF-16 code-unit order gets this wrong
  }

  private val genCodePoint: Gen[Int] =
    Gen.frequency(
      6 -> Gen.chooseNum(0x20, 0x7e),
      2 -> Gen.chooseNum(0x80, 0xd7ff),
      1 -> Gen.chooseNum(0xe000, 0xffff),
      1 -> Gen.chooseNum(0x10000, 0x10ffff)
    )

  private val genUnicodeString: Gen[String] =
    Gen.listOf(genCodePoint).map(cps => new String(cps.toArray, 0, cps.length))

  private def utf8ByteOrder(a: String, b: String): Int =
    Arrays.compareUnsigned(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8)
    )

  property("Utf8Order equals unsigned byte order of the UTF-8 encodings") {
    forAll(genUnicodeString, genUnicodeString) { (a, b) =>
      assertEquals(
        math.signum(Utf8Order.compare(a, b)),
        math.signum(utf8ByteOrder(a, b)),
        s"strings: ${a.codePoints.toArray.toVector} vs ${b.codePoints.toArray.toVector}"
      )
    }
  }

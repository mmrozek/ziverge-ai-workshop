package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.charset.StandardCharsets

class TextTokensSuite extends munit.ScalaCheckSuite:

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  test("spec golden: a CRLF b tokenizes to [a\\r\\n, b]") {
    assertEquals(TextTokens.tokenize("a\r\nb"), Vector("a\r\n", "b"))
  }

  test("empty file has no tokens") {
    assertEquals(TextTokens.tokenize(""), Vector.empty[String])
    assertEquals(TextTokens.tokenizeBytes(Array.emptyByteArray), Some(Vector.empty[String]))
  }

  test("splits immediately after every LF, retaining LF") {
    assertEquals(TextTokens.tokenize("a\nb\na\n"), Vector("a\n", "b\n", "a\n"))
    assertEquals(TextTokens.tokenize("\n"), Vector("\n"))
    assertEquals(TextTokens.tokenize("\n\n"), Vector("\n", "\n"))
    assertEquals(TextTokens.tokenize("a"), Vector("a"))
    assertEquals(TextTokens.tokenize("a\nb"), Vector("a\n", "b"))
  }

  test("text detection accepts plain and multibyte UTF-8") {
    assert(TextTokens.isText(utf8("hello\n")))
    assert(TextTokens.isText(utf8("é😀\n")))
    assert(TextTokens.isText(Array.emptyByteArray))
    // 4-byte boundaries: U+10000 and U+10FFFF
    assert(TextTokens.isText(Array(0xf0, 0x90, 0x80, 0x80).map(_.toByte)))
    assert(TextTokens.isText(Array(0xf4, 0x8f, 0xbf, 0xbf).map(_.toByte)))
    // 2- and 3-byte lower boundaries: U+0080 and U+0800
    assert(TextTokens.isText(Array(0xc2, 0x80).map(_.toByte)))
    assert(TextTokens.isText(Array(0xe0, 0xa0, 0x80).map(_.toByte)))
  }

  test("bytes with NUL are not text") {
    assert(!TextTokens.isText(Array[Byte](0x61, 0x00, 0x62)))
    assertEquals(TextTokens.decode(Array[Byte](0x00)), None)
  }

  test("invalid UTF-8 is not text") {
    val bad = List(
      List(0x80), // stray continuation byte
      List(0xc3), // truncated 2-byte sequence
      List(0xc0, 0x80), // overlong NUL
      List(0xc1, 0xbf), // overlong 2-byte
      List(0xe0, 0x80, 0x80), // overlong 3-byte
      List(0xed, 0xa0, 0x80), // encoded UTF-16 surrogate U+D800
      List(0xf0, 0x80, 0x80, 0x80), // overlong 4-byte
      List(0xf4, 0x90, 0x80, 0x80), // above U+10FFFF
      List(0xf5, 0x80, 0x80, 0x80), // invalid lead byte
      List(0xff), // invalid lead byte
      List(0xe2, 0x82), // truncated 3-byte sequence
      List(0x61, 0xc3, 0x28) // continuation byte missing mid-stream
    )
    bad.foreach { bs =>
      assert(!TextTokens.isText(bs.map(_.toByte).toArray), bs.map(b => f"0x$b%02x"))
      assertEquals(TextTokens.decode(bs.map(_.toByte).toArray), None)
    }
  }

  // ------------------------------------------------------------ decodeUtf8 (CR-NUL)

  test("decodeUtf8 accepts a raw NUL — unlike decode, which treats NUL as non-text (CR-NUL)") {
    assertEquals(TextTokens.decodeUtf8(Array[Byte](0x61, 0x00, 0x62)), Some("a\u0000b"))
    assertEquals(TextTokens.decode(Array[Byte](0x61, 0x00, 0x62)), None)
  }

  test("decodeUtf8 still rejects genuinely invalid UTF-8") {
    assertEquals(TextTokens.decodeUtf8(Array[Byte](0x80.toByte)), None)
  }

  test("canonical token sequence predicate") {
    assert(TextTokens.isCanonical(Vector.empty))
    assert(TextTokens.isCanonical(Vector("a\n", "b\n")))
    assert(TextTokens.isCanonical(Vector("a\r\n", "b")))
    assert(!TextTokens.isCanonical(Vector("a", "b\n"))) // non-final token lacks LF
    assert(!TextTokens.isCanonical(Vector("a\nb"))) // interior LF
    assert(!TextTokens.isCanonical(Vector(""))) // empty token
    assert(!TextTokens.isCanonical(Vector("a\n", "")))
  }

  test("text-token predicate (insert-token validation)") {
    assert(TextTokens.isTextToken("a\n"))
    assert(TextTokens.isTextToken("a")) // final-position token
    assert(TextTokens.isTextToken("é😀\n"))
    assert(!TextTokens.isTextToken(""))
    assert(!TextTokens.isTextToken("a\nb"))
    assert(!TextTokens.isTextToken("a\u0000\n"))
    assert(!TextTokens.isTextToken("\ud800")) // unpaired surrogate: no UTF-8 form
  }

  // Strings over an LF/CR-heavy alphabet exercise every split position.
  private val textGen: Gen[String] =
    Gen.listOf(Gen.oneOf('a', 'b', '\n', '\r')).map(_.mkString)

  property("tokenize is lossless: tokens concatenate back to the text") {
    forAll(textGen) { s =>
      assertEquals(TextTokens.render(TextTokens.tokenize(s)), s)
    }
  }

  property("tokenize output is always a canonical token sequence") {
    forAll(textGen) { s =>
      assert(TextTokens.isCanonical(TextTokens.tokenize(s)))
    }
  }

  property("decode inverts UTF-8 encoding for NUL-free text") {
    forAll(textGen) { s =>
      assertEquals(TextTokens.decode(utf8(s)), Some(s))
    }
  }

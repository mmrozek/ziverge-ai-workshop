package snap.core

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

/** Shared scalacheck generators for core tests. */
object CoreGens:
  // Every entry is a valid path segment (nonempty, no separator/control/backslash,
  // not "." or "..", not ".snap"), so joining any of them with "/" parses.
  private val segmentPool: Seq[String] =
    Seq("a", "b", "z", "ab", "file", "nested", "sub", "x1", "é", "😀", ".hidden", "...", "data")

  val pathGen: Gen[SnapPath] =
    for
      n <- Gen.choose(1, 4)
      segments <- Gen.listOfN(n, Gen.oneOf(segmentPool))
    yield parseOrAbort(segments.mkString("/"))

  val bytesGen: Gen[IArray[Byte]] =
    // The freshly generated array is never aliased or mutated afterwards, so the
    // zero-copy wrap is safe.
    Gen.listOf(Arbitrary.arbitrary[Byte]).map(bs => IArray.unsafeFromArray(bs.toArray))

  /** Strings of Unicode scalar values (no surrogates), so UTF-8 encoding is total. Ranges
    * deliberately cover U+E000..U+FFFF and supplementary planes, where UTF-16 code-unit order
    * diverges from UTF-8 byte order.
    */
  val unicodeStringGen: Gen[String] =
    val scalarValue: Gen[Int] = Gen.oneOf(
      Gen.chooseNum(0x20, 0x7e), // ASCII printable — collision-heavy on purpose
      Gen.chooseNum(0x01, 0xd7ff),
      Gen.chooseNum(0xe000, 0x10ffff)
    )
    Gen.listOf(scalarValue).map(_.map(cp => String(Character.toChars(cp))).mkString)

  def parseOrAbort(raw: String): SnapPath =
    // Test-only helper: inputs are valid by construction.
    SnapPath.parse(raw).toOption.get

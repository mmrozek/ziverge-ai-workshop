package snap.json

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property tests for the strict JSON layer: `parse(write(v)) == v`, plus byte-determinism of both
  * directions. (There is no order-permutation property at this layer by design — object field order
  * is part of the AST value, so permuted fields are a different value; the codec layers above
  * compare typed values instead.)
  */
class JsonRoundTripSuite extends ScalaCheckSuite:

  // -- generators ------------------------------------------------------------

  /** Valid Unicode text: ASCII, named escapes, other control chars, non-ASCII BMP chars, and a
    * supplementary character (surrogate pair). Built from whole code points so no unpaired
    * surrogate can appear.
    */
  private val textGen: Gen[String] =
    val piece: Gen[String] = Gen.oneOf(
      Gen.choose(0x20, 0x7e).map(_.toChar.toString),
      Gen.oneOf('\n', '\t', '\r', '\b', '\f', '"', '\\').map(_.toString),
      Gen.choose(0x00, 0x1f).map(_.toChar.toString),
      Gen.oneOf("é", "−", "�", new String(Character.toChars(0x1f600)))
    )
    Gen.chooseNum(0, 12).flatMap(n => Gen.listOfN(n, piece)).map(_.mkString)

  /** Safe integers, as raw decimal text. */
  private val integerRawGen: Gen[String] =
    Gen.choose(-9007199254740991L, 9007199254740991L).map(_.toString)

  /** Non-integer JSON numbers (fraction and/or exponent), as raw text. */
  private val decimalRawGen: Gen[String] =
    for
      sign <- Gen.oneOf("", "-")
      whole <- Gen.oneOf(Gen.const("0"), Gen.choose(1L, 999999L).map(_.toString))
      fraction <- Gen.option(Gen.choose(0, 999).map(n => "." + n.toString))
      exponent <- Gen.option(
        for
          marker <- Gen.oneOf("e", "E")
          expSign <- Gen.oneOf("", "+", "-")
          digits <- Gen.choose(0, 99)
        yield marker + expSign + digits.toString
      )
    yield sign + whole + fraction.getOrElse("") + exponent.getOrElse("")

  private val scalarGen: Gen[Json] = Gen.oneOf(
    Gen.const(Json.JNull),
    Gen.oneOf(true, false).map(Json.JBool(_)),
    textGen.map(Json.JString(_)),
    Gen.oneOf(integerRawGen, decimalRawGen).map(Json.JNumber(_))
  )

  private def jsonGen(depth: Int): Gen[Json] =
    if depth <= 0 then scalarGen
    else
      Gen.frequency(
        3 -> scalarGen,
        1 -> Gen
          .chooseNum(0, 4)
          .flatMap(n => Gen.listOfN(n, Gen.lzy(jsonGen(depth - 1))))
          .map(items => Json.JArray(items.toVector)),
        1 -> Gen
          .chooseNum(0, 4)
          .flatMap(n => Gen.listOfN(n, Gen.zip(textGen, Gen.lzy(jsonGen(depth - 1)))))
          // Duplicate keys are invalid input by contract; keep first occurrences
          // (deterministic) so generated objects are always parseable.
          .map(fields => Json.JObject(fields.distinctBy(_._1).toVector))
      )

  private val anyJson: Gen[Json] = jsonGen(3)

  // -- properties ------------------------------------------------------------

  property("parse(write(v)) == v") {
    forAll(anyJson) { (value: Json) =>
      assertEquals(JsonParser.parse(Writer.write(value)), Right(value))
    }
  }

  property("write is byte-deterministic across repeated runs") {
    forAll(anyJson) { (value: Json) =>
      val first = Writer.writeUtf8(value)
      val second = Writer.writeUtf8(value)
      assert(first.sameElements(second))
    }
  }

  property("write . parse . write is a fixed point (idempotent serialization)") {
    forAll(anyJson) { (value: Json) =>
      val written = Writer.write(value)
      assertEquals(JsonParser.parse(written).map(Writer.write), Right(written))
    }
  }

  property("parse is deterministic across repeated runs") {
    forAll(anyJson) { (value: Json) =>
      val written = Writer.write(value)
      assertEquals(JsonParser.parse(written), JsonParser.parse(written))
    }
  }

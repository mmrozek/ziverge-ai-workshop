package snap.json

import munit.FunSuite
import snap.core.ContributorId
import snap.core.SnapError

/** Typed contributor-configuration decode/encode (SPEC §8, R98). */
class ConfigCodecSuite extends FunSuite:

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  private def decodeText(text: String): Either[SnapError, ContributorId] =
    JsonParser.parse(text).flatMap(ConfigCodec.decode)

  test("decodes the spec's exact shape") {
    assertEquals(
      decodeText("""{"contributor":{"id":"alice@example.com"}}"""),
      Right(id("alice@example.com"))
    )
  }

  test("encode-then-decode round-trips") {
    val original = id("bob@example.com")
    val bytes = ConfigCodec.encodeBytes(original)
    val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(decodeText(text), Right(original))
  }

  test("encodes exactly the two-field shape, nothing else (SPEC §8: preserves no unknown fields)") {
    assertEquals(
      Writer.write(ConfigCodec.encode(id("a@x"))),
      "{\n  \"contributor\": {\n    \"id\": \"a@x\"\n  }\n}\n"
    )
  }

  test("a top-level value that is not an object is rejected") {
    assertEquals(decodeText("""["contributor"]"""), Left(SnapError.ConfigNotObject))
  }

  test("an unknown top-level field is rejected, naming the field") {
    assertEquals(
      decodeText("""{"contributor":{"id":"a@x"},"unknown":true}"""),
      Left(SnapError.UnknownField("config", "unknown"))
    )
  }

  test("an unknown contributor field is rejected, naming the field") {
    assertEquals(
      decodeText("""{"contributor":{"id":"a@x","extra":1}}"""),
      Left(SnapError.UnknownField("contributor", "extra"))
    )
  }

  test("a missing contributor field is rejected") {
    assertEquals(decodeText("""{}"""), Left(SnapError.MissingField("config", "contributor")))
  }

  test("a missing id field is rejected") {
    assertEquals(
      decodeText("""{"contributor":{}}"""),
      Left(SnapError.MissingField("contributor", "id"))
    )
  }

  test("a non-string id is rejected") {
    assertEquals(
      decodeText("""{"contributor":{"id":1}}"""),
      Left(SnapError.FieldWrongType("contributor", "id"))
    )
  }

  test("a non-object contributor value is rejected") {
    assertEquals(
      decodeText("""{"contributor":"a@x"}"""),
      Left(SnapError.FieldWrongType("config", "contributor"))
    )
  }

  test("an invalid id is rejected with the `invalid contributor id` diagnostic (tests 03/25)") {
    decodeText("""{"contributor":{"id":"not-an-id"}}""") match
      case Left(e) => assert(e.message.startsWith("invalid contributor id: "), e.message)
      case other   => fail(s"expected a rejection, got $other")
  }

  test("a duplicate JSON key is rejected (R41)") {
    assertEquals(
      decodeText("""{"contributor":{"id":"a@x","id":"b@x"}}"""),
      Left(SnapError.DuplicateJsonKey("id"))
    )
  }

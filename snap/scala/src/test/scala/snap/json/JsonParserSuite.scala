package snap.json

import snap.core.SnapError

class JsonParserSuite extends munit.FunSuite:

  private def parseFailureMessage(input: String): String =
    JsonParser.parse(input) match
      case Left(error)  => error.message
      case Right(value) => fail(s"expected a parse failure, got $value")

  // --- structural parsing --------------------------------------------------

  test("parses the empty repository document to the exact AST") {
    val obtained = JsonParser.parse("""{"format":1,"frontier":[],"patches":[]}""")
    val expected = Json.JObject(
      Vector(
        "format" -> Json.JNumber("1"),
        "frontier" -> Json.JArray(Vector.empty),
        "patches" -> Json.JArray(Vector.empty)
      )
    )
    assertEquals(obtained, Right(expected))
  }

  test("object fields preserve source order") {
    val obtained = JsonParser.parse("""{"b":1,"a":2,"0":3}""")
    val expected = Json.JObject(
      Vector(
        "b" -> Json.JNumber("1"),
        "a" -> Json.JNumber("2"),
        "0" -> Json.JNumber("3")
      )
    )
    assertEquals(obtained, Right(expected))
  }

  test("accepts ordinary whitespace and top-level scalars") {
    assertEquals(
      JsonParser.parse(" \t\n{ \"a\" : true }\n"),
      Right(Json.JObject(Vector("a" -> Json.JBool(true))))
    )
    assertEquals(JsonParser.parse("null"), Right(Json.JNull))
    assertEquals(JsonParser.parse("false"), Right(Json.JBool(false)))
    assertEquals(JsonParser.parse("\"x\""), Right(Json.JString("x")))
    assertEquals(JsonParser.parse("3"), Right(Json.JNumber("3")))
  }

  test("unescapes strings including surrogate pairs") {
    assertEquals(
      JsonParser.parse("""{"p":"é 😀 \\ \" \n"}"""),
      Right(Json.JObject(Vector("p" -> Json.JString("é 😀 \\ \" \n"))))
    )
    assertEquals(JsonParser.parse("\"\\ud83d\\ude00\""), Right(Json.JString("😀")))
  }

  test("keeps literal non-ASCII text") {
    assertEquals(
      JsonParser.parse("""{"p":"é😀"}"""),
      Right(Json.JObject(Vector("p" -> Json.JString("é😀"))))
    )
  }

  // --- duplicate keys (R41; tests 15/25) -----------------------------------

  test("rejects a duplicate key naming the key") {
    assertEquals(
      JsonParser.parse("""{"a":1,"a":2}"""),
      Left(SnapError.DuplicateJsonKey("a"))
    )
    assertEquals(parseFailureMessage("""{"a":1,"a":2}"""), "duplicate JSON key a")
  }

  test("rejects a duplicate key in a nested object (test 25 shape)") {
    val input = """{"contributor": {"id": "a@x", "id": "b@x"}}"""
    assertEquals(JsonParser.parse(input), Left(SnapError.DuplicateJsonKey("id")))
    assertEquals(parseFailureMessage(input), "duplicate JSON key id")
  }

  test("reports the first duplicate key in document order") {
    assertEquals(
      JsonParser.parse("""{"b":1,"b":2,"a":3,"a":4}"""),
      Left(SnapError.DuplicateJsonKey("b"))
    )
  }

  test(
    "a duplicate key containing LF renders as one physical line in the message (PR1/CR3)"
  ) {
    val input = "{\"a\\nb\":1,\"a\\nb\":2}"
    assertEquals(JsonParser.parse(input), Left(SnapError.DuplicateJsonKey("a\nb")))
    val message = parseFailureMessage(input)
    assertEquals(message, "duplicate JSON key a\\nb")
    assert(!message.contains("\n"), message)
  }

  test("a duplicate key wins over a later syntax error") {
    assertEquals(
      JsonParser.parse("""{"a":1,"a":2,"""),
      Left(SnapError.DuplicateJsonKey("a"))
    )
  }

  test("a syntax error before any duplicate key reports invalid JSON") {
    assert(parseFailureMessage("""{"a":!,"a":1}""").contains("invalid JSON"))
  }

  test("the same key in different objects is not a duplicate") {
    assertEquals(
      JsonParser.parse("""{"a":{"x":1},"b":{"x":2}}"""),
      Right(
        Json.JObject(
          Vector(
            "a" -> Json.JObject(Vector("x" -> Json.JNumber("1"))),
            "b" -> Json.JObject(Vector("x" -> Json.JNumber("2")))
          )
        )
      )
    )
  }

  test("duplicate string values are not mistaken for duplicate keys") {
    assertEquals(
      JsonParser.parse("""{"a":"k","b":"k"}"""),
      Right(Json.JObject(Vector("a" -> Json.JString("k"), "b" -> Json.JString("k"))))
    )
  }

  // --- invalid JSON diagnostic class (R41; tests 03/13) --------------------

  private val malformedInputs = List(
    "unterminated string" -> """{"a": "unterminated""",
    "bad escape" -> """{"a": "\x"}""",
    "trailing garbage" -> """{"a": 1} trailing""",
    "second top-level value" -> """{} []""",
    "empty input" -> "",
    "whitespace-only input" -> "   \n\t",
    "bare open brace" -> "{",
    "missing colon" -> """{"a" 1}""",
    "single quotes" -> "{'a': 1}",
    "leading zero number" -> """{"a": 01}""",
    "bare word" -> "nope",
    "raw control char in string" -> "{\"a\": \"\u0001\"}"
  )

  malformedInputs.foreach { case (label, input) =>
    test(s"malformed JSON ($label) maps to the invalid JSON class") {
      assert(
        parseFailureMessage(input).contains("invalid JSON"),
        s"message for $label: ${parseFailureMessage(input)}"
      )
    }
  }

  // --- numbers: raw text retained, integers judged from text (gotcha 4) ----

  test("numbers retain their raw decimal text") {
    assertEquals(
      JsonParser.parse("""{"n": 1.50e+2}"""),
      Right(Json.JObject(Vector("n" -> Json.JNumber("1.50e+2"))))
    )
  }

  test("parses 9007199254740991 as a safe integer") {
    JsonParser.parse("""{"n": 9007199254740991}""") match
      case Right(Json.JObject(Vector(("n", number)))) =>
        assertEquals(number.asSafeInteger, Some(9007199254740991L))
      case other => fail(s"unexpected parse result: $other")
  }

  test("rejects 9007199254740992 as a safe integer, judged from text") {
    JsonParser.parse("""{"n": 9007199254740992}""") match
      case Right(Json.JObject(Vector(("n", number)))) =>
        // Double round-trip would accept this value (gotcha 4); text must not.
        assertEquals(number.asSafeInteger, None)
      case other => fail(s"unexpected parse result: $other")
  }

  private val rejectedIntegers =
    List(
      "1.5",
      "1e2",
      "1.0",
      "1E0",
      "9007199254740992",
      "-9007199254740992",
      "99999999999999999999",
      "01"
    )
  private val acceptedIntegers = List(
    "0" -> 0L,
    "-0" -> 0L,
    "1" -> 1L,
    "-1" -> -1L,
    "42" -> 42L,
    "9007199254740991" -> 9007199254740991L,
    "-9007199254740991" -> -9007199254740991L
  )

  rejectedIntegers.foreach { raw =>
    test(s"asSafeInteger rejects $raw") {
      assertEquals(Json.JNumber(raw).asSafeInteger, None)
    }
  }

  acceptedIntegers.foreach { case (raw, value) =>
    test(s"asSafeInteger accepts $raw") {
      assertEquals(Json.JNumber(raw).asSafeInteger, Some(value))
    }
  }

  test("asSafeInteger is None for non-numbers") {
    assertEquals(Json.JString("1").asSafeInteger, None)
    assertEquals(Json.JNull.asSafeInteger, None)
  }

  // --- determinism ----------------------------------------------------------

  test("parsing the same input twice yields identical results") {
    val inputs = List("""{"b":1,"a":[2,3],"c":{"d":"e"}}""", """{"a":1,"a":2}""", "{", "")
    inputs.foreach { input =>
      assertEquals(JsonParser.parse(input), JsonParser.parse(input))
    }
  }

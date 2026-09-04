package snap.json

class WriterSuite extends munit.FunSuite:

  /** Lifted verbatim from `snap/tests/12-http-server.yaml` (`body_text_equals` — the byte-pinned
    * served snapshot, R42/D7): 419 bytes, empty `base` array inline, each `[id, revision]` pair
    * member on its own line, LF after the string literal `one\n` stays two characters (backslash,
    * n), one trailing LF. Triple-quoted Scala literals do not process escapes, so this embeds the
    * exact bytes.
    */
  private val golden12: String =
    """|{
       |  "format": 1,
       |  "frontier": [
       |    [
       |      "a@x",
       |      1
       |    ]
       |  ],
       |  "patches": [
       |    {
       |      "author": "a@x",
       |      "revision": 1,
       |      "base": [],
       |      "message": "one",
       |      "changes": [
       |        {
       |          "type": "text",
       |          "path": "file.txt",
       |          "edit": [
       |            {
       |              "insert": [
       |                "one\n"
       |              ]
       |            }
       |          ]
       |        }
       |      ]
       |    }
       |  ]
       |}
       |""".stripMargin

  private val golden12Value: Json =
    Json.JObject(
      Vector(
        "format" -> Json.JNumber("1"),
        "frontier" -> Json.JArray(
          Vector(Json.JArray(Vector(Json.JString("a@x"), Json.JNumber("1"))))
        ),
        "patches" -> Json.JArray(
          Vector(
            Json.JObject(
              Vector(
                "author" -> Json.JString("a@x"),
                "revision" -> Json.JNumber("1"),
                "base" -> Json.JArray(Vector.empty),
                "message" -> Json.JString("one"),
                "changes" -> Json.JArray(
                  Vector(
                    Json.JObject(
                      Vector(
                        "type" -> Json.JString("text"),
                        "path" -> Json.JString("file.txt"),
                        "edit" -> Json.JArray(
                          Vector(
                            Json.JObject(
                              Vector(
                                "insert" -> Json.JArray(Vector(Json.JString("one\n")))
                              )
                            )
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

  test("the embedded golden matches the contract's byte count") {
    assertEquals(golden12.getBytes("UTF-8").length, 419)
  }

  test("writer reproduces test 12's exact serialization (golden)") {
    assertEquals(Writer.write(golden12Value), golden12)
  }

  test("golden parses back to the exact value") {
    assertEquals(JsonParser.parse(golden12), Right(golden12Value))
  }

  test("parse-then-write of the golden is byte-identical (fixed point)") {
    assertEquals(JsonParser.parse(golden12).map(Writer.write), Right(golden12))
  }

  test("empty containers are written inline") {
    assertEquals(Writer.write(Json.JArray(Vector.empty)), "[]\n")
    assertEquals(Writer.write(Json.JObject(Vector.empty)), "{}\n")
    assertEquals(
      Writer.write(Json.JObject(Vector("a" -> Json.JArray(Vector.empty)))),
      "{\n  \"a\": []\n}\n"
    )
  }

  test("every element of a non-empty array is on its own line") {
    val value = Json.JArray(
      Vector(
        Json.JNumber("1"),
        Json.JArray(Vector(Json.JNumber("2"), Json.JNumber("3")))
      )
    )
    assertEquals(
      Writer.write(value),
      "[\n  1,\n  [\n    2,\n    3\n  ]\n]\n"
    )
  }

  test("top-level scalars serialize with a trailing LF") {
    assertEquals(Writer.write(Json.JNull), "null\n")
    assertEquals(Writer.write(Json.JBool(true)), "true\n")
    assertEquals(Writer.write(Json.JBool(false)), "false\n")
    assertEquals(Writer.write(Json.JNumber("42")), "42\n")
    assertEquals(Writer.write(Json.JString("x")), "\"x\"\n")
  }

  test("numbers are emitted as their raw text, untouched") {
    assertEquals(Writer.write(Json.JNumber("1.50e+2")), "1.50e+2\n")
    assertEquals(Writer.write(Json.JNumber("9007199254740992")), "9007199254740992\n")
  }

  test("string escaping: required escapes only, non-ASCII literal") {
    assertEquals(Writer.write(Json.JString("a\"b")), "\"a\\\"b\"\n")
    assertEquals(Writer.write(Json.JString("a\\b")), "\"a\\\\b\"\n")
    assertEquals(Writer.write(Json.JString("a\nb\tc\rd")), "\"a\\nb\\tc\\rd\"\n")
    assertEquals(Writer.write(Json.JString("\b\f")), "\"\\b\\f\"\n")
    assertEquals(Writer.write(Json.JString("\u0001\u001f")), "\"\\u0001\\u001f\"\n")
    assertEquals(Writer.write(Json.JString("é😀")), "\"é😀\"\n")
  }

  test("object fields are written in stored order") {
    val value = Json.JObject(Vector("b" -> Json.JNumber("1"), "a" -> Json.JNumber("2")))
    assertEquals(Writer.write(value), "{\n  \"b\": 1,\n  \"a\": 2\n}\n")
  }

  test("writeUtf8 equals the UTF-8 bytes of write") {
    val value = Json.JObject(Vector("p" -> Json.JString("é😀")))
    assert(Writer.writeUtf8(value).sameElements(Writer.write(value).getBytes("UTF-8")))
  }

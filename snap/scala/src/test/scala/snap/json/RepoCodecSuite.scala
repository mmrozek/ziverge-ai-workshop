package snap.json

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import snap.core.Change
import snap.core.ContributorId
import snap.core.CoreGens
import snap.core.EditOp
import snap.core.EditScript
import snap.core.Patch
import snap.core.Repo
import snap.core.Repository
import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Version

import java.nio.charset.StandardCharsets

/** Typed repository decode/encode (SPEC §4.1–§4.3, R40–R52). Rejection fixtures lift the exact
  * inputs of provided tests 15/23/27; the asserted messages are the strings those tests pin.
  */
class RepoCodecSuite extends ScalaCheckSuite:

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw': $e"), identity)

  private def v(pairs: (String, Long)*): Version =
    Version
      .fromPairs(pairs.toVector)
      .fold(e => fail(s"expected valid version: ${e.message}"), identity)

  private def decodeText(text: String): Either[SnapError, Repository] =
    JsonParser.parse(text).flatMap(RepoCodec.decode)

  private def decodeOrFail(text: String): Repository =
    decodeText(text).fold(e => fail(s"expected decode to succeed: ${e.message}"), identity)

  private def rejectionMessage(text: String): String =
    decodeText(text) match
      case Left(e)  => e.message
      case Right(_) => fail("expected decode to reject")

  /** Wraps one patch-object body into a syntactically complete repository document. */
  private def repoWithPatch(patchBody: String, frontier: String = """[["a@x", 1]]"""): String =
    s"""{"format": 1, "frontier": $frontier, "patches": [$patchBody]}"""

  /** Wraps one change into a well-formed single-patch repository document. */
  private def repoWithChange(change: String): String =
    repoWithPatch(
      s"""{"author": "a@x", "revision": 1, "base": [], "message": "m", "changes": [$change]}"""
    )

  // ------------------------------------------------------------ spec example

  /** The exact example document of SPEC §4.1. */
  private val specExample = """{
    |  "format": 1,
    |  "frontier": [["alice@example.com",1]],
    |  "patches": [
    |    {
    |      "author": "alice@example.com",
    |      "revision": 1,
    |      "base": [],
    |      "message": "add greeting",
    |      "changes": [
    |        {
    |          "type": "text",
    |          "path": "hello.txt",
    |          "edit": [{"insert":["hello\n"]}]
    |        }
    |      ]
    |    }
    |  ]
    |}""".stripMargin

  private val specExampleValue = Repository(
    v("alice@example.com" -> 1L),
    Vector(
      Patch
        .make(
          id("alice@example.com"),
          1L,
          Version.empty,
          "add greeting",
          Vector(
            Change.Text(p("hello.txt"), EditScript(Vector(EditOp.Insert(Vector("hello\n")))))
          )
        )
        .fold(e => fail(s"expected valid patch: ${e.message}"), identity)
    )
  )

  test("decodes the spec §4.1 example into the exact typed value") {
    assertEquals(decodeOrFail(specExample), specExampleValue)
  }

  test("the decoded example passes structural validation and its result version is the frontier") {
    val validated = Repo.validate(decodeOrFail(specExample))
    assertEquals(validated.map(_.results), Right(Vector(v("alice@example.com" -> 1L))))
  }

  // ------------------------------------------------------------ unknown fields (R43)

  test("top-level unknown field: exact pinned message (test 23)") {
    val text = """{"format": 1, "frontier": [], "patches": [], "unknown": true}"""
    assertEquals(rejectionMessage(text), "repository has unknown field: unknown")
  }

  test("patch-level unknown field names the field (test 27)") {
    val text = repoWithPatch(
      """{"author": "a@x", "revision": 1, "base": [], "message": "x",
        | "changes": [{"type": "text", "path": "f", "edit": []}], "unknown": true}""".stripMargin
    )
    assertEquals(rejectionMessage(text), "patch has unknown field: unknown")
  }

  test("change-level unknown field: pinned fragment anchored at the line end (test 23)") {
    val message = rejectionMessage(
      repoWithChange("""{"type": "put", "path": "f", "content": "YQ==", "extra": 1}""")
    )
    assertEquals(message, "change has unknown field: extra")
    assert(message.endsWith("unknown field: extra"))
  }

  test("variant-foreign fields are unknown: content on a text change, edit on a put") {
    assertEquals(
      rejectionMessage(
        repoWithChange("""{"type": "text", "path": "f", "edit": [], "content": "YQ=="}""")
      ),
      "change has unknown field: content"
    )
    assertEquals(
      rejectionMessage(
        repoWithChange("""{"type": "delete", "path": "f", "edit": []}""")
      ),
      "change has unknown field: edit"
    )
  }

  // ------------------------------------------------------------ missing fields / wrong types

  test("missing required fields are named at every level") {
    assertEquals(rejectionMessage("""{}"""), "repository is missing field: format")
    assertEquals(
      rejectionMessage("""{"format": 1, "patches": []}"""),
      "repository is missing field: frontier"
    )
    assertEquals(
      rejectionMessage("""{"format": 1, "frontier": []}"""),
      "repository is missing field: patches"
    )
    assertEquals(
      rejectionMessage(
        repoWithPatch("""{"revision": 1, "base": [], "message": "m", "changes": []}""")
      ),
      "patch is missing field: author"
    )
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "text", "edit": []}""")),
      "change is missing field: path"
    )
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "put", "path": "f"}""")),
      "change is missing field: content"
    )
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "text", "path": "f"}""")),
      "change is missing field: edit"
    )
  }

  test("wrong JSON types are rejected") {
    assertEquals(rejectionMessage("""[1, 2]"""), "repository is not a JSON object")
    assertEquals(
      rejectionMessage("""{"format": 1, "frontier": [], "patches": {}}"""),
      "repository field patches has the wrong type"
    )
    assertEquals(
      rejectionMessage("""{"format": 1, "frontier": [], "patches": [42]}"""),
      "patch is not a JSON object"
    )
    assertEquals(
      rejectionMessage(
        repoWithPatch("""{"author": 5, "revision": 1, "base": [], "message": "m", "changes": []}""")
      ),
      "patch field author has the wrong type"
    )
    assertEquals(
      rejectionMessage(
        repoWithPatch(
          """{"author": "a@x", "revision": 1, "base": [], "message": 5, "changes": []}"""
        )
      ),
      "patch field message has the wrong type"
    )
    assertEquals(
      rejectionMessage(
        repoWithPatch(
          """{"author": "a@x", "revision": 1, "base": [], "message": "m", "changes": 5}"""
        )
      ),
      "patch field changes has the wrong type"
    )
    assertEquals(rejectionMessage(repoWithChange("""5""")), "change is not a JSON object")
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "text", "path": "f", "edit": {}}""")),
      "change field edit has the wrong type"
    )
  }

  test("format must be the integer 1") {
    assertEquals(
      rejectionMessage("""{"format": 2, "frontier": [], "patches": []}"""),
      "repository format must be 1"
    )
    assertEquals(
      rejectionMessage("""{"format": "1", "frontier": [], "patches": []}"""),
      "repository format must be 1"
    )
    assertEquals(
      rejectionMessage("""{"format": 1.0, "frontier": [], "patches": []}"""),
      "repository format must be 1"
    )
  }

  // ------------------------------------------------------------ versions (R32)

  test("noncanonical frontier order: pinned `canonical` fragment (test 23)") {
    val text = """{"format": 1, "frontier": [["b@x", 1], ["a@x", 1]], "patches": []}"""
    assert(rejectionMessage(text).contains("canonical"))
  }

  test("frontier pair shape and revision rules") {
    assertEquals(
      rejectionMessage("""{"format": 1, "frontier": [["a@x"]], "patches": []}"""),
      "frontier must be an array of [id, revision] pairs"
    )
    assertEquals(
      rejectionMessage("""{"format": 1, "frontier": 5, "patches": []}"""),
      "frontier must be an array of [id, revision] pairs"
    )
    assertEquals(
      rejectionMessage("""{"format": 1, "frontier": [["a@x", 0]], "patches": []}"""),
      "revision must be a positive safe integer"
    )
    assertEquals(
      rejectionMessage("""{"format": 1, "frontier": [["a@x", 1.5]], "patches": []}"""),
      "revision must be a positive safe integer"
    )
    assert(
      rejectionMessage("""{"format": 1, "frontier": [["bad id", 1]], "patches": []}""")
        .contains("contributor id")
    )
  }

  test("fractional patch revision: pinned fragment anchored at the line end (test 23)") {
    val text = repoWithPatch(
      """{"author": "a@x", "revision": 1.5, "base": [], "message": "fraction",
        | "changes": [{"type": "text", "path": "f", "edit": []}]}""".stripMargin
    )
    val message = rejectionMessage(text)
    assertEquals(message, "revision must be a positive safe integer")
    assert(message.endsWith("positive safe integer"))
  }

  test("revision above 2^53−1 is rejected from the raw text (gotcha 4)") {
    val text = repoWithPatch(
      """{"author": "a@x", "revision": 9007199254740992, "base": [], "message": "m",
        | "changes": [{"type": "text", "path": "f", "edit": []}]}""".stripMargin
    )
    assertEquals(rejectionMessage(text), "revision must be a positive safe integer")
  }

  test("patch base decodes through the same version rules (R32)") {
    val text = repoWithPatch(
      """{"author": "a@x", "revision": 1, "base": [["b@x", 1], ["a@y", 1]], "message": "m",
        | "changes": [{"type": "text", "path": "f", "edit": []}]}""".stripMargin
    )
    assert(rejectionMessage(text).contains("canonical"))
  }

  // ------------------------------------------------------------ patch value rules (R48/R49)

  test("empty message: pinned fragment anchored at the line end (test 23)") {
    val text = repoWithPatch(
      """{"author": "a@x", "revision": 1, "base": [], "message": "",
        | "changes": [{"type": "text", "path": "f", "edit": []}]}""".stripMargin
    )
    val message = rejectionMessage(text)
    assertEquals(message, "patch message is empty")
    assert(message.endsWith("message is empty") && message != "message is empty")
  }

  test("empty changes: pinned fragment anchored at the line end (test 23)") {
    val text = repoWithPatch(
      """{"author": "a@x", "revision": 1, "base": [], "message": "none", "changes": []}"""
    )
    val message = rejectionMessage(text)
    assertEquals(message, "patch changes is empty")
    assert(message.endsWith("changes is empty") && message != "changes is empty")
  }

  test("changes out of path order are rejected (test 27)") {
    val text = repoWithPatch(
      """{"author": "a@x", "revision": 1, "base": [], "message": "order",
        | "changes": [
        |   {"type": "text", "path": "z", "edit": []},
        |   {"type": "text", "path": "a", "edit": []}
        | ]}""".stripMargin
    )
    assertEquals(rejectionMessage(text), "patch changes are not sorted by path")
  }

  // ------------------------------------------------------------ paths (R23)

  test("every decoded path goes through SnapPath.parse: pinned `path is invalid` (test 15)") {
    val cases = Vector(
      """{"type": "put", "path": ".snap/secret", "content": "YQ=="}""", // test 15
      """{"type": "text", "path": "", "edit": []}""",
      """{"type": "delete", "path": "a//b"}""",
      """{"type": "delete", "path": "../up"}""",
      """{"type": "delete", "path": "a\\b"}"""
    )
    cases.foreach { change =>
      val message = rejectionMessage(repoWithChange(change))
      assertEquals(message, "change path is invalid")
      assert(message.contains("path is invalid"))
    }
    // nested .snap below the root is tracked (D13)
    assert(decodeText(repoWithChange("""{"type": "delete", "path": "sub/.snap"}""")).isRight)
  }

  // ------------------------------------------------------------ base64 (R50)

  test("non-canonical base64 is rejected: pinned `canonical base64` (test 15)") {
    val message = rejectionMessage(
      repoWithChange("""{"type": "put", "path": "f", "content": "abc"}""") // test 15: bad length
    )
    assertEquals(message, "change content is not canonical base64")
    assert(message.contains("canonical base64"))
    // nonzero trailing bits: decodes but does not re-encode to itself
    assert(
      decodeText(repoWithChange("""{"type": "put", "path": "f", "content": "YR=="}""")).isLeft
    )
    // misplaced or excess padding, foreign alphabet
    Vector("YQ=", "=YQ=", "Y Q==", "YQ==YQ==", "YQ*=").foreach { content =>
      assert(
        decodeText(
          repoWithChange(s"""{"type": "put", "path": "f", "content": "$content"}""")
        ).isLeft,
        content
      )
    }
  }

  test("canonical base64 round-trips to the exact bytes") {
    val decoded =
      decodeOrFail(repoWithChange("""{"type": "put", "path": "f", "content": "AAEC"}"""))
    decoded.patches(0).changes(0) match
      case Change.Put(_, content) =>
        assertEquals(content.toVector, Vector[Byte](0, 1, 2)) // spec §4.3 example value
      case other => fail(s"expected a put, got $other")
    // empty content is canonical (zero bytes)
    val empty = decodeOrFail(repoWithChange("""{"type": "put", "path": "f", "content": ""}"""))
    empty.patches(0).changes(0) match
      case Change.Put(_, content) => assertEquals(content.length, 0)
      case other                  => fail(s"expected a put, got $other")
  }

  // ------------------------------------------------------------ change / edit schema (R50, R54)

  test("change type must be text, put, or delete") {
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "move", "path": "f"}""")),
      "change type must be text, put, or delete"
    )
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": 5, "path": "f"}""")),
      "change field type has the wrong type"
    )
  }

  test("edit operations are one-key objects: pinned fragment at the line end (test 23)") {
    val message = rejectionMessage(
      repoWithChange("""{"type": "text", "path": "f", "edit": [{"retain": 1, "delete": 1}]}""")
    )
    assertEquals(message, "edit operation must have one operation")
    assert(message.endsWith("must have one operation"))
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "text", "path": "f", "edit": [{}]}""")),
      "edit operation must have one operation"
    )
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "text", "path": "f", "edit": [5]}""")),
      "edit operation is not a JSON object"
    )
    assertEquals(
      rejectionMessage(repoWithChange("""{"type": "text", "path": "f", "edit": [{"foo": 1}]}""")),
      "edit operation must be retain, delete, or insert"
    )
  }

  test("edit counts: retain 0 pinned fragment at the line end (test 23)") {
    val message = rejectionMessage(
      repoWithChange("""{"type": "text", "path": "f", "edit": [{"retain": 0}]}""")
    )
    assertEquals(message, "edit count is not a positive safe integer")
    assert(message.endsWith("positive safe integer"))
    assertEquals(
      rejectionMessage(
        repoWithChange("""{"type": "text", "path": "f", "edit": [{"delete": 1.5}]}""")
      ),
      "edit count is not a positive safe integer"
    )
    assertEquals(
      rejectionMessage(
        repoWithChange("""{"type": "text", "path": "f", "edit": [{"retain": true}]}""")
      ),
      "edit count is not a positive safe integer"
    )
  }

  test("empty insert: pinned fragment at the line end (test 23)") {
    val message = rejectionMessage(
      repoWithChange("""{"type": "text", "path": "f", "edit": [{"insert": []}]}""")
    )
    assertEquals(message, "edit insert is empty")
    assert(message.endsWith("insert is empty"))
    assertEquals(
      rejectionMessage(
        repoWithChange("""{"type": "text", "path": "f", "edit": [{"insert": [5]}]}""")
      ),
      "edit insert token is not a text token"
    )
    assertEquals(
      rejectionMessage(
        repoWithChange("""{"type": "text", "path": "f", "edit": [{"insert": 5}]}""")
      ),
      "edit operation field insert has the wrong type"
    )
  }

  test("adjacent same-kind operations: pinned `adjacent insert` fragment (test 15)") {
    val message = rejectionMessage(
      repoWithChange(
        """{"type": "text", "path": "f", "edit": [{"insert": ["a\n"]}, {"insert": ["b\n"]}]}"""
      )
    )
    assertEquals(message, "edit has adjacent insert operations")
    assert(message.contains("adjacent insert"))
  }

  test("duplicate JSON keys are rejected by the strict parser (test 15)") {
    assert(
      rejectionMessage("""{"format":1,"format":1,"frontier":[],"patches":[]}""")
        .contains("duplicate JSON key")
    )
  }

  // ------------------------------------------------------------ structural equality (R47)

  test("whitespace and key order do not affect the decoded value (test 26's premise)") {
    val reordered = """{
      |  "patches": [ { "changes": [ { "edit": [ { "insert": ["hello\n"] } ],
      |        "path"   : "hello.txt", "type": "text" } ],
      |      "message": "add greeting", "base": [],
      |      "revision": 1, "author": "alice@example.com" } ],
      |  "frontier": [["alice@example.com", 1]],
      |  "format": 1
      |}""".stripMargin
    assertEquals(decodeOrFail(reordered), decodeOrFail(specExample))
    assertEquals(decodeOrFail(reordered).patches, decodeOrFail(specExample).patches)
  }

  test("put content compares by bytes across separately decoded documents (R47)") {
    val doc = repoWithChange("""{"type": "put", "path": "f", "content": "AAEC"}""")
    val spaced = doc.replace(""""type": "put"""", """    "type"   :   "put"""")
    assertEquals(decodeOrFail(doc), decodeOrFail(spaced))
  }

  // ------------------------------------------------------------ encode (D7/R42)

  /** The canonical serialization of the spec §4.1 example — Writer's byte-pinned style (test 12):
    * two-space indent, every array element on its own line, trailing LF.
    */
  private val specExampleCanonical = """{
    |  "format": 1,
    |  "frontier": [
    |    [
    |      "alice@example.com",
    |      1
    |    ]
    |  ],
    |  "patches": [
    |    {
    |      "author": "alice@example.com",
    |      "revision": 1,
    |      "base": [],
    |      "message": "add greeting",
    |      "changes": [
    |        {
    |          "type": "text",
    |          "path": "hello.txt",
    |          "edit": [
    |            {
    |              "insert": [
    |                "hello\n"
    |              ]
    |            }
    |          ]
    |        }
    |      ]
    |    }
    |  ]
    |}
    |""".stripMargin

  test("encode produces the canonical writer bytes for the spec example") {
    assertEquals(
      new String(RepoCodec.encodeBytes(specExampleValue), StandardCharsets.UTF_8),
      specExampleCanonical
    )
  }

  test("decode of the canonical encoding is the identity on the example") {
    assertEquals(decodeOrFail(specExampleCanonical), specExampleValue)
  }

  // ------------------------------------------------------------ properties

  private def changeGen(path: SnapPath): Gen[Change] =
    Gen.oneOf(
      Gen.const(Change.Text(path, EditScript(Vector(EditOp.Insert(Vector("x\n")))))),
      Gen.const(Change.Text(path, EditScript.empty)),
      CoreGens.bytesGen.map(content => Change.Put(path, content)),
      Gen.const(Change.Delete(path))
    )

  private val changesGen: Gen[Vector[Change]] =
    for
      paths <- Gen
        .nonEmptyListOf(CoreGens.pathGen)
        .map(_.distinct.sorted(using SnapPath.ordering).toVector)
      changes <- paths.foldLeft(Gen.const(Vector.empty[Change])) { (acc, path) =>
        acc.flatMap(out => changeGen(path).map(out :+ _))
      }
    yield changes

  /** A serial chain of `length` patches by one contributor, each based on its predecessor. */
  private def chainGen(author: ContributorId, length: Int): Gen[Vector[Patch]] =
    (1 to length)
      .foldLeft(Gen.const((Vector.empty[Patch], Version.empty))) { (accGen, n) =>
        for
          (acc, base) <- accGen
          changes <- changesGen
          message <- Gen.oneOf("m", "two\nlines", "tab\there")
        yield
          val patch = Patch
            .make(author, n.toLong, base, message, changes)
            .fold(e => fail(s"generator built an invalid patch: ${e.message}"), identity)
          val result = patch.result
            .fold(e => fail(s"generator built an invalid result: ${e.message}"), identity)
          (acc :+ patch, result)
      }
      .map(_._1)

  /** Structurally valid repositories: independent per-contributor chains (bases stay inside each
    * chain, so closure, reachability, and acyclicity hold by construction), patches listed in
    * author order, frontier = join of the chain results.
    */
  private val repositoryGen: Gen[Repository] =
    for
      lengthA <- Gen.choose(0, 3)
      lengthB <- Gen.choose(0, 2)
      chainA <- chainGen(id("a@x"), lengthA)
      chainB <- chainGen(id("b@x"), lengthB)
    yield
      val patches = chainA ++ chainB // "a@x" < "b@x" in Utf8Order
      val frontier = (chainA.lastOption.toVector ++ chainB.lastOption.toVector)
        .map(_.result.fold(e => fail(s"invalid result: ${e.message}"), identity))
        .foldLeft(Version.empty)(_.join(_))
      Repository(frontier, patches)

  property("encode/decode round-trips every generated repository (R41/R47)") {
    forAll(repositoryGen) { repository =>
      val bytes = RepoCodec.encodeBytes(repository)
      val decoded = decodeText(new String(bytes, StandardCharsets.UTF_8))
      assertEquals(decoded, Right(repository))
    }
  }

  property("canonical encoding is byte-stable across decode/encode cycles (D7)") {
    forAll(repositoryGen) { repository =>
      val once = RepoCodec.encodeBytes(repository)
      val decoded = decodeOrFail(new String(once, StandardCharsets.UTF_8))
      val twice = RepoCodec.encodeBytes(decoded)
      assert(once.sameElements(twice))
    }
  }

  property("generated repositories pass structural validation deterministically") {
    forAll(repositoryGen) { repository =>
      val first = Repo.validate(repository)
      assert(first.isRight)
      assertEquals(Repo.validate(repository), first)
    }
  }

package snap.core

/** ContributorId validation per SPEC §3.1 (R28–R29) and locked decision D12 (control characters are
  * 0x00–0x1F and 0x7F).
  */
class ContributorIdSuite extends munit.FunSuite:

  private def accepted(s: String): Unit =
    ContributorId.parse(s) match
      case Right(id)    => assertEquals(id.value, s, "spelling must be preserved exactly (R29)")
      case Left(reason) => fail(s"expected '$s' to be accepted, got: $reason")

  private def rejected(s: String): Unit =
    assert(ContributorId.parse(s).isLeft, s"expected '$s' to be rejected")

  test("accepts email-shaped ASCII ids and preserves spelling") {
    accepted("a@x")
    accepted("alice@example.com")
    accepted("Alice@Example.COM") // no case normalization
    accepted("user+tag@sub.domain")
    accepted("!#$%&'*a@x") // unusual printable ASCII is allowed
    accepted("a-b@x") // '-' alone is fine
    accepted("a>b@x") // '>' alone is fine
    accepted("a@x-") // trailing '-' (still no "->" substring)
    accepted("0@0")
  }

  test("byte-length boundary: 254 accepted, 255 rejected") {
    accepted("a" * 252 + "@x") // exactly 254 bytes
    rejected("a" * 253 + "@x") // 255 bytes
  }

  test("rejects wrong @ shapes") {
    rejected("") // empty
    rejected("ax") // no @
    rejected("two@@x") // two @
    rejected("a@b@c") // two @
    rejected("@x") // empty left side
    rejected("a@") // empty right side
    rejected("@") // both sides empty
  }

  test("rejects whitespace") {
    rejected("space @x")
    rejected("a@x ")
    rejected(" a@x")
    rejected("a\t@x")
    rejected("a\n@x")
    rejected("a\r@x")
  }

  test("rejects the explicitly forbidden characters and '->'") {
    rejected("a,b@x")
    rejected("a(b@x")
    rejected("a)b@x")
    rejected("a(b)@x")
    rejected("a->b@x")
    rejected("->a@x")
    rejected("a@x->")
  }

  test("rejects control characters (0x00-0x1F and 0x7F per D12) and non-ASCII") {
    rejected("a\u0000@x")
    rejected("a\u0001@x")
    rejected("a\u001f@x")
    rejected("a\u007f@x") // DEL is a control character (D12)
    rejected("\u00e9@x") // non-ASCII ('e' with acute)
    rejected("a@\u00e9")
    rejected("a\u00a0@x") // non-breaking space
    rejected("a\ud83d\ude00@x") // emoji U+1F600
  }

  test("ordering follows Utf8Order on the raw value") {
    val ids = Vector("b@x", "a@x", "B@x", "a@x!").map(s =>
      ContributorId.parse(s).fold(e => fail(s"bad id $s: $e"), identity)
    )
    assertEquals(
      ids.sorted(ContributorId.ordering).map(_.value),
      Vector("B@x", "a@x", "a@x!", "b@x")
    )
  }

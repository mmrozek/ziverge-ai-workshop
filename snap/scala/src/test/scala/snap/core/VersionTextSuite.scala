package snap.core

/** Canonical version text form (SPEC §3.2, R31): strict parse and exact-inverse print. One directed
  * test per rejection class, plus the exact rejection inputs the provided suite uses (tests 19 and
  * 25).
  */
class VersionTextSuite extends munit.FunSuite:

  private def ok(text: String): Version =
    Version.parse(text).fold(e => fail(s"expected '$text' to parse, got: $e"), identity)

  private def bad(text: String): Unit =
    assert(Version.parse(text).isLeft, s"expected '$text' to be rejected")

  test("parses the empty version and prints it back") {
    assertEquals(ok("()"), Version.empty)
    assertEquals(Version.empty.canonicalText, "()")
  }

  test("parses valid versions and print is the exact inverse") {
    for text <- Vector(
        "(a@x->1)",
        "(a@x->1,b@x->2)",
        "(a@x->9007199254740991)", // Revision.Max is accepted
        "(jdegoes@example.com->2323,vigoo@example.com->239)", // spec's own example
        "(A@x->1,a@x->1)", // uppercase sorts before lowercase in UTF-8 bytes
        "(a@x-->1)" // id "a@x-": first "->" is still an unambiguous separator
      )
    do assertEquals(ok(text).canonicalText, text)
  }

  test("counters read back with absent = 0") {
    val v = ok("(a@x->3,b@x->1)")
    val a = ContributorId.parse("a@x").toOption.get
    val b = ContributorId.parse("b@x").toOption.get
    val c = ContributorId.parse("c@x").toOption.get
    assertEquals(v.get(a), 3L)
    assertEquals(v.get(b), 1L)
    assertEquals(v.get(c), 0L)
  }

  test("rejects duplicate ids") {
    bad("(a@x->1,a@x->2)")
    bad("(a@x->1,a@x->1)")
  }

  test("rejects explicit zeroes") {
    bad("(a@x->0)")
    bad("(good@x->0)") // test 25's exact input
  }

  test("rejects leading zeroes") {
    bad("(a@x->01)")
    bad("(a@x->007)")
  }

  test("rejects overflow (> 9007199254740991)") {
    bad("(a@x->9007199254740992)") // Max + 1, test 25's exact input (modulo id)
    bad("(good@x->9007199254740992)")
    bad("(a@x->99999999999999999999)") // more digits than any Long
  }

  test("rejects invalid ids") {
    bad("(a->1)") // no @
    bad("(@x->1)")
    bad("(two@@x->1)")
    bad("(a,b@x->1)")
    bad("(é@x->1)")
  }

  test("rejects whitespace anywhere") {
    bad("(a@x->1, b@x->1)") // test 25's exact input
    bad("( a@x->1)")
    bad("(a@x ->1)")
    bad("(a@x-> 1)")
    bad("(a@x->1 )")
    bad(" ()")
    bad("() ")
    bad("(a@x->1,b@x->1 )")
  }

  test("rejects noncanonical ordering") {
    bad("(b@x->1,a@x->1)") // test 25's exact input
    bad("(a@x->1,A@x->1)") // 'A' < 'a' in UTF-8 bytes, so this order is wrong
  }

  test("rejects negative and non-numeric revisions") {
    bad("(a@x->-1)")
    bad("(good@x->-1)") // test 25's exact input
    bad("(a@x->+1)")
    bad("(a@x->1a)")
    bad("(a@x->)")
  }

  test("rejects malformed structure") {
    bad("")
    bad("(")
    bad(")")
    bad("a@x->1")
    bad("(a@x->1")
    bad("a@x->1)")
    bad("((a@x->1))")
    bad("(a@x->1)x")
    bad("(a@x)") // missing ->
    bad("(a@x->1,)") // trailing comma / empty entry
    bad("(,)")
    bad("(,a@x->1)")
  }

  test("pair-array seam round-trips in canonical order (R32)") {
    val v = ok("(jdegoes@example.com->2323,vigoo@example.com->239)")
    val pairs = Vector(("jdegoes@example.com", 2323L), ("vigoo@example.com", 239L))
    assertEquals(v.toPairs, pairs)
    assertEquals(Version.fromPairs(pairs), Right(v))
  }

  test("fromPairs rejects the same classes as parse") {
    assert(Version.fromPairs(Vector(("a@x", 0L))).isLeft) // zero
    assert(Version.fromPairs(Vector(("a@x", -1L))).isLeft)
    assert(Version.fromPairs(Vector(("a@x", Revision.Max + 1L))).isLeft) // overflow
    assert(Version.fromPairs(Vector(("bad id", 1L))).isLeft) // invalid id
    assert(Version.fromPairs(Vector(("b@x", 1L), ("a@x", 1L))).isLeft) // order
    assert(Version.fromPairs(Vector(("a@x", 1L), ("a@x", 2L))).isLeft) // duplicate
    // reason substrings pinned by the provided suite (test 23) for T06 reuse
    assertEquals(
      Version.fromPairs(Vector(("a@x", 0L))).left.map(_.contains("positive safe integer")),
      Left(true)
    )
    assertEquals(
      Version.fromPairs(Vector(("b@x", 1L), ("a@x", 1L))).left.map(_.contains("canonical")),
      Left(true)
    )
  }

  test("fromMap sorts deterministically and validates bounds") {
    val a = ContributorId.parse("a@x").toOption.get
    val b = ContributorId.parse("b@x").toOption.get
    assertEquals(
      Version.fromMap(Map(b -> 2L, a -> 1L)).map(_.canonicalText),
      Right("(a@x->1,b@x->2)")
    )
    assert(Version.fromMap(Map(a -> 0L)).isLeft)
    assert(Version.fromMap(Map(a -> (Revision.Max + 1L))).isLeft)
  }

  test("updated inserts, replaces, and validates bounds") {
    val a = ContributorId.parse("a@x").toOption.get
    val b = ContributorId.parse("b@x").toOption.get
    val v1 = Version.empty.updated(b, 1L).toOption.get
    val v2 = v1.updated(a, 2L).toOption.get
    assertEquals(v2.canonicalText, "(a@x->2,b@x->1)")
    assertEquals(v2.updated(a, 3L).map(_.canonicalText), Right("(a@x->3,b@x->1)"))
    assert(v2.updated(a, 0L).isLeft)
    assert(v2.updated(a, Revision.Max + 1L).isLeft)
  }

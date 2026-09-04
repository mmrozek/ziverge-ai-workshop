package snap.core

import munit.FunSuite

/** Patch/Change/Dot model: R46 result computation, R47 structural equality, R48 message rules (with
  * D16), R49 changes rules.
  */
class PatchSuite extends FunSuite:

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw': $e"), identity)

  private def v(pairs: (String, Long)*): Version =
    Version
      .fromPairs(pairs.toVector)
      .fold(e => fail(s"expected valid version: ${e.message}"), identity)

  private def bytes(s: String): IArray[Byte] =
    IArray.unsafeFromArray(s.getBytes("UTF-8")) // fresh array, never aliased

  private val textF = Change.Text(p("f"), EditScript.empty)

  private def mk(
      author: String = "a@x",
      revision: Long = 1L,
      base: Version = Version.empty,
      message: String = "m",
      changes: Vector[Change] = Vector(textF)
  ): Either[SnapError, Patch] =
    Patch.make(id(author), revision, base, message, changes)

  private def mkOrFail(
      author: String = "a@x",
      revision: Long = 1L,
      base: Version = Version.empty,
      message: String = "m",
      changes: Vector[Change] = Vector(textF)
  ): Patch =
    mk(author, revision, base, message, changes)
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)

  test("dot identity and diagnostic rendering") {
    val patch = mkOrFail(author = "a@x", revision = 1L)
    assertEquals(patch.dot, Dot(id("a@x"), 1L))
    assertEquals(patch.dot.text, "a@x revision 1") // shape pinned via tests 16/23 messages
  }

  test("result = base with author bumped, all other components preserved (R46)") {
    val base = v("a@x" -> 1L, "b@x" -> 3L)
    val patch = mkOrFail(author = "a@x", revision = 2L, base = base)
    assertEquals(patch.result, Right(v("a@x" -> 2L, "b@x" -> 3L)))
    // first revision of a new contributor over an empty base
    assertEquals(mkOrFail(author = "a@x", revision = 1L).result, Right(v("a@x" -> 1L)))
  }

  test("revision bounds are enforced at construction (R30)") {
    assertEquals(mk(revision = 0L), Left(SnapError.RevisionNotSafeInteger))
    assertEquals(mk(revision = -1L), Left(SnapError.RevisionNotSafeInteger))
    assertEquals(mk(revision = Revision.Max + 1L), Left(SnapError.RevisionNotSafeInteger))
    assert(mk(revision = Revision.Max).isRight)
  }

  test("message rules (R48): empty rejected with the pinned fragment at the line end") {
    val err = mk(message = "").left.map(_.message)
    assertEquals(err, Left("patch message is empty"))
    // test 23's regex needs at least one character between "snap: " and the fragment
    assert(err.swap.exists(m => m.endsWith("message is empty") && m != "message is empty"))
  }

  test("message rules (R48): tab and LF allowed, no other control character incl. DEL (D12)") {
    assert(mk(message = "line one\nline\ttwo\n").isRight)
    assertEquals(mk(message = "a\rb"), Left(SnapError.PatchMessageForbiddenCharacter))
    assertEquals(mk(message = "a\u0000b"), Left(SnapError.PatchMessageForbiddenCharacter))
    assertEquals(mk(message = "a\u001bb"), Left(SnapError.PatchMessageForbiddenCharacter))
    assertEquals(mk(message = "a\u007fb"), Left(SnapError.PatchMessageForbiddenCharacter))
    assert(mk(message = "héllo 😀").isRight) // non-ASCII is fine — UTF-8 string
  }

  test("message rules (R48): unpaired surrogate has no UTF-8 encoding") {
    assertEquals(mk(message = "a\ud800b"), Left(SnapError.PatchMessageNotUtf8))
  }

  test("message rules (D16): the 4096-byte limit is snap commit's alone — not enforced here") {
    assert(mk(message = "x" * 5000).isRight)
  }

  test("changes rules (R49): nonempty, with the pinned fragment at the line end") {
    val err = mk(changes = Vector.empty).left.map(_.message)
    assertEquals(err, Left("patch changes is empty"))
    assert(err.swap.exists(m => m.endsWith("changes is empty") && m != "changes is empty"))
  }

  test("changes rules (R49): sorted by path in Utf8Order, at most one change per path") {
    val sorted = Vector(
      Change.Text(p("nested/file"), EditScript.empty),
      Change.Put(p("z"), bytes("z")),
      Change.Delete(p("é")),
      Change.Put(p("😀"), bytes("!"))
    )
    assert(mk(changes = sorted).isRight) // test 25's pinned byte order
    assertEquals(mk(changes = sorted.reverse), Left(SnapError.ChangesNotSorted))
    assertEquals(
      mk(changes = Vector(Change.Text(p("z"), EditScript.empty), Change.Delete(p("a")))),
      Left(SnapError.ChangesNotSorted) // test 27's z-before-a fixture
    )
    assertEquals(
      mk(changes = Vector(textF, Change.Delete(p("f")))),
      Left(SnapError.ChangesDuplicatePath)
    )
  }

  test("Put equality is over content bytes, not array identity (R47)") {
    val one = Change.Put(p("f"), bytes("same"))
    val two = Change.Put(p("f"), bytes("same"))
    assertEquals(one, two)
    assertEquals(one.hashCode, two.hashCode)
    assertNotEquals(one, Change.Put(p("f"), bytes("other")))
    assertNotEquals(one, Change.Put(p("g"), bytes("same")))
    assertNotEquals(one: Change, Change.Delete(p("f")): Change)
  }

  test("patch equality is structural over parsed typed values (R47)") {
    val changes = Vector(Change.Put(p("f"), bytes("data")))
    val one = mkOrFail(base = v("b@x" -> 2L), message = "same", changes = changes)
    val two = mkOrFail(
      base = v("b@x" -> 2L),
      message = "same",
      changes = Vector(Change.Put(p("f"), bytes("data")))
    )
    assertEquals(one, two)
    assertEquals(one.hashCode, two.hashCode)
    assertNotEquals(one, mkOrFail(base = v("b@x" -> 2L), message = "other", changes = changes))
  }

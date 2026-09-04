package snap.core

/** Directed tests for causal compare (SPEC §3.3, R33/R35), join (R34), and the Snap total order
  * (SPEC §3.4, R36 — DESIGN §10 gotcha 3).
  */
class VersionAlgebraSuite extends munit.FunSuite:

  private def v(text: String): Version =
    Version.parse(text).fold(e => fail(s"parse failed for $text: $e"), identity)

  private def snap(a: Version, b: Version): Int =
    Version.snapOrdering.compare(a, b)

  test("compare: equal") {
    assertEquals(v("()").compareCausal(v("()")), Ord.Equal)
    assertEquals(v("(a@x->1,b@x->2)").compareCausal(v("(a@x->1,b@x->2)")), Ord.Equal)
  }

  test("compare: before/after with absent components as zero") {
    assertEquals(v("()").compareCausal(v("(a@x->1)")), Ord.Before)
    assertEquals(v("(a@x->1)").compareCausal(v("()")), Ord.After)
    assertEquals(v("(a@x->1)").compareCausal(v("(a@x->2)")), Ord.Before)
    assertEquals(v("(a@x->1)").compareCausal(v("(a@x->1,b@x->1)")), Ord.Before)
    assertEquals(v("(a@x->1,b@x->1)").compareCausal(v("(a@x->1)")), Ord.After)
    assertEquals(v("(a@x->2,b@x->3)").compareCausal(v("(a@x->1,b@x->3)")), Ord.After)
  }

  test("compare: concurrent") {
    assertEquals(v("(a@x->1)").compareCausal(v("(b@x->1)")), Ord.Concurrent)
    assertEquals(v("(a@x->1,b@x->2)").compareCausal(v("(a@x->2,b@x->1)")), Ord.Concurrent)
    assertEquals(v("(a@x->1,c@x->1)").compareCausal(v("(b@x->1,c@x->1)")), Ord.Concurrent)
  }

  test("join: componentwise max over the union") {
    assertEquals(
      v("(a@x->2,b@x->1)").join(v("(a@x->1,c@x->3)")).canonicalText,
      "(a@x->2,b@x->1,c@x->3)"
    )
    assertEquals(v("()").join(v("(a@x->1)")).canonicalText, "(a@x->1)")
    assertEquals(v("(a@x->1)").join(v("()")).canonicalText, "(a@x->1)")
    // test 21's pinned join: (a@x->2,b@x->1) ⊔ (a@x->1,b@x->2) = (a@x->2,b@x->2)
    assertEquals(
      v("(a@x->2,b@x->1)").join(v("(a@x->1,b@x->2)")).canonicalText,
      "(a@x->2,b@x->2)"
    )
  }

  test("snap order: (bob@x->1) PRECEDES (alice@x->1) — gotcha 3, lower counter first") {
    val bob = v("(bob@x->1)")
    val alice = v("(alice@x->1)")
    // Sorted union of ids is (alice@x, bob@x); at alice@x the counters are
    // 0 vs 1 — the first unequal counter decides and LOWER means EARLIER.
    assert(snap(bob, alice) < 0, "(bob@x->1) must precede (alice@x->1)")
    assert(snap(alice, bob) > 0)
    assertEquals(bob.compareCausal(alice), Ord.Concurrent)
  }

  test("snap order: directed spot checks") {
    assertEquals(snap(v("()"), v("()")), 0)
    assert(snap(v("()"), v("(a@x->1)")) < 0) // empty precedes everything nonempty
    assert(snap(v("(a@x->1)"), v("(a@x->2)")) < 0) // extends causal order
    assert(snap(v("(b@x->1)"), v("(a@x->1,b@x->1)")) < 0) // 0 < 1 at a@x
    assert(snap(v("(a@x->1)"), v("(b@x->1)")) > 0) // 1 > 0 at a@x
    assert(snap(v("(a@x->1,b@x->9)"), v("(a@x->2)")) < 0) // first unequal id decides
  }

  test("snap order: equal only for equal versions") {
    assertEquals(snap(v("(a@x->1,b@x->2)"), v("(a@x->1,b@x->2)")), 0)
    assert(snap(v("(a@x->1)"), v("(a@x->1,b@x->1)")) != 0)
  }

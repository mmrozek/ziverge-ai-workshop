package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import snap.core.EditOp.Delete
import snap.core.EditOp.Insert
import snap.core.EditOp.Retain

import scala.annotation.tailrec

/** T15 — [[Ot.transform]] against SPEC §6.3 (R71): directed tests for every table row, count
  * splitting, trailing insertions, the Q-insert priority rule, the consumption invariant; unit
  * fixtures lifted from the provided YAML suites 09/18/22; and property tests over generated diff
  * pairs. OT has no permutation dimension (its inputs are two ordered scripts), so the determinism
  * obligations here are repeated-run identity, the identity-edit no-ops, and the
  * canonical-integration-order pinning that makes concurrent updates symmetric at replay level.
  */
class OtSuite extends munit.ScalaCheckSuite:

  // Core-risk module: buy extra property coverage (default is 100 samples).
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  private def script(ops: EditOp*): EditScript = EditScript(ops.toVector)

  private def rightOrFail[E, A](e: Either[E, A])(using munit.Location): A =
    e.fold(err => fail(s"unexpected Left: $err"), identity)

  // --- the six table rows, directed ---

  test("row 1: Q insert emits retain(token count of the Q insert), consuming Q only") {
    assertEquals(
      Ot.transform(EditScript.empty, script(Insert(Vector("x\n", "y\n")))),
      Right(script(Retain(2L)))
    )
  }

  test("row 2: P insert passes through unchanged, consuming P only") {
    assertEquals(
      Ot.transform(script(Insert(Vector("x\n"))), EditScript.empty),
      Right(script(Insert(Vector("x\n"))))
    )
  }

  test("row 3: P retain / Q retain emits retain(min), consuming both") {
    assertEquals(Ot.transform(script(Retain(3L)), script(Retain(3L))), Right(script(Retain(3L))))
  }

  test("row 4: P delete / Q retain emits delete(min), consuming both") {
    assertEquals(Ot.transform(script(Delete(2L)), script(Retain(2L))), Right(script(Delete(2L))))
  }

  test("row 5: P retain / Q delete emits nothing, consuming both") {
    assertEquals(Ot.transform(script(Retain(2L)), script(Delete(2L))), Right(EditScript.empty))
  }

  test("row 6: P delete / Q delete emits nothing, consuming both — each base token dies once") {
    assertEquals(Ot.transform(script(Delete(2L)), script(Delete(2L))), Right(EditScript.empty))
  }

  // --- count splitting, coalescing, trailing insertions ---

  test("count splitting across operation boundaries (rows 3, 5, 6 in one pass)") {
    // P: retain 2, delete 1 · Q: retain 1, delete 2 — every operation splits: retain(min 1),
    // then P's leftover retain dies against Q's delete, then the deletes overlap silently.
    assertEquals(
      Ot.transform(script(Retain(2L), Delete(1L)), script(Retain(1L), Delete(2L))),
      Right(script(Retain(1L)))
    )
  }

  test("splitting + coalescing: deletes split by a Q delete re-coalesce into one operation") {
    // delete(min 1) · overlap · delete(min 1) — adjacent same-kind output merges (R55).
    assertEquals(
      Ot.transform(script(Delete(3L)), script(Retain(1L), Delete(1L), Retain(1L))),
      Right(script(Delete(2L)))
    )
  }

  test("trailing P insert after Q is exhausted") {
    assertEquals(
      Ot.transform(script(Retain(1L), Insert(Vector("z\n"))), script(Retain(1L))),
      Right(script(Retain(1L), Insert(Vector("z\n"))))
    )
  }

  test("trailing Q insert after P is exhausted becomes a coalesced retain") {
    assertEquals(
      Ot.transform(script(Retain(1L)), script(Retain(1L), Insert(Vector("z\n")))),
      Right(script(Retain(2L)))
    )
  }

  // --- the Q-insert priority rule ---

  test("concurrent inserts at one cursor: Q insert has priority, Q's text lands first") {
    val base = Vector("a\n", "b\n")
    val p = script(Retain(1L), Insert(Vector("P\n")), Retain(1L))
    val q = script(Retain(1L), Insert(Vector("Q\n")), Retain(1L))
    val transformed = rightOrFail(Ot.transform(p, q))
    // Transformed P retains over Q's insertion before emitting its own insert…
    assertEquals(transformed, script(Retain(2L), Insert(Vector("P\n")), Retain(1L)))
    // …so the earlier-integrated Q text precedes P's at the shared cursor.
    val c = rightOrFail(q.applyTo(base))
    assertEquals(rightOrFail(transformed.applyTo(c)), Vector("a\n", "Q\n", "P\n", "b\n"))
  }

  test("Q insert row applies regardless of P's next operation, including an exhausted P") {
    // P's next operation is a delete: Q's inserted token is retained first, then the base dies.
    assertEquals(
      Ot.transform(script(Delete(1L)), script(Insert(Vector("z\n")), Delete(1L))),
      Right(script(Retain(1L)))
    )
    // P is exhausted (empty edit of the empty file): Q's trailing insert is still processed.
    assertEquals(
      Ot.transform(EditScript.empty, script(Insert(Vector("z\n")))),
      Right(script(Retain(1L)))
    )
  }

  test(
    "reviews/T15-review.md finding 2: P insert row fires against a pending Q delete head"
  ) {
    // p = insert[x], retain 1 · q = delete 1. Row 2 (P insert) matches first (q's head is not an
    // insert), consuming P only and emitting the insert unchanged; the residual retain(1)/delete(1)
    // pair then cancels under row 5. Previously only reachable probabilistically through the
    // generated properties (e.g. p = diff(["a\n"], ["b\n","a\n"]), q = diff(["a\n"], [])).
    assertEquals(
      Ot.transform(script(Insert(Vector("x\n")), Retain(1L)), script(Delete(1L))),
      Right(script(Insert(Vector("x\n"))))
    )
  }

  test("survival: Q insert before a P delete — inserted text survives, base token dies") {
    // Script form of 22-ot-matrix.yaml "survive": base 0..4; P deletes "1\n"; Q inserts "B\n"
    // right before it. Deletion consumes only base tokens (R71).
    val p = script(Retain(1L), Delete(1L), Retain(3L))
    val q = script(Retain(1L), Insert(Vector("B\n")), Retain(4L))
    assertEquals(Ot.transform(p, q), Right(script(Retain(2L), Delete(1L), Retain(3L))))
  }

  // --- consumption invariant and degenerate shapes ---

  test("consumption mismatch is the typed error, whichever stream ends early") {
    val mismatch = Left(SnapError.OtBaseMismatch)
    assertEquals(Ot.transform(script(Retain(2L)), script(Retain(1L))), mismatch)
    assertEquals(Ot.transform(script(Retain(1L)), script(Retain(1L), Delete(1L))), mismatch)
    assertEquals(Ot.transform(script(Delete(1L)), EditScript.empty), mismatch)
    assertEquals(Ot.transform(EditScript.empty, script(Retain(1L))), mismatch)
  }

  test("two empty scripts transform to the empty script") {
    assertEquals(Ot.transform(EditScript.empty, EditScript.empty), Right(EditScript.empty))
  }

  test(
    "concurrent LF-less appends: the table's output is exact; merged tokens may be non-canonical"
  ) {
    // Base: empty file. P inserts "x" (no LF), Q inserts "y" (no LF) — each authored result is
    // canonical on its own. The table gives retain(1), insert["x"]; applied to C = ["y"] the
    // merged sequence ["y", "x"] carries a LF-less token mid-sequence, so the merged *bytes* are
    // the concatenation "yx" (§6.5 disclaims desirable merged text; R57's canonical-result rule
    // binds patch scripts, not the application of a transformed script — see the task notes;
    // T16's replay must apply transformed scripts without the canonical-result check).
    val transformed =
      rightOrFail(Ot.transform(script(Insert(Vector("x"))), script(Insert(Vector("y")))))
    assertEquals(transformed, script(Retain(1L), Insert(Vector("x"))))
    assertEquals(applyStructural(transformed, Vector("y")), Some(Vector("y", "x")))
  }

  // --- fixtures lifted from the provided YAML suites (pinned merged bytes) ---

  /** The §6.2-step-3 shape on raw file texts: `p = diff(A, incoming)`, aggregate
    * `q = diff(A, context)`, `C = apply(q, A)`, result `apply(transform(p, q), C)` rendered back to
    * text. `incoming` is the canonically later (last-integrated) side.
    */
  private def mergedText(baseText: String, incomingText: String, contextText: String)(using
      munit.Location
  ): String =
    val base = TextTokens.tokenize(baseText)
    val p = Diff.diff(base, TextTokens.tokenize(incomingText))
    val q = Diff.diff(base, TextTokens.tokenize(contextText))
    val c = rightOrFail(q.applyTo(base))
    TextTokens.render(rightOrFail(rightOrFail(Ot.transform(p, q)).applyTo(c)))

  test("fixture 09-merge-text: concurrent appends land in canonical integration order") {
    // 09-merge-text.yaml: seed "base\n"; bob@x → "base\nright\n" integrates first (snap order:
    // at alice@x its counter is 0 vs alice's 1), alice@x → "base\nleft\n" transforms through it.
    assertEquals(mergedText("base\n", "base\nleft\n", "base\nright\n"), "base\nright\nleft\n")
  }

  test("fixture 22-ot-matrix dd: overlapping deletes remove each base token only once") {
    // alice@x (incoming) deletes "1\n" and "2\n"; bob@x (context, integrates first) deletes "1\n".
    assertEquals(mergedText("0\n1\n2\n3\n4\n", "0\n3\n4\n", "0\n2\n3\n4\n"), "0\n3\n4\n")
  }

  test("fixture 22-ot-matrix split: P insert, Q-insert priority, split counts, trailing insert") {
    // alice@x (incoming) → "A\n0\n3\n4\nTAIL\n"; bob@x (context) → "0\n1\nB\n3\n4\n".
    assertEquals(
      mergedText("0\n1\n2\n3\n4\n", "A\n0\n3\n4\nTAIL\n", "0\n1\nB\n3\n4\n"),
      "A\n0\nB\n3\n4\nTAIL\n"
    )
  }

  test("fixture 22-ot-matrix rd: a token the incoming edit retains stays deleted by context") {
    // alice@x (incoming) appends "A\n"; bob@x (context) deletes "1\n".
    assertEquals(
      mergedText("0\n1\n2\n3\n4\n", "0\n1\n2\n3\n4\nA\n", "0\n2\n3\n4\n"),
      "0\n2\n3\n4\nA\n"
    )
  }

  test("fixture 22-ot-matrix survive: a Q insert before a P deletion survives") {
    // alice@x (incoming) deletes "1\n"; bob@x (context) inserted "B\n" just before it.
    assertEquals(
      mergedText("0\n1\n2\n3\n4\n", "0\n2\n3\n4\n", "0\nB\n1\n2\n3\n4\n"),
      "0\nB\n2\n3\n4\n"
    )
  }

  test("fixture 18-three-way-convergence: aggregate Q per patch reproduces the pinned bytes") {
    // 18-three-way-convergence.yaml: base "start\nend\n"; c@x deletes "start\n", b@x inserts
    // "B\n", a@x inserts "A\n". Snap order integrates c, then b, then a. Each incoming patch is
    // transformed once against the aggregate Q = diff(base, canonical-so-far) covering *all*
    // earlier concurrent effects (§6.3 last paragraph, R72) — never chained patch-by-patch.
    val baseText = "start\nend\n"
    // c integrates against the base itself (B == C, applied directly): canonical tree "end\n".
    val afterC = "end\n"
    val afterB = mergedText(baseText, "start\nB\nend\n", afterC)
    assertEquals(afterB, "B\nend\n")
    assertEquals(mergedText(baseText, "start\nA\nend\n", afterB), "B\nA\nend\n")
  }

  // --- properties over generated diff pairs ---

  // Tiny alphabet biased toward "a"/"b" so repeated-line collisions dominate (as in DiffSuite).
  private val lineGen: Gen[String] =
    Gen.frequency(
      4 -> Gen.const("a"),
      4 -> Gen.const("b"),
      1 -> Gen.const("c"),
      1 -> Gen.const("dd")
    )

  /** Canonical token sequences whose tokens all end in LF: every token of the merged sequence then
    * ends in LF too, so the strict [[EditScript.applyTo]] (canonical-result check included) must
    * succeed on the transformed script.
    */
  private val lfTokensGen: Gen[Vector[String]] =
    Gen.listOf(lineGen.map(_ + "\n")).map(_.toVector)

  /** Canonical token sequences with an optional LF-less final token — the general case. The merged
    * sequence may be non-canonical (see the LF-less appends test), so general properties apply the
    * transformed script with [[applyStructural]].
    */
  private val tokensGen: Gen[Vector[String]] =
    for
      lines <- Gen.listOf(lineGen.map(_ + "\n"))
      last <- Gen.option(lineGen)
    yield lines.toVector :++ last.toVector

  /** Test-only structural application: exact base consumption, no canonical-result check — mirrors
    * how replay applies a transformed script (R57 binds patch scripts; task notes).
    */
  private def applyStructural(s: EditScript, old: Vector[String]): Option[Vector[String]] =
    @tailrec
    def go(k: Int, pos: Int, acc: Vector[String]): Option[Vector[String]] =
      if k == s.ops.length then Option.when(pos == old.length)(acc)
      else
        s.ops(k) match
          case Retain(n) =>
            if n > (old.length - pos).toLong then None
            else go(k + 1, pos + n.toInt, acc :++ old.slice(pos, pos + n.toInt))
          case Delete(n) =>
            if n > (old.length - pos).toLong then None
            else go(k + 1, pos + n.toInt, acc)
          case Insert(ts) => go(k + 1, pos, acc :++ ts)
    go(0, 0, Vector.empty)

  property("transformed P applies onto apply(q, A), consuming exactly its token count") {
    forAll(tokensGen, tokensGen, tokensGen) { (a, bTarget, cTarget) =>
      val q = Diff.diff(a, cTarget)
      val c = rightOrFail(q.applyTo(a))
      val transformed = rightOrFail(Ot.transform(Diff.diff(a, bTarget), q))
      assert(applyStructural(transformed, c).isDefined, (transformed, c))
    }
  }

  property("on LF-terminated sequences the transformed P passes the strict canonical apply") {
    forAll(lfTokensGen, lfTokensGen, lfTokensGen) { (a, bTarget, cTarget) =>
      val q = Diff.diff(a, cTarget)
      val c = rightOrFail(q.applyTo(a))
      val transformed = rightOrFail(Ot.transform(Diff.diff(a, bTarget), q))
      assert(transformed.applyTo(c).isRight, (transformed, c))
    }
  }

  property("transform errors iff the scripts consume different base token counts") {
    // diff(A, B) always consumes exactly |A| base tokens, so generating the two scripts from
    // bases of independent lengths exercises both sides of the iff.
    forAll(tokensGen, tokensGen, tokensGen, tokensGen) { (a1, b1, a2, c2) =>
      val result = Ot.transform(Diff.diff(a1, b1), Diff.diff(a2, c2))
      if a1.length == a2.length then assert(result.isRight, result)
      else assertEquals(result, Left(SnapError.OtBaseMismatch))
    }
  }

  property("output is structurally valid — no adjacent same-kind operations (R55)") {
    forAll(tokensGen, tokensGen, tokensGen) { (a, bTarget, cTarget) =>
      val transformed = rightOrFail(Ot.transform(Diff.diff(a, bTarget), Diff.diff(a, cTarget)))
      assertEquals(transformed.validate, Right(()))
    }
  }

  property("identity context is a no-op: transform(p, diff(A, A)) == p") {
    forAll(tokensGen, tokensGen) { (a, bTarget) =>
      val p = Diff.diff(a, bTarget)
      assertEquals(Ot.transform(p, Diff.diff(a, a)), Right(p))
    }
  }

  property("identity incoming stays a no-op: transformed diff(A, A) applied to C yields C") {
    forAll(tokensGen, tokensGen) { (a, cTarget) =>
      val q = Diff.diff(a, cTarget)
      val c = rightOrFail(q.applyTo(a))
      val transformed = rightOrFail(Ot.transform(Diff.diff(a, a), q))
      assertEquals(applyStructural(transformed, c), Some(c))
    }
  }

  property("determinism: repeated runs produce the identical transformed script") {
    forAll(tokensGen, tokensGen, tokensGen) { (a, bTarget, cTarget) =>
      val runs = Vector.fill(3)(Ot.transform(Diff.diff(a, bTarget), Diff.diff(a, cTarget)))
      assert(runs.forall(_ == runs.head), runs)
    }
  }

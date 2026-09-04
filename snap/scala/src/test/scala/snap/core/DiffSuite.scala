package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import snap.core.EditOp.Delete
import snap.core.EditOp.Insert
import snap.core.EditOp.Retain

class DiffSuite extends munit.ScalaCheckSuite:

  // Core-risk module: buy extra property coverage (default is 100 samples).
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  // Tiny alphabet biased toward "a"/"b" so repeated-line collisions dominate —
  // the regime where a wrong tie-break diverges from the pinned scripts.
  private val lineGen: Gen[String] =
    Gen.frequency(
      4 -> Gen.const("a"),
      4 -> Gen.const("b"),
      1 -> Gen.const("c"),
      1 -> Gen.const("dd")
    )

  // Canonical token sequences: LF-terminated lines, optionally one final LF-less token.
  private val tokensGen: Gen[Vector[String]] =
    for
      lines <- Gen.listOf(lineGen.map(_ + "\n"))
      last <- Gen.option(lineGen)
    yield lines.toVector :++ last.toVector

  test("golden (test 05): a,b,a -> b,a,a(no final LF) is [delete 1, retain 2, insert [a]]") {
    assertEquals(
      Diff.diff(Vector("a\n", "b\n", "a\n"), Vector("b\n", "a\n", "a")),
      EditScript(Vector(Delete(1L), Retain(2L), Insert(Vector("a"))))
    )
  }

  test(
    "golden (reviews/T05-review.md finding 1): equality-before-tie on trailing repeated lines"
  ) {
    // R64 ("including for repeated equal lines"): rule 1 (equal tokens retain) is checked BEFORE
    // the exhausted-side/tie rules, even when the equal token is the LAST of the shorter side. A
    // future refactor toward a common-suffix-anchored or Myers-style walk could instead emit the
    // delete/insert first here — every other project test (apply-roundtrip, validity,
    // cost-minimality, determinism, even the test-05 golden) stays green under such a variant
    // (reviews/T05-review.md finding 1), so this golden is the only guard against that regression.
    assertEquals(
      Diff.diff(Vector("a\n", "a\n"), Vector("a\n")),
      EditScript(Vector(Retain(1L), Delete(1L)))
    )
    assertEquals(
      Diff.diff(Vector("a\n"), Vector("a\n", "a\n")),
      EditScript(Vector(Retain(1L), Insert(Vector("a\n"))))
    )
  }

  test("deletion-on-tie: at equal costs the walk deletes before inserting") {
    // D(1,0) == D(0,1) == 1 here; `<=` must pick delete, so delete precedes insert.
    assertEquals(
      Diff.diff(Vector("a\n"), Vector("b\n")),
      EditScript(Vector(Delete(1L), Insert(Vector("b\n"))))
    )
  }

  test("degenerate shapes") {
    assertEquals(Diff.diff(Vector.empty, Vector.empty), EditScript.empty)
    assertEquals(
      Diff.diff(Vector.empty, Vector("x\n", "y")),
      EditScript(Vector(Insert(Vector("x\n", "y"))))
    )
    assertEquals(Diff.diff(Vector("x\n", "y\n"), Vector.empty), EditScript(Vector(Delete(2L))))
    assertEquals(Diff.diff(Vector("x\n", "y"), Vector("x\n", "y")), EditScript(Vector(Retain(2L))))
  }

  test("trailing-newline change is a delete/insert of the final token") {
    // "a\n" and "a" are distinct tokens; equality is exact.
    assertEquals(
      Diff.diff(Vector("a\n"), Vector("a")),
      EditScript(Vector(Delete(1L), Insert(Vector("a"))))
    )
  }

  property("apply(diff(A, B), A) == B") {
    forAll(tokensGen, tokensGen) { (a, b) =>
      assertEquals(Diff.diff(a, b).applyTo(a), Right(b))
    }
  }

  property("diff output is structurally valid with no adjacent same-kind ops") {
    forAll(tokensGen, tokensGen) { (a, b) =>
      val s = Diff.diff(a, b)
      assertEquals(s.validate, Right(()))
      val kinds = s.ops.map {
        case Retain(_) => "retain"
        case Delete(_) => "delete"
        case Insert(_) => "insert"
      }
      assert(kinds.lazyZip(kinds.drop(1)).forall(_ != _), s.ops)
    }
  }

  property("diff(A, A) retains everything (idempotent identity)") {
    forAll(tokensGen) { a =>
      val expected =
        if a.isEmpty then EditScript.empty else EditScript(Vector(Retain(a.length.toLong)))
      assertEquals(Diff.diff(a, a), expected)
    }
  }

  property("determinism: repeated runs produce the identical script") {
    forAll(tokensGen, tokensGen) { (a, b) =>
      val runs = Vector.fill(3)(Diff.diff(a, b))
      assert(runs.forall(_ == runs.head), runs)
    }
  }

  property("script cost equals the spec's minimum insert/delete distance (small inputs)") {
    val smallGen =
      for
        n <- Gen.choose(0, 5)
        a <- Gen.listOfN(n, lineGen.map(_ + "\n"))
        m <- Gen.choose(0, 5)
        b <- Gen.listOfN(m, lineGen.map(_ + "\n"))
      yield (a.toVector, b.toVector)
    forAll(smallGen) { case (a, b) =>
      assertEquals(cost(Diff.diff(a, b)), refDistance(a, b).toLong)
    }
  }

  private def cost(s: EditScript): Long =
    s.ops.map {
      case Retain(_)  => 0L
      case Delete(n)  => n
      case Insert(ts) => ts.length.toLong
    }.sum

  /** Test-only oracle: the spec recurrence evaluated by naive recursion (tiny inputs). */
  private def refDistance(a: Vector[String], b: Vector[String]): Int =
    def go(i: Int, j: Int): Int =
      if i == a.length then b.length - j
      else if j == b.length then a.length - i
      else if a(i) == b(j) then go(i + 1, j + 1)
      else 1 + math.min(go(i + 1, j), go(i, j + 1))
    go(0, 0)

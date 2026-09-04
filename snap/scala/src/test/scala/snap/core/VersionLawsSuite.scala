package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property tests for the version algebra: join laws, compare laws, Snap-order totality and
  * extension of causal order, round-trips, and construction-order independence (determinism
  * obligations, CLAUDE.md / R109 spirit).
  */
class VersionLawsSuite extends munit.ScalaCheckSuite:

  private def id(s: String): ContributorId =
    ContributorId.parse(s).fold(e => fail(s"bad id $s: $e"), identity)

  // Small pool so generated versions collide often (Equal/Before/Concurrent all occur).
  private val idPool: Vector[ContributorId] =
    Vector("a@x", "b@x", "c@x", "aa@x", "alice@example.com", "bob@example.com").map(id)

  private val genRevision: Gen[Long] =
    Gen.frequency(8 -> Gen.chooseNum(1L, 4L), 1 -> Gen.const(Revision.Max))

  private val genVersion: Gen[Version] =
    for
      ids <- Gen.someOf(idPool)
      revs <- Gen.listOfN(ids.size, genRevision)
    yield Version
      .fromMap(ids.toVector.zip(revs).toMap)
      .fold(e => fail(s"generator produced invalid version: $e"), identity)

  private def snap(a: Version, b: Version): Int =
    Version.snapOrdering.compare(a, b)

  // --- join laws (R34) ---

  property("join is commutative") {
    forAll(genVersion, genVersion) { (v, w) =>
      assertEquals(v.join(w), w.join(v))
    }
  }

  property("join is associative") {
    forAll(genVersion, genVersion, genVersion) { (u, v, w) =>
      assertEquals(u.join(v).join(w), u.join(v.join(w)))
    }
  }

  property("join is idempotent and empty is its identity") {
    forAll(genVersion) { v =>
      assertEquals(v.join(v), v)
      assertEquals(v.join(Version.empty), v)
      assertEquals(Version.empty.join(v), v)
    }
  }

  property("join is a causal upper bound of both arguments") {
    forAll(genVersion, genVersion) { (v, w) =>
      val j = v.join(w)
      assert(Set[Ord](Ord.Equal, Ord.Before).contains(v.compareCausal(j)))
      assert(Set[Ord](Ord.Equal, Ord.Before).contains(w.compareCausal(j)))
    }
  }

  // --- compare laws (R33/R35) ---

  property("compare returns Equal iff the versions are equal") {
    forAll(genVersion, genVersion) { (v, w) =>
      assertEquals(v.compareCausal(w) == Ord.Equal, v == w)
    }
  }

  property("compare is antisymmetric; Concurrent and Equal are symmetric") {
    forAll(genVersion, genVersion) { (v, w) =>
      val expected = v.compareCausal(w) match
        case Ord.Equal      => Ord.Equal
        case Ord.Before     => Ord.After
        case Ord.After      => Ord.Before
        case Ord.Concurrent => Ord.Concurrent
      assertEquals(w.compareCausal(v), expected)
    }
  }

  property("compare agrees with componentwise counters over the id union") {
    forAll(genVersion, genVersion) { (v, w) =>
      val union = (v.entries.map(_._1) ++ w.entries.map(_._1)).distinct
      val allLe = union.forall(c => v.get(c) <= w.get(c))
      val allGe = union.forall(c => v.get(c) >= w.get(c))
      val expected =
        if allLe && allGe then Ord.Equal
        else if allLe then Ord.Before
        else if allGe then Ord.After
        else Ord.Concurrent
      assertEquals(v.compareCausal(w), expected)
    }
  }

  // --- Snap order (R36) ---

  property("snap order is antisymmetric and zero exactly on equality") {
    forAll(genVersion, genVersion) { (v, w) =>
      assertEquals(math.signum(snap(v, w)), -math.signum(snap(w, v)))
      assertEquals(snap(v, w) == 0, v == w)
    }
  }

  property("snap order is transitive (total order)") {
    forAll(genVersion, genVersion, genVersion) { (u, v, w) =>
      if snap(u, v) <= 0 && snap(v, w) <= 0 then assert(snap(u, w) <= 0)
    }
  }

  property("snap order extends causal order") {
    forAll(genVersion, genVersion) { (v, w) =>
      v.compareCausal(w) match
        case Ord.Equal      => assertEquals(snap(v, w), 0)
        case Ord.Before     => assert(snap(v, w) < 0)
        case Ord.After      => assert(snap(v, w) > 0)
        case Ord.Concurrent => assert(snap(v, w) != 0) // still totally ordered
    }
  }

  // --- round-trips (R31/R32) ---

  property("parse(canonicalText) is the identity") {
    forAll(genVersion) { v =>
      assertEquals(Version.parse(v.canonicalText), Right(v))
    }
  }

  property("fromPairs(toPairs) is the identity") {
    forAll(genVersion) { v =>
      assertEquals(Version.fromPairs(v.toPairs), Right(v))
    }
  }

  // --- determinism: construction is insertion-order independent ---

  property("fromMap and incremental updated builds agree for every insertion order") {
    forAll(genVersion, Gen.chooseNum(0, 16)) { (v, rot) =>
      val pairs = v.entries
      val k = if pairs.isEmpty then 0 else rot % pairs.length
      def build(ps: Vector[(ContributorId, Long)]): Version =
        ps.foldLeft(Version.empty) { (acc, p) =>
          acc.updated(p._1, p._2).fold(e => fail(s"updated failed: $e"), identity)
        }
      val orders = Vector(pairs, pairs.reverse, pairs.drop(k) ++ pairs.take(k))
      for order <- orders do
        assertEquals(build(order), v)
        assertEquals(build(order).canonicalText, v.canonicalText) // byte-identical print
        assertEquals(Version.fromMap(order.toMap), Right(v))
    }
  }

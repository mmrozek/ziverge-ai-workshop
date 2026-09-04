package snap.core

import munit.FunSuite

import java.nio.charset.StandardCharsets
import scala.collection.immutable.SortedSet

/** Deterministic replay, directed tests (SPEC §4.5 steps 5–6, §6.1–§6.2; R45, R51–R52, R60,
  * R65–R66). Fixtures lift the histories of provided tests 15/23/27; concurrent-integration
  * directed tests live in [[ConcurrentReplaySuite]], the YAML merge fixtures in
  * [[ConcurrentReplayFixturesSuite]], and the property tests in [[ReplayLawsSuite]] /
  * [[ConcurrentReplayLawsSuite]].
  */
class ReplaySuite extends FunSuite:

  private val noWarnings: SortedSet[Warning] = SortedSet.empty

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw': $e"), identity)

  private def v(pairs: (String, Long)*): Version =
    Version
      .fromPairs(pairs.toVector)
      .fold(e => fail(s"expected valid version: ${e.message}"), identity)

  private def utf8(text: String): IArray[Byte] =
    // Fresh array, never aliased afterwards.
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  private def text(path: String, ops: EditOp*): Change =
    Change.Text(p(path), EditScript(ops.toVector))
  private def put(path: String, content: String): Change = Change.Put(p(path), utf8(content))
  private def putBytes(path: String, bytes: Byte*): Change =
    Change.Put(p(path), IArray.unsafeFromArray(bytes.toArray))
  private def del(path: String): Change = Change.Delete(p(path))
  private def ins(tokens: String*): EditOp = EditOp.Insert(tokens.toVector)

  private def patch(author: String, revision: Long, base: Version, changes: Change*): Patch =
    Patch
      .make(id(author), revision, base, "m", changes.toVector)
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)

  /** Runs the real §4.5 steps 1–4 first — fixtures here are structurally valid. */
  private def validated(frontier: Version, patches: Patch*): Repo.StructurallyValid =
    Repo
      .validate(Repository(frontier, patches.toVector))
      .fold(e => fail(s"expected structurally valid history: ${e.message}"), identity)

  /** Bypasses steps 1–4 for deliberately defective or permuted histories (replay-side tests). */
  private def handBuilt(frontier: Version, patches: Vector[Patch]): Repo.StructurallyValid =
    Repo.StructurallyValid(
      Repository(frontier, patches),
      patches.map(_.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity))
    )

  private def tree(entries: (String, String)*): Tree =
    Tree.from(entries.map((path, content) => (p(path), utf8(content))))

  private def rejects(frontier: Version, patches: Patch*): SnapError =
    Repo.validateFully(Repository(frontier, patches.toVector)) match
      case Left(e)  => e
      case Right(_) => fail("expected validation to reject")

  // --- ready-loop ordering (R66, D14) ---

  test("key 1: concurrent patches integrate in snap order of result versions (gotcha 3)") {
    val a1 = patch("a@x", 1L, Version.empty, text("fa", ins("a\n")))
    val b1 = patch("b@x", 1L, Version.empty, text("fb", ins("b\n")))
    val valid = validated(v("a@x" -> 1L, "b@x" -> 1L), a1, b1)
    // result (b@x->1) precedes (a@x->1): at id a@x the counters are 0 vs 1.
    assertEquals(
      Replay.integrationOrder(valid, v("a@x" -> 1L, "b@x" -> 1L)),
      Right(Vector(Dot(id("b@x"), 1L), Dot(id("a@x"), 1L)))
    )
  }

  test("causal dependencies integrate before concurrent patches (R66)") {
    val a1 = patch("a@x", 1L, Version.empty, text("fa", ins("a\n")))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), text("fa2", ins("a2\n")))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), text("fb", ins("b\n")))
    val valid = validated(v("a@x" -> 2L, "b@x" -> 1L), a1, a2, b1)
    // a1 is the only initially ready patch; then (a->1,b->1) precedes (a->2) in snap order.
    assertEquals(
      Replay.integrationOrder(valid, v("a@x" -> 2L, "b@x" -> 1L)),
      Right(Vector(Dot(id("a@x"), 1L), Dot(id("b@x"), 1L), Dot(id("a@x"), 2L)))
    )
  }

  test("key 2: equal result versions fall back to Utf8Order of author (hand-built tie)") {
    // Two ready patches can never share a result version in a valid history — hand-built tie.
    val tie = v("a@x" -> 1L, "b@x" -> 1L)
    val byAuthor =
      Replay.readyOrdering.compare((tie, Dot(id("a@x"), 1L)), (tie, Dot(id("b@x"), 1L)))
    assert(byAuthor < 0)
    assert(Replay.readyOrdering.compare((tie, Dot(id("b@x"), 1L)), (tie, Dot(id("a@x"), 1L))) > 0)
  }

  test("key 3: equal result versions and authors fall back to numeric revision (hand-built tie)") {
    val tie = v("a@x" -> 1L, "b@x" -> 1L)
    assert(Replay.readyOrdering.compare((tie, Dot(id("a@x"), 1L)), (tie, Dot(id("a@x"), 2L))) < 0)
    assert(Replay.readyOrdering.compare((tie, Dot(id("a@x"), 2L)), (tie, Dot(id("a@x"), 1L))) > 0)
    assertEquals(
      Replay.readyOrdering.compare((tie, Dot(id("a@x"), 2L)), (tie, Dot(id("a@x"), 2L))),
      0
    )
  }

  test("key 1 dominates keys 2 and 3; key 2 dominates key 3") {
    // (b->1) precedes (a->1) in snap order even though its author sorts later.
    assert(
      Replay.readyOrdering
        .compare((v("b@x" -> 1L), Dot(id("z@x"), 9L)), (v("a@x" -> 1L), Dot(id("a@x"), 1L))) < 0
    )
    val tie = v("a@x" -> 1L, "b@x" -> 1L)
    assert(Replay.readyOrdering.compare((tie, Dot(id("a@x"), 9L)), (tie, Dot(id("b@x"), 1L))) < 0)
  }

  // --- linear materialization (R65, R69 rule 1) ---

  private val linearFixture: (Repo.StructurallyValid, Version) =
    val a1 = patch(
      "a@x",
      1L,
      Version.empty,
      putBytes("bin", 0, 1, 2),
      text("f", ins("one\n", "two\n"))
    )
    val a2 = patch(
      "a@x",
      2L,
      v("a@x" -> 1L),
      text("f", EditOp.Retain(1L), EditOp.Delete(1L), ins("three\n"))
    )
    val a3 = patch("a@x", 3L, v("a@x" -> 2L), del("bin"), put("g", "raw"))
    (validated(v("a@x" -> 3L), a1, a2, a3), v("a@x" -> 3L))

  test("a linear create/edit/delete history materializes to its final tree") {
    val (valid, frontier) = linearFixture
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("f" -> "one\nthree\n", "g" -> "raw"), noWarnings))
    )
  }

  test("intermediate known versions materialize to intermediate trees") {
    val (valid, _) = linearFixture
    val bin = Tree.from(Vector((p("bin"), IArray[Byte](0, 1, 2))))
    assertEquals(
      Replay.materialize(valid, v("a@x" -> 1L)),
      Right((bin.updated(p("f"), utf8("one\ntwo\n")), noWarnings))
    )
    assertEquals(
      Replay.materialize(valid, v("a@x" -> 2L)),
      Right((bin.updated(p("f"), utf8("one\nthree\n")), noWarnings))
    )
  }

  test("the empty version materializes to the empty tree") {
    val (valid, _) = linearFixture
    assertEquals(Replay.materialize(valid, Version.empty), Right((Tree.empty, noWarnings)))
  }

  test("an empty text edit creates an empty file (R52/R58)") {
    val a1 = patch("a@x", 1L, Version.empty, text("f"))
    val valid = validated(v("a@x" -> 1L), a1)
    assertEquals(
      Replay.materialize(valid, v("a@x" -> 1L)),
      Right((tree("f" -> ""), noWarnings))
    )
  }

  test("disjoint concurrent creates integrate through rule 1 (identical in B and C per path)") {
    val a1 = patch("a@x", 1L, Version.empty, text("x", ins("a\n")))
    val b1 = patch("b@x", 1L, Version.empty, text("y", ins("b\n")))
    val frontier = v("a@x" -> 1L, "b@x" -> 1L)
    val valid = validated(frontier, a1, b1)
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("x" -> "a\n", "y" -> "b\n"), noWarnings))
    )
  }

  test("validateFully composes steps 1–6 and returns the frontier tree and warnings") {
    val (valid, frontier) = linearFixture
    val result = Repo.validateFully(valid.repository)
    assertEquals(result.map(_.tree), Right(tree("f" -> "one\nthree\n", "g" -> "raw")))
    assertEquals(result.map(_.warnings), Right(noWarnings)) // linear history: nothing to resolve
    assertEquals(result.map(_.repository), Right(valid.repository))
    assertEquals(result.map(_.structure), Right(valid))
    assertEquals(result.map(_.results), Right(valid.results))
    // frontier stays the declared one
    assertEquals(result.map(_.repository.frontier), Right(frontier))
  }

  // --- validation step 5: change vs materialized exact base (R51–R52, R25) ---

  test("delete of an absent path is rejected with the pinned full line (test 23)") {
    val a1 = patch("a@x", 1L, Version.empty, put("f", "a"))
    val b1 = patch("b@x", 1L, Version.empty, del("f"))
    val error = rejects(v("a@x" -> 1L, "b@x" -> 1L), a1, b1)
    assertEquals(error, SnapError.DeleteOfAbsentPath(p("f")))
    assertEquals(error.message, "delete of absent path: f")
  }

  test("delete of an absent path in a linear history is rejected the same way") {
    val a1 = patch("a@x", 1L, Version.empty, del("f"))
    assertEquals(rejects(v("a@x" -> 1L), a1), SnapError.DeleteOfAbsentPath(p("f")))
  }

  test("a put that repeats the base bytes is a no-op change (test 15)") {
    val a1 = patch("a@x", 1L, Version.empty, put("f", "a"))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), put("f", "a"))
    val error = rejects(v("a@x" -> 2L), a1, a2)
    assertEquals(error, SnapError.NoOpChange(p("f")))
    assert(error.message.endsWith("no-op change"), error.message)
  }

  test("a text edit that reproduces the old tokens is a no-op change (R52)") {
    val a1 = patch("a@x", 1L, Version.empty, text("f", ins("x\n")))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), text("f", EditOp.Retain(1L)))
    assertEquals(rejects(v("a@x" -> 2L), a1, a2), SnapError.NoOpChange(p("f")))
  }

  test("an underconsuming edit is rejected with the pinned fragment (test 15)") {
    val a1 = patch("a@x", 1L, Version.empty, text("f", ins("one\n", "two\n")))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), text("f", EditOp.Retain(1L)))
    val error = rejects(v("a@x" -> 2L), a1, a2)
    assertEquals(error, SnapError.InvalidEdit(EditError.Underconsumption))
    assert(error.message.endsWith("does not consume old content"), error.message)
  }

  test("an overconsuming edit is rejected with the pinned fragment (test 23)") {
    val a1 = patch("a@x", 1L, Version.empty, text("f", ins("one\n")))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), text("f", EditOp.Delete(2L)))
    val error = rejects(v("a@x" -> 1L, "b@x" -> 1L), a1, b1)
    assertEquals(error, SnapError.InvalidEdit(EditError.Overconsumption))
    assert(error.message.endsWith("consumes beyond old content"), error.message)
  }

  test("a retain in a text creation overconsumes the empty old sequence") {
    val a1 = patch("a@x", 1L, Version.empty, text("f", EditOp.Retain(1L)))
    assertEquals(rejects(v("a@x" -> 1L), a1), SnapError.InvalidEdit(EditError.Overconsumption))
  }

  test("an empty text edit over a present path underconsumes (test 27's create-present)") {
    val a1 = patch("a@x", 1L, Version.empty, put("f", "a"))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), text("f"))
    assertEquals(rejects(v("a@x" -> 2L), a1, a2), SnapError.InvalidEdit(EditError.Underconsumption))
  }

  test("an edit producing non-canonical result tokens is rejected (test 27's bad token)") {
    // "a" carries no LF and is not the last token: the applied result is not canonical (R57).
    val a1 = patch("a@x", 1L, Version.empty, text("f", ins("a", "b")))
    assertEquals(
      rejects(v("a@x" -> 1L), a1),
      SnapError.InvalidEdit(EditError.NonCanonicalResult)
    )
  }

  test("a text edit over non-text base bytes is rejected (test 27's text-over-binary)") {
    val a1 = patch("a@x", 1L, Version.empty, putBytes("f", 0))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), text("f", EditOp.Delete(1L)))
    assertEquals(rejects(v("a@x" -> 2L), a1, a2), SnapError.TextEditOverNonText(p("f")))
  }

  test("a patch creating a path and its descendant conflicts (test 15)") {
    val a1 = patch("a@x", 1L, Version.empty, put("a", "a"), put("a/b", "b"))
    val error = rejects(v("a@x" -> 1L), a1)
    assertEquals(error, SnapError.TreePathsConflict(p("a/b")))
    assert(error.message.endsWith("tree paths conflict"), error.message)
  }

  test("a patch creating a descendant of an existing base file conflicts (R25, linear)") {
    val a1 = patch("a@x", 1L, Version.empty, put("a", "a"))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), put("a/b", "b"))
    assertEquals(rejects(v("a@x" -> 2L), a1, a2), SnapError.TreePathsConflict(p("a/b")))
  }

  // --- validation step 6: replay of the declared frontier (R60) ---

  test("an initially empty ready set fails with the pinned phrase (replay-side R60)") {
    // Steps 1–4 catch this cycle first (test 15); hand-built to prove the replay-side guarantee.
    val a1 = patch("a@x", 1L, v("b@x" -> 1L), text("a", ins("a\n")))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), text("b", ins("b\n")))
    val valid = handBuilt(v("a@x" -> 1L, "b@x" -> 1L), Vector(a1, b1))
    val result = Replay.materialize(valid, v("a@x" -> 1L, "b@x" -> 1L))
    assertEquals(result, Left(SnapError.CyclicHistory))
    assertEquals(result.left.map(_.message), Left("cyclic or incomplete patch history"))
  }

  test("a ready set emptying mid-replay fails with the pinned phrase (R60)") {
    val a1 = patch("a@x", 1L, Version.empty, text("a", ins("a\n")))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L, "c@x" -> 1L), text("b", ins("b\n")))
    val c1 = patch("c@x", 1L, v("a@x" -> 1L, "b@x" -> 1L), text("c", ins("c\n")))
    val frontier = v("a@x" -> 1L, "b@x" -> 1L, "c@x" -> 1L)
    val valid = handBuilt(frontier, Vector(a1, b1, c1))
    assertEquals(
      Replay.materialize(valid, frontier),
      Left(SnapError.CyclicHistory)
    )
  }

  // --- known-version predicate (R45) ---

  private val knownFixture: Repo.StructurallyValid =
    val a1 = patch("a@x", 1L, Version.empty, text("fa", ins("a\n")))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), text("fa2", ins("a2\n")))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), text("fb", ins("b\n")))
    validated(v("a@x" -> 2L, "b@x" -> 1L), a1, a2, b1)

  test("the empty version is always known (R45)") {
    assertEquals(Replay.checkKnown(knownFixture, Version.empty), Right(()))
    val empty = validated(Version.empty)
    assertEquals(Replay.checkKnown(empty, Version.empty), Right(()))
  }

  test("every per-contributor prefix closure is known (R45)") {
    for known <- Vector(
        v("a@x" -> 1L),
        v("a@x" -> 2L),
        v("a@x" -> 1L, "b@x" -> 1L),
        v("a@x" -> 2L, "b@x" -> 1L)
      )
    do assertEquals(Replay.checkKnown(knownFixture, known), Right(()))
  }

  test("a vector selecting a patch without its base is unknown (R45)") {
    // b@x->1 alone selects b1 but not its base a@x->1.
    val result = Replay.checkKnown(knownFixture, v("b@x" -> 1L))
    assertEquals(result, Left(SnapError.UnknownVersion(v("b@x" -> 1L))))
    assertEquals(result.left.map(_.message), Left("unknown version: (b@x->1)"))
  }

  test("a vector selecting an absent patch is unknown (R45)") {
    assertEquals(
      Replay.checkKnown(knownFixture, v("a@x" -> 3L)),
      Left(SnapError.UnknownVersion(v("a@x" -> 3L)))
    )
    assertEquals(
      Replay.checkKnown(knownFixture, v("c@x" -> 1L)),
      Left(SnapError.UnknownVersion(v("c@x" -> 1L)))
    )
  }

  test("materialize rejects unknown versions before replaying") {
    assertEquals(
      Replay.materialize(knownFixture, v("b@x" -> 1L)),
      Left(SnapError.UnknownVersion(v("b@x" -> 1L)))
    )
  }

  // --- the former T16 seam: genuinely concurrent cases now resolve through the full engine
  //     (the LinearOnly staging errors these asserted no longer exist; the exhaustive per-rule
  //     coverage lives in ConcurrentReplaySuite) ---

  test("concurrent creates of one path resolve to the canonically later create (R73 rule 4)") {
    val a1 = patch("a@x", 1L, Version.empty, text("f", ins("one\n")))
    val b1 = patch("b@x", 1L, Version.empty, text("f", ins("two\n")))
    val frontier = v("a@x" -> 1L, "b@x" -> 1L)
    val valid = validated(frontier, a1, b1)
    // b1 integrates first (snap order); a1's later create wins over b1's.
    assertEquals(
      Replay.materialize(valid, frontier),
      Right(
        (tree("f" -> "one\n"), SortedSet(Warning(p("f"), WarningReason.LaterCreateWins)))
      )
    )
  }

  test("concurrent OT-shaped edits of one file merge through the aggregate context edit (R71)") {
    val a1 = patch("a@x", 1L, Version.empty, text("f", ins("x\n")))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), text("f", EditOp.Retain(1L), ins("a\n")))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), text("f", ins("b\n"), EditOp.Retain(1L)))
    val frontier = v("a@x" -> 2L, "b@x" -> 1L)
    val valid = validated(frontier, a1, a2, b1)
    // Order a1, b1, a2 (snap order): a2 transforms through Q = diff("x\n", "b\nx\n") and lands
    // its insertion after the retained base token; OT emits no warning (R74).
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("f" -> "b\nx\na\n"), noWarnings))
    )
  }

  test("a concurrent namespace conflict resolves to the later create, never a prefix clash (R68)") {
    val a1 = patch("a@x", 1L, Version.empty, put("a/b", "b"))
    val b1 = patch("b@x", 1L, Version.empty, put("a", "a"))
    val frontier = v("a@x" -> 1L, "b@x" -> 1L)
    val valid = validated(frontier, a1, b1)
    // b1 first installs file `a`; a1's create of `a/b` wins the namespace, removing `a` — the
    // warning names the REMOVED path (test 11's pin).
    val result = Replay.materialize(valid, frontier)
    assertEquals(
      result,
      Right((tree("a/b" -> "b"), SortedSet(Warning(p("a"), WarningReason.NamespaceWins))))
    )
    assert(result.exists(_._1.isPrefixFree))
  }

  // --- determinism (repeated runs) ---

  test("materialization is repeatable: the same inputs produce equal trees and orders") {
    val (valid, frontier) = linearFixture
    assertEquals(
      Replay.materialize(valid, frontier),
      Replay.materialize(valid, frontier)
    )
    assertEquals(
      Replay.integrationOrder(valid, frontier),
      Replay.integrationOrder(valid, frontier)
    )
  }

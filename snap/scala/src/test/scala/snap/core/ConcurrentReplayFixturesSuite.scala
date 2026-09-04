package snap.core

import munit.FunSuite

import java.nio.charset.StandardCharsets
import scala.collection.immutable.SortedSet

/** The merge engine proven against the provided YAML fixtures, lifted patch-for-patch (the merge
  * COMMAND arrives in T17; these tests replay the exact joined histories the commands would build
  * and assert the pinned bytes and warning lines): tests 09 (concurrent text, integration order),
  * 10 (every whole-file rule, sorted warnings), 11 (namespace, both canonical orders), 17
  * (later-create-wins), 18 (three-way convergence, all association orders), 21 (replay order across
  * interleaved revisions), 22 (the OT matrix). Edits are authored through [[Diff.diff]] — exactly
  * the scripts `snap commit` would produce.
  */
class ConcurrentReplayFixturesSuite extends FunSuite:

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

  private def textEdit(path: String, oldContent: String, newContent: String): Change =
    Change.Text(
      p(path),
      Diff.diff(TextTokens.tokenize(oldContent), TextTokens.tokenize(newContent))
    )

  private def putBytes(path: String, bytes: Byte*): Change =
    Change.Put(p(path), IArray.unsafeFromArray(bytes.toArray))
  private def del(path: String): Change = Change.Delete(p(path))

  private def patch(author: String, revision: Long, base: Version, changes: Change*): Patch =
    Patch
      .make(id(author), revision, base, "m", changes.toVector)
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)

  private def validated(frontier: Version, patches: Patch*): Repo.StructurallyValid =
    Repo
      .validate(Repository(frontier, patches.toVector))
      .fold(e => fail(s"expected structurally valid history: ${e.message}"), identity)

  /** Bypasses steps 1–4 for permuted patch arrays (replay must not read input order). */
  private def handBuilt(frontier: Version, patches: Vector[Patch]): Repo.StructurallyValid =
    Repo.StructurallyValid(
      Repository(frontier, patches),
      patches.map(_.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity))
    )

  private def tree(entries: (String, String)*): Tree =
    Tree.from(entries.map((path, content) => (p(path), utf8(content))))

  private def warn(path: String, reason: WarningReason): Warning = Warning(p(path), reason)

  // --- test 09: concurrent text edits converge; integration order pins Q-insert priority ---

  test("test 09: concurrent appends merge to base/right/left with no warnings") {
    val seed = patch("seed@x", 1L, Version.empty, textEdit("notes.txt", "", "base\n"))
    val alice =
      patch("alice@x", 1L, v("seed@x" -> 1L), textEdit("notes.txt", "base\n", "base\nleft\n"))
    val bob =
      patch("bob@x", 1L, v("seed@x" -> 1L), textEdit("notes.txt", "base\n", "base\nright\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L)
    val valid = validated(frontier, alice, bob, seed)
    assertEquals(
      Replay.integrationOrder(valid, frontier),
      Right(Vector(Dot(id("seed@x"), 1L), Dot(id("bob@x"), 1L), Dot(id("alice@x"), 1L)))
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("notes.txt" -> "base\nright\nleft\n"), noWarnings))
    )
    // Re-merge is a no-op (test 09's third merge): the same joined input replays identically.
    assertEquals(Replay.materialize(valid, frontier), Replay.materialize(valid, frontier))
  }

  // --- test 10: every whole-file rule in one merge; warning set and order pinned ---

  test("test 10: delete/put/text conflicts resolve with the pinned winners and sorted warnings") {
    val base = "base\n"
    val seed = patch(
      "seed@x",
      1L,
      Version.empty,
      textEdit("delete.txt", "", base),
      textEdit("identical.txt", "", base),
      textEdit("incompatible.txt", "", base),
      textEdit("later-put.txt", "", base)
    )
    val alice = patch(
      "alice@x",
      1L,
      v("seed@x" -> 1L),
      textEdit("delete.txt", base, "left\n"),
      textEdit("identical.txt", base, "same\n"),
      textEdit("incompatible.txt", base, "left text\n"),
      putBytes("later-put.txt", 0, 1) // AAE=
    )
    val bob = patch(
      "bob@x",
      1L,
      v("seed@x" -> 1L),
      del("delete.txt"),
      textEdit("identical.txt", base, "same\n"),
      putBytes("incompatible.txt", 0, -1), // AP8=
      textEdit("later-put.txt", base, "right text\n")
    )
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L)
    val valid = validated(frontier, alice, bob, seed)

    val expectedTree = Tree.from(
      Vector(
        (p("identical.txt"), utf8("same\n")),
        (p("incompatible.txt"), IArray[Byte](0, -1)),
        (p("later-put.txt"), IArray[Byte](0, 1))
      )
    )
    val result = Replay.materialize(valid, frontier)
    assertEquals(result.map(_._1), Right(expectedTree))
    // The exact warning pairs, in R74's path-then-reason order — the order test 10 pins on stderr.
    assertEquals(
      result.map(_._2.toVector),
      Right(
        Vector(
          warn("delete.txt", WarningReason.DeleteWins),
          warn("incompatible.txt", WarningReason.PutWins),
          warn("later-put.txt", WarningReason.LaterPutWins)
        )
      )
    )
    // The pre-merge local replay (alice's own frontier) is warning-free, so merge's R75
    // subtraction (T17) prints exactly the joined set once and nothing on re-merge.
    assertEquals(
      Replay.materialize(valid, v("alice@x" -> 1L, "seed@x" -> 1L)).map(_._2),
      Right(noWarnings)
    )
  }

  // --- test 11: namespace winners in both canonical orders; warning names the removed path ---

  test("test 11: later create of file `a` removes concurrent `a/b` (warning: a/b)") {
    val alice = patch("alice@x", 1L, Version.empty, textEdit("a", "", "ancestor\n"))
    val bob = patch("bob@x", 1L, Version.empty, textEdit("a/b", "", "descendant\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val valid = validated(frontier, alice, bob)
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("a" -> "ancestor\n"), SortedSet(warn("a/b", WarningReason.NamespaceWins))))
    )
  }

  test("test 11: later create of `x/y` removes concurrent file `x` (warning: x)") {
    val bob = patch("bob@x", 1L, Version.empty, textEdit("x", "", "ancestor\n"))
    val alice = patch("alice@x", 1L, Version.empty, textEdit("x/y", "", "descendant\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val valid = validated(frontier, alice, bob)
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("x/y" -> "descendant\n"), SortedSet(warn("x", WarningReason.NamespaceWins))))
    )
  }

  // --- test 17: later-create-wins, independent of merge direction ---

  test("test 17: concurrent creates converge on alice's content in either merge direction") {
    val alice = patch("alice@x", 1L, Version.empty, textEdit("same.txt", "", "alice\n"))
    val bob = patch("bob@x", 1L, Version.empty, textEdit("same.txt", "", "bob\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val expected =
      Right(
        (tree("same.txt" -> "alice\n"), SortedSet(warn("same.txt", WarningReason.LaterCreateWins)))
      )
    // Both merge directions build the same joined repository; the file-order and the reversed
    // patch array replay identically (input order never read).
    assertEquals(Replay.materialize(validated(frontier, alice, bob), frontier), expected)
    assertEquals(
      Replay.materialize(handBuilt(frontier, Vector(bob, alice)), frontier),
      expected
    )
  }

  // --- test 18: three concurrent text patches, every association order converges ---

  private val test18: (Vector[Patch], Version) =
    val base = "start\nend\n"
    val seed = patch("seed@x", 1L, Version.empty, textEdit("story.txt", "", base))
    val a = patch("a@x", 1L, v("seed@x" -> 1L), textEdit("story.txt", base, "start\nA\nend\n"))
    val b = patch("b@x", 1L, v("seed@x" -> 1L), textEdit("story.txt", base, "start\nB\nend\n"))
    val c = patch("c@x", 1L, v("seed@x" -> 1L), textEdit("story.txt", base, "end\n"))
    (Vector(a, b, c, seed), v("a@x" -> 1L, "b@x" -> 1L, "c@x" -> 1L, "seed@x" -> 1L))

  test("test 18: the joined three-way history merges to B/A/end with no warnings") {
    val (patches, frontier) = test18
    assertEquals(
      Replay.materialize(validated(frontier, patches*), frontier),
      Right((tree("story.txt" -> "B\nA\nend\n"), noWarnings))
    )
  }

  test("test 18: every intermediate pairwise merge is warning-free (line OT emits none)") {
    val (patches, frontier) = test18
    val valid = validated(frontier, patches*)
    val intermediates = Vector(
      v("a@x" -> 1L, "b@x" -> 1L, "seed@x" -> 1L) -> "start\nB\nA\nend\n",
      v("a@x" -> 1L, "c@x" -> 1L, "seed@x" -> 1L) -> "A\nend\n",
      v("b@x" -> 1L, "c@x" -> 1L, "seed@x" -> 1L) -> "B\nend\n"
    )
    intermediates.foreach { (version, content) =>
      assertEquals(
        Replay.materialize(valid, version),
        Right((tree("story.txt" -> content), noWarnings))
      )
    }
  }

  test("test 18: all 24 patch-array permutations produce identical trees and warnings") {
    val (patches, frontier) = test18
    val expected = Replay.materialize(validated(frontier, patches*), frontier)
    assert(expected.isRight)
    patches.permutations.foreach { permuted =>
      assertEquals(Replay.materialize(handBuilt(frontier, permuted), frontier), expected)
    }
  }

  // --- test 21: interleaved per-contributor revisions replay in snap order ---

  test("test 21: the merged story pins the replay order base/B1/B2/A2") {
    val a1 = patch("a@x", 1L, Version.empty, textEdit("story.txt", "", "base\n"))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L), textEdit("story.txt", "base\n", "base\nA2\n"))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), textEdit("story.txt", "base\n", "base\nB1\n"))
    val b2 = patch(
      "b@x",
      2L,
      v("a@x" -> 1L, "b@x" -> 1L),
      textEdit("story.txt", "base\nB1\n", "base\nB1\nB2\n")
    )
    val frontier = v("a@x" -> 2L, "b@x" -> 2L)
    val valid = validated(frontier, a1, a2, b1, b2)
    assertEquals(
      Replay.integrationOrder(valid, frontier),
      Right(
        Vector(Dot(id("a@x"), 1L), Dot(id("b@x"), 1L), Dot(id("b@x"), 2L), Dot(id("a@x"), 2L))
      )
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("story.txt" -> "base\nB1\nB2\nA2\n"), noWarnings))
    )
  }

  // --- test 22: the OT matrix — every sub-case merges silently to the pinned bytes ---

  private def otMatrixCase(aliceContent: String, bobContent: String): (Tree, SortedSet[Warning]) =
    val base = "0\n1\n2\n3\n4\n"
    val seed = patch("seed@x", 1L, Version.empty, textEdit("f", "", base))
    val alice = patch("alice@x", 1L, v("seed@x" -> 1L), textEdit("f", base, aliceContent))
    val bob = patch("bob@x", 1L, v("seed@x" -> 1L), textEdit("f", base, bobContent))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L)
    Replay
      .materialize(validated(frontier, alice, bob, seed), frontier)
      .fold(e => fail(s"expected merge to succeed: ${e.message}"), identity)

  test("test 22 dd: overlapping deletes remove each base token once") {
    assertEquals(otMatrixCase("0\n3\n4\n", "0\n2\n3\n4\n"), (tree("f" -> "0\n3\n4\n"), noWarnings))
  }

  test("test 22 split: P insert, Q-insert priority, count splitting, trailing P insert") {
    assertEquals(
      otMatrixCase("A\n0\n3\n4\nTAIL\n", "0\n1\nB\n3\n4\n"),
      (tree("f" -> "A\n0\nB\n3\n4\nTAIL\n"), noWarnings)
    )
  }

  test("test 22 rd: a token retained by P but deleted by context stays deleted") {
    assertEquals(
      otMatrixCase("0\n1\n2\n3\n4\nA\n", "0\n2\n3\n4\n"),
      (tree("f" -> "0\n2\n3\n4\nA\n"), noWarnings)
    )
  }

  test("test 22 survive: a context insert before a P deletion survives") {
    assertEquals(
      otMatrixCase("0\n2\n3\n4\n", "0\nB\n1\n2\n3\n4\n"),
      (tree("f" -> "0\nB\n2\n3\n4\n"), noWarnings)
    )
  }

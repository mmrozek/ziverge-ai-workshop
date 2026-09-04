package snap.core

import munit.FunSuite

import java.nio.charset.StandardCharsets
import scala.collection.immutable.SortedSet

/** Concurrent integration, directed tests (SPEC §6.2, §6.4; R67–R74): one test per path-level rule
  * asserting both the winning bytes and the exact warning, the namespace pre-pass in both canonical
  * orders, the identical-concurrent-change collapse (before OT, no warning), the aggregate-`Q`
  * discipline (R72), warning ordering (R74), and the sub-replay error path. The YAML merge fixtures
  * live in [[ConcurrentReplayFixturesSuite]]; properties in [[ConcurrentReplayLawsSuite]].
  */
class ConcurrentReplaySuite extends FunSuite:

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

  /** A text change carrying exactly the script `snap commit` would author: the canonical diff of
    * the old content against the new (SPEC §5 — the same engine feeds patch creation and OT).
    */
  private def textEdit(path: String, oldContent: String, newContent: String): Change =
    Change.Text(
      p(path),
      Diff.diff(TextTokens.tokenize(oldContent), TextTokens.tokenize(newContent))
    )

  private def put(path: String, content: String): Change = Change.Put(p(path), utf8(content))
  private def putBytes(path: String, bytes: Byte*): Change =
    Change.Put(p(path), IArray.unsafeFromArray(bytes.toArray))
  private def del(path: String): Change = Change.Delete(p(path))

  private def patch(author: String, revision: Long, base: Version, changes: Change*): Patch =
    Patch
      .make(id(author), revision, base, "m", changes.toVector)
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)

  /** Runs the real §4.5 steps 1–4 first — every fixture here is a structurally valid history. */
  private def validated(frontier: Version, patches: Patch*): Repo.StructurallyValid =
    Repo
      .validate(Repository(frontier, patches.toVector))
      .fold(e => fail(s"expected structurally valid history: ${e.message}"), identity)

  private def tree(entries: (String, String)*): Tree =
    Tree.from(entries.map((path, content) => (p(path), utf8(content))))

  private def warn(path: String, reason: WarningReason): Warning = Warning(p(path), reason)

  /** The standard three-party shape: seed commits, alice and bob branch concurrently off it. Bob's
    * patch integrates first ((bob->1,seed->1) precedes (alice->1,seed->1) in Snap order — at
    * `alice@x` the counters are 0 vs 1), so alice's is the canonically LATER, incoming side.
    */
  private def seeded(
      seedChanges: Vector[Change],
      aliceChanges: Vector[Change],
      bobChanges: Vector[Change]
  ): (Repo.StructurallyValid, Version) =
    val seed = patch("seed@x", 1L, Version.empty, seedChanges*)
    val alice = patch("alice@x", 1L, v("seed@x" -> 1L), aliceChanges*)
    val bob = patch("bob@x", 1L, v("seed@x" -> 1L), bobChanges*)
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L)
    (validated(frontier, alice, bob, seed), frontier)

  // --- §6.4 path-level rules, one directed test each (R73): winning bytes AND exact warning ---

  test("rule 2: an incoming delete over a concurrently edited path wins (delete-wins)") {
    val (valid, frontier) = seeded(
      Vector(textEdit("f", "", "base\n")),
      Vector(del("f")), // alice (later): deletes f
      Vector(textEdit("f", "base\n", "base\nbob\n")) // bob (earlier): edits f
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((Tree.empty, SortedSet(warn("f", WarningReason.DeleteWins))))
    )
    assertEquals(warn("f", WarningReason.DeleteWins).reason.text, "delete-wins")
  }

  test("rule 3: an earlier concurrent delete beats an incoming edit (delete-wins)") {
    val (valid, frontier) = seeded(
      Vector(textEdit("f", "", "base\n")),
      Vector(textEdit("f", "base\n", "base\nalice\n")), // alice (later): edits f
      Vector(del("f")) // bob (earlier): deletes f
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((Tree.empty, SortedSet(warn("f", WarningReason.DeleteWins))))
    )
  }

  test("rule 4: concurrent creates of one path — the canonically later create wins (test 17)") {
    val alice = patch("alice@x", 1L, Version.empty, textEdit("same.txt", "", "alice\n"))
    val bob = patch("bob@x", 1L, Version.empty, textEdit("same.txt", "", "bob\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val valid = validated(frontier, alice, bob)
    assertEquals(
      Replay.materialize(valid, frontier),
      Right(
        (tree("same.txt" -> "alice\n"), SortedSet(warn("same.txt", WarningReason.LaterCreateWins)))
      )
    )
    assertEquals(warn("x", WarningReason.LaterCreateWins).reason.text, "later-create-wins")
  }

  test("rule 4: 'later' means canonical integration order, not content or author intent") {
    // Swap which author writes which content: the winner flips with it — alice's patch is the
    // canonically later one either way.
    val alice = patch("alice@x", 1L, Version.empty, textEdit("same.txt", "", "bob\n"))
    val bob = patch("bob@x", 1L, Version.empty, textEdit("same.txt", "", "alice\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val valid = validated(frontier, alice, bob)
    assertEquals(
      Replay.materialize(valid, frontier).map(_._1),
      Right(tree("same.txt" -> "bob\n"))
    )
  }

  test("rule 5: an incoming put beats a concurrent text edit (later-put-wins, test 10 shape)") {
    val (valid, frontier) = seeded(
      Vector(textEdit("f", "", "base\n")),
      Vector(putBytes("f", 0, 1)), // alice (later): binary put
      Vector(textEdit("f", "base\n", "right text\n")) // bob (earlier): text edit
    )
    val expected = Tree.from(Vector((p("f"), IArray[Byte](0, 1))))
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((expected, SortedSet(warn("f", WarningReason.LaterPutWins))))
    )
    assertEquals(warn("f", WarningReason.LaterPutWins).reason.text, "later-put-wins")
  }

  test("rule 5: concurrent puts of different bytes — the later put wins (later-put-wins)") {
    val (valid, frontier) = seeded(
      Vector(textEdit("f", "", "base\n")),
      Vector(put("f", "A")), // alice (later)
      Vector(put("f", "B")) // bob (earlier)
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("f" -> "A"), SortedSet(warn("f", WarningReason.LaterPutWins))))
    )
  }

  test("rule 6: incoming text over non-text current content — current wins (put-wins)") {
    val (valid, frontier) = seeded(
      Vector(textEdit("f", "", "base\n")),
      Vector(textEdit("f", "base\n", "left text\n")), // alice (later): text edit
      Vector(putBytes("f", 0, -1)) // bob (earlier): binary put (0x00 0xFF)
    )
    val expected = Tree.from(Vector((p("f"), IArray[Byte](0, -1))))
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((expected, SortedSet(warn("f", WarningReason.PutWins))))
    )
    assertEquals(warn("f", WarningReason.PutWins).reason.text, "put-wins")
  }

  // --- identical concurrent changes collapse BEFORE OT, with no warning (R69 case 2) ---

  test("identical concurrent edits collapse without duplication and without warning") {
    val (valid, frontier) = seeded(
      Vector(textEdit("f", "", "base\n")),
      Vector(textEdit("f", "base\n", "base\nsame\n")),
      Vector(textEdit("f", "base\n", "base\nsame\n"))
    )
    // Sending alice's edit through OT instead would duplicate the line ("base\nsame\nsame\n"):
    // the Q-insert priority row retains over bob's identical insertion first.
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("f" -> "base\nsame\n"), noWarnings))
    )
  }

  test("identical concurrent creates collapse without warning") {
    val alice = patch("alice@x", 1L, Version.empty, textEdit("g", "", "same\n"))
    val bob = patch("bob@x", 1L, Version.empty, textEdit("g", "", "same\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val valid = validated(frontier, alice, bob)
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("g" -> "same\n"), noWarnings))
    )
  }

  test("identical concurrent deletes collapse without warning") {
    val (valid, frontier) = seeded(
      Vector(textEdit("f", "", "base\n")),
      Vector(del("f")),
      Vector(del("f"))
    )
    assertEquals(Replay.materialize(valid, frontier), Right((Tree.empty, noWarnings)))
  }

  // --- namespace pre-pass (R68), both canonical orders (test 11) ---

  test("namespace: later create of file `a` removes concurrent `a/b`; warning names the removed") {
    val alice = patch("alice@x", 1L, Version.empty, textEdit("a", "", "ancestor\n"))
    val bob = patch("bob@x", 1L, Version.empty, textEdit("a/b", "", "descendant\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val valid = validated(frontier, alice, bob)
    // bob's a/b lands first; alice's later create of `a` wins the namespace.
    val result = Replay.materialize(valid, frontier)
    assertEquals(
      result,
      Right((tree("a" -> "ancestor\n"), SortedSet(warn("a/b", WarningReason.NamespaceWins))))
    )
    assertEquals(warn("a/b", WarningReason.NamespaceWins).reason.text, "namespace-wins")
    assert(result.exists(_._1.isPrefixFree))
  }

  test("namespace: later create of `x/y` removes concurrent file `x`; warning names the removed") {
    val alice = patch("alice@x", 1L, Version.empty, textEdit("x/y", "", "descendant\n"))
    val bob = patch("bob@x", 1L, Version.empty, textEdit("x", "", "ancestor\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 1L)
    val valid = validated(frontier, alice, bob)
    // bob's file `x` lands first; alice's later create of `x/y` wins the namespace.
    val result = Replay.materialize(valid, frontier)
    assertEquals(
      result,
      Right((tree("x/y" -> "descendant\n"), SortedSet(warn("x", WarningReason.NamespaceWins))))
    )
    assert(result.exists(_._1.isPrefixFree))
  }

  test("namespace: `C'` excludes the patch's own authored deletions (§6.2)") {
    // Alice deletes `a` and creates `a/b` in ONE patch while bob concurrently edited `a`: her own
    // deletion clears the way (no namespace conflict, no namespace-wins), and the discarded edit
    // surfaces as the incoming delete's rule-2 warning instead.
    val (valid, frontier) = seeded(
      Vector(textEdit("a", "", "base\n")),
      Vector(del("a"), textEdit("a/b", "", "child\n")), // alice (later)
      Vector(textEdit("a", "base\n", "base\nbob\n")) // bob (earlier)
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("a/b" -> "child\n"), SortedSet(warn("a", WarningReason.DeleteWins))))
    )
  }

  test("namespace decisions and per-path outcomes of one patch apply together (R70)") {
    // One alice patch both creates the namespace winner (a/b over bob's concurrent `a`) and edits
    // an unrelated file — all effects land in one step.
    val (valid, frontier) = seeded(
      Vector(textEdit("notes", "", "n\n")),
      Vector(textEdit("a/b", "", "child\n"), textEdit("notes", "n\n", "n\nalice\n")),
      Vector(textEdit("a", "", "root\n"))
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right(
        (
          tree("a/b" -> "child\n", "notes" -> "n\nalice\n"),
          SortedSet(warn("a", WarningReason.NamespaceWins))
        )
      )
    )
  }

  // --- aggregate context edit (R72): Q = diff(B, C), never per-patch chaining ---

  test("aggregate Q collapses a concurrent delete-then-reinsert chain that chaining would not") {
    // seed: f = x,y · bob deletes x then re-inserts it (two patches) · carol appends z (so C != B
    // and the OT branch actually runs) · alice concurrently deletes x.
    // Aggregate: at alice's integration Q = diff("x\ny\n", "x\ny\nz\n") = [retain 2, insert z] —
    // bob's delete+reinsert cancels out in the canonical diff — so alice's delete of x survives:
    // final "y\nz\n". Per-patch chaining would kill alice's delete against bob's delete and let
    // bob's re-inserted x survive, yielding "x\ny\nz\n" — a different (wrong) result.
    val seed = patch("seed@x", 1L, Version.empty, textEdit("f", "", "x\ny\n"))
    val b1 = patch("bob@x", 1L, v("seed@x" -> 1L), textEdit("f", "x\ny\n", "y\n"))
    val b2 = patch("bob@x", 2L, v("bob@x" -> 1L, "seed@x" -> 1L), textEdit("f", "y\n", "x\ny\n"))
    val c1 = patch("carol@x", 1L, v("seed@x" -> 1L), textEdit("f", "x\ny\n", "x\ny\nz\n"))
    val a1 = patch("alice@x", 1L, v("seed@x" -> 1L), textEdit("f", "x\ny\n", "y\n"))
    val frontier = v("alice@x" -> 1L, "bob@x" -> 2L, "carol@x" -> 1L, "seed@x" -> 1L)
    val valid = validated(frontier, a1, b1, b2, c1, seed)
    assertEquals(
      Replay.integrationOrder(valid, frontier),
      Right(
        Vector(
          Dot(id("seed@x"), 1L),
          Dot(id("carol@x"), 1L),
          Dot(id("bob@x"), 1L),
          Dot(id("bob@x"), 2L),
          Dot(id("alice@x"), 1L)
        )
      )
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("f" -> "y\nz\n"), noWarnings))
    )
  }

  // --- warnings (R74): unique sorted pairs; rendered reasons ---

  test("warning reasons render exactly the spec vocabulary") {
    assertEquals(WarningReason.DeleteWins.text, "delete-wins")
    assertEquals(WarningReason.LaterCreateWins.text, "later-create-wins")
    assertEquals(WarningReason.LaterPutWins.text, "later-put-wins")
    assertEquals(WarningReason.NamespaceWins.text, "namespace-wins")
    assertEquals(WarningReason.PutWins.text, "put-wins")
  }

  test("warnings sort by path then reason, both in Utf8Order (R74)") {
    val set = SortedSet(
      warn("b", WarningReason.DeleteWins),
      warn("a", WarningReason.PutWins),
      warn("a", WarningReason.DeleteWins),
      warn("a", WarningReason.NamespaceWins),
      warn("a", WarningReason.LaterPutWins),
      warn("a", WarningReason.LaterCreateWins),
      warn("a", WarningReason.DeleteWins) // duplicate collapses
    )
    assertEquals(
      set.toVector,
      Vector(
        warn("a", WarningReason.DeleteWins),
        warn("a", WarningReason.LaterCreateWins),
        warn("a", WarningReason.LaterPutWins),
        warn("a", WarningReason.NamespaceWins),
        warn("a", WarningReason.PutWins),
        warn("b", WarningReason.DeleteWins)
      )
    )
  }

  // --- sub-replay fallibility (reviews/T07-review.md nit 1): base materialization CAN fail ---

  // --- sub-replay interposition (reviews/T16-review.md nit 1): a warning that only the SUB-REPLAY
  // would raise must never leak into the frontier's warning set ---

  test(
    "reviews/T16-review.md nit 1: a warning raised only while materializing a patch's base is" +
      " discarded, even when it differs from the outer walk's own resolution of the same paths"
  ) {
    // Shape (T16 review ruling 5): p1 and p2 are two same-path (`f`) concurrent branches off
    // `seed` that also compose gamma's declared base (gamma's own change is elsewhere, at
    // `gpath`) — but an UNRELATED third branch `d`, also concurrent at `f`, interposes between
    // p1 and p2 in the OUTER ready order (author ids are chosen so Snap order integrates
    // p1, then d, then p2 — see the review's own scenario). `d` is NOT part of gamma's base, so
    // materializing gamma's base sub-replays only {seed, p1, p2}.
    //
    // Outer walk (direct integration, real global context):
    //   p1 (B=C at "base\n") -> f = "base\nP1\n"
    //   d  (delete; T absent regardless of B/C) -> rule 2 delete-wins; f removed
    //   p2 (put; B present "base\n", C absent since d just removed f) -> rule 3 delete-wins
    //   gamma integrates last, touching only "gpath"; f is untouched by it.
    // Final: f absent, ONE warning (delete-wins, deduped from d's and p2's own resolutions).
    //
    // Sub-replay for gamma's base (only seed, p1, p2 — no d):
    //   p1 -> f = "base\nP1\n" (present, since d's deletion never happens in this smaller context)
    //   p2 (put; B present "base\n", C present "base\nP1\n", T="PUT\n" != C) -> rule 5
    //   later-put-wins — a DIFFERENT reason than the outer walk's delete-wins, for the SAME p2.
    // Discarding this sub-replay's warnings (Replay.scala's materializeMemo) is not merely
    // avoiding a duplicate: folding it in would add a WRONG "f: later-put-wins" pair that does not
    // correspond to the true global resolution (the review's point 5, sharpened).
    val seed = patch("seed@x", 1L, Version.empty, textEdit("f", "", "base\n"))
    val p1 = patch("zp1@x", 1L, v("seed@x" -> 1L), textEdit("f", "base\n", "base\nP1\n"))
    val d = patch("mid@x", 1L, v("seed@x" -> 1L), del("f"))
    val p2 = patch("ap2@x", 1L, v("seed@x" -> 1L), put("f", "PUT\n"))
    val gamma = patch(
      "gam@x",
      1L,
      v("ap2@x" -> 1L, "seed@x" -> 1L, "zp1@x" -> 1L),
      textEdit("gpath", "", "g\n")
    )
    val frontier = v("ap2@x" -> 1L, "gam@x" -> 1L, "mid@x" -> 1L, "seed@x" -> 1L, "zp1@x" -> 1L)
    val valid = validated(frontier, p2, gamma, d, seed, p1)

    // Confirm the engineered outer integration order (p1, then d, then p2, then gamma) actually
    // holds before trusting the result below.
    assertEquals(
      Replay.integrationOrder(valid, frontier),
      Right(
        Vector(
          Dot(id("seed@x"), 1L),
          Dot(id("zp1@x"), 1L),
          Dot(id("mid@x"), 1L),
          Dot(id("ap2@x"), 1L),
          Dot(id("gam@x"), 1L)
        )
      )
    )
    assertEquals(
      Replay.materialize(valid, frontier),
      Right((tree("gpath" -> "g\n"), SortedSet(warn("f", WarningReason.DeleteWins))))
    )
  }

  test("a structurally valid history with a non-self-contained base fails with the pinned phrase") {
    // c1's base (a@x->2) selects a2 but not a2's own base component b@x->1: the sub-replay of
    // that base stalls (no ready patch) — `cyclic or incomplete patch history` (R60), even though
    // every step-1–4 check passes.
    val a1 = patch("a@x", 1L, Version.empty, textEdit("fa", "", "a\n"))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), textEdit("fb", "", "b\n"))
    val a2 = patch("a@x", 2L, v("a@x" -> 1L, "b@x" -> 1L), textEdit("fa", "a\n", "a\na2\n"))
    val c1 = patch("c@x", 1L, v("a@x" -> 2L), textEdit("fc", "", "c\n"))
    val frontier = v("a@x" -> 2L, "b@x" -> 1L, "c@x" -> 1L)
    val valid = validated(frontier, a1, a2, b1, c1)
    val result = Replay.materialize(valid, frontier)
    assertEquals(result, Left(SnapError.CyclicHistory))
    assertEquals(result.left.map(_.message), Left("cyclic or incomplete patch history"))
  }

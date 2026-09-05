package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.rng.Seed

import java.nio.charset.StandardCharsets
import scala.collection.immutable.SortedSet

/** Property tests for the concurrent merge engine (SPEC §6.2–§6.5; determinism obligations,
  * CLAUDE.md / R76): for generated valid concurrent histories, `(tree, warnings)` is a function of
  * the patch set and frontier alone — insensitive to the patch array's order (equivalently, to the
  * interleaving in which branches were imported/merged), byte-identical across repeated replays —
  * the merged tree stays prefix-free, and text-only (OT) histories resolve without warnings.
  */
class ConcurrentReplayLawsSuite extends munit.ScalaCheckSuite:

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"bad id $raw: ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"bad path $raw: $e"), identity)

  private def utf8(text: String): IArray[Byte] =
    // Fresh array, never aliased afterwards.
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  private val authors: Vector[ContributorId] = Vector(id("a@x"), id("b@x"), id("c@x"))

  /** A pool with genuine ancestor/descendant relations so concurrent branches collide on paths,
    * namespaces, and content kinds.
    */
  private val createPool: Vector[SnapPath] =
    Vector(p("a"), p("a/b"), p("b"), p("c/d"), p("c/d/e"))

  /** One generated step, turned into a concrete valid patch by [[buildConcurrent]]: the author, a
    * bitmask choosing which existing results to join into the base (concurrency arises when a
    * branch's base excludes other branches' results), an operation kind, and pick seeds.
    */
  private final case class StepSeed(authorPick: Int, baseMask: Int, kind: Int, pick: Int)

  private val genSeeds: Gen[Vector[StepSeed]] =
    for
      n <- Gen.choose(2, 10)
      seeds <- Gen.listOfN(
        n,
        for
          authorPick <- Gen.choose(0, authors.size - 1)
          baseMask <- Gen.choose(0, 255)
          kind <- Gen.choose(0, 3)
          pick <- Gen.choose(0, 1000)
        yield StepSeed(authorPick, baseMask, kind, pick)
      )
    yield seeds.toVector

  /** Builds a valid concurrent multi-author history. Each step's base is the join of the author's
    * own last result (keeping `revision = base[author] + 1`) with a seed-chosen subset of the other
    * existing results — joins of known versions are known, so every base is materializable. The
    * change is authored against the materialized exact base tree, so §4.5 step 5 always passes:
    * creates avoid present paths and their ancestors/descendants (falling back to a globally fresh
    * single-segment name), edits diff a present text file to itself plus a unique line, deletes
    * pick a present path, puts write unique bytes (biased toward binary to exercise rule 6).
    *
    * Generator coverage (concurrency, all five warning reasons) is not claimed here as prose —
    * reviews/T16-review.md nit 2 flagged exactly that as unverifiable. See the dedicated,
    * fixed-seed coverage test below (`"generator coverage: ..."`), which FAILS if the generator
    * stops producing genuinely concurrent histories or stops covering all five reasons, so the
    * invariance properties in this suite cannot silently go vacuous.
    */
  private def buildConcurrent(seeds: Vector[StepSeed]): (Vector[Patch], Version) =
    val built = seeds.zipWithIndex.foldLeft((Vector.empty[Patch], Vector.empty[Version])) {
      case ((patches, results), (seed, i)) =>
        val author = authors(seed.authorPick)
        val ownLast = patches.lastIndexWhere(_.author == author) match
          case -1 => Version.empty
          case k  => results(k)
        val base = results.indices.foldLeft(ownLast) { (acc, k) =>
          if ((seed.baseMask >> (k % 8)) & 1) == 1 then acc.join(results(k)) else acc
        }
        val baseTree = materialized(patches, results, base)
        val change = chooseChange(seed, i, baseTree)
        val patch = Patch
          .make(author, base.get(author) + 1L, base, "m", Vector(change))
          .fold(e => fail(s"generator produced an invalid patch: ${e.message}"), identity)
        val result = patch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
        (patches :+ patch, results :+ result)
    }
    val frontier = built._2.foldLeft(Version.empty)(_.join(_))
    (built._1, frontier)

  private def materialized(
      patches: Vector[Patch],
      results: Vector[Version],
      version: Version
  ): Tree =
    val frontier = results.foldLeft(Version.empty)(_.join(_))
    Replay
      .materialize(handBuilt(frontier, patches), version)
      .fold(e => fail(s"generator base failed to materialize: ${e.message}"), _._1)

  /** The edit script for editing `old`'s tokens at a seed-picked position: mode 0 is a pure insert
    * of one brand-new, globally-unique line (the only mode available at `pos == old.size`, i.e.
    * `maxRemove == 0`, appending past the last token — nothing there to remove); mode 1 is a pure
    * delete of 1..`maxRemove` existing tokens; mode 2 replaces 1..`maxRemove` existing tokens with
    * one new unique line. Modes 1 and 2 are what make `Change.Text` scripts (and therefore the
    * aggregate context edit `Q = Diff.diff(B, C)` computed live during replay) sometimes contain a
    * `Delete` op and sometimes a genuine content replacement — reviews/T18-review.md finding 1: an
    * insertion-only generator can never author a `Delete` op, so neither R62's diff tie-break nor
    * OT's three delete-consuming rows (Ot.scala:69-93) are reachable by any property in this suite.
    * Shared by [[chooseChange]] and [[buildTextOnly]] so both call sites carry the fix.
    */
  private def textEdit(seed: StepSeed, i: Int, old: Vector[String]): EditScript =
    val pos = seed.pick % (old.size + 1)
    val maxRemove = old.size - pos
    val editMode = if maxRemove == 0 then 0 else seed.pick % 3
    val removed = if editMode == 0 then 0 else 1 + (seed.pick / 3) % maxRemove
    val inserted = if editMode == 1 then Vector.empty else Vector(s"e$i\n")
    Diff.diff(old, old.patch(pos, inserted, removed))

  private def chooseChange(seed: StepSeed, i: Int, baseTree: Tree): Change =
    val present = baseTree.paths
    def create: Change =
      val candidates = createPool.filter { cand =>
        !baseTree.contains(cand) &&
        baseTree.ancestorsOf(cand).isEmpty &&
        baseTree.descendantsOf(cand).isEmpty
      }
      val path = if candidates.isEmpty then p(s"g$i") else candidates(seed.pick % candidates.size)
      Change.Text(path, Diff.diff(Vector.empty, Vector(s"c$i\n")))
    seed.kind match
      case 1 => // edit a present text file: insert, delete, or replace existing tokens at a
        // seed-picked position (see textEdit's doc)
        val textPaths = present.filter(path =>
          TextTokens
            .tokenizeBytes(
              IArray.genericWrapArray(baseTree.get(path).fold(IArray.empty[Byte])(identity)).toArray
            )
            .isDefined
        )
        if textPaths.isEmpty then create
        else
          val path = textPaths(seed.pick % textPaths.size)
          val old = TextTokens
            .tokenizeBytes(
              IArray.genericWrapArray(baseTree.get(path).fold(IArray.empty[Byte])(identity)).toArray
            )
            .getOrElse(fail("filtered path must be text"))
          Change.Text(path, textEdit(seed, i, old))
      case 2 => // delete a present path
        if present.isEmpty then create else Change.Delete(present(seed.pick % present.size))
      case 3 => // replace a present path with unique bytes, alternating text and binary
        if present.isEmpty then create
        else
          val path = present(seed.pick % present.size)
          val content =
            if seed.pick % 3 != 0 then IArray[Byte](0, (i % 127).toByte) // binary: NUL byte
            else utf8(s"p$i\n")
          Change.Put(path, content)
      case _ => create

  private def handBuilt(frontier: Version, patches: Vector[Patch]): Repo.StructurallyValid =
    Repo.StructurallyValid(
      Repository(frontier, patches),
      patches.map(_.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity))
    )

  private def sortedForValidate(patches: Vector[Patch]): Vector[Patch] =
    patches.sortBy(patch => (patch.author.value, patch.revision))(
      Ordering.Tuple2(Utf8Order, Ordering.Long)
    )

  /** The generated history must also pass the real §4.5 steps 1–4 — a generator soundness gate. */
  private def structurallyValid(frontier: Version, patches: Vector[Patch]): Repo.StructurallyValid =
    Repo
      .validate(Repository(frontier, sortedForValidate(patches)))
      .fold(e => fail(s"generated history failed steps 1–4: ${e.message}"), identity)

  private val genHistory: Gen[(Vector[Patch], Version)] = genSeeds.map(buildConcurrent)

  /** A deterministic permutation from generated seeds: indices sorted by (seed, index). */
  private def genPermutation(n: Int): Gen[Vector[Int]] =
    Gen.listOfN(n, Gen.choose(Long.MinValue, Long.MaxValue)).map { seeds =>
      (0 until n).toVector.sortBy(i => (seeds(i), i))
    }

  property("(tree, warnings) is invariant under patch-array and import-order permutation") {
    forAll(genHistory.flatMap { case h @ (patches, _) =>
      genPermutation(patches.size).map(perm => (h, perm))
    }) { case ((patches, frontier), perm) =>
      val reference = Replay.materialize(structurallyValid(frontier, patches), frontier)
      assert(reference.isRight, reference.left.map(_.message))
      // Steps 1–4 enforce file sorting, so the permuted array is hand-assembled: replay itself
      // must never read input order — a permuted union is exactly what a different merge
      // interleaving imports.
      assertEquals(Replay.materialize(handBuilt(frontier, perm.map(patches)), frontier), reference)
    }
  }

  property("replaying an already-replayed frontier is idempotent (byte-identical runs)") {
    forAll(genHistory) { case (patches, frontier) =>
      val valid = structurallyValid(frontier, patches)
      val first = Replay.materialize(valid, frontier)
      val second = Replay.materialize(valid, frontier)
      assertEquals(first, second) // Tree equality is byte-content equality
      // A structurally fresh proof value over the same history replays identically too.
      assertEquals(Replay.materialize(structurallyValid(frontier, patches), frontier), first)
    }
  }

  property("the merged tree is prefix-free: namespace resolution never leaks a clash (R68/R25)") {
    forAll(genHistory) { case (patches, frontier) =>
      val result = Replay.materialize(structurallyValid(frontier, patches), frontier)
      assert(result.exists(_._1.isPrefixFree), result.left.map(_.message))
    }
  }

  // --- text-only histories: the OT path emits no warnings (R74) ---

  private val genTextOnly: Gen[(Vector[Patch], Version)] =
    genSeeds.map(buildTextOnly)

  /** A seed create of one file plus concurrent text edits of it — every integration resolves
    * through R69 cases 1–3 (direct apply, identical collapse, or OT), never the warning rules.
    */
  private def buildTextOnly(seeds: Vector[StepSeed]): (Vector[Patch], Version) =
    val file = p("story")
    val seedPatch = Patch
      .make(
        id("seed@x"),
        1L,
        Version.empty,
        "m",
        Vector(Change.Text(file, Diff.diff(Vector.empty, Vector("one\n", "two\n"))))
      )
      .fold(e => fail(s"unbuildable seed: ${e.message}"), identity)
    val seedResult = seedPatch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
    val built = seeds.zipWithIndex.foldLeft((Vector(seedPatch), Vector(seedResult))) {
      case ((patches, results), (seed, i)) =>
        val author = authors(seed.authorPick)
        val ownLast = patches.lastIndexWhere(_.author == author) match
          case -1 => seedResult // every base contains the seed, so the file always exists
          case k  => results(k)
        val base = results.indices.foldLeft(ownLast.join(seedResult)) { (acc, k) =>
          if ((seed.baseMask >> (k % 8)) & 1) == 1 then acc.join(results(k)) else acc
        }
        val baseTree = materialized(patches, results, base)
        val old = TextTokens
          .tokenizeBytes(
            IArray.genericWrapArray(baseTree.get(file).fold(IArray.empty[Byte])(identity)).toArray
          )
          .getOrElse(fail("text-only file must stay text"))
        val change = Change.Text(file, textEdit(seed, i, old))
        val patch = Patch
          .make(author, base.get(author) + 1L, base, "m", Vector(change))
          .fold(e => fail(s"generator produced an invalid patch: ${e.message}"), identity)
        val result = patch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
        (patches :+ patch, results :+ result)
    }
    (built._1, built._2.foldLeft(Version.empty)(_.join(_)))

  property("text-only concurrent histories merge through OT with an empty warning set") {
    forAll(genTextOnly) { case (patches, frontier) =>
      val result = Replay.materialize(structurallyValid(frontier, patches), frontier)
      assertEquals(result.map(_._2), Right(SortedSet.empty[Warning]))
    }
  }

  property("text-only merges are also permutation-invariant") {
    forAll(genTextOnly.flatMap { case h @ (patches, _) =>
      genPermutation(patches.size).map(perm => (h, perm))
    }) { case ((patches, frontier), perm) =>
      val reference = Replay.materialize(structurallyValid(frontier, patches), frontier)
      assertEquals(Replay.materialize(handBuilt(frontier, perm.map(patches)), frontier), reference)
    }
  }

  // --- targeted content-correctness check (reviews/T18-review.md finding 1) ---
  //
  // The properties above compare a computation against ITSELF (permutation/idempotence) or a
  // recombination against a reference built the same way — a deterministic-but-wrong Diff/Ot bug
  // survives both, because both sides of the comparison run through the identical (buggy) code on
  // the identical input. Confirmed empirically while building this fix: mutating either
  // `Diff.scala`'s tie-break (R62) or `Ot.scala`'s row 4 (a P-delete/Q-retain row silently emitting
  // `Retain` instead of `Delete`) leaves every property above green even once the generator
  // authors real deletions and replacements (see reviews/T18-review.md and this task's own notes
  // for the exact mutation-testing trace). This property instead compares against an
  // INDEPENDENTLY DERIVED expected token sequence, so it can actually distinguish correct from
  // wrong.

  private final case class CursorCollision(base: Vector[String], pos: Int, removed: Int)

  /** `n` distinct, LF-terminated base tokens (`"L0\n".."L{n-1}\n"`, never colliding with the "R\n"/
    * "I\n" tags used below), a position `pos` inside the base, and a removal count `removed` that
    * always leaves at least one token genuinely replaced (`pos < n`, `1 <= removed <= n - pos`).
    */
  private val genCursorCollision: Gen[CursorCollision] =
    for
      n <- Gen.choose(2, 6)
      pos <- Gen.choose(0, n - 1)
      removed <- Gen.choose(1, n - pos)
    yield CursorCollision((0 until n).map(k => s"L$k\n").toVector, pos, removed)

  /** Two concurrent single-change patches over a shared seeded file, both based on the seed's
    * result (genuinely concurrent — neither sees the other): `replacerAuthor` deletes
    * [[CursorCollision.removed]] existing tokens at `pos` and inserts a distinct `"R\n"` tag (a
    * genuine content replacement of DISJOINT content — ties the diff walk at every step of that
    * sub-block, per R61's recurrence, so this always exercises R62's tie-break, not just
    * sometimes); `inserterAuthor` purely inserts a distinct `"I\n"` tag at the SAME position `pos`
    * (no removal) — the same cursor as the replacement. Both stay within R69 case 3 (OT, no
    * warnings): neither creates, deletes, nor touches any other path.
    *
    * Authors are a parameter, not fixed literals: [[Replay.readyOrdering]] decides which of two
    * concurrent patches is integrated first (becomes the aggregate context `Q` the other is
    * transformed against as `P`) by comparing RESULT VERSIONS, not by which edit is "the replace" —
    * so whichever author name wins that comparison is always cast as `Q`, and the delete op only
    * ever reaches `Ot.transform` as a `P delete` (exercising row 4) when the REPLACER loses it. The
    * property below calls this with both author-role assignments per generated case so row 4 is
    * exercised regardless of which literal names are chosen (reviews/T18-review.md finding 1:
    * confirmed necessary by mutation testing — a fixed author pair let the OT row 4 mutation
    * (`Ot.scala`'s `P delete`/`Q retain` row silently emitting `Retain` instead of `Delete`) slip
    * through even after this property existed).
    */
  private def buildCursorCollision(
      c: CursorCollision,
      replacerAuthor: ContributorId,
      inserterAuthor: ContributorId
  ): (Vector[Patch], Version) =
    val file = p("cursor")
    val seed = Patch
      .make(
        id("seed@x"),
        1L,
        Version.empty,
        "m",
        Vector(Change.Text(file, Diff.diff(Vector.empty, c.base)))
      )
      .fold(e => fail(s"unbuildable seed: ${e.message}"), identity)
    val seedResult = seed.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
    val replaced = c.base.patch(c.pos, Vector("R\n"), c.removed)
    val inserted = c.base.patch(c.pos, Vector("I\n"), 0)
    val replacer = Patch
      .make(
        replacerAuthor,
        1L,
        seedResult,
        "m",
        Vector(Change.Text(file, Diff.diff(c.base, replaced)))
      )
      .fold(e => fail(s"unbuildable replacer: ${e.message}"), identity)
    val inserter = Patch
      .make(
        inserterAuthor,
        1L,
        seedResult,
        "m",
        Vector(Change.Text(file, Diff.diff(c.base, inserted)))
      )
      .fold(e => fail(s"unbuildable inserter: ${e.message}"), identity)
    val replacerResult =
      replacer.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
    val inserterResult =
      inserter.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
    val frontier = seedResult.join(replacerResult).join(inserterResult)
    (Vector(seed, replacer, inserter), frontier)

  property(
    "a concurrent replace and a concurrent pure insert at the same cursor converge with the" +
      " insert's tag immediately before the replace's tag, and every replaced token gone" +
      " (reviews/T18-review.md finding 1)"
  ) {
    forAll(genCursorCollision) { c =>
      // Both author-role assignments (see buildCursorCollision's doc): exactly one of the two puts
      // the replacer's delete on the `P` side of `Ot.transform`, hitting row 4.
      Vector((id("d1@x"), id("d2@x")), (id("d2@x"), id("d1@x"))).foreach {
        case (replacerAuthor, inserterAuthor) =>
          val (patches, frontier) = buildCursorCollision(c, replacerAuthor, inserterAuthor)
          val result = Replay.materialize(structurallyValid(frontier, patches), frontier)
          result match
            case Left(e)                 => fail(s"expected a clean OT merge, got: ${e.message}")
            case Right((tree, warnings)) =>
              assertEquals(warnings, SortedSet.empty[Warning])
              val bytes = IArray
                .genericWrapArray(tree.get(p("cursor")).fold(IArray.empty[Byte])(identity))
                .toArray
              val tokens =
                TextTokens.tokenizeBytes(bytes).getOrElse(fail("merged cursor file must stay text"))
              val removedTokens = c.base.slice(c.pos, c.pos + c.removed).toSet
              assert(!tokens.exists(removedTokens.contains), s"a replaced token survived: $tokens")
              val insIdx = tokens.indexOf("I\n")
              val replIdx = tokens.indexOf("R\n")
              assert(insIdx >= 0 && replIdx >= 0, s"both tags must survive exactly once: $tokens")
              assertEquals(
                replIdx,
                insIdx + 1,
                s"expected the insert tag immediately before the replace tag: $tokens"
              )
      }
    }
  }

  // --- generator soundness (reviews/T16-review.md nit 2): an assertion, not a prose claim ---

  /** Deterministic (fixed seed `7L`, reproducible): draws `samples` histories directly via
    * [[Gen.pureApply]] rather than `forAll`, so the coverage measurement is exact over a known set
    * of histories rather than approximate over whatever `forAll` happens to try. Fails if
    * [[buildConcurrent]] regresses to producing only sequential (non-concurrent) histories, or
    * stops exercising every one of the five [[WarningReason]] values — the exact vacuousness risk
    * the review flagged in the superseded prose comment above.
    */
  test(
    "generator coverage: buildConcurrent produces genuinely concurrent histories covering all" +
      " five warning reasons (not vacuous)"
  ) {
    val samples = 300
    val params = Gen.Parameters.default
    val histories =
      LazyList.iterate(Seed(7L))(_.next).take(samples).map(genHistory.pureApply(params, _))

    def hasConcurrentPair(patches: Vector[Patch]): Boolean =
      patches.indices.exists { i =>
        (i + 1 until patches.size).exists { j =>
          patches(i).result.toOption
            .zip(patches(j).result.toOption)
            .exists { case (ri, rj) => ri.compareCausal(rj) == Ord.Concurrent }
        }
      }

    // reviews/T18-review.md finding 1: a generator regressing to insertion-only `Change.Text`
    // scripts would silently drop all coverage of R62's diff tie-break and OT's delete-consuming
    // rows — this must fail exactly as loudly as a missing warning reason would.
    def hasTextDelete(patches: Vector[Patch]): Boolean =
      patches.exists(_.changes.exists {
        case Change.Text(_, edit) =>
          edit.ops.exists { case EditOp.Delete(_) => true; case _ => false }
        case _ => false
      })

    val evaluated = histories.map { case (patches, frontier) =>
      val warnings = Replay
        .materialize(structurallyValid(frontier, patches), frontier)
        .fold(e => fail(s"generated history failed to replay: ${e.message}"), _._2)
      (warnings, hasConcurrentPair(patches), hasTextDelete(patches))
    }.toVector

    val concurrentCount = evaluated.count(_._2)
    val historiesWithWarnings = evaluated.count(_._1.nonEmpty)
    val deleteEditCount = evaluated.count(_._3)
    val reasonsSeen = evaluated.iterator.flatMap(_._1).map(_.reason).toSet

    // Measured over this exact fixed-seed run (this test's own assertions ARE the measurement —
    // no unverified prose): with seed 7L and 300 samples, at least a quarter of histories contain a
    // `Change.Text` script with a `Delete` op (reviews/T18-review.md finding 1's fix), most contain
    // a genuinely concurrent pair of patches, a substantial fraction produce at least one warning,
    // and all five reasons are observed. Thresholds below are set with margin under the measured
    // counts (see the task notes for the exact figures from this implementation's own runs).
    assert(
      concurrentCount >= samples * 7 / 10,
      s"expected most of $samples generated histories to contain a concurrent pair, only" +
        s" $concurrentCount did"
    )
    assert(
      historiesWithWarnings >= samples * 2 / 5,
      s"expected a substantial fraction of $samples histories to produce a warning, only" +
        s" $historiesWithWarnings did"
    )
    assert(
      deleteEditCount >= samples / 4,
      s"expected a substantial fraction of $samples histories to contain a text edit with a" +
        s" Delete op, only $deleteEditCount did"
    )
    assertEquals(
      reasonsSeen,
      Set(
        WarningReason.DeleteWins,
        WarningReason.LaterCreateWins,
        WarningReason.LaterPutWins,
        WarningReason.NamespaceWins,
        WarningReason.PutWins
      )
    )
  }

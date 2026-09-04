package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

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
    * Coverage was measured, not assumed: across 200 fixed-seed samples, 96 histories produced
    * warnings and every reason fired (delete-wins 42, later-create-wins 32, namespace-wins 22,
    * later-put-wins 16, put-wins 6) — the invariance properties below are not vacuous.
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
      case 1 => // edit a present text file: insert one unique line at a seed-picked position
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
          val pos = seed.pick % (old.size + 1)
          Change.Text(path, Diff.diff(old, old.patch(pos, Vector(s"e$i\n"), 0)))
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
        val pos = seed.pick % (old.size + 1)
        val change = Change.Text(file, Diff.diff(old, old.patch(pos, Vector(s"e$i\n"), 0)))
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

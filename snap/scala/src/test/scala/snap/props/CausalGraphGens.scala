package snap.props

import org.scalacheck.Gen
import snap.core.Change
import snap.core.ContributorId
import snap.core.Diff
import snap.core.EditScript
import snap.core.Patch
import snap.core.Replay
import snap.core.Repo
import snap.core.Repository
import snap.core.SnapError
import snap.core.SnapPath
import snap.core.TextTokens
import snap.core.Tree
import snap.core.Utf8Order
import snap.core.Version

import java.nio.charset.StandardCharsets
import scala.collection.immutable.SortedSet

/** A scalacheck generator of valid causal patch graphs (SPEC §4–§6; R109; CLAUDE.md's mandatory
  * determinism properties) built exclusively against `snap.core`'s PUBLIC surface —
  * [[snap.core.Repo.validate]]/[[snap.core.Repo.validateFully]] and
  * [[snap.core.Replay.materialize]] — never the `private[core]` proof constructors. This is
  * deliberate: `ConvergencePropsSuite` exercises the engine the way an eventual external consumer
  * (the `merge` command, T17/second pass) would, one layer further out than `snap.core`'s own
  * `ConcurrentReplayLawsSuite` (which reaches into `Repo.StructurallyValid`'s package-private
  * constructor to bypass steps 1–4 for permuted arrays — a capability that is, by design,
  * unforgeable from here; see reviews/T16-review.md point 7).
  *
  * The shape mirrors `ConcurrentReplayLawsSuite`'s `buildConcurrent` (house style, per the T18 task
  * brief): a step-driven fold builds a multi-contributor history where each step's base is the
  * author's own last result joined with a seed-chosen subset of other authors' existing results —
  * concurrency arises whenever a branch's base excludes another branch's result — and the change is
  * authored against the exact materialized base tree, so every generated patch is valid by
  * construction:
  *
  *   - **R65 base closure**: `base` is always a join of already-validated result versions (or
  *     empty), so its causal closure is always exactly the already-generated prefix.
  *   - **contiguous revisions per contributor**: each new patch for an author is
  *     `base.get(author) + 1`, so a contributor's revisions are exactly `1..k` by construction.
  *   - **prefix-free authored trees**: each patch carries exactly one change, so its own authored
  *     result can never conflict with itself (`Replay.authoredResult`'s cross-patch prefix check is
  *     never touched by a single-change patch); cross-patch prefix collisions are the deliberate
  *     namespace-collision shapes ([[createPool]]) that §6.2's pre-pass must resolve.
  */
object CausalGraphGens extends munit.Assertions:

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"bad id $raw: ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"bad path $raw: $e"), identity)

  private def utf8(text: String): IArray[Byte] =
    // Fresh array, never aliased afterwards.
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  /** Four contributors — one more than `ConcurrentReplayLawsSuite`'s three, so a 2- or 3-way
    * replica split (below) always has room to distribute authors' work unevenly across shards.
    */
  private val authors: Vector[ContributorId] =
    Vector(id("a1@x"), id("a2@x"), id("a3@x"), id("a4@x"))

  /** A pool with genuine ancestor/descendant relations so concurrent branches collide on paths,
    * namespaces, and content kinds (mirrors `ConcurrentReplayLawsSuite.createPool`, one extra
    * collision family).
    */
  private val createPool: Vector[SnapPath] =
    Vector(p("a"), p("a/b"), p("b"), p("c/d"), p("c/d/e"), p("m"), p("m/n"))

  /** One generated step: the author, a bitmask choosing which existing results to join into the
    * base, an operation kind, a pick seed for content/path selection, and a replica-assignment pick
    * (used only by [[splitIntoReplicas]] — irrelevant to the patch itself, so it never influences
    * validity).
    */
  final case class StepSeed(authorPick: Int, baseMask: Int, kind: Int, pick: Int, replicaPick: Int)

  private val genStepSeed: Gen[StepSeed] =
    for
      authorPick <- Gen.choose(0, authors.size - 1)
      baseMask <- Gen.choose(0, 255)
      kind <- Gen.choose(0, 3)
      pick <- Gen.choose(0, 1000)
      replicaPick <- Gen.choose(0, 1000)
    yield StepSeed(authorPick, baseMask, kind, pick, replicaPick)

  val genSeeds: Gen[Vector[StepSeed]] =
    Gen.choose(4, 14).flatMap(n => Gen.listOfN(n, genStepSeed).map(_.toVector))

  /** Builds a valid multi-contributor causal graph from generated steps (see the class doc for why
    * every produced patch is valid by construction). Returns the patch vector in generation order
    * (NOT necessarily the sorted order [[snap.core.Repo.validate]] requires — callers sort via
    * [[sortedForValidate]]) together with the graph's frontier (the join of every patch's result).
    */
  def buildGraph(seeds: Vector[StepSeed]): (Vector[Patch], Version) =
    val built = seeds.foldLeft((Vector.empty[Patch], Vector.empty[Version])) {
      case ((patches, results), seed) =>
        val author = authors(seed.authorPick)
        val ownLast = patches.lastIndexWhere(_.author == author) match
          case -1 => Version.empty
          case k  => results(k)
        val base = results.indices.foldLeft(ownLast) { (acc, k) =>
          if ((seed.baseMask >> (k % 8)) & 1) == 1 then acc.join(results(k)) else acc
        }
        val baseTree = materializeVersion(patches, base)
        val change = chooseChange(seed, patches.size, baseTree)
        val patch = Patch
          .make(author, base.get(author) + 1L, base, "m", Vector(change))
          .fold(e => fail(s"generator produced an invalid patch: ${e.message}"), identity)
        val result = patch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
        (patches :+ patch, results :+ result)
    }
    val frontier = built._2.foldLeft(Version.empty)(_.join(_))
    (built._1, frontier)

  /** Materializes `version` against exactly its causal closure within `allPatches`, going only
    * through the public pipeline ([[snap.core.Repo.validate]] then
    * [[snap.core.Replay.materialize]]) — the closure filter mirrors `Replay.select`'s own
    * `revision <= version[author]` rule, which is what makes the filtered set exactly reachable
    * from `version` (so steps 1–4 always pass here by construction of the caller).
    */
  private def materializeVersion(allPatches: Vector[Patch], version: Version): Tree =
    val closure = allPatches.filter(pt => pt.revision <= version.get(pt.author))
    val valid = Repo
      .validate(Repository(version, sortedForValidate(closure)))
      .fold(e => fail(s"generator base failed structural validation: ${e.message}"), identity)
    Replay
      .materialize(valid, version)
      .fold(e => fail(s"generator base failed to materialize: ${e.message}"), _._1)

  /** The edit script for editing `old`'s tokens at a seed-picked position: mode 0 is a pure insert
    * of one brand-new, globally-unique line (the only mode available at `pos == old.size`, i.e.
    * `maxRemove == 0`, appending past the last token — nothing there to remove); mode 1 is a pure
    * delete of 1..`maxRemove` existing tokens; mode 2 replaces 1..`maxRemove` existing tokens with
    * one new unique line. Modes 1 and 2 are what make `Change.Text` scripts (and therefore the
    * aggregate context edit `Q = Diff.diff(B, C)` computed live during replay) sometimes contain a
    * `Delete` op and sometimes a genuine content replacement — see [[chooseChange]]'s call site.
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
        // seed-picked position (reviews/T18-review.md finding 1: an insertion-only generator can
        // never author a `Change.Text` script containing a `Delete` op, so neither R62's diff
        // tie-break nor OT's three delete-consuming rows are reachable by any property).
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

  /** `Repo.validate` requires patches sorted by `(author, revision)` in `Utf8Order` (SPEC §4.5 step
    * 2) — the on-disk canonical order, which a public caller must always supply, unlike
    * `snap.core`'s own tests bypassing it via the private `StructurallyValid` constructor.
    */
  def sortedForValidate(patches: Vector[Patch]): Vector[Patch] =
    patches.sortBy(patch => (patch.author.value, patch.revision))(
      Ordering.Tuple2(Utf8Order, Ordering.Long)
    )

  // --- replica splitting and core-level recombination (task brief: union the patch vectors, join
  // the frontiers, then Repo.validateFully — never the `merge` command) ---

  /** One replica's local state: the patches it holds and the join of their own result versions —
    * NOT necessarily a causally self-contained history on its own (a shard can hold a patch whose
    * declared base lives in a different shard; only the full recombination is required to be
    * complete). This mirrors a real replica that has recorded some patches without having synced
    * every peer it causally depends on yet.
    */
  final case class ReplicaState(patches: Vector[Patch], frontier: Version)

  /** Splits a causal graph into `replicaCount` (2 or 3) shards by a seed-controlled per-step
    * assignment — an arbitrary but deterministic partition of the patch vector, standing in for
    * "which replica originally authored/received this patch." The partition need not respect
    * per-author or per-branch boundaries; [[coreMerge]]'s recombination is required to reconstruct
    * the original graph regardless of how the split was drawn.
    */
  def splitIntoReplicas(
      patches: Vector[Patch],
      seeds: Vector[StepSeed],
      replicaCount: Int
  ): Vector[Vector[Patch]] =
    patches.zip(seeds).foldLeft(Vector.fill(replicaCount)(Vector.empty[Patch])) {
      case (shards, (patch, seed)) =>
        val idx = seed.replicaPick % replicaCount
        shards.updated(idx, shards(idx) :+ patch)
    }

  /** A replica's local frontier: the join of the result versions of exactly the patches it holds
    * (SPEC §3.3, R34). Joining every replica's local frontier back together reconstructs the full
    * graph's frontier because `join` is commutative, associative, and idempotent, and every patch
    * contributes its own result to exactly one shard (the split is a partition).
    */
  def replicaFrontier(shard: Vector[Patch]): Version =
    shard.foldLeft(Version.empty) { (acc, patch) =>
      acc.join(patch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity))
    }

  /** The core-level recombination primitive (task brief, explicitly not the `merge` command): union
    * the two replicas' patch vectors and join their frontiers. `.distinct` is defensive — a genuine
    * partition never actually duplicates a patch across shards, but two replicas that independently
    * hold the identical dot (same author/revision/content, e.g. after a prior recombination) must
    * not double-count it. `Version.join` is commutative and associative (SPEC §3.3, R34;
    * `VersionLawsSuite`), so chaining this operation over any association or order of any number of
    * replicas reaches the same frontier; the union of patch vectors is a plain set operation up to
    * `.distinct`, equally insensitive to combination order.
    */
  def coreMerge(a: ReplicaState, b: ReplicaState): ReplicaState =
    ReplicaState((a.patches ++ b.patches).distinct, a.frontier.join(b.frontier))

  /** Runs the full public validation pipeline (§4.5 steps 1–6: [[snap.core.Repo.validateFully]]) on
    * a replica state, sorting its patches into canonical order first (see [[sortedForValidate]]).
    */
  def validateState(state: ReplicaState): Either[SnapError, Repo.Valid] =
    Repo.validateFully(Repository(state.frontier, sortedForValidate(state.patches)))

  /** The four observables R109 and SPEC §6.5 require to be identical across recombination order:
    * the frontier, the patch set (as a canonically sorted vector — order-independent up to that
    * canonicalization), the materialized tree, and the warning set.
    */
  def snapshot(valid: Repo.Valid): (Version, Vector[Patch], Tree, SortedSet[snap.core.Warning]) =
    (
      valid.repository.frontier,
      sortedForValidate(valid.repository.patches),
      valid.tree,
      valid.warnings
    )

  // ============================================================================================
  // T18 second pass: merge-COMMAND-level generators (snap.cli.CommandsMergeConvergenceSuite).
  //
  // The generators above (buildGraph/splitIntoReplicas/coreMerge) deliberately do NOT guarantee a
  // shard is causally self-contained on its own (ReplicaState's own doc comment) — recombination
  // only has to work on the FULL union. Driving the real `merge` command needs more: `merge` loads
  // and fully validates EACH side independently before ever unioning them (SPEC §7.8/D11), so a
  // shard whose declared base lives only in the OTHER shard would fail R65's base-closure check
  // before any of the interesting behavior (R76 direction independence, R38 collision reporting)
  // is ever reached. The generators below build graphs shaped so every shard IS independently
  // valid by construction: one shared, already-closed seed history forking into serial,
  // single-author continuations that never reference each other's results.
  // ============================================================================================

  /** A serial (single-author, no internal concurrency) continuation of `startPatches`: `seeds.size`
    * sequential patches by `author` alone, each based on exactly the author's own previous result
    * (or `startFrontier` for the first step) — never joined with any other branch's results.
    * Because of that, `startPatches ++` the returned patches is ALWAYS causally self-contained
    * (R65) regardless of what other branches [[genNWay]] forks off the same `startPatches`, which
    * is exactly what lets a caller install it as a real, independently loadable on-disk repository
    * (mirrors `CommandsMergeSuite`'s hand-written `concurrentPair`, generated instead of
    * hand-picked). Also serial ⇒ zero internal concurrency ⇒ replaying `startPatches ++ result`
    * alone produces zero warnings (§6.2 rule 1 applies at every step, since `C` always equals `P`'s
    * own declared base) — a fact the properties below rely on to compute merge's R75 "new warnings"
    * set without a second replay.
    */
  private def buildSerialBranch(
      author: ContributorId,
      startFrontier: Version,
      startPatches: Vector[Patch],
      seeds: Vector[StepSeed]
  ): Vector[Patch] =
    seeds
      .foldLeft((Vector.empty[Patch], startFrontier)) { case ((branch, frontier), seed) =>
        val baseTree = materializeVersion(startPatches ++ branch, frontier)
        val change = chooseChange(seed, startPatches.size + branch.size, baseTree)
        val patch = Patch
          .make(author, frontier.get(author) + 1L, frontier, "m", Vector(change))
          .fold(e => fail(s"generator produced an invalid patch: ${e.message}"), identity)
        val result = patch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
        (branch :+ patch, result)
      }
      ._1

  /** One shared seed history forked into `branches.size` independently-authored serial
    * continuations (see [[buildSerialBranch]]). Every `seedPatches ++ branches(i)` is standalone
    * valid; `allPatches` is the fully recombined graph merging every branch together would reach.
    */
  final case class NWayCase(seedPatches: Vector[Patch], branches: Vector[Vector[Patch]]):
    def shardPatches(i: Int): Vector[Patch] = seedPatches ++ branches(i)
    def shardFrontier(i: Int): Version = replicaFrontier(shardPatches(i))
    def allPatches: Vector[Patch] = seedPatches ++ branches.flatten
    def allFrontier: Version = replicaFrontier(allPatches)

  /** Authors reserved for [[genNWay]] — disjoint from [[authors]] (which backs [[buildGraph]]) so
    * the two generator families never interact. Index 0 is the shared seed's sole author; indices
    * 1..3 are (up to) three branch authors, enough for the 2- or 3-replica case.
    */
  private val nWayAuthors: Vector[ContributorId] =
    Vector(id("nw-seed@x"), id("nw-a@x"), id("nw-b@x"), id("nw-c@x"))

  /** A shared-seed history (1-3 steps, one author) forked into `replicaCount` (2 or 3)
    * independently authored branches (2-5 steps each) — see [[NWayCase]]. Reuses the same
    * `chooseChange`/`createPool`-driven step machinery as [[buildGraph]], restricted to one author
    * per branch: two branches independently evolving the SAME small starting tree naturally produce
    * the full range of SPEC §6.4/§6.2 conflict shapes (concurrent edits, deletes, puts, and
    * namespace collisions between a path one branch creates and a prefix-related path the other
    * branch independently creates) without hand-coded per-reason fixtures — see the property
    * suite's own scaladoc for the measured coverage this reaches in practice.
    */
  def genNWay(replicaCount: Int): Gen[NWayCase] =
    if replicaCount < 2 || replicaCount > nWayAuthors.size - 1 then
      fail("genNWay: replicaCount must be 2 or 3")
    for
      seedSeeds <- Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, genStepSeed).map(_.toVector))
      branchSeedsList <- Gen.listOfN(
        replicaCount,
        Gen.choose(2, 5).flatMap(n => Gen.listOfN(n, genStepSeed).map(_.toVector))
      )
    yield
      val seedPatches = buildSerialBranch(nWayAuthors.head, Version.empty, Vector.empty, seedSeeds)
      val seedFrontier = replicaFrontier(seedPatches)
      val branches = branchSeedsList.zipWithIndex.toVector.map { case (seeds, i) =>
        buildSerialBranch(nWayAuthors(i + 1), seedFrontier, seedPatches, seeds)
      }
      NWayCase(seedPatches, branches)

  // --- multi-dot collision generator (reviews/T17-review.md finding 2) ---

  /** A single-line text create, tagged so two independently-generated patches for the same author
    * are trivially distinguishable by content (see [[genColliding]]).
    */
  private def taggedCreate(path: SnapPath, tag: String): Change =
    Change.Text(path, Diff.diff(Vector.empty, Vector(s"$tag\n")))

  /** Authors reserved for [[genColliding]] — disjoint from both [[authors]] and [[nWayAuthors]].
    * Plain ASCII, so `Utf8Order` agrees with natural string order: `"col-a@x" < "col-b@x" <
    * "col-c@x" < "col-d@x"`, letting a caller compute the expected smallest colliding dot directly
    * from which indices were chosen, without re-deriving the order.
    */
  private val collidingAuthorPool: Vector[ContributorId] =
    Vector(id("col-a@x"), id("col-b@x"), id("col-c@x"), id("col-d@x"))

  /** Side-exclusive filler dots (present on exactly one side, at every OTHER dot) — see
    * [[genColliding]]'s doc for why these are load-bearing, not decorative: without them, every dot
    * in [[collidingAuthorPool]] is present at the SAME vector index on both sides (same fixed pool,
    * sorted the same way), so [[CommandsMerge.unionPatches]]'s merge-join never has to ADVANCE one
    * side past a dot the other side lacks — the `byDot < 0`/`byDot > 0` branches, the ones an
    * ordering bug would actually corrupt, go completely unexercised, and the walk degenerates to
    * "compare index i to index i," which finds the same first divergence regardless of the
    * comparator's sign. `"aa-only@x"` sorts before the whole pool, `"col-bz- only@x"` sorts
    * strictly between `"col-b@x"` and `"col-c@x"`, and `"zz-only@x"` sorts after the whole pool —
    * chosen positions, not random ones, so every generated case exercises before/middle/after
    * interleaving on both sides regardless of which authors end up colliding.
    */
  private val leftOnlyFillers: Vector[ContributorId] = Vector(id("aa-only@x"), id("zz-only@x"))
  private val rightOnlyFillers: Vector[ContributorId] = Vector(id("col-bz-only@x"))

  /** Two independently-authored repositories sharing zero history: they collide (§3.5/R38) at every
    * dot in `collidingAuthors` — same author, same revision 1, DIFFERENT content on each side,
    * guaranteed different **by construction** (distinct literal tags, never left to
    * `chooseChange`'s randomness to happen to differ) — while agreeing exactly at every dot in
    * `agreeingAuthors` (the identical [[Patch]] value installed on both sides, so those dots never
    * trigger [[snap.core.SnapError.PatchCollision]]) and additionally diverge in SHAPE via
    * [[leftOnlyFillers]]/[[rightOnlyFillers]] (present, at revision 1, on only one side each) — see
    * that val's doc for why this is required for the property to have real falsifying power. All
    * three author groups are drawn from (or fixed relative to) [[collidingAuthorPool]], already
    * sorted by [[snap.core.ContributorId.ordering]] — the expected reported collision (the smallest
    * colliding dot, SPEC §3.5/R38) is always `collidingAuthors.min(ContributorId .ordering)`,
    * regardless of how every group interleaves in overall dot order.
    */
  final case class CollidingCase(
      agreeingAuthors: Vector[ContributorId],
      collidingAuthors: Vector[ContributorId],
      leftPatches: Vector[Patch],
      rightPatches: Vector[Patch]
  )

  /** `collidingCount` (2 or 3) authors from [[collidingAuthorPool]] collide; the pool's remaining
    * 1-2 authors agree — a mix, so the union scan has to walk past non-colliding dots before
    * finding the smallest colliding one, rather than every dot in the fixture being a collision —
    * plus the fixed [[leftOnlyFillers]]/[[rightOnlyFillers]] on top, so the merge-join genuinely
    * has to interleave-advance both sides around the collisions, not just compare same-author pairs
    * index-for-index (see [[leftOnlyFillers]]'s doc).
    */
  def genColliding(collidingCount: Int): Gen[CollidingCase] =
    if collidingCount < 2 || collidingCount >= collidingAuthorPool.size then
      fail("genColliding: collidingCount must leave at least one agreeing author")
    Gen.pick(collidingCount, collidingAuthorPool.indices).map { collidingIdx =>
      val collidingSet = collidingIdx.toSet
      val colliding = collidingAuthorPool.zipWithIndex.collect {
        case (a, i) if collidingSet(i) => a
      }
      val agreeing = collidingAuthorPool.zipWithIndex.collect {
        case (a, i) if !collidingSet(i) => a
      }
      def patchFor(author: ContributorId, path: SnapPath, tag: String): Patch =
        Patch
          .make(author, 1L, Version.empty, "m", Vector(taggedCreate(path, tag)))
          .fold(e => fail(s"generator produced an invalid patch: ${e.message}"), identity)
      val agreeingPatches =
        agreeing.zipWithIndex.map((author, i) => patchFor(author, p(s"agree-$i"), s"agree-$i"))
      val leftColliding =
        colliding.zipWithIndex.map((author, i) => patchFor(author, p(s"col-$i"), s"left-$i"))
      val rightColliding =
        colliding.zipWithIndex.map((author, i) => patchFor(author, p(s"col-$i"), s"right-$i"))
      val leftOnly =
        leftOnlyFillers.zipWithIndex.map((author, i) => patchFor(author, p(s"lo-$i"), s"lo-$i"))
      val rightOnly =
        rightOnlyFillers.zipWithIndex.map((author, i) => patchFor(author, p(s"ro-$i"), s"ro-$i"))
      CollidingCase(
        agreeing,
        colliding,
        sortedForValidate(agreeingPatches ++ leftColliding ++ leftOnly),
        sortedForValidate(agreeingPatches ++ rightColliding ++ rightOnly)
      )
    }

  /** 2 or 3 simultaneous colliding dots, randomized which authors collide (see [[genColliding]]).
    */
  val genCollidingCase: Gen[CollidingCase] = Gen.choose(2, 3).flatMap(genColliding)

package snap.props

import org.scalacheck.Gen
import snap.core.Change
import snap.core.ContributorId
import snap.core.Diff
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

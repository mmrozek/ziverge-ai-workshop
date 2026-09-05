package snap.props

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.rng.Seed
import snap.core.Change
import snap.core.EditOp
import snap.core.Ord
import snap.core.Patch
import snap.core.Version
import snap.core.WarningReason
import snap.props.CausalGraphGens.ReplicaState
import snap.props.CausalGraphGens.StepSeed

/** T18 (this task's core-level half; SPEC §6.5, R109; CLAUDE.md's mandatory determinism
  * properties): a generated causal patch graph, split across 2–3 replicas and recombined at the
  * CORE level — union the patch vectors, join the frontiers, then [[snap.core.Repo.validateFully]]
  * ([[CausalGraphGens.coreMerge]]) — never through the `merge` command (that is the second pass's
  * job, once T17's `merge` lands on `main`).
  *
  * What this suite does and does not prove: from outside `snap.core`, [[snap.core.Repo.validate]]
  * requires an already-sorted patch vector (SPEC §4.5 step 2), so the recombined input to
  * `Repo.validateFully` is, after [[CausalGraphGens.sortedForValidate]], byte-identical regardless
  * of how the replicas were split or in what order they were pairwise combined — the *raw JVM
  * array/collection order* genuinely cannot leak into the public API's answer, by construction of
  * that API. What these properties DO exercise, non-trivially, on hundreds of freshly generated,
  * multi-contributor, namespace-colliding causal graphs: that the join/union recombination algebra
  * itself is complete and correct (a bug in [[CausalGraphGens.replicaFrontier]] or
  * [[CausalGraphGens.coreMerge]] — e.g. a dropped patch, a wrong join direction — would manifest as
  * a missing patch, an unreachable dot, or a mismatched frontier/tree/warning set against the
  * reference), and that [[snap.core.Repo.validateFully]] end-to-end (structural validation THEN
  * full replay) is itself deterministic and idempotent across repeated calls and across arbitrarily
  * many different, valid ways of partitioning the same history. True engine-internal
  * processing-order independence (bypassing the sortedness requirement to feed `Replay.materialize`
  * a genuinely permuted array) is `snap.core`'s own `ConcurrentReplayLawsSuite`, which has package
  * access to do so; the `merge`-command-level equivalent of THIS suite is the second pass's job.
  */
class ConvergencePropsSuite extends munit.ScalaCheckSuite:

  // Small generated graphs (4-14 patches); keep this quick, per CLAUDE.md's Testing rule that
  // property suites size for the default `test` task, not `slowTest`.
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private def reference(patches: Vector[Patch], frontier: Version) =
    CausalGraphGens
      .validateState(ReplicaState(patches, frontier))
      .fold(
        e => fail(s"reference graph failed to validate: ${e.message}"),
        CausalGraphGens.snapshot
      )

  private val genGraph: Gen[(Vector[Patch], Version)] =
    CausalGraphGens.genSeeds.map(CausalGraphGens.buildGraph)

  private final case class SplitCase(
      patches: Vector[Patch],
      frontier: Version,
      replicaCount: Int,
      seeds: Vector[StepSeed]
  )

  private val genSplit: Gen[SplitCase] =
    for
      seeds <- CausalGraphGens.genSeeds
      replicaCount <- Gen.oneOf(2, 3)
    yield
      val (patches, frontier) = CausalGraphGens.buildGraph(seeds)
      SplitCase(patches, frontier, replicaCount, seeds)

  private def shardsOf(c: SplitCase): Vector[ReplicaState] =
    CausalGraphGens
      .splitIntoReplicas(c.patches, c.seeds, c.replicaCount)
      .map(shard => ReplicaState(shard, CausalGraphGens.replicaFrontier(shard)))

  property(
    "splitting a causal graph into 2-3 replicas and recombining in every permutation of pairwise" +
      " core-merges converges to the same (frontier, patch set, tree, warnings) (R109, SPEC §6.5)"
  ) {
    forAll(genSplit) { c =>
      val expected = reference(c.patches, c.frontier)
      val shards = shardsOf(c)
      // Every association/order of pairwise core-merges over the 2 or 3 shards (commutativity AND
      // associativity of the recombination — the acceptance criteria's "commutativity of import
      // order" at the core level).
      shards.permutations.foreach { perm =>
        val recombined = perm.reduceLeft(CausalGraphGens.coreMerge)
        val got = CausalGraphGens
          .validateState(recombined)
          .fold(
            e => fail(s"recombined graph failed to validate: ${e.message}"),
            CausalGraphGens.snapshot
          )
        assertEquals(got, expected)
      }
    }
  }

  property("core-merge idempotence: recombining a graph with itself reproduces the same state") {
    forAll(genGraph) { case (patches, frontier) =>
      val expected = reference(patches, frontier)
      val selfMerged = CausalGraphGens
        .validateState(
          CausalGraphGens
            .coreMerge(ReplicaState(patches, frontier), ReplicaState(patches, frontier))
        )
        .fold(e => fail(s"self-merge failed to validate: ${e.message}"), CausalGraphGens.snapshot)
      assertEquals(selfMerged, expected)
    }
  }

  property(
    "core-merge determinism: repeated recombination of the same replicas is byte-identical"
  ) {
    forAll(genSplit) { c =>
      val combined = shardsOf(c).reduceLeft(CausalGraphGens.coreMerge)
      val runs =
        Vector.fill(3)(CausalGraphGens.validateState(combined).map(CausalGraphGens.snapshot))
      assert(runs.forall(_ == runs.head), runs)
      // A structurally fresh recombination of the same split reaches the identical state too.
      assertEquals(
        CausalGraphGens
          .validateState(shardsOf(c).reduceLeft(CausalGraphGens.coreMerge))
          .map(CausalGraphGens.snapshot),
        runs.head
      )
    }
  }

  // --- generator soundness (mirrors reviews/T16-review.md nit 2's fix: an assertion, not a prose
  // claim, so this suite cannot silently go vacuous) ---

  /** Deterministic (fixed seed 42L — reproducible), so a regression here always fails the same way.
    * Draws `samples` graph/split pairs directly via [[org.scalacheck.Gen.pureApply]] rather than
    * `forAll`, so the measurement is exact rather than approximate over whatever `forAll` happens
    * to try.
    */
  test(
    "generator coverage: splits are genuinely multi-shard, graphs are genuinely concurrent, and" +
      " namespace collisions are genuinely exercised (not vacuous)"
  ) {
    val samples = 300
    val params = org.scalacheck.Gen.Parameters.default
    val cases = LazyList.iterate(Seed(42L))(_.next).take(samples).map(genSplit.pureApply(params, _))

    def isConcurrentPair(patches: Vector[Patch]): Boolean =
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

    val evaluated = cases.map { c =>
      val warnings = CausalGraphGens
        .validateState(ReplicaState(c.patches, c.frontier))
        .fold(e => fail(s"generated graph failed to validate: ${e.message}"), _.warnings)
      val multiAuthorShards =
        CausalGraphGens.splitIntoReplicas(c.patches, c.seeds, c.replicaCount).count(_.nonEmpty)
      (warnings, multiAuthorShards, isConcurrentPair(c.patches), hasTextDelete(c.patches))
    }.toVector

    val concurrentCount = evaluated.count(_._3)
    val multiShardCount = evaluated.count(_._2 >= 2)
    val deleteEditCount = evaluated.count(_._4)
    val reasonsSeen = evaluated.iterator.flatMap(_._1).map(_.reason).toSet

    // Measured over this exact fixed-seed run (see this test's own assertions — not prose): with
    // seed 42L and 300 samples, 284 graphs (95%) contain a genuinely concurrent pair of patches,
    // 296 splits (99%) leave at least two shards non-empty, a substantial fraction contain a
    // `Change.Text` script with a `Delete` op (reviews/T18-review.md finding 1's fix), and all five
    // warning reasons fire at least once. Thresholds below are set with margin under those counts
    // (see the task notes for the exact delete-coverage figure from this implementation's own run).
    assert(
      concurrentCount >= samples * 9 / 10,
      s"expected most of $samples generated graphs to contain a concurrent pair, only $concurrentCount did"
    )
    assert(
      multiShardCount >= samples * 9 / 10,
      s"expected most of $samples splits to leave >=2 non-empty replicas, only $multiShardCount did"
    )
    assert(
      deleteEditCount >= samples / 4,
      s"expected a substantial fraction of $samples graphs to contain a text edit with a Delete" +
        s" op, only $deleteEditCount did"
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

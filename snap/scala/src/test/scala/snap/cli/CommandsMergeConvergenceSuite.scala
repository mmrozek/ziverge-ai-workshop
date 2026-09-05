package snap.cli

import org.scalacheck.Gen
import snap.core.ContributorId
import snap.core.Patch
import snap.core.Repo
import snap.core.Repository
import snap.core.Tree
import snap.core.WarningReason
import snap.fs.Materialize
import snap.fs.Store
import snap.props.CausalGraphGens

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/** T18 second pass (`reviews/T17-review.md` findings 1-2; SPEC §6.5/R76, §3.5/R38, §7.8): the
  * `merge`-COMMAND-level half of the convergence property suite, driving real on-disk repositories
  * through [[Cli.run]] — the actual filesystem scan, install, and metadata write on top of the
  * algebra [[snap.props.ConvergencePropsSuite]] already proved at the core level.
  *
  * '''What these properties can and cannot falsify''' (stated plainly per the task brief, not
  * asserted as reassurance): [[snap.core.Repo.validate]] requires an already-sorted patch vector,
  * and [[CommandsMerge.unionPatches]]'s output is canonically sorted too — so, exactly as
  * `ConvergencePropsSuite`'s own scaladoc says of the core-level suite, NONE of the properties here
  * can catch a bug where the REPLAY ENGINE itself internally depends on array/collection processing
  * order (that class of bug needs `snap.core`'s own package-private `ConcurrentReplayLawsSuite`,
  * which can hand `Replay.materialize` a genuinely permuted array). What driving the real command
  * DOES add over the core-level suite, and can genuinely fail on:
  *
  *   - a bug in [[WorkTree]]'s scan, [[Materialize]]'s install (the four-step remove/create/write/
  *     prune sequence, which visits a DIFFERENT sequence of intermediate filesystem states
  *     depending on which side is "local" and which merge chain produced the current tree), or
  *     [[Store]]'s atomic write, that happens to depend on the CURRENT on-disk tree shape rather
  *     than only on the target — i.e. installer/CLI-plumbing path-independence, not engine-internal
  *     processing-order independence;
  *   - an accidental dependency on which side's operand is "local" vs "remote" anywhere in
  *     [[CommandsMerge]]'s composition (R76) that the committed fixture-based
  *     [[CommandsMergeSuite]] doesn't exercise for four of the five §6.4 reasons (Finding #1) or
  *     for more than one simultaneous colliding dot (Finding #2).
  *
  * Every generated case is independently self-contained on each side by construction (see
  * [[CausalGraphGens.NWayCase]]/[[CausalGraphGens.CollidingCase]]'s doc comments) — required
  * because `merge` fully validates each side BEFORE unioning them (D11), unlike
  * `ConvergencePropsSuite`'s shards, which are only ever combined as a whole.
  *
  * Cost (CLAUDE.md Testing: CLI-touching properties are "tens of cases, not hundreds"): the
  * expensive, disk-touching step (an actual [[Cli.run]] `merge` invocation) is applied to at most a
  * few dozen cases per property; coverage MEASUREMENT (which of the five §6.4 reasons a generated
  * pair produces) is done first at the cheap core level (hundreds of samples, no I/O), and only one
  * witness per reason is then driven through the real command.
  */
class CommandsMergeConvergenceSuite extends munit.FunSuite:

  // ------------------------------------------------------------------------------- test plumbing

  private def runMerge(localRoot: Path, remoteOperand: String): (Int, String, String) =
    val fx = TestEnv(cwd = localRoot)
    val exit = Cli.run(fx.env, List("merge", remoteOperand))
    (exit, fx.stdout, fx.stderr)

  /** Installs a generated, standalone-valid patch vector as a real on-disk repository: `.snap/
    * repository.json` plus every working file the frontier materializes to — the moral equivalent
    * of `init` followed by enough `commit`s to reach this exact state, skipping the intermediate
    * steps since `merge` only ever looks at the current on-disk state.
    */
  private def materializeOnDisk(root: Path, patches: Vector[Patch]): Repo.Valid =
    val frontier = CausalGraphGens.replicaFrontier(patches)
    val sorted = CausalGraphGens.sortedForValidate(patches)
    val valid = Repo
      .validateFully(Repository(frontier, sorted))
      .fold(e => fail(s"generated repository failed to validate: ${e.message}"), identity)
    Files.createDirectories(root)
    Files.createDirectories(root.resolve(".snap"))
    Materialize
      .install(root, Tree.empty, valid.tree)
      .fold(e => fail(s"failed to install generated working tree: ${e.message}"), identity)
    Store
      .writeRepository(root.resolve(".snap").resolve(Store.RepositoryFileName), valid.repository)
      .fold(e => fail(s"failed to write generated repository.json: ${e.message}"), identity)
    valid

  private def repoBytes(root: Path): Vector[Byte] =
    Files.readAllBytes(root.resolve(".snap").resolve(Store.RepositoryFileName)).toVector

  /** Every tracked working file (outside `.snap/`) as relative path -> bytes (mirrors
    * `CommandsMergeSuite`'s own `workingFiles`, duplicated rather than shared so this suite never
    * depends on the internals of an already-reviewed, committed test file).
    */
  private def workingFiles(root: Path): Map[String, Vector[Byte]] =
    val rels = Using.resource(Files.walk(root)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(pth => Files.isRegularFile(pth))
        .map(pth => root.relativize(pth).toString)
        .filterNot(rel => rel == ".snap" || rel.startsWith(".snap/"))
        .toVector
    }
    rels.map(rel => rel -> Files.readAllBytes(root.resolve(rel)).toVector).toMap

  private def copyRepo(from: Path): Path =
    val to = Files.createTempDirectory("snap-t18-copy")
    val entries = Using.resource(Files.walk(from))(_.iterator().asScala.toVector)
    entries.foreach { src =>
      val dest = to.resolve(from.relativize(src).toString)
      if Files.isDirectory(src) then Files.createDirectories(dest) else Files.copy(src, dest)
      ()
    }
    to

  /** Deepest-first delete — `Files.walk` visits parents before children, so reversing its order
    * deletes every child before the directory that contains it. Every test that creates a temp
    * directory cleans it up via this, in a `finally`, per CLAUDE.md's Testing rule.
    */
  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val ordered = Using.resource(Files.walk(root))(_.iterator().asScala.toVector)
      ordered.reverseIterator.foreach(Files.delete)

  // -------------------------------------------------- property 1: direction independence (finding 1)

  /** Reviews/T17-review.md finding 1: R76 direction independence, generated across all five §6.4
    * warning reasons (delete-wins, later-create-wins, later-put-wins, namespace-wins, put-wins),
    * not just the two the committed fixture exercises.
    *
    * Both shards of a [[CausalGraphGens.NWayCase]] are a single serial branch off a shared seed
    * (see that class's doc comment), so each shard replayed ALONE produces zero warnings — meaning
    * merge's R75 "new warnings" set (`mergedValid.warnings -- local.warnings`) always equals the
    * FULL merged warnings set, on both sides, regardless of which shard is "local". That equality
    * is exactly what makes this generator shape usable for a DIRECTION-independence check without
    * needing to separately reconstruct each side's own pre-merge baseline.
    */
  test(
    "merge command: direction independence holds across all five warning reasons," +
      " measured then witnessed (reviews/T17-review.md finding 1)"
  ) {
    val sampleSize = 200
    val samples = Gen
      .listOfN(sampleSize, CausalGraphGens.genNWay(2))
      .sample
      .getOrElse(fail("generator sampling failed to produce a value"))

    def reasonsOf(c: CausalGraphGens.NWayCase): Set[WarningReason] =
      CausalGraphGens
        .validateState(CausalGraphGens.ReplicaState(c.allPatches, c.allFrontier))
        .fold(
          e => fail(s"generated pair failed to validate: ${e.message}"),
          _.warnings.iterator.map(_.reason).toSet
        )

    val withReasons = samples.map(c => (c, reasonsOf(c)))
    val allReasons = WarningReason.values.toSet
    val counts =
      WarningReason.values.toVector.map(r => r.text -> withReasons.count(_._2.contains(r)))
    val union = withReasons.iterator.flatMap(_._2).toSet
    assertEquals(
      union,
      allReasons,
      s"expected all five §6.4 reasons across $sampleSize generated shard pairs; per-reason counts" +
        s" (case may count toward more than one reason): $counts"
    )

    // One witness per reason (first occurrence in generation order); a case producing more than
    // one reason serves as a witness for each, keeping the number of actual CLI merges small.
    val witnessCases = WarningReason.values.toVector
      .map(r => withReasons.collectFirst { case (c, reasons) if reasons.contains(r) => c }.get)
      .distinct

    witnessCases.foreach { c =>
      val expectedReasons = reasonsOf(c)
      val leftA = Files.createTempDirectory("snap-t18-r76-fwd-local")
      val rightA = Files.createTempDirectory("snap-t18-r76-fwd-remote")
      val leftB = Files.createTempDirectory("snap-t18-r76-bwd-remote")
      val rightB = Files.createTempDirectory("snap-t18-r76-bwd-local")
      try
        materializeOnDisk(leftA, c.shardPatches(0))
        materializeOnDisk(rightA, c.shardPatches(1))
        materializeOnDisk(leftB, c.shardPatches(0))
        materializeOnDisk(rightB, c.shardPatches(1))

        val forward = runMerge(leftA, rightA.toString) // A local, B remote
        val backward = runMerge(rightB, leftB.toString) // B local, A remote

        assertEquals(forward._1, 0, s"forward merge failed: ${forward._3}")
        assertEquals(backward._1, 0, s"backward merge failed: ${backward._3}")
        assertEquals(forward._2, backward._2, "stdout (joined version) must match")
        assertEquals(forward._3, backward._3, "stderr (warning lines) must match")
        assertEquals(workingFiles(leftA), workingFiles(rightB), "working trees must match")
        assertEquals(repoBytes(leftA), repoBytes(rightB), "repository.json bytes must match")

        expectedReasons.foreach { r =>
          assert(
            forward._3.contains(r.text),
            s"expected reason '${r.text}' in stderr: ${forward._3}"
          )
        }
      finally
        deleteRecursively(leftA)
        deleteRecursively(rightA)
        deleteRecursively(leftB)
        deleteRecursively(rightB)
    }
  }

  // -------------------------------------------------- property 2: multi-dot collision (finding 2)

  /** Reviews/T17-review.md finding 2: the reported collision is the smallest colliding dot in dot
    * order, identically in both directions, even with SEVERAL simultaneous colliding dots (and a
    * non-colliding dot the scan must walk past first) — not just the single-collision fixture the
    * committed suite pins.
    */
  test(
    "merge command: with several simultaneous colliding dots, the smallest is reported," +
      " both directions (reviews/T17-review.md finding 2)"
  ) {
    val cases =
      Gen.listOfN(12, CausalGraphGens.genCollidingCase).sample.getOrElse(fail("sampling failed"))
    cases.foreach { c =>
      val expectedAuthor = c.collidingAuthors.min(ContributorId.ordering)
      val expectedLine = s"snap: patch collision: ${expectedAuthor.value} revision 1\n"

      val local = Files.createTempDirectory("snap-t18-col-fwd-local")
      val remote = Files.createTempDirectory("snap-t18-col-fwd-remote")
      val localB = Files.createTempDirectory("snap-t18-col-bwd-local")
      val remoteB = Files.createTempDirectory("snap-t18-col-bwd-remote")
      try
        materializeOnDisk(local, c.leftPatches)
        materializeOnDisk(remote, c.rightPatches)
        materializeOnDisk(localB, c.rightPatches) // backward: local <- right, remote = left
        materializeOnDisk(remoteB, c.leftPatches)
        val localBefore = repoBytes(local)
        val remoteBefore = repoBytes(remote)
        val localBBefore = repoBytes(localB)
        val remoteBBefore = repoBytes(remoteB)

        val forward = runMerge(local, remote.toString)
        val backward = runMerge(localB, remoteB.toString)

        assertEquals(forward, (1, "", expectedLine))
        assertEquals(backward, (1, "", expectedLine))
        // R103: a rejected merge mutates neither side.
        assertEquals(repoBytes(local), localBefore)
        assertEquals(repoBytes(remote), remoteBefore)
        assertEquals(repoBytes(localB), localBBefore)
        assertEquals(repoBytes(remoteB), remoteBBefore)
      finally
        deleteRecursively(local)
        deleteRecursively(remote)
        deleteRecursively(localB)
        deleteRecursively(remoteB)
    }
  }

  // ------------------------------------------------- property 3a: merge(R,R) idempotence via CLI

  /** `merge(R, R)` through the real command leaves the repository byte-identical with empty stderr
    * (SPEC §7.8's "no-op" guarantee) — checked both against a byte-identical independent copy AND
    * against the literal same path (`merge .`), over generated full multi-author graphs (reusing
    * [[CausalGraphGens.genSeeds]]/[[CausalGraphGens.buildGraph]] — the general-purpose generator,
    * not the two-sided one, since this property needs only ONE standalone-valid repo).
    */
  test("merge command: merge(R, R) is byte-identical with empty stderr, copy and self-path") {
    val cases = Gen
      .listOfN(12, CausalGraphGens.genSeeds.map(CausalGraphGens.buildGraph))
      .sample
      .getOrElse(fail("sampling failed"))
    cases.foreach { case (patches, _) =>
      val root = Files.createTempDirectory("snap-t18-idem-root")
      try
        val valid = materializeOnDisk(root, patches)
        val expectedStdout = s"${valid.repository.frontier.canonicalText}\n"
        val before = repoBytes(root)
        val filesBefore = workingFiles(root)

        val copy = copyRepo(root)
        try
          assertEquals(runMerge(root, copy.toString), (0, expectedStdout, ""))
          assertEquals(repoBytes(root), before)
          assertEquals(workingFiles(root), filesBefore)
        finally deleteRecursively(copy)

        assertEquals(runMerge(root, "."), (0, expectedStdout, ""))
        assertEquals(repoBytes(root), before)
        assertEquals(workingFiles(root), filesBefore)
      finally deleteRecursively(root)
    }
  }

  // ------------------------------------- property 3b: import-order commutativity through the CLI

  /** For a graph split across 2-3 replicas (see [[CausalGraphGens.NWayCase]]), every permutation of
    * pairwise CLI merges reaches the same final `(repository.json bytes, working tree, frontier,
    * warnings)` — the acceptance criterion's "commutativity of import order," proved again one
    * layer further out than `ConvergencePropsSuite`'s core-level version of the same property. As
    * that suite's own scaladoc notes (and this class's header repeats): the recombined,
    * canonically-sorted input to `Repo.validateFully` is the same regardless of merge-chain order
    * by construction, so this does NOT newly prove engine-internal processing-order independence.
    * What IS newly exercised here: every permutation drives the chain's INTERMEDIATE on-disk state
    * through a different sequence of installs (a 3-way chain's first merge produces a different
    * intermediate tree depending which two shards combine first), so a bug where
    * `Materialize.install`/`Store.writeRepository` depended on that intermediate shape rather than
    * only on the final target would surface here as a permutation-dependent final byte or file.
    */
  test(
    "merge command: every permutation of pairwise merges over 2-3 replicas reaches the same" +
      " final state"
  ) {
    def checkReplicaCount(replicaCount: Int, sampleCount: Int): Unit =
      val cases = Gen
        .listOfN(sampleCount, CausalGraphGens.genNWay(replicaCount))
        .sample
        .getOrElse(fail("sampling failed"))
      cases.foreach { c =>
        val shardRoots =
          (0 until replicaCount)
            .map(i => Files.createTempDirectory(s"snap-t18-perm-shard$i"))
            .toVector
        try
          shardRoots.zipWithIndex.foreach { case (root, i) =>
            materializeOnDisk(root, c.shardPatches(i))
          }
          val reference = Repo
            .validateFully(
              Repository(c.allFrontier, CausalGraphGens.sortedForValidate(c.allPatches))
            )
            .fold(e => fail(s"reference graph failed to validate: ${e.message}"), identity)

          val observed = (0 until replicaCount).permutations.map { order =>
            // Fresh copies per permutation: the chain mutates its target in place, so every
            // ordering has to start from the same pristine per-shard state.
            val workingCopies = shardRoots.map(copyRepo)
            try
              val target = workingCopies(order.head)
              order.tail.foreach { i =>
                val r = runMerge(target, workingCopies(i).toString)
                assertEquals(r._1, 0, s"merge failed mid-chain (order=$order): ${r._3}")
              }
              val finalValid = Store
                .readRepository(target.resolve(".snap").resolve(Store.RepositoryFileName))
                .fold(e => fail(s"final repository failed to re-validate: ${e.message}"), identity)
              (
                repoBytes(target),
                workingFiles(target),
                finalValid.repository.frontier,
                finalValid.warnings
              )
            finally workingCopies.foreach(deleteRecursively)
          }.toVector

          assert(
            observed.forall(_ == observed.head),
            s"permutations diverged for a $replicaCount-replica case"
          )
          assertEquals(observed.head._3, reference.repository.frontier)
          assertEquals(observed.head._4, reference.warnings)
        finally shardRoots.foreach(deleteRecursively)
      }

    checkReplicaCount(2, 6)
    checkReplicaCount(3, 3)
  }

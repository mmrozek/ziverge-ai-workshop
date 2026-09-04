package snap.core

import munit.FunSuite

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.Duration

/** Stack-safety regression probe for [[Replay]] (phase-1 review finding CR1, stack half): the
  * T07-era ready-loop chained its steps through `Either#flatMap`, so the recursive call to `loop`
  * was never in tail position — a valid, spec-conformant history of ~1k+ patches crashed the JVM
  * with `StackOverflowError` instead of producing a typed result. `Replay.loop` is now `@tailrec`
  * (see its scaladoc), but the ready-loop's `materializeMemo` sub-replays for base materialization
  * are a second, independent call path into the same method, so both shapes below are pinned as
  * regression tests: a deep LINEAR history (worst case for sub-replay depth before the fix — each
  * patch's base is a version never memoized under its own key by the outer loop, so materializing
  * it re-walked the entire prefix from scratch) and a deep CONCURRENT history (repeated two-way
  * diamonds forcing real per-path conflict resolution, R73 rule 5, at every generation). Confirmed
  * to `StackOverflowError` before the `@tailrec` fix at these same depths; both pass now.
  *
  * `*SlowSuite`: replay is Θ(n²) in patch count (D19/phase-1 review PR5, an accepted, T23-deferred
  * trade-off, not a stack-safety concern — correctness first), so these depths take real wall-clock
  * time despite being O(1) in STACK. Excluded from the default `sbt test` run (`build.sbt`'s
  * `Test / testOptions` filter) and run instead via `sbt slowTest` — a phase-gate check, not
  * per-task material.
  */
class ReplayStackSafetySlowSuite extends FunSuite:

  // munit's default per-test timeout (30s) is too short for the Θ(n²) depths this suite runs at
  // deliberately (see the class doc); this suite is already opted out of the default `test` run
  // and only ever invoked explicitly (`sbt slowTest`), so a generous fixed ceiling is the right
  // trade-off over open-ended hangs.
  override val munitTimeout: Duration = Duration("5min")

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

  private def put(path: String, content: String): Change = Change.Put(p(path), utf8(content))

  private def patch(author: String, revision: Long, base: Version, changes: Change*): Patch =
    Patch
      .make(id(author), revision, base, "m", changes.toVector)
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)

  /** §4.5 step 2's declared file order: patches sorted by (author, revision) — Utf8Order on the
    * author text, then numeric revision. `deepConcurrentHistory`'s two-author diamonds are built in
    * causal (interleaved) order for readability, so the sort happens once here rather than in every
    * fixture.
    */
  private def sortedForFile(patches: Vector[Patch]): Vector[Patch] =
    patches.sortBy(p => (p.author.value, p.revision))(Ordering.Tuple2(Utf8Order, Ordering.Long))

  private def validated(frontier: Version, patches: Vector[Patch]): Repo.StructurallyValid =
    Repo
      .validate(Repository(frontier, sortedForFile(patches)))
      .fold(e => fail(s"expected structurally valid history: ${e.message}"), identity)

  /** A single-author chain of `count` patches, each creating one new distinct path and depending on
    * exactly its immediate predecessor. The worst case for [[Replay]]'s sub-replay recursion: the
    * outer ready-loop never memoizes a version under its own key (only `materializeMemo`'s
    * cache-miss branch does), so materializing patch `k`'s base `v(a->(k-1))` re-walks patches
    * `1..k-1` from scratch every time — before the `@tailrec` fix, a nested, equally
    * non-tail-recursive loop stacked on top of the outer one's still-pending frames; now O(1) stack
    * either way.
    */
  private def deepLinearHistory(count: Int): (Repo.StructurallyValid, Version) =
    val author = "a@x"
    val patches = (1 to count).iterator.map { i =>
      val base = if i == 1 then Version.empty else v(author -> (i - 1).toLong)
      patch(author, i.toLong, base, put(s"f$i", s"v$i\n"))
    }.toVector
    val frontier = v(author -> count.toLong)
    (validated(frontier, patches), frontier)

  /** `generations` two-way diamonds on one shared, contended path: each generation's two sibling
    * patches (authors `a@x`/`b@x`) share the SAME declared base (genuinely concurrent — neither is
    * an ancestor of the other) and both `put` the one path `shared.txt` with distinct bytes, so
    * every generation forces a real R73 rule-5 (`later-put-wins`) resolution, not just disjoint
    * creates. The next generation's shared base is the join of both siblings' results, so the
    * history is one long causal chain of diamonds — `2 * generations + 1` total patches.
    */
  private def deepConcurrentHistory(generations: Int): (Repo.StructurallyValid, Version) =
    val root = patch("a@x", 1L, Version.empty, put("shared.txt", "gen0\n"))
    val (patches, finalA, finalB) =
      (1 to generations).foldLeft((Vector(root), 1L, 0L)) { case ((acc, prevA, prevB), gen) =>
        val base = if prevB == 0L then v("a@x" -> prevA) else v("a@x" -> prevA, "b@x" -> prevB)
        val left = patch("a@x", prevA + 1L, base, put("shared.txt", s"a$gen\n"))
        val right = patch("b@x", prevB + 1L, base, put("shared.txt", s"b$gen\n"))
        (acc :+ left :+ right, prevA + 1L, prevB + 1L)
      }
    val frontier = v("a@x" -> finalA, "b@x" -> finalB)
    (validated(frontier, patches), frontier)

  // Depths chosen to complete in about a minute under Θ(n²) replay (D19/PR5) while staying
  // comfortably past the ~1k-patch threshold the phase-1 review measured for the pre-fix crash —
  // not "at least 5000" (that figure undercounted the accepted quadratic cost as a hang risk
  // rather than a CPU-time one; orchestrator correction, this integration).

  test("a deep linear history of 1500 patches replays without StackOverflowError") {
    val count = 1500
    val (valid, frontier) = deepLinearHistory(count)
    val result = Replay.materialize(valid, frontier)
    assert(result.isRight, s"expected successful materialization, got $result")
    val Right((tree, warnings)) = result: @unchecked
    assertEquals(tree.paths.size, count)
    assertEquals(warnings, scala.collection.immutable.SortedSet.empty[Warning])
  }

  test(
    "a deep concurrent history of 1501 patches (750 diamonds) replays without" +
      " StackOverflowError"
  ) {
    val generations = 750
    val (valid, frontier) = deepConcurrentHistory(generations)
    assertEquals(valid.repository.patches.size, 2 * generations + 1)
    val result = Replay.materialize(valid, frontier)
    assert(result.isRight, s"expected successful materialization, got $result")
    val Right((tree, warnings)) = result: @unchecked
    // Every generation collides on the same single path; the (path, reason) pair collapses to one
    // element in the sorted set (R74) regardless of how many generations triggered it.
    assertEquals(warnings, SortedSetOf(Warning(p("shared.txt"), WarningReason.LaterPutWins)))
    assertEquals(tree.get(p("shared.txt")).map(_.length > 0), Some(true))
  }

  /** `SortedSet(...)` needs the `Warning` ordering in scope at the call site; a tiny named helper
    * reads better than importing `scala.collection.immutable.SortedSet` alongside the `Warning`
    * companion's `given` at every call site in this file.
    */
  private def SortedSetOf(warnings: Warning*): scala.collection.immutable.SortedSet[Warning] =
    scala.collection.immutable.SortedSet(warnings*)

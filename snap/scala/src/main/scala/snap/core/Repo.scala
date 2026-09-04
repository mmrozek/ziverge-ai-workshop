package snap.core

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

/** The complete repository value (SPEC §4.1, R40): the declared frontier and the patch list in file
  * order. The `format` marker is not stored — the codec accepts only `1` and always writes `1`.
  * Equality is structural over the parsed typed values (R41/R47): two repositories decoded from
  * differently formatted JSON compare equal.
  */
final case class Repository(frontier: Version, patches: Vector[Patch])

object Repository:
  /** `snap init`'s value (R1/R17): empty frontier, no patches. */
  val empty: Repository = Repository(Version.empty, Vector.empty)

/** Structural repository validation — SPEC §4.5 steps 1–4 (R44, R46, R59, R60).
  *
  * Step 1's value rules are enforced upstream: the JSON codec (exact schema, R43) and the
  * validating factories (`ContributorId`, `Version`, `SnapPath`, `EditScript`, [[Patch.make]]) make
  * a [[Repository]] value carry them by construction. This object owns steps 2–4:
  *
  *   - patch sorting, one value per dot, contiguous per-contributor revisions (step 2);
  *   - complete base closure and `revision = base[author] + 1` (step 3);
  *   - acyclic causality (step 4);
  *   - `patches` = exactly the causal closure of `frontier`, no unreachable patches (R44).
  *
  * Steps 5–6 (every change against its materialized exact base; deterministic replay of the
  * frontier) live in [[Replay]] and consume the returned [[Repo.StructurallyValid]] proof value —
  * the typed hook that makes skipping them impossible to overlook. [[Repo.validateFully]] composes
  * all six steps and is what the read pipeline ([[snap.fs.Store]]) calls.
  *
  * Pure: no filesystem access, no mutation (R103); iteration only over the file-ordered patch
  * vector and canonically ordered version entries, so the reported error is a deterministic
  * function of the input.
  */
object Repo:

  /** Proof that §4.5 steps 1–4 passed. `results(i)` is `patches(i)`'s result version (R46),
    * precomputed so the replay (steps 5–6, ready-loop ordering by Snap order of results) never
    * recomputes or re-validates it.
    *
    * The constructor is `private[core]` (T16, reviews/T07-review.md nit 2): outside `snap.core` the
    * proof is unforgeable — [[validate]] is the only producer — while core-package tests keep
    * building deliberately defective or permuted histories directly.
    */
  final case class StructurallyValid private[core] (
      repository: Repository,
      results: Vector[Version]
  )

  /** Proof that the full §4.5 pipeline (steps 1–6) passed: the structural proof plus step 6's
    * output — the materialized frontier tree (the current tree every command reads) and the
    * replay's warning set (R74; the merge command's R75 set subtraction consumes it — T17).
    *
    * Constructor `private[core]` like [[StructurallyValid]]'s: [[validateFully]] is the only
    * producer outside the core package.
    */
  final case class Valid private[core] (
      structure: StructurallyValid,
      tree: Tree,
      warnings: SortedSet[Warning]
  ):
    def repository: Repository = structure.repository
    def results: Vector[Version] = structure.results

  /** §4.5 steps 1–6: structural validation ([[validate]]) followed by per-change base checks and
    * the deterministic replay of the declared frontier ([[Replay.materialize]], steps 5–6) with the
    * full concurrent integration engine (§6.2, T16).
    */
  def validateFully(repository: Repository): Either[SnapError, Valid] =
    for
      structure <- validate(repository)
      replayed <- Replay.materialize(structure, repository.frontier)
      (tree, warnings) = replayed
    yield Valid(structure, tree, warnings)

  /** Runs steps 2–4 in a fixed order — step 2 in full (sorting/dots → contiguity) before step 3
    * (increments → base closure), then frontier closure → reachability → acyclicity (PR3/CR8: a
    * doubly-invalid history reports step 2's diagnostic class, matching §4.5's own numbering); the
    * first violation decides.
    */
  def validate(repository: Repository): Either[SnapError, StructurallyValid] =
    val patches = repository.patches
    for
      _ <- checkSortedAndDots(patches)
      _ <- checkContiguity(patches)
      _ <- checkIncrements(patches)
      dots = patches.iterator.map(_.dot).toSet
      _ <- checkBaseClosure(patches, dots)
      _ <- checkFrontierClosure(repository.frontier, dots)
      _ <- checkReachable(repository.frontier, patches)
      _ <- checkAcyclic(patches)
      results <- traverse(patches)(_.result)
    yield StructurallyValid(repository, results)

  /** Step 2 (R44): sorted by author (`Utf8Order`) then numeric revision, and one value per dot. The
    * same dot twice is rejected either way: structurally equal values are a redundant listing
    * (`patches` is *exactly* the closure — each patch appears once), structurally different values
    * are corruption (R47, §3.5 — the pinned `patch collision` shape).
    */
  private def checkSortedAndDots(patches: Vector[Patch]): Either[SnapError, Unit] =
    (1 until patches.length).iterator
      .flatMap { i =>
        val prev = patches(i - 1)
        val cur = patches(i)
        val byAuthor = ContributorId.ordering.compare(prev.author, cur.author)
        if byAuthor > 0 then Some(SnapError.PatchesNotSorted)
        else if byAuthor < 0 then None
        else if prev.revision > cur.revision then Some(SnapError.PatchesNotSorted)
        else if prev.revision < cur.revision then None
        else if prev == cur then Some(SnapError.DuplicatePatch(cur.dot))
        else Some(SnapError.PatchCollision(cur.dot))
      }
      .nextOption()
      .toLeft(())

  /** Step 3 (R46): `revision = base[author] + 1` for every patch. `base.get` is 0 for an absent
    * component, and both sides are bounded by 2^53−1, so the sum cannot overflow a `Long`.
    */
  private def checkIncrements(patches: Vector[Patch]): Either[SnapError, Unit] =
    patches.iterator
      .flatMap { p =>
        Option.unless(p.revision == p.base.get(p.author) + 1L)(SnapError.DotMismatch(p.dot))
      }
      .nextOption()
      .toLeft(())

  /** Step 2 (§3.5): per contributor the revisions present are exactly `1..k`. Relies on
    * [[checkSortedAndDots]] having passed: a contributor's patches are then adjacent with strictly
    * ascending revisions, so the first patch of a run must be revision 1 and each successor must
    * increment by one. Reports the first absent dot (test 15 pins the `missing a@x` fragment).
    */
  private def checkContiguity(patches: Vector[Patch]): Either[SnapError, Unit] =
    patches.indices.iterator
      .flatMap { i =>
        val p = patches(i)
        val expected =
          if i > 0 && patches(i - 1).author == p.author then patches(i - 1).revision + 1L
          else 1L
        Option.unless(p.revision == expected)(SnapError.MissingPatch(Dot(p.author, expected)))
      }
      .nextOption()
      .toLeft(())

  /** Step 3: every base entry `(c, n)` has its patch present. Together with contiguity this gives
    * the complete base closure — presence of `(c, n)` implies presence of `(c, 1..n)`.
    */
  private def checkBaseClosure(
      patches: Vector[Patch],
      dots: Set[Dot]
  ): Either[SnapError, Unit] =
    patches.iterator
      .flatMap { p =>
        p.base.entries.iterator.flatMap { (id, n) =>
          Option.unless(dots.contains(Dot(id, n)))(SnapError.MissingPatch(Dot(id, n)))
        }
      }
      .nextOption()
      .toLeft(())

  /** R44/R45: every frontier component `(c, V[c])` has its patch present (with contiguity, the
    * whole selected set `n <= V[c]` then exists).
    */
  private def checkFrontierClosure(
      frontier: Version,
      dots: Set[Dot]
  ): Either[SnapError, Unit] =
    frontier.entries.iterator
      .flatMap { (id, n) =>
        Option.unless(dots.contains(Dot(id, n)))(SnapError.MissingPatch(Dot(id, n)))
      }
      .nextOption()
      .toLeft(())

  /** R44: no patch outside the causal closure of the frontier — every dot `(c, n)` satisfies
    * `n <= frontier[c]` (test 23 pins the `unreachable patch: ` prefix).
    */
  private def checkReachable(
      frontier: Version,
      patches: Vector[Patch]
  ): Either[SnapError, Unit] =
    patches.iterator
      .flatMap { p =>
        Option.unless(p.revision <= frontier.get(p.author))(SnapError.UnreachablePatch(p.dot))
      }
      .nextOption()
      .toLeft(())

  /** Step 4 (R60): acyclic causality. A patch depends on the patch at `(d, base[d])` for every base
    * component; grow the set of integrable patches to a fixpoint — if it stops short, no ready
    * patch remains before the history is complete (test 15's two-patch base cycle). Set operations
    * are membership-only; iteration stays on the file-ordered vector, so the outcome (a bare
    * yes/no) is trivially deterministic. O(n²) — correctness first (D19 spirit).
    */
  private def checkAcyclic(patches: Vector[Patch]): Either[SnapError, Unit] =
    @tailrec
    def grow(admitted: Set[Dot]): Set[Dot] =
      val next = patches.iterator
        .filter { p =>
          !admitted.contains(p.dot) &&
          p.base.entries.forall((id, n) => admitted.contains(Dot(id, n)))
        }
        .map(_.dot)
        .toVector
      if next.isEmpty then admitted else grow(admitted ++ next)
    if grow(Set.empty).size == patches.length then Right(())
    else Left(SnapError.CyclicHistory)

  private def traverse[A, B](items: Vector[A])(
      f: A => Either[SnapError, B]
  ): Either[SnapError, Vector[B]] =
    items.foldLeft[Either[SnapError, Vector[B]]](Right(Vector.empty)) { (acc, item) =>
      acc.flatMap(out => f(item).map(out :+ _))
    }

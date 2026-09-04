package snap.core

import java.nio.charset.StandardCharsets

/** Deterministic replay (SPEC §6.1–§6.2; DESIGN §5, D14, D19): patch selection for a version and
  * the known-version predicate (R45/R65), the ready-loop with all three ordering keys verbatim
  * (R66), per-change validation against the materialized exact base tree (SPEC §4.5 steps 5–6,
  * R51–R52, R25, R59–R60), and integration of one patch through the [[Integration]] seam — T07
  * ships [[Replay.LinearOnly]] (R69 rule 1); T16 adds the concurrent engine behind the same seam.
  *
  * Pure: no I/O, no clock, no randomness. Every decision iterates sorted structures — the pending
  * set is reduced by an explicit total order ([[Replay.readyOrdering]]), trees iterate in
  * `Utf8Order` by construction, and the memo map is only ever probed by key — so the result is a
  * function of the patch set and version alone, never of input or processing order (R76).
  */
object Replay:

  /** R66/D14 — the ready-loop ordering over (result version, dot), all three keys verbatim:
    *
    *   1. Snap order of the result versions (lower counter at the first differing sorted id =
    *      earlier — DESIGN §10 gotcha 3);
    *   1. unsigned UTF-8 order of the author; then
    *   1. numeric revision.
    *
    * Valid histories decide at key 1 — two distinct ready patches cannot share a result version (a
    * shared result would make each patch's own dot part of the other's base closure, a cycle) — but
    * keys 2 and 3 are implemented and live (D14). The order is total and strict on any set of
    * distinct dots, so reducing by it is insensitive to iteration order.
    */
  val readyOrdering: Ordering[(Version, Dot)] = new Ordering[(Version, Dot)]:
    def compare(a: (Version, Dot), b: (Version, Dot)): Int =
      val bySnap = Version.snapOrdering.compare(a._1, b._1)
      if bySnap != 0 then bySnap
      else
        val byAuthor = ContributorId.ordering.compare(a._2.author, b._2.author)
        if byAuthor != 0 then byAuthor
        else java.lang.Long.compare(a._2.revision, b._2.revision)

  /** Integration of one patch into the canonical tree (SPEC §6.2) — the typed T16 extension point.
    * T07 ships [[LinearOnly]]; T16 adds the full concurrent strategy (namespace pre-pass R68, rules
    * 2–4 of R69, path rules R73, warning pairs R74) behind this same seam, widening the result to
    * carry warnings.
    */
  trait Integration:
    /** Integrates `patch` into `canonical`, given its materialized exact base tree `base` (R67) and
      * its step-5-validated authored result tree `authored` (= `base` with every change applied).
      */
    def integrate(
        patch: Patch,
        base: Tree,
        authored: Tree,
        canonical: Tree
    ): Either[SnapError, Tree]

  /** R69 rule 1 only: when every path changed by the patch is identical in the base tree `B` and
    * the canonical tree `C` (always true in linear histories) and the patch raises no namespace
    * conflict (R68), the authored change applies directly — and the result provably equals the full
    * engine's (no concurrent rule would have fired, and rule 1 emits no warning). Any genuinely
    * concurrent case is the typed [[SnapError.ConcurrentHistoryUnsupported]] — never silent wrong
    * behavior — until T16 replaces this strategy.
    */
  object LinearOnly extends Integration:
    def integrate(
        patch: Patch,
        base: Tree,
        authored: Tree,
        canonical: Tree
    ): Either[SnapError, Tree] =
      // Rule 1 precondition, per changed path: identical presence and bytes in B and C.
      val ruleOneHolds =
        patch.changes.forall(ch => sameEntry(base.get(ch.path), canonical.get(ch.path)))
      if !ruleOneHolds then Left(SnapError.ConcurrentHistoryUnsupported(patch.dot))
      else if namespaceConflict(patch, authored, canonical) then
        Left(SnapError.ConcurrentHistoryUnsupported(patch.dot))
      else
        // R70: all of the patch's path changes land together. `authored` presence at a changed
        // path encodes the change's effect exactly: text/put leave it present, delete absent.
        Right(patch.changes.foldLeft(canonical) { (tree, ch) =>
          authored.get(ch.path) match
            case Some(bytes) => tree.updated(ch.path, bytes)
            case None        => tree.removed(ch.path)
        })

    /** R68 detection only (resolution is T16's): with `S` = the paths the patch makes present and
      * `C'` = the canonical tree minus the patch's authored deletions, is there any `s ∈ S` with a
      * (necessarily different) current ancestor or descendant in `C'`? In a truly linear history
      * this never fires — such a conflict would already be a step-5 `tree paths conflict` in the
      * authored result — so it only guards against silently building a non-prefix-free tree from
      * concurrent creates (e.g. concurrent `a` and `a/b`).
      */
    private def namespaceConflict(patch: Patch, authored: Tree, canonical: Tree): Boolean =
      val deletions = patch.changes.collect { case ch if !authored.contains(ch.path) => ch.path }
      val cPrime = deletions.foldLeft(canonical)(_.removed(_))
      patch.changes.iterator
        .map(_.path)
        .filter(authored.contains)
        .exists(s => cPrime.ancestorsOf(s).nonEmpty || cPrime.descendantsOf(s).nonEmpty)

  /** R45: the known-version predicate. `version` is known (materializable) iff every patch `(c, n)`
    * selected by `n <= version[c]` exists and the selected set contains every selected patch's
    * complete base. `()` is always known. Rejection is the typed [[SnapError.UnknownVersion]] — the
    * CLI (T11/T12) renders it as `unknown version: <v>`.
    *
    * Relies on the structural proof: contiguity (§4.5 step 2) makes presence of `(c, version[c])`
    * equivalent to presence of every `(c, n <= version[c])`, and base containment
    * `base[d] <= version[d]` selects the base's whole causal closure.
    */
  def checkKnown(valid: Repo.StructurallyValid, version: Version): Either[SnapError, Unit] =
    val patches = valid.repository.patches
    val dots = patches.iterator.map(_.dot).toSet
    val everySelectedExists = version.entries.forall((c, n) => dots.contains(Dot(c, n)))
    def basesContained = patches.iterator
      .filter(p => p.revision <= version.get(p.author))
      .forall(p => p.base.entries.forall((d, m) => m <= version.get(d)))
    if everySelectedExists && basesContained then Right(())
    else Left(SnapError.UnknownVersion(version))

  /** Materializes `version` (SPEC §6.1–§6.2): checks it is known (R45), selects every patch `(c,
    * n)` with `n <= version[c]` (R65), and replays the selection from the empty tree through the
    * ready-loop (R66), validating each patch's changes against its materialized exact base tree on
    * the way (§4.5 step 5). Base trees are materialized recursively through a per-run memo (D19).
    */
  def materialize(
      valid: Repo.StructurallyValid,
      version: Version,
      integration: Integration
  ): Either[SnapError, Tree] =
    checkKnown(valid, version).flatMap(_ => run(valid, version, integration).map(_._1))

  /** The canonical integration order of `version`'s selection — the sequence of dots the ready-loop
    * integrates (R66). This order is a specified observable (it is what "later" means in §6.4);
    * exposed for order-directed tests and later consumers.
    */
  def integrationOrder(
      valid: Repo.StructurallyValid,
      version: Version,
      integration: Integration
  ): Either[SnapError, Vector[Dot]] =
    checkKnown(valid, version).flatMap(_ => run(valid, version, integration).map(_._2))

  /** SPEC §4.5 step 5 (R51–R52, R25): applies `patch`'s changes to its materialized exact base
    * tree, validating every change on the way, and returns the authored result tree `T`.
    *
    * Per change, in the changes' path-sorted order (checks in this fixed order):
    *
    *   - `delete`: the path must be present in the base (R51 — `delete of absent path: <p>`);
    *   - `put`: over a present path with identical bytes it is a no-op (R52); otherwise create or
    *     replacement;
    *   - `text` over an absent path: creation from the empty token sequence (an empty edit creates
    *     an empty file — R52/R58); over a present path: the base bytes must be text (R53), the
    *     script must apply (R54–R57, surfacing [[EditError]]s), and a result identical to the old
    *     tokens is a no-op (R52).
    *
    * Then the whole authored result must be prefix-free (R25 — `tree paths conflict`).
    *
    * Every change is judged against the same base tree `B` (the spec's "exact base"): changes touch
    * distinct paths (R49), so folding them into an accumulator never disturbs another change's base
    * view.
    */
  def authoredResult(base: Tree, patch: Patch): Either[SnapError, Tree] =
    val applied = patch.changes.foldLeft[Either[SnapError, Tree]](Right(base)) { (acc, change) =>
      acc.flatMap(tree => applyChange(base, tree, change))
    }
    applied.flatMap { authored =>
      // First offending path in Utf8Order; equivalent to !authored.isPrefixFree.
      authored.paths.find(p => authored.ancestorsOf(p).nonEmpty) match
        case Some(p) => Left(SnapError.TreePathsConflict(p))
        case None    => Right(authored)
    }

  // --- internals ---

  /** One selected patch with its precomputed result version (R46, from
    * [[Repo.StructurallyValid.results]]).
    */
  private final case class Sel(patch: Patch, result: Version)

  private val selOrdering: Ordering[Sel] =
    Ordering.by((s: Sel) => (s.result, s.patch.dot))(readyOrdering)

  /** Trees materialized so far in this run, by version (D19). Probed by key only — never iterated —
    * so it feeds no ordering decision.
    */
  private type Memo = Map[Version, Tree]

  private def run(
      valid: Repo.StructurallyValid,
      version: Version,
      integration: Integration
  ): Either[SnapError, (Tree, Vector[Dot])] =
    loop(valid, select(valid, version), Version.empty, Tree.empty, Vector.empty, Map.empty)(
      integration
    ).map((tree, order, _) => (tree, order))

  /** R65: every patch `(c, n)` with `n <= version[c]`, paired with its precomputed result. Iterates
    * the file-ordered patch vector; the pairing relies on `results(i)` describing `patches(i)`.
    */
  private def select(valid: Repo.StructurallyValid, version: Version): Vector[Sel] =
    valid.repository.patches.indices.iterator
      .filter { i =>
        val p = valid.repository.patches(i)
        p.revision <= version.get(p.author)
      }
      .map(i => Sel(valid.repository.patches(i), valid.results(i)))
      .toVector

  /** The ready-loop (R66): start from the empty tree; repeatedly integrate the least ready patch by
    * [[readyOrdering]]. A patch is ready when its base's causal closure is integrated — because
    * integration preserves per-contributor downward closure, `progress` (the join of integrated
    * results) captures the integrated set exactly, and readiness is `base <= progress`
    * componentwise. If no patch is ready before the selection is exhausted, the history has a cycle
    * or missing dependency (R60).
    *
    * Not tail-recursive (the base materialization recursion interleaves); depth is bounded by the
    * selection size, fine for repository-scale inputs (D19: correctness first).
    */
  private def loop(
      valid: Repo.StructurallyValid,
      pending: Vector[Sel],
      progress: Version,
      canonical: Tree,
      order: Vector[Dot],
      memo: Memo
  )(integration: Integration): Either[SnapError, (Tree, Vector[Dot], Memo)] =
    if pending.isEmpty then Right((canonical, order, memo))
    else
      val ready = pending.filter(s => contained(s.patch.base, progress))
      if ready.isEmpty then Left(SnapError.CyclicHistory)
      else
        val next = ready.min(selOrdering)
        for
          baseAndMemo <- materializeMemo(valid, next.patch.base, memo)(integration)
          (baseTree, memo1) = baseAndMemo
          authored <- authoredResult(baseTree, next.patch)
          integrated <- integration.integrate(next.patch, baseTree, authored, canonical)
          out <- loop(
            valid,
            pending.filterNot(_.patch.dot == next.patch.dot),
            progress.join(next.result),
            integrated,
            order :+ next.patch.dot,
            memo1
          )(integration)
        yield out

  /** Materializes a base version through the memo (D19): each version's tree is computed at most
    * once per run. The recursion terminates because a patch's base is causally strictly below its
    * result. A sub-replay CAN still fail: a steps-1–4-valid history may declare a base that is not
    * self-contained (its selection misses a dependency), which surfaces here as `CyclicHistory` —
    * spec-correct per §4.1/§6.1 (such a base is not materializable). Do not assume sub-replays are
    * infallible when extending this (T16). See reviews/T07-review.md nit 1.
    */
  private def materializeMemo(
      valid: Repo.StructurallyValid,
      version: Version,
      memo: Memo
  )(integration: Integration): Either[SnapError, (Tree, Memo)] =
    memo.get(version) match
      case Some(tree) => Right((tree, memo))
      case None       =>
        loop(valid, select(valid, version), Version.empty, Tree.empty, Vector.empty, memo)(
          integration
        ).map((tree, _, memo1) => (tree, memo1.updated(version, tree)))

  /** `base <= progress` componentwise — the base's causal closure is integrated. */
  private def contained(base: Version, progress: Version): Boolean =
    base.entries.forall((d, m) => m <= progress.get(d))

  /** Validates and applies one change: judged against `base` (the exact base tree), applied to
    * `acc` (the authored result under construction). See [[authoredResult]] for the rules.
    */
  private def applyChange(base: Tree, acc: Tree, change: Change): Either[SnapError, Tree] =
    change match
      case Change.Delete(path) =>
        if base.contains(path) then Right(acc.removed(path))
        else Left(SnapError.DeleteOfAbsentPath(path))
      case Change.Put(path, content) =>
        base.get(path) match
          case Some(existing) if bytesEqual(existing, content) =>
            Left(SnapError.NoOpChange(path))
          case _ => Right(acc.updated(path, content))
      case Change.Text(path, edit) =>
        base.get(path) match
          case None =>
            // Creation: the old token sequence is empty; an empty edit creates an empty file
            // (R52/R58). Any retain/delete overconsumes and surfaces as a typed EditError.
            edit
              .applyTo(Vector.empty)
              .fold(
                e => Left(SnapError.InvalidEdit(e)),
                tokens => Right(acc.updated(path, encode(tokens)))
              )
          case Some(existing) =>
            TextTokens.tokenizeBytes(IArray.genericWrapArray(existing).toArray) match
              case None            => Left(SnapError.TextEditOverNonText(path))
              case Some(oldTokens) =>
                edit.applyTo(oldTokens) match
                  case Left(e)          => Left(SnapError.InvalidEdit(e))
                  case Right(newTokens) =>
                    // Canonical token sequences concatenate losslessly, so token equality is
                    // byte equality (R52's "does not alter ... bytes").
                    if newTokens == oldTokens then Left(SnapError.NoOpChange(path))
                    else Right(acc.updated(path, encode(newTokens)))

  /** Token sequence back to file bytes. The freshly built array is never aliased or mutated
    * afterwards, so the zero-copy wrap is safe.
    */
  private def encode(tokens: Vector[String]): IArray[Byte] =
    IArray.unsafeFromArray(TextTokens.render(tokens).getBytes(StandardCharsets.UTF_8))

  private def sameEntry(a: Option[IArray[Byte]], b: Option[IArray[Byte]]): Boolean =
    (a, b) match
      case (None, None)       => true
      case (Some(x), Some(y)) => bytesEqual(x, y)
      case _                  => false

  // Byte-content equality, module-local like `Tree`'s and `Change`'s (kept private per file rather
  // than exposing one file's internals across the package).
  private def bytesEqual(a: IArray[Byte], b: IArray[Byte]): Boolean =
    a.length == b.length && (0 until a.length).forall(i => a(i) == b(i))

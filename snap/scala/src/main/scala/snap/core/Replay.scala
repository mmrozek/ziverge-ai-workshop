package snap.core

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

/** Why replay auto-resolved a conflict at a path (SPEC §6.2 R68, §6.4 R73). The rendered token is
  * the exact spec vocabulary; the full `warning: auto-resolved <path>: <reason>` line belongs to
  * the merge command's presentation (R75, T17).
  */
enum WarningReason:
  /** §6.4 rules 2–3: a concurrent delete discards the other side's whole effect. */
  case DeleteWins

  /** §6.4 rule 4: the canonically later concurrent create of one path wins. */
  case LaterCreateWins

  /** §6.4 rule 5: the incoming atomic `put` replacement wins. */
  case LaterPutWins

  /** §6.2 namespace pre-pass (R68): the named path was removed because a concurrent patch installed
    * a conflicting ancestor or descendant — the warning names the REMOVED path (test 11).
    */
  case NamespaceWins

  /** §6.4 rule 6: incoming text over non-text current content — the current content wins. */
  case PutWins

  /** The spec's reason token, verbatim (§6.4's warning-pair grammar). */
  def text: String = this match
    case DeleteWins      => "delete-wins"
    case LaterCreateWins => "later-create-wins"
    case LaterPutWins    => "later-put-wins"
    case NamespaceWins   => "namespace-wins"
    case PutWins         => "put-wins"

/** One auto-resolution warning pair (SPEC §6.4, R74): the affected path and the rule that decided.
  * Replay returns the set of unique pairs; duplicates collapse by construction of the sorted set.
  */
final case class Warning(path: SnapPath, reason: WarningReason)

object Warning:
  /** R74: sorted by path, then reason — both in unsigned UTF-8 byte order (the reason through its
    * rendered token, so the order is a property of the spec vocabulary, not of enum declaration
    * order). Test 10 pins the resulting warning line order.
    */
  given ordering: Ordering[Warning] = new Ordering[Warning]:
    def compare(a: Warning, b: Warning): Int =
      val byPath = SnapPath.ordering.compare(a.path, b.path)
      if byPath != 0 then byPath else Utf8Order.compare(a.reason.text, b.reason.text)

/** Deterministic replay (SPEC §6; DESIGN §5, D14, D19): patch selection for a version and the
  * known-version predicate (R45/R65), the ready-loop with all three ordering keys verbatim (R66),
  * per-change validation against the materialized exact base tree (SPEC §4.5 steps 5–6, R51–R52,
  * R25, R59–R60), and the full concurrent integration of one patch (§6.2 namespace pre-pass R68,
  * per-path dispatch R69, path-level rules R73, OT through the aggregate context edit R71–R72),
  * accumulating the warning set (R74).
  *
  * Pure: no I/O, no clock, no randomness. Every decision iterates sorted structures — the pending
  * set is reduced by an explicit total order ([[Replay.readyOrdering]]), trees iterate in
  * `Utf8Order` by construction, changes are path-sorted by construction, warnings live in a
  * `SortedSet`, and the memo map is only ever probed by key — so the result is a function of the
  * patch set and version alone, never of input or processing order (R76).
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

  /** Materializes `version` (SPEC §6.1–§6.4): checks it is known (R45), selects every patch `(c,
    * n)` with `n <= version[c]` (R65), and replays the selection from the empty tree through the
    * ready-loop (R66), validating each patch's changes against its materialized exact base tree on
    * the way (§4.5 step 5) and integrating it per §6.2. Base trees are materialized recursively
    * through a per-run memo (D19).
    *
    * Returns the frontier tree together with the replay's warning set (R74): the unique
    * `(path, reason)` pairs emitted by the ready-loop's integrations, sorted by path then reason.
    * The merge command's `joined -- preMergeLocal` set subtraction (R75) is built on two calls to
    * this method (T17).
    */
  def materialize(
      valid: Repo.StructurallyValid,
      version: Version
  ): Either[SnapError, (Tree, SortedSet[Warning])] =
    checkKnown(valid, version).flatMap(_ => run(valid, version).map(r => (r.tree, r.warnings)))

  /** The canonical integration order of `version`'s selection — the sequence of dots the ready-loop
    * integrates (R66). This order is a specified observable (it is what "later" means in §6.4);
    * exposed for order-directed tests and later consumers.
    */
  def integrationOrder(
      valid: Repo.StructurallyValid,
      version: Version
  ): Either[SnapError, Vector[Dot]] =
    checkKnown(valid, version).flatMap(_ => run(valid, version).map(_.order))

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

  /** One completed ready-loop run: the final tree, the integration order, the warning set (R74),
    * and the memo threaded through for sub-replays.
    */
  private final case class Replayed(
      tree: Tree,
      order: Vector[Dot],
      warnings: SortedSet[Warning],
      memo: Memo
  )

  private def run(
      valid: Repo.StructurallyValid,
      version: Version
  ): Either[SnapError, Replayed] =
    loop(
      valid,
      select(valid, version),
      Version.empty,
      Tree.empty,
      Vector.empty,
      SortedSet.empty,
      Map.empty
    )

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
    * Warnings accumulate across the loop's integrations into one sorted set (R74).
    *
    * `@tailrec` (phase-1 review CR1, stack half — reviews/phase-1-review.md,
    * reviews/T07-review.md): the T07-era body chained these same steps through `Either#flatMap`, so
    * the recursive call to `loop` sat inside a lambda passed to `authoredResult(...).flatMap`,
    * never in tail position — every ready patch grew the JVM stack by a frame that could only pop
    * once the ENTIRE rest of the replay finished, StackOverflowing on ~1k+ patch valid histories (a
    * spec-valid input crashing instead of producing a typed result). Rewritten as an explicit match
    * so the self-call is the last expression on every path — the compiler-verified tail call
    * compiles to a loop, so a single invocation's own iteration is O(1) stack regardless of the
    * selection size.
    *
    * [[materializeMemo]]'s own call into `loop` for a cache-miss base (below) is an ordinary,
    * non-recursive call from a different method — invisible to `@tailrec`'s self-call check — and
    * is itself O(1) stack for the same reason: it is `loop` again, so its internal iteration is
    * exactly as flat, however large that sub-selection is. `ReplayStackSafetySlowSuite` pins both a
    * deep linear history and a deep concurrent history (repeated conflicting diamonds), well past
    * the ~1k-patch threshold that reproduced the pre-fix crash.
    *
    * **Θ(n²) → O(n) (T23, phase-1 review PR5/CR1 performance half, re-measured after T16's engine
    * landed — 800 patches took ~7.5s, matching the review's original ~9.3s order of magnitude):**
    * every successful integration below now ALSO stores `nextCanonical` into the memo under its own
    * result version (`newProgress`), not just the base-tree cache-miss branch in
    * [[materializeMemo]]. In a linear history a patch's declared base is exactly the prior step's
    * `newProgress`, so before this change every `materializeMemo` probe for it was a cache MISS,
    * re-walking the entire prefix from scratch (Σ(k−1) ≈ n²/2 integrations across a run) — the
    * outer loop was computing that exact tree one line above and simply never recording it under
    * its own key. `materialize(V)` is deterministic in `V` alone (R12/R13/R76), so recording it
    * here changes no result, only which of the two population points a later probe happens to hit.
    */
  @tailrec
  private def loop(
      valid: Repo.StructurallyValid,
      pending: Vector[Sel],
      progress: Version,
      canonical: Tree,
      order: Vector[Dot],
      warnings: SortedSet[Warning],
      memo: Memo
  ): Either[SnapError, Replayed] =
    if pending.isEmpty then Right(Replayed(canonical, order, warnings, memo))
    else
      val ready = pending.filter(s => contained(s.patch.base, progress))
      if ready.isEmpty then Left(SnapError.CyclicHistory)
      else
        val next = ready.min(selOrdering)
        materializeMemo(valid, next.patch.base, memo) match
          case Left(err)                => Left(err)
          case Right((baseTree, memo1)) =>
            authoredResult(baseTree, next.patch) match
              case Left(err)       => Left(err)
              case Right(authored) =>
                integrate(next.patch, baseTree, authored, canonical) match
                  case Left(err)                           => Left(err)
                  case Right((nextCanonical, newWarnings)) =>
                    val newProgress = progress.join(next.result)
                    loop(
                      valid,
                      pending.filterNot(_.patch.dot == next.patch.dot),
                      newProgress,
                      nextCanonical,
                      order :+ next.patch.dot,
                      warnings ++ newWarnings,
                      // T23 (phase-1 review PR5/CR1, the performance half — re-measured: 800
                      // patches took ~7.5s before this change): the outer loop already knows
                      // `materialize(newProgress) == nextCanonical` (R12/R13/R76 — the same patch
                      // set and version always produce the same tree, however it was reached), so
                      // storing it here turns any LATER `materializeMemo` probe for this exact
                      // version into a memo hit instead of a full sub-replay. In a linear history a
                      // patch's base is exactly the prior step's `newProgress`, so this alone
                      // collapses the ready-loop from Θ(n²) integrations to O(n) — each base is
                      // computed once, by the step that already held its tree, never re-walked.
                      memo1.updated(newProgress, nextCanonical)
                    )

  /** Materializes a base version through the memo (D19): each version's tree is computed at most
    * once per run. The recursion terminates because a patch's base is causally strictly below its
    * result. A sub-replay CAN still fail: a steps-1–4-valid history may declare a base that is not
    * self-contained (its selection misses a dependency), which surfaces here as `CyclicHistory` —
    * spec-correct per §4.1/§6.1 (such a base is not materializable). Do not assume sub-replays are
    * infallible when extending this. See reviews/T07-review.md nit 1.
    *
    * A sub-replay's warnings are deliberately discarded: R74's warning set is the property of THE
    * replay — the outer ready-loop's integrations, each selected patch integrated exactly once.
    * Base materialization is §6.2's subroutine for obtaining `B`; it re-integrates a subset of the
    * same patches in a smaller context, and counting those re-integrations would double-report (or
    * misreport, since the smaller context can resolve differently). Only the tree is memoized.
    */
  private def materializeMemo(
      valid: Repo.StructurallyValid,
      version: Version,
      memo: Memo
  ): Either[SnapError, (Tree, Memo)] =
    memo.get(version) match
      case Some(tree) => Right((tree, memo))
      case None       =>
        loop(
          valid,
          select(valid, version),
          Version.empty,
          Tree.empty,
          Vector.empty,
          SortedSet.empty,
          memo
        ).map(r => (r.tree, r.memo.updated(version, r.tree)))

  /** `base <= progress` componentwise — the base's causal closure is integrated. */
  private def contained(base: Version, progress: Version): Boolean =
    base.entries.forall((d, m) => m <= progress.get(d))

  // --- §6.2: integrating one patch (R67–R70) ---

  /** The namespace pre-pass result (R68). `settled` is probed by membership only (never iterated);
    * `removals`, `installs`, and `warnings` are sorted by construction.
    */
  private final case class Namespace(
      settled: Set[SnapPath],
      removals: SortedSet[SnapPath],
      installs: Vector[(SnapPath, IArray[Byte])],
      warnings: SortedSet[Warning]
  )

  private object Namespace:
    val empty: Namespace =
      Namespace(Set.empty, SortedSet.empty(SnapPath.ordering), Vector.empty, SortedSet.empty)

  /** One resolved path change: the path's final entry in the next canonical tree (`Some(bytes)` =
    * present with those bytes, `None` = absent) plus the warning the deciding rule emitted, if any.
    */
  private final case class PathOutcome(
      path: SnapPath,
      entry: Option[IArray[Byte]],
      warning: Option[Warning]
  )

  /** Integrates one patch `P` into the canonical tree `C` (SPEC §6.2, R67–R70), given its
    * materialized exact base tree `B` and its step-5-validated authored result tree `T` (= `B` with
    * every change applied):
    *
    *   1. the namespace pre-pass (R68) settles whole-namespace conflicts first and overrides the
    *      per-path rules ([[namespacePrePass]]);
    *   1. every remaining changed path is evaluated against the same `B` and `C` (R69,
    *      [[resolvePath]]) — never against intermediate states;
    *   1. all resulting path changes apply together (R70): the marked current paths are removed and
    *      every resolved entry installed in one step from `C` to the next canonical tree. The
    *      pre-pass removals, the pre-pass installs, and the per-path outcomes touch pairwise
    *      disjoint path sets (a settled path is excluded from per-path evaluation; a removed
    *      current path can never be one of `P`'s changed paths — an authored deletion is excluded
    *      from `C'`, and a changed path present in `T` alongside the conflicting `S`-path would
    *      have failed step 5's prefix-freeness), so the application order within the step cannot
    *      matter; the fold below fixes removals → outcomes → installs anyway.
    *
    * Returns the next canonical tree and the patch's warning pairs (R74; duplicates collapse in the
    * sorted set).
    */
  private def integrate(
      patch: Patch,
      base: Tree,
      authored: Tree,
      canonical: Tree
  ): Either[SnapError, (Tree, SortedSet[Warning])] =
    val ns = namespacePrePass(patch, base, authored, canonical)
    val remaining = patch.changes.filterNot(ch => ns.settled.contains(ch.path))
    val outcomes = remaining.foldLeft[Either[SnapError, Vector[PathOutcome]]](
      Right(Vector.empty)
    ) { (acc, change) =>
      acc.flatMap(out => resolvePath(change, base, authored, canonical).map(out :+ _))
    }
    outcomes.map { resolved =>
      val afterRemovals = ns.removals.foldLeft(canonical)(_.removed(_))
      val afterOutcomes = resolved.foldLeft(afterRemovals) { (tree, o) =>
        o.entry match
          case Some(bytes) => tree.updated(o.path, bytes)
          case None        => tree.removed(o.path)
      }
      val nextTree = ns.installs.foldLeft(afterOutcomes) { case (tree, (path, bytes)) =>
        tree.updated(path, bytes)
      }
      (nextTree, ns.warnings ++ resolved.flatMap(_.warning))
    }

  /** The namespace pre-pass (SPEC §6.2, R68), quoted: "Let `S` be the paths that `P` makes present,
    * and let `C'` be `C` with every path that `P` authored as a deletion removed. If a path in `S`
    * has a different current ancestor or descendant in `C'`, mark the incoming path for
    * installation as its authored result `T` and mark every conflicting current path for removal.
    * Each removed path emits `namespace-wins`. These decisions override the per-path rules."
    *
    * `S` is the paths `P` makes present — paths absent in `B` that `P`'s change leaves present in
    * `T` (creates, whether by `put` or by a text edit over an absent path). A path already present
    * in `B` is not *made* present by an edit or replacement; for those, a concurrent
    * delete-plus-conflicting-create in `C` resolves through §6.4 rule 3 (the earlier concurrent
    * delete wins), keeping the rules' precedence coherent — the pre-pass exists to let incoming
    * CREATES clear conflicting namespace, mirroring rule 4's later-create-wins. (This reading is
    * also the T07 review's, which characterized `S` as "newly-present paths"; see the task notes.)
    *
    * The warning names the REMOVED path (test 11 pins `a/b: namespace-wins` and
    * `x: namespace-wins`). Duplicate removals and warnings collapse in the sorted sets. Two paths
    * in `S` can never conflict with each other (`T` is prefix-free, step 5), so the fold's per-`s`
    * decisions are independent and their union is order-independent; `changes` is path-sorted by
    * construction, so iteration is deterministic anyway.
    */
  private def namespacePrePass(
      patch: Patch,
      base: Tree,
      authored: Tree,
      canonical: Tree
  ): Namespace =
    val authoredDeletions = patch.changes.collect { case Change.Delete(p) => p }
    val cPrime = authoredDeletions.foldLeft(canonical)(_.removed(_))
    val makesPresent: Vector[(SnapPath, IArray[Byte])] = patch.changes.iterator
      .map(_.path)
      .filter(p => !base.contains(p))
      .flatMap(p => authored.get(p).map(bytes => (p, bytes)))
      .toVector
    makesPresent.foldLeft(Namespace.empty) { case (ns, (s, bytes)) =>
      // Ancestors and descendants are proper by construction, so every conflictor is a
      // "different" current path; `s` itself never conflicts with itself.
      val conflictors = cPrime.ancestorsOf(s) ++ cPrime.descendantsOf(s)
      if conflictors.isEmpty then ns
      else
        Namespace(
          ns.settled + s,
          ns.removals ++ conflictors,
          ns.installs :+ (s -> bytes),
          ns.warnings ++ conflictors.map(Warning(_, WarningReason.NamespaceWins))
        )
    }

  /** Per-path dispatch for one change not settled by the namespace rule (SPEC §6.2, R69) — every
    * path judged against the same `B` and `C`, in the spec's order:
    *
    *   1. path identical in `B` and `C` → apply the authored change directly (the entry becomes
    *      `T`'s — for identical bytes, applying to `C` equals applying to `B`);
    *   1. path identical in `C` and `T` → keep it unchanged, no warning (collapses identical
    *      concurrent changes BEFORE OT rather than duplicating their effect);
    *   1. `B`, `C`, `T` all text and `P` a text change → OT through the aggregate context edit
    *      ([[transformAndApply]]);
    *   1. otherwise §6.4's path-level rules ([[pathRules]]).
    *
    * Only the OT branch is fallible (typed [[SnapError.OtBaseMismatch]] / [[SnapError.InvalidEdit]]
    * — internal invariants surfaced as values, unreachable for scripts derived from one base).
    */
  private def resolvePath(
      change: Change,
      base: Tree,
      authored: Tree,
      canonical: Tree
  ): Either[SnapError, PathOutcome] =
    val path = change.path
    val bEntry = base.get(path)
    val cEntry = canonical.get(path)
    val tEntry = authored.get(path)
    if sameEntry(bEntry, cEntry) then Right(PathOutcome(path, tEntry, None)) // R69 case 1
    else if sameEntry(cEntry, tEntry) then Right(PathOutcome(path, cEntry, None)) // R69 case 2
    else
      textCase(change, bEntry, cEntry, tEntry) match
        case Some((edit, bTokens, cTokens)) => // R69 case 3
          transformAndApply(path, edit, bTokens, cTokens)
        case None => // R69 case 4
          Right(pathRules(change, bEntry, cEntry, tEntry))

  /** R69 case 3's precondition: `B`, `C`, and `T` are text and `P` is a text change. Yields the
    * incoming edit and the base/current token sequences when it holds. (`T` of a text change over a
    * present text base is always text — rendered canonical tokens — but the condition is checked
    * verbatim rather than assumed.)
    */
  private def textCase(
      change: Change,
      bEntry: Option[IArray[Byte]],
      cEntry: Option[IArray[Byte]],
      tEntry: Option[IArray[Byte]]
  ): Option[(EditScript, Vector[String], Vector[String])] =
    change match
      case Change.Text(_, edit) =>
        for
          bBytes <- bEntry
          cBytes <- cEntry
          tBytes <- tEntry
          bTokens <- TextTokens.tokenizeBytes(IArray.genericWrapArray(bBytes).toArray)
          cTokens <- TextTokens.tokenizeBytes(IArray.genericWrapArray(cBytes).toArray)
          if TextTokens.isText(IArray.genericWrapArray(tBytes).toArray)
        yield (edit, bTokens, cTokens)
      case _ => None

  /** R69 case 3 (SPEC §6.2–§6.3, R71–R72): derive the AGGREGATE context edit `Q = diff(B, C)` —
    * computed once per integrated patch from the two trees, never chained per historical patch
    * (R72; the canonical diff may collapse a concurrent delete-then-reinsert into identity, which
    * per-patch chaining cannot) — transform the incoming edit `P` through `Q` (§6.3), and apply the
    * transformed script to `C`'s tokens.
    *
    * The application enforces exact consumption but NOT the canonical-result check: §6.5 forces a
    * merge result for every valid history, and a transformed script may legitimately produce a
    * token sequence with a non-final LF-less token (reviews/T15-review.md, spec-confirmed). The
    * result is rendered to BYTES immediately — the transient non-canonical token list never
    * escapes; every downstream consumer re-tokenizes from the tree's bytes (T15 finding 1). OT
    * emits no warning (R74).
    */
  private def transformAndApply(
      path: SnapPath,
      edit: EditScript,
      bTokens: Vector[String],
      cTokens: Vector[String]
  ): Either[SnapError, PathOutcome] =
    val aggregate = Diff.diff(bTokens, cTokens)
    for
      transformed <- Ot.transform(edit, aggregate)
      merged <- transformed.applyTransformed(cTokens).left.map(SnapError.InvalidEdit(_))
    yield PathOutcome(path, Some(encode(merged)), None)

  /** SPEC §6.4's path-level rules (R73), resolved in this order for base entry `B`, current
    * canonical entry `C`, and incoming authored result `T`:
    *
    *   1. `C` = `T` → keep `C`, no warning — this predicate is R69 case 2, already checked before
    *      dispatch reaches here ([[resolvePath]]), so it is not re-tested: on entry `C != T`;
    *   1. `T` absent → the incoming delete wins (`delete-wins`; `C` is present here, else rule 1);
    *   1. `B` present ∧ `C` absent → the earlier concurrent delete wins (`delete-wins`);
    *   1. `B` absent ∧ `C`, `T` present → the incoming (canonically later) create wins
    *      (`later-create-wins`);
    *   1. incoming change is `put` → the incoming atomic replacement wins (`later-put-wins`);
    *   1. otherwise `P` is text and `C` is non-text — the incompatible current content wins
    *      (`put-wins`). (`B`, `C`, `T` are all present here: `B` and `C` absent together is R69
    *      case 1, the other absence splits went to rules 2–4; a text `C` would have taken the OT
    *      branch.)
    *
    * "Later" always means canonical integration order. Every rule that discards a whole effect
    * emits its warning pair (R74).
    */
  private def pathRules(
      change: Change,
      bEntry: Option[IArray[Byte]],
      cEntry: Option[IArray[Byte]],
      tEntry: Option[IArray[Byte]]
  ): PathOutcome =
    val path = change.path
    if tEntry.isEmpty then // rule 2
      PathOutcome(path, None, Some(Warning(path, WarningReason.DeleteWins)))
    else if bEntry.isDefined && cEntry.isEmpty then // rule 3
      PathOutcome(path, None, Some(Warning(path, WarningReason.DeleteWins)))
    else if bEntry.isEmpty && cEntry.isDefined then // rule 4 (tEntry is present here)
      PathOutcome(path, tEntry, Some(Warning(path, WarningReason.LaterCreateWins)))
    else
      change match
        case Change.Put(_, _) => // rule 5
          PathOutcome(path, tEntry, Some(Warning(path, WarningReason.LaterPutWins)))
        case _ => // rule 6
          PathOutcome(path, cEntry, Some(Warning(path, WarningReason.PutWins)))

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
          case Some(existing) if ByteArrays.equal(existing, content) =>
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
    ByteArrays.equalOption(a, b)

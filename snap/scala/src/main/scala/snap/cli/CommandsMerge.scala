package snap.cli

import snap.core.ContributorId
import snap.core.Messages
import snap.core.Patch
import snap.core.Repo
import snap.core.Repository
import snap.core.SnapError
import snap.core.Tree
import snap.fs.Materialize
import snap.fs.Store
import snap.fs.WorkTree

import java.nio.file.Path
import scala.annotation.tailrec

/** `snap merge <repository>` (SPEC §7.8; R5, R14, R38, R75–R76, R89; DESIGN §5 step 4, §7, §8,
  * D11): requires a clean working tree but NO contributor configuration; loads and validates the
  * other repository; unions the patch sets (structural dedupe per dot — a cross-repository dot
  * collision is corruption, §3.5/R38) and joins the frontiers (componentwise max, R34); canonically
  * replays the joined history, installs the result ([[Materialize.install]], §10 mutation order),
  * atomically replaces `repository.json` strictly afterwards (R105–R106); creates no patch and
  * increments no revision; prints the NEW warnings to stderr (R75) and the joined version to
  * stdout. Merging equal or already-contained history succeeds, changes nothing, emits no warnings,
  * and prints the unchanged version — the general path realizes this with no special case: the
  * union collapses to the local patch set, the join to the local frontier, the install to a no-op,
  * and the metadata write to byte-identical canonical bytes (D7).
  *
  * This task is composition, not new merge semantics: the §6 engine ([[snap.core.Replay]], T16) and
  * the filesystem materializer (T12) are consumed as they are. In particular the two replays R75's
  * warning subtraction needs are already values — the pre-merge local set is the `warnings` of the
  * local [[Repo.Valid]] loaded during validation, the joined set is the `warnings` of the union
  * repository's [[Repo.validateFully]] — nothing is recomputed, and no repository is validated more
  * than once per run (replay is Θ(n²) in patch count, D19).
  *
  * Failure precedence (D11, observable — test 20 and the dirty-before-remote acceptance case):
  * local parse+validate → working-tree scan (unsupported entry, then dirty) → remote load+validate
  * → dot cross-check → replay → write. A grammar violation precedes everything (R79, coarse check
  * here; T13 owns the exhaustive matrix). Every step is a link in one `for`-comprehension, so a
  * failure at any link reaches [[Cli]]'s emitter with zero prior output and zero mutation (R103).
  *
  * Determinism (R76): the union is a symmetric function of the two patch sets (a dot present on
  * both sides carries structurally equal patches — one value — or fails), the join is symmetric,
  * and the replay is a pure function of (patch set, frontier); merge direction therefore cannot
  * change the joined repository, its serialized bytes, the installed tree, or the warning set. The
  * printed warning order is [[Warning.ordering]] (path by `Utf8Order`, then reason) — the
  * `SortedSet` is iterated directly, never re-sorted downstream.
  */
object CommandsMerge:

  val handler: CommandHandler = (env, repoRoot, operands) =>
    val outcome = for
      operand <- parseOperand(operands)
      root <- Commands.requireRoot(repoRoot)
      local <- Commands.readRepository(root) // D11: local parse+validate (pre-merge replay)
      working <- WorkTree.scan(root) // D11: scan — unsupported entry (R104) …
      _ <- requireClean(local.tree, working) // … then dirty (R27; test 20's order)
      remoteRoot <- resolveOperand(env, operand) // R78: local path vs http(s):// URL (T20)
      remote <- Store.readRepository(
        Commands.repositoryFile(remoteRoot)
      ) // D11: remote load+validate
      patches <- unionPatches(
        local.repository.patches,
        remote.repository.patches
      ) // dot cross-check
      merged = Repository(local.repository.frontier.join(remote.repository.frontier), patches)
      // D11's replay step: §4.5 steps 1–6 on the union — the ONE replay of the joined history,
      // whose `warnings` are R75's joined set. Also the defensive gate mirroring CommandsRevert:
      // the repository file never receives a value that would not read back valid.
      mergedValid <- Repo.validateFully(merged)
      _ <- Materialize.install(root, local.tree, mergedValid.tree) // working files first (R105)
      _ <- Store.writeRepository(
        Commands.repositoryFile(root),
        merged
      ) // metadata strictly after (R106)
    yield
      // R75: only pairs present in the joined replay but absent from the pre-merge local replay,
      // one line per pair, in Warning order (the sorted set difference keeps the ordering).
      val newWarnings = mergedValid.warnings -- local.warnings
      newWarnings.foreach(w => Presentation.Plain.warning(env, Messages.autoResolved(w)))
      merged.frontier.canonicalText + "\n"
    outcome

  /** Exactly one operand — the other repository (T13 owns the exhaustive grammar matrix; this
    * coarse arity check mirrors [[CommandsRevert.parseOperand]]).
    */
  private def parseOperand(operands: List[String]): Either[SnapError, String] = operands match
    case repository :: Nil => Right(repository)
    case _                 => Left(SnapError.InvalidCommand)

  /** R78: an explicit `http://`/`https://` operand is a remote repository — its resolution lands in
    * T20 and stays [[SnapError.NotImplemented]] here (mirroring [[CommandsDiff]]'s `--repo` seam),
    * checked at THIS position so D11's precedence already holds for URLs (a dirty tree is reported
    * before the operand kind matters, exactly as it will be once T20 wires the client). Anything
    * else is a local path to a repository root, resolved against the process working directory —
    * `env.cwd`, never the discovered repository root.
    */
  private def resolveOperand(env: Env, operand: String): Either[SnapError, Path] =
    if operand.startsWith("http://") || operand.startsWith("https://") then
      Left(SnapError.NotImplemented)
    else Right(env.cwd.resolve(operand).toAbsolutePath.normalize())

  /** R27/R89's clean requirement, reusing the one canonical current-vs-working comparison
    * ([[WorkingChanges]]) that `status`/`commit`/`revert` use.
    */
  private def requireClean(current: Tree, working: Tree): Either[SnapError, Unit] =
    Either.cond(WorkingChanges.compute(current, working).isEmpty, (), SnapError.WorkingTreeDirty)

  /** Import as set union (R14: idempotent, commutative, associative) on the canonical sorted
    * representation: both inputs are patch vectors of validated repositories, so each is strictly
    * sorted by author (`Utf8Order`) then numeric revision with one value per dot (§4.5 step 2), and
    * a single linear merge yields the union in the same canonical order. Per dot the dedupe is
    * structural (R47): the same dot on both sides with structurally equal patches collapses to that
    * one value — which side supplies it is indistinguishable, making the merge symmetric — while
    * structurally different values are corruption, failing BEFORE the replay and the write
    * (§3.5/R38, D11's dot cross-check step; test 16 pins `patch collision: a@x revision 1`). The
    * leftmost collision in dot order decides, so the reported error is deterministic and
    * direction-independent.
    */
  private[cli] def unionPatches(
      left: Vector[Patch],
      right: Vector[Patch]
  ): Either[SnapError, Vector[Patch]] =
    @tailrec
    def loop(i: Int, j: Int, acc: Vector[Patch]): Either[SnapError, Vector[Patch]] =
      if i >= left.length then Right(acc ++ right.drop(j))
      else if j >= right.length then Right(acc ++ left.drop(i))
      else
        val a = left(i)
        val b = right(j)
        val byAuthor = ContributorId.ordering.compare(a.author, b.author)
        val byDot =
          if byAuthor != 0 then byAuthor else java.lang.Long.compare(a.revision, b.revision)
        if byDot < 0 then loop(i + 1, j, acc :+ a)
        else if byDot > 0 then loop(i, j + 1, acc :+ b)
        else if a == b then loop(i + 1, j + 1, acc :+ a)
        else Left(SnapError.PatchCollision(a.dot))
    loop(0, 0, Vector.empty)

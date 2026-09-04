package snap.cli

import snap.core.Dot
import snap.core.Patch
import snap.core.Replay
import snap.core.Repo
import snap.core.Repository
import snap.core.SnapError
import snap.core.Tree
import snap.core.Version
import snap.fs.Materialize
import snap.fs.Store
import snap.fs.WorkTree

/** `snap revert <version>` (SPEC §7.7, R88): requires contributor configuration, a clean working
  * tree, and a locally known target version; authors ONE new patch (message `revert to <version>`,
  * exempt from `commit`'s 4096-byte limit — D16) whose changes diff the current tree into the
  * target's; installs the target's file contents ([[Materialize.install]], §10 mutation order);
  * atomically replaces `repository.json`; prints the NEW version. The new patch is a normal forward
  * increment of the configured contributor (R46) based on the CURRENT frontier, not the target
  * version, so the frontier only ever grows — revert never removes a patch or moves the frontier
  * backward.
  *
  * Check order (each step's error is the command's outcome). SPEC §10/R103 groups "parsing,
  * repository validation, replay, dirty-tree checks, and target-tree construction" before any
  * write; §7.7 separately lists "contributor configuration, a clean working tree, and a locally
  * known target version" as revert's preconditions. The two orderings are reconciled by test
  * evidence (test 14: reverting to an unrecognized target version reports `unknown version` even
  * with no contributor configured at all — so the target-known check must precede the contributor-
  * id requirement; Notes / decisions): repository load+validate → parse the version operand →
  * target known/materialize (R45, via [[Replay.materialize]]) → contributor id (R100) →
  * working-tree scan (R104) → dirty requirement (R27) → equal-trees short-circuit (R88) → diff
  * current→target → one patch → full validation of the would-be repository → install → atomic
  * write.
  */
object CommandsRevert:

  val handler: CommandHandler = (env, repoRoot, operands) =>
    for
      versionText <- parseOperand(operands)
      root <- Commands.requireRoot(repoRoot)
      valid <- Commands.readRepository(root)
      targetVersion <- Version.parse(versionText)
      // The warning half (R74) is intentionally discarded: SPEC §7.7 gives revert no
      // warning-reporting obligation of its own — only `merge` prints warnings (R75, T17).
      targetTree <- Replay.materialize(valid.structure, targetVersion).map(_._1)
      author <- Config.requireContributorId(env, root)
      working <- WorkTree.scan(root)
      _ <- requireClean(valid.tree, working)
      _ <- requireDifferent(valid.tree, targetTree)
      frontier = valid.repository.frontier
      revision <- CommandsCommit.nextRevision(frontier, author)
      _ <- CommandsCommit.checkNoCollision(valid.repository.patches, Dot(author, revision))
      changes = CommandsCommit.buildChanges(WorkingChanges.compute(valid.tree, targetTree))
      patch <- Patch.make(author, revision, frontier, revertMessage(targetVersion), changes)
      result <- patch.result
      next = Repository(result, CommandsCommit.insertSorted(valid.repository.patches, patch))
      // Defensive gate, mirroring CommandsCommit: the repository file never receives a value that
      // would not read back valid.
      _ <- Repo.validateFully(next)
      _ <- Materialize.install(root, valid.tree, targetTree)
      _ <- Store.writeRepository(Commands.repositoryFile(root), next)
    yield result.canonicalText + "\n"

  /** Exactly one operand — the target version's canonical text (T13 owns the exhaustive grammar
    * matrix; this coarse arity check mirrors [[CommandsCommit.parseMessage]]/
    * [[CommandsInit.parsePath]]).
    */
  private def parseOperand(operands: List[String]): Either[SnapError, String] = operands match
    case version :: Nil => Right(version)
    case _              => Left(SnapError.InvalidCommand)

  /** R27/R85's dirty check, reusing the one canonical current-vs-working comparison
    * ([[WorkingChanges]]) that `status`/`commit` use.
    */
  private def requireClean(current: Tree, working: Tree): Either[SnapError, Unit] =
    Either.cond(WorkingChanges.compute(current, working).isEmpty, (), SnapError.WorkingTreeDirty)

  /** SPEC §7.7's equal-trees short-circuit: `Tree` equality is structural (same paths, same bytes),
    * so this is exactly "the current and target trees are equal".
    */
  private def requireDifferent(current: Tree, target: Tree): Either[SnapError, Unit] =
    Either.cond(current != target, (), SnapError.TargetTreeAlreadyCurrent)

  /** SPEC §7.7: `revert to <version>`, using the parsed version's own canonical text rather than
    * the raw operand — R31 requires CLI version arguments to already be canonical, so the two
    * always agree, and this is defensively immune to any future relaxation of that requirement.
    */
  private def revertMessage(target: Version): String = s"revert to ${target.canonicalText}"

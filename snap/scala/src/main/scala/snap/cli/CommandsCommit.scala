package snap.cli

import snap.core.Change
import snap.core.ContributorId
import snap.core.Diff
import snap.core.Dot
import snap.core.Patch
import snap.core.Repo
import snap.core.Repository
import snap.core.Revision
import snap.core.SnapError
import snap.core.TextTokens
import snap.core.Version
import snap.fs.Store
import snap.fs.WorkTree

import java.nio.charset.StandardCharsets

/** `snap commit <message>` (SPEC §7.5, R85): requires contributor configuration and a dirty working
  * tree; diffs the complete current tree against the complete working tree; authors ONE patch based
  * on the current frontier, incrementing the configured contributor's revision (R46); atomically
  * replaces `repository.json` (the working files are already in place — §10) and prints the new
  * version.
  *
  * Check order (each step's error is the command's outcome): repository load+validate → contributor
  * id (R100) → message rules (R48 + D16 — before the tree checks: test 25 pins
  * `invalid commit message` for an empty message even on a CLEAN tree) → working-tree scan (R104) →
  * dirty requirement (R26/R85) → revision bounds and dot collision → full validation of the
  * would-be repository → atomic write.
  */
object CommandsCommit:

  /** D16: the commit-message limit, in UTF-8 BYTES (not characters). */
  private val MaxMessageBytes: Int = 4096

  val handler: CommandHandler = (env, repoRoot, operands) =>
    for
      message <- parseMessage(operands)
      root <- Commands.requireRoot(repoRoot)
      valid <- Commands.readRepository(root)
      author <- Config.requireContributorId(env, root)
      _ <- checkCommitMessage(message)
      working <- WorkTree.scan(root)
      deltas = WorkingChanges.compute(valid.tree, working)
      _ <- Either.cond(deltas.nonEmpty, (), SnapError.WorkingTreeClean)
      frontier = valid.repository.frontier
      revision <- nextRevision(frontier, author)
      _ <- checkNoCollision(valid.repository.patches, Dot(author, revision))
      patch <- Patch.make(author, revision, frontier, message, buildChanges(deltas))
      result <- patch.result
      next = Repository(result, insertSorted(valid.repository.patches, patch))
      // Defensive gate in the R103 spirit: the repository file never receives a value that would
      // not read back valid. Unreachable-failure by construction (the changes reproduce the
      // scanned working tree over the validated current tree), so the cost is one extra replay.
      _ <- Repo.validateFully(next)
      _ <- Store.writeRepository(Commands.repositoryFile(root), next)
    yield CommandOutput(ResultKind.Success("Committed"), result.canonicalText + "\n")

  /** Exactly one operand — the message, whatever its shape (messages may legitimately contain or
    * start with anything R48 allows; test 04 commits a message holding tab, LF, and backslash). T13
    * owns the exhaustive grammar matrix.
    */
  private def parseMessage(operands: List[String]): Either[SnapError, String] = operands match
    case message :: Nil => Right(message)
    case _              => Left(SnapError.InvalidCommand)

  /** R48's character rules (the one canonical implementation, [[Patch.checkMessage]]) plus D16's
    * 4096-UTF-8-byte input limit; every violation renders as the single pinned `invalid commit
    * message` line (test 25 pins the empty case; the rest of the class is untested wording). The
    * byte length is measured only after the character rules pass, so `getBytes` never substitutes
    * (the message is known encodable).
    */
  private[cli] def checkCommitMessage(message: String): Either[SnapError, Unit] =
    val ok = Patch.checkMessage(message).isRight &&
      message.getBytes(StandardCharsets.UTF_8).length <= MaxMessageBytes
    Either.cond(ok, (), SnapError.InvalidCommitMessage)

  /** R46/R85: the author's next revision on the current frontier. `frontier(author) + 1` cannot
    * overflow a `Long` (frontier counters are ≤ 2^53−1 by validation), and a frontier already at
    * the maximum makes the next revision out of bounds — R85's "overflow" error, reported through
    * R30's [[Revision.check]] (untested wording).
    */
  private[cli] def nextRevision(
      frontier: Version,
      author: ContributorId
  ): Either[SnapError, Long] =
    Revision.check(frontier.get(author) + 1L)

  /** R85's "dot collision is an error". Unreachable after §4.5 validation — `patches` is exactly
    * the frontier's closure, so every existing revision of `author` is ≤ `frontier(author)` — but
    * the spec names the case, so it is checked rather than assumed (pinned `patch collision` shape
    * reused, D5).
    */
  private[cli] def checkNoCollision(
      patches: Vector[Patch],
      dot: Dot
  ): Either[SnapError, Unit] =
    Either.cond(!patches.exists(_.dot == dot), (), SnapError.PatchCollision(dot))

  /** R85's change-kind selection, per delta (already in path order, giving R49's sorted changes —
    * one per path):
    *
    *   - removed → `delete`;
    *   - a `text` change when the new content is text AND the old side is absent or text — the old
    *     token sequence is empty on absence, so a new text file is a creation edit (R58; a new
    *     EMPTY file gets the empty script);
    *   - otherwise `put` with the exact new bytes.
    *
    * The script comes from the one canonical diff (R61) applied to the old/new token sequences, so
    * applying the change to the current tree reproduces the working tree byte-exactly (tokenization
    * is lossless and UTF-8 text round-trips).
    */
  private[cli] def buildChanges(deltas: Vector[Delta]): Vector[Change] =
    deltas.map { delta =>
      (delta.before, delta.after) match
        case (_, None)             => Change.Delete(delta.path)
        case (before, Some(after)) =>
          val newTokens = TextTokens.tokenizeBytes(toArray(after))
          val oldTokens = before match
            case None        => Some(Vector.empty[String])
            case Some(bytes) => TextTokens.tokenizeBytes(toArray(bytes))
          (oldTokens, newTokens) match
            case (Some(o), Some(n)) => Change.Text(delta.path, Diff.diff(o, n))
            case _                  => Change.Put(delta.path, after)
    }

  /** Inserts the new patch preserving R44's file order (author in `Utf8Order`, then numeric
    * revision), so the written repository stays canonically sorted.
    */
  private[cli] def insertSorted(patches: Vector[Patch], patch: Patch): Vector[Patch] =
    val idx = patches.indexWhere { p =>
      val byAuthor = ContributorId.ordering.compare(p.author, patch.author)
      byAuthor > 0 || (byAuthor == 0 && p.revision > patch.revision)
    }
    if idx < 0 then patches :+ patch else patches.patch(idx, Vector(patch), 0)

  private def toArray(bytes: IArray[Byte]): Array[Byte] =
    IArray.genericWrapArray(bytes).toArray

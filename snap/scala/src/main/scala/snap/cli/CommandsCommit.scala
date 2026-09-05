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
import snap.core.Tree
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
      validated <- Repo.validateFully(next)
      // T23 (phase-2 review, "new" finding — same class as finding #1, a different second source of
      // truth): `validateFully`'s own success only proves `next` is INTERNALLY consistent — it says
      // nothing about whether the tree it replays to actually matches `working`, the tree
      // `WorkTree.scan` just read off disk. Mirrors `CommandsRevert.requireReplayMatchesInstalled`
      // exactly, comparing against `working` (the natural target here — `commit` installs no
      // separately-computed tree the way `revert` installs `targetTree`, so `working` IS what
      // `repository.json` must describe once written).
      _ = requireChangesReproduceWorking(validated.tree, working)
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
    *
    * Also the commit-time half of R37's serial-contributor rule ("for each contributor, revision n
    * has exactly one patch and follows n-1"): computing the new revision as `frontier(author) + 1`,
    * never anything else, makes a gap or an out-of-sequence revision unrepresentable by
    * construction — there is no code path in `commit` that could author `n+2` while skipping `n+1`,
    * or `n-1` while `n` already exists locally. R37's other half — one contributor ID authoring
    * CONCURRENTLY in disconnected copies — can only be discovered when two such copies later meet
    * (`merge`'s dot-collision check, R38/R47, T17); a single local `commit` cannot violate it
    * against itself.
    */
  private[cli] def nextRevision(
      frontier: Version,
      author: ContributorId
  ): Either[SnapError, Long] =
    Revision.check(frontier.get(author) + 1L)

  /** R85's "dot collision is an error" (R37's defensive backstop: even if [[nextRevision]] were
    * ever wrong, no dot already in `patches` could be re-authored unnoticed). Unreachable after
    * §4.5 validation — `patches` is exactly the frontier's closure, so every existing revision of
    * `author` is ≤ `frontier(author)` — but the spec names the case, so it is checked rather than
    * assumed (pinned `patch collision` shape reused, D5).
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

  /** Internal invariant guard for the defensive gate above (T23, mirrors
    * [[CommandsRevert.requireReplayMatchesInstalled]] exactly): `replayedTree` — the tree obtained
    * by fully re-replaying the new repository from scratch, via the general concurrent-integration
    * engine ([[Repo.validateFully]]) — must equal `working`, the tree [[snap.fs.WorkTree.scan]]
    * just read off disk and about to be described by the `repository.json` this handler is around
    * to write.
    *
    * CONFIRMED not reachable today: `buildChanges` derives every change from the one canonical diff
    * (R61) between `valid.tree` and `working`, so applying that patch to `valid.tree` — which is
    * exactly what `Repo.validateFully`'s own replay of `next` does, since `commit` introduces no
    * concurrency (one new patch, serially appended to an already-integrated frontier) — reproduces
    * `working` byte-for-byte by construction. This exists purely to catch a future divergence
    * between the two paths (e.g. a `Diff`/`EditScript` bug that fails to round-trip some content) —
    * which would otherwise mean `repository.json` commits to a value whose own replay produces a
    * tree different from what is actually on disk (the R103/R106 "metadata claims a tree that was
    * never written" scenario).
    *
    * That is an internal invariant violation, not a user-facing error, so per D4/R107 it is routed
    * to `Main`'s top-level catch-all (exit 2) by raising here — never as a normal
    * `Left(SnapError...)` (which would incorrectly surface as an exit-1 diagnostic a user could
    * trigger).
    */
  private[cli] def requireChangesReproduceWorking(replayedTree: Tree, working: Tree): Unit =
    if replayedTree != working then
      // Sole sanctioned route to D4's exit-2 top-level catch-all (Main.run) for an internal
      // invariant violation; see the doc comment above. SnapError/Either stays reserved for
      // user-facing, exit-1 outcomes.
      throw new IllegalStateException( // scalafix:ok DisableSyntax.throw
        "commit: replayed repository tree does not match the scanned working tree " +
          "(internal invariant violation, phase-2 review — same class as finding #1)"
      )

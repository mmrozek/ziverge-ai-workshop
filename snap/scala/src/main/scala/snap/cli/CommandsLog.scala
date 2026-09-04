package snap.cli

import snap.core.Dot
import snap.core.Patch
import snap.core.Replay
import snap.core.Repo
import snap.core.Version

/** `snap log` (SPEC §7.4, R84): the frontier's patches in REVERSE canonical integration order, one
  * tab-separated `<result-version>\t<author>\t<message>` line each, with backslash, tab, and LF in
  * the message escaped as `\\`, `\t`, `\n` — in that order. An empty history prints nothing and
  * succeeds. `log` never scans the working tree.
  */
object CommandsLog:

  val handler: CommandHandler = (_, repoRoot, operands) =>
    for
      _ <- Commands.expectNoOperands(operands)
      root <- Commands.requireRoot(repoRoot)
      valid <- Commands.readRepository(root)
      // The canonical integration order is the specified observable (R66); this is exactly what
      // Replay.integrationOrder exists for. It cannot fail here — readRepository already replayed
      // the frontier — but the type is Either, so the error is propagated rather than forced.
      order <- Replay.integrationOrder(
        valid.structure,
        valid.repository.frontier,
        Replay.LinearOnly
      )
    yield render(valid, order)

  private def render(valid: Repo.Valid, order: Vector[Dot]): String =
    // Probed by key only — never iterated — so it feeds no ordering decision; the output order is
    // the reversed integration order alone.
    val byDot: Map[Dot, (Patch, Version)] =
      valid.repository.patches.indices.iterator.map { i =>
        val patch = valid.repository.patches(i)
        patch.dot -> (patch, valid.results(i))
      }.toMap
    order.reverseIterator
      // Total: every ordered dot names a selected patch of the repository (Replay invariant);
      // `get` keeps the impossible miss branch silent-safe without a throwing `apply`.
      .flatMap(byDot.get)
      .map { (patch, result) =>
        s"${result.canonicalText}\t${patch.author.value}\t${escape(patch.message)}\n"
      }
      .mkString

  /** R84's escape rule, in the normative order (gotcha 8): backslash FIRST — so the backslashes
    * introduced for tab and LF are never themselves re-escaped — then tab, then LF.
    */
  private[cli] def escape(message: String): String =
    message.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

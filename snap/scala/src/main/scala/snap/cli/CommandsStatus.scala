package snap.cli

import snap.core.Tree
import snap.core.Version
import snap.fs.WorkTree

/** `snap status` (SPEC §7.3, R83): the current version line, then the working changes vs the
  * current tree, sorted by path. Read-only: it inspects a dirty tree without complaint, but MUST
  * fail on an unsupported working-tree entry (R104 — the scan itself errors). A clean repository
  * prints only the version line.
  */
object CommandsStatus:

  val handler: CommandHandler = (_, repoRoot, operands) =>
    for
      _ <- Commands.expectNoOperands(operands)
      root <- Commands.requireRoot(repoRoot)
      // D11 precedence: local repository parse+validate before the working-tree scan.
      valid <- Commands.readRepository(root)
      working <- WorkTree.scan(root)
    yield render(valid.repository.frontier, valid.tree, working)

  /** Plain layout pinned by test 04: `version <v>` first, then one `<code> <path>` row per delta in
    * path order — `A` absent→present, `M` changed bytes, `D` present→absent (R83).
    */
  private def render(frontier: Version, current: Tree, working: Tree): String =
    val header = s"version ${frontier.canonicalText}\n"
    val rows = WorkingChanges.compute(current, working).iterator.map { delta =>
      val code = (delta.before, delta.after) match
        case (None, Some(_))    => "A"
        case (Some(_), Some(_)) => "M"
        case _                  => "D"
      s"$code ${delta.path.value}\n"
    }
    header + rows.mkString

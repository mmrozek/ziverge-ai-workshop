package snap.cli

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
    yield
      val deltas = WorkingChanges.compute(valid.tree, working)
      val version = valid.repository.frontier.canonicalText
      CommandOutput(ResultKind.Status(version, deltas), render(version, deltas))

  /** Plain layout pinned by test 04: `version <v>` first, then one `<code> <path>` row per delta in
    * path order — `A` absent→present, `M` changed bytes, `D` present→absent (R83). `deltas` is
    * computed once by the handler and shared with [[ResultKind.Status]] (T22) so terminal rendering
    * is never a reparse of this text.
    */
  private def render(version: String, deltas: Vector[Delta]): String =
    val header = s"version $version\n"
    val rows = deltas.iterator.map { delta =>
      val code = (delta.before, delta.after) match
        case (None, Some(_))    => "A"
        case (Some(_), Some(_)) => "M"
        case _                  => "D"
      s"$code ${delta.path.value}\n"
    }
    header + rows.mkString

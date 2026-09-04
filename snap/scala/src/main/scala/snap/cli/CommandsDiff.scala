package snap.cli

import snap.core.SnapError
import snap.fs.WorkTree

/** T10 seam for `snap diff` (SPEC §7.6): only the no-argument form's failure-precedence prefix —
  * local repository load+validate, then the working-tree scan (D11; test 08's diff step pins the
  * `unsupported working tree entry` outcome) — with the actual comparison and rendering still the
  * T08 stub error. T11 replaces the [[SnapError.NotImplemented]] tails with the real diff and owns
  * the `<old> <new> [--repo <repository>]` forms entirely.
  */
object CommandsDiff:

  val handler: CommandHandler = (_, repoRoot, operands) =>
    operands match
      case Nil =>
        for
          root <- Commands.requireRoot(repoRoot)
          _ <- Commands.readRepository(root)
          _ <- WorkTree.scan(root)
          rendered <- Left(SnapError.NotImplemented): Either[SnapError, String] // T11
        yield rendered
      case _ => Left(SnapError.NotImplemented) // versioned forms: T11

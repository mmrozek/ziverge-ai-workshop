package snap.cli

import snap.core.Replay
import snap.core.SnapError
import snap.core.Version
import snap.fs.WorkTree

/** `snap diff` (SPEC §7.6; DESIGN §8, D8; R31, R45, R79, R86–R87). Two local forms:
  *
  *   - no arguments: the materialized current (frontier) tree vs the working tree — repo
  *     load+validate, then the working-tree scan (D11's failure precedence; test 08's diff step
  *     pins the `unsupported working tree entry` outcome at this seam, inherited from T10);
  *   - `diff <old> <new>`: two locally KNOWN versions (R45 via [[Replay.materialize]], which checks
  *     known-ness before replaying) — both operands parsed as canonical versions first (R31).
  *
  * `--repo <repository>` is accepted by the grammar (SPEC §7.6's fenced block) so a well-formed
  * four-operand invocation never falls into the generic usage error, but its actual remote
  * resolution is [[SnapError.NotImplemented]] until T20/T21 wire cross-repository diff. Every other
  * shape is `diff`'s own distinct usage channel ([[SnapError.DiffUsage]], DESIGN §8) — never the
  * generic [[SnapError.InvalidCommand]] (tests 14/24) — since T13 owns the exhaustive per-command
  * grammar matrix and only needs this one carved out early.
  *
  * Every branch is a single `for`-comprehension: [[Cli.emit]] only sees the final `Either`, so a
  * later validation failure (R86: "validate every repository and version before producing output")
  * can never follow partial output onto the stream — there is no code path that emits before this
  * function returns.
  */
object CommandsDiff:

  val handler: CommandHandler = (_, repoRoot, operands) =>
    operands match
      case Nil =>
        for
          root <- Commands.requireRoot(repoRoot)
          valid <- Commands.readRepository(root)
          working <- WorkTree.scan(root)
        yield DiffRender.render(valid.tree, working)
      case oldText :: newText :: Nil =>
        for
          oldVersion <- parseVersionArg(oldText)
          newVersion <- parseVersionArg(newText)
          root <- Commands.requireRoot(repoRoot)
          valid <- Commands.readRepository(root)
          oldTree <- Replay.materialize(valid.structure, oldVersion, Replay.LinearOnly)
          newTree <- Replay.materialize(valid.structure, newVersion, Replay.LinearOnly)
        yield DiffRender.render(oldTree, newTree)
      // Grammar-valid `--repo` shape (SPEC §7.6); remote resolution lands in T20/T21.
      case _ :: _ :: "--repo" :: _ :: Nil => Left(SnapError.NotImplemented)
      case _                              => Left(SnapError.DiffUsage)

  /** R31: a `diff` version operand that fails [[Version.parse]] renders uniformly as `invalid
    * version: <raw>`, echoing the offending text rather than the specific typed reason (T11 Notes /
    * decisions — mirrors D9's `invalid port: <arg>`; neither test 19 nor test 25 pins a reason,
    * only the `invalid version` class).
    */
  private def parseVersionArg(text: String): Either[SnapError, Version] =
    Version.parse(text).left.map(_ => SnapError.InvalidVersionArgument(text))

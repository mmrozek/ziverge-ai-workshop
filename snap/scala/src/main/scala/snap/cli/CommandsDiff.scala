package snap.cli

import snap.core.Replay
import snap.core.SnapError
import snap.core.Version
import snap.fs.WorkTree

/** `snap diff` (SPEC §7.6; DESIGN §8, D8; R31, R45, R78–R79, R86–R87, R102). Three forms:
  *
  *   - no arguments: the materialized current (frontier) tree vs the working tree — repo
  *     load+validate, then the working-tree scan (D11's failure precedence; test 08's diff step
  *     pins the `unsupported working tree entry` outcome at this seam, inherited from T10);
  *   - `diff <old> <new>`: two locally KNOWN versions (R45 via [[Replay.materialize]], which checks
  *     known-ness before replaying) — both operands parsed as canonical versions first (R31);
  *   - `diff <old> <new> --repo <repository>` (T20/T21): `old` resolves in the LOCAL repository,
  *     `new` in `repository` — a local path or an `http(s)://` URL alike
  *     ([[Commands.loadRemoteRepository]]), never imported. R86's "validate every repository and
  *     version before output" plus its cross-repository corruption check (§3.5/R38: "fail as
  *     corrupt if its parsed patch values differ", test 16) both run before either side is
  *     materialized — reusing [[CommandsMerge.unionPatches]] for exactly its dot cross-check (the
  *     union it returns is discarded; `diff` never merges). No working tree is touched by this
  *     form.
  *
  * Every other shape is `diff`'s own distinct usage channel ([[SnapError.DiffUsage]], DESIGN §8) —
  * never the generic [[SnapError.InvalidCommand]] (tests 14/24) — since T13 owns the exhaustive
  * per-command grammar matrix and only needs this one carved out early.
  *
  * Every branch is a single `for`-comprehension: [[Cli.emit]] only sees the final `Either`, so a
  * later validation failure (R86: "validate every repository and version before producing output")
  * can never follow partial output onto the stream — there is no code path that emits before this
  * function returns.
  */
object CommandsDiff:

  /** `diff`'s three documented shapes (SPEC §7.6), each carrying exactly the operand text its
    * branch needs. The ONE canonical shape parser (T23, phase-2 review finding 3):
    * [[Grammar.diffRule]] used to mirror this same three-case pattern match independently — a
    * one-sided edit could silently change which of the two decided an outcome, with no compiler or
    * test signal, since the handler-side copy was unreachable through [[Cli.run]]
    * ([[Grammar.check]] always runs first). `Grammar` now calls [[parseShape]] directly instead of
    * re-declaring the shapes.
    */
  private[cli] enum Shape:
    case NoArgs
    case TwoVersions(oldText: String, newText: String)
    case CrossRepo(oldText: String, newText: String, repoOperand: String)

  /** Recognizes `diff`'s three shapes, or [[SnapError.DiffUsage]] for anything else — `diff`'s own
    * distinct usage channel (DESIGN §8), never the generic [[SnapError.InvalidCommand]] (tests
    * 14/24).
    */
  private[cli] def parseShape(operands: List[String]): Either[SnapError, Shape] = operands match
    case Nil                       => Right(Shape.NoArgs)
    case oldText :: newText :: Nil => Right(Shape.TwoVersions(oldText, newText))
    case oldText :: newText :: "--repo" :: repoOperand :: Nil =>
      Right(Shape.CrossRepo(oldText, newText, repoOperand))
    case _ => Left(SnapError.DiffUsage)

  val handler: CommandHandler = (env, repoRoot, operands) =>
    parseShape(operands).flatMap {
      case Shape.NoArgs =>
        for
          root <- Commands.requireRoot(repoRoot)
          valid <- Commands.readRepository(root)
          working <- WorkTree.scan(root)
        yield CommandOutput(ResultKind.Diff, DiffRender.render(valid.tree, working))
      case Shape.TwoVersions(oldText, newText) =>
        for
          oldVersion <- parseVersionArg(oldText)
          newVersion <- parseVersionArg(newText)
          root <- Commands.requireRoot(repoRoot)
          valid <- Commands.readRepository(root)
          // `diff <old> <new>` never reports warnings (SPEC §7.6 has no warning output for this
          // form — only `merge`'s R75 set subtraction does, T17); the historical auto-resolution
          // pairs from replaying two arbitrary known versions are discarded here, not silently
          // dropped from a place that needs them (contrast `Repo.Valid.warnings`, threaded through
          // by `Store`/`Repo` for that exact reason).
          oldReplay <- Replay.materialize(valid.structure, oldVersion)
          newReplay <- Replay.materialize(valid.structure, newVersion)
        yield CommandOutput(ResultKind.Diff, DiffRender.render(oldReplay._1, newReplay._1))
      case Shape.CrossRepo(oldText, newText, repoOperand) =>
        for
          oldVersion <- parseVersionArg(oldText)
          newVersion <- parseVersionArg(newText)
          root <- Commands.requireRoot(repoRoot)
          local <- Commands.readRepository(root) // D11-style: local parse+validate first
          remote <- Commands.loadRemoteRepository(env, repoOperand) // local path or one HTTP GET
          // R86/§3.5: every dot present in both repositories must carry the same patch value —
          // reusing `unionPatches` purely for that cross-check (test 16); the merged vector it
          // would also return is of no use to `diff`, which never unions or writes anything.
          _ <- CommandsMerge.unionPatches(local.repository.patches, remote.repository.patches)
          oldReplay <- Replay.materialize(local.structure, oldVersion)
          newReplay <- Replay.materialize(remote.structure, newVersion)
        yield CommandOutput(ResultKind.Diff, DiffRender.render(oldReplay._1, newReplay._1))
    }

  /** R31: a `diff` version operand that fails [[Version.parse]] renders uniformly as `invalid
    * version: <raw>`, echoing the offending text rather than the specific typed reason (T11 Notes /
    * decisions — mirrors D9's `invalid port: <arg>`; neither test 19 nor test 25 pins a reason,
    * only the `invalid version` class). T23: the one canonical implementation, also used by
    * [[CommandsRevert]] so the two commands' invalid-version-syntax wording can never diverge
    * (holdout exposure 3).
    */
  private[cli] def parseVersionArg(text: String): Either[SnapError, Version] =
    Version.parse(text).left.map(_ => SnapError.InvalidVersionArgument(text))

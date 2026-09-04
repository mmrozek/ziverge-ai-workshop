package snap.cli

import snap.core.ContributorId
import snap.core.SnapError
import snap.fs.Store

import java.nio.file.Path
import java.nio.file.Paths

/** Contributor-configuration file locations and the read-side precedence rule (SPEC §8, R98–R100).
  * Shared by `snap config` ([[CommandsConfig]], this task) and `commit`/`revert` (T10/T12), which
  * both need [[requireContributorId]] to resolve the author of a new patch — T06 deferred this
  * whole module to T09 (see the T06 task notes).
  */
object Config:

  /** `.snap/config.json` inside a repository (SPEC §8). */
  def localFile(repoRoot: Path): Path = repoRoot.resolve(".snap").resolve(Store.ConfigFileName)

  /** `$HOME/.snapconfig.json`, or `None` when `HOME` is absent from the environment (R99: "If
    * `$HOME` is absent, global configuration is unavailable" — not an error by itself on the read
    * side; only a `--global` *write* turns this into [[SnapError.GlobalConfigUnavailable]], in
    * [[CommandsConfig]]).
    */
  def globalFile(env: Env): Option[Path] =
    env.env.get("HOME").map(home => Paths.get(home).resolve(Store.GlobalConfigFileName))

  /** R99's precedence: read and validate the local file first; if it yields an id, the global file
    * is never read at all (so a malformed global file is irrelevant whenever local provides an id —
    * test 03's premise). Otherwise read the global file, if `HOME` is set. A missing file is
    * `None`, never an error; a file that IS read and is malformed, has an unknown/duplicate field,
    * or holds an invalid id, is an error (R99) — propagated as-is from [[Store.readConfig]].
    */
  def resolve(env: Env, repoRoot: Path): Either[SnapError, Option[ContributorId]] =
    Store.readConfig(localFile(repoRoot)).flatMap {
      case Some(id) => Right(Some(id))
      case None     =>
        globalFile(env) match
          case Some(file) => Store.readConfig(file)
          case None       => Right(None)
    }

  /** R100: `commit`/`revert` require a contributor id; the exact pinned message (test 19) when
    * neither local nor global configuration provides one.
    */
  def requireContributorId(env: Env, repoRoot: Path): Either[SnapError, ContributorId] =
    resolve(env, repoRoot).flatMap(_.toRight(SnapError.ContributorIdRequired))

package snap.cli

/** Output seam (DESIGN §8, R92–R97): every command result or error funnels through here, so T22's
  * terminal renderer can be selected per stream without any call site changing. Presentation never
  * influences execution, effects, warning selection/order, or exit status (R92) — [[Cli]] alone
  * decides those; a `Presentation` only turns already-decided outcomes into bytes.
  */
trait Presentation:

  /** A successful command's stdout text, already fully formatted (including any trailing LF). Empty
    * text prints nothing — several commands succeed silently (e.g. `config`, spec §7.2).
    */
  def result(env: Env, text: String): Unit

  /** An error detail, without the `snap: ` prefix (spec §10: plain errors are one line
    * `snap: <detail>`).
    */
  def error(env: Env, detail: String): Unit

  /** A warning detail, without the `warning: ` prefix (spec §6.4/§7.11: a plain warning is one line
    * `warning: <detail>`; terminal mode restyles it — T22). Only `merge` emits warnings (R75, T17);
    * the command decides selection and order (R92), a `Presentation` only renders each line.
    */
  def warning(env: Env, detail: String): Unit

object Presentation:

  /** The byte-stable presentation (spec §7.11) — the only implementation until T22 adds `Terminal`.
    * Every stream renders plain for now, regardless of `SNAP_COLOR`/`NO_COLOR`/TTY: T08 only
    * validates `SNAP_COLOR`'s value (R95); actually selecting a renderer per stream is T22's job.
    */
  object Plain extends Presentation:
    def result(env: Env, text: String): Unit =
      if text.nonEmpty then env.stdout.print(text)

    def error(env: Env, detail: String): Unit =
      // Literal LF, not `println` (which appends the platform `line.separator`) — spec §10 requires
      // LF line endings regardless of host/JVM (PR2/CR6).
      env.stderr.print(s"snap: $detail\n")

    def warning(env: Env, detail: String): Unit =
      // Same literal-LF rule as `error`; tests 10/11/17 byte-pin whole warning lines.
      env.stderr.print(s"warning: $detail\n")

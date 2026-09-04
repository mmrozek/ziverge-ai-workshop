package snap.cli

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

/** Per-stream TTY probe (DESIGN §8/D20, R93/R108). Behind a trait so unit tests inject fake values
  * (R108 requires unit-testing `auto` selection for TTY and non-TTY stdout/stderr independently,
  * since the shared harness pipes both streams and can never present a real TTY — SPEC-NOTES §3.3).
  */
trait Tty:
  def isStdoutTty: Boolean
  def isStderrTty: Boolean

object Tty:

  /** Placeholder until T22 wires the real probe (D20: a child `/bin/sh -c "test -t N"` process with
    * fd inheritance — the JDK has no per-stream `isatty`). Always reports non-TTY, which matches
    * what the harness observes anyway, so `SNAP_COLOR=auto` degrades to plain either way before T22
    * lands.
    */
  case object Stub extends Tty:
    def isStdoutTty: Boolean = false
    def isStderrTty: Boolean = false

/** The effect boundary (DESIGN §2/§8), built exactly once in [[Main]] from real process state.
  * Everything below `Main` takes an `Env` value — no `sys.env`/`System.getenv` call exists anywhere
  * else in the codebase, which is what makes repo discovery and presentation selection (R108)
  * unit-testable with fakes (in-memory sinks, a fake `cwd`/`env` map).
  *
  * @param cwd
  *   process working directory; repo discovery (R77) walks up from here
  * @param env
  *   the full process environment, captured once (`HOME`, `SNAP_COLOR`, `NO_COLOR`, ...) —
  *   downstream code reads only this map, never the ambient environment
  * @param stdout
  *   UTF-8 sink for results (D22)
  * @param stderr
  *   UTF-8 sink for warnings/errors (D22)
  * @param tty
  *   per-stream TTY probe (real probe lands in T22; [[Tty.Stub]] until then)
  */
final case class Env(
    cwd: Path,
    env: Map[String, String],
    stdout: PrintStream,
    stderr: PrintStream,
    tty: Tty
)

object Env:

  /** Builds the real `Env` (DESIGN §2: `Main` is the only place touching real env, real streams,
    * and `System.exit`). stdout/stderr are wrapped as UTF-8 `PrintStream`s unconditionally (D22) —
    * the harness runs under `LANG=C`/`LC_ALL=C` (gotcha 7), where the JVM's platform-default
    * charset must never be relied on. Auto-flush is on so the `--serve` readiness line (R90)
    * reaches the harness immediately.
    */
  def real(): Env =
    Env(
      cwd = Paths.get(System.getProperty("user.dir")),
      env = sys.env,
      stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8),
      stderr = new PrintStream(System.err, true, StandardCharsets.UTF_8),
      tty = Tty.Stub
    )

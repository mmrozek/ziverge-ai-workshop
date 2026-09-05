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

  /** Fixed non-TTY fixture for unit tests that don't care about TTY selection (most of them —
    * R108's matrix injects its own fakes for the cases that do).
    */
  case object Stub extends Tty:
    def isStdoutTty: Boolean = false
    def isStderrTty: Boolean = false

  /** D20's real probe, wired into [[Env.real]] as of T22: the JDK has no per-stream `isatty`, so
    * each check spawns a child `/bin/sh -c "test -t N"` with `Redirect.INHERIT` on exactly the file
    * descriptor under test — the child's fd *is* ours, so its own `test -t` answers for our stream.
    * Exit code 0 means that stream is a terminal. The other two child streams are discarded so the
    * probe never echoes anything of its own onto our stdout/stderr. Each `def` spawns fresh — never
    * memoized — which is what keeps probing lazy: [[Presentation.select]] only ever calls these
    * methods from its `auto`-and-`NO_COLOR`-absent branch, so a `never`/`always` selection or a
    * present `NO_COLOR` spawns no subprocess at all (R93/R108's negative constraint).
    */
  case object Real extends Tty:
    def isStdoutTty: Boolean = probe(fd = 1)
    def isStderrTty: Boolean = probe(fd = 2)

    private def probe(fd: Int): Boolean =
      val builder = new ProcessBuilder("/bin/sh", "-c", s"test -t $fd")
      if fd == 1 then
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
      else
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      val process = builder.start()
      process.waitFor() == 0

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
  *   per-stream TTY probe ([[Tty.Real]] in production as of T22; tests inject [[Tty.Stub]] or a
  *   fake)
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
      tty = Tty.Real
    )

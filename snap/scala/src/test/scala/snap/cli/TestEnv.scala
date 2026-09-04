package snap.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Shared fixture for CLI unit tests (T08): an [[Env]] backed by in-memory sinks so tests can
  * assert exact stdout/stderr bytes without touching the real console, plus a fake `cwd`/`env` map
  * so no test depends on the real process environment.
  */
object TestEnv:
  final case class Captured(
      env: Env,
      private val out: ByteArrayOutputStream,
      private val err: ByteArrayOutputStream
  ):
    def stdout: String = out.toString(StandardCharsets.UTF_8)
    def stderr: String = err.toString(StandardCharsets.UTF_8)

  def apply(cwd: Path, envMap: Map[String, String] = Map.empty, tty: Tty = Tty.Stub): Captured =
    val outBytes = new ByteArrayOutputStream()
    val errBytes = new ByteArrayOutputStream()
    val out = new PrintStream(outBytes, true, StandardCharsets.UTF_8)
    val err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)
    Captured(Env(cwd, envMap, out, err, tty), outBytes, errBytes)

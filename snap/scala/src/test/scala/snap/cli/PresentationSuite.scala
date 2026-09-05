package snap.cli

import snap.core.SnapPath

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** T22's terminal renderer (SPEC §7.11, R92–R97, R108; DESIGN §8, D20): [[Presentation.select]]'s
  * full R93–R95 matrix against injected fake [[Tty]] values (R108), the negative constraint that no
  * probe subprocess is spawned outside the one branch that needs it, exact byte-for-byte rendering
  * per [[ResultKind]] (mirroring test 28's cases directly, for our own debugging — the provided
  * suite remains the contract), and R92's "presentation changes no execution/effects/exit status"
  * property driven end to end through [[Cli.run]].
  */
class PresentationSuite extends munit.FunSuite:

  private def path(value: String): SnapPath = SnapPath.parse(value).toOption.get
  private def bytes(text: String): IArray[Byte] =
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  /** Records every probe call so tests can assert both the returned value AND whether the probe was
    * ever invoked at all — [[java.util.concurrent.atomic.AtomicInteger]] rather than `var` (no
    * mutable state needed beyond a counter, and the codebase's `DisableSyntax.noVars` rule is
    * syntactic on `var`, but a plain counter reads just as clearly with an atomic).
    */
  private final class CountingTty(stdout: Boolean, stderr: Boolean) extends Tty:
    val stdoutCalls = new AtomicInteger(0)
    val stderrCalls = new AtomicInteger(0)
    def isStdoutTty: Boolean =
      stdoutCalls.incrementAndGet()
      stdout
    def isStderrTty: Boolean =
      stderrCalls.incrementAndGet()
      stderr

  private def env(entries: Map[String, String], tty: Tty): Env =
    Env(Path.of("."), entries, System.out, System.err, tty)

  // --------------------------------------------------------------- R93–R95/R108: selection matrix

  test("always selects Terminal on both streams regardless of NO_COLOR or TTY-ness") {
    for
      noColor <- List(Map.empty[String, String], Map("NO_COLOR" -> ""), Map("NO_COLOR" -> "1"))
      stdoutTty <- List(false, true)
      stderrTty <- List(false, true)
    do
      val tty = new CountingTty(stdoutTty, stderrTty)
      val presenters = Presentation.select(env(Map("SNAP_COLOR" -> "always") ++ noColor, tty))
      assertEquals(presenters, Presenters(Presentation.Terminal, Presentation.Terminal))
      assertEquals(tty.stdoutCalls.get(), 0, "always must not probe stdout")
      assertEquals(tty.stderrCalls.get(), 0, "always must not probe stderr")
  }

  test("never selects Plain on both streams regardless of NO_COLOR or TTY-ness") {
    for
      noColor <- List(Map.empty[String, String], Map("NO_COLOR" -> ""), Map("NO_COLOR" -> "1"))
      stdoutTty <- List(false, true)
      stderrTty <- List(false, true)
    do
      val tty = new CountingTty(stdoutTty, stderrTty)
      val presenters = Presentation.select(env(Map("SNAP_COLOR" -> "never") ++ noColor, tty))
      assertEquals(presenters, Presenters(Presentation.Plain, Presentation.Plain))
      assertEquals(tty.stdoutCalls.get(), 0, "never must not probe stdout")
      assertEquals(tty.stderrCalls.get(), 0, "never must not probe stderr")
  }

  test("an invalid SNAP_COLOR value defensively selects Plain on both streams, no probe") {
    // Unreachable through Cli.run (validateSnapColor rejects this before select is ever called) but
    // select's own contract must still be total and safe (Cli.scala's doc comment on this branch).
    for value <- List("sometimes", "loud", "") do
      val tty = new CountingTty(true, true)
      val presenters = Presentation.select(env(Map("SNAP_COLOR" -> value), tty))
      assertEquals(presenters, Presenters(Presentation.Plain, Presentation.Plain))
      assertEquals(tty.stdoutCalls.get(), 0)
      assertEquals(tty.stderrCalls.get(), 0)
  }

  test("NO_COLOR present (including empty) forces Plain on both streams in auto/unset mode") {
    for
      snapColor <- List(Map.empty[String, String], Map("SNAP_COLOR" -> "auto"))
      noColorValue <- List("", "1", "anything")
      stdoutTty <- List(false, true)
      stderrTty <- List(false, true)
    do
      val tty = new CountingTty(stdoutTty, stderrTty)
      val presenters =
        Presentation.select(env(snapColor ++ Map("NO_COLOR" -> noColorValue), tty))
      assertEquals(presenters, Presenters(Presentation.Plain, Presentation.Plain))
      assertEquals(tty.stdoutCalls.get(), 0, "NO_COLOR present must not probe stdout")
      assertEquals(tty.stderrCalls.get(), 0, "NO_COLOR present must not probe stderr")
  }

  test("unset/auto with NO_COLOR absent selects Terminal/Plain per stream independently (R108)") {
    for
      snapColor <- List(Map.empty[String, String], Map("SNAP_COLOR" -> "auto"))
      stdoutTty <- List(false, true)
      stderrTty <- List(false, true)
    do
      val tty = new CountingTty(stdoutTty, stderrTty)
      val presenters = Presentation.select(env(snapColor, tty))
      val expectedStdout = if stdoutTty then Presentation.Terminal else Presentation.Plain
      val expectedStderr = if stderrTty then Presentation.Terminal else Presentation.Plain
      assertEquals(presenters, Presenters(expectedStdout, expectedStderr))
      assertEquals(tty.stdoutCalls.get(), 1, "auto+NO_COLOR-absent must probe stdout exactly once")
      assertEquals(tty.stderrCalls.get(), 1, "auto+NO_COLOR-absent must probe stderr exactly once")
  }

  // ---------------------------------------------------------------------------- D20: real Tty probe

  test("Tty.Real reports non-TTY for redirected/piped streams (sanity: no crash, no hang)") {
    // This test's own stdout/stderr are captured by the test runner (never a real terminal), so
    // Tty.Real must report false for both without throwing — the same shape the harness's piped
    // streams present in every one of the 27 non-terminal provided tests.
    assertEquals(Tty.Real.isStdoutTty, false)
    assertEquals(Tty.Real.isStderrTty, false)
  }

  // ------------------------------------------------------------------- exact rendering (ResultKind)

  test("Success renders exactly S(32,check) label S(36,version) — init/commit/revert/merge shape") {
    val out = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
    Presentation.Terminal.result(e, ResultKind.Success("Committed"), "(alice@x->1)\n")
    assertEquals(
      out.toString(StandardCharsets.UTF_8),
      "[32m✓[0m [1mCommitted[0m [36m(alice@x->1)[0m\n"
    )
  }

  test("VersionLine renders bold, dropping and restoring exactly one trailing LF") {
    val out = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
    Presentation.Terminal.result(e, ResultKind.VersionLine, "snap 1.0.0\n")
    assertEquals(out.toString(StandardCharsets.UTF_8), "[1msnap 1.0.0[0m\n")
  }

  test("Status renders the clean line when deltas are empty") {
    val out = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
    Presentation.Terminal.result(
      e,
      ResultKind.Status("(a@x->1)", Vector.empty),
      "version (a@x->1)\n"
    )
    assertEquals(
      out.toString(StandardCharsets.UTF_8),
      "[1mSnap status[0m  [36m(a@x->1)[0m\n\n" +
        "  [32m✓[0m Working tree clean\n"
    )
  }

  test("Status renders A/M/D rows with U+2212 (not '-') for deleted, in the given delta order") {
    val out = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
    val deltas = Vector(
      Delta(path("added.txt"), None, Some(bytes("x"))),
      Delta(path("gone.txt"), Some(bytes("x")), None),
      Delta(path("mod.txt"), Some(bytes("x")), Some(bytes("y")))
    )
    Presentation.Terminal.result(e, ResultKind.Status("()", deltas), "version ()\n...")
    assertEquals(
      out.toString(StandardCharsets.UTF_8),
      "[1mSnap status[0m  [36m()[0m\n\n" +
        "  [32m+[0m added.txt [2m(added)[0m\n" +
        "  [31m−[0m gone.txt [2m(deleted)[0m\n" +
        "  [33m~[0m mod.txt [2m(modified)[0m\n"
    )
  }

  test("LogEntries separates entries with exactly one blank line, none trailing") {
    val out = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
    val entries = Vector(
      LogLine("(a@x->2)", "a@x", "second"),
      LogLine("(a@x->1)", "a@x", "first")
    )
    Presentation.Terminal.result(e, ResultKind.LogEntries(entries), "ignored-by-this-kind")
    assertEquals(
      out.toString(StandardCharsets.UTF_8),
      "[36m●[0m [1msecond[0m\n" +
        "  [36m(a@x->2)[0m [2mby[0m [35ma@x[0m\n" +
        "\n" +
        "[36m●[0m [1mfirst[0m\n" +
        "  [36m(a@x->1)[0m [2mby[0m [35ma@x[0m\n"
    )
  }

  test("Diff wraps by first-applicable prefix, in the spec's own precedence order") {
    val out = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
    val plain =
      "--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n context\n-old\n+new\n\\ No newline at end of file\n" +
        "Binary files /dev/null and b/x differ\n"
    Presentation.Terminal.result(e, ResultKind.Diff, plain)
    assertEquals(
      out.toString(StandardCharsets.UTF_8),
      "[1m--- a/f[0m\n[1m+++ b/f[0m\n[36m@@ -1,2 +1,2 @@[0m\n" +
        " context\n[31m-old[0m\n[32m+new[0m\n" +
        "[2m\\ No newline at end of file[0m\n" +
        "[33mBinary files /dev/null and b/x differ[0m\n"
    )
  }

  test("Raw prints text unchanged (config's silent output, --serve's plain-exempt kind)") {
    val out = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
    Presentation.Terminal.result(e, ResultKind.Raw, "http://127.0.0.1:1/repository.json\n")
    assertEquals(out.toString(StandardCharsets.UTF_8), "http://127.0.0.1:1/repository.json\n")
  }

  test("R97: empty text prints nothing in Terminal mode either — no stray SGR/reset bytes") {
    for kind <- List(
        ResultKind.Success("Committed"),
        ResultKind.Status("()", Vector.empty),
        ResultKind.LogEntries(Vector.empty),
        ResultKind.Diff,
        ResultKind.VersionLine,
        ResultKind.Raw
      )
    do
      val out = new java.io.ByteArrayOutputStream()
      val e = env(Map.empty, Tty.Stub).copy(stdout = new java.io.PrintStream(out, true, "UTF-8"))
      Presentation.Terminal.result(e, kind, "")
      assertEquals(out.toString(StandardCharsets.UTF_8), "", s"kind=$kind")
  }

  test("error and warning render exactly per §7.11's two different wrapping shapes") {
    val err = new java.io.ByteArrayOutputStream()
    val e = env(Map.empty, Tty.Stub).copy(stderr = new java.io.PrintStream(err, true, "UTF-8"))
    Presentation.Terminal.error(e, "invalid command or arguments")
    assertEquals(
      err.toString(StandardCharsets.UTF_8),
      "[31m✗ snap: invalid command or arguments[0m\n"
    )
    err.reset()
    Presentation.Terminal.warning(e, "auto-resolved same: later-create-wins")
    assertEquals(
      err.toString(StandardCharsets.UTF_8),
      "[33m⚠[0m [33mauto-resolved same: later-create-wins[0m\n"
    )
  }

  // --------------------------------------------------------- R92: presentation changes bytes only

  private def write(root: Path, rel: String, text: String): Unit =
    Files.write(root.resolve(rel), text.getBytes(StandardCharsets.UTF_8))
    ()

  private def stripAnsi(s: String): String = s.replaceAll("\\[[0-9]+m", "")

  test("R92: a plain and a terminal run of the same commit differ only in stream bytes") {
    val plainRoot = Files.createTempDirectory("snap-r92-plain")
    val termRoot = Files.createTempDirectory("snap-r92-term")
    for root <- List(plainRoot, termRoot) do
      assertEquals(Cli.run(TestEnv(cwd = root).env, List("init")), 0)
      assertEquals(
        Cli.run(TestEnv(cwd = root).env, List("config", "contributor.id", "alice@x")),
        0
      )
      write(root, "f.txt", "hello\n")

    val plainFx = TestEnv(cwd = plainRoot, envMap = Map("SNAP_COLOR" -> "never"))
    val termFx = TestEnv(cwd = termRoot, envMap = Map("SNAP_COLOR" -> "always"))
    val plainExit = Cli.run(plainFx.env, List("commit", "first"))
    val termExit = Cli.run(termFx.env, List("commit", "first"))

    assertEquals(plainExit, termExit, "same exit code")
    assertEquals(plainFx.stdout, "(alice@x->1)\n")
    assertEquals(
      stripAnsi(termFx.stdout),
      "✓ Committed (alice@x->1)\n"
    )
    // Identical repository/filesystem effects (R92): same bytes on disk, independent of the two
    // presentations chosen above.
    assertEquals(
      Files.readAllBytes(plainRoot.resolve(".snap").resolve("repository.json")).toVector,
      Files.readAllBytes(termRoot.resolve(".snap").resolve("repository.json")).toVector
    )
    assertEquals(
      Files.readAllBytes(plainRoot.resolve("f.txt")).toVector,
      Files.readAllBytes(termRoot.resolve("f.txt")).toVector
    )
  }

  test("R92: warning selection and order are identical in plain and terminal merge runs") {
    def concurrentPair(): (Path, Path) =
      val left = Files.createTempDirectory("snap-r92-merge-left")
      val right = Files.createTempDirectory("snap-r92-merge-right")
      for (root, id) <- List(left -> "alice@x", right -> "bob@x") do
        assertEquals(Cli.run(TestEnv(cwd = root).env, List("init")), 0)
        assertEquals(Cli.run(TestEnv(cwd = root).env, List("config", "contributor.id", id)), 0)
      write(left, "same.txt", "alice\n")
      write(right, "same.txt", "bob\n")
      write(left, "notes.txt", "left\n")
      write(right, "notes.txt", "right\n")
      assertEquals(Cli.run(TestEnv(cwd = left).env, List("commit", "left")), 0)
      assertEquals(Cli.run(TestEnv(cwd = right).env, List("commit", "right")), 0)
      (left, right)

    val (plainLeft, plainRight) = concurrentPair()
    val (termLeft, termRight) = concurrentPair()

    val plainFx = TestEnv(cwd = plainLeft, envMap = Map("SNAP_COLOR" -> "never"))
    val termFx = TestEnv(cwd = termLeft, envMap = Map("SNAP_COLOR" -> "always"))
    val plainExit = Cli.run(plainFx.env, List("merge", plainRight.toString))
    val termExit = Cli.run(termFx.env, List("merge", termRight.toString))

    assertEquals(plainExit, 0)
    assertEquals(termExit, 0)

    val plainWarnings = plainFx.stderr.linesIterator.collect { case s"warning: $detail" =>
      detail
    }.toVector
    val termWarnings = stripAnsi(termFx.stderr).linesIterator.collect { case s"⚠ $detail" =>
      detail
    }.toVector
    assert(plainWarnings.nonEmpty, "fixture must actually produce a warning")
    assertEquals(termWarnings, plainWarnings, "identical warning order in both presentations")
  }

  test("R97/empty: config's silent output stays empty under SNAP_COLOR=always") {
    val root = Files.createTempDirectory("snap-r92-config")
    assertEquals(Cli.run(TestEnv(cwd = root).env, List("init")), 0)
    val fx = TestEnv(cwd = root, envMap = Map("SNAP_COLOR" -> "always"))
    val exit = Cli.run(fx.env, List("config", "contributor.id", "alice@x"))
    assertEquals(exit, 0)
    assertEquals(fx.stdout, "")
    assertEquals(fx.stderr, "")
  }

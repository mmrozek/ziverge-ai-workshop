package snap.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** `snap log` (SPEC §7.4, R84) through the full [[Cli.run]] pipeline, plus the escape rule's exact
  * order (backslash → tab → LF — gotcha 8).
  */
class CommandsLogSuite extends munit.FunSuite:

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  private def initRepo(): Path =
    val root = Files.createTempDirectory("snap-log-test")
    assertEquals(run(root, "init")._1, 0)
    assertEquals(run(root, "config", "contributor.id", "alice@example.com")._1, 0)
    root

  private def write(root: Path, rel: String, text: String): Unit =
    Files.write(root.resolve(rel), text.getBytes(StandardCharsets.UTF_8))
    ()

  test("empty history prints nothing and succeeds (R84)") {
    val root = initRepo()
    assertEquals(run(root, "log"), (0, "", ""))
  }

  test("patches print in reverse canonical integration order, tab-separated (test 04)") {
    val root = initRepo()
    write(root, "a.txt", "a\n")
    assertEquals(run(root, "commit", "first\tline\nsecond\\tail")._1, 0)
    write(root, "a.txt", "changed\n")
    assertEquals(run(root, "commit", "second")._1, 0)
    assertEquals(
      run(root, "log"),
      (
        0,
        "(alice@example.com->2)\talice@example.com\tsecond\n" +
          "(alice@example.com->1)\talice@example.com\tfirst\\tline\\nsecond\\\\tail\n",
        ""
      )
    )
  }

  test("escape handles backslash, tab, and LF together, backslash first (gotcha 8)") {
    // Message characters: x \ y TAB z LF w — the backslash is escaped first, so the
    // backslashes introduced for TAB/LF are never double-escaped.
    assertEquals(CommandsLog.escape("x\\y\tz\nw"), "x\\\\y\\tz\\nw")
  }

  test("escape output never contains a raw tab or LF") {
    val messages = List("\t", "\n", "\\", "\\t", "\\n", "a\tb\nc\\d", "\\\\\t\n")
    messages.foreach { m =>
      val escaped = CommandsLog.escape(m)
      assert(!escaped.exists(c => c == '\t' || c == '\n'), s"raw control survived in: $escaped")
    }
  }

  test("escape is the identity on messages without backslash, tab, or LF") {
    assertEquals(CommandsLog.escape("plain message with spaces"), "plain message with spaces")
  }

  test("log takes no operands (coarse R79)") {
    val root = initRepo()
    assertEquals(run(root, "log", "extra"), (1, "", "snap: invalid command or arguments\n"))
  }

  test("log never scans the working tree: it succeeds with a symlink present (SPEC §10 scope)") {
    val root = initRepo()
    Files.createSymbolicLink(root.resolve("link"), Path.of("missing"))
    assertEquals(run(root, "log"), (0, "", ""))
  }

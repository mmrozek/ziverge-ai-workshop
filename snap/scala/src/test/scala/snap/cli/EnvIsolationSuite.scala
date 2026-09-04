package snap.cli

import java.io.File
import java.nio.file.Files

/** Regression guard for T08's negative acceptance criterion: no `sys.env` or `System.getenv` call
  * exists anywhere in `src/main/scala` except inside [[Env]]'s own construction (DESIGN §2 — `Env`
  * is the one effect boundary; everything below `Main` takes it as a value). A plain source scan is
  * blunt, but it catches exactly the regression the rule guards against: a shortcut that reads the
  * ambient environment deep in dispatch logic instead of threading `Env` through.
  */
class EnvIsolationSuite extends munit.FunSuite:

  private val mainScalaRoot = new File("src/main/scala")

  private def scalaFilesUnder(dir: File): List[File] =
    Option(dir.listFiles()).fold(List.empty[File])(_.toList).flatMap { f =>
      if f.isDirectory then scalaFilesUnder(f)
      else if f.getName.endsWith(".scala") then List(f)
      else Nil
    }

  /** Strips `/** ... */`/`//` comments so the scan doesn't trip over prose that merely *names*
    * `sys.env`/`System.getenv` (as this file's own doc comments do, and as `Cli.scala`'s do when
    * explaining the rule) — only actual code should ever reference them.
    */
  private def stripComments(text: String): String =
    val noBlockComments = text.replaceAll("(?s)/\\*.*?\\*/", "")
    noBlockComments.linesIterator.map(_.replaceFirst("//.*$", "")).mkString("\n")

  test("no sys.env/System.getenv call exists outside Env.scala") {
    assume(mainScalaRoot.isDirectory, "expects cwd at the sbt project root (snap/scala)")
    val offenders = scalaFilesUnder(mainScalaRoot)
      .filterNot(_.getName == "Env.scala")
      .flatMap { f =>
        val code = stripComments(Files.readString(f.toPath))
        List("sys.env", "System.getenv").filter(code.contains).map(hit => s"${f.getPath}: $hit")
      }
    assertEquals(offenders, List.empty[String])
  }

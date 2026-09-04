import snap.cli.Cli
import snap.cli.Command
import snap.cli.CommandHandler
import snap.cli.Env
import snap.core.Messages

/** Real entry point (T08 replaces the T01 stub). Thin per DESIGN §2/§10 gotcha 9: build the effect
  * boundary once, delegate everything to [[snap.cli.Cli]], map the result to a process exit code.
  * The only place in the codebase that touches `System.exit`, the real environment, and the real
  * streams — everything below here operates on an [[Env]] value.
  */
object Main:

  /** [[main]] minus the actual `System.exit` call, so tests can drive it with a fake [[Env]] and
    * inspect the returned exit code without killing the test JVM. Also the top-level catch-all
    * (R107, D4): any `Throwable` [[Cli.run]] doesn't anticipate maps to exit 2 with one
    * `snap: `-prefixed line on stderr, never exit 1 (which is reserved for expected
    * [[SnapError]]s).
    */
  def run(
      env: Env,
      args: List[String],
      commands: Map[Command, CommandHandler] = Cli.defaultCommands
  ): Int =
    try Cli.run(env, args, commands)
    catch
      case t: Throwable =>
        val detail = Option(t.getMessage).getOrElse(t.getClass.getName)
        env.stderr.println(s"snap: ${Messages.internalError(detail)}")
        2

  def main(args: Array[String]): Unit =
    System.exit(run(Env.real(), args.toList))

// Stub entry point (T01). Replaced by real CLI dispatch in T08 — see
// docs/plan/DESIGN.md §2 (Main.scala is the only place touching System.exit).
object Main:
  def main(args: Array[String]): Unit =
    System.err.println("snap: not implemented")
    System.exit(1)

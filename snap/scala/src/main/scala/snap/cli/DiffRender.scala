package snap.cli

import snap.core.Diff
import snap.core.EditOp
import snap.core.SnapPath
import snap.core.TextTokens
import snap.core.Tree

/** `snap diff`'s rendering (SPEC §7.6, R87; DESIGN §8, D8): a pure function of two trees — no
  * `Env`/time access, effects live only in [[CommandsDiff]]. Changed paths are found by
  * [[WorkingChanges.compute]] (the same `Utf8Order` sorted-merge walk `status`/`commit` use — R87's
  * "changed paths sort by path" for free); per path, a binary line when any PRESENT side is
  * non-text (D8: text block only when every present side is text), else a whole-file unified block
  * using §5's canonical script over the LF-retaining tokens (SPEC §4.4).
  */
object DiffRender:

  /** Renders the complete diff of `oldTree` against `newTree`; empty when the trees are equal (R87:
    * "no differences means no stdout").
    */
  def render(oldTree: Tree, newTree: Tree): String =
    WorkingChanges.compute(oldTree, newTree).iterator.map(renderDelta).mkString

  private def renderDelta(delta: Delta): String =
    val oldBytes = delta.before.map(toArray)
    val newBytes = delta.after.map(toArray)
    // D8: binary iff ANY present side is non-text; a text block requires ALL present sides text.
    val anyBinary = (oldBytes.iterator ++ newBytes.iterator).exists(b => !TextTokens.isText(b))
    if anyBinary then renderBinary(delta.path, delta.before.isDefined, delta.after.isDefined)
    else renderText(delta.path, delta.before.isDefined, delta.after.isDefined, oldBytes, newBytes)

  private def renderBinary(path: SnapPath, oldPresent: Boolean, newPresent: Boolean): String =
    s"Binary files ${sideA(oldPresent, path)} and ${sideB(newPresent, path)} differ\n"

  private def renderText(
      path: SnapPath,
      oldPresent: Boolean,
      newPresent: Boolean,
      oldBytes: Option[Array[Byte]],
      newBytes: Option[Array[Byte]]
  ): String =
    // An absent side has no tokens, same as an empty file — the tree-materialized-vs-absent
    // distinction only matters for the `/dev/null` headers below (R87).
    val oldTokens = oldBytes.flatMap(TextTokens.tokenizeBytes).getOrElse(Vector.empty)
    val newTokens = newBytes.flatMap(TextTokens.tokenizeBytes).getOrElse(Vector.empty)
    val header = s"--- ${sideA(oldPresent, path)}\n+++ ${sideB(newPresent, path)}\n"
    val hunk = s"@@ -1,${oldTokens.length} +1,${newTokens.length} @@\n"
    header + hunk + renderOps(Diff.diff(oldTokens, newTokens).ops, oldTokens)

  /** Walks the canonical script alongside `oldTokens` (retain/delete consume old tokens in order;
    * insert supplies its own), rendering one line per token — never a per-operation summary line. A
    * left fold over an immutable accumulator (no `var`, DESIGN conventions).
    */
  private def renderOps(ops: Vector[EditOp], oldTokens: Vector[String]): String =
    ops
      .foldLeft((0, "")) { case ((pos, text), op) =>
        op match
          case EditOp.Retain(n) =>
            val count = n.toInt
            val line = oldTokens.slice(pos, pos + count).map(renderLine(' ', _)).mkString
            (pos + count, text + line)
          case EditOp.Delete(n) =>
            val count = n.toInt
            val line = oldTokens.slice(pos, pos + count).map(renderLine('-', _)).mkString
            (pos + count, text + line)
          case EditOp.Insert(tokens) =>
            (pos, text + tokens.map(renderLine('+', _)).mkString)
      }
      ._2

  /** One diff line for `token` (SPEC §7.6): a token ending in LF supplies its own line terminator
    * (its trailing LF is dropped and replaced by the line we print); a token without a final LF
    * (only ever the very last token of its sequence, R57) is followed by LF and then the pinned
    * `\ No newline at end of file` marker line (both the deleted and inserted side can need it).
    */
  private def renderLine(sign: Char, token: String): String =
    if token.endsWith("\n") then s"$sign${token.dropRight(1)}\n"
    else s"$sign$token\n\\ No newline at end of file\n"

  private def sideA(present: Boolean, path: SnapPath): String =
    if present then s"a/${path.value}" else "/dev/null"

  private def sideB(present: Boolean, path: SnapPath): String =
    if present then s"b/${path.value}" else "/dev/null"

  private def toArray(bytes: IArray[Byte]): Array[Byte] =
    IArray.genericWrapArray(bytes).toArray

package snap.core

import scala.annotation.tailrec

/** Why a raw string is not a valid tracked path (SPEC §2 / R23).
  *
  * Minimal local error type: `snap/core/Errors.scala` (built by a parallel task) does not exist in
  * this task's scope. When the `SnapError` catalog lands, these reasons map into it; until then
  * callers only need "invalid, and why" for diagnostics. Checks run in the declaration order below;
  * the first failure is reported.
  */
enum PathError:
  /** The raw path is the empty string. */
  case Empty

  /** Contains an ASCII control character (0x00–0x1F or 0x7F — DESIGN D12) or `\`. */
  case IllegalCharacter

  /** Contains an unpaired surrogate, so it has no UTF-8 encoding (SPEC §2 requires a UTF-8 path).
    */
  case MalformedUnicode

  /** Has an empty segment: leading or trailing `/`, or `//`. */
  case EmptySegment

  /** Has a `.` or `..` segment. */
  case DotSegment

  /** First segment is `.snap` (nested `.snap` below the root is allowed — DESIGN D13).
    */
  case ReservedFirstSegment

/** A validated tracked path (SPEC §2 / R23): a nonempty UTF-8 relative path with `/` separators, no
  * ASCII control character (0x00–0x1F, 0x7F — DESIGN D12) and no backslash, no empty, `.` or `..`
  * segment, and first segment not equal to `.snap`. `sub/.snap/x` IS valid — only the first segment
  * is reserved (DESIGN D13). No Unicode or case normalization is performed.
  *
  * Construction only through [[SnapPath.parse]]; the constructor (and the synthetic `apply`/`copy`)
  * are private, so every instance holds a validated value.
  */
final case class SnapPath private (value: String):
  /** The `/`-separated segments; each nonempty by construction. */
  def segments: Vector[String] = value.split("/", -1).toVector

  /** Proper segment-prefix ancestors, root-first: `a/b/c` → `a`, `a/b`. Every proper segment prefix
    * of a valid path is itself valid (its segments are a subset of this path's and the first
    * segment is shared), so direct construction is safe.
    */
  def ancestors: Vector[SnapPath] =
    (0 until value.length).collect {
      case i if value.charAt(i) == '/' => new SnapPath(value.substring(0, i))
    }.toVector

  /** True iff `other` has this path as a proper segment prefix. Since `/` is the separator and
    * cannot occur inside a segment, a string prefix ending at a `/` boundary is exactly a segment
    * prefix — so `a` is an ancestor of `a/b` but not of `ab`.
    */
  def isAncestorOf(other: SnapPath): Boolean = other.value.startsWith(value + "/")

  /** True iff this path has `other` as a proper segment prefix. */
  def isDescendantOf(other: SnapPath): Boolean = other.isAncestorOf(this)

object SnapPath:
  /** Paths sort by unsigned lexicographic UTF-8 bytes (SPEC §2). */
  given ordering: Ordering[SnapPath] = Ordering.by[SnapPath, String](_.value)(using Utf8Order)

  /** Validates `raw` against SPEC §2 / R23. Checks run in the order of the [[PathError]] cases; the
    * first failure wins.
    */
  def parse(raw: String): Either[PathError, SnapPath] =
    if raw.isEmpty then Left(PathError.Empty)
    else if raw.exists(isForbiddenChar) then Left(PathError.IllegalCharacter)
    else if hasUnpairedSurrogate(raw, 0) then Left(PathError.MalformedUnicode)
    else
      val segments = raw.split("/", -1)
      if segments.exists(_.isEmpty) then Left(PathError.EmptySegment)
      else if segments.exists(s => s == "." || s == "..") then Left(PathError.DotSegment)
      else if segments(0) == ".snap" then Left(PathError.ReservedFirstSegment)
      else Right(new SnapPath(raw))

  /** SPEC §2 / R25: a path set is prefix-free by segment iff no path is a proper segment prefix of
    * another — if `a` is a file, no `a/...` may exist. `a` and `ab` do not conflict; neither do
    * siblings `a/b` and `a/c`. The result is independent of the input's iteration order: `forall`
    * over pure membership tests is commutative.
    */
  def prefixFree(paths: Iterable[SnapPath]): Boolean =
    val present = paths.iterator.map(_.value).toSet
    paths.iterator.forall(p => p.ancestors.forall(a => !present.contains(a.value)))

  /** ASCII control characters 0x00–0x1F and 0x7F (DESIGN D12) and backslash. Safe on UTF-16 code
    * units: surrogates are >= 0xD800 and can never match.
    */
  private def isForbiddenChar(c: Char): Boolean =
    c <= '\u001f' || c == '\u007f' || c == '\\'

  @tailrec
  private def hasUnpairedSurrogate(s: String, i: Int): Boolean =
    if i >= s.length then false
    else
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) then
        if i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) then
          hasUnpairedSurrogate(s, i + 2)
        else true
      else if Character.isLowSurrogate(c) then true
      else hasUnpairedSurrogate(s, i + 1)

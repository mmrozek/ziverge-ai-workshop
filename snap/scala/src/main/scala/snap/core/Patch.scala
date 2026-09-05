package snap.core

import java.nio.charset.StandardCharsets

/** Patch identity (SPEC §4.2, R10/R46): the pair `(author, revision)`. */
final case class Dot(author: ContributorId, revision: Long):
  /** Diagnostic rendering `<author> revision <n>` — the shape the provided suite pins inside
    * `patch collision: a@x revision 1` (test 16) and `unreachable patch: …` (test 23). Composed
    * into full diagnostics only by the [[Messages]] catalog.
    */
  def text: String = s"${author.value} revision ${revision.toString}"

/** One authored change (SPEC §4.3, R50): `text {path, edit}`, `put {path, content}`, or
  * `delete {path}`. Equality is structural over the parsed typed values (R47), including `put`
  * content bytes.
  */
sealed trait Change:
  def path: SnapPath

object Change:
  /** Text create or edit: an [[EditScript]] over the base's canonical token sequence (R50/R54). */
  final case class Text(path: SnapPath, edit: EditScript) extends Change

  /** Atomic create or replacement with exact bytes (R50). Structural equality compares the bytes —
    * `IArray[Byte]` is an array at runtime, whose default case-class equality would be by
    * reference.
    */
  final case class Put(path: SnapPath, content: IArray[Byte]) extends Change:
    override def equals(that: Any): Boolean = that match
      case other: Put => path == other.path && ByteArrays.equal(content, other.content)
      case _          => false

    /** Deterministic across runs: folds content bytes; no identity hashes. */
    override def hashCode: Int = 31 * path.hashCode + ByteArrays.hash(content)

  /** Delete of a present path (R50). */
  final case class Delete(path: SnapPath) extends Change

/** A patch (SPEC §4.2): one contributor's increment over an exact base version. Construction only
  * through [[Patch.make]], which enforces the value rules of §4.5 step 1 — revision bounds (R30),
  * message character rules (R48 with D16), and changes-list rules (R49). Equality is structural
  * over the parsed typed values (R47): two patches decoded from differently formatted JSON compare
  * equal (test 26's premise).
  *
  * The dot-consistency rule `revision = base[author] + 1` (R46) is repository-level validation
  * (§4.5 step 3, [[Repo.validate]]), not a construction invariant — a decoded patch must be
  * representable so the validator can reject it with the pinned diagnostics.
  */
final case class Patch private (
    author: ContributorId,
    revision: Long,
    base: Version,
    message: String,
    changes: Vector[Change]
):
  def dot: Dot = Dot(author, revision)

  /** R46: `result = base` with `result[author] = revision`. `Left` only when `revision` is out of
    * bounds — unreachable for instances built by [[Patch.make]] (bounds checked there), kept typed
    * rather than silently absorbed.
    */
  def result: Either[SnapError, Version] = base.updated(author, revision)

object Patch:

  /** Validating factory (§4.5 step 1 value rules). Checks run in a fixed order — revision bounds,
    * message, changes — so the reported error is deterministic.
    */
  def make(
      author: ContributorId,
      revision: Long,
      base: Version,
      message: String,
      changes: Vector[Change]
  ): Either[SnapError, Patch] =
    for
      _ <- Revision.check(revision)
      _ <- checkMessage(message)
      _ <- checkChanges(changes)
    yield new Patch(author, revision, base, message, changes)

  /** R48 with D16: nonempty; tab and LF allowed; no other ASCII control character including DEL
    * (D12); must have a UTF-8 encoding (no unpaired surrogate). The 4096-byte limit is `snap
    * commit`'s input rule alone (D16) and deliberately NOT enforced here — generated revert
    * messages may be longer. Public since T10: `snap commit` reuses this one canonical R48
    * implementation for its input validation (adding the byte limit and remapping every violation
    * to the pinned `invalid commit message` wording).
    */
  def checkMessage(message: String): Either[SnapError, Unit] =
    if message.isEmpty then Left(SnapError.PatchMessageEmpty)
    else if message.exists(isForbiddenControl) then Left(SnapError.PatchMessageForbiddenCharacter)
    else if !StandardCharsets.UTF_8.newEncoder().canEncode(message) then
      // canEncode is a pure containment check (UTF-8 encodes everything except
      // unpaired surrogates); no exception control flow involved.
      Left(SnapError.PatchMessageNotUtf8)
    else Right(())

  /** R49: nonempty, strictly ascending by path in `Utf8Order` — which also enforces at most one
    * change per path. The leftmost violation decides.
    */
  private def checkChanges(changes: Vector[Change]): Either[SnapError, Unit] =
    if changes.isEmpty then Left(SnapError.ChangesEmpty)
    else
      (1 until changes.length).iterator
        .flatMap { k =>
          val c = SnapPath.ordering.compare(changes(k - 1).path, changes(k).path)
          if c > 0 then Some(SnapError.ChangesNotSorted)
          else if c == 0 then Some(SnapError.ChangesDuplicatePath)
          else None
        }
        .nextOption()
        .toLeft(())

  /** ASCII control characters other than tab and LF, including DEL 0x7F (R48, D12). Safe on UTF-16
    * code units: surrogates are >= 0xD800 and never match.
    */
  private def isForbiddenControl(c: Char): Boolean =
    (c < ' ' && c != '\t' && c != '\n') || c == '\u007f'

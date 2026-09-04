package snap.core

/** Total order on strings equal to the unsigned lexicographic order of their UTF-8 encodings (SPEC
  * §2, §3.2; DESIGN D23). UTF-8 is order-preserving over code points, so comparing code points
  * yields the byte order without allocating the encoded bytes.
  *
  * Never use `String.compareTo` for contract-relevant ordering: it compares UTF-16 code units,
  * which sorts U+E000..U+FFFF *after* supplementary characters (DESIGN §10 gotcha 1).
  *
  * Integration note (T03/T04): T04 defines a comparator with identical semantics for paths; the
  * duplicate is deduped at integration time.
  */
object Utf8Order extends Ordering[String]:
  def compare(a: String, b: String): Int = loop(a, b, 0)

  @annotation.tailrec
  private def loop(a: String, b: String, i: Int): Int =
    if i >= a.length && i >= b.length then 0
    else if i >= a.length then -1 // a is a strict prefix of b
    else if i >= b.length then 1
    else if a.charAt(i) == b.charAt(i) then loop(a, b, i + 1)
    else
      // First differing position: code-point comparison equals UTF-8 byte
      // comparison. `codePointAt` pairs surrogates, so a supplementary
      // character correctly sorts above every BMP character.
      Integer.compare(a.codePointAt(i), b.codePointAt(i))

/** Revision bounds (SPEC §3.1, R30): a revision is a positive integer no greater than
  * `9007199254740991` (2^53 − 1). Zero means "no revision" and is never stored (absent = 0).
  */
object Revision:
  val Max: Long = 9007199254740991L

  def isValid(revision: Long): Boolean = revision >= 1L && revision <= Max

  /** The rendered message ends with the pinned fragment `positive safe integer` (test 23, R30); the
    * wording lives in [[Messages.revisionNotSafeInteger]] (D5 — migrated from T03's
    * `Either[String, A]` seam in T06).
    */
  def check(revision: Long): Either[SnapError, Long] =
    if isValid(revision) then Right(revision)
    else Left(SnapError.RevisionNotSafeInteger)

/** A validated contributor id (SPEC §3.1, R28–R29): ASCII email-shaped — exactly one `@` with
  * nonempty text on both sides; no control character (0x00–0x1F and 0x7F, locked decision D12), no
  * whitespace, `,`, `(`, `)`, and no substring `->`; at most 254 bytes. Spelling is preserved
  * exactly (R29): construction never normalizes.
  *
  * The constructor is private; [[ContributorId.parse]] is the only way in.
  */
final case class ContributorId private (value: String)

object ContributorId:
  val MaxBytes: Int = 254

  /** Canonical id order: unsigned UTF-8 bytes (SPEC §3.2). For the ASCII-only ids this equals
    * natural string order, but the one shared comparator is used on principle (DESIGN §3, D23).
    */
  val ordering: Ordering[ContributorId] =
    Ordering.by((id: ContributorId) => id.value)(Utf8Order)

  /** Reasons are typed ([[IdError]]) and rendered only by [[Messages.contributorId]] (D5 — migrated
    * from T03's `Either[String, A]` seam in T06).
    */
  def parse(value: String): Either[SnapError, ContributorId] =
    // `value.length` counts UTF-16 units and UTF-8 bytes-per-char is always
    // >= 1 per unit, so length > 254 implies bytes > 254 regardless of
    // content; after the ASCII check below, chars == bytes exactly.
    if value.length > MaxBytes then Left(SnapError.InvalidContributorId(IdError.TooLong))
    else if !value.forall(isAllowedChar) then
      Left(SnapError.InvalidContributorId(IdError.ForbiddenCharacter))
    else if value.count(_ == '@') != 1 then Left(SnapError.InvalidContributorId(IdError.AtCount))
    else if value.head == '@' || value.last == '@' then
      Left(SnapError.InvalidContributorId(IdError.EmptyAtSide))
    else if value.contains("->") then Left(SnapError.InvalidContributorId(IdError.ArrowSubstring))
    else Right(new ContributorId(value))

  /** Printable ASCII 0x21–0x7E: excludes non-ASCII, the control range 0x00–0x1F, DEL 0x7F (D12),
    * and all whitespace including space; `,`, `(`, `)` are excluded explicitly (SPEC §3.1).
    */
  private def isAllowedChar(c: Char): Boolean =
    c >= 0x21 && c <= 0x7e && c != ',' && c != '(' && c != ')'

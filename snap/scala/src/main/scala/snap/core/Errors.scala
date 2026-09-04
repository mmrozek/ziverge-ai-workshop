package snap.core

/** Position of a JSON syntax failure (1-based line and column, as reported by the tokenizer).
  * Carried as data so [[SnapError.message]] stays the single place that renders diagnostics.
  */
final case class JsonLocation(line: Int, col: Int)

/** The single error channel of the implementation (DESIGN D4): domain code returns
  * `Either[SnapError, A]`, never throws. Cases carry structured data; the rendered text lives in
  * [[Messages]] (DESIGN D5) and is produced only by [[SnapError.message]].
  *
  * Seeded in T02 with the strict-JSON cases; later tasks append cases for their own diagnostics.
  */
enum SnapError:
  /** Malformed JSON of any kind (tokenizer rejection, trailing garbage, truncated input). Maps to
    * the `invalid JSON` diagnostic class — tests pin `invalid JSON` as a substring (R41; tests
    * 03/13). `location` is absent when the input ended prematurely (no meaningful position exists).
    */
  case InvalidJson(location: Option[JsonLocation])

  /** A JSON object with a repeated key (R41; tests 15/25 pin the shape `duplicate JSON key <k>`).
    */
  case DuplicateJsonKey(key: String)

  /** One-line diagnostic detail, without the `snap: ` prefix — the CLI layer (T08) prepends the
    * prefix when printing (spec §10 `snap: <detail>`).
    */
  def message: String = this match
    case InvalidJson(Some(location)) => Messages.invalidJsonAt(location)
    case InvalidJson(None)           => Messages.invalidJsonTruncated
    case DuplicateJsonKey(key)       => Messages.duplicateJsonKey(key)

/** Message catalog (DESIGN D5): every diagnostic string of the implementation lives here,
  * test-pinned ones verbatim. No other module builds diagnostic text.
  */
object Messages:
  /** Pinned shape `duplicate JSON key <k>` (test 25 matches `^snap: duplicate JSON key .+\n$`, test
    * 15 the substring).
    */
  def duplicateJsonKey(key: String): String = s"duplicate JSON key $key"

  /** Only the substring `invalid JSON` is pinned (tests 03/13); position is a courtesy detail, kept
    * single-line and free of input echoes.
    */
  def invalidJsonAt(location: JsonLocation): String =
    s"invalid JSON at line ${location.line} column ${location.col}"

  /** Truncated input has no failure position. */
  val invalidJsonTruncated: String = "invalid JSON: unexpected end of input"

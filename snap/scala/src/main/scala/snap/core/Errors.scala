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

  /** No known command matched the first token, an operand didn't fit the command's shape, or a
    * grammar rule (R79) was violated outside `diff`'s distinct usage channel (DESIGN §8; test 14/24
    * pin `invalid command or arguments` verbatim). T08 seeds this with only the coarse checks it
    * can make (unknown command, `--version` arity); the exhaustive per-command matrix is T13's.
    */
  case InvalidCommand

  /** A command that resolves against the nearest repository (R77) found none walking from the
    * process cwd to the filesystem root (test 14 pins this verbatim).
    */
  case NotASnapRepository

  /** `SNAP_COLOR` set to something other than unset/`auto`/`always`/`never` (R95; test 28 pins this
    * verbatim). The offending value is never echoed — the spec's wording is fixed regardless of
    * what was set.
    */
  case InvalidSnapColor

  /** A recognized command with no implementation yet (T08 stub, replaced command-by-command through
    * T09–T21). Carries no detail: the wording matches T01's placeholder `Main.scala` verbatim.
    */
  case NotImplemented

  /** One-line diagnostic detail, without the `snap: ` prefix — the CLI layer (T08) prepends the
    * prefix when printing (spec §10 `snap: <detail>`).
    */
  def message: String = this match
    case InvalidJson(Some(location)) => Messages.invalidJsonAt(location)
    case InvalidJson(None)           => Messages.invalidJsonTruncated
    case DuplicateJsonKey(key)       => Messages.duplicateJsonKey(key)
    case InvalidCommand              => Messages.invalidCommand
    case NotASnapRepository          => Messages.notASnapRepository
    case InvalidSnapColor            => Messages.invalidSnapColor
    case NotImplemented              => Messages.notImplemented

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

  /** Pinned verbatim (tests 14, 24): unknown command, extra operands, or any other grammar
    * violation outside `diff`'s distinct usage channel (DESIGN §8).
    */
  val invalidCommand: String = "invalid command or arguments"

  /** Pinned verbatim (test 14, R77). */
  val notASnapRepository: String = "not a Snap repository"

  /** Pinned verbatim (test 28, R95). */
  val invalidSnapColor: String = "SNAP_COLOR must be auto, always, or never"

  /** T08 stub text for every not-yet-implemented command; matches T01's placeholder `Main.scala`
    * verbatim. Replaced command-by-command starting T09.
    */
  val notImplemented: String = "not implemented"

  /** Exit-2 catch-all (R107, D4): built only by `Main`'s top-level exception handler from an
    * unexpected `Throwable`'s message — domain code never produces this.
    */
  def internalError(detail: String): String = s"internal error: $detail"

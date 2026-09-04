package snap.json

import org.typelevel.jawn.ParseException
import org.typelevel.jawn.Parser
import snap.core.JsonLocation
import snap.core.SnapError

import scala.util.Failure
import scala.util.Success

/** Strict JSON parsing (DESIGN §6): jawn tokenizes (RFC 8259 — string escapes, surrogate pairs,
  * number grammar, trailing garbage), [[AstFacade]] builds our [[Json]] AST and detects duplicate
  * object keys.
  *
  * Error precedence is first-error-in-document-order: a duplicate key recorded by the facade always
  * precedes any later syntax failure, because parsing is strictly sequential.
  */
object JsonParser:
  /** Parse a complete JSON document. Trailing non-whitespace, truncated input, and every other
    * tokenizer rejection map to [[SnapError.InvalidJson]] (the pinned `invalid JSON` diagnostic
    * class, R41); repeated object keys map to [[SnapError.DuplicateJsonKey]] naming the key (tests
    * 15/25).
    */
  def parse(input: String): Either[SnapError, Json] =
    val facade = new AstFacade
    // jawn signals failure via exceptions inside `Try`; this call is the single
    // boundary where they are converted to `Either` (DESIGN D4).
    val attempt = Parser.parseFromString(input)(facade)
    facade.firstDuplicateKey match
      case Some(key) => Left(SnapError.DuplicateJsonKey(key))
      case None      =>
        attempt match
          case Success(json)              => Right(json)
          case Failure(e: ParseException) =>
            Left(SnapError.InvalidJson(Some(JsonLocation(e.line, e.col))))
          case Failure(_) =>
            // IncompleteParseException: input ended mid-value, no position.
            Left(SnapError.InvalidJson(None))

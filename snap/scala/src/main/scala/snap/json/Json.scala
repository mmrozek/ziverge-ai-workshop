package snap.json

/** Immutable JSON AST for the strict layer (DESIGN §6).
  *
  *   - Object fields are an ordered `Vector[(String, Json)]` preserving source order — no unordered
  *     collection ever feeds behavior (R41: the parsed typed value is authoritative; determinism
  *     ground rule).
  *   - Numbers retain their raw decimal text exactly as written; integer-ness and range are judged
  *     from that text, never through `Double` (DESIGN gotcha 4).
  */
enum Json:
  case JNull
  case JBool(value: Boolean)
  case JNumber(raw: String)
  case JString(value: String)
  case JArray(items: Vector[Json])
  case JObject(fields: Vector[(String, Json)])

  /** `Some(n)` iff this is a number whose raw text is a plain JSON integer (no fraction, no
    * exponent, no leading zeros) with |n| ≤ 9007199254740991 (2^53 − 1). Judged entirely from the
    * text — `9007199254740992` is rejected even though it parses as a `Double` (DESIGN gotcha 4;
    * test 25). Callers attach their own context-specific diagnostics (e.g. the pinned
    * `positive safe integer` messages of the typed codecs).
    */
  def asSafeInteger: Option[Long] = this match
    case Json.JNumber(raw) => Json.safeIntegerFromText(raw)
    case _                 => None

object Json:
  /** 2^53 − 1, the largest safe integer, as decimal text (16 digits). */
  private val MaxSafeText: String = "9007199254740991"

  /** Integer value of a raw JSON number text iff it is a plain integer within ±(2^53 − 1), decided
    * by digit-string inspection only. Accepts `-0` as 0 (it denotes the in-range integer zero).
    * Same-length digit strings compare numerically via lexicographic order, so no numeric type
    * wider than `Long` is ever involved.
    */
  private[json] def safeIntegerFromText(raw: String): Option[Long] =
    val negative = raw.startsWith("-")
    val digits = if negative then raw.drop(1) else raw
    val plainInteger =
      digits.nonEmpty &&
        digits.forall(c => c >= '0' && c <= '9') &&
        (digits == "0" || digits.head != '0')
    if !plainInteger then None
    else if digits.length > MaxSafeText.length then None
    else if digits.length == MaxSafeText.length && digits > MaxSafeText then None
    else
      // ≤ 16 digits always fits a Long.
      val magnitude = digits.toLong
      Some(if negative then -magnitude else magnitude)

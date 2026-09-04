package snap.json

import java.nio.charset.StandardCharsets

/** The one canonical JSON serializer (DESIGN §6, D7) — used for every `repository.json` write and
  * the served HTTP snapshot. Style is byte-pinned by test 12 (`body_text_equals`, R42):
  *
  *   - two-space indentation;
  *   - every element of a non-empty array on its own line (including each member of a
  *     `[id, revision]` pair);
  *   - object fields as `"key": value`, one per line;
  *   - empty containers inline (`[]`, `{}`);
  *   - exactly one trailing LF.
  *
  * Numbers are emitted as their retained raw text; strings use minimal RFC 8259 escaping (`\"`,
  * `\\`, short escapes for the named controls, `\u00xx` for the remaining chars below 0x20) with
  * all other characters — including non-ASCII — emitted literally. Output is a pure function of the
  * input value: fields and elements are written in their stored order, and no locale-sensitive
  * formatting is involved (hex via `Integer.toHexString`).
  */
object Writer:
  private val Indent = "  "

  /** Canonical text form, trailing LF included. */
  def write(json: Json): String = render(json, 0) + "\n"

  /** Canonical bytes: always UTF-8, never the platform charset (DESIGN gotcha 7 — the harness runs
    * under `LC_ALL=C`).
    */
  def writeUtf8(json: Json): Array[Byte] =
    write(json).getBytes(StandardCharsets.UTF_8)

  private def render(json: Json, depth: Int): String = json match
    case Json.JNull          => "null"
    case Json.JBool(value)   => if value then "true" else "false"
    case Json.JNumber(raw)   => raw
    case Json.JString(value) => quote(value)
    case Json.JArray(items)  =>
      if items.isEmpty then "[]"
      else
        val inner = items
          .map(item => pad(depth + 1) + render(item, depth + 1))
          .mkString(",\n")
        s"[\n$inner\n${pad(depth)}]"
    case Json.JObject(fields) =>
      if fields.isEmpty then "{}"
      else
        val inner = fields
          .map { case (key, value) =>
            pad(depth + 1) + quote(key) + ": " + render(value, depth + 1)
          }
          .mkString(",\n")
        s"{\n$inner\n${pad(depth)}}"

  private def pad(depth: Int): String = Indent * depth

  private def quote(value: String): String =
    val escaped = value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' =>
        // Locale-independent lowercase hex; c < 0x20 ⇒ at most two digits.
        val hex = Integer.toHexString(c.toInt)
        if hex.length == 1 then "\\u000" + hex else "\\u00" + hex
      case c => c.toString
    }
    "\"" + escaped + "\""

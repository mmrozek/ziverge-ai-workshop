package snap.core

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

/** Text detection and LF-retaining tokenization (SPEC §4.4, R53/R57).
  *
  * Pure. `java.nio.charset.StandardCharsets.UTF_8` is a charset constant, not I/O (DESIGN §2's "no
  * io/nio" note targets effects); UTF-8 *validity* is decided by the hand-rolled [[isValidUtf8]]
  * below, so decoding never relies on platform defaults or exception control flow.
  */
object TextTokens:

  /** A file is text iff its bytes are valid UTF-8 and contain no NUL byte (R53). */
  def isText(bytes: Array[Byte]): Boolean =
    !bytes.exists(_ == 0x00) && isValidUtf8(bytes)

  /** Decodes text-file bytes to a string; `None` when the bytes are not text (R53). */
  def decode(bytes: Array[Byte]): Option[String] =
    if isText(bytes) then Some(new String(bytes, StandardCharsets.UTF_8)) else None

  /** Decodes bytes to a string iff they are valid UTF-8 — unlike [[decode]], a NUL byte does not
    * disqualify them (CR-NUL). For the JSON parse boundary ([[snap.fs.Store]]'s repository/config
    * reads), not file-content text detection: JSON's own grammar rejects a raw NUL with a
    * positioned `invalid JSON` diagnostic, so this gate must reject only genuine UTF-8 encoding
    * failures and let a NUL fall through to the parser rather than being pre-empted by a "not valid
    * UTF-8" verdict.
    */
  def decodeUtf8(bytes: Array[Byte]): Option[String] =
    if isValidUtf8(bytes) then Some(new String(bytes, StandardCharsets.UTF_8)) else None

  /** Splits immediately after every LF byte, retaining LF in the token (R53): `"a\r\nb"` →
    * `["a\r\n", "b"]`; the empty file has no tokens.
    */
  def tokenize(text: String): Vector[String] =
    if text.isEmpty then Vector.empty
    else
      val lfs = (0 until text.length).filter(text.charAt(_) == '\n')
      val starts = 0 +: lfs.map(_ + 1)
      val ends = lfs.map(_ + 1) :+ text.length
      // Only the final (start, end) pair can be empty — when the text ends in LF.
      starts.zip(ends).collect { case (s, e) if s < e => text.substring(s, e) }.toVector

  /** Tokenizes file bytes; `None` when the bytes are not text. */
  def tokenizeBytes(bytes: Array[Byte]): Option[Vector[String]] =
    decode(bytes).map(tokenize)

  /** Renders a token sequence back to file text (tokens concatenate losslessly). */
  def render(tokens: Seq[String]): String = tokens.mkString

  /** Canonical token sequence (R57): every token is nonempty, contains LF at most as its final
    * character, and every token except possibly the last ends in LF.
    */
  def isCanonical(tokens: Seq[String]): Boolean =
    tokens.forall(t => t.nonEmpty && !t.dropRight(1).contains('\n')) &&
      tokens.dropRight(1).forall(_.endsWith("\n"))

  /** A single token producible by [[tokenize]] from some text file: nonempty, no NUL, LF at most as
    * the final character, and no unpaired UTF-16 surrogate (such a string has no UTF-8 encoding, so
    * it can never be file text). Validates insert tokens — R54's "nonempty text tokens".
    */
  def isTextToken(token: String): Boolean =
    token.nonEmpty &&
      !token.contains('\u0000') &&
      !token.dropRight(1).contains('\n') &&
      wellFormedUtf16(token, 0)

  @tailrec
  private def wellFormedUtf16(s: String, i: Int): Boolean =
    if i >= s.length then true
    else
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) then
        i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) &&
        wellFormedUtf16(s, i + 2)
      else !Character.isLowSurrogate(c) && wellFormedUtf16(s, i + 1)

  /** RFC 3629 UTF-8 validity: rejects stray continuation bytes, truncated sequences, overlong
    * forms, encoded UTF-16 surrogates, and code points above U+10FFFF.
    */
  private def isValidUtf8(bytes: Array[Byte]): Boolean =
    def cont(i: Int): Boolean = i < bytes.length && (bytes(i) & 0xc0) == 0x80
    def in(i: Int, lo: Int, hi: Int): Boolean =
      i < bytes.length && {
        val b = bytes(i) & 0xff
        b >= lo && b <= hi
      }
    @tailrec
    def go(i: Int): Boolean =
      if i >= bytes.length then true
      else
        val b0 = bytes(i) & 0xff
        if b0 <= 0x7f then go(i + 1)
        else if b0 >= 0xc2 && b0 <= 0xdf then cont(i + 1) && go(i + 2)
        else if b0 == 0xe0 then in(i + 1, 0xa0, 0xbf) && cont(i + 2) && go(i + 3)
        else if b0 == 0xed then in(i + 1, 0x80, 0x9f) && cont(i + 2) && go(i + 3)
        else if b0 >= 0xe1 && b0 <= 0xef then cont(i + 1) && cont(i + 2) && go(i + 3)
        else if b0 == 0xf0 then in(i + 1, 0x90, 0xbf) && cont(i + 2) && cont(i + 3) && go(i + 4)
        else if b0 == 0xf4 then in(i + 1, 0x80, 0x8f) && cont(i + 2) && cont(i + 3) && go(i + 4)
        else if b0 >= 0xf1 && b0 <= 0xf3 then cont(i + 1) && cont(i + 2) && cont(i + 3) && go(i + 4)
        else false
    go(0)

package snap.json

import org.typelevel.jawn.FContext
import org.typelevel.jawn.Facade

import scala.collection.mutable.ListBuffer

/** jawn facade building our [[Json]] AST (DESIGN §6, D2).
  *
  * One instance serves exactly one parse call. jawn's push-style callback API forces accumulation,
  * so this class is a named mutability boundary (conventions §"Language & style"): every buffer
  * below is confined to a single parse on a single thread, appended strictly in document order, and
  * never read until the parse completes — the observable result is therefore a pure function of the
  * input text.
  *
  * Duplicate object keys are recorded (not thrown — jawn offers no non-throwing abort, and we
  * refuse `throw` as control flow); [[JsonParser]] checks [[firstDuplicateKey]] after the parse.
  * Because parsing is strictly left-to-right, the first recorded duplicate is the first error in
  * document order, even when a syntax error follows later in the input.
  */
private[json] final class AstFacade extends Facade.NoIndexFacade[Json]:
  private val duplicateKeys = ListBuffer.empty[String]

  /** First duplicate object key in document order, if any. */
  def firstDuplicateKey: Option[String] = duplicateKeys.headOption

  def jnull: Json = Json.JNull
  def jfalse: Json = Json.JBool(false)
  def jtrue: Json = Json.JBool(true)
  def jnum(s: CharSequence, decIndex: Int, expIndex: Int): Json =
    // Raw decimal text retained verbatim (DESIGN gotcha 4).
    Json.JNumber(s.toString)
  def jstring(s: CharSequence): Json = Json.JString(s.toString)

  def singleContext(): FContext[Json] =
    new FContext.NoIndexFContext[Json]:
      // jawn adds exactly one top-level value before calling finish().
      private val values = ListBuffer.empty[Json]
      def add(s: CharSequence): Unit = values += jstring(s)
      def add(v: Json): Unit = values += v
      def finish(): Json = values.lastOption.getOrElse(Json.JNull)
      def isObj: Boolean = false

  def arrayContext(): FContext[Json] =
    new FContext.NoIndexFContext[Json]:
      private val values = ListBuffer.empty[Json]
      def add(s: CharSequence): Unit = values += jstring(s)
      def add(v: Json): Unit = values += v
      def finish(): Json = Json.JArray(values.toVector)
      def isObj: Boolean = false

  def objectContext(): FContext[Json] =
    new FContext.NoIndexFContext[Json]:
      // Alternating cells in document order: Left(key) then Right(value).
      // jawn's protocol guarantees each key add strictly precedes its value
      // add, so "a key is pending" ⇔ the last cell is a Left.
      private val cells = ListBuffer.empty[Either[String, Json]]
      private def expectingKey: Boolean = cells.lastOption.forall(_.isRight)
      def add(s: CharSequence): Unit =
        if expectingKey then
          val key = s.toString
          if cells.contains(Left(key)) then duplicateKeys += key
          cells += Left(key)
        else cells += Right(jstring(s))
      def add(v: Json): Unit = cells += Right(v)
      def finish(): Json =
        val fields = cells.toList
          .grouped(2)
          .collect { case List(Left(key), Right(value)) => (key, value) }
          .toVector
        Json.JObject(fields)
      def isObj: Boolean = true

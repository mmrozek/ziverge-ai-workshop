package snap.json

import snap.core.ContributorId
import snap.core.SnapError

/** Typed decode/encode of the contributor-configuration document `{"contributor":{"id":"…"}}` (SPEC
  * §8, R98). Strict like [[RepoCodec]]: the exact schema only — unknown fields at either level are
  * errors naming the field (reusing [[SnapError.UnknownField]]/[[SnapError.MissingField]]/
  * [[SnapError.FieldWrongType]], the same generic cases `RepoCodec` uses) — and the id string is
  * validated through [[ContributorId.parse]], the single validating factory whether the id came
  * from a configuration file or a `snap config` command-line operand (DESIGN D5's "one message
  * catalog").
  *
  * Encoding writes exactly this shape and nothing else: SPEC §8 requires `config` to "preserve no
  * unknown fields", and [[snap.fs.Store.writeConfig]] never reads the old file before overwriting
  * it — there is nothing else to encode.
  */
object ConfigCodec:
  private val ConfigFields = Set("contributor")
  private val ContributorFields = Set("id")

  // ---------------------------------------------------------------- decode

  def decode(json: Json): Either[SnapError, ContributorId] = json match
    case Json.JObject(fields) =>
      for
        _ <- checkUnknown(fields, ConfigFields, "config")
        contributorJson <- required(fields, "contributor", "config")
        id <- decodeContributor(contributorJson)
      yield id
    case _ => Left(SnapError.ConfigNotObject)

  private def decodeContributor(json: Json): Either[SnapError, ContributorId] = json match
    case Json.JObject(fields) =>
      for
        _ <- checkUnknown(fields, ContributorFields, "contributor")
        idJson <- required(fields, "id", "contributor")
        idText <- idJson match
          case Json.JString(value) => Right(value)
          case _                   => Left(SnapError.FieldWrongType("contributor", "id"))
        id <- ContributorId.parse(idText)
      yield id
    case _ => Left(SnapError.FieldWrongType("config", "contributor"))

  private def checkUnknown(
      fields: Vector[(String, Json)],
      allowed: Set[String],
      owner: String
  ): Either[SnapError, Unit] =
    fields.iterator
      .map(_._1)
      .find(!allowed.contains(_))
      .map(SnapError.UnknownField(owner, _))
      .toLeft(())

  private def required(
      fields: Vector[(String, Json)],
      name: String,
      owner: String
  ): Either[SnapError, Json] =
    fields.find(_._1 == name).map(_._2).toRight(SnapError.MissingField(owner, name))

  // ---------------------------------------------------------------- encode

  /** The configuration document as a [[Json]] AST (SPEC §8's exact shape, field order
    * `contributor.id`).
    */
  def encode(id: ContributorId): Json =
    Json.JObject(
      Vector(
        "contributor" -> Json.JObject(Vector("id" -> Json.JString(id.value)))
      )
    )

  /** Canonical serialized bytes (D7) — the only encoding [[snap.fs.Store.writeConfig]] ever writes.
    */
  def encodeBytes(id: ContributorId): Array[Byte] = Writer.writeUtf8(encode(id))

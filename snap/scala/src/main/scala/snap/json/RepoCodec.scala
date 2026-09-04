package snap.json

import snap.core.Change
import snap.core.ContributorId
import snap.core.EditError
import snap.core.EditOp
import snap.core.EditScript
import snap.core.Patch
import snap.core.Repository
import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Version

import java.util.Base64

/** Typed decode/encode of the repository document `{"format":1, "frontier":…, "patches":…}` (SPEC
  * §4.1–§4.3, R40–R52; DESIGN §6).
  *
  * Decoding is strict: the exact schema only, unknown fields are errors naming the field at every
  * level (R43), every string routed through its core validating factory (`ContributorId`,
  * `Version.fromPairs`, `SnapPath.parse`, `EditScript.validate`, [[Patch.make]]), numbers judged
  * from their raw text via `asSafeInteger` (gotcha 4). The reported error is deterministic: within
  * one object, unknown fields are detected in document order, then values are decoded in canonical
  * field order regardless of source order; arrays decode left to right.
  *
  * Encoding produces the [[Json]] AST; canonical bytes come only from [[Writer]] (D7).
  */
object RepoCodec:

  private val RepositoryFields = Set("format", "frontier", "patches")
  private val PatchFields = Set("author", "revision", "base", "message", "changes")
  private val TextChangeFields = Set("type", "path", "edit")
  private val PutChangeFields = Set("type", "path", "content")
  private val DeleteChangeFields = Set("type", "path")

  /** Union of every variant's fields (CR9): a field name outside all three variants is unknown
    * regardless of `type`, so it must be caught before `type` is even extracted — matching this
    * module's own documented precedence ("unknown fields are detected... then values are decoded").
    */
  private val AllChangeFields = TextChangeFields ++ PutChangeFields ++ DeleteChangeFields

  // ---------------------------------------------------------------- decode

  def decode(json: Json): Either[SnapError, Repository] = json match
    case Json.JObject(fields) =>
      for
        _ <- checkUnknown(fields, RepositoryFields, "repository")
        format <- required(fields, "format", "repository")
        _ <- checkFormat(format)
        frontierJson <- required(fields, "frontier", "repository")
        frontier <- decodeVersion(frontierJson, "frontier")
        patchesJson <- required(fields, "patches", "repository")
        patches <- decodePatches(patchesJson)
      yield Repository(frontier, patches)
    case _ => Left(SnapError.RepositoryNotObject)

  private def checkFormat(json: Json): Either[SnapError, Unit] =
    if json.asSafeInteger.contains(1L) then Right(())
    else Left(SnapError.RepositoryFormatInvalid)

  /** R32: an ordered array of `[id, revision]` pairs; canonical order enforced by
    * `Version.fromPairs` (test 23 pins the `canonical` fragment). `field` is `frontier` or `base`.
    */
  private def decodeVersion(json: Json, field: String): Either[SnapError, Version] =
    json match
      case Json.JArray(items) =>
        traverse(items)(decodePair(_, field)).flatMap(Version.fromPairs)
      case _ => Left(SnapError.VersionNotPairs(field))

  private def decodePair(json: Json, field: String): Either[SnapError, (String, Long)] =
    json match
      case Json.JArray(Vector(Json.JString(id), revision)) =>
        revision.asSafeInteger.toRight(SnapError.RevisionNotSafeInteger).map(n => (id, n))
      case _ => Left(SnapError.VersionNotPairs(field))

  private def decodePatches(json: Json): Either[SnapError, Vector[Patch]] =
    json match
      case Json.JArray(items) => traverse(items)(decodePatch)
      case _                  => Left(SnapError.FieldWrongType("repository", "patches"))

  private def decodePatch(json: Json): Either[SnapError, Patch] = json match
    case Json.JObject(fields) =>
      for
        _ <- checkUnknown(fields, PatchFields, "patch")
        author <- requiredString(fields, "author", "patch").flatMap(ContributorId.parse)
        revisionJson <- required(fields, "revision", "patch")
        revision <- revisionJson.asSafeInteger.toRight(SnapError.RevisionNotSafeInteger)
        baseJson <- required(fields, "base", "patch")
        base <- decodeVersion(baseJson, "base")
        message <- requiredString(fields, "message", "patch")
        changesJson <- required(fields, "changes", "patch")
        changes <- decodeChanges(changesJson)
        patch <- Patch.make(author, revision, base, message, changes)
      yield patch
    case _ => Left(SnapError.PatchNotObject)

  private def decodeChanges(json: Json): Either[SnapError, Vector[Change]] =
    json match
      case Json.JArray(items) => traverse(items)(decodeChange)
      case _                  => Left(SnapError.FieldWrongType("patch", "changes"))

  /** R50: `text {path, edit}` / `put {path, content}` / `delete {path}` — the variant's exact field
    * set, nothing else (test 23 anchors `unknown field: extra` at the change level). A field
    * outside every variant is rejected before `type` is extracted (CR9): otherwise
    * `{"path":"f","bogus":1}` (no `type` at all) would report the missing `type` field instead of
    * the unknown `bogus` one, inverting this module's own "unknown fields first" precedence.
    */
  private def decodeChange(json: Json): Either[SnapError, Change] = json match
    case Json.JObject(fields) =>
      for
        _ <- checkUnknown(fields, AllChangeFields, "change")
        kind <- requiredString(fields, "type", "change")
        change <- kind match
          case "text" =>
            for
              _ <- checkUnknown(fields, TextChangeFields, "change")
              path <- decodePath(fields)
              editJson <- required(fields, "edit", "change")
              edit <- decodeEdit(editJson)
            yield Change.Text(path, edit)
          case "put" =>
            for
              _ <- checkUnknown(fields, PutChangeFields, "change")
              path <- decodePath(fields)
              content <- requiredString(fields, "content", "change")
              bytes <- decodeCanonicalBase64(content)
            yield Change.Put(path, bytes)
          case "delete" =>
            for
              _ <- checkUnknown(fields, DeleteChangeFields, "change")
              path <- decodePath(fields)
            yield Change.Delete(path)
          case _ => Left(SnapError.ChangeTypeInvalid)
      yield change
    case _ => Left(SnapError.ChangeNotObject)

  /** Every JSON-decoded path goes through `SnapPath.parse` (R23; test 15 pins `path is invalid` —
    * the `.snap/…` case among others).
    */
  private def decodePath(fields: Vector[(String, Json)]): Either[SnapError, SnapPath] =
    requiredString(fields, "path", "change")
      .flatMap(raw => SnapPath.parse(raw).left.map(SnapError.ChangePathInvalid.apply))

  /** R54–R55: ops decoded one-key-per-object, then the script's structural rules via
    * `EditScript.validate` (counts, adjacency, insert tokens — T05's pinned fragments).
    */
  private def decodeEdit(json: Json): Either[SnapError, EditScript] =
    json match
      case Json.JArray(items) =>
        for
          ops <- traverse(items)(decodeOp)
          script = EditScript(ops)
          _ <- script.validate.left.map(SnapError.InvalidEdit.apply)
        yield script
      case _ => Left(SnapError.FieldWrongType("change", "edit"))

  private def decodeOp(json: Json): Either[SnapError, EditOp] = json match
    case Json.JObject(Vector((name, value))) =>
      name match
        case "retain" =>
          value.asSafeInteger
            .toRight(SnapError.InvalidEdit(EditError.BadCount))
            .map(EditOp.Retain.apply)
        case "delete" =>
          value.asSafeInteger
            .toRight(SnapError.InvalidEdit(EditError.BadCount))
            .map(EditOp.Delete.apply)
        case "insert" =>
          value match
            case Json.JArray(items) =>
              traverse(items) {
                case Json.JString(token) => Right(token)
                case _                   => Left(SnapError.InvalidEdit(EditError.BadInsertToken))
              }.map(EditOp.Insert.apply)
            case _ => Left(SnapError.FieldWrongType("edit operation", "insert"))
        case _ => Left(SnapError.EditOpUnknown)
    case Json.JObject(_) => Left(SnapError.InvalidEdit(EditError.NotOneOperation))
    case _               => Left(SnapError.EditOpNotObject)

  /** R50: standard padded RFC 4648 base64, canonical — decode then re-encode must reproduce the
    * input exactly (test 15 pins `canonical base64`). The shape pre-check (length a multiple of 4,
    * `=` only as the final one or two characters, alphabet-only body) makes the JDK decoder total,
    * so no exception ever fires; the re-encode comparison then rejects nonzero trailing bits.
    */
  private def decodeCanonicalBase64(text: String): Either[SnapError, IArray[Byte]] =
    val padding =
      if text.endsWith("==") then 2
      else if text.endsWith("=") then 1
      else 0
    val body = text.substring(0, text.length - padding)
    if text.length % 4 != 0 || !body.forall(isBase64Char) then
      Left(SnapError.ContentNotCanonicalBase64)
    else
      val bytes = Base64.getDecoder.decode(text)
      if Base64.getEncoder.encodeToString(bytes) == text then Right(IArray.unsafeFromArray(bytes))
      else Left(SnapError.ContentNotCanonicalBase64)

  private def isBase64Char(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
      c == '+' || c == '/'

  // ---------------------------------------------------------------- helpers

  /** R43: the first field outside the exact schema, in document order, is an error naming the
    * field. The top level is pinned exactly (`repository has unknown field: unknown`, test 23).
    */
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

  private def requiredString(
      fields: Vector[(String, Json)],
      name: String,
      owner: String
  ): Either[SnapError, String] =
    required(fields, name, owner).flatMap {
      case Json.JString(value) => Right(value)
      case _                   => Left(SnapError.FieldWrongType(owner, name))
    }

  private def traverse[A, B](items: Vector[A])(
      f: A => Either[SnapError, B]
  ): Either[SnapError, Vector[B]] =
    items.foldLeft[Either[SnapError, Vector[B]]](Right(Vector.empty)) { (acc, item) =>
      acc.flatMap(out => f(item).map(out :+ _))
    }

  // ---------------------------------------------------------------- encode

  /** The repository document as a [[Json]] AST, fields in the spec's canonical order. Bytes come
    * from [[Writer]] (one canonical serializer — D7/R42).
    */
  def encode(repository: Repository): Json =
    Json.JObject(
      Vector(
        "format" -> Json.JNumber("1"),
        "frontier" -> encodeVersion(repository.frontier),
        "patches" -> Json.JArray(repository.patches.map(encodePatch))
      )
    )

  /** Canonical serialized bytes (2-space indent, expanded arrays, trailing LF — R42, test 12). */
  def encodeBytes(repository: Repository): Array[Byte] =
    Writer.writeUtf8(encode(repository))

  private def encodeVersion(version: Version): Json =
    Json.JArray(version.toPairs.map { (id, n) =>
      Json.JArray(Vector(Json.JString(id), Json.JNumber(n.toString)))
    })

  private def encodePatch(patch: Patch): Json =
    Json.JObject(
      Vector(
        "author" -> Json.JString(patch.author.value),
        "revision" -> Json.JNumber(patch.revision.toString),
        "base" -> encodeVersion(patch.base),
        "message" -> Json.JString(patch.message),
        "changes" -> Json.JArray(patch.changes.map(encodeChange))
      )
    )

  private def encodeChange(change: Change): Json = change match
    case Change.Text(path, edit) =>
      Json.JObject(
        Vector(
          "type" -> Json.JString("text"),
          "path" -> Json.JString(path.value),
          "edit" -> Json.JArray(edit.ops.map(encodeOp))
        )
      )
    case Change.Put(path, content) =>
      Json.JObject(
        Vector(
          "type" -> Json.JString("put"),
          "path" -> Json.JString(path.value),
          "content" -> Json.JString(
            Base64.getEncoder.encodeToString(IArray.genericWrapArray(content).toArray)
          )
        )
      )
    case Change.Delete(path) =>
      Json.JObject(
        Vector(
          "type" -> Json.JString("delete"),
          "path" -> Json.JString(path.value)
        )
      )

  private def encodeOp(op: EditOp): Json = op match
    case EditOp.Retain(count)  => Json.JObject(Vector("retain" -> Json.JNumber(count.toString)))
    case EditOp.Delete(count)  => Json.JObject(Vector("delete" -> Json.JNumber(count.toString)))
    case EditOp.Insert(tokens) =>
      Json.JObject(Vector("insert" -> Json.JArray(tokens.map(Json.JString.apply))))

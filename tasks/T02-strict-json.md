# T02 — Strict JSON layer & canonical writer (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T01
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/json/`: JSON AST + strict parsing layered on **jawn-parser** (D2 — Typelevel
Scala tokenizer, parsing through our custom `Facade`): the facade builds our AST,
errors on duplicate object keys naming the key, and keeps raw decimal number text
(integer-ness and the ±(2^53−1) bound judged from text, never via Double); jawn
parse errors map to the `invalid JSON` diagnostic class. Plus the canonical writer
(ours, 2-space indent, every array element on its own line, trailing LF — the exact
style test 12 byte-pins). Also `core/Errors.scala` seeded with the `SnapError` ADT and
catalog object. DESIGN §6, §8; D2, D4, D5, D7; R41–R43 (structural half); gotchas 4–5.

## Scope
`snap/scala/src/main/scala/snap/json/*.scala`,
`snap/scala/src/main/scala/snap/core/Errors.scala`, tests in
`snap/scala/src/test/scala/snap/json/`.

## Acceptance criteria
- [x] Parser rejects: duplicate keys (error contains the key name), `1.5`, `1e2`,
      `9007199254740992` (gotcha 4 — from text), accepts `9007199254740991`; malformed
      JSON (unterminated string, bad escape, trailing garbage) maps to the
      `invalid JSON` diagnostic class; unit tests cover each.
- [x] Round-trip property: `parse(write(v)) == v` for generated JSON values.
- [x] Writer golden test reproduces the exact serialization style pinned by test 12
      (`snap/tests/12-http-server.yaml` `body_text_equals`), including expanded
      `[id, revision]` pairs and trailing LF.
- [x] No mutable state, no exceptions for control flow (`Either[SnapError, _]`); parser
      behavior independent of map iteration order (AST objects preserve source order).

## Notes / decisions

- **Files** (all within declared scope): `snap/json/{Json,AstFacade,JsonParser,Writer}.scala`
  and `snap/core/Errors.scala`. The parser object is named `JsonParser` (not `Parser`) to
  avoid clashing with `org.typelevel.jawn.Parser`.
- **Duplicate keys without throw/var:** jawn offers no non-throwing abort, so the facade
  *records* duplicate keys in a per-parse buffer and `JsonParser` checks it after the
  parse. Because parsing is strictly left-to-right, this yields exact
  first-error-in-document-order precedence: a duplicate key recorded earlier in the text
  wins over a later syntax error (unit-tested both ways). The facade is a named
  mutability boundary (jawn's push-style callbacks force accumulation): `ListBuffer`s
  confined to one parse on one thread, appended in document order, read only after the
  parse — no `var`, no `throw`, no `null`, **no `scalafix:ok` suppressions needed**.
  Object contexts store alternating `Left(key)/Right(value)` cells, so field source
  order is preserved by construction (`JObject(Vector[(String, Json)])`).
- **`invalid JSON` message shapes** (only the substring is test-pinned; D5 untested →
  `snap: <detail>`): `invalid JSON at line <l> column <c>` for tokenizer rejections
  (position from jawn's `ParseException`; single line, no input echo) and
  `invalid JSON: unexpected end of input` for truncated input
  (`IncompleteParseException` has no position).
- **Integer extraction is `Option`-based** (`Json.asSafeInteger`): the *lexing* of
  `1.5`/`1e2` must succeed — test 23 pins the typed `positive safe integer` message for
  a fractional revision, which could never appear if lexing failed — so "parser rejects
  1.5/1e2/2^53" is implemented at the extraction level, judged from the retained raw
  text (16-digit strings compared lexicographically against `9007199254740991`; never
  `Double`). Context-pinned messages belong to the typed codecs (T06/T14).
  Reading recorded per ambiguity policy: `-0` is accepted as integer 0 (it denotes an
  in-range integer; every positivity check downstream rejects 0 anyway); leading-zero
  forms are already rejected by the tokenizer as invalid JSON.
- **Writer style** beyond the golden: empty containers inline (`[]`/`{}` — the golden
  pins `"base": []`), minimal RFC 8259 string escaping (`\"`, `\\`, named short escapes,
  `\u00xx` lowercase for remaining chars < 0x20 via locale-independent
  `Integer.toHexString`), non-ASCII emitted literally (test 25/26 expect literal
  `é`/`😀` bytes). Added `Writer.writeUtf8` so D7 consumers (fs/http) get canonical
  bytes without touching the platform charset (gotcha 7).
- **Determinism tests:** round-trip `parse(write(v)) == v`, byte-determinism of `write`
  across repeated runs, `write∘parse∘write` fixed point, and repeated-parse equality.
  No order-permutation property exists at this layer *by design* — object field order
  is part of the AST value (source order is semantics); typed-value comparison across
  key orderings lands with the codecs (T06).
- **Golden** lifted verbatim (419 bytes, trailing LF) from
  `snap/tests/12-http-server.yaml` into `WriterSuite`; the byte count is asserted, and
  the golden is checked in all three directions (write(value) == golden,
  parse(golden) == value, parse-then-write fixed point).
- Verification (2026-09-04): `sbt test` — 60 passed, 0 failed (44 JsonParserSuite,
  11 WriterSuite, 4 JsonRoundTripSuite properties, 1 T01 smoke); `sbt scalafmtCheckAll`
  and `sbt "scalafixAll --check"` both green.

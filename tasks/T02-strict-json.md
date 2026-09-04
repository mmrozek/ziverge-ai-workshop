# T02 — Strict JSON layer & canonical writer (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T01
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/json/`: JSON AST, strict parser (duplicate object keys are errors naming the key;
numbers keep raw decimal text; integer-ness and the ±(2^53−1) bound judged from text,
never via Double), and the canonical writer (2-space indent, every array element on its
own line, trailing LF — the exact style test 12 byte-pins). Also `core/Errors.scala`
seeded with the `SnapError` ADT and catalog object. DESIGN §6, §8; D4, D5, D7;
R41–R43 (structural half); gotchas 4–5.

## Scope
`snap/scala/src/main/scala/snap/json/*.scala`,
`snap/scala/src/main/scala/snap/core/Errors.scala`, tests in
`snap/scala/src/test/scala/snap/json/`.

## Acceptance criteria
- [ ] Parser rejects: duplicate keys (error contains the key name), `1.5`, `1e2`,
      `9007199254740992` (gotcha 4 — from text), accepts `9007199254740991`; unit
      tests cover each.
- [ ] Round-trip property: `parse(write(v)) == v` for generated JSON values.
- [ ] Writer golden test reproduces the exact serialization style pinned by test 12
      (`snap/tests/12-http-server.yaml` `body_text_equals`), including expanded
      `[id, revision]` pairs and trailing LF.
- [ ] No mutable state, no exceptions for control flow (`Either[SnapError, _]`); parser
      behavior independent of map iteration order (AST objects preserve source order).

## Notes / decisions

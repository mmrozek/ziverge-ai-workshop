# T04 — Paths, Utf8Order, trees (2 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T01
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/`: `Utf8Order` (unsigned UTF-8 byte order — D23, gotcha 1), `SnapPath`
validating factory (R23 with D12's control-char extent, no first segment `.snap`,
D13 nested `.snap` tracked), segment access, prefix-free predicate over path sets
(R25), `Tree` (sorted path→bytes map with ancestor/descendant queries needed by
namespace resolution). DESIGN §2, §3.

## Scope
`snap/scala/src/main/scala/snap/core/{Path,Tree}.scala`, tests in
`snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [ ] Path validation rejects: empty, control chars 0x00–0x1F and 0x7F, backslash,
      empty/`.`/`..` segments, first segment `.snap`; accepts `sub/.snap/x` (D13) and
      non-ASCII UTF-8 — unit test per rule.
- [ ] `Utf8Order` sorts `nested/file` < `z` < `é` < `😀` (test 25's pinned order) and a
      directed case where UTF-16 order would differ (e.g. U+FFFD vs U+10000) — proving
      byte order, not code-unit order (gotcha 1).
- [ ] Prefix-free check: `a` + `a/b` rejected, `a` + `ab` accepted, `a/b` + `a/c`
      accepted; property test that Tree iteration order is always sorted regardless of
      insertion order.
- [ ] No `var`, no wall-clock/env access in this module.

## Notes / decisions

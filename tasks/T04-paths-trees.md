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
- [x] Path validation rejects: empty, control chars 0x00–0x1F and 0x7F, backslash,
      empty/`.`/`..` segments, first segment `.snap`; accepts `sub/.snap/x` (D13) and
      non-ASCII UTF-8 — unit test per rule.
- [x] `Utf8Order` sorts `nested/file` < `z` < `é` < `😀` (test 25's pinned order) and a
      directed case where UTF-16 order would differ (e.g. U+FFFD vs U+10000) — proving
      byte order, not code-unit order (gotcha 1).
- [x] Prefix-free check: `a` + `a/b` rejected, `a` + `ab` accepted, `a/b` + `a/c`
      accepted; property test that Tree iteration order is always sorted regardless of
      insertion order.
- [x] No `var`, no wall-clock/env access in this module.

## Notes / decisions

- **Local error type (parallel-worktree constraint):** `snap/core/Errors.scala`
  (T03/T06 scope) did not exist in this task's worktree, so path validation returns a
  minimal local `enum PathError` (`Empty`, `IllegalCharacter`, `MalformedUnicode`,
  `EmptySegment`, `DotSegment`, `ReservedFirstSegment`) in `Path.scala`. Fold these
  into the `SnapError` catalog at integration; checks run in declared order, first
  failure wins (deterministic).
- **Unpaired surrogates rejected** (`PathError.MalformedUnicode`): SPEC §2 requires a
  *UTF-8* path; a JVM string with an unpaired surrogate has no UTF-8 encoding, and
  accepting one would break the byte-sort equivalence. Minor ambiguity resolved on the
  strict side per the ambiguity policy — surface at phase review.
- **`Utf8Order`** compares Unicode code points (tail-recursive `codePointAt` walk) —
  equivalent to unsigned UTF-8 byte order for well-formed strings and still total on
  malformed ones. Proven byte-order-equivalent by a scalacheck property against
  `getBytes(UTF_8)` unsigned comparison, plus the directed U+FFFD vs U+10000 case
  where `String.compareTo` disagrees. Lives in `Path.scala` per DESIGN §2. T03 may
  land an equivalent comparator in `Version.scala` — integration dedupes to this one
  (D23: single comparator).
- **`SnapPath`** is a case class with a private constructor (Scala 3 also privatizes
  `apply`/`copy`), so instances exist only via `parse`. Segment-prefix queries use the
  string form (`startsWith(value + "/")`) — equivalent to segment comparison since `/`
  cannot occur inside a segment; `a` vs `ab` correctly unrelated.
- **`Tree`** wraps `TreeMap[SnapPath, IArray[Byte]]` keyed by `SnapPath.ordering`, so
  every exposed iterator is sorted by construction (insertion-order independence is
  structural, plus property-tested with reverse/interleave permutations and repeated
  builds, including equal `hashCode`). Content is `IArray[Byte]`; `Tree.equals`/
  `hashCode` compare byte content, not array identity. `Tree` deliberately does NOT
  enforce prefix-freeness (replay must represent conflicts before resolving them);
  `isPrefixFree` (R25) is the validation-point predicate, `ancestorsOf`/`descendantsOf`
  serve §6.2 namespace resolution.
- **Verification (2026-09-04):** `sbt scalafmtAll scalafixAll test scalafmtCheckAll
  "scalafixAll --check"` — tests: 35 passed, 0 failed (SmokeSuite 1, Utf8OrderSuite 6,
  SnapPathSuite 20, TreeSuite 8; includes scalacheck properties); both lint gates
  green. No `scalafix:ok` suppressions. No files outside the declared scope
  (worktree mode: `TASKS.md`/`CURRENT.md`/`AGENTS-STATUS.md` intentionally untouched;
  shared test generators added as `src/test/scala/snap/core/CoreGens.scala` within the
  test-directory scope).
- **Integration note (orchestrator, 2026-09-04):** deduped per D23 to T03's
  `Utf8Order` in `Ids.scala` (semantically identical code-point walk; T03's landed on
  `main` first with a byte-compare property test). Removed from `Path.scala`: the local
  `Utf8Order` object and this task's `Utf8OrderSuite.scala` (fully subsumed by T03's
  suite — pinned test-25 order, UTF-16-divergence directed case, prefix rule, plus the
  byte-order property). All other T04 files integrated verbatim.

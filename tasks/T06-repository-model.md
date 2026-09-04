# T06 — Repository model, codec, structural validation (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T02, T03, T04
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/Patch.scala` (Patch/Change/Dot, result computation R46, structural patch
equality R47, message rules R48/D16, changes rules R49–R52) and `snap/core/Repo.scala` +
`snap/json/RepoCodec` (exact schema R40–R43, unknown fields named, validation §4.5 steps
1–4: schema, patch sorting by author/revision, one value per dot, contiguous revisions,
complete base closure, `revision = base[author]+1`, acyclicity, no unreachable patches
R44). Plus `snap/fs/Store.scala`: read `repository.json`, atomic same-directory
temp-file write (R105, gotcha 10) using the canonical writer. DESIGN §6, §7.

## Scope
`snap/scala/src/main/scala/snap/core/{Patch,Repo}.scala`,
`snap/scala/src/main/scala/snap/json/RepoCodec.scala`,
`snap/scala/src/main/scala/snap/fs/Store.scala`, tests under
`snap/scala/src/test/scala/snap/{core,json}/`.

## Acceptance criteria
- [x] Codec decodes the spec §4.1 example and rejects, with the test-pinned message
      fragments: unknown fields at every level, missing fields, wrong types, fractional
      revision (`positive safe integer`), empty message (`message is empty`), empty
      changes (`changes is empty`), unsorted patches, revision gap (`missing a@x`),
      unreachable patch, base cycle (`cyclic or incomplete patch history`) — one unit
      test per rejection (source material: tests 15/23/27).
      → `RepoCodecSuite` (schema/value rejections, exact pinned strings asserted) +
      `RepoValidateSuite` (history rejections, tests 15/23/27 fixtures lifted).
- [x] Structural patch equality (R47) is over parsed typed values: same patches with
      different JSON whitespace/key order compare equal (test 26's premise).
      → `RepoCodecSuite` "whitespace and key order…" / "put content compares by
      bytes…"; `PatchSuite` Put/Patch equality (bytes, not array identity).
- [x] Store round-trip: write then read yields an equal repository value; write goes
      through a same-directory temp file and never leaves a partial
      `repository.json` on simulated failure before the move.
      → `StoreSuite`: round-trip, canonical bytes, stage/commit crash-window test
      (target byte-identical after stage; temp in the same directory), failing write
      → typed error and nothing created.
- [x] Validation performs no filesystem mutation; changes-vs-base checks (steps 5–6)
      are explicitly deferred to T07 (leave a typed hook, not a silent gap).
      → `Repo.validate` is pure (core has no IO imports); returns
      `Repo.StructurallyValid(repository, results)` — the typed proof value T07's
      steps 5–6 consume (per-patch result versions precomputed for the ready-loop).

## Notes / decisions
- **Pre-implementation pointers from `reviews/T03-review.md` triage (orchestrator):**
  migrate T03's `Either[String, A]` reason-phrase seams into `SnapError`/`Messages`
  (finding 1); wire the R32 AST codec onto `Version.toPairs`/`fromPairs` (finding 3);
  route every JSON-decoded path through `SnapPath.parse` (finding 2 guard).
  Additionally (T05 review finding 3): migrate `EditScript`'s task-local `EditError`
  messages into the `SnapError`/`Messages` catalog alongside T03's seams.
- **Implementation notes (implementer, 2026-09-04):**
  - **Error-catalog migration done as directed.** `Revision.check`, `ContributorId.parse`,
    and `Version.{parse,fromPairs,fromMap,updated}` now return `Either[SnapError, A]`;
    reasons are typed enums (`IdError`, `VersionError` — new, in `Errors.scala`) rendered
    only by `Messages`. `EditError` stays as EditScript's typed reason channel (mirroring
    `PathError`), but its strings moved verbatim into `Messages.editError`; the codec
    wraps it as `SnapError.InvalidEdit`. Files touched outside this task's declared
    scope, sanctioned by the pointers above: `Ids.scala`, `Version.scala`,
    `EditScript.scala`, plus two assertions in `VersionTextSuite` that now read the
    fragment through `.message`. No other call-site fallout (compiler-verified).
  - **R32 wiring done:** `RepoCodec.decodeVersion` → `Version.fromPairs` (frontier and
    every patch `base`); noncanonical order and pair-revision bounds covered by unit
    tests lifted from test 23. Every JSON-decoded path goes through `SnapPath.parse`
    (T03 review finding 2 guard) — `.snap/…`, empty, `//`, `..`, backslash all rejected
    with the pinned `path is invalid` fragment; `sub/.snap` accepted (D13).
  - **Message shapes:** where test 23 anchors a fragment at the line end behind `.+`,
    the catalog string carries a prefix (`patch message is empty`, `patch changes is
    empty`, `change has unknown field: <f>`); the top-level unknown-field message is the
    exact pinned `repository has unknown field: <f>`. Dot diagnostics render as
    `<author> revision <n>` (the shape test 16 pins in `patch collision: …`);
    `unreachable patch: <dot>` matches test 23's anchored prefix, the revision-gap
    message is `patch history is missing <dot>` (contains test 15's `missing a@x`).
  - **One-value-per-dot decision (minor ambiguity, recorded per policy):** a dot listed
    twice is rejected even when the two values are structurally equal — R44 says
    `patches` is *exactly* the causal closure, which lists each patch once
    (`duplicate patch <dot>`, untested wording). Structurally different values at one
    dot are corruption (§3.5/R47) and reuse the test-16 `patch collision: <dot>` shape
    locally.
  - **Model choices:** `Repository(frontier, patches)` does not store `format` — the
    codec accepts only `1` and always writes `1`. `Change.Put` overrides equality to
    compare content bytes (`IArray` is a runtime array — reference equality otherwise;
    R47 and test 26 depend on this). `Patch.make` enforces §4.5-step-1 value rules
    (revision bounds, R48 message character rules incl. the unpaired-surrogate case,
    R49 changes rules); the R46 dot-consistency rule stays in `Repo.validate` step 3 so
    a decoded-but-wrong patch is representable and rejectable with the pinned wording.
    D16 honored: the 4096-byte limit is NOT in repository validation (unit test pins a
    5000-byte message as accepted).
  - **Base64 (R50):** shape pre-check (length % 4, `=` only as final one/two chars,
    alphabet-only body) makes `java.util.Base64` total — no exception control flow —
    then decode-and-re-encode must reproduce the input exactly (rejects `abc`, `YR==`,
    misplaced padding). Encode side reuses the JDK encoder (canonical by construction).
  - **Store:** `snap/fs` is the named filesystem effect boundary — NIO failures become
    typed `CannotRead/WriteRepository` via `Try` (same pattern as `JsonParser`'s jawn
    boundary; D4's exit-2 catch-all remains for unexpected exceptions only). Atomic
    write = fixed-name same-directory temp (`repository.json.tmp`, deterministic;
    single-process model) + `ATOMIC_MOVE`+`REPLACE_EXISTING`. Read gate: bytes must be
    NUL-free UTF-8 (`TextTokens.decode`) before parsing; failure renders in the
    `invalid JSON` diagnostic class (untested wording).
  - **Validation order (deterministic, first violation wins):** sorted/dup-dot →
    R46 increments → per-contributor contiguity → base-dot existence → frontier-dot
    existence → reachability (R44) → acyclicity fixpoint (R60). Existence + reachability
    together imply R45's closure-containment; the acyclicity check is a pure
    ready-set fixpoint (O(n²), correctness first).
  - **Deferred:** `ConfigCodec` left for T09 (not cleanly colocated — config schema is
    a separate file with its own rules; the strict field helpers in `RepoCodec` are
    reusable). Step-5/6-dependent pinned strings (`does not consume old content` at
    repository level, `consumes beyond old content` vs a real base, `delete of absent
    path`, `tree paths conflict`, `no-op change`, create-over-present, text-over-binary,
    non-canonical token result) are T07's wiring; their structural cousins (adjacency,
    counts, empty insert, one-key rule) land here.
  - **Scope deviation:** tests also added under `src/test/scala/snap/fs/` (task scope
    listed only `snap/{core,json}/`) — required by the Store acceptance criterion.
  - **Verification:** `sbt test` 257/257 pass (T06 suites: PatchSuite 11,
    RepoValidateSuite 15, RepoCodecSuite 33 incl. 3 scalacheck properties, StoreSuite
    11; fresh-seed re-run green); `sbt scalafmtCheckAll` pass; `sbt "scalafixAll
    --check"` pass; no `scalafix:ok` suppressions.

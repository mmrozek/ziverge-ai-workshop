# T01 — Scala workspace skeleton & lint gate (2 SP)

- **Phase:** 1 — Foundation
- **Depends on:** —
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
The `snap/scala/` sbt workspace exists in exactly the shape the provided runners expect
(SPEC-NOTES §3.1, DESIGN §2, D1–D3): buildable fat jar, lint gate live, stub `Main` that
prints `snap: not implemented` to stderr and exits 1. After this task,
`./snap/verify --lang scala` builds and runs the suite (0/28 passing).

## Scope
`snap/scala/build.sbt`, `snap/scala/project/plugins.sbt`,
`snap/scala/project/build.properties`, `snap/scala/.scalafmt.conf`,
`snap/scala/.scalafix.conf`, `snap/scala/src/main/scala/Main.scala`,
`snap/scala/src/test/scala/` (one smoke test), `.gitignore` additions
(`target/`, `.bloop/`, `.bsp/`, `.metals/`).

## Acceptance criteria
- [x] `cd snap/scala && sbt -batch assembly` produces exactly one jar matching
      `target/*/scala-*/*-assembly-*.jar` with `Main-Class` in the manifest;
      `java -jar <jar> --version` runs (stub behavior acceptable).
- [x] `./snap/verify --lang scala` builds, runs all 28 tests, and reports 0 passed —
      the command itself succeeds in driving the suite (no harness/launcher errors).
- [x] `sbt scalafmtCheckAll` and `sbt "scalafixAll --check"` pass; scalafix rules per
      `docs/SCALA-CONVENTIONS.md` (DisableSyntax: noVars/noThrows/noNulls/noReturns/
      noWhileLoops, OrganizeImports, RemoveUnused with `-Wunused:all`).
- [x] munit + scalacheck available in test scope only (D3); `sbt test` runs the smoke
      test; runtime dependencies in the assembly are exactly those listed in DESIGN D2
      (jawn-parser), nothing else.

## Notes / decisions
- **Versions pinned** (all resolved against Maven Central at implementation time,
  no version was specified by DESIGN beyond D1–D3): Scala `3.3.8` (latest 3.3 LTS
  patch), sbt-assembly `2.5.0`, sbt-scalafmt `2.6.2`, sbt-scalafix `0.14.7`,
  jawn-parser `1.7.0` (D2), munit `1.3.6` + munit-scalacheck `1.3.1` + scalacheck
  `1.20.0` (D3, `% Test`), scalafmt-core `3.11.5`.
- **sbt launcher version bumped to 1.13.0** (from the machine-default 1.11.4):
  sbt-scalafmt 2.6.2 requires sbt 1.12.9+ (`sbt-scalafmt requires sbt 1.12.9+`
  hard error observed on first `scalafmtAll` run). Recorded here since it's outside
  the task's literal scope list but was necessary to make the lint gate work at all;
  `project/build.properties` is otherwise squarely in scope.
- `name := "snap"` set explicitly in `build.sbt` (the sbt default project name would
  otherwise be derived from the directory `scala/`) so the assembly jar is
  `snap-assembly-<version>.jar` — clearly matches the runner's discovery glob
  independent of directory naming.
- `assembly / assemblyMergeStrategy` adds a `module-info.class` discard case (falls
  through to the default strategy otherwise); jawn-parser's only transitive deps are
  scala-library/scala3-library, so no other conflicts were expected or observed —
  kept as defensive boilerplate, not because a conflict was hit.
- `.scalafix.conf`: `RemoveUnused.imports = false` because `OrganizeImports`
  (`removeUnused = true`) already owns import cleanup; running both on imports is a
  documented scalafix footgun (double-edits / churn), not a `scalafix:ok` suppression.
- Verification (2026-09-04, this machine): `sbt -batch assembly` → exactly one jar at
  `snap/scala/target/scala-3.3.8/snap-assembly-1.0.0.jar` (manifest `Main-Class: Main`);
  `java -jar <jar> --version` and `java -jar <jar>` both print `snap: not implemented`
  to stderr and exit 1; `sbt test` → 1 passed (SmokeSuite); `sbt scalafmtCheckAll` and
  `sbt "scalafixAll --check"` → both success with no diffs; assembly jar inspected —
  contains only `Main.*`, `org/typelevel/jawn/**`, and `scala/**` (no munit/scalacheck
  leakage into the runtime jar). `./snap/verify --lang scala` from repo root → suite
  builds and runs all 28 provided cases, `0/28 passed`, all 28 failures are the stub's
  `snap: not implemented` / exit 1 (no harness, build, or launcher errors).

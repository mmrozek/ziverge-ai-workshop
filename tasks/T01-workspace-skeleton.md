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
- [ ] `cd snap/scala && sbt -batch assembly` produces exactly one jar matching
      `target/*/scala-*/*-assembly-*.jar` with `Main-Class` in the manifest;
      `java -jar <jar> --version` runs (stub behavior acceptable).
- [ ] `./snap/verify --lang scala` builds, runs all 28 tests, and reports 0 passed —
      the command itself succeeds in driving the suite (no harness/launcher errors).
- [ ] `sbt scalafmtCheckAll` and `sbt "scalafixAll --check"` pass; scalafix rules per
      `docs/SCALA-CONVENTIONS.md` (DisableSyntax: noVars/noThrows/noNulls/noReturns/
      noWhileLoops, OrganizeImports, RemoveUnused with `-Wunused:all`).
- [ ] munit + scalacheck available in test scope only (D3); `sbt test` runs the smoke
      test; no runtime dependencies in the assembly (D2).

## Notes / decisions

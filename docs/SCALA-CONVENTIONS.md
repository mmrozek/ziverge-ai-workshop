# Scala conventions — snap

Binding for all Scala code in this repo; the `scala-antipatterns` skill audits them
mechanically. Run that audit before every phase review.

## Language & style

- Scala 3 syntax. Prefer `enum` and sealed `case class` ADTs over inheritance.
  Make illegal states unrepresentable — e.g. a clock comparison result is an
  `enum Ordering { case Before, After, Equal, Concurrent }`, never an Int convention
  or a pair of booleans.
- No `var` and no `scala.collection.mutable.*` in domain logic (clock, history,
  merge, storage model). Use `foldLeft`, recursion, or `Iterator` chains. Mutable
  state is allowed only at named boundaries (CLI loop, caches) with a comment
  stating the invariant that makes it safe.
- No `try/catch` as control flow in domain logic — encode failure in types.
  The single error strategy (typed `Either`s vs. exceptions caught at exactly one
  boundary in `Main`) gets locked in DESIGN.md; never mix both — half-migrated
  error handling is a reliable source of review findings.
## Formatting & linting (mandatory)

- **scalafmt** — `.scalafmt.conf` committed in the skeleton task with a pinned
  `version` and `runner.dialect = scala3`. Fix with `sbt scalafmtAll`; verify with
  `sbt scalafmtCheckAll`.
- **scalafix** — sbt-scalafix plugin with `.scalafix.conf` committed alongside:
  - `DisableSyntax` (noVars, noThrows, noNulls, noReturns, noWhileLoops) — the
    mechanical arm of the rules above; exceptions only via explicit
    `scalafix:ok` with a justifying comment, treated like any boundary comment.
  - `OrganizeImports`, `RemoveUnused` (needs `-Wunused:all` in scalacOptions).
  - Fix with `sbt scalafixAll`; verify with `sbt "scalafixAll --check"`.
- **Gates:** every task commit passes `scalafmtCheckAll` and `scalafixAll --check`;
  phase gates re-run both. If the provided harness ships its own formatter/linter
  config, that config wins — ours adapts to it, never the reverse.
- Both config files are locked decisions: changing them is a DESIGN.md changelog
  entry, not a drive-by edit.

## Determinism (project-critical)

- Never call `System.currentTimeMillis`/`nanoTime`, `Instant.now`/`LocalDateTime.now`,
  `java.util.Date`, `System.getenv`/`getProperty`, or `scala.util.Random` in domain
  code. Thread time, configuration, and replica identity in as values on state —
  re-derive from state, never read the ambient environment deep in the code.
- Output and serialization must be byte-identical across runs and JVMs:
  - iterate `Map`/`Set` only through an explicit ordering (`.toSeq.sortBy(...)`);
  - number/string formatting through `Locale.ROOT` (`String.format(Locale.ROOT, …)`
    or `f"…"`) — bare `String.format` uses the default locale and breaks on
    non-English JVMs.
- Tie-breaks between concurrent updates are explicit, documented total orders
  (e.g. lexicographic replica id) — never insertion order, hash order, or
  processing order.

## Libraries

- JDK-first for infrastructure (`java.net.http`, etc.). Prefer an established
  library over hand-rolling a nontrivial format, but every new dependency is a
  DESIGN.md locked-decisions entry, never an ad-hoc import. Test framework:
  whatever the provided suite dictates (munit is a good default otherwise).

## Tests

- Tests assert outcomes, not internals. Our unit tests exist for our debugging;
  the provided suite is the contract and the only definition of done.

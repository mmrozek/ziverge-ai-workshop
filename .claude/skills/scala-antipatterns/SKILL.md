---
name: scala-antipatterns
description: Audit the project's Scala sources for anti-patterns — var declarations, try/catch control flow, hand-rolled serialization codecs, and nondeterminism smells (clock/env/random access, unordered iteration). Produces a triaged FIX/NOFIX report, then asks how to proceed. Run before every phase review, or on request.
---

# Scala anti-pattern audit

Audit the Scala sources (search root: the project's `src/main/scala/`; exclude
`target/` and generated files), present findings as a table, then ask the user how
to proceed. Classification context: `docs/SCALA-CONVENTIONS.md`.

## Patterns to detect

### 1. `var` declarations
- Match: `\bvar\s+[a-zA-Z_]`
- **FIX (medium):** local accumulator/loop state — rewrites to `foldLeft`,
  recursion, or `Iterator` chaining.
- **NOFIX (low):** intentional shared state at a documented boundary (comment states
  the invariant). Note why in the report.

### 2. `try { … } catch { … }` blocks
- Match: `\btry\s*[\{(]` with a `\bcatch\b` in the same expression.
- **FIX (medium):** value-returning block — replace with `scala.util.Try`/`.toEither`
  or the typed error channel DESIGN.md locks.
- **NOFIX (low):** side-effecting cleanup with no return value, or the single
  designated boundary in `Main`.

### 3. Hand-rolled serialization codecs
- Match: `def encode|def decode|given .*(Encoder|Decoder|Codec)|implicit val .*(Encoder|Decoder|Codec)`
- **FIX (high):** mirrors the case-class shape one-for-one and a derivation pattern
  already exists in the codebase — replace with derivation. Don't introduce a new
  dependency family unprompted.
- **NOFIX (medium):** transforms the shape (renames, flattens, custom wire format) —
  derivation would lose intent. **Always NOFIX** if the spec pins an exact byte
  format: hand-rolled output is then the point.

### 4. Nondeterminism smells (project-critical)
- Match: `System\.(currentTimeMillis|nanoTime|getenv|getProperty)|Instant\.now|LocalDateTime\.now|new java\.util\.Date|scala\.util\.Random`
  plus `Map`/`Set` iteration feeding output or merge decisions without an explicit
  ordering (`.toSeq.sortBy`, `.toList.sorted`, or a `SortedMap`/`SortedSet`).
- **FIX (critical):** any hit in domain code (clock, merge, history, serialization) —
  the fix is threading the value in through state, per the conventions doc.
- **NOFIX (low):** boundaries DESIGN.md explicitly designates (CLI entry, logging).

## How to run

1. Run the four greps **in parallel** (Grep tool or `grep -rn -E`).
2. `Read` surrounding context before classifying — never classify from the grep line
   alone. A `try` and its `catch` are one finding; de-duplicate.
3. Skip hits whose context clearly makes the pattern legitimate, but record skips of
   pattern 4 in the report anyway (nondeterminism gets no silent passes).

## Report

One markdown table, grouped by file, ordered by line:

| File:line | Pattern | Severity | Action | Reason |
|---|---|---|---|---|

Then a totals line: `<N> findings: <X> FIX, <Y> NOFIX`. Keep it under ~40 rows
(top-30 by severity + suppressed count if more).

## Ask how to proceed

Call `AskUserQuestion` (header "Fix mode") with: **Fix all FIX items** /
**Pick which to fix** / **Skip — report only**. When fixing: one finding at a time,
verify after each via Metals MCP `compile-file` (or the warm sbt session), run the
tests covering the edited file, stop and report at the first failure.

**Exception:** when this audit runs as part of the `phase-review` gate, skip the
question — hand the FIX findings to the review triage instead, where accept/defer/
reject is decided and recorded.

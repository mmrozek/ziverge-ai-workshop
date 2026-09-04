# scala-antipatterns audit — phase-2 gate (2026-09-05)

Search root `snap/scala/src/main/scala/`, run in phase-review gate mode (findings feed
the phase-2 triage; no interactive fix prompt). Classification context:
`docs/SCALA-CONVENTIONS.md`, DESIGN D2/D4/D5/D7/D23. Scope: the full current
`src/main/scala` tree (38 sources — T16's concurrent-replay engine is present and
included in the scan since the skill scans the whole tree, but its findings belong to
T16's own pre-commit review, not this phase's triage; T17 is not present in this
worktree).

All four greps were re-run directly against the current tree (not copied from
phase-1's report) and every hit was read in context before classification.

| File:line | Pattern | Severity | Action | Reason |
|---|---|---|---|---|
| `Main.scala:25-26` | `try`/`catch` (brace-less Scala 3 form — matches `\btry\b`, not the skill's literal `\btry\s*[\{(]` regex) | low | NOFIX | the single designated exit-2 boundary (D4), unchanged since phase 1 (`reviews/phase-1-antipatterns.md`); invariant documented at the site; every other module is `Either`-only |
| `snap/cli/Env.scala:62` | `System.getProperty("user.dir")` | low | NOFIX | inside `Env.real()`, the one designated effect boundary (DESIGN §2/§8), unchanged since phase 1; everything downstream takes `Env` as a value; `EnvIsolationSuite` mechanically enforces no other env/property read |
| `snap/json/RepoCodec.scala` (17 encode/decode defs), `snap/json/ConfigCodec.scala` (4), `snap/core/TextTokens.scala:19,29` | hand-rolled codecs | medium | NOFIX | pre-existing from phase 1, untouched by any T11–T14 diff; spec pins exact byte formats (test 12 canonical JSON, R50 canonical base64) and ~35 verbatim per-field error strings that derivation cannot express; D2/D5/D7 make hand-rolled the point. Re-verified none of these files appear in `git show --stat` for 20f3896/13168f9/6daa92d/333f4b1 |
| `snap/core/Replay.scala:592` | `private def encode(tokens: Vector[String]): IArray[Byte]` | medium | NOFIX (out of this phase) | belongs to T16's concurrent-replay engine (phase 3, `071639e`, interleaved in the tree but not under review here); a byte-rendering helper for tokens, not a JSON codec; flagged for cross-reference only — T16 has its own pre-commit core review (`reviews/T16-review.md`) |
| `snap/core/Tree.scala:25,28,36,41,45,53,60,65` | `Map`/iterator use over `entries: TreeMap[SnapPath, IArray[Byte]]` | low | NOFIX | pre-existing (phase 1); `Tree` wraps a `TreeMap` keyed by `SnapPath.ordering` (Utf8Order, D23) — every iterator is sorted by construction regardless of insertion order; re-verified by reading (`Tree.scala:18`'s class doc + `equals`/`hashCode` at 50-62, which depend on and get this ordering guarantee) |
| `snap/fs/Materialize.scala:157` (`childNames`), cf. `snap/fs/WorkTree.scala:103` | raw-string `.sorted(Utf8Order)` on unvalidated directory-entry names, feeding install/delete order | low | NOFIX | T12 (phase 2) reuses WorkTree's already-accepted pattern (`reviews/phase-1-review.md` PR9/Nit #9: "total and deterministic on any string; ordering choice pre-validation is unobservable since such paths are rejected downstream regardless"); confirmed `Materialize.scala:150-160`'s doc comment cites `WorkTree.children` explicitly as the precedent |

Zero hits, verified with the exact regexes plus a widened `\bvar\b` / `\bcatch\b` / `\btry\b`
sweep to catch Scala 3's brace-less forms the skill's literal patterns can miss:

- **`var` declarations:** zero hits anywhere in `src/main/scala` (only a doc-comment in
  `DiffRender.scala:52` says "no `var`").
- **Nondeterminism (clock/random/env beyond the one boundary):** zero. No
  `Instant.now`, `LocalDateTime.now`, `new java.util.Date`, `scala.util.Random`,
  `System.currentTimeMillis`/`nanoTime`, or `System.getenv`/`sys.env` call anywhere;
  `String.format`/`f"…"` numeric interpolation is absent from every phase-2 file
  (`DiffRender.scala`, `CommandsDiff.scala`, `Materialize.scala`, `CommandsRevert.scala`,
  `Grammar.scala`, `CommandsServe.scala`, `CommandsInit.scala`) — all numeric rendering
  goes through plain `Int`/`Long` string interpolation, which is locale-independent.
- **Raw `String.compareTo`/unordered path comparison:** zero in phase-2's own files —
  grepped `compareTo|\.sorted\b|\.sortBy\b` across every T11–T14 file; the only hit is
  `Materialize.scala:157`'s `Utf8Order`-explicit sort (listed above).

## Phase-2's own new/changed files — zero new hits

`DiffRender.scala`, `CommandsDiff.scala` (rewritten), `Materialize.scala`,
`CommandsRevert.scala`, `Grammar.scala`, `CommandsServe.scala`, the `CommandsInit.scala`
diff, the `Cli.scala` diff, and the `Errors.scala` additions contribute **zero** hits
across all four patterns. Every finding above is either a phase-1 carryover (unchanged
by this phase's diff, already triaged at the phase-1 gate) or T16's (out of this
phase's scope).

**Totals: 6 findings — 0 FIX, 6 NOFIX.** Nothing handed to the phase-2 triage as
actionable. No pattern-4 (nondeterminism) skip needed recording beyond the two
designated boundaries already listed — the no-silent-pass rule is satisfied by listing
`Env.scala:62` explicitly above.

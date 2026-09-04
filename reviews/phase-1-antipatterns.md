# scala-antipatterns audit — phase-1 gate (2026-09-04)

Search root `snap/scala/src/main/scala/`, run in phase-review gate mode (findings feed
the phase-1 triage; no interactive fix prompt). Classification context:
`docs/SCALA-CONVENTIONS.md`, DESIGN D2/D4/D5/D7/D23.

| File:line | Pattern | Severity | Action | Reason |
|---|---|---|---|---|
| Main.scala:26 | try/catch | low | NOFIX | the single designated exit-2 boundary (D4); invariant documented at the site; Cli/domain code is Either-only |
| snap/cli/Env.scala:62 | `System.getProperty("user.dir")` | low | NOFIX | inside `Env.real()` — the one designated effect boundary (DESIGN §2/§8); everything downstream takes `Env` as a value; EnvIsolationSuite mechanically enforces no other env reads (recorded per the no-silent-pass rule) |
| snap/json/RepoCodec.scala (23 encode/decode defs), snap/json/ConfigCodec.scala (4), snap/core/TextTokens.scala:19, snap/core/Replay.scala:308 | hand-rolled codecs | medium | NOFIX | spec pins exact byte formats (test 12 canonical JSON, R50 canonical base64) and ~35 verbatim error strings with unknown-field names per level — derivation cannot express either; locked decisions D2/D5/D7 make hand-rolled the point |
| snap/core/Tree.scala:25,42,45,65 | Map iteration | low | NOFIX | `Tree` wraps a `TreeMap` keyed by `SnapPath.ordering` (Utf8Order) — every iterator is sorted by construction (D23); property-tested insertion-order independence (recorded per the no-silent-pass rule) |

- `var` declarations: **zero hits** in main sources.
- Nondeterminism greps (clock/env/random): only the two designated-boundary hits above;
  all other matches were comments stating the constraint.

**Totals: 4 findings — 0 FIX, 4 NOFIX.** Nothing handed to triage as actionable; the
two pattern-4 skips are recorded above as required.

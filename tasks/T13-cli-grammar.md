# T13 — CLI grammar matrix & port validation (2 SP)

- **Phase:** 2 — Diff, revert & validation matrices
- **Depends on:** T12 (parallel-safe with T14 — disjoint files)
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Exhaustive argument grammar per command (R79): exact positions, at-most-once options,
unknown options never treated as paths, extra operands rejected — every violation →
`snap: invalid command or arguments`, **except** `diff` grammar errors → the distinct
`snap: usage: snap diff …` channel (exact wording matched to tests 14/24's regex).
`--serve` port validation per D9 (`snap: invalid port: <arg>` — the server itself is
T19; grammar and port parsing land now). DESIGN §8.

## Scope
`snap/scala/src/main/scala/snap/cli/Cli.scala`, tests in
`snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [x] Provided tests `14-cli-errors` and `24-cli-grammar-matrix` pass
      (`--filter cli-errors`, `--filter cli-grammar`).
- [x] Table-driven unit test covering every command × {extra operand, unknown option,
      duplicate option, misplaced option, missing option value}.
- [x] Port parsing: canonical decimal 0–65535 accepted; `65536`, `-1`, `08`, `abc`,
      empty → `snap: invalid port: <arg>` (D9), exit 1, no server startup attempt.
- [x] Grammar evaluation happens before repository discovery/IO for every command
      (negative constraint: a grammar error in a non-repo directory still prints the
      grammar error, not `not a Snap repository`).

## Notes / decisions

- **Architecture:** added a new `Grammar` object (`snap/scala/src/main/scala/snap/cli/Grammar.scala`)
  holding a declarative `Map[Command, Rule]` — one pure `List[String] => Either[SnapError, Unit]` per
  command. `Cli.run` calls `Grammar.check(cmd, operands)` immediately after `parse` and BEFORE
  `Command.needsRepoDiscovery`/`discoverRepo` (fixes CR7: previously several commands — e.g. `config`
  without `--global`, `status`, `diff` — ran repo discovery before their own handler's coarse operand
  check, so a grammar-invalid invocation outside a repository wrongly reported `not a Snap repository`
  instead of the grammar error). This is outside the task's literal "Scope" line (which names only
  `Cli.scala`), but is the direct, unavoidable fix for the CR7 acceptance criterion — a single new
  file, touching no other module's internals, chosen specifically so T17's future `merge` semantics
  and T14's `Errors.scala`/validator work stay disjoint from this change.
- **Scope note (CommandsInit.scala):** also tightened `CommandsInit.parsePath`'s own empty-operand
  guard (CR14) as defense-in-depth, even though `Grammar.initRule` already blocks `snap init ""` at
  the `Cli.run` gate — so the handler is correct on its own, not only behind that gate (e.g. if a test
  or future caller invokes `CommandsInit.handler` directly, as `CommandsInitSuite` already does for
  another case). This is the only edit outside `Cli.scala`/`Grammar.scala`/the new `--serve` files/
  tests.
- **`--serve` (D9/SPEC §7.9):** added `CommandsServe.scala` — grammar-only arity is `Grammar`'s job;
  this handler owns D9's port *value* (canonical decimal `0..65535`, default `8765`, mirroring
  `Version.parseRevisionText`'s ASCII-digit/no-leading-zero idiom) and, on a valid port, still returns
  `SnapError.NotImplemented` — the real server (repository snapshot, HTTP bind, SIGINT/SIGTERM) is
  T19's job. Wired into `Cli.defaultCommands` (`Command.Serve -> CommandsServe.handler`), replacing
  the generic stub for that one case only; `Command.Merge` is untouched (still the stub) per the
  instruction to leave T17's command alone.
- **Ordering decision — port validation vs. repository discovery (ambiguity, non-core):** D9 doesn't
  say whether an invalid `--serve` port must be reported even when no repository is present. SPEC
  §7.9's own bullet order lists "Validates and snapshots the current repository at startup" before any
  binding/port language, and D10 already establishes the precedent that repository-presence checks
  win over an operand's *value* validity for `config` (an invalid contributor id given without
  `--global` outside a repo still reports `not a Snap repository`, not the id error). By analogy,
  `--serve`'s port value check runs after repository discovery (`Command.needsRepoDiscovery` already
  returns `true` for `Serve`, unchanged) — only the *shape* (arity) gate moved earlier, not the port
  *value* check. Both provided tests (14, 24) run `--serve` inside an already-valid repo, so this
  choice doesn't affect them; recorded here for the holdout audit.
- **CR14 scope:** read narrowly — "empty operands are grammar errors" is fixed for `init`'s path
  operand only (the case CR14 names, where an absent operand has a default-substitution meaning that
  an explicit `""` must not silently receive). Other commands' operands that can legally be empty
  strings already have their own, more specific semantic diagnostics for emptiness (e.g. `commit ""`
  → `invalid commit message`, test 25; a bad/empty `config` id → `invalid contributor id: ...`) — those
  are left alone, since generalizing CR14 to every operand would silently downgrade already-tested,
  more-specific error classes to the generic grammar error.
- **Existing test updated:** `CliSuite`'s "known-but-unimplemented commands" test previously called
  `merge` with zero operands (an artifact of the pre-T13 stub era, when `Merge` had no real arity at
  all); SPEC §7.8 requires exactly one `<repository>` operand, so that invocation is now itself a
  grammar error. Updated the test to invoke `merge other-repo` so it still exercises the "reaches the
  stub" path it was written for.
- **Diff's grammar mirrored, not shared:** `Grammar.diffRule` duplicates `CommandsDiff.handler`'s
  three-shape match (rather than having one call the other) because `CommandsDiff` also needs to
  decide *behavior* per shape (render vs. `NotImplemented` for the `--repo` form), not just legality;
  `Grammar` only ever needs the legal/illegal boundary. Both are SPEC §7.6-derived and were kept
  side by side deliberately — touching `CommandsDiff.scala` was avoidable and is out of this task's
  scope.
- **Files touched:** `Cli.scala` (grammar gate + `Serve` wiring), new `Grammar.scala`, new
  `CommandsServe.scala`, `CommandsInit.scala` (CR14 defense-in-depth only), `core/Errors.scala`
  (three `// T13 additions` blocks adding `SnapError.InvalidPort` + its `Messages.invalidPort`
  rendering — no existing entries reordered/reworded), plus tests: new `GrammarSuite.scala`, new
  `CommandsServeSuite.scala`, additions to `CliSuite.scala` (CR7 negative-constraint tests + the
  `merge` fix above) and `CommandsInitSuite.scala` (CR14 regression test).

## Pre-implementation pointers (phase-1 review triage)
- CR7: grammar/arity validation MUST run before repository discovery for every command
  (already an acceptance criterion here — a holdout grammar case outside a repo pins it). — **Done**:
  see `Grammar.scala` + `Cli.run`'s new ordering, and `CliSuite`'s three "CR7:" tests.
- CR14: `snap init ""` currently initializes cwd — empty operands are grammar errors. — **Done**: see
  `Grammar.initRule`, `CommandsInit.parsePath`, and `CommandsInitSuite`'s "CR14:" test.

## Integration verification (T13 onto main, post-T14)

Verified 2026-09-04: T13's diff cherry-picked cleanly onto `main` alongside T14's new
`CommandsCommitSuite` test ("a corrupt repository.json blocks commit before touching anything
else"). Checked the one interaction worth checking closely: `commit "should never be authored"`
is exactly one operand, so `Grammar.oneFreeTextOperandRule` (Grammar.scala:87-90) accepts it at
the grammar gate unconditionally — the gate never inspects repository state — and control proceeds
to repo discovery and `CommandsCommit.handler`, which fails at `readRepository` with the
duplicate-JSON-key diagnostic. No regression: the test still fails for the right reason. Confirmed
by `sbt test` (`CommandsCommitSuite`: 22 total, 0 failed) and `./snap/verify --lang scala` (test 15,
"repository reader rejects malformed schemas histories paths and edits", passing). All five gates
green with no code changes required; see the integration report for exact figures.

# Phase 2 review — Diff, revert & validation matrices

**Verdict: approve-with-fixes.** 0 Critical · 0 Major · 1 Minor · 2 Nits.

- **Date / range:** 2026-09-05. Landing shas (chronological, not numeric order — T16's
  phase-3 concurrent-replay engine landed between T11 and T12 per the project's
  "phases pipeline" policy): `20f3896` (T11) → `071639e` (T16, **out of scope**) →
  `13168f9` (T12) → `6daa92d` (T14) → `333f4b1` (T13). Diffed the union via
  `git show` on each of the four in-scope commits plus `git diff 78f961a..333f4b1 --
  snap/scala` for the whole span (78f961a = phase-1 close). T16 (`071639e`) is present
  in the working tree and was read as context where phase-2 code depends on it
  (`Replay.materialize`'s signature), but not reviewed on its own merits — it has its
  own `reviews/T16-review.md`. **T17 (`merge`) is confirmed absent from this worktree**
  (`git show HEAD:snap/scala/src/main/scala/snap/cli/CommandsMerge.scala` → "does not
  exist in HEAD"; `Cli.defaultCommands` still stubs `Command.Merge`; `Errors.scala` has
  no T17 block) — verified directly rather than assumed, after an early tooling mixup
  (see note below).
- **Tasks closed:** T11 (`20f3896`), T12 (`13168f9`), T13 (`333f4b1`), T14 (`6daa92d`) —
  all four show `done` in `tasks/TASKS.md` with matching commit hashes.
- **Suites:** provided **15/28 passing** (01, 02, 03, 04, 05, 06, 07, 08, 14, 15, 19, 23,
  24, 25, 27 — exactly phase 2's own ten tests, 05/06/07/14/15/19/23/24/25/27, plus
  phase 1's five); project **589/589 passing**; both lint gates green.

**Tooling note (process, not a code finding):** this review runs in an isolated
worktree; early in the session I read several files by absolute path without the
worktree prefix and landed on the *shared* checkout instead, which is ahead of this
worktree (it has uncommitted T16/T17-in-progress work, including a `CommandsMerge.scala`
that does not exist here). Caught via `git diff -q` between the two trees before
drawing any conclusions from the affected reads; `Cli.scala` and `Errors.scala` were the
only two files that actually differed, and both were re-read from the correct worktree
path before use. Every finding and every command execution below is against
`/Users/mmrozek/work/AI/.claude/worktrees/agent-aa3ae9bd2ac067ce0` only.

## Verification (re-executed by the reviewer, foreground, from the worktree root)

```
cd snap/scala && sbt -batch clean assembly
  38 Scala sources compiled → snap-assembly-1.0.0.jar built. (No CommandsMerge.scala —
  confirms T17 is genuinely absent, not just undispatched.)

cd snap/scala && sbt -batch test
  Passed: Total 589, Failed 0, Errors 0 → exit 0

cd snap/scala && sbt -batch scalafmtCheckAll
  scalafmt: Checking 46 Scala sources → success

cd snap/scala && sbt -batch "scalafixAll --check"
  Running scalafix on 46 Scala sources → success

PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala
  13 failed, 15 passed in 46007ms → exit 1
  green: 01-init, 02-init-paths, 03-configuration, 04-commit-status-log,
         05-diff-goldens, 06-binary-and-empty, 07-revert, 08-unsupported-entries,
         14-cli-errors, 15-repository-validation, 19-version-boundaries,
         23-strict-validation-matrix, 24-cli-grammar-matrix,
         25-config-version-path-boundaries, 27-history-canonicality
  red:   09,10,11 (merge, T17), 12,13 (HTTP, T19), 16,17,18 (merge, T17), 20 (merge,
         T17), 21 (needs merge for its final assertion), 22 (OT via merge fixture, T17),
         26 (merge step blocks the rest of the case), 28 (terminal presentation, T22)
```

Exactly the expected 15/28, exactly phase 2's own ten tests green, zero regressions in
phase 1's five, and every one of the 13 failures traces to an unimplemented command
(`merge`/`--serve`/terminal mode) rather than a phase-2 defect — confirmed by reading
each failure's diagnostic (`snap: not implemented` in every merge/serve case; the
terminal-presentation failure is a plain `()` where colored output was expected, i.e.
T22 not landed, not a phase-2 regression).

`java -version` on this machine already resolves to 17.0.12 by default, so the
`PATH=...` prefix was a no-op here, but was still applied verbatim per the run
instructions.

Did **not** run `sbt slowTest` (per CLAUDE.md: phase-gate material already verified for
T16, not phase-2's concern, and explicitly excluded from this brief).

## Findings

### Spec compliance

No findings. Verified against SPEC §7 preamble/§7.1–§7.9, §10, and the phase-1
deferrals:

- **CR7 (grammar before repository discovery) — CONFIRMED fixed, structurally, for
  every command.** `Cli.run` (`Cli.scala:130-138`) calls `Grammar.check(cmd, operands)`
  and only *then* branches into `Command.needsRepoDiscovery`/`discoverRepo`. This is
  one code path shared by all ten `Command` cases — not a per-command patch — so the
  fix is total by construction, not by enumeration; the three targeted regression tests
  in `CliSuite.scala:185-216` (status, config, diff) each exercise a different rule
  shape (no-operand, two-shape, diff's own usage channel) as spot checks on top of that
  structural guarantee.
- **CR14 (`snap init ""`) — CONFIRMED fixed**, at two independent layers:
  `Grammar.initRule` (`Grammar.scala:56-59`, gates before discovery/IO) and
  `CommandsInit.parsePath` (`CommandsInit.scala:36-39`, defense-in-depth for direct
  handler callers). `CommandsInitSuite.scala:145-153` asserts both the exact stderr line
  and `!Files.exists(root.resolve(".snap"))`.
- **`Grammar.scala`'s table vs. SPEC §7, exhaustive per-command comparison** (operand
  counts/positions, options, at-most-once, unknown-option handling): every rule matches
  its command's fenced grammar block exactly — `init` (§7.1, 0–1 operand, no options),
  `config` (§7.2, the two `[--global]` shapes), `status`/`log` (§7.3–§7.4, zero
  operands), `commit`/`revert`/`merge` (§7.5/§7.7/§7.8, exactly one operand, no
  options), `diff` (§7.6, the three fenced shapes incl. `--repo`'s fixed position),
  `--serve` (§7.9, 0–1 operand, port value deferred to the handler). `diffRule` and
  `CommandsDiff.handler`'s own match (`CommandsDiff.scala:31-54`) are pattern-identical
  — traced case by case, no drift. Likewise `configRule` vs.
  `CommandsConfig.parseOperands` (`CommandsConfig.scala:33-37`) and `initRule` vs.
  `CommandsInit.parsePath`: all three pairs agree on every input today (see Design
  drift #3 for the maintenance risk this duplication carries).
- **R70/R103/R105–R106 mutation ordering** (`Materialize.scala`, `CommandsRevert.scala`):
  traced and reproduced — see Correctness & determinism below.
- **R87 diff rendering** against the SPEC §7.6 fenced format and both goldens: exact
  match, including the `/dev/null` header substitution, the missing-final-LF marker
  independently on each side, CRLF passed through byte-for-byte
  (`DiffRenderSuite.scala:115-121`), and the D8 binary-line rule for every present-side
  combination (text→binary, binary→text, absent→binary — `DiffRenderSuite.scala:125-140`).

### Correctness & determinism

**#1 [Minor] `CommandsRevert.scala:57-62` — the "defensive full validation" gate never
compares its own result against the tree actually written to the filesystem, so it
cannot catch the one failure mode it is nominally there to prevent.**

```scala
next = Repository(result, CommandsCommit.insertSorted(valid.repository.patches, patch))
_ <- Repo.validateFully(next)                      // independently re-replays `next`
_ <- Materialize.install(root, valid.tree, targetTree)   // installs `targetTree`, computed
                                                          // earlier via a DIFFERENT call
_ <- Store.writeRepository(Commands.repositoryFile(root), next)
```

`targetTree` (installed onto disk) comes from `Replay.materialize(valid.structure,
targetVersion)` (line 46) — the *old* structure, replayed only up to the target
version. `Repo.validateFully(next)` (line 60) independently re-replays the *entire new*
repository (old patches + the new revert patch) through T16's general
concurrent-integration engine, from the empty tree, via `Replay.materialize(structure,
repository.frontier)` (`Repo.scala:74`). Its resulting tree is bound, then **discarded**
— never compared to `targetTree` before `Materialize.install` writes `targetTree` to
disk and `Store.writeRepository` writes `next` to `repository.json`.

CONFIRMED (code trace) that today this is not a live bug: since the revert patch is a
serial append on top of an already-fully-integrated frontier (no concurrency), §6.2
rule 1 applies (`B == C` at integration time), so replaying `next` up to its new
frontier is provably equivalent to applying the revert patch's authored changes
directly to `valid.tree` — which is exactly how `targetTree` was built via
`WorkingChanges.compute(valid.tree, targetTree)` → `buildChanges` → `Patch.make`
round-tripping. `CommandsCommit.scala:52-54`'s own comment for the identical pattern
says exactly this ("Unreachable-failure by construction... the cost is one extra
replay") — but `CommandsRevert.scala:58-59`'s copy of the comment ("mirroring
CommandsCommit") drops that justification, and for revert it isn't quite the same
claim: commit has no separate install step to diverge from, revert does.

Concrete failure scenario (PLAUSIBLE, not reproduced — this is a latent-regression
guard gap, not an active defect): if a future change to `Replay`'s ready-loop,
memoization, or the §6.2 namespace pre-pass ever caused the two replay call shapes to
disagree for some history (e.g. a bug that only manifests when integrating a patch
that is the *sole* member of the frontier's causal frontier vs. one reached via a
longer selection), `Repo.validateFully(next)` would still return `Right` (its own
independently-computed tree is internally consistent, just not equal to `targetTree`),
the working tree would be updated to `targetTree`, and `repository.json` would commit
to a value whose *own* replay produces a *different* tree than what is now on disk —
exactly the R103/R106 "metadata claims a tree that was never written" scenario Focus
Area 4 asks about, and no test in `CommandsRevertSuite`/`MaterializeSuite` would catch
it, since none compares `Repo.validateFully(next).tree` to `targetTree`.

Suggested direction: assert `Repo.validateFully(next).map(_.tree) == Right(targetTree)`
(or restructure so both are derived from one replay call) as a cheap, permanent
invariant check — this is exactly the kind of "byte-identical output under permuted
computation paths" property CLAUDE.md's Testing section already asks for elsewhere.

Nothing else. What I traced or reproduced clean:

- **Mutation ordering (R103/R105–R106), `Materialize.install`:** the four-step order
  (`Materialize.scala:64-73`) — delete current-only paths, prune, ensure parents, write,
  prune again — is followed exactly; a file→directory transition (`node` file → `node/child`)
  and a directory→file transition (`node/child` → `node` file) both collapse correctly
  into "delete then create," confirmed by reading and by `MaterializeSuite.scala:75-95`.
  A write failure mid-loop (`Either`'s `foldLeft`/`flatMap` short-circuit) never reaches
  the second `pruneEmptyDirectories` call or `CommandsRevert`'s subsequent
  `Store.writeRepository` — confirmed by code trace (`Materialize.scala:86-89`'s
  `foldLeft` never invokes `attempt` once `acc` is `Left`) and by
  `CommandsRevertSuite.scala:165-196`'s real filesystem-permission-based write-failure
  test, which asserts `repository.json` is byte-identical to its pre-run bytes and the
  untouched sibling file is unmodified. `repository.json` is written strictly after
  `Materialize.install` returns `Right` in every caller (`CommandsRevert.scala:61-62`) —
  no code path writes metadata first.
- **`.snap` is never touched by install**: `Materialize.pruneEmptyDirectories`
  (`Materialize.scala:139-148`) explicitly filters out `MetadataDirName` from the
  directories it recurses into; `SnapPath.parse` rejects any `.snap`-first-segment path
  by construction, so `deletePaths`/`writePaths` (fed from `Tree.paths`) can never target
  anything under `.snap` either.
- **R25/R70 both transition directions**: traced why one mechanism ("delete every
  current-only path, then prune, then create/write") naturally covers both file→dir and
  dir→file — both trees are prefix-free (R25), so a directory that must become a file
  has already had every tracked descendant removed in step 1, leaving it empty for the
  prune sweep. Confirmed against `MaterializeSuite.scala:75-107` (including the
  deep-nested-ancestor-pruning case) and provided test 07.
- **Diff rendering (T11) vs. SPEC §7.6 and goldens 05/06**, re-derived from the spec
  text before reading `DiffRenderSuite.scala`: path-sorted blocks via
  `WorkingChanges.compute` (Utf8Order sorted-merge, `WorkingChanges.scala:64-84`),
  `/dev/null` substitution (`DiffRender.scala:80-84`), per-side independent
  missing-final-LF marker (`renderLine`, `DiffRender.scala:76-78`) — confirmed on a
  case where *both* sides lack a final LF, each gets its own marker line
  (`DiffRenderSuite.scala:81-95`), and a case where a retained token missing LF renders
  its marker exactly once (`:97-111`). Binary-vs-text is decided by "any present side
  non-text" (D8), confirmed for every combination including one side absent
  (`:137-140`). Path ordering is independent of `Tree`-construction/insertion order
  (`:144-155`, and separately `MaterializeSuite.scala:120-130` for the install side) —
  both are property-style determinism checks, not just golden pins.
- **R86 validate-before-output**: `CommandsDiff.handler`'s `<old> <new>` branch parses
  both version operands, loads+validates the repository, then materializes both
  versions — all inside one `for`-comprehension that `Cli.emit` only observes at the
  end (`CommandsDiff.scala:38-51`), so a later failure can never follow partial stdout.
  `CommandsDiffSuite.scala:151-162` confirms end to end with a corrupt on-disk
  repository.
- **`SnapError` dead-entry sweep (Focus 1b), independently re-run, not copied from
  T14's report.** Extracted the 65 case names actually declared inside `enum
  SnapError:` (`Errors.scala:65-399`) and grepped `SnapError\.<Case>\b` outside
  `Errors.scala`: **65/65 constructed somewhere** (zero dead entries). T14's own report
  says "64" — reconciled: T14 audited *before* T13 landed chronologically (T14's commit
  `6daa92d` predates T13's `333f4b1` even though T13 is numerically earlier), so T14
  never saw T13's later `InvalidPort` addition; 64 was correct at the time T14 wrote it,
  65 is correct now, and the one delta is fully accounted for. **Agreement: CONFIRMED.**
- **Trigger-site audit (Focus 1a), independently re-traced for 6+ pinned diagnostics
  spanning all three named layers, not just message-string grepping:**
  - *JSON layer*: `duplicate JSON key` fires in `AstFacade`/`JsonParser`
    (`JsonParser.scala:30`, `AstFacade.scala:62`) — the pure tokenizer/AST layer, before
    any schema-specific decoding begins.
  - *JSON/schema layer*: `canonical base64` (`RepoCodec.scala:185-196`,
    `decodeCanonicalBase64`) and `path is invalid` (`RepoCodec.scala:142`,
    `SnapPath.parse` inside `decodeChange`) are both pure syntactic decode-time checks —
    neither needs a materialized base tree, confirmed by reading the call sites take
    only the raw JSON string/fields, not a `Tree`.
  - *Repo-structure layer*: `missing a@x` fires in `Repo.checkContiguity`
    (`Repo.scala:134-144`), which is step 2 in `Repo.validate`'s fixed order
    (`Repo.scala:83-95`: sortedAndDots → **contiguity** → increments → baseClosure →
    frontierClosure → reachable → acyclic) — confirming phase-1's PR3/CR8 fix (step 2
    before step 3) is in place and is what makes this trigger-site claim true.
  - *Replay layer*: `tree paths conflict` fires in `Replay.authoredResult`
    (`Replay.scala:153-162`), which builds each patch's *own* authored result tree
    against its materialized exact base and checks prefix-freedom there — this is the
    per-patch replay step (§4.5 step 5), not decode time, since prefix-freedom of the
    *result* requires the actual base+changes tree, not just the raw JSON shape.
  - *Replay layer, structural-vs-content split*: `retain 0`/`adjacent insert`/`must have
    one operation`/`insert is empty` all fire from `EditScript.validate`
    (`EditScript.scala:62-66`), called once at JSON-decode time
    (`RepoCodec.scala:147-153`, `decodeEdit`) — pure structural checks needing no base
    tree — while `does not consume old content`/`consumes beyond old content` fire from
    the *same* `EditScript.run`'s consumption fold (`EditScript.scala:92-106`) but only
    when invoked from `Replay.authoredResult` → `applyChange` → `script.applyTo(oldTokens)`
    with the *actual* materialized base tokens (`Replay.scala:574,582`) — i.e. replay
    time, step 5, exactly where T14's audit places them.
  - **Agreement: CONFIRMED** on every diagnostic re-traced (6, spanning all three named
    layers plus the JSON layer T14's brief didn't separately name). No coincidental-later-check
    trigger site found.
- **Test-suite verification claims**: `CommandsCommitSuite` (22 tests, part of the 589
  green) includes the R103 gap-closing test T14 added; re-read it directly rather than
  trusting the commit message — it uses test 15's exact duplicate-key fixture, asserts
  exit 1 before the working-tree scan runs, and checks two working files plus
  `repository.json` are byte-identical afterward. Matches the claim exactly.
- **Determinism**: no `var`, no raw `try/catch` (Scala-3 brace-less form aside — the
  one designated `Main` boundary), no clock/env/random reads beyond the one designated
  `Env.scala:62` boundary, no `String.compareTo`/unsorted-iteration feeding output
  anywhere in T11–T14's files (see `reviews/phase-2-antipatterns.md` for the full
  four-pattern sweep — 0 FIX, 6 NOFIX, all either pre-existing/phase-1 or T16's).
  `DiffRenderSuite.scala:144-155` and `MaterializeSuite.scala:120-130` both directly
  assert output is independent of `Tree`-construction/insertion order, which is the
  concrete form this project's determinism requirement takes for phase 2's surface.

### Test coverage

No provided-test-pinned behavior in phase 2's scope is left unexercised by the project
suite; every pinned string/shape T14's report claims was already covered was
independently spot-checked present (grammar matrix table-driven in `GrammarSuite.scala`,
diff goldens replicated unit-level in `DiffRenderSuite.scala`, revert's R103 mutation
window using a real filesystem permission failure in `CommandsRevertSuite.scala`). Two
directed-test gaps worth naming (elaborated under Holdout exposure, since neither is a
spec violation on its own):

- No test drives `DiffRender`/`CommandsDiff` through a pure **text** full-file deletion
  (old side present, non-empty text; new side absent) — only a **binary** deletion is
  exercised at that level (`DiffRenderSuite.scala:71-77`, provided test 06). Traced the
  code path by hand and believe it is correct (§5's `D(i, m) = n - i` boundary row is
  independently well-tested in `DiffSuite`), but the specific combination through
  `DiffRender.render` is untested.
- The `Repo.validateFully(next).tree == targetTree` invariant discussed in Correctness
  finding #1 has no test anywhere (neither in `CommandsRevertSuite` nor
  `MaterializeSuite`).

### Design drift

**#2 [Nit] `Grammar.scala:80-90` (`oneFreeTextOperandRule`, shared by `commit`/
`revert`/`merge`) vs. `Grammar.scala:50-59` (`initRule`) — the same underlying R79
question ("is a `--`-shaped single operand an unknown-option grammar error, or the
command's own free-text operand?") is answered two different ways by two rules in the
same table, and only one of the two readings is recorded as an ambiguity decision.**
`initRule` explicitly treats a `--`-prefixed path operand as an unknown-option grammar
error (`path.startsWith("--")` guard, line 58) — driven by test 24's pinned `init,
--unknown` case. `oneFreeTextOperandRule` explicitly does the *opposite* for
`commit`/`revert`/`merge`: a `--`-shaped operand is accepted as the literal
message/version/repository value (`GrammarSuite.scala:78-82` names and tests this
exact case: `"commit: a '--'-shaped message is free text, not an unknown option (commit
has no options)"`). Both readings are individually defensible (see Holdout exposure #1
for the spec-text analysis) and this one is deliberately chosen, reasoned in the
Scaladoc, and covered by a passing test — so this is not a functional defect. What's
missing is the ground-rule-1 paper trail: `tasks/T13-cli-grammar.md`'s "Notes /
decisions" section records the `--serve`-vs-repository-discovery ordering ambiguity
explicitly as "(ambiguity, non-core)" but has no equivalent entry for this one, even
though it is the same class of decision and (per Holdout exposure #1) carries real
holdout risk. Suggested direction: append one line to T13's Notes documenting the
choice and its rationale (which already exists, verbatim, in the Scaladoc) — a
five-minute fix, not a code change.

**#3 [Nit] Three grammar rules are intentionally duplicated, not shared, with the
corresponding handler's own coarse operand check** (`Grammar.diffRule` vs.
`CommandsDiff.handler`'s match, `Grammar.configRule` vs.
`CommandsConfig.parseOperands`, `Grammar.initRule` vs. `CommandsInit.parsePath`) — the
task's own notes call this out as deliberate ("mirrored, not shared... touching
`CommandsDiff.scala` was avoidable and out of this task's scope"). Verified all three
pairs agree on every input today (traced case by case, not just spot-checked) — no
finding for *current* behavior. Flagging only the maintenance shape: since `Grammar`
runs first and any of these three handlers' own duplicate check is now permanently
unreachable through `Cli.run`, a future edit to one side of a pair without the other
would silently and invisibly change which of two *already-passing* code paths decides
the outcome for some input, with no compiler or test signal pointing at the drift
(each side's own unit tests would keep passing in isolation). Not asking for a
refactor now — noting it as a known, accepted shape per the task's own reasoning, worth
one line in a future task's notes if either file is touched again.

### Pitfalls for future phases

Not findings — carried-forward facts the next phases (T17 `merge`, T19 `--serve`, T20/T21
remote diff) should not trip over.

1. **T12 filed a pointer for T13 ("Recorded for T13's attention if it wants one
   consistent wrapper across every version-taking command") that T13 did not pick
   up.** `snap revert <syntactically-invalid-version>` still surfaces
   `Version.parse`'s raw typed-reason message (e.g. `snap: version must be of the form
   () or (id->n,...)`) rather than the `snap: invalid version: <raw>` wrapper `diff`
   (`CommandsDiff.scala:61-62`, `SnapError.InvalidVersionArgument`) and `--serve`
   (`CommandsServe.scala:54-63`, `SnapError.InvalidPort`) both give their malformed
   operands. Not a spec violation — SPEC never pins wording here, and no provided test
   exercises it (confirmed: no test file passes a syntactically-malformed version to
   `revert`) — but it's an unresolved, previously-flagged cross-task action item, not a
   new observation, and a natural pickup for whichever task next touches
   `CommandsRevert.scala`.
2. **The `Repo.validateFully(next)` vs. `targetTree` gap (Correctness finding #1)**
   belongs on T17's radar too: `merge` (T17) will also call `Materialize.install` with a
   separately-computed joined tree (per `Materialize.scala`'s own doc comment, written
   generically for exactly this reuse) and, if it follows `CommandsRevert`'s "defensive
   validate" precedent, would inherit the same non-comparing shape.
3. Reconfirmed **all Prior-review deferrals from phase 1** relevant to phase 2 (CR7,
   CR14) are closed — see Spec compliance above — and no new deferral is being created
   by this review beyond finding #1 above, which is self-contained (fix lives entirely
   in `CommandsRevert.scala`).

## Holdout exposure

Prioritized, concrete, phase-2-surface only (`diff`, `revert`, the grammar table, port
parsing) per CLAUDE.md's standing "the provided suite is a sample" assumption.

1. **(Highest) The `--`-prefixed-single-operand grammar reading for `commit`/`revert`/
   `merge` (and, by the same mechanism, `diff`'s two-operand form and `--serve`'s port
   operand) is genuinely ambiguous against SPEC §7's preamble, and a holdout
   `24-cli-grammar-matrix`-style case could reasonably assert either outcome.** The
   preamble says "Unknown options... are errors" without qualifying that only commands
   which *document* options are subject to it. The current implementation reads it as
   scoped to documented option positions (so `commit --foo`, `revert --unknown`, `merge
   --repo` are accepted as literal operand text, each then failing — or succeeding! —
   on its own terms: `commit --anything` on a dirty tree with valid config
   **actually commits** a patch with the message `--anything`, not an error). A holdout
   case in the same family as `24-cli-grammar-matrix.yaml` that runs `commit --unknown`
   or `merge --bogus` and expects `snap: invalid command or arguments` (the same
   outcome `init --unknown` and `log --unknown` get, per the *other* reading already
   implemented and tested) would fail today. I do not have a way to determine which
   reading the spec authors intended — SPEC-NOTES §2.3 documents that the provided
   suite already asserts several things "beyond the spec text" for grammar, and this is
   exactly that kind of gap. Flagged here rather than as a definite defect because both
   readings are internally consistent and one is already implemented, tested, and
   reasoned (see Design drift #2).
2. **`--serve`'s port-value-vs-repository-discovery ordering is explicitly unverified
   by the provided suite** (T13's own notes: "Both provided tests (14, 24) run `--serve`
   inside an already-valid repo"). A holdout case running `snap --serve badport` in a
   *non-repository* directory would currently get `snap: not a Snap repository` (repo
   discovery runs before `CommandsServe.parsePort` is ever reached), not `snap: invalid
   port: badport`. This is a previously-documented, deliberate D10-analogy choice, not
   a new finding — restated here because Focus Area 7 specifically asks about port
   parsing.
3. **`revert`'s invalid-version-syntax wording** (Pitfall #1 above) is a live holdout
   risk if a test asserts the `invalid version:` class for `revert` the way tests 19/25
   do for `diff`.
4. **A pure-text full-file deletion through `DiffRender`** (Test coverage, above) is
   untested at the integration level; I traced it as correct by hand but a holdout
   golden exercising `diff` after deleting a multi-line text file (no binary content
   anywhere in the case) would be the first real exercise of that exact combination.
5. **The `Repo.validateFully`/`targetTree` non-comparison** (Correctness #1) is not
   something a black-box holdout test could easily target (it requires an actual replay
   divergence to manifest, which today's implementation does not have) — listed here
   only because Focus Area 4 explicitly asks about exactly this class of risk, and the
   guard that exists doesn't fully cover it.

## What I checked and found correct

- **Full independent re-execution** of every command specified in the brief, with exact
  output reproduced above (589/589 tests, both lint gates, 15/28 harness with the exact
  expected set), from the correct isolated worktree (see tooling note).
- **CR7 and CR14** (phase-1 deferred findings): both genuinely fixed, structurally (not
  just for the tested commands), traced through `Cli.run`'s single shared dispatch path
  rather than inferred from passing tests alone.
- **`Grammar.scala`'s table**, compared line by line against SPEC §7's ten fenced
  grammar blocks: no rule accepts something the spec forbids or forbids something the
  spec allows, for any documented shape. The one open question is the undocumented-`--`
  case discussed above, which is a gap in what the spec *specifies*, not a
  contradiction of what it says.
- **Mutation ordering and crash safety** (R103, R105–R106) in both `Materialize.scala`
  and `CommandsRevert.scala`: no mutation precedes a validation failure,
  `repository.json` is written strictly after the working tree, both file→directory and
  directory→file transitions are handled by one uniform mechanism, and a failure
  partway through the write loop leaves exactly the documented partial state (verified
  with a real filesystem-permission-induced failure, not just a mocked one).
- **`diff` rendering fidelity**: goldens 05/06 reproduced exactly by the unit-level
  `DiffRenderSuite`, plus untested-by-the-suite combinations (CRLF verbatim, binary↔text
  transition both directions, absent-side-forces-binary, path-order independent of tree
  construction order) all directly tested and traced correct.
- **T14's two negative audit claims**: independently re-verified both. Dead-entry sweep:
  65/65 current `SnapError` cases constructed outside `Errors.scala` (T14's "64" was
  correct at the time it was written, before T13's later `InvalidPort` addition — fully
  reconciled, not a discrepancy). Trigger-site audit: re-traced 6 pinned diagnostics
  spanning the JSON, repository-structure, and replay layers named in the brief, plus
  the JSON-schema-decode layer; every one fires from the spec-correct step. **Agreement
  with T14 on both claims: CONFIRMED, not just PLAUSIBLE.**
- **Antipattern sweep** (`reviews/phase-2-antipatterns.md`): 0 FIX / 6 NOFIX, zero new
  hits anywhere in phase 2's own new/changed files across all four patterns.

## Triage (orchestrator)

Verdict accepted: **approve-with-fixes**. Two fixes accepted and applied before the phase
closes; one finding accepted as a documented shape; the holdout-exposure items routed to
the tasks that own them. The reviewer re-executed every gate itself (589/589, both lint
gates, harness 15/28 with zero regression) and independently re-derived T14's two audit
claims, which was the main thing I wanted checked — both CONFIRMED, and it reconciled the
`SnapError` count discrepancy (T14's "64" was correct when written; T13 later added one,
making it 65).

| # | Severity | Decision | Action |
|---|---|---|---|
| 1 | Minor | **accepted — fixed** | `CommandsRevert`'s defensive `Repo.validateFully(next)` discarded its own resulting tree instead of comparing it against the `targetTree` actually installed, so the guard could not catch the divergence it exists to detect. Confirmed not a live bug (a serial append on a fully-integrated frontier hits §6.2 rule 1, making the two provably equal), but an unenforced invariant on the mutation path is exactly what a guard is for. Now compared, with a violation routed to the internal-error channel (exit 2, R107). `CommandsCommit`'s identical pattern checked alongside. |
| 2 | Nit → **upgraded to a behavior fix** | **accepted — fixed** | The reviewer filed this as a missing paper trail: `initRule` treats a `--`-prefixed operand as an unknown option while `oneFreeTextOperandRule` accepts it as literal text, and only one reading was logged. Investigating it changed the verdict — the asymmetry is a **defect**, not just an undocumented choice. SPEC §7's preamble is unqualified for all commands ("Unknown options, extra operands, and missing option values are errors"), and test 24 pins `init --unknown` → error *and* additionally asserts `path_not_exists: --unknown`, i.e. the contract requires that a `--`-shaped token is never consumed as free-form operand text. No provided test uses a `--`-prefixed free-text operand for `commit`/`revert`/`merge`, so the strict reading costs nothing against the suite. Unified across `commit`, `revert`, `merge` and `--serve`'s port operand; `diff` keeps its usage channel. Recorded as **D28**. Accepted consequence: a message or path legitimately beginning with `--` is unreachable from the CLI, exactly as the contract already accepts for `init`. |
| 3 | Nit | **accepted, no action** | Three grammar rules deliberately mirror rather than share the handler's own coarse check, and the reviewer traced all three pairs as agreeing on every input today. The concern is drift: a handler-side check is now unreachable through `Cli.run`, so a one-sided future edit would silently move which path decides, with no test signal. Consolidation pointer added to `tasks/T23-holdout-hardening.md`, which already owns the analogous byte-helper consolidation. |

Holdout-exposure items (the report's most valuable section), routed:

- **#1** (`--`-prefixed operands) — resolved by fix 2 above; no longer an exposure.
- **#2** (`snap --serve badport` in a non-repository directory reports `not a Snap
  repository` rather than `invalid port`, because discovery precedes the port *value*
  check) — deferred to **T19's review**. Port-value parsing is pure argument validation,
  and phase-1's CR7 established that argument validation precedes filesystem IO, so the
  current ordering is arguably inconsistent with that principle. T19 owns `--serve` and is
  in flight; its reviewer rules.
- **#3** (`revert`'s invalid-version-syntax wording vs the `invalid version:` class that
  tests 19/25 pin for `diff`) and **#4** (a pure-text full-file deletion through
  `DiffRender`, untested at integration level) — both deferred to
  **`tasks/T23-holdout-hardening.md`**.
- **#5** is closed by fix 1.


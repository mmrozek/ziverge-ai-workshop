# T23 — Holdout-gap hardening & final pass (2 SP)

- **Phase:** 5 — Terminal presentation & holdout hardening
- **Depends on:** T18, T21, T22
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Close every SPEC-NOTES §2.1 gap that earlier tasks did not explicitly own, so the
holdout assumption holds (CLAUDE.md: implement the full spec text, not the sample):
254-byte contributor-id limit and control chars in ids (R28/D12); 4096-byte commit
message limit at the exact boundary, in bytes not chars (R48/D16); tracked-path
control-char/backslash/`.`/`..` rejections inside patches (R23); commit-time revision
overflow and dot collision (R85); exit-code-2 discipline (R107 — a forced internal
error exits 2, never 1); serial-contributor commit guard (R37). Then the final
full-suite + lint pass before the post-completion audit.

## Scope
Small targeted additions across `snap/scala/src/main/scala/snap/{core,cli}/`, unit
tests in `snap/scala/src/test/scala/`. No new modules.

## Acceptance criteria
- [x] Unit tests at each boundary: 254 vs 255-byte id; 4096 vs 4097-byte message
      (multibyte char straddling the limit); `9007199254740991` revision commit
      attempt → overflow error; id containing 0x7F rejected (D12).
- [x] Exit-code discipline: an injected unexpected exception produces exit 2 and a
      `snap: `-prefixed stderr line; no expected-error path exits 2 (grep-level audit
      recorded in notes).
- [x] Provided suite at this task's base (27/28 — see notes on the 28th), lint gate,
      and project test suite (`sbt test`) all green.
- [x] No suppressed scalafix rules without a justifying comment; determinism smells
      audit (`scala-antipatterns` skill) clean before the phase-5 review.

## Notes / decisions

**Base state / scope note:** at this task's base commit, `28-terminal-presentation` was
already the only failing provided test (T22's terminal renderer — `CommandOutput`/
`Presenters`/`Terminal` — was not yet in this worktree; T21's cross-repo hardening was
also not yet in this worktree). This task's own acceptance criterion literally reads
"28/28", but T22 landing is outside T23's scope and a dependency this task cannot
satisfy by itself — verified 27/28 throughout (no regression), with the 28th expected to
turn green once T22 integrates, per the orchestrator's own framing of this task.

**1. Boundary tests (R28/D12, R48/D16, R23, R85) — already present, verified, not
duplicated.** All were already implemented and unit-tested by earlier tasks (T03's
`ContributorIdSuite` — "byte-length boundary: 254 accepted, 255 rejected", DEL 0x7F
rejection; T14's `CommandsCommitSuite` — "message of exactly 4096 UTF-8 bytes is
accepted; 4097 is rejected", the é-straddling-the-boundary case, and "a frontier already
at 2^53−1 overflows" using `Revision.Max = 9007199254740991L`). Verified by reading the
actual source (`Ids.scala`, `Patch.scala`, `CommandsCommit.scala`) and confirmed all
pass. The one genuine gap found: R23 control characters specifically *inside a patch's
path* (as opposed to `SnapPath.parse` tested directly) were never driven through the
JSON decode path — `RepoCodecSuite`'s "every decoded path goes through SnapPath.parse"
test covered `.snap/secret`, empty, `a//b`, `../up`, `a\b`, but no raw control byte.
Added a `\t`-inside-path case (JSON's own two-character `\t` escape, not a `\u` escape —
Scala's lexer rewrites `\u` sequences to actual code points even inside triple-quoted
strings at compile time, which would have produced literal control bytes in the JSON
text and made it invalid JSON rather than reaching path validation).

**2. Exit-code discipline (R107) — grep-level audit.** `grep -rn 'throw \|scalafix:ok\|System\.exit'
snap/scala/src/main/scala`: exactly two `System.exit` call sites (`Main.scala:35`, the
sole `run`→`System.exit` wiring point; `http/Server.scala:99`, the SIGINT/SIGTERM
handler mandated by R90/D21) and, after this task's own addition (item 6 below), exactly
two `throw` sites, each with its own `scalafix:ok DisableSyntax.throw` suppression and
justifying doc comment (`CommandsRevert.requireReplayMatchesInstalled`,
`CommandsCommit.requireChangesReproduceWorking`) — both route to `Main`'s ONE top-level
catch-all (exit 2), never a normal `Left(SnapError...)` (exit 1). No other exit-adjacent
construct exists in `src/main`. `MainSuite.scala` already had (and still has) both
directions covered: an expected `SnapError` → exit 1 with `snap: not implemented`, and
an injected unanticipated exception → exit 2 with `snap: internal error: ...` — no
expected-error path was found to exit 2 anywhere in the codebase.

**3. Serial-contributor commit guard (R37).** Already fully guarded structurally:
`CommandsCommit.nextRevision` computes `frontier(author) + 1` and nothing else, making a
skipped or repeated revision unrepresentable by construction; `checkNoCollision` is a
defensive backstop; `Repo.validateFully(next)` re-validates the whole would-be
repository including R44's contiguous-per-contributor-revision invariant. R37's other
half (one ID authoring concurrently in disconnected copies) can only be discovered at
`merge` time (R38/R47, already covered by test 16 and `CommandsMerge.unionPatches`) — a
single local `commit` cannot violate it against itself. Added explicit R37 citations to
both functions' doc comments and one integration test (three sequential commits by one
author produce exactly the gapless run 1, 2, 3) so the guard reads as intentional rather
than incidental.

**4. `scalafix:ok` suppression audit.** Confirmed exactly one suppression existed
pre-task (`CommandsRevert.scala`, justified in its doc comment per scalafix's own
"no trailing prose on the suppression line" constraint) and no others had crept in. This
task's item 6 (below) adds a second, symmetric one in `CommandsCommit.scala`, justified
the same way. Both confirmed present and read as intended after the final
`scalafixAll --check` pass.

**5. Θ(n²) replay (phase-1 review PR5/CR1) — re-measured, then fixed.** Re-measured
against the current (T16-era) engine with a throwaway probe (a single-author linear
history, `Replay.materialize` timed directly, removed before this pass's final commit):
800 patches took ~7.5s (vs. the review's original ~9.3s — same order of magnitude, T16
did not incidentally fix this). Root cause confirmed exactly as the review described:
`Replay.loop`'s outer ready-loop computes `nextCanonical` at every step but never stored
it in the memo under its own resulting version — only `materializeMemo`'s cache-MISS
branch populated the memo, so a later patch's base (in a linear history, exactly the
prior step's progress) was always a miss, re-walking the entire prefix from scratch
(Σ(k−1) ≈ n²/2 integrations). Fix: `memo1.updated(newProgress, nextCanonical)` added to
the outer loop's own recursive call — one `Map.updated` per integration step, correct by
R12/R13/R76 (`materialize(V)` is a pure function of `V` and the patch set alone, so
caching a tree computed by the outer loop is exactly as valid as caching one computed by
a sub-replay). Re-measured post-fix: 800 patches → ~50-100ms (roughly two orders of
magnitude faster); 6400 patches → ~1.4s (confirms near-linear, not quadratic, scaling).
Added one permanent regression test to `ReplaySuite.scala` (800-patch linear history,
generous 5s ceiling — the pre-fix time at this exact size was ~7.5s, so this catches a
full regression without being sensitive to ordinary machine variance at the post-fix
scale) plus a correctness assertion (right tree, right size, no warnings). Did NOT touch
`ReplayStackSafetySlowSuite` (out of scope, per the orchestrator's explicit instruction
not to run `sbt slowTest`) — that suite's own doc comment referencing "Θ(n²) ... an
accepted, T23-deferred trade-off" is now stale and should be updated by whoever next
touches that file, but editing a file I was told not to exercise felt like the wrong
place to make an unverified doc-only edit under time pressure; flagging here instead.

**6. CR12b — byte-equality/hash consolidation.** New `snap.core.ByteArrays` object
(`equal`/`hash`/`equalOption`/`hashOption`) is now the one canonical implementation;
`Tree.scala`, `Patch.scala` (`Change.Put`), `Replay.scala` (`applyChange`/`sameEntry`),
and `cli/WorkingChanges.scala` (`Delta`) all delegate to it instead of each carrying its
own hand-copied `bytesEqual`/`bytesHash` pair. Removed now-unused `tailrec` imports in
`Tree.scala`/`Patch.scala`.

**7. Finding 3 — Grammar-vs-handler duplicate checks.** Consolidated all three pairs to
one source of truth per command: `Grammar.initRule`/`configRule` now call
`CommandsInit.parsePath`/`CommandsConfig.parseOperands` directly (both promoted from
`private` to `private[cli]`); `diff`'s case was less direct since its handler needs the
*matched* operand text, not just a yes/no — extracted a `CommandsDiff.Shape` enum
(`NoArgs`/`TwoVersions`/`CrossRepo`) and a `parseShape` function as the ONE place the
three-way pattern match is written, used by both `Grammar.diffRule` (mapped to `Unit`)
and `handler` (matched on the specific case). No behavior change; `sbt test` (660/660)
and the full provided suite (27/28, same 27) confirm it.

**8. New finding — `CommandsCommit`'s discarded `Repo.validateFully` result.** Chose to
add the comparison (mirroring `CommandsRevert.requireReplayMatchesInstalled`) rather
than only document why it cannot diverge, for symmetry with revert and because the cost
is one field access, not a new replay (the replay already happens for `validateFully`
itself). Added `CommandsCommit.requireChangesReproduceWorking(replayedTree, working)`:
if `Repo.validateFully(next)`'s own replayed tree ever differs from `working` (the tree
`WorkTree.scan` actually read off disk), raises via the same sanctioned exit-2 route
`CommandsRevert` established — never a normal `Left(SnapError...)`, since a real
divergence would be a bug in our own diff/edit-script logic, not a user-triggerable
condition. Confirmed unreachable today by the same argument as revert's (a `commit`
patch is a single serial append with no concurrency, so `buildChanges`'s diff-derived
changes applied via the general replay engine reproduce `working` by construction) —
mirrored the four `requireReplayMatchesInstalled` unit tests (equal, structurally-equal-
but-independent, mismatch-throws, target-only-path-throws) for the new function.

**9. Holdout exposure 3 — `revert`'s invalid-version wording aligned with `diff`'s.**
`CommandsRevert.handler` now calls `CommandsDiff.parseVersionArg` (promoted to
`private[cli]`) instead of propagating `Version.parse`'s own typed `VersionError`
wording directly — both commands now render a syntactically invalid version operand as
`snap: invalid version: <raw>`, the same class tests 19/25 pin for `diff`. No provided
test pinned the old wording for `revert` (only `UnknownVersion`'s "unknown version" class
is tested there, a different case — a syntactically *valid* but not-yet-materializable
version), so this was safe to change; verified with two new `CommandsRevertSuite` tests
(a garbage string, and a leading-zero revision).

**10. Holdout exposure 4 — pure-text full-file deletion golden.** Added to
`DiffRenderSuite.scala`: a multi-line text file (`"a\nb\nc\n"`) deleted with no binary
content anywhere in the case, asserting the exact `--- a/f` / `+++ /dev/null` /
`@@ -1,3 +1,0 @@` / three bulk `-` lines rendering.

**Scope deviations:** none beyond what's recorded above (the `ByteArrays` consolidation
touches `Tree.scala`/`Patch.scala`/`Replay.scala`/`WorkingChanges.scala`, and the
Grammar consolidation touches `CommandsInit.scala`/`CommandsConfig.scala`/
`CommandsDiff.scala`, all cited in the task's own pre-implementation pointers as
in-scope). No `Errors.scala` changes were needed — every wording used already existed
in the catalog (`InvalidVersionArgument`, `InvalidCommitMessage`, `PatchCollision`,
`RevisionNotSafeInteger`), so the "delimited `// T23 additions` block" guidance for
`Errors.scala` (given to avoid colliding with T21's concurrent work there) did not
apply — no edits were made to that file at all.

**Concurrency note (T21/T22):** confirmed via `diff -rq` against the shared checkout
that this worktree's base genuinely lacks T21 and T22 (both still show as separate,
un-integrated work — `Cli.scala`'s `CommandHandler` is still `Either[SnapError, String]`,
`Presentation.scala` has only `Plain`, no `CrossRepoSafetySuite`/`PresentationSuite`
exist here). All new/edited tests in this task drive commands through `Cli.run`/
`TestEnv`/`Main` rather than constructing bare `CommandHandler` values, per the
orchestrator's instruction, so they should integrate cleanly once T22 lands. Files
touched that T22 also touches: `Grammar.scala`, `CommandsCommit.scala`,
`CommandsConfig.scala`, `CommandsDiff.scala`, `CommandsInit.scala`, `CommandsRevert.scala`
— all edits are to logic paths (operand parsing, the defensive gate, wording) untouched
by T22's own diff (T22 only widens the handlers' success type from `String` to
`CommandOutput`, per its own commit's description), so a merge should be mechanical.
`Main.scala`/`Cli.scala` themselves were NOT edited by this task.

## Pre-implementation pointers (phase-1 review triage)
- PR5/CR1: Θ(n²) replay (measured 800 patches → 9.3 s for one status) — optimize only
  against a measured suite timeout; re-measure after T16's engine landed.
- CR12b: consolidate the four hand-copied byte-equality/hash helpers into one
  `snap.core` utility (Replay/WorkingChanges/Tree/Patch).

## Pre-implementation pointers (phase-2 review triage)
- Finding 3: `Grammar.diffRule`/`configRule`/`initRule` each mirror, rather than share,
  the corresponding handler's own coarse operand check (`CommandsDiff.handler`'s match,
  `CommandsConfig.parseOperands`, `CommandsInit.parsePath`). All three pairs agree on
  every input today (reviewer traced them case by case), but the handler-side checks are
  now unreachable through `Cli.run`, so a one-sided edit would silently change which path
  decides an outcome with no compiler or test signal. Consolidate to one source of truth
  per command, or leave the handler check and delete the duplicate — either way, one
  place.
- New, found while applying the phase-2 fixes (same class as finding 1, different second
  source of truth): `CommandsCommit`'s defensive `Repo.validateFully(next)` result is also
  discarded without comparison. Commit installs nothing, so there is no installed tree to
  compare against — but the natural target is `working`, the tree `WorkTree.scan` read
  from disk. If `buildChanges`/`Diff`/`EditScript` ever failed to reproduce the working
  tree exactly, `repository.json` would describe a current tree that does not match what
  is on disk and commit would never notice. Decide whether to add the comparison (mirror
  `CommandsRevert.requireReplayMatchesInstalled`) or to document why it cannot diverge.
- The repo's **first** `scalafix:ok` suppression now exists, in
  `CommandsRevert.scala` (`DisableSyntax.throw`, for the deliberate exit-2 internal-error
  raise). Its justification lives in the doc comment above the function because scalafix
  rejects trailing prose on the suppression line. T23's acceptance criterion "no
  suppressed scalafix rules without a justifying comment" should confirm this one reads
  as intended, and that no others have crept in.
- Holdout exposure 3: `revert`'s invalid-version-syntax wording. Tests 19/25 pin the
  `invalid version:` class for `diff`'s operands; a holdout asserting the same class for
  `revert` would currently find different wording. Align them unless the spec
  distinguishes.
- Holdout exposure 4: a **pure-text** full-file deletion through `DiffRender` (a
  multi-line text file deleted, with no binary content anywhere in the case) is untested
  at the integration level — traced correct by hand, never exercised. Add the golden.

**Integration verification (post-hoc, onto `main` without T21/T22 as T23's own base — the
one pre-resolved conflict in `CommandsDiff.scala` kept both T23's `Shape`/`parseShape`
dispatch and T22's `CommandOutput` wrapping per branch, dropping the now-redundant
trailing `case _ => Left(SnapError.DiffUsage)` since `parseShape` owns that).** Verified:
`Grammar.diffRule`/`initRule`/`configRule` now literally call
`CommandsDiff.parseShape`/`CommandsInit.parsePath`/`CommandsConfig.parseOperands`, so the
two sides cannot diverge by construction; an unrecognized diff shape still renders `usage:
snap diff …` (via `SnapError.DiffUsage`, propagated through `parseShape`'s `Left`, never
falling through to the generic invalid-command wording). Traced the `Replay.loop` memo
fix line by line: `newProgress` at each step exactly captures the causal closure
integrated so far (a pre-existing invariant the loop's own doc already relies on for
readiness), `select(valid, newProgress)` therefore selects exactly that same integrated
subset (never more, since `newProgress <= version_top` componentwise), and the ready-loop
is a deterministic function of a selection alone (R76) — so `nextCanonical` stored under
`newProgress` is provably identical to what a fresh `materializeMemo` sub-replay of that
same version would produce, recursively, for every invocation of `loop` (top-level or
nested). No corruption risk found. Confirmed all four `ByteArrays` call sites
(`Tree.equals`/`hashCode`, `Change.Put.equals`/`hashCode`, `Replay.applyChange`/
`sameEntry`, `WorkingChanges.Delta`) delegate with unchanged semantics (`grep -rn
"bytesEqual\|bytesHash"` outside `ByteArrays.scala` returns nothing) and hashing is
byte-for-byte the same recurrence (`31*acc+byte`, `None → 0`). Gates run from
`/Users/mmrozek/work/AI`: (1) `sbt -batch clean assembly` clean; (2) `sbt -batch test` —
693 total, 0 failed (main was 684 pre-integration); (3) `scalafmtCheckAll` clean; (4)
`scalafixAll --check` clean; (5) `PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH"
./snap/verify --lang scala` — **28/28** passed in 72.3s; (6) `sbt -batch slowTest` —
`ReplayStackSafetySlowSuite` 2/2 passed in 0.6s (previously bounded specifically because
of the pre-fix Θ(n²) cost — now fast for the same reason the 800-patch regression test
is fast). No untracked files under `snap/scala`; no stray probe/scratch content found in
the staged diff (`grep` for TODO/FIXME/probe/scratch/println across the diff: only
prose references inside doc comments, no actual debug code).

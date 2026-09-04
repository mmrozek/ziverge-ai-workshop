# T17 pre-commit review — `merge` command (SPEC §7.8)

**Verdict: approve**

0 Critical, 0 Major, 2 Minor, 1 Nit. I traced the full composition (D11 precedence, union/
dedupe, the two-replay warning subtraction, install/write ordering, the no-op path, and
direction independence) against SPEC §3.5, §6.2–§6.5, §7.8, §10 line by line, and I did not
find a spec divergence. I additionally reproduced R76 (direction independence) empirically
for the four conflict reasons the committed suites never exercise bidirectionally
(delete-wins, put-wins, later-put-wins, namespace-wins), beyond OT and later-create-wins
which the suites already cover — see Verification. The two Minor findings are coverage
gaps in the regression net for properties I independently confirmed hold; the Nit is an
architectural observation with no behavioral effect. None block the commit.

- **Date / scope:** 2026-09-05 (session date rolled over from 2026-09-04 mid-review),
  uncommitted working tree on `main` (`git diff HEAD -- snap/scala tasks`, staged +
  unstaged together, base includes T12/T13/T14/T16 already merged to `main`):
  `snap/cli/CommandsMerge.scala` (new, 139 lines), `snap/cli/CommandsMergeSuite.scala`
  (new, 331 lines), `snap/cli/Cli.scala` (`Command.Merge` wired to the real handler),
  `snap/cli/Presentation.scala` (`warning` added to the trait + `Plain`),
  `snap/core/Errors.scala` (`Messages.autoResolved`, additive), `snap/cli/CliSuite.scala`
  (stub-list shrink), `tasks/T17-merge-command.md`.
- **Reviewed against:** SPEC §3.5, §4.1–§4.5, §6.1–§6.5, §7.8, §10; `docs/plan/SPEC-NOTES.md`
  R5, R14, R38, R75–R76, R78, R89, R103, R105–R106; `docs/plan/DESIGN.md` §5 step 4, §7, §8,
  §9 (D11, D27 fidelity only); `docs/SCALA-CONVENTIONS.md`; `reviews/T16-review.md`
  (ruling 5, sub-replay warning discarding); `tasks/T17-merge-command.md` (acceptance
  criteria + Notes); the underlying, already-reviewed engine (`snap.core.Replay`/`Repo`,
  T16) and materializer (`snap.fs.Materialize`/`Store`, T12), read but not re-litigated.

## Rulings on the required review points

**1. Failure precedence (D11) end to end — CONFIRMED faithful.**
`CommandsMerge.handler` (`CommandsMerge.scala:52–82`) is one `for`-comprehension whose
generator order is exactly: `parseOperand` (coarse arity, `:87–89`) → `requireRoot` → local
`Commands.readRepository(root)` (`:55`, local parse+validate, this call's own
`Repo.validateFully` gives the pre-merge local replay) → `WorkTree.scan(root)` (`:56`,
fails on an unsupported entry before anything else — `WorkTree.scan` enforces this
internally, `WorkTree.scala:23–27`) → `requireClean` (`:57`, dirty check, reusing
`WorkingChanges.compute`, the identical predicate `status`/`commit`/`revert` use) →
`resolveOperand` (`:58`, pure string classification — `http(s)://` vs. local path, no I/O) →
`Store.readRepository` on the remote (`:59–61`, remote load+validate) → `unionPatches`
(`:62–65`, dot cross-check) → `Repo.validateFully(merged)` (`:70`, the one joined replay) →
`Materialize.install` (`:71`) → `Store.writeRepository` (`:72–75`). This is a single
`flatMap` chain (`Either`'s `for`), so a failure at any link returns immediately with zero
prior mutation (R103) — I confirmed this is not merely structural but observably true (see
Verification: the "clean tree with an unreadable remote" and "dot collision" cases both
assert `repository.json` bytes are unchanged after failure).

Cli-level ordering above the handler (`Cli.scala:119–139`) additionally puts `SNAP_COLOR`
validation, command-line parsing, and `Grammar.check` (T13's exhaustive per-command arity
matrix) **before** repository discovery, which itself precedes `CommandsMerge.handler` —
so a grammar violation (wrong operand count) never reaches the local-load step at all; I
confirmed `Grammar.rules(Command.Merge) = oneFreeTextOperandRule` (`Grammar.scala:87–90,
122`) makes `CommandsMerge.parseOperand`'s own failure branch unreachable from `Cli.run` in
practice (both zero- and two-operand `CommandsMergeSuite` cases are actually satisfied by
`Grammar.check`, not by `CommandsMerge.parseOperand` — the task's own Notes record this
explicitly and I verified it by tracing `Cli.run`'s dispatch order, not by trusting the
comment).

Each adjacent pair is either the locked D11 order (already approved during planning, not
reopened here) or trivially forced (`WorkTree.scan` must succeed to produce a `Tree` before
`requireClean` can compare it; `resolveOperand` must succeed before a path exists to read).
The two acceptance-pinned observables hold exactly as specified: a dirty tree beats a
nonexistent remote (`CommandsMergeSuite.scala:225–237`, reproduced independently — see
Verification) and an `http://` operand yields `NotImplemented` only at the remote-load
position, i.e. strictly after the dirty check (`:262–278`, reproduced). I found no ordering
the spec text itself fixes differently — §7.8 and §10 describe the pipeline at a coarser
grain ("loads and validates," "before writing") than D11's specific interleaving, so D11 is
a defensible refinement, consistently applied.

**2. Union and per-dot dedupe — CONFIRMED genuine structural equality, canonical
direction-independent order, deterministic collision reporting.**
`unionPatches` (`CommandsMerge.scala:120–138`) is a linear sorted-merge over two vectors
that are *already* guaranteed canonically sorted, one-value-per-dot by construction (both
operands are `Repo.Valid.repository.patches`, which only exist after `Repo.validate`'s step
2 — `Repo.scala:97–116`). At a shared dot it compares with `a == b` where `a, b: Patch`
(`:136`); `Patch` is a case class over `(author, revision, base: Version, message,
changes: Vector[Change])`, and `Change.Put` overrides `equals`/`hashCode` to compare byte
content rather than array identity (`Patch.scala:29–34`) — so `==` here is genuine
structural/byte equality per R47, not a hash or a field subset. I confirmed this by reading
`Patch`'s and `Change`'s definitions directly, not by trusting the doc comment.

**Direction independence of the union's *value*, not just its behavior, is provable from
the algorithm's shape, and I verified it two ways.** Algebraically: the merge-join always
advances through both vectors in ascending dot order and, at any given dot, emits either
the sole side's value or (when both sides hold it) a value structurally equal on both sides
— so `unionPatches(A, B)` and `unionPatches(B, A)` walk the same underlying set of dots in
the same order and can only differ in *which* structurally-equal `Patch` reference is
picked at a shared dot, never in the resulting `Vector[Patch]` value (case-class `==`
composed transitively down to byte-safe `Change.Put` equality makes "structurally equal"
exact serialized-byte equal). Empirically:
`CommandsMergeSuite.scala:317–330` pins `unionPatches(l, r) == unionPatches(r, l)` directly
on a real fixture, plus idempotence (`unionPatches(l, l) == Right(l)`) and one
associativity check. This only exercises a single non-colliding-shared-dot fixture (the
one shared seed patch); I additionally traced the *collision* path by hand: the scan
reports the error at the **first mismatching dot in ascending dot order**, and that
position is a function of the two sorted vectors' *content* alone, not of which vector the
caller names "left" — so for two colliding dots, both `unionPatches(A,B)` and
`unionPatches(B,A)` must report the smaller one. The provided/committed test for collision
reporting (`CommandsMergeSuite.scala:288–313`) only has one colliding dot in the fixture,
so this multi-dot claim is traced, not test-pinned — see Finding #2.

**3. Exactly three materializations, two of which feed R75; the third (remote) is
REQUIRED, not redundant, and validating it CAN change the outcome.**
The two operands of R75's subtraction are exactly right: `local.warnings` is the `warnings`
field of the `Repo.Valid` obtained from `Commands.readRepository(root)` **before** the
remote is even read (`:55`, `:79`), and `mergedValid.warnings` is the `warnings` of
`Repo.validateFully(merged)` (`:70`, `:79`) — the pre-merge local set and the joined set,
exactly as R75 specifies, no recomputation. The difference (`:79`,
`mergedValid.warnings -- local.warnings`) is a genuine `SortedSet[Warning]` set difference,
ordered by `Warning.ordering` (already CONFIRMED `Utf8Order`-based in T16's review), and the
`SortedSet` is iterated directly via `.foreach` (`:80`) with no re-sort.

The **third** materialization (`Store.readRepository` on the remote, which independently
runs the remote's own full §4.5 steps 1–6, then discards its tree/warnings and keeps only
`remote.repository.{frontier,patches}`) is **required by R89's text** ("Loads and
validates the other repository" — §4.5 defines "validate" as the full six-step pipeline
including replay) and, independently, **required for correctness of `unionPatches` itself**:
the merge-join algorithm's invariant ("both inputs are canonically sorted, one value per
dot") is only true because `Store.readRepository` ran `Repo.validate`'s step 2 on the
remote first. Skipping remote validation would not just under-report a spec-mandated
diagnostic (test 26's "malformed remote never mutates" case) — it would make
`unionPatches`'s sorted-merge-walk silently wrong (comparing dots out of order, or admitting
duplicate/unsorted dots) if the remote's raw JSON patches happened not to already be
canonically sorted and deduped. So validating the remote **can** change the outcome from
"silently corrupt union" to "correctly rejected malformed remote" — this is not
redundant work, it is load-bearing for both spec compliance and the union algorithm's own
correctness precondition. I confirmed the mechanism (not just the claim) by reading
`Store.readRepository` (`Store.scala:53–63`) and `Repo.validate`'s step-2 check
(`Repo.scala:97–116`).

**4. Warnings print only after the write succeeds — SPEC-FORCED, not merely defensible,
and correctly implemented.**
The warning-printing side effect (`:79–80`) lives inside the `for`-comprehension's `yield`
block, which in Scala's `Either`-monad desugaring only evaluates after every prior
generator — including `_ <- Store.writeRepository(...)` (`:72–75`) — has produced a `Right`.
I confirmed this is not just structurally true but the literal reading of §7.8's own
sentence order ("Canonically replays, installs the result, and updates `repository.json`
... Prints new warnings ... and the joined version") and consistent with R106: if the
metadata write fails after a successful install, the user sees only the write's error (no
warnings), and the working tree has already been updated to the merged content while
`repository.json` still names the old, pre-merge frontier — this is *exactly* R106's
sanctioned window ("An I/O failure ... during a multi-file update may leave a dirty,
partially updated working tree with the old `repository.json`. Snap reports the failure;
the user may repair the files and retry"), not a merge-specific gap. I verified no code
path prints a warning before `Store.writeRepository` succeeds by reading the
`for`-comprehension's generator order directly (no branch bypasses it).

**5. Install order and crash safety (R105–R106) — CONFIRMED.**
`Materialize.install(root, local.tree, mergedValid.tree)` (`:71`) precedes
`Store.writeRepository` (`:72–75`) unconditionally — matching R105's "update working files
first" and mirroring `revert`'s own order (confirmed by reading `Materialize.scala`'s own
doc comment, which states this explicitly and is enforced by every caller, not just
`merge`). Merge creates no `Patch` value anywhere in `CommandsMerge.scala` (grep-confirmed:
no `Patch.make`/`Patch(` construction in the file) and increments no revision — the merged
repository's frontier is `local.repository.frontier.join(remote.repository.frontier)`
(`:66`), and `Version.join` is componentwise `max` (`Version.scala:63–79`), which by
construction can never be smaller than either input in any component — so the frontier
can never move backward. A crash between `install` and `writeRepository` leaves
`repository.json` still describing the OLD (pre-merge) frontier while the working tree now
holds the MERGED bytes; a subsequent `status` would replay the old, still-valid
`repository.json` (current tree = pre-merge local tree) and compare it against the
now-merged on-disk bytes, correctly reporting every path the merge touched as a dirty
delta (A/M/D per §7.3) — a legitimate "dirty tree, please repair or retry" state exactly as
R106 anticipates, not a corruption. I did not fault-inject this (no source changes), but
traced it from `Store`'s and `Materialize`'s own code (same-directory temp file + only-
after-install write order) plus `CommandsStatus`'s use of the same `WorkingChanges`
predicate `merge` uses for its own dirty check.

**6. The no-op path — CONFIRMED genuinely branch-free and crash-safe.**
For equal/contained history, `unionPatches` collapses to the local vector by construction
(every dot on both sides is `==`), `join` collapses to the local frontier (`max(x,x)=x`),
and `Materialize.install(root, current, target)` with `current == target` computes empty
`removed`/`written` vectors (`Materialize.scala:65–66`) — the only filesystem operations
that still run are two `pruneEmptyDirectories` passes, which the class doc explicitly notes
are idempotent no-ops when nothing changed. `Store.writeRepository` (`Store.scala:66–67`,
`74–99`) has **no identical-bytes shortcut**: it always stages the full canonical bytes to
`<target>.tmp` and then does `ATOMIC_MOVE` + `REPLACE_EXISTING`, regardless of whether the
staged bytes equal the target's current bytes — so there is no special "skip the write"
branch that could introduce a truncation window different from the general case; the
existing crash-window invariant (target untouched until the atomic rename) applies
identically whether or not content changed. I verified the *behavioral* claim (byte-for-byte
identical `repository.json`, no warning, unchanged tree) three ways: the project test
(`CommandsMergeSuite.scala:162–184`, both a re-merge and a from-scratch equal-copy merge),
the reproduced 09-merge-text.yaml provided case (its third, idempotent `merge` step), and
my own repeated `sbt testOnly` runs (see Verification).

**7. Direction independence (R76) — CONFIRMED for all five warning reasons, but the
committed regression net only pins two of them; see Finding #1.**
`CommandsMergeSuite`'s direction-independence test (`:145–158`) compares stdout, stderr,
the complete working-file byte map (`workingFiles`, path→bytes for every tracked file), and
the complete `repository.json` bytes — that is the right set of observables for R76 (I
confirmed no observable is missing: version + warnings + tree + metadata is everything
`merge` can produce). Its fixture (`concurrentPair`, `:93–106`) exercises OT (silent,
`notes.txt`) and rule 4 later-create-wins (`same.txt`) — **not** delete-wins, put-wins
(rule 6), later-put-wins (rule 5), or namespace-wins. The provided suite doesn't close this
gap either: `10-merge-conflicts.yaml` merges only one direction (`left ← right`); test
`11-namespace-conflicts.yaml` checks two *different*, role-swapped scenarios (an
ancestor/descendant pair, then a re-labeled early/late pair), not a true `merge(A,B)` vs.
`merge(B,A)` check on one common pair. I did not stop at "the property is architecturally
guaranteed" — I independently constructed and ran four additional scenarios through the
built jar (delete-wins, put-wins/rule-6, later-put-wins/rule-5, namespace-wins), each merged
in both directions from independent copies, and confirmed byte-identical stdout, stderr,
full working trees, and `repository.json` in every case (see Verification for the exact
commands and output). So the property **does** hold for all five reasons — it is not
accidentally true for the narrow committed fixture — but this is currently unguarded by
any committed test. Recorded as Finding #1 (Minor, coverage).

**8. Determinism — CONFIRMED, no vacuous assertions.**
Grepped `CommandsMerge.scala` and `CommandsMergeSuite.scala` for `compareTo`, `System.`,
`Random`, `.hashCode`, unordered `Set`/`Map` construction, `scala.collection.mutable` — none
present. `unionPatches` compares authors via `ContributorId.ordering` (`Ids.scala:59–60`,
itself `Utf8Order`-backed, never `String.compareTo`) and revisions via
`java.lang.Long.compare`; the only recursive helper (`unionPatches.loop`) is `@tailrec`.
Warning iteration goes through the already-`SortedSet`-typed `mergedValid.warnings --
local.warnings`, never re-sorted. `CommandsMergeSuite`'s assertions could not pass
vacuously: every assertion compares against a hand-computed expected byte string (exact
version text, exact warning line, exact file bytes), not a self-referential comparison,
and the direction-independence/no-op/idempotence tests compare two **independently
produced** artifacts (fresh copies, fresh runs) rather than an artifact against itself.

**9. `Presentation.warning` — right layering; does not pre-empt T22, with one caveat
(Finding #3, Nit).**
Adding `warning` to the `Presentation` trait (`Presentation.scala:20–24`) alongside
`result`/`error`, implemented only in `Plain` (`:41–43`) with the literal-LF rule `error`
already uses, is the correct reading of §7.11: a plain warning (`warning: <detail>` →
`⚠ <detail>` in terminal mode) is its own presentation category, textually parallel to
`error`'s `snap: ` prefix, and needs its own method for T22 to override — this is not a new
decision, it is the same shape §7.11 already specifies for errors, extended to the other
category the spec names. **However**, the call site (`CommandsMerge.scala:80`) invokes
`Presentation.Plain.warning` directly from inside the command handler, rather than
returning the warnings for `Cli.emit` to print — `Cli.emit` (`Cli.scala:180–187`) is
otherwise the *only* place any command's presentation output is emitted, for every other
command. This is functionally harmless today (T22 hasn't wired up per-stream Plain/Terminal
selection yet, so `Cli.emit` and this call site both hardcode the same `Plain` object), and
it does not violate R92 (nothing about presentation *selection* happens here). But it is a
second, un-abstracted channel to `env.stderr` that T22 will need to find and update
alongside `Cli.emit` when it introduces real per-stream selection — recorded as Finding #3
(Nit) rather than a blocker, since the `CommandHandler` type (`(Env, Option[Path],
List[String]) => Either[SnapError, String]`) has no channel today for "extra stderr lines,"
and widening it is arguably T22's or a dedicated refactor's job, not T17's.

**10. D27 and T16 ruling 5 — not reopened, fidelity confirmed.**
`CommandsMerge` never re-derives namespace semantics or re-adds sub-replay warnings: the
only warnings it ever touches are the two `Repo.Valid.warnings` sets already produced by
the (previously reviewed) engine, consumed via one set difference. I checked only that
T17's code treats these values as opaque given quantities — it does — and did not
re-examine D27's or ruling 5's own correctness (out of scope per instruction).

## Findings

**#1 [Minor]** `snap/scala/src/test/scala/snap/cli/CommandsMergeSuite.scala:145–158` (and
the provided suite: `snap/tests/10-merge-conflicts.yaml`, `snap/tests/11-namespace-
conflicts.yaml`) — R76 direction-independence is asserted end-to-end (version, warnings,
full tree, full `repository.json` bytes — the right observables) but only against a fixture
exercising OT and later-create-wins. Delete-wins, put-wins (rule 6), later-put-wins
(rule 5), and namespace-wins are never merged in both directions on a common pair by any
committed test. Concrete risk: a future change to `unionPatches`, `Version.join`, or the
replay's determinism that happened to break symmetry *specifically* in the presence of one
of these four reasons (e.g. an accidental dependency on which side's `Patch` reference is
retained at a colliding-but-equal dot, or an ordering assumption inside the namespace
pre-pass that isn't actually reason-agnostic) would pass every currently-committed test
and still violate R76. I independently confirmed the property holds today for all five
reasons by construction and by running four additional scenarios through the built jar
(see Verification) — this is a coverage gap in the regression net, not a live defect.
Suggested direction: add one or two `CommandsMergeSuite` cases (or fold into T18's property
suite) that merge a fixture containing a namespace collision and a put/text conflict in
both directions and assert full byte-identity, mirroring the existing
direction-independence test's shape.

**#2 [Minor]** `snap/scala/src/main/scala/snap/cli/CommandsMerge.scala:120–138` (dot
collision reporting) and `snap/scala/src/test/scala/snap/cli/CommandsMergeSuite.scala:
288–313` — the doc comment claims "the leftmost collision in dot order decides, so the
reported error is deterministic and direction-independent" (`:117–118`), but the only test
exercising collision reporting has exactly one colliding dot, so both merge directions
trivially report the same (only) dot. I traced the merge-join algorithm by hand and
confirmed the claim holds for multiple simultaneous collisions (the scan always halts at
the smallest colliding dot in ascending order, a function of the sorted vectors' content,
not of argument naming) — so this is a coverage gap, not a live defect. Suggested
direction: extend the existing "same dot with different values" test with a second,
later-sorting colliding dot, asserting the smaller one is reported in both directions.

**#3 [Nit]** `snap/scala/src/main/scala/snap/cli/CommandsMerge.scala:80` — this is the only
call site outside `Cli.emit` (`Cli.scala:180–187`) that invokes `Presentation` directly,
and it hardcodes `Presentation.Plain` rather than obtaining a presentation value through
any per-stream selection seam. No behavioral effect today (T22 hasn't introduced Plain/
Terminal selection yet, so every call site is hardcoded to `Plain` regardless), but it
means T22 will need to update two call sites, not one, and will need to decide how a
command handler (whose current type only returns one stdout string) obtains the correctly-
selected stderr presentation for warnings. Worth a one-line pointer in T22's task file;
not a defect in T17.

No Critical or Major findings.

## Verification (reproduced independently)

All commands run in the foreground from the repo root / `snap/scala`, Java 17 first on
`PATH` where the harness is involved. No `run_in_background`, no Monitor.

1. **`cd snap/scala && sbt -batch clean assembly`** → `[success]`; 39 main sources compiled
   clean (up from T16's 34 — T12/T13/T14 landed on `main` in between), jar
   `snap-assembly-1.0.0.jar` built, hash `dcc17931…`.

2. **`cd snap/scala && sbt -batch test`** →
   ```
   [info] Passed: Total 603, Failed 0, Errors 0, Passed 603
   [success] Total time: 8 s
   ```
   Exactly the expected 603 (589 pre-T17 + this task's 14, matching the task notes'
   own count). `CommandsMergeSuite` reported `0 failed, 0 ignored, 14 total`. Build runs
   under `-Werror`/`-Wunused:all` (confirmed present in `build.sbt:30,34`), so this also
   confirms the new code compiles with zero warnings.

3. **`cd snap/scala && sbt -batch scalafmtCheckAll`** → `[success]` (39 main + 47 test
   sources checked clean).

4. **`cd snap/scala && sbt -batch "scalafixAll --check"`** → `[success]` (39 main + 47 test
   sources, no findings).

5. **`PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala`**
   → **23 passed, 5 failed**, exactly the predicted split:
   ```
   ✓ 01 init creates an empty repository
   ✓ 02 initialization preserves files and rejects nested or existing repositories
   ✓ 03 local and global contributor configuration have strict precedence
   ✓ 04 commit status and log expose exact deterministic history
   ✓ 05 diff renders canonical repeated-line edits and missing final newlines
   ✓ 06 binary and empty files are versioned byte exactly
   ✓ 07 revert is additive and restores file-directory transitions
   ✓ 08 working tree scans reject symlinks and special files without mutation
   ✓ 09 local merge converges concurrent text changes and is idempotent
   ✓ 10 merge applies every whole-file conflict rule with sorted warnings
   ✓ 11 canonical namespace winners replace conflicting files in both directions
   ✗ 12 server exposes one immutable repository snapshot and exits on SIGTERM   (T19)
   ✗ 13 HTTP merge and diff use one exact validated GET without redirects       (T20)
   ✓ 14 command grammar and common failures use stable exit channels
   ✓ 15 repository reader rejects malformed schemas histories paths and edits
   ✗ 16 cross-repository dot collisions fail before changing local state       (diff --repo step only — T20/T21; merge's own collision step passed, confirmed by reading the failure: it stops at "stderr did not contain patch collision" on the diff --repo run, not the merge run)
   ✓ 17 concurrent creates choose the canonical later value independent of merge direction
   ✓ 18 three-way text history converges across different merge association orders
   ✓ 19 CLI versions are canonical known causal frontiers
   ✓ 20 merge refuses dirty and unsupported working trees without importing history
   ✓ 21 vector clocks use causal closure componentwise join and canonical Snap order
   ✓ 22 text OT covers overlapping deletes split counts insert priority and trailing inserts
   ✓ 23 repository validation rejects every malformed layer before mutation
   ✓ 24 every command rejects unknown misplaced duplicate and extra arguments
   ✓ 25 configuration versions paths and text use their exact canonical boundaries
   ✗ 26 local exchange preserves text bytes and malformed remotes never mutate  (its diff --repo step — HTTP/T20/T21)
   ✓ 27 patch histories require exact schemas canonical order and valid base transitions
   ✗ 28 terminal presentation is colorful readable and explicitly controllable (T22)
   5 failed, 23 passed in 61136ms
   ```
   Matches the task notes' predicted set exactly: {12, 13, 16 (`diff --repo` step only),
   26, 28}, all owned by later tasks (T19/T20/T21/T22), none a T17 regression.

6. **Repeated-run determinism:** `sbt -batch "testOnly snap.cli.CommandsMergeSuite"` run
   twice more in separate fresh JVMs → `14 total, 0 failed` both times.

7. **Independent empirical extension of R76 beyond the committed fixtures** (ad hoc, via
   the already-built jar and `./snap/run --lang scala`, no source changes): I constructed
   four scenarios the committed suites don't cover bidirectionally and merged each pair in
   both directions from independent copies, diffing stdout/stderr/full working
   tree/`repository.json` bytes:
   - **delete-wins + put-wins (rule 6) in one merge:** a shared-base pair where one side
     edits a path as text after the other side replaced it with binary `put`, plus a path
     one side edits (kept present) while the other deletes it and recreates it as a
     directory. Forward (`left merge right`) and backward (`right merge left`, fresh
     copies) produced identical stdout (`(alice@x->1,bob@x->1,seed@x->1)`), identical
     stderr (`warning: auto-resolved incompatible.txt: put-wins` +
     `warning: auto-resolved ns: delete-wins`), byte-identical trees (`diff -rq` empty),
     and byte-identical `repository.json`.
   - **later-put-wins (rule 5), isolated:** one side authors a binary `put` (NUL bytes)
     over a path the other side edits as text, plus the same delete/recreate-as-directory
     shape on a second path. Both directions: identical
     `warning: auto-resolved later-put.txt: later-put-wins` +
     `warning: auto-resolved ns: delete-wins`, identical winning bytes (`00 01`), full
     `diff -rq` and `repository.json` byte-identity.
   - **namespace-wins, isolated:** from a shared base with neither `a` nor `a/b` present,
     one side creates `a` as a new file, the other creates `a/b` as a new file (genuine
     `S`-set collision per D27, unlike the earlier two scenarios' rule-3 case). Both
     directions: identical `warning: auto-resolved a/b: namespace-wins`, identical version,
     `diff -rq` empty, `repository.json` byte-identical.
   - **R78 operand resolution from a nested cwd** (not a conflict scenario, but also
     uncovered by any committed test — every existing merge test invokes `merge` from the
     repository root itself, where cwd and discovered root coincide): ran `merge` from
     `a/sub/deep` (nested inside repository `a`) with operand `../../../b` — a path that
     only resolves correctly relative to the actual cwd, not the discovered repository
     root `a`. Succeeded and imported `b`'s patch correctly, confirming
     `resolveOperand`'s use of `env.cwd` (`CommandsMerge.scala:101`) is exercised for real,
     not merely written correctly by coincidence of every test using `cwd == root`.

   All four scenarios and their exact commands/output are reproducible; none required
   touching any source file. These results back Finding #1's "the property holds but is
   undertested" conclusion and the R78 confirmation under ruling 1/9.

8. **`sbt slowTest` was NOT run** — no slow-suite-relevant code changed by this task
   (`CommandsMerge` only composes already-reviewed primitives), and the brief scopes it to
   phase gates.

## What I checked and found correct

- D11's failure precedence is implemented exactly as designed and is observably correct at
  every tested boundary (dirty-before-remote-read, unsupported-entry-before-remote-read,
  URL-NotImplemented-at-the-remote-load-position, dot-collision-before-any-mutation) —
  reproduced independently, not just read.
- `unionPatches` performs genuine structural/byte equality dedupe (via `Patch`'s case-class
  equality composed through `Change.Put`'s byte-safe override), produces a canonically
  sorted, direction-independent result by construction, and reports the deterministic
  smallest colliding dot — proven algebraically, empirically pinned for the
  single-collision case, traced (not test-pinned) for the multi-collision case (Finding #2).
- R75's warning subtraction consumes exactly the two `Repo.Valid.warnings` values the task
  intended (pre-merge local, joined union), with no recomputation, over the already-
  `Utf8Order`-sorted `Warning.ordering`.
- The remote's own full validation (§4.5 steps 1–6) is required both by R89's text and by
  `unionPatches`'s own correctness precondition — not redundant work, and I identified a
  concrete way skipping it could silently corrupt the union rather than merely skip an
  extra diagnostic.
- Warnings print only after `Store.writeRepository` succeeds, which is the literal reading
  of §7.8's sentence order and consistent with R106's explicitly sanctioned partial-failure
  window; a crash between install and write leaves a legitimately "dirty, please retry"
  state, not corruption.
- Merge creates no patch, never moves the frontier backward (`Version.join` is
  componentwise `max`), and the no-op path is genuinely branch-free — no special case
  anywhere, including at the byte-write layer (`Store.writeRepository` has no
  identical-bytes shortcut, so the no-op path shares the exact same crash-window behavior
  as every other write).
- Direction independence (R76) holds for all five warning reasons, confirmed both
  architecturally (the union and join algorithms are provably symmetric; replay is a pure
  function of the resulting `Repository` value) and empirically, for four scenarios the
  committed test suites don't exercise (Finding #1 records this as a coverage gap, not a
  defect).
- No wall-clock/env/randomness/hash-order dependence anywhere in the new code; the only
  recursive helper is `@tailrec`; no `String.compareTo` on paths or ids.
- `Presentation.warning`'s addition to the trait is the right shape per §7.11 and does not
  pre-empt any T22 decision about *what* gets rendered — only its call site is a minor,
  non-blocking architectural loose end for T22 to account for (Finding #3).
- D27 and T16's sub-replay-warning-discarding ruling are consumed as opaque, already-proven
  facts; `CommandsMerge` does not re-derive or second-guess either.
- Lint gates and the full project suite are green; the provided harness shows exactly the
  predicted 23/28 split with no unexpected regression, reproduced independently rather than
  taken from the task notes.

## Triage (orchestrator)

Verdict accepted: **approve**, commit as-is. No finding blocks the commit: all three are
coverage or layering items on a command the reviewer traced against the spec line by line
and re-verified independently (603/603, both lint gates, harness 23/28 with the expected
pass list), including four extra direction-independence scenarios it constructed and ran
through the built jar rather than inferring.

| # | Severity | Decision | Where it goes |
|---|---|---|---|
| 1 | Minor | **deferred → T18 part 2** | R76 direction independence is asserted on the right observables but only for OT and later-create-wins fixtures. T18's second half is exactly the `merge`-level property suite, so instead of hand-adding four fixtures, the generator will cover all five warning reasons in both directions — a stronger fix than the finding asks for. Pointer added to `tasks/T18-convergence-properties.md`. |
| 2 | Minor | **deferred → T18 part 2** | Multi-dot collision determinism is traced correct but pinned only by a single-collision test. Same reasoning: the property suite generates multi-dot collisions, which subsumes the suggested two-dot fixture. Pointer added to `tasks/T18-convergence-properties.md`. |
| 3 | Nit | **deferred → T22** | `CommandsMerge` reaches `Presentation.Plain` directly instead of going through `Cli.emit`, making it a second presentation call site. No behavioral effect today (every site is hardcoded to `Plain` until T22 introduces selection), and T22 owns the per-stream renderer, so it fixes both sites together and decides how a handler obtains the selected stderr presentation. Pointer added to `tasks/T22-terminal-presentation.md`. |

Rulings worth carrying forward: the remote repository's full validation is **not**
redundant — it is required by R89's text *and* by `unionPatches`'s sortedness
precondition, so skipping it could corrupt the union rather than merely drop a
diagnostic. And printing warnings only after the metadata write succeeds is spec-forced,
consistent with R106's crash window.


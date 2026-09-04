# T07 pre-commit review — replay ready-loop, materialization, validation steps 5–6

- **Date / scope:** 2026-09-04, staged working-tree diff on `main` (base `ec2be33`):
  `snap/core/Replay.scala` (new), `snap/core/Repo.scala`, `snap/core/Errors.scala`,
  `snap/fs/Store.scala`, `ReplaySuite.scala` (new), `ReplayLawsSuite.scala` (new),
  `tasks/T07-replay-loop.md`. First compile of the T07 ∪ T09 union (T07 worktree
  predated T09).
- **Reviewed against:** SPEC §4.1 (known version), §4.3, §4.5 steps 5–6, §6.1, §6.2
  rule 1; DESIGN §5, D14, D19; SPEC-NOTES §4.2 item 2, risk note 2; the task file's
  acceptance criteria and Notes.
- **Verification (run independently by the reviewer):**
  - `sbt -batch scalafmtCheckAll "scalafixAll --check" test` → exit 0; both lint
    checks `[success]`; **project suite 351 passed, 0 failed, 0 errors** (includes
    ReplaySuite 34/34, ReplayLawsSuite 4/4).
  - Second run of `scalafmtCheckAll` + `scalafixAll --check` +
    `testOnly snap.core.ReplaySuite snap.core.ReplayLawsSuite` → exit 0, 38/38.
    Two separate JVM runs of the ScalaCheck suites (fresh seeds each run) passed —
    repeated-run determinism exercised, not just asserted.
  - Harness (`--filter` is single-valued in `test-harness/src/cli.ts:26`, so two runs):
    `./snap/verify --lang scala --filter 01-init` → **1 passed** (no regression).
    `./snap/verify --lang scala --filter 03-configuration` → 1 failed at **step 7**
    (`commit local-wins`): expected `(local@example.com->1)`, got exit 1 /
    `snap: not implemented` — exactly the T10 gap; all earlier steps (init, config
    writes, config-precedence reads) passed, i.e. the new `readRepository` →
    `validateFully` pipeline did not regress anything before the commit step.

## Findings

### Ready-loop ordering (SPEC §6.1, R66, D14) — no findings

- `Replay.scala:30-37` `readyOrdering`: key 1 is `Version.snapOrdering` over the
  **result** versions (`Sel.result`, precomputed in `Repo.StructurallyValid.results`
  from `Patch.result` = base + own dot — Repo.scala:79, Patch.scala:78), key 2
  `ContributorId.ordering` (`Utf8Order`, Ids.scala:59-60) over author, key 3
  `java.lang.Long.compare` over revision. Verbatim §6.1. CONFIRMED.
- Hand derivation from §3.4 (2 contributors, a1=(a@x,1)/base (), b1=(b@x,1)/base ()):
  sorted union {a@x, b@x}; first counter pair is 1 vs 0 at a@x, lower is earlier, so
  result `(b@x->1)` precedes `(a@x->1)` → **b1 integrates first**. Traced
  `Version.snapOrdering` (Version.scala:104-124): `compare((a→1),(b→1))` hits
  `c < 0 → 1`, so `(a→1)` sorts after — code agrees, and ReplaySuite:64-73 asserts
  the integration order `[b@x 1, a@x 1]`. Matches DESIGN §10 gotcha 3. CONFIRMED.
- Totality/strictness: two distinct dots always differ at key 2 or 3; snap-compare 0
  only for identical versions; `ready.min(selOrdering)` (Replay.scala:230) is therefore
  insensitive to `pending`'s vector order for any dot-unique input (dot uniqueness is
  step-2's `checkSortedAndDots`). Readiness `base <= progress` (Replay.scala:263-265)
  is exact because the integrated set is per-contributor downward closed (increment
  rule + readiness, by induction) — traced, CONFIRMED.
- Keys 2/3 live and directed-tested on hand-built ties (ReplaySuite:87-114), including
  dominance of key 1 over 2/3 and 2 over 3.

### `Replay.LinearOnly` safety claim (R69 rule 1, R68 guard) — no findings

I attempted to construct a case where the guard passes but the tree differs from the
full §6.2 engine's; none exists, by this argument (traced on Replay.scala:62-97):

- Guard passes ⇒ every changed path has identical presence+bytes in `B` and `C`
  (`sameEntry`, byte-level) ⇒ the full engine's per-path evaluation hits rule 1
  (checked first) for every path ⇒ "apply the authored change directly", all together
  (R70) — which is exactly the fold over `authored` presence at Replay.scala:78-82
  (text edit applied to `C`'s copy equals applying to `B`'s copy since bytes are
  identical; delete/put likewise).
- The namespace guard uses the spec's exact `C'` (canonical minus the patch's authored
  deletions, Replay.scala:92-93) and an `S` that is a superset of the spec's
  ("changed paths present in authored" includes pure edits, not only newly-present
  paths). Superset-S can only make the guard fire *more* often (→ typed error, safe);
  and under the rule-1 precondition it is in fact equivalent: an edited path present in
  both `B` and prefix-free `C` can have no ancestor/descendant in `C` at all. So the
  spec pre-pass fires ⇒ guard errors; guard passes ⇒ pre-pass silent. CONFIRMED.
- Adversarial probes traced by hand, all safe:
  - concurrent delete-then-absent (C[p] absent = B[p] absent, P creates p): rule-1
    identical-absent on both paths, engines agree;
  - concurrent identical create of the same path/bytes: guard fails (B absent,
    C present) → typed error — full engine would collapse via rule 2, but staging
    error ≠ wrong bytes (documented T16 seam);
  - concurrent creates `a` vs `a/b`: rule-1 precondition passes per path, namespace
    guard fires → typed error (directed test ReplaySuite:371-378) — without the guard
    this would have built a non-prefix-free tree;
  - delete `a` + create `a/b` in ONE patch over linear history: `C'` excludes the
    authored deletion → no conflict, correct target (matches §6.2's `C'` semantics);
  - base-vs-canonical divergence at an *unchanged* path is irrelevant to both engines
    (both only touch P's paths); at a *changed* path it fails `sameEntry` → typed error.
- `canonical` stays prefix-free by induction (empty tree; authored result checked
  prefix-free in step 5; guard blocks cross-patch namespace violations), so the
  prefix-free assumption used above holds. CONFIRMED.

### Known-version predicate (R45) — no findings

`Replay.checkKnown` (Replay.scala:108-116): top-dot-per-contributor existence plus
base containment `base[d] <= V[d]` for every selected patch — with the structural
proof's contiguity this is exactly §4.1's definition; the reliance is documented in
the doc comment. Edges verified: `()` always known (both `forall`s vacuous, tested
incl. the empty repository); frontier and interior per-contributor prefixes (tested,
4 shapes); unknown contributor (tested); absent higher revision (tested); vector
selecting a patch whose base is not contained (`(b@x->1)` with b1 based on a1 —
tested, exact message `unknown version: (b@x->1)`); zero revisions unrepresentable in
`Version` (D17) and rejected at CLI parse (`ExplicitZeroRevision`), so absent=0 by
construction. `materialize` checks knownness before replaying (tested). CONFIRMED.

### Validation steps 5–6 (R51–R52, R25, R59–R60) — no findings

- Pinned strings verified against the actual YAML fixtures, which the unit tests lift
  verbatim: test 23 `^snap: delete of absent path: f\n$` (concurrent put/delete
  fixture — integration order puts b1 first and its `authoredResult` fails before the
  `LinearOnly` guard can ever produce the staging error; either order yields the same
  message since step 5 judges against the *exact base*, not `C`) ↔
  `Messages.deleteOfAbsentPath`; test 15 `no-op change` (put repeating base bytes),
  `tree paths conflict` (put `a` + put `a/b`), `does not consume old content`,
  `cyclic or incomplete patch history` — all fixture-for-fixture mirrored in
  ReplaySuite with exact typed errors and message assertions. Test 19's full line
  `snap: unknown version: (a@x->2)` composes from the catalog + CLI `snap: ` prefix.
  All of test 27's cases are shape-only (`^snap: .+\n$`) — satisfied. CONFIRMED.
- **R51 create-vs-edit reading (task note) — judged sound.** §4.3 has no intent flag:
  `text`/`put` cover both create and edit/replace, so creation-ness can only be
  determined by base presence, making the absence clause unfailable for text/put and
  leaving `delete` as the only live absence rule — consistent with the single pinned
  absence message. Test 27's "create present" (empty edit over present `f`) rejecting
  as `edit does not consume old content` satisfies the shape pin; §4.4's "empty script
  valid only when creating an empty text file" is fully honored (present nonempty →
  underconsumption; present *empty* → no-op — both invalid; absent → creates empty
  file, tested). No spec text discriminates against this reading. CONFIRMED.
- No-op detection: put-identical-bytes and edit-reproducing-old-tokens both rejected;
  token equality = byte equality holds because canonical tokens concatenate losslessly
  (tokenize/render are inverses, TextTokens.scala:25-39). The R52 exception never
  reaches `NoOpChange` (creation alters existence). CONFIRMED.
- Prefix-freeness of the authored result: checked over the full authored tree after
  all changes (Replay.scala:164-169), reporting the first offending path in
  `Utf8Order` — deterministic; presence judged against `base`, application against the
  accumulator, so delete-`a`-create-`a/b` and create-`a`-delete-`a/b` both resolve
  correctly regardless of change order within the sorted vector. Tested both intra-patch
  and against an existing base file. CONFIRMED.
- Early-empty ready set → `SnapError.CyclicHistory` with the pinned phrase, tested
  both initially-empty (hand-built 2-cycle) and mid-replay (3-party incomplete
  dependency). CONFIRMED.
- **Error-precedence claim (task note) — verified:** ready-loop order → path-sorted
  change order → fixed per-change check order, all over totally ordered structures;
  I checked fixtures 15/23/27 each isolate a single defect, so no pinned expectation
  depends on cross-patch precedence. CONFIRMED.

### Determinism — no findings

- No `System.*`, `now`, `getenv`, randomness, or mutable/hash collections in the
  staged core files (grepped). The only unordered structures are the D19 memo
  (`Map[Version, Tree]`) and the `dots: Set[Dot]` — both key-probed only, never
  iterated (traced every use). Trees iterate via `TreeMap` in `Utf8Order` by
  construction; `pending` is a Vector reduced by a strict total order. CONFIRMED.
- Property tests: input-order insensitivity of both tree and integration order under
  generated permutations of the `patches` array; byte-identical repeated
  materialization; independently tracked expected tree; every intermediate frontier
  known. Ran twice in separate JVMs (fresh ScalaCheck seeds) — green both times.

### Merge hygiene (`Errors.scala` hand-merge) — no findings

Read the full file: `// T09 additions` (enum cases `RepositoryAlreadyExists` …
`GlobalConfigUnavailable`, match arms, `Messages` block) and `// T07 additions`
(enum cases `UnknownVersion` … `ConcurrentHistoryUnsupported`, match arms, `Messages`
block) are both complete, non-overlapping, appended at the ends of their respective
sections; no conflict markers; `SnapError.message` is exhaustive (compile green under
`-Werror`-style lint gate proves the union mechanically). CONFIRMED.

### Findings list

#1 [Nit] `snap/scala/src/main/scala/snap/core/Replay.scala:249` — the
`materializeMemo` doc comment claims sub-replays "can introduce no new validation
error". Overclaim: a steps-1–4-valid history can contain a patch whose base version
is not self-contained (e.g. a1(base ()), b1..b5 linear, a2(base (a→1,b→5)), c1(base
(a→2)) — passes sorting/contiguity/base-closure/acyclicity/reachability), and the
sub-replay of c1's base `(a→2)` then fails with `CyclicHistory` even though every
outer-loop patch integrates fine. The **behavior is spec-correct** — such a base is
not materializable per §4.1/§6.1 (its selection lacks b@x→5), such a patch is
unauthorable (any real frontier containing a→2 contains b→5), and §4.5's "cycle or
missing dependency" covers the rejection deterministically — but T16 will edit this
code and should not trust the comment's reasoning. Concrete risk: a T16 author
"simplifies" the sub-replay error path away believing it unreachable. CONFIRMED
(scenario traced through `checkBaseClosure`/`checkAcyclic` and the loop by hand;
behavior verified correct, comment wording wrong).

#2 [Nit] `snap/scala/src/main/scala/snap/core/Repo.scala:42-52` —
`Repo.StructurallyValid` and `Repo.Valid` are case classes with public constructors,
so the "proof that steps 1–4/1–6 passed" can be forged (the test suites deliberately
do via `handBuilt`). Concrete scenario: a later command task constructs
`Repo.Valid(structure, someTree)` directly instead of calling `validateFully`,
silently skipping steps 5–6 — the exact failure mode the proof types exist to make
"impossible to overlook" (Repo.scala:29). DESIGN §1 item 4 ("illegal states
unrepresentable where cheap") suggests a private constructor with the factory as the
only producer; test forging can go through a package-private hook. Not a T07 defect —
no current caller misuses it. CONFIRMED (trivially constructible).

No Critical, Major, or Minor findings. Acceptance criteria: all five verified against
code and tests (ordering keys incl. hand-built ties; wrong-base/no-op/non-canonical/
early-empty rejections with pinned strings; input-order-insensitivity property;
known-version edges; no wall-clock/env, sorted iteration only). Scope deviation
(`Store.scala` → `validateFully`, new `Repo.Valid` return type) is recorded in the
task notes, minimal, and covered by the harness regression runs above.

## Status

**Verdict: approve** (the two Nits need no pre-commit action; #1 is a comment-wording
fix and #2 a hardening suggestion — both fine to fold into T16 or a later `T07-fix`/
phase-review pass).

- Lint gate: `scalafmtCheckAll` PASS, `scalafixAll --check` PASS.
- Project suite: 351 passed / 0 failed (ReplaySuite 34, ReplayLawsSuite 4; ScalaCheck
  suites green across two independently seeded runs).
- Harness: `01-init` 1/1 passed; `03-configuration` fails only at step 7
  (`commit`, `snap: not implemented`) — the expected T10 gap, nothing earlier.
- Ready for commit as `T07: …` together with this report.

## Triage (orchestrator, 2026-09-04)

| # | Severity | Decision |
|---|---|---|
| 1 | Nit | **Accepted now (comment-only)** — the `materializeMemo` doc comment rewritten to state that sub-replays CAN fail (`CyclicHistory` on a non-self-contained base) and warn T16 explicitly. No behavior change. |
| 2 | Nit | **Defer to T16** — proof-type constructor hardening (`StructurallyValid`/`Valid` forgeable); pointer added to T16's task file: seal if feasible without gutting test ergonomics, else record why not. |

Verdict **approve** stands. Code, comment fix, and this report commit together.

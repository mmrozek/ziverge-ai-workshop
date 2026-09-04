# Phase 1 review — Foundation (init, config, status, commit, log)

- **Date / range:** 2026-09-04, `297bb98..41a5caa` on `main` (base `297bb98` = T01, whose
  committed state — `build.sbt`, `.scalafix.conf`, `.scalafmt.conf`, `project/` — was read as
  context). Working tree at review time = `41a5caa` + the `097e0ef` chore commit; no uncommitted
  source changes.
- **Tasks closed:** T01 (297bb98), T02 (81430ea), T03 (4b70529), T04 (30d9e75), T05 (e348367),
  T06 (ae85906), T07 (025238e), T08 (6e46415), T09 (0fc896d), T10 (41a5caa).
- **Out of scope, treated as context only:** T15 (`snap/core/Ot.scala`, `acfb222`, phase 3 —
  reviewed in `reviews/T15-review.md`) and the interleaved `docs:`/`chore:` commits.
- **Suites:** provided **8/28 passing** (01, 02, 03, 04, 08, 15, 23, 27 — exactly the expected
  set: phase 1's five goal tests plus 15/23/27 from phase 2 already green); project
  **426/426 passing**; lint gate green.

## Verification (re-executed by the reviewer, not taken from the task files)

```
cd snap/scala && sbt -batch scalafmtCheckAll "scalafixAll --check" test
  scalafmtCheckAll   success
  scalafixAll --check success (DisableSyntax/OrganizeImports/RemoveUnused; no scalafix:ok)
  test               Passed: Total 426, Failed 0, Errors 0    → exit 0
PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala
  20 failed, 8 passed in 37109ms → exit 1
  green: 01-init, 02-init-paths, 03-configuration, 04-commit-status-log,
         08-unsupported-entries, 15-repository-validation,
         23-strict-validation-matrix, 27-history-canonicality
```

No deviation from the expected 8/28. Every one of the 20 failures is the first step of its case
that needs an unimplemented command (`diff` / `revert` / `merge` / `--serve` / terminal mode);
none fails on phase-1 behavior. Corroborating detail: the failing cases get deep into their
step lists first — test 10 reaches step 22, test 18 step 23, test 21 step 16, test 22 step 15 —
so several dozen `init`/`config`/`status`/`commit`/`log` steps beyond the five goal tests already
behave exactly as pinned.

Seed stability: `sbt -batch "testOnly snap.core.* snap.json.*"` run twice more with fresh
ScalaCheck seeds → 304/304 both times (no seed is pinned in `build.sbt`, so these are genuinely
different samples).

Also read: `reviews/phase-1-antipatterns.md` (scala-antipatterns gate run, 4 findings / 0 FIX) —
I re-checked each NOFIX independently and agree with all four classifications.

---

## Findings

### Spec compliance

**#1 [Major] `snap/scala/src/main/scala/snap/core/Errors.scala:439,510,512,514,661,664`
(sink: `snap/cli/Presentation.scala:31`) — error diagnostics can span two lines, violating §10 /
R107 "In plain mode, errors are one line `snap: <detail>`".** Six catalog entries interpolate
*untrusted* text that may legally contain LF/CR/other control characters: JSON object key and
field names (`duplicate JSON key <k>`, `<owner> has unknown field: <f>`, `… is missing field: <f>`,
`… field <f> has the wrong type`) and raw working-tree entry names
(`unsupported working tree entry: <p>`, `invalid working tree path: <p>`). Validated values are
safe by construction (`SnapPath`, `ContributorId`, `Version` all reject control characters), so
this is limited to the pre-validation echo sites.

CONFIRMED — four reproductions against the assembled jar:

| input | stderr (od -c) |
|---|---|
| `.snap/config.json` = `{"a\nb":1,"a\nb":2}` | `snap: duplicate JSON key a \n b \n` |
| `repository.json` with field `"x\ny"` | `snap: repository has unknown field: x \n y \n` |
| regular file named `bad<LF>name` | `snap: invalid working tree path: bad \n name \n` |
| symlink named `link<LF>name` | `snap: unsupported working tree entry: link \n name \n` |

Concrete failure scenario: the harness matches most malformed-input cases with anchored regexes
(`^snap: .+\n$` in tests 12/26/27, `^snap: duplicate JSON key .+\n$` in test 25). A holdout case in
the 26-portability-and-failure-safety family that names an unsupported entry with a control
character — precisely the case where R21/R104 *require* Snap to report it — produces two lines and
fails the anchored match, and in terminal mode (T22) the `S(31,"✗ " + error)` wrapper puts the SGR
reset after the second line. Severity is Major rather than Minor because a normative sentence is
violated on legal input at six sites; triage may reasonably downgrade given no provided test
exercises it.

**#2 [Minor] `snap/scala/src/main/scala/snap/cli/Presentation.scala:31` and
`snap/scala/src/main/scala/Main.scala:29` — the error channel terminates lines with the
`line.separator` system property instead of a literal LF (R107 "Output is UTF-8 with LF line
endings").** Both sites use `PrintStream.println`, which appends `System.lineSeparator()`; the
stdout path correctly uses `print` with LF baked into the text (`Presentation.scala:28`). CONFIRMED
as a code path (the property is read); the *observable* impact is nil on the harness's platforms,
where the separator is `"\n"` — hence Minor. Concrete failure scenario: any JVM where
`line.separator` is not `\n` — a Windows JVM, or any runner that starts the candidate with
`-Dline.separator` set — turns every `snap: <detail>` line into CRLF and fails every
`stderr_equals` assertion in the suite. (The harness scrubs `JAVA_TOOL_OPTIONS`, so it cannot
reach us that way; `snap/run`'s `exec java -jar` accepts no flags either. The exposure is a
different host/JVM, not this harness.) This is
also the one remaining platform/env read outside the designated `Env` boundary, so it is exactly
the class of leak CLAUDE.md ground rule 3 targets.

**#3 [Minor] `snap/scala/src/main/scala/snap/core/Repo.scala:68-80` — validation runs part of
§4.5 step 3 before the rest of step 2, and the deviation is observable in which diagnostic
appears.** Order implemented: sorted/dots (step 2) → `revision = base[author]+1` (step 3) →
contiguity (step 2) → base closure (step 3) → frontier closure → reachability → acyclicity.
CONFIRMED consequence: a history `{"revision":2,"base":[]}` with frontier `[["a@x",2]]` reports
`snap: patch a@x revision 2 does not increment its base`, whereas the spec's step order would
report the missing revision 1 first. Test 15's gap fixture happens to declare `base:[["a@x",1]]`,
so it satisfies step 3 and reaches the contiguity error — which is why the suite is green
(verified by reading `snap/tests/15-repository-validation.yaml:25-46`). Concrete failure scenario:
a holdout gap fixture written with `base: []` (the shape a naive fixture author picks) and
`stderr_contains: missing a@x` fails. The T06 notes record this order as "deterministic, first
violation wins" but do not justify it against §4.5's numbering.

### Correctness & determinism

**#4 [Minor] `snap/scala/src/main/scala/snap/fs/Store.scala:41,106-107` — one hard-coded temp
file name, `repository.json.tmp`, is used to stage *every* atomic write, including both
configuration files.** `tempPathFor(target) = target.resolveSibling("repository.json.tmp")`
ignores the target, so `Store.writeConfig($HOME/.snapconfig.json, …)` stages
`$HOME/repository.json.tmp`, and a local `config` write stages `.snap/repository.json.tmp` — the
same path the repository writer uses. CONFIRMED naming (code read + probe: a `config --global`
write does create and then rename `$HOME/repository.json.tmp`; nothing leaks on the success path,
which is why tests 01/02/03 stay green). Concrete failure scenario (PLAUSIBLE, needs an
interrupted write): with a repository initialized at `$HOME` — legal and not unusual — an I/O
failure or signal between `stage` and `commit` leaves `$HOME/repository.json.tmp`, a *regular file
inside the working tree*, so it becomes tracked: `status` reports `A repository.json.tmp` and
`merge`/`revert` then refuse the tree as dirty (R27) for a reason the user cannot explain. R105
only demands "a same-directory temporary file"; deriving the name from the target
(`<target>.tmp`) removes the whole class.

**#5 [Minor] `snap/scala/src/main/scala/snap/core/Replay.scala:253-263` — materializing a
frontier costs Θ(n²) patch integrations, and `log`/`commit` pay it two and three times over.**
`materializeMemo` answers a memo miss by re-running `loop` over the *whole* selection of that base
version from the empty tree; in a linear history patch *k*'s base `V(k-1)` is a miss, so the run
performs Σ(k−1) ≈ n²/2 integrations even though the canonical tree for `V(k-1)` is the tree the
outer loop had just built. CONFIRMED by measurement against the assembled jar (hand-written
linear histories, single `status` invocation):

| patches | wall time |
|---|---|
| 100 | 0.38 s (≈0.13 s replay + JVM start) |
| 800 | 9.34 s |

That is the expected 64× for 8× the patches. `CommandsLog.scala:24-28` runs a second full replay
(`Replay.integrationOrder`) after `readRepository` already replayed, and `CommandsCommit.scala:40`
+ `:55` run two more per commit. Concrete failure scenario: a holdout fixture that `write_file`s a
~1500-patch history (≈120 KB of JSON, cheap to generate) needs ≈33 s for one `status` and blows
the harness's 30 s case timeout; a `log` step doubles that. Nothing in the spec bounds
performance, and D19 records "correctness first" — but the fix is local (memoize the tree the loop
already holds, keyed by `progress`) and the current shape is a real timeout risk under the holdout
assumption.

**#6 [Minor] `snap/scala/src/main/scala/snap/core/Diff.scala:52-63` — the full DP table is
allocated as `Array.ofDim[Int](n+1, m+1)`, so a large text commit can exit 2 on legal input.**
Memory is 4·n·m bytes plus per-row overhead. CONFIRMED: committing a 20 000-line modification of a
20 000-line file prints `snap: internal error: Java heap space` and exits **2** under
`java -Xmx256m -jar snap-assembly-1.0.0.jar`, while succeeding under this machine's default 12 GB
heap. `snap/run` execs `java -jar` with no flags, so the available heap is whatever the evaluation
machine's ergonomics pick (¼ of RAM). Concrete failure scenario: a holdout "large file" case on a
2-4 GB runner turns a legitimate `commit` into an unexpected-internal-failure exit code. Two
mitigations are on record and pull the other way — §12 excludes "large-file optimizations" from
scope and D18 locks the literal DP — so this may be triaged as accepted-by-design; it is reported
because the failure mode is exit 2 rather than a clean error, and because the exit-2 path is
otherwise reserved for bugs (R107).

Nothing else. What I traced or reproduced clean:

- **Determinism sweep of `snap/core` (independent of `EnvIsolationSuite`, which only covers
  `sys.env`/`System.getenv` in `snap/cli`):** grep for clock/random/locale/env across all of
  `src/main/scala` returns only `Env.real()`'s `System.getProperty("user.dir")` + `sys.env`
  (Env.scala:62-63, the designated boundary) and comments. Zero `var`, zero `while`. Every
  `Set`/`Map` in `snap/core` is probed by key only and never iterated for an ordering decision —
  `Replay.scala:110`, `Repo.scala:74`, `Path.scala:87` (membership), `Replay` memo (keyed lookup),
  `CommandsLog.scala:34-38` (keyed lookup, output order comes from `order.reverseIterator`),
  `Cli.scala:89-98` (dispatch lookup). `Tree` iterates a `TreeMap` keyed by `Utf8Order`;
  `Version.fromMap` sorts before building; `WorkTree.children` sorts directory entries by
  `Utf8Order` rather than listing order. CONFIRMED by reading every site.
- **End-to-end determinism probe:** the same content committed in two sandboxes with *reversed
  file creation order* (which changes directory-entry order on disk) produced byte-identical
  `.snap/repository.json` (sha1 `f4c1eba…` both) and identical `log` bytes. CONFIRMED.
- **§5 canonical diff, re-derived from the spec text before reading the code:** the table is a
  literal transcription of the recurrence including both boundary rows, and the walk is
  equality → exhausted side → `d(i+1)(j) <= d(i)(j+1)` → delete. End-to-end probe: committing
  `a,b,a → b,a,a` (no final LF) produced exactly
  `[{"delete":1},{"retain":2},{"insert":["a"]}]` — test 05's golden, deletion-on-tie included.
  CONFIRMED (this is the artifact SPEC-NOTES risk 1 is about).
- **§3.2 canonical version syntax, re-derived from the spec:** `Version.parse` rejects shape
  violations, missing `->`, empty/non-decimal/leading-zero/explicit-zero revisions, >16-digit and
  2^53 overflow, duplicate ids and non-canonical order; ids reject whitespace, `,`, `(`, `)`,
  `->`, non-ASCII, controls incl. DEL, and >254 bytes; `print ∘ parse` is exact even for ids
  ending in `-` or containing `>`. `fromPairs` applies the same canonicality gate to the JSON
  form (R32). CONFIRMED by trace + `VersionTextSuite`/`VersionLawsSuite` + probes
  (`(a@x->9007199254740992)` → `positive safe integer`, non-canonical frontier → `…canonical…`).
- **§3.4 Snap order:** the union walk returns −1/+1 at the first id present on only one side
  (absent counter 0 < any stored counter ≥ 1), which is the spec's "first unequal counter
  decides"; `(bob@x->1) < (alice@x->1)` as DESIGN gotcha 3 requires. Totality/antisymmetry/
  transitivity/causal-extension are property-tested (`VersionLawsSuite:98-119`). CONFIRMED.
- **§4.5 steps 5-6 and §6.1 ready-loop:** all three ordering keys implemented verbatim
  (`Replay.scala:30-37`); readiness is `base ≤ join(integrated results)`, which is exact because
  integration preserves per-contributor downward closure; exhausted ready set → `CyclicHistory`
  (R60). Order-independence and repeat-stability are property-tested
  (`ReplayLawsSuite:134-156`). CONFIRMED.
- **§7.5 commit, re-derived from the spec:** contributor id required → message rules (bytes, not
  characters) → scan → dirty required → `revision = frontier[author]+1` with overflow rejected →
  dot-collision guard → sorted insertion (R44) → full re-validation → atomic metadata replace →
  prints the new version. Change-kind selection matches R85 exactly; probes confirmed
  `a\r\nb` → `["a\r\n","b"]`, NUL → `put`, empty file → `text` with the empty script (R58),
  text→binary → `put`, binary→text → `put`, removal → `delete`, and `status` clean immediately
  after each commit (byte-exact round-trip). CONFIRMED.
- **Message-ordering constraint pinned by tests 15/23:** mechanically checked that all 60
  `SnapError` cases are rendered (no orphan), that all 62 `Messages` members are referenced, and
  that every end-anchored fragment (`positive safe integer`, `message is empty`, `changes is
  empty`, `unknown field: extra`, `must have one operation`, `insert is empty`, `consumes beyond
  old content`, `does not consume old content`, `unreachable patch: `, `delete of absent path: f`,
  `repository has unknown field: unknown`) sits at the exact end of its message with nothing
  appended by the CLI layer. CONFIRMED (script + read).
- **Strict JSON layer:** probed 17 adversarial documents through the config read path — trailing
  garbage, trailing comma, single quotes, comments, unquoted keys, `NaN`, BOM, empty/whitespace
  input, duplicate keys (incl. nested-context isolation), `1.5`, `9007199254740992`, wrong types,
  unknown fields at both levels, `\ud800` escapes — every one rejected with the right diagnostic
  class, and key order/whitespace tolerance (R41) confirmed with a fully reordered
  `repository.json`. CONFIRMED.
- **R105 atomic write:** same-directory staging, target untouched until `ATOMIC_MOVE`, no temp
  left behind, byte-identical repeat writes — tested (`StoreSuite:92-110`) and re-verified by
  reading; on-disk `repository.json` after a real commit is byte-identical to test 12's pinned
  `body_text_equals` block (checked character by character). CONFIRMED.
- **Holdout spot-checks named in the brief:** R28's 254-byte bound (254 accepted / 255 rejected,
  end-to-end), D12's 0x7F (rejected in ids, paths, and commit messages, end-to-end), R48/R85's
  4096-**byte** limit (4096 accepted / 4097 rejected; the é-straddling case is unit-tested),
  R24's UTF-16 divergence (`status` orders `zz` < U+FFFD < U+10000 — the order Java's
  `String.compareTo` gets wrong), R107 exit codes (0 / 1 / 2 all observed, exit 2 only from the
  `Main` catch-all). All CONFIRMED end-to-end, not just by unit test.
- **Cross-task seams the brief flagged:** the `Errors.scala` catalog survives its three hand-merged
  unions intact (see above); the `CommandHandler` seam stays total by construction
  (`defaultCommands` is built from `Command.values`, so a new command gets the stub automatically
  rather than a `NoSuchElementException`); `Store.readRepository` running the full §4.5 pipeline is
  spec-correct ("Before using a repository, Snap validates … 6. deterministic replay of the
  declared frontier"), and the cost of that choice is finding #5, not a correctness problem.

### Test coverage

No gaps that leave a phase-1 spec requirement unexercised. Notable strengths: R28's byte bound,
R48's 4096-byte limit, R24's supplementary-character divergence, R58's empty-file script, R105's
crash window, and R107's exit-2 path all have directed tests even though SPEC-NOTES §2.1 lists
them as untested by the provided suite; test 12's byte-pinned serialization is a unit golden in two
places (`WriterSuite:89`, `RepoCodecSuite:487-524`) even though the case itself is phase 4.

Two requirements in phase-1 *files* are still owed, both by plan, both correctly assigned:

- **R108** (unit-test `auto` presentation selection for TTY/non-TTY stdout and stderr
  independently — a MUST the spec puts on the implementation, not the harness) has no test yet;
  `Tty.Stub` (`Env.scala:23-25`) hard-codes non-TTY. Owner: T22.
- **R93/R94/R96** presentation selection likewise. Owner: T22.

The two Minor findings #1 and #2 are each uncovered by any test (no assertion pins the one-line
error invariant or the LF-only error terminator); if they are accepted, the fix should land with a
covering assertion.

### Design drift

**#7 [Nit] `snap/scala/src/main/scala/snap/core/Path.scala:5-11` — stale integration comment now
that `Errors.scala` exists, and `SnapError.ChangePathInvalid`'s payload is write-only.** The
doc comment still says "`snap/core/Errors.scala` (built by a parallel task) does not exist in this
task's scope … When the `SnapError` catalog lands, these reasons map into it" — but T06 completed
that migration for `ContributorId`/`Revision`/`Version`/`EditError`, and `Errors.scala:150` now
imports `PathError` in the other direction. `SnapPath.parse` remains the only factory returning a
task-local reason type, and the carried reason is deliberately never rendered
(`Errors.scala:383`). No behavioral defect. Concrete drift risk: a T14 author reading this comment
"finishes" the migration by moving the path reasons into `Messages` and appends the reason after
the pinned `path is invalid` fragment, breaking test 15's `stderr_contains`.

**#8 [Nit] `snap/scala/build.sbt:21-26` — the lint gate has no `-Werror`/`-Xfatal-warnings`, so the
`Errors.scala` catalog's exhaustiveness is guarded only by a non-fatal warning.** A missing case in
the 60-case `SnapError.message` match compiles with a "match may not be exhaustive" warning and
ships as a `MatchError` → exit 2 at runtime. I verified today's union is complete mechanically
(60/60 cases rendered), so this is a guard gap, not a defect — but it also corrects
`reviews/T07-review.md`'s claim that "the `-Werror`-style lint gate proves the union mechanically":
there is no such gate.

**#9 [Nit] `snap/scala/src/main/scala/snap/fs/WorkTree.scala:93` — `Utf8Order` is applied to raw,
unvalidated file names.** `reviews/T03-review.md` #2 established that `Utf8Order` diverges from
encoded-byte order for strings containing unpaired surrogates, and its triage accepted the finding
on the grounds that "no lone-surrogate string reaches `Utf8Order` from the domain". Re-checked at
this gate: the JSON path is closed as promised (every decoded path goes through `SnapPath.parse`,
which rejects `MalformedUnicode` — `RepoCodec.scala:130-132`, `Path.scala:73`) and ids are
ASCII-validated, so the accept still holds for all domain values. The one remaining raw-string
comparison is the directory-listing sort, where a file name that the JVM decodes to an unpaired
surrogate (undecodable bytes under `sun.jnu.encoding`) would order divergently. Impact is bounded
to *which* invalid entry is reported first — such a file is rejected by `SnapPath.parse` regardless
— so this is a Nit, not a reopen. PLAUSIBLE (I did not manufacture an undecodable filename).

**#10 [Nit] `reviews/T05-review.md` — the file contains a literal NUL byte at offset 7984, so git
classifies it as binary** (`git diff --stat` shows `Bin 0 -> 11163 bytes`). Consequence: the review
report is invisible to `git diff`/`git blame`/`grep` and cannot be reviewed in a diff view, which
works against ground rule 7 ("everything is tracked"). The NUL sits inside a code span discussing
NUL handling; escaping it (`\0` / `<NUL>`) restores normal text handling.

### Pitfalls for future phases

Not findings — verified facts the next phases must not trip over.

1. **`Replay.LinearOnly` is wired at three sites, one of them without a strategy parameter.**
   `Repo.validateFully` (`Repo.scala:62`) hard-codes it and takes no `Integration` argument, so
   T16 must change that signature or hard-wire the new engine there; `CommandsLog.scala:27` passes
   it explicitly; `SnapError.ConcurrentHistoryUnsupported` (`Errors.scala:320`) is the staging
   error. Until T16 lands, *every* read path — `status`, `log`, `commit`, `diff` — fails on a
   spec-valid concurrent history, and `commit` also fails on any repository created by a previous
   `merge`. `Repo.Valid` carries no warning set, which T16 must widen. All of this is recorded in
   T16's pre-implementation pointers; the three-site count is not, so it is recorded here.
2. **`snap: invalid version: <arg>` does not exist in the catalog, and cannot simply be added to
   the shared cases.** Test 25 pins `^snap: invalid version: .+\n$` for a *CLI operand* while test
   23 pins `^snap: .*canonical.*\n$` and `^snap: .+positive safe integer\n$` for the *same*
   `SnapError.InvalidVersionValue`/`RevisionNotSafeInteger` cases arriving from repository JSON.
   T11/T13 need a context-carrying wrapper at the CLI boundary, not a reword of the shared
   entries. (`Version.scala:139` records the intent; the wrapper is unwritten.)
3. **Grammar errors are currently reported *after* repository discovery.** `Cli.run:122-124`
   discovers the repository before the handler validates arity, so `snap status extra` outside a
   repository reports `not a Snap repository` rather than `invalid command or arguments`. Test 24
   runs every grammar case with `cwd: repo` (verified in the YAML), so the provided suite cannot
   see this; T13 should settle the precedence deliberately.
4. **`checkNoCollision`/`nextRevision` overflow paths and `Repo.StructurallyValid`'s public
   constructor** remain the two "unforgeable proof" gaps `reviews/T07-review.md` nit 2 deferred to
   T16; still open, still harmless (no production caller forges one).
5. **Findings #5/#6 are the phase's holdout-performance exposure** and belong on T23's radar
   alongside the SPEC-NOTES §2.1 list.

### Documented ambiguity decisions made during the phase

Surfaced here per CLAUDE.md ground rule 1. All are non-core readings, all recorded in the owning
task's Notes, and I agree each is the most spec-consistent reading:

| Decision | Reading | Where recorded |
|---|---|---|
| D12 | "ASCII control character" includes DEL 0x7F, in paths, ids and messages | T03/T04/T05 notes; verified end-to-end |
| D13 | only a *first* segment `.snap` is reserved — `sub/.snap/x` is tracked | T04/T06/T10 notes; verified end-to-end |
| D16 | the 4096-byte message limit is `commit`-input-only; repository validation does not enforce it | T06 notes (5000-byte message accepted by the validator) |
| D10 | `config --global` needs no repository; a `--global` write with no `$HOME` is an error (the read side treats it as "no value") | T09 notes |
| D11 | repository parse+validate precedes the working-tree scan for `status`/`commit`/`diff` | T10 notes |
| — | `config` without `--global` outside a repository reuses `not a Snap repository` (SPEC-NOTES Q6) | T09; verified end-to-end |
| — | a regular file whose name violates R23 (e.g. a backslash) is an error, not a silent skip | T10 notes; verified end-to-end |
| — | one dot listed twice is an error even when the two values are structurally equal (R44 "exactly") | T06 notes |
| — | `commit`'s message check precedes the clean-tree check (forced by test 25), contributor id precedes both (untested) | T10 notes |

### Prior-review deferrals — honored?

| From | Item | Status |
|---|---|---|
| T03 #1 | migrate `Either[String, A]` seams into `SnapError`/`Messages` at T06 | **honored** — `Ids`/`Version` return `SnapError`; `IdError`/`VersionError` are typed; all text in `Messages` |
| T03 #2 | route every JSON-decoded path through `SnapPath.parse`; re-check at the phase gate | **honored** — `RepoCodec.scala:130-132`; unpaired surrogates rejected. Residual raw-string sort recorded as #9 above |
| T03 #3 | wire R32 AST↔`Version` in T06 | **honored** — `decodeVersion` → `Version.fromPairs`, covered in `RepoCodecSuite` |
| T05 #1 | equality-before-tie script golden → T18 | **honored** — pointer present in `tasks/T18-convergence-properties.md` |
| T05 #2 | append-only Notes correction (NUL/surrogate checks are load-bearing) | **honored** — appended at the end of `tasks/T05-tokens-diff.md`, original text untouched (history stays honest) |
| T05 #3 | `EditError` messages into the catalog | **honored** — `EditError.message` delegates to `Messages.editError`; no module builds diagnostic text |
| T07 nit 1 | fix the "sub-replays cannot fail" comment; warn T16 | **honored** — `Replay.scala:246-251` + T16 pointer |
| T07 nit 2 | private constructors for the proof types → T16 | **deferred as agreed** — pointer present, still open |
| T15 #1/#2 | no canonical-result check on transformed scripts (T16); directed P-insert/Q-delete test (T18) | **honored** — both pointers present in the task files |

---

## Orchestrator triage

_(added by the orchestrator; findings above must not be edited)_

## Follow-ups created

_(added by the orchestrator)_

## Status after review

**Verdict: approve-with-fixes.** 0 Critical · 1 Major · 5 Minor · 4 Nits.

Phase 1's contract is met: the five goal tests plus three phase-2 validation matrices are green,
426 project tests and the lint gate pass, and the parts that decide this project — Snap order,
causal compare/join, the §5 diff tie-break, the ready-loop's three keys, the strict JSON layer and
the canonical serializer — are spec-faithful when re-derived from the spec text and confirmed by
end-to-end probing, not just by the suite. The one Major (#1) is a contained output-sanitization
gap with a one-line fix at the catalog boundary; #2 is a one-line change in the same area. #3-#6
are behavioral-margin items (diagnostic selection order, temp-file naming, and two
scaling limits) that triage may reasonably defer to T14/T23 with tasks filed.

Suite counts after fixes: _(to be filled by the orchestrator after triage)_

## Combined triage (orchestrator, 2026-09-04)

Inputs: this report (PR1–PR10), the code-review pass (CR1–CR15, low effort, findings
embedded in the session log; overlaps noted), `reviews/phase-1-antipatterns.md` (0 FIX).

| Finding | Severity | Decision |
|---|---|---|
| PR1 / CR-LF — six diagnostics interpolate untrusted text; LF ⇒ two-line errors (R107) | Major | **Accept now** — sanitize control chars in untrusted interpolations at the Messages boundary |
| PR2 / CR-sep — `println` uses `line.separator`, not literal LF (§10) | Minor | **Accept now** — explicit `\n` writes in Presentation.error + Main catch-all |
| PR3 / CR-order — §4.5 step 3 (increments) runs before step 2 (contiguity); doubly-invalid history reports the wrong class | Minor | **Accept now** — reorder |
| PR4 / CR-tmp — hard-coded `repository.json.tmp` stages config writes too | Minor | **Accept now** — temp name derived from target |
| PR5 / CR1(perf half) — Θ(n²) replay | Minor | **Defer to T23** (measured; no suite case approaches timeout; D19 says optimize against evidence) — pointer added |
| CR1 (stack half) — non-tail replay loop can StackOverflow on ~1k+ patch valid histories (exit 2 on valid input) | Major-adjacent | **Defer to T16 integration** — T16 rewrote Replay.scala wholesale in its worktree; stack-safety verification + fix belongs to its pre-commit core review, not a conflicting patch here. Pointer added to T16's task file |
| PR6 / CR-DP — full DP table OOM on huge files | Minor | **Accept as documented trade-off** — D18 locks the literal DP; §12 excludes large-file optimizations; revisit only via a D18 amendment with script-equality proof |
| CR2 — `HOME=""` resolves global config against cwd | — | **Accept now** — empty `HOME` treated as unavailable (recorded as locked decision D24) |
| CR5 — root `.snap` symlink silently skipped by scan while discovery/init follow it | — | **Accept now** — discovery/init require a real directory (NOFOLLOW); scanner reports a root `.snap` symlink as unsupported (metadata exclusion applies to the real directory only; §2 MUST-report/MUST-NOT-follow wins). Recorded as D25 |
| CR7 — grammar validated after repo discovery (unpinned precedence) | — | **Defer to T13** — already an explicit T13 acceptance criterion; pointer reinforced |
| CR9 — `decodeChange` extracts `type` before the unknown-field check, masking R43 diagnostics | — | **Accept now** — reorder per the module's own documented precedence |
| CR10 — `Files.exists` gate maps unreadable config to "no value" | — | **Accept now** — attempt read; absent → None, I/O failure → CannotReadConfig |
| CR-NUL — NUL-containing (valid-UTF-8) bytes reported as "not valid UTF-8", position lost | — | **Accept now** — gate checks UTF-8 only; raw NUL falls through to jawn's positioned invalid-JSON error |
| CR11 = PR4 | — | (same fix) |
| CR12 — `Delta` has reference equality (only IArray case class without content override); byte-helpers copied ×4 | — | **Split**: equality override **accept now**; helper consolidation **defer to T23** (pointer) |
| CR13 — `1.0`/`1e2` rejected by text-based integer-ness; cross-impl exchange reading | — | **Accept as decision** — strictness kept, recorded as locked decision D26 (spec: "non-integer numbers are errors"; canonical writer never emits such spellings; §11 item 11 is out of the public harness) |
| CR14 — `snap init ""` initializes cwd | — | **Defer to T13** (owns the exhaustive grammar matrix) — pointer added |
| PR7 — stale migration comment in Path.scala | Nit | **Accept now** |
| PR8 — no `-Werror`; enum exhaustiveness rides on a non-fatal warning | Nit | **Accept now** — `-Werror` added (zero current warnings, verified by this review) |
| PR9 — Utf8Order applied to unvalidated filenames in the scanner walk | Nit | **Accept as doc** — total and deterministic on any string (T04 property); ordering choice pre-validation is unobservable |
| PR10 — literal NUL makes `reviews/T05-review.md` binary to git | Nit | **Accept now** — escaped in place (content-preserving amendment) |

Accepted fixes land in one `review(phase-1):` commit (sonnet implementer; gates +
harness re-run recorded there). Phase 1 closes with that commit.

## Fix-batch verification & late findings (orchestrator, 2026-09-04)

- All 13 accepted fixes applied and verified: `sbt test` 449/449 under `-Werror`, both
  lint gates, harness at exactly 8/28 (01,02,03,04,08,15,23,27), behavioral probes
  byte-verified (one-line LF-escaped errors; `HOME=""` unavailable; discovery refuses a
  symlinked `.snap`).
- **False positive during verification:** test 23 appeared to hang post-batch. Root
  cause was NOT a code defect: `snap/run` (contract) treats `src/test` edits as jar
  staleness while `sbt assembly` never re-stamps the jar for test-only changes, so
  every invocation paid a full sbt bootstrap (~2.6 s × 12 runs > the 30 s case budget).
  Resolved with `sbt -batch clean assembly`; a clean-worktree control run proved the
  batch innocent. **Workflow rule added to CLAUDE.md**: after test-only edits, force
  `clean assembly` before `./snap/verify`.
- **Spot-check (c) clarification:** a `.snap` symlink in a repo-less directory now
  correctly yields `snap: not a Snap repository` — discovery refusing to follow the
  symlink IS the CR5 fix working; the "scanner reports it" expectation is unreachable
  at a discovered root (which by definition has a real `.snap` directory). Nested
  `.snap` symlinks inside a real repo are reported by ordinary entry classification.

**Phase 1 closed** with this commit; deferred items live as pointers in T13/T16/T23.

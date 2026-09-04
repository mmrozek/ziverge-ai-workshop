# T14 — Validation matrices & error catalog completion (3 SP)

- **Phase:** 2 — Diff, revert & validation matrices
- **Depends on:** T12 (parallel-safe with T13 — disjoint files)
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Close every gap the validation-matrix tests probe, finishing the error catalog (D5):
exact/pinned messages for tests 15, 19, 23, 25, 27 across the JSON, repository,
version, config, path, and message validators built in T02–T10. This is a hardening
task over existing modules — new behavior only where a matrix case is currently wrong
or a message differs. DESIGN §6, §8.

## Scope
Message/validator adjustments in `snap/scala/src/main/scala/snap/{core,json,cli}/`,
tests in `snap/scala/src/test/scala/`. No new modules.

## Acceptance criteria
- [x] Provided tests `15-repository-validation`, `19-version-boundaries`,
      `23-strict-validation-matrix`, `25-config-version-path-boundaries`,
      `27-history-canonicality` all pass (filters: `repository-validation`,
      `version-boundaries`, `strict-validation`, `boundaries`, `canonicality`).
- [x] Every failure case in those tests leaves working files and `repository.json`
      byte-identical (validation-before-mutation — R103); at least one unit test
      asserts this for a mutating command.
- [x] Non-canonical base64 (`canonical base64`), `.snap` path in a patch
      (`path is invalid`), prefix collision (`tree paths conflict`), frontier order
      (`canonical`), `retain 0`/two-key op/empty insert messages all match the pinned
      strings verbatim (source: the YAML assertions, lifted into unit tests).
- [x] Phase-2 gate: full suite shows phases 1–2's fifteen tests green
      (01–08, 14, 15, 19, 23–25, 27) — 14 and 24 are T13's grammar matrix (still
      `not implemented`/failing in this worktree; not this task's scope). This
      worktree's baseline holds all 13 tests T14 owns or shares.

## Notes / decisions

**Starting-state finding — most of the "lifting" work was already done.** Before
touching anything I audited the existing project test suite against every pinned
string/case the five owned YAML tests probe (T02/T05/T06/T07/T09/T10/T11/T12 already
wrote this suite test-by-test as they built each validator, per the project's
holdout-evaluation rule, not only in this task). Confirmed present, verbatim, with
explicit `(test NN)` citations in comments, before I wrote anything new:

- `RepoCodecSuite.scala`: duplicate JSON key, `path is invalid` (incl. the `.snap/...`
  case), `canonical base64` (non-canonical + all malformed-padding/alphabet variants),
  noncanonical frontier order → `canonical`, fractional/overflow revision → `positive
  safe integer`, empty message/changes, unknown change field, two-key edit op → `must
  have one operation`, `retain 0` → `positive safe integer`, empty insert, adjacent
  insert.
- `RepoValidateSuite.scala` / `ReplaySuite.scala`: `missing a@x`, `cyclic or incomplete
  patch history`, `tree paths conflict`, `does not consume old content`, `consumes
  beyond old content`, `delete of absent path: f`, `no-op change`.
- `EditScriptSuite.scala`: `positive safe integer`, `insert is empty`, `must have one
  operation`, `adjacent insert`, underconsumption/overconsumption.
- `VersionTextSuite.scala` / `ContributorIdSuite.scala`: every one of test 25's five bad
  contributor-id patterns and five bad-version patterns (`->0`, `->-1`,
  `9007199254740992`, both wrong-order forms), each with a `// test 25's exact input`
  comment.
- `ConfigSuite.scala` / `CommandsConfigSuite.scala`: local-blocks-global, config
  overwrite drops unknown fields, R100's exact line.
- `CommandsStatusSuite.scala`: empty-dir/`.snap`-invisibility and the UTF-8 byte path
  sort (test 25).
- `CommandsRevertSuite.scala`: an explicit R103 test (install-failure mid-mutation,
  checks both a working file and `repository.json` bytes) and the "known-version check
  precedes contributor-id" ordering test 14/19 both depend on.

Given that, this task's actual net-new work was: (1) a systematic **trigger-site
audit** — read every validator, not just its message, to confirm each pinned string
fires from the spec-correct step, not a coincidentally-matching later check; (2) a
**spec-vs-catalog completeness sweep**; (3) closing the one real gap I found in the
R103 coverage.

**Trigger-site audit (item 4 of the brief) — no bugs found.** Traced every check in
tests 15/23/27 against `Repo.validate`'s six-step order (`checkSortedAndDots` →
`checkContiguity` → `checkIncrements` → `checkBaseClosure` → `checkFrontierClosure` →
`checkReachable` → `checkAcyclic`, then `Replay`'s step-5 per-change validation):
duplicate-key/path/base64/frontier-order/fractional-revision/retain-0/two-key-op/
empty-insert/adjacent-insert all correctly fire at JSON-decode time (step 1 — none of
them needs a materialized base); `missing a@x` fires at `checkContiguity` (step 2);
the base-cycle fires at `checkAcyclic` (step 4); `does not consume old content`,
`consumes beyond old content`, `tree paths conflict`, `no-op change`, `delete of
absent path`, `text edit over binary`, and the non-canonical-token-insert case (test
27) all correctly require the materialized base and fire in `Replay.authoredResult`/
`applyChange` (step 5) — none of them could be checked earlier without a base tree.
`unreachable patch:` (test 23) fires after `checkFrontierClosure`, consistent with
R44. Test 27's "wrong dot" (base containing the patch's own new dot) hits
`checkIncrements`'s `DotMismatch` (the base's self-reference makes `base[author] = 1`
so `revision(1) != base[author]+1(2)`); the YAML only asserts a generic `snap: .+`
pattern here, so no wording is at risk either way. `diff <old> <new>`'s two version
operands are parsed old-then-new, matching every test-19/25 case (each fixture is
invalid on exactly one side). `snap config`'s overwrite path never reads the old file
(SPEC §7.2/§8 — confirmed in `CommandsConfig.scala`/`Store.writeConfig`), matching
test 25's "does not validate the old file" premise structurally, not just by message.

**Catalog completeness audit (item 2 of the brief).** Walked SPEC §2 (working-tree
rules — `SnapPath.parse`/`WorkTree.scan`), §3 (contributor id / version syntax —
`Ids.scala`/`Version.scala`), §4.1–§4.5 (schema/patch/change/token/validation rules —
`RepoCodec.scala`/`Patch.scala`/`EditScript.scala`/`TextTokens.scala`/`Repo.scala`/
`Replay.scala`), §8 (configuration — `ConfigCodec.scala`/`Config.scala`), and §10
(mutation/failure — `Store.scala`/`Materialize.scala`) against the current
implementation, condition by condition. Found no spec-stated condition that is
unreachable or unenforced. Cross-checked dead-code risk mechanically: every one of
`SnapError`'s 64 cases is constructed at least once outside `Errors.scala` itself (grep
sweep over `snap/scala/src/main/scala`) — no message in the catalog is unreachable.
(§6's replay/OT internals — namespace pre-pass, path-level winner rules, `merge`'s
warning reporting — are T15/T16/T17's scope per this task's brief and were not
re-audited beyond confirming their error cases, e.g. `OtBaseMismatch`, are reachable.)

**R103 gap closed.** The existing R103 coverage (`CommandsRevertSuite`,
`CommandsCommitSuite`, `StoreSuite`) checked mid-mutation install failures and
individual-file byte-identity, but nothing exercised the exact test-15/23 shape for a
*mutating* command: multiple pre-existing working files plus a **corrupt on-disk
`repository.json`**, through the full CLI pipeline. Added
`CommandsCommitSuite.scala` — "a corrupt repository.json blocks commit before touching
anything else (R103, tests 15/23's validation-before-mutation pattern)" — using test
15's own duplicate-key fixture verbatim, asserting the failure fires before
`commit`'s working-tree scan (repository load+validate is `commit`'s first step —
`CommandsCommit.scala`'s documented check order) and that two working files (one
nested) plus `repository.json` are all still exactly their pre-run bytes.

**No message/validator reword.** No existing catalog entry, message wording, or
validator call site needed correction — the audit found the implementation already
spec-correct at every checked point, so no "T14 additions" markers were needed in
`Errors.scala` and no other file's validation logic changed. Everything shipped is one
new unit test in `CommandsCommitSuite.scala`.

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
- [ ] Unit tests at each boundary: 254 vs 255-byte id; 4096 vs 4097-byte message
      (multibyte char straddling the limit); `9007199254740991` revision commit
      attempt → overflow error; id containing 0x7F rejected (D12).
- [ ] Exit-code discipline: an injected unexpected exception produces exit 2 and a
      `snap: `-prefixed stderr line; no expected-error path exits 2 (grep-level audit
      recorded in notes).
- [ ] Full provided suite green: `./snap/verify --lang scala` reports 28/28; lint gate
      passes; project test suite (`sbt test`) green.
- [ ] No suppressed scalafix rules without a justifying comment; determinism smells
      audit (`scala-antipatterns` skill) clean before the phase-5 review.

## Notes / decisions

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

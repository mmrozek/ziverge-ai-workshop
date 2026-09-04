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

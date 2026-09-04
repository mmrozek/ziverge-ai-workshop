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
- [ ] Provided tests `15-repository-validation`, `19-version-boundaries`,
      `23-strict-validation-matrix`, `25-config-version-path-boundaries`,
      `27-history-canonicality` all pass (filters: `repository-validation`,
      `version-boundaries`, `strict-validation`, `boundaries`, `canonicality`).
- [ ] Every failure case in those tests leaves working files and `repository.json`
      byte-identical (validation-before-mutation — R103); at least one unit test
      asserts this for a mutating command.
- [ ] Non-canonical base64 (`canonical base64`), `.snap` path in a patch
      (`path is invalid`), prefix collision (`tree paths conflict`), frontier order
      (`canonical`), `retain 0`/two-key op/empty insert messages all match the pinned
      strings verbatim (source: the YAML assertions, lifted into unit tests).
- [ ] Phase-2 gate: full suite shows phases 1–2's fifteen tests green
      (01–08, 14, 15, 19, 23–25, 27).

## Notes / decisions

# T07 — Replay ready-loop, materialization, validation steps 5–6 (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T05, T06
- **Risk:** **core** (the replay ordering keys are the central tie-break — formal
  pre-commit review, saved as `reviews/T07-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/Replay.scala`: patch selection for a version (R65, known-version predicate
R45), the ready-loop with all three ordering keys verbatim (R66, D14), integration of
one patch in the **linear** case (R69 case 1: path identical in `B` and `C` → apply the
authored change directly; concurrent cases are typed hooks until T16), per-change
base-tree checks completing validation §4.5 steps 5–6 (R51–R52, R59–R60), and memoized
`materialize` (D19). After this task a valid linear repository materializes to its tree.
DESIGN §5 (steps 1–3a exercised linearly), §7.

## Scope
`snap/scala/src/main/scala/snap/core/Replay.scala`, extension of `Repo.validate`,
tests in `snap/scala/src/test/scala/snap/core/`.

## Acceptance criteria
- [ ] Ready-loop ordering unit tests: patches ordered by snap order of result versions;
      directed tests for keys 2 (author `Utf8Order`) and 3 (numeric revision) using
      hand-built (invalid-in-practice) ties — all three keys live (D14).
- [ ] Validation rejects: change against wrong base (text create over present path,
      edit/delete of absent path — `delete of absent path: f`), no-op change
      (`no-op change`), non-canonical result tokens; a history whose ready set empties
      early fails with `cyclic or incomplete patch history` (R60).
- [ ] Property test: materializing a generated **linear** history is insensitive to the
      input order of the `patches` array (after validation's sort check is bypassed for
      generation, replay itself never reads input order).
- [ ] Known-version predicate: accepts `()` always and any per-contributor prefix
      closure; rejects vectors selecting a patch whose base is not contained
      (`unknown version` at the CLI layer).
- [ ] No wall-clock/env access; iteration over patches/paths only via sorted structures.

## Notes / decisions

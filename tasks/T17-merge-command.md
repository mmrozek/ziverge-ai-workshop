# T17 — `merge` command (3 SP)

- **Phase:** 3 — Merge & OT
- **Depends on:** T12, T16
- **Risk:** **core** (merge behavior — formal pre-commit review, saved as
  `reviews/T17-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap merge <repository>` for local-path operands (R5, R14, R38, R75–R76, R89; HTTP
operands land in T20): failure precedence per D11 (local validate → worktree scan →
remote load+validate → dot cross-check → replay → write); union patch sets with
structural dedupe per dot, different value → `patch collision: <id> revision <n>`;
join frontiers; **two replays** (pre-merge local + joined) and warning set-subtraction,
new warnings to stderr as `warning: auto-resolved <path>: <reason>` sorted, joined
version to stdout; install via T12's materializer; no patch created. No-op merge of
equal/contained history: succeeds, changes nothing, no warnings, prints unchanged
version. DESIGN §5 (step 4), §7, §8.

## Scope
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (merge), repository-operand
resolution (local part), tests in `snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [ ] Provided tests `09-merge-text`, `10-merge-conflicts`, `11-namespace-conflicts`,
      `17-concurrent-creates`, `21-version-algebra` pass (filters: `merge-text`,
      `merge-conflicts`, `namespace`, `concurrent-creates`, `version-algebra`).
- [ ] Direction independence asserted in a unit test: merging A→B and B→A yields
      byte-identical trees and identical joined frontiers (R76).
- [ ] Re-merge of the same repository: exit 0, unchanged version on stdout, empty
      stderr, `repository.json` byte-identical (no-op path).
- [ ] Warning subtraction unit test: a warning present in the pre-merge local replay is
      NOT re-printed by merge (R75) — construct a local history that already warns.
- [ ] Dirty tree → exact `snap: working tree is dirty` before the remote is even read
      (D11 order, observable via a nonexistent remote path + dirty tree).

## Notes / decisions

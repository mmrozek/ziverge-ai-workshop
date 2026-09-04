# T12 — Filesystem install & `revert` (2 SP)

- **Phase:** 2 — Diff, revert & validation matrices
- **Depends on:** T11
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/fs/Materialize.scala`: install a target tree over the working directory per R70 +
§10 mutation order (R103, R105–R106): full target computed first, working files updated
(remove blockers, create dirs, write files, prune empty dirs), `repository.json`
replaced atomically only afterwards. `snap revert <version>` (R88): config + clean tree
+ known target required; authors one patch `revert to <version>` diffing current →
target (message exempt from the 4096 limit — D16); prints the **new** version; equal
trees → exact `snap: target tree is already current`. DESIGN §7.

## Scope
`snap/scala/src/main/scala/snap/fs/Materialize.scala`,
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (revert), tests in
`snap/scala/src/test/scala/snap/{fs,cli}/`.

## Acceptance criteria
- [ ] Provided test `07-revert` passes (`--filter revert`), including file→directory
      and directory→file transitions.
- [ ] Mutation-order unit test: on a simulated working-file write failure, the old
      `repository.json` is intact (metadata replace never precedes tree update — R106).
- [ ] Dirty tree → exact `snap: working tree is dirty`; missing config → exact R100
      message; both checked before any filesystem change (R103).
- [ ] Revert never removes patches and never moves the frontier backward — unit test
      asserts patch count grows by one and the new frontier strictly dominates.

## Notes / decisions

# T21 — Cross-repo collision, failure precedence, portability (2 SP)

- **Phase:** 4 — HTTP & cross-repo
- **Depends on:** T20
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
The cross-repository safety net (R15, R38, R47, R86, R103–R104; D11): `diff --repo`
resolves `new` in the other repository without importing; every dot present in both
repositories is compared structurally and a mismatch fails as
`patch collision: <id> revision <n>` before any output/mutation (both `diff --repo` and
`merge`); failure-precedence order D11 enforced and unit-tested; portability bytes
(CRLF tokens, NUL → put, Unicode text) verified end-to-end.

## Scope
Cross-repo checks in `snap/scala/src/main/scala/snap/cli/Commands*.scala` and
`snap/scala/src/main/scala/snap/core/Repo.scala`, tests in
`snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [ ] Provided tests `16-dot-collision`, `20-dirty-merge`,
      `26-portability-and-failure-safety` pass (filters: `dot-collision`,
      `dirty-merge`, `portability`).
- [ ] D11 precedence unit tests: dirty tree + malformed remote → dirty error (the
      unobserved combination, pinned by our locked decision); malformed local repo
      reported before the remote is read.
- [ ] Every failing case leaves the local tree and `repository.json` byte-identical
      (no-mutation assertions in unit tests mirroring the YAML's exact tree listings).
- [ ] Structural patch identity: merging a remote that differs only in JSON
      whitespace/key order is a clean no-op (test 26's premise, also unit-tested).

## Notes / decisions

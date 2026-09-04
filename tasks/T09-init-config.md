# T09 — `init` + `config` (2 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T06, T08
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap init [path]` (R80–R81: default `.`, recursive creation, empty repository, prints
`()`, reinit and init-inside-repo errors) and
`snap config [--global] contributor.id <id>` (R82, R98–R100: validate id first, local
`.snap/config.json` vs `$HOME/.snapconfig.json`, no repo needed for `--global`,
overwrite without reading the old file, silent on success, D10 for missing repo).
Config **reading** with local-over-global precedence and strict validation of any file
actually read (R99) lands here too (needed by commit/revert later). DESIGN §8.

## Scope
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (init/config),
`snap/scala/src/main/scala/snap/fs/Store.scala` (config IO), tests in
`snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [ ] Provided tests `01-init`, `02-init-paths`, `03-configuration` pass
      (`./snap/verify --lang scala --filter init` and `--filter configuration`).
- [ ] Config precedence unit tests: local id wins without reading global (malformed
      global ignored); no local → global read and validated (`invalid JSON` on
      malformed); absent `HOME` → global unavailable, not an error by itself (R99;
      test 19's `HOME: null` case).
- [ ] `config contributor.id` rejects invalid ids with the test-pinned
      `invalid contributor id` message before writing anything; `config` without a
      repository and without `--global` → `snap: not a Snap repository` (D10).
- [ ] Newly written `repository.json`/config files use the canonical writer (D7) and
      atomic replace (R105).

## Notes / decisions

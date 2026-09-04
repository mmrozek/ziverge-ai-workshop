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
- [x] Provided tests `01-init`, `02-init-paths` pass in full.
- [ ] Provided test `03-configuration`: **not yet green — see Notes.** It passes
      steps 1–6 (global `config --global` write, local `init`, local `config`
      write) and then fails at step 7, the suite's first `commit` invocation,
      which is still T10's unimplemented stub (`snap: not implemented`) in this
      worktree. This is a real cross-task dependency gap (T09 depends only on
      T06+T08, not T10, yet the test exercises `commit`), not a defect in
      `init`/`config`/the config-read path this task built — every pre-`commit`
      assertion in test 03 passes. Expected to turn green once T10 lands
      `commit` in the same tree; flagging rather than implementing `commit`
      myself, since that is T10's declared scope.
- [x] Config precedence unit tests: local id wins without reading global (malformed
      global ignored); no local → global read and validated (`invalid JSON` on
      malformed); absent `HOME` → global unavailable, not an error by itself (R99;
      test 19's `HOME: null` case).
- [x] `config contributor.id` rejects invalid ids with the test-pinned
      `invalid contributor id` message before writing anything; `config` without a
      repository and without `--global` → `snap: not a Snap repository` (D10).
- [x] Newly written `repository.json`/config files use the canonical writer (D7) and
      atomic replace (R105).

## Notes / decisions

- **Scope deviation — files touched beyond the task's original `Scope` list**, all
  directed by the task assignment itself (recorded per the "record why" rule):
  - `snap/scala/src/main/scala/snap/json/ConfigCodec.scala` (new) — typed
    decode/encode of `{"contributor":{"id":"…"}}` (SPEC §8, R98), mirroring
    `RepoCodec`'s style; reuses the generic `UnknownField`/`MissingField`/
    `FieldWrongType` `SnapError` cases (only `ConfigNotObject` is new) and routes the
    id string through `ContributorId.parse` — the one validating factory whether the
    id came from a file or a `config` command-line operand.
  - `snap/scala/src/main/scala/snap/cli/Config.scala` (new) — the config **read**
    path: `localFile`/`globalFile` path helpers, `resolve` (R99 precedence: local
    read+validated first; if it yields an id, global is never read; else global,
    if `HOME` is set), and `requireContributorId` (R100, for T10/T12 to call).
    Placed in `snap/cli/` rather than `snap/fs/` because it needs `Env` (for
    `HOME`) and an already-discovered repo root — both CLI-level concepts; `Store`
    stays a pure path→bytes boundary.
  - `snap/scala/src/main/scala/snap/cli/Cli.scala` — **required** rework, called
    out explicitly by the task: `CommandHandler` gained a third parameter
    (`operands: List[String]`) because `init`/`config` are the first handlers that
    need their own arguments (T08's stub never did); `Command.needsRepoDiscovery`
    now takes the whole `ParsedCommand` instead of just `Command`, so `config
    --global`'s exemption (D10) can look at the actual operands before deciding
    whether to discover a repository. `defaultCommands` now wires `Init`/`Config`
    to their real handlers instead of the stub. Updated the two existing tests
    whose lambdas had the old 2-arg `CommandHandler` shape (`CliSuite.scala`,
    `MainSuite.scala`) and one `CliSuite` test whose premise ("init's stub still
    dispatches") no longer holds now that `init` is real (it now asserts the
    actual "no discovery, repository created" behavior instead of "not
    implemented").
  - `snap/scala/src/main/scala/snap/fs/Store.scala` — added `readConfig`/
    `writeConfig`/`createDirectories` plus the `ConfigFileName`/
    `GlobalConfigFileName` constants (this *is* in the task's declared scope:
    "config IO"). Also generalized `atomicWrite`/`stage`/`commit` with a
    defaulted `onError: Throwable => SnapError` parameter so config writes report
    `CannotWriteConfig` instead of being mislabeled `CannotWriteRepository`; the
    default preserves every existing call site and test (`StoreSuite.scala`
    calls `stage`/`commit` positionally with two args, unchanged).
  - `snap/scala/src/main/scala/snap/cli/CommandsInit.scala`,
    `CommandsConfig.scala` (new) — the two command handlers, per the task's
    `Commands*.scala` naming.

- **`SnapError`/`Messages` additions (T09 block).** Appended at the very end of the
  enum and of `Messages`, in a `// T09 additions` comment block, per the
  cross-worktree merge instruction (T07 runs in a parallel worktree and also
  extends `Errors.scala`). New cases: `RepositoryAlreadyExists(path)`,
  `CannotInitializeInsideRepository(existingRoot)`, `CannotCreateDirectory(detail)`,
  `ConfigNotObject`, `ConfigNotUtf8`, `CannotReadConfig(detail)`,
  `CannotWriteConfig(detail)`, `ContributorIdRequired`, `GlobalConfigUnavailable`.
  One **existing** rendering function was also changed (not appended, since it's a
  behavioral fix to already-shipped code, not a new entry):
  `Messages.contributorId` now wraps every `IdError` reason with an
  `invalid contributor id: ` prefix. This is required by the contract, not a
  stylistic choice — test 03 asserts `stderr_contains: invalid contributor id` and
  test 25 pins the exact pattern `^snap: invalid contributor id: .+\n$` for both
  `config`'s own id validation and a `commit` that reads an invalid id out of a
  config file. Verified this doesn't regress any existing assertion: the only
  other place this message is checked is `RepoCodecSuite`'s
  `.contains("contributor id")`, which still holds since the wrapped text still
  contains that substring.

- **`init` grammar (coarse, T13 owns the exhaustive matrix).** `path` operand
  defaults to `.`; exactly zero or one plain operand is accepted. A single
  `--`-shaped operand (e.g. `init --unknown`) is rejected as an unknown option
  rather than accepted as a bizarre-but-legal directory name — `init` has zero
  documented options, so R79's "unknown options... are errors" is read to apply to
  any `--`-prefixed token in this position. This is not tested by 01/02/03 but
  happens to already satisfy test 24's `init a b` / `init --unknown` cases (T13's
  job to verify exhaustively) at no extra cost.

- **`init`'s nested/reinit check walks up from the target, read-only, before any
  directory is created** (`CommandsInit.checkNotInsideRepository`): the first
  `.snap` found decides the diagnostic — at the target itself it's
  `RepositoryAlreadyExists` (reinit), at any ancestor it's
  `CannotInitializeInsideRepository` (nesting). Because this check precedes all
  filesystem mutation, both failure modes leave the filesystem completely
  untouched (test 02's `path_not_exists: repo/child/.snap` assertion), matching
  R103's "validation failures never mutate" spirit even though `init` sits outside
  R103's literal `merge`/`revert` scope.

- **`config`'s grammar (coarse, T13 owns the exhaustive matrix).** Exactly two
  shapes accepted: `contributor.id <id>` and `--global contributor.id <id>`
  (SPEC §7.2's exact positional shape); everything else is `InvalidCommand`. This
  already satisfies test 24's `--global` in the wrong position / duplicated
  `--global` cases as a side effect of the literal-shape match, not a separate
  special case.

- **`config`'s repo-discovery exemption (D10) is decided positionally** — first
  operand exactly `"--global"` skips discovery; anything else (including a
  malformed grammar without `--global`) discovers normally, so a bad-grammar
  `config` call outside a repository still reports `snap: not a Snap repository`
  before grammar is even checked, matching D10 and the task's acceptance
  criterion literally.

- **`--global` write with no `HOME`: chosen wording (untested).** R99 says a
  missing `HOME` makes global configuration "unavailable" for **reads** (not an
  error by itself). A `--global` **write** has nowhere to go without `HOME`, so it
  must fail; no test pins the exact wording, so I picked
  `snap: global configuration is unavailable: HOME is not set` (new
  `SnapError.GlobalConfigUnavailable`) — consistent with R99's "unavailable"
  terminology, applied to the one case (a write) where unavailability can't be
  silently absorbed into "no value".

- **Provided-test filters used:** `./snap/verify --lang scala --filter 01-init`,
  `--filter 02-init-paths`, `--filter 03-configuration` (the task's own suggested
  `--filter init`/`--filter configuration` substrings would each match multiple
  files were more `*init*`/`*config*`-named tests to land later, so the exact
  numeric-prefix filters were used instead for an unambiguous, reproducible
  report).

- **Verification (this worktree, clean run):**
  - `sbt clean test`: **313 total, 0 failed** (257 pre-T09, one of which
    (`CliSuite`'s "init never requires a pre-existing repository" test) was
    rewritten in place to match real `init` behavior instead of the T08 stub;
    +56 new tests across `ConfigCodecSuite` (12), `StoreConfigSuite` (13),
    `ConfigSuite` (11), `CommandsInitSuite` (10), `CommandsConfigSuite` (10)).
  - `sbt scalafmtCheckAll` and `sbt "scalafixAll --check"`: both clean, no
    findings, no `scalafix:ok` suppressions needed.
  - `./snap/verify --lang scala --filter 01-init`: **1 passed**.
  - `./snap/verify --lang scala --filter 02-init-paths`: **1 passed**.
  - `./snap/verify --lang scala --filter 03-configuration`: **1 failed** — fails
    at step 7 (the suite's first `commit`), `snap: not implemented`; steps 1–6
    (global `config --global`, `init local`, local `config`) all pass. Left
    failing rather than special-cased or weakened, per the hard rules — this will
    turn green once T10 lands `commit` in this same tree (T10 depends on T09,
    confirming the ordering).

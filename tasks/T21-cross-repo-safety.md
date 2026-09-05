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
- [x] Provided tests `16-dot-collision`, `20-dirty-merge`,
      `26-portability-and-failure-safety` pass (filters: `dot-collision`,
      `dirty-merge`, `portability`).
- [x] D11 precedence unit tests: dirty tree + malformed remote → dirty error (the
      unobserved combination, pinned by our locked decision); malformed local repo
      reported before the remote is read.
- [x] Every failing case leaves the local tree and `repository.json` byte-identical
      (no-mutation assertions in unit tests mirroring the YAML's exact tree listings).
- [x] Structural patch identity: merging a remote that differs only in JSON
      whitespace/key order is a clean no-op (test 26's premise, also unit-tested).

## Notes / decisions

No genuine defect found — the D11 `for`-comprehension in `CommandsMerge` and the
mirrored branch in `CommandsDiff` already implement the locked precedence
(local parse+validate → working-tree scan → remote load+validate → dot cross-check →
replay → write) exactly as documented; this task's hardening confirmed rather than
fixed it. All new coverage lives in one file,
`snap/scala/src/test/scala/snap/cli/CrossRepoSafetySuite.scala` (15 tests), split into
the four groups the acceptance criteria name:

1. **D11 precedence at the unobserved combinations.** Dirty tree + malformed remote
   (local-path AND HTTP forms) → the pinned `snap: working tree is dirty` line, with
   the HTTP case additionally asserting **zero GETs** ever reach the remote (a
   local-path remote can't demonstrate "never read at all" on its own — reading its
   bytes has no observable side effect from the test's vantage point — so I added a
   minimal request-counting HTTP stub, `CountingStub`, modeled on
   `snap.http.ClientSuite`'s `Stub`). Also added the unsupported-working-tree-entry
   analogue (test 20's *other* scan-step case) paired with a malformed remote, for the
   same "unobserved combination" reason. Malformed LOCAL repository before the remote
   is read: covered for `merge` (local-path AND HTTP) and for `diff --repo` (HTTP),
   each asserting the exact pinned local error (`snap: repository has unknown field:
   bad\n`) AND zero remote GETs.
2. **No-mutation on every failing path.** Four tests capture the complete working-tree
   file map (`.snap/` included) immediately after `init` and re-assert byte-exact
   equality after each failing `merge`/`diff --repo` (local-path and HTTP remotes),
   mirroring test 26's `tree_equals`/`json_equals` shape directly as a unit assertion;
   the HTTP cases also pin "exactly one GET" (test 26's `http_requests_equal` shape).
3. **Structural patch identity.** Pinned at two layers: (a) decoding test 26's own
   "duplicate" JSON pair (2-space canonical vs. single-line reversed-key-order) through
   `JsonParser` + `RepoCodec` and asserting the parsed `Repository` values are `==`,
   plus `CommandsMerge.unionPatches` on the two patch vectors returning the clean
   union (not a collision); (b) the same pair driven through a real `Cli.run("merge",
   ...)`, asserting the file content is unchanged and the merged repository holds
   exactly one patch.
4. **Portability bytes end to end.** Built a `portableRemote()` fixture that performs a
   REAL `commit` (real files on disk, not hand-built `Patch` values) with test 26's
   three files (CRLF-bearing `crlf.txt` with no trailing LF, NUL-bearing `nul.bin`,
   non-ASCII `unicode.txt`), then: (a) pins the committed `Change` values directly
   (`Change.Text`/`Change.Put` incl. the exact edit-script tokens and put bytes) —
   proof the NUL byte forces `put` classification and text tokenization preserves the
   CR byte; (b) reproduces test 26's `diff --repo` byte-exact stdout as a standalone
   unit assertion; (c) **new coverage beyond test 26**: a real `merge` (not just
   `diff`) of the same fixture, asserting the *materialized working-tree files on
   disk* are byte-exact after the full commit → union → replay → install pipeline —
   test 26 only ever exercises these bytes through the diff renderer, never through
   actual installation.

Ambiguity/reading note: none escalated. `corruptRepository()` uses an unknown
top-level field (R43) as the one "malformed remote" shape throughout, since the exact
flavor of malformation is incidental to what this task is proving (precedence/
no-mutation), not a new semantics question — the provided suite's own tests use a
mix of shapes for the same reason.

One incidental compile note (not a scope deviation): `IArray[Byte].toArray` is
deprecated in this Scala 3.3.8 stdlib in favor of
`IArray.genericWrapArray(x).toArray` — used the latter (already the pattern
`snap.core.Replay` uses at several call sites) rather than reaching for `Patch`/
`Tree`'s hand-rolled byte-equality helpers, which are `private` to `snap.core`.

No handler-typed code touched, and no production code changed at all — this task is
test-only, so it carries zero surface for the concurrent T22 `CommandHandler` →
`CommandOutput` migration to reconcile. All new tests drive `Cli.run(env, args)` +
`TestEnv` exclusively, per the task's concurrency warning.

### Integration verification onto `main` (post-T22), 2026-09-05

Confirmed cherry-pick onto `main` (after T22's `CommandOutput`/`Cli.emit` presentation-layer
landing) is unaffected, as this file's own framing predicted. Checked specifically:

- `TestEnv.apply` (`snap/scala/src/test/scala/snap/cli/TestEnv.scala`) defaults `tty = Tty.Stub`
  (`isStdoutTty`/`isStderrTty` both `false`) and an empty `envMap`, so `Cli.run`'s
  `Presentation.select` (`snap/scala/src/main/scala/snap/cli/Presentation.scala`) takes the
  `None`/no-`NO_COLOR` branch down to `Presenters(Plain, Plain)` for every test in this suite —
  plain mode is genuinely *selected*, not merely defaulted around a bug. `Presentation.Plain`
  prints `error`/`warning` as the literal `snap: <detail>\n` / `warning: <detail>\n` and `result`
  as `text` verbatim, ignoring `kind` entirely — so this suite's pinned stderr line
  (`snap: repository has unknown field: bad\n`), pinned stdout lines
  (`(remote@x->1)\n`, `(same@x->1)\n`), and the byte-exact `diff --repo` rendering all still hold
  character-for-character through the new `CommandOutput` → `Cli.emit` → `Presenters` path. No
  styling leak into plain mode observed — this is a real T22 conformance check, not an assumption.
- `CountingStub` (this file): re-ran under `sbt -batch test` and confirmed via `ps`/`lsof` after
  the run that no Java listener/port remains bound and no stray thread survives — `stop()`'s
  `httpServer.stop(0)` + `executor.shutdownNow()` in a `finally` (via `withStub`) cleans up on
  every path, including assertion failure. The "zero GETs" and "exactly one GET" assertions both
  passed.
- Gates run from repo root: `sbt -batch clean assembly` (ok); `sbt -batch test` → **684** total, 0
  failed (669 pre-T21 + this suite's 15); `sbt -batch scalafmtCheckAll` (ok — one transient failure
  on an unrelated, untracked, since-vanished file `ReplayPerfProbeSuite.scala` was observed on the
  first run, traced to a concurrent T23 worktree agent writing into the shared checkout, not to
  anything in this task; re-run was clean); `sbt -batch "scalafixAll --check"` (ok); provided suite
  via `./snap/verify --lang scala` under Java 17 → **28/28**, unchanged from post-T22. No files
  edited during this verification pass.

# T08 — CLI dispatch, repo discovery, plain output, exit codes (2 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T02
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/cli/`: `Env` (effect boundary — cwd, env map, UTF-8 stdout/stderr per D22, TTY
trait stub), command-line grammar core (R79) with dispatch to command stubs,
repository discovery walking cwd→root (R77), plain presentation (results→stdout,
`snap: <detail>` errors→stderr), exit-code mapping (0/1/2 — R107, D4) with the
top-level catch-all, `--version` printing `snap 1.0.0` (R91, D6), and `SNAP_COLOR`
value validation before command execution (R95; terminal mode itself is T22 — until
then `always` selects a stub renderer that will be replaced, wired so execution is
identical). `Main.scala` becomes thin (gotcha 9). DESIGN §2, §8.

## Scope
`snap/scala/src/main/scala/snap/cli/{Env,Cli,Presentation}.scala`,
`snap/scala/src/main/scala/Main.scala`, tests in
`snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [x] `snap --version` prints exactly `snap 1.0.0\n`, exit 0, without touching the
      filesystem (no repository discovery — gotcha 9).
- [x] Unknown command / extra operands → exit 1, exactly
      `snap: invalid command or arguments` on stderr; command needing a repo outside
      one → exactly `snap: not a Snap repository` (test 14 wording).
- [x] Discovery finds `.snap/` from a nested cwd (test 19's premise) and stops at the
      filesystem root; unexpected exceptions map to exit 2 with a `snap: `-prefixed
      line on stderr.
- [x] `SNAP_COLOR=sometimes` → plain `snap: SNAP_COLOR must be auto, always, or never`,
      exit 1, before any command logic; env is read only inside `Env` construction —
      no `sys.env`/`System.getenv` anywhere else (negative constraint).

## Notes / decisions

- **Layout:** `snap/scala/src/main/scala/snap/cli/{Env,Presentation,Cli}.scala` plus
  the thin `Main.scala` at the workspace root (unchanged location — `mainClass` and
  `snap/run`'s probe expect it there). `Errors.scala` (`snap/core`) gained four cases:
  `InvalidCommand`, `NotASnapRepository`, `InvalidSnapColor`, `NotImplemented`, plus
  `Messages.internalError` for the exit-2 catch-all text — no other file touched.
- **Dispatch table, not a hardcoded match:** `Cli.defaultCommands: Map[Command,
  CommandHandler]` maps every non-`Version` command to a `(Env, Option[Path]) =>
  Either[SnapError, String]` stub returning `NotImplemented`. `Cli.run` takes this map
  as a defaulted parameter — later tasks (T09 `init`/`config`, T10
  `status`/`log`/`commit`, T11 `diff`, T12 `revert`, T17 `merge`, T19 `--serve`) replace
  one entry at a time without touching grammar, discovery, or exit-code logic. This is
  also the seam the exit-2 unit test uses (an injected handler that throws).
- **`config`'s `--global` exemption is deferred to T09.** D10 says `config` without
  `--global` needs a repo, but T08 doesn't parse flags — it only recognizes the command
  surface (R79's exhaustive per-command grammar is T13's job per this task's own
  scope note). Coarse choice recorded here: `Command.needsRepoDiscovery` requires a
  repo for every command except `init` and `--version`, so `config` is treated as
  needing one even under `--global` until T09 implements it properly. Not a core-risk
  ambiguity (no clock/merge/tie-break involved) — picked the simplest reading and
  moved on per the ambiguity policy.
- **`init` never triggers discovery.** Spec/DESIGN don't say this explicitly, but R80/
  R81 make it obvious: `init`'s job is to create a repository, and R77 discovery exists
  to find one for commands that *operate* on an existing repo. Treating `init` as
  discovery-exempt (alongside `--version`) is the only spec-consistent reading; a unit
  test pins it (nonexistent cwd still reaches the "not implemented" stub rather than
  failing with "not a Snap repository").
- **Version payload has no parameter.** `SnapError.InvalidSnapColor` and
  `SnapError.NotImplemented` carry no data — the pinned messages never echo the
  offending value or a command name, so there was nothing to preserve (illegal states
  unrepresentable / no dead fields).
- **Main.run vs Main.main:** `Main.main` is a one-line `System.exit(run(...))`; `Main.run`
  holds the actual try/catch and is what tests call directly (never invokes
  `System.exit`, so it can't kill the test JVM). This is the only `try/catch` in the
  codebase, matching D4's "top-level catch-all in Main only."
- **Test-only regression guard:** `EnvIsolationSuite` scans `src/main/scala` (comments
  stripped) for `sys.env`/`System.getenv` outside `Env.scala`, enforcing the negative
  constraint mechanically rather than by convention alone.
- **`MainSuite` lives in the default package** (`snap/scala/src/test/scala/MainSuite.scala`,
  no `package` line), not under `snap.cli`: Scala's empty/default package can only be
  referenced from other code that is itself in the empty package, and `Main` must stay
  in the default package to match `build.sbt`'s `mainClass := Some("Main")`.
- Verification: `sbt test` — 127 total, 0 failed (20 new: 16 `CliSuite`, 3 `MainSuite`,
  1 `EnvIsolationSuite`). `sbt scalafmtAll scalafixAll` then `sbt scalafmtCheckAll
  "scalafixAll --check"` — both clean. Manual smoke via `./snap/run --lang scala
  {--version,unknown,status,commit}` and a nested-cwd `.snap` directory all matched
  expected wording/exit codes.

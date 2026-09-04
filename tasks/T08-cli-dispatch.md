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
- [ ] `snap --version` prints exactly `snap 1.0.0\n`, exit 0, without touching the
      filesystem (no repository discovery — gotcha 9).
- [ ] Unknown command / extra operands → exit 1, exactly
      `snap: invalid command or arguments` on stderr; command needing a repo outside
      one → exactly `snap: not a Snap repository` (test 14 wording).
- [ ] Discovery finds `.snap/` from a nested cwd (test 19's premise) and stops at the
      filesystem root; unexpected exceptions map to exit 2 with a `snap: `-prefixed
      line on stderr.
- [ ] `SNAP_COLOR=sometimes` → plain `snap: SNAP_COLOR must be auto, always, or never`,
      exit 1, before any command logic; env is read only inside `Env` construction —
      no `sys.env`/`System.getenv` anywhere else (negative constraint).

## Notes / decisions

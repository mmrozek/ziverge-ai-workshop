# T22 — Terminal renderer, SNAP_COLOR/NO_COLOR, TTY (3 SP)

- **Phase:** 5 — Terminal presentation & holdout hardening
- **Depends on:** T13, T17, T19
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
The complete terminal presentation (R92–R97, R108; DESIGN §8, D20, gotcha 8): per-stream
renderer selection (`SNAP_COLOR` unset/`auto`/`always`/`never` × `NO_COLOR` × TTY-ness),
exact ANSI layouts for init/commit/revert/merge success lines, status (U+2212 for
deleted, double space in header), log (blank line between entries), diff line styling
(incl. dim `\ ` and yellow `Binary files `), `--version`, warning `⚠`/error `✗`;
`config` stays silent; the `--serve` URL stays plain; presentation provably changes no
execution/effects/exit codes (R92). Real TTY probe per D20 behind the `Tty` trait.

## Scope
`snap/scala/src/main/scala/snap/cli/Presentation.scala` (+ `Env` TTY impl), tests in
`snap/scala/src/test/scala/snap/cli/`.

## Acceptance criteria
- [ ] Provided test `28-terminal-presentation` passes (`--filter terminal`), byte-exact.
- [ ] R108 unit tests: the full selection matrix — {unset, auto, always, never,
      invalid} × {NO_COLOR present (incl. empty), absent} × {stdout TTY, not} ×
      {stderr TTY, not} — against injected fake `Tty` values, asserting per-stream mode
      independently.
- [ ] A same-command pair test (terminal vs plain) asserting identical exit code,
      identical repository/filesystem effects, and identical warning order (R92).
- [ ] Empty plain output stays empty in terminal mode (no stray SGR/reset bytes); the
      probe subprocess is spawned only when mode is auto-and-NO_COLOR-absent (negative
      constraint — assert no probe under `NO_COLOR=1`).

## Notes / decisions

## Pre-implementation pointers
- From `reviews/T17-review.md` nit 3 (deferred here at triage): there are **two**
  `Presentation` call sites, not one. `Cli.emit` is the usual seam, but
  `snap/cli/CommandsMerge.scala:80` reaches `Presentation.Plain` directly to print the
  `warning: auto-resolved …` lines. No behavioral difference today — every site is
  hardcoded to `Plain` until this task introduces selection — but T22 must update both
  and decide how a command handler (whose return type currently carries only one stdout
  string) obtains the correctly selected **stderr** presentation for warnings. Routing
  merge's warnings back through `Cli.emit` is the obvious direction; whichever way it
  goes, R92 requires the warning order to be identical in plain and terminal mode.
- T19 adds a **third** presentation call site: `CommandsServe` prints the `--serve` ready
  line through `Presentation.Plain` directly. Per this task's own scope line that URL
  **stays plain** in every mode, so the correct outcome is a deliberate plain rendering,
  not an oversight to be "fixed" by routing it through the selected renderer. Enumerate
  all three sites (`Cli.emit`, `CommandsMerge`, `CommandsServe`) before restructuring, and
  state in the notes which of them is intentionally exempt.

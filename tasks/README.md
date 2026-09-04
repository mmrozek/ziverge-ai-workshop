# tasks/ — task tracking

`TASKS.md` is the live status board (one line per task) and the **only** place task
status lives. Each task also gets its own file named `T<nn>-short-slug.md` holding its
definition — the split keeps implementer-agent context small (one task file, not the
whole plan) and parallel work conflict-free. Task ids are **flat and global**
(`T01`, `T02`, …) — phases group tasks in the plan, but ids never change if a task
moves between phases.

A task is the unit of commit: one task → one commit, subject `T<nn>: <summary>`.
Follow-up fixes to a completed task commit as `T<nn>-fix: <summary>`.

**Never rewrite a completed task's entry.** If done work turns out wrong or incomplete,
file a `T<nn>-fix` commit or a brand-new task — history stays honest.

## Task file template

```markdown
# T<nn> — <title> (<1|2|3|5> SP)

- **Phase:** N — <phase name>
- **Depends on:** <task ids or "—">
- **Risk:** normal | core (touches clock compare / merge / tie-break logic —
  gets a formal pre-commit review, saved as `reviews/T<nn>-review.md`)

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
One or two sentences: what exists after this task that didn't before.
Cite the DESIGN.md sections and requirement ids (R…) it implements.

## Scope
Files expected to be created or changed. Anything outside this list needs a note.

## Acceptance criteria
- [ ] Falsifiable, binary statements — name the provided tests that must pass.
- [ ] Include negative constraints where they matter
      (e.g. "no wall-clock access in this module").

## Notes / decisions
Filled during implementation: surprises, decisions made, deviations from plan.
```

Sizing: story points 1/2/3/5. Anything estimated above 5 gets split before it enters
the plan.

## Status board format (`TASKS.md`)

```markdown
| Task | Phase | Title | SP | Status | Depends | Commit |
|---|---|---|---|---|---|---|
| T01 | 1 | ... | 2 | done | — | abc1234 |
| T03 | 1 | ... | 3 | todo | T01,T02 | |
```

`Depends` lists task ids (comma-separated) or `—`; it must stay in sync with the task
files and PLAN.md's dependency graph. `scripts/status-board.sh` renders this table
live (colored graph, dependency arrows, change notifications).

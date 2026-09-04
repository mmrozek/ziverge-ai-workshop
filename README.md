# snap — vector-clock version control system

A version control system built on vector clocks with **deterministic automatic merging**.
Developed spec-first: `snap/SPEC.md` and the language-neutral YAML suite in
`snap/tests/` are the contract; work proceeds in reviewed phases split into small
tasks. The implementation is Scala, in `snap/scala/`, verified with
`./snap/verify --lang scala`.

## Status

**Spec analysis / planning.** The contract has arrived in `snap/`; implementation
starts after the plan in `docs/plan/PLAN.md` is approved.

## Repository layout

| Path | Purpose |
|---|---|
| `snap/` | The project: `SPEC.md` + `tests/` + harness (source of truth — never edited), `ts/` scaffold (read-only), `scala/` our implementation |
| `docs/plan/` | `SPEC-NOTES.md` → `DESIGN.md` → `PLAN.md` — written after spec analysis, approved before development |
| `tasks/` | One file per task (`T<nn>-slug.md`); status tracked in `tasks/TASKS.md` |
| `reviews/` | Post-phase review reports (`phase-N-review.md`) |
| `.claude/agents/` | Worker agent definitions (implementer, reviewer, spec analyst) |
| `.claude/skills/` | Workflow skills (planning, task loop, phase review) |
| `src/`, `tests/` | Implementation and project-authored tests (created once language is known) |

## Workflow

1. **Spec analysis → design → plan.** From `snap/SPEC.md` + `snap/tests/`, spec analysis
   (`SPEC-NOTES.md`), architecture (`DESIGN.md`), and the phased task breakdown
   (`PLAN.md`) are written and presented for approval before any code.
2. **Tasks.** Each task is implemented, tested, and committed individually
   (one commit per task, `T<nn>: <summary>`).
3. **Phase review.** After each phase, a reviewer agent audits the phase's diff against
   the spec; the report is saved to `reviews/`, every finding is triaged, and accepted
   fixes land before the next phase. A final post-completion audit with independent
   reviewers runs before the project is declared done.

See `CLAUDE.md` for the full working rules.

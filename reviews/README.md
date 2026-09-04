# reviews/ — review reports

One report per completed phase: `phase-N-review.md`. If a phase needs a re-review
after fixes, append a "Re-review" section to the same file rather than creating a new one.

Risky tasks (`Risk: core` — clock compare / merge / tie-break logic) additionally get a
formal **pre-commit** review: `T<nn>-review.md`, produced by the reviewer agent in task
mode on the working-tree diff and committed together with the task itself. The phase
reviewer reads these when auditing the phase.

Reviews are produced by the `reviewer` agent (see `.claude/agents/reviewer.md`) using
the `phase-review` skill, which defines the report template and checklist. Each report
has two parts: the reviewer's findings (severity-rated, file:line, untouched after
delivery) and the orchestrator's triage — a per-finding decision
(accept-now / defer-to-task / accept-as-doc / reject) with reasoning. A phase is closed
only when the report exists here, every finding is triaged, and accepted fixes are
committed (`review(phase-N): …`).

After the full suite is green, a `post-completion-review.md` is produced by independent
reviewers (spec-vs-tests coverage, code quality, runtime probing) before the project is
declared done.

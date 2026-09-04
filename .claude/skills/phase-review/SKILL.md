---
name: phase-review
description: Run the review gate after a snap phase completes — launch the reviewer agent, triage its findings, fix what's accepted, and close the phase. Also defines the final post-completion audit. Use when a phase's last task is done, or when the user asks to review a phase.
---

# phase-review

Precondition: every task in the phase is `done` in `tasks/TASKS.md` and the full
provided suite has been run (record the counts). The review gate has two roles:
the **reviewer** (agent, writes findings, never fixes) and the **orchestrator**
(you — triages, fixes, closes).

## Steps

0. Update `tasks/CURRENT.md` at each review stage ("phase N: review running" /
   "phase N: triaging findings" / "phase N: applying review fixes") so the status
   board reflects the gate, and track reviewer agents in `tasks/AGENTS-STATUS.md`
   (add on launch, remove on completion) like any other subagent.
1. **Launch the `reviewer` agent** with the phase number and commit range
   (first task commit of the phase → HEAD). It writes `reviews/phase-N-review.md`
   using the template below. Run it on the strong model (no downgrade — reviews are
   crucial per CLAUDE.md's model policy). In parallel, run the built-in `code-review`
   skill on the same range as an independent second opinion for generic correctness
   bugs, and the `scala-antipatterns` skill over the phase's code (in gate mode: no
   fix prompt); fold surviving findings from both into the report's triage,
   attributed to their source.
2. **Triage every finding** — append an "Orchestrator triage" section to the report.
   Per finding: **Decision** (accept-now | defer-to-task | accept-as-doc | reject),
   **Action**, **Reasoning**. Rules:
   - Correctness or determinism findings in merged code: accept-now unless provably wrong.
   - Defer-to-task requires actually creating the task file (`T<nn>`, status `todo`).
   - Reject requires a concrete counter-argument, not "seems fine".
   - Never edit the reviewer's findings text — triage goes below it.
3. **Fix accepted findings**, re-run the full suite, commit as
   `review(phase-N): <findings summary>`. If fixes were substantial, ask the reviewer
   agent (via SendMessage) for a re-check; append its verdict as "Re-review".
4. **Close:** update the report's "Status after review", commit the report
   (`docs(phase-N): review report`), and give the user a compact summary — verdict,
   findings by severity, what was fixed/deferred/rejected, documented ambiguity
   decisions made during the phase, suite counts. The next phase starts automatically
   once the summary is delivered — no explicit user go needed (their call, 2026-09-04);
   they interrupt if something bothers them.

## Report template (`reviews/phase-N-review.md`)

```markdown
# Phase N review — <phase name>

- **Date / range:** <date>, <sha..sha>
- **Tasks closed:** T.. (sha), T.. (sha)
- **Suites:** provided X/Y passing, project A/B passing (commands used)

## Findings
Sections: Spec compliance · Correctness & determinism · Test coverage ·
Design drift · Pitfalls for future phases.
Each finding: `#<n> [Critical|Major|Minor|Nit] file:line — claim` + concrete failure
scenario + CONFIRMED/PLAUSIBLE. "No findings" + evidence checked is a valid section.

## Orchestrator triage
#<n>: Decision / Action / Reasoning   (added by orchestrator, findings above untouched)

## Follow-ups created
T<nn> — <title> (from #<n>)

## Status after review
<closed / closed-with-deferrals / rework>; suite counts after fixes.
```

## Post-completion audit (after the full provided suite is green)

Before declaring the project done, launch **three independent reviewers in parallel**
(separate agents, fresh context, no shared conclusions, strong model — no downgrade),
each writing its own section of `reviews/post-completion-review.md`:

1. **Spec-vs-tests:** walk the spec requirement-by-requirement; find behavior the spec
   demands that no test exercises, and verify it manually.
2. **Code quality:** full-codebase read for invariant violations, dead code, error
   handling, nondeterminism smells.
3. **Runtime probing:** actually drive the built system on adversarial scenarios —
   concurrent edits from many replicas, merge orderings, tie-break edges, empty/degenerate
   inputs — checking determinism by running each scenario repeatedly and in permuted orders.

Triage and fix exactly as with phase reviews. Passing tests are necessary, not
sufficient — this audit exists because green suites still hide real bugs.

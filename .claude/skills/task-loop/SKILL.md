---
name: task-loop
description: Execute snap tasks from the approved plan — pick the next task, implement (directly or via the implementer agent), verify tests, commit, update the board. Use for "continue", "do the next task", "work through phase N".
---

# task-loop

Precondition: `docs/plan/PLAN.md` is approved. If it isn't, stop and use `project-plan`.

## Loop (repeat per task until the phase's tasks are done or blocked)

0. **Board pane (once per session):** if the status-board pane isn't already running,
   open it per CLAUDE.md's "Progress board" instructions. Throughout the loop, write a
   one-line update to `tasks/CURRENT.md` at every transition (picked / implementing /
   verifying / committing / blocked) and update `tasks/TASKS.md` statuses **immediately
   on transition** (the board notifies the user off those changes). When spawning an
   implementer, add its line to `tasks/AGENTS-STATUS.md` (`name | role | doing | since`);
   remove the line when it finishes.
1. **Pick:** next `todo` task in plan order whose dependencies are `done`
   (check `tasks/TASKS.md`). If the user named a task, use that one.
2. **Implement:** for a self-contained task, launch the `implementer` agent with the
   task id; implement directly only when the task needs conversation-level context the
   agent lacks. Pick the model per CLAUDE.md's model policy: `model: "sonnet"` for 1–2 SP
   mechanical tasks; inherit (no override) for anything touching clock compare/merge/
   tie-break logic or ≥3 SP. Parallel implementers only for tasks the plan flags
   parallel-safe (disjoint files) — spawn each with `isolation: "worktree"` (or a herdr
   pane), never the same tree; integrate back into `main` in plan order.
3. **Integrate (don't re-execute — user, 2026-09-04):** verification is the
   implementer's job — it runs the acceptance tests and the lint gate and reports exact
   results; the orchestrator does NOT re-run them. The orchestrator's check is a **diff
   skim only**: scope creep, contract modifications (snap/SPEC.md, snap/tests/,
   harness — forbidden), nondeterminism smells (time, randomness, unordered iteration
   in domain logic), and unjustified `scalafix:ok` suppressions. Independent
   re-execution belongs to the reviewer (risky-task pre-commit reviews and phase
   reviews). Exception: worktree-isolated tasks — after applying the diff back onto
   `main` the implementer's run no longer covers the integrated state, so have the
   implementer (or a fresh one) verify on `main`; still not the orchestrator.
4. **Risky-task review (Risk: core only — clock compare / merge / tie-break):** before
   committing, launch the `reviewer` agent in task mode on the working-tree diff
   (strong model, no downgrade). It writes `reviews/T<nn>-review.md`. Triage its
   findings exactly like a phase review (accept/deferred/reject recorded in the
   report); fix accepted findings and re-run tests + lint before moving on. The task's
   code and its review report go into the same commit. Normal-risk tasks skip this step.
5. **Commit:** subject `T<nn>: <summary>`; body cites the DESIGN §/requirement ids and
   the provided tests turned green. The task's code, tests, and updated task file go in
   one commit. Set the task `done` in `TASKS.md` with the commit hash (follow-up `chore:`
   commit for the hash). Fixes to an already-done task: `T<nn>-fix: <summary>`.
6. **Blocked?** If the implementer stopped on a core-semantics ambiguity (clock
   comparison, merge, tie-breaks) or a suspect provided test, bring the question to the
   user and work a non-dependent task meanwhile; don't route around it. Minor-reading
   decisions the implementer documented are not blocks — verify they're recorded in the
   task file and move on (they get surfaced at the phase review).

## Phase boundary

When the last task of a phase is done, switch to the `phase-review` skill; the
**reviewer** runs the full provided suite and the lint gate as part of the phase review
(its procedure step 2) — the orchestrator doesn't. Do not start the next phase's tasks
before the review gate clears.

## Reporting

After each task, one short paragraph to the user: task id, what changed, test counts,
commit hash. After a batch, a compact status of the board.

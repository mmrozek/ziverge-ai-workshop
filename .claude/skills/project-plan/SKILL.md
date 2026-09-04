---
name: project-plan
description: Produce the snap design and implementation plan from the contract in snap/ (SPEC.md + tests/). Runs spec analysis, writes docs/plan/DESIGN.md and PLAN.md (phases → tasks), creates the task files, and ends by presenting the plan for user approval. Use at project start or when the plan needs a material revision.
---

# project-plan

Goal: approved `docs/plan/DESIGN.md` + `docs/plan/PLAN.md`. No implementation before
that approval.

## Steps

1. **Spec analysis.** Launch the `spec-analyst` agent; wait for `docs/plan/SPEC-NOTES.md`.
   If it raises open questions, put them to the user first — answers shape the design.
2. **Verify the harness.** Actually run the provided test suite (expect all-failing or
   compile errors — the point is that the command works and the baseline count is known).
   Record the exact command in the plan.
3. **Write `docs/plan/DESIGN.md`** — the architecture tasks will cite by section:
   - Module/data-model layout: the core types (replica id, vector clock, event/commit,
     store, merge) and their responsibilities.
   - Vector-clock semantics as implemented: increment, compare (happens-before / equal /
     concurrent), merge — with the spec's exact deterministic tie-break rules restated.
   - **Locked decisions table:** decision | choice | rationale | spec/SPEC-NOTES ref.
     (Language and tooling — dictated by the provided tests — go here.)
   - Known gotchas per module (seed from SPEC-NOTES risk notes).
4. **Write `docs/plan/PLAN.md`:**
   - **Phases:** 3–6 vertical slices, each named by the provided tests it turns green,
     each independently reviewable. The skeleton task of phase 1 sets up sbt-scalafmt +
     sbt-scalafix with the configs `docs/SCALA-CONVENTIONS.md` prescribes, so the lint
     gate is live from the first real commit.
   - **Task index:** flat global ids `T01…`, sized 1/2/3/5 SP (split anything bigger),
     grouped by phase — **one line per task** (id, title, SP, risk, deps) linking to
     its task file. Full definitions live ONLY in `tasks/T<nn>-*.md`: What (citing
     DESIGN § and requirement ids `R…`), file scope, dependencies, **Risk tag** (`core`
     for anything touching clock compare / merge / tie-break — triggers a formal
     pre-commit review), and 3–4 falsifiable acceptance criteria naming the provided
     tests that must pass, with negative constraints where they matter. Flag
     parallel-safe tasks (disjoint files only). Don't duplicate definitions into
     PLAN.md — single source of truth per kind: definition = task file, status =
     TASKS.md, structure = PLAN.md.
   - **Dependency graph (user requirement):** a ```mermaid `graph TD` block — one node
     per task (subgraph per phase), one edge per dependency. Must stay in sync with the
     task files' "Depends on" and the `Depends` column of `TASKS.md`; the status board
     renders the same edges live.
   - **Test & requirement map:** every provided test assigned to exactly one phase;
     every `R…` covered by some task (uncovered = plan bug).
   - **Risks** with mitigations.
5. **Create task files** in `tasks/` per the template in `tasks/README.md`, plus the
   `TASKS.md` board, all statuses `todo` (this overwrites any demo fixture in
   `tasks/TASKS.md` — only the real board is ever committed).
6. **Commit** (`docs: design, plan, and task breakdown for review`) and present a compact
   summary to the user: phases, task counts, key locked decisions, open questions.
   **Stop and wait for approval.** Record approval (date + amendments) at the top of
   PLAN.md, commit.

## Rules

- Plan revisions after approval: edit PLAN.md with a changelog entry, re-approve, commit.
  Never rewrite completed task entries — new tasks or `T<nn>-fix` instead.
- Don't gold-plate: no phases or tasks for things the spec doesn't ask for.

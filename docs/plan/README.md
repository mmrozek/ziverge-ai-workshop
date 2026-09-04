# docs/plan/ — analysis, design, plan

Three documents, produced in order once the spec lands, each feeding the next:

1. **`SPEC-NOTES.md`** (spec-analyst agent) — requirement inventory (`R…` ids), provided
   test inventory and coverage map, language/tooling constraints, ambiguities, risks.
2. **`DESIGN.md`** — architecture and data model, vector-clock semantics and the exact
   deterministic tie-break rules, locked-decisions table, per-module gotchas.
   Tasks cite DESIGN sections; it's the "why/how" source during implementation.
3. **`PLAN.md`** — phases (vertical slices named by the provided tests they turn green)
   and the `T<nn>` task breakdown with acceptance criteria, dependency notes, and the
   test/requirement coverage map. Approved by the user before any code; revisions get a
   changelog entry and re-approval.

See `.claude/skills/project-plan/SKILL.md` for the authoring procedure.

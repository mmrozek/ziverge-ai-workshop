# snap — working rules

Vector-clock version control system with deterministic automatic merging.
The project lives in `snap/`: `snap/SPEC.md` and the YAML suite in `snap/tests/`
(driven by `snap/test-harness/`, entry point `./snap/verify`) are the contract.
**All development happens inside `snap/`** (user, 2026-09-04): our implementation is
the sbt workspace `snap/scala/`; `snap/ts/` is the bundled scaffold — reference only.
This file defines **how** work happens here; `README.md` describes the layout.

## Ground rules

1. **Spec wins.** Never edit the contract: `snap/SPEC.md`, `snap/tests/`,
   `snap/test-harness/`, `snap/run*`, `snap/verify`, `snap/README.md`,
   `snap/AGENTS.md`, or the `snap/ts/` scaffold. (snap's own AGENTS.md permits spec
   amendments — our rule overrides it: spec changes only via the user.)
   Ambiguity policy (user, 2026-09-04),
   split by risk: for **core semantics** (clock comparison, merge behavior, tie-break
   rules) stop and ask the user — never guess. For anything else, pick the most
   spec-consistent reading, record it in the task file's "Notes / decisions", and
   surface it in the phase review — don't stall a phase on a minor reading.
2. **Plan before code.** No implementation before `docs/plan/DESIGN.md` and
   `docs/plan/PLAN.md` exist and the user has approved them. Same for material plan
   changes: update the plan with a changelog entry, get approval, then code.
3. **Determinism is the product.** Merge results must be a pure function of the inputs —
   independent of wall-clock time, iteration order of hash maps/sets, replica processing
   order, or randomness. Any tie-break must be an explicit, documented rule. No
   `now()`/env reads deep in domain code — thread time and configuration in as values.
4. **One task = one commit.** Subjects: `T<nn>: <summary>` for tasks,
   `T<nn>-fix: <summary>` for follow-up fixes to a done task, `review(phase-N): <summary>`
   for review fixes, `docs:`/`chore:` for the rest. No squash, no amend of pushed work.
5. **History stays honest.** Never rewrite a completed task's entry in `tasks/` — file a
   `T<nn>-fix` or a new task instead.
6. **Phase gate.** A phase is closed only after: all its tasks done → provided tests for
   the phase pass → lint gate green → `reviewer` agent report saved in
   `reviews/phase-N-review.md` → every finding triaged (accept/defer/reject, recorded in
   the report) → accepted fixes committed → user shown the review summary. The next
   phase then starts **automatically** — the user interrupts if something bothers them
   (their call, 2026-09-04). **Phases pipeline** (user, 2026-09-04): a later phase's
   implementation may start while an earlier phase's review is still running, provided
   task dependencies are met — but a phase only *closes* once its review findings are
   triaged and accepted fixes are committed; conflicting review fixes take precedence
   over in-flight work. The only hard user-approval gate is the initial plan.
   **Phases 3–5 exception (user, 2026-09-05):** phases 3, 4 and 5 get **no** gate review
   ("skip phase 3 and 4 gate reviews. I want only final one" / "phase 5 gate can be
   skipped"). Phases 1 and 2 were gated normally. All later work goes straight to the
   post-completion audit, which must absorb what those gates would have done: the
   `scala-antipatterns` run over the final tree, the full-suite + lint gate, and a triage
   pass over its own findings. **Note the uneven residual risk:** phase 3's tasks (T15,
   T16, T17, T18) each already had a formal pre-commit *core* review, so little is lost
   there — but phase 4 (T19, T20, T21) is `Risk: normal` throughout and has had no
   independent review at all, so the audit is the first and only outside look at the HTTP
   client, the server, and the cross-repo path. The audit brief must carry an explicit
   phase-4 lens.
7. **Everything is tracked.** Plans, task files, reviews, agent/skill definitions — all
   committed to this repo. Don't keep state only in conversation.

## Workflow

```
spec+tests arrive → spec analysis (SPEC-NOTES.md) → DESIGN.md + PLAN.md → USER APPROVAL
  └─ per task:   implement (implementer agent or directly) → tests green → commit → update TASKS.md
  └─ per phase:  phase-review skill → reviews/phase-N-review.md → triage + fix → USER SUMMARY
  └─ at the end: post-completion audit (independent reviewers) before declaring done
```

Phases are vertical slices: each one is named by the provided tests it turns green.

**Verification commands** (from repo root): full suite `./snap/verify --lang scala`
(builds the Scala workspace first). **JDK note (T10 finding):** the harness passes
through only `PATH` (drops `JAVA_HOME`); the machine-default JDK 24 prints a
`sun.misc.Unsafe` warning on startup that breaks every `stderr_equals ""` assertion —
always run with Java 17 first on PATH:
`PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala`.
The harness itself is checked with
`cd snap/test-harness && npm run check && npm test` (only relevant if the user changes
it — we never do). Manual runs: `./snap/run --lang scala <command…>`.

- **Planning:** use the `project-plan` skill when the spec lands.
- **Task execution:** use the `task-loop` skill; delegate to the `implementer` agent for
  self-contained tasks. Parallel tasks only when they touch disjoint files — spawn each
  implementer with the Agent tool's `isolation: "worktree"` (harness-managed worktree,
  auto-cleanup) or a herdr pane, integrate back into `main` in plan order; otherwise
  sequential.
- **Review cadence (user, 2026-09-04):** formal review after every **phase**
  (`phase-review` skill), plus a formal **pre-commit task review** for `Risk: core`
  tasks — anything touching clock compare, merge, or tie-break logic — written to
  `reviews/T<nn>-review.md` and committed with the task. Normal tasks get the task-loop
  verification only. The reviewer never fixes code; fixes happen after the report.
- **Progress board (user requirement):** from the moment implementation starts, a
  stacked herdr pane runs `scripts/status-board.sh` — "NOW" summary, colored per-phase
  task graph with dependency arrows, agents section, progress bar. Open it once at the
  start of the first task: `herdr pane split --current --direction down --ratio 0.4
  --cwd "$PWD" --no-focus` (take `.result.pane.pane_id` from the JSON), then
  `herdr pane run <pane-id> "./scripts/status-board.sh"`. The board also **notifies the
  user** (herdr notification) on every task status transition — so keep the data fresh:
  - `tasks/CURRENT.md`: one line, updated at **every** transition (picked / implementing
    / verifying / committing / review / blocked). Ephemeral, gitignored.
  - `tasks/AGENTS-STATUS.md`: one line per live in-process subagent
    (`name | role | doing | since HH:MM`), added when spawned, removed when it
    finishes. Ephemeral, gitignored. (herdr pane agents appear automatically.)
  - `tasks/TASKS.md`: the durable record — status changes land here immediately,
    not batched at task end; the board's change notifications key off it.

## Model policy

Match model cost to task criticality when spawning agents (Agent tool `model` param
overrides agent frontmatter). Revised again by the user 2026-09-04 ("i dont want to
use fable at all, i need to make things faster") — supersedes the earlier "keep only
risky tasks as fable" revision:

- **Fable: never by default** — no task tier maps to it, not even Risk-core work. Only on
  an explicit per-task user request, exactly like Opus below. Standing exception granted
  by the user 2026-09-04: **T17 (`merge` command) is implemented by a fable implementer**
  ("use fable for t17 - it is crucial task"). The exception is per task and does not
  extend to T17's reviewer or to later tasks.
- **Sonnet (default):** everything — implementers of any risk/SP (including Risk-core),
  pre-commit task reviewers, phase reviews, integration verification runs, spec
  analysis / plan work.
- **Opus:** only on an explicit per-task user request. **Granted by the user 2026-09-05
  for the post-completion audit** ("use opus for the post-completion audit") — that audit
  runs on Opus; no other task inherits the grant.
- **Haiku:** pure scans/greps.
- The orchestrator session itself runs on a faster model (user switches via `/model` —
  Sonnet unless they say otherwise); since spawns always pass an explicit `model`, the
  session model never leaks into subagent tiering.

## Mechanical guards (`.claude/settings.json`)

Permission rules enforce the ground rules — don't route around them: Edit/Write on the
contract (`snap/SPEC.md`, `snap/tests/`, `snap/test-harness/`, `snap/ts/`, runner
scripts, snap's README/AGENTS) is denied — spec changes come only from the user;
`git commit --amend`, `reset`, `rebase`, `clean` always prompt; routine git plus
`./snap/verify`, `./snap/run_tests`, `./snap/run`, and `sbt` are pre-allowed so the
task loop doesn't stall.

## Scala tooling (language confirmed by user, 2026-09-04)

- Machine: sbt (sdkman), coursier, Java 17, `metals-mcp` 1.6.8 at
  `~/Library/Application Support/Coursier/bin/metals-mcp` (coursier bin is not on
  PATH — use the full path).
- **Primary: Metals v2** (https://metals-lsp.org/, 2.0.0-M2 as of 2026-09) — user's
  call: indexes sources directly (~1M lines/s) without waiting for build sync. It's a
  milestone release and its agent-integration path (MCP endpoint / emitted config)
  isn't documented yet — set it up and verify when the project directory exists;
  if it doesn't hold up, fall back without ceremony.
- **Verified fallback — standalone Metals MCP:**
  `"$HOME/Library/Application Support/Coursier/bin/metals-mcp" --workspace <dir> --port 8765`
  then `claude mcp add --transport http metals "http://localhost:8765/mcp"`.
  Tools: `compile-file`/`compile-module`/`compile-full`, `test`, `get-usages`,
  `inspect`, `glob-search`, `format-file`, scalafix rules. Prefer Metals tools over
  raw sbt for incremental compile checks and usage/rename analysis.
- **Other channels:** IntelliJ MCP (`mcp__idea__*`) when the project is open in
  IntelliJ; a persistent sbt shell (herdr pane, `~compile`/`~testQuick`) for
  watch-style runs and the provided harness's own runner.
- `.mcp.json`, `.metals/`, `.bloop/`, `.bsp/` are machine-local — gitignored.
- **Conventions:** `docs/SCALA-CONVENTIONS.md` is binding for all Scala code; the
  `scala-antipatterns` skill audits compliance — run it before every phase review.

## Testing

- The provided YAML suite (`snap/tests/`, run via `./snap/verify --lang scala`) defines
  done — run the relevant subset per task (`run_tests` supports filtering), the full
  suite at each phase gate. Never weaken, skip, or special-case a provided test.
- **Lint gate:** `sbt scalafmtCheckAll` and `sbt "scalafixAll --check"` pass on every
  task commit and at every phase gate (configs and rules: `docs/SCALA-CONVENTIONS.md`).
- **Slow suites are phase-gate only** (user, 2026-09-04): expensive probes — deep-history
  stack-safety, large-scale performance — are named `*SlowSuite` and excluded from the
  `test` task (`Test / testOptions += Tests.Filter`); run them with `sbt slowTest` at the
  phase gate, not on every task. A task runs a slow suite only if it directly touches
  what that suite probes. Rationale: per-task verification has to stay fast.
- **Assume holdout evaluation** (user, 2026-09-04): the provided suite is a sample of
  the contract, not its edge. Implement the full spec text even where no provided test
  exercises it; the post-completion audit is a hard gate, not a formality.
- Project-authored tests live in `snap/scala/src/test/scala/` and **must** include property-based
  determinism checks for merge logic: commutativity, idempotence, order-independence
  of applying concurrent updates, and byte-identical output under permuted
  insertion/processing orders and repeated runs.

## Directory quick map

`snap/` the project — `SPEC.md` + `tests/` + `test-harness/` contract (read-only),
`ts/` bundled scaffold (read-only), **`scala/` our implementation (sbt)** ·
`docs/plan/` SPEC-NOTES + DESIGN + PLAN · `tasks/` task files + `TASKS.md` board ·
`reviews/` reviews · `.claude/` + `scripts/` workflow tooling.

## Workflow trap — jar staleness vs test-only edits (phase-1 finding)

`snap/run` (contract, read-only) rebuilds when ANY file under `snap/scala/src` —
including `src/test` — is newer than the assembly jar, but `sbt assembly` never
re-stamps the jar for test-only changes. Result: after editing only test files, every
`./snap/verify` invocation silently pays a full sbt bootstrap per CLI call and
long multi-step cases (e.g. test 23) blow their 30 s budget as a phantom "hang".
**Rule: after any test-only edit, run `cd snap/scala && sbt -batch clean assembly`
before `./snap/verify`.**

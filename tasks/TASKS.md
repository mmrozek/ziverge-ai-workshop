# TASKS — status board

Status values: `todo` · `in-progress` · `verifying` · `review` · `blocked` · `done`.
Definitions live in `tasks/T<nn>-*.md`; structure in `docs/plan/PLAN.md`. This file is
the **only** place status lives.

| Task | Phase | Title | SP | Status | Depends | Commit |
|---|---|---|---|---|---|---|
| T01 | 1 | Scala workspace skeleton & lint gate | 2 | done | — | 297bb98 |
| T02 | 1 | Strict JSON layer & canonical writer | 3 | done | T01 | 81430ea |
| T03 | 1 | Version algebra (compare/join/snap order) | 3 | done | T01 | 4b70529 |
| T04 | 1 | Paths, Utf8Order, trees | 2 | done | T01 | 30d9e75 |
| T05 | 1 | Text tokens, edit scripts, canonical diff | 3 | done | T01 | e348367 |
| T06 | 1 | Repository model, codec, structural validation | 3 | done | T02,T03,T04 | ae85906 |
| T07 | 1 | Replay ready-loop, materialization, validation 5–6 | 3 | done | T05,T06 | 025238e |
| T08 | 1 | CLI dispatch, discovery, plain output, exit codes | 2 | done | T02 | 6e46415 |
| T09 | 1 | init + config | 2 | done | T06,T08 | 0fc896d |
| T10 | 1 | Worktree scanner, status, commit, log | 3 | done | T07,T09 | 41a5caa |
| T11 | 2 | diff command & rendering | 3 | done | T10 | 20f3896 |
| T12 | 2 | Filesystem install & revert | 2 | done | T11 | 13168f9 |
| T13 | 2 | CLI grammar matrix & port validation | 2 | done | T12 | 333f4b1 |
| T14 | 2 | Validation matrices & error catalog completion | 3 | done | T12 | 6daa92d |
| T15 | 3 | OT transform | 3 | done | T05 | acfb222 |
| T16 | 3 | Concurrent replay: namespace, path rules, warnings | 5 | done | T07,T15 | 071639e |
| T17 | 3 | merge command | 3 | done | T12,T16 | 6cc2291 |
| T18 | 3 | Convergence hardening & property suite | 2 | done | T17 | 720f7e5 + 75067db |
| T19 | 4 | --serve HTTP server | 3 | done | T07,T13 | e80c960 |
| T20 | 4 | HTTP client & remote operands | 2 | done | T17,T19 | f89e91b |
| T21 | 4 | Cross-repo collision, failure precedence, portability | 2 | in-progress | T20 | |
| T22 | 5 | Terminal renderer, SNAP_COLOR/NO_COLOR, TTY | 3 | verifying | T13,T17,T19 | |
| T23 | 5 | Holdout-gap hardening & final pass | 2 | in-progress | T18,T21,T22 | |

# TASKS — status board

Status values: `todo` · `in-progress` · `verifying` · `review` · `blocked` · `done`.
Definitions live in `tasks/T<nn>-*.md`; structure in `docs/plan/PLAN.md`. This file is
the **only** place status lives.

| Task | Phase | Title | SP | Status | Depends | Commit |
|---|---|---|---|---|---|---|
| T01 | 1 | Scala workspace skeleton & lint gate | 2 | done | — | aada3d6 |
| T02 | 1 | Strict JSON layer & canonical writer | 3 | done | T01 | 38d42c9 |
| T03 | 1 | Version algebra (compare/join/snap order) | 3 | done | T01 | 9d7ab02 |
| T04 | 1 | Paths, Utf8Order, trees | 2 | done | T01 | c5647ff |
| T05 | 1 | Text tokens, edit scripts, canonical diff | 3 | done | T01 | ae0bec6 |
| T06 | 1 | Repository model, codec, structural validation | 3 | done | T02,T03,T04 | 4e2005e |
| T07 | 1 | Replay ready-loop, materialization, validation 5–6 | 3 | done | T05,T06 | 2bca6b4 |
| T08 | 1 | CLI dispatch, discovery, plain output, exit codes | 2 | done | T02 | 9f81a9b |
| T09 | 1 | init + config | 2 | done | T06,T08 | 2315d47 |
| T10 | 1 | Worktree scanner, status, commit, log | 3 | done | T07,T09 | 8d2fa1a |
| T11 | 2 | diff command & rendering | 3 | done | T10 | 01f253d |
| T12 | 2 | Filesystem install & revert | 2 | done | T11 | 7d515d0 |
| T13 | 2 | CLI grammar matrix & port validation | 2 | done | T12 | 66ebba3 |
| T14 | 2 | Validation matrices & error catalog completion | 3 | done | T12 | e69011e |
| T15 | 3 | OT transform | 3 | done | T05 | 5bcccda |
| T16 | 3 | Concurrent replay: namespace, path rules, warnings | 5 | done | T07,T15 | 2a481e6 |
| T17 | 3 | merge command | 3 | done | T12,T16 | e8977ed |
| T18 | 3 | Convergence hardening & property suite | 2 | done | T17 | ec3b89a + c8a9cc4 |
| T19 | 4 | --serve HTTP server | 3 | done | T07,T13 | 39f1b06 |
| T20 | 4 | HTTP client & remote operands | 2 | done | T17,T19 | 915278c |
| T21 | 4 | Cross-repo collision, failure precedence, portability | 2 | done | T20 | 74020c3 |
| T22 | 5 | Terminal renderer, SNAP_COLOR/NO_COLOR, TTY | 3 | done | T13,T17,T19 | 6ba6fab |
| T23 | 5 | Holdout-gap hardening & final pass | 2 | done | T18,T21,T22 | 692bbf1 |

## Commit hashes were re-recorded after a history rewrite

On 2026-09-05 the whole history was rewritten to carry `michal.mrozek@ziverge.com` as
author and committer (the repository had been built under a different address). The
rewrite changed metadata only — the tree at `main` is byte-identical, verified before and
after — but every commit id changed, so the hashes above are the post-rewrite ones and are
the only ones that resolve.

The SHAs quoted inside `reviews/*.md` and inside older commit messages are pre-rewrite and
will not resolve. They are left as written rather than edited: a review report is a record
of what was examined at the time, and rewriting its contents to match new ids would make it
a worse record, not a better one. Match them by task id and subject line instead.

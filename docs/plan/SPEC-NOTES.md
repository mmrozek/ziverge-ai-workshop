# SPEC-NOTES — snap

Spec analysis of `snap/SPEC.md` (canonical contract) and the 28-file YAML acceptance
suite in `snap/tests/`. Source paths are relative to the repo root
`/Users/mmrozek/work/AI/`. Spec locations use the spec's own `§` numbering.

Where a test pins something the spec leaves open, **the test wins** and it is flagged in
§2.3 below. Per project policy (CLAUDE.md overrides `snap/AGENTS.md`), spec text is never
amended by us; contradictions and gaps go to the user via §5 (open questions).

---

## 1. Requirement inventory

One line per normative requirement; compound spec sentences are split. Ids are stable —
never renumber, append only.

### Product model and invariants (§1, §1.1)

| Id | Loc | Requirement |
|----|-----|-------------|
| R1 | §1 | Every repository starts from the empty file tree at version `()`. |
| R2 | §1, §4.2 | Each patch names the exact version (base) on which it was authored. |
| R3 | §1, §4.2 | Each patch increments its author's revision counter by exactly one. |
| R4 | §1, §3 | A version is a vector clock (contributor id → latest revision); it describes a causal frontier, not a branch/commit. |
| R5 | §1, §7.8 | Merge imports the other repo's patches, joins frontiers, deterministically rebuilds the joined state; it creates no merge patch. |
| R6 | §1, §6 | Every concurrent change is resolved automatically and deterministically; both original patches stay in history. |
| R7 | §1, §6.4 | Whole-file conflict resolution records `(path, reason)` warning facts. |
| R8 | §1 | The binary name is `snap`. |
| R9 | §1.1(1) | A version is a finite vector of nonzero contributor counters. |
| R10 | §1.1(2) | One patch owns exactly one `(contributor, revision)` dot. |
| R11 | §1.1(3) | A patch's complete causal base is present and immutable. |
| R12 | §1.1(4) | Every known version is reproducible from the empty tree and its patches. |
| R13 | §1.1(5) | The same validated patch set always produces the same file tree. |
| R14 | §1.1(6) | Import is set union: idempotent, commutative, associative. |
| R15 | §1.1(7) | The same dot with different patch values is corruption, not a merge conflict. |
| R16 | §1.1(8) | `.snap/` metadata is never part of the tracked tree. |

### Repository and working tree (§2)

| Id | Loc | Requirement |
|----|-----|-------------|
| R17 | §2, §7.1 | `snap init [path]` creates `.snap/` beneath an existing or newly created working directory; initial version and tracked tree are empty. |
| R18 | §2 | Snap tracks every regular file below the repository root except `.snap/` and its contents. |
| R19 | §2 | File contents are arbitrary bytes. |
| R20 | §2 | Directories are implicit; empty directories are not tracked. |
| R21 | §2 | Symlinks and other non-regular entries are unsupported: Snap MUST report them and MUST NOT follow them. |
| R22 | §2 | Permissions, ownership, timestamps, extended attributes are not tracked. |
| R23 | §2 | Tracked path validity: nonempty UTF-8, `/` separators, no ASCII control char or backslash, no empty/`.`/`..` segment, first segment ≠ `.snap`. |
| R24 | §2 | No Unicode or case normalization; paths sort by unsigned lexicographic UTF-8 bytes. |
| R25 | §2, §6.4 | Every tracked tree is prefix-free by path segment; validated for every patch's authored result, enforced during concurrent replay by §6.4. |
| R26 | §2 | Working tree is clean iff its path/byte map exactly equals the current tree and contains no unsupported entry. |
| R27 | §2 | `commit` records a dirty tree; `merge` and `revert` refuse to replace a dirty tree; read-only commands may inspect one. |

### Versions (§3)

| Id | Loc | Requirement |
|----|-----|-------------|
| R28 | §3.1 | Contributor ID: ASCII email-shaped; exactly one `@` with nonempty text both sides; no control character, whitespace, `,`, `(`, `)`, or substring `->`; ≤ 254 bytes. |
| R29 | §3.1 | Contributor ID spelling is preserved exactly. |
| R30 | §3.1 | A revision is a positive integer ≤ 9007199254740991; zero means "no revision" and is omitted. |
| R31 | §3.2 | Canonical version syntax: `()` or `(id->n,...)` sorted by unsigned UTF-8 bytes, no spaces; CLI arguments MUST use this exact form; duplicate ids, explicit zeroes, leading zeroes, overflow, invalid ids, whitespace, noncanonical order are errors. |
| R32 | §3.2 | In repository JSON a version is an ordered array of `[id, revision]` pairs (canonical order — pinned by test 23's frontier-ordering case). |
| R33 | §3.3 | Causal comparison: absent component is zero; `=` all equal; `<` all ≤ with one strict; `>` converse; `\|\|` otherwise. |
| R34 | §3.3 | `join(V, W)[c] = max(V[c], W[c])`. |
| R35 | §3.3 | The version type MUST preserve all four comparison outcomes (concurrency ≠ before/after). |
| R36 | §3.4 | Snap order (total order over versions): sorted union of contributor ids, lexicographic compare of the counter at each id, first unequal counter decides; extends causal order; no chronological meaning. |
| R37 | §3.5 | Serial contributor rule: for each contributor, revision `n` has exactly one patch and follows `n-1`; one ID MUST NOT author concurrently in disconnected copies. |
| R38 | §3.5, §4.2 | If import finds the same dot with structurally different patches, the repository is corrupt and merge fails before writing. |

### Repository and patch format (§4)

| Id | Loc | Requirement |
|----|-----|-------------|
| R39 | §4.1 | Layout: `.snap/repository.json` plus optional `.snap/config.json`. |
| R40 | §4.1 | `repository.json` holds the complete repository value: `{"format":1, "frontier":…, "patches":…}`. |
| R41 | §4.1 | Readers accept ordinary JSON whitespace and key order; valid input has unique object keys (duplicates are errors); the parsed typed value, not bytes, is authoritative. |
| R42 | §4.1 | Writers SHOULD use two-space indentation and trailing LF (test 12 pins the exact serialized bytes for the served snapshot — effectively MUST for one shared canonical serializer; see §2.3, §5 Q3). |
| R43 | §4.1 | Unknown fields, non-integer numbers, and invalid typed values are errors. |
| R44 | §4.1 | `patches` contains exactly the causal closure of `frontier`, sorted by author then numeric revision, with no unreachable patches. |
| R45 | §4.1 | Known/materializable version: syntactically valid, every patch `(c,n)` with `n ≤ V[c]` exists, and that set contains every selected patch's complete base; `diff` and `revert` reject unknown vectors. |
| R46 | §4.2 | `revision = base[author] + 1`; `result = base` with `result[author] = revision`; all other components equal the base. |
| R47 | §4.2 | Patches at the same dot are duplicates only when structurally equal as parsed typed values; different values are corruption. |
| R48 | §4.2 | `message`: nonempty UTF-8; tab and LF allowed, no other ASCII control char; `snap commit` limits user messages to 4096 bytes; generated revert messages may be longer. |
| R49 | §4.2 | `changes` is nonempty, sorted by path, at most one change per path. |
| R50 | §4.3 | Change variants: `text {path, edit}`, `put {path, content}` with standard padded RFC 4648 base64 (canonical — test 15), `delete {path}`. |
| R51 | §4.3 | Text/put creation requires the path absent in the patch's exact base tree; edit, replacement, delete require it present. |
| R52 | §4.3 | A change that does not alter path existence or bytes is invalid (no-op), except an empty text edit may create an empty file. |
| R53 | §4.4 | A file is text iff bytes are valid UTF-8 with no NUL; tokenize by splitting immediately after every LF, retaining LF; empty file has no tokens. |
| R54 | §4.4 | Edit ops are one-key `{retain:n}` / `{delete:n}` / `{insert:[s…]}`; counts are positive safe integers; insert has one or more nonempty text tokens. |
| R55 | §4.4 | Adjacent operations of the same kind are forbidden. |
| R56 | §4.4 | The script MUST consume the complete old token sequence; no implicit trailing retain. |
| R57 | §4.4 | The applied result MUST be exactly the canonical token sequence: every token except possibly the last ends in LF; no token contains an interior LF. |
| R58 | §4.4 | An empty script is valid only when creating an empty text file. |
| R59 | §4.5 | Before use, validate: (1) exact schema and all versions/ids/paths/messages/changes; (2) patch sorting, one value per dot, contiguous revisions; (3) complete base closure and `revision = base[author]+1`; (4) acyclic causality; (5) every change against its materialized exact base; (6) deterministic replay of the declared frontier. |
| R60 | §4.5 | If no ready patch remains before replay completes, the history has a cycle or missing dependency: validation fails; Snap never fuzzily applies a patch. |

### Canonical text diff (§5)

| Id | Loc | Requirement |
|----|-----|-------------|
| R61 | §5 | One deterministic token diff (used by patch creation, displayed diffs, and OT) defined by the `D(i,j)` min-insert/delete recurrence. |
| R62 | §5 | Tie-break: at unequal tokens choose `delete 1` when `D(i+1, j) <= D(i, j+1)`, else `insert [B[j]]`. |
| R63 | §5 | Walk from `(0,0)`: equal tokens → `retain 1`; exhausted side → insert/delete remainder; coalesce adjacent same-kind ops. |
| R64 | §5 | Myers/Hirschberg/etc. allowed only if the output script is identical, including for repeated equal lines. |

### Deterministic replay and OT (§6)

| Id | Loc | Requirement |
|----|-----|-------------|
| R65 | §6.1 | Materializing `V` selects every patch `(c,n)` with `n ≤ V[c]`; the set must contain every selected patch's base. |
| R66 | §6.1 | Replay: start from empty tree; repeatedly integrate the least ready patch by (1) Snap order of result versions, (2) unsigned UTF-8 order of author, (3) numeric revision. |
| R67 | §6.2 | To integrate patch `P`: materialize its exact base tree `B`; `C` is the canonical tree built so far (`B` plus only earlier concurrent effects). |
| R68 | §6.2 | Namespace pre-pass per patch: with `S` = paths `P` makes present and `C'` = `C` minus `P`'s authored deletions, any path in `S` with a different current ancestor or descendant in `C'` installs its authored result `T` and removes every conflicting current path; each removed path emits `namespace-wins`; these decisions override the per-path rules; removals and installs form the target simultaneously. |
| R69 | §6.2 | Per remaining path (all against the same `B` and `C`): (1) path identical in `B` and `C` → apply the authored change directly; (2) path identical in `C` and `T` → keep unchanged (collapses identical concurrent changes before OT); (3) `B`,`C`,`T` text and `P` a text change → transform through aggregate context edit `Q = diff(B,C)` per §6.3 and apply to `C`; (4) otherwise §6.4 path rules. |
| R70 | §6.2 | Apply all resulting path changes of one patch together; installation removes files blocking required directories, creates directories, writes targets, removes newly empty directories. |
| R71 | §6.3 | OT transform table (6 rows, exact); `Q insert` row has priority; deletion consumes only base tokens (concurrent inserted text survives); both scripts consume the same base token count; process trailing insertions; coalesce output. |
| R72 | §6.3 | The transform runs once against the aggregate context edit, not once per historical patch. |
| R73 | §6.4 | Path-level rules, in order: (1) `C`=`T` → keep, no warning; (2) `T` absent → `delete-wins`; (3) `B` present, `C` absent → `delete-wins`; (4) `B` absent, `C` and `T` present → `later-create-wins`; (5) incoming is `put` → `later-put-wins`; (6) else `P` text over non-text `C` → `put-wins`. "Later" always means canonical integration order. |
| R74 | §6.4 | Replay returns the set of unique warning pairs sorted by path, then reason; line OT emits no warning. |
| R75 | §6.4 | Merge prints only pairs present in the joined replay but absent from the pre-merge local replay, one per line: `warning: auto-resolved <path>: <reason>`. |
| R76 | §6.5 | Same valid patch set and frontier MUST produce the same bytes and warning set in every implementation; re-merging the same history is a no-op; merge direction cannot change the joined result. |

### Commands (§7)

| Id | Loc | Requirement |
|----|-----|-------------|
| R77 | §7 | Snap locates the nearest repository by walking from the current directory to the filesystem root. |
| R78 | §7 | A repository operand is an explicit `http://`/`https://` URL, otherwise a local path resolved against the process working directory. |
| R79 | §7 | Options occur exactly in the documented positions, at most once; unknown options, extra operands, missing option values are errors. |
| R80 | §7.1 | `init`: path defaults to `.` and is created if absent (recursively per test 02); creates empty `repository.json`; existing files stay uncommitted; prints `()`. |
| R81 | §7.1 | Reinitializing a repository is an error; initializing a target inside an existing repository is an error. |
| R82 | §7.2 | `config [--global] contributor.id <id>`: validates the id before writing; local → `.snap/config.json` in nearest repo; `--global` → `$HOME/.snapconfig.json`, no repo needed; preserves no unknown fields; prints nothing on success. |
| R83 | §7.3 | `status` prints `version <v>` then working changes sorted by path with codes `A` (absent→present), `M` (changed bytes), `D` (present→absent); clean prints only the version line. |
| R84 | §7.4 | `log` prints patches in reverse canonical integration order, one tab-separated line `<result-version>\t<author>\t<message>`; message escapes backslash, tab, LF as `\\`, `\t`, `\n` in that order. |
| R85 | §7.5 | `commit <message>`: requires contributor config and dirty tree; rejects messages > 4096 UTF-8 bytes; diffs complete current tree vs complete working tree; one patch based on current frontier; text change when new content is text and old path absent-or-text, else `put`; removed paths → `delete`; atomically replaces `repository.json`; prints the new version; clean tree / invalid message / overflow / dot collision are errors. |
| R86 | §7.6 | `diff` (no args) compares current tree vs working tree; `diff <old> <new>` compares two locally known versions; `--repo <repository>` resolves `new` in another local/HTTP repository without importing; validate every repository and version before output; cross-repo: compare every dot present in both and fail as corrupt if parsed values differ. |
| R87 | §7.6 | Diff output: changed paths sorted by path; per text path a whole-file unified block `--- a/<p>`, `+++ b/<p>`, `@@ -1,<old-count> +1,<new-count> @@` then §5 script operations; `/dev/null` for an absent side; token without final LF followed by LF + `\ No newline at end of file`; binary change → one line `Binary files a/<p> and b/<p> differ`; no differences → empty stdout, exit 0. |
| R88 | §7.7 | `revert <version>`: requires contributor config, clean tree, locally known target; authors one new patch (message `revert to <version>`) diffing current → target; installs target, prints the NEW version; equal trees → error `snap: target tree is already current`; never removes patches or moves the frontier backward. |
| R89 | §7.8 | `merge <repository>`: requires clean tree, no contributor config; loads and validates the other repository; unions patch sets, joins frontiers; replays canonically, installs, updates `repository.json`; creates no patch; new warnings → stderr, joined version → stdout; merging equal/contained history succeeds, changes nothing, emits no warnings, prints the unchanged version. |
| R90 | §7.9 | `--serve [port]`: validates and snapshots the repository at startup; binds only 127.0.0.1; port defaults to 8765, `0` = OS-selected; prints and flushes `http://127.0.0.1:<actual-port>/repository.json`; serves the startup snapshot until SIGINT/SIGTERM, then exits 0. |
| R91 | §7.10 | `--version` prints `snap <semver>` without locating a repository (test 28 pins the semver to exactly `1.0.0`). |
| R92 | §7.11 | Selecting a presentation MUST NOT change command execution, repository/filesystem effects, warning selection or order, or exit status. |
| R93 | §7.11 | `SNAP_COLOR`: unset or `auto` → terminal mode independently per stream when that stream is a TTY, unless `NO_COLOR` is present; `always` → terminal mode on both streams even redirected, overrides `NO_COLOR`; `never` → plain on both. |
| R94 | §7.11 | `NO_COLOR` presence (even empty value) selects the complete plain presentation in `auto` mode. |
| R95 | §7.11 | Any other `SNAP_COLOR` value errors before command execution with plain `snap: SNAP_COLOR must be auto, always, or never`. |
| R96 | §7.11 | The `--serve` startup URL always remains plain. |
| R97 | §7.11 | Exact terminal (ANSI SGR) layouts for init/commit/revert/merge success lines, status, log, diff line-styling, `--version`, warning `⚠`, error `✗`; `config` remains silent; nonempty records end with LF; no added trailing spaces; empty plain output stays empty. |

### Configuration (§8)

| Id | Loc | Requirement |
|----|-----|-------------|
| R98 | §8 | Configuration is UTF-8 JSON with exactly the shape `{"contributor":{"id":…}}`. |
| R99 | §8 | Local `.snap/config.json` is read and validated first; if it provides an ID, global is not read; else `$HOME/.snapconfig.json`; missing file = no value; a malformed file, non-unique or unknown field, or invalid ID in a file that is read is an error; no `$HOME` → global unavailable. |
| R100 | §8 | Only `commit` and `revert` require an ID; if missing they fail with exactly `snap: contributor.id is required; configure it locally or globally`. |

### HTTP repository (§9)

| Id | Loc | Requirement |
|----|-----|-------------|
| R101 | §9 | `--serve` supports GET and HEAD of `/repository.json`; GET returns the startup snapshot with `Content-Type: application/json; charset=utf-8`; HEAD returns same status/headers, no body; other paths → 404; other methods → 405 with `Allow: GET, HEAD`. |
| R102 | §9 | An `http(s)://` operand triggers exactly one GET of that exact URL, requires status 200, parses the body as a repository value, validates normally; HTTP is read-only; redirects are not followed (302 is an error, test 13). |

### Mutation and failures (§10)

| Id | Loc | Requirement |
|----|-----|-------------|
| R103 | §10 | For `merge`/`revert`, complete parsing, repository validation, replay, dirty-tree checks, and target-tree construction before writing; validation failures cause no mutation. |
| R104 | §10 | Any command that scans the working tree fails on a symlink or other unsupported entry rather than following or silently ignoring it. |
| R105 | §10 | Mutation order: update working files first; replace `repository.json` via a same-directory temporary file only after the working-tree update succeeds; `commit` needs only the metadata replacement. |
| R106 | §10 | An I/O failure mid-update may leave a dirty tree with the old `repository.json`; Snap reports the failure (crash recovery out of scope). |
| R107 | §10 | Output is UTF-8 with LF; results → stdout; warnings and errors → stderr; exit 0 success, 1 expected errors, 2 unexpected internal failures; plain errors are one line `snap: <detail>`. |

### Testing obligations on the implementation (§11)

| Id | Loc | Requirement |
|----|-----|-------------|
| R108 | §11 | Each implementation MUST additionally unit-test `auto` presentation selection for TTY and non-TTY stdout and stderr independently (the shared harness pipes both streams and cannot). |
| R109 | §11 | Property tests SHOULD generate valid causal patch graphs and verify import-permutation convergence (frontier, patch set, warnings, tree). |

---

## 2. Test inventory

All 28 files are single-case format-1 YAML driven by the harness in
`snap/test-harness/` (see §3). "Covers" lists primary requirement ids; nearly every test
also implicitly covers R8 (binary contract), R107 (exit/stream discipline), and plain
mode (R93/R94 — the harness sets `NO_COLOR=1` in every case by default).

| # | File / case name | What it asserts | Covers |
|---|---|---|---|
| 01 | `01-init.yaml` — init creates an empty repository | `init` in cwd (default path `.`), stdout `()\n`, exact tree listing (`.snap/`, `repository.json`), parsed JSON `{format:1,frontier:[],patches:[]}` | R1, R17, R39, R40, R80 |
| 02 | `02-init-paths.yaml` — initialization preserves files and rejects nested/existing | existing files preserved; re-init exit 1 stderr contains `repository already exists`; init in subdir of a repo exit 1 contains `cannot initialize inside repository`, no `.snap` created; `init new/repository` creates nested dirs | R17, R80, R81, R77 |
| 03 | `03-configuration.yaml` — local/global contributor precedence | `config --global` writes `$HOME/.snapconfig.json` exactly; local config written in repo; malformed global ignored when local provides ID; malformed global read (no local) → `invalid JSON`; valid global fallback; `config contributor.id bad-id` → `invalid contributor id` (note: `}}}}` in the YAML is harness escaping for literal `}}` — the "valid" global file is well-formed JSON) | R82, R98, R99, R28 |
| 04 | `04-commit-status-log.yaml` — exact deterministic history | `status` on empty repo `version ()\n`; A rows sorted; commit prints `(alice@example.com->1)`; M/A/D rows sorted; `log` reverse order, TSV, message escaping `\t` `\n` `\\`; clean commit → exit 1 `snap: working tree is clean` | R83, R84, R85, R24 |
| 05 | `05-diff-goldens.yaml` — canonical repeated-line diffs, missing final LF | golden working diff (`/dev/null` header for create, `\ No newline at end of file`); golden repository JSON pinning the canonical script `[delete 1, retain 2, insert ["a"]]` for `a,b,a → b,a,a(no LF)` (delete-on-tie); two-version diff; equal-versions diff → empty stdout, exit 0 | R61–R64, R85, R86, R87, R42 |
| 06 | `06-binary-and-empty.yaml` — binary and empty files byte-exact | binary create → `Binary files /dev/null and b/data.bin differ`; empty text file create block `@@ -1,0 +1,0 @@` with no ops; blocks in path order; bytes round-trip; binary delete line | R19, R50, R53, R58, R87 |
| 07 | `07-revert.yaml` — revert is additive, file↔directory transitions | revert to older/newer versions prints new version; file→dir and dir→file materialization; log shows `revert to (a@x->N)` messages; already-current → exact `snap: target tree is already current`; dirty tree → exact `snap: working tree is dirty` | R88, R70, R84, R27 |
| 08 | `08-unsupported-entries.yaml` — reject symlinks/FIFOs without mutation | `status`/`commit`/`diff` exit 1 with exact `snap: unsupported working tree entry: <path>`; repository JSON unchanged | R21, R104, R26 |
| 09 | `09-merge-text.yaml` — merge converges concurrent text, idempotent | joined stdout version canonical; merged content `base\nright\nleft\n` pins integration order + Q-insert priority; both directions equal trees; re-merge no-op prints unchanged version | R5, R14, R66, R69, R71, R76, R89 |
| 10 | `10-merge-conflicts.yaml` — every whole-file rule, sorted warnings | one merge emits exactly `delete-wins`, `put-wins`, `later-put-wins` warnings sorted by path; identical concurrent change no warning; winners' bytes asserted; re-merge silent | R7, R73, R74, R75, R89 |
| 11 | `11-namespace-conflicts.yaml` — namespace winners both directions | file `a` vs `a/b` in both canonical orders; warning names the *removed* path (`a/b: namespace-wins`, then `x: namespace-wins`); loser fully removed, dirs materialized | R25, R68, R70 |
| 12 | `12-http-server.yaml` — one immutable snapshot, SIGTERM/SIGINT | ready-line URL format; GET body parsed + exact headers; HEAD no body; POST → 405 `Allow: GET, HEAD`; `?query` → 404; snapshot immune to later commit — GET body asserted as **exact pretty-printed bytes** (2-space indent, arrays expanded, trailing LF); SIGTERM and SIGINT exit 0; invalid repo at startup → exit 1, no URL | R90, R101, R42, R59 |
| 13 | `13-http-client.yaml` — one exact validated GET, no redirects | cross-repo diff over HTTP without import; HTTP merge; malformed body → `invalid JSON`; 302 → error contains `HTTP 302`, redirect not followed; recorded request targets exact | R78, R86, R102, R89 |
| 14 | `14-cli-errors.yaml` — stable exit channels | `--version` matches `snap x.y.z`; no repo → exact `snap: not a Snap repository`; unknown command → exact `snap: invalid command or arguments`; `diff` arity → stderr contains `usage: snap diff`; unknown version in revert → contains `unknown version`; `--serve 65536` → exact `snap: invalid port: 65536` | R77, R79, R91, R45 |
| 15 | `15-repository-validation.yaml` — malformed schemas/histories/paths/edits | duplicate JSON key; revision gap (`missing a@x`); `.snap` path in patch (`path is invalid`); non-canonical base64 (`canonical base64`); underconsuming script (`does not consume old content`); prefix collision in authored result (`tree paths conflict`); base cycle (`cyclic or incomplete patch history`); no-op put (`no-op change`); adjacent inserts (`adjacent insert`); working files untouched | R41, R43, R50, R55, R56, R59, R60, R25, R52, R23 |
| 16 | `16-dot-collision.yaml` — cross-repo dot collision fails first | same dot, different patch values: cross-repo `diff --repo` and `merge` both exit 1 with `patch collision: a@x revision 1`; local files and JSON unchanged | R15, R38, R47, R86, R103 |
| 17 | `17-concurrent-creates.yaml` — later-create-wins direction independent | concurrent creates of same path; both merge directions warn `later-create-wins`, converge to the canonically-later (alice) content; trees equal | R73(4), R76, R36 |
| 18 | `18-three-way-convergence.yaml` — association-order convergence | 3 concurrent text patches (two inserts + one delete) merged in all 6 association orders; all converge to `B\nA\nend\n`; **no warnings** for line-level OT | R14, R66, R69, R71, R72, R74, R76 |
| 19 | `19-version-boundaries.yaml` — CLI versions are canonical known frontiers | repo discovery from nested cwd; leading zero and duplicate-id versions → `invalid version`; unknown but canonical → exact `snap: unknown version: (a@x->2)`; `revert "()"` (empty tree is always known) works; missing contributor → exact R100 message; `HOME: null` handling | R31, R45, R77, R88, R99, R100 |
| 20 | `20-dirty-merge.yaml` — merge refuses dirty/unsupported trees, no import | dirty → exact `snap: working tree is dirty`, repository JSON unchanged; symlink in tree → exact unsupported-entry error, still no import | R27, R89, R103, R104, R21 |
| 21 | `21-version-algebra.yaml` — closure, componentwise join, Snap order | commit result versions carry full frontier (`(a@x->1,b@x->1)`); join `(a@x->2,b@x->2)` both directions; merged content `base\nB1\nB2\nA2\n` pins replay order; diff between arbitrary known vectors incl. a non-frontier vector and a "backwards" diff | R33, R34, R36, R45, R46, R66, R86 |
| 22 | `22-ot-matrix.yaml` — pairwise OT cases (timeout 60s) | overlapping deletes deleted once; combined case exercising P-insert, Q-insert priority, retain/delete count splitting, trailing P insert (`A\n0\nB\n3\n4\nTAIL\n`); P-retain/Q-delete stays deleted; Q-insert before P-delete survives; all silent (no warnings) | R71, R72, R74, R76 |
| 23 | `23-strict-validation-matrix.yaml` — every malformed layer, pinned messages | exact `snap: repository has unknown field: unknown`; noncanonical frontier order → `/canonical/`; fractional revision → `positive safe integer`; `unreachable patch:`; `message is empty`; `changes is empty`; change `unknown field: extra`; two-key edit op → `must have one operation`; `retain 0` → `positive safe integer`; `insert is empty`; overconsuming delete → `consumes beyond old content`; delete of absent path → exact `snap: delete of absent path: f` | R43, R44, R48, R49, R51, R54, R56, R59, R30, R32 |
| 24 | `24-cli-grammar-matrix.yaml` — argument grammar for every command | extra/unknown/duplicate/misplaced args for `--version`, `init`, `config`, `status`, `log`, `commit`, `revert`, `merge`, `--serve` → exact `snap: invalid command or arguments`; `diff` arity/option errors → `snap: usage: snap diff .+` (distinct channel); `--unknown` never treated as a path | R79, R82, R86 |
| 25 | `25-config-version-path-boundaries.yaml` — canonical boundaries | `config` overwrite drops unknown fields (does not validate the old file); duplicate key in local config → `snap: duplicate JSON key …`; invalid local ID blocks global fallback; ID rejections (`two@@x`, space, `,`, `(`, `)`, `->`); version rejections (`->0`, negative, `9007199254740992` overflow, wrong order, embedded space); empty dirs and `.snap/untracked` invisible to status; UTF-8 byte path sort `nested/file < z < é < 😀`; empty commit message → exact `snap: invalid commit message` | R23, R24, R28, R30, R31, R82, R98, R99, R48, R20, R16 |
| 26 | `26-portability-and-failure-safety.yaml` — byte preservation, no mutation on bad remotes | CRLF tokenization (`"a\r\n", "b"`), NUL → `put`, Unicode text; exact cross-repo diff bytes incl. `+a\r\n`; malformed local and HTTP remotes: `merge` and `diff --repo` exit 1, zero local mutation (exact tree listing); structural patch identity across whitespace/key order → merge of "duplicate" repos is a clean no-op | R19, R41, R47, R50, R53, R86, R87, R102, R103 |
| 27 | `27-history-canonicality.yaml` — exact schemas, canonical order, base transitions | unknown patch field; patches not sorted by author; `base` containing the author's own new dot (revision ≠ base+1); insert of non-final token without LF (non-canonical token sequence); text create over present path; text edit over binary base — all exit 1, `snap: …` | R43, R44, R46, R51, R53, R57, R59 |
| 28 | `28-terminal-presentation.yaml` — exact ANSI bytes, env precedence (case env `SNAP_COLOR: always`) | exact ANSI layouts for init/commit/revert/merge/status(clean+dirty)/log(entry spacing)/diff (incl. binary yellow, `\ ` dim)/`--version` (**pins `snap 1.0.0`**)/warning `⚠`/error `✗`; `always` overrides harness `NO_COLOR=1`; `never`, `auto`+`NO_COLOR` (incl. empty), unset+piped → plain; `SNAP_COLOR=sometimes` → exact plain R95 error; serve URL plain even under `always`; status uses `−` (U+2212) for deleted; trailing-space path and message rendered verbatim | R91–R97, R95, R96, R107 |

### 2.1 Requirements with no (or weak) covering test

- **R90** default port 8765 and bare `--serve` (tests always pass port `0`).
- **R93/R108** `auto` + TTY terminal mode — impossible in the harness (pipes); the spec
  itself mandates implementation-side unit tests.
- **R102** `https://` operand recognition (harness is HTTP-only by design).
- **R28** 254-byte ID limit; control characters in IDs (only the printable rejections are
  tested).
- **R23** backslash / ASCII control characters / `.`·`..` segments in tracked paths
  (only `.snap` first-segment is tested, in 15).
- **R48** the 4096-byte commit-message limit (only the empty message is tested).
- **R85** revision overflow / dot collision at commit time.
- **R66** tie-break keys 2 (author) and 3 (revision) — never exercised; valid histories
  decide at key 1.
- **R105/R106** temp-file atomicity and partial-failure behavior (not observable by the
  harness).
- **R107** exit code 2 for internal failures (never asserted; every asserted failure is 1).
- **R37** concurrent authoring under one ID at commit time (only the import-side
  collision, test 16).
- **R109** property tests — outside the provided suite entirely.

### 2.2 Provided-suite mapping to §11's required coverage list

§11 items 1–10 and 12 map to tests 21/25 (1), 15/23/27 (2), 05 (3), 22/18 (4), 10/11/17
(5), 18/09 (6), 04/05/07/13/14 (7), 20/16/26 (8), 03/19/25 (9), 09/12/13 (10), 28 (12).
Item 11 (cross-language exchange) is explicitly out of the public harness.

### 2.3 Tests asserting beyond the spec text (tests win — recorded, not resolved here)

1. **Exact error strings.** The spec pins only the `snap: <detail>` shape (§10) and three
   exact messages (§7.7, §7.11, §8). The suite pins many more, some exactly
   (`stderr_equals`/anchored regex), some as substrings: `repository already exists`,
   `cannot initialize inside repository`, `invalid JSON`, `invalid contributor id: …`,
   `snap: working tree is clean`, `snap: working tree is dirty`,
   `snap: unsupported working tree entry: <path>`, `snap: not a Snap repository`,
   `snap: invalid command or arguments`, `snap: usage: snap diff …`,
   `snap: invalid port: 65536`, `snap: unknown version: (…)`, `invalid version: …`,
   `snap: invalid commit message`, `duplicate JSON key …`, `missing a@x`,
   `path is invalid`, `canonical base64`, `does not consume old content`,
   `tree paths conflict`, `cyclic or incomplete patch history`, `no-op change`,
   `adjacent insert`, `snap: repository has unknown field: <f>`, `…canonical…`,
   `…positive safe integer`, `unreachable patch: …`, `…message is empty`,
   `…changes is empty`, `…unknown field: extra`, `…must have one operation`,
   `…insert is empty`, `…consumes beyond old content`, `snap: delete of absent path: f`,
   `patch collision: <id> revision <n>`, `HTTP 302`.
2. **Semver pinned.** Spec says `snap <semver>`; test 28 requires exactly `snap 1.0.0`.
3. **Served body bytes pinned.** Spec §4.1 makes the writer format a SHOULD; test 12's
   `body_text_equals` pins the exact serialization of the served snapshot: 2-space
   indent, every array element (including each `[id, revision]` pair member) on its own
   line, trailing LF.
4. **Empty-file diff block.** Test 06 requires a `@@ -1,0 +1,0 @@` block (no op lines)
   for creating an empty text file; §7.6 doesn't spell this case out.
5. **`diff` grammar errors use a distinct `usage:` message** while every other command
   uses the generic `snap: invalid command or arguments` (tests 14/24); the spec treats
   all grammar errors uniformly (§7).
6. **404 for `/repository.json?query=…`** — matching is on the raw request target, not
   the path (test 12); §9 only says "other paths return 404".
7. **`config` overwrites without validating the existing file** — test 25 rewrites a
   config that contains an unknown field; §8 says an unknown field "in a file that is
   read" is an error, so `config` evidently must not read-validate before writing.
8. **`init new/repository`** creates intermediate directories (test 02); §7.1 says only
   "created if absent".

No test contradicts the spec outright; all deltas are refinements/pins.

---

## 3. Language & tooling

**Verdict:** implementation in **Scala** (user decision) in workspace `snap/scala/`
(currently absent — the skeleton task creates it). The acceptance suite itself is
language-neutral: a Node/TypeScript harness (`snap/test-harness/`, run via `tsx`) driving
the candidate as a subprocess with YAML format-1 cases. Node + npm and sbt/JVM are the
only toolchain requirements; the harness installs its own locked deps on first run.

### 3.1 Exact layout the runners expect for `--lang scala`

From `snap/run` (launcher) and `snap/run_tests` (harness wrapper) — verbatim behavior:

- **Workspace root:** `snap/scala/` (both scripts prefer `snap/solutions/scala/` if it
  exists; it does not, so `snap/scala/` is used).
- **Availability probe (`run`):** the file `snap/scala/src/main/scala/Main.scala` must
  exist at exactly that path, or `run --lang scala` dies with "scala Snap implementation
  is unavailable". Other sources may live anywhere under `src/`, but this file is the
  marker (and the mtime representative for auto language selection).
- **Build command:** `sbt -batch assembly`, executed with cwd `snap/scala/` (by
  `run_tests` unconditionally before the suite, and by `run` on demand). So the workspace
  needs `build.sbt` at `snap/scala/build.sbt` and the **sbt-assembly plugin**
  (`project/plugins.sbt`).
- **Artifact discovery (`run`):**
  `find "$root/target" -path '*/scala-*/*-assembly-*.jar' -type f | head -n 1` — the fat
  jar must land under `target/scala-<v>/` with `-assembly-` in the filename (sbt-assembly
  default: `target/scala-3.x.y/<name>-assembly-<version>.jar`). `head -n 1` means there
  must be exactly one matching jar — no cross-builds, no stale second artifact.
- **Staleness check (`run`):** rebuilds when the jar is missing, any file under
  `snap/scala/src` is newer than the jar, or `build.sbt` is newer than the jar. Files
  under `project/` do **not** trigger rebuilds — after touching plugins, rebuild manually.
- **Launch:** `exec java -jar "$jar" "$@"` — the assembly manifest must set `Main-Class`
  (set `Compile / mainClass` explicitly). No JVM flags can be injected here, and the
  harness scrubs the environment (no `JAVA_TOOL_OPTIONS`), so encoding/locale behavior
  must be handled inside the program (see risk 5).

### 3.2 Running the suite

- Full suite: `./snap/verify --lang scala` (from `/Users/mmrozek/work/AI`). `verify`
  execs `run_tests`, which npm-ci's the harness on first use, runs `sbt -batch assembly`,
  wraps the candidate as a temp script that execs `snap/run --lang scala "$@"`, and
  invokes `npx tsx src/cli.ts --candidate <wrapper>`.
- Subset / single file: `./snap/verify --lang scala --filter <text>` — substring match
  against the YAML **filename or case name** (e.g. `--filter 22-ot-matrix` for one file,
  `--filter merge` for several). There is no step-level filter.
- Other options: `--tests PATH` (alternate YAML dir), `--list` (validate + list),
  `--verbose`/`-v` (step stdout/stderr), `--keep-failed` (preserve sandbox, prints path),
  `--summary PATH` (plain-text machine-readable summary). `--lang` and `--candidate`
  cannot be combined; `--candidate /path/to/exec` bypasses the bundled build entirely
  (useful for a thin launcher during development).
- `snap/ts/` is a stub scaffold only (`src/main.ts` prints `snap: not implemented`,
  exit 1) — reference for shape, not behavior.

### 3.3 Harness assumptions that shape the implementation

- **Deterministic environment per case:** fresh sandbox; only inherited `PATH`; `HOME`
  and `TMPDIR` point into the sandbox; `NO_COLOR=1`, `LANG=C`, `LC_ALL=C`,
  `NO_PROXY=127.0.0.1,localhost`; everything else removed; case/step `env` applied on
  top (`null` removes a variable — e.g. test 19 removes `HOME`).
- **Every CLI command is a fresh process**; state lives on disk. Case timeout defaults to
  **30 s** (test 22 raises to 60 s); test 28 alone runs ~35 candidate invocations in one
  case, so per-invocation JVM startup must stay small (fat jar via `java -jar` is fine;
  never route per-command startup through sbt — `run`'s staleness check already avoids
  it once the jar is fresh).
- Output decoded as strict UTF-8; exact byte assertions including final LF; 16 MiB
  stream caps; `--serve` readiness is detected by regex on accumulated stdout, so the URL
  line must be flushed unbuffered (R90).
- `stop` sends SIGTERM/SIGINT to the process group and asserts **exit code 0** plus exact
  final streams (tests 12, 28) — the server must trap signals and exit 0 itself.
- HEAD requests are checked over a raw socket: any body bytes written for HEAD become a
  protocol violation the harness sees (test 12 asserts empty body).

---

## 4. Domain model sketch

Entities the spec implies (inventory, not design):

- **ContributorId** — validated ASCII email-shaped string (R28), spelling preserved.
- **Version / vector clock** — map id→positive revision, absent = 0 (R9, R30); canonical
  CLI text form (R31) and JSON pair-array form (R32); partial-order compare + join +
  Snap total order.
- **Dot** — `(author, revision)`; patch identity (R10, R46, R47).
- **Patch** — `{author, revision, base, message, changes}`; result = base with author
  bumped (R46).
- **Change** — `text(path, edit)` | `put(path, base64)` | `delete(path)` (R50–R52).
- **EditScript** — retain/delete/insert over LF-retaining tokens (R53–R58).
- **Repository** — `{format:1, frontier, patches}`; patches = exact sorted causal
  closure of the frontier (R40, R44); one canonical serializer (R42, test 12).
- **Tree** — path→bytes map, prefix-free by segment, UTF-8-byte-sorted (R24, R25).
- **Replay engine** — selection + ready-loop + per-patch integration (namespace pre-pass,
  per-path dispatch, OT) producing `(tree, warningSet)` (§6).
- **Warning** — unique `(path, reason)` pairs, reasons ∈ {delete-wins, later-create-wins,
  later-put-wins, namespace-wins, put-wins} (R73–R75).
- **Working tree scanner** — regular files only, fails on unsupported entries (R18–R21,
  R104); clean/dirty determination (R26).
- **Config** — local/global lookup with strict precedence (R98–R100).
- **Diff engine** — canonical token diff (§5), shared by commit, display, and OT.
- **CLI dispatcher** — grammar per §7 (R79), repo discovery (R77), operand resolution
  (R78).
- **HTTP server/client** — snapshot server (R90, R101); single-GET read-only client
  (R102).
- **Presentation layer** — plain vs terminal renderer selected by
  SNAP_COLOR/NO_COLOR/TTY, orthogonal to execution (R92–R97).

### 4.1 Vector-clock semantics as specified (quotes are load-bearing)

**Increment rule** (§4.2):

> ```
> revision = B[author] + 1
> result   = B with result[author] = revision
> ```
> All other result components equal the base. One patch therefore increments one
> contributor.

**Compare rule** (§3.3) — absent component is zero; for every contributor `c`:

> - `V = W` iff every component is equal.
> - `V < W` (before) iff every `V[c] <= W[c]` and at least one is strict.
> - `V > W` (after) is the converse.
> - `V || W` (concurrent) iff `V != W`, `V` is not before `W`, and `W` is not before `V`.
> - `join(V, W)[c] = max(V[c], W[c])`.

All four outcomes must be distinct in the type (R35).

**Merge rule** (§7.8 + §6): union the patch sets (structural-equality dedupe per dot,
different value = corruption), join the frontiers componentwise, replay the joined
closure from the empty tree, install, write. No merge patch, no revision increment.

### 4.2 The deterministic tie-breaks (exact spec wording)

These four rules are where implementations diverge; quote them, implement them verbatim.

1. **Snap order** (§3.4):
   > Take the sorted union of contributor IDs and lexicographically compare the counter
   > at each ID. The first unequal counter decides.

   (Contributor IDs sort by unsigned UTF-8 bytes, §3.2. A *lower* counter at the first
   differing ID means *earlier* in Snap order — so `(bob->1)` precedes `(alice->1)`
   because at `alice@x` the counters are 0 vs 1. Tests 9/10/11/17/21 all depend on this.)

2. **Replay ordering of ready patches** (§6.1):
   > choose the least ready patch by:
   > 1. Snap order of their result versions;
   > 2. unsigned UTF-8 order of author; then
   > 3. numeric revision.

3. **Diff deletion-on-tie** (§5):
   > Otherwise choose `delete 1` when `D(i + 1, j) <= D(i, j + 1)`.

   > This recurrence and deletion-on-tie rule define the output. Implementations MAY use
   > Myers, Hirschberg, or another optimization only if it produces the same script,
   > including for repeated equal lines.

   (Test 05 goldens the repeated-line case: `a,b,a → b,a,a` must yield
   `delete 1, retain 2, insert [a]`.)

4. **OT concurrent-insert priority** (§6.3):
   > The `Q insert` row has priority. Concurrent inserts at one cursor therefore appear
   > in canonical integration order. Deletion consumes only base tokens, so concurrent
   > inserted text survives.

   Full table (P = incoming edit, Q = aggregate context edit `diff(B, C)`):

   | Next operations | Output in transformed `P` | Consumption |
   |---|---|---|
   | `Q insert` | `retain(length(Q insert))` | Q only |
   | `P insert` | same `P insert` | P only |
   | `P retain`, `Q retain` | `retain(min)` | both |
   | `P delete`, `Q retain` | `delete(min)` | both |
   | `P retain`, `Q delete` | nothing | both |
   | `P delete`, `Q delete` | nothing | both |

   And (§6.3): "Snap performs this transform once against the aggregate context edit,
   not once per historical patch."

5. **Path-level winner rules** (§6.4), resolved *in this order* for base path `B`,
   current canonical path `C`, incoming authored result `T`:
   > 1. If `C` and `T` are identical, keep `C` and emit no warning.
   > 2. If `T` is absent, the incoming delete wins (`delete-wins`).
   > 3. If `B` is present and `C` is absent, the earlier concurrent delete wins
   >    (`delete-wins`).
   > 4. If `B` is absent and `C` and `T` are present, the incoming (canonically later)
   >    create wins (`later-create-wins`).
   > 5. If the incoming change is `put`, the incoming atomic replacement wins
   >    (`later-put-wins`).
   > 6. Otherwise `P` is text and `C` is non-text, so the incompatible current content
   >    wins (`put-wins`).

   > "Later" always means canonical integration order, never wall-clock time.

6. **Namespace pre-pass** (§6.2) — runs *before* the per-path rules and overrides them:
   > First resolve namespace conflicts for the patch as a whole. Let `S` be the paths
   > that `P` makes present, and let `C'` be `C` with every path that `P` authored as a
   > deletion removed. If a path in `S` has a different current ancestor or descendant in
   > `C'`, mark the incoming path for installation as its authored result `T` and mark
   > every conflicting current path for removal. Each removed path emits
   > `namespace-wins`.

7. **Warning aggregation** (§6.4):
   > Replay returns the set of unique warning pairs sorted by path, then reason. Line OT
   > emits no warning. Merge prints only pairs present in the joined replay but absent
   > from the pre-merge local replay.

   (So merge computes warnings for **two** replays: pre-merge local frontier and joined
   frontier, then set-subtracts.)

---

## 5. Ambiguities & open questions

Numbered for the user; each has a suggested resolution. None block skeleton work; Q1–Q3
should be settled before the error-handling and serializer tasks.

1. **Error-message catalog (spec §10 vs tests everywhere).** The spec pins only the
   `snap: <detail>` shape plus three exact messages; the tests pin ~35 more, many via
   `stderr_contains`/regex, leaving the full sentence open (e.g. what surrounds
   `missing a@x`, `path is invalid`, `canonical…`). *Suggested resolution:* treat every
   tested string as canonical, fix the complete wording of each diagnostic in DESIGN.md
   as a single message table, and use plain `snap: <detail>` one-liners for everything
   untested.
2. **`--version` semver (§7.10 vs test 28).** Spec says any semver; test 28 requires
   exactly `snap 1.0.0`. *Suggested resolution:* hardcode `1.0.0`.
3. **Canonical writer bytes (§4.1 SHOULD vs test 12).** Test 12 byte-pins the served
   `GET /repository.json` body (2-space indent, one array element per line, trailing
   LF). The spec does not say the served body must equal the on-disk bytes.
   *Suggested resolution:* one canonical serializer (matching test 12's exact style)
   used for both `repository.json` writes and the served snapshot; serving the on-disk
   bytes then also works because we always write canonically.
4. **Binary diff trigger (§7.6).** "For a binary change, print one line" — undefined
   when one side is text and the other binary (tests only cover binary↔absent).
   *Suggested resolution:* render the binary line whenever either present side is
   non-text; text block only when both present sides (or the single present side) are
   text.
5. **`--serve` port validity (§7.9 vs test 14).** Spec gives default 8765 and `0`; test
   pins `snap: invalid port: 65536`. Range and non-numeric handling are open.
   *Suggested resolution:* accept integers 0–65535 in canonical decimal; anything else →
   `snap: invalid port: <arg>`, exit 1.
6. **`config` without a repository (no `--global`) (§7.2).** Error is unspecified and
   untested. *Suggested resolution:* reuse `snap: not a Snap repository`.
7. **Precedence between dirty-tree check and remote validation in `merge` (§7.8/§10).**
   Test 20 shows dirty beats a valid remote; test 26 shows a malformed remote beats a
   clean tree; both-dirty-and-malformed is unobserved. *Suggested resolution:* check
   local validity → dirty tree → load/validate remote (any order matching both tests;
   fix one in DESIGN.md).
8. **"ASCII control character" extent (§2, §3.1, §4.2).** Does it include DEL (0x7F)?
   *Suggested resolution:* treat 0x00–0x1F and 0x7F as control everywhere (paths, IDs,
   messages — messages additionally allowing tab/LF).
9. **Nested `.snap` below the first segment (§2).** Path rule bans only a *first*
   segment `.snap`, so a working tree containing `sub/.snap/...` (a nested repo) would be
   tracked. Probably intended (spec is explicit: "no first segment equal to `.snap`"),
   but surprising. *Suggested resolution:* implement as written; no exclusion below the
   root.
10. **Snap-order ties in the ready set (§6.1 keys 2–3).** "Valid histories normally
    decide at the first key" — we found no valid history where two distinct ready
    patches share a result version, and no test exercises keys 2/3.
    *Suggested resolution:* implement all three keys verbatim anyway (cheap, and guards
    against our analysis being wrong).
11. **HTTP client details (§9).** Timeouts, maximum body size, and acceptable
    `Content-Type` for the *client* GET are unspecified (server's is pinned). Tests need
    only exact-URL GET, status-200 enforcement, and no redirects.
    *Suggested resolution:* generous fixed timeout, no content-type requirement on
    responses, cap body reads sanely; document in DESIGN.md.
12. **`revert to <version>` in terminal-mode log and 4096-byte interplay (§4.2/§7.7).**
    Generated revert messages "may be longer" than 4096 — confirm the repository
    validator does *not* enforce 4096 (it's a commit-time limit on user input only).
    Tests 07 use short versions. *Suggested resolution:* validator enforces only R48's
    character rules; 4096 is checked solely in `snap commit`.

Per the launching instructions: `snap/AGENTS.md` invites amending SPEC.md when ambiguity
surfaces — our CLAUDE.md overrides that; all of the above go to the user, and `spec/`
(`snap/`) stays untouched.

---

## 6. Risk notes

The five places most likely to produce subtle bugs:

1. **Canonical diff tie-breaking on repeated lines (R61–R64, test 05).** Any
   off-the-shelf or hand-rolled Myers variant that doesn't reproduce the exact
   `D(i+1,j) <= D(i,j+1)` deletion-on-tie walk produces a *valid but different* script —
   goldens fail, and worse, OT/merge results silently diverge (the diff feeds `Q`).
   Safest: implement the spec's DP + walk literally first; optimize only against a
   property test for script equality.

2. **The replay pipeline's rule ordering (R66–R75, tests 9–11, 17, 18, 21, 22).**
   Convergence depends on getting *all* of these right together: Snap-order of result
   versions (lower counter at first differing sorted ID = earlier), namespace pre-pass
   before per-path rules, "identical C/T" collapse before OT, aggregate `Q = diff(B,C)`
   (never per-patch chaining), Q-insert priority, delete-consumes-base-only, and
   warning set-subtraction against the pre-merge replay. Each is individually testable;
   an error in any one shows up only as a wrong merged byte in tests 18/21/22.

3. **Byte-stable surfaces (R42, R84, R87, R97; tests 04, 05, 12, 26, 28).** Four exact
   formats: (a) the canonical JSON serialization (test 12 pins every line break);
   (b) diff rendering including `\ No newline at end of file`, CRLF-bearing tokens, and
   the empty-file `@@ -1,0 +1,0 @@` block; (c) log escaping order (`\\` before `\t`
   before `\n`); (d) terminal ANSI layouts — note the U+2212 `−` (not ASCII hyphen) for
   deleted status rows, the double space after `Snap status`, entry-separating blank
   line in log, and warning/error styling on stderr. Also path *sorting must be unsigned
   UTF-8 bytes* — Java/Scala `String.compareTo` is UTF-16 code-unit order, which differs
   for supplementary characters (test 25's `é` < `😀` passes either way, but e.g.
   U+FFFD vs U+10000 would not): compare by code point or encoded bytes.

4. **Strict JSON layer (R41, R43, R30; tests 15, 23, 25, 27).** Needs a duplicate-key-
   detecting parser (standard parsers silently drop duplicates), rejection of
   non-integer numbers (`1.5`) and of `9007199254740992` (parsing to `Double` rounds it
   to 2^53 and can *accept* it — validate from the decimal text or use exact integer
   parsing), unknown-field detection at every level with the field name in the message,
   and validation-before-mutation ordering (tests assert untouched files/JSON after
   every failure).

5. **JVM/process realities under the harness (tests 12, 19, 25, 28; §3.3 above).**
   The harness runs the candidate with `LC_ALL=C`, a scrubbed env, and `java -jar` with
   no injectable JVM flags: (a) stdout/stderr must be written as UTF-8 regardless of
   platform defaults, and the `--serve` URL flushed immediately; (b) filenames like `é`
   and `😀` must survive `sun.jnu.encoding` under the C locale (fine on macOS, a known
   trap on Linux JVMs — handle/verify explicitly); (c) SIGTERM/SIGINT must produce **exit
   code 0** — the JVM's default is 128+signal (143/130), so install signal handlers that
   exit(0), not just shutdown hooks; (d) ~35 JVM startups inside one 30-second case
   (test 28) leave no room for slow startup — keep the fat jar lean and do no eager work
   before dispatch.

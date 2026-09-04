# DESIGN — snap (Scala implementation)

Architecture for the Scala implementation of snap, living in `snap/scala/`. The contract
is `snap/SPEC.md` + `snap/tests/` (run via `./snap/verify --lang scala`); requirement ids
(`R…`) and open-question numbers (`Q…`) refer to `docs/plan/SPEC-NOTES.md`. Tasks cite
sections of this document — section numbers are stable, append-only.

---

## §1 Principles

1. **Determinism is the product** (CLAUDE.md ground rule 3). The entire domain core is
   pure: no wall-clock, no `getenv`, no randomness, no unordered-collection iteration
   feeding any decision. Effects (filesystem, HTTP, env, TTY-ness, signals) live at the
   edges and are threaded in as values.
2. **One canonical implementation per concept.** One diff engine (feeds commit, display,
   and OT — R61), one JSON serializer (feeds `repository.json` writes and the served
   snapshot — R42/Q3), one UTF-8-byte comparator (feeds path sort, version sort, snap
   order), one error catalog.
3. **Tests are the contract's edge, not its extent** (holdout assumption). Every
   requirement in SPEC-NOTES §1 is implemented even when no provided test covers it
   (SPEC-NOTES §2.1 lists the gaps); project tests cover those.
4. **Illegal states unrepresentable** where cheap (`docs/SCALA-CONVENTIONS.md` is
   binding): validated types (`ContributorId`, `Version`, `SnapPath`) are constructed
   only through their validating factories.

## §2 Module layout

Workspace `snap/scala/` (layout dictated by the runners — SPEC-NOTES §3.1):

```
snap/scala/
  build.sbt                     # Scala 3.3 LTS, mainClass, assembly, lint config
  project/plugins.sbt           # sbt-assembly, sbt-scalafmt, sbt-scalafix
  .scalafmt.conf  .scalafix.conf
  src/main/scala/
    Main.scala                  # required probe path (snap/run checks this exact file);
                                # thin: builds Env, delegates to snap.cli.Cli, maps exit code
    snap/core/                  # PURE — no java.io/nio/net imports
      Ids.scala                 #   ContributorId (R28–R29), revision bounds (R30)
      Version.scala             #   vector clock: compare/join/snap-order/canonical text (§3)
      Path.scala                #   SnapPath validation (R23), Utf8Order, prefix-free (R25)
      Tree.scala                #   path→bytes map + segment trie queries
      TextTokens.scala          #   text detection + tokenization (R53), token canonicality (R57)
      EditScript.scala          #   retain/delete/insert ops, script validation + apply (R54–R58)
      Diff.scala                #   canonical token diff (§4)
      Ot.scala                  #   transform P through Q (§5)
      Patch.scala               #   Patch/Change/Dot, result computation (R46)
      Repo.scala                #   Repository value + validation §4.5 (R59–R60)
      Replay.scala              #   ready-loop + integration + warnings (§5)
      Errors.scala              #   SnapError ADT + message catalog (§8)
    snap/json/                  # strict JSON (§6): Json AST, Parser, Canonical writer,
                                # RepoCodec / ConfigCodec (typed decode, unknown fields)
    snap/fs/                    # WorkTree scanner (R18–R21, R26, R104),
                                # Materialize (install target tree, §7 mutation order),
                                # Store (repository.json/config read + atomic write, R105)
    snap/http/                  # Server (R90, R101), Client (R102)
    snap/cli/                   # Cli (grammar R79, dispatch), Commands (§7.1–7.10),
                                # Presentation (plain/terminal, R92–R97), Env (effect boundary)
  src/test/scala/               # munit + scalacheck: unit, golden, property tests
```

Dependency direction: `core` depends on nothing; `json` on `core` (error types);
`fs`/`http` on `core`+`json`; `cli` on everything. `Main.scala` is the only place that
touches `System.exit`, real env, and real streams.

**`Env`** (in `snap/cli/`) is the effect boundary record, built once in `Main`:
cwd, env map (`HOME`, `SNAP_COLOR`, `NO_COLOR`), per-stream TTY probe, stdout/stderr
sinks. Everything below `Main` takes `Env` (or narrower slices of it) as a value —
this is what makes R108's `auto`-selection unit tests possible and keeps the core pure.

## §3 Vector-clock semantics (core — verbatim from spec)

`Version` = immutable sorted map `ContributorId → Long` with **no zero entries** (R9,
R30); absent = 0. All maps ordered by `Utf8Order` (unsigned byte order of the UTF-8
encoding — for the ASCII-only ids this equals natural `String` order, but one comparator
is used everywhere on principle, see §9 gotcha 1).

- **Increment** (R46): `revision = base(author) + 1`; `result = base + (author → revision)`.
- **Compare** (R33): four-outcome ADT `enum Ord { Equal, Before, After, Concurrent }` —
  the type preserves all four outcomes (R35). Computed over the union of keys, absent=0.
- **Join** (R34): componentwise max.
- **Snap order** (R36, quote): *"Take the sorted union of contributor IDs and
  lexicographically compare the counter at each ID. The first unequal counter decides."*
  Lower counter at the first differing id (in `Utf8Order` of ids) = earlier. Total order;
  extends causal order; no other meaning.
- **Canonical text form** (R31): strict parse — duplicate ids, explicit zeroes, leading
  zeroes, overflow (> 9007199254740991), invalid ids, whitespace, noncanonical order are
  all errors. Print is the exact inverse.
- **JSON form** (R32): ordered array of `[id, revision]` pairs in canonical id order.

## §4 Canonical diff (core)

Literal implementation of the spec's recurrence (R61–R64): compute `D(i,j)` bottom-up
(two-row DP is fine — the walk needs `D(i+1,j)` and `D(i,j+1)`, so keep the full table
or recompute rows; **decision: full `(n+1)×(m+1)` Int table**, correctness first, token
counts in scope are small), then walk from `(0,0)`:
equal → `retain 1`; else `delete 1` **iff `D(i+1,j) <= D(i,j+1)`** (deletion-on-tie —
this exact `<=` is load-bearing, test 05 goldens `a,b,a → b,a,a(noLF)` as
`[delete 1, retain 2, insert ["a"]]`); else `insert [B[j]]`; exhausted side → bulk
insert/delete; coalesce adjacent same-kind ops. No Myers/Hirschberg unless a later task
proves script-equality by property test (none planned — YAGNI).

## §5 Replay and OT (core)

`Replay.materialize(patches, V)` → `(Tree, SortedSet[Warning])`:

1. **Select** every patch `(c,n)` with `n <= V(c)`; missing base ⇒ validation error (R65).
2. **Ready-loop** (R66): repeatedly integrate the least ready patch by (1) Snap order of
   result versions, (2) `Utf8Order` of author, (3) numeric revision — all three keys
   implemented verbatim even though valid histories decide at key 1 (Q10 resolution).
   If no patch is ready before completion → `cyclic or incomplete patch history` (R60).
3. **Integrate one patch `P`** (R67–R70) against base tree `B` (memoized
   `materialize(P.base)` — decision §7) and canonical-so-far `C`:
   a. **Namespace pre-pass** (R68): `S` = paths `P` makes present; `C'` = `C` minus
      `P`'s authored deletions; any `s ∈ S` with a different current ancestor/descendant
      in `C'` → install authored `T(s)`, remove every conflicting current path, emit
      `namespace-wins` per removed path. Overrides the per-path rules.
   b. **Per remaining path** (R69, in order): identical in `B` and `C` → apply authored
      change directly; identical in `C` and `T` → keep (no warning, collapses identical
      concurrent changes *before* OT); `B`,`C`,`T` all text and `P` a text change →
      transform through **aggregate** `Q = diff(B,C)` (once, never per historical
      patch — R72) and apply to `C`; otherwise path-level rules (R73, exact order):
      1. `C`=`T` keep · 2. `T` absent → `delete-wins` · 3. `B` present ∧ `C` absent →
      `delete-wins` · 4. `B` absent ∧ `C`,`T` present → `later-create-wins` ·
      5. incoming is `put` → `later-put-wins` · 6. else → `put-wins`.
   c. Apply all of `P`'s resulting path changes **together** (R70).
4. **Warnings** (R74): unique `(path, reason)` pairs, sorted by path then reason. OT
   emits none. `merge` prints `joinedWarnings -- preMergeLocalWarnings` (R75) — two
   replays, then set subtraction.

**OT transform** (R71): the spec's 6-row table verbatim; `Q insert` row has priority;
deletion consumes only base tokens; counts split as needed; both scripts must consume the
same base token count; trailing insertions processed; output coalesced.

## §6 Strict JSON layer

Layered: a battle-tested tokenizer underneath, our contract semantics on top (D2).
**jawn-parser** (Typelevel, Scala — the parser under circe; no AST/derivation baggage)
handles RFC 8259 lexing: string unescaping incl. surrogate pairs, number grammar,
malformed input. jawn parses through a caller-supplied `Facade`, which is exactly our
seam — the facade builds our `Json` AST directly and we own everything the contract pins:

- **Facade/AST builder**: object contexts receive each key as it arrives — uniqueness
  checked there (`duplicate JSON key <k>` — R41; we own the message and the key name, no
  exception-message scraping); numbers arrive as **raw decimal text**
  (`jnum(s, decIndex, expIndex)`) and the AST keeps that text. Typed decoding rejects
  any number that is not an integer in `[-(2^53-1), 2^53-1]` **judged from the text**,
  never via `Double` round-trip (`9007199254740992` must be rejected — gotcha 4). Any
  jawn parse error maps to the `invalid JSON` diagnostic class (tests pin it as a
  substring).
- **Typed decode** (`RepoCodec`, `ConfigCodec`): exact schemas; unknown fields are errors
  naming the field (R43, test 23 `snap: repository has unknown field: unknown`); every
  string validated by the core factories.
- **Canonical writer** (R42/Q3): 2-space indent, every array element on its own line
  (including each member of a `[id, revision]` pair), trailing LF — the exact style
  test 12 byte-pins. Used for **all** `repository.json` writes and the served snapshot.

## §7 Filesystem, mutation order, HTTP

- **WorkTree scanner**: walk the repository root, skip `.snap/` at the root only (R16;
  nested `sub/.snap` is tracked — Q9), regular files only; any symlink/other entry fails
  with `snap: unsupported working tree entry: <path>` before anything else happens
  (R21, R104). Produces a `Tree` (sorted). Clean ⇔ exact equality with current tree (R26).
- **Materialize/install** (R70, R105): compute target tree fully, then: update working
  files first (remove blockers, create dirs, write files, remove newly-empty dirs),
  replace `repository.json` via same-directory temp file + atomic move **only after**
  the working-tree update succeeded. `commit` performs only the metadata replacement.
  Validation failures never mutate (R103).
- **Base-tree memoization**: `materialize` memoizes trees per version within one command
  (plain `Map[Version, Tree]` accumulated in the replay fold). Correctness first;
  optimize only against a measured timeout.
- **HTTP server** (R90, R101): `com.sun.net.httpserver.HttpServer`, bind `127.0.0.1`,
  snapshot = canonical serialization of the validated startup repository (bytes fixed at
  startup). `GET /repository.json` → 200, `Content-Type: application/json; charset=utf-8`;
  HEAD same status/headers, zero body bytes; any other raw request target (including
  `/repository.json?q=…`) → 404; other methods → 405 + `Allow: GET, HEAD`. URL line
  printed and **flushed** before serving. SIGINT/SIGTERM → exit **0** via
  `sun.misc.Signal` handlers (JVM default would be 130/143 — gotcha 6).
- **HTTP client** (R78, R102): `java.net.http.HttpClient`, redirects **NEVER**
  (non-200 → error containing `HTTP <status>`), one GET of the exact URL, body parsed
  and validated like a local repository. `http://` and `https://` prefixes both
  recognized (https untested but specified).

## §8 CLI, errors, presentation

- **Grammar** (R79): exact positional grammar per command, options at most once, in
  documented positions. Every grammar violation → `snap: invalid command or arguments`,
  **except** `snap diff` arity/option errors → `snap: usage: snap diff <old> <new>
  [--repo <repository>]` (distinct channel — tests 14/24; exact wording matched to the
  tests' regex during implementation).
- **Repo discovery** (R77): walk cwd → filesystem root for `.snap/`.
- **Errors**: single `SnapError` ADT; every emission site uses the catalog in
  `Errors.scala` — one object holding every diagnostic string, the ~35 test-pinned ones
  (SPEC-NOTES §2.3.1) written verbatim (Q1). Untested diagnostics: plain one-line
  `snap: <detail>`. Expected failures exit 1; a top-level catch-all in `Main` maps any
  unexpected exception to exit 2 with `snap: internal error: <detail>` (R107).
- **Presentation** (R92–R97): `Renderer` trait with `Plain` and `Terminal`
  implementations selected **per stream** at startup: `SNAP_COLOR` unset/`auto` →
  terminal iff that stream is a TTY and `NO_COLOR` absent (presence, even empty, forces
  plain — R94); `always` → terminal on both, overrides `NO_COLOR`; `never` → plain; any
  other value → plain error `snap: SNAP_COLOR must be auto, always, or never` before
  command execution (R95). The `--serve` URL is always plain (R96). Terminal layouts
  exactly per §7.11 (status uses U+2212 `−` for deleted; `Snap status` header has a
  double space; log entries separated by a blank line — gotcha 8). Presentation never
  changes execution, effects, warning selection/order, or exit status (R92).
- **TTY detection** (R93/R108): behind a `Tty` trait in `Env`. Real implementation:
  child process `/bin/sh -c "test -t 1"` (resp. `-t 2`) with `Redirect.INHERIT` on that
  stream — the child's fd *is* ours, so its `test -t` answers for our stream; exit 0 =
  TTY. Probed lazily, only when `SNAP_COLOR` is unset/`auto` and `NO_COLOR` is absent
  (the harness always sets `NO_COLOR=1` or `SNAP_COLOR`, so no probe cost in the suite).
  Unit tests (R108) inject fake `Tty` values and assert the selection matrix.

## §9 Locked decisions

| # | Decision | Choice | Rationale / ref |
|---|---|---|---|
| D1 | Language / toolchain | Scala 3.3 LTS, sbt, sbt-assembly fat jar, Java 17 | user decision; runner layout SPEC-NOTES §3.1 |
| D2 | Runtime dependencies | **Scala-first** (user, 2026-09-04): prefer Scala libraries for commodity subproblems when they don't obscure contract-pinned behavior. Adopted: **jawn-parser** (Typelevel) as the JSON tokenizer under our AST (§6). Explicit exceptions, each with a reason: HTTP stays JDK (`java.net.http`, `com.sun.net.httpserver`) — Scala HTTP stacks (http4s, zio-http) bring an effect runtime, disproportionate for one GET + a two-route snapshot server under the startup budget (gotcha 9); CLI parsing hand-rolled — every grammar error string is test-pinned; signals via `sun.misc.Signal` — no alternative. | user direction; §6; each new runtime dep = new row here |
| D3 | Test dependencies | munit + scalacheck (test scope only) | property tests are mandatory (R109, CLAUDE.md); excluded from assembly |
| D4 | Error strategy | `SnapError` ADT + `Either`; no exceptions for control flow; exit 2 = top-level catch-all only | conventions; R107 |
| D5 | Error message catalog | every test-pinned string verbatim in `Errors.scala`; untested → `snap: <detail>` | Q1 |
| D6 | `--version` output | hardcoded `snap 1.0.0` | Q2; test 28 pins it |
| D7 | JSON writer | one canonical serializer (test 12's exact style) for disk writes and served body | Q3 |
| D8 | Binary diff trigger | binary line iff any present side is non-text; text block only when all present sides are text | Q4 |
| D9 | `--serve` port | canonical decimal integer 0–65535; else `snap: invalid port: <arg>`, exit 1 | Q5; test 14 |
| D10 | `config` without repo (no `--global`) | `snap: not a Snap repository` | Q6 |
| D11 | merge/diff-repo failure precedence | local parse+validate → working-tree scan (unsupported/dirty) → remote load+validate → dot cross-check → replay → write | Q7; consistent with tests 20 and 26 |
| D12 | "ASCII control character" | 0x00–0x1F **and 0x7F**, everywhere (paths, ids; messages additionally allow tab+LF) | Q8; DEL is an ASCII control char |
| D13 | Nested `.snap` below root | tracked (spec says *first* segment) | Q9 |
| D14 | Replay ordering keys | all three keys implemented verbatim | Q10 |
| D15 | HTTP client limits | 30 s request timeout, 64 MiB body cap, any Content-Type accepted | Q11; untested, generous |
| D16 | 4096-byte message limit | enforced **only** in `snap commit`; repository validator checks only R48 character rules | Q12; spec: revert messages "may be longer" |
| D17 | Version representation | `Version` wrapping an id-sorted immutable `Vector[(ContributorId, Long)]`-backed map; zero entries unrepresentable | §3; deterministic iteration by construction |
| D18 | Diff algorithm | literal spec DP, full table, no optimized variant | §4; test 05 golden; risk note 1 |
| D19 | Base-tree strategy | memoized `materialize` per version within a command | §7; correctness first |
| D20 | TTY probing | `/bin/sh -c "test -t N"` child with fd inheritance, behind injectable `Tty` trait | §8; JDK 17 has no per-stream isatty |
| D21 | Signal handling | `sun.misc.Signal` INT/TERM handlers → `System.exit(0)` | §7; harness asserts exit 0 |
| D22 | Console encoding | stdout/stderr wrapped as UTF-8 `PrintStream`s in `Main`, unconditionally | gotcha 7; harness runs `LC_ALL=C` |
| D23 | Comparators | single `Utf8Order` (unsigned UTF-8 byte order) for paths, ids, version sort, snap order | §3; risk note 3 |

| D24 | Empty `HOME` | treated as unavailable, same as absent (never resolved against cwd) | phase-1 review CR2; §8 says "absent", empty has no sane resolution |
| D25 | Root `.snap` symlink | discovery/init require a real directory (NOFOLLOW); the scanner reports a root `.snap` symlink as an unsupported entry — the metadata exclusion applies only to the real directory | phase-1 review CR5; §2 MUST-report / MUST-NOT-follow |
| D26 | Integer-ness of JSON numbers | judged from the decimal spelling: `1.0`, `1e2` are rejected wherever an integer is required | phase-1 review CR13; §4.1 "non-integer numbers … are errors"; our writer never emits such spellings |
| D27 | §6.2 namespace pre-pass, the set `S` | `S` = paths the incoming patch **creates** — absent in `B`, present in its authored result `T`. A path already present in `B` is not *made* present by an edit or a `put`, so it falls through to the §6.4 path-level rules | T16; core-semantics reading escalated to and **confirmed by the user (2026-09-04)**. "makes present" reads as *causes to be present*, and the pre-pass is the ancestor/descendant analogue of rule 4, which is itself gated on "`B` is absent". The wider reading (any path present in `T`) would let a mere edit of `a` delete a concurrent `a/b` and thereby invert rule 3's principle that an earlier concurrent delete beats an incoming modification. Test 11 exercises create-vs-create only, so the contract does not discriminate — holdout risk accepted |

| D28 | `--`-prefixed tokens in operand positions | an unknown-option grammar error for **every** command, uniformly — never consumed as free-form operand text. `diff` reports it through its own `usage: snap diff …` channel, everything else through `invalid command or arguments` | phase-2 review finding 2. §7's preamble is unqualified ("Unknown options, extra operands, and missing option values are errors"), and test 24 pins `init --unknown` as an error *and* asserts `path_not_exists: --unknown` — so the contract requires a `--`-shaped token not to be taken as an operand even where the operand is free-form text. Accepted cost: a commit message or repository path beginning with `--` is unreachable from the CLI, since the spec defines no `--` separator; the contract already accepts that cost for `init`'s path |

New runtime dependencies require a new row here first (conventions rule).

## §10 Known gotchas (per module)

1. **`core/Path` — UTF-16 vs UTF-8 order:** Scala/Java `String` comparison is UTF-16
   code-unit order; differs from UTF-8 byte order for supplementary characters
   (U+E000..U+FFFF sort *after* surrogate-pair characters in UTF-16). `Utf8Order`
   compares by code point (equivalent to byte order) — never `String.compareTo`.
2. **`core/Diff` — the tie:** `<=` in `D(i+1,j) <= D(i,j+1)` chooses delete; a `<` here
   passes most tests and silently corrupts merges later (diff feeds `Q`). Golden test 05
   plus a dedicated unit test on `a,b,a → b,a,a`.
3. **`core/Replay` — snap-order direction:** lower counter at the first differing id =
   earlier. `(bob→1)` precedes `(alice→1)` (at `alice…` the counters are 0 vs 1).
   Tests 09/10/11/17/21 all break if this flips.
4. **`json/Parser` — 2^53:** `9007199254740992.toDouble == 9007199254740991.toDouble`
   is false but `Double` parsing *accepts* the overflow value; judge integer-ness and
   range from the decimal text (test 25 pins the overflow rejection).
5. **`json/Parser` — duplicate keys:** standard parsers keep the last value; ours must
   error with the key name (tests 15/25).
6. **`http/Server` — HEAD:** the harness reads the raw socket; a single body byte on
   HEAD fails test 12. With `HttpServer`, respond to HEAD with explicit `Content-Length`
   header and `sendResponseHeaders(200, -1)`, writing nothing.
7. **JVM under scrubbed env:** `LC_ALL=C`, no `JAVA_TOOL_OPTIONS`, launched as plain
   `java -jar`: platform default charset must never be relied on — all file reads/writes
   are byte-level, all text explicitly UTF-8; filenames with `é`/`😀` (test 25) must
   round-trip (verify `sun.jnu.encoding` behavior on this machine's JVM in T10).
8. **`cli/Presentation` — exact bytes:** U+2212 `−` (not `-`) for deleted status rows;
   double space after `Snap status`; blank line *between* log entries only; escape
   order in log messages `\\` → `\t` → `\n`; `\ No newline at end of file` styled dim;
   empty plain output stays empty (no stray reset codes). Test 28 pins every byte.
9. **`cli` — startup cost:** test 28 runs ~35 JVM starts in 30 s. No eager work before
   dispatch (no repository read for `--version`; no config read unless the command needs
   it). Keep `Main` thin.
10. **`fs/Store` — atomicity:** same-directory temp file + `ATOMIC_MOVE` (R105);
    cross-device moves can't happen by construction (same dir).

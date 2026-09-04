# T15 pre-commit review — OT transform (SPEC §6.3)

- **Date / scope:** 2026-09-04, staged working-tree diff on `main` (base `d7effa7`):
  `snap/core/Ot.scala` (new, 116 lines), `snap/core/OtSuite.scala` (new, 323 lines),
  `snap/core/Errors.scala` (`// T15 additions` blocks, additive), `tasks/T15-ot-transform.md`.
  T10 lives in a parallel worktree and is absent here — irrelevant to OT.
- **Reviewed against:** SPEC §6.3 (the 6-row table, R71–R72), §6.5, §4.4 (R54–R57), §6.2
  step 3; DESIGN §5 (OT paragraph); SPEC-NOTES §4.2 item 4; the task file's acceptance
  criteria and Notes; YAML fixtures `09-merge-text.yaml`, `18-three-way-convergence.yaml`,
  `22-ot-matrix.yaml`.
- **Verification (run independently by the reviewer):**
  - `sbt -batch scalafmtCheckAll "scalafixAll --check" test` → exit 0; **project suite
    380 passed, 0 failed, 0 errors** (OtSuite 29/29: 16 directed, 6 fixtures, 7 properties
    at 300 samples). The staged Errors.scala (T07 ∪ T15 union) compiles clean — no
    conflict artifacts; both `T07 additions` and `T15 additions` blocks present
    (Errors.scala:284, 322, 385, 392, 596, 619).
  - Second run of the lint gate alone → both `[success]`, exit 0.
  - `testOnly snap.core.OtSuite` twice more in a fresh JVM (fresh ScalaCheck seeds each
    run) → 29/29 both runs. Repeated-run determinism exercised, not just asserted.
  - Provided tests 09/18/22 need `merge` (T16/T17) and cannot run yet; their pinned bytes
    are covered by the lifted fixtures, all six of which I re-derived by hand (below).

## Findings

### Row priority and dispatch order (R71) — no findings

- `Ot.scala:51-96`: dispatch is literally the table's priority. Case 1
  (`(_, Insert :: qt)`) matches **any** `ps` — including `Nil` and insert-headed — so the
  `Q insert` row fires whenever Q's next unconsumed op is an insert, exactly the spec's
  priority clause. Case 2 (`(Insert :: pt, _)`) is reachable only when Q's head is not an
  insert (case 1 already claimed those), i.e. Q-head retain/delete or `Nil` — the correct
  residual for the `P insert` row. Rows 3–6 require both heads retain/delete, so no
  ordering among them or against case 2 can change behavior; the only load-bearing
  orderings (Q-insert before P-insert; both inserts before the `(Nil, Nil)` terminator and
  the mismatch catch-all) are pinned by directed tests (OtSuite:96, 108, 80, 87).
  Dispatch is total: cases 1–2 + `(Nil,Nil)` + four pairs + catch-all cover every shape.
  CONFIRMED by exhaustive case analysis.
- Hand-traced per the table, code agrees at every step:
  - concurrent inserts at one cursor (p = `R1,I[P],R1`, q = `R1,I[Q],R1`) →
    `R2, I[P], R1`; applied: `a,Q,P,b` — Q's earlier-integrated text first (OtSuite:96);
  - Q-insert while P-delete pending (p = `D1`, q = `I[z],D1`) → `R1` — inserted text
    survives, base token dies once (OtSuite:108);
  - trailing Q insert after P exhausted (p = `R1`, q = `R1,I[z]`) → `R2` (OtSuite:87);
    trailing P insert after Q exhausted → passes through (OtSuite:80);
  - P-insert vs Q-retain head → insert emitted then pair rows (split fixture trace);
    P-insert vs Q-delete head (p = `I[x],R1`, q = `D1`) → `[I[x]]` (row 2 then row 5) —
    correct; see Nit #2 on its directed coverage.
- `length(Q insert)` is the **token count**: `Ot.scala:55` uses `tokens.length`, not
  byte/char length. CONFIRMED.

### Splitting, re-heading, coalescing — no findings

- `Ot.scala:103-104` `remainder`: a partially consumed retain/delete is re-headed with
  `left = count − min`; dropped exactly when zero. Under the documented precondition
  (structurally valid inputs, counts ≥ 1 — Ot.scala:41-43, justified: `q` is a `Diff.diff`
  output, `p` a validated patch script), `min ≥ 1` in every pair row, so no output count
  is ever non-positive and every recursive step strictly decreases (op count + remaining
  counts) — terminates. CONFIRMED.
- `Ot.scala:111-116` `push`: coalesces only same-kind neighbors at the reversed
  accumulator head; kinds are context-free in a flat op stream, so there is no boundary
  it could wrongly merge across — and merging retains/deletes made adjacent by rows 5/6
  emitting nothing is exactly what §6.3 "Coalesce adjacent output operations" requires
  (pinned: OtSuite:72). Count addition cannot overflow `Long` (two safe integers sum
  < 2^54). Property `transformed.validate == Right(())` at 300 samples pins R55 output
  validity (OtSuite:295). CONFIRMED.

### Consumption invariant (R71 "no unmatched retain or delete") — no findings

- `Ot.scala:96`: the catch-all is reachable only for `(Nil, Retain/Delete :: _)` and
  `(Retain/Delete :: _, Nil)` — precisely "one stream ended while the other still holds a
  retain or delete", both directions. Trailing inserts on either side are consumed by
  cases 1–2 before the terminator, so they cannot false-positive (directed:
  OtSuite:131-137 covers all four mismatch shapes; OtSuite:80/87/108 cover the trailing
  inserts; the iff property OtSuite:285 generates bases of independent lengths and
  asserts `Right` ⟺ equal length at 300 samples). Since rows 3–6 consume base tokens 1:1
  from both streams and inserts consume none, "both end together" ⟺ "equal base
  consumption" — the check is exact, not approximate. CONFIRMED.
- `SnapError.OtBaseMismatch` / `Messages.otBaseMismatch` (Errors.scala:322-327, 619-622):
  additive, end-of-block, wording marked untested (no provided test pins any OT error
  message — correct, this is an internal replay invariant). D4/D5 placement consistent
  with the catalog rule; the declared-scope deviation is recorded in the task Notes.

### YAML fixtures re-derived by hand — no findings

All six lifted fixtures were derived on paper from the table + §5 diff (including the
deletion-on-tie rule) and match both the code's output and the YAML-pinned bytes:

- **09 append/append:** snap order — result `(bob→1, seed→1)` precedes
  `(alice→1, seed→1)` (sorted union; at `alice@x` counters 0 vs 1, lower earlier), so bob
  is context Q, alice incoming P. p = `R1, I[left]`, q = `R1, I[right]` → transformed
  `R2, I[left]` → `base\nright\nleft\n`. Matches 09-merge-text.yaml:70 and OtSuite:176.
- **22 dd:** p = `R1,D2,R2`, q = `R1,D1,R3` → `R1,D1,R2` → `0\n3\n4\n` (yaml:59).
- **22 split:** p = `I[A],R1,D2,R2,I[TAIL]`, q = `R2,D1,I[B],R2` →
  `I[A],R1,D1,R3,I[TAIL]` → `A\n0\nB\n3\n4\nTAIL\n` (yaml:99) — exercises P-insert,
  Q-insert priority, unequal splits, overlapping deletes, trailing P insert in one pass.
- **22 rd:** p = `R5,I[A]`, q = `R1,D1,R3` → `R4,I[A]` → `0\n2\n3\n4\nA\n` (yaml:130).
- **22 survive:** p = `R1,D1,R3`, q = `R1,I[B],R4` → `R2,D1,R3` → `0\nB\n2\n3\n4\n`
  (yaml:160) — deletion consumes base only; B survives.
- **18 three-way:** integration order c, b, a (per snap order, derived by hand); c applies
  directly (B = C); b transforms through aggregate `q = diff(base, "end\n") = D1,R1` →
  `I[B],R1` → `B\nend\n`; a through `q = diff(base, "B\nend\n") = D1,I[B],R1` →
  `R1,I[A],R1` → `B\nA\nend\n` (yaml:175). This trace also proves the priority row is
  load-bearing: P-insert-first at a's step would give `A\nB\nend\n` — wrong. The fixture
  correctly models R72 (one transform per integrated patch against the **aggregate** Q,
  never chained per historical patch). CONFIRMED.

### Determinism, purity, conventions — no findings

- `Ot.scala` is a pure function over two `Vector[EditOp]`s: no `var`, no throw, no
  `now()`/env/locale/randomness, no hash-map iteration (Lists/Vectors only), tail-
  recursive. Repeated-run identity property (OtSuite:318) plus my two extra fresh-seed
  JVM runs. CONFIRMED.
- Identity laws pinned: `transform(p, diff(A,A)) == p` and transformed identity applied
  to C yields C (OtSuite:302, 309).

### The T16 canonical-result question (task Notes, OtSuite:143) — verdict: SOUND, with one sharpening

**Claim under review:** transformed scripts applied to `C` may yield a token sequence
with a LF-less token in non-final position (base ∅, P = `insert["x"]`, Q = `insert["y"]`
→ transformed `retain 1, insert["x"]` → `["y","x"]`, bytes `yx`), and T16's replay must
therefore apply transformed scripts **without** `EditScript.applyTo`'s canonical-result
check (exact consumption still enforced).

**My independent judgment: the reading is correct and in fact forced by the spec.**

- R57's "Applying it MUST produce exactly the canonical token sequence" lives in §4.4,
  which defines *patch* edit scripts and their validity against their exact base. The
  transformed script is not a patch script; §6.3 fully determines its shape and imposes
  no canonicality on the application result; §6.3/§6.4 define no failure or warning path
  for text OT.
- §6.5 requires that every valid patch set + frontier "MUST produce the same bytes" —
  merge always yields a result. Both patches in the scenario are individually valid
  (a final token may lack LF, §4.4), so a canonical-result check firing during OT
  application would make a valid history unmergeable — a direct §6.5 violation. The
  "Snap does not guarantee … desirable merged text" disclaimer covers the ugliness of
  `yx`. CONFIRMED against spec text.
- **Sharpening (the token-identity question):** the non-canonical sequence `["y","x"]`
  must exist only transiently. `["y","x"]` and `["yx"]` concatenate to the same bytes,
  but they are different token sequences, and downstream token-level consumers *do* care:
  a later patch authored on the merged tree computes its script over
  `tokenize("yx") = ["yx"]` (1 token); if replay materialized the tree as the raw list
  `["y","x"]` (2 tokens), that valid patch would under-consume its own base and fail
  validation (§4.5 step 5), or mis-transform. The existing architecture already forces
  the right behavior: `Tree` is a path → **bytes** map (Tree.scala:6,18) and Replay
  re-tokenizes from bytes at each integration (Replay.scala:294, `tokenizeBytes`), so
  rendering the merged sequence to bytes re-canonicalizes it implicitly and no consumer
  ever sees the transient list. **T16 must render the applied token sequence to bytes
  for the tree and must not cache or reuse the raw post-OT token list for later
  `diff(B, C)` computations or validation.** See finding #1.

### Findings list

- **#1 [Minor] tasks/T15-ot-transform.md:61-73 — the T16 note is correct but stops one
  step short of the re-tokenization requirement.** The note pins "apply without the
  canonical-result check" and "merged bytes are the plain token concatenation", but does
  not state that the resulting token sequence must be reduced to bytes (and thereby
  re-canonicalized by the next `tokenize`) before any subsequent token-level use.
  Concrete failure if T16 ignores this: after the `yx` merge, a contributor commits on
  the merged tree; their patch's script consumes `tokenize("yx")` = 1 token; a replay
  that kept `["y","x"]` (2 tokens) as the file's token sequence would spuriously reject
  that valid patch (under-consumption at §4.5 step 5) or corrupt a later aggregate Q.
  Today's `Tree`/`Replay` byte-map architecture makes the correct behavior the path of
  least resistance, so this is guidance-hardening, not a live bug — but the note is the
  document T16's implementer will follow. Suggested action: append one sentence to the
  task note (or carry it into T16's task file). PLAUSIBLE as a future failure mode;
  the spec analysis behind it is CONFIRMED.
- **#2 [Nit] snap/scala/src/test/scala/snap/core/OtSuite.scala:29-127 — no directed test
  pins row 2 (`P insert`) firing against a pending `Q delete` head.** The case (p =
  `I[x],R1`, q = `D1` → `[I[x]]`) is reachable in the generated properties (e.g. p =
  `diff(["a\n"], ["b\n","a\n"])`, q = `diff(["a\n"], [])`) and I hand-traced it as
  correct, but coverage is probabilistic rather than pinned. A dispatch regression here
  is structurally hard to construct (pair rows can't match an insert head), so Nit only.
  CONFIRMED (coverage gap verified by reading the directed tests; behavior verified by
  trace).

No Critical or Major findings. Acceptance criteria: all four checked boxes verified
against the actual tests — directed rows 1–6 (OtSuite:31-59), Q-insert-before-P-delete
(:121), splitting (:63,:72), trailing inserts (:80,:87), concurrent inserts (:96);
properties (:267,:276,:285,:295); fixture-encoded convergence (:173-219); negative
constraints hold by inspection.

## Status

**Approve** (with the two non-blocking notes above). Pre-commit gate satisfied:

- lint gate: `scalafmtCheckAll` + `scalafixAll --check` → both `[success]`, exit 0;
- project suite: 380/380 (`sbt -batch scalafmtCheckAll "scalafixAll --check" test`,
  exit 0); OtSuite 29/29, re-run twice more in a fresh JVM with fresh property seeds;
- provided suites 09/18/22 not yet runnable (need T16/T17 `merge`); their pinned bytes
  hand-verified against the lifted fixtures;
- T16 canonical-result question ruled: apply transformed scripts without the
  canonical-result check — sound and spec-forced (§6.5) — with the added requirement
  that merged output is rendered to bytes and re-tokenized for all downstream use
  (finding #1).

## Triage (orchestrator, 2026-09-04)

| # | Severity | Decision |
|---|---|---|
| 1 | Minor | **Accept now (doc-only)** — the re-tokenization requirement added to T16's pre-implementation pointers and appended to T15's task notes: merged output renders to bytes and is re-tokenized for all downstream use; the non-canonical token list is transient. No code change (Tree's byte map already forces it). |
| 2 | Nit | **Defer to T18** — directed test for the P-insert row against a pending Q-delete head joins T18's regression net (pointer appended). Hand-traced correct; property-covered probabilistically today. |

Verdict **approve** stands. Code and this report commit together.

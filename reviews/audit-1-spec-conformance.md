# Post-completion audit — lens 1: SPEC conformance

**Verdict: block** (1 Major, 2 Minor, 2 Nit)

The Major is a single, contained filesystem-effect defect in `snap/fs/Materialize.scala`
that both `merge` and `revert` inherit; everything else I checked against `snap/SPEC.md`
holds, including every byte-pinned output format, the canonical diff, the OT table, the
§6.4 winner rules, §4.5's validation order and the whole HTTP surface. Fix finding 1 and
this is a release.

Method: read `snap/SPEC.md` end to end first, then traced each stated behavior to its
implementation, then probed the built CLI directly. Baseline re-established in this
worktree before any probing:

- `cd snap/scala && sbt -batch clean assembly` → `[success]`, jar hash `b23d1460…`
- `PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH" ./snap/verify --lang scala`
  → **28 passed** in 68 323 ms (Java 17.0.12).

All reproductions below were run against that jar with Java 17. Scratch repositories live
under `$TMPDIR`; nothing tracked was modified (`git status --short` empty at the end).

---

## Findings

### 1. `merge` and `revert` delete *every* empty directory under the repository root, not just newly-emptied ones — a no-op merge silently destroys untracked directory structure — **Major**

**SPEC §6.2:** "Installation removes files that block required directories, creates
required directories, writes target files, and **removes newly empty directories** so the
filesystem represents exactly that target path/byte map."

**SPEC §7.8:** "Merging equal or already-contained history succeeds, **changes nothing**,
emits no warnings, and prints the unchanged version."

`snap/scala/src/main/scala/snap/fs/Materialize.scala:64-73` — `install` calls
`pruneEmptyDirectories(root)` twice, unconditionally, and
`snap/scala/src/main/scala/snap/fs/Materialize.scala:123-148` walks the *entire* tree
under the root deleting any directory that holds no entry — regardless of whether this
operation emptied it. It runs even when `removed` and `written` are both empty.
Callers: `snap/scala/src/main/scala/snap/cli/CommandsMerge.scala:75` and
`snap/scala/src/main/scala/snap/cli/CommandsRevert.scala:72`.

Because §2 says "Directories are implicit; empty directories are not tracked", such a
directory is invisible to `status` — the working tree is *clean*, the merge is a *no-op*,
and the directory is deleted anyway. There is no undo: the repository never recorded it.

**Reproduction (CONFIRMED).** Repository with one committed file, plus two untracked
empty directories the user created:

```
$ cd $TMPDIR/snapaudit/r1            # repo at (alice@example.com->1), one file a.txt
$ mkdir -p myEmptyDir/nested docs
$ snap status
version (alice@example.com->1)       # clean — empty dirs are not tracked
$ ls
.snap  a.txt  docs  myEmptyDir
$ snap merge .                       # merging already-contained history
(alice@example.com->1)
$ echo $?
0
$ ls
.snap  a.txt                         # docs/ and myEmptyDir/ are GONE
```

Expected per §7.8: `docs/` and `myEmptyDir/nested/` still present, exit 0, same version.

Same root cause via `revert` (CONFIRMED, same session): a `keepMe/deep/` created before
`snap revert (alice@example.com->1)` was gone afterwards, although the revert only touched
`a.txt`.

For contrast, `commit` — which does not call `Materialize.install` — correctly leaves such
a directory alone (verified: `survivor/` survived a commit). So the behaviour is also
internally inconsistent across the three mutating commands.

I considered the competing reading, that "so the filesystem represents exactly that target
path/byte map" licenses removing every directory with no tracked file in it. It does not
survive contact with the text: it makes the qualifier "**newly** empty" meaningless, and it
directly contradicts §7.8's "changes nothing" for the already-contained case, which is the
reproduction above.

**Suggested direction.** Prune only directories that this installation actually emptied —
i.e. the ancestor chains of the paths in `removed` — plus any directory occupying a path
the target needs as a file (that second case is why the global sweep was there; it is
exercised by the file↔directory transitions in test 07 and by the namespace-winner install
`a` → `a/b`, both of which must keep working). A pre-existing empty directory unrelated to
`removed` must be left untouched.

---

### 2. `diff`'s misplaced-option errors bypass its own `usage:` channel — **Minor**

**SPEC §7.6** grammar: `snap diff <old> <new> [--repo <repository>]`, and
**SPEC §7** preamble: "Options occur exactly in the positions shown below and may appear at
most once. Unknown options, extra operands, and missing option values are errors."

`snap/scala/src/main/scala/snap/cli/CommandsDiff.scala:54` — `parseShape` matches *any*
two-token operand list as `TwoVersions`, so a `--`-prefixed token lands in a version
position and is reported by `parseVersionArg`
(`snap/scala/src/main/scala/snap/cli/CommandsDiff.scala:104`) as an invalid *version*
rather than as a grammar violation:

```
$ snap diff --repo /tmp/snapaudit/r1
snap: invalid version: --repo          [exit 1]
$ snap diff --unknown '(alice@example.com->1)'
snap: invalid version: --unknown       [exit 1]
```

Expected (and what DESIGN D28 says this project does *uniformly*):
`snap: usage: snap diff <old> <new> [--repo <repository>]`.

This is the one place D28 is not applied, so it is an internal inconsistency as well as a
divergence from the channel tests 14/24 pin for `diff`'s grammar errors. Both readings exit
1, so it is not a correctness break — but a holdout asserting `stderr_contains: usage` for
a misplaced `--repo` would fail. The three shapes `diff x`, `diff a b c` and
`diff a b --repo` all correctly report the usage line (verified), which makes the gap look
accidental rather than deliberate.

**Suggested direction.** Reject a `--`-prefixed token in `<old>`/`<new>` inside
`parseShape`, before the version parse, so it reports `DiffUsage`.

---

### 3. D28 makes a `--`-prefixed commit message unreachable — **Minor** (ruling requested)

**SPEC §7.5:** `snap commit <message>`; **§4.2:** "`message` is a nonempty UTF-8 string. It
may contain tab and LF but no other ASCII control character."

`snap/scala/src/main/scala/snap/cli/Grammar.scala:96-99` — `oneFreeTextOperandRule` rejects
any single operand starting with `--`, for `commit`, `revert` and `merge` alike:

```
$ snap commit "--wip: adds b"
snap: invalid command or arguments     [exit 1]
```

**My ruling on D28: partially supported, over-broad at `commit`.** The spec supports it
where the operand is *optional* and the token is therefore genuinely ambiguous: `init [path]`
and `--serve [port]`. Test 24 settles `init` (it asserts both the error *and*
`path_not_exists: --unknown`), and §7's preamble is unqualified. But `commit <message>`
takes a **mandatory** free-text operand; the position is unambiguous, the spec defines no
`--` separator, and §4.2 permits `--wip` as a message. So the contract makes this message
legal and the CLI makes it unreachable. `revert` and `merge` carry no practical exposure
(a version always starts with `(`; a repository path or URL starting with `--` is not a
real case), so the exposure is `commit` alone.

**Suggested direction.** Keep D28 for `init` and `--serve` (test-pinned / ambiguous), and
accept any single operand — `--`-prefixed or not — as `commit`'s message. If the team
prefers to keep D28 uniform, record the accepted holdout risk explicitly; it is a
judgement call, not a defect, which is why this is Minor and does not block.

---

### 4. `Diff.diff` allocates a full `(n+1)×(m+1)` `Int` table — **Nit**

**SPEC §5** defines the recurrence; **§12** puts "large-file optimizations" out of scope,
and DESIGN D18 deliberately locks the literal DP. So this is not a conformance defect — it
is a measured robustness note.

`snap/scala/src/main/scala/snap/core/Diff.scala:52-63`. Measured on this machine
(CONFIRMED): editing one line of a committed file takes

| lines | `snap commit` wall time |
| --- | --- |
| 2 000 | 0.28 s |
| 8 000 | 0.71 s |
| 20 000 | 2.64 s |
| 40 000 | 10.88 s (≈6.4 GB of `Int` cells) |

At 40 000 tokens the table alone is 1.6 × 10⁹ ints. It did not OOM here, but it would on a
smaller-heap machine, and an OOM surfaces through `Main`'s catch-all as exit 2
`snap: internal error: …` rather than a diagnostic. No provided test approaches this size
and the 30 s harness budget makes a holdout unlikely; flagging for the record only. A
banded/Myers variant is explicitly permitted by §5 ("MAY use Myers, Hirschberg, or another
optimization only if it produces the same script") but would need a script-equality property
test, which D18 declined.

---

### 5. Contributor-id length is checked in UTF-16 units before the ASCII check — **Nit**

**SPEC §3.1:** a contributor ID is "at most 254 **bytes**".

`snap/scala/src/main/scala/snap/core/Ids.scala:69` uses `value.length` (UTF-16 code units),
not the UTF-8 byte length, and runs before the printable-ASCII gate at line 70. The verdict
is always correct — a string that survives line 70 is pure ASCII, where units == bytes, and
anything non-ASCII is rejected at line 70 regardless — so only the *reason* carried in the
error can differ for a long non-ASCII input, and the reason is not spec-pinned. Boundary
confirmed by probe: a 254-byte id is accepted, a 255-byte id is rejected with
`snap: invalid contributor id: contributor id exceeds 254 bytes`. Cosmetic; noted so a
future relaxation of the ASCII rule does not silently make the length check wrong.

---

## D27 ruling (requested)

**D27 is correct and correctly implemented. No finding.**

**SPEC §6.2:** "Let `S` be the paths that `P` **makes present**, and let `C'` be `C` with
every path that `P` authored as a deletion removed."

`snap/scala/src/main/scala/snap/core/Replay.scala:431-434` reads `S` as the paths absent in
`B` and present in `T` — the paths the patch *creates*.

Why the text supports this over "every path present in `T`":

1. "makes present" reads as *causes to become present*. A path already in `B` is not made
   present by an edit or a replacement; the spec has separate vocabulary for that ("the
   paths that `P` changes", used two paragraphs later).
2. The pre-pass is the whole-patch analogue of §6.4 rule 4 (`later-create-wins`), which is
   itself gated on "`B` is **absent**". Reading `S` as creations keeps the two consistent.
3. The wide reading silently inverts §6.4 rule 3. Under it, an incoming *edit* of `a` would
   delete a concurrent `a/b` and win the namespace — while rule 3 explicitly says that when
   "`B` is present and `C` is absent, the earlier concurrent delete wins". The pre-pass
   "overrides the per-path rules", so the wide reading would make rule 3 unreachable in
   exactly the case it names.
4. The sentence "The authored result is prefix-free, so two paths in `S` cannot conflict"
   is satisfied by both readings and so discriminates neither.

**Behavioural confirmation (CONFIRMED).** I hand-built the discriminating history at
`$TMPDIR/snapaudit/d27` — `seed@x` creates file `a`; `alice@x` edits `a`; `bob@x`
concurrently deletes `a` and creates `a/b`. Snap order integrates `bob` before `alice`
(verified via `snap log`: `seed`, `bob`, `alice`). Result:

```
$ snap status
version (alice@x->1,bob@x->1,seed@x->1)
D a/b                                   # frontier tree is exactly {a/b}
$ snap merge ../d27R                    # from the alice-only side
warning: auto-resolved a: delete-wins
(alice@x->1,bob@x->1,seed@x->1)
$ find . -not -path '*/.snap*'
./a
./a/b                                   # regular file `a` correctly became directory a/
```

That is the narrow reading resolving through §6.4 rule 3, exactly as D27 documents. The
wide reading would have produced `{a: "x2\n"}` with a `namespace-wins` warning.

Residual risk, stated plainly: §6.2 never defines "makes present", and no provided test
discriminates (test 11 is create-vs-create, where the readings agree). The reading is
defensible and I would not change it, but the ambiguity is real and is not resolvable from
the text with certainty.

---

## Spec areas checked and found correct

Each item below I traced to code and, where marked (probed), also exercised against the
built CLI.

**§2 — paths and working tree.** `SnapPath.parse` (`Path.scala:70-79`) enforces nonempty,
no ASCII control (incl. DEL, D12) or backslash, no empty/`.`/`..` segment, no first segment
`.snap`, and rejects unpaired surrogates; `Utf8Order` (`Ids.scala:13-26`) is code-point
order, which equals unsigned UTF-8 byte order for well-formed strings (I checked the
supplementary-vs-BMP and low-surrogate cases by hand). Prefix-freeness is validated on every
authored result (`Replay.authoredResult`, `Replay.scala:157-162`). Symlink and FIFO both
reported, never followed, with `LinkOption.NOFOLLOW_LINKS` (probed:
`snap: unsupported working tree entry: linky` / `: pipe1`, exit 1). Nested `sub/.snap/`
tracked per D13 (probed: `A sub/.snap/inner.txt`). Backslash filename →
`snap: invalid working tree path: has\back.txt` (probed). Directory children iterated in
`Utf8Order`, never listing order (`WorkTree.scala:101-106`).

**§3 — versions.** Four-outcome `Ord` preserved (`Version.scala:38-57`); componentwise
`join`; Snap order verified against the text (lower counter at the first differing id sorts
earlier — `Version.scala:104-124`) and shown to extend causal order. Canonical parse probed
across 13 inputs: leading zero, explicit zero, missing paren, missing paren-pair, `+1`,
overflow `9007199254740992`, leading space, duplicate id, non-canonical order — all
`snap: invalid version: <raw>` exit 1; `9007199254740991` and canonical-but-unknown
correctly reach `snap: unknown version: …`. Contributor-id rules probed: `a@@b`, `a,b@c`,
`a->b@c`, `a b@c`, `@b`, `a@`, `José@example.com` all rejected with a specific reason;
254 bytes accepted, 255 rejected.

**§4 — repository format and §4.5's six steps.** `Repo.validate` (`Repo.scala:83-95`) runs
step 2 (sorting/dots → contiguity) fully before step 3 (increments → base closure), then
frontier closure → reachability → acyclicity, then steps 5–6 in `Replay`; the ordering
matches §4.5's numbering and the first violation decides. Known/materializable predicate
matches §4.1's definition verbatim (`Replay.checkKnown`, `Replay.scala:97-105`). Change
variants, base-existence rules, and the no-op rule with the empty-text-edit exception all
implemented in `Replay.applyChange` (`Replay.scala:572-603`). Base64 canonicality is
decode-then-re-encode with a shape pre-check that makes the JDK decoder total
(`RepoCodec.scala:185-196`); `=` in a non-terminal position and non-multiple-of-4 lengths
are rejected. Numbers judged from decimal text, never `Double` (`Json.scala:38-51`) —
`1.0`, `1e2` and `9007199254740992` all rejected. Duplicate object keys error by name.
Canonical writer style confirmed byte-for-byte against a written `repository.json`
(2-space indent, every array element on its own line including each `[id, revision]`
member, trailing LF).

**§4.4 — tokens and edit scripts.** `tokenize` splits after every LF retaining it, empty
file → no tokens; I hand-checked `"a\r\nb"`, `"a\n"`, `"\n\n"`, `""`. Canonicality
predicate matches the text. Counts positive safe integers, adjacency forbidden, exact
consumption with no implicit trailing retain, empty script valid only for empty-file
creation. Insert tokens must be nonempty text tokens (NUL-free), which is what guarantees
§6.4 rule 6's "P is text and C is non-text" premise.

**§5 — canonical diff.** Literal DP table and walk, `<=` deletion-on-tie
(`Diff.scala:32`). Verified against the spec's own hard case end-to-end (probed):
`a,b,a → b,a,a` (no final LF) renders as `-a / b / a / +a` + the no-newline marker, i.e.
`[delete 1, retain 2, insert ["a"]]`.

**§6.1–§6.3 — replay, ready-loop, OT.** All three ordering keys implemented verbatim
(`Replay.readyOrdering`, `Replay.scala:79-86`); integration order is an observable and is
what `log` reverses. OT table transcribed row-for-row with the `Q insert` priority row
first (`Ot.scala:51-96`); count splitting, trailing-insert handling, and the
"both scripts consume the same base token count" invariant are all present. `Q` is the
aggregate `diff(B, C)` computed once per integrated patch, never chained per historical
patch (`Replay.scala:525`).

I confirmed the one genuinely subtle OT corner by hand-built history at
`$TMPDIR/snapaudit/ot1`: an empty base file with two concurrent LF-less inserts. Transform
yields `[retain 1, insert ["x"]]` over `["y"]` → transient tokens `["y","x"]`, which is
*not* a canonical token sequence — and `applyTransformed` deliberately skips §4.4's
canonicality check there (`EditScript.scala:86-87`). That is correct: §4.4's requirement
governs patch scripts against their exact base, §6.5 forces a merge result for every valid
history, and the tokens are rendered to bytes immediately and re-tokenized downstream.
Observed result: file `f` = `yx`, i.e. the earlier-integrated insert first, exactly as
§6.3's "concurrent inserts at one cursor appear in canonical integration order" requires.

**§6.4 — path-level rules and warnings.** Rules 1–6 in the spec's order with the correct
guards (`Replay.pathRules`, `Replay.scala:549-567`); I checked reachability of each branch
and confirmed rule 6 can only be entered with `B`, `C`, `T` all present and `C` non-text.
Warning reason tokens are the spec's exact vocabulary; the set is unique, sorted by path
then reason (`Warning.ordering`, `Replay.scala:47-50`); OT emits none. Merge prints
`joined -- preMergeLocal`, one line per pair, format
`warning: auto-resolved <path>: <reason>` (probed). I also confirmed the canonical tree
stays prefix-free by exhausting the ways a path can become present during one integration.

**§7 — commands.** Repository discovery walks cwd → root; local operands resolve against
the process cwd (`Commands.scala:52-54`). Probed and correct: `init` prints `()`, refuses
reinit and nesting, creates nothing on failure; `config` local/global precedence, silent on
success, validates before writing; `status` version line + sorted `A`/`M`/`D` rows, clean
tree prints only the version line; `log` reverse integration order, tab-separated,
`\\`/`\t`/`\n` escaped in that order, empty history prints nothing and exits 0; `commit`
4096-byte boundary exact (4096 ok, 4097 → `snap: invalid commit message`), empty message
rejected even on a clean tree, `put`-vs-`text` selection per §7.5; `diff` all three forms
including `/dev/null` headers, `@@ -1,<n> +1,<m> @@` counts, `\ No newline at end of file`
on both sides, `Binary files … differ`, and empty output + exit 0 when equal; `revert`
message `revert to <version>`, additive, `snap: target tree is already current` verbatim;
`merge` requires a clean tree but **no** contributor configuration (confirmed: merged a
repository with no `config.json`), unions and joins, and a re-merge of already-contained
history emits no warnings and prints the unchanged version (probed). `--version` prints
`snap 1.0.0` with no repository lookup, from `/` (probed).

**§7.11 — presentation.** Every layout family byte-checked under `SNAP_COLOR=always`:
success line, `Snap status` double space + blank line + clean line + all three dirty row
colours with U+2212 for deleted, log entry pair with two-space continuation, diff line
styles in the spec's first-applicable order, `--version` bold, error
`S(31,"✗ snap: …")` as one wrap, warning as two independent yellow wraps, `config` silent.
Precedence probed: `always` overrides `NO_COLOR`; `NO_COLOR` present with an *empty* value
forces plain under `auto`; `never` forces plain; `SNAP_COLOR=bogus` →
`snap: SNAP_COLOR must be auto, always, or never` in plain, before command execution
(including before `--version`), exit 1. §11's mandatory unit test of `auto` TTY/non-TTY
selection per stream exists (`PresentationSuite.scala:95-107`).

**§8 — configuration.** Exact `{"contributor":{"id":…}}` schema, unknown/duplicate fields
and invalid ids error only for a file that is actually read, missing file = no value,
`HOME` absent (and empty, D24) = global unavailable, local-provides-id short-circuits the
global read.

**§9 — HTTP.** Probed against a live `--serve 0`: `GET /repository.json` → 200 with
`Content-Type: application/json; charset=utf-8` and the canonical body;
`HEAD /repository.json` → identical status and headers (including `Content-Length: 898`)
with zero body bytes; `GET /nope` → 404; `POST /repository.json` → 405 with
`Allow: GET, HEAD`; `POST /nope` → 404 (path before method, per §9's bullet order);
`GET /repository.json?x=1` → 404. Startup snapshot immutability confirmed: a `commit`
while the server ran did not change the served body. `SIGTERM` → exit **0**. Client side:
`Redirect.NEVER`, one GET, status must be 200, body through the same strict pipeline;
`HTTP <status>` substring is emitted for a non-200.

**§10 — mutation ordering, failure safety, exit codes.** `merge` and `revert` complete
parse → validate → replay → dirty check → target construction before any write; both call
`Materialize.install` and only then `Store.writeRepository`; `commit` performs only the
metadata replacement. Writes are same-directory `<name>.tmp` + `ATOMIC_MOVE`; no temp file
was left behind by any probe. Errors are one line `snap: <detail>` on stderr, results on
stdout (confirmed by redirecting the two streams separately on a warning-emitting merge),
exit 0/1, with exit 2 reserved for `Main`'s top-level catch-all. Diagnostics interpolating
untrusted text are control-char-sanitised so an error can never span two lines.

**Error wording.** Every string the spec pins verbatim matches byte-for-byte:
`snap: target tree is already current`, `snap: SNAP_COLOR must be auto, always, or never`,
`snap: contributor.id is required; configure it locally or globally`,
`warning: auto-resolved <path>: <reason>`, `snap: <detail>`, `snap <semver>`,
`version <v>` + `A/M/D <path>`, the `\t`-separated log line, the `--- `/`+++ `/`@@ `/`\ `
diff lines, `Binary files a/<p> and b/<p> differ`, and
`http://127.0.0.1:<port>/repository.json`. Everything else routes through the single
`Messages` catalog; I found no place where a wording the spec leaves free has been pinned
in a way that could collide with a differently-worded holdout — the free ones are all
`snap: <detail>` one-liners around a spec-named noun.

---

## Statements I could NOT confirm either way

1. **§2, non-UTF-8 filenames.** `WorkTree.children` reads names via
   `Path.getFileName.toString`. On Linux a filename can be an arbitrary byte string; under
   `LC_ALL=C` the JVM's `sun.jnu.encoding` may substitute replacement characters, which
   would silently *rename* such a file into the tree instead of reporting it. I could not
   test this: this host is macOS/APFS, which enforces UTF-8 filenames. Code reading alone
   cannot settle what the JVM does there.
2. **§9, `https://`.** `Commands.isRemoteOperand` accepts both schemes and the code path is
   identical past the prefix check, but I had no TLS server to exercise it, so the
   `https://` branch is unverified end to end.
3. **§10, mid-install I/O failure.** "An I/O failure or process interruption during a
   multi-file update may leave a dirty, partially updated working tree with the old
   `repository.json`." I could not inject a failure between `Materialize.install` and
   `Store.writeRepository`. Verified by reading only: both callers sequence them in one
   `for`-comprehension, so the metadata write cannot run after a `Left` from install.
4. **§11 item 11, cross-language exchange.** Requires the TypeScript and Rust reference
   implementations; only the Scala one exists here.
5. **D27's "makes present".** I ruled on the text above and the implementation matches the
   ruling, but §6.2 never defines the phrase and no provided test discriminates the two
   readings. This is an irreducible contract ambiguity, not something further probing can
   settle.
6. **Exit code 2.** Reachable only through `Main`'s catch-all for an unanticipated
   `Throwable` (or the two documented internal-invariant guards in `CommandsCommit`/
   `CommandsRevert`, both argued unreachable). I could not trigger it from the CLI, so the
   exit-2 channel is verified by code reading and the project's own unit tests only.

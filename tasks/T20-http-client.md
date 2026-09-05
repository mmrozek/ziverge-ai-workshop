# T20 — HTTP client & remote operands (2 SP)

- **Phase:** 4 — HTTP & cross-repo
- **Depends on:** T17, T19
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
Repository-operand resolution for `http://`/`https://` URLs (R78, R102, D15; DESIGN §7):
exactly one GET of the exact URL via `java.net.http.HttpClient` with redirects NEVER,
require status 200 (non-200 → error containing `HTTP <status>`), parse + validate the
body as a repository value; wire into `merge <url>` and `diff --repo <url>`. HTTP is
read-only.

## Scope
`snap/scala/src/main/scala/snap/http/Client.scala`, operand resolution in
`snap/scala/src/main/scala/snap/cli/`, tests in `snap/scala/src/test/scala/snap/http/`.

## Acceptance criteria
- [x] Provided test `13-http-client` passes (`--filter http-client`): cross-repo diff
      over HTTP without import, HTTP merge, malformed body → `invalid JSON`, 302 →
      error containing `HTTP 302` with no second request.
- [x] Unit test with a local stub server: exactly one request received, exact request
      target preserved (path + query byte-for-byte), no retry on failure.
- [x] `https://` operands are recognized and routed to the client (holdout gap —
      unit-tested at the resolution layer; TLS handshake not exercised).
- [x] Timeout and body-size cap per D15; a hanging stub server fails with an error, not
      a hang past the timeout.

## Notes / decisions

- **Files added/changed** (all within declared scope except where noted):
  `snap/scala/src/main/scala/snap/http/Client.scala` (new); `snap/scala/src/main/scala/snap/cli/Commands.scala`
  (added `isRemoteOperand`/`loadRemoteRepository` — one shared R78/R102 resolution function for
  both `merge` and `diff --repo`, per DESIGN §1's "one canonical implementation per concept");
  `snap/scala/src/main/scala/snap/cli/CommandsMerge.scala` (replaced the `resolveOperand`
  `NotImplemented` seam in place with `Commands.loadRemoteRepository`; did not touch the warning
  print line, per the T22-conflict instruction); `snap/scala/src/main/scala/snap/cli/CommandsDiff.scala`
  (implemented the `--repo` branch: `old` resolved in the local repository, `new` in the remote,
  reusing `CommandsMerge.unionPatches` purely for its R86/§3.5 dot cross-check); `snap/scala/src/main/scala/snap/core/Errors.scala`
  (three `// T20 additions` blocks: enum cases `HttpStatus`/`HttpRequestFailed`/`RemoteBodyNotUtf8`,
  their `message` arms, and their `Messages` catalog entries — existing entries not reworded or
  reordered). Tests: `snap/scala/src/test/scala/snap/http/ClientSuite.scala` (new, 11 cases);
  updated `snap/scala/src/test/scala/snap/cli/CommandsMergeSuite.scala` (the http(s) placeholder
  test now asserts the real `HttpRequestFailed` connection-refusal outcome instead of
  `not implemented`) and `snap/scala/src/test/scala/snap/cli/CommandsDiffSuite.scala` (the `--repo`
  placeholder test replaced; added local-path cross-repo, HTTP cross-repo, dot-collision, and
  version-validation cases for the `--repo` form).

- **D15 timeout — empirically verified two-layer design.** `HttpRequest.Builder.timeout` alone is
  *not* sufficient on this JDK (17.0.12): a stub server that sends a status line and a declared
  `Content-Length` and then never finishes the body is **not** bounded by that per-request timeout —
  `HttpClient.send` blocked for the full duration of a 60s test sleep despite a 1s configured
  timeout (verified with a throwaway scratch reproduction before writing `Client.scala`). A totally
  silent server (no bytes at all) *is* correctly bounded by `HttpRequest.Builder.timeout` alone. To
  cover both hang shapes, `Client.get` uses `client.sendAsync(...).orTimeout(timeout, ...).get()`:
  `CompletableFuture.orTimeout` is a JDK-level deadline independent of `HttpClient`'s own timeout
  machinery, so it unblocks the caller in both cases. Verified empirically that JDK 17's
  `HttpClient` internal executor threads are daemon threads, so an abandoned/timed-out exchange does
  not prevent JVM exit or leak a live foreground thread — safe for both the CLI (single command,
  process exits soon after anyway) and the test suite (no port/thread leak). This is the most
  significant non-obvious finding of this task; see `Client.scala`'s `DefaultTimeout`/`get` doc
  comments for the same reasoning inline.

- **D15 body-size cap — pragmatic two-tier enforcement.** A declared `Content-Length` over the cap
  is rejected without reading any body bytes (`BodySubscribers.discarding`). Without a declared
  length (chunked transfer, or a server that lies about a smaller length than it sends), the body is
  read in full via `BodySubscribers.ofByteArray()` and only then checked against the cap — this
  buffers one oversized body before rejecting it. Accepted per D15's own "generous, untested"
  framing (DESIGN §9) rather than hand-rolling a custom streaming `Flow.Subscriber` with a hard
  read-time cutoff, which would have added significant untested complexity for a requirement the
  design table itself already downgrades to "generous, untested."

- **New `SnapError`/`Messages` wording (all untested — D5's convention for untested diagnostics):**
  `HttpStatus(status)` → `"remote repository request failed: HTTP $status"` (test 13 only pins the
  substring `HTTP 302`); `HttpRequestFailed(detail)` → `"cannot fetch remote repository: $detail"`
  (covers connection refusal, the request timeout, and the body-size cap — every GET failure short
  of a non-200 status); `RemoteBodyNotUtf8` → kept in the `invalid JSON` diagnostic class, mirroring
  `RepositoryNotUtf8`, since a valid repository document is always UTF-8.

- **`diff --repo`'s version-parsing behavior changed from the T13 placeholder.** Before this task,
  the grammar-accepted `diff <old> <new> --repo <repo>` shape returned `NotImplemented`
  unconditionally, without even syntax-checking `old`/`new` as version literals. Now both are parsed
  first (matching the local `<old> <new>` form and R86's "validate every repository and version
  before producing output"). This is a strictly more spec-consistent completion of the existing seam
  (an invalid version literal now correctly reports `invalid version: ...` instead of masking it
  behind `not implemented`), not a new ambiguity — recorded here for visibility since it changes
  observable behavior beyond the bare "wire up the client" framing.

- **Reused `CommandsMerge.unionPatches` for `diff --repo`'s R86/§3.5 cross-check** rather than
  writing a second dot-comparison function: `diff` never merges or writes, so the `Vector[Patch]` the
  function returns on success is discarded — only its `Left(PatchCollision(dot))` on a value
  mismatch is used, which is exactly what test 16 pins (`patch collision: a@x revision 1`,
  reused verbatim for both `merge` and `diff --repo`, and confirmed direction/command-independent by
  the existing `unionPatches` unit tests in `CommandsMergeSuite`).

- **Did not touch `Presentation.scala` or any print/styling call site**, per the T22-concurrency
  instruction; `CommandsMerge.scala`'s warning-printing line
  (`newWarnings.foreach(w => Presentation.Plain.warning(...))`) is unchanged.

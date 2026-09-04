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
- [ ] Provided test `13-http-client` passes (`--filter http-client`): cross-repo diff
      over HTTP without import, HTTP merge, malformed body → `invalid JSON`, 302 →
      error containing `HTTP 302` with no second request.
- [ ] Unit test with a local stub server: exactly one request received, exact request
      target preserved (path + query byte-for-byte), no retry on failure.
- [ ] `https://` operands are recognized and routed to the client (holdout gap —
      unit-tested at the resolution layer; TLS handshake not exercised).
- [ ] Timeout and body-size cap per D15; a hanging stub server fails with an error, not
      a hang past the timeout.

## Notes / decisions

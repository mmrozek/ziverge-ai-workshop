# T19 — `--serve` HTTP server (3 SP)

- **Phase:** 4 — HTTP & cross-repo
- **Depends on:** T07, T13
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap --serve [port]` (R90, R96, R101; DESIGN §7, D20–D21, gotcha 6): validate and
snapshot the repository at startup (snapshot = canonical serializer bytes, fixed once);
bind `127.0.0.1` only; default port 8765, `0` → OS-selected; print + flush the plain
URL line; `GET /repository.json` → 200 with `Content-Type: application/json;
charset=utf-8`; HEAD same status/headers, zero body bytes; any other raw request target
(including `?query`) → 404; other methods → 405 + `Allow: GET, HEAD`; SIGINT/SIGTERM →
exit 0 via `sun.misc.Signal`.

## Scope
`snap/scala/src/main/scala/snap/http/Server.scala`,
`snap/scala/src/main/scala/snap/cli/Commands*.scala` (serve), tests in
`snap/scala/src/test/scala/snap/http/`.

## Acceptance criteria
- [ ] Provided test `12-http-server` passes (`--filter http-server`), including the
      byte-pinned GET body, HEAD-with-no-body over a raw socket, snapshot immunity to a
      later commit, and SIGTERM/SIGINT exiting 0.
- [ ] Unit tests (own HTTP client against an ephemeral port): 404 for `/other` and for
      `/repository.json?x=1`; 405 + `Allow: GET, HEAD` for POST/PUT/DELETE; headers
      identical between GET and HEAD.
- [ ] Bare `snap --serve` binds port 8765 (untested by the suite — R90 holdout gap,
      unit-tested here with a skip-if-occupied guard).
- [ ] Invalid repository at startup → exit 1, no URL printed, no port bound; the URL
      line is written and flushed before the accept loop starts (no buffering).

## Notes / decisions

# T06 — Repository model, codec, structural validation (3 SP)

- **Phase:** 1 — Foundation
- **Depends on:** T02, T03, T04
- **Risk:** normal

Note: live status is NOT kept here — `TASKS.md` is the single source of truth for
status. Task files hold the definition (stable) and the notes (append-only).

## What
`snap/core/Patch.scala` (Patch/Change/Dot, result computation R46, structural patch
equality R47, message rules R48/D16, changes rules R49–R52) and `snap/core/Repo.scala` +
`snap/json/RepoCodec` (exact schema R40–R43, unknown fields named, validation §4.5 steps
1–4: schema, patch sorting by author/revision, one value per dot, contiguous revisions,
complete base closure, `revision = base[author]+1`, acyclicity, no unreachable patches
R44). Plus `snap/fs/Store.scala`: read `repository.json`, atomic same-directory
temp-file write (R105, gotcha 10) using the canonical writer. DESIGN §6, §7.

## Scope
`snap/scala/src/main/scala/snap/core/{Patch,Repo}.scala`,
`snap/scala/src/main/scala/snap/json/RepoCodec.scala`,
`snap/scala/src/main/scala/snap/fs/Store.scala`, tests under
`snap/scala/src/test/scala/snap/{core,json}/`.

## Acceptance criteria
- [ ] Codec decodes the spec §4.1 example and rejects, with the test-pinned message
      fragments: unknown fields at every level, missing fields, wrong types, fractional
      revision (`positive safe integer`), empty message (`message is empty`), empty
      changes (`changes is empty`), unsorted patches, revision gap (`missing a@x`),
      unreachable patch, base cycle (`cyclic or incomplete patch history`) — one unit
      test per rejection (source material: tests 15/23/27).
- [ ] Structural patch equality (R47) is over parsed typed values: same patches with
      different JSON whitespace/key order compare equal (test 26's premise).
- [ ] Store round-trip: write then read yields an equal repository value; write goes
      through a same-directory temp file and never leaves a partial
      `repository.json` on simulated failure before the move.
- [ ] Validation performs no filesystem mutation; changes-vs-base checks (steps 5–6)
      are explicitly deferred to T07 (leave a typed hook, not a silent gap).

## Notes / decisions

package snap.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import snap.core.Change
import snap.core.EditOp
import snap.fs.Store

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/** T21 hardening: the cross-repository safety net (SPEC §3.5, §7.6, §7.8, §10; R15, R38, R47, R86,
  * R103–R104; DESIGN D11) beyond what tests 16/20/26 directly exercise. Framing (T21 task file):
  * those three provided tests already pass — 20 via T17's `merge`, 16/26 via T20's remote operands
  * — so this suite is not turning tests green but proving the failure-precedence and portability
  * guarantees hold at combinations the provided suite never observes (holdout assumption, CLAUDE.md
  * "Testing"). Four groups, matching the task's acceptance criteria one-to-one:
  *
  *   1. D11 precedence at the UNOBSERVED combinations: a dirty tree plus a malformed remote (test
  *      20 only pairs dirty with a well-formed-but-unreachable remote; test 26 only pairs a
  *      malformed remote with a CLEAN tree) — dirty must still win. And a malformed LOCAL
  *      repository must be reported before the remote is read AT ALL, proven for an HTTP remote by
  *      asserting zero GETs ever land ([[CountingStub]]) rather than merely by the error class.
  *   2. No-mutation on every failing path (R103): every case mirrors the exact tree/JSON snapshot
  *      assertions tests 16/20/26 make, captured immediately after `init` and re-checked byte-exact
  *      after the failed command.
  *   3. Structural patch identity (R41/R47): test 26's "duplicate" repositories — same patch, one
  *      written with 2-space canonical formatting, the other single-line with reversed key order —
  *      pinned directly at the decode/union layer, not only through the end-to-end merge.
  *   4. Portability bytes (R19/R50/R53) through a REAL local exchange: an actual `commit` writing
  *      CRLF/NUL/non-ASCII bytes to disk, then both a `diff --repo` (test 26's own form) and — new
  *      coverage the provided suite never exercises — a real `merge` whose materialized working
  *      files are asserted byte-exact, proving the bytes survive the full commit -> union -> replay
  *      -> install pipeline, not merely the diff renderer.
  */
class CrossRepoSafetySuite extends munit.FunSuite:

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  private def initRepo(id: String): Path =
    val root = Files.createTempDirectory("snap-xrepo-test")
    assertEquals(run(root, "init")._1, 0)
    assertEquals(run(root, "config", "contributor.id", id)._1, 0)
    root

  private def write(root: Path, rel: String, bytes: Array[Byte]): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, bytes)
    ()

  private def writeText(root: Path, rel: String, text: String): Unit =
    write(root, rel, text.getBytes(StandardCharsets.UTF_8))

  private def commit(root: Path, message: String): Unit =
    assertEquals(run(root, "commit", message)._1, 0)

  private def repositoryFile(root: Path): Path = Commands.repositoryFile(root)

  private def repoBytes(root: Path): Vector[Byte] =
    Files.readAllBytes(repositoryFile(root)).toVector

  /** Every regular file under `root` (`.snap/` included) as relative-path -> bytes, byte-exact —
    * the unit-test analogue of the harness's `tree_equals` (test 26's own no-mutation check).
    */
  private def allFiles(root: Path): Map[String, Vector[Byte]] =
    val rels = Using.resource(Files.walk(root)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .map(root.relativize(_).toString)
        .toVector
    }
    rels.map(rel => rel -> Files.readAllBytes(root.resolve(rel)).toVector).toMap

  /** Overwrites a repository's on-disk JSON directly with a document that fails decode on an
    * unknown top-level field (R43) — malformed regardless of what `init` originally wrote, and
    * independent of any other structural rule this suite is not trying to probe.
    */
  private def corruptRepository(root: Path): Unit =
    writeText(
      root,
      ".snap/repository.json",
      """{"format": 1, "frontier": [], "patches": [], "bad": true}"""
    )

  private val malformedBody: String =
    """{"format": 1, "frontier": [], "patches": [], "bad": true}"""

  private val malformedMessage: String = "snap: repository has unknown field: bad\n"

  /** A minimal raw-socket HTTP stub that counts GETs (mirrors [[snap.http.ClientSuite]]'s `Stub`,
    * trimmed to what this suite needs). A local-path remote cannot prove "the remote is never read
    * AT ALL" on its own — reading its bytes has no observable side effect — so the HTTP variant of
    * every D11 precedence case here asserts [[requestCount]] instead of only the error class.
    */
  private final class CountingStub(body: String, status: Int = 200):
    private val httpServer =
      HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val executor = Executors.newSingleThreadExecutor()
    private val hits = new AtomicInteger(0)
    httpServer.setExecutor(executor)
    httpServer.createContext(
      "/repository.json",
      (exchange: HttpExchange) =>
        try
          hits.incrementAndGet()
          val bytes = body.getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        finally exchange.close()
    )
    httpServer.start()

    def url: String = s"http://127.0.0.1:${httpServer.getAddress.getPort}/repository.json"
    def requestCount: Int = hits.get()
    def stop(): Unit =
      httpServer.stop(0)
      executor.shutdownNow()
      ()

  private def withStub(body: String)(test: CountingStub => Unit): Unit =
    val stub = new CountingStub(body)
    try test(stub)
    finally stub.stop()

  // ======================================================================================
  // 1. D11 precedence at the combinations tests 20/26 never pair together
  // ======================================================================================

  test(
    "merge: a dirty tree beats a malformed LOCAL-path remote (D11's unobserved combination)"
  ) {
    val local = initRepo("a@x")
    writeText(local, "f.txt", "one\n")
    commit(local, "one")
    writeText(local, "dirty.txt", "dirty\n")
    val before = repoBytes(local)

    val remote = initRepo("b@x")
    corruptRepository(remote) // well-formed at init, then corrupted on disk

    assertEquals(run(local, "merge", remote.toString), (1, "", "snap: working tree is dirty\n"))
    assertEquals(repoBytes(local), before)
    assertEquals(Files.readString(local.resolve("dirty.txt")), "dirty\n")
  }

  test(
    "merge: a dirty tree beats a malformed HTTP remote — zero GETs ever reach it (D11)"
  ) {
    val local = initRepo("a@x")
    writeText(local, "f.txt", "one\n")
    commit(local, "one")
    writeText(local, "dirty.txt", "dirty\n")
    val before = repoBytes(local)

    withStub(malformedBody) { stub =>
      assertEquals(run(local, "merge", stub.url), (1, "", "snap: working tree is dirty\n"))
      assertEquals(
        stub.requestCount,
        0,
        "the remote must never be contacted once the tree is dirty"
      )
    }
    assertEquals(repoBytes(local), before)
  }

  test(
    "merge: an unsupported working-tree entry ALSO beats a malformed HTTP remote (D11 order)"
  ) {
    // D11's scan step reports an unsupported entry before the dirty check even runs (test 20's own
    // two-step shape); pairing it with a malformed remote instead of test 20's unreachable one is
    // the same unobserved-combination principle as the dirty case above.
    val local = initRepo("a@x")
    writeText(local, "f.txt", "one\n")
    commit(local, "one")
    Files.createSymbolicLink(local.resolve("link"), local.resolve("f.txt"))
    val before = repoBytes(local)

    withStub(malformedBody) { stub =>
      assertEquals(
        run(local, "merge", stub.url),
        (1, "", "snap: unsupported working tree entry: link\n")
      )
      assertEquals(stub.requestCount, 0)
    }
    assertEquals(repoBytes(local), before)
  }

  test(
    "merge: a malformed LOCAL repository is reported before a reachable local-path remote is loaded"
  ) {
    val local = initRepo("a@x")
    corruptRepository(local)
    val corruptedLocal = repoBytes(local)

    val remote = initRepo("b@x")
    writeText(remote, "g.txt", "remote\n")
    commit(remote, "remote")
    val remoteBefore = repoBytes(remote)

    assertEquals(run(local, "merge", remote.toString), (1, "", malformedMessage))
    assertEquals(repoBytes(local), corruptedLocal) // R103: still untouched
    assertEquals(repoBytes(remote), remoteBefore) // the remote was never even opened
  }

  test(
    "merge: a malformed LOCAL repository is reported before an HTTP remote is ever contacted"
  ) {
    val local = initRepo("a@x")
    corruptRepository(local)
    val corruptedLocal = repoBytes(local)

    withStub(malformedBody) { stub =>
      assertEquals(run(local, "merge", stub.url), (1, "", malformedMessage))
      assertEquals(stub.requestCount, 0, "local validation must fail before any GET is issued")
    }
    assertEquals(repoBytes(local), corruptedLocal)
  }

  test(
    "diff --repo: a malformed LOCAL repository is reported before an HTTP remote is contacted"
  ) {
    val local = initRepo("a@x")
    corruptRepository(local)
    val corruptedLocal = repoBytes(local)

    withStub(malformedBody) { stub =>
      val result = run(local, "diff", "()", "()", "--repo", stub.url)
      assertEquals(result, (1, "", malformedMessage))
      assertEquals(stub.requestCount, 0, "local validation must fail before any GET is issued")
    }
    assertEquals(repoBytes(local), corruptedLocal)
  }

  // ======================================================================================
  // 2. No-mutation on every failing path (R103) — mirrors test 26's own tree/JSON assertions
  // ======================================================================================

  test("merge with a malformed local-path remote mutates nothing (test 26's shape, local path)") {
    val local = initRepo("a@x")
    val initialFiles = allFiles(local)
    val remote = initRepo("b@x")
    corruptRepository(remote)

    val (exit, out, err) = run(local, "merge", remote.toString)
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.matches("snap: .+\n"), err)
    assertEquals(allFiles(local), initialFiles)
  }

  test("diff --repo with a malformed local-path remote mutates nothing") {
    val local = initRepo("a@x")
    val initialFiles = allFiles(local)
    val remote = initRepo("b@x")
    corruptRepository(remote)

    val (exit, out, err) = run(local, "diff", "()", "()", "--repo", remote.toString)
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.matches("snap: .+\n"), err)
    assertEquals(allFiles(local), initialFiles)
  }

  test("merge with a malformed HTTP remote mutates nothing and issues exactly one GET") {
    val local = initRepo("a@x")
    val initialFiles = allFiles(local)

    withStub(malformedBody) { stub =>
      val (exit, out, err) = run(local, "merge", stub.url)
      assertEquals(exit, 1)
      assertEquals(out, "")
      assert(err.matches("snap: .+\n"), err)
      assertEquals(stub.requestCount, 1)
    }
    assertEquals(allFiles(local), initialFiles)
  }

  test("diff --repo with a malformed HTTP remote mutates nothing and issues exactly one GET") {
    val local = initRepo("a@x")
    val initialFiles = allFiles(local)

    withStub(malformedBody) { stub =>
      val (exit, out, err) = run(local, "diff", "()", "()", "--repo", stub.url)
      assertEquals(exit, 1)
      assertEquals(out, "")
      assert(err.matches("snap: .+\n"), err)
      assertEquals(stub.requestCount, 1)
    }
    assertEquals(allFiles(local), initialFiles)
  }

  // ======================================================================================
  // 3. Structural patch identity (R41/R47) — test 26's premise, pinned directly
  // ======================================================================================

  // The same one patch, written two ways: 2-space canonical (like our own writer's output) and
  // single-line with every object's keys reversed — the exact pair test 26 exercises end-to-end.
  private val canonicallyFormatted: String =
    """{
      |  "format": 1,
      |  "frontier": [["same@x", 1]],
      |  "patches": [
      |    {
      |      "author": "same@x",
      |      "revision": 1,
      |      "base": [],
      |      "message": "same",
      |      "changes": [{"type": "text", "path": "f", "edit": [{"insert": ["same\n"]}]}]
      |    }
      |  ]
      |}
      |""".stripMargin

  private val whitespaceAndKeyOrderVaried: String =
    """{ "patches": [
      |  {
      |    "changes": [{"edit": [{"insert": ["same\n"]}], "path": "f", "type": "text"}],
      |    "message": "same", "base": [], "revision": 1, "author": "same@x"
      |  }
      |], "frontier": [["same@x", 1]], "format": 1
      |}
      |""".stripMargin

  test(
    "structural patch identity: JSON whitespace/key order never affects the decoded value (R41)"
  ) {
    val left = snap.json.JsonParser
      .parse(canonicallyFormatted)
      .flatMap(snap.json.RepoCodec.decode)
      .getOrElse(fail("test fixture: left should parse"))
    val right = snap.json.JsonParser
      .parse(whitespaceAndKeyOrderVaried)
      .flatMap(snap.json.RepoCodec.decode)
      .getOrElse(fail("test fixture: right should parse"))
    assertEquals(left, right) // same parsed typed value despite differing bytes
    // A clean union — not a collision — because dedupe is structural, not byte-wise (R47).
    assertEquals(CommandsMerge.unionPatches(left.patches, right.patches), Right(left.patches))
  }

  test(
    "structural patch identity end-to-end: merging 'duplicate' repos is a clean no-op (test 26)"
  ) {
    val left = Files.createTempDirectory("snap-xrepo-dup-left")
    val right = Files.createTempDirectory("snap-xrepo-dup-right")
    assertEquals(run(left, "init")._1, 0)
    assertEquals(run(right, "init")._1, 0)
    writeText(left, "f", "same\n")
    writeText(right, "f", "same\n")
    writeText(left, ".snap/repository.json", canonicallyFormatted)
    writeText(right, ".snap/repository.json", whitespaceAndKeyOrderVaried)

    assertEquals(run(left, "merge", right.toString), (0, "(same@x->1)\n", ""))
    assertEquals(Files.readString(left.resolve("f")), "same\n") // test 26's own assertion
    // The union collapsed to the ONE structurally-equal patch, not a `patch collision`.
    val merged =
      Store.readRepository(repositoryFile(left)).getOrElse(fail("expected a valid merge"))
    assertEquals(merged.repository.patches.length, 1)
  }

  // ======================================================================================
  // 4. Portability bytes (R19/R50/R53) through a REAL local exchange, end to end
  // ======================================================================================

  private val crlfBytes: Array[Byte] = "a\r\nb".getBytes(StandardCharsets.UTF_8) // no trailing LF
  private val nulBytes: Array[Byte] = Array[Byte]('a'.toByte, 0x00.toByte, 'b'.toByte)
  private val unicodeText: String = "hé\n"

  /** Builds and commits the portable remote fixture (test 26's own three files), via a REAL
    * `commit` over real files on disk — not a hand-built [[snap.core.Patch]] value.
    */
  private def portableRemote(): Path =
    val remote = initRepo("remote@x")
    write(remote, "crlf.txt", crlfBytes)
    write(remote, "nul.bin", nulBytes)
    writeText(remote, "unicode.txt", unicodeText)
    assertEquals(
      run(remote, "commit", "portable-bytes"),
      (0, "(remote@x->1)\n", "")
    )
    remote

  test("commit classifies the NUL-bearing file as put and preserves CRLF/Unicode bytes exactly") {
    val remote = portableRemote()
    val patches =
      Store.readRepository(repositoryFile(remote)).getOrElse(fail("fixture")).repository.patches
    assertEquals(patches.length, 1)
    val changes = patches.head.changes
    assertEquals(changes.length, 3) // sorted by path: crlf.txt < nul.bin < unicode.txt
    changes(0) match
      case Change.Text(path, edit) =>
        assertEquals(path.value, "crlf.txt")
        assertEquals(edit.ops, Vector(EditOp.Insert(Vector("a\r\n", "b"))))
      case other => fail(s"expected a text change for crlf.txt, got $other")
    changes(1) match
      case Change.Put(path, content) =>
        assertEquals(path.value, "nul.bin")
        assertEquals(IArray.genericWrapArray(content).toArray.toVector, nulBytes.toVector)
      case other => fail(s"expected a put change for nul.bin (NUL forces binary), got $other")
    changes(2) match
      case Change.Text(path, edit) =>
        assertEquals(path.value, "unicode.txt")
        assertEquals(edit.ops, Vector(EditOp.Insert(Vector(unicodeText))))
      case other => fail(s"expected a text change for unicode.txt, got $other")
  }

  test("diff --repo renders portable bytes exactly (test 26's own case, pinned directly)") {
    val remote = portableRemote()
    val local = Files.createTempDirectory("snap-xrepo-portable-diff")
    assertEquals(run(local, "init")._1, 0)
    assertEquals(
      run(local, "diff", "()", "(remote@x->1)", "--repo", remote.toString),
      (
        0,
        "--- /dev/null\n+++ b/crlf.txt\n@@ -1,0 +1,2 @@\n+a\r\n+b\n\\ No newline at end of file\n" +
          "Binary files /dev/null and b/nul.bin differ\n" +
          "--- /dev/null\n+++ b/unicode.txt\n@@ -1,0 +1,1 @@\n+hé\n",
        ""
      )
    )
    assert(!Files.exists(local.resolve("crlf.txt")), "diff --repo must never import")
  }

  test(
    "a real MERGE (not just diff) materializes portable bytes byte-exact on disk — new coverage"
  ) {
    // Test 26 only exercises `diff --repo` for these three files; a real `merge` additionally routes
    // them through union -> replay -> install (Materialize), which is genuinely different code from
    // the diff renderer and is not otherwise pinned with these exact bytes anywhere in the suite.
    val remote = portableRemote()
    val local = Files.createTempDirectory("snap-xrepo-portable-merge")
    assertEquals(run(local, "init")._1, 0)
    assertEquals(run(local, "merge", remote.toString), (0, "(remote@x->1)\n", ""))
    assertEquals(Files.readAllBytes(local.resolve("crlf.txt")).toVector, crlfBytes.toVector)
    assertEquals(Files.readAllBytes(local.resolve("nul.bin")).toVector, nulBytes.toVector)
    assertEquals(
      Files.readAllBytes(local.resolve("unicode.txt")).toVector,
      unicodeText.getBytes(StandardCharsets.UTF_8).toVector
    )
  }

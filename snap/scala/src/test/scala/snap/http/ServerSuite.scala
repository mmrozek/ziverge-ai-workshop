package snap.http

import munit.FunSuite
import snap.core.ContributorId
import snap.core.Patch
import snap.core.Repository
import snap.core.SnapError
import snap.core.Version
import snap.json.RepoCodec

import java.net.Socket
import java.nio.charset.StandardCharsets

/** [[Server]] unit tests (SPEC §7.9/§9): routing on the raw request target, HEAD's zero-body
  * contract (checked over a raw socket, not a higher-level HTTP client — DESIGN gotcha 6, mirroring
  * how the provided harness itself checks it), and the R90 holdout gap (bare `--serve`'s default
  * port). The full success path — ready line + SIGINT/SIGTERM + process exit — is provided test 12
  * (a real subprocess); see [[snap.cli.CommandsServeSuite]]'s class doc for why that path is not
  * safe to drive in-process here.
  *
  * Every test binds an ephemeral port (`0`) and stops the server in a `finally` — no port or accept
  * thread may leak between cases (task instructions; a leaked accept loop would hang the whole
  * suite).
  */
class ServerSuite extends FunSuite:

  /** A small, non-empty snapshot — one patch, so the served body is more than the trivial
    * `{"format": 1, "frontier": [], "patches": []}` empty-repository shape (belt-and-suspenders
    * against a routing bug that happens to also produce the right bytes for the empty case). Not
    * run through [[snap.core.Repo.validateFully]] — [[Server]] only serves bytes, it never
    * re-validates them, so this fixture only needs to satisfy [[Patch.make]]'s own shape checks,
    * not full causal closure.
    */
  private val snapshot: Array[Byte] =
    def unsafe[A](either: Either[SnapError, A]): A =
      either.getOrElse(fail("test fixture"))
    val author = unsafe(ContributorId.parse("a@x"))
    val path = snap.core.SnapPath.parse("f").getOrElse(fail("test fixture"))
    val patch = unsafe(
      Patch.make(author, 1L, Version.empty, "one", Vector(snap.core.Change.Delete(path)))
    )
    val frontier = unsafe(Version.fromPairs(Vector(("a@x", 1L))))
    RepoCodec.encodeBytes(Repository(frontier, Vector(patch)))

  private def withServer(port: Int = 0)(testCode: Server.Instance => Unit): Unit =
    Server.start(snapshot, port) match
      case Left(err)       => fail(s"expected a successful bind: ${err.message}")
      case Right(instance) =>
        try testCode(instance)
        finally instance.stop()

  /** Sends one raw HTTP/1.1 request over a fresh socket and parses back `(status, headers, body)`.
    * `headers` keys are lower-cased (HTTP header names are case-insensitive). Deliberately bypasses
    * any higher-level HTTP client so a stray response body byte on a HEAD response — the exact
    * failure mode DESIGN gotcha 6 calls out — is unambiguously visible as extra bytes after the
    * header block, the same way the provided harness observes it.
    */
  private def rawRequest(
      instance: Server.Instance,
      method: String,
      target: String
  ): (Int, Map[String, String], Array[Byte]) =
    val socket = new Socket("127.0.0.1", instance.port)
    try
      // `Connection: close` (RFC 7230 §6.6) so the server closes the socket once the response is
      // sent, regardless of the response's own framing — otherwise a 200 with a defined
      // Content-Length keeps the HTTP/1.1 connection alive and `readAllBytes()` below would block
      // forever waiting for an EOF that never comes.
      val request = s"$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
      socket.getOutputStream.write(request.getBytes(StandardCharsets.UTF_8))
      socket.getOutputStream.flush()
      val raw = socket.getInputStream.readAllBytes()
      // ISO-8859-1 is a 1:1 char/byte mapping (unlike UTF-8), so an index found in the decoded
      // header text is exactly the matching byte offset in `raw` — needed to slice the body bytes
      // without any risk of an encoding-driven off-by-N.
      val asLatin1 = new String(raw, StandardCharsets.ISO_8859_1)
      val headerEnd = asLatin1.indexOf("\r\n\r\n")
      val headerLines = asLatin1.substring(0, headerEnd).split("\r\n").toList
      val status = headerLines.head.split(" ")(1).toInt
      val headers = headerLines.tail.map { line =>
        val sep = line.indexOf(':')
        (line.substring(0, sep).trim.toLowerCase, line.substring(sep + 1).trim)
      }.toMap
      (status, headers, raw.slice(headerEnd + 4, raw.length))
    finally socket.close()

  private def withoutDate(headers: Map[String, String]): Map[String, String] =
    headers.removed("date")

  // ------------------------------------------------------------------------------------- routing

  test("GET /repository.json: 200, the canonical content type, and the exact snapshot bytes") {
    withServer() { instance =>
      val (status, headers, body) = rawRequest(instance, "GET", "/repository.json")
      assertEquals(status, 200)
      assertEquals(headers.get("content-type"), Some("application/json; charset=utf-8"))
      assertEquals(body.toVector, snapshot.toVector)
    }
  }

  test("HEAD /repository.json: identical status/headers to GET, but zero body bytes (gotcha 6)") {
    withServer() { instance =>
      val (getStatus, getHeaders, _) = rawRequest(instance, "GET", "/repository.json")
      val (headStatus, headHeaders, headBody) = rawRequest(instance, "HEAD", "/repository.json")
      assertEquals(headStatus, getStatus)
      // `date` is `com.sun.net.httpserver`'s own auto-generated header (RFC 7231 §7.1.1.2), not
      // something this handler sets — excluded so this assertion can never flake on a request pair
      // that happens to straddle a one-second boundary (project-wide "no wall-clock in tests" spirit).
      assertEquals(withoutDate(headHeaders), withoutDate(getHeaders))
      assertEquals(headBody.length, 0)
    }
  }

  test("POST /repository.json: 405 with Allow: GET, HEAD") {
    withServer() { instance =>
      val (status, headers, _) = rawRequest(instance, "POST", "/repository.json")
      assertEquals(status, 405)
      assertEquals(headers.get("allow"), Some("GET, HEAD"))
    }
  }

  test("PUT and DELETE on the one resource are also 405 with the same Allow header") {
    withServer() { instance =>
      for method <- List("PUT", "DELETE") do
        val (status, headers, _) = rawRequest(instance, method, "/repository.json")
        assertEquals(status, 405, method)
        assertEquals(headers.get("allow"), Some("GET, HEAD"), method)
    }
  }

  test("an unrelated path is 404, regardless of method") {
    withServer() { instance =>
      for method <- List("GET", "HEAD", "POST") do
        val (status, _, _) = rawRequest(instance, method, "/other")
        assertEquals(status, 404, method)
    }
  }

  test(
    "a query string on the otherwise-exact resource path is a distinct target: 404 (test 12)"
  ) {
    withServer() { instance =>
      val (status, _, _) = rawRequest(instance, "GET", "/repository.json?x=1")
      assertEquals(status, 404)
    }
  }

  test("matching is on the raw target, not a normalized path: a trailing slash does not match") {
    withServer() { instance =>
      val (status, _, _) = rawRequest(instance, "GET", "/repository.json/")
      assertEquals(status, 404)
    }
  }

  // -------------------------------------------------------------------------------- binding (D9)

  test("binds an OS-selected port when asked for port 0, and it is a real listening port") {
    withServer() { instance =>
      assert(instance.port > 0, s"expected a real ephemeral port, got ${instance.port}")
      val (status, _, _) = rawRequest(instance, "GET", "/repository.json")
      assertEquals(status, 200)
    }
  }

  test(
    "bare `--serve` binds the default port 8765 (R90 holdout gap — skipped if already occupied)"
  ) {
    // D9/SPEC §7.9: 8765 is the documented default, mirrored in CommandsServe.DefaultPort — not
    // referenced directly here to keep this http-package test free of a `snap.cli` dependency
    // (the layering [[snap.http]] itself observes: `http` depends on `core`/`json`, never `cli`).
    val defaultPort = 8765
    Server.start(snapshot, defaultPort) match
      case Right(instance) =>
        try assertEquals(instance.port, defaultPort)
        finally instance.stop()
      case Left(SnapError.CannotBindServer(detail)) =>
        // Only acceptable failure on a shared machine: the port is already in use by something
        // else — skip rather than fail, per the task's own "skip-if-occupied guard" instruction.
        assume(false, s"port $defaultPort already in use, skipping: $detail")
      case Left(err) => fail(s"unexpected failure binding the default port: ${err.message}")
  }

  test("start reports a typed CannotBindServer, not an uncaught exception, on a bind conflict") {
    withServer() { first =>
      Server.start(snapshot, first.port) match
        case Left(SnapError.CannotBindServer(_)) => () // expected
        case Left(err)                           => fail(s"wrong error case: ${err.message}")
        case Right(second)                       =>
          second.stop()
          fail("expected the second bind on the same port to fail")
    }
  }

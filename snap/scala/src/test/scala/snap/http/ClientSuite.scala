package snap.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import munit.FunSuite
import snap.core.Change
import snap.core.ContributorId
import snap.core.EditOp
import snap.core.EditScript
import snap.core.Patch
import snap.core.Repository
import snap.core.SnapError
import snap.core.SnapPath
import snap.core.Version
import snap.json.RepoCodec

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.CollectionHasAsScala

/** [[Client]] unit tests (SPEC §9/§7.6/§7.8; R78, R102; DESIGN D15): the one-GET/no-redirect/status
  * contract, the strict body pipeline (a malformed body reports `invalid JSON`, test 13), and D15's
  * timeout/body-size cap — exercised with small injected values ([[Client.fetchRepository]]'s
  * `timeout`/`maxBodyBytes` parameters) rather than actually waiting 30 s or transferring 64 MiB.
  *
  * Every stub server binds an ephemeral port and is stopped in [[withStub]]'s `finally` — no port
  * or accept/worker thread may leak between cases (task instructions; a leaked accept loop would
  * hang the whole suite). The stub is built on the same `com.sun.net.httpserver.HttpServer`
  * [[Server]] uses (DESIGN D2), which gives full control over response shape (redirects, malformed
  * bodies, declared-but-unfulfilled `Content-Length`) that the production [[Server]] never needs.
  */
class ClientSuite extends FunSuite:

  private def unsafe[A](either: Either[SnapError, A]): A = either.getOrElse(fail("test fixture"))

  /** One-patch snapshot: unlike [[ServerSuite]]'s fixture (which the server only ever serves as
    * bytes, never re-validating them), this one must pass the FULL §4.5 pipeline — including step
    * 5's per-change base check — because [[Client.fetchRepository]] runs [[Repo.validateFully]] on
    * whatever it fetches. A single-insert text change creates `f` from its absent base, which is
    * valid; a `delete` of a path with no prior create (what [[ServerSuite]] uses) is not (R51).
    */
  private val validSnapshot: Array[Byte] =
    val author = unsafe(ContributorId.parse("a@x"))
    val path = SnapPath.parse("f").getOrElse(fail("test fixture"))
    val edit = EditScript(Vector(EditOp.Insert(Vector("hello\n"))))
    val patch = unsafe(
      Patch.make(author, 1L, Version.empty, "one", Vector(Change.Text(path, edit)))
    )
    val frontier = unsafe(Version.fromPairs(Vector(("a@x", 1L))))
    RepoCodec.encodeBytes(Repository(frontier, Vector(patch)))

  /** A single-route (`/`) raw HTTP stub: every request is logged (method, exact raw target — the
    * harness's own `http_requests_equal` analogue) and handed to `respond` to produce the response.
    * A dedicated cached thread pool (not [[Server]]'s single-thread executor) so a handler that
    * deliberately blocks (the timeout tests) never starves a later request in the same case.
    */
  private final class Stub(respond: HttpExchange => Unit):
    private val httpServer =
      HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val executor = Executors.newCachedThreadPool()
    private val log = new ConcurrentLinkedQueue[(String, String)]()
    httpServer.setExecutor(executor)
    httpServer.createContext(
      "/",
      (exchange: HttpExchange) =>
        try
          log.add((exchange.getRequestMethod, exchange.getRequestURI.toString))
          respond(exchange)
        catch case _: InterruptedException => () // a timeout test's stub thread was interrupted
        finally exchange.close()
    )
    httpServer.start()

    def url(target: String): String = s"http://127.0.0.1:${httpServer.getAddress.getPort}$target"
    def requests: List[(String, String)] = log.asScala.toList
    def stop(): Unit =
      httpServer.stop(0)
      executor.shutdownNow() // interrupts any handler still blocked (e.g. a timeout case's sleep)
      ()

  private def withStub(respond: HttpExchange => Unit)(test: Stub => Unit): Unit =
    val stub = new Stub(respond)
    try test(stub)
    finally stub.stop()

  private def respondBytes(exchange: HttpExchange, status: Int, body: Array[Byte]): Unit =
    exchange.sendResponseHeaders(status, body.length.toLong)
    exchange.getResponseBody.write(body)

  // -------------------------------------------------------------------------------- happy path

  test("fetchRepository: one GET, status 200, parses and fully validates the body (R78/R102)") {
    withStub(respondBytes(_, 200, validSnapshot)) { stub =>
      Client.fetchRepository(stub.url("/repository.json")) match
        case Right(valid) => assertEquals(valid.repository.patches.length, 1)
        case Left(err)    => fail(s"expected success: ${err.message}")
      assertEquals(stub.requests, List(("GET", "/repository.json")))
    }
  }

  test("fetchRepository preserves the exact request target — path and query byte-for-byte") {
    withStub(respondBytes(_, 200, validSnapshot)) { stub =>
      val target = "/repository.json?rev=3&note=hello"
      assert(Client.fetchRepository(stub.url(target)).isRight)
      assertEquals(stub.requests, List(("GET", target)))
    }
  }

  // -------------------------------------------------------------------------------- status / redirects

  test("a 302 is HttpStatus, not followed, and issues no second request (test 13)") {
    withStub { exchange =>
      exchange.getResponseHeaders.add("Location", "/elsewhere")
      exchange.sendResponseHeaders(302, -1)
    } { stub =>
      assertEquals(
        Client.fetchRepository(stub.url("/repository.json")),
        Left(SnapError.HttpStatus(302))
      )
      assertEquals(stub.requests, List(("GET", "/repository.json")))
    }
  }

  test("a non-redirect non-200 status (500) also fails, with no retry") {
    withStub(_.sendResponseHeaders(500, -1)) { stub =>
      assertEquals(
        Client.fetchRepository(stub.url("/repository.json")),
        Left(SnapError.HttpStatus(500))
      )
      assertEquals(stub.requests.size, 1)
    }
  }

  // -------------------------------------------------------------------------------- malformed body

  test("a malformed 200 body reports the invalid JSON diagnostic class (test 13)") {
    withStub(
      respondBytes(_, 200, "not-json".getBytes(StandardCharsets.UTF_8))
    ) { stub =>
      Client.fetchRepository(stub.url("/repository.json")) match
        case Left(err) => assert(err.message.contains("invalid JSON"), err.message)
        case Right(_)  => fail("expected a decode failure")
    }
  }

  // -------------------------------------------------------------------------------- D15: timeout

  test("a server that never responds fails with an error, not a hang (D15 timeout)") {
    withStub(_ => Thread.sleep(60000)) { stub =>
      val start = System.nanoTime()
      val result =
        Client.fetchRepository(stub.url("/repository.json"), timeout = Duration.ofMillis(300))
      val elapsedMs = (System.nanoTime() - start) / 1000000L
      assert(result.isLeft, result)
      assert(elapsedMs < 10000L, s"expected a bounded failure, took ${elapsedMs}ms")
    }
  }

  test(
    "a server that starts a response and stalls mid-body also fails, not hangs (D15 timeout)"
  ) {
    // Declares far more than it ever sends, then blocks — reproduces exactly the shape that
    // `HttpRequest.Builder.timeout` alone does NOT bound on this JDK (verified empirically, see
    // [[Client.DefaultTimeout]]'s doc): `sendAsync(...).orTimeout(...)` is the layer this covers.
    withStub { exchange =>
      exchange.sendResponseHeaders(200, 1000000L)
      exchange.getResponseBody.write("{\"format\"".getBytes(StandardCharsets.UTF_8))
      exchange.getResponseBody.flush()
      Thread.sleep(60000)
    } { stub =>
      val start = System.nanoTime()
      val result =
        Client.fetchRepository(stub.url("/repository.json"), timeout = Duration.ofMillis(300))
      val elapsedMs = (System.nanoTime() - start) / 1000000L
      assert(result.isLeft, result)
      assert(elapsedMs < 10000L, s"expected a bounded failure, took ${elapsedMs}ms")
    }
  }

  // -------------------------------------------------------------------------------- D15: body cap

  test("a declared Content-Length over the cap is rejected without reading the body") {
    withStub { exchange =>
      val big = Array.fill(1000000)('a'.toByte)
      respondBytes(exchange, 200, big)
    } { stub =>
      val result = Client.fetchRepository(stub.url("/repository.json"), maxBodyBytes = 100L)
      assert(result.isLeft, result)
    }
  }

  test("an oversized body with no declared length (chunked) is rejected after being read (D15)") {
    withStub { exchange =>
      exchange.sendResponseHeaders(200, 0) // 0 => chunked transfer, no Content-Length header
      exchange.getResponseBody.write(Array.fill(1000)('a'.toByte))
    } { stub =>
      val result = Client.fetchRepository(stub.url("/repository.json"), maxBodyBytes = 100L)
      assert(result.isLeft, result)
    }
  }

  test("a body within the cap still succeeds") {
    withStub(respondBytes(_, 200, validSnapshot)) { stub =>
      val result = Client.fetchRepository(stub.url("/repository.json"), maxBodyBytes = 1000000L)
      assert(result.isRight, result)
    }
  }

  // -------------------------------------------------------------------------------- https:// (holdout)

  test("an https:// URL is routed to the real client — routing only, no TLS handshake (R102)") {
    // Nothing listens here; a connection failure through the SAME `fetchRepository` path as
    // `http://` proves routing without attempting a real TLS handshake (task acceptance: "TLS
    // itself is a holdout gap"). [[snap.cli.CommandsMergeSuite]] pins the same routing through the
    // full CLI resolution layer.
    val result =
      Client.fetchRepository("https://127.0.0.1:1/repository.json", timeout = Duration.ofSeconds(2))
    result match
      case Left(SnapError.HttpRequestFailed(_)) => () // expected: a real connection attempt failed
      case other                                => fail(s"expected HttpRequestFailed, got: $other")
  }

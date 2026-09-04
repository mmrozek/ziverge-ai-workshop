package snap.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import snap.core.SnapError
import sun.misc.Signal
import sun.misc.SignalHandler

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** `snap --serve`'s HTTP server (SPEC §7.9/§9; DESIGN §7, D2, D21, gotcha 6).
  *
  * Serves exactly one fixed resource, `/repository.json`, from an immutable byte snapshot fixed at
  * [[start]] time — this object never re-reads the repository, so a later `commit` in the same
  * working copy cannot change what a still-running server returns (SPEC §7.9 "the startup
  * snapshot"; test 12). Built on the JDK's `com.sun.net.httpserver` (DESIGN D2: HTTP stays JDK — a
  * Scala effect-runtime HTTP stack would be disproportionate for one two-route read-only server).
  *
  * Routing matches the RAW request-target string (`HttpExchange.getRequestURI.toString`), never a
  * decoded/normalized path: `com.sun.net.httpserver` constructs that `URI` straight from the
  * request line's request-target substring, so its `toString` reproduces that substring verbatim
  * (including an unstripped query string) rather than a resolved/normalized path. That is what
  * makes `/repository.json?x=1` a distinct, unmatched target (404) instead of being silently
  * treated as `/repository.json` (SPEC §9; test 12).
  */
object Server:

  /** SPEC §9: the one resource this server ever serves. */
  private val ResourcePath = "/repository.json"

  private val ContentType = "application/json; charset=utf-8"

  /** SPEC §9: "other methods return 405 with `Allow: GET, HEAD`" — the exact header value. */
  private val AllowedMethods = "GET, HEAD"

  /** A bound, already-listening server. [[port]] reads back the OS-assigned value when [[start]]
    * was asked to bind port `0` (SPEC §7.9). [[stop]] releases the socket with no grace period and
    * shuts down the dedicated dispatch executor [[start]] created for it — tests bind ephemeral
    * instances per case and must call this in a fixture teardown so no port or accept thread leaks
    * between cases.
    */
  final case class Instance private[http] (
      private val httpServer: HttpServer,
      private val executor: ExecutorService
  ):
    def port: Int = httpServer.getAddress.getPort
    def stop(): Unit =
      httpServer.stop(0)
      executor.shutdownNow()
      ()

  /** Binds `127.0.0.1:port` and starts accepting connections immediately, serving `snapshot` for
    * every future request (captured once, by reference, in the closure [[handler]] builds — never
    * re-read). Binding failures (address already in use, an unresolvable address) are reported as a
    * typed [[SnapError.CannotBindServer]] rather than left to surface as R107's generic "internal
    * error" exit 2 — the same thrown-exception-to-typed-error boundary every other
    * filesystem/network effect in this codebase already uses (D4; mirrors `snap.fs.Store`'s
    * `attempt`).
    */
  def start(snapshot: Array[Byte], port: Int): Either[SnapError, Instance] =
    Try {
      val address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
      val httpServer = HttpServer.create(address, 0)
      httpServer.createContext("/", handler(snapshot))
      // A dedicated single-thread executor, owned by this Instance and shut down in `stop` —
      // ample for a read-only, two-route snapshot server under this project's startup/latency
      // budget (DESIGN D2 rationale); no request ever blocks on I/O beyond writing an in-memory
      // byte array. Explicit rather than the JDK's "leave it unset" default (which takes a `null`
      // executor argument, disallowed here — conventions `DisableSyntax.noNulls`) so this object
      // owns a thread it can deterministically tear down instead of relying on `HttpServer.stop`'s
      // handling of its own internal default dispatcher.
      val executor = Executors.newSingleThreadExecutor()
      httpServer.setExecutor(executor)
      httpServer.start()
      (httpServer, executor)
    } match
      case Success((httpServer, executor)) => Right(Instance(httpServer, executor))
      case Failure(e)                      => Left(SnapError.CannotBindServer(describe(e)))

  /** SIGINT/SIGTERM → exit **0** (D21; SPEC §7.9 "until SIGINT or SIGTERM, then exits 0"). The
    * JVM's own default disposition for these signals produces exit codes 130/143 instead —
    * `sun.misc.Signal` is the only way to override that on the JDK (D21; no public replacement API
    * exists). Installed only from [[snap.cli.CommandsServe.handler]]'s success path, and — load-
    * bearing, see that handler's class doc — strictly *before* the ready line is printed: a caller
    * may signal the process the instant it sees that line, so the handlers must already be live by
    * then. Never installed from a test, which would tear down the whole test JVM the instant either
    * signal actually fired.
    */
  def installShutdownHandlers(): Unit =
    val exitOnSignal: SignalHandler = (_: Signal) => System.exit(0)
    Signal.handle(new Signal("INT"), exitOnSignal)
    Signal.handle(new Signal("TERM"), exitOnSignal)

  /** Blocks the calling thread forever. In production this call never returns: the process ends
    * only when a signal handler installed by [[installShutdownHandlers]] calls `System.exit` from a
    * different thread, which tears down the whole JVM — including this park — without this method
    * ever completing normally. Return type `Nothing` lets [[snap.cli.CommandsServe.handler]] end
    * its success branch here directly, with no unreachable dead-code value needed to satisfy
    * `CommandHandler`'s `Either[SnapError, String]` result type.
    *
    * Nothing ever counts the latch down (no `throw`/dead-code value needed to type this as
    * `Nothing`, conventions `DisableSyntax.noThrows`): if `await()` ever spuriously returned
    * without the JVM having already exited, the tail-recursive self-call parks on a fresh latch
    * again, forever, rather than falling through with a fabricated value.
    */
  @tailrec def blockForever(): Nothing =
    val latch = new CountDownLatch(1)
    latch.await()
    blockForever()

  /** SPEC §7.9's exact ready line, always plain (R96) — printed by the CLI layer, not here, so this
    * object stays free of any `Env`/`Presentation` dependency.
    */
  def readyLine(port: Int): String = s"http://127.0.0.1:$port$ResourcePath\n"

  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  /** One handler for every request, routed by the exact raw target string (never a
    * parsed/normalized path — SPEC §9): `/repository.json` alone dispatches on method (`GET`/`HEAD`
    * served, anything else 405 + `Allow`); every other target — including `/repository.json` with a
    * query string — is 404, regardless of method. Path is checked before method, matching SPEC §9's
    * own bullet order ("Other paths return 404; other methods return 405"): a `POST /elsewhere` is
    * a 404, not a 405.
    *
    * Every response also sends `Connection: close` (see [[respondClosing]]): this server never
    * keeps a connection open past one request/response. Empirically load-bearing, not cosmetic —
    * `com.sun.net.httpserver` silently closes the TCP connection itself after any response whose
    * length it cannot frame as persistent (every `sendResponseHeaders(code, -1)` call here: HEAD,
    * 404, 405), but without an explicit `Connection: close` response header a client that pools
    * HTTP/1.1 connections (e.g. Node's default `http.Agent`, which is what the provided harness's
    * non-HEAD `http_request` step uses) has no way to know that and tries to reuse the now-dead
    * socket for its very next request — observed as "socket hang up" on exactly the request that
    * follows a 404/405 in test 12's sequence. Closing every response, including the 200s, keeps the
    * policy uniform and defect-proof rather than reasoning per status code about which lengths the
    * framework happens to frame safely today.
    */
  private def handler(snapshot: Array[Byte]): HttpHandler =
    (exchange: HttpExchange) =>
      try
        val rawTarget = exchange.getRequestURI.toString
        if rawTarget == ResourcePath then
          exchange.getRequestMethod match
            case "GET"  => respondGet(exchange, snapshot)
            case "HEAD" => respondHead(exchange, snapshot)
            case _      => respondMethodNotAllowed(exchange)
        else respondNotFound(exchange)
      finally exchange.close()

  /** `Connection: close` on every response (see the [[handler]] doc) — set before
    * `sendResponseHeaders`, which is when the JDK server actually transmits the header block.
    */
  private def respondClosing(exchange: HttpExchange): Unit =
    exchange.getResponseHeaders.add("Connection", "close")

  private def respondGet(exchange: HttpExchange, snapshot: Array[Byte]): Unit =
    exchange.getResponseHeaders.add("Content-Type", ContentType)
    respondClosing(exchange)
    // A positive length makes the framework set the matching Content-Length header itself.
    exchange.sendResponseHeaders(200, snapshot.length.toLong)
    exchange.getResponseBody.write(snapshot)

  /** Gotcha 6: HEAD gets an explicit `Content-Length` — matching exactly what GET just sent, so the
    * two responses' headers are identical — followed by `sendResponseHeaders(200, -1)` and no write
    * at all. `-1` tells the framework no body follows (skipping chunked encoding, which would
    * otherwise need a terminating zero-length chunk); writing even one byte here is what fails the
    * harness's raw-socket HEAD assertion (test 12).
    */
  private def respondHead(exchange: HttpExchange, snapshot: Array[Byte]): Unit =
    exchange.getResponseHeaders.add("Content-Type", ContentType)
    exchange.getResponseHeaders.add("Content-Length", snapshot.length.toString)
    respondClosing(exchange)
    exchange.sendResponseHeaders(200, -1)

  private def respondNotFound(exchange: HttpExchange): Unit =
    respondClosing(exchange)
    exchange.sendResponseHeaders(404, -1)

  private def respondMethodNotAllowed(exchange: HttpExchange): Unit =
    exchange.getResponseHeaders.add("Allow", AllowedMethods)
    respondClosing(exchange)
    exchange.sendResponseHeaders(405, -1)

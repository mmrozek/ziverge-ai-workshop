package snap.http

import snap.core.Repo
import snap.core.SnapError
import snap.core.TextTokens
import snap.json.JsonParser
import snap.json.RepoCodec

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodySubscribers
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** The read-only HTTP repository client (SPEC §9/§7.6/§7.8; R78, R102; DESIGN §7, D15).
  *
  * Exactly one GET of the exact URL a repository operand names, redirects
  * [[HttpClient.Redirect.NEVER]] (SPEC §9: "redirects... are out of scope"; test 13 pins `HTTP 302`
  * with no second request following the `Location` header). Status must be 200 — anything else is
  * [[SnapError.HttpStatus]]. A 200 body is run through the exact same strict pipeline a local
  * `repository.json` read uses ([[snap.fs.Store.readRepository]]: UTF-8 decode → strict JSON parse
  * → typed decode → full §4.5 structural+replay validation), so a malformed remote body reports the
  * same `invalid JSON` diagnostic class a malformed local file would (test 13).
  *
  * Built on `java.net.http.HttpClient` (DESIGN D2: HTTP stays JDK — a Scala effect-runtime stack
  * would be disproportionate for one blocking GET). A fresh client is built per call: this is a
  * single request, not a connection-pooled long-lived service, so there is no shared state to reuse
  * or tear down (contrast [[snap.http.Server]], which owns a long-lived listening socket and thread
  * pool across many requests).
  *
  * D15's two limits are threaded in as parameters — never read from the ambient environment
  * (conventions "Determinism") — defaulting to the locked values so production call sites
  * ([[snap.cli.Commands.loadRemoteRepository]]) need not repeat them; tests inject small ones so a
  * timeout/cap case runs in milliseconds instead of actually waiting 30 s or transferring 64 MiB.
  */
object Client:

  /** D15: 30 s request timeout (Q11 — untested, generous). Set on the request itself AND enforced a
    * second, independent way (see [[get]]) — verified empirically (T20 Notes / decisions) that on
    * this JDK, [[HttpRequest.Builder.timeout]] alone reliably bounds a server that never sends
    * anything at all, but does NOT bound one that starts a response (status line, or a declared
    * `Content-Length`) and then stalls mid-body: `HttpClient.send` blocks past the deadline until
    * the connection eventually drops, however long that takes. A hanging server must fail with an
    * error, not hang past the timeout, in BOTH shapes of hang (D15), hence the second layer.
    */
  val DefaultTimeout: Duration = Duration.ofSeconds(30)

  /** D15: 64 MiB response body cap (Q11 — untested, generous). */
  val DefaultMaxBodyBytes: Long = 64L * 1024 * 1024

  /** R78/R102: fetches, parses, and fully validates the repository served at `url`. */
  def fetchRepository(
      url: String,
      timeout: Duration = DefaultTimeout,
      maxBodyBytes: Long = DefaultMaxBodyBytes
  ): Either[SnapError, Repo.Valid] =
    for
      bytes <- get(url, timeout, maxBodyBytes)
      text <- TextTokens.decodeUtf8(bytes).toRight(SnapError.RemoteBodyNotUtf8)
      json <- JsonParser.parse(text)
      repository <- RepoCodec.decode(json)
      valid <- Repo.validateFully(repository)
    yield valid

  /** The one GET (SPEC §9). Every failure mode — a non-200 status, an oversized body, connection
    * refusal, a malformed URL, or a hanging server (either shape, see [[DefaultTimeout]]) — is
    * caught at this one effect boundary (D4, mirrors [[snap.http.Server.start]]'s `Try`) instead of
    * escaping as an uncaught exception.
    *
    * `sendAsync(...).orTimeout(...).get()` rather than the simpler `send(...)`: `orTimeout` is a
    * `CompletableFuture`-level deadline that does not depend on `HttpClient`'s own timeout
    * machinery at all — it unblocks this call even in the stalled-mid-body case where that
    * machinery does not (verified empirically, see [[DefaultTimeout]]'s doc). The abandoned
    * exchange, if any, is left to `HttpClient`'s own daemon-thread executor (verified empirically
    * to not block JVM exit) — there is no public JDK 17 API to cancel it outright, and this is a
    * single blocking GET, not a pooled resource this object owns and must tear down (contrast
    * [[snap.http.Server.stop]]).
    */
  private def get(
      url: String,
      timeout: Duration,
      maxBodyBytes: Long
  ): Either[SnapError, Array[Byte]] =
    Try {
      val client = HttpClient
        .newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER) // SPEC §9: redirects are never followed
        .connectTimeout(timeout)
        .build()
      val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build()
      client
        .sendAsync(request, cappingHandler(maxBodyBytes))
        .orTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
        .get()
    } match
      case Failure(e)        => Left(SnapError.HttpRequestFailed(describe(unwrap(e))))
      case Success(response) =>
        if response.statusCode() != 200 then Left(SnapError.HttpStatus(response.statusCode()))
        else response.body().left.map(SnapError.HttpRequestFailed.apply)

  /** `Future.get()` always wraps the real failure in an [[ExecutionException]] (`orTimeout` itself
    * completes the future with a bare `TimeoutException`, no wrapping needed there); unwrapped so
    * [[describe]] renders the actual cause (`ConnectException`, `HttpTimeoutException`,
    * `TimeoutException`, ...) instead of the uninformative wrapper's own class name every time.
    */
  private def unwrap(e: Throwable): Throwable = e match
    case ee: ExecutionException => Option(ee.getCause).getOrElse(ee)
    case other                  => other

  /** A [[HttpResponse.BodyHandler]] that never unconditionally buffers more than `limit` bytes: a
    * declared `Content-Length` over the limit is rejected without reading any body bytes at all
    * (`BodySubscribers.discarding`); otherwise the body is read in full and re-checked against the
    * limit — this second path buffers one oversized body before rejecting it (no `Content-Length`,
    * e.g. chunked transfer, gives no cheaper way to know the size up front), an accepted cost for
    * D15's own "generous, untested" cap (T20 Notes / decisions).
    */
  private def cappingHandler(limit: Long): HttpResponse.BodyHandler[Either[String, Array[Byte]]] =
    (responseInfo: HttpResponse.ResponseInfo) =>
      val declared = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L)
      if declared > limit then
        BodySubscribers.mapping(
          BodySubscribers.discarding(),
          (_: Void) => Left(s"remote response declares $declared bytes, over the $limit-byte limit")
        )
      else
        BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          (bytes: Array[Byte]) =>
            if bytes.length.toLong > limit then
              Left(s"remote response is ${bytes.length} bytes, over the $limit-byte limit")
            else Right(bytes)
        )

  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

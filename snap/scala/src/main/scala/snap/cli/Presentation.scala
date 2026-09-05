package snap.cli

/** What a successful command's stdout text actually *is*, semantically — the minimum structure
  * [[Presentation.Terminal]] needs to restyle §7.11's plain bytes without reparsing them (reparsing
  * plain text back into rows/entries would need partial matches on data whose shape is only an
  * implementation convention, not a contract). [[Presentation.Plain]] ignores this entirely: it
  * always prints the already-correct plain `text` verbatim, so `kind` can never influence
  * plain-mode bytes (R92). `diff` is the one exception in spirit, not mechanism — §7.11 defines its
  * terminal styling AS a transform of the plain bytes ("diff preserves every plain byte
  * except..."), so [[Presentation.Terminal]] legitimately reparses `text` line-by-line for that one
  * case.
  */
enum ResultKind:
  /** `init`/`commit`/`revert`/`merge` (SPEC §7.11): `text` is `"(version)\n"`; terminal mode adds
    * the `label` word and the ✓ glyph around it.
    */
  case Success(label: String)

  /** `status` (SPEC §7.3/§7.11): the current version's canonical text, plus the same deltas
    * `text`'s A/M/D rows were built from (path order, R83) — terminal mode's colored rows are a
    * direct rendering of `deltas`, never a reparse of `text`.
    */
  case Status(version: String, deltas: Vector[Delta])

  /** `log` (SPEC §7.4/§7.11): one [[LogLine]] per entry, already in the same reverse-integration
    * display order as `text`'s TSV rows (both built from one pass over that order, T22 — see
    * [[CommandsLog]]).
    */
  case LogEntries(entries: Vector[LogLine])

  /** `diff` (SPEC §7.6/§7.11): `text` IS the content — terminal mode restyles it line-by-line per
    * §7.11's literal "preserves every plain byte except..." rule, so no separate payload is
    * carried.
    */
  case Diff

  /** `--version` (SPEC §7.10/§7.11): `text` is `"snap 1.0.0\n"`, wrapped bold with no other change.
    */
  case VersionLine

  /** Everything else: `config`'s silent `""`, and `--serve`'s ready line (rendered through
    * [[Presentation.Plain]] directly, per R96 — never reaches `Terminal` with this kind at all, but
    * it needs a total value for the one direct call). Printed unchanged by every implementation.
    */
  case Raw

/** One `log` entry's already-escaped display fields (SPEC §7.4/§7.11): `message` is the same
  * escaped one-line text (backslash, tab, LF → `\\`, `\t`, `\n`, gotcha 8) that both plain and
  * terminal output use verbatim — §7.11 does not re-escape or unescape it.
  */
final case class LogLine(version: String, author: String, message: String)

/** Output seam (DESIGN §8, R92–R97): every command result or error funnels through here, so the
  * per-stream terminal renderer can be selected without any call site changing. Presentation never
  * influences execution, effects, warning selection/order, or exit status (R92) — [[Cli]] alone
  * decides those; a `Presentation` only turns already-decided outcomes into bytes.
  */
trait Presentation:

  /** A successful command's stdout text, already fully formatted in §7.11's plain shape (including
    * any trailing LF). Empty text prints nothing in every implementation — several commands succeed
    * silently (e.g. `config`, spec §7.2) and R97 requires empty plain output to stay empty in
    * terminal mode too, with no stray SGR/reset bytes. `kind` carries the minimum structure a
    * terminal renderer needs to restyle `text` (see [[ResultKind]]); [[Plain]] ignores it entirely.
    */
  def result(env: Env, kind: ResultKind, text: String): Unit

  /** An error detail, without the `snap: ` prefix (spec §10: plain errors are one line
    * `snap: <detail>`).
    */
  def error(env: Env, detail: String): Unit

  /** A warning detail, without the `warning: ` prefix (spec §6.4/§7.11: a plain warning is one line
    * `warning: <detail>`; terminal mode restyles it). Only `merge` emits warnings (R75); the
    * command decides selection and order (R92) by returning them as data
    * ([[CommandOutput.warnings]]) — [[Cli.emit]] is the only place that ever calls this, so
    * ordering is identical in both modes.
    */
  def warning(env: Env, detail: String): Unit

/** The presentation selected for one stream, computed once per invocation by
  * [[Presentation.select]] (R93–R95) and reused for every result/warning/error [[Cli.emit]] prints
  * on that stream.
  */
final case class Presenters(stdout: Presentation, stderr: Presentation)

object Presentation:

  /** The byte-stable presentation (spec §7.11): every method ignores `kind`/env TTY/color entirely
    * and prints exactly the given text, unconditionally correct as the "no valid presentation was
    * selected" fallback (R95) and as one of the two streams whenever `SNAP_COLOR=never`/`auto`
    * resolves a stream to non-terminal.
    */
  object Plain extends Presentation:
    def result(env: Env, kind: ResultKind, text: String): Unit =
      if text.nonEmpty then env.stdout.print(text)

    def error(env: Env, detail: String): Unit =
      // Literal LF, not `println` (which appends the platform `line.separator`) — spec §10 requires
      // LF line endings regardless of host/JVM (PR2/CR6).
      env.stderr.print(s"snap: $detail\n")

    def warning(env: Env, detail: String): Unit =
      // Same literal-LF rule as `error`; tests 10/11/17 byte-pin whole warning lines.
      env.stderr.print(s"warning: $detail\n")

  /** The terminal presentation (SPEC §7.11): ANSI SGR sequences per `S(n, text)` = `ESC[` + code +
    * `m` + text + `ESC[0m`. Every render function is a pure string transform of already-decided
    * plain data (`text`/`kind`) — the sole effect is the final `print` call, mirroring [[Plain]].
    */
  object Terminal extends Presentation:
    private val Bold = 1
    private val Dim = 2
    private val Red = 31
    private val Green = 32
    private val Yellow = 33
    private val Magenta = 35
    private val Cyan = 36

    private def sgr(code: Int, text: String): String = s"[${code}m${text}[0m"

    def result(env: Env, kind: ResultKind, text: String): Unit =
      // R97: empty plain output remains empty in terminal mode too — no stray SGR/reset bytes.
      if text.nonEmpty then env.stdout.print(render(kind, text))

    def error(env: Env, detail: String): Unit =
      // S(31, "✗ " + <error>) — ONE wrap around the glyph and the whole plain error line, unlike
      // `warning`'s two independently-wrapped halves (SPEC §7.11).
      env.stderr.print(sgr(Red, s"✗ snap: $detail") + "\n")

    def warning(env: Env, detail: String): Unit =
      env.stderr.print(s"${sgr(Yellow, "⚠")} ${sgr(Yellow, detail)}\n")

    private def render(kind: ResultKind, text: String): String = kind match
      case ResultKind.Success(label)      => renderSuccess(label, text)
      case ResultKind.Status(version, ds) => renderStatus(version, ds)
      case ResultKind.LogEntries(entries) => renderLog(entries)
      case ResultKind.Diff                => renderDiff(text)
      case ResultKind.VersionLine         => renderVersion(text)
      case ResultKind.Raw                 => text

    /** `S(32,"✓") + " " + S(1,label) + " " + S(36,version) + LF`; `text` is always `"(...)\n"`. */
    private def renderSuccess(label: String, text: String): String =
      val version = text.stripSuffix("\n")
      s"${sgr(Green, "✓")} ${sgr(Bold, label)} ${sgr(Cyan, version)}\n"

    /** `S(1,"Snap status") + "  " + S(36,version) + LF + LF`, then either the clean line or one
      * colored row per delta, in the SAME path order `deltas` already carries (R83).
      */
    private def renderStatus(version: String, deltas: Vector[Delta]): String =
      val header = s"${sgr(Bold, "Snap status")}  ${sgr(Cyan, version)}\n\n"
      val body =
        if deltas.isEmpty then s"  ${sgr(Green, "✓")} Working tree clean\n"
        else deltas.iterator.map(renderStatusRow).mkString
      header + body

    /** `(color,symbol,label)` = `(32,"+","added")` / `(33,"~","modified")` / `(31,"−","deleted")` —
      * the same three-way split [[CommandsStatus.render]] uses for A/M/D, total over `Delta`'s own
      * invariant (never both sides absent).
      */
    private def renderStatusRow(delta: Delta): String =
      val (color, symbol, label) = (delta.before, delta.after) match
        case (None, Some(_))    => (Green, "+", "added")
        case (Some(_), Some(_)) => (Yellow, "~", "modified")
        case _                  => (Red, "−", "deleted") // U+2212 MINUS SIGN, not '-' (gotcha 8)
      s"  ${sgr(color, symbol)} ${delta.path.value} ${sgr(Dim, s"($label)")}\n"

    /** `S(36,"●") + " " + S(1,message) + LF + " " + S(36,version) + " " + S(2,"by") + " " +
      * S(35,author) + LF`, entries separated by one additional blank LF (gotcha 8) — never after
      * the last entry, which `mkString("\n")` gives for free since every entry already ends with
      * its own LF.
      */
    private def renderLog(entries: Vector[LogLine]): String =
      entries
        .map { entry =>
          s"${sgr(Cyan, "●")} ${sgr(Bold, entry.message)}\n" +
            s"  ${sgr(Cyan, entry.version)} ${sgr(Dim, "by")} ${sgr(Magenta, entry.author)}\n"
        }
        .mkString("\n")

    /** §7.11: "diff preserves every plain byte except that the complete text of each matching line,
      * excluding LF, is wrapped by the first applicable style" — a literal transform of the plain
      * bytes, checked in the spec's own order so `--- `/`+++ ` (bold) are never mistaken for a lone
      * leading `-` (red), and `\ ` (dim) is never mistaken for a lone leading nothing.
      */
    private def renderDiff(text: String): String =
      // `text` always ends with LF when nonempty (every DiffRender line does), so splitting on "\n"
      // leaves one trailing empty element from that final separator — dropped, not a real line.
      text.split("\n", -1).toVector.dropRight(1).map(renderDiffLine).mkString

    private def renderDiffLine(line: String): String =
      if line.startsWith("--- ") || line.startsWith("+++ ") then sgr(Bold, line) + "\n"
      else if line.startsWith("@@ ") then sgr(Cyan, line) + "\n"
      else if line.startsWith("-") then sgr(Red, line) + "\n"
      else if line.startsWith("+") then sgr(Green, line) + "\n"
      else if line.startsWith("\\ ") then sgr(Dim, line) + "\n"
      else if line.startsWith("Binary files ") then sgr(Yellow, line) + "\n"
      else line + "\n" // context lines (leading space) and anything else: unchanged (§7.11)

    /** `S(1,"snap <semver>") + LF`; `text` is always `"snap 1.0.0\n"` (D6). */
    private def renderVersion(text: String): String =
      sgr(Bold, text.stripSuffix("\n")) + "\n"

  /** R93–R95: the per-stream presentation pair for one invocation, computed exactly once by
    * [[Cli.run]] AFTER `SNAP_COLOR` has already been validated (`unset`/`auto`/`always`/`never` —
    * the `Some(_)` arm below is unreachable through that gate, kept total and defensively `Plain`
    * rather than forced). `always`/`never` never touch `env.tty` at all; `auto`/unset probe it only
    * when `NO_COLOR` is absent — the negative constraint R108 pins: no probe subprocess when
    * `NO_COLOR` is set or the mode isn't `auto`.
    */
  def select(env: Env): Presenters =
    env.env.get("SNAP_COLOR") match
      case Some("always")      => Presenters(Terminal, Terminal)
      case Some("never")       => Presenters(Plain, Plain)
      case None | Some("auto") =>
        if env.env.contains("NO_COLOR") then Presenters(Plain, Plain) // R94: presence, even empty
        else
          Presenters(
            if env.tty.isStdoutTty then Terminal else Plain,
            if env.tty.isStderrTty then Terminal else Plain
          )
      case Some(_) => Presenters(Plain, Plain)

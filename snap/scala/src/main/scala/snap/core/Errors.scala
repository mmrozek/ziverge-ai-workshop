package snap.core

/** Position of a JSON syntax failure (1-based line and column, as reported by the tokenizer).
  * Carried as data so [[SnapError.message]] stays the single place that renders diagnostics.
  */
final case class JsonLocation(line: Int, col: Int)

/** Why a raw string is not a valid contributor id (SPEC §3.1, R28). Pure data; rendering lives in
  * [[Messages.contributorId]] (DESIGN D5).
  */
enum IdError:
  /** Longer than [[ContributorId.MaxBytes]] UTF-8 bytes. */
  case TooLong

  /** Contains a character outside printable ASCII 0x21–0x7E, or `,`, `(`, `)` (D12 includes DEL).
    */
  case ForbiddenCharacter

  /** Does not contain exactly one `@`. */
  case AtCount

  /** `@` is the first or last character. */
  case EmptyAtSide

  /** Contains the substring `->`. */
  case ArrowSubstring

/** Why a version literal or pair array is not canonical (SPEC §3.2, R31/R32). Pure data; rendering
  * lives in [[Messages.versionValue]] (DESIGN D5). Out-of-bounds revisions surface as
  * [[SnapError.RevisionNotSafeInteger]] and bad ids as [[SnapError.InvalidContributorId]] instead.
  */
enum VersionError:
  /** Not of the form `()` or `(id->n,...)`. */
  case Shape

  /** An entry without the `->` separator. */
  case MissingArrow

  /** An entry whose revision text is empty. */
  case EmptyRevision

  /** A revision text containing a non-digit (sign, whitespace, garbage). */
  case NonDecimalRevision

  /** An explicit `->0` entry (absent = 0 is never written — R9/R30). */
  case ExplicitZeroRevision

  /** A revision text with a leading zero. */
  case LeadingZeroRevision

  /** The same contributor id twice. */
  case DuplicateId(id: String)

  /** Ids not strictly ascending in `Utf8Order`. */
  case NonCanonicalOrder

/** The single error channel of the implementation (DESIGN D4): domain code returns
  * `Either[SnapError, A]`, never throws. Cases carry structured data; the rendered text lives in
  * [[Messages]] (DESIGN D5) and is produced only by [[SnapError.message]].
  *
  * Seeded in T02 with the strict-JSON cases; later tasks append cases for their own diagnostics.
  * T06 appended the repository decode/validation cases and folded in T03's id/version reasons and
  * T05's [[EditError]] (both previously task-local `Either[String, A]` / inline-message seams).
  */
enum SnapError:
  /** Malformed JSON of any kind (tokenizer rejection, trailing garbage, truncated input). Maps to
    * the `invalid JSON` diagnostic class — tests pin `invalid JSON` as a substring (R41; tests
    * 03/13). `location` is absent when the input ended prematurely (no meaningful position exists).
    */
  case InvalidJson(location: Option[JsonLocation])

  /** A JSON object with a repeated key (R41; tests 15/25 pin the shape `duplicate JSON key <k>`).
    */
  case DuplicateJsonKey(key: String)

  /** No known command matched the first token, an operand didn't fit the command's shape, or a
    * grammar rule (R79) was violated outside `diff`'s distinct usage channel (DESIGN §8; test 14/24
    * pin `invalid command or arguments` verbatim). T08 seeds this with only the coarse checks it
    * can make (unknown command, `--version` arity); the exhaustive per-command matrix is T13's.
    */
  case InvalidCommand

  /** A command that resolves against the nearest repository (R77) found none walking from the
    * process cwd to the filesystem root (test 14 pins this verbatim).
    */
  case NotASnapRepository

  /** `SNAP_COLOR` set to something other than unset/`auto`/`always`/`never` (R95; test 28 pins this
    * verbatim). The offending value is never echoed — the spec's wording is fixed regardless of
    * what was set.
    */
  case InvalidSnapColor

  /** A recognized command with no implementation yet (T08 stub, replaced command-by-command through
    * T09–T21). Carries no detail: the wording matches T01's placeholder `Main.scala` verbatim.
    */
  case NotImplemented

  // --- T06: id / version values (migrated T03 seams; R28–R32) ---

  /** A string that is not a valid contributor id (R28). */
  case InvalidContributorId(reason: IdError)

  /** A revision (or edit count carried through a version) outside `1 .. 2^53−1` (R30). Test 23
    * anchors `positive safe integer` at the end of the line.
    */
  case RevisionNotSafeInteger

  /** A version literal or `[id, revision]` pair array that is not canonical (R31/R32). Test 23's
    * noncanonical-frontier case matches `canonical` anywhere in the line.
    */
  case InvalidVersionValue(reason: VersionError)

  // --- T06: repository schema decode (SPEC §4.1–§4.3, R39–R52) ---

  /** The repository document is not a JSON object. */
  case RepositoryNotObject

  /** A field outside the exact schema (R43). `owner` is `repository` / `patch` / `change`; test 23
    * pins the top level exactly (`repository has unknown field: unknown`), test 23's change case
    * anchors `unknown field: extra` at the end of the line, test 27 covers the patch level.
    */
  case UnknownField(owner: String, field: String)

  /** A required schema field is absent (R40/R43; untested wording). */
  case MissingField(owner: String, field: String)

  /** A present field whose JSON type does not fit the schema (R43; untested wording). */
  case FieldWrongType(owner: String, field: String)

  /** `format` is not the integer `1` (R40). */
  case RepositoryFormatInvalid

  /** `frontier` or a patch `base` is not an array of `[id, revision]` pairs (R32). */
  case VersionNotPairs(field: String)

  /** A `patches` element that is not a JSON object. */
  case PatchNotObject

  /** A `changes` element that is not a JSON object. */
  case ChangeNotObject

  /** A change `type` other than `text` / `put` / `delete` (R50). */
  case ChangeTypeInvalid

  /** A change path rejected by [[SnapPath.parse]] (R23; test 15 pins the substring
    * `path is invalid`). The reason is carried for structure, never rendered — the pinned fragment
    * stays at the end of the line.
    */
  case ChangePathInvalid(reason: PathError)

  /** A `put` content string that is not canonical standard padded RFC 4648 base64 (R50; test 15
    * pins the substring `canonical base64`). Canonical = decode-then-re-encode reproduces the input
    * exactly.
    */
  case ContentNotCanonicalBase64

  /** An edit-script element that is not a JSON object (R54). */
  case EditOpNotObject

  /** A one-key edit operation whose key is not `retain` / `delete` / `insert` (R54). */
  case EditOpUnknown

  /** A structurally invalid edit script or operation (R54–R57; wraps T05's typed reasons — the
    * pinned fragments sit at the end of each rendered message).
    */
  case InvalidEdit(error: EditError)

  /** An empty patch `message` (R48; test 23 anchors `message is empty` at the end of the line with
    * at least one character before it — the rendered message carries the `patch ` prefix).
    */
  case PatchMessageEmpty

  /** A patch `message` containing an ASCII control character other than tab or LF, including DEL
    * (R48, D12; untested wording).
    */
  case PatchMessageForbiddenCharacter

  /** A patch `message` with no UTF-8 encoding (unpaired surrogate — R48; untested wording). */
  case PatchMessageNotUtf8

  /** An empty `changes` array (R49; test 23 anchors `changes is empty` at the end of the line). */
  case ChangesEmpty

  /** `changes` not sorted by path in `Utf8Order` (R49; test 27 pins only the `snap: ` shape). */
  case ChangesNotSorted

  /** More than one change for a single path (R49; untested wording). */
  case ChangesDuplicatePath

  // --- T06: structural validation (SPEC §4.5 steps 1–4, R44/R46/R59–R60) ---

  /** The patch array is not sorted by author (`Utf8Order`) then numeric revision (R44). */
  case PatchesNotSorted

  /** The same dot listed twice with structurally equal values — the closure lists each patch once
    * (R44 "exactly"; untested wording, decision recorded in the T06 task notes).
    */
  case DuplicatePatch(dot: Dot)

  /** The same dot with structurally different values — corruption (R47, §3.5; test 16 pins
    * `patch collision: a@x revision 1` for the cross-repo case, reused verbatim here).
    */
  case PatchCollision(dot: Dot)

  /** `revision != base[author] + 1` (R46; test 27 pins only the `snap: ` shape). */
  case DotMismatch(dot: Dot)

  /** A dot required by a revision gap, a base entry, or the frontier has no patch (R44/R45; test 15
    * pins the substring `missing a@x`).
    */
  case MissingPatch(dot: Dot)

  /** A patch outside the causal closure of the frontier (R44; test 23 pins the exact prefix
    * `unreachable patch: `).
    */
  case UnreachablePatch(dot: Dot)

  /** No ready patch remains before the history is complete (R60; test 15 pins the whole phrase). */
  case CyclicHistory

  // --- T06: store (filesystem boundary) ---

  /** `repository.json` bytes are not valid UTF-8 text — rendered in the `invalid JSON` diagnostic
    * class (a valid repository document is always UTF-8; untested wording).
    */
  case RepositoryNotUtf8

  /** Filesystem failure while reading `repository.json` (untested wording). */
  case CannotReadRepository(detail: String)

  /** Filesystem failure while writing `repository.json` (untested wording). */
  case CannotWriteRepository(detail: String)

  // --- T09 additions: init + config (SPEC §7.1, §7.2, §8; R80–R82, R98–R100) ---

  /** `snap init`'s target already has a `.snap` directory (SPEC §7.1: "Reinitializing a repository
    * is an error"; test 02 pins the substring `repository already exists`). Carries the resolved
    * target path for debugging.
    */
  case RepositoryAlreadyExists(path: String)

  /** `snap init`'s target sits inside an already-initialized repository, discovered by walking up
    * from the target (SPEC §7.1: "Initializing a target inside an existing repository is an error";
    * test 02 pins the substring `cannot initialize inside repository`). Carries the ancestor
    * repository's root for debugging.
    */
  case CannotInitializeInsideRepository(existingRoot: String)

  /** Filesystem failure creating `init`'s target directory or its `.snap` subdirectory (untested
    * wording).
    */
  case CannotCreateDirectory(detail: String)

  /** The configuration document is not a JSON object (SPEC §8; untested wording — `RepoCodec`'s
    * `RepositoryNotObject` is the analogous case for `repository.json`).
    */
  case ConfigNotObject

  /** `config.json`/`.snapconfig.json` bytes are not valid UTF-8 text — rendered in the `invalid
    * JSON` diagnostic class, mirroring [[RepositoryNotUtf8]] (untested wording).
    */
  case ConfigNotUtf8

  /** Filesystem failure while reading a configuration file (untested wording). */
  case CannotReadConfig(detail: String)

  /** Filesystem failure while writing a configuration file (untested wording). */
  case CannotWriteConfig(detail: String)

  /** Neither local `.snap/config.json` nor `$HOME/.snapconfig.json` provides a contributor id, and
    * the command (`commit`/`revert`) requires one (SPEC §8, R100; test 19 pins this exact line).
    */
  case ContributorIdRequired

  /** `snap config --global` was invoked but `HOME` is absent from the environment, so there is no
    * path to write the global configuration file to (SPEC §8: "`--global`... needs no repository" —
    * silent about a missing `HOME` for a *write*, unlike the read side's R99 "unavailable, not an
    * error"; a write has nowhere to go, so it must fail — untested wording, decision recorded in
    * the T09 task notes).
    */
  case GlobalConfigUnavailable

  // --- T07 additions: replay + validation steps 5–6 (R25, R45, R51–R52, R60) ---

  /** A syntactically valid version that is not known/materializable in the repository (R45): a
    * selected patch does not exist, or a selected patch's base is not contained in the selection.
    * Test 19 pins the full line `snap: unknown version: (a@x->2)`; test 14 the substring `unknown
    * version`. Rendered here; the CLI (T11/T12) only prepends `snap: `.
    */
  case UnknownVersion(version: Version)

  /** A delete change whose path is absent in the patch's exact base tree (R51; test 23 pins the
    * full line `snap: delete of absent path: f`).
    */
  case DeleteOfAbsentPath(path: SnapPath)

  /** A change that alters neither path existence nor bytes (R52; test 15 pins the substring `no-op
    * change` — kept at the end of the rendered line). The empty-text-edit-creates-empty-file
    * exception never reaches this case: creation always alters existence.
    */
  case NoOpChange(path: SnapPath)

  /** A patch's authored result tree is not prefix-free (R25; test 15 pins the substring `tree paths
    * conflict`). Carries the first path (in `Utf8Order`) that has a proper segment-prefix ancestor
    * present in the authored result.
    */
  case TreePathsConflict(path: SnapPath)

  /** A text edit over a present base path whose bytes are not text — invalid UTF-8 or a NUL byte,
    * R53 — so there is no old token sequence to edit (test 27's shape-only `text over binary` case;
    * untested wording).
    */
  case TextEditOverNonText(path: SnapPath)

  // T07's `ConcurrentHistoryUnsupported` staging case was removed by T16: the concurrent
  // integration engine (SPEC §6.2) replaced `Replay.LinearOnly`, closing the gap it flagged.

  // --- T15 additions: OT transform (SPEC §6.3, R71) ---

  /** The two scripts handed to [[Ot.transform]] consume different base token counts (R71: "Both
    * scripts consume the same base token count", "No unmatched retain or delete can remain"). An
    * internal invariant for replay — both scripts derive from the same base tree — surfaced as a
    * typed error rather than a defensive throw (D4; untested wording).
    */
  case OtBaseMismatch
  // --- T10 additions: working-tree scan + status/commit (SPEC §2, §7.3/§7.5, §10; R16–R21,
  // R26–R27, R83, R85, R104) ---

  /** A symlink, FIFO, or other non-regular entry anywhere in the working-tree walk (R21, R104; test
    * 08 pins `unsupported working tree entry: <path>` exactly, path relative with `/` separators).
    * Carried as raw text — an unsupported entry's name need not be a valid tracked path.
    */
  case UnsupportedWorkTreeEntry(path: String)

  /** A regular working-tree file whose root-relative path fails [[SnapPath.parse]] — e.g. a
    * backslash or ASCII control character in a file name (R23 applied to the scan; untested
    * wording). Carried as raw text for the same reason as [[UnsupportedWorkTreeEntry]].
    */
  case InvalidWorkTreePath(path: String)

  /** Filesystem failure while listing or reading the working tree (untested wording). */
  case CannotReadWorkTree(detail: String)

  /** `snap commit` on a clean working tree — exact path/byte equality with the current tree, no
    * unsupported entries (R26, R85; test 04 pins `working tree is clean` verbatim).
    */
  case WorkingTreeClean

  /** A `snap commit` message that is empty, violates R48's character rules, or exceeds D16's 4096
    * UTF-8 bytes (R85; test 25 pins `invalid commit message` verbatim for the empty case — one
    * wording for the whole commit-input rule class, unlike the repository-validation
    * `PatchMessage*` cases above).
    */
  case InvalidCommitMessage

  // --- T11 additions: `diff` command (SPEC §7.6; R31, R79, R86–R87) ---

  /** A `diff <old> <new>` operand that fails [[Version.parse]] (R31). Rendered by echoing the raw
    * operand text rather than the specific [[VersionError]]/id/revision reason — mirroring D9's
    * `invalid port: <arg>` — because tests 19/25 pin only the class (`invalid version`
    * substring/prefix), never a particular reason (T11 Notes / decisions).
    */
  case InvalidVersionArgument(raw: String)

  /** `diff`'s own arity/option grammar violation — the distinct usage channel DESIGN §8 carves out
    * (tests 14/24 pin the `usage: snap diff` prefix/substring, never the generic
    * [[InvalidCommand]]).
    */
  case DiffUsage
  // --- T12 additions: filesystem install & revert (SPEC §7.7, §10; R88, R103–R106) ---

  /** `merge`/`revert` refuse to replace a dirty working tree (SPEC §2/§10, R27; test 07 pins the
    * full line `snap: working tree is dirty`). Checked with the same [[snap.cli.WorkingChanges]]
    * comparison `status`/`commit` use, before any filesystem mutation (R103).
    */
  case WorkingTreeDirty

  /** `snap revert`'s target-tree comparison found no difference from the current tree (SPEC §7.7;
    * test 07 pins the full line `snap: target tree is already current`). No patch is authored and
    * nothing is installed.
    */
  case TargetTreeAlreadyCurrent

  /** Filesystem failure while installing a target tree over the working directory (SPEC §10,
    * R105–R106; [[snap.fs.Materialize]]) — untested wording, mirrors [[CannotReadWorkTree]]/
    * [[CannotWriteRepository]]'s boundary pattern. A failure here may leave a partially updated,
    * dirty working tree with the OLD `repository.json` still intact (R106) — the metadata write
    * never runs until installation returns `Right`.
    */
  case CannotUpdateWorkingTree(detail: String)

  // --- T13 additions: `--serve` port validation (SPEC §7.9, D9) ---

  /** `--serve`'s port operand is not a canonical decimal integer in `0..65535` (D9; test 14 pins
    * the exact line `snap: invalid port: 65536`). `raw` is untrusted CLI argv text read before
    * validation — sanitized (PR1/CR3) since it may legally contain control characters.
    */
  case InvalidPort(raw: String)

  /** One-line diagnostic detail, without the `snap: ` prefix — the CLI layer (T08) prepends the
    * prefix when printing (spec §10 `snap: <detail>`).
    */
  def message: String = this match
    case InvalidJson(Some(location))    => Messages.invalidJsonAt(location)
    case InvalidJson(None)              => Messages.invalidJsonTruncated
    case DuplicateJsonKey(key)          => Messages.duplicateJsonKey(key)
    case InvalidCommand                 => Messages.invalidCommand
    case NotASnapRepository             => Messages.notASnapRepository
    case InvalidSnapColor               => Messages.invalidSnapColor
    case NotImplemented                 => Messages.notImplemented
    case InvalidContributorId(reason)   => Messages.contributorId(reason)
    case RevisionNotSafeInteger         => Messages.revisionNotSafeInteger
    case InvalidVersionValue(reason)    => Messages.versionValue(reason)
    case RepositoryNotObject            => Messages.repositoryNotObject
    case UnknownField(owner, field)     => Messages.unknownField(owner, field)
    case MissingField(owner, field)     => Messages.missingField(owner, field)
    case FieldWrongType(owner, field)   => Messages.fieldWrongType(owner, field)
    case RepositoryFormatInvalid        => Messages.repositoryFormatInvalid
    case VersionNotPairs(field)         => Messages.versionNotPairs(field)
    case PatchNotObject                 => Messages.patchNotObject
    case ChangeNotObject                => Messages.changeNotObject
    case ChangeTypeInvalid              => Messages.changeTypeInvalid
    case ChangePathInvalid(_)           => Messages.changePathInvalid
    case ContentNotCanonicalBase64      => Messages.contentNotCanonicalBase64
    case EditOpNotObject                => Messages.editOpNotObject
    case EditOpUnknown                  => Messages.editOpUnknown
    case InvalidEdit(error)             => Messages.editError(error)
    case PatchMessageEmpty              => Messages.patchMessageEmpty
    case PatchMessageForbiddenCharacter => Messages.patchMessageForbiddenCharacter
    case PatchMessageNotUtf8            => Messages.patchMessageNotUtf8
    case ChangesEmpty                   => Messages.changesEmpty
    case ChangesNotSorted               => Messages.changesNotSorted
    case ChangesDuplicatePath           => Messages.changesDuplicatePath
    case PatchesNotSorted               => Messages.patchesNotSorted
    case DuplicatePatch(dot)            => Messages.duplicatePatch(dot)
    case PatchCollision(dot)            => Messages.patchCollision(dot)
    case DotMismatch(dot)               => Messages.dotMismatch(dot)
    case MissingPatch(dot)              => Messages.missingPatch(dot)
    case UnreachablePatch(dot)          => Messages.unreachablePatch(dot)
    case CyclicHistory                  => Messages.cyclicHistory
    case RepositoryNotUtf8              => Messages.repositoryNotUtf8
    case CannotReadRepository(detail)   => Messages.cannotReadRepository(detail)
    case CannotWriteRepository(detail)  => Messages.cannotWriteRepository(detail)
    // --- T09 additions ---
    case RepositoryAlreadyExists(path)          => Messages.repositoryAlreadyExists(path)
    case CannotInitializeInsideRepository(root) => Messages.cannotInitializeInsideRepository(root)
    case CannotCreateDirectory(detail)          => Messages.cannotCreateDirectory(detail)
    case ConfigNotObject                        => Messages.configNotObject
    case ConfigNotUtf8                          => Messages.configNotUtf8
    case CannotReadConfig(detail)               => Messages.cannotReadConfig(detail)
    case CannotWriteConfig(detail)              => Messages.cannotWriteConfig(detail)
    case ContributorIdRequired                  => Messages.contributorIdRequired
    case GlobalConfigUnavailable                => Messages.globalConfigUnavailable
    // --- T07 additions ---
    case UnknownVersion(version)   => Messages.unknownVersion(version)
    case DeleteOfAbsentPath(path)  => Messages.deleteOfAbsentPath(path)
    case NoOpChange(path)          => Messages.noOpChange(path)
    case TreePathsConflict(path)   => Messages.treePathsConflict(path)
    case TextEditOverNonText(path) => Messages.textEditOverNonText(path)
    // --- T15 additions ---
    case OtBaseMismatch => Messages.otBaseMismatch
    // --- T10 additions ---
    case UnsupportedWorkTreeEntry(path) => Messages.unsupportedWorkTreeEntry(path)
    case InvalidWorkTreePath(path)      => Messages.invalidWorkTreePath(path)
    case CannotReadWorkTree(detail)     => Messages.cannotReadWorkTree(detail)
    case WorkingTreeClean               => Messages.workingTreeClean
    case InvalidCommitMessage           => Messages.invalidCommitMessage
    // --- T11 additions ---
    case InvalidVersionArgument(raw) => Messages.invalidVersionArgument(raw)
    case DiffUsage                   => Messages.diffUsage
    // --- T12 additions ---
    case WorkingTreeDirty                => Messages.workingTreeDirty
    case TargetTreeAlreadyCurrent        => Messages.targetTreeAlreadyCurrent
    case CannotUpdateWorkingTree(detail) => Messages.cannotUpdateWorkingTree(detail)
    // --- T13 additions ---
    case InvalidPort(raw) => Messages.invalidPort(raw)

/** Message catalog (DESIGN D5): every diagnostic string of the implementation lives here,
  * test-pinned ones verbatim. No other module builds diagnostic text. Where a provided test anchors
  * a fragment at the end of the line (test 23's regexes), the fragment sits at the exact end of the
  * rendered message and nothing is ever appended after it.
  */
object Messages:
  /** Escapes ASCII control characters — including LF/CR/TAB — in untrusted, interpolated text so a
    * rendered diagnostic never spans more than one physical line (R107: "In plain mode, errors are
    * one line `snap: <detail>`"). Applied only at call sites where the interpolated value is not
    * yet validated and so may legally contain such characters (JSON object keys/field names read
    * before schema validation, raw working-tree entry names) — never to this catalog's own pinned
    * text, which never contains a control character. `\n`/`\r`/`\t` use their short mnemonic; every
    * other ASCII control character (0x00–0x1F, and DEL 0x7F per D12) renders as `\u00xx`, mirroring
    * [[snap.json.Writer.quote]]'s locale-independent hex. Identity on any string without a control
    * character, so it never changes an already-pinned assertion (tests 02/03/08/15/23/25
    * interpolate benign names).
    */
  private def sanitizeControlChars(text: String): String =
    text.flatMap {
      case '\n'                          => "\\n"
      case '\r'                          => "\\r"
      case '\t'                          => "\\t"
      case c if c < ' ' || c == '\u007f' =>
        val hex = Integer.toHexString(c.toInt)
        if hex.length == 1 then "\\u000" + hex else "\\u00" + hex
      case c => c.toString
    }

  /** Pinned shape `duplicate JSON key <k>` (test 25 matches `^snap: duplicate JSON key .+\n$`, test
    * 15 the substring). `key` is untrusted (read before validation) — sanitized (PR1/CR3).
    */
  def duplicateJsonKey(key: String): String =
    s"duplicate JSON key ${sanitizeControlChars(key)}"

  /** Only the substring `invalid JSON` is pinned (tests 03/13); position is a courtesy detail, kept
    * single-line and free of input echoes.
    */
  def invalidJsonAt(location: JsonLocation): String =
    s"invalid JSON at line ${location.line} column ${location.col}"

  /** Truncated input has no failure position. */
  val invalidJsonTruncated: String = "invalid JSON: unexpected end of input"

  /** Pinned verbatim (tests 14, 24): unknown command, extra operands, or any other grammar
    * violation outside `diff`'s distinct usage channel (DESIGN §8).
    */
  val invalidCommand: String = "invalid command or arguments"

  /** Pinned verbatim (test 14, R77). */
  val notASnapRepository: String = "not a Snap repository"

  /** Pinned verbatim (test 28, R95). */
  val invalidSnapColor: String = "SNAP_COLOR must be auto, always, or never"

  /** T08 stub text for every not-yet-implemented command; matches T01's placeholder `Main.scala`
    * verbatim. Replaced command-by-command starting T09.
    */
  val notImplemented: String = "not implemented"

  /** Exit-2 catch-all (R107, D4): built only by `Main`'s top-level exception handler from an
    * unexpected `Throwable`'s message — domain code never produces this.
    */
  def internalError(detail: String): String = s"internal error: $detail"

  // --- T06: id / version values (R28–R32; untested wording except where noted) ---

  /** T09 note: wrapped with the `invalid contributor id: ` prefix (tests 03/25 — test 25 pins the
    * exact pattern `^snap: invalid contributor id: .+$`) regardless of call site (repository author
    * decode, `snap config`, or a configuration-file read) — one rendering for [[IdError]]
    * everywhere (D5's "one message catalog").
    */
  def contributorId(reason: IdError): String =
    s"invalid contributor id: ${contributorIdReason(reason)}"

  private def contributorIdReason(reason: IdError): String = reason match
    case IdError.TooLong            => s"contributor id exceeds ${ContributorId.MaxBytes} bytes"
    case IdError.ForbiddenCharacter => "contributor id contains a forbidden character"
    case IdError.AtCount            => "contributor id must contain exactly one '@'"
    case IdError.EmptyAtSide        => "contributor id must have nonempty text on both sides of '@'"
    case IdError.ArrowSubstring     => "contributor id must not contain '->'"

  /** Pinned fragment `positive safe integer` at the end of the line (test 23, R30). */
  val revisionNotSafeInteger: String = "revision must be a positive safe integer"

  def versionValue(reason: VersionError): String = reason match
    case VersionError.Shape                => "version must be of the form () or (id->n,...)"
    case VersionError.MissingArrow         => "version entry is missing '->'"
    case VersionError.EmptyRevision        => "revision is empty"
    case VersionError.NonDecimalRevision   => "revision must be a decimal integer"
    case VersionError.ExplicitZeroRevision => "explicit zero revision"
    case VersionError.LeadingZeroRevision  => "leading zero in revision"
    case VersionError.DuplicateId(id)      => s"duplicate contributor id: $id"
    // Pinned fragment `canonical` anywhere in the line (test 23, R32).
    case VersionError.NonCanonicalOrder => "contributor ids are not in canonical order"

  // --- T06: repository schema decode (R39–R52) ---

  val repositoryNotObject: String = "repository is not a JSON object"

  /** Shape `<owner> has unknown field: <f>` — pinned exactly at the top level (test 23:
    * `repository has unknown field: unknown`); the change level anchors `unknown field: <f>` at the
    * end of the line (test 23), the patch level is shape-only (test 27). `owner` is always one of
    * this catalog's own fixed strings (`repository`/`patch`/`change`); `field` is an untrusted JSON
    * key read before schema validation, so only it is sanitized (PR1/CR3).
    */
  def unknownField(owner: String, field: String): String =
    s"$owner has unknown field: ${sanitizeControlChars(field)}"

  def missingField(owner: String, field: String): String =
    s"$owner is missing field: ${sanitizeControlChars(field)}"

  def fieldWrongType(owner: String, field: String): String =
    s"$owner field ${sanitizeControlChars(field)} has the wrong type"

  val repositoryFormatInvalid: String = "repository format must be 1"

  def versionNotPairs(field: String): String =
    s"$field must be an array of [id, revision] pairs"

  val patchNotObject: String = "patch is not a JSON object"

  val changeNotObject: String = "change is not a JSON object"

  val changeTypeInvalid: String = "change type must be text, put, or delete"

  /** Pinned fragment `path is invalid` (test 15), kept at the end of the line. */
  val changePathInvalid: String = "change path is invalid"

  /** Pinned fragment `canonical base64` (test 15), kept at the end of the line. */
  val contentNotCanonicalBase64: String = "change content is not canonical base64"

  val editOpNotObject: String = "edit operation is not a JSON object"

  val editOpUnknown: String = "edit operation must be retain, delete, or insert"

  /** Migrated verbatim from T05's inline `EditError` messages (D5). Each pinned fragment (tests
    * 15/23: `does not consume old content`, `consumes beyond old content`, `adjacent insert`,
    * `insert is empty`, `positive safe integer`, `must have one operation`) sits at the exact end
    * of its message.
    */
  def editError(error: EditError): String = error match
    case EditError.Underconsumption   => "edit does not consume old content"
    case EditError.Overconsumption    => "edit consumes beyond old content"
    case EditError.Adjacent(kind)     => s"edit has adjacent $kind operations"
    case EditError.EmptyInsert        => "edit insert is empty"
    case EditError.BadInsertToken     => "edit insert token is not a text token"
    case EditError.BadCount           => "edit count is not a positive safe integer"
    case EditError.NotOneOperation    => "edit operation must have one operation"
    case EditError.NonCanonicalResult => "edit result is not a canonical token sequence"

  /** Pinned fragment `message is empty` at the end of the line; test 23's regex requires at least
    * one character between `snap: ` and the fragment — the `patch ` prefix provides it.
    */
  val patchMessageEmpty: String = "patch message is empty"

  val patchMessageForbiddenCharacter: String =
    "patch message contains a forbidden control character"

  val patchMessageNotUtf8: String = "patch message is not valid UTF-8"

  /** Pinned fragment `changes is empty` at the end of the line (test 23); prefixed like
    * [[patchMessageEmpty]].
    */
  val changesEmpty: String = "patch changes is empty"

  val changesNotSorted: String = "patch changes are not sorted by path"

  val changesDuplicatePath: String = "patch has more than one change for a path"

  // --- T06: structural validation (§4.5 steps 1–4) ---

  val patchesNotSorted: String = "patches are not sorted by author and revision"

  def duplicatePatch(dot: Dot): String = s"duplicate patch ${dot.text}"

  /** Pinned shape `patch collision: a@x revision 1` (test 16, cross-repo dot collision — reused
    * verbatim for a local same-dot corruption, §3.5).
    */
  def patchCollision(dot: Dot): String = s"patch collision: ${dot.text}"

  def dotMismatch(dot: Dot): String = s"patch ${dot.text} does not increment its base"

  /** Pinned fragment `missing a@x` (test 15) — `dot.text` starts with the author id. */
  def missingPatch(dot: Dot): String = s"patch history is missing ${dot.text}"

  /** Pinned exact prefix `unreachable patch: ` (test 23). */
  def unreachablePatch(dot: Dot): String = s"unreachable patch: ${dot.text}"

  /** Pinned whole phrase (test 15, R60). */
  val cyclicHistory: String = "cyclic or incomplete patch history"

  // --- T06: store (filesystem boundary; untested wording) ---

  /** Kept in the `invalid JSON` diagnostic class: a valid repository document is always UTF-8. */
  val repositoryNotUtf8: String = "invalid JSON: repository file is not valid UTF-8"

  def cannotReadRepository(detail: String): String = s"cannot read repository: $detail"

  def cannotWriteRepository(detail: String): String = s"cannot write repository: $detail"

  // --- T09 additions: init + config (SPEC §7.1, §7.2, §8) ---

  /** Pinned substring `repository already exists` (test 02). */
  def repositoryAlreadyExists(path: String): String = s"repository already exists: $path"

  /** Pinned substring `cannot initialize inside repository` (test 02). */
  def cannotInitializeInsideRepository(existingRoot: String): String =
    s"cannot initialize inside repository: $existingRoot"

  def cannotCreateDirectory(detail: String): String = s"cannot create directory: $detail"

  val configNotObject: String = "config is not a JSON object"

  /** Kept in the `invalid JSON` diagnostic class, mirroring [[repositoryNotUtf8]]. */
  val configNotUtf8: String = "invalid JSON: config file is not valid UTF-8"

  def cannotReadConfig(detail: String): String = s"cannot read config: $detail"

  def cannotWriteConfig(detail: String): String = s"cannot write config: $detail"

  /** Pinned verbatim (test 19, R100). */
  val contributorIdRequired: String = "contributor.id is required; configure it locally or globally"

  /** Untested wording (T09 decision — see task notes): a `--global` write has nowhere to go without
    * `HOME`, unlike the read side where a missing `HOME` is simply "no value" (R99).
    */
  val globalConfigUnavailable: String = "global configuration is unavailable: HOME is not set"

  // --- T07 additions: replay + validation steps 5–6 ---

  /** Pinned full line `snap: unknown version: (a@x->2)` (test 19, R45); test 14 matches the
    * substring `unknown version`.
    */
  def unknownVersion(version: Version): String = s"unknown version: ${version.canonicalText}"

  /** Pinned full line `snap: delete of absent path: f` (test 23, R51). */
  def deleteOfAbsentPath(path: SnapPath): String = s"delete of absent path: ${path.value}"

  /** Pinned fragment `no-op change` (test 15, R52), kept at the exact end of the line. */
  def noOpChange(path: SnapPath): String = s"change ${path.value} is a no-op change"

  /** Pinned fragment `tree paths conflict` (test 15, R25), kept at the exact end of the line. */
  def treePathsConflict(path: SnapPath): String = s"${path.value}: tree paths conflict"

  /** Untested wording (R51/R53): a text edit needs a text base to tokenize. */
  def textEditOverNonText(path: SnapPath): String = s"text edit over non-text path: ${path.value}"

  // T07's `concurrentHistoryUnsupported` entry was removed by T16 with its enum case; T16 adds no
  // new diagnostics (OT reuses `otBaseMismatch`/`editError`, sub-replay failures reuse
  // `cyclicHistory`, and warnings are not errors — their reason tokens render on `WarningReason`).

  // --- T15 additions: OT transform (SPEC §6.3; untested wording) ---

  /** Internal replay invariant (R71): both transform inputs must derive from one base tree. */
  val otBaseMismatch: String = "edit scripts consume different base token counts"
  // --- T10 additions: working-tree scan + status/commit (SPEC §2, §7.3/§7.5, §10) ---

  /** Pinned exactly (test 08, R21/R104): the path is root-relative with `/` separators. `path` is
    * raw, unvalidated filesystem text — sanitized (PR1/CR3) since it may legally contain control
    * characters (that is precisely why the entry is unsupported/invalid in the first place).
    */
  def unsupportedWorkTreeEntry(path: String): String =
    s"unsupported working tree entry: ${sanitizeControlChars(path)}"

  /** Untested wording (R23 applied to the working-tree scan). `path` is raw, unvalidated filesystem
    * text — sanitized (PR1/CR3).
    */
  def invalidWorkTreePath(path: String): String =
    s"invalid working tree path: ${sanitizeControlChars(path)}"

  /** Untested wording (filesystem boundary, mirroring [[cannotReadRepository]]). */
  def cannotReadWorkTree(detail: String): String = s"cannot read working tree: $detail"

  /** Pinned verbatim (test 04, R85). */
  val workingTreeClean: String = "working tree is clean"

  /** Pinned verbatim (test 25, R85/D16) — one wording for every commit-message input violation. */
  val invalidCommitMessage: String = "invalid commit message"

  // --- T11 additions: `diff` command (SPEC §7.6) ---

  /** Pinned substring `invalid version` (tests 19/25) — echoes the raw offending operand rather
    * than a specific reason, mirroring D9's `invalid port: <arg>` (T11 Notes / decisions). `raw` is
    * an untrusted CLI argv operand read before [[Version.parse]] succeeds — sanitized (PR1/CR3)
    * since it may legally contain control characters.
    */
  def invalidVersionArgument(raw: String): String = s"invalid version: ${sanitizeControlChars(raw)}"

  /** `diff`'s distinct usage channel (DESIGN §8; tests 14/24 pin the `usage: snap diff` prefix).
    * The exact wording mirrors SPEC §7.6's fenced grammar block.
    */
  val diffUsage: String = "usage: snap diff <old> <new> [--repo <repository>]"
  // --- T12 additions: filesystem install & revert (SPEC §7.7, §10; R88, R103–R106) ---

  /** Pinned verbatim (test 07, R27). Shared wording class with [[workingTreeClean]] but the
    * opposite direction: `commit` requires a dirty tree, `merge`/`revert` require a clean one.
    */
  val workingTreeDirty: String = "working tree is dirty"

  /** Pinned verbatim (test 07, R88). */
  val targetTreeAlreadyCurrent: String = "target tree is already current"

  /** Untested wording (filesystem boundary, mirroring [[cannotReadWorkTree]]). */
  def cannotUpdateWorkingTree(detail: String): String = s"cannot update working tree: $detail"

  // --- T13 additions: `--serve` port validation (SPEC §7.9, D9) ---

  /** Pinned verbatim (test 14): `snap: invalid port: 65536`. Echoes the raw offending operand
    * rather than a specific reason, mirroring [[invalidVersionArgument]]'s established pattern for
    * the same class of CLI argument-value error — neither test 14 nor 24 pins more than the class.
    * `raw` is untrusted CLI argv text read before validation — sanitized (PR1/CR3).
    */
  def invalidPort(raw: String): String = s"invalid port: ${sanitizeControlChars(raw)}"

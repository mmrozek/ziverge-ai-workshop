package snap.core

/** Outcome of the causal comparison of two versions (SPEC §3.3, R33). The type preserves all four
  * outcomes — concurrency is not equivalent to before or after (R35).
  */
enum Ord:
  case Equal, Before, After, Concurrent

/** A version: a vector clock mapping contributor ids to their latest revision (SPEC §3, R4/R9).
  * Represented per DESIGN D17 as an id-sorted immutable vector of `(id, revision)` entries with no
  * zero entries (absent = 0), so iteration order is deterministic by construction and zero entries
  * are unrepresentable. The constructor is private; every factory validates.
  */
final case class Version private (entries: Vector[(ContributorId, Long)]):

  def isEmpty: Boolean = entries.isEmpty

  /** The counter for `id`; an absent component is zero (SPEC §3.3). */
  def get(id: ContributorId): Long =
    entries.find(_._1 == id).fold(0L)(_._2)

  /** This version with `id`'s counter set to `revision` (inserted or replaced, canonical order
    * preserved). Rejects out-of-bounds revisions (R30) — the seam T06 builds the increment rule
    * (R46) on.
    */
  def updated(id: ContributorId, revision: Long): Either[SnapError, Version] =
    Revision.check(revision).map { rev =>
      val idx = entries.indexWhere((e, _) => ContributorId.ordering.compare(e, id) >= 0)
      if idx < 0 then new Version(entries :+ (id -> rev))
      else if entries(idx)._1 == id then new Version(entries.updated(idx, id -> rev))
      else new Version(entries.patch(idx, Vector(id -> rev), 0))
    }

  /** Causal comparison over the union of contributor ids, absent = 0 (SPEC §3.3, R33): `Equal` iff
    * every component equal; `Before` iff every component <= with at least one strict; `After` the
    * converse; `Concurrent` otherwise.
    */
  def compareCausal(that: Version): Ord =
    @annotation.tailrec
    def loop(i: Int, j: Int, less: Boolean, greater: Boolean): Ord =
      if less && greater then Ord.Concurrent
      else if i >= entries.length && j >= that.entries.length then
        if less then Ord.Before
        else if greater then Ord.After
        else Ord.Equal
      else if i >= entries.length then loop(i, j + 1, true, greater) // only in that: 0 < n
      else if j >= that.entries.length then loop(i + 1, j, less, true) // only in this: n > 0
      else
        val (idA, nA) = entries(i)
        val (idB, nB) = that.entries(j)
        val c = ContributorId.ordering.compare(idA, idB)
        if c < 0 then loop(i + 1, j, less, true) // idA only in this
        else if c > 0 then loop(i, j + 1, true, greater) // idB only in that
        else if nA < nB then loop(i + 1, j + 1, true, greater)
        else if nA > nB then loop(i + 1, j + 1, less, true)
        else loop(i + 1, j + 1, less, greater)
    loop(0, 0, false, false)

  /** Componentwise join: `join(V, W)[c] = max(V[c], W[c])` (SPEC §3.3, R34). Both inputs carry only
    * positive counters, so the merge of two sorted zero-free vectors is sorted and zero-free by
    * construction.
    */
  def join(that: Version): Version =
    @annotation.tailrec
    def loop(
        i: Int,
        j: Int,
        acc: Vector[(ContributorId, Long)]
    ): Vector[(ContributorId, Long)] =
      if i >= entries.length then acc ++ that.entries.drop(j)
      else if j >= that.entries.length then acc ++ entries.drop(i)
      else
        val a = entries(i)
        val b = that.entries(j)
        val c = ContributorId.ordering.compare(a._1, b._1)
        if c < 0 then loop(i + 1, j, acc :+ a)
        else if c > 0 then loop(i, j + 1, acc :+ b)
        else loop(i + 1, j + 1, acc :+ (a._1 -> math.max(a._2, b._2)))
    new Version(loop(0, 0, Vector.empty))

  /** Canonical text form (SPEC §3.2, R31): `()` or `(id->n,...)` in canonical id order with no
    * spaces. Exact inverse of [[Version.parse]].
    */
  def canonicalText: String =
    if entries.isEmpty then "()"
    else entries.iterator.map((id, n) => s"${id.value}->${n.toString}").mkString("(", ",", ")")

  /** JSON pair-array seam (SPEC §3.2, R32): the `[id, revision]` pairs in canonical order, as plain
    * data. The Json AST codec wiring happens at integration with T02's `snap/json/` layer.
    */
  def toPairs: Vector[(String, Long)] =
    entries.map((id, n) => (id.value, n))

object Version:

  val empty: Version = new Version(Vector.empty)

  /** Snap total order over versions (SPEC §3.4, R36), quoted: "Take the sorted union of contributor
    * IDs and lexicographically compare the counter at each ID. The first unequal counter decides."
    * A LOWER counter at the first differing id (ids in `Utf8Order`) sorts EARLIER — `(bob@x->1)`
    * precedes `(alice@x->1)` because at `alice@x` the counters are 0 vs 1 (DESIGN §10 gotcha 3).
    * Extends causal order; no chronological meaning.
    */
  val snapOrdering: Ordering[Version] = new Ordering[Version]:
    def compare(v: Version, w: Version): Int =
      @annotation.tailrec
      def loop(i: Int, j: Int): Int =
        val vDone = i >= v.entries.length
        val wDone = j >= w.entries.length
        if vDone && wDone then 0
        // Remaining union ids exist on one side only; at the first of them the
        // exhausted side's counter is 0, the other side's is >= 1.
        else if vDone then -1
        else if wDone then 1
        else
          val (idV, nV) = v.entries(i)
          val (idW, nW) = w.entries(j)
          val c = ContributorId.ordering.compare(idV, idW)
          if c < 0 then 1 // idV is first in the union; w has 0 there, v has >= 1
          else if c > 0 then -1 // idW is first in the union; v has 0 there
          else
            val n = java.lang.Long.compare(nV, nW)
            if n != 0 then n else loop(i + 1, j + 1)
      loop(0, 0)

  /** Builds a version from an unordered counter map; sorts by `Utf8Order` of the id (the only
    * deterministic way to iterate a `Map` here) and rejects any out-of-bounds counter. Zero
    * counters are rejected rather than dropped so bugs cannot hide behind silent normalization.
    */
  def fromMap(counters: Map[ContributorId, Long]): Either[SnapError, Version] =
    val sorted = counters.toVector.sortBy(_._1)(ContributorId.ordering)
    sorted.find((_, n) => !Revision.isValid(n)) match
      case Some(_) => Left(SnapError.RevisionNotSafeInteger)
      case None    => Right(new Version(sorted))

  /** Strict canonical parse (SPEC §3.2, R31): `()` or `(id->n,...)`. Duplicate ids, explicit
    * zeroes, leading zeroes, overflow, invalid ids, whitespace, and noncanonical ordering are all
    * errors. Reasons are typed ([[VersionError]] et al.) and rendered only by the [[Messages]]
    * catalog; user-facing wording (`snap: invalid version: <arg>`) belongs to the CLI layer.
    */
  def parse(text: String): Either[SnapError, Version] =
    if text.length < 2 || text.charAt(0) != '(' || text.charAt(text.length - 1) != ')' then
      Left(SnapError.InvalidVersionValue(VersionError.Shape))
    else
      val body = text.substring(1, text.length - 1)
      if body.isEmpty then Right(empty)
      else traverse(body.split(",", -1).toVector)(parseEntry).flatMap(fromCanonicalEntries)

  /** Builds a version from decoded JSON `[id, revision]` pairs (SPEC §3.2, R32). The pairs must
    * already be in canonical order — noncanonical order, duplicates, invalid ids, and out-of-bounds
    * revisions are errors. The rendered `canonical` / `positive safe integer` fragments match the
    * pinned diagnostics of test 23 (catalog entries [[Messages.versionValue]] /
    * [[Messages.revisionNotSafeInteger]]).
    */
  def fromPairs(pairs: Vector[(String, Long)]): Either[SnapError, Version] =
    traverse(pairs) { (idText, rev) =>
      for
        id <- ContributorId.parse(idText)
        n <- Revision.check(rev)
      yield (id, n)
    }.flatMap(fromCanonicalEntries)

  private def parseEntry(entry: String): Either[SnapError, (ContributorId, Long)] =
    // Ids cannot contain "->" and revisions are bare digits, so the first
    // "->" occurrence is an unambiguous separator (and print is its inverse).
    val sep = entry.indexOf("->")
    if sep < 0 then Left(SnapError.InvalidVersionValue(VersionError.MissingArrow))
    else
      for
        id <- ContributorId.parse(entry.substring(0, sep))
        n <- parseRevisionText(entry.substring(sep + 2))
      yield (id, n)

  private def parseRevisionText(text: String): Either[SnapError, Long] =
    if text.isEmpty then Left(SnapError.InvalidVersionValue(VersionError.EmptyRevision))
    else if !text.forall(c => c >= '0' && c <= '9') then
      // covers whitespace, sign, garbage
      Left(SnapError.InvalidVersionValue(VersionError.NonDecimalRevision))
    else if text == "0" then Left(SnapError.InvalidVersionValue(VersionError.ExplicitZeroRevision))
    else if text.charAt(0) == '0' then
      Left(SnapError.InvalidVersionValue(VersionError.LeadingZeroRevision))
    else if text.length > 16 then Left(SnapError.RevisionNotSafeInteger) // > Max digits
    else Revision.check(text.toLong) // <= 16 digits always fits a Long

  /** Shared canonicality gate for parsed entries: strictly ascending ids in `Utf8Order` — rejects
    * duplicates and noncanonical order (R31/R32).
    */
  private def fromCanonicalEntries(
      entries: Vector[(ContributorId, Long)]
  ): Either[SnapError, Version] =
    val firstViolation = (1 until entries.length).iterator
      .flatMap { k =>
        val c = ContributorId.ordering.compare(entries(k - 1)._1, entries(k)._1)
        if c == 0 then
          Some(SnapError.InvalidVersionValue(VersionError.DuplicateId(entries(k)._1.value)))
        else if c > 0 then Some(SnapError.InvalidVersionValue(VersionError.NonCanonicalOrder))
        else None
      }
      .nextOption()
    firstViolation.toLeft(new Version(entries))

  private def traverse[A, B](items: Vector[A])(
      f: A => Either[SnapError, B]
  ): Either[SnapError, Vector[B]] =
    items.foldLeft[Either[SnapError, Vector[B]]](Right(Vector.empty)) { (acc, item) =>
      acc.flatMap(out => f(item).map(out :+ _))
    }

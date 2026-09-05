package snap.cli

import snap.fs.Store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/** `snap merge` (SPEC §7.8, R5/R14/R38/R75–R76/R89; DESIGN D11) through the full [[Cli.run]]
  * pipeline — the T17 acceptance criteria as executable checks:
  *
  *   - direction independence (R76): merging A→B and B→A yields byte-identical working trees,
  *     byte-identical `repository.json`, and the same joined version and warning lines;
  *   - re-merge idempotence: exit 0, unchanged version, empty stderr, `repository.json`
  *     byte-identical (the no-op path, R89);
  *   - R75's warning subtraction: a pair already present in the pre-merge local replay is NOT
  *     re-printed, while a genuinely new pair still is;
  *   - D11's failure precedence, observable: a dirty tree (and an unsupported entry) is reported
  *     before the remote operand is even touched — pinned with a nonexistent remote path and with
  *     an `http://` operand (whose resolution is T20's);
  *   - the cross-repository dot cross-check (§3.5/R38): structural dedupe collapses equal patches,
  *     different values at one dot fail with the pinned `patch collision` line before any mutation,
  *     identically in both directions.
  *
  * The union's algebra (R14: commutative, idempotent, associative on the canonical sorted
  * representation) is additionally pinned directly on [[CommandsMerge.unionPatches]].
  */
class CommandsMergeSuite extends munit.FunSuite:

  private def run(cwd: Path, args: String*): (Int, String, String) =
    val fx = TestEnv(cwd = cwd)
    val exit = Cli.run(fx.env, args.toList)
    (exit, fx.stdout, fx.stderr)

  private def initRepo(id: String): Path =
    val root = Files.createTempDirectory("snap-merge-test")
    assertEquals(run(root, "init")._1, 0)
    configure(root, id)
    root

  private def configure(root: Path, id: String): Unit =
    assertEquals(run(root, "config", "contributor.id", id)._1, 0)

  private def write(root: Path, rel: String, text: String): Unit =
    val file = root.resolve(rel)
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    ()

  private def commit(root: Path, message: String): Unit =
    assertEquals(run(root, "commit", message)._1, 0)

  private def textAt(root: Path, rel: String): String =
    new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8)

  private def repoBytes(root: Path): Vector[Byte] =
    Files.readAllBytes(root.resolve(".snap").resolve("repository.json")).toVector

  /** Recursive copy — the suite's `copy_tree` analogue (tests 09/10/17 clone repos this way). */
  private def copyRepo(from: Path): Path =
    val to = Files.createTempDirectory("snap-merge-copy")
    val entries = Using.resource(Files.walk(from))(_.iterator().asScala.toVector)
    entries.foreach { src =>
      val dest = to.resolve(from.relativize(src).toString)
      if Files.isDirectory(src) then Files.createDirectories(dest)
      else Files.copy(src, dest)
      ()
    }
    to

  /** Every tracked working file (outside `.snap/`) as relative path → bytes — the harness's
    * `trees_equal` analogue, byte-exact.
    */
  private def workingFiles(root: Path): Map[String, Vector[Byte]] =
    val rels = Using.resource(Files.walk(root)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p))
        .map(p => root.relativize(p).toString)
        .filterNot(rel => rel == ".snap" || rel.startsWith(".snap/"))
        .toVector
    }
    rels.map(rel => rel -> Files.readAllBytes(root.resolve(rel)).toVector).toMap

  /** Two repositories with a shared seed commit and one concurrent commit each: alice edits
    * `notes.txt` and creates `same.txt`, bob edits `notes.txt` the other way and creates `same.txt`
    * with different bytes — so a merge exercises both the OT path (silent) and rule 4
    * (`later-create-wins`).
    */
  private def concurrentPair(): (Path, Path) =
    val left = initRepo("seed@x")
    write(left, "notes.txt", "base\n")
    commit(left, "base")
    val right = copyRepo(left)
    configure(left, "alice@x")
    configure(right, "bob@x")
    write(left, "notes.txt", "base\nleft\n")
    write(left, "same.txt", "alice\n")
    commit(left, "left")
    write(right, "notes.txt", "base\nright\n")
    write(right, "same.txt", "bob\n")
    commit(right, "right")
    (left, right)

  // -------------------------------------------------------------------- happy path (test 09 shape)

  test("merge converges concurrent text edits and prints the joined version (R5/R71/R89)") {
    val (left, right) = concurrentPair()
    val (exit, out, err) = run(left, "merge", right.toString)
    assertEquals(exit, 0)
    assertEquals(out, "(alice@x->1,bob@x->1,seed@x->1)\n")
    assertEquals(err, "warning: auto-resolved same.txt: later-create-wins\n")
    assertEquals(textAt(left, "notes.txt"), "base\nright\nleft\n")
    assertEquals(textAt(left, "same.txt"), "alice\n") // alice is canonically later (gotcha 3)
  }

  test("merge requires no contributor configuration (R89): an unconfigured repo can merge") {
    val local = Files.createTempDirectory("snap-merge-test")
    assertEquals(run(local, "init")._1, 0) // no `config contributor.id` ever runs here
    val remote = initRepo("a@x")
    write(remote, "f.txt", "remote\n")
    commit(remote, "remote")
    assertEquals(run(local, "merge", remote.toString), (0, "(a@x->1)\n", ""))
    assertEquals(textAt(local, "f.txt"), "remote\n")
  }

  test("a local operand resolves against the process working directory (R78)") {
    val parent = Files.createTempDirectory("snap-merge-rel")
    val a = parent.resolve("a")
    val b = parent.resolve("b")
    assertEquals(run(parent, "init", "a")._1, 0)
    assertEquals(run(parent, "init", "b")._1, 0)
    configure(b, "b@x")
    write(b, "f.txt", "b\n")
    commit(b, "from b")
    assertEquals(run(a, "merge", "../b"), (0, "(b@x->1)\n", ""))
    assertEquals(textAt(a, "f.txt"), "b\n")
  }

  // ------------------------------------------------------------- direction independence (R76)

  test("merge direction cannot change the joined result: trees and metadata byte-identical (R76)") {
    val (left, right) = concurrentPair()
    val leftCopy = copyRepo(left)
    val rightCopy = copyRepo(right)

    val forward = run(left, "merge", right.toString) // A ← B
    val backward = run(rightCopy, "merge", leftCopy.toString) // B ← A
    assertEquals(forward._1, 0)
    assertEquals(backward._1, 0)
    assertEquals(forward._2, backward._2) // same joined version on stdout
    assertEquals(forward._3, backward._3) // same warning lines on stderr
    assertEquals(workingFiles(left), workingFiles(rightCopy)) // byte-identical trees
    assertEquals(repoBytes(left), repoBytes(rightCopy)) // byte-identical repository.json
  }

  // ----------------------------------------------------------------------- no-op paths (R89)

  test("re-merging the same repository is a no-op: unchanged version, silent, bytes identical") {
    val (left, right) = concurrentPair()
    assertEquals(run(left, "merge", right.toString)._1, 0)
    val before = repoBytes(left)
    val files = workingFiles(left)
    assertEquals(
      run(left, "merge", right.toString),
      (0, "(alice@x->1,bob@x->1,seed@x->1)\n", "")
    )
    assertEquals(repoBytes(left), before)
    assertEquals(workingFiles(left), files)
  }

  test("merging an equal copy succeeds, changes nothing, and prints the unchanged version") {
    val root = initRepo("a@x")
    write(root, "f.txt", "one\n")
    commit(root, "one")
    val copy = copyRepo(root)
    val before = repoBytes(root)
    assertEquals(run(root, "merge", copy.toString), (0, "(a@x->1)\n", ""))
    assertEquals(repoBytes(root), before)
    assertEquals(textAt(root, "f.txt"), "one\n")
  }

  // -------------------------------------------------- untracked directories (audit finding 1)
  // Reproduces `reviews/audit-1-spec-conformance.md` finding 1 end to end through `snap merge`:
  // the prior `Materialize.pruneEmptyDirectories` swept the ENTIRE working tree, so an untracked,
  // pre-existing empty directory was silently deleted even by a no-op merge of already-contained
  // history — contradicting SPEC §7.8's "changes nothing".

  test(
    "merging already-contained history leaves pre-existing untracked empty directories alone, " +
      "including a nested one, with the rest of the tree byte-identical (audit finding 1)"
  ) {
    val root = initRepo("a@x")
    write(root, "f.txt", "one\n")
    commit(root, "one")
    val copy = copyRepo(root)
    Files.createDirectories(root.resolve("myEmptyDir/nested"))
    Files.createDirectories(root.resolve("docs"))
    val filesBefore = workingFiles(root)
    assertEquals(run(root, "merge", copy.toString), (0, "(a@x->1)\n", ""))
    assertEquals(workingFiles(root), filesBefore)
    assert(
      Files.isDirectory(root.resolve("myEmptyDir/nested")),
      "nested untracked directory must survive a no-op merge"
    )
    assert(
      Files.isDirectory(root.resolve("docs")),
      "untracked directory must survive a no-op merge"
    )
  }

  test(
    "a namespace-winner install (bob's directory `a/b` replaced by alice's later-created file " +
      "`a`) still prunes correctly, and an unrelated untracked empty directory survives"
  ) {
    val aliceRepo = initRepo("alice@x")
    write(aliceRepo, "a", "ancestor\n")
    commit(aliceRepo, "alice creates a")
    val bobRepo = initRepo("bob@x")
    write(bobRepo, "a/b", "descendant\n")
    commit(bobRepo, "bob creates a/b")
    // Untracked, unrelated to the merge — must survive even though this install prunes bob's
    // now-superseded `a/` directory elsewhere in the same tree.
    Files.createDirectories(bobRepo.resolve("spare/nested"))

    val (exit, out, err) = run(bobRepo, "merge", aliceRepo.toString)
    assertEquals(exit, 0)
    assertEquals(out, "(alice@x->1,bob@x->1)\n")
    assertEquals(err, "warning: auto-resolved a/b: namespace-wins\n")
    assert(Files.isRegularFile(bobRepo.resolve("a")), "alice's file must win the namespace")
    assertEquals(textAt(bobRepo, "a"), "ancestor\n")
    assert(!Files.exists(bobRepo.resolve("a/b")), "bob's superseded a/b must be gone")
    assert(
      Files.isDirectory(bobRepo.resolve("spare/nested")),
      "unrelated untracked directory must survive"
    )
  }

  // ------------------------------------------------------------------ warning subtraction (R75)

  test("a warning already in the pre-merge local replay is not re-printed; a new one is (R75)") {
    // History: seed creates delete.txt; alice edits it while bob concurrently deletes it. After
    // merging bob, alice's own frontier replay carries (delete.txt, delete-wins) permanently.
    val a = initRepo("seed@x")
    write(a, "delete.txt", "base\n")
    commit(a, "base")
    val b = copyRepo(a)
    configure(a, "alice@x")
    configure(b, "bob@x")
    write(a, "delete.txt", "base\nedit\n")
    commit(a, "edit")
    Files.delete(b.resolve("delete.txt"))
    commit(b, "delete")
    val first = run(a, "merge", b.toString)
    assertEquals(first._1, 0)
    assertEquals(first._3, "warning: auto-resolved delete.txt: delete-wins\n")

    // New concurrent history: carol (atop bob's line) and alice both create fresh.txt.
    val c = copyRepo(b)
    configure(c, "carol@x")
    write(c, "fresh.txt", "c\n")
    commit(c, "carol fresh")
    write(a, "fresh.txt", "a\n")
    commit(a, "alice fresh")

    // The joined replay warns on BOTH delete.txt (old) and fresh.txt (new); only the new pair
    // prints — the pre-merge local set already contains the delete-wins pair.
    val second = run(a, "merge", c.toString)
    assertEquals(second._1, 0)
    assertEquals(second._2, "(alice@x->2,bob@x->1,carol@x->1,seed@x->1)\n")
    assertEquals(second._3, "warning: auto-resolved fresh.txt: later-create-wins\n")
    assertEquals(textAt(a, "fresh.txt"), "a\n") // alice's create is canonically later
    assert(!Files.exists(a.resolve("delete.txt")))
  }

  // ------------------------------------------------------------- failure precedence (D11, test 20)

  test("a dirty tree is reported before the remote is read: nonexistent operand, exact line") {
    val root = initRepo("a@x")
    write(root, "f.txt", "one\n")
    commit(root, "one")
    write(root, "dirty.txt", "dirty\n")
    val before = repoBytes(root)
    assertEquals(
      run(root, "merge", root.resolve("does-not-exist").toString),
      (1, "", "snap: working tree is dirty\n")
    )
    assertEquals(repoBytes(root), before)
    assertEquals(textAt(root, "dirty.txt"), "dirty\n")
  }

  test("an unsupported working-tree entry is reported before the remote is read (R104)") {
    val root = initRepo("a@x")
    write(root, "f.txt", "one\n")
    commit(root, "one")
    Files.createSymbolicLink(root.resolve("link"), root.resolve("f.txt"))
    assertEquals(
      run(root, "merge", root.resolve("does-not-exist").toString),
      (1, "", "snap: unsupported working tree entry: link\n")
    )
  }

  test("a clean tree with an unreadable remote fails on the remote load, mutating nothing") {
    val root = initRepo("a@x")
    write(root, "f.txt", "one\n")
    commit(root, "one")
    val before = repoBytes(root)
    val (exit, out, err) = run(root, "merge", root.resolve("does-not-exist").toString)
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: cannot read repository: "), err)
    assertEquals(repoBytes(root), before)
  }

  test("an http(s) operand keeps D11's precedence: dirty wins before the GET is ever issued") {
    val root = initRepo("a@x")
    write(root, "f.txt", "one\n")
    commit(root, "one")
    write(root, "dirty.txt", "dirty\n")
    // Dirty first, even for a URL operand — the operand kind is resolved at the remote-load step,
    // so a URL pointed at a port nothing listens on never gets far enough to matter here.
    assertEquals(
      run(root, "merge", "http://127.0.0.1:1/repository.json"),
      (1, "", "snap: working tree is dirty\n")
    )
    Files.delete(root.resolve("dirty.txt"))
    // T20 (R78/R102): once the tree is clean, `https://` is routed to the real client, which
    // attempts the one GET and fails on connection refusal (nothing listens on port 1) — the
    // typed [[snap.core.SnapError.HttpRequestFailed]] boundary, not `not implemented` (T20 replaces
    // the placeholder this test pinned before the client existed).
    val (exit, out, err) = run(root, "merge", "https://127.0.0.1:1/repository.json")
    assertEquals(exit, 1)
    assertEquals(out, "")
    assert(err.startsWith("snap: cannot fetch remote repository: "), err)
  }

  test("merge takes exactly one operand (coarse R79; T13 owns the exhaustive matrix)") {
    val root = initRepo("a@x")
    assertEquals(run(root, "merge"), (1, "", "snap: invalid command or arguments\n"))
    assertEquals(run(root, "merge", "x", "y"), (1, "", "snap: invalid command or arguments\n"))
  }

  // ------------------------------------------------------------------ dot collision (§3.5, R38)

  test(
    "the same dot with different values fails with the pinned line, both directions, no writes"
  ) {
    // Two disconnected repos whose contributor authored concurrently: the same dot (a@x, 1) holds
    // structurally different patches — §3.5's deliberate limitation.
    val local = initRepo("a@x")
    write(local, "file.txt", "local\n")
    commit(local, "local")
    val remote = initRepo("a@x")
    write(remote, "file.txt", "remote\n")
    commit(remote, "remote")
    val localBefore = repoBytes(local)
    val remoteBefore = repoBytes(remote)
    assertEquals(
      run(local, "merge", remote.toString),
      (1, "", "snap: patch collision: a@x revision 1\n")
    )
    assertEquals(
      run(remote, "merge", local.toString),
      (1, "", "snap: patch collision: a@x revision 1\n")
    )
    assertEquals(repoBytes(local), localBefore)
    assertEquals(repoBytes(remote), remoteBefore)
    assertEquals(textAt(local, "file.txt"), "local\n")
    assertEquals(textAt(remote, "file.txt"), "remote\n")
  }

  // ------------------------------------------------------------------- union algebra (R14)

  test("unionPatches is commutative, idempotent, and structurally deduping (R14/R47)") {
    val (left, right) = concurrentPair()
    val l = Store.readRepository(Commands.repositoryFile(left)).toOption.get.repository.patches
    val r = Store.readRepository(Commands.repositoryFile(right)).toOption.get.repository.patches
    val lr = CommandsMerge.unionPatches(l, r)
    val rl = CommandsMerge.unionPatches(r, l)
    assertEquals(lr, rl) // commutative — direction cannot change the union
    assertEquals(CommandsMerge.unionPatches(l, l), Right(l)) // idempotent
    assertEquals(CommandsMerge.unionPatches(r, r), Right(r))
    // The shared seed patch collapses to one value: |union| = |l| + |r| - |shared|.
    assertEquals(lr.toOption.get.length, 3)
    // Associativity on this fixture: (l ∪ r) ∪ r = l ∪ r.
    assertEquals(lr.flatMap(CommandsMerge.unionPatches(_, r)), lr)
  }

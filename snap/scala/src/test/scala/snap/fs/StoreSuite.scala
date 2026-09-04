package snap.fs

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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ListBuffer

/** `repository.json` persistence: read pipeline (bytes → parse → decode → validate) and the atomic
  * same-directory temp-file write (R105, gotcha 10).
  */
class StoreSuite extends FunSuite:

  // Test-boundary mutability: created directories are only appended here and read once in
  // afterAll for cleanup — no test logic depends on this buffer.
  private val createdDirs = ListBuffer.empty[Path]

  override def afterAll(): Unit =
    createdDirs.foreach { dir =>
      // deepest-first so directories are empty when deleted
      val entries = Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).toList
      entries.forEach(p => Files.deleteIfExists(p))
    }

  private def tempDir(): Path =
    val dir = Files.createTempDirectory("snap-store-suite")
    createdDirs += dir
    dir

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw': $e"), identity)

  private def v(pairs: (String, Long)*): Version =
    Version
      .fromPairs(pairs.toVector)
      .fold(e => fail(s"expected valid version: ${e.message}"), identity)

  /** A nonempty repository exercising all three change variants and a non-ASCII path. */
  private val sample: Repository =
    val changes = Vector(
      Change.Text(p("hello.txt"), EditScript(Vector(EditOp.Insert(Vector("hello\n"))))),
      Change.Put(p("image.bin"), IArray[Byte](0, 1, 2)),
      Change.Put(p("é"), IArray.unsafeFromArray("café\n".getBytes(StandardCharsets.UTF_8)))
    )
    val one = Patch
      .make(id("a@x"), 1L, Version.empty, "add files", changes)
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)
    val two = Patch
      .make(id("a@x"), 2L, v("a@x" -> 1L), "drop\nsomething", Vector(Change.Delete(p("é"))))
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)
    Repository(v("a@x" -> 2L), Vector(one, two))

  test("write then read round-trips the repository value") {
    val file = tempDir().resolve("repository.json")
    assertEquals(Store.writeRepository(file, sample), Right(()))
    assertEquals(Store.readRepository(file).map(_.repository), Right(sample))
  }

  test("write emits the canonical serializer's exact bytes (D7) and leaves no temp file") {
    val dir = tempDir()
    val file = dir.resolve("repository.json")
    assertEquals(Store.writeRepository(file, sample), Right(()))
    assert(Files.readAllBytes(file).sameElements(snap.json.RepoCodec.encodeBytes(sample)))
    assert(!Files.exists(Store.tempPathFor(file)))
    // exactly one entry in the directory
    val listed = Files.list(dir).toList
    assertEquals(listed.size(), 1)
  }

  test("the temp name is derived from the target file name, same directory (PR4/CR11)") {
    val file = Path.of("/a/b").resolve("repository.json")
    assertEquals(Store.tempPathFor(file), Path.of("/a/b").resolve("repository.json.tmp"))
    val globalConfig = Path.of("/home/alice").resolve(".snapconfig.json")
    assertEquals(
      Store.tempPathFor(globalConfig),
      Path.of("/home/alice").resolve(".snapconfig.json.tmp")
    )
  }

  test(
    "a repository write and a global config write staged in the same directory never collide " +
      "(PR4/CR11): both survive with their own bytes"
  ) {
    val dir = tempDir()
    val repoFile = dir.resolve(Store.RepositoryFileName)
    val configFile = dir.resolve(Store.GlobalConfigFileName)
    val alice = id("alice@example.com")
    assertEquals(Store.writeRepository(repoFile, sample), Right(()))
    assertEquals(Store.writeConfig(configFile, alice), Right(()))
    assertEquals(Store.readRepository(repoFile).map(_.repository), Right(sample))
    assertEquals(Store.readConfig(configFile), Right(Some(alice)))
    assert(!Files.exists(Store.tempPathFor(repoFile)))
    assert(!Files.exists(Store.tempPathFor(configFile)))
    assertNotEquals(Store.tempPathFor(repoFile), Store.tempPathFor(configFile))
  }

  test("repeated writes are byte-identical (determinism)") {
    val dir = tempDir()
    val one = dir.resolve("one.json")
    val two = dir.resolve("two.json")
    assertEquals(Store.writeRepository(one, sample), Right(()))
    assertEquals(Store.writeRepository(two, sample), Right(()))
    assert(Files.readAllBytes(one).sameElements(Files.readAllBytes(two)))
  }

  test("staging never touches the target: a failure before the move leaves the old bytes") {
    val dir = tempDir()
    val file = dir.resolve("repository.json")
    val oldBytes =
      "{\"format\": 1, \"frontier\": [], \"patches\": []}\n".getBytes(StandardCharsets.UTF_8)
    Files.write(file, oldBytes)
    // simulate the crash window: stage the new content, then stop before the move
    val staged = Store.stage(file, snap.json.RepoCodec.encodeBytes(sample))
    assert(staged.isRight)
    assert(Files.readAllBytes(file).sameElements(oldBytes)) // target untouched
    staged.foreach { temp =>
      assertEquals(temp.getParent, file.getParent) // same directory — gotcha 10
      assert(Files.exists(temp))
      // completing the move installs the new bytes atomically and removes the temp
      assertEquals(Store.commit(temp, file), Right(()))
      assert(Files.readAllBytes(file).sameElements(snap.json.RepoCodec.encodeBytes(sample)))
      assert(!Files.exists(temp))
    }
  }

  test("a failing write reports a typed error and creates nothing") {
    val missing = tempDir().resolve("no-such-dir").resolve("repository.json")
    Store.writeRepository(missing, sample) match
      case Left(SnapError.CannotWriteRepository(_)) => ()
      case other => fail(s"expected CannotWriteRepository, got $other")
    assert(!Files.exists(missing))
  }

  test("reading a missing file reports a typed error") {
    val missing = tempDir().resolve("repository.json")
    Store.readRepository(missing) match
      case Left(SnapError.CannotReadRepository(_)) => ()
      case other => fail(s"expected CannotReadRepository, got $other")
  }

  test("reading malformed JSON reports the pinned `invalid JSON` class") {
    val file = tempDir().resolve("repository.json")
    Files.write(file, "{not json".getBytes(StandardCharsets.UTF_8))
    assert(Store.readRepository(file).left.exists(_.message.contains("invalid JSON")))
  }

  test("reading non-UTF-8 bytes reports the `invalid JSON` class") {
    val file = tempDir().resolve("repository.json")
    Files.write(file, Array[Byte](0x7b, -1, -2, 0x7d))
    assertEquals(Store.readRepository(file), Left(SnapError.RepositoryNotUtf8))
    assert(Store.readRepository(file).left.exists(_.message.contains("invalid JSON")))
  }

  test(
    "a raw NUL is valid UTF-8 and falls through to jawn's own positioned diagnostic, not " +
      "`RepositoryNotUtf8` (CR-NUL)"
  ) {
    val file = tempDir().resolve("repository.json")
    // NUL where a JSON value is expected — valid UTF-8 (0x00 is a one-byte code point), so the
    // pre-parse gate must let it through; the JSON grammar itself then rejects it, positioned.
    Files.write(file, Array[Byte]('{', 0x00, '}'))
    val result = Store.readRepository(file)
    assert(result.left.exists(_.message.contains("invalid JSON")), result)
    assert(
      result.left.exists(e => e.message.contains("line") && e.message.contains("column")),
      result
    )
    assert(!result.left.exists(_.message.contains("not valid UTF-8")), result)
  }

  test("read rejects a schema-valid but structurally invalid history (test 23's unreachable)") {
    val file = tempDir().resolve("repository.json")
    val text = """{
      |  "format": 1,
      |  "frontier": [],
      |  "patches": [
      |    {
      |      "author": "a@x",
      |      "revision": 1,
      |      "base": [],
      |      "message": "unreachable",
      |      "changes": [{"type": "text", "path": "f", "edit": []}]
      |    }
      |  ]
      |}""".stripMargin
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    assertEquals(
      Store.readRepository(file).left.map(_.message),
      Left("unreachable patch: a@x revision 1")
    )
  }

  test("read rejects a duplicated JSON key (test 15's fixture)") {
    val file = tempDir().resolve("repository.json")
    Files.write(
      file,
      """{"format":1,"format":1,"frontier":[],"patches":[]}""".getBytes(StandardCharsets.UTF_8)
    )
    assert(Store.readRepository(file).left.exists(_.message.contains("duplicate JSON key")))
  }

  test("read performs no filesystem mutation (R103)") {
    val dir = tempDir()
    val file = dir.resolve("repository.json")
    assertEquals(Store.writeRepository(file, sample), Right(()))
    val before = Files.readAllBytes(file)
    assert(Store.readRepository(file).isRight)
    assert(Files.readAllBytes(file).sameElements(before))
    assertEquals(Files.list(dir).toList.size(), 1)
  }

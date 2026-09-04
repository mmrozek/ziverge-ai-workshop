package snap.fs

import munit.FunSuite
import snap.core.ContributorId
import snap.core.SnapError

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ListBuffer

/** Contributor-configuration persistence (SPEC §8, R99) and `init`'s directory creation (T09). */
class StoreConfigSuite extends FunSuite:

  // Test-boundary mutability: created directories are only appended here and read once in
  // afterAll for cleanup — no test logic depends on this buffer (same pattern as StoreSuite).
  private val createdDirs = ListBuffer.empty[Path]

  override def afterAll(): Unit =
    createdDirs.foreach { dir =>
      val entries = Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).toList
      entries.forEach(p => Files.deleteIfExists(p))
    }

  private def tempDir(): Path =
    val dir = Files.createTempDirectory("snap-store-config-suite")
    createdDirs += dir
    dir

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  // ------------------------------------------------------------ readConfig

  test("a missing config file reads as no value, not an error") {
    val file = tempDir().resolve("config.json")
    assertEquals(Store.readConfig(file), Right(None))
  }

  test(
    "an unreadable config path is CannotReadConfig, not silently folded into absent (CR10)"
  ) {
    // A directory stand-in for "exists but cannot be read as a file": `Files.readAllBytes` on a
    // directory fails with an I/O error that is NOT `NoSuchFileException`, so the attempt-read gate
    // must distinguish it from genuine absence rather than reporting `Right(None)`.
    val dirAsFile = tempDir()
    Store.readConfig(dirAsFile) match
      case Left(SnapError.CannotReadConfig(_)) => ()
      case other                               => fail(s"expected CannotReadConfig, got $other")
  }

  test("write then read round-trips the contributor id") {
    val file = tempDir().resolve("config.json")
    val alice = id("alice@example.com")
    assertEquals(Store.writeConfig(file, alice), Right(()))
    assertEquals(Store.readConfig(file), Right(Some(alice)))
  }

  test("write emits the canonical serializer's exact bytes (D7) and leaves no temp file") {
    val dir = tempDir()
    val file = dir.resolve("config.json")
    val bob = id("bob@example.com")
    assertEquals(Store.writeConfig(file, bob), Right(()))
    assert(Files.readAllBytes(file).sameElements(snap.json.ConfigCodec.encodeBytes(bob)))
    assert(!Files.exists(Store.tempPathFor(file)))
    assertEquals(Files.list(dir).toList.size(), 1)
  }

  test(
    "overwrite replaces the file completely, never reading the old content (test 25's premise)"
  ) {
    val file = tempDir().resolve("config.json")
    Files.write(
      file,
      """{"contributor":{"id":"old@x"},"unknown":true}""".getBytes(StandardCharsets.UTF_8)
    )
    val fresh = id("new@x")
    assertEquals(Store.writeConfig(file, fresh), Right(()))
    assertEquals(Store.readConfig(file), Right(Some(fresh)))
    val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    assert(!text.contains("unknown"), text)
    assert(!text.contains("old@x"), text)
  }

  test(
    "reading malformed JSON reports the pinned `invalid JSON` class (test 03's malformed global)"
  ) {
    val file = tempDir().resolve("config.json")
    Files.write(file, "not json".getBytes(StandardCharsets.UTF_8))
    assert(Store.readConfig(file).left.exists(_.message.contains("invalid JSON")))
  }

  test("reading non-UTF-8 bytes reports the `invalid JSON` class") {
    val file = tempDir().resolve("config.json")
    Files.write(file, Array[Byte](0x7b, -1, -2, 0x7d))
    assertEquals(Store.readConfig(file), Left(SnapError.ConfigNotUtf8))
    assert(Store.readConfig(file).left.exists(_.message.contains("invalid JSON")))
  }

  test("reading a duplicate JSON key reports the pinned diagnostic (test 25)") {
    val file = tempDir().resolve("config.json")
    Files.write(
      file,
      """{"contributor":{"id":"a@x","id":"b@x"}}""".getBytes(StandardCharsets.UTF_8)
    )
    assert(Store.readConfig(file).left.exists(_.message.contains("duplicate JSON key")))
  }

  test("reading an invalid id reports the `invalid contributor id` diagnostic (test 25)") {
    val file = tempDir().resolve("config.json")
    Files.write(file, """{"contributor":{"id":"not-an-id"}}""".getBytes(StandardCharsets.UTF_8))
    assert(Store.readConfig(file).left.exists(_.message.contains("invalid contributor id")))
  }

  test("a failing write reports a typed error and creates nothing") {
    val missing = tempDir().resolve("no-such-dir").resolve("config.json")
    Store.writeConfig(missing, id("a@x")) match
      case Left(SnapError.CannotWriteConfig(_)) => ()
      case other                                => fail(s"expected CannotWriteConfig, got $other")
    assert(!Files.exists(missing))
  }

  test("reading performs no filesystem mutation (R103)") {
    val dir = tempDir()
    val file = dir.resolve("config.json")
    val alice = id("alice@example.com")
    assertEquals(Store.writeConfig(file, alice), Right(()))
    val before = Files.readAllBytes(file)
    assert(Store.readConfig(file).isRight)
    assert(Files.readAllBytes(file).sameElements(before))
    assertEquals(Files.list(dir).toList.size(), 1)
  }

  // ------------------------------------------------------------ createDirectories

  test("createDirectories creates missing parents and is idempotent") {
    val dir = tempDir().resolve("a").resolve("b").resolve("c")
    assertEquals(Store.createDirectories(dir), Right(()))
    assert(Files.isDirectory(dir))
    // calling again on an existing directory is a no-op success
    assertEquals(Store.createDirectories(dir), Right(()))
  }

  test("createDirectories leaves existing files inside an existing directory untouched") {
    val dir = tempDir()
    val marker = dir.resolve("existing.txt")
    Files.write(marker, "keep me\n".getBytes(StandardCharsets.UTF_8))
    assertEquals(Store.createDirectories(dir), Right(()))
    assertEquals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8), "keep me\n")
  }

  test("createDirectories reports a typed error when a path component is a plain file") {
    val dir = tempDir()
    val blocker = dir.resolve("blocker")
    Files.write(blocker, "not a directory".getBytes(StandardCharsets.UTF_8))
    Store.createDirectories(blocker.resolve("child")) match
      case Left(SnapError.CannotCreateDirectory(_)) => ()
      case other => fail(s"expected CannotCreateDirectory, got $other")
  }

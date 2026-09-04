package snap.core

import munit.FunSuite

/** Structural repository validation — SPEC §4.5 steps 1–4 (R44, R46, R59–R60). Fixtures lift the
  * histories of provided tests 15/23/27; steps 5–6 (changes vs materialized base, replay) are T07's
  * and consume the returned `Repo.StructurallyValid`.
  */
class RepoValidateSuite extends FunSuite:

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"expected valid id '$raw': ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"expected valid path '$raw': $e"), identity)

  private def v(pairs: (String, Long)*): Version =
    Version
      .fromPairs(pairs.toVector)
      .fold(e => fail(s"expected valid version: ${e.message}"), identity)

  private def patch(
      author: String,
      revision: Long,
      base: Version,
      message: String = "m",
      path: String = "f"
  ): Patch =
    Patch
      .make(id(author), revision, base, message, Vector(Change.Text(p(path), EditScript.empty)))
      .fold(e => fail(s"expected valid patch: ${e.message}"), identity)

  private def errorMessage(repository: Repository): String =
    Repo.validate(repository) match
      case Left(e)  => e.message
      case Right(_) => fail("expected validation to reject")

  test("empty repository is valid with no results") {
    assertEquals(
      Repo.validate(Repository.empty),
      Right(Repo.StructurallyValid(Repository.empty, Vector.empty))
    )
  }

  test("valid multi-contributor history passes with per-patch results (R46)") {
    val a1 = patch("a@x", 1L, Version.empty)
    val a2 = patch("a@x", 2L, v("a@x" -> 1L))
    val b1 = patch("b@x", 1L, v("a@x" -> 1L))
    val repository = Repository(v("a@x" -> 2L, "b@x" -> 1L), Vector(a1, a2, b1))
    assertEquals(
      Repo.validate(repository),
      Right(
        Repo.StructurallyValid(
          repository,
          Vector(v("a@x" -> 1L), v("a@x" -> 2L), v("a@x" -> 1L, "b@x" -> 1L))
        )
      )
    )
  }

  test("revision gap reports the missing dot (test 15: `missing a@x`)") {
    // frontier a@x->2 with only revision 2 present
    val repository =
      Repository(v("a@x" -> 2L), Vector(patch("a@x", 2L, v("a@x" -> 1L), message = "gap")))
    val message = errorMessage(repository)
    assertEquals(message, "patch history is missing a@x revision 1")
    assert(message.contains("missing a@x"))
  }

  test("mid-chain revision gap reports the first missing revision") {
    val repository = Repository(
      v("a@x" -> 3L),
      Vector(patch("a@x", 1L, Version.empty), patch("a@x", 3L, v("a@x" -> 2L)))
    )
    assertEquals(errorMessage(repository), "patch history is missing a@x revision 2")
  }

  test("patches not sorted by author are rejected (test 27)") {
    val repository = Repository(
      v("a@x" -> 1L, "b@x" -> 1L),
      Vector(
        patch("b@x", 1L, Version.empty, path = "b"),
        patch("a@x", 1L, Version.empty, path = "a")
      )
    )
    assertEquals(Repo.validate(repository), Left(SnapError.PatchesNotSorted))
  }

  test("patches not sorted by numeric revision are rejected") {
    val repository = Repository(
      v("a@x" -> 2L),
      Vector(patch("a@x", 2L, v("a@x" -> 1L)), patch("a@x", 1L, Version.empty))
    )
    assertEquals(Repo.validate(repository), Left(SnapError.PatchesNotSorted))
  }

  test("one value per dot: structurally equal duplicate rows are rejected") {
    val a1 = patch("a@x", 1L, Version.empty)
    val repository = Repository(v("a@x" -> 1L), Vector(a1, a1))
    assertEquals(Repo.validate(repository), Left(SnapError.DuplicatePatch(Dot(id("a@x"), 1L))))
  }

  test("one value per dot: structurally different values are corruption (R47, §3.5)") {
    val one = patch("a@x", 1L, Version.empty, message = "one")
    val other = patch("a@x", 1L, Version.empty, message = "two")
    val repository = Repository(v("a@x" -> 1L), Vector(one, other))
    val result = Repo.validate(repository)
    assertEquals(result, Left(SnapError.PatchCollision(Dot(id("a@x"), 1L))))
    assertEquals(result.left.map(_.message), Left("patch collision: a@x revision 1"))
  }

  test("revision must increment the base's author component (R46; test 27's wrong dot)") {
    // base already contains the author's own dot: base[a@x]+1 = 2, revision = 1
    val repository = Repository(v("a@x" -> 1L), Vector(patch("a@x", 1L, v("a@x" -> 1L))))
    assertEquals(Repo.validate(repository), Left(SnapError.DotMismatch(Dot(id("a@x"), 1L))))
  }

  test("base referencing an absent contributor's patch is missing (step 3)") {
    val repository = Repository(v("a@x" -> 1L), Vector(patch("a@x", 1L, v("b@x" -> 1L))))
    assertEquals(Repo.validate(repository), Left(SnapError.MissingPatch(Dot(id("b@x"), 1L))))
  }

  test("frontier referencing an absent patch is missing (R45)") {
    val repository = Repository(v("a@x" -> 1L), Vector.empty)
    assertEquals(Repo.validate(repository), Left(SnapError.MissingPatch(Dot(id("a@x"), 1L))))
  }

  test("unreachable patch is rejected with the pinned prefix (test 23)") {
    val repository = Repository(Version.empty, Vector(patch("a@x", 1L, Version.empty)))
    val result = Repo.validate(repository)
    assertEquals(result, Left(SnapError.UnreachablePatch(Dot(id("a@x"), 1L))))
    assertEquals(result.left.map(_.message), Left("unreachable patch: a@x revision 1"))
    assert(result.left.exists(_.message.startsWith("unreachable patch: ")))
  }

  test("base cycle is rejected with the pinned phrase (test 15)") {
    val a1 = patch("a@x", 1L, v("b@x" -> 1L), message = "cycle a", path = "a")
    val b1 = patch("b@x", 1L, v("a@x" -> 1L), message = "cycle b", path = "b")
    val repository = Repository(v("a@x" -> 1L, "b@x" -> 1L), Vector(a1, b1))
    val result = Repo.validate(repository)
    assertEquals(result, Left(SnapError.CyclicHistory))
    assertEquals(result.left.map(_.message), Left("cyclic or incomplete patch history"))
  }

  test("validation is deterministic: repeated runs return the same value") {
    val a1 = patch("a@x", 1L, Version.empty)
    val valid = Repository(v("a@x" -> 1L), Vector(a1))
    assertEquals(Repo.validate(valid), Repo.validate(valid))
    val cyclic = Repository(
      v("a@x" -> 1L, "b@x" -> 1L),
      Vector(
        patch("a@x", 1L, v("b@x" -> 1L), path = "a"),
        patch("b@x", 1L, v("a@x" -> 1L), path = "b")
      )
    )
    assertEquals(Repo.validate(cyclic), Repo.validate(cyclic))
  }

  test("validation performs no mutation: the input repository value is returned unchanged") {
    val a1 = patch("a@x", 1L, Version.empty)
    val repository = Repository(v("a@x" -> 1L), Vector(a1))
    val validated = Repo.validate(repository)
    assertEquals(validated.map(_.repository), Right(repository))
  }

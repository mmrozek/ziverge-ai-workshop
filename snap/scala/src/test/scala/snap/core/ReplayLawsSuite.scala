package snap.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.charset.StandardCharsets

/** Property tests for deterministic replay over generated linear histories (R66, R76 in T07's
  * linear scope; determinism obligations, CLAUDE.md / R109): materialization is a function of the
  * patch set and version alone — insensitive to the input order of the `patches` array and
  * byte-identical across repeated runs — and matches an independently tracked expected tree.
  */
class ReplayLawsSuite extends munit.ScalaCheckSuite:

  private def id(raw: String): ContributorId =
    ContributorId.parse(raw).fold(e => fail(s"bad id $raw: ${e.message}"), identity)

  private def p(raw: String): SnapPath =
    SnapPath.parse(raw).fold(e => fail(s"bad path $raw: $e"), identity)

  private def utf8(text: String): IArray[Byte] =
    // Fresh array, never aliased afterwards.
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  private val authors: Vector[ContributorId] = Vector(id("a@x"), id("b@x"), id("c@x"))

  /** One generated step: an operation-kind seed and two pick seeds, turned into a concrete valid
    * patch by [[buildLinear]] (generation stays total: kinds fall back to `create` when the tree
    * has no file to edit/replace/delete).
    */
  private final case class StepSeed(kind: Int, authorPick: Int, filePick: Int)

  private val genSeeds: Gen[Vector[StepSeed]] =
    for
      n <- Gen.choose(1, 10)
      seeds <- Gen.listOfN(
        n,
        for
          kind <- Gen.choose(0, 3)
          authorPick <- Gen.choose(0, authors.size - 1)
          filePick <- Gen.choose(0, 1000)
        yield StepSeed(kind, authorPick, filePick)
      )
    yield seeds.toVector

  /** Builds a strictly linear multi-author history (every base is the full previous frontier) plus
    * the independently tracked expected tree. All files are single-segment text files, so every
    * generated change is valid against its base: creations use fresh names, edits append a unique
    * line (never a no-op), puts write unique content, deletes pick a present path.
    */
  private def buildLinear(seeds: Vector[StepSeed]): (Vector[Patch], Version, Tree) =
    val start = (Vector.empty[Patch], Version.empty, Tree.empty)
    seeds.zipWithIndex.foldLeft(start) { case ((patches, frontier, tree), (seed, i)) =>
      val author = authors(seed.authorPick)
      val paths = tree.paths
      val change =
        if seed.kind == 0 || paths.isEmpty then
          // create: text file with one unique line (or, every third step, an empty file — R58)
          val path = p(s"f$i")
          if seed.filePick % 3 == 0 then Change.Text(path, EditScript.empty)
          else Change.Text(path, EditScript(Vector(EditOp.Insert(Vector(s"line$i\n")))))
        else
          val path = paths(seed.filePick % paths.size)
          seed.kind match
            case 1 => // edit: append a unique line via the canonical diff
              val oldTokens = tokensAt(tree, path)
              val newTokens = oldTokens :+ s"line$i\n"
              Change.Text(path, Diff.diff(oldTokens, newTokens))
            case 2 => // delete a present path
              Change.Delete(path)
            case _ => // replace with unique content
              Change.Put(path, utf8(s"put$i\n"))
      val patch = Patch
        .make(author, frontier.get(author) + 1L, frontier, "m", Vector(change))
        .fold(e => fail(s"generator produced an invalid patch: ${e.message}"), identity)
      val result = patch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity)
      (patches :+ patch, result, applyExpected(tree, change))
    }

  /** The generator's own view of a change's effect — independent of [[Replay.authoredResult]]. */
  private def applyExpected(tree: Tree, change: Change): Tree = change match
    case Change.Delete(path)       => tree.removed(path)
    case Change.Put(path, content) => tree.updated(path, content)
    case Change.Text(path, edit)   =>
      val tokens = edit.ops.collect { case EditOp.Insert(ts) => ts }.flatten
      val retained = edit.ops.collectFirst { case EditOp.Retain(n) => n }
      val oldTokens = tree.get(path).fold(Vector.empty[String])(_ => tokensAt(tree, path))
      // Generated edits are either pure creations (inserts only) or diff(old, old :+ line);
      // both reduce to "old retained tokens plus the inserted ones" in order.
      val rebuilt = retained match
        case Some(n) => oldTokens.take(n.toInt) ++ tokens ++ oldTokens.drop(n.toInt)
        case None    => tokens
      tree.updated(path, utf8(rebuilt.mkString))

  private def tokensAt(tree: Tree, path: SnapPath): Vector[String] =
    tree.get(path) match
      case Some(bytes) =>
        TextTokens
          .tokenizeBytes(IArray.genericWrapArray(bytes).toArray)
          .getOrElse(fail(s"generator produced a non-text file at ${path.value}"))
      case None => Vector.empty

  private val genHistory: Gen[(Vector[Patch], Version, Tree)] = genSeeds.map(buildLinear)

  /** A deterministic permutation from generated seeds: indices sorted by (seed, index). */
  private def genPermutation(n: Int): Gen[Vector[Int]] =
    Gen.listOfN(n, Gen.choose(Long.MinValue, Long.MaxValue)).map { seeds =>
      (0 until n).toVector.sortBy(i => (seeds(i), i))
    }

  private def sortedForValidate(patches: Vector[Patch]): Vector[Patch] =
    patches.sortBy(patch => (patch.author.value, patch.revision))(
      Ordering.Tuple2(Utf8Order, Ordering.Long)
    )

  private def structurallyValid(frontier: Version, patches: Vector[Patch]): Repo.StructurallyValid =
    Repo
      .validate(Repository(frontier, patches))
      .fold(e => fail(s"generated history failed steps 1–4: ${e.message}"), identity)

  private def handBuilt(frontier: Version, patches: Vector[Patch]): Repo.StructurallyValid =
    Repo.StructurallyValid(
      Repository(frontier, patches),
      patches.map(_.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity))
    )

  property("a generated linear history materializes to the independently tracked tree") {
    forAll(genHistory) { case (patches, frontier, expected) =>
      val valid = structurallyValid(frontier, sortedForValidate(patches))
      assertEquals(Replay.materialize(valid, frontier, Replay.LinearOnly), Right(expected))
    }
  }

  property("materialization is insensitive to the input order of the patches array") {
    forAll(genHistory.flatMap { case h @ (patches, _, _) =>
      genPermutation(patches.size).map(perm => (h, perm))
    }) { case ((patches, frontier, expected), perm) =>
      // Steps 1–4 enforce file sorting, so the permuted history is hand-assembled: replay itself
      // must never read input order (the ready-loop reduces by a total order).
      val permuted = handBuilt(frontier, perm.map(patches))
      assertEquals(Replay.materialize(permuted, frontier, Replay.LinearOnly), Right(expected))
      assertEquals(
        Replay.integrationOrder(permuted, frontier, Replay.LinearOnly),
        Replay.integrationOrder(handBuilt(frontier, patches), frontier, Replay.LinearOnly)
      )
    }
  }

  property("repeated materialization of one history is byte-identical") {
    forAll(genHistory) { case (patches, frontier, _) =>
      val valid = structurallyValid(frontier, sortedForValidate(patches))
      val first = Replay.materialize(valid, frontier, Replay.LinearOnly)
      val second = Replay.materialize(valid, frontier, Replay.LinearOnly)
      assertEquals(first, second) // Tree equality is byte-content equality
    }
  }

  property("every intermediate frontier of a linear history is a known version (R45)") {
    forAll(genHistory) { case (patches, frontier, _) =>
      val valid = structurallyValid(frontier, sortedForValidate(patches))
      val frontiers = patches.scanLeft(Version.empty) { (acc, patch) =>
        acc.join(patch.result.fold(e => fail(s"unbuildable result: ${e.message}"), identity))
      }
      frontiers.foreach(version => assertEquals(Replay.checkKnown(valid, version), Right(())))
    }
  }

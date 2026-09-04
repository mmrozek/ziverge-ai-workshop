package snap.core

import snap.core.EditOp.Delete
import snap.core.EditOp.Insert
import snap.core.EditOp.Retain

class EditScriptSuite extends munit.FunSuite:

  private def script(ops: EditOp*): EditScript = EditScript(ops.toVector)

  test("counts must be positive safe integers") {
    for bad <- List(0L, -1L, 9007199254740992L) do // 2^53 is not safe
      assertEquals(script(Retain(bad)).validate, Left(EditError.BadCount), bad)
      assertEquals(script(Delete(bad)).validate, Left(EditError.BadCount), bad)
    assertEquals(script(Retain(9007199254740991L)).validate, Right(())) // 2^53 - 1 is
    assert(EditError.BadCount.message.endsWith("positive safe integer")) // test 23 anchor
  }

  test("insert must be a nonempty array of nonempty text tokens") {
    assertEquals(script(Insert(Vector.empty)).validate, Left(EditError.EmptyInsert))
    assert(EditError.EmptyInsert.message.endsWith("insert is empty")) // test 23 anchor
    assertEquals(script(Insert(Vector(""))).validate, Left(EditError.BadInsertToken))
    assertEquals(script(Insert(Vector("a\nb"))).validate, Left(EditError.BadInsertToken))
    assertEquals(script(Insert(Vector("a\u0000\n"))).validate, Left(EditError.BadInsertToken))
    assertEquals(script(Insert(Vector("a\n", "b"))).validate, Right(()))
  }

  test("adjacent same-kind operations are forbidden") {
    val ins = script(Insert(Vector("a\n")), Insert(Vector("b\n"))) // test 15's repository
    assertEquals(ins.validate, Left(EditError.Adjacent("insert")))
    assert(EditError.Adjacent("insert").message.contains("adjacent insert")) // test 15 anchor
    assertEquals(script(Retain(1L), Retain(1L)).validate, Left(EditError.Adjacent("retain")))
    assertEquals(script(Delete(1L), Delete(1L)).validate, Left(EditError.Adjacent("delete")))
    assertEquals(script(Retain(1L), Insert(Vector("x\n")), Retain(1L)).validate, Right(()))
    assertEquals(script(Delete(1L), Insert(Vector("x\n")), Delete(1L)).validate, Right(()))
  }

  test("script must consume the complete old token sequence") {
    // {"retain": 1} against ["one\n", "two\n"] — test 15's underconsumption repository.
    assertEquals(
      script(Retain(1L)).applyTo(Vector("one\n", "two\n")),
      Left(EditError.Underconsumption)
    )
    assert(EditError.Underconsumption.message.contains("does not consume old content"))
    // No implicit trailing retain: a delete-only script over a prefix underconsumes too.
    assertEquals(script(Delete(1L)).applyTo(Vector("a\n", "b\n")), Left(EditError.Underconsumption))
  }

  test("script must not consume beyond old content") {
    // {"delete": 2} against ["one\n"] — test 23's overconsumption repository.
    assertEquals(script(Delete(2L)).applyTo(Vector("one\n")), Left(EditError.Overconsumption))
    assertEquals(script(Retain(2L)).applyTo(Vector("one\n")), Left(EditError.Overconsumption))
    assert(EditError.Overconsumption.message.endsWith("consumes beyond old content"))
  }

  test("empty script is valid only for empty to empty (empty-file creation)") {
    assertEquals(EditScript.empty.validate, Right(()))
    assertEquals(EditScript.empty.applyTo(Vector.empty), Right(Vector.empty[String]))
    assertEquals(EditScript.empty.applyTo(Vector("a\n")), Left(EditError.Underconsumption))
  }

  test("application must produce exactly a canonical token sequence") {
    // An LF-less token in non-final position makes the result non-canonical.
    assertEquals(
      script(Insert(Vector("a")), Retain(1L)).applyTo(Vector("b\n")),
      Left(EditError.NonCanonicalResult)
    )
    // The same token in final position is fine.
    assertEquals(
      script(Retain(1L), Insert(Vector("a"))).applyTo(Vector("b\n")),
      Right(Vector("b\n", "a"))
    )
  }

  test("golden application: test 05's pinned script") {
    assertEquals(
      script(Delete(1L), Retain(2L), Insert(Vector("a"))).applyTo(Vector("a\n", "b\n", "a\n")),
      Right(Vector("b\n", "a\n", "a"))
    )
  }

  test("retain copies, delete removes, insert adds") {
    assertEquals(
      script(Retain(1L), Insert(Vector("x\n")), Retain(1L)).applyTo(Vector("a\n", "b\n")),
      Right(Vector("a\n", "x\n", "b\n"))
    )
    assertEquals(script(Delete(2L)).applyTo(Vector("a\n", "b\n")), Right(Vector.empty[String]))
    assertEquals(
      script(Insert(Vector("a\n", "b\n"))).applyTo(Vector.empty),
      Right(Vector("a\n", "b\n"))
    )
  }

  test("applyTo checks structure before consumption") {
    assertEquals(script(Insert(Vector.empty)).applyTo(Vector.empty), Left(EditError.EmptyInsert))
  }

  test("leftmost defect decides the reported error") {
    assertEquals(
      script(Insert(Vector.empty), Retain(0L)).validate,
      Left(EditError.EmptyInsert)
    )
    assertEquals(
      script(Retain(0L), Insert(Vector.empty)).validate,
      Left(EditError.BadCount)
    )
  }

  test("codec-level error fragments are exposed for later wiring (test 23 anchors)") {
    assert(EditError.NotOneOperation.message.endsWith("must have one operation"))
  }

package snap.cli

import snap.core.SnapPath
import snap.core.Tree

import java.nio.charset.StandardCharsets

/** [[Delta]]/[[WorkingChanges]] (SPEC §2, §7.3/§7.5). Content-equality coverage for `Delta`
  * (CR12a): `IArray[Byte]` is an array at runtime, so the derived case-class equality would compare
  * by reference and two structurally identical deltas built from separately-allocated byte arrays
  * would compare unequal unless `Delta` overrides `equals`/`hashCode` (mirroring `Tree` and
  * `Change.Put`).
  */
class WorkingChangesSuite extends munit.FunSuite:

  private def path(value: String): SnapPath = SnapPath.parse(value).toOption.get
  private def bytes(text: String): IArray[Byte] =
    IArray.unsafeFromArray(text.getBytes(StandardCharsets.UTF_8))

  test("deltas with separately-allocated but equal content bytes are == (CR12a)") {
    val a = Delta(path("f"), Some(bytes("before")), Some(bytes("after")))
    val b = Delta(path("f"), Some(bytes("before")), Some(bytes("after")))
    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
  }

  test("deltas with equal-content None sides are == (CR12a)") {
    assertEquals(Delta(path("f"), None, Some(bytes("x"))), Delta(path("f"), None, Some(bytes("x"))))
    assertEquals(Delta(path("f"), Some(bytes("x")), None), Delta(path("f"), Some(bytes("x")), None))
  }

  test("deltas differing only in content bytes are not == (CR12a)") {
    assertNotEquals(
      Delta(path("f"), Some(bytes("x")), Some(bytes("y"))): Any,
      Delta(path("f"), Some(bytes("x")), Some(bytes("z"))): Any
    )
  }

  test("WorkingChanges.compute is deterministic and order-independent across permuted trees") {
    val current = Tree.from(Vector(path("a") -> bytes("a1"), path("b") -> bytes("b1")))
    val workingInOrder = Tree.from(Vector(path("a") -> bytes("a2"), path("c") -> bytes("c1")))
    val workingReversed = Tree.from(Vector(path("c") -> bytes("c1"), path("a") -> bytes("a2")))
    assertEquals(
      WorkingChanges.compute(current, workingInOrder),
      WorkingChanges.compute(current, workingReversed)
    )
  }

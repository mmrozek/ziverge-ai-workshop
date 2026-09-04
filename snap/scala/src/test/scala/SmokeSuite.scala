// Proves munit is wired into the test scope (T01 acceptance criteria).
// Real coverage starts in later tasks.
class SmokeSuite extends munit.FunSuite:
  test("munit test framework runs") {
    assertEquals(1 + 1, 2)
  }

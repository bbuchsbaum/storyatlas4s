package storyatlas4s.intaglio

import munit.FunSuite

class RecallWindowSuite extends FunSuite:
  test("checked windows are finite, nonnegative, ordered, and inclusive") {
    val window = RecallWindow.of(4.0, 8.0).fold(e => fail(e), identity)
    assertEquals(window.span, 4.0)
    assert(window.contains(4.0))
    assert(window.contains(8.0))
    assert(!window.contains(3.999))
    assert(!window.contains(8.001))
    assert(RecallWindow.of(-1.0, 2.0).isLeft)
    assert(RecallWindow.of(2.0, 2.0).isLeft)
    assert(RecallWindow.of(Double.NaN, 2.0).isLeft)
    assert(RecallWindow.of(0.0, Double.PositiveInfinity).isLeft)
  }

  test("bounded preserves a requested span while keeping it inside the total extent") {
    assertEquals(RecallWindow.bounded(-3.0, 4.0, 12.0), RecallWindow.of(0.0, 4.0).toOption)
    assertEquals(RecallWindow.bounded(11.0, 4.0, 12.0), RecallWindow.of(8.0, 12.0).toOption)
    assertEquals(RecallWindow.bounded(3.0, 4.0, 12.0), RecallWindow.of(3.0, 7.0).toOption)
    assertEquals(RecallWindow.bounded(0.0, 13.0, 12.0), None)
    assertEquals(RecallWindow.bounded(0.0, 4.0, 0.0), None)
  }

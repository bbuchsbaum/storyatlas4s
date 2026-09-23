package storyatlas4s.shell

import munit.FunSuite

class MatrixRangeSuite extends FunSuite:
  test("labels report the exact displayed range in reader terms") {
    assertEquals(MatrixRange.label("Rows", MatrixRange.span(4, 11, 84)), "Rows 5–12 of 84")
    assertEquals(MatrixRange.label("Columns", MatrixRange.span(0, 0, 1)), "Columns 1–1 of 1")
    assertEquals(MatrixRange.label("Rows", MatrixRange.span(0, 3, 0)), "No rows")
  }

  test("spans clamp to the inventory and never invert") {
    assertEquals(MatrixRange.span(-3, 200, 84), MatrixSpan(0, 83, 84))
    assertEquals(MatrixRange.span(90, 95, 84), MatrixSpan(83, 83, 84))
    assertEquals(MatrixRange.span(10, 5, 84), MatrixSpan(10, 10, 84))
  }

  test("paging moves by the displayed size and stops at both ends") {
    val middle = MatrixRange.span(10, 19, 84)
    assertEquals(MatrixRange.page(middle, forward = true), 20)
    assertEquals(MatrixRange.page(middle, forward = false), 0)
    assertEquals(MatrixRange.page(MatrixRange.span(80, 83, 84), forward = true), 83)
    assertEquals(MatrixRange.page(MatrixRange.span(0, 0, 4), forward = true), 1)
    assertEquals(MatrixRange.page(MatrixRange.span(0, 0, 0), forward = true), 0)
  }

  test("navigator blocks cover every index exactly once, in order") {
    for (total, max) <- Seq((4, 12), (84, 12), (524, 12), (1000, 24), (13, 12)) do
      val blocks = MatrixRange.blocks(total, max)
      assert(blocks.size <= max, s"$total/$max")
      assertEquals(blocks.flatMap(b => b.first to b.last), Vector.range(0, total), s"$total/$max")
      assert(blocks.forall(_.total == total))
  }

  test("small inventories get one block per index") {
    assertEquals(
      MatrixRange.blocks(4, 12).map(b => (b.first, b.last)),
      Vector(0 -> 0, 1 -> 1, 2 -> 2, 3 -> 3)
    )
    assertEquals(MatrixRange.blocks(0, 12), Vector.empty)
  }

package storyatlas4s.app

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*

class MatrixCellsSuite extends FunSuite:
  test("fill bins are fixed thresholds and a supplied zero is its own bin") {
    assertEquals(MatrixCells.bin(0.0), 0)
    assertEquals(MatrixCells.bin(0.1), 1)
    assertEquals(MatrixCells.bin(0.10001), 2)
    assertEquals(MatrixCells.bin(0.25), 2)
    assertEquals(MatrixCells.bin(0.45), 3)
    assertEquals(MatrixCells.bin(0.75), 4)
    assertEquals(MatrixCells.bin(0.82), 5)
    assertEquals(MatrixCells.bin(1.0), 5)
  }

  test("recall text joins exact fragments and names an empty horizon instead of inventing text") {
    assertEquals(
      MatrixCells.recallText(Vector.empty),
      "Not visible at the current reader horizon"
    )
  }

  test("a target header shows the local name and keeps the full key for inspection") {
    val ring = Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("bell:sit:ring")))
    assert(ring.key.endsWith("ring"), ring.key)
    assertEquals(MatrixCells.shortLabel(ring), "ring")
  }

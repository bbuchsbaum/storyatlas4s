package storyatlas4s.layout

import munit.FunSuite

/** The optional AWT measurer produces a well-formed table the pure paginator accepts. */
class AwtMeasurerSuite extends FunSuite:
  private def ok[A](either: Either[LayoutError, A]): A =
    either.fold(e => fail(s"unexpected failure: ${e.message}"), identity)

  test("advances are one per code unit, zero on newlines and low surrogates, positive on glyphs"):
    val measurer = ok(AwtMeasurer.of("Monospaced"))
    val style = ok(TextStyle.of("Monospaced", 12))
    val text = "abc\n🙂"
    val metrics = ok(measurer.measure(ok(TextRun.of(text)), style))
    assertEquals(metrics.advances.length, text.length)
    assertEquals(metrics.advances(3), 0)
    assertEquals(metrics.advances(5), 0)
    assert(metrics.advances(0) > 0)
    assertEquals(metrics.advances(0), metrics.advances(1))
    assertEquals(metrics.lineHeight, 1200 * 12)
    assertEquals(metrics.unitsPerPixel, 1000)
    assert(measurer.name.startsWith("awt/Monospaced/"))

  test("rejects an empty family and non-positive units"):
    assert(AwtMeasurer.of("  ").isLeft)
    assert(AwtMeasurer.of("Monospaced", unitsPerPixel = 0).isLeft)

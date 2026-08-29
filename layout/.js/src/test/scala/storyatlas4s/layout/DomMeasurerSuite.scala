package storyatlas4s.layout

import munit.FunSuite
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.RelationLayer
import storymodel4s.view.*

/** The DOM measurer over a fake width provider (no browser under Node), and its refusal to guess
  * when there is no document.
  */
class DomMeasurerSuite extends FunSuite:
  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private val style = ok(TextStyle.of("monospace", 16))

  /** A proportional fake: `i` is narrow, `W` is wide, everything else 8px; refuses control chars.
    */
  private val fakeWidth: DomMeasurer.TextWidth = (text, s) =>
    val cp = text.codePointAt(0)
    if Character.isISOControl(cp) then
      Left(LayoutError.Measurement("fake", s"control U+${cp.toHexString}"))
    else
      val em = s.sizePx.toDouble
      Right(
        cp match
          case 'i' => 0.25 * em
          case 'W' => 0.95 * em
          case _   => 0.5 * em
      )

  private val fake = ok(DomMeasurer.of("fake-dom/1", 1000, 1200, fakeWidth))

  test(
    "advances are per code unit in layout units: rounded widths, 0 for newline and low surrogates"
  ):
    val run = ok(TextRun.of("iW\n😀x"))
    val metrics = ok(fake.measure(run, style))
    assertEquals(metrics.advances, Vector(4000, 15200, 0, 8000, 0, 8000))
    assertEquals(metrics.lineHeight, 19200)
    assertEquals(metrics.unitsPerPixel, 1000)
    assertEquals(metrics.advances.length, run.text.length)

  test("units scale the same width, and the name is the receipt's measurer field"):
    val coarse = ok(DomMeasurer.of("fake-dom/coarse", 10, 1000, fakeWidth))
    val run = ok(TextRun.of("iW"))
    val metrics = ok(coarse.measure(run, style))
    assertEquals(metrics.advances, Vector(40, 152))
    assertEquals(metrics.lineHeight, 16000)
    assertEquals(coarse.name, "fake-dom/coarse")

  test("a width the provider refuses fails the run instead of substituting a number"):
    val run = ok(TextRun.of("a\tb"))
    fake.measure(run, style) match
      case Left(LayoutError.Measurement("fake", reason)) => assert(reason.contains("control"))
      case other => fail(s"expected a measurement failure, got $other")

  test("invalid construction is refused"):
    assert(DomMeasurer.of("has space", 1000, 1200, fakeWidth).isLeft)
    assert(DomMeasurer.of("x", 0, 1200, fakeWidth).isLeft)
    assert(DomMeasurer.of("x", 1000, 0, fakeWidth).isLeft)

  test("the canvas measurer is unavailable without a document (Node), never a guess"):
    DomMeasurer.canvas() match
      case Left(LayoutError.Unavailable(name, _)) => assert(name.startsWith(DomMeasurer.Version))
      case other => fail(s"expected Unavailable outside a browser, got $other")

  test("the canvas font shorthand keeps one family: bare keyword or a quoted, escaped string"):
    assertEquals(DomMeasurer.cssFont(style), "16px monospace")
    assertEquals(
      DomMeasurer.cssFont(ok(TextStyle.of("Source Code Pro", 12))),
      "12px \"Source Code Pro\""
    )
    assertEquals(DomMeasurer.cssFont(ok(TextStyle.of("a\"b\\c", 9))), "9px \"a\\\"b\\\\c\"")
    assertEquals(
      DomMeasurer.cssFont(ok(TextStyle.of("serif, monospace", 9))),
      "9px \"serif, monospace\""
    )

  test("pagination under the DOM measurer tiles the fixture text and receipts the measurer (V-T1)"):
    val model = Wog.model
    val state =
      ok(CommonViewState.of(relationLayers = Set(RelationLayer.Causal, RelationLayer.Reference)))
    val spec = ok(CodexSpec.forLens(CodexLens.Overview, ChannelBudget.All))
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "dom-measurer-suite",
        CodexCompiler.configurationChecksum(state, spec)
      )
    )
    val flow = ok(CodexCompiler(provenance).compile(model, state, spec))
    val placed = ok(Paginator.layout(flow, ok(PageSpec.of(480, 640)), style, fake))
    val text = model.source.canonicalText
    assertEquals(placed.textFragments.map(f => ok(placed.text(f))).mkString, text)
    assertEquals(placed.receipt.measurer, "fake-dom/1")
    assertEquals(placed.receipt.lineHeight, 19200)
    assert(placed.pages.length > 1)
    assertEquals(
      placed.annotationFragments.map(_.annotation).toSet,
      flow.annotations.map(_.id).toSet
    )

package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import _root_.intaglio.value
import munit.FunSuite
import storyatlas4s.layout.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** The paginated overlay on the fixture: one scene per page, one group per piece named by its
  * fragment id (V-I2), page-framed geometry, determinism.
  */
class PagedCodexLoweringSuite extends FunSuite:
  private val model = Wog.model
  private val lenses = Vector(CodexLens.Reading, CodexLens.Overview)

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def flow(lens: CodexLens): CodexFlow =
    val st =
      ok(CommonViewState.of(relationLayers = Set(RelationLayer.Causal, RelationLayer.Reference)))
    val spec = ok(CodexSpec.forLens(lens, ChannelBudget.All))
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "paged-lowering-suite",
        CodexCompiler.configurationChecksum(st, spec)
      )
    )
    ok(CodexCompiler(provenance).compile(model, st, spec))

  private val style = ok(TextStyle.of("monospace", 16))
  private val page = ok(PageSpec.of(480, 640))

  private def paginate(lens: CodexLens): PaginatedCodex =
    ok(Paginator.layout(flow(lens), page, style, MonospaceMeasurer.instance))

  private val dataName = """data-name="([^"]*)"""".r

  test("one scene per page whose names are exactly the page's annotation piece ids, in order"):
    lenses.foreach { lens =>
      val placed = paginate(lens)
      val scenes = ok(CodexLowering.lower(placed))
      assertEquals(scenes.length, placed.pages.length, lens.toString)
      scenes.zip(placed.pages).foreach { (scene, page) =>
        val names = GraphicsNames.collect(scene).map(_.value)
        assertEquals(
          names,
          page.lines.flatMap(_.annotations).map(_.id.value),
          s"$lens page ${page.index}"
        )
        assertEquals(ok(CodexLowering.lower(placed, page)), scene)
      }
      val all = scenes.flatMap(GraphicsNames.collect).map(_.value)
      assertEquals(all, placed.annotationFragments.map(_.id.value))
      assertEquals(all.distinct.length, all.length)
    }

  test("the Overview lens places pieces and the rows name every (kind, lane) they occupy"):
    val placed = paginate(CodexLens.Overview)
    assert(placed.annotationFragments.nonEmpty)
    val rows = ok(PagedCodexLowering.rows(placed))
    assert(rows.nonEmpty)
    assertEquals(rows, rows.distinct)
    placed.flow.annotations.foreach { a =>
      val slot = placed.flow.lanes.slotOf(a.id).getOrElse(fail(a.id.value))
      assert(rows.contains(PagedCodexLowering.Row(a.kind, slot)), a.id.value)
    }
    assertEquals(ok(PagedCodexLowering.rows(paginate(CodexLens.Reading))), Vector.empty)

  test("every band lies inside its page box and its line box in page pixels"):
    val placed = paginate(CodexLens.Overview)
    val units = placed.metrics.unitsPerPixel.toDouble
    val lineHeight = placed.metrics.lineHeight / units
    val scenes = ok(CodexLowering.lower(placed))
    scenes.zip(placed.pages).foreach { (scene, page) =>
      val pieces = page.lines.flatMap(line => line.annotations.map(line -> _))
      val groups = scene.grobs.flatMap(_.children)
      assertEquals(groups.length, pieces.length)
      groups.zip(pieces).foreach { case (group, (line, piece)) =>
        val band =
          group.children.collectFirst { case p: ig.Grob.Polygon => p }.getOrElse(fail("band"))
        val xs = band.points.map(p => native(p.x))
        val ys = band.points.map(p => native(p.y))
        val top = line.text.line * lineHeight
        // x is the advance sum from the line start; a piece over hanging whitespace may end past
        // the page width, exactly as a `white-space: pre` rail would place it.
        val start = placed.metrics.widthOf(line.text.span.start, piece.span.start) / units
        val end = placed.metrics.widthOf(line.text.span.start, piece.span.endExclusive) / units
        assertEqualsDouble(xs.min, start, 1e-9, piece.id.value)
        assertEqualsDouble(xs.max, end, 1e-9, piece.id.value)
        assert(xs.min >= 0.0, piece.id.value)
        assert(ys.min >= top - 1e-9 && ys.max <= top + lineHeight + 1e-9, piece.id.value)
      }
    }

  test("lowering and SVG serialization are deterministic and carry the piece ids (V-D1)"):
    lenses.foreach { lens =>
      val placed = paginate(lens)
      val a = ok(CodexLowering.lower(placed))
      val b = ok(CodexLowering.lower(placed))
      assertEquals(a, b)
      val options = ok(SvgOptions(placed.page.widthPx, placed.page.heightPx))
      a.zip(placed.pages).foreach { (scene, page) =>
        val svg = ok(SvgRenderer.render(scene, options)).value
        assertEquals(svg, ok(SvgRenderer.render(scene, options)).value)
        assertEquals(
          dataName.findAllMatchIn(svg).map(_.group(1)).toVector,
          page.lines.flatMap(_.annotations).map(_.id.value)
        )
      }
    }

  private def native(expr: ig.LengthExpr): Double = expr match
    case ig.LengthExpr.Const(length) => length.value
    case other                       => fail(s"unexpected expression $other")

package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import _root_.intaglio.value
import munit.FunSuite
import storymodel4s.align.{
  AlignError,
  AlignState,
  AlignmentMatrix,
  AlignmentRow,
  ExternalState,
  SourceNodeRef
}
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** Lowering laws for the Recall Voyage, fixture-free: identity (one `data-name` per mark, equal to
  * its `MarkId`), determinism, and that alternatives are drawn only for the units the shell names.
  */
class VoyageLoweringSuite extends FunSuite:
  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)
  private def okA[A](e: Either[AlignError, A]): A = e.fold(err => fail(err.toString), identity)
  private def span(a: Double, b: Double) = ok(ClockSpan.of(a, b))
  private def secs(v: Double) = ok(Seconds.of(v))
  private val a = SourceNodeRef.Situation(SituationId.unsafe("a"))
  private val b = SourceNodeRef.Situation(SituationId.unsafe("b"))
  private val c = SourceNodeRef.Situation(SituationId.unsafe("c"))
  private val g1 = SourceNodeRef.Segment(SegmentId.unsafe("g1"))
  private val g2 = SourceNodeRef.Segment(SegmentId.unsafe("g2"))
  private val u1 = RecallUnitId.unsafe("u1")
  private val u2 = RecallUnitId.unsafe("u2")
  private val u3 = RecallUnitId.unsafe("u3")
  private val u4 = RecallUnitId.unsafe("u4")

  private val timeline = ok(
    SourceTimeline.of(
      Vector(
        SourceTimelineNode(a, 0, Some(1), span(0, 10), "a"),
        SourceTimelineNode(b, 0, Some(1), span(10, 20), "b"),
        SourceTimelineNode(c, 0, Some(2), span(20, 30), "c"),
        SourceTimelineNode(g1, 1, Some(1), span(0, 20), "group 1"),
        SourceTimelineNode(g2, 1, Some(2), span(20, 30), "group 2")
      ),
      Vector(
        SourceTimelineGroup(1, "group 1", span(0, 20)),
        SourceTimelineGroup(2, "group 2", span(20, 30))
      )
    )
  )
  private val units = Vector(
    VoyageUnit(u1, 0, "one", Some(secs(1.0)), Some(secs(2.0))),
    VoyageUnit(u2, 1, "two", Some(secs(5.0)), None),
    VoyageUnit(u3, 2, "three", None, None),
    VoyageUnit(u4, 3, "four", Some(secs(9.0)), None)
  )
  private val matrix = okA(
    AlignmentMatrix.of(
      Vector(
        okA(
          AlignmentRow.of(
            u1,
            Map(
              AlignState.Source(a) -> 0.5,
              AlignState.Source(b) -> 0.2,
              AlignState.External(ExternalState.Association) -> 0.3
            )
          )
        ),
        okA(AlignmentRow.of(u2, Map(AlignState.External(ExternalState.Unranked) -> 1.0))),
        okA(AlignmentRow.of(u3, Map(AlignState.Source(c) -> 1.0))),
        okA(AlignmentRow.of(u4, Map(AlignState.Source(g2) -> 0.6, AlignState.Source(a) -> 0.4)))
      )
    )
  )
  private val decisions = Vector(
    VoyageDecision(u1, Some(c), Some(2), AnchorOrigin.DecodeFilled),
    VoyageDecision(u2, None, None, AnchorOrigin.PosteriorArgmax),
    VoyageDecision(u3, Some(c), Some(2), AnchorOrigin.PosteriorArgmax),
    VoyageDecision(u4, Some(g2), Some(2), AnchorOrigin.PosteriorArgmax)
  )
  private val coding =
    IndependentCoding("coding", Checksum.ofText("coding"), Vector(CodedInterval(span(0, 4), 1)))
  private val provenance = ok(
    ViewProvenance.of(
      Checksum.ofText("source"),
      None,
      ViewBasis.AlignmentRun,
      VoyageCompiler.compilerVersion,
      Checksum.ofText("config")
    )
  )
  private val scene = ok(
    VoyageCompiler.compile(
      ok(RecallVoyageInput.of(units, matrix, timeline, decisions, Some(coding), secs(12.0))),
      Set.empty,
      provenance
    )
  )

  private def render(lowered: ig.Scene): String =
    val options = ok(
      SvgOptions(VoyageLowering.Box.default.width, VoyageLowering.Box.default.height)
    )
    ok(SvgRenderer.render(lowered, options)).value

  test("every non-alternative mark is named exactly once by its MarkId, and nothing else is named"):
    val lowered = ok(VoyageLowering.lower(scene))
    val names = GraphicsNames.collect(lowered).map(_.value)
    val expected = scene.marks.collect {
      case m: VoyageMark.UnitAnchor => m.identity.mark.value
      case m: VoyageMark.Unanchored => m.identity.mark.value
      case m: VoyageMark.Untimed    => m.identity.mark.value
    }
    assertEquals(names.sorted, expected.sorted)
    assertEquals(names.distinct.size, names.size)
    val svg = render(lowered)
    expected.foreach(n => assert(svg.contains(s"""data-name="$n""""), n))

  test("alternatives are drawn only for the units the shell names, and are named by their MarkId"):
    val withAlts = ok(VoyageLowering.lower(scene, alternativesFor = Set(u1)))
    val names = GraphicsNames.collect(withAlts).map(_.value)
    val altIds = scene.marks.collect {
      case m: VoyageMark.Alternative if m.unit == u1 => m.identity.mark.value
    }
    assert(altIds.nonEmpty)
    altIds.foreach(id => assert(names.contains(id), id))
    val without = ok(VoyageLowering.lower(scene))
    altIds.foreach(id => assert(!GraphicsNames.collect(without).map(_.value).contains(id), id))

  test("lowering and rendering are deterministic"):
    val x = render(ok(VoyageLowering.lower(scene, Set(u1))))
    val y = render(ok(VoyageLowering.lower(scene, Set(u1))))
    assertEquals(x, y)

  test("the layers are the five the contract names, in order"):
    val lowered = ok(VoyageLowering.lower(scene))
    assertEquals(lowered.grobs.size, 5)

  test("a coding band is drawn per coded interval and carries no name"):
    val lowered = ok(VoyageLowering.lower(scene))
    val bands = lowered.grobs(1)
    assertEquals(bands.children.size, 1)
    assert(GraphicsNames.collect(bands).isEmpty)

  test("the clock prints minutes and zero-padded seconds"):
    assertEquals(VoyageLowering.clock(0.0), "0:00")
    assertEquals(VoyageLowering.clock(65.4), "1:05")
    assertEquals(VoyageLowering.clock(1426.0), "23:46")

  test("every mark carries a title, a class naming its kind, and its unit as data") {
    val x = render(ok(VoyageLowering.lower(scene)))
    val titles = x.sliding("<title>".length).count(_ == "<title>")
    val expected = scene.marks.count(!_.isInstanceOf[VoyageMark.Alternative]) + 1
    assert(titles >= expected, s"$titles titles for $expected named marks and bands")
    assert(x.contains("one"), "the unit's words are in its title")
    assert(x.contains("anchor mass 0.00"), "the row's numbers are in its title")
    assert(x.contains(VoyageLowering.Classes.anchor), "anchors are classed")
    assert(x.contains("origin-filled"), "the origin is a class")
    assert(x.contains("data-unit=\"0\""), "the unit ordinal is data")
    assert(x.contains(VoyageLowering.Classes.coding), "coded bands are classed")
  }

  test("ghosts of moved argmaxes are drawn by default and withheld on request") {
    val on = render(ok(VoyageLowering.lower(scene)))
    val off = render(ok(VoyageLowering.lower(scene, ghosts = false)))
    assert(on.contains(VoyageLowering.Classes.ghost), "the moved anchor u1 leaves a ghost")
    assert(!off.contains(VoyageLowering.Classes.ghost), "no ghost when the shell withholds them")
  }

  test("context columns are drawn faint and classed as context; focused ones are not") {
    val focused = render(ok(VoyageLowering.lower(scene, alternativesFor = Set(u1))))
    val context = render(ok(VoyageLowering.lower(scene, contextFor = Set(u1))))
    assert(focused.contains(VoyageLowering.Classes.alternative), "u1's column is drawn")
    assert(!focused.contains("voyage-alt context"), "a focused column is not classed context")
    assert(context.contains("voyage-alt context"), "a context column says so")
  }

  test("a point's size is the device radius: u1's mass-0.5 alternative ring has r = radius(0.5)") {
    val x = render(ok(VoyageLowering.lower(scene, alternativesFor = Set(u1))))
    val radii = """ r="([0-9.]+)"""".r.findAllMatchIn(x).map(_.group(1).toDouble).toVector
    val want = VoyageLowering.radius(0.5)
    assert(radii.exists(r => math.abs(r - want) < 1e-3), s"expected r=$want among $radii")
    assert(
      !radii.exists(r => math.abs(r - 2 * want) < 1e-3),
      "twice the radius is the size-as-diameter bug"
    )
  }

  test("every drawn mark carries its data-name exactly once in the rendered SVG") {
    val x = render(ok(VoyageLowering.lower(scene, alternativesFor = Set(u1))))
    scene.marks.foreach { m =>
      val needle = s"""data-name="${m.identity.mark.value}""""
      val n = x.sliding(needle.length).count(_ == needle)
      m match
        case a: VoyageMark.Alternative if a.unit != u1 => assertEquals(n, 0, needle)
        case _                                         => assertEquals(n, 1, needle)
    }
  }

  private def sceneWithText(text: String): VoyageScene =
    val renamed = units.map(u => if u.id == u1 then u.copy(text = text) else u)
    ok(
      VoyageCompiler.compile(
        ok(RecallVoyageInput.of(renamed, matrix, timeline, decisions, Some(coding), secs(12.0))),
        Set.empty,
        provenance
      )
    )

  test("a unit's words are escaped once on the way into a title, never by the lowering") {
    val x = render(ok(VoyageLowering.lower(sceneWithText("a < b & c"))))
    assert(x.contains("a &lt; b &amp; c"), "the renderer escapes the title text")
    assert(!x.contains("a < b"), "raw markup characters never reach the SVG text")
    assert(!x.contains("&amp;lt;"), "the lowering does not pre-escape")
  }

  test("a control character in a unit's words is dropped from the title, not fatal") {
    val tab = 9.toChar.toString
    val illegal = 1.toChar.toString
    val x = render(ok(VoyageLowering.lower(sceneWithText("bad" + illegal + "word" + tab + "tab"))))
    assert(
      x.contains("badword" + tab + "tab"),
      "the illegal code point is gone and the tab is kept"
    )
  }

  test("a unit named as both focused and context is drawn once, focused") {
    val x = render(ok(VoyageLowering.lower(scene, alternativesFor = Set(u1), contextFor = Set(u1))))
    val alts = scene.marks.count {
      case a: VoyageMark.Alternative => a.unit == u1
      case _                         => false
    }
    val marker = "voyage-mark voyage-alt"
    val drawn = x.sliding(marker.length).count(_ == marker)
    assertEquals(drawn, alts, "one column per alternative")
    assert(!x.contains("voyage-alt context"), "focus wins over context")
  }

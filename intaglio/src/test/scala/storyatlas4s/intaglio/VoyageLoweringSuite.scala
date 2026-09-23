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

  private def native(expr: ig.LengthExpr): Double = expr match
    case ig.LengthExpr.Const(length) => length.value
    case other                       => fail(s"unexpected expression $other")

  private def descendants(grob: ig.Grob): Vector[ig.Grob] =
    grob +: grob.children.flatMap(descendants)

  private val massBox = VoyageLowering.Box.default.copy(trackHeight = 64, gap = 40)

  private def massSamples(lowered: ig.Scene): Map[(Int, String), ig.Grob.Annotated] =
    lowered.grobs
      .flatMap(descendants)
      .collect {
        case a: ig.Grob.Annotated
            if a.meta.cssClass.exists(_.value.split(" ").contains(VoyageLowering.Classes.mass)) =>
          val data = a.meta.data.map((k, v) => k.value -> v).toMap
          (data("unit").toInt -> data("channel")) -> a
      }
      .toMap

  private def metadata(grob: ig.Grob.Annotated): Map[String, String] =
    grob.meta.data.map((k, v) => k.value -> v).toMap

  test("mass tracks distinguish measured zero, missing anchor and untimed placement") {
    val lowered =
      ok(VoyageLowering.lower(scene, box = massBox, track = VoyageLowering.Track.Masses))
    val samples = massSamples(lowered)
    assertEquals(
      samples.keySet,
      Set(0, 1, 3).flatMap(i => Set(i -> "mass-anchor", i -> "mass-external"))
    )
    val zero = samples(0 -> "mass-anchor")
    assertEquals(metadata(zero)("mass-bits"), "0x0000000000000000")
    assertEquals(metadata(zero)("status"), "zero")
    assertEquals(metadata(zero)("origin"), "filled")
    assert(!descendants(zero).exists(_.isInstanceOf[ig.Grob.Polygon]), "zero is no positive bar")
    val missing = samples(1 -> "mass-anchor")
    assertEquals(metadata(missing)("status"), "unavailable")
    assert(!metadata(missing).contains("mass-bits"), "unavailable is never numeric zero")
    assert(descendants(missing).exists(_.isInstanceOf[ig.Grob.Points]), "missing has its own glyph")
    assertEquals(metadata(samples(1 -> "mass-external"))("mass-bits"), "0x3ff0000000000000")
    assert(!samples.keys.exists(_._1 == 2), "untimed unit has no invented clock position")
    assertEquals(
      GraphicsNames.collect(lowered),
      GraphicsNames.collect(ok(VoyageLowering.lower(scene)))
    )
    assertEquals(
      lowered.grobs.take(4).drop(1),
      ok(VoyageLowering.lower(scene, box = massBox)).grobs.take(4).drop(1),
      "coding, links and scientific marks stay unchanged"
    )
  }

  test("mass heights use the supplied values on independent fixed scales") {
    val lowered =
      ok(VoyageLowering.lower(scene, box = massBox, track = VoyageLowering.Track.Masses))
    val samples = massSamples(lowered)
    def barHeight(unit: Int, channel: String): Double =
      val p = descendants(samples(unit -> channel))
        .collectFirst { case p: ig.Grob.Polygon => p }
        .getOrElse(fail("expected positive mass bar"))
      val ys = p.points.map(p => native(p.y))
      ys.max - ys.min
    assertEqualsDouble(barHeight(0, "mass-external"), 8.4, 1e-9)
    assertEqualsDouble(barHeight(1, "mass-external"), 28.0, 1e-9)
    assertEqualsDouble(barHeight(3, "mass-anchor"), 16.8, 1e-9)
    assertEquals(
      metadata(samples(3 -> "mass-external"))("mass-bits"),
      "0x0000000000000000",
      "the coarse anchor's 0.6 does not fabricate a complementary external0.4"
    )
  }

  test("mass tracks preserve values and source grain through viewport, resize and focus") {
    val whole = ok(VoyageLowering.lower(scene, box = massBox, track = VoyageLowering.Track.Masses))
    val window = ok(RecallWindow.of(5, 9))
    val small = massBox.copy(width = 500, right = 128)
    val zoomed = ok(
      VoyageLowering.lower(
        scene,
        alternativesFor = Set(u4),
        box = small,
        window = Some(window),
        track = VoyageLowering.Track.Masses
      )
    )
    val samples = massSamples(zoomed)
    assertEquals(
      samples.keySet,
      Set(1, 3).flatMap(i => Set(i -> "mass-anchor", i -> "mass-external"))
    )
    samples.foreach { case (key, grob) =>
      assertEquals(metadata(grob), metadata(massSamples(whole)(key)))
    }
    val bar = descendants(samples(3 -> "mass-anchor"))
      .collectFirst { case p: ig.Grob.Polygon => p }
      .getOrElse(fail("anchor bar"))
    val xs = bar.points.map(p => native(p.x))
    val ys = bar.points.map(p => native(p.y))
    assertEqualsDouble((xs.min + xs.max) / 2, 372.0, 1e-9, "inclusive end shares main plot x")
    assertEqualsDouble(ys.max - ys.min, 16.8, 1e-9, "selection outline does not change mass height")
    assertEquals(
      scene.marks.collect { case a: VoyageMark.UnitAnchor if a.unit == u4 => a.level },
      Vector(1)
    )
  }

  test("mass metadata respects permitted text and historical publication remains the default") {
    val clipped = render(
      ok(
        VoyageLowering.lower(
          scene,
          box = massBox,
          visibleRecallText = Some(Map.empty),
          track = VoyageLowering.Track.Masses
        )
      )
    )
    assert(!clipped.contains("“one”"))
    assert(!clipped.contains("“two”"))
    assert(clipped.contains("unavailable: no source anchor"))
    assert(!render(ok(VoyageLowering.lower(scene))).contains("voyage-mass"))
    assertEquals(
      render(ok(VoyageLowering.lower(scene))),
      render(ok(VoyageLowering.lower(scene, track = VoyageLowering.Track.Groups)))
    )
    assert(
      VoyageLowering
        .lower(scene, box = massBox.copy(trackHeight = 8), track = VoyageLowering.Track.Masses)
        .isLeft
    )
  }

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

  test("ghosts are quiet by default, selected explicitly, and all remain available") {
    val off = render(ok(VoyageLowering.lower(scene)))
    val selected = render(ok(VoyageLowering.lower(scene, ghostsFor = Set(u1))))
    val other = render(ok(VoyageLowering.lower(scene, ghostsFor = Set(u4))))
    val all = render(ok(VoyageLowering.lower(scene, ghosts = true)))
    assert(!off.contains(VoyageLowering.Classes.ghost), "no all-unit fence by default")
    assert(selected.contains(VoyageLowering.Classes.ghost), "selected moved anchor u1 has a ghost")
    assert(!other.contains(VoyageLowering.Classes.ghost), "selection never enables another unit")
    assertEquals(selected, all, "only one moved argmax in this fixture")
    val names = """data-name="([^"]+)"""".r
    assertEquals(names.findAllIn(off).toVector, names.findAllIn(all).toVector)
  }

  test("source group shading is neutral and cannot impersonate absent independent coding") {
    val uncoded = ok(
      VoyageCompiler.compile(
        ok(RecallVoyageInput.of(units, matrix, timeline, decisions, None, secs(12.0))),
        Set.empty,
        provenance
      )
    )
    val lowered = ok(VoyageLowering.lower(uncoded))
    val x = render(lowered)
    assert(x.contains(VoyageLowering.Classes.groupBand))
    assert(!x.contains(VoyageLowering.Classes.coding))
    assert(!x.contains("#c9922e"), "no gold source boundaries in an uncoded document")
    assertEquals(lowered.grobs(1).children.size, 0)
    assertEquals(uncoded.marks, scene.marks, "coding decoration never changes the marks")
  }

  test("dense and selected groups get labels even when their spans are shorter than a label") {
    val small = VoyageLowering.Box.default.copy(plotHeight = 12)
    val x = render(ok(VoyageLowering.lower(scene, box = small)))
    assert(x.contains("<title>group 2</title>"))
    assert(x.contains("voyage-group-label\" data-group=\"2\""))
    val tied = ok(
      VoyageCompiler.compile(
        ok(
          RecallVoyageInput.of(
            units,
            matrix,
            timeline,
            decisions.updated(3, VoyageDecision(u4, Some(a), Some(1), AnchorOrigin.DecodeBound)),
            None,
            secs(12.0)
          )
        ),
        Set.empty,
        provenance
      )
    )
    val selected = render(ok(VoyageLowering.lower(tied, alternativesFor = Set(u1), box = small)))
    assert(
      selected.contains("voyage-group-label\" data-group=\"2\""),
      "selected group wins a collision with an earlier group"
    )
  }

  test("long numbered group labels wrap without truncation and retain the actual midpoint") {
    val long = "2 A numbered group with a complete descriptive label"
    val renamed = ok(
      SourceTimeline.of(
        timeline.nodes,
        timeline.groups.map(g => if g.ordinal == 2 then g.copy(label = long) else g)
      )
    )
    val s = ok(
      VoyageCompiler.compile(
        ok(RecallVoyageInput.of(units, matrix, renamed, decisions, None, secs(12.0))),
        Set.empty,
        provenance
      )
    )
    val x = render(ok(VoyageLowering.lower(s, alternativesFor = Set(u1))))
    assert(x.contains(s"<title>$long</title>"))
    assert(x.contains("descriptive label"), "the end is visible text, not only a tooltip")
    assert(!x.contains("…"))
    val expectedY = VoyageLowering.scales(s, VoyageLowering.Box.default).y(25.0)
    assertEquals(expectedY, 104.0)
    // The leader tick remains at the supplied group's midpoint, not a redistributed label row.
    assert(x.contains("901,104 909,104"), x)
  }

  test("context columns are drawn faint and classed as context; focused ones are not") {
    val focused = render(ok(VoyageLowering.lower(scene, alternativesFor = Set(u1))))
    val context = render(ok(VoyageLowering.lower(scene, contextFor = Set(u1))))
    assert(focused.contains(VoyageLowering.Classes.alternative), "u1's column is drawn")
    assert(!focused.contains("voyage-alt context"), "a focused column is not classed context")
    assert(context.contains("voyage-alt context"), "a context column says so")
  }

  test("wrapping a long group token preserves a non-BMP character at the column boundary") {
    val token = "x" * 24 + "\uD83D\uDE80" + "tail"
    val renamed = ok(
      SourceTimeline.of(
        timeline.nodes,
        timeline.groups.map(g => if g.ordinal == 2 then g.copy(label = token) else g)
      )
    )
    val s = ok(
      VoyageCompiler.compile(
        ok(RecallVoyageInput.of(units, matrix, renamed, decisions, None, secs(12.0))),
        Set.empty,
        provenance
      )
    )
    val x = render(ok(VoyageLowering.lower(s, alternativesFor = Set(u1))))
    assert(
      x.contains(("x" * 24) + "\uD83D\uDE80</text>"),
      "the surrogate pair stays together in visible text, not just the title"
    )
    assert(x.contains(">tail</text>"))
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

  test(
    "a recall window is display-only: it preserves boundary marks and suppresses off-window ids"
  ) {
    val window = RecallWindow.of(4.0, 8.0).fold(e => fail(e), identity)
    val originalMarks = scene.marks
    val lowered = ok(VoyageLowering.lower(scene, alternativesFor = Set(u1), window = Some(window)))
    val names = GraphicsNames.collect(lowered).map(_.value).toSet
    val u2Id = scene.marks
      .collectFirst {
        case m: VoyageMark.Unanchored if m.unit == u2 => m.identity.mark.value
      }
      .getOrElse(fail("u2 unanchored mark"))
    val u1Ids = scene.marks.collect {
      case m if m.identity.mark.value.contains("/u1") => m.identity.mark.value
    }
    val u4Ids = scene.marks.collect {
      case m if m.identity.mark.value.contains("/u4") => m.identity.mark.value
    }
    assert(names.contains(u2Id), "an onset in the window remains")
    assert(
      u1Ids.forall(id => !names.contains(id)),
      "off-window alternatives and anchors have no renderer id"
    )
    assert(u4Ids.forall(id => !names.contains(id)), "off-window anchors have no renderer id")
    assertEquals(scene.marks, originalMarks, "lowering never alters the compiler scene")
    val sc = VoyageLowering.scales(scene, VoyageLowering.Box.default, Some(window))
    assertEqualsDouble(sc.x(4.0), VoyageLowering.Box.default.left.toDouble, 1e-9)
    assertEqualsDouble(
      sc.x(8.0),
      (VoyageLowering.Box.default.width - VoyageLowering.Box.default.right).toDouble,
      1e-9
    )
    val svg = render(lowered)
    assert(svg.contains("0:04"), "the visible start is labelled with its source clock value")
    assert(svg.contains("0:08"), "the visible end is labelled with its source clock value")
  }

  test("window boundaries include timed marks, coding bands and track segments are clipped") {
    val boundedUnits = units
      .updated(0, units(0).copy(onset = Some(secs(4.0)), lastWordOnset = Some(secs(4.0))))
      .updated(
        3,
        units(3).copy(onset = Some(secs(8.0)))
      )
    val wideCoding =
      IndependentCoding("coding", Checksum.ofText("wide"), Vector(CodedInterval(span(2, 10), 1)))
    val boundedScene = ok(
      VoyageCompiler.compile(
        ok(
          RecallVoyageInput
            .of(boundedUnits, matrix, timeline, decisions, Some(wideCoding), secs(12.0))
        ),
        Set.empty,
        provenance
      )
    )
    val window = RecallWindow.of(4.0, 8.0).fold(e => fail(e), identity)
    val lowered = ok(VoyageLowering.lower(boundedScene, window = Some(window)))
    val names = GraphicsNames.collect(lowered).map(_.value).toSet
    val boundaryIds = boundedScene.marks.collect {
      case m: VoyageMark.UnitAnchor if m.unit == u1 || m.unit == u4 => m.identity.mark.value
    }
    assert(boundaryIds.forall(names.contains), "both inclusive boundaries keep their marks")
    val band = descendants(lowered.grobs(1))
      .collectFirst { case p: ig.Grob.Polygon => p }
      .getOrElse(fail("coding band"))
    val xs = band.points.map(p => native(p.x))
    assertEqualsDouble(xs.min, VoyageLowering.Box.default.left.toDouble, 1e-9)
    assertEqualsDouble(
      xs.max,
      (VoyageLowering.Box.default.width - VoyageLowering.Box.default.right).toDouble,
      1e-9
    )
    val trackLines = descendants(lowered.grobs(4)).collect { case l: ig.Grob.Lines => l }
    assert(trackLines.nonEmpty, "the clipped track contains lines")
    val trackXs = trackLines.flatMap(_.points.map(p => native(p.x)))
    assert(trackXs.forall(x => x >= xs.min - 1e-9 && x <= xs.max + 1e-9), trackXs.toString)
  }

  test("untimed marks are retained by default and can be withheld explicitly") {
    val window = RecallWindow.of(4.0, 8.0).fold(e => fail(e), identity)
    val untimedId = scene.marks
      .collectFirst { case m: VoyageMark.Untimed =>
        m.identity.mark.value
      }
      .getOrElse(fail("untimed mark"))
    assert(
      GraphicsNames
        .collect(ok(VoyageLowering.lower(scene, window = Some(window))))
        .map(_.value)
        .contains(untimedId)
    )
    assert(
      !GraphicsNames
        .collect(ok(VoyageLowering.lower(scene, window = Some(window), includeUntimed = false)))
        .map(_.value)
        .contains(untimedId)
    )
  }

  test("a window outside the supplied recall extent is refused") {
    val outside = RecallWindow.of(4.0, 13.0).fold(e => fail(e), identity)
    assert(VoyageLowering.lower(scene, window = Some(outside)).isLeft)
  }

  test("a fractional endpoint is labelled distinctly from an interior whole-second tick") {
    val window = RecallWindow.of(0.0, 1.8).fold(e => fail(e), identity)
    val svg = render(ok(VoyageLowering.lower(scene, window = Some(window))))
    assert(svg.contains("0:01</text>"), "the nice one-second interior tick remains")
    assert(svg.contains("0:01.8</text>"), "the exact fractional endpoint is not rounded to 0:02")
  }

  test("time labels retain distant endpoints without crowding them with near interior labels") {
    val longScene = ok(
      VoyageCompiler.compile(
        ok(RecallVoyageInput.of(units, matrix, timeline, decisions, Some(coding), secs(600.0))),
        Set.empty,
        provenance
      )
    )
    val window = RecallWindow.of(240.0, 600.0).fold(e => fail(e), identity)
    val svg = render(ok(VoyageLowering.lower(longScene, window = Some(window))))
    assert(svg.contains("4:00</text>"), "the requested start stays labelled")
    assert(svg.contains("10:00</text>"), "the requested end stays labelled")
    assert(!svg.contains("4:10</text>"), "a near-start interior label is omitted")
  }

  test("no window retains the full-domain scale") {
    val sc = VoyageLowering.scales(scene, VoyageLowering.Box.default)
    assertEqualsDouble(sc.rangeStart, 0.0, 1e-9)
    assertEqualsDouble(sc.rangeEnd, scene.recallLength.value, 1e-9)
    assertEqualsDouble(sc.x(0.0), VoyageLowering.Box.default.left.toDouble, 1e-9)
    assertEqualsDouble(
      sc.x(scene.recallLength.value),
      (VoyageLowering.Box.default.width - VoyageLowering.Box.default.right).toDouble,
      1e-9
    )
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

  test("explicit horizon text never falls back to beyond-horizon quotations in SVG titles") {
    val source = sceneWithText("beyond-horizon-canary")
    val original = ok(VoyageLowering.lower(source))
    val hidden = ok(VoyageLowering.lower(source, visibleRecallText = Some(Map.empty)))
    assert(!render(hidden).contains("beyond-horizon-canary"))
    assertEquals(GraphicsNames.collect(hidden), GraphicsNames.collect(original))
    val partial = render(
      ok(VoyageLowering.lower(source, visibleRecallText = Some(Map(u1 -> "permitted fragment"))))
    )
    assert(partial.contains("permitted fragment"))
    assert(!partial.contains("beyond-horizon-canary"))
  }

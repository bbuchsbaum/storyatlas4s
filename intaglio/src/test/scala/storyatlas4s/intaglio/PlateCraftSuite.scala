package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.value
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** Courts over the composition itself, not only over what is drawn.
  *
  * The old plate was structurally correct and visually a failure: four labels in one eight-pixel
  * column, every mark the same black, a margin rail of sixty-six captions printed over each other.
  * None of that could turn a law red, so none of it was caught. These are the laws it would have
  * turned red, stated so they fail: contrast is computed rather than eyeballed, the label budget is
  * measured rather than hoped for, and a withheld label is proved not to be a dropped mark.
  */
class PlateCraftSuite extends FunSuite:
  private val model = Wog.model
  private val length = model.source.canonicalText.length
  private val box = PlateBox.default

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private val state: CommonViewState = ok(CommonViewState.of())

  private def scene(
      level: NarrativeLevel = NarrativeLevel.Scene,
      surface: SurfaceDetail = SurfaceDetail.Hidden
  ): NarrativeScene =
    val spec = AtlasSpec(ZoomLevel(level, surface), ThreadPolicy.All(PositiveInt.unsafe(3)))
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "plate-craft-suite",
        AtlasCompiler.configurationChecksum(state, spec)
      )
    )
    ok(AtlasCompiler(provenance).compile(model, state, spec))

  /** The draft path, which is the one that carries recorded absence. */
  private def draftScene(violations: Vector[Violation]): NarrativeScene =
    val draft = DraftModel.of(
      Wog.draft,
      ValidationOutcome(ValidationReport(violations), validated = None),
      DerivationRecord.NotSupplied
    )
    val spec = AtlasSpec(
      ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden),
      ThreadPolicy.All(PositiveInt.unsafe(3))
    )
    val provenance = ok(
      ViewProvenance.draftBuild(
        draft,
        "plate-craft-suite",
        AtlasCompiler.configurationChecksum(state, spec)
      )
    )
    ok(AtlasCompiler(provenance).compileDraft(draft, state, spec))

  // ------------------------------------------------------------------ contrast, computed

  /** WCAG 2.2 relative luminance of an sRGB colour. */
  private def luminance(c: Ink.Rgb): Double =
    def channel(v: Int): Double =
      val s = v / 255.0
      if s <= 0.03928 then s / 12.92 else math.pow((s + 0.055) / 1.055, 2.4)
    0.2126 * channel(c._1) + 0.7152 * channel(c._2) + 0.0722 * channel(c._3)

  private def contrast(a: Ink.Rgb, b: Ink.Rgb): Double =
    val (hi, lo) = (luminance(a), luminance(b)) match
      case (x, y) if x >= y => (x, y)
      case (x, y)           => (y, x)
    (hi + 0.05) / (lo + 0.05)

  private def round2(v: Double): Double = math.round(v * 100.0) / 100.0

  test("every ink that carries text clears WCAG 2.2 AA on the plate's own paper"):
    val texts = Vector(
      "ink" -> Ink.ink,
      "muted" -> Ink.muted,
      "absence" -> Ink.absence,
      "focus" -> Ink.focus
    )
    texts.foreach { (name, c) =>
      val ratio = contrast(c, Ink.paper)
      assert(ratio >= 4.5, s"$name is $ratio:1 against the paper, below the 4.5:1 text minimum")
    }

  test("every ink that carries a graphical object clears the 3:1 non-text minimum"):
    Vector("rule" -> Ink.rule, "ground" -> Ink.ground).foreach { (name, c) =>
      val ratio = contrast(c, Ink.paper)
      assert(ratio >= 3.0, s"$name is $ratio:1 against the paper, below the 3:1 object minimum")
    }
    // A landmark sits on the narrated ground, so it is that pairing which has to be legible, not
    // only the glyph against the paper.
    val onGround = contrast(Ink.ink, Ink.ground)
    assert(onGround >= 3.0, s"an inked glyph on the narrated ground is only $onGround:1")

  test("the palette's contrast ratios are the ones recorded in the report"):
    // Pinned so a palette edit has to restate what it did to legibility rather than drift.
    assertEquals(round2(contrast(Ink.ink, Ink.paper)), 17.4)
    assertEquals(round2(contrast(Ink.muted, Ink.paper)), 8.4)
    assertEquals(round2(contrast(Ink.absence, Ink.paper)), 6.44)
    assertEquals(round2(contrast(Ink.rule, Ink.paper)), 3.11)
    assertEquals(round2(contrast(Ink.ground, Ink.paper)), 3.42)
    assertEquals(round2(contrast(Ink.focus, Ink.paper)), 8.21)
    assertEquals(round2(contrast(Ink.ink, Ink.ground)), 5.09)

  test("the type scale has real steps and two families, not one flat size"):
    val sizes = Vector(
      Typeface.titlePt,
      Typeface.sectionPt,
      Typeface.labelPt,
      Typeface.metaPt,
      Typeface.machinePt
    ).distinct
    // A plate whose type sits inside a 1.1:1 range has no hierarchy whatever its content.
    assert(sizes.length >= 4, s"only ${sizes.length} distinct sizes in the scale")
    assert(
      sizes.max / sizes.min >= 1.5,
      s"the scale spans only ${sizes.max / sizes.min}:1, which reads as one size"
    )
    // And size is not the only channel: model text is set in a serif, machine strings in a mono.
    assertNotEquals(Typeface.prose, Typeface.machine)
    val svg = ok(
      SvgRenderer.render(
        ok(AtlasLowering.lower(draftScene(Vector.empty), length, box)),
        ok(SvgOptions(1600, 1080))
      )
    ).value
    val families = """font-family="([^"]*)"""".r.findAllMatchIn(svg).map(_.group(1)).toSet
    assertEquals(families, Set(Typeface.prose, Typeface.machine))
    val emitted = """font-size="([\d.]+)"""".r.findAllMatchIn(svg).map(_.group(1).toDouble).toSet
    assert(emitted.size >= 4, s"the plate emits only ${emitted.size} distinct type sizes")

  test("a label that does not fit keeps both of its ends"):
    // Cutting the end takes exactly what tells one identity or one qualifier from another.
    val identity = "c-entity:b679d64ced89"
    val cut = Measure
      .elideMiddle(identity, Measure.widthPx(identity, Typeface.labelPt) * 0.6, Typeface.labelPt)
      .getOrElse(fail("a 20-character identity should still elide to something"))
    assert(cut.contains("…"), cut)
    assert(identity.startsWith(cut.takeWhile(_ != '…')), cut)
    assert(identity.endsWith(cut.reverse.takeWhile(_ != '…').reverse), cut)
    assert(cut.length < identity.length, cut)
    // A label that fits is returned whole.
    assertEquals(Measure.elideMiddle(identity, 10000.0, Typeface.labelPt), Some(identity))

  test("every drawn label is served by a leader that ends at that label's baseline"):
    val s = draftScene(Vector.empty)
    val plan = AtlasPlate.plan(s, length, box)
    val lowered = ok(AtlasLowering.lower(s, length, box))
    val device = ok(
      ig.DeviceScene.fromScene(lowered, ig.DeviceContext.unsafe(box.widthPx, box.heightPx))
    )
    // For each labelled mark the group holds a leader polyline whose last point is at the text's
    // own y: a leader that ran on through the block could not say which label it serves.
    val labelled = plan.labels.keySet
    assert(labelled.nonEmpty)
    var checked = 0
    def walk(elements: Vector[ig.DeviceElement], name: Option[String]): Unit =
      elements.foreach {
        case ig.DeviceElement.Group(n, _, _, children) =>
          walk(children, n.map(_.value).orElse(name))
        case ig.DeviceElement.Mark(primitive) =>
          name.filter(labelled.contains).foreach { _ =>
            primitive match
              case ig.DevicePrimitive.Polyline(points, false, _, _) if points.length == 3 =>
                assertEquals(points(1).y, points(2).y, "the leader's foot is not level")
                assertNotEquals(points.head.y, points(1).y, "the leader has no rise")
                checked += 1
              case _ => ()
          }
      }
    walk(device.elements, None)
    assertEquals(checked, labelled.size, "not every labelled mark carries a terminating leader")

  test("the plate states what its level draws and what the model has of it"):
    Vector(NarrativeLevel.Story, NarrativeLevel.Episode, NarrativeLevel.Scene).foreach { level =>
      val plan = AtlasPlate.plan(scene(level), length, box)
      assert(plan.levelNote.contains(level.toString), plan.levelNote)
      assert(plan.levelNote.contains("regions"), plan.levelNote)
      val svg = ok(
        SvgRenderer.render(
          ok(AtlasLowering.lower(scene(level), length, box)),
          ok(SvgOptions(1600, 1080))
        )
      ).value
      Measure
        .wrap(plan.levelNote, plan.rightPx - plan.contentLeftPx, Typeface.finePt)
        .foreach(line => assert(svg.contains(line), s"$level does not state its own grain"))
    }
    // Story and Episode are no longer the same picture: each says what it draws.
    val story = AtlasPlate.plan(scene(NarrativeLevel.Story), length, box).levelNote
    val episode = AtlasPlate.plan(scene(NarrativeLevel.Episode), length, box).levelNote
    assertNotEquals(story, episode)

  test("the plate names the epistemic channels it reaches and the ones it does not"):
    val s = draftScene(
      Vector(Violation("hierarchy.single-primary-root", Severity.Error, "segments", "none"))
    )
    val plan = AtlasPlate.plan(s, length, box)
    assert(plan.channelNote.contains("Bracket"), plan.channelNote)
    Vector("Fan", "OpenHatch", "Placeholder").foreach(channel =>
      assert(plan.channelNote.contains(channel), s"$channel is not accounted for")
    )
    // A plate whose dots are all alike has to say that uniformity is the projection's limit.
    assert(plan.channelNote.contains("no epistemic status"), plan.channelNote)
    val svg = ok(
      SvgRenderer.render(ok(AtlasLowering.lower(s, length, box)), ok(SvgOptions(1600, 1080)))
    ).value
    // A statement the plate owes a reader is wrapped, never elided: every line of it is drawn.
    val lines = Measure.wrap(plan.channelNote, plan.rightPx - plan.contentLeftPx, Typeface.finePt)
    assert(lines.length > 1, "the note is short enough that wrapping is untested")
    lines.foreach(line => assert(svg.contains(line), s"the plate drops '$line'"))
    assertEquals(lines.mkString(" · "), plan.channelNote)

  test("the narrated ground names itself where it is drawn"):
    val svg = ok(
      SvgRenderer.render(
        ok(AtlasLowering.lower(draftScene(Vector.empty), length, box)),
        ok(SvgOptions(1600, 1080))
      )
    ).value
    // The plate's most dominant object may not be an unnamed track, and what it says of itself is
    // what it is rather than what it is not: a disclaimer against a real invitation to read
    // structure is a worse answer than a description.
    assert(svg.contains("separate stretches of narration"), "the ribbon does not name itself")
    assert(
      svg.contains("the gaps between them are where someone speaks"),
      "the gaps are unexplained"
    )

  test("a leader and a frame tie are never the same stroke"):
    val style = ok(Style.params)
    assertNotEquals(style.leader.lineType, style.tie.lineType)
    assertNotEquals(style.leader.stroke, style.tie.stroke)

  test("no type on the plate is smaller than the declared floor"):
    val sizes = Vector(
      Typeface.titlePt,
      Typeface.sectionPt,
      Typeface.metaPt,
      Typeface.finePt,
      Typeface.labelPt,
      Typeface.laneNamePt,
      Typeface.laneKindPt,
      Typeface.axisPt
    )
    sizes.foreach(pt =>
      assert(pt >= Typeface.minimumPt, s"${pt}pt is below the ${Typeface.minimumPt}pt floor")
    )
    // Tiny type may not be used to simulate density (design brief §15): 9pt is 12 device pixels at
    // the 96dpi the SVG backend emits into.
    assertEquals(Typeface.minimumPt * 96.0 / 72.0, 12.0)

  // ------------------------------------------------------------------ the label budget

  private def plan(s: NarrativeScene): AtlasPlate.Plan = AtlasPlate.plan(s, length, box)

  /** The pixel interval a placed label occupies in its row. */
  private def interval(p: AtlasPlate.Plan, l: AtlasPlate.PlacedLabel): (Double, Double) =
    val start = p.xOf(l.markX.toDouble) + l.dxPx
    (start, start + Measure.widthPx(l.text, Typeface.labelPt))

  test("no two labels in one lane and row come within the declared clearance"):
    Vector(scene(), scene(NarrativeLevel.Episode), draftScene(Vector.empty)).foreach { s =>
      val p = plan(s)
      p.labels.values
        .groupBy(l => (l.lane, l.row))
        .foreach { case ((lane, row), labels) =>
          val placed = labels.toVector.map(interval(p, _)).sortBy(_._1)
          placed.sliding(2).foreach {
            case Vector((_, aEnd), (bStart, _)) =>
              assert(
                bStart - aEnd >= Metric.labelGapPx - 1.0e-9,
                s"lane $lane row $row: labels are ${bStart - aEnd}px apart, " +
                  s"below the ${Metric.labelGapPx}px clearance"
              )
            case _ => ()
          }
        }
    }

  test("every label stays inside the plot and is elided rather than shrunk"):
    val p = plan(scene())
    p.labels.values.foreach { label =>
      val (start, end) = interval(p, label)
      assert(start >= p.plotLeftPx - 1.0e-9, s"'${label.text}' starts left of the plot")
      assert(end <= p.rightPx + 1.0e-9, s"'${label.text}' runs past the plot")
      assert(
        Measure.widthPx(label.text, Typeface.labelPt) <= Metric.labelMaxPx + 1.0e-9,
        s"'${label.text}' exceeds the label width budget"
      )
    }

  test("a withheld label is never a dropped mark: the plate draws and names every one"):
    val s = draftScene(
      Vector(Violation("hierarchy.single-primary-root", Severity.Error, "segments", "none"))
    )
    val p = plan(s)
    assert(p.labelsWithheld > 0, "the fixture is dense enough for the budget to bite")
    val names = GraphicsNames.collect(ok(AtlasLowering.lower(s, length, box))).map(_.value).toSet
    assertEquals(names, s.marks.map(_.identity.mark.value).toSet)
    // And the plate says how many it withheld, so the reader is never left to wonder.
    val svg =
      ok(SvgRenderer.render(ok(AtlasLowering.lower(s, length, box)), ok(SvgOptions(1600, 1080))))
    assert(svg.value.contains(s"${p.labelsDrawn} of ${p.labellable} drawn"))
    assert(svg.value.contains(s"${p.labelsWithheld} withheld"))

  // ------------------------------------------------------------------ recorded absence

  test("recorded absence is grouped by reason, and every mark keeps its own place"):
    val s = draftScene(
      Vector(
        Violation("hierarchy.single-primary-root", Severity.Error, "segments", "none"),
        Violation("hierarchy.situation-root-reachable", Severity.Error, "situations/a", "orphan"),
        Violation("hierarchy.situation-root-reachable", Severity.Error, "situations/b", "orphan")
      )
    )
    val p = plan(s)
    val absences = s.marks.filter(_.epistemicPlacement.isDefined)
    assert(absences.nonEmpty)
    // One reason, written once, however many marks record it.
    assert(
      p.absence.length < absences.length,
      s"${p.absence.length} groups for ${absences.length} marks: the reasons were not collapsed"
    )
    p.absence.foreach(g => assert(g.detail.nonEmpty, s"${g.headline} has no reason"))
    // Every mark is accounted for exactly once, either on its spans or in the margin.
    val accounted =
      p.absence.flatMap(g => g.rowOf.keys.toVector ++ g.unplaced)
    assertEquals(accounted.length, absences.length)
    assertEquals(accounted.toSet, absences.map(_.identity.mark.value).toSet)

  test("two absence marks share a packing row only when their spans are disjoint"):
    val s = draftScene(Vector.empty)
    val p = plan(s)
    val spans = s.marks
      .flatMap(m => m.epistemicPlacement.flatMap(_.spanSet).map(m.identity.mark.value -> _))
      .toMap
    val scale = p.plotWidthPx / math.max(length.toDouble, 1.0)
    p.absence.foreach { group =>
      group.rowOf.groupBy(_._2).foreach { (row, members) =>
        val footprints = members.keys.toVector
          .flatMap(id => spans.get(id))
          .map(s =>
            (
              s.spans.toVector.map(_.start).min * scale,
              s.spans.toVector.map(_.endExclusive).max * scale
            )
          )
          .sortBy(_._1)
        footprints.sliding(2).foreach {
          case Vector((_, aEnd), (bStart, _)) =>
            assert(bStart >= aEnd, s"row $row packs overlapping footprints")
          case _ => ()
        }
      }
    }

  // ------------------------------------------------------------------ composition

  test("the lane plot's geometry depends on the lanes and never on the surface detail"):
    val viewports = Vector(SurfaceDetail.Hidden, SurfaceDetail.Sentences, SurfaceDetail.Tokens)
      .map(d =>
        AtlasLowering
          .layerOf(
            ok(AtlasLowering.lower(scene(surface = d), length, box)),
            AtlasLowering.Layer.LanePlot
          )
          .viewport
      )
    assertEquals(viewports.distinct.length, 1)

  test("a lane is named from a context band that occupies it, never from a neighbour"):
    val s = draftScene(Vector.empty)
    val p = plan(s)
    val bands = s.marks.collect { case b: VisualPrimitive.ContextBand => b.lane -> b.kind }.toMap
    p.lanes.foreach { lane =>
      assertEquals(lane.kind, bands.get(lane.index), s"lane ${lane.index}")
    }
    assertEquals(p.lanes.headOption.flatMap(_.kind), Some(ContextKind.NarratedWorld))
    // Lanes above zero are speech frames here, and are named as the model names them.
    assert(p.lanes.tail.forall(l => l.kind.forall(k => !AtlasPlate.isNarrated(k))))

  test("a lane is named in the story's own words, never by a content address"):
    val s = draftScene(Vector.empty)
    val plan = AtlasPlate.plan(s, length, box)
    val labels = s.marks.collect { case t: VisualPrimitive.Thread =>
      t.identity.address.key.render -> t.label
    }.toMap
    assert(plan.lanes.nonEmpty)
    plan.lanes.foreach { lane =>
      lane.holder match
        case AtlasPlate.LaneHolder.Named(label) =>
          // The label is the entity's own, taken from its thread and not invented.
          assert(
            labels.values.toSet.contains(label),
            s"lane ${lane.index} label '$label' is not an entity's"
          )
        case AtlasPlate.LaneHolder.Unnamed(reason) =>
          // Words, not an identifier: a hash on the face of the plate is what made it unreadable.
          assert(reason.contains(" "), s"lane ${lane.index} holder '$reason' is not a phrase")
          assert(
            !reason.contains(":"),
            s"lane ${lane.index} holder '$reason' looks like an address"
          )
        case AtlasPlate.LaneHolder.Unheld => ()
    }
    // And a frame the model does attribute really is named, so the law is not vacuous.
    assert(
      plan.lanes.exists(_.holder.isInstanceOf[AtlasPlate.LaneHolder.Named]),
      "no lane is named, so the join to the entity threads is doing nothing"
    )

  test("the plate says what a situation label is, so it is not read as the story's words"):
    val svg = ok(
      SvgRenderer.render(
        ok(AtlasLowering.lower(draftScene(Vector.empty), length, box)),
        ok(SvgOptions(1600, 1080))
      )
    ).value
    assert(svg.contains("the model's own description of it, not the story's words"), svg.take(0))

  test("the plate composes for the box it is given, and a bigger box buys more labels"):
    val s = scene()
    val small = AtlasPlate.plan(s, length, ok(PlateBox.of(1000, 700)))
    val large = AtlasPlate.plan(s, length, ok(PlateBox.of(2000, 1400)))
    assert(large.plotWidthPx > small.plotWidthPx)
    assert(
      large.laneHeightPx > small.laneHeightPx,
      s"lane height ${large.laneHeightPx} at 2000x1400 vs ${small.laneHeightPx} at 1000x700"
    )
    assert(
      large.labelsDrawn > small.labelsDrawn,
      s"${large.labelsDrawn} labels at 2000x1400 is no better than ${small.labelsDrawn} at 1000x700"
    )

  test("the axis is broken by Intaglio's own generator and every tick is inside the range"):
    val p = plan(scene())
    assert(p.ticks.nonEmpty)
    p.ticks.foreach { (value, label) =>
      assert(value >= 0.0 && value <= length.toDouble, s"tick $value is outside [0, $length]")
      assert(label.nonEmpty)
    }

  test("the plate draws no colour that is not in the declared palette"):
    val svg = ok(
      SvgRenderer.render(
        ok(AtlasLowering.lower(draftScene(Vector.empty), length, box)),
        ok(SvgOptions(1600, 1080))
      )
    ).value
    val hex = """(?:stroke|fill)="(#[0-9a-f]{6})"""".r
    val used = hex.findAllMatchIn(svg).map(_.group(1)).toSet
    val declared =
      Vector(Ink.paper, Ink.ink, Ink.muted, Ink.rule, Ink.hairline, Ink.absence, Ink.ground)
        .map((r, g, b) => f"#$r%02x$g%02x$b%02x")
        .toSet
    assertEquals(used.diff(declared), Set.empty[String], s"undeclared ink in the plate")

  test("the plate is deterministic at every zoom level, in the scene and in the SVG"):
    Vector(NarrativeLevel.Story, NarrativeLevel.Episode, NarrativeLevel.Scene).foreach { level =>
      Vector(SurfaceDetail.Hidden, SurfaceDetail.Sentences, SurfaceDetail.Tokens).foreach {
        detail =>
          val s = scene(level, detail)
          val a = ok(AtlasLowering.lower(s, length, box))
          val b = ok(AtlasLowering.lower(s, length, box))
          assertEquals(a, b, s"$level/$detail")
          val options = ok(SvgOptions(1600, 1080))
          assertEquals(
            ok(SvgRenderer.render(a, options)).value,
            ok(SvgRenderer.render(b, options)).value,
            s"$level/$detail"
          )
      }
    }

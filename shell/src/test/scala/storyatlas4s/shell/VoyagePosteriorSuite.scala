package storyatlas4s.shell

import munit.FunSuite
import storymodel4s.align.{AlignState, AlignmentMatrix, AlignmentRow, ExternalState, SourceNodeRef}
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

class VoyagePosteriorSuite extends FunSuite:
  private def ok[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def span(a: Double, b: Double) = ok(ClockSpan.of(a, b))
  private val unit = RecallUnitId.unsafe("test-unit")
  private val a = SourceNodeRef.Situation(SituationId.unsafe("a"))
  private val b = SourceNodeRef.Situation(SituationId.unsafe("b"))
  private val fill = SourceNodeRef.Situation(SituationId.unsafe("fill"))
  private val group = SourceNodeRef.Segment(SegmentId.unsafe("group"))
  private val timeline = ok(
    SourceTimeline.of(
      Vector(
        SourceTimelineNode(a, 0, Some(1), span(0, 10), "A"),
        SourceTimelineNode(b, 0, Some(1), span(10, 20), "B"),
        SourceTimelineNode(fill, 0, Some(1), span(20, 30), "Filled choice"),
        SourceTimelineNode(group, 1, Some(1), span(0, 30), "Whole group")
      ),
      Vector(SourceTimelineGroup(1, "Group", span(0, 30)))
    )
  )
  private val provenance = ok(
    ViewProvenance.of(
      Checksum.ofText("synthetic source"),
      None,
      ViewBasis.AlignmentRun,
      VoyageCompiler.compilerVersion,
      Checksum.ofText("synthetic posterior presentation")
    )
  )

  private def scene(
      source: Map[SourceNodeRef, Double],
      external: Double,
      chosen: Option[SourceNodeRef],
      origin: AnchorOrigin,
      timed: Boolean = true
  ): VoyageScene =
    val masses = source.map((ref, mass) => AlignState.Source(ref) -> mass) +
      (AlignState.External(ExternalState.Unranked) -> external)
    val input = ok(
      RecallVoyageInput.of(
        Vector(
          VoyageUnit(unit, 0, "Synthetic recall", Option.when(timed)(ok(Seconds.of(1))), None)
        ),
        ok(AlignmentMatrix.of(Vector(ok(AlignmentRow.of(unit, masses))))),
        timeline,
        Vector(VoyageDecision(unit, chosen, chosen.map(_ => 1), origin)),
        None,
        ok(Seconds.of(10))
      )
    )
    ok(VoyageCompiler.compile(input, Set.empty, provenance))

  test("a filled decision never heads the complete ranked posterior") {
    val compiled = scene(Map(a -> 0.4, b -> 0.2), 0.4, Some(fill), AnchorOrigin.DecodeFilled)
    val twin = compiled.textualTwin
    val record = VoyagePosterior.forUnit(compiled, unit).get
    assertEquals(record.drawn.mass, 0.0)
    assertEquals(record.candidates.map(c => (c.ref, c.mass)), Vector(a -> 0.4, b -> 0.2))
    assert(record.candidates.forall(!_.drawn))
    assertEquals(record.drawn.sourceMass, 0.4 + 0.2)
    assertEquals(record.drawn.externalMass, 0.4)
    assertEquals(compiled.textualTwin, twin)
  }

  test("a supported drawn choice appears once at its mass rank") {
    val record = VoyagePosterior
      .forUnit(
        scene(Map(a -> 0.6, b -> 0.1), 0.3, Some(b), AnchorOrigin.DecodeBound),
        unit
      )
      .get
    assertEquals(
      record.candidates.map(c => (c.ref, c.mass, c.drawn)),
      Vector((a, 0.6, false), (b, 0.1, true))
    )
  }

  test("ties use source keys even when one of the tied candidates is drawn") {
    val record = VoyagePosterior
      .forUnit(
        scene(Map(b -> 0.25, a -> 0.25), 0.5, Some(b), AnchorOrigin.DecodeBound),
        unit
      )
      .get
    assertEquals(record.candidates.map(_.ref), Vector(a, b))
    assertEquals(record.candidates.map(_.mass), Vector(0.25, 0.25))
  }

  test("coarse support stays on its group without distributing mass to children") {
    val record = VoyagePosterior
      .forUnit(
        scene(Map(group -> 0.75), 0.25, Some(group), AnchorOrigin.PosteriorArgmax),
        unit
      )
      .get
    assertEquals(record.candidates.map(_.ref), Vector(group))
    assertEquals(record.drawn.level, 1)
    assertEquals(record.drawn.span, span(0, 30))
  }

  test("external-only filled rows have an empty source candidate list") {
    val record = VoyagePosterior
      .forUnit(
        scene(Map.empty, 1.0, Some(fill), AnchorOrigin.DecodeFilled),
        unit
      )
      .get
    assertEquals(record.candidates, Vector.empty)
    assertEquals(record.drawn.sourceMass, 0.0)
    assertEquals(record.drawn.externalMass, 1.0)
  }

  test("untimed and unanchored marks do not fabricate complete posterior payloads") {
    val untimed = scene(Map(a -> 1.0), 0, Some(a), AnchorOrigin.PosteriorArgmax, timed = false)
    val unanchored = scene(Map.empty, 1.0, None, AnchorOrigin.PosteriorArgmax)
    assertEquals(VoyagePosterior.forUnit(untimed, unit), None)
    assertEquals(VoyagePosterior.forUnit(unanchored, unit), None)
  }

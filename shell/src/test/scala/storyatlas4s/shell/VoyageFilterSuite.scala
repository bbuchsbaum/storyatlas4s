package storyatlas4s.shell

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

class VoyageFilterSuite extends FunSuite:

  private def ok[E, A](either: Either[E, A]): A = either.fold(e => fail(e.toString), identity)
  private def span(a: Double, b: Double) = ok(ClockSpan.of(a, b))
  private def secs(v: Double) = ok(Seconds.of(v))
  private val a = SourceNodeRef.Situation(SituationId.unsafe("a"))
  private val b = SourceNodeRef.Situation(SituationId.unsafe("b"))
  private val c = SourceNodeRef.Situation(SituationId.unsafe("c"))
  private val g2 = SourceNodeRef.Segment(SegmentId.unsafe("g2"))
  private val u1 = RecallUnitId.unsafe("u1")
  private val u2 = RecallUnitId.unsafe("u2")
  private val u3 = RecallUnitId.unsafe("u3")
  private val u4 = RecallUnitId.unsafe("u4")
  private val u5 = RecallUnitId.unsafe("u5")

  private val timeline = ok(
    SourceTimeline.of(
      Vector(
        SourceTimelineNode(a, 0, Some(1), span(0, 10), "a"),
        SourceTimelineNode(b, 0, Some(1), span(10, 20), "b"),
        SourceTimelineNode(c, 0, Some(2), span(20, 30), "c"),
        SourceTimelineNode(g2, 1, Some(2), span(20, 30), "group 2")
      ),
      Vector(
        SourceTimelineGroup(1, "group 1", span(0, 20)),
        SourceTimelineGroup(2, "group 2", span(20, 30))
      )
    )
  )
  private val ext = AlignState.External(ExternalState.Association)
  // u1: decode-filled at c (argmax a, 0.5); u2: unanchored; u3: untimed; u4: group-level argmax;
  // u5: decode-bound at b, external-dominant (argmax a, 0.3)
  private val scene = ok(
    VoyageCompiler.compile(
      ok(
        RecallVoyageInput.of(
          Vector(
            VoyageUnit(u1, 0, "one", Some(secs(1.0)), None),
            VoyageUnit(u2, 1, "two", Some(secs(3.0)), None),
            VoyageUnit(u3, 2, "three", None, None),
            VoyageUnit(u4, 3, "four", Some(secs(5.0)), None),
            VoyageUnit(u5, 4, "five", Some(secs(7.0)), None)
          ),
          ok(
            AlignmentMatrix.of(
              Vector(
                ok(
                  AlignmentRow.of(
                    u1,
                    Map(AlignState.Source(a) -> 0.5, AlignState.Source(b) -> 0.2, ext -> 0.3)
                  )
                ),
                ok(AlignmentRow.of(u2, Map(AlignState.External(ExternalState.Unranked) -> 1.0))),
                ok(AlignmentRow.of(u3, Map(AlignState.Source(c) -> 1.0))),
                ok(AlignmentRow.of(u4, Map(AlignState.Source(g2) -> 0.9, ext -> 0.1))),
                ok(
                  AlignmentRow.of(
                    u5,
                    Map(AlignState.Source(a) -> 0.3, AlignState.Source(b) -> 0.1, ext -> 0.6)
                  )
                )
              )
            )
          ),
          timeline,
          Vector(
            VoyageDecision(u1, Some(c), Some(2), AnchorOrigin.DecodeFilled),
            VoyageDecision(u2, None, None, AnchorOrigin.PosteriorArgmax),
            VoyageDecision(u3, Some(c), Some(2), AnchorOrigin.PosteriorArgmax),
            VoyageDecision(u4, Some(g2), Some(2), AnchorOrigin.PosteriorArgmax),
            VoyageDecision(u5, Some(b), Some(1), AnchorOrigin.DecodeBound)
          ),
          None,
          secs(10.0)
        )
      ),
      Set.empty,
      ok(
        ViewProvenance.of(
          Checksum.ofText("source"),
          None,
          ViewBasis.AlignmentRun,
          VoyageCompiler.compilerVersion,
          Checksum.ofText("config")
        )
      )
    )
  )
  import VoyageFilter.*

  test("an unset filter matches nothing and dims nothing") {
    assertEquals(VoyageFilter.none.matches(scene), Map.empty)
    assertEquals(VoyageFilter.none.step(scene, None, forward = true), Step.NotSet)
  }

  test("each criterion matches exactly the marks that carry its fact") {
    def only(c: Criterion) = VoyageFilter.none.toggled(c).matches(scene).keySet
    assertEquals(only(Criterion.DecodeFilled), Set(u1))
    assertEquals(only(Criterion.DecodeBound), Set(u5))
    assertEquals(only(Criterion.ExternalDominant), Set(u5))
    assertEquals(only(Criterion.GroupGrain), Set(u4))
    assertEquals(only(Criterion.Untimed), Set(u3))
    assertEquals(only(Criterion.Unanchored), Set(u2))
  }

  test("each supplied chip count equals the units its criterion filters") {
    Criterion.values.foreach { c =>
      val n = count(scene, c)
      assertEquals(n.value, VoyageFilter.none.toggled(c).matches(scene).size, c.label)
      assertEquals(n.countedByView, c == Criterion.GroupGrain, c.label)
    }
  }

  test("thresholds read the supplied argmax mass, external mass and localizability") {
    val low = VoyageFilter(argmaxMassBelow = Some(0.4)).matches(scene)
    assertEquals(low.keySet, Set(u5), "u5's argmax a carries 0.3; u1's carries 0.5")
    assertEquals(low(u5), Vector("argmax mass below 0.4"))
    assertEquals(VoyageFilter(externalMassAbove = Some(0.5)).matches(scene).keySet, Set(u5))
    assertEquals(
      VoyageFilter(localizabilityBelow = Some(1.01)).matches(scene).keySet,
      Set(u1, u4, u5)
    )
  }

  test("any unions the criteria; all requires every set criterion") {
    val both = VoyageFilter(criteria = Set(Criterion.DecodeBound, Criterion.ExternalDominant))
    assertEquals(both.matches(scene)(u5), Vector("decode-bound", "external-dominant"))
    val any = VoyageFilter(criteria = Set(Criterion.DecodeBound, Criterion.DecodeFilled))
    assertEquals(any.matches(scene).keySet, Set(u1, u5))
    assertEquals(any.copy(combine = Combine.All).matches(scene), Map.empty)
    assertEquals(both.copy(combine = Combine.All).matches(scene).keySet, Set(u5))
  }

  test("stepping through matches says why it cannot move") {
    val f = VoyageFilter(criteria = Set(Criterion.DecodeBound, Criterion.DecodeFilled))
    assertEquals(f.step(scene, None, forward = true), Step.To(u1))
    assertEquals(f.step(scene, Some(u1), forward = true), Step.To(u5))
    assertEquals(f.step(scene, Some(u5), forward = true), Step.NoFurther)
    assertEquals(f.step(scene, Some(u1), forward = false), Step.NoEarlier)
    assertEquals(
      VoyageFilter.none.toggled(Criterion.Untimed).step(scene, None, true),
      Step.AllUntimed(1)
    )
    val nothing = VoyageFilter(externalMassAbove = Some(0.99))
    assertEquals(nothing.step(scene, None, forward = true), Step.NoMatch)
  }

package storyatlas4s.shell

import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** A five-unit scene with every mark kind, shared by the shell's Voyage suites. */
object VoyageShellFixture:

  def ok[E, A](either: Either[E, A]): A = either.fold(e => sys.error(e.toString), identity)
  def span(a: Double, b: Double) = ok(ClockSpan.of(a, b))
  def secs(v: Double) = ok(Seconds.of(v))
  val a = SourceNodeRef.Situation(SituationId.unsafe("a"))
  val b = SourceNodeRef.Situation(SituationId.unsafe("b"))
  val c = SourceNodeRef.Situation(SituationId.unsafe("c"))
  val g2 = SourceNodeRef.Segment(SegmentId.unsafe("g2"))
  val u1 = RecallUnitId.unsafe("u1")
  val u2 = RecallUnitId.unsafe("u2")
  val u3 = RecallUnitId.unsafe("u3")
  val u4 = RecallUnitId.unsafe("u4")
  val u5 = RecallUnitId.unsafe("u5")

  val timeline = ok(
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
  val ext = AlignState.External(ExternalState.Association)
  // u1: decode-filled at c (argmax a, 0.5); u2: unanchored; u3: untimed; u4: group-level argmax;
  // u5: decode-bound at b, external-dominant (argmax a, 0.3)
  val scene = ok(
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

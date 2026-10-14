package storyatlas4s.shell

import munit.FunSuite
import storyatlas4s.edition.TemporalTestData
import storymodel4s.align.*
import storymodel4s.codec.TemporalInspectionCodecs
import storymodel4s.core.Checksum
import storymodel4s.recall.*

class TemporalSeriesSuite extends FunSuite:
  import TemporalTestData.*

  private def opened = right(TemporalWorkspaceController.open(session, reference))
  private val timedBoth = right(
    RecallTiming.checked(
      inventory,
      clock,
      inventory.words.zipWithIndex.map { (word, index) =>
        val observation = index match
          case 0 =>
            RecallTiming.Observation.Interval(
              sec(0),
              sec(2),
              RecallTiming.Basis.SourceReported(Checksum.ofText("series-observation"))
            )
          case 1 =>
            RecallTiming.Observation.OnsetOnly(
              sec(3),
              RecallTiming.Basis.SourceReported(Checksum.ofText("series-observation"))
            )
          case 2 =>
            RecallTiming.Observation.Interval(
              sec(5),
              sec(7),
              RecallTiming.Basis.SourceReported(Checksum.ofText("series-observation"))
            )
          case _ =>
            RecallTiming.Observation.OnsetOnly(
              sec(8),
              RecallTiming.Basis.SourceReported(Checksum.ofText("series-observation"))
            )
        RecallTiming.Entry(word.id, clock, observation)
      },
      RecallTiming.Provenance(
        Checksum.ofText("series-correspondence"),
        RecallTiming.RecordingLink.NotEstablished
      )
    )
  )
  private val timedSession = right(TemporalInspectionCodecs.checked(workspace, Some(timedBoth)))
  private def openedTimed = right(TemporalWorkspaceController.open(timedSession, reference))

  test("recall-clock rows retain supplied observations and producer region queries") {
    val controller = right(
      openedTimed.dispatch(
        TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.UniformIntervals)
      )
    )
    val series = right(TemporalSeries.read(controller)).getOrElse(fail("missing recall clock"))
    assertEquals(series.clock, clock)
    assertEquals(series.points.map(_.unit), controller.projection.rows.map(_.unit))
    assertEquals(series.points.size, 2)
    assertEquals(series.points.map(_.observations.size), Vector(2, 2))
    assert(series.points.head.observations.exists {
      case RecallTiming.Observation.Interval(start, endExclusive, _) =>
        start == sec(0) && endExclusive == sec(2)
      case _ => false
    })
    assert(series.points(1).observations.exists {
      case RecallTiming.Observation.Interval(start, endExclusive, _) =>
        start == sec(5) && endExclusive == sec(7)
      case _ => false
    })

    val region = right(
      TemporalQuery.Region.onTimeAxis(
        controller.projection.coordinate.axis,
        controller.roi.flatMap(
          _.geometry.toVector.flatMap(_.intervals).map(i => i.start -> i.endExclusive)
        ),
        controller.roi.flatMap(_.geometry.toVector.flatMap(_.points).map(_.at))
      )
    )
    series.points.foreach { point =>
      val expected = right(controller.projection.query(point.unit, region))
      assertEquals(point.regionMass, Right(expected.allocatedRegionMass))
      assertEquals(
        point.sourceWidePrecisionTicks,
        TemporalConcentration
          .shortestInspectionWindow(
            expected.prepared,
            TemporalConcentration.Target.ShareOfAllocatedSource(0.5)
          )
          .map(_.width)
          .left
          .map(_.toString)
      )
    }
  }

  test("series pages keep unequal timed rows, missing rows, and producer values separate") {
    val controller = right(
      openedTimed.dispatch(
        TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.UniformIntervals)
      )
    )
    val timed = right(TemporalSeries.read(controller, 0, 2)).getOrElse(fail("missing recall clock"))
    assertEquals(timed.points.map(_.unit), inventory.units.take(2).map(_.id))
    timed.points.foreach { point =>
      assert(point.regionMass.isRight)
      assert(point.sourceWidePrecisionTicks.isRight)
    }
    val missingController = opened
    val missing =
      right(TemporalSeries.read(missingController)).getOrElse(fail("missing recall clock"))
    assert(
      missing.points.last.observations.forall(_.isInstanceOf[RecallTiming.Observation.Missing])
    )
  }

  test(
    "absent recall clock yields no series and reading a series leaves selection and receipt unchanged"
  ) {
    val surface = right(SurfaceWorkspaceController.open(session))
    val selected = right(surface.dispatch(SurfaceWorkspaceAction.Recall(inventory.units.last.id)))
    val controller = selected.temporal.getOrElse(fail("missing temporal controller"))
    val before = right(controller.encode)
    assertEquals(controller.selection, Set(session.recallAddress(inventory.units.last.id).get))
    assertEquals(
      right(TemporalSeries.read(controller)).map(_.points.map(_.unit)),
      Some(inventory.units.map(_.id))
    )
    assertEquals(right(controller.encode), before)
    assertEquals(
      right(TemporalWorkspaceController.restore(before, session)).selection,
      controller.selection
    )

    val untimed = right(TemporalInspectionCodecs.checked(workspace, None))
    val noClock = right(TemporalWorkspaceController.open(untimed, reference))
    assertEquals(right(TemporalSeries.read(noClock)), None)
  }

  test("failed producer rows remain unavailable series points with their reasons") {
    val controller =
      right(TemporalWorkspaceController.open(TemporalTestData.failed, TemporalTestData.reference))
    val series = right(TemporalSeries.read(controller)).getOrElse(fail("missing recall clock"))
    assert(series.points.forall(_.regionMass.isLeft))
    assert(series.points.forall(_.sourceWidePrecisionTicks.isLeft))
    assert(
      series.points.exists(_.observations.exists(_.isInstanceOf[RecallTiming.Observation.Missing]))
    )
  }

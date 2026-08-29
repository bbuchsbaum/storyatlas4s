package storyatlas4s.app

import munit.FunSuite
import scala.collection.mutable
import storymodel4s.core.Address
import storymodel4s.view.*

class SemanticZoomSuite extends FunSuite:
  private def zoomAt(level: ZoomLevel): SemanticZoom =
    SemanticZoom.at(level).fold(error => fail(error.message), identity)

  test("each continuous axis commits at deterministic thresholds with hysteresis"):
    val initial = zoomAt(ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Hidden))

    val belowForward = initial.moveNarrative(0.64)
    assertEquals(belowForward.committed.narrative, NarrativeLevel.Story)
    val forward = belowForward.moveNarrative(0.66)
    assertEquals(forward.committed.narrative, NarrativeLevel.Episode)

    val jitter = Vector(0.61, 0.58, 0.63, 0.55, 0.62).foldLeft(forward) { case (state, position) =>
      state.moveNarrative(position)
    }
    assertEquals(jitter.committed.narrative, NarrativeLevel.Episode)
    assertEquals(jitter.committed.surface, SurfaceDetail.Hidden)

    val aboveReverse = jitter.moveNarrative(0.36)
    assertEquals(aboveReverse.committed.narrative, NarrativeLevel.Episode)
    val reverse = aboveReverse.moveNarrative(0.34)
    assertEquals(reverse.committed.narrative, NarrativeLevel.Story)

  test("narrative and surface gesture coordinates remain independent"):
    val initial = zoomAt(ZoomLevel(NarrativeLevel.Episode, SurfaceDetail.Sentences))
    val surface = initial.moveSurface(2.0)
    assertEquals(surface.committed, ZoomLevel(NarrativeLevel.Episode, SurfaceDetail.Tokens))
    assertEquals(surface.narrativePosition, initial.narrativePosition)

    val narrative = surface.moveNarrative(0.0)
    assertEquals(narrative.committed, ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Tokens))
    assertEquals(narrative.surfacePosition, surface.surfacePosition)

  test("large, non-finite, and out-of-range gesture values are deterministic"):
    val initial = zoomAt(ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Hidden))
    assertEquals(initial.moveNarrative(99.0).committed.narrative, NarrativeLevel.Scene)
    assertEquals(
      initial.moveSurface(Double.PositiveInfinity).committed.surface,
      SurfaceDetail.Tokens
    )
    assertEquals(initial.moveSurface(-99.0).surfacePosition, 0.0)
    val unchanged = initial.moveNarrative(Double.NaN)
    assertEquals(unchanged.committed, initial.committed)
    assertEquals(unchanged.narrativePosition, initial.narrativePosition)
    assertEquals(unchanged.surfacePosition, initial.surfacePosition)

  test("an unconfigured semantic level is a typed error rather than a sentinel position"):
    assertEquals(
      SemanticZoom.at(ZoomLevel(NarrativeLevel.Event, SurfaceDetail.Hidden)),
      Left(SemanticZoomError.UnsupportedNarrative(NarrativeLevel.Event))
    )

  test("out-of-order completion publishes only the last complete ViewChoice"):
    final case class Snapshot(markIds: Set[String], placement: SelectionPlacement[String])

    val address = Address
      .parse("story/situation/semantic-zoom-probe")
      .fold(error => fail(error.message), identity)
    val horizon = EpistemicHorizon.ReaderAt(701)
    val first = ViewChoice.initial.copy(
      zoom = ZoomLevel(NarrativeLevel.Episode, SurfaceDetail.Sentences),
      selection = Set(address),
      focus = Some(address),
      horizon = horizon
    )
    val latest = first.copy(zoom = ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Hidden))
    val callbacks = mutable.Map.empty[Long, Snapshot => Unit]
    val displays = mutable.ArrayBuffer.empty[CompilationDisplay[ViewChoice, Snapshot]]
    val runtime = CompilationRuntime[ViewChoice, Snapshot](
      (intent, complete) => callbacks.update(intent.revision.value, complete),
      displays.addOne
    )

    runtime.request(first)
    runtime.request(latest)
    val expected = Snapshot(Set("latest-episode-region"), SelectionPlacement.ViaAncestor(address))
    callbacks(1L)(expected)
    callbacks(0L)(Snapshot(Set("stale-sentence"), SelectionPlacement.OffProjection))

    val ready = displays.collect { case CompilationDisplay.Ready(value) => value }
    assertEquals(ready.length, 1)
    assertEquals(ready.head.intent.revision.value, 1L)
    assertEquals(ready.head.intent.request, latest)
    assertEquals(ready.head.intent.request.selection, Set(address))
    assertEquals(ready.head.intent.request.focus, Some(address))
    assertEquals(ready.head.intent.request.horizon, horizon)
    assertEquals(ready.head.result, expected)

  test("a new request hides the formerly ready result until the new intent completes"):
    val callbacks = mutable.Map.empty[Long, String => Unit]
    val displays = mutable.ArrayBuffer.empty[CompilationDisplay[String, String]]
    val runtime = CompilationRuntime[String, String](
      (intent, complete) => callbacks.update(intent.revision.value, complete),
      displays.addOne
    )

    runtime.request("first")
    callbacks(0L)("first-result")
    runtime.request("second")

    displays.last match
      case CompilationDisplay.Pending(intent) => assertEquals(intent.request, "second")
      case other                              => fail(s"expected pending latest intent, got $other")

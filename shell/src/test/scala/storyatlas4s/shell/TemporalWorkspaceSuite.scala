package storyatlas4s.shell

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storyatlas4s.edition.TemporalTestData
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.view.*

class TemporalWorkspaceSuite extends FunSuite:
  import TemporalTestData.*
  private def opened = right(SurfaceWorkspaceController.open(session))
  private def region(c: TemporalWorkspaceController, a: Long, b: Long) =
    right(
      TemporalQuery.Region.onTimeAxis(c.projection.coordinate.axis, Vector(a -> b), Vector.empty)
    )
  private def file(name: String, value: String) = name -> value.getBytes(UTF_8).toVector

  test(
    "surface selection replaces semantic addresses and keeps original timing through clear and policy switches"
  ) {
    val start = opened
    assert(start.temporal.nonEmpty)
    assertEquals(start.temporal.get.selection, Set.empty[Address])
    val first = right(start.dispatch(SurfaceWorkspaceAction.Recall(inventory.units.head.id)))
    val second = right(first.dispatch(SurfaceWorkspaceAction.Recall(inventory.units.last.id)))
    assertEquals(
      second.temporal.get.selection,
      Set(session.recallAddress(inventory.units.last.id).get)
    )
    assertEquals(second.session, session)
    val clear = right(second.dispatch(SurfaceWorkspaceAction.Clear))
    assertEquals(clear.temporal.get.selection, Set.empty[Address])
    assertEquals(clear.session, session)
    assertEquals(
      right(clear.dispatch(SurfaceWorkspaceAction.Policy(reconstruction))).session,
      session
    )
  }

  test(
    "independent disjoint ROI top-k bins and timing survive exact canonical v2 replay and policy switch"
  ) {
    val start = right(opened.dispatch(SurfaceWorkspaceAction.Policy(reference)))
    val t = start.temporal.get
    val roi = Vector(region(t, 10, 15), region(t, 80, 85))
    val a =
      right(start.dispatch(SurfaceWorkspaceAction.Temporal(TemporalWorkspaceAction.Region(roi))))
    val b = right(a.dispatch(SurfaceWorkspaceAction.Temporal(TemporalWorkspaceAction.TopK(1))))
    val c = right(
      b.dispatch(
        SurfaceWorkspaceAction.Temporal(
          TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.UniformIntervals)
        )
      )
    )
    val d = right(
      c.dispatch(
        SurfaceWorkspaceAction.Temporal(
          TemporalWorkspaceAction.RecallWindow(
            Some(TemporalProjection.RecallWindow(clock.digest, sec(0), sec(4)))
          )
        )
      )
    )
    val selected = right(d.dispatch(SurfaceWorkspaceAction.Recall(inventory.units.head.id)))
    val bytes = right(SurfaceWorkspaceController.encode(selected))
    val replay = right(SurfaceWorkspaceController.decode(bytes, session))
    assertEquals(right(SurfaceWorkspaceController.encode(replay)), bytes)
    assertEquals(replay.temporal.get.roi.map(_.geometry), roi.map(_.geometry))
    assertEquals(replay.temporal.get.topK, 1)
    assertEquals(
      replay.temporal.get.projection.exposure.get.units.head.duration,
      BigInt(2) -> BigInt(1)
    )
    val next = right(replay.dispatch(SurfaceWorkspaceAction.Policy(reconstruction)))
    assertEquals(next.temporal.get.roi.map(_.geometry), roi.map(_.geometry))
    assertEquals(next.temporal.get.projection.request, replay.temporal.get.projection.request)
    assertEquals(next.temporal.get.projection.measure, TemporalQuery.Measure.ModelPosterior)
    assertEquals(next.temporal.get.topK, 1)
    val imported = right(
      WorkspaceImport.open(
        Vector(file("data.json", TemporalTestData.bytes), file("view.json", bytes)),
        None
      )
    )
    imported match
      case WorkspaceSession.SurfaceInvestigation(value) =>
        assertEquals(right(SurfaceWorkspaceController.encode(value)), bytes)
      case _ => fail("wrong restored mode")
  }

  test(
    "standalone temporal receipt reopens with its original clock policy and semantic selection"
  ) {
    val selected = right(
      right(opened.dispatch(SurfaceWorkspaceAction.Policy(reference)))
        .dispatch(SurfaceWorkspaceAction.Recall(inventory.units.head.id))
    )
    val temporal = selected.temporal.get
    val receipt = right(temporal.encode)
    val restored = right(
      WorkspaceImport.open(
        Vector(file("data.json", TemporalTestData.bytes), file("receipt.json", receipt)),
        None
      )
    )
    restored match
      case WorkspaceSession.SurfaceInvestigation(value) =>
        assertEquals(value.session, selected.session)
        assertEquals(value.state, selected.state)
        assertEquals(right(value.temporal.get.encode), receipt)
      case _ => fail("wrong restored mode")
  }

  test("foreign semantic addresses clocks and view bytes refuse without replacing current state") {
    val start = opened
    val noTiming = right(TemporalInspectionCodecs.checked(workspace, None))
    val foreign = noTiming.recallAddress(inventory.units.head.id).get
    assert(start.temporal.get.dispatch(TemporalWorkspaceAction.Selection(Set(foreign))).isLeft)
    assert(
      SurfaceWorkspaceController
        .decode(right(SurfaceWorkspaceController.encode(start)), noTiming)
        .isLeft
    )
    val request = start.temporal.get.projection.request.copy(recallWindow =
      Some(TemporalProjection.RecallWindow(Checksum.ofText("foreign"), sec(0), sec(4)))
    )
    assert(
      start
        .dispatch(SurfaceWorkspaceAction.Temporal(TemporalWorkspaceAction.Request(request)))
        .isLeft
    )
    assertEquals(start.session, session)
    assertEquals(
      SurfaceWorkspaceController.restore(workspace, start.state, start.temporal),
      Left(WorkspaceRefusal.StaleArtifacts)
    )
  }

  test(
    "failed rows stay inspectable and overlapping ROI refuses independently of available measurements"
  ) {
    val start = right(SurfaceWorkspaceController.open(failed))
    val selected = right(start.dispatch(SurfaceWorkspaceAction.Recall(inventory.units.head.id)))
    val t = selected.temporal.get
    assert(t.projection.rows.forall(_.grid.isLeft))
    assert(t.projection.rows.forall(_.processing.isInstanceOf[ProcessingStatus.Failed]))
    assertEquals(
      t.dispatch(TemporalWorkspaceAction.Region(Vector(region(t, 10, 20), region(t, 15, 25)))),
      Left(TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.Overlap))
    )
    assert(right(SurfaceWorkspaceController.encode(selected)).nonEmpty)
  }

  test("cropping and rebinning retain original quantities and omitted mass") {
    val start = right(TemporalWorkspaceController.open(session, reference))
    val allocated = right(
      start.dispatch(TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.UniformIntervals))
    )
    val row = allocated.projection.rows.head
    val original = right(row.grid)
    val cropped =
      right(allocated.dispatch(TemporalWorkspaceAction.Bins(Vector(10L -> 15L, 80L -> 85L))))
    val changed = right(cropped.projection.rows.head.grid)
    assertEquals(
      changed.prepared.components.map(_.weight),
      original.prepared.components.map(_.weight)
    )
    assertEqualsDouble(changed.allocationDiscrepancy, 0.0, 1e-12)
    val ranked = right(right(cropped.projection.query(row.unit, region(cropped, 10, 15))).topK(0))
    assertEqualsDouble(ranked.omittedWeight, original.prepared.ledger.suppliedTotal, 1e-12)
    assertEquals(
      ranked.omittedAllocatedRegionMass,
      right(cropped.projection.query(row.unit, region(cropped, 10, 15))).allocatedRegionMass
    )
  }

  test("source axis changes preserve independent recall clock and reset foreign ROI") {
    val start = opened
    val t = start.temporal.get
    val other = workspace.source.bundles.toVector
      .collect { case m: BundleEntry.Media => m.bundle }
      .find(_.identity != t.projection.coordinate.bundle)
      .get
    val request = t.projection.request
      .copy(selection = TemporalSupport.Selection.Part(other.identity), bins = Vector(0L -> 100L))
    val changed = right(
      start.dispatch(SurfaceWorkspaceAction.Temporal(TemporalWorkspaceAction.Request(request)))
    )
    assertEquals(changed.session, session)
    assert(changed.temporal.get.roi.forall(_.axis == other.primaryAxis))
    assertEquals(
      changed.temporal.get.projection.request.recallWindow,
      t.projection.request.recallWindow
    )
  }

  test("export denial applies equally to temporal views and surface selections") {
    val start = right(SurfaceWorkspaceController.open(denied))
    assertEquals(SurfaceWorkspaceController.encode(start), Left(WorkspaceRefusal.PermissionDenied))
    assertEquals(start.temporal.get.encode, Left(TemporalInspectionCodecs.Error.PermissionDenied))
  }

  test("interactive region and bin limits refuse before all-failed row traversal") {
    val t = right(SurfaceWorkspaceController.open(failed)).temporal.get
    val empty = right(
      TemporalQuery.Region.onTimeAxis(t.projection.coordinate.axis, Vector.empty, Vector.empty)
    )
    assert(t.dispatch(TemporalWorkspaceAction.Region(Vector.fill(128)(empty))).isRight)
    assertEquals(
      t.dispatch(TemporalWorkspaceAction.Region(Vector.fill(129)(empty))),
      Left(TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.TooManyBins))
    )
    assertEquals(
      t.dispatch(TemporalWorkspaceAction.Bins(Vector.fill(129)(0L -> 1L))),
      Left(TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.TooManyBins))
    )
    val descriptor = right(t.encode)
    val json = right(Canonical.parse(descriptor))
    val roi = right(Canonical.parse("""{"intervals":[],"points":[]}"""))
    val excessive =
      Canonical.print(json.mapObject(_.add("roi", io.circe.Json.fromValues(Vector.fill(129)(roi)))))
    assertEquals(
      TemporalWorkspaceController.restore(excessive, session),
      Left(TemporalInspectionCodecs.Error.ForeignView)
    )
    assertEquals(
      TemporalWorkspaceController.restore(excessive, failed),
      Left(TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.TooManyBins))
    )
  }

/** Emit only the admitted synthetic controls for the local browser court. */
object TemporalBrowserFixtures:
  def main(args: Array[String]): Unit =
    require(args.isEmpty)
    import TemporalTestData.*
    import io.circe.Json
    val files = Vector.newBuilder[(String, Json)]
    def write(name: String, value: String): Unit = {
      val _ = files += name -> Json.fromString(value)
    }
    write("timed.json", bytes)
    write("plain.json", right(workspace.exportBytes))
    write(
      "untimed.json",
      right(
        TemporalInspectionCodecs.encode(right(TemporalInspectionCodecs.checked(workspace, None)))
      )
    )
    write("failed.json", right(TemporalInspectionCodecs.encode(failed)))
    write("denied.json", repack(Map.empty, WorkspaceContentGrant.Denied))
    val c = right(SurfaceWorkspaceController.open(session))
    val r = right(c.dispatch(SurfaceWorkspaceAction.Policy(reference)))
    val a = right(
      r.dispatch(
        SurfaceWorkspaceAction.Temporal(
          TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.UniformIntervals)
        )
      )
    )
    val projection = a.temporal.get.projection
    val rows = projection.rows.flatMap { row =>
      projection.regions.zipWithIndex.map { (region, index) =>
        val grid = right(row.grid)
        val geometry = region.geometry.get
        Json.obj(
          "unit" -> Json.fromString(row.unit.value),
          "bin" -> Json.fromString(
            (geometry.intervals.map(i => s"${i.start}:${i.endExclusive}") ++ geometry.points.map(
              p => s"@${p.at}"
            )).mkString(", ")
          ),
          "allocated" -> Json.fromDoubleOrNull(grid.allocatedAt(index).get),
          "lower" -> Json.fromDoubleOrNull(grid.boundsAt(index).get._1),
          "upper" -> Json.fromDoubleOrNull(grid.boundsAt(index).get._2)
        )
      }
    }
    write("expected-grid.json", Canonical.print(Json.fromValues(rows)))
    val original = right(SurfaceWorkspaceController.open(session)).temporal.get
    val quarter = right(
      original.dispatch(TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.Mixture(0.25)))
    )
    write("quarter-view.json", right(quarter.encode))
    val declared = original.projection.request.declarations.zipWithIndex.map((d, i) =>
      d.copy(allocation =
        if i == 0 then TemporalQuery.Allocation.UniformIntervals
        else TemporalQuery.Allocation.SupportOnly
      )
    )
    val heterogeneous = right(
      original.dispatch(
        TemporalWorkspaceAction.Request(original.projection.request.copy(declarations = declared))
      )
    )
    write("heterogeneous-view.json", right(heterogeneous.encode))
    println("TEMPORAL_BROWSER_FIXTURES=" + Canonical.print(Json.obj(files.result()*)))

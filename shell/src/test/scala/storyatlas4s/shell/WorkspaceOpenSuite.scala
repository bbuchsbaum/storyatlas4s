package storyatlas4s.shell

import munit.FunSuite
import storyatlas4s.edition.{ImportedArtifact, WorkspaceTestData}
import storymodel4s.codec.WorkspaceCodecs
import storymodel4s.view.WorkspaceRefusal

class WorkspaceOpenSuite extends FunSuite:
  private lazy val bell = ImportedArtifact.Workspace(
    WorkspaceCodecs.decode(WorkspaceTestData.archives("bell")).toOption.get
  )
  private lazy val wog = ImportedArtifact.Workspace(
    WorkspaceCodecs.decode(WorkspaceTestData.archives("wog")).toOption.get
  )

  test("a late older read cannot replace a later admitted workspace") {
    var snapshots = Vector.empty[OpenSnapshot[ImportedArtifact]]
    val runtime = new WorkspaceOpen[ImportedArtifact](s => snapshots :+= s)
    val older = runtime.begin()
    val later = runtime.begin()
    runtime.complete(later, Right(bell))
    val before = snapshots
    runtime.complete(older, Right(wog))
    assertEquals(snapshots, before)
    assertEquals(snapshots.last.current, Some(bell))
  }

  test("cancellation invalidates pending completions and failure retains the admitted workspace") {
    var latest = OpenSnapshot[ImportedArtifact](None, OpenDisplay.Idle)
    val runtime = new WorkspaceOpen[ImportedArtifact](s => latest = s)
    runtime.complete(runtime.begin(), Right(bell))
    val pending = runtime.begin()
    runtime.cancel()
    runtime.complete(pending, Right(wog))
    assertEquals(latest, OpenSnapshot(Some(bell), OpenDisplay.Cancelled))
    runtime.complete(runtime.begin(), Left(WorkspaceRefusal.PermissionDenied))
    assertEquals(
      latest,
      OpenSnapshot(Some(bell), OpenDisplay.Refused(WorkspaceRefusal.PermissionDenied))
    )
  }

package storyatlas4s.shell

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storyatlas4s.edition.WorkspaceTestData
import storymodel4s.codec.WorkspaceCodecs
import storymodel4s.view.WorkspaceRefusal

class WorkspaceImportSuite extends FunSuite:
  private def file(name: String, text: String) = name -> text.getBytes(UTF_8).toVector
  private lazy val bell = WorkspaceController
    .open(
      WorkspaceCodecs.decode(WorkspaceTestData.archives("bell")).toOption.get
    )
    .toOption
    .get
  private lazy val selected = bell.dispatch(WorkspaceAction.Walk(1)).toOption.get
  private def state(result: Either[WorkspaceRefusal, WorkspaceSession]) = result.toOption.get match
    case WorkspaceSession.Investigation(controller) => controller.state
    case _                                          => fail("expected admitted investigation")

  test("saved state and exact packet open atomically without relying on file names") {
    val files = Vector(
      file("arbitrary-name", WorkspaceTestData.archives("bell")),
      file("another-name", WorkspaceSave.encode(selected))
    )
    assertEquals(state(WorkspaceImport.open(files, None)), selected.state)
    assertEquals(state(WorkspaceImport.open(files.reverse, None)), selected.state)
  }

  test("a descriptor alone needs current exact artifacts; foreign artifacts refuse") {
    val descriptor = file("save.json", WorkspaceSave.encode(selected))
    assertEquals(
      WorkspaceImport.open(Vector(descriptor), None),
      Left(WorkspaceRefusal.MissingRequiredRole)
    )
    assertEquals(state(WorkspaceImport.open(Vector(descriptor), Some(bell))), selected.state)
    assertEquals(
      WorkspaceImport.open(
        Vector(descriptor, file("packet.json", WorkspaceTestData.archives("wog"))),
        Some(bell)
      ),
      Left(WorkspaceRefusal.StaleArtifacts)
    )
  }

  test("ambiguous descriptor and path collisions refuse before replacement") {
    val save = WorkspaceSave.encode(selected)
    assertEquals(
      WorkspaceImport.open(Vector(file("a", save), file("b", save)), Some(bell)),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
    assertEquals(
      WorkspaceImport.open(Vector(file("SAVE", save), file("save", save)), Some(bell)),
      Left(WorkspaceRefusal.DuplicatePath)
    )
  }

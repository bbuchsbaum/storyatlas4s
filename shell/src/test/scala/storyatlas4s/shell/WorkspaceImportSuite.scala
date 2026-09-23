package storyatlas4s.shell

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storyatlas4s.edition.{SourceInput, WorkspaceTestData}
import storymodel4s.codec.{StoryModelCodec, WorkspaceCodecs}
import storymodel4s.core.Addressable
import storymodel4s.recall.RecallRef
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
  private def imported(controller: WorkspaceController) =
    SourceInput.decode(StoryModelCodec.encode(controller.workspace.draft.model)).toOption.get
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

  test("an exact imported source restores checked source state without changing recall state") {
    val local = bell.sourceLocal(bell.workspace.sourceAddresses.keys.head).get
    val choice = ViewChoice.initial.copy(
      selection = Set(local),
      focus = Some(local),
      measurer = MeasurerChoice.Monospace
    )
    val attached = WorkspaceImport
      .attachSource(imported(selected), choice, 1, WorkspaceSession.Investigation(selected))
      .toOption
      .get
    val controller = attached match
      case WorkspaceSession.Investigation(value) => value
      case _                                     => fail("expected attached investigation")

    assertEquals(controller.state.mode, WorkspaceMode.Source)
    assertEquals(controller.state.selection, Set(bell.workspace.sourceAddresses.keys.head))
    assertEquals(controller.state.focus, Some(bell.workspace.sourceAddresses.keys.head))
    assertEquals(controller.state.sourceLens, choice.lens)
    assertEquals(controller.state.sourceZoom, choice.zoom)
    assertEquals(controller.state.sourceHorizon, choice.horizon)
    assertEquals(controller.state.measurer, choice.measurer)
    assertEquals(controller.state.viewport, selected.state.viewport.copy(sourceOffset = 1))
    assertEquals(controller.state.activeRecall, selected.state.activeRecall)
    assertEquals(controller.state.correspondence, selected.state.correspondence)
    assertEquals(controller.state.recallHorizon, selected.state.recallHorizon)
  }

  test("foreign models and unsupported source addresses refuse attachment") {
    val foreign = WorkspaceController
      .open(WorkspaceCodecs.decode(WorkspaceTestData.archives("wog")).toOption.get)
      .toOption
      .get
    assertEquals(
      WorkspaceImport.attachSource(
        imported(foreign),
        ViewChoice.initial,
        0,
        WorkspaceSession.Investigation(bell)
      ),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
    assertEquals(
      WorkspaceImport.attachSource(
        imported(bell),
        ViewChoice.initial,
        0,
        WorkspaceSession.Source(imported(bell))
      ),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )

    val unsupported =
      Addressable[RecallRef].address(RecallRef.Unit(bell.workspace.inventory.units.head.id))
    assertEquals(
      WorkspaceImport.attachSource(
        imported(bell),
        ViewChoice.initial.copy(selection = Set(unsupported), focus = Some(unsupported)),
        0,
        WorkspaceSession.Investigation(bell)
      ),
      Left(WorkspaceRefusal.InvalidSelection)
    )
    assertEquals(
      WorkspaceImport.attachSource(
        imported(bell),
        ViewChoice.initial.copy(focus = Some(unsupported)),
        0,
        WorkspaceSession.Investigation(bell)
      ),
      Left(WorkspaceRefusal.InvalidSelection)
    )
  }

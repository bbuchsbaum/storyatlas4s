package storyatlas4s.shell

import io.circe.Json
import munit.FunSuite
import storyatlas4s.edition.WorkspaceTestData
import storyatlas4s.layout.MonospaceMeasurer
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.*

class WorkspaceControllerSuite extends FunSuite:
  private lazy val workspaces =
    WorkspaceTestData.archives.map((k, v) => k -> WorkspaceCodecs.decode(v).toOption.get)
  private def initial = WorkspaceController.open(workspaces("bell")).toOption.get
  private def ok[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private val u0 = RecallUnitId.unsafe("m1:u0")
  private val ring = SourceNodeRef.Situation(SituationId.unsafe("bell:sit:ring"))

  test("canonical traversal reaches every inventory outcome including untimed and failed units") {
    var controller = initial
    val visited = Vector.newBuilder[RecallUnitId]
    (0 until 4).foreach { _ =>
      controller = ok(controller.dispatch(WorkspaceAction.Walk(1)))
      visited += controller.state.activeRecall.get
    }
    assertEquals(
      visited.result(),
      Vector("m1:u0", "m1:u1", "m1:u2", "m1:u3").map(RecallUnitId.unsafe)
    )
    assertEquals(
      controller.state.focus,
      workspaces("bell").recallAddress(RecallUnitId.unsafe("m1:u3"))
    )
  }

  test(
    "a source candidate retains its recall context and identity across projections and policies"
  ) {
    val inspected = ok(initial.dispatch(WorkspaceAction.Inspect(u0, ring)))
    val source = ok(inspected.dispatch(WorkspaceAction.Mode(WorkspaceMode.Source)))
    val switched = ok(source.dispatch(WorkspaceAction.Policy(ArtifactId.unsafe("authored-b"))))
    assertEquals(switched.state.selection, inspected.state.selection)
    assertEquals(switched.state.focus, inspected.state.focus)
    assertEquals(switched.state.correspondence, Some(Correspondence(u0, ring)))
    assertEquals(switched.sourceChoice.selection.size, 1)
    assertEquals(
      switched.sourceChoice.selection.flatMap(switched.sourceQualified),
      Set(workspaces("bell").sourceAddress(ring).get)
    )
    assertEquals(switched.state.activeRecall, Some(u0))
  }

  test(
    "switching to a compatible result with missing measures retains focus without stale values"
  ) {
    val base = workspaces("bell")
    val original = base.policies.head.record
    val failed = ok(
      MappingResult.checked(
        base.inventory,
        base.source,
        original.policies,
        original.roles,
        original.ledger,
        base.inventory.units.map(u =>
          UnitOutcome.failed(u.id, ProcessingFailure.ProviderFailure("synthetic missing result"))
        )
      )
    )
    val emptyId = ArtifactId.unsafe("missing-measures")
    val workspace = ok(
      WorkspaceCodecs.create(
        base.draft.model,
        base.recall,
        base.inventory,
        None,
        Vector(
          WorkspaceMappingInput(base.policies.head.id, original, None),
          WorkspaceMappingInput(emptyId, failed, None)
        ),
        base.timing,
        WorkspaceOrigin.AuthoredFixture,
        "0" * 40,
        WorkspaceContentGrant.Granted,
        WorkspaceContentGrant.Granted
      )
    )
    val before =
      ok(ok(WorkspaceController.open(workspace)).dispatch(WorkspaceAction.Inspect(u0, ring)))
    assert(before.policy.matrix.row(u0).get.cell(Destination.Target(ring)).get.normalized.nonEmpty)
    val after = ok(before.dispatch(WorkspaceAction.Policy(emptyId)))
    assertEquals(after.state.focus, before.state.focus)
    assertEquals(after.state.selection, before.state.selection)
    assertEquals(after.state.correspondence, before.state.correspondence)
    val cell = after.policy.matrix.row(u0).get.cell(Destination.Target(ring)).get
    assert(
      cell.raw.isEmpty && cell.normalized.isEmpty && cell.transport.isEmpty && cell.posterior.isEmpty
    )
  }

  test("foreign identities and undeclared policies cannot activate even when local ids coincide") {
    assertEquals(
      initial.dispatch(WorkspaceAction.Select(workspaces("wog").recallAddress(u0).get)),
      Left(WorkspaceRefusal.InvalidSelection)
    )
    assertEquals(
      initial.dispatch(WorkspaceAction.Policy(ArtifactId.unsafe("foreign"))),
      Left(WorkspaceRefusal.IncompatiblePolicy)
    )
  }

  test(
    "independent horizons and cursors preserve selection and clip only exact supplied fragments"
  ) {
    val selected = ok(initial.dispatch(WorkspaceAction.Jump(u0)))
    val source = ok(
      selected.dispatch(
        WorkspaceAction.SourcePresentation(
          selected.sourceChoice.copy(horizon = EpistemicHorizon.ReaderAt(12))
        )
      )
    )
    val recall = ok(source.dispatch(WorkspaceAction.RecallHorizon(EpistemicHorizon.ReaderAt(0))))
    val moved = ok(
      recall.dispatch(
        WorkspaceAction.Viewport(
          WorkspaceViewport(
            sourceCursor = Some(ok(Seconds.of(20))),
            recallCursor = Some(ok(Seconds.of(2.5)))
          )
        )
      )
    )
    assertEquals(moved.state.selection, selected.state.selection)
    assertEquals(moved.state.sourceHorizon, EpistemicHorizon.ReaderAt(12))
    assertEquals(moved.state.recallHorizon, EpistemicHorizon.ReaderAt(0))
    assertEquals(ok(moved.recallEvidence(u0)), Vector.empty)
    assertEquals(ok(moved.sourceEvidence(ring)).get.map(_._2), Vector("A bell rang."))
    assertEquals(moved.state.viewport.sourceCursor.map(_.value), Some(20.0))
    assertEquals(moved.state.viewport.recallCursor.map(_.value), Some(2.5))
  }

  test("saved state restores exact identity, policy, horizons, independent clocks and viewport") {
    val selected = ok(initial.dispatch(WorkspaceAction.Inspect(u0, ring)))
    val desired = selected.state.copy(
      policy = ArtifactId.unsafe("authored-b"),
      mode = WorkspaceMode.Source,
      sourceHorizon = EpistemicHorizon.ReaderAt(12),
      recallHorizon = EpistemicHorizon.ReaderAt(12),
      viewport = WorkspaceViewport(
        5,
        3,
        Some(ok(Seconds.of(20))),
        Some(ok(Seconds.of(2.5))),
        Some(ok(ClockSpan.of(1, 5))),
        matrixRow = 2,
        matrixColumn = 1
      )
    )
    val controller = ok(WorkspaceController.restore(selected.workspace, desired))
    val saved = WorkspaceSave.encode(controller)
    assertEquals(ok(WorkspaceSave.decode(saved, controller.workspace)).state, desired)
    assertEquals(
      WorkspaceSave.decode(saved, workspaces("wog")),
      Left(WorkspaceRefusal.StaleArtifacts)
    )
    assert(!saved.contains("A bell rang."))
    assert(!saved.contains("It rang again."))
    val changed =
      WorkspaceSave.json(controller).mapObject(_.add("fixedCut", Json.fromString("wrong")))
    assert(WorkspaceSave.decode(Canonical.print(changed), controller.workspace).isLeft)
    val duplicate = saved.dropRight(1) + ",\"policy\":\"authored-a\"}"
    assert(WorkspaceSave.decode(duplicate, controller.workspace).isLeft)
  }

  test("imported draft source uses existing compilers without acquiring fixture authority") {
    workspaces.values.foreach { workspace =>
      val compiled = ok(
        AppCompiler.compileDraft(
          workspace.draft,
          ViewChoice.initial.copy(measurer = MeasurerChoice.Monospace),
          MonospaceMeasurer.instance
        )
      )
      assertEquals(compiled.flow.provenance.basis, ViewBasis.DraftBuild)
      assertEquals(compiled.scene.provenance.draft, Some(workspace.draft.promotion))
      assertEquals(compiled.canonicalText, workspace.model.source.canonicalText)
      assertEquals(compiled.receipts.toMap.apply("basis"), ViewBasis.DraftBuild.label)
    }
  }

  /** Public producer construction only: a narrow cut and an empty inventory are lawful inputs,
    * independent of the two browser fixtures' whole-domain, four-unit examples.
    */
  private def narrowed(empty: Boolean): SourceRecallWorkspace =
    val base = workspaces("bell")
    val recall =
      if !empty then base.recall
      else
        ok(
          RecallGraph
            .validated(
              base.recall.transcript,
              base.recall.atlas,
              Vector.empty,
              RecallRelations.empty
            )
            .toEither
        )
    val inventory =
      if !empty then base.inventory
      else
        ok(
          RecallInventory.of(
            recall,
            "\\S+".r
              .findAllMatchIn(recall.transcript.canonicalText)
              .map(m => TextSpan.unsafe(m.start, m.end))
              .toVector,
            WordIdPolicy.inputArtifact(Checksum.ofText(recall.transcript.canonicalText))
          )
        )
    val original = base.policies.head.record
    val grain = TargetGrain.SingleLevel(base.source.target(ring).get.level)
    val universe = ok(DeclaredUniverse.of(Vector(ring), grain))
    val policies = ok(
      MappingPolicies.of(
        InferencePolicy.Unspecified("consumer refusal court"),
        ContextPolicy.Unspecified("not modeled"),
        CandidatePolicy.Unknown("not nominated"),
        ReferencePrior.NotApplicable("no measures"),
        DecisionPolicy.NotApplicable("no decision"),
        universe
      )
    )
    val roles = ok(
      UnitRoles.of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
    )
    val outcomes = inventory.units.map(u =>
      UnitOutcome.failed(u.id, ProcessingFailure.ProviderFailure("synthetic failure"))
    )
    val record = ok(
      MappingResult.checked(inventory, base.source, policies, roles, original.ledger, outcomes)
    )
    ok(
      WorkspaceCodecs.create(
        base.draft.model,
        recall,
        inventory,
        None,
        Vector(WorkspaceMappingInput(ArtifactId.unsafe("narrow"), record, None)),
        inventory.units.map(u => u.id -> WorkspaceTiming.Untimed).toMap,
        WorkspaceOrigin.AuthoredFixture,
        "0" * 40,
        WorkspaceContentGrant.Granted,
        WorkspaceContentGrant.Granted
      )
    )

  test(
    "replay refuses a correspondence outside the fixed cut just as interactive inspection does"
  ) {
    val controller = ok(WorkspaceController.open(narrowed(false)))
    val inside = ok(controller.dispatch(WorkspaceAction.Inspect(u0, ring)))
    val outside = SourceNodeRef.Situation(SituationId.unsafe("bell:sit:quiet"))
    assert(controller.workspace.sourceAddress(outside).isDefined)
    assertEquals(
      controller.dispatch(WorkspaceAction.Inspect(u0, outside)),
      Left(WorkspaceRefusal.InvalidSelection)
    )
    val invalid = inside.state.copy(correspondence = Some(Correspondence(u0, outside)))
    assertEquals(
      WorkspaceController.restore(controller.workspace, invalid),
      Left(WorkspaceRefusal.InvalidSelection)
    )
    val forged = WorkspaceSave
      .json(inside)
      .mapObject(
        _.add(
          "correspondence",
          Json.obj(
            "unit" -> Json.fromString(u0.value),
            "target" -> Json.fromString(controller.workspace.sourceAddress(outside).get.render)
          )
        )
      )
    assertEquals(
      WorkspaceSave.decode(Canonical.print(forged), controller.workspace),
      Left(WorkspaceRefusal.InvalidSelection)
    )
    assertEquals(
      ok(WorkspaceSave.decode(WorkspaceSave.encode(inside), controller.workspace)).state,
      inside.state
    )
  }

  test("an empty recall opens and replays without manufacturing a selected unit") {
    val controller = ok(WorkspaceController.open(narrowed(true)))
    assertEquals(controller.workspace.inventory.units.size, 0)
    assertEquals(controller.state.focus, None)
    assertEquals(controller.state.selection, Set.empty[Address])
    assertEquals(
      controller.dispatch(WorkspaceAction.Walk(1)),
      Left(WorkspaceRefusal.InvalidSelection)
    )
    assertEquals(
      ok(WorkspaceSave.decode(WorkspaceSave.encode(controller), controller.workspace)).state,
      controller.state
    )
  }

  test("viewport replay refuses coordinates outside the admitted fixed matrix") {
    val controller = ok(WorkspaceController.open(workspaces("bell")))
    Vector(
      WorkspaceViewport(matrixRow = -1),
      WorkspaceViewport(matrixRow = 4),
      WorkspaceViewport(matrixColumn = -1),
      WorkspaceViewport(matrixColumn = Int.MaxValue)
    ).foreach { viewport =>
      assertEquals(
        controller.dispatch(WorkspaceAction.Viewport(viewport)),
        Left(WorkspaceRefusal.UnsupportedContent)
      )
    }
  }

  test("Back and Return restore qualified state while viewport events retain the return path") {
    val first = ok(WorkspaceHistory.open(initial).dispatch(WorkspaceAction.Walk(1)))
    val second = ok(first.dispatch(WorkspaceAction.Walk(1)))
    val back = second.back
    assertEquals(back.current.state, first.current.state)
    val scrolled = ok(
      back.dispatch(WorkspaceAction.Viewport(back.current.state.viewport.copy(sourceOffset = 1)))
    )
    assert(scrolled.canReturn)
    assertEquals(scrolled.forward.current.state, second.current.state)
    val branched = ok(back.dispatch(WorkspaceAction.Policy(ArtifactId.unsafe("authored-b"))))
    assert(!branched.canReturn)
    assertEquals(WorkspaceHistory.open(initial).back.current.state, initial.state)
  }

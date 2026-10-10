package storyatlas4s.shell

import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import storyatlas4s.edition.{ArtifactInput, ImportedArtifact}
import storymodel4s.align.*
import storymodel4s.align.bridge.TextSourceView
import storymodel4s.codec.{Canonical, SurfaceWorkspaceCodecs, TextSourceArtifactCodec}
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.{WorkspaceContentGrant, WorkspaceRefusal}

/** JVM consumer courts use an actual checked mapping execution; a property can select a producer
  * packet.
  */
class SurfaceWorkspaceControllerSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => fail(error.toString), identity)

  private lazy val syntheticPacket: Vector[Byte] =
    val sourceText = "Anna arrived. Bob left."
    val recallText = "Anna arrived."
    val source = right(TextSourcePackage.fromText(sourceText))
    val view = right(TextSourceView.of(source, SurfaceUnitKind.Sentence))
    val recall = RecallSegmenter.segment(right(StorySource.fromText(recallText)))
    val inventory = right(
      RecallInventory.of(
        recall,
        SurfaceSequence(recall.atlas).lexicalTokens.map(_.span),
        WordIdPolicy.inputArtifact(Checksum.ofText(recallText))
      )
    )
    val candidates = right(
      StrictCandidateGenerator.canonicalLevel(
        StrictSemanticChannel.Lexical,
        right(StrictCandidateConfig.of(2, TieBudgetRequest.AtMost(2), false, None)),
        recall,
        view,
        0
      )
    )
    val pricing = right(
      StrictCostModel.of(
        StrictSemanticChannel.Lexical,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        externalMismatch = 0.0,
        distortionPenalty = 0.0
      )
    )
    val evidence = right(LocalEvidence.compute(recall, view, candidates, pricing))
    val universe = right(
      DeclaredUniverse.of(view.nodes.map(_.ref), TargetGrain.SingleLevel(0))
    )
    val grouping = right(
      StructuredReconstruction.Grouping
        .of(view, view.nodes.zipWithIndex.map((node, i) => node.ref -> i))
    )
    val execution = right(
      MappingRun.compute(
        recall,
        view,
        inventory,
        view.representation,
        evidence,
        right(LocalReference.Profile.of(universe, 1.0)),
        StructuredReconstruction.Profile.sherlock(),
        grouping
      )
    )
    val disclosure = Canonical.print(
      Json.obj(
        "schemaVersion" -> Json.fromString("recall-disclosure-receipt/v1"),
        "scope" -> Json.fromString("local-workspace"),
        "textDigest" -> Json.fromString(Checksum.ofText(recallText).hex),
        "basis" -> Json.obj("kind" -> Json.fromString("public-corpus-admitted"))
      )
    )
    right(
      SurfaceWorkspaceCodecs.create(
        TextSourceArtifactCodec.encode(source),
        SurfaceWorkspaceCodecs.SourceKind.Text(SurfaceUnitKind.Sentence),
        recall,
        inventory,
        right(execution.record(MappingRun.Member.Reference)),
        right(execution.record(MappingRun.Member.Reconstruction)),
        disclosure,
        "a" * 40,
        WorkspaceContentGrant.Granted,
        WorkspaceContentGrant.Granted
      )
    ).exportBytes.toOption.get.getBytes(UTF_8).toVector

  private def packet: Vector[Byte] =
    sys.props.get("storyatlas.surfaceWorkspace.fixture") match
      case Some(value) =>
        val path = Path.of(value)
        if !Files.isRegularFile(path) then
          fail(s"surface workspace fixture is not a regular file: $value")
        Files.readAllBytes(path).toVector
      case None => syntheticPacket

  private def controller: SurfaceWorkspaceController =
    ArtifactInput.open(Vector("producer-output.json" -> packet)).toOption match
      case Some(ImportedArtifact.SurfaceWorkspace(value)) =>
        SurfaceWorkspaceController.open(value).toOption.get
      case _ => fail("configured or runtime-built packet did not admit as a surface workspace")

  test("imported reference and reconstruction policies remain inspectable across universes") {
    val initial = controller
    val ids = initial.investigation.policies.map(_.id)
    assertEquals(ids.map(_.value).toSet, Set("reference", "reconstruction"))
    val selected = ids.foldLeft(initial) { (current, id) =>
      current.dispatch(SurfaceWorkspaceAction.Policy(id)).toOption.get
    }
    assertEquals(selected.state.policy, ids.last)
    assert(
      selected.investigation.recallText(selected.investigation.inventory.units.head.id).nonEmpty
    )
    assert(selected.investigation.source.targets.nonEmpty)
  }

  test("saved inspection selections bind the exact imported packet") {
    val initial = controller
    val recall = initial.investigation.inventory.units.head.id
    val source = initial.investigation.source.targets.head.ref
    val selected = initial
      .dispatch(SurfaceWorkspaceAction.Recall(recall))
      .flatMap(_.dispatch(SurfaceWorkspaceAction.Source(source)))
      .toOption
      .get
    val saved = SurfaceWorkspaceController.encode(selected)

    assertEquals(
      SurfaceWorkspaceController.decode(saved, selected.investigation).toOption.map(_.state),
      Some(selected.state)
    )
    val foreign = saved.replace(selected.investigation.identity.hex, "0" * 64)
    assertEquals(
      SurfaceWorkspaceController.decode(foreign, selected.investigation),
      Left(WorkspaceRefusal.StaleArtifacts)
    )
  }

  test("packet and saved surface descriptor restore through public import in either file order") {
    val initial = controller
    val selected = initial
      .dispatch(SurfaceWorkspaceAction.Recall(initial.investigation.inventory.units.head.id))
      .toOption
      .get
    val descriptor =
      "selection.json" -> SurfaceWorkspaceController.encode(selected).getBytes(UTF_8).toVector
    val files = Vector("packet.json" -> packet, descriptor)
    Vector(files, files.reverse).foreach { ordered =>
      val reopened = WorkspaceImport
        .open(ordered, None)
        .toOption
        .collect { case WorkspaceSession.SurfaceInvestigation(value) =>
          value
        }
        .getOrElse(fail("public import did not restore surface investigation"))
      assertEquals(reopened.state, selected.state)
      assertEquals(reopened.investigation.identity, selected.investigation.identity)
    }
  }

package storyatlas4s.edition

import java.nio.charset.StandardCharsets.UTF_8

import io.circe.Json
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** External consumer courts: these are producer-written files, never constructed by fixture APIs in
  * this test process. The identical bytes run through the JVM and JavaScript decoders.
  */
class WorkspacePacketSuite extends FunSuite:
  import RecallCodecs.given
  private lazy val archives = WorkspaceTestData.archives
  private lazy val opened =
    archives.map((name, text) => name -> WorkspaceCodecs.decode(text).toOption.get)
  private val a = ArtifactId.unsafe("authored-a")
  private val b = ArtifactId.unsafe("authored-b")
  private val historical = ArtifactId.unsafe("historical-lexical")
  private val u0 = RecallUnitId.unsafe("m1:u0")
  private val ring = SourceNodeRef.Situation(SituationId.unsafe("bell:sit:ring"))
  private val quiet = SourceNodeRef.Situation(SituationId.unsafe("bell:sit:quiet"))

  /** Change a member and re-sign its physical metadata, so permission/semantic tests reach the
    * contextual boundary instead of merely breaking the outer byte checksum.
    */
  private def replaceMember(text: String, role: String)(change: Json => Json): String =
    val archive = Canonical.parse(text).toOption.get
    val entries = archive.hcursor.get[Vector[Json]]("entries").toOption.get
    val entry = entries.find(_.hcursor.downField("role").get[String]("kind").contains(role)).get
    val path = entry.hcursor.get[String]("path").toOption.get
    val files = archive.hcursor.get[Vector[Json]]("files").toOption.get
    val original = files.find(_.hcursor.get[String]("path").contains(path)).get
    val changed = Canonical.print(
      change(Canonical.parse(original.hcursor.get[String]("utf8").toOption.get).toOption.get)
    )
    val bytes = changed.getBytes(UTF_8)
    val metadata = entry.hcursor
      .downField("disposition")
      .get[Json]("artifact")
      .toOption
      .get
      .mapObject(
        _.add("checksum", Json.fromString(Checksum.ofBytes(bytes).hex))
          .add("byteLength", Json.fromInt(bytes.length))
      )
    val updated = entry.mapObject(
      _.add(
        "disposition",
        entry.hcursor
          .get[Json]("disposition")
          .toOption
          .get
          .mapObject(_.add("artifact", metadata))
      )
    )
    Canonical.print(
      archive.mapObject(
        _.add("entries", Json.fromValues(entries.map(e => if e == entry then updated else e)))
          .add(
            "files",
            Json.fromValues(
              files.map(f =>
                if f == original then f.mapObject(_.add("utf8", Json.fromString(changed))) else f
              )
            )
          )
      )
    )

  test("both generated packets expose complete fixed cuts and preserve partial source authority") {
    assertEquals(opened.keySet, Set("wog", "bell"))
    opened.foreach { (name, workspace) =>
      assertEquals(workspace.inventory.units.map(_.ordinal), Vector(0, 1, 2, 3))
      assertEquals(workspace.policies.map(_.id).toSet, Set(a, b, historical))
      assertEquals(workspace.policy(a).get.matrix.rows.size, 4)
      assertEquals(
        workspace.policy(a).get.record.policies.universe.targets.size,
        if name == "wog" then 84 else 3
      )
      assertEquals(
        workspace.policy(a).get.matrix.rows.last.outcome.localization,
        LocalizationStatus.NotComputed
      )
    }
    assertEquals(opened("bell").draft.abstentions.size, 3)
    assertEquals(opened("wog").draft.derivation, DerivationRecord.NotSupplied)
    assertNotEquals(opened("bell").recallAddress(u0), opened("wog").recallAddress(u0))
  }

  test(
    "external consumer retains unrenormalized values, missing measures and decoder disagreement"
  ) {
    val workspace = opened("bell")
    val row = workspace.policy(a).get.matrix.row(u0).get
    assertEquals(row.cell(Destination.Target(ring)).get.raw.map(_._2), Vector(0.9))
    assertEquals(row.cell(Destination.Target(ring)).get.normalized, Some(0.25))
    assertEquals(row.cell(Destination.Target(quiet)).get.normalized, Some(0.25))
    assertEquals(row.cell(Destination.External(ExternalState.Intrusion)).get.normalized, Some(0.5))
    assertEquals(row.cell(Destination.Target(ring)).get.posterior, Vector.empty)
    assertEquals(row.outcome.decision.get.rawArgmax.map(_._1), Some(Destination.Target(ring)))
    assertEquals(row.outcome.decision.get.chosen, Some(Destination.Target(quiet)))
    assertEquals(
      workspace.policy(b).get.matrix.row(u0).get.cell(Destination.Target(quiet)).get.normalized,
      Some(0.6)
    )
    assertEquals(
      workspace.policy(a).get.matrix.rows(2).outcome.localization,
      LocalizationStatus.Nonlocalizable
    )
  }

  test(
    "exact discontiguous evidence, repeated inverse references and policy exports are consumable"
  ) {
    val workspace = opened("bell")
    assertEquals(
      workspace.sourceEvidence(ring).toOption.flatten.get.map(_._2),
      Vector("A bell rang.", "The bell rang again.")
    )
    assertEquals(
      workspace.recallEvidence(u0).toOption.get.map(_._2),
      Vector("A bell rang.", "It rang again.")
    )
    assertEquals(workspace.inverse(a, ring).toOption.get.map(_.value), Vector("m1:u0", "m1:u1"))
    val selection = Set(workspace.recallAddress(u0).get)
    val first = WorkspaceSubsetCodec.selected(workspace, a, selection).toOption.get
    val second = WorkspaceSubsetCodec.selected(workspace, b, selection).toOption.get
    assert(first.accessibleText.contains("Policy: authored-a"))
    assert(second.accessibleText.contains("Policy: authored-b"))
    assert(!first.accessibleText.contains("This note is outside the selected evidence."))
    assertNotEquals(first.dataJson, second.dataJson)
    assertEquals(
      WorkspaceSubsetCodec.selected(workspace, a, Set(opened("wog").recallAddress(u0).get)),
      Left(WorkspaceRefusal.InvalidSelection)
    )
  }

  test("both source packets compile through the existing Codex and Atlas draft compilers") {
    opened.values.foreach { workspace =>
      val state = CommonViewState
        .of(Set.empty, None, EpistemicHorizon.Omniscient, EditionSpec.relationLayers)
        .toOption
        .get
      val codex = CodexSpec.forLens(CodexLens.Overview, ChannelBudget.All).toOption.get
      val atlas = AtlasSpec(
        ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden),
        ThreadPolicy.All(PositiveInt.from(12).toOption.get)
      )
      val cp = ViewProvenance
        .draftBuild(
          workspace.draft,
          EditionSpec.compilerVersion,
          CodexCompiler.configurationChecksum(state, codex)
        )
        .toOption
        .get
      val ap = ViewProvenance
        .draftBuild(
          workspace.draft,
          EditionSpec.compilerVersion,
          AtlasCompiler.configurationChecksum(state, atlas)
        )
        .toOption
        .get
      val flow = CodexCompiler(cp).compileDraft(workspace.draft, state, codex).toOption.get
      val scene = AtlasCompiler(ap).compileDraft(workspace.draft, state, atlas).toOption.get
      assertEquals(flow.source.canonicalText, workspace.model.source.canonicalText)
      assertEquals(flow.provenance.basis, ViewBasis.DraftBuild)
      assertEquals(scene.provenance.basis, ViewBasis.DraftBuild)
      assertEquals(scene.provenance.draft, Some(workspace.draft.promotion))
    }
  }

  test("timed Voyage compiles from supplied posterior while untimed input remains inspectable") {
    val timed = WorkspaceVoyage.from(opened("bell"), historical).toOption.get
    val document = timed.document.get
    val scene = document.compile(Set.empty).toOption.get
    assertEquals(timed.units.size, 4)
    assertEquals(document.input.timeline.nodes.size, 3)
    assert(scene.marks.exists(_.isInstanceOf[VoyageMark.UnitAnchor]))
    assertEquals(timed.addresses.keySet, scene.navigation.byAddress.keySet)
    assert(document.provenance.basis.label.contains("synthetic"))
    document.input.matrix.rows.foreach { row =>
      assertEquals(
        row.mass,
        opened("bell")
          .policy(historical)
          .get
          .record
          .outcome(row.unit)
          .get
          .measures
          .posterior
          .get
          .mass
      )
    }
    val untimed = WorkspaceVoyage.from(opened("wog"), historical).toOption.get
    assertEquals(untimed.document, None)
    assertEquals(untimed.units.size, 4)
    assertEquals(
      untimed.units.map(_.disposition).distinct,
      Vector(WorkspaceVoyage.Disposition.Unsupported(WorkspaceVoyage.Unavailable.ClocksNotSupplied))
    )
  }

  test("unsupported schema and changed member bytes fail before any compiler receives a packet") {
    val raw = archives("bell")
    val unknown = raw.replace("workspace-archive/v0.1", "workspace-archive/future")
    assertEquals(WorkspaceCodecs.decode(unknown), Left(WorkspaceRefusal.UnsupportedVersion))
    assert(WorkspaceCodecs.decode(raw.replace("A bell rang.", "A gong rang.")).isLeft)
  }

  test("re-signed foreign recall identity refuses even when local unit IDs coincide") {
    val foreign = Canonical.parse(Canonical.encode(opened("wog").recall)).toOption.get
    val paired = replaceMember(archives("bell"), "Recall")(_ => foreign)
    assert(WorkspaceCodecs.decode(paired).isLeft)
    assertEquals(opened("bell").inventory.units.map(_.id), opened("wog").inventory.units.map(_.id))
  }

  test("re-signed inspection and export denials are enforced at their distinct boundaries") {
    val denied = replaceMember(archives("bell"), "Capabilities")(
      _.mapObject(_.add("inspection", Json.fromString("Denied")))
    )
    assertEquals(WorkspaceCodecs.decode(denied), Left(WorkspaceRefusal.PermissionDenied))
    val inspectOnly = replaceMember(archives("bell"), "Capabilities")(
      _.mapObject(_.add("export", Json.fromString("Denied")))
    )
    val admitted = WorkspaceCodecs.decode(inspectOnly).toOption.get
    assertEquals(admitted.inventory.units.size, 4)
    assertEquals(
      WorkspaceSubsetCodec.selected(admitted, a, Set(admitted.recallAddress(u0).get)),
      Left(WorkspaceRefusal.PermissionDenied)
    )
    assertEquals(
      WorkspaceArchiveCodec.encode(admitted.archive.manifest),
      Left(WorkspaceRefusal.PermissionDenied)
    )
  }

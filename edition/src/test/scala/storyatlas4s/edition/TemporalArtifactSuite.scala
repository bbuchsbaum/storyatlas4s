package storyatlas4s.edition

import java.nio.charset.StandardCharsets.UTF_8
import io.circe.Json
import io.circe.syntax.*
import storymodel4s.codec.OutputCodecs.given
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.*
import munit.FunSuite

/** Existing admitted Anna/Bob control from producer MappingCodecFixture and
  * TemporalInspectionSuite. Synthetic development data, no participant text; repeated source events
  * intentionally test ambiguity.
  */
private[storyatlas4s] object TemporalTestData:
  def right[A](value: Either[?, A]): A =
    value.fold(e => throw new IllegalArgumentException(e.toString), identity)
  val text = StorySource.fromText("Anna arrived. Bob left.").toOption.get
  private val atlas = SurfaceAnalyzer.analyze(text)
  private val names = Vector("Anna", "Bob")
  val recall = right(
    RecallGraph
      .validated(
        text,
        atlas,
        atlas.sentences.zipWithIndex.map { (sentence, i) =>
          RecallUnit(
            RecallUnitId.unsafe(s"u$i"),
            i,
            SpanSet.one(SpanRef(Some(sentence.id), sentence.span)),
            atlas.text(sentence),
            DiscourseFunction.EpisodicAssertion,
            ExpressedUncertainty.Unmarked,
            PropositionSketch(
              Some(Vector("arrive", "leave")(i)),
              Vector(SketchParticipant(SketchRole.Agent, None, names(i))),
              PolarityTag.Positive,
              ModalityTag.Asserted,
              Vector.empty,
              Vector.empty,
              Vector.empty,
              Set(names(i).toLowerCase)
            ),
            None
          )
        },
        RecallRelations.empty
      )
      .toEither
  )
  val inventory = right(
    RecallInventory.of(
      recall,
      "\\S+".r
        .findAllMatchIn(text.canonicalText)
        .map(m => TextSpan.unsafe(m.start, m.end))
        .toVector,
      WordIdPolicy.inputArtifact(Checksum.ofText("synthetic-parser-input"))
    )
  )
  val csv = TimedSourceArtifactCodec.Header.mkString(",") +
    "\n1,Anna arrived.,a,10,20,1,Scene,,Anna\n2,Bob left.,a,30,60,1,Scene,,Bob\n3,Anna arrived.,a,80,90,1,Scene,,Anna\n4,Bob left.,b,10,20,1,Scene,,Bob\n"
  val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"},{"part":"b","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
  val artifact = right(TimedSourceArtifactCodec.encode(csv.getBytes(UTF_8), parts.getBytes(UTF_8)))
  val view = right(TimedSourceArtifactCodec.decode(artifact))
  private val config = right(StrictCandidateConfig.of(4, TieBudgetRequest.AtMost(8), false, None))
  private val candidates = right(
    StrictCandidateGenerator.canonicalLevel(StrictSemanticChannel.Lexical, config, recall, view, 0)
  )
  private val cost = right(
    StrictCostModel.of(
      StrictSemanticChannel.Lexical,
      weights = CostWeights.semanticOnly,
      functionPrior = FunctionPrior.none,
      externalMismatch = 0.0,
      distortionPenalty = 0.0
    )
  )
  private val evidence = right(LocalEvidence.compute(recall, view, candidates, cost))
  private val universe = right(
    DeclaredUniverse.of(view.nodes.filter(_.level == 0).map(_.ref), TargetGrain.SingleLevel(0))
  )
  private val grouping = right(
    StructuredReconstruction.Grouping.of(
      view,
      view.nodes
        .filter(_.parent.isEmpty)
        .sortBy(_.discoursePosition)
        .zipWithIndex
        .map((n, i) => n.ref -> i)
    )
  )
  private val execution = right(
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
  private val disclosure = Canonical.print(
    Json.obj(
      "schemaVersion" -> Json.fromString("recall-disclosure-receipt/v1"),
      "scope" -> Json.fromString("local-workspace"),
      "textDigest" -> Json.fromString(Checksum.ofText(text.rawText).hex),
      "basis" -> Json.obj("kind" -> Json.fromString("public-corpus-admitted"))
    )
  )
  val workspace = right(
    SurfaceWorkspaceCodecs.create(
      artifact,
      SurfaceWorkspaceCodecs.SourceKind.TimedAnnotations,
      recall,
      inventory,
      right(execution.referenceRecord),
      right(execution.reconstructionRecord),
      disclosure,
      "0" * 40,
      WorkspaceContentGrant.Granted,
      WorkspaceContentGrant.Granted
    )
  )
  val clock = RecallTiming.Clock.declared(
    Checksum.ofText("synthetic-recording"),
    RecallTiming.ClockKey.unsafe("synthetic-seconds"),
    Checksum.ofText("synthetic-clock"),
    RecallTiming.RecordingIdentity.Unestablished,
    RecallTiming.Origin.Unestablished
  )
  def sec(n: Long): ExactRational = right(ExactRational.of(n, 1))
  val timing = right(
    RecallTiming.checked(
      inventory,
      clock,
      inventory.words.zipWithIndex.map { (word, index) =>
        RecallTiming.Entry(
          word.id,
          clock,
          index match
            case 0 =>
              RecallTiming.Observation.Interval(
                sec(0),
                sec(2),
                RecallTiming.Basis.SourceReported(Checksum.ofText("synthetic-observation"))
              )
            case 1 =>
              RecallTiming.Observation.OnsetOnly(
                sec(3),
                RecallTiming.Basis.SourceReported(Checksum.ofText("synthetic-observation"))
              )
            case _ => RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
        )
      },
      RecallTiming.Provenance(
        Checksum.ofText("synthetic-correspondence"),
        RecallTiming.RecordingLink.NotEstablished
      )
    )
  )
  val session = right(TemporalInspectionCodecs.checked(workspace, Some(timing)))
  val bytes = right(TemporalInspectionCodecs.encode(session))
  val reference = ArtifactId.unsafe("reference")
  val reconstruction = ArtifactId.unsafe("reconstruction")

  def repack(
      changes: Map[WorkspaceRole, String],
      exportGrant: WorkspaceContentGrant = WorkspaceContentGrant.Granted
  ): String =
    val manifest = workspace.archive.manifest
    val original = manifest.entries
      .map(e => e.role -> new String(manifest.bytes(e.role).get.toArray, UTF_8))
      .toMap
    val content0 = original ++ changes
    val receipt = right(Canonical.parse(content0(WorkspaceRole.Receipt)))
    val members = workspace.policies.map { p =>
      val bytes = content0(WorkspaceRole.Mapping(p.id))
      val record = right(Canonical.parse(bytes))
      Json.obj(
        "id" -> Json.fromString(p.id.value),
        "bytes" -> Json.fromString(Checksum.ofText(bytes).hex),
        "recordClaim" -> record.hcursor.get[Json]("record_digest").toOption.get
      )
    }
    val receiptText =
      Canonical.print(receipt.mapObject(_.add("members", Json.fromValues(members))))
    val caps = WorkspaceArchiveCodec.encodeCapabilities(
      workspace.archive.capabilities
        .copy(declaration = Checksum.ofText(receiptText), exportPermission = exportGrant)
    )
    val content =
      content0 ++ Map(WorkspaceRole.Receipt -> receiptText, WorkspaceRole.Capabilities -> caps)
    val entries = manifest.entries.map { e =>
      val old = manifest.artifact(e.role).get
      val ref = ArtifactRef.fromBytes(
        old.id,
        old.role,
        old.mediaType,
        old.schemaVersion,
        content(e.role).getBytes(UTF_8)
      )
      e.copy(disposition = WorkspaceDisposition.Supplied(ref))
    }
    val rebuilt = right(
      WorkspaceManifest.of(
        manifest.schemaVersion,
        entries,
        entries.map(e => e.path -> content(e.role).getBytes(UTF_8).toVector).toMap
      )
    )
    // A consumer can receive denied-export bytes; production encoders correctly refuse to create them.
    val json = right(Canonical.parse(right(workspace.exportBytes)))
    val changedEntries = json.hcursor.get[Vector[Json]]("entries").toOption.get.map { e =>
      val path = e.hcursor.get[String]("path").toOption.get
      val entry = entries.find(_.path.value == path).get
      e.mapObject(
        _.add(
          "disposition",
          Json.obj(
            "status" -> Json.fromString("Supplied"),
            "artifact" -> rebuilt.artifact(entry.role).get.asJson
          )
        )
      )
    }
    val changedFiles = json.hcursor.get[Vector[Json]]("files").toOption.get.map { f =>
      val path = f.hcursor.get[String]("path").toOption.get
      val entry = entries.find(_.path.value == path).get
      f.mapObject(_.add("utf8", Json.fromString(content(entry.role))))
    }
    Canonical.print(
      json.mapObject(
        _.add("entries", Json.fromValues(changedEntries))
          .add("files", Json.fromValues(changedFiles))
      )
    )

  lazy val denied = right(
    TemporalInspectionCodecs.checked(
      right(SurfaceWorkspaceCodecs.decode(repack(Map.empty, WorkspaceContentGrant.Denied))),
      Some(timing)
    )
  )

  /** Failed imported claims remain selectable; they do not become zero-valued measurements. */
  lazy val failed =
    val changes: Map[WorkspaceRole, String] = workspace.policies.map { policy =>
      val absent =
        Json.obj("status" -> Json.fromString("absent"), "reason" -> Json.fromString("not-computed"))
      val outcomes = policy.outcomes.map(
        _.mapObject(
          _.add(
            "processing_status",
            Json.obj(
              "status" -> Json.fromString("failed"),
              "failure" -> Json.obj(
                "status" -> Json.fromString("provider-failure"),
                "detail" -> Json.fromString("synthetic")
              )
            )
          )
            .add("localization_status", Json.fromString("NotComputed"))
            .add("mapping_links", Json.arr())
            .add("links", Json.arr())
            .add(
              "measures",
              Json.obj(
                "raw" -> Json.arr(),
                "normalized" -> absent,
                "posterior" -> absent,
                "transport" -> absent
              )
            )
            .add("decision", absent)
            .add("stages", absent)
        )
      )
      WorkspaceRole.Mapping(policy.id) -> Canonical.print(
        policy.document.mapObject(_.add("outcomes", Json.fromValues(outcomes)))
      )
    }.toMap
    right(
      TemporalInspectionCodecs.checked(
        right(SurfaceWorkspaceCodecs.decode(repack(changes))),
        Some(timing)
      )
    )

class TemporalArtifactSuite extends FunSuite:
  import TemporalTestData.*
  test("temporal envelope admits exact source inventory and supplied timing") {
    val result =
      ArtifactInput.open(Vector("study.json" -> bytes.getBytes(UTF_8).toVector)).toOption.get
    result match
      case ImportedArtifact.TemporalWorkspace(value) => assertEquals(value, session)
      case _                                         => fail("expected temporal workspace")
  }
  test("changed timing bytes cannot reuse the producer envelope identity") {
    val changed = bytes.replace("synthetic-seconds", "foreign-seconds")
    assertNotEquals(changed, bytes)
    assert(ArtifactInput.open(Vector("study.json" -> changed.getBytes(UTF_8).toVector)).isLeft)
  }

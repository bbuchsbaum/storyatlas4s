package storyatlas4s.shell

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import storymodel4s.codec.{Canonical, SourceRecallWorkspace, WorkspaceArchiveCodec}
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** A view descriptor contains identities and presentation state, never narrative payloads or
  * grants. Reopening always requires the exact checked input packet and rechecks its current
  * capabilities.
  */
object WorkspaceSave:
  val Version = "storyatlas-investigation/v1"
  private type Result[A] = Either[WorkspaceRefusal, A]
  private def str(value: String): Json = Json.fromString(value)
  private def opt[A](value: Option[A])(f: A => Json): Json = value.fold(Json.Null)(f)
  private def horizon(value: EpistemicHorizon): Json = value match
    case EpistemicHorizon.Omniscient       => Json.obj("kind" -> str("omniscient"))
    case EpistemicHorizon.ReaderAt(offset) =>
      Json.obj("kind" -> str("reader"), "offset" -> Json.fromInt(offset))
  private def number(value: Seconds): Json = Json.fromDoubleOrNull(value.value)
  private def binding(workspace: SourceRecallWorkspace): Json = Json.obj(
    "archiveSchema" -> str(WorkspaceArchiveCodec.SchemaVersion),
    "members" -> Json.fromValues(workspace.archive.manifest.entries.map { entry =>
      val disposition = entry.disposition match
        case WorkspaceDisposition.Supplied(ref) =>
          Json.obj(
            "kind" -> str("supplied"),
            "id" -> str(ref.id.value),
            "checksum" -> str(ref.checksum.hex),
            "byteLength" -> Json.fromLong(ref.byteLength)
          )
        case other => Json.obj("kind" -> str(other.toString))
      Json.obj(
        "role" -> str(entry.role.key),
        "path" -> str(entry.path.value),
        "disposition" -> disposition
      )
    })
  )

  def json(controller: WorkspaceController): Json =
    val s = controller.state
    val v = s.viewport
    Json.obj(
      "schemaVersion" -> str(Version),
      "artifacts" -> binding(controller.workspace),
      "policy" -> str(s.policy.value),
      "fixedCut" -> str(controller.policy.record.policies.universe.id.digest.hex),
      "selection" -> Json.fromValues(s.selection.toVector.sorted.map(a => str(a.render))),
      "focus" -> opt(s.focus)(a => str(a.render)),
      "activeRecall" -> opt(s.activeRecall)(a => str(a.value)),
      "correspondence" -> opt(s.correspondence)(c =>
        Json.obj(
          "unit" -> str(c.unit.value),
          "target" -> str(controller.workspace.sourceAddress(c.target).get.render)
        )
      ),
      "mode" -> str(s.mode.toString),
      "lens" -> str(s.sourceLens.toString),
      "narrativeLevel" -> str(s.sourceZoom.narrative.toString),
      "surfaceDetail" -> str(s.sourceZoom.surface.toString),
      "sourceHorizon" -> horizon(s.sourceHorizon),
      "recallHorizon" -> horizon(s.recallHorizon),
      "measurer" -> str(s.measurer.toString),
      "viewport" -> Json.obj(
        "sourceOffset" -> Json.fromInt(v.sourceOffset),
        "recallOrdinal" -> Json.fromInt(v.recallOrdinal),
        "sourceCursor" -> opt(v.sourceCursor)(number),
        "recallCursor" -> opt(v.recallCursor)(number),
        "recallWindow" -> opt(v.recallWindow)(w =>
          Json.obj("start" -> number(w.start), "end" -> number(w.end))
        )
      )
    )
  def encode(controller: WorkspaceController): String = Canonical.print(json(controller))

  /** Canonical descriptors are intentionally strict: unknown fields, duplicate keys and silent
    * fallback enum values do not acquire meaning on replay. This accepts exactly our saved format.
    */
  def decode(text: String, workspace: SourceRecallWorkspace): Result[WorkspaceController] =
    def read[A: Decoder](j: Json, name: String): Result[A] =
      j.hcursor.get[A](name).left.map(_ => WorkspaceRefusal.UnsupportedContent)
    def address(value: String): Result[Address] =
      Address.parse(value).left.map(_ => WorkspaceRefusal.InvalidSelection)
    def unit(value: String): Result[RecallUnitId] =
      RecallUnitId.from(value).left.map(_ => WorkspaceRefusal.InvalidSelection)
    def enumValue[A](raw: String, values: Array[A]): Result[A] =
      values.find(_.toString == raw).toRight(WorkspaceRefusal.UnsupportedContent)
    def readHorizon(j: Json): Result[EpistemicHorizon] = read[String](j, "kind").flatMap {
      case "omniscient" => Right(EpistemicHorizon.Omniscient)
      case "reader"     => read[Int](j, "offset").map(EpistemicHorizon.ReaderAt(_))
      case _            => Left(WorkspaceRefusal.UnsupportedContent)
    }
    def seconds(value: Double): Result[Seconds] =
      Seconds.of(value).left.map(_ => WorkspaceRefusal.UnsupportedContent)
    for
      j <- Canonical.parse(text).left.map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(Canonical.print(j) == text.trim, (), WorkspaceRefusal.UnsupportedContent)
      version <- read[String](j, "schemaVersion")
      _ <- Either.cond(version == Version, (), WorkspaceRefusal.UnsupportedVersion)
      artifacts <- read[Json](j, "artifacts")
      _ <- Either.cond(artifacts == binding(workspace), (), WorkspaceRefusal.StaleArtifacts)
      policyString <- read[String](j, "policy")
      policy <- ArtifactId.from(policyString).left.map(_ => WorkspaceRefusal.IncompatiblePolicy)
      selectionStrings <- read[Vector[String]](j, "selection")
      selection <- selectionStrings.traverse(address)
      focusString <- read[Option[String]](j, "focus")
      focus <- focusString.traverse(address)
      activeString <- read[Option[String]](j, "activeRecall")
      active <- activeString.traverse(unit)
      relationJson <- read[Option[Json]](j, "correspondence")
      relation <- relationJson.traverse { value =>
        for
          u <- read[String](value, "unit").flatMap(unit)
          a <- read[String](value, "target").flatMap(address)
          target <- workspace.sourceAddresses.get(a).toRight(WorkspaceRefusal.InvalidSelection)
        yield Correspondence(u, target)
      }
      mode <- read[String](j, "mode").flatMap(enumValue(_, WorkspaceMode.values))
      lens <- read[String](j, "lens").flatMap(enumValue(_, CodexLens.values))
      narrative <- read[String](j, "narrativeLevel").flatMap(enumValue(_, NarrativeLevel.values))
      surface <- read[String](j, "surfaceDetail").flatMap(enumValue(_, SurfaceDetail.values))
      sourceHorizon <- read[Json](j, "sourceHorizon").flatMap(readHorizon)
      recallHorizon <- read[Json](j, "recallHorizon").flatMap(readHorizon)
      measurer <- read[String](j, "measurer").flatMap(enumValue(_, MeasurerChoice.values))
      viewport <- read[Json](j, "viewport")
      sourceOffset <- read[Int](viewport, "sourceOffset")
      recallOrdinal <- read[Int](viewport, "recallOrdinal")
      sourceTime <- read[Option[Double]](viewport, "sourceCursor").flatMap(_.traverse(seconds))
      recallTime <- read[Option[Double]](viewport, "recallCursor").flatMap(_.traverse(seconds))
      windowJson <- read[Option[Json]](viewport, "recallWindow")
      window <- windowJson.traverse { value =>
        for
          start <- read[Double](value, "start").flatMap(seconds)
          end <- read[Double](value, "end").flatMap(seconds)
          span <- ClockSpan
            .of(start.value, end.value)
            .left
            .map(_ => WorkspaceRefusal.UnsupportedContent)
        yield span
      }
      restored <- WorkspaceController.restore(
        workspace,
        WorkspaceState(
          policy,
          selection.toSet,
          focus,
          active,
          relation,
          mode,
          lens,
          ZoomLevel(narrative, surface),
          sourceHorizon,
          recallHorizon,
          measurer,
          WorkspaceViewport(sourceOffset, recallOrdinal, sourceTime, recallTime, window)
        )
      )
      _ <- Either.cond(encode(restored) == text.trim, (), WorkspaceRefusal.UnsupportedContent)
    yield restored

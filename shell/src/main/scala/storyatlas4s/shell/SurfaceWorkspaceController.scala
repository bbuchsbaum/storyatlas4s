package storyatlas4s.shell

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.SourceNodeRef
import storymodel4s.codec.{Canonical, SurfaceWorkspaceCodecs}
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.WorkspaceRefusal

/** Host-neutral inspection state for one imported surface-workspace packet. */
final case class SurfaceWorkspaceState(
    policy: ArtifactId,
    recall: Option[RecallUnitId],
    source: Option[SourceNodeRef]
)

enum SurfaceWorkspaceAction:
  case Policy(id: ArtifactId)
  case Recall(id: RecallUnitId)
  case Source(ref: SourceNodeRef)
  case Clear

/** Reads imported mapping claims without constructing a MappingResult or execution authority. */
final class SurfaceWorkspaceController private (
    val investigation: SurfaceWorkspaceCodecs.Investigation,
    val state: SurfaceWorkspaceState
):
  def policy: SurfaceWorkspaceCodecs.Policy = investigation.policy(state.policy).get
  def recallText: Option[String] = state.recall.flatMap(investigation.recallText)
  def sourceText: Option[String] = state.source.flatMap(investigation.sourceText)

  def dispatch(
      action: SurfaceWorkspaceAction
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    val next = action match
      case SurfaceWorkspaceAction.Policy(id) => state.copy(policy = id)
      case SurfaceWorkspaceAction.Recall(id) => state.copy(recall = Some(id))
      case SurfaceWorkspaceAction.Source(ref) => state.copy(source = Some(ref))
      case SurfaceWorkspaceAction.Clear => state.copy(recall = None, source = None)
    SurfaceWorkspaceController.restore(investigation, next)

object SurfaceWorkspaceController:
  val Version = "surface-workspace-selection/v1"

  def open(
      investigation: SurfaceWorkspaceCodecs.Investigation
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    investigation.policies.headOption
      .toRight(WorkspaceRefusal.IncompatiblePolicy)
      .flatMap(policy => restore(investigation, SurfaceWorkspaceState(policy.id, None, None)))

  def restore(
      investigation: SurfaceWorkspaceCodecs.Investigation,
      state: SurfaceWorkspaceState
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    if investigation.policy(state.policy).isEmpty then Left(WorkspaceRefusal.IncompatiblePolicy)
    else if !state.recall.forall(id => investigation.recall.unit(id).nonEmpty) ||
      !state.source.forall(ref => investigation.source.target(ref).nonEmpty)
    then Left(WorkspaceRefusal.InvalidSelection)
    else Right(new SurfaceWorkspaceController(investigation, state))

  /** Saved inspection state binds the exact archive identity; it cannot cross packets. */
  def encode(controller: SurfaceWorkspaceController): String = Canonical.print(
    Json.obj(
      "schemaVersion" -> Json.fromString(Version),
      "workspace" -> Json.fromString(controller.investigation.identity.hex),
      "policy" -> Json.fromString(controller.state.policy.value),
      "recall" -> controller.state.recall.fold(Json.Null)(id => Json.fromString(id.value)),
      "source" -> controller.state.source.fold(Json.Null)(ref => Json.fromString(ref.key))
    )
  )

  def decode(
      text: String,
      investigation: SurfaceWorkspaceCodecs.Investigation
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    for
      json <- Canonical.parse(text).left.map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(Canonical.print(json) == text.trim, (), WorkspaceRefusal.UnsupportedContent)
      schema <- json.hcursor.get[String]("schemaVersion").left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      identity <- json.hcursor.get[String]("workspace").left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(schema == Version, (), WorkspaceRefusal.UnsupportedVersion)
      _ <- Either.cond(identity == investigation.identity.hex, (), WorkspaceRefusal.StaleArtifacts)
      policy <- json.hcursor.get[String]("policy").toOption.flatMap(ArtifactId.from(_).toOption)
        .toRight(WorkspaceRefusal.IncompatiblePolicy)
      recall <- json.hcursor.get[Option[String]]("recall").toOption.flatten
        .traverse(RecallUnitId.from(_).left.map(_ => WorkspaceRefusal.InvalidSelection))
      source <- json.hcursor.get[Option[String]]("source").toOption.flatten
        .traverse(key =>
          investigation.source.targets
            .find(_.ref.key == key)
            .map(_.ref)
            .toRight(WorkspaceRefusal.InvalidSelection)
        )
      controller <- restore(investigation, SurfaceWorkspaceState(policy, recall, source))
      _ <- Either.cond(encode(controller) == text.trim, (), WorkspaceRefusal.UnsupportedContent)
    yield controller

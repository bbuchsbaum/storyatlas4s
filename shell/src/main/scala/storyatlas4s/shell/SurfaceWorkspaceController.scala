package storyatlas4s.shell

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.SourceNodeRef
import storymodel4s.codec.{Canonical, SurfaceWorkspaceCodecs, TemporalInspectionCodecs}
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.{ArtifactId, WorkspaceRefusal}

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
  case Temporal(action: TemporalWorkspaceAction)

/** Reads imported mapping claims without constructing a MappingResult or execution authority. */
final class SurfaceWorkspaceController private (
    val investigation: SurfaceWorkspaceCodecs.Investigation,
    val state: SurfaceWorkspaceState,
    val session: TemporalInspectionCodecs.Session,
    val temporal: Option[TemporalWorkspaceController]
):
  def policy: SurfaceWorkspaceCodecs.Policy = investigation.policy(state.policy).get
  def recallText: Option[String] = state.recall.flatMap(investigation.recallText)
  def sourceText: Option[String] = state.source.flatMap(investigation.sourceText)

  def dispatch(
      action: SurfaceWorkspaceAction
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    def selection(next: SurfaceWorkspaceState): Set[Address] =
      next.recall.flatMap(session.recallAddress).toSet ++ next.source
        .flatMap(session.sourceAddress)
        .toSet
    def synchronize(
        next: SurfaceWorkspaceState,
        updated: Option[TemporalWorkspaceController] = temporal
    ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
      updated
        .traverse(
          _.dispatch(TemporalWorkspaceAction.Selection(selection(next))).left
            .map(SurfaceWorkspaceController.refusal)
        )
        .flatMap(value => SurfaceWorkspaceController.restore(session, next, value))
    action match
      case SurfaceWorkspaceAction.Policy(id) =>
        temporal match
          case Some(current) =>
            current
              .switchPolicy(id, selection(state.copy(policy = id)))
              .left
              .map(SurfaceWorkspaceController.refusal)
              .flatMap(next =>
                SurfaceWorkspaceController.restore(session, state.copy(policy = id), Some(next))
              )
          case None => SurfaceWorkspaceController.restore(session, state.copy(policy = id), None)
      case SurfaceWorkspaceAction.Recall(id)  => synchronize(state.copy(recall = Some(id)))
      case SurfaceWorkspaceAction.Source(ref) => synchronize(state.copy(source = Some(ref)))
      case SurfaceWorkspaceAction.Clear => synchronize(state.copy(recall = None, source = None))
      case SurfaceWorkspaceAction.Temporal(action) =>
        temporal
          .toRight(WorkspaceRefusal.InvalidSelection)
          .flatMap(_.dispatch(action).left.map(SurfaceWorkspaceController.refusal).flatMap { next =>
            SurfaceWorkspaceController.restore(session, state, Some(next))
          })

object SurfaceWorkspaceController:
  val Version = "surface-workspace-selection/v1"
  val Version2 = "surface-workspace-selection/v2"

  def open(
      investigation: SurfaceWorkspaceCodecs.Investigation
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    TemporalInspectionCodecs.checked(investigation, None).left.map(refusal).flatMap(open)

  /** Preserve optional supplied timing when a checked temporal packet is imported. */
  def open(
      session: TemporalInspectionCodecs.Session
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    for
      policy <- session.workspace.policies.headOption
        .toRight(WorkspaceRefusal.IncompatiblePolicy)
      temporal = TemporalWorkspaceController
        .open(session, policy.id)
        .flatMap(_.dispatch(TemporalWorkspaceAction.Selection(Set.empty)))
        .toOption
      result <- checked(session, SurfaceWorkspaceState(policy.id, None, None), temporal)
    yield result

  def restore(
      investigation: SurfaceWorkspaceCodecs.Investigation,
      state: SurfaceWorkspaceState,
      temporal: Option[TemporalWorkspaceController] = None
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    TemporalInspectionCodecs
      .checked(investigation, None)
      .left
      .map(refusal)
      .flatMap(session => restore(session, state, temporal))

  /** Derive host selection from the checked receipt, preserving its actual policy and clock. */
  private[shell] def fromTemporal(
      temporal: TemporalWorkspaceController
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    val recalls = temporal.selection.toVector.flatMap(temporal.session.recallAddresses.get)
    val sources = temporal.selection.toVector.flatMap(temporal.session.sourceAddresses.get)
    if recalls.size > 1 || sources.size > 1 then Left(WorkspaceRefusal.InvalidSelection)
    else
      restore(
        temporal.session,
        SurfaceWorkspaceState(
          temporal.projection.policy.id,
          recalls.headOption,
          sources.headOption
        ),
        Some(temporal)
      )

  private def restore(
      session: TemporalInspectionCodecs.Session,
      state: SurfaceWorkspaceState,
      temporal: Option[TemporalWorkspaceController]
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    val investigation = session.workspace
    if investigation.policy(state.policy).isEmpty then Left(WorkspaceRefusal.IncompatiblePolicy)
    else if !state.recall.forall(id => investigation.recall.unit(id).nonEmpty) ||
      !state.source.forall(ref => investigation.source.target(ref).nonEmpty)
    then Left(WorkspaceRefusal.InvalidSelection)
    else checked(session, state, temporal)

  private def checked(
      session: TemporalInspectionCodecs.Session,
      state: SurfaceWorkspaceState,
      temporal: Option[TemporalWorkspaceController]
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    val expected = state.recall.flatMap(session.recallAddress).toSet ++ state.source
      .flatMap(session.sourceAddress)
      .toSet
    if session.workspace.policy(state.policy).isEmpty then Left(WorkspaceRefusal.IncompatiblePolicy)
    else if temporal.exists(_.session != session) then Left(WorkspaceRefusal.StaleArtifacts)
    else if temporal.exists(t => t.projection.policy.id != state.policy || t.selection != expected)
    then Left(WorkspaceRefusal.InvalidSelection)
    else Right(new SurfaceWorkspaceController(session.workspace, state, session, temporal))

  private[shell] def refusal(error: TemporalInspectionCodecs.Error): WorkspaceRefusal = error match
    case TemporalInspectionCodecs.Error.ForeignPolicy    => WorkspaceRefusal.IncompatiblePolicy
    case TemporalInspectionCodecs.Error.PermissionDenied => WorkspaceRefusal.PermissionDenied
    case TemporalInspectionCodecs.Error.ForeignView |
        TemporalInspectionCodecs.Error.ForeignTiming =>
      WorkspaceRefusal.StaleArtifacts
    case TemporalInspectionCodecs.Error.MissingUnit => WorkspaceRefusal.InvalidSelection
    case _                                          => WorkspaceRefusal.UnsupportedContent

  private def fields(controller: SurfaceWorkspaceController): Vector[(String, Json)] =
    Vector(
      "workspace" -> Json.fromString(controller.investigation.identity.hex),
      "policy" -> Json.fromString(controller.state.policy.value),
      "recall" -> controller.state.recall.fold(Json.Null)(id => Json.fromString(id.value)),
      "source" -> controller.state.source.fold(Json.Null)(ref => Json.fromString(ref.key))
    )

  /** Saved state binds export permission, the checked session and any temporal projection. */
  def encode(controller: SurfaceWorkspaceController): Either[WorkspaceRefusal, String] =
    for
      _ <- controller.investigation.exportBytes
      temporal <- controller.temporal.traverse(_.encode.left.map(refusal))
    yield Canonical.print(
      Json.obj(
        (Vector("schemaVersion" -> Json.fromString(Version2)) ++ fields(controller) ++ Vector(
          "session" -> Json.fromString(controller.session.digest.hex),
          "temporal" -> temporal.fold(Json.Null)(Json.fromString)
        ))*
      )
    )

  /** The ordinary workspace overload restores v2 only with an absent timing attachment. */
  def decode(
      text: String,
      investigation: SurfaceWorkspaceCodecs.Investigation
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    Canonical.parse(text).left.map(_ => WorkspaceRefusal.UnsupportedContent).flatMap { json =>
      if json.hcursor.get[String]("schemaVersion").contains(Version2) then
        TemporalInspectionCodecs
          .checked(investigation, None)
          .left
          .map(refusal)
          .flatMap(session => decode(text, session))
      else decodeV1(text, investigation)
    }

  private def decodeV1(
      text: String,
      investigation: SurfaceWorkspaceCodecs.Investigation
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    for
      json <- Canonical.parse(text).left.map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(Canonical.print(json) == text.trim, (), WorkspaceRefusal.UnsupportedContent)
      schema <- json.hcursor
        .get[String]("schemaVersion")
        .left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      identity <- json.hcursor
        .get[String]("workspace")
        .left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(schema == Version, (), WorkspaceRefusal.UnsupportedVersion)
      _ <- Either.cond(identity == investigation.identity.hex, (), WorkspaceRefusal.StaleArtifacts)
      policy <- json.hcursor
        .get[String]("policy")
        .toOption
        .flatMap(ArtifactId.from(_).toOption)
        .toRight(WorkspaceRefusal.IncompatiblePolicy)
      recall <- json.hcursor
        .get[Option[String]]("recall")
        .toOption
        .flatten
        .traverse(RecallUnitId.from(_).left.map(_ => WorkspaceRefusal.InvalidSelection))
      source <- json.hcursor
        .get[Option[String]]("source")
        .toOption
        .flatten
        .traverse(key =>
          investigation.source.targets
            .find(_.ref.key == key)
            .map(_.ref)
            .toRight(WorkspaceRefusal.InvalidSelection)
        )
      controller <- restore(investigation, SurfaceWorkspaceState(policy, recall, source))
      _ <- Either.cond(
        Canonical.print(
          Json.obj((Vector("schemaVersion" -> Json.fromString(Version)) ++ fields(controller))*)
        ) == text.trim,
        (),
        WorkspaceRefusal.UnsupportedContent
      )
    yield controller

  /** Restore v2 only against the already admitted timing-bound session. */
  def decode(
      text: String,
      session: TemporalInspectionCodecs.Session
  ): Either[WorkspaceRefusal, SurfaceWorkspaceController] =
    for
      json <- Canonical.parse(text).left.map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(Canonical.print(json) == text.trim, (), WorkspaceRefusal.UnsupportedContent)
      schema <- json.hcursor
        .get[String]("schemaVersion")
        .left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(schema == Version2, (), WorkspaceRefusal.UnsupportedVersion)
      identity <- json.hcursor
        .get[String]("workspace")
        .left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(
        identity == session.workspace.identity.hex,
        (),
        WorkspaceRefusal.StaleArtifacts
      )
      policy <- json.hcursor
        .get[String]("policy")
        .toOption
        .flatMap(ArtifactId.from(_).toOption)
        .toRight(WorkspaceRefusal.IncompatiblePolicy)
      recall <- json.hcursor
        .get[Option[String]]("recall")
        .toOption
        .flatten
        .traverse(RecallUnitId.from(_).left.map(_ => WorkspaceRefusal.InvalidSelection))
      source <- json.hcursor
        .get[Option[String]]("source")
        .toOption
        .flatten
        .traverse(key =>
          session.workspace.source.targets
            .find(_.ref.key == key)
            .map(_.ref)
            .toRight(WorkspaceRefusal.InvalidSelection)
        )
      digest <- json.hcursor
        .get[String]("session")
        .left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- Either.cond(digest == session.digest.hex, (), WorkspaceRefusal.StaleArtifacts)
      bytes <- json.hcursor
        .get[Option[String]]("temporal")
        .left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      temporal <- bytes.traverse(TemporalWorkspaceController.restore(_, session).left.map(refusal))
      state = SurfaceWorkspaceState(policy, recall, source)
      expected = state.recall.flatMap(session.recallAddress).toSet ++ state.source
        .flatMap(session.sourceAddress)
        .toSet
      _ <- Either.cond(
        temporal.forall(t => t.projection.policy.id == policy && t.selection == expected),
        (),
        WorkspaceRefusal.InvalidSelection
      )
      result <- restore(session, state, temporal)
      encoded <- encode(result)
      _ <- Either.cond(encoded == text.trim, (), WorkspaceRefusal.UnsupportedContent)
    yield result

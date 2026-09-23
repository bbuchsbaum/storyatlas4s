package storyatlas4s.shell

import java.nio.charset.StandardCharsets.UTF_8
import cats.syntax.all.*
import storyatlas4s.edition.{ArtifactInput, ImportedArtifact, ImportedSource}
import storymodel4s.codec.{Canonical, StoryModelCodec}
import storymodel4s.view.{RecallVoyageDocument, WorkspaceRefusal}

enum WorkspaceSession:
  case Investigation(controller: WorkspaceController)
  case Source(source: ImportedSource)
  case Voyage(document: RecallVoyageDocument)

/** Atomic admission of artifacts and an optional saved investigation. A descriptor never grants
  * access to missing artifacts. Opening it alone rechecks against the currently admitted packet.
  */
object WorkspaceImport:
  private def sessionInvestigation(
      session: WorkspaceSession
  ): Either[WorkspaceRefusal, WorkspaceController] = session match
    case WorkspaceSession.Investigation(controller) => Right(controller)
    case _                                          => Left(WorkspaceRefusal.SemanticJoinMismatch)

  /** Attach a source only to the exact workspace model already under investigation. Source
    * addresses are checked through the controller's existing bridge; a renderer-local address is
    * never silently discarded during the mode transition.
    */
  def attachSource(
      source: ImportedSource,
      choice: ViewChoice,
      sourceOffset: Int,
      session: WorkspaceSession
  ): Either[WorkspaceRefusal, WorkspaceSession] =
    for
      controller <- sessionInvestigation(session)
      _ <- Either.cond(
        StoryModelCodec.encode(source.draft) == StoryModelCodec.encode(
          controller.workspace.draft.model
        ),
        (),
        WorkspaceRefusal.SemanticJoinMismatch
      )
      selection <- choice.selection.toVector.traverse(address =>
        controller.sourceQualified(address).toRight(WorkspaceRefusal.InvalidSelection)
      )
      focus <- choice.focus.traverse(address =>
        controller.sourceQualified(address).toRight(WorkspaceRefusal.InvalidSelection)
      )
      restored <- WorkspaceController.restore(
        controller.workspace,
        controller.state.copy(
          selection = selection.toSet,
          focus = focus,
          mode = WorkspaceMode.Source,
          sourceLens = choice.lens,
          sourceZoom = choice.zoom,
          sourceHorizon = choice.horizon,
          measurer = choice.measurer,
          viewport = controller.state.viewport.copy(sourceOffset = sourceOffset)
        )
      )
    yield WorkspaceSession.Investigation(restored)

  def open(
      files: Vector[(String, Vector[Byte])],
      current: Option[WorkspaceController]
  ): Either[WorkspaceRefusal, WorkspaceSession] =
    val names = files.map(_._1)
    if files.isEmpty || files.size > ArtifactInput.MaxFiles ||
      files.map(_._2.size.toLong).sum > ArtifactInput.MaxBytes
    then Left(WorkspaceRefusal.UnsupportedContent)
    else if names.combinations(2).exists(pair => pair(0).equalsIgnoreCase(pair(1))) then
      Left(WorkspaceRefusal.DuplicatePath)
    else
      val descriptors = files.flatMap { case (name, bytes) =>
        val text = new String(bytes.toArray, UTF_8)
        Option.when(text.getBytes(UTF_8).toVector == bytes)(text).flatMap { value =>
          Canonical
            .parse(value)
            .toOption
            .filter(_.hcursor.get[String]("schemaVersion").contains(WorkspaceSave.Version))
            .map(_ => name -> value)
        }
      }
      if descriptors.size > 1 then Left(WorkspaceRefusal.UnsupportedContent)
      else
        val artifacts = files.filterNot(f => descriptors.exists(_._1 == f._1))
        val admitted =
          if artifacts.isEmpty then
            current
              .toRight(WorkspaceRefusal.MissingRequiredRole)
              .map(WorkspaceSession.Investigation(_))
          else
            ArtifactInput.open(artifacts).flatMap {
              case ImportedArtifact.Workspace(workspace) =>
                WorkspaceController.open(workspace).map(WorkspaceSession.Investigation(_))
              case ImportedArtifact.Source(source)   => Right(WorkspaceSession.Source(source))
              case ImportedArtifact.Voyage(document) => Right(WorkspaceSession.Voyage(document))
            }
        for
          session <- admitted
          restored <- descriptors.headOption.traverse { case (_, text) =>
            session match
              case WorkspaceSession.Investigation(controller) =>
                WorkspaceSave
                  .decode(text, controller.workspace)
                  .map(WorkspaceSession.Investigation(_))
              case _ => Left(WorkspaceRefusal.SemanticJoinMismatch)
          }
        yield restored.getOrElse(session)

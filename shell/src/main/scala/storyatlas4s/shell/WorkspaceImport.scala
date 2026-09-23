package storyatlas4s.shell

import java.nio.charset.StandardCharsets.UTF_8
import cats.syntax.all.*
import storyatlas4s.edition.{ArtifactInput, ImportedArtifact, ImportedSource}
import storymodel4s.codec.Canonical
import storymodel4s.view.{RecallVoyageDocument, WorkspaceRefusal}

enum WorkspaceSession:
  case Investigation(controller: WorkspaceController)
  case Source(source: ImportedSource)
  case Voyage(document: RecallVoyageDocument)

/** Atomic admission of artifacts and an optional saved investigation. A descriptor never grants
  * access to missing artifacts. Opening it alone rechecks against the currently admitted packet.
  */
object WorkspaceImport:
  def open(
      files: Vector[(String, Vector[Byte])],
      current: Option[WorkspaceController]
  ): Either[WorkspaceRefusal, WorkspaceSession] =
    val names = files.map(_._1.toLowerCase(java.util.Locale.ROOT))
    if files.isEmpty || files.size > ArtifactInput.MaxFiles ||
      files.map(_._2.size.toLong).sum > ArtifactInput.MaxBytes
    then Left(WorkspaceRefusal.UnsupportedContent)
    else if names.distinct.size != names.size then Left(WorkspaceRefusal.DuplicatePath)
    else
      val descriptors = files.flatMap { case (name, bytes) =>
        val text = new String(bytes.toArray, UTF_8)
        Option.when(text.getBytes(UTF_8).toVector == bytes)(text).flatMap { value =>
          Canonical.parse(value).toOption
            .filter(_.hcursor.get[String]("schemaVersion").contains(WorkspaceSave.Version))
            .map(_ => name -> value)
        }
      }
      if descriptors.size > 1 then Left(WorkspaceRefusal.UnsupportedContent)
      else
        val artifacts = files.filterNot(f => descriptors.exists(_._1 == f._1))
        val admitted =
          if artifacts.isEmpty then
            current.toRight(WorkspaceRefusal.MissingRequiredRole).map(WorkspaceSession.Investigation(_))
          else
            ArtifactInput.open(artifacts).flatMap {
              case ImportedArtifact.Workspace(workspace) =>
                WorkspaceController.open(workspace).map(WorkspaceSession.Investigation(_))
              case ImportedArtifact.Source(source) => Right(WorkspaceSession.Source(source))
              case ImportedArtifact.Voyage(document) => Right(WorkspaceSession.Voyage(document))
            }
        for
          session <- admitted
          restored <- descriptors.headOption.traverse { case (_, text) =>
            session match
              case WorkspaceSession.Investigation(controller) =>
                WorkspaceSave.decode(text, controller.workspace).map(WorkspaceSession.Investigation(_))
              case _ => Left(WorkspaceRefusal.SemanticJoinMismatch)
          }
        yield restored.getOrElse(session)

package storyatlas4s.shell

import storyatlas4s.edition.ImportedArtifact
import storymodel4s.view.WorkspaceRefusal

enum OpenDisplay:
  case Idle, Checking, Cancelled
  case Refused(reason: WorkspaceRefusal)
  case Opened

/** Atomic host-neutral Open transaction; a failed/cancelled/stale attempt never replaces the
  * previous admitted artifact. The host schedules I/O and parsing, and publishes this snapshot.
  */
final case class OpenSnapshot(current: Option[ImportedArtifact], display: OpenDisplay)

final class WorkspaceOpen(publish: OpenSnapshot => Unit):
  private var gate = LatestIntent.empty[Unit, Either[WorkspaceRefusal, ImportedArtifact]]
  private var current = Option.empty[ImportedArtifact]

  def begin(): IntentRevision =
    val (next, intent) = gate.request(())
    gate = next
    publish(OpenSnapshot(current, OpenDisplay.Checking))
    intent.revision

  def complete(revision: IntentRevision, result: Either[WorkspaceRefusal, ImportedArtifact]): Unit =
    val (next, accepted) = gate.complete(revision, result)
    gate = next
    accepted.foreach { value =>
      val display = value.result match
        case Left(problem) => OpenDisplay.Refused(problem)
        case Right(artifact) => current = Some(artifact); OpenDisplay.Opened
      publish(OpenSnapshot(current, display))
    }

  def cancel(): Unit =
    gate = gate.cancel
    publish(OpenSnapshot(current, OpenDisplay.Cancelled))

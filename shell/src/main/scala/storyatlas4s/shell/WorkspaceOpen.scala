package storyatlas4s.shell

import storymodel4s.view.WorkspaceRefusal

enum OpenDisplay:
  case Idle, Checking, Cancelled
  case Refused(reason: WorkspaceRefusal)
  case Opened

/** Atomic host-neutral Open transaction; a failed/cancelled/stale attempt never replaces the
  * previous admitted artifact. The host schedules I/O and parsing, and publishes this snapshot.
  */
final case class OpenSnapshot[A](current: Option[A], display: OpenDisplay)

final class WorkspaceOpen[A](publish: OpenSnapshot[A] => Unit):
  private var gate = LatestIntent.empty[Unit, Either[WorkspaceRefusal, A]]
  private var current = Option.empty[A]

  def begin(): IntentRevision =
    val (next, intent) = gate.request(())
    gate = next
    publish(OpenSnapshot(current, OpenDisplay.Checking))
    intent.revision

  def complete(revision: IntentRevision, result: Either[WorkspaceRefusal, A]): Unit =
    val (next, accepted) = gate.complete(revision, result)
    gate = next
    accepted.foreach { value =>
      val display = value.result match
        case Left(problem)   => OpenDisplay.Refused(problem)
        case Right(artifact) => current = Some(artifact); OpenDisplay.Opened
      publish(OpenSnapshot(current, display))
    }

  def cancel(): Unit =
    gate = gate.cancel
    publish(OpenSnapshot(current, OpenDisplay.Cancelled))

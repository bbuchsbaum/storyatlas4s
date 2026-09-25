package storyatlas4s.intaglio

import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** A display comparison of two supplied group identities, never a new decision or a distance.
  * Missing groups and the incomplete payloads of untimed/unanchored marks remain unknown.
  */
final case class VoyageGroupComparison private (
    unit: RecallUnitId,
    ordinal: Int,
    onset: Option[Seconds],
    drawn: Option[Int],
    argmax: Option[Int]
):
  def status: VoyageGroupComparison.Status = (drawn, argmax) match
    case (Some(a), Some(b)) if a == b => VoyageGroupComparison.Status.Agreement
    case (Some(_), Some(_))           => VoyageGroupComparison.Status.Disagreement
    case _                            => VoyageGroupComparison.Status.Unknown

object VoyageGroupComparison:
  enum Status:
    case Agreement, Disagreement, Unknown

  def records(scene: VoyageScene): Vector[VoyageGroupComparison] =
    val anchors = scene.marks.collect { case a: VoyageMark.UnitAnchor => a.unit -> a }.toMap
    scene.units.sortBy(_.ordinal).map { u =>
      val anchor = anchors.get(u.id)
      VoyageGroupComparison(
        u.id,
        u.ordinal,
        u.onset,
        anchor.flatMap(_.group),
        anchor.flatMap(_.argmax).flatMap(scene.timeline.node).flatMap(_.group)
      )
    }

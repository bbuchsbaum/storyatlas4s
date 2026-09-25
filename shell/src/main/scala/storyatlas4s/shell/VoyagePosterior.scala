package storyatlas4s.shell

import storymodel4s.align.SourceNodeRef
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.{VoyageMark, VoyageScene}

/** Presentation of the complete source candidate inventory carried by a timed anchor's marks.
  * Sorting changes only reading order. Masses, support grain, and the drawn decision stay supplied;
  * a zero-mass fill is a decision, not a posterior candidate. Untimed and unanchored marks do not
  * carry this inventory, so they cannot supply this presentation.
  */
final case class VoyagePosterior private (
    drawn: VoyageMark.UnitAnchor,
    candidates: Vector[VoyagePosterior.Candidate]
)

object VoyagePosterior:
  final case class Candidate(ref: SourceNodeRef, mass: Double, group: Option[Int], drawn: Boolean)

  val orderingNote: String = "Descending supplied mass; ties use the source key, lexicographically."

  def forUnit(scene: VoyageScene, unit: RecallUnitId): Option[VoyagePosterior] =
    scene.marks.collectFirst { case a: VoyageMark.UnitAnchor if a.unit == unit => a }.map { a =>
      val candidates = scene.marks.collect {
        case alt: VoyageMark.Alternative if alt.unit == unit =>
          Candidate(alt.anchor, alt.mass, alt.group, drawn = false)
      } :+ Candidate(a.anchor, a.mass, a.group, drawn = true)
      VoyagePosterior(a, candidates.filter(_.mass > 0).sortBy(c => (-c.mass, c.ref.key)))
    }

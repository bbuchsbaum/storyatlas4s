package storyatlas4s.shell

import storymodel4s.recall.RecallUnitId
import storymodel4s.view.{AnchorOrigin, VoyageMark, VoyageScene}

/** The Recall Voyage inspection filter (workshop ruling 14, `docs/delivery/voyage-workshop`).
  *
  * It selects units for inspection by the facts their marks already carry; it computes no new
  * quantity and chooses no threshold of its own. Every threshold starts unset, and a filter with
  * nothing set matches nothing and dims nothing. Origin, external-dominance, group grain and the
  * thresholds apply to anchored units; `Untimed` and `Unanchored` match those kinds. The result is
  * a display choice: it never enters a projection, a selection, or a receipt.
  */
final case class VoyageFilter(
    criteria: Set[VoyageFilter.Criterion] = Set.empty,
    argmaxMassBelow: Option[Double] = None,
    externalMassAbove: Option[Double] = None,
    localizabilityBelow: Option[Double] = None,
    combine: VoyageFilter.Combine = VoyageFilter.Combine.Any
):
  import VoyageFilter.*

  /** How many criteria and thresholds are set. */
  def active: Int =
    criteria.size + Vector(argmaxMassBelow, externalMassAbove, localizabilityBelow).count(
      _.nonEmpty
    )

  def isSet: Boolean = active > 0

  def toggled(c: Criterion): VoyageFilter =
    copy(criteria = if criteria(c) then criteria - c else criteria + c)

  /** The reasons a unit's mark matches, in a fixed order; empty when it does not match or when the
    * filter is unset. Under `All`, a unit matches only when every set criterion does.
    */
  def reasons(scene: VoyageScene, mark: VoyageMark): Vector[String] =
    if !isSet then Vector.empty
    else
      val hits: Vector[String] = mark match
        case _: VoyageMark.Untimed =>
          Vector(Criterion.Untimed).filter(criteria).map(_.label)
        case _: VoyageMark.Unanchored =>
          Vector(Criterion.Unanchored).filter(criteria).map(_.label)
        case a: VoyageMark.UnitAnchor =>
          val argmaxMass = VoyageFilter.argmaxMass(scene, a)
          Vector(
            Option.when(criteria(Criterion.DecodeBound) && a.origin == AnchorOrigin.DecodeBound)(
              Criterion.DecodeBound.label
            ),
            Option.when(criteria(Criterion.DecodeFilled) && a.origin == AnchorOrigin.DecodeFilled)(
              Criterion.DecodeFilled.label
            ),
            Option.when(criteria(Criterion.ExternalDominant) && a.externalDominant)(
              Criterion.ExternalDominant.label
            ),
            Option.when(criteria(Criterion.GroupGrain) && a.level > 0)(Criterion.GroupGrain.label),
            argmaxMassBelow.flatMap(t =>
              Option.when(argmaxMass.exists(_ < t))(s"argmax mass below ${threshold(t)}")
            ),
            externalMassAbove.flatMap(t =>
              Option.when(a.externalMass > t)(s"external mass above ${threshold(t)}")
            ),
            localizabilityBelow.flatMap(t =>
              Option.when(a.localizability.exists(_ < t))(s"localizability below ${threshold(t)}")
            )
          ).flatten
        case _: VoyageMark.Alternative => Vector.empty
      combine match
        case Combine.Any => hits
        case Combine.All => if hits.size == active then hits else Vector.empty

  /** Every unit's reasons, keyed by unit; units that do not match are absent. */
  def matches(scene: VoyageScene): Map[RecallUnitId, Vector[String]] =
    scene.marks
      .filterNot(_.isInstanceOf[VoyageMark.Alternative])
      .map(m => m.unit -> reasons(scene, m))
      .filter(_._2.nonEmpty)
      .toMap

  /** The next (or previous) timed matching unit after `from` in recall order, if any. */
  def step(scene: VoyageScene, from: Option[RecallUnitId], forward: Boolean): Step =
    if !isSet then Step.NotSet
    else
      val matched = matches(scene)
      val timed =
        scene.units.filter(u => u.onset.nonEmpty && matched.contains(u.id)).sortBy(_.ordinal)
      if matched.isEmpty then Step.NoMatch
      else if timed.isEmpty then Step.AllUntimed(matched.size)
      else
        val at = from.flatMap(id => scene.units.find(_.id == id)).map(_.ordinal)
        val next =
          if forward then timed.find(u => at.forall(u.ordinal > _))
          else timed.reverse.find(u => at.forall(u.ordinal < _))
        next.fold(if forward then Step.NoFurther else Step.NoEarlier)(u => Step.To(u.id))

object VoyageFilter:

  val none: VoyageFilter = VoyageFilter()

  enum Combine:
    case Any, All

  enum Criterion(val label: String):
    case DecodeBound extends Criterion("decode-bound")
    case DecodeFilled extends Criterion("decode-filled")
    case ExternalDominant extends Criterion("external-dominant")
    case GroupGrain extends Criterion("group-grain")
    case Untimed extends Criterion("untimed")
    case Unanchored extends Criterion("unanchored")

  /** What a step through matches found, so a host can say why it did not move. */
  enum Step:
    case To(unit: RecallUnitId)
    case NotSet
    case NoMatch
    case AllUntimed(count: Int)
    case NoFurther
    case NoEarlier

  /** A chip's count and where it comes from. Five counts are `VoyageSummary` fields; group grain
    * has no summary field, so the view counts it and says so.
    */
  final case class Count(value: Int, countedByView: Boolean)

  def count(scene: VoyageScene, c: Criterion): Count =
    val s = scene.summary
    c match
      case Criterion.DecodeBound      => Count(s.decodeBound, false)
      case Criterion.DecodeFilled     => Count(s.decodeFilled, false)
      case Criterion.ExternalDominant => Count(s.externalDominant, false)
      case Criterion.Untimed          => Count(s.untimed, false)
      case Criterion.Unanchored       => Count(s.unanchored, false)
      case Criterion.GroupGrain       =>
        Count(
          scene.marks.count {
            case a: VoyageMark.UnitAnchor => a.level > 0
            case _                        => false
          },
          true
        )

  /** The posterior argmax's supplied mass: the placed anchor's own mass when it is the argmax,
    * otherwise the mass of the admitted anchor the argmax names. `None` when the scene carries
    * neither.
    */
  def argmaxMass(scene: VoyageScene, a: VoyageMark.UnitAnchor): Option[Double] =
    a.argmax.flatMap(ref =>
      if ref == a.anchor then Some(a.mass)
      else
        scene.marks.collectFirst {
          case alt: VoyageMark.Alternative if alt.unit == a.unit && alt.anchor == ref => alt.mass
        }
    )

  /** A threshold printed as the exports print numbers: the shortest plain decimal, identically on
    * every platform and never rounded to a different value.
    */
  private def threshold(t: Double): String = VoyageExport.number(t)

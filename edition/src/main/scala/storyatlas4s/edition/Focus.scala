package storyatlas4s.edition

import storymodel4s.core.Address
import storymodel4s.story.ContextKind
import storymodel4s.view.*

/** Which one object the workspace focuses, and what each face of the view did with it.
  *
  * The shared focus contract (design brief §6) is the most important interaction in the product,
  * and a static edition has to state it rather than perform it. So the edition picks one address by
  * a **printed rule**, compiles every face under that one selection, and prints what each face was
  * able to do with it — on a mark, via an ancestor, or off the projection. Nothing is ever silently
  * dropped, which is the whole point of the three states.
  *
  * The rule is deliberately geometric and deliberately not a judgement of importance: it reads only
  * discourse order and the context frame a situation sits in, both of which the scene supplies.
  */
object Focus:

  /** The chosen object, and everything about it the scene already knew. */
  final case class Chosen(
      address: Address,
      mark: MarkId,
      lane: Int,
      context: ContextKind,
      label: String
  )

  /** Printed on the workspace, so a reader can check the choice rather than trust it. */
  val rule: String =
    "the first situation, in discourse order, that sits in a context frame other than the " +
      "narrated world; ties broken by mark identity"

  /** Apply the rule to a compiled scene.
    *
    * A lane's kind comes from the context band that occupies it, never from a neighbour, so a
    * situation on a lane with no visible band is not eligible: the rule needs to know the frame is
    * not the narrated world, and an unnamed lane cannot answer that.
    */
  def choose(scene: NarrativeScene): Option[Chosen] =
    val kinds: Map[Int, ContextKind] =
      scene.marks.collect { case b: VisualPrimitive.ContextBand => b.lane -> b.kind }.toMap
    scene.marks
      .collect { case l: VisualPrimitive.Landmark => l }
      .sortBy(l => (l.at.x, l.identity.mark.value))
      .flatMap(l => kinds.get(l.at.lane).map(kind => (l, kind)))
      .collectFirst {
        case (l, kind) if kind != ContextKind.NarratedWorld =>
          Chosen(l.identity.address, l.identity.mark, l.at.lane, kind, l.label)
      }

  /** The Atlas marks that carry the focus directly; empty unless the placement is `OnMark`. */
  def marks(scene: NarrativeScene, focus: Option[Chosen]): Set[MarkId] =
    onMark(focus.map(_.address).flatMap(scene.selectionPlacements.get))

  /** The Codex annotations that carry the focus directly; empty unless the placement is `OnMark`.
    */
  def annotations(flow: CodexFlow, focus: Option[Chosen]): Set[AnnotationId] =
    onMark(focus.map(_.address).flatMap(flow.selectionPlacements.get))

  private def onMark[A](placement: Option[SelectionPlacement[A]]): Set[A] = placement match
    case Some(SelectionPlacement.OnMark(marks)) => marks.toVector.toSet
    case _                                      => Set.empty

  /** What a face did with the focus, in the words the contract uses.
    *
    * `None` is not the same as `OffProjection`: it means the face was never asked, because the
    * address is not in the shared selection at all. Collapsing the two would let a plate claim it
    * had considered a selection it never carried.
    */
  def placement[A](placement: Option[SelectionPlacement[A]]): String = placement match
    case Some(SelectionPlacement.OnMark(marks)) =>
      s"on mark (${marks.length} ${if marks.length == 1 then "mark" else "marks"})"
    case Some(SelectionPlacement.ViaAncestor(ancestor)) => s"via ancestor ${ancestor.render}"
    case Some(SelectionPlacement.OffProjection)         => "off projection"
    case None                                           => "not in this view's shared selection"

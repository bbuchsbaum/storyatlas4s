package storyatlas4s.shell

import cats.syntax.all.*
import storymodel4s.core.Address

/** What one rendered target must communicate about selection and focus, in any host.
  *
  * Direct interaction and ancestor proxy treatment stay distinct: a proxy never claims the direct
  * state, and one target may truthfully be direct for one address and proxy for another. The web
  * host maps this to ARIA attributes and CSS classes; a native host maps it to its own accessible
  * name and emphasis. `label` is the complete accessible description.
  */
final case class InteractionPresentation(
    label: String,
    directlySelected: Boolean,
    directlyFocused: Boolean,
    selectionProxyFor: Vector[String],
    focusProxyFor: Vector[String]
):
  def selectionIsComposite: Boolean = directlySelected && selectionProxyFor.nonEmpty
  def focusIsComposite: Boolean = directlyFocused && focusProxyFor.nonEmpty

object InteractionPresentation:

  /** The presentation of every target of a checked index, keyed by its rendered name.
    *
    * Every rendered name is present, decorated or not, so a host never has to decide what an
    * undecorated target means. Each decoration is checked against the index first: a target the
    * index does not know, or one whose visible address differs from the index's, is refused rather
    * than drawn, so no host can present a selection for the wrong address.
    */
  def forTargets[Name](
      targets: RenderedTargetIndex[Name],
      interactions: Vector[InteractionDecoration[Name, Address]],
      label: (Name, Address) => String
  ): Either[InteractionError, Map[String, InteractionPresentation]] =
    interactions.traverse(targets.validate).map { checked =>
      val byTarget = checked.groupBy(_.target)
      targets.renderedNames.iterator.flatMap { rendered =>
        targets.resolve(rendered).map { (name, address) =>
          rendered -> of(label(name, address), byTarget.getOrElse(name, Vector.empty))
        }
      }.toMap
    }

  /** The presentation of one target from its base label and the decorations it carries. */
  def of[Name](
      base: String,
      states: Vector[InteractionDecoration[Name, Address]]
  ): InteractionPresentation =
    InteractionPresentation(
      accessibleLabel(base, states),
      hasDirect(states, InteractionRole.Selection),
      hasDirect(states, InteractionRole.Focus),
      proxyOrigins(states, InteractionRole.Selection),
      proxyOrigins(states, InteractionRole.Focus)
    )

  private def hasDirect[Name](
      states: Vector[InteractionDecoration[Name, Address]],
      role: InteractionRole
  ): Boolean =
    states.exists {
      case InteractionDecoration(_, `role`, SemanticRepresentation.Direct(_)) => true
      case _                                                                  => false
    }

  private def proxyOrigins[Name](
      states: Vector[InteractionDecoration[Name, Address]],
      role: InteractionRole
  ): Vector[String] =
    states
      .collect { case InteractionDecoration(_, `role`, SemanticRepresentation.Proxy(original, _)) =>
        original.render
      }
      .distinct
      .sorted

  private def accessibleLabel[Name](
      base: String,
      states: Vector[InteractionDecoration[Name, Address]]
  ): String =
    val descriptions = states
      .sortBy(value => InteractionDecoration.sortKey(value, _.toString, _.render))
      .map {
        case InteractionDecoration(
              _,
              InteractionRole.Selection,
              SemanticRepresentation.Direct(at)
            ) =>
          s"directly selected ${at.render}"
        case InteractionDecoration(_, InteractionRole.Focus, SemanticRepresentation.Direct(at)) =>
          s"semantic focus ${at.render}"
        case InteractionDecoration(
              _,
              InteractionRole.Selection,
              SemanticRepresentation.Proxy(original, visible)
            ) =>
          s"selection proxy for ${original.render} via ${visible.render}"
        case InteractionDecoration(
              _,
              InteractionRole.Focus,
              SemanticRepresentation.Proxy(original, visible)
            ) =>
          s"focus proxy for ${original.render} via ${visible.render}"
      }
    (base +: descriptions).mkString(". ")

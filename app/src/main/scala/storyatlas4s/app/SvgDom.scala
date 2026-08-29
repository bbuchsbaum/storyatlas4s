package storyatlas4s.app

import org.scalajs.dom
import storymodel4s.core.Address

/** Pure DOM-facing attributes keep direct and proxy semantics testable without a browser. */
private[app] final case class InteractionPresentation(
    label: String,
    directlySelected: Boolean,
    directlyFocused: Boolean,
    selectionProxyFor: Vector[String],
    focusProxyFor: Vector[String]
)

/** The DOM side of the renderer protocol: an Intaglio SVG string goes into a container, its named
  * groups become keyboard-reachable, and direct interaction remains distinct from ancestor proxy
  * treatment. A name resolves to an address only through the caller's compiled navigation index.
  */
object SvgDom:
  val SelectedClass: String = "selected"
  val FocusedClass: String = "focused"
  val SelectionProxyClass: String = "selection-proxy"
  val FocusProxyClass: String = "focus-proxy"

  def inject(
      container: dom.Element,
      svg: String,
      interactions: Vector[InteractionDecoration[String, Address]],
      label: String => Option[String]
  ): Unit =
    container.innerHTML = svg
    val interactionsByName = interactions.groupBy(_.target)
    val named = container.querySelectorAll("[data-name]")
    var index = 0
    while index < named.length do
      val element = named(index).asInstanceOf[dom.Element]
      val name = element.getAttribute("data-name")
      val states = interactionsByName.getOrElse(name, Vector.empty)
      val baseLabel = label(name)
      val presentation = presentationFor(baseLabel.getOrElse(name), states)
      baseLabel.foreach { _ =>
        element.setAttribute("tabindex", "0")
        element.setAttribute("role", "button")
        element.setAttribute("aria-label", presentation.label)
        element.setAttribute("aria-pressed", presentation.directlySelected.toString)
        element.setAttribute("aria-current", presentation.directlyFocused.toString)
      }
      if presentation.directlySelected then element.classList.add(SelectedClass)
      if presentation.directlyFocused then element.classList.add(FocusedClass)
      if presentation.selectionProxyFor.nonEmpty then
        element.classList.add(SelectionProxyClass)
        element.setAttribute(
          "data-selection-proxy-for",
          presentation.selectionProxyFor.mkString(" ")
        )
      if presentation.focusProxyFor.nonEmpty then
        element.classList.add(FocusProxyClass)
        element.setAttribute("data-focus-proxy-for", presentation.focusProxyFor.mkString(" "))
      index += 1

  /** The `data-name` of the nearest named ancestor of an event target, if any. */
  def nameAt(target: dom.EventTarget): Option[String] = target match
    case element: dom.Element =>
      Option(element.closest("[data-name]")).flatMap(e => Option(e.getAttribute("data-name")))
    case _ => None

  private[app] def presentationFor(
      base: String,
      states: Vector[InteractionDecoration[String, Address]]
  ): InteractionPresentation =
    InteractionPresentation(
      accessibleLabel(base, states),
      hasDirect(states, InteractionRole.Selection),
      hasDirect(states, InteractionRole.Focus),
      proxyOrigins(states, InteractionRole.Selection),
      proxyOrigins(states, InteractionRole.Focus)
    )

  private def hasDirect(
      states: Vector[InteractionDecoration[String, Address]],
      role: InteractionRole
  ): Boolean =
    states.exists {
      case InteractionDecoration(_, `role`, SemanticRepresentation.Direct(_)) => true
      case _                                                                  => false
    }

  private def proxyOrigins(
      states: Vector[InteractionDecoration[String, Address]],
      role: InteractionRole
  ): Vector[String] =
    states
      .collect { case InteractionDecoration(_, `role`, SemanticRepresentation.Proxy(original, _)) =>
        original.render
      }
      .distinct
      .sorted

  private def accessibleLabel(
      base: String,
      states: Vector[InteractionDecoration[String, Address]]
  ): String =
    val descriptions = states
      .sortBy(value => InteractionDecoration.sortKey(value, identity, _.render))
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

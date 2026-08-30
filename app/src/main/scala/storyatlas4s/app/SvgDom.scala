package storyatlas4s.app

import cats.syntax.all.*
import org.scalajs.dom
import storymodel4s.core.Address

/** Pure DOM-facing attributes keep direct and proxy semantics testable without a browser. */
private[app] final case class InteractionPresentation(
    label: String,
    directlySelected: Boolean,
    directlyFocused: Boolean,
    selectionProxyFor: Vector[String],
    focusProxyFor: Vector[String]
):
  def selectionIsComposite: Boolean = directlySelected && selectionProxyFor.nonEmpty
  def focusIsComposite: Boolean = directlyFocused && focusProxyFor.nonEmpty

/** The DOM side of the renderer protocol: an Intaglio SVG string goes into a container, its named
  * groups become keyboard-reachable, and direct interaction remains distinct from ancestor proxy
  * treatment. A name resolves to an address only through the caller's compiled navigation index.
  */
private[app] object SvgDom:
  val SelectedClass: String = "selected"
  val FocusedClass: String = "focused"
  val SelectionProxyClass: String = "selection-proxy"
  val FocusProxyClass: String = "focus-proxy"
  val SelectionCompositeClass: String = "selection-direct-proxy"
  val FocusCompositeClass: String = "focus-direct-proxy"

  def inject[Name](
      container: dom.Element,
      svg: String,
      targets: RenderedTargetIndex[Name],
      interactions: Vector[InteractionDecoration[Name, Address]],
      label: (Name, Address) => String
  ): Either[InteractionError, Unit] =
    container.innerHTML = svg
    val named = container.querySelectorAll("[data-name]")
    val actualNames = Vector.tabulate(named.length)(index => named(index).getAttribute("data-name"))
    for
      _ <- targets.validateRendered(actualNames)
      checked <- interactions.traverse(targets.validate)
      renderedInteractions <- checked.traverse(value =>
        targets
          .rendered(value.target)
          .toRight(
            InteractionError.PhantomRenderedTarget(
              targets.surface,
              value.target.toString
            )
          )
          .map(_ -> value)
      )
    yield
      val interactionsByName = renderedInteractions.groupMap(_._1)(_._2)
      var index = 0
      while index < named.length do
        val element = named(index)
        val renderedName = element.getAttribute("data-name")
        targets.resolve(renderedName).foreach { (name, address) =>
          val states = interactionsByName.getOrElse(renderedName, Vector.empty)
          val presentation = presentationFor(label(name, address), states)
          element.setAttribute("tabindex", "0")
          element.setAttribute("role", "button")
          element.setAttribute("aria-label", presentation.label)
          element.setAttribute("aria-pressed", presentation.directlySelected.toString)
          element.setAttribute("aria-current", presentation.directlyFocused.toString)
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
          if presentation.selectionIsComposite then element.classList.add(SelectionCompositeClass)
          if presentation.focusIsComposite then element.classList.add(FocusCompositeClass)
        }
        index += 1

  /** The `data-name` of the nearest named ancestor of an event target, if any. */
  def nameAt(target: dom.EventTarget): Option[String] = target match
    case element: dom.Element =>
      Option(element.closest("[data-name]")).flatMap(e => Option(e.getAttribute("data-name")))
    case _ => None

  private[app] def presentationFor[Name](
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

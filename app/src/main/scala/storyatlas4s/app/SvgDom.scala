package storyatlas4s.app

import org.scalajs.dom
import storyatlas4s.shell.{
  InteractionDecoration,
  InteractionError,
  InteractionPresentation,
  RenderedTargetIndex
}
import storymodel4s.core.Address

/** The DOM side of the renderer protocol: an Intaglio SVG string goes into a container, its named
  * groups become keyboard-reachable, and each carries the shell's host-neutral
  * [[InteractionPresentation]] as ARIA state and CSS classes. A name resolves to an address only
  * through the caller's compiled navigation index.
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
      presentations <- InteractionPresentation.forTargets(targets, interactions, label)
    yield
      var index = 0
      while index < named.length do
        val element = named(index)
        presentations.get(element.getAttribute("data-name")).foreach { presentation =>
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

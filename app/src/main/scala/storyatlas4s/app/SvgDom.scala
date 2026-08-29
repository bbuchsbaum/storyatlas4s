package storyatlas4s.app

import org.scalajs.dom

/** The DOM side of the renderer protocol: an Intaglio SVG string goes into a container, its named
  * groups (`data-name` = mark, annotation, or fragment identity) become keyboard-reachable, and the
  * selected ones are marked. Selection state is expressed as a class plus `aria-pressed`, and the
  * side panel spells it out, so it is never colour-only (V-U5). A name is resolved back to an
  * address only by the caller, through the compiled navigation index.
  */
object SvgDom:
  val SelectedClass: String = "selected"

  def inject(
      container: dom.Element,
      svg: String,
      selected: Set[String],
      label: String => Option[String]
  ): Unit =
    container.innerHTML = svg
    val named = container.querySelectorAll("[data-name]")
    var index = 0
    while index < named.length do
      val element = named(index).asInstanceOf[dom.Element]
      val name = element.getAttribute("data-name")
      label(name).foreach { text =>
        element.setAttribute("tabindex", "0")
        element.setAttribute("role", "button")
        element.setAttribute("aria-label", text)
        element.setAttribute("aria-pressed", if selected.contains(name) then "true" else "false")
      }
      if selected.contains(name) then element.classList.add(SelectedClass)
      index += 1

  /** The `data-name` of the nearest named ancestor of an event target, if any. */
  def nameAt(target: dom.EventTarget): Option[String] = target match
    case element: dom.Element =>
      Option(element.closest("[data-name]")).flatMap(e => Option(e.getAttribute("data-name")))
    case _ => None

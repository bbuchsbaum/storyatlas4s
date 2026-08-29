package storyatlas4s.app

import org.scalajs.dom

/** The DOM side of the renderer protocol: an Intaglio SVG string goes into a container, its named
  * groups (`data-name` = mark, annotation, or fragment identity) become keyboard-reachable, and the
  * selected and semantically focused ones are marked. Selection uses `aria-pressed`; focus uses
  * `aria-current`; both have non-colour classes and textual placements in the audit panel (V-U5). A
  * name resolves to an address only through the caller's compiled navigation index.
  */
object SvgDom:
  val SelectedClass: String = "selected"
  val FocusedClass: String = "focused"

  def inject(
      container: dom.Element,
      svg: String,
      selected: Set[String],
      focused: Set[String],
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
        element.setAttribute("aria-current", if focused.contains(name) then "true" else "false")
      }
      if selected.contains(name) then element.classList.add(SelectedClass)
      if focused.contains(name) then element.classList.add(FocusedClass)
      index += 1

  /** The `data-name` of the nearest named ancestor of an event target, if any. */
  def nameAt(target: dom.EventTarget): Option[String] = target match
    case element: dom.Element =>
      Option(element.closest("[data-name]")).flatMap(e => Option(e.getAttribute("data-name")))
    case _ => None

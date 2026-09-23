package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.shell.*

/** Viewport anchors are source offsets and fixed-cut coordinates, independent of both clocks. */
private[app] object WorkspaceScroll:
  private def elements(root: dom.Element, selector: String): Vector[dom.Element] =
    val nodes = root.querySelectorAll(selector)
    Vector.tabulate(nodes.length)(i => nodes(i).asInstanceOf[dom.Element])

  def bind(
      pane: HtmlElement,
      source: Boolean,
      current: () => WorkspaceController,
      signal: Signal[WorkspaceController],
      dispatch: WorkspaceAction => Unit
  ): HtmlElement =
    var observer = Option.empty[dom.MutationObserver]
    var restoring = false
    var mounted = false
    def restore(): Unit =
      if mounted && pane.ref.clientHeight > 0 then
        val root = pane.ref
        val v = current().state.viewport
        val anchor =
          if source then
            elements(root, "[data-source-offset]")
              .filter(_.getAttribute("data-source-offset").toInt <= v.sourceOffset)
              .lastOption
          else Option(root.querySelector(s"[data-matrix-cell='${v.matrixRow}-${v.matrixColumn}']"))
        restoring = true
        if source && v.sourceOffset == 0 then root.scrollTop = 0
        else
          anchor.foreach { element =>
            root.scrollTop += element.getBoundingClientRect().top - root.getBoundingClientRect().top
          }
        if !source then
          if v.matrixRow == 0 then root.scrollTop = 0
          if v.matrixColumn == 0 then root.scrollLeft = 0
          else
            anchor.foreach { element =>
              root.scrollLeft += element.getBoundingClientRect().left - root
                .getBoundingClientRect()
                .left
            }
        val _ = dom.window.requestAnimationFrame(_ => restoring = false)
    def schedule(): Unit =
      val _ = dom.window.requestAnimationFrame(_ => restore())
    def capture(): Unit =
      if !restoring && mounted then
        val root = pane.ref
        val box = root.getBoundingClientRect()
        val old = current().state.viewport
        val next =
          if source then
            val offset =
              if root.scrollTop <= 0 then 0
              else
                elements(root, "[data-source-offset]")
                  .find(_.getBoundingClientRect().bottom > box.top + 1)
                  .map(_.getAttribute("data-source-offset").toInt)
                  .getOrElse(old.sourceOffset)
            old.copy(sourceOffset = offset)
          else
            val coordinate = elements(root, "[data-matrix-cell]")
              .find { element =>
                val r = element.getBoundingClientRect()
                r.bottom > box.top + 1 && r.right > box.left + 1
              }
              .map(_.getAttribute("data-matrix-cell").split('-').map(_.toInt))
            coordinate.fold(old)(at => old.copy(matrixRow = at(0), matrixColumn = at(1)))
        if next != old then dispatch(WorkspaceAction.Viewport(next))
    pane.amend(
      onScroll --> (_ => capture()),
      onMountCallback { ctx =>
        mounted = true
        val watch = new dom.MutationObserver((_, _) => schedule())
        watch.observe(
          ctx.thisNode.ref,
          new dom.MutationObserverInit {
            childList = true
            subtree = true
          }
        )
        observer = Some(watch)
        signal.map(_.state.mode).distinct.foreach(_ => schedule())(using ctx.owner)
        schedule()
      },
      onUnmountCallback { _ =>
        mounted = false
        observer.foreach(_.disconnect())
        observer = None
      }
    )
    pane

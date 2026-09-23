package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.shell.*

/** Viewport anchors are source offsets and fixed-cut coordinates, independent of both clocks. */
private[app] object WorkspaceScroll:
  private def elements(root: dom.Element, selector: String): Vector[dom.Element] =
    val nodes = root.querySelectorAll(selector)
    Vector.tabulate(nodes.length)(i => nodes(i).asInstanceOf[dom.Element])

  /** The matrix keeps its header row and recall column sticky. A cell is only visible to the reader
    * below and to the right of them, so restore, capture and the displayed range all use this inset
    * box rather than the pane's outer corner.
    */
  def matrixBox(root: dom.Element): (Double, Double, Double, Double) =
    val box = root.getBoundingClientRect()
    val top = Option(root.querySelector("thead"))
      .map(_.getBoundingClientRect().bottom)
      .filter(_ > box.top)
      .getOrElse(box.top)
    val left = Option(root.querySelector("tbody th"))
      .map(_.getBoundingClientRect().right)
      .filter(_ > box.left)
      .getOrElse(box.left)
    val right = Option(root.querySelector("tbody td.processing-column"))
      .map(_.getBoundingClientRect().left)
      .filter(r => r > left && r < box.right)
      .getOrElse(box.right)
    (top, left, box.bottom, right)

  /** Rows and columns whose cells lie wholly inside the inset box; None while the pane is hidden.
    */
  def matrixVisible(root: dom.Element, rows: Int, columns: Int): Option[(MatrixSpan, MatrixSpan)] =
    if root.getBoundingClientRect().width <= 0 then None
    else
      val (top, left, bottom, right) = matrixBox(root)
      val seen = elements(root, "[data-matrix-cell]").flatMap { element =>
        val r = element.getBoundingClientRect()
        Option.when(
          r.width > 0 && r.top >= top - 1 && r.bottom <= bottom + 1 && r.left >= left - 1 &&
            r.right <= right + 1
        )(element.getAttribute("data-matrix-cell").split('-').map(_.toInt))
      }
      Option.when(seen.nonEmpty)(
        MatrixRange.span(seen.map(_(0)).min, seen.map(_(0)).max, rows) ->
          MatrixRange.span(seen.map(_(1)).min, seen.map(_(1)).max, columns)
      )

  def bind(
      pane: HtmlElement,
      source: Boolean,
      current: () => WorkspaceController,
      signal: Signal[WorkspaceController],
      dispatch: WorkspaceAction => Unit,
      restorations: EventStream[Unit]
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
        if source then
          if v.sourceOffset == 0 then root.scrollTop = 0
          else
            anchor.foreach { element =>
              root.scrollTop += element.getBoundingClientRect().top - root
                .getBoundingClientRect()
                .top
            }
        else
          if v.matrixRow == 0 then root.scrollTop = 0
          if v.matrixColumn == 0 then root.scrollLeft = 0
          anchor.foreach { element =>
            val (top, left, _, _) = matrixBox(root)
            if v.matrixRow != 0 then root.scrollTop += element.getBoundingClientRect().top - top
            if v.matrixColumn != 0 then
              root.scrollLeft += element.getBoundingClientRect().left - left
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
            val (top, left, _, _) = matrixBox(root)
            val coordinate = elements(root, "[data-matrix-cell]")
              .find { element =>
                val r = element.getBoundingClientRect()
                r.bottom > top + 1 && r.right > left + 1
              }
              .map(_.getAttribute("data-matrix-cell").split('-').map(_.toInt))
            coordinate.fold(old)(at => old.copy(matrixRow = at(0), matrixColumn = at(1)))
        if next != old then dispatch(WorkspaceAction.Viewport(next))
    pane.amend(
      onScroll --> (_ => capture()),
      onMountCallback { ctx =>
        mounted = true
        val watch = new dom.MutationObserver((_, _) => schedule())
        if source then
          watch.observe(
            ctx.thisNode.ref,
            new dom.MutationObserverInit {
              childList = true
              subtree = true
            }
          )
          observer = Some(watch)
        // Matrix cells are stable DOM. Their changing labels must not reset a user's scroll.
        signal
          .map(c => (c.state.mode, c.state.policy))
          .distinct
          .foreach(_ => schedule())(using ctx.owner)
        restorations.foreach(_ => schedule())(using ctx.owner)
        schedule()
      },
      onUnmountCallback { _ =>
        mounted = false
        observer.foreach(_.disconnect())
        observer = None
      }
    )
    pane

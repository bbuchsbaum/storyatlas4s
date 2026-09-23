package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Local workspace host with the built-in source fixture as its initial example. Canonical files
  * are admitted at runtime without rebuilding app.js. Existing generated Voyage pages retain their
  * embedded-document entry and static no-script fallback.
  */
object Main:
  def main(args: Array[String]): Unit =
    val mount = dom.document.getElementById("app")
    Option(dom.document.getElementById(storyatlas4s.edition.VoyagePage.DocumentElementId)) match
      case Some(script) =>
        // The page carries the static plate as its no-script fallback; the pane replaces it rather
        // than stacking under it.
        renderOnDomContentLoaded(
          { mount.innerHTML = ""; mount.removeAttribute("class"); mount },
          VoyageView.fromDocumentText(script.textContent)
        )
      case None =>
        renderOnDomContentLoaded(mount, WorkspaceHost())

package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.layout.DomMeasurer
import storymodel4s.fixtures.wog.WarOfTheGhostsModel

/** Entry point: the War of the Ghosts fixture is the same `WarOfTheGhostsModel.model` object the
  * CLI edition compiles, linked into `app.js` from storymodel4s `fixtures` (never copied into this
  * repository). No fetch, no server, no external resource: `index.html` loads `app.js` and the
  * shell mounts into `#app`.
  *
  * A page that carries a Recall Voyage document in an inline script (the `voyage.html` the CLI
  * writes, ADR 0002 §14 D4) mounts the voyage pane over that document instead; the document is
  * decoded and its scene compiled in the browser, so the evidence law runs where the marks draw.
  */
object Main:
  def main(args: Array[String]): Unit =
    val mount = dom.document.getElementById("app")
    Option(dom.document.getElementById(storyatlas4s.edition.VoyagePage.DocumentElementId)) match
      case Some(script) =>
        renderOnDomContentLoaded(mount, VoyageView.fromDocumentText(script.textContent))
      case None =>
        renderOnDomContentLoaded(mount, AppView(WarOfTheGhostsModel.model, DomMeasurer.canvas()))

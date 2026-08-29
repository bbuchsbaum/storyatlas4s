package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.layout.DomMeasurer
import storymodel4s.fixtures.wog.WarOfTheGhostsModel

/** Entry point: the War of the Ghosts fixture is the same `WarOfTheGhostsModel.model` object the
  * CLI edition compiles, linked into `app.js` from storymodel4s `fixtures` (never copied into this
  * repository). No fetch, no server, no external resource: `index.html` loads `app.js` and the
  * shell mounts into `#app`.
  */
object Main:
  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      AppView(WarOfTheGhostsModel.model, DomMeasurer.canvas())
    )

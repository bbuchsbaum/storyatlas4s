package storyatlas4s.shell

import _root_.intaglio.Scene
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*

/** One renderer-neutral drawing the shell hands to a host: an Intaglio scene, the pixel box it was
  * laid out for, and its accessible title.
  *
  * The host chooses the backend (StoryAtlas ADR 0001). The web host renders [[svg]]; a canvas host
  * draws the same scene with its own Intaglio renderer. Nothing scientific is decided by the
  * choice, and a rendering failure never changes the view state that produced the plate.
  */
final case class Plate(scene: Scene, widthPx: Int, heightPx: Int, title: String):

  /** The plate as a standalone SVG document, or the renderer's stated reason it cannot be one. */
  def svg: Either[String, String] =
    SvgOptions(widthPx, heightPx, Some(title)).left
      .map(_.message)
      .flatMap(options => SvgRenderer.render(scene, options).bimap(_.message, _.value))

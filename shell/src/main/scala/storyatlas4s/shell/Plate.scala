package storyatlas4s.shell

import _root_.intaglio.{Scene, value}
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.intaglio.GraphicsNames

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

/** A plate together with the checked index of the names drawn on it.
  *
  * A host resolves a hit only through the index of the plate it hit (StoryAtlas ADR 0001). Keeping
  * the two in one value makes that the only thing a host can do: the pair is built only when the
  * plate's rendered names and the index's names are exactly the same set, so a name picked on this
  * plate can never be resolved against another plate's index.
  */
final class TargetedPlate[Name] private (val plate: Plate, val targets: RenderedTargetIndex[Name]):
  override def equals(other: Any): Boolean = other match
    case that: TargetedPlate[?] => plate == that.plate && targets == that.targets
    case _                      => false
  override def hashCode(): Int = (plate, targets).hashCode
  override def toString: String = s"TargetedPlate(${plate.title}, ${targets.size} targets)"

object TargetedPlate:
  /** Pair a plate with its index, refusing any name drawn but not indexed, or indexed but absent.
    */
  def of[Name](
      plate: Plate,
      targets: RenderedTargetIndex[Name]
  ): Either[InteractionError, TargetedPlate[Name]] =
    targets
      .validateRendered(GraphicsNames.collect(plate.scene).map(_.value))
      .map(_ => new TargetedPlate(plate, targets))

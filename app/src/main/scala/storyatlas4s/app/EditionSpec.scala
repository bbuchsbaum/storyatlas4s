package storyatlas4s.app

import storymodel4s.story.RelationLayer
import storymodel4s.view.{CodexLens, NarrativeLevel}

/** The edition's fixed configuration, mirrored from `cli/Edition` (JVM) so the browser compiles the
  * same artifacts the static edition wrote: the page box, the font request, the relation layers,
  * the thread budget, the lenses, and the zoom levels. `cli` is JVM-only, so the values are
  * repeated here rather than shared; the receipts panel prints every one of them.
  */
object EditionSpec:
  val pageWidthPx: Int = 480
  val pageHeightPx: Int = 640
  val fontFamily: String = "monospace"
  val fontSizePx: Int = 16

  val relationLayers: Set[RelationLayer] = Set(RelationLayer.Causal, RelationLayer.Reference)
  val threadMax: Int = 3

  /** The view compilers are storymodel4s's; the version recorded is its pinned revision. */
  val compilerVersion: String = s"storymodel4s@${Pins.storymodel4sRevision}"

  val lenses: Vector[CodexLens] = Vector(CodexLens.Reading, CodexLens.Overview)
  val levels: Vector[NarrativeLevel] =
    Vector(NarrativeLevel.Story, NarrativeLevel.Episode, NarrativeLevel.Scene)

  /** The Atlas document box in CSS pixels; the SVG carries a `viewBox`, so the shell scales it. */
  val atlasWidthPx: Int = 1200
  val atlasHeightPx: Int = 360

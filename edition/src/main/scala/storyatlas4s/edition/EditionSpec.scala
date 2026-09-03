package storyatlas4s.edition

import storymodel4s.story.RelationLayer
import storymodel4s.view.{CodexLens, NarrativeLevel, SurfaceDetail, ZoomLevel}

/** The edition's fixed configuration, shared by `cli/Edition` (JVM) and the browser shell (JS) so
  * both compile the same artifacts: the page box, the font request, the relation layers, the thread
  * budget, the lenses, the zoom levels, and the SVG document boxes. Every receipt prints the values
  * it was built under.
  */
object EditionSpec:
  /** The publication page: a 480x640px box of 16px monospace (50 columns, 33 lines) under the fixed
    * metric measurer, so every break is checkable by counting characters, identical on any platform
    * (ADR 0002 D3), and the fixture spans several pages.
    */
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
  val surfaceDetails: Vector[SurfaceDetail] =
    Vector(SurfaceDetail.Hidden, SurfaceDetail.Sentences, SurfaceDetail.Tokens)

  /** Every independently configured semantic state emitted by the edition and offered live. */
  val zoomLevels: Vector[ZoomLevel] =
    levels.flatMap(level => surfaceDetails.map(surface => ZoomLevel(level, surface)))

  /** The Atlas document box in CSS pixels (the SVG carries a `viewBox`, so a shell may scale it).
    */
  val atlasWidthPx: Int = 1600
  val atlasHeightPx: Int = 1080
  def atlasBox: String = s"${atlasWidthPx}x${atlasHeightPx}px"

  /** The flow-level Codex overlay document box (`codex-<lens>.svg`). */
  val codexOverlayWidthPx: Int = 1600
  val codexOverlayHeightPx: Int = 640
  def codexOverlayBox: String = s"${codexOverlayWidthPx}x${codexOverlayHeightPx}px"

  def pageBox: String = s"${pageWidthPx}x${pageHeightPx}px"

  /** The workspace: one reading pane of exact text beside one Atlas plate, under one selection.
    *
    * The page is wider than the publication page because the workspace's job is reading, and a
    * 40-column column is a specimen rather than a text. At 16px monospace a 640px page is about 66
    * columns, which is inside the comfortable measure the design brief asks for, and the type is
    * the same 16px the brief makes acceptance-bearing — the pane is never miniaturised to make the
    * plate fit.
    */
  val workspacePageWidthPx: Int = 640
  val workspacePageHeightPx: Int = 960

  /** The plate beside it. Narrower than the standalone plate, so its label budget is composed for
    * the width it actually gets rather than scaled down into illegibility.
    */
  val workspaceAtlasWidthPx: Int = 1240
  val workspaceAtlasHeightPx: Int = 1080

  /** The one zoom and lens the workspace joins: the level at which situations exist, and the lens
    * that admits every structural channel.
    */
  val workspaceZoom: ZoomLevel = ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden)
  val workspaceLens: CodexLens = CodexLens.Overview

  def workspaceBox: String =
    s"${workspacePageWidthPx}x${workspacePageHeightPx}px + ${workspaceAtlasWidthPx}x${workspaceAtlasHeightPx}px"

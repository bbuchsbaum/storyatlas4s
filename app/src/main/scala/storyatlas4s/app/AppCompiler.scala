package storyatlas4s.app

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.edition.{EditionSpec, Pins}
import storyatlas4s.intaglio.{AtlasLowering, CodexLowering, GraphicsNames, PagedCodexLowering}
import storyatlas4s.layout.{Measurer, PageSpec, PaginatedCodex, Paginator, TextStyle}
import storymodel4s.core.*
import storymodel4s.story.*
import storymodel4s.view.*

/** One placed line of the text rail: its fragment id, its exact text, and whether a selected
  * annotation has a piece on it.
  */
final case class CodexLine(id: String, text: String, selected: Boolean)

/** One page of the Codex: the rail lines and the page's overlay SVG (V-I2 names inside). */
final case class CodexPage(index: Int, lines: Vector[CodexLine], overlay: String)

/** Everything the shell draws for one [[ViewChoice]]: both artifacts compiled in storymodel4s under
  * one `CommonViewState`, the paginated Codex, the lowered overlays and Atlas, the selection's
  * placements in both, and the receipts. Nothing here is inferred: every field is read off a
  * compiled artifact or a receipt.
  */
final case class Compiled(
    choice: ViewChoice,
    state: CommonViewState,
    flow: CodexFlow,
    placed: PaginatedCodex,
    rows: Vector[PagedCodexLowering.Row],
    pages: Vector[CodexPage],
    scene: NarrativeScene,
    atlasSvg: String,
    atlasNames: Int,
    selectedMarks: Set[String],
    selectedFragments: Set[String],
    fragmentTargets: Map[String, Address],
    codexPlacements: Vector[(Address, SelectionPlacement[AnnotationId])],
    atlasPlacements: Vector[(Address, SelectionPlacement[MarkId])],
    receipts: Vector[(String, String)]
):
  def canonicalText: String = flow.source.canonicalText
  def pieces: Int = placed.annotationFragments.length

/** The pure step from a choice to what is drawn: the same calls `cli/Edition` makes, in the
  * browser.
  */
object AppCompiler:

  def compile(
      model: StoryModel[ModelStatus.Validated],
      choice: ViewChoice,
      measurer: Measurer
  ): Either[String, Compiled] =
    val text = model.source.canonicalText
    for
      state <- CommonViewState
        .of(
          selection = choice.selection,
          horizon = choice.horizon,
          relationLayers = EditionSpec.relationLayers
        )
        .left
        .map(_.message)
      threads <- PositiveInt
        .from(EditionSpec.threadMax)
        .map(ThreadPolicy.All.apply)
        .left
        .map(_.message)
      // Codex: compile, paginate, lower one overlay per page.
      spec <- CodexSpec.forLens(choice.lens, ChannelBudget.All).left.map(_.message)
      codexConfig = CodexCompiler.configurationChecksum(state, spec)
      codexProvenance <- ViewProvenance
        .fixture(model.source.canonicalChecksum, EditionSpec.compilerVersion, codexConfig)
        .left
        .map(_.message)
      flow <- CodexCompiler(codexProvenance).compile(model, state, spec).left.map(_.message)
      page <- PageSpec.of(EditionSpec.pageWidthPx, EditionSpec.pageHeightPx).left.map(_.message)
      style <- TextStyle.of(EditionSpec.fontFamily, EditionSpec.fontSizePx).left.map(_.message)
      placed <- Paginator.layout(flow, page, style, measurer).left.map(_.message)
      rows <- PagedCodexLowering.rows(placed).left.map(_.message)
      overlayScenes <- CodexLowering.lower(placed).left.map(_.message)
      overlayOptions <- SvgOptions(
        page.widthPx,
        page.heightPx,
        Some(s"Narrative Codex overlay — lens ${choice.lens}")
      ).left.map(_.message)
      overlays <- overlayScenes.traverse(scene =>
        SvgRenderer.render(scene, overlayOptions).bimap(_.message, _.value)
      )
      _ <-
        if overlays.length == placed.pages.length then Right(())
        else Left(s"${overlays.length} overlays for ${placed.pages.length} pages")
      // Atlas: compile at the chosen level, lower, render.
      atlasSpec = AtlasSpec(ZoomLevel(choice.level, SurfaceDetail.Hidden), threads)
      atlasConfig = AtlasCompiler.configurationChecksum(state, atlasSpec)
      atlasProvenance <- ViewProvenance
        .fixture(model.source.canonicalChecksum, EditionSpec.compilerVersion, atlasConfig)
        .left
        .map(_.message)
      scene <- AtlasCompiler(atlasProvenance).compile(model, state, atlasSpec).left.map(_.message)
      orderedSelection = choice.selection.toVector.sortBy(_.render)
      codexPlacements <- orderedSelection.traverse(address =>
        flow.selectionPlacements
          .get(address)
          .toRight(s"Codex compiler omitted placement for ${address.render}")
          .map(address -> _)
      )
      atlasPlacements <- orderedSelection.traverse(address =>
        scene.selectionPlacements
          .get(address)
          .toRight(s"Atlas compiler omitted placement for ${address.render}")
          .map(address -> _)
      )
      lowered <- AtlasLowering.lower(scene, text.length).left.map(_.message)
      atlasOptions <- SvgOptions(
        EditionSpec.atlasWidthPx,
        EditionSpec.atlasHeightPx,
        Some(s"Narrative Atlas — zoom ${choice.level}/${SurfaceDetail.Hidden}")
      ).left.map(_.message)
      atlasSvg <- SvgRenderer.render(lowered, atlasOptions).bimap(_.message, _.value)
      // Selection: resolve through the navigation indexes, never through renderer names.
      selectedAnnotations = choice.selection.toVector.flatMap(flow.navigation.annotationsFor).toSet
      selectedFragments = placed.annotationFragments
        .filter(f => selectedAnnotations.contains(f.annotation))
        .map(_.id.value)
        .toSet
      selectedMarks = choice.selection.toVector
        .flatMap(scene.navigation.marksFor)
        .map(_.value)
        .toSet
      fragmentTargets = placed.annotationFragments
        .flatMap(f => flow.navigation.targetOf(f.annotation).map(f.id.value -> _))
        .toMap
      lines <- placed.pages.traverse(p =>
        p.lines.traverse(line =>
          placed
            .text(line.text)
            .left
            .map(_.message)
            .map(text =>
              CodexLine(
                line.text.id.value,
                text,
                line.annotations.exists(piece => selectedFragments.contains(piece.id.value))
              )
            )
        )
      )
    yield
      val pages = placed.pages.zip(lines).zip(overlays).map { case ((p, ls), overlay) =>
        CodexPage(p.index, ls, overlay)
      }
      Compiled(
        choice,
        state,
        flow,
        placed,
        rows,
        pages,
        scene,
        atlasSvg,
        GraphicsNames.collect(lowered).length,
        selectedMarks,
        selectedFragments,
        fragmentTargets,
        codexPlacements,
        atlasPlacements,
        receipts(model, state, flow, placed, scene, measurer, codexConfig, atlasConfig)
      )

  /** The receipt as ordered text pairs: what a saved view must record (V-D3). */
  private def receipts(
      model: StoryModel[ModelStatus.Validated],
      state: CommonViewState,
      flow: CodexFlow,
      placed: PaginatedCodex,
      scene: NarrativeScene,
      measurer: Measurer,
      codexConfig: Checksum,
      atlasConfig: Checksum
  ): Vector[(String, String)] =
    val p = flow.provenance
    Vector(
      "story" -> flow.source.title.getOrElse(flow.source.id.value),
      "basis" -> p.basis.label,
      "sourceChecksum" -> p.sourceChecksum.hex,
      "modelReceiptChecksum" -> model.receipt.fold("not available")(_.contentChecksum.hex),
      "compilerVersion" -> p.compilerVersion,
      "storymodel4sRevision" -> Pins.storymodel4sRevision,
      "intaglioRevision" -> Pins.intaglioRevision,
      "horizon" -> Playhead.describe(flow.source.canonicalText, state.horizon),
      "sharedState" -> EvidenceVisibility.stateParts(state).mkString("; "),
      "codexChannels" -> {
        val kinds = flow.contract.activeKinds.toVector.map(_.wireName).sorted
        if kinds.isEmpty then "none" else kinds.mkString(",")
      },
      "codexConfigChecksum" -> codexConfig.hex,
      "codexAnnotations" -> flow.annotations.length.toString,
      "codexAnnotationFragments" -> placed.annotationFragments.length.toString,
      "atlasZoom" -> s"${scene.zoom.narrative}/${scene.zoom.surface}",
      "atlasBoxPx" -> EditionSpec.atlasBox,
      "atlasConfigChecksum" -> atlasConfig.hex,
      "atlasMarks" -> scene.marks.length.toString,
      "measurerInUse" -> measurer.name
    ) ++ placed.receipt.fields.map((key, value) => s"layout.$key" -> value) ++ Vector(
      "layout.receiptChecksum" -> placed.receipt.checksum.hex
    )

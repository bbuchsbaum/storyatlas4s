package storyatlas4s.shell

import _root_.intaglio.value
import cats.syntax.all.*
import storyatlas4s.edition.{EditionSpec, Pins}
import storyatlas4s.intaglio.{
  AtlasLowering,
  CodexLowering,
  GraphicsNames,
  PagedCodexLowering,
  PlateBox
}
import storyatlas4s.layout.{FragmentId, Measurer, PageSpec, PaginatedCodex, Paginator, TextStyle}
import storymodel4s.core.*
import storymodel4s.story.*
import storymodel4s.view.*

/** One placed line of the text rail with direct and proxy interaction presence kept distinct. */
final case class CodexLine(
    id: String,
    text: String,
    selected: Boolean,
    focused: Boolean,
    selectionProxy: Boolean,
    focusProxy: Boolean
)

/** One page of the Codex: the rail lines and the page's overlay plate (V-I2 names inside). */
final case class CodexPage(
    index: Int,
    lines: Vector[CodexLine],
    overlay: TargetedPlate[FragmentId]
):
  def targets: RenderedTargetIndex[FragmentId] = overlay.targets

/** An explicitly diagnostic Codex plate proving the otherwise absent ViaAncestor DOM capacity. */
final case class CodexProxyCourt(
    original: Address,
    visible: Address,
    overlay: TargetedPlate[FragmentId],
    interactions: Vector[InteractionDecoration[FragmentId, Address]]
):
  def targets: RenderedTargetIndex[FragmentId] = overlay.targets

/** Everything the shell draws for one [[ViewChoice]]: both artifacts compiled in storymodel4s under
  * one `CommonViewState`, the paginated Codex, the lowered overlays and Atlas, the selection's and
  * focus's placements in both, and the receipts. Nothing here is inferred: every field is read off
  * a compiled artifact or a receipt.
  */
final case class Compiled(
    choice: ViewChoice,
    state: CommonViewState,
    flow: CodexFlow,
    placed: PaginatedCodex,
    rows: Vector[PagedCodexLowering.Row],
    pages: Vector[CodexPage],
    scene: NarrativeScene,
    atlas: TargetedPlate[MarkId],
    atlasNames: Int,
    atlasInteractions: Vector[InteractionDecoration[MarkId, Address]],
    codexInteractions: Vector[InteractionDecoration[FragmentId, Address]],
    fragmentTargets: RenderedTargetIndex[FragmentId],
    codexProxyCourt: Option[CodexProxyCourt],
    codexPlacements: Vector[(Address, SelectionPlacement[AnnotationId])],
    atlasPlacements: Vector[(Address, SelectionPlacement[MarkId])],
    receipts: Vector[(String, String)]
):
  def atlasTargets: RenderedTargetIndex[MarkId] = atlas.targets
  def canonicalText: String = flow.source.canonicalText
  def pieces: Int = placed.annotationFragments.length

  def selectedMarks: Set[MarkId] = directTargets(atlasInteractions, InteractionRole.Selection)
  def focusedMarks: Set[MarkId] = directTargets(atlasInteractions, InteractionRole.Focus)
  def selectedProxyMarks: Set[MarkId] =
    proxyTargets(atlasInteractions, InteractionRole.Selection)
  def focusedProxyMarks: Set[MarkId] = proxyTargets(atlasInteractions, InteractionRole.Focus)
  def selectedFragments: Set[FragmentId] =
    directTargets(codexInteractions, InteractionRole.Selection)
  def focusedFragments: Set[FragmentId] = directTargets(codexInteractions, InteractionRole.Focus)
  def selectedProxyFragments: Set[FragmentId] =
    proxyTargets(codexInteractions, InteractionRole.Selection)
  def focusedProxyFragments: Set[FragmentId] =
    proxyTargets(codexInteractions, InteractionRole.Focus)

  private def directTargets[Name](
      decorations: Vector[InteractionDecoration[Name, Address]],
      role: InteractionRole
  ): Set[Name] =
    decorations.collect {
      case InteractionDecoration(target, `role`, SemanticRepresentation.Direct(_)) => target
    }.toSet

  private def proxyTargets[Name](
      decorations: Vector[InteractionDecoration[Name, Address]],
      role: InteractionRole
  ): Set[Name] =
    decorations.collect {
      case InteractionDecoration(target, `role`, SemanticRepresentation.Proxy(_, _)) => target
    }.toSet

/** The pure step from a choice to what is drawn: the same calls `cli/Edition` makes, in the
  * browser.
  */
object AppCompiler:

  def compile(
      model: TextModel[ModelStatus.Validated],
      choice: ViewChoice,
      measurer: Measurer
  ): Either[String, Compiled] = compileSource(Right(model), choice, measurer)

  /** Imported artifacts retain their checked draft/derivation disposition. Structural promotion
    * alone never licenses the built-in fixture basis.
    */
  def compileDraft(
      draft: DraftModel,
      choice: ViewChoice,
      measurer: Measurer
  ): Either[String, Compiled] = compileSource(Left(draft), choice, measurer)

  private def compileSource(
      input: Either[DraftModel, TextModel[ModelStatus.Validated]],
      choice: ViewChoice,
      measurer: Measurer
  ): Either[String, Compiled] =
    val source = input.fold(_.model.source, _.source)
    val receipt = input.fold(_.model.receipt, _.receipt)
    val text = source.canonicalText
    def provenance(config: Checksum): Either[String, ViewProvenance] = input match
      case Left(draft) =>
        ViewProvenance.draftBuild(draft, EditionSpec.compilerVersion, config).left.map(_.message)
      case Right(model) =>
        ViewProvenance
          .fixture(model.source.canonicalChecksum, EditionSpec.compilerVersion, config)
          .left
          .map(_.message)
    for
      state <- CommonViewState
        .of(
          selection = choice.selection,
          focus = choice.focus,
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
      codexProvenance <- provenance(codexConfig)
      flow <- input
        .fold(
          draft => CodexCompiler(codexProvenance).compileDraft(draft, state, spec),
          model => CodexCompiler(codexProvenance).compile(model, state, spec)
        )
        .left
        .map(_.message)
      page <- PageSpec.of(EditionSpec.pageWidthPx, EditionSpec.pageHeightPx).left.map(_.message)
      style <- TextStyle.of(EditionSpec.fontFamily, EditionSpec.fontSizePx).left.map(_.message)
      placed <- Paginator.layout(flow, page, style, measurer).left.map(_.message)
      rows <- PagedCodexLowering.rows(placed).left.map(_.message)
      overlayScenes <- CodexLowering.lower(placed).left.map(_.message)
      overlays = overlayScenes.map(scene =>
        Plate(
          scene,
          page.widthPx,
          page.heightPx,
          s"Narrative Codex overlay — lens ${choice.lens}"
        )
      )
      _ <-
        if overlays.length == placed.pages.length then Right(())
        else Left(s"${overlays.length} overlays for ${placed.pages.length} pages")
      // Atlas: compile at the exact chosen two-axis zoom, lower, render.
      atlasSpec = AtlasSpec(choice.zoom, threads)
      atlasConfig = AtlasCompiler.configurationChecksum(state, atlasSpec)
      atlasProvenance <- provenance(atlasConfig)
      scene <- input
        .fold(
          draft => AtlasCompiler(atlasProvenance).compileDraft(draft, state, atlasSpec),
          model => AtlasCompiler(atlasProvenance).compile(model, state, atlasSpec)
        )
        .left
        .map(_.message)
      trackedAddresses = (choice.selection ++ choice.focus).toVector.sortBy(_.render)
      codexPlacements <- trackedAddresses.traverse(address =>
        flow.selectionPlacements
          .get(address)
          .toRight(s"Codex compiler omitted placement for ${address.render}")
          .map(address -> _)
      )
      atlasPlacements <- trackedAddresses.traverse(address =>
        scene.selectionPlacements
          .get(address)
          .toRight(s"Atlas compiler omitted placement for ${address.render}")
          .map(address -> _)
      )
      atlasBox <- PlateBox
        .of(EditionSpec.atlasWidthPx, EditionSpec.atlasHeightPx)
        .left
        .map(_.message)
      lowered <- AtlasLowering.lower(scene, text.length, atlasBox).left.map(_.message)
      atlasPlate = Plate(
        lowered,
        EditionSpec.atlasWidthPx,
        EditionSpec.atlasHeightPx,
        s"Narrative Atlas — zoom ${choice.zoom.narrative}/${choice.zoom.surface}"
      )
      fragmentEntries <- placed.annotationFragments
        .traverse(fragment =>
          flow.navigation
            .targetOf(fragment.annotation)
            .toRight(
              InteractionError.MissingNavigationTarget(
                InteractionSurface.Codex,
                fragment.id.value
              )
            )
            .map(fragment.id -> _)
        )
        .left
        .map(_.message)
      fragmentTargets <- RenderedTargetIndex
        .build(
          InteractionSurface.Codex,
          fragmentEntries,
          overlayScenes.flatMap(GraphicsNames.collect).map(_.value),
          _.value
        )
        .left
        .map(_.message)
      pageTargets <- placed.pages
        .zip(overlayScenes)
        .traverse { (placedPage, overlayScene) =>
          val ids = placedPage.lines.flatMap(_.annotations).map(_.id).toSet
          RenderedTargetIndex
            .build(
              InteractionSurface.Codex,
              fragmentEntries.filter((id, _) => ids.contains(id)),
              GraphicsNames.collect(overlayScene).map(_.value),
              _.value
            )
            .map(placedPage.index -> _)
        }
        .left
        .map(_.message)
      pageOverlays <- overlays
        .zip(pageTargets)
        .traverse((plate, page) => TargetedPlate.of(plate, page._2))
        .left
        .map(_.message)
      atlasEntries <- scene.marks
        .traverse(mark =>
          scene.navigation.addressOf
            .get(mark.identity.mark)
            .toRight(
              InteractionError.MissingNavigationTarget(
                InteractionSurface.Atlas,
                mark.identity.mark.value
              )
            )
            .map(mark.identity.mark -> _)
        )
        .left
        .map(_.message)
      atlasTargets <- RenderedTargetIndex
        .build(
          InteractionSurface.Atlas,
          atlasEntries,
          GraphicsNames.collect(lowered).map(_.value),
          _.value
        )
        .left
        .map(_.message)
      atlas <- TargetedPlate.of(atlasPlate, atlasTargets).left.map(_.message)
      fragmentsByAnnotation = placed.annotationFragments
        .groupMap(_.annotation)(_.id)
        .view
        .mapValues(_.distinct.sorted)
        .toMap
      codexInteractions <- interactionsFor(
        choice,
        codexPlacements.toMap,
        annotation => fragmentsByAnnotation.getOrElse(annotation, Vector.empty),
        ancestor =>
          flow.navigation
            .exactAnnotationsFor(ancestor)
            .flatMap(annotation => fragmentsByAnnotation.getOrElse(annotation, Vector.empty)),
        fragmentTargets,
        _.value,
        _.value
      ).left.map(_.message)
      atlasInteractions <- interactionsFor(
        choice,
        atlasPlacements.toMap,
        mark => Vector(mark),
        ancestor => scene.navigation.marksFor(ancestor),
        atlasTargets,
        _.value,
        _.value
      ).left.map(_.message)
      codexProxyCourt <- diagnosticCodexProxyCourt(
        choice,
        atlasPlacements,
        codexPlacements,
        flow,
        placed,
        fragmentsByAnnotation,
        fragmentTargets,
        placed.pages.map(_.index).zip(pageOverlays)
      ).left.map(_.message)
      selectedFragments = directTargets(codexInteractions, InteractionRole.Selection)
      focusedFragments = directTargets(codexInteractions, InteractionRole.Focus)
      selectedProxyFragments = proxyTargets(codexInteractions, InteractionRole.Selection)
      focusedProxyFragments = proxyTargets(codexInteractions, InteractionRole.Focus)
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
                line.annotations.exists(piece => selectedFragments.contains(piece.id)),
                line.annotations.exists(piece => focusedFragments.contains(piece.id)),
                line.annotations.exists(piece => selectedProxyFragments.contains(piece.id)),
                line.annotations.exists(piece => focusedProxyFragments.contains(piece.id))
              )
            )
        )
      )
    yield
      val pages = placed.pages.zip(lines).zip(pageOverlays).map { case ((p, ls), overlay) =>
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
        atlas,
        atlasTargets.size,
        atlasInteractions,
        codexInteractions,
        fragmentTargets,
        codexProxyCourt,
        codexPlacements,
        atlasPlacements,
        receipts(
          receipt.map(_.contentChecksum),
          state,
          flow,
          placed,
          scene,
          measurer,
          codexConfig,
          atlasConfig
        )
      )

  private[shell] def interactionsFor[Mark, Name](
      choice: ViewChoice,
      placements: Map[Address, SelectionPlacement[Mark]],
      directTargets: Mark => Vector[Name],
      proxyTargets: Address => Vector[Name],
      targetIndex: RenderedTargetIndex[Name],
      renderMark: Mark => String,
      renderName: Name => String
  ): Either[InteractionError, Vector[InteractionDecoration[Name, Address]]] =
    def forRole(
        addresses: Vector[Address],
        role: InteractionRole
    ): Either[InteractionError, Vector[InteractionDecoration[Name, Address]]] =
      addresses
        .traverse { address =>
          placements
            .get(address)
            .toRight(InteractionError.MissingPlacement(address))
            .flatMap {
              case SelectionPlacement.OnMark(marks) =>
                marks.toVector
                  .traverse { mark =>
                    val targets = directTargets(mark).distinct
                    if targets.isEmpty then
                      Left(
                        InteractionError.MissingPlacementMemberTarget(address, renderMark(mark))
                      )
                    else
                      targets.traverse(target =>
                        targetIndex.validate(
                          InteractionDecoration(
                            target,
                            role,
                            SemanticRepresentation.Direct(address)
                          )
                        )
                      )
                  }
                  .map(_.flatten)
              case SelectionPlacement.ViaAncestor(ancestor) =>
                if address == ancestor then Left(InteractionError.InvalidProxy(address, ancestor))
                else
                  val targets = proxyTargets(ancestor).distinct
                  if targets.isEmpty then
                    Left(InteractionError.MissingProxyTarget(address, ancestor))
                  else
                    targets.traverse(target =>
                      targetIndex.validate(
                        InteractionDecoration(
                          target,
                          role,
                          SemanticRepresentation.Proxy(address, ancestor)
                        )
                      )
                    )
              case SelectionPlacement.OffProjection => Right(Vector.empty)
            }
        }
        .map(_.flatten)

    for
      selected <- forRole(
        choice.selection.toVector.sortBy(_.render),
        InteractionRole.Selection
      )
      focused <- forRole(choice.focus.toVector, InteractionRole.Focus)
    yield (selected ++ focused).sortBy(value =>
      InteractionDecoration.sortKey(value, renderName, _.render)
    )

  /** Builds an admitted diagnostic Codex plate from a real paginated fragment when the selected
    * address has an Atlas ViaAncestor placement. WOG has no natural Codex ViaAncestor under the
    * configured lenses, so this court exercises decoration, fragment identity, SVG injection, and
    * CSS without claiming that the diagnostic placement came from CodexCompiler.
    */
  private def diagnosticCodexProxyCourt(
      choice: ViewChoice,
      atlasPlacements: Vector[(Address, SelectionPlacement[MarkId])],
      codexPlacements: Vector[(Address, SelectionPlacement[AnnotationId])],
      flow: CodexFlow,
      placed: PaginatedCodex,
      fragmentsByAnnotation: Map[AnnotationId, Vector[FragmentId]],
      fragmentTargets: RenderedTargetIndex[FragmentId],
      pageOverlays: Vector[(Int, TargetedPlate[FragmentId])]
  ): Either[InteractionError, Option[CodexProxyCourt]] =
    atlasPlacements.collectFirst { case (original, SelectionPlacement.ViaAncestor(visible)) =>
      original -> visible
    } match
      case None                      => Right(None)
      case Some((original, visible)) =>
        val targetsFor = (address: Address) =>
          flow.navigation
            .exactAnnotationsFor(address)
            .flatMap(annotation => fragmentsByAnnotation.getOrElse(annotation, Vector.empty))
            .distinct
        targetsFor(visible).headOption match
          case None              => Right(None)
          case Some(firstTarget) =>
            val diagnosticPlacements =
              codexPlacements.toMap.updated(original, SelectionPlacement.ViaAncestor(visible))
            interactionsFor(
              choice,
              diagnosticPlacements,
              annotation => fragmentsByAnnotation.getOrElse(annotation, Vector.empty),
              targetsFor,
              fragmentTargets,
              _.value,
              _.value
            ).flatMap { interactions =>
              val pageByFragment = placed.annotationFragments.map(f => f.id -> f.page).toMap
              val overlaysByPage = pageOverlays.toMap
              val court = for
                page <- pageByFragment.get(firstTarget)
                overlay <- overlaysByPage.get(page)
              yield
                val pageInteractions =
                  interactions.filter(value => overlay.targets.contains(value.target))
                CodexProxyCourt(original, visible, overlay, pageInteractions)
              court match
                case Some(value) if value.interactions.exists {
                      case InteractionDecoration(
                            _,
                            InteractionRole.Selection,
                            SemanticRepresentation.Proxy(`original`, `visible`)
                          ) =>
                        true
                      case _ => false
                    } =>
                  Right(Some(value))
                case Some(_) => Left(InteractionError.MissingProxyTarget(original, visible))
                case None    =>
                  Left(
                    InteractionError.MissingRenderedTarget(
                      InteractionSurface.Codex,
                      firstTarget.value
                    )
                  )
            }

  private def directTargets[Name](
      decorations: Vector[InteractionDecoration[Name, Address]],
      role: InteractionRole
  ): Set[Name] =
    decorations.collect {
      case InteractionDecoration(target, `role`, SemanticRepresentation.Direct(_)) => target
    }.toSet

  private def proxyTargets[Name](
      decorations: Vector[InteractionDecoration[Name, Address]],
      role: InteractionRole
  ): Set[Name] =
    decorations.collect {
      case InteractionDecoration(target, `role`, SemanticRepresentation.Proxy(_, _)) => target
    }.toSet

  /** The receipt as ordered text pairs: what a saved view must record (V-D3). */
  private def receipts(
      modelReceipt: Option[Checksum],
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
      "modelReceiptChecksum" -> modelReceipt.fold("not available")(_.hex),
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

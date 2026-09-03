package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.edition.{EditionSpec, Pins}
import storyatlas4s.intaglio.{AtlasLowering, CodexLowering, GraphicsNames, PlateBox}
import storyatlas4s.layout.{LayoutReceipt, MonospaceMeasurer, PageSpec, Paginator, TextStyle}
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.story.*
import storymodel4s.view.*

/** One file of a static edition, with the facts its receipt records; `layout` is the paginator's
  * receipt for a placed artifact (V-D3), absent for flow-level ones.
  */
final case class EditionFile(
    name: String,
    artifact: String,
    detail: String,
    configChecksum: Checksum,
    names: Int,
    content: String,
    layout: Option[LayoutReceipt]
):
  def checksum: Checksum = Checksum.ofText(content)

/** A complete static edition: every artifact compiled in storymodel4s, lowered here, plus receipt.
  */
final case class Edition(
    /** What was drawn: the fixture's name, or the file the model was read from. `provenanceBasis`
      * is what says which, and it is not derived from this string.
      */
    model: String,
    provenanceBasis: ViewBasis,
    sourceChecksum: Checksum,
    modelReceiptChecksum: Option[Checksum],
    state: CommonViewState,
    files: Vector[EditionFile],
    /** Present exactly when this is a draft edition; `None` says the model was promoted. */
    promotion: Option[DraftPromotion] = None
):
  /** What the draft compilers were told about derivation.
    *
    * `gapCount` is `Some(n)` only when a derivation record was supplied and reported `n` gaps.
    * `None` means no record reached the view at all, and it is emitted as its own string rather
    * than as zero: a scene that says "0 gaps" claims the compiler derived everything, and a scene
    * with no record knows nothing about derivation either way. Those two must never share a
    * receipt.
    */
  private def derivationJson: Json = promotion match
    case None    => Json.Null
    case Some(p) =>
      Json.Obj(
        Vector(
          "promoted" -> Json.Str(p.promoted.toString),
          "unsatisfiedLawCount" -> Json.Num(p.violationCount.toLong),
          "gaps" -> p.gapCount.fold[Json](Json.Str(ModelInput.derivationRecordNote))(n =>
            Json.Num(n.toLong)
          ),
          "laws" -> Json.arr(
            p.unsatisfiedLaws.map(l =>
              Json.Obj(
                Vector(
                  "law" -> Json.Str(l.law),
                  "severity" -> Json.Str(l.severity.toString),
                  "count" -> Json.Num(l.count.value.toLong)
                )
              )
            )
          )
        )
      )

  def receipt: String =
    Json
      .obj(
        "edition" -> Json.Str("storyatlas4s"),
        "model" -> Json.Str(model),
        "basis" -> Json.Str(provenanceBasis.label),
        "draft" -> derivationJson,
        "sourceChecksum" -> Json.Str(sourceChecksum.hex),
        "modelReceiptChecksum" -> modelReceiptChecksum.fold[Json](Json.Null)(c => Json.Str(c.hex)),
        "compilerVersion" -> Json.Str(EditionSpec.compilerVersion),
        "storymodel4sRevision" -> Json.Str(Pins.storymodel4sRevision),
        "intaglioRevision" -> Json.Str(Pins.intaglioRevision),
        "atlasBoxPx" -> Json.Str(EditionSpec.atlasBox),
        "codexOverlayBoxPx" -> Json.Str(EditionSpec.codexOverlayBox),
        "pageBoxPx" -> Json.Str(EditionSpec.pageBox),
        "sharedState" -> Json.strings(EvidenceVisibility.stateParts(state)),
        "files" -> Json.arr(
          files.map(f =>
            Json.Obj(
              Vector(
                "file" -> Json.Str(f.name),
                "artifact" -> Json.Str(f.artifact),
                "detail" -> Json.Str(f.detail),
                "configChecksum" -> Json.Str(f.configChecksum.hex),
                "names" -> Json.Num(f.names.toLong),
                "sha256" -> Json.Str(f.checksum.hex)
              ) ++ f.layout.map(layoutJson).map("layout" -> _)
            )
          )
        )
      )
      .render

  /** `LayoutReceipt.fields` verbatim, in order, so the JSON and the twin derive from one rendering.
    */
  private def layoutJson(receipt: LayoutReceipt): Json =
    Json.Obj(receipt.fields.map((key, value) => key -> Json.Str(value)))

object Edition:
  val ReceiptFile: String = "receipt.json"

  /** Every constant of the edition lives in [[EditionSpec]], shared with the browser shell. */
  private val atlasZooms = EditionSpec.zoomLevels
  private val codexLenses = EditionSpec.lenses

  /** The fast case: the researcher-reviewed War of the Ghosts fixture, linked into this build.
    *
    * It stays a case, and it is no longer the only one — the viewer renders the pipeline's own
    * output from V0 onward (docs/plans/2026-09-03-visualization-recovery-plan.md §5).
    */
  def warOfTheGhosts: Either[String, Edition] =
    build(WarOfTheGhostsModel.model, "war-of-the-ghosts", ViewBasis.ResearcherReviewedFixture)

  /** A model read from a `storymodel.json` the pipeline wrote.
    *
    * A model the validator promoted renders through exactly the path the fixture takes, under
    * `ViewBasis.ValidatedBuild`, so the receipt never calls a machine build a reviewed fixture.
    * That basis is a claim about how the model was made, and `ViewProvenance` only grants it to a
    * model carrying a build receipt. A validated model without one is refused here, plainly and
    * early, rather than quietly relabelled: `ResearcherReviewedFixture` is the one basis that needs
    * no receipt, and it is not true of a file this process did not review.
    *
    * A model the validator did not promote goes to the draft compilers, which draw it *as partial*:
    * its unsatisfied laws, and whatever gaps and abstentions its derivation record reports, become
    * marks rather than holes in the ink. That is more truthful than making a model validate to
    * satisfy a renderer, and it is what a researcher needs when the pipeline is imperfect, which is
    * always.
    */
  def fromRead(read: ReadModel): Either[String, Edition] =
    read.validated match
      case Some(model) if model.receipt.isEmpty =>
        Left(
          s"${read.path} validates but carries no build receipt, so no view basis is true of " +
            "it: a validated build must be receipted, and it is not a reviewed fixture."
        )
      case Some(model) =>
        build(model, read.path.getFileName.toString, ViewBasis.ValidatedBuild)
      case None =>
        draft(read)

  /** The draft edition: atlases only.
    *
    * There is no draft Codex compiler in this pin — `CodexCompiler.compile` still takes a
    * `StoryModel[Validated]` — so a draft edition writes atlas SVGs and their twins and no
    * `codex-*` files. Emitting an empty Codex would claim the reading view had been compiled and
    * had nothing to say.
    */
  private def draft(read: ReadModel): Either[String, Edition] =
    val model = read.draftModel
    for
      state <- CommonViewState
        .of(relationLayers = EditionSpec.relationLayers)
        .left
        .map(_.message)
      threads <- PositiveInt
        .from(EditionSpec.threadMax)
        .map(ThreadPolicy.All.apply)
        .left
        .map(_.message)
      files <- atlasZooms.flatTraverse(zoom => draftAtlasFiles(model, state, zoom, threads))
    yield Edition(
      read.path.getFileName.toString,
      ViewBasis.DraftBuild,
      model.model.source.canonicalChecksum,
      model.model.receipt.map(_.contentChecksum),
      state,
      files,
      Some(model.promotion)
    )

  private def draftAtlasFiles(
      model: DraftModel,
      state: CommonViewState,
      zoom: ZoomLevel,
      threads: ThreadPolicy
  ): Either[String, Vector[EditionFile]] =
    val spec = AtlasSpec(zoom, threads)
    val config = AtlasCompiler.configurationChecksum(state, spec)
    val stem =
      s"atlas-${zoom.narrative.toString.toLowerCase}-${zoom.surface.toString.toLowerCase}"
    val detail = s"zoom ${zoom.narrative}/${zoom.surface}, box ${EditionSpec.atlasBox}"
    for
      // `draftBuild` is the only honest way to build this receipt: it binds the promotion of this
      // exact bundle, and `compileDraft` refuses a receipt that describes any other.
      provenance <- ViewProvenance
        .draftBuild(model, EditionSpec.compilerVersion, config)
        .left
        .map(_.message)
      scene <- AtlasCompiler(provenance).compileDraft(model, state, spec).left.map(_.message)
      box <- PlateBox
        .of(EditionSpec.atlasWidthPx, EditionSpec.atlasHeightPx)
        .left
        .map(_.message)
      lowered <- AtlasLowering
        .lower(scene, model.model.source.canonicalText.length, box)
        .left
        .map(_.message)
      names = GraphicsNames.collect(lowered).length
      options <- SvgOptions(
        EditionSpec.atlasWidthPx,
        EditionSpec.atlasHeightPx,
        Some(s"Narrative Atlas (draft) — $detail")
      ).left.map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
    yield Vector(
      EditionFile(s"$stem.svg", "atlas-draft", detail, config, names, svg.value, None),
      EditionFile(
        s"$stem.txt",
        "atlas-draft-twin",
        detail,
        config,
        scene.marks.length,
        scene.textualTwin,
        None
      )
    )

  /** `basis` is a fact about where the model came from, never a default: `ValidatedBuild` and
    * `HumanAdjudicated` both require the model to carry a build receipt, and `ViewProvenance`
    * refuses them without one.
    */
  def build(
      model: StoryModel[ModelStatus.Validated],
      name: String,
      basis: ViewBasis
  ): Either[String, Edition] =
    val receiptChecksum = model.receipt.map(_.contentChecksum)
    for
      state <- CommonViewState
        .of(relationLayers = EditionSpec.relationLayers)
        .left
        .map(_.message)
      threads <- PositiveInt
        .from(EditionSpec.threadMax)
        .map(ThreadPolicy.All.apply)
        .left
        .map(_.message)
      atlas <- atlasZooms.flatTraverse(zoom =>
        atlasFiles(model, receiptChecksum, basis, state, zoom, threads)
      )
      codex <- codexLenses.flatTraverse(lens =>
        codexFiles(model, receiptChecksum, basis, state, lens)
      )
    yield Edition(
      name,
      basis,
      model.source.canonicalChecksum,
      receiptChecksum,
      state,
      atlas ++ codex
    )

  private def atlasFiles(
      model: StoryModel[ModelStatus.Validated],
      receiptChecksum: Option[Checksum],
      basis: ViewBasis,
      state: CommonViewState,
      zoom: ZoomLevel,
      threads: ThreadPolicy
  ): Either[String, Vector[EditionFile]] =
    val spec = AtlasSpec(zoom, threads)
    val config = AtlasCompiler.configurationChecksum(state, spec)
    val stem =
      s"atlas-${zoom.narrative.toString.toLowerCase}-${zoom.surface.toString.toLowerCase}"
    val detail = s"zoom ${zoom.narrative}/${zoom.surface}, box ${EditionSpec.atlasBox}"
    for
      provenance <- ViewProvenance
        .of(
          model.source.canonicalChecksum,
          receiptChecksum,
          basis,
          EditionSpec.compilerVersion,
          config
        )
        .left
        .map(_.message)
      scene <- AtlasCompiler(provenance).compile(model, state, spec).left.map(_.message)
      box <- PlateBox
        .of(EditionSpec.atlasWidthPx, EditionSpec.atlasHeightPx)
        .left
        .map(_.message)
      lowered <- AtlasLowering
        .lower(scene, model.source.canonicalText.length, box)
        .left
        .map(_.message)
      names = GraphicsNames.collect(lowered).length
      options <- SvgOptions(
        EditionSpec.atlasWidthPx,
        EditionSpec.atlasHeightPx,
        Some(s"Narrative Atlas — $detail")
      ).left.map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
    yield Vector(
      EditionFile(s"$stem.svg", "atlas", detail, config, names, svg.value, None),
      EditionFile(
        s"$stem.txt",
        "atlas-twin",
        detail,
        config,
        scene.marks.length,
        scene.textualTwin,
        None
      )
    )

  private def codexFiles(
      model: StoryModel[ModelStatus.Validated],
      receiptChecksum: Option[Checksum],
      basis: ViewBasis,
      state: CommonViewState,
      lens: CodexLens
  ): Either[String, Vector[EditionFile]] =
    val stem = s"codex-${lens.toString.toLowerCase}"
    val detail = s"lens $lens"
    for
      spec <- CodexSpec.forLens(lens, ChannelBudget.All).left.map(_.message)
      config = CodexCompiler.configurationChecksum(state, spec)
      provenance <- ViewProvenance
        .of(
          model.source.canonicalChecksum,
          receiptChecksum,
          basis,
          EditionSpec.compilerVersion,
          config
        )
        .left
        .map(_.message)
      flow <- CodexCompiler(provenance).compile(model, state, spec).left.map(_.message)
      lowered <- CodexLowering.lower(flow, model.source.canonicalText.length).left.map(_.message)
      names = GraphicsNames.collect(lowered).length
      options <- SvgOptions(
        EditionSpec.codexOverlayWidthPx,
        EditionSpec.codexOverlayHeightPx,
        Some(s"Narrative Codex overlay — $detail")
      ).left.map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
      page <- PageSpec.of(EditionSpec.pageWidthPx, EditionSpec.pageHeightPx).left.map(_.message)
      style <- TextStyle.of(EditionSpec.fontFamily, EditionSpec.fontSizePx).left.map(_.message)
      placed <- Paginator.layout(flow, page, style, MonospaceMeasurer.instance).left.map(_.message)
      html <- CodexHtml.render(placed, detail).left.map(_.message)
      pieces = placed.annotationFragments.length
      codexFiles = Vector(
        EditionFile(s"$stem.svg", "codex", detail, config, names, svg.value, None),
        EditionFile(
          s"$stem.txt",
          "codex-twin",
          detail,
          config,
          flow.annotations.length,
          flow.textualTwin,
          None
        ),
        EditionFile(
          s"$stem.html",
          "codex-pages",
          detail,
          config,
          pieces,
          html,
          Some(placed.receipt)
        ),
        EditionFile(
          s"$stem-pages.txt",
          "codex-pages-twin",
          detail,
          config,
          pieces,
          placed.textualTwin,
          Some(placed.receipt)
        )
      )
      previewFiles =
        if lens == CodexLens.Overview then
          Vector(
            EditionFile(
              "preview.html",
              "static-preview",
              detail,
              config,
              pieces,
              html,
              Some(placed.receipt)
            ),
            EditionFile(
              "preview.txt",
              "static-preview-twin",
              detail,
              config,
              pieces,
              placed.textualTwin,
              Some(placed.receipt)
            )
          )
        else Vector.empty
    yield codexFiles ++ previewFiles

  /** Write every file and the receipt into `dir`, creating it; returns the paths written. */
  def write(edition: Edition, dir: java.nio.file.Path): Either[String, Vector[java.nio.file.Path]] =
    import java.nio.charset.StandardCharsets.UTF_8
    import java.nio.file.Files
    val entries = edition.files.map(f => f.name -> f.content) :+ (ReceiptFile -> edition.receipt)
    try
      Files.createDirectories(dir)
      Right(entries.map { (name, content) =>
        val path = dir.resolve(name)
        Files.write(path, content.getBytes(UTF_8))
        path
      })
    catch case e: java.io.IOException => Left(s"cannot write edition to $dir: ${e.getMessage}")

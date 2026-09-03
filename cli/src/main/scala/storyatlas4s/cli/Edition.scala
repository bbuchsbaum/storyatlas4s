package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.edition.{EditionSpec, Pins}
import storyatlas4s.intaglio.{AtlasLowering, CodexLowering, GraphicsNames}
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
    files: Vector[EditionFile]
):
  def receipt: String =
    Json
      .obj(
        "edition" -> Json.Str("storyatlas4s"),
        "model" -> Json.Str(model),
        "basis" -> Json.Str(provenanceBasis.label),
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
    * A model the validator did not promote has no path here yet, and this refuses rather than
    * inventing one. `AtlasCompiler.compile` takes `StoryModel[Validated]` by design; forcing a
    * draft through it would draw a partial model as a complete one, which is the single thing the
    * recovery plan forbids. The draft compiler being added in storymodel4s (`viz/v0-draft-atlas`)
    * lands in this branch: it becomes `draftEdition(read)`, calling the draft compile in place of
    * `AtlasCompiler(...).compile` and folding the gap marks into the same `EditionFile` vector.
    *
    * One fact that branch will need, measured on the War of the Ghosts model `storyBuild replay`
    * produced at pin 353f9f3d: **the derivation gaps are not in `storymodel.json`.** The pipeline
    * reports 70 gaps and 135 violations, but 69 of those violations are `compiler.required-
    * derivation`, raised by the narrative compiler and recorded only in the sibling
    * `compilation-report.json`. Re-validating the decoded model here finds 66, all of them
    * hierarchy laws, from one root cause: the story summary was never derived, so there are no
    * segments, so all 65 situations are unreachable from a primary root. Drawing the gaps as marks
    * therefore needs the compilation report as a second edition input, or the gaps must reach the
    * model; `StoryModel` carries no record of them today.
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
        Left(
          s"${read.path} decoded but does not validate: ${read.report.errors.length} errors, " +
            s"${read.report.warnings.length} warnings. The validated compilers may not draw it, " +
            "and the draft compiler that can is not in this pin yet."
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
      lowered <- AtlasLowering.lower(scene, model.source.canonicalText.length).left.map(_.message)
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
    yield Vector(
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
      EditionFile(s"$stem.html", "codex-pages", detail, config, pieces, html, Some(placed.receipt)),
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

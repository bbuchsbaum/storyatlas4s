package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
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
    fixture: String,
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
        "fixture" -> Json.Str(fixture),
        "basis" -> Json.Str(provenanceBasis.label),
        "sourceChecksum" -> Json.Str(sourceChecksum.hex),
        "modelReceiptChecksum" -> modelReceiptChecksum.fold[Json](Json.Null)(c => Json.Str(c.hex)),
        "compilerVersion" -> Json.Str(Edition.compilerVersion),
        "storymodel4sRevision" -> Json.Str(Pins.storymodel4sRevision),
        "intaglioRevision" -> Json.Str(Pins.intaglioRevision),
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

  /** The publication page: a 480x640px box of 16px monospace (50 columns, 33 lines) under the fixed
    * metric measurer, so every break is checkable by counting characters, identical on any platform
    * (ADR 0002 D3), and the fixture spans several pages.
    */
  val pageWidthPx: Int = 480
  val pageHeightPx: Int = 640
  val fontFamily: String = "monospace"
  val fontSizePx: Int = 16

  /** The view compilers are storymodel4s's; the version recorded is its pinned revision. */
  val compilerVersion: String = s"storymodel4s@${Pins.storymodel4sRevision}"

  private val atlasLevels =
    Vector(NarrativeLevel.Story, NarrativeLevel.Episode, NarrativeLevel.Scene)
  private val codexLenses = Vector(CodexLens.Reading, CodexLens.Overview)

  /** The slice-1 acceptance artifact: the researcher-reviewed War of the Ghosts fixture. */
  def warOfTheGhosts: Either[String, Edition] =
    build(WarOfTheGhostsModel.model, "war-of-the-ghosts")

  def build(model: StoryModel[ModelStatus.Validated], fixture: String): Either[String, Edition] =
    for
      state <- CommonViewState
        .of(relationLayers = Set(RelationLayer.Causal, RelationLayer.Reference))
        .left
        .map(_.message)
      threads <- PositiveInt.from(3).map(ThreadPolicy.All.apply).left.map(_.message)
      atlas <- atlasLevels.flatTraverse(level => atlasFiles(model, state, level, threads))
      codex <- codexLenses.flatTraverse(lens => codexFiles(model, state, lens))
    yield Edition(
      fixture,
      ViewBasis.ResearcherReviewedFixture,
      model.source.canonicalChecksum,
      model.receipt.map(_.contentChecksum),
      state,
      atlas ++ codex
    )

  private def atlasFiles(
      model: StoryModel[ModelStatus.Validated],
      state: CommonViewState,
      level: NarrativeLevel,
      threads: ThreadPolicy
  ): Either[String, Vector[EditionFile]] =
    val spec = AtlasSpec(ZoomLevel(level, SurfaceDetail.Hidden), threads)
    val config = AtlasCompiler.configurationChecksum(state, spec)
    val stem = s"atlas-${level.toString.toLowerCase}"
    val detail = s"zoom ${level}/${SurfaceDetail.Hidden}"
    for
      provenance <- ViewProvenance
        .fixture(model.source.canonicalChecksum, compilerVersion, config)
        .left
        .map(_.message)
      scene <- AtlasCompiler(provenance).compile(model, state, spec).left.map(_.message)
      lowered <- AtlasLowering.lower(scene, model.source.canonicalText.length).left.map(_.message)
      names = GraphicsNames.collect(lowered).length
      options <- SvgOptions(1600, 420, Some(s"Narrative Atlas — $detail")).left.map(_.message)
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
      state: CommonViewState,
      lens: CodexLens
  ): Either[String, Vector[EditionFile]] =
    val stem = s"codex-${lens.toString.toLowerCase}"
    val detail = s"lens $lens"
    for
      spec <- CodexSpec.forLens(lens, ChannelBudget.All).left.map(_.message)
      config = CodexCompiler.configurationChecksum(state, spec)
      provenance <- ViewProvenance
        .fixture(model.source.canonicalChecksum, compilerVersion, config)
        .left
        .map(_.message)
      flow <- CodexCompiler(provenance).compile(model, state, spec).left.map(_.message)
      lowered <- CodexLowering.lower(flow, model.source.canonicalText.length).left.map(_.message)
      names = GraphicsNames.collect(lowered).length
      options <- SvgOptions(1600, 640, Some(s"Narrative Codex overlay — $detail")).left
        .map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
      page <- PageSpec.of(pageWidthPx, pageHeightPx).left.map(_.message)
      style <- TextStyle.of(fontFamily, fontSizePx).left.map(_.message)
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

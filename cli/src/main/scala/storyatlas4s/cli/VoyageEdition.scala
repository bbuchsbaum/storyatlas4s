package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import storyatlas4s.edition.{Pins, VoyagePage}
import storyatlas4s.intaglio.{GraphicsNames, VoyageLowering}
import storymodel4s.codec.Canonical
import storymodel4s.codec.VoyageCodecs.given
import storymodel4s.core.Checksum
import storymodel4s.view.*

/** `storyatlas4s voyage --document <voyage.json> --out <dir>`: the static Recall Voyage edition.
  *
  * The document is decoded through storymodel4s `codec`, which re-proves its join; the scene is
  * compiled by storymodel4s `VoyageCompiler` with an empty selection, so the evidence law runs
  * here; the plate is lowered and rendered; the page embeds the document for the interactive pane
  * (`app.js` beside it). Nothing here computes a number: every figure in the receipt is read from
  * the scene or hashed from a file.
  */
final case class VoyageEdition(
    document: RecallVoyageDocument,
    documentJson: String,
    documentPath: String,
    scene: VoyageScene,
    svg: String,
    names: Int
):
  def twin: String = scene.textualTwin
  def html: String = VoyagePage.render(documentJson, svg, "Recall Voyage")

  def receipt: String =
    val s = scene.summary
    Json
      .obj(
        "edition" -> Json.Str("storyatlas4s"),
        "artifact" -> Json.Str("recall-voyage"),
        "document" -> Json.Str(documentPath),
        "documentSha256" -> Json.Str(Checksum.ofText(documentJson).hex),
        "basis" -> Json.Str(scene.provenance.basis.label),
        "sourceChecksum" -> Json.Str(scene.provenance.sourceChecksum.hex),
        "configChecksum" -> Json.Str(scene.provenance.configChecksum.hex),
        "compilerVersion" -> Json.Str(scene.provenance.compilerVersion),
        "storymodel4sRevision" -> Json.Str(Pins.storymodel4sRevision),
        "intaglioRevision" -> Json.Str(Pins.intaglioRevision),
        "plateBoxPx" -> Json.Str(
          s"${VoyageLowering.Box.default.width}x${VoyageLowering.Box.default.height}"
        ),
        "marks" -> Json.Num(scene.marks.size.toLong),
        "names" -> Json.Num(names.toLong),
        "units" -> Json.Num(s.units.toLong),
        "anchored" -> Json.Num(s.anchored.toLong),
        "posteriorArgmax" -> Json.Num(s.posteriorArgmax.toLong),
        "decodeBound" -> Json.Num(s.decodeBound.toLong),
        "decodeFilled" -> Json.Num(s.decodeFilled.toLong),
        "unanchored" -> Json.Num(s.unanchored.toLong),
        "untimed" -> Json.Num(s.untimed.toLong),
        "externalDominant" -> Json.Num(s.externalDominant.toLong),
        "codedAgreement" -> s.codedAgreement.fold[Json](Json.Null)((a, t) =>
          Json.obj("agree" -> Json.Num(a.toLong), "of" -> Json.Num(t.toLong))
        ),
        "coding" -> scene.coding.fold[Json](Json.Null)(c =>
          Json.obj(
            "name" -> Json.Str(c.name),
            "sha256" -> Json.Str(c.checksum.hex),
            "intervals" -> Json.Num(c.intervals.size.toLong)
          )
        ),
        "files" -> Json.arr(
          VoyageEdition
            .files(this)
            .map((name, content) =>
              Json.obj("file" -> Json.Str(name), "sha256" -> Json.Str(Checksum.ofText(content).hex))
            )
        )
      )
      .render

object VoyageEdition:
  val SvgFile = "voyage.svg"
  val TwinFile = "voyage.txt"
  val HtmlFile = "voyage.html"
  val ReceiptFile = "voyage-receipt.json"

  def read(path: Path): Either[String, (RecallVoyageDocument, String)] =
    for
      text <- ModelInput.slurp(path)
      doc <- Canonical
        .decode[RecallVoyageDocument](text)
        .left
        .map(e => s"$path is not a readable voyage document: ${e.message}")
    yield (doc, text)

  def build(
      document: RecallVoyageDocument,
      documentJson: String,
      documentPath: String
  ): Either[String, VoyageEdition] =
    for
      scene <- document.compile(Set.empty).left.map(_.message)
      lowered <- VoyageLowering.lower(scene).left.map(_.message)
      options <- SvgOptions(
        VoyageLowering.Box.default.width,
        VoyageLowering.Box.default.height,
        Some("Recall Voyage")
      ).left.map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
    yield VoyageEdition(
      document,
      documentJson,
      documentPath,
      scene,
      svg.value,
      GraphicsNames.collect(lowered).length
    )

  def files(e: VoyageEdition): Vector[(String, String)] =
    Vector(SvgFile -> e.svg, TwinFile -> e.twin, HtmlFile -> e.html)

  def write(e: VoyageEdition, dir: Path): Either[String, Vector[Path]] =
    val entries = files(e) :+ (ReceiptFile -> e.receipt)
    try
      Files.createDirectories(dir)
      Right(entries.map { (name, content) =>
        val path = dir.resolve(name)
        Files.write(path, content.getBytes(UTF_8))
        path
      })
    catch case ex: java.io.IOException => Left(s"cannot write voyage to $dir: ${ex.getMessage}")

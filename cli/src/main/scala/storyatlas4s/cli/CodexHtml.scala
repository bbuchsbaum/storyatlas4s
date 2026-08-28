package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.intaglio.{CodexLowering, PagedCodexLowering}
import storyatlas4s.layout.{PaginatedCodex, PlacedLine, PlacedPage}

/** The paginated Narrative Codex as one self-contained HTML document (ADR 0002 D6).
  *
  * The DOM owns the prose: each page is a `.text` rail of one `<span class="line">` per
  * `TextFragment` in document order, and nothing else — no whitespace between spans, no inserted
  * characters — so the concatenated text content of the spans (and of the rail itself) is the
  * canonical text exactly (V-T2). Each span carries the line's fragment id as `data-name`; the
  * page's inline SVG overlay, lowered from the same `PaginatedCodex`, carries the annotation piece
  * ids as `data-name`, so a name resolves the same way in either layer (V-I2).
  *
  * Only `&`, `<`, `>` (and `"` in attributes) are escaped; every other character, newlines
  * included, is written as itself, and the rail is rendered `white-space: pre`. A line is a
  * fixed-height block, so a soft break falls where the paginator put it without any character being
  * added. No script, no external resource, no colour carries meaning: the row a band sits in is its
  * kind and lane, and the legend spells the rows out (V-U5).
  */
object CodexHtml:

  /** The document, or why a page could not be lowered or a line could not be sliced. */
  def render(placed: PaginatedCodex, detail: String): Either[String, String] =
    val flow = placed.flow
    val receipt = placed.receipt
    for
      rows <- PagedCodexLowering.rows(placed).left.map(_.message)
      scenes <- CodexLowering.lower(placed).left.map(_.message)
      options <- SvgOptions(
        placed.page.widthPx,
        placed.page.heightPx,
        Some(s"Narrative Codex overlay — $detail")
      ).left.map(_.message)
      overlays <- scenes.traverse(scene =>
        SvgRenderer.render(scene, options).bimap(_.message, _.value)
      )
      pages <- placed.pages.traverse(page => renderPage(placed, page, overlays))
    yield
      val title = s"Narrative Codex — $detail"
      val lineHeight = px(receipt.lineHeight.toDouble / receipt.unitsPerPixel)
      val out = new StringBuilder
      out.append("<!DOCTYPE html>\n<html lang=\"")
      out.append(escapeAttr(flow.source.language.value)).append("\">\n<head>\n")
      out.append("<meta charset=\"utf-8\">\n")
      out.append("<title>").append(escapeText(title)).append("</title>\n")
      out.append("<style>\n")
      out.append("body { margin: 0; padding: 1rem; background: #ffffff; color: #000000; }\n")
      out.append(
        "header, footer { font: 13px/1.5 sans-serif; max-width: 72em; margin: 0 auto 1rem; }\n"
      )
      out.append("h1 { font-size: 1.2em; margin: 0 0 0.5em; }\n")
      out.append(
        "dl { display: grid; grid-template-columns: max-content 1fr; gap: 0 1em; margin: 0; }\n"
      )
      out.append("dt { font-weight: bold; } dd { margin: 0; overflow-wrap: anywhere; }\n")
      out
        .append(".page { position: relative; width: ")
        .append(receipt.page.widthPx)
        .append("px; height: ")
        .append(receipt.page.heightPx)
        .append("px; margin: 0 auto 1.5rem; border: 1px solid #000000; }\n")
      out
        .append(
          ".text { position: absolute; top: 0; left: 0; margin: 0; width: 100%; height: 100%;"
        )
        .append(" overflow: hidden; font-family: ")
        .append(cssFamily(receipt.style.family))
        .append("; font-size: ")
        .append(receipt.style.sizePx)
        .append("px; line-height: ")
        .append(lineHeight)
        .append("px; }\n")
      out
        .append(".line { display: block; height: ")
        .append(lineHeight)
        .append("px; white-space: pre; overflow: hidden; }\n")
      out.append(".overlay { position: absolute; top: 0; left: 0; pointer-events: none; }\n")
      out.append(".overlay svg { display: block; }\n")
      out.append(
        ".page-label { position: absolute; left: 0; bottom: -1.4em; font: 11px/1.2 sans-serif; }\n"
      )
      out.append("</style>\n</head>\n<body>\n<header>\n")
      out.append("<h1>").append(escapeText(title)).append("</h1>\n<dl>\n")
      definition(out, "Story", flow.source.title.getOrElse(flow.source.id.value))
      definition(out, "Basis", flow.provenance.basis.label)
      definition(out, "Source checksum", flow.provenance.sourceChecksum.hex)
      definition(out, "Compiler", flow.provenance.compilerVersion)
      definition(out, "Configuration", flow.provenance.configChecksum.hex)
      receipt.fields.foreach((key, value) => definition(out, key, value))
      definition(out, "receiptChecksum", receipt.checksum.hex)
      definition(
        out,
        "Overlay rows",
        if rows.isEmpty then "none (no annotation is placed)"
        else
          rows.zipWithIndex
            .map((row, index) => s"${index + 1}: ${row.label}")
            .mkString("top to bottom within each line — ", "; ", "")
      )
      out.append("</dl>\n</header>\n<main>\n")
      pages.foreach(out.append)
      out.append("</main>\n<footer>")
      out.append(escapeText(s"Basis: ${flow.provenance.basis.label}. "))
      out.append(escapeText("Text rendered from the storymodel4s source at edition time (V-T3)."))
      out.append("</footer>\n</body>\n</html>\n")
      out.result()

  private def renderPage(
      placed: PaginatedCodex,
      page: PlacedPage,
      overlays: Vector[String]
  ): Either[String, String] =
    for
      overlay <- overlays
        .lift(page.index)
        .toRight(s"page ${page.index} has no overlay (${overlays.length} lowered)")
      lines <- page.lines.traverse(line => renderLine(placed, line))
    yield
      val out = new StringBuilder
      out.append("<section class=\"page\" data-page=\"").append(page.index).append("\">\n")
      out.append("<div class=\"text\">")
      lines.foreach(out.append)
      out.append("</div>\n<div class=\"overlay\">").append(overlay).append("</div>\n")
      out
        .append("<div class=\"page-label\">")
        .append(escapeText(s"Page ${page.index + 1} of ${placed.pages.length} · "))
        .append(escapeText(placed.flow.provenance.basis.label))
        .append("</div>\n</section>\n")
      out.result()

  private def renderLine(placed: PaginatedCodex, line: PlacedLine): Either[String, String] =
    placed
      .text(line.text)
      .left
      .map(_.message)
      .map(text =>
        s"""<span class="line" data-name="${escapeAttr(line.text.id.value)}">${escapeText(
            text
          )}</span>"""
      )

  private def definition(out: StringBuilder, term: String, value: String): Unit =
    out
      .append("<dt>")
      .append(escapeText(term))
      .append("</dt><dd>")
      .append(escapeText(value))
      .append("</dd>\n")
    ()

  /** Text content: only the three characters the HTML parser would otherwise interpret. */
  def escapeText(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  /** Attribute values: text escaping plus the quote that delimits them. */
  def escapeAttr(value: String): String =
    escapeText(value).replace("\"", "&quot;")

  /** A bare identifier stays a CSS keyword (so `monospace` is the generic family); anything else is
    * a quoted family name.
    */
  private def cssFamily(family: String): String =
    if family.forall(c => c.isLetterOrDigit || c == '-' || c == '_') then family
    else "\"" + family.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** Fixed-point pixels (at most four decimals, no exponent), the SVG backend's formatting rule. */
  private def px(value: Double): String =
    val scaled = math.rint(math.abs(value) * 10000.0).toLong
    val sign = if value < 0.0 && scaled != 0L then "-" else ""
    val whole = scaled / 10000L
    var frac = (scaled % 10000L).toInt
    if frac == 0 then s"$sign$whole"
    else
      var digits = 4
      while frac % 10 == 0 do
        frac /= 10
        digits -= 1
      val text = frac.toString
      s"$sign$whole." + ("0" * (digits - text.length)) + text

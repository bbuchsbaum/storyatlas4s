package storyatlas4s.layout

import storymodel4s.view.{LaneSlot, TextAnnotation}

/** The deterministic plain-text form of a paginated codex: pages, lines, and the pieces on them.
  *
  * It is the screen-reader and snapshot-diff form of the placed edition (ADR 0002 D12): every
  * fragment id appears once, with its exact span and, for annotation pieces, the annotation's kind,
  * lane, and target. Line text is rendered from the flow's source at render time.
  */
object PaginatedCodexTextualTwin:
  def render(placed: PaginatedCodex): String =
    val flow = placed.flow
    val receipt = placed.receipt
    val out = new StringBuilder
    out.append("Paginated Narrative Codex\n")
    out.append("Story: ").append(flow.source.title.getOrElse(flow.source.id.value)).append('\n')
    out.append("Basis: ").append(flow.provenance.basis.label).append('\n')
    out.append("Source checksum: ").append(flow.provenance.sourceChecksum.hex).append('\n')
    out.append("Compiler: ").append(flow.provenance.compilerVersion).append('\n')
    out.append("Configuration: ").append(flow.provenance.configChecksum.hex).append('\n')
    out.append("Paginator: ").append(receipt.paginator).append('\n')
    out.append("Measurer: ").append(receipt.measurer).append('\n')
    out
      .append("Font: ")
      .append(receipt.style.family)
      .append(' ')
      .append(receipt.style.sizePx)
      .append("px\n")
    out
      .append("Page: ")
      .append(receipt.page.widthPx)
      .append('x')
      .append(receipt.page.heightPx)
      .append("px, ")
      .append(receipt.linesPerPage)
      .append(" lines per page, line height ")
      .append(receipt.lineHeight)
      .append('/')
      .append(receipt.unitsPerPixel)
      .append("px\n")
    out.append("Metrics checksum: ").append(receipt.metricsChecksum.hex).append('\n')
    out.append("Receipt checksum: ").append(receipt.checksum.hex).append('\n')
    out
      .append("Pages: ")
      .append(receipt.pages)
      .append(" Lines: ")
      .append(receipt.lines)
      .append(" Annotations: ")
      .append(receipt.annotations)
      .append(" Annotation fragments: ")
      .append(receipt.annotationFragments)
      .append(" Overflowing lines: ")
      .append(receipt.overflowingLines)
      .append('\n')
    placed.pages.foreach { page =>
      out.append('\n').append("Page ").append(page.index)
      out.append(" (").append(page.lines.length).append(" lines)\n")
      page.lines.foreach(line => renderLine(placed, line, out))
    }
    out.result()

  private def renderLine(placed: PaginatedCodex, line: PlacedLine, out: StringBuilder): Unit =
    val text = line.text
    out
      .append("- ")
      .append(text.id.value)
      .append(' ')
      .append(text.span.toString)
      .append(" width=")
      .append(text.width)
    if text.overflow then out.append(" overflow")
    // Rendered from the source at twin time, never stored in the placed codex (V-T3).
    // The slice cannot fail: every line span was cut from this flow's canonical text by the
    // paginator and `PaginatedCodex` has no public constructor; the marker keeps the twin total
    // and honest, and the laws assert it never appears.
    out.append(" |").append(placed.text(text).fold(unreadable, escape)).append("|\n")
    line.annotations.foreach { piece =>
      out.append("  ").append(piece.id.value).append(' ').append(piece.span.toString)
      placed.annotationOf(piece) match
        case Some(annotation) => renderAnnotation(placed, annotation, out)
        case None             => out.append(" (unresolved)")
      out.append('\n')
    }

  private def renderAnnotation(
      placed: PaginatedCodex,
      annotation: TextAnnotation,
      out: StringBuilder
  ): Unit =
    out
      .append(' ')
      .append(annotation.kind.wireName)
      .append(" lane=")
      .append(renderLane(placed.flow.lanes.slotOf(annotation.id)))
      .append(" target=")
      .append(annotation.target.render)
    ()

  private def renderLane(slot: Option[LaneSlot]): String = slot match
    case Some(LaneSlot.Lane(index)) => index.value.toString
    case Some(LaneSlot.Overflow)    => "overflow"
    case None                       => "unallocated"

  /** The visible form of a line whose text could not be sliced; never produced in practice. */
  val UnreadableMarker: String = "<unreadable"

  private def unreadable(error: LayoutError): String =
    s"$UnreadableMarker: ${error.message}>"

  private def escape(text: String): String =
    text.replace("\\", "\\\\").replace("\n", "\\n")

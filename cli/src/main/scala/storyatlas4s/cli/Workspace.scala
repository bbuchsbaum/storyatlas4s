package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.edition.{EditionSpec, Focus}
import storyatlas4s.intaglio.PagedCodexLowering
import storyatlas4s.layout.PaginatedCodex
import storymodel4s.view.*

/** The two-pane workspace: the exact words beside one plate, under one selection.
  *
  * The design brief's default architecture is a persistent Codex holding roughly half the usable
  * width and **one** primary projection beside it, coordinated by one persistent semantic focus
  * (§5, §6). Everything on this page is that shape:
  *
  *   - the reading pane is real DOM text at 16px, never clamped and never miniaturised to make the
  *     plate fit; it is the evidential surface and it dominates the left half;
  *   - the plate is composed for the width it actually gets, so its own label budget is solved for
  *     this pane rather than scaled down into illegibility;
  *   - one address is focused, chosen by a printed rule, and each face states what it could do with
  *     it — on a mark, via an ancestor, or off the projection. A selection is never silently
  *     dropped;
  *   - the absences that have no honest discourse position are given a region that is visibly not
  *     the text: outside the page rail, under its own rule, addressed by subject rather than by
  *     offset. They are the model's failures and they are not allowed to borrow a plausible span.
  *
  * There is no script and no external resource: this is design and scientific evidence that opens
  * from the filesystem, not an interactive prototype.
  */
object Workspace:

  val File: String = "workspace.html"

  /** The one annotation family the reading pane paints. */
  val PaintedKinds: Set[AnnotationKind] = AnnotationKind.absence

  def render(
      placed: PaginatedCodex,
      scene: NarrativeScene,
      plateSvg: String,
      focus: Option[Focus.Chosen],
      detail: String
  ): Either[CodexHtml.Error, String] =
    val flow = placed.flow
    val selected = Focus.annotations(flow, focus)
    for
      _ <- CodexHtml.unencodable(flow.source.canonicalText).toLeft(())
      // The reading pane paints exactly one layer: recorded absence, underlined in the line's own
      // leading. The other channels are compiled, named in the twin, and drawn at full resolution
      // in the standalone Codex plate — discoverable, not painted over the prose (brief §8.1).
      scenes <- PagedCodexLowering
        .lowerPages(placed, PaintedKinds, PagedCodexLowering.RowPolicy.Collapsed)
        .left
        .map(e => CodexHtml.Error.Lowering(e.message))
      options <- SvgOptions(
        placed.page.widthPx,
        placed.page.heightPx,
        Some(s"Narrative Codex overlay — $detail")
      ).left.map(e => CodexHtml.Error.Rendering(e.message))
      overlays <- scenes.traverse(scene =>
        SvgRenderer.render(scene, options).bimap(e => CodexHtml.Error.Rendering(e.message), _.value)
      )
      pages <- placed.pages.traverse(page => CodexHtml.renderPage(placed, page, overlays, selected))
    yield document(placed, scene, plateSvg, focus, detail, pages)

  private def document(
      placed: PaginatedCodex,
      scene: NarrativeScene,
      plateSvg: String,
      focus: Option[Focus.Chosen],
      detail: String,
      pages: Vector[String]
  ): String =
    val flow = placed.flow
    val receipt = placed.receipt
    val title = s"StoryAtlas workspace — $detail"
    val out = new StringBuilder
    out.append("<!DOCTYPE html>\n<html lang=\"")
    out.append(CodexHtml.escapeAttr(flow.source.language.value)).append("\">\n<head>\n")
    out.append("<meta charset=\"utf-8\">\n")
    out.append("<title>").append(CodexHtml.escapeText(title)).append("</title>\n")
    out.append("<style>\n").append(css(placed)).append("</style>\n</head>\n<body>\n")
    header(out, placed, focus)
    focusBar(out, flow, scene, focus)
    out.append("<main class=\"panes\">\n")
    readingPane(out, placed, pages)
    platePane(out, plateSvg, scene)
    out.append("</main>\n")
    unplacedLedger(out, flow)
    footer(out, flow, receipt)
    out.append("</body>\n</html>\n")
    out.result()

  // ------------------------------------------------------------------ regions

  private def header(
      out: StringBuilder,
      placed: PaginatedCodex,
      focus: Option[Focus.Chosen]
  ): Unit =
    val flow = placed.flow
    val p = flow.provenance
    out.append("<header>\n<h1>StoryAtlas workspace</h1>\n")
    out.append("<p class=\"identity\">")
    out.append("<strong>Story</strong> ")
    out.append(CodexHtml.escapeText(flow.source.title.getOrElse(flow.source.id.value)))
    out.append(" · <strong>Basis</strong> ")
    out.append(CodexHtml.escapeText(p.basis.label))
    p.draft.foreach { d =>
      out.append(" · ")
      out.append(if d.promoted then "promotable" else "does not promote")
      out.append(" · ")
      out.append(d.violationCount).append(" unsatisfied laws")
      out.append(" · ")
      out.append(
        d.gapCount.fold("derivation record not supplied")(n => s"$n derivation gaps")
      )
    }
    out.append("</p>\n")
    out.append("<p class=\"checksums\"><span>source ")
    out.append(CodexHtml.escapeText(p.sourceChecksum.hex))
    out.append("</span><span>configuration ")
    out.append(CodexHtml.escapeText(p.configChecksum.hex))
    out.append("</span><span>compiler ")
    out.append(CodexHtml.escapeText(p.compilerVersion))
    out.append("</span></p>\n")
    if focus.isEmpty then
      out.append("<p class=\"note\">")
      out.append(
        CodexHtml.escapeText(
          "No object satisfies the focus rule in this compilation, so the workspace carries no " +
            "shared selection. That is a stated result, not a missing one."
        )
      )
      out.append("</p>\n")
    out.append("</header>\n")

  /** The breadcrumb of the shared focus contract, and what each face did with it. */
  private def focusBar(
      out: StringBuilder,
      flow: CodexFlow,
      scene: NarrativeScene,
      focus: Option[Focus.Chosen]
  ): Unit =
    out.append("<section class=\"focus\">\n")
    out.append("<h2>Shared selection</h2>\n<dl>\n")
    definition(out, "Rule", Focus.rule)
    focus match
      case None =>
        definition(out, "Resolved", "nothing; no situation sits outside the narrated world here")
      case Some(chosen) =>
        definition(out, "Address", chosen.address.render)
        definition(out, "Model label", chosen.label)
        definition(out, "Context frame", s"${chosen.context.label} · lane ${chosen.lane}")
        definition(
          out,
          "In the Codex",
          Focus.placement(flow.selectionPlacements.get(chosen.address))
        )
        definition(
          out,
          "In the Atlas",
          Focus.placement(scene.selectionPlacements.get(chosen.address))
        )
    out.append("</dl>\n<p class=\"note\">")
    out.append(
      CodexHtml.escapeText(
        "One address, carried by both faces. Where a face has no direct mark for it the state " +
          "says so; the selection is preserved either way and is never silently dropped."
      )
    )
    out.append("</p>\n</section>\n")

  private def readingPane(out: StringBuilder, placed: PaginatedCodex, pages: Vector[String]): Unit =
    val flow = placed.flow
    val kinds = flow.annotations.map(_.kind.wireName).distinct.sorted
    out.append("<section class=\"pane codex\">\n")
    out.append("<h2>Narrative Codex <span class=\"sub\">exact text</span></h2>\n")
    out.append("<p class=\"note\">")
    val painted = flow.annotations.count(a => PaintedKinds.contains(a.kind))
    out.append(flow.annotations.length).append(" annotations over ")
    out.append(placed.pages.length)
    out.append(if placed.pages.length == 1 then " page — " else " pages — ")
    out.append(CodexHtml.escapeText(kinds.mkString(", ")))
    out.append(". Every word below is the canonical text; nothing is inserted, elided or rewritten")
    out.append(" to explain a model state.")
    out.append("</p>\n<p class=\"note\">")
    out.append("Painted here: ").append(painted)
    out.append(
      CodexHtml.escapeText(
        " recorded absences, underlined in the line's own leading on exactly the words they " +
          "concern. The other channels are compiled and named in the twin, and drawn at full " +
          "lane resolution in the standalone Codex plate: a reading surface carries one layer, " +
          "not every layer at once."
      )
    )
    out.append("</p>\n")
    emptyChannels(flow).foreach { statement =>
      out.append("<p class=\"unavailable\">").append(CodexHtml.escapeText(statement))
      out.append("</p>\n")
    }
    pages.foreach(out.append)
    out.append("</section>\n")

  /** Channels the contract declares and this compilation could not fill.
    *
    * A declared channel with nothing in it is a scientific state of its own, and the design brief
    * is explicit that missing, not requested and unavailable must not all render as an empty mark
    * (§8.4). Here the cause is the input boundary: a model read from a `storymodel.json` arrives
    * with no derivation record, because the record is not an interchange artifact — the pipeline's
    * own compilation report writes upstream claims and evidence as counts, so the data is not in
    * the file. The gap and abstention channels are therefore uncompilable rather than empty, and
    * the page says which.
    */
  private[cli] def emptyChannels(flow: CodexFlow): Option[String] =
    val present = flow.annotations.map(_.kind).toSet
    val empty = (flow.contract.activeKinds -- present).toVector.map(_.wireName).sorted
    if empty.isEmpty then None
    else
      val absenceChannels = empty.filter(name => name == "gap" || name == "abstention")
      val why =
        if absenceChannels.isEmpty then ""
        else if flow.provenance.draft.exists(_.gapCount.isEmpty) then
          s" No derivation record accompanied this model, so ${absenceChannels.mkString(" and ")} " +
            "could not be compiled at all. That is a state of the input, not a finding about the " +
            "story: a derivation record is not yet an interchange artifact, so a model read from " +
            "a file cannot carry one."
        else s" The derivation record reported nothing for ${absenceChannels.mkString(" and ")}."
      Some(s"Declared channels with no annotation here: ${empty.mkString(", ")}.$why")

  private def platePane(out: StringBuilder, plateSvg: String, scene: NarrativeScene): Unit =
    out.append("<section class=\"pane atlas\">\n")
    out.append("<h2>Discourse Atlas <span class=\"sub\">")
    out.append(CodexHtml.escapeText(s"${scene.zoom.narrative} / ${scene.zoom.surface}"))
    out.append("</span></h2>\n")
    out.append("<p class=\"note\">")
    out.append(
      CodexHtml.escapeText(
        "One projection at a time. Its axes, lanes and withheld labels are declared on the plate."
      )
    )
    out.append("</p>\n<div class=\"plate\">").append(plateSvg).append("</div>\n")
    out.append("</section>\n")

  /** A region that is visibly not a position in the text.
    *
    * These records concern the model, not a stretch of words: their subject could not be resolved
    * to a discourse position, and borrowing a plausible span would be an invention. So they sit
    * outside the page rail, are addressed by subject, and carry the reason no offset was honest.
    */
  private def unplacedLedger(out: StringBuilder, flow: CodexFlow): Unit =
    flow.draft.foreach { ledger =>
      out.append("<section class=\"unplaced\">\n")
      out.append("<h2>Recorded absence with no position in the text</h2>\n")
      out.append("<p class=\"note\">")
      if ledger.unplaced.isEmpty then
        out.append(
          CodexHtml.escapeText(
            s"None. All ${ledger.marked.length} recorded absences cite spans and are annotated on " +
              "exactly those words above."
          )
        )
      else
        out.append(ledger.unplaced.length)
        out.append(
          CodexHtml.escapeText(
            s" of ${ledger.total} recorded absences have no honest discourse position, so they are " +
              "not in the text at all. They are listed here by subject, with the reason no offset " +
              "would have been true. The other "
          )
        )
        out.append(ledger.marked.length)
        out.append(
          CodexHtml.escapeText(" cite spans and are annotated on exactly those words above.")
        )
      out.append("</p>\n")
      if ledger.unplaced.nonEmpty then
        out.append("<table>\n<thead><tr>")
        Vector("Subject", "Kind", "Channel", "No position because", "Record").foreach { head =>
          out.append("<th scope=\"col\">").append(CodexHtml.escapeText(head)).append("</th>")
        }
        out.append("</tr></thead>\n<tbody>\n")
        ledger.unplaced
          .sortBy(entry => (entry.absence.kind.wireName, entry.reason.render, entry.subject.render))
          .foreach { entry =>
            out.append("<tr>")
            cell(out, entry.subject.render, mono = true)
            cell(out, entry.absence.kind.wireName, mono = true)
            cell(out, entry.absence.channel.toString, mono = false)
            cell(out, entry.reason.render, mono = true)
            cell(out, entry.absence.render, mono = false)
            out.append("</tr>\n")
          }
        out.append("</tbody>\n</table>\n")
      out.append("</section>\n")
    }

  private def footer(
      out: StringBuilder,
      flow: CodexFlow,
      receipt: storyatlas4s.layout.LayoutReceipt
  ): Unit =
    out.append("<footer>\n<details>\n<summary>Provenance and layout receipt</summary>\n<dl>\n")
    definition(out, "Basis", flow.provenance.basis.label)
    definition(out, "Source checksum", flow.provenance.sourceChecksum.hex)
    definition(
      out,
      "Model receipt checksum",
      flow.provenance.modelReceiptChecksum.fold("not available")(_.hex)
    )
    definition(out, "Compiler", flow.provenance.compilerVersion)
    definition(out, "Configuration", flow.provenance.configChecksum.hex)
    definition(
      out,
      "Channels",
      flow.contract.activeKinds.toVector.map(_.wireName).sorted.mkString(", ")
    )
    definition(out, "Lane overflow", flow.lanes.overflow.length.toString)
    receipt.fields.foreach((key, value) => definition(out, key, value))
    definition(out, "receiptChecksum", receipt.checksum.hex)
    out.append("</dl>\n</details>\n<p class=\"note\">")
    out.append(
      CodexHtml.escapeText(
        "No script and no external resource. Every visual on this page has a textual twin beside " +
          "it in the edition."
      )
    )
    out.append("</p>\n</footer>\n")

  // ------------------------------------------------------------------ plumbing

  private def definition(out: StringBuilder, term: String, value: String): Unit =
    out
      .append("<dt>")
      .append(CodexHtml.escapeText(term))
      .append("</dt><dd>")
      .append(CodexHtml.escapeText(value))
      .append("</dd>\n")
    ()

  private def cell(out: StringBuilder, value: String, mono: Boolean): Unit =
    out
      .append(if mono then "<td class=\"mono\">" else "<td>")
      .append(CodexHtml.escapeText(value))
      .append("</td>")
    ()

  private def css(placed: PaginatedCodex): String =
    val receipt = placed.receipt
    val lineHeight = receipt.lineHeight.toDouble / receipt.unitsPerPixel
    val sb = new StringBuilder
    sb.append(":root { --paper: #faf8f2; --ink: #14140f; --muted: #4a4a44; --rule: #8e8e86;")
    sb.append(" --focus: #1b4f72; --absence: #7a5310; }\n")
    sb.append("* { box-sizing: border-box; }\n")
    sb.append("body { margin: 0; padding: 24px; background: var(--paper); color: var(--ink);")
    sb.append(" font: 15px/1.55 Palatino, 'Palatino Linotype', 'Book Antiqua', Georgia, serif; }\n")
    sb.append("h1 { font-size: 22px; margin: 0 0 6px; font-weight: normal; }\n")
    sb.append("h2 { font-size: 15px; margin: 0 0 6px; }\n")
    sb.append("h2 .sub { font-weight: normal; color: var(--muted); }\n")
    sb.append(".note { margin: 0 0 10px; color: var(--muted); font-size: 13px; }\n")
    sb.append(".unavailable { margin: 0 0 12px; padding: 8px 10px; font-size: 13px;")
    sb.append(" color: var(--absence); border-left: 3px solid var(--absence);")
    sb.append(" background: #f4efe3; }\n")
    sb.append("header, .focus, .unplaced, footer { max-width: ")
    sb.append(EditionSpec.workspacePageWidthPx + EditionSpec.workspaceAtlasWidthPx + 60)
    sb.append("px; }\n")
    sb.append("header { border-bottom: 1px solid var(--rule); padding-bottom: 12px; }\n")
    sb.append(".identity { margin: 0 0 4px; }\n")
    sb.append(".checksums { margin: 0; font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo,")
    sb.append(" Consolas, monospace; color: var(--muted); }\n")
    sb.append(".checksums span { display: inline-block; margin-right: 22px; }\n")
    sb.append(".focus { border-bottom: 1px solid var(--rule); padding: 12px 0; }\n")
    sb.append(".focus dl { display: grid; grid-template-columns: max-content 1fr; gap: 2px 16px;")
    sb.append(" margin: 0 0 8px; font-size: 13px; }\n")
    sb.append("dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; }\n")
    sb.append(".focus dd { font: 13px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas,")
    sb.append(" monospace; }\n")
    sb.append(".panes { display: flex; gap: 28px; align-items: flex-start; padding: 16px 0; }\n")
    sb.append(".pane { min-width: 0; }\n")
    sb.append(".codex { flex: 0 0 ")
    sb.append(placed.page.widthPx + 2).append("px; }\n")
    sb.append(".atlas { flex: 0 0 ")
    sb.append(EditionSpec.workspaceAtlasWidthPx + 2).append("px; }\n")
    sb.append(".plate { border: 1px solid var(--rule); background: var(--paper); }\n")
    // Never scaled: the plate's type floor is stated in points, and a browser downscale would
    // put it below the floor the plate's own laws enforce.
    sb.append(".plate svg { display: block; }\n")
    sb.append(".page { position: relative; width: ")
    sb.append(receipt.page.widthPx).append("px; height: ")
    sb.append(receipt.page.heightPx).append("px; margin: 0 0 20px;")
    sb.append(" border: 1px solid var(--rule); background: #ffffff; }\n")
    sb.append(".text { position: absolute; top: 0; left: 0; margin: 0; width: 100%; height: 100%;")
    sb.append(" overflow: hidden; font-family: ")
    sb.append(CodexHtml.cssFamily(receipt.style.family))
    sb.append("; font-size: ").append(receipt.style.sizePx)
    sb.append("px; line-height: ").append(lineHeight).append("px; color: var(--ink); }\n")
    sb.append(".line { display: block; height: ").append(lineHeight)
    sb.append("px; white-space: pre; overflow: hidden; }\n")
    sb.append(".overlay { position: absolute; top: 0; left: 0; pointer-events: none; }\n")
    sb.append(".overlay svg { display: block; }\n")
    sb.append(".page-label { position: absolute; left: 0; bottom: -1.4em;")
    sb.append(" font: 11px/1.2 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;")
    sb.append(" color: var(--muted); }\n")
    sb.append(CodexHtml.selectionCss)
    sb.append(".unplaced { border-top: 3px double var(--rule); margin-top: 8px;")
    sb.append(" padding-top: 14px; }\n")
    sb.append(".unplaced table { border-collapse: collapse; font-size: 13px; width: 100%; }\n")
    sb.append(".unplaced th, .unplaced td { text-align: left; vertical-align: top;")
    sb.append(" padding: 3px 14px 3px 0; border-bottom: 1px solid #e0ded6; }\n")
    sb.append(".unplaced th { color: var(--absence); font-weight: bold; }\n")
    sb.append(".unplaced .mono { font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas,")
    sb.append(" monospace; }\n")
    sb.append("footer { border-top: 1px solid var(--rule); margin-top: 18px; padding-top: 12px;")
    sb.append(" font-size: 13px; }\n")
    sb.append("footer dl { display: grid; grid-template-columns: max-content 1fr; gap: 0 16px;")
    sb.append(" margin: 8px 0 0; font-size: 12px; }\n")
    sb.append("summary { cursor: pointer; }\n")
    sb.result()

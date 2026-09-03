package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.edition.{EditionSpec, Focus}
import storyatlas4s.intaglio.PagedCodexLowering
import storymodel4s.story.ContextKind
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

  def render(
      flow: CodexFlow,
      scene: NarrativeScene,
      sentences: Vector[VisualPrimitive.SurfaceUnit],
      plateSvg: String,
      focus: Option[Focus.Chosen],
      detail: String
  ): Either[CodexHtml.Error, String] =
    CodexHtml
      .unencodable(flow.source.canonicalText)
      .toLeft(document(flow, scene, sentences, plateSvg, focus, detail))

  private def document(
      flow: CodexFlow,
      scene: NarrativeScene,
      sentences: Vector[VisualPrimitive.SurfaceUnit],
      plateSvg: String,
      focus: Option[Focus.Chosen],
      detail: String
  ): String =
    val title = s"StoryAtlas workspace — $detail"
    val out = new StringBuilder
    out.append("<!DOCTYPE html>\n<html lang=\"")
    out.append(CodexHtml.escapeAttr(flow.source.language.value)).append("\">\n<head>\n")
    out.append("<meta charset=\"utf-8\">\n")
    out.append("<title>").append(CodexHtml.escapeText(title)).append("</title>\n")
    out.append("<style>\n").append(css()).append("</style>\n</head>\n<body>\n")
    header(out, flow, focus)
    projections(out, scene)
    focusBar(out, flow, scene, focus)
    val rows = Reading.rows(sentences, flow.source.canonicalText.length)
    out.append("<main class=\"panes\">\n")
    readingPane(out, flow, scene, rows, focus)
    out.append("<div class=\"pane atlas\">\n")
    platePane(out, plateSvg, scene)
    castList(out, flow, scene, rows)
    inspector(out, flow, scene, focus)
    unplacedLedger(out, flow)
    out.append("</div>\n")
    out.append("</main>\n")
    footer(out, flow)
    out.append("</body>\n</html>\n")
    out.result()

  // ------------------------------------------------------------------ regions

  private def header(
      out: StringBuilder,
      flow: CodexFlow,
      focus: Option[Focus.Chosen]
  ): Unit =
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
    out.append("</p>\n<p class=\"note\">")
    // The reader's horizon is a state of the whole view and has to be visible, even where a static
    // edition cannot offer the control that changes it.
    out.append("<strong>Reader horizon</strong> ")
    out.append(
      CodexHtml.escapeText(
        flow.contract.horizon match
          case EpistemicHorizon.Omniscient => "omniscient — every claim the model holds is shown"
          case EpistemicHorizon.ReaderAt(offset) =>
            s"reader at UTF-16 offset $offset — only claims a reader could have made by there"
      )
    )
    out.append(
      CodexHtml.escapeText(
        ". This edition compiles one horizon; comparing two is an interaction, and this page has " +
          "no controls by design."
      )
    )
    out.append("</p>\n</section>\n")

  /** The words, one row per sentence, with what discriminates painted on them.
    *
    * A row is a sentence and the material up to the next, so the rows tile the canonical text
    * exactly and no character of the source is dropped between them. The prose is set in a serif at
    * a reading size and a reading leading; the gutter beside it carries the sentence's own address
    * and the counts of the uniform channels, which is where a count belongs.
    */
  private def readingPane(
      out: StringBuilder,
      flow: CodexFlow,
      scene: NarrativeScene,
      rows: Vector[Reading.Row],
      focus: Option[Focus.Chosen]
  ): Unit =
    val text = flow.source.canonicalText
    val selected = Focus.annotations(flow, focus)
    val frames = Reading.frames(scene)
    val painted = Reading.painted(flow, frames, selected)
    val speech = flow.annotations.count(a =>
      a.kind == AnnotationKind.Context &&
        frames.get(a.target).exists(_ != ContextKind.NarratedWorld)
    )
    val mentions = flow.annotations.count(_.kind == AnnotationKind.Entity)

    out.append("<section class=\"pane codex\">\n")
    out.append("<h2>Narrative Codex <span class=\"sub\">the exact words</span></h2>\n")
    out.append("<p class=\"note\">")
    out.append(rows.length).append(" sentences · ").append(flow.annotations.length)
    out.append(" annotations compiled. Every word below is the canonical text: nothing is ")
    out.append("inserted, elided or rewritten to explain a model state.</p>\n")

    out.append("<ul class=\"key\">\n")
    keyRow(out, Reading.Speech, s"$speech context frames that are not the narrated world")
    keyRow(out, Reading.Mention, s"$mentions entity mentions")
    keyRow(out, Reading.Selected, "the shared selection")
    out.append("</ul>\n")
    out.append("<p class=\"note\">")
    out.append(
      CodexHtml.escapeText(
        "Painted on the words: only what tells one passage from another. The uniform channels — " +
          "one claim per situation, one unsatisfied law per situation — are counted in the gutter " +
          "instead, because sixty-five identical marks on the prose would say nothing about the " +
          "story and would hide what does."
      )
    )
    out.append("</p>\n")
    emptyChannels(flow).foreach { statement =>
      out.append("<p class=\"unavailable\">").append(CodexHtml.escapeText(statement))
      out.append("</p>\n")
    }

    out.append("<div class=\"reading\">\n")
    out.append("<div class=\"rowhead\"><span class=\"ord\">unit</span>")
    out.append("<span class=\"tal\">claims</span><span class=\"tal\">laws</span>")
    out.append("<span class=\"prosehead\">the canonical text</span></div>\n")
    rows.foreach { row =>
      val claims = Reading.tally(flow, row, AnnotationKind.Claim)
      val laws = Reading.tally(flow, row, AnnotationKind.UnsatisfiedLaw)
      out.append("<div class=\"row\" data-name=\"")
      out.append(CodexHtml.escapeAttr(row.mark.value)).append("\">")
      out.append("<span class=\"ord\">s").append("%02d".format(row.ordinal)).append("</span>")
      tallyCell(out, claims, "cl")
      tallyCell(out, laws, "law")
      out.append("<p class=\"prose\">")
      Reading.runs(row, text, painted).foreach { run =>
        if run.classes.isEmpty then out.append(CodexHtml.escapeText(run.text))
        else
          out
            .append("<span class=\"")
            .append(run.classes.mkString(" "))
            .append("\">")
            .append(CodexHtml.escapeText(run.text))
            .append("</span>")
      }
      out.append("</p></div>\n")
    }
    out.append("</div>\n</section>\n")

  private def keyRow(out: StringBuilder, cls: String, label: String): Unit =
    out
      .append("<li><span class=\"")
      .append(cls)
      .append(" swatch\">words</span> ")
      .append(CodexHtml.escapeText(label))
      .append("</li>\n")
    ()

  private def tallyCell(out: StringBuilder, count: Int, cls: String): Unit =
    out.append("<span class=\"tal ").append(cls)
    if count == 0 then out.append(" zero")
    out.append("\">").append(if count == 0 then "·" else count.toString).append("</span>")
    ()

  /** Question 3, answered: who is in this story, and where does each appear.
    *
    * Every row is the entity's own label, the count of its mentions, the sentences it runs between,
    * and the exact words it is mentioned by. Nothing here is a summary of the story; it is an index
    * of it, and every cell can be read back to the text beside it.
    */
  private def castList(
      out: StringBuilder,
      flow: CodexFlow,
      scene: NarrativeScene,
      rows: Vector[Reading.Row]
  ): Unit =
    val cast = Reading.cast(flow, scene, rows)
    out.append("<section class=\"cast\">\n<h2>Cast <span class=\"sub\">")
    out.append("who is in this story, and where</span></h2>\n")
    if cast.isEmpty then
      out.append("<p class=\"note\">")
      out.append(
        CodexHtml.escapeText(
          "No entity in this compilation carries both a label and a mention. An entity's own " +
            "label reaches a compiled scene only through its thread, so an entity outside the " +
            "thread budget has an address here and no name."
        )
      )
      out.append("</p>\n")
    else
      out.append("<p class=\"note\">")
      out.append(cast.length).append(" of ")
      out.append(
        flow.annotations.map(_.target).filter(_.render.contains("/entity/")).distinct.length
      )
      out.append(
        CodexHtml.escapeText(
          " entities carry a label here. A label reaches the plate only through an entity's " +
            "thread, so the thread budget is what decides how much of the cast can be named."
        )
      )
      out.append("</p>\n<table>\n<thead><tr>")
      Vector("Entity", "Mentions", "From", "To", "Mentioned by").foreach(h =>
        out.append("<th scope=\"col\">").append(CodexHtml.escapeText(h)).append("</th>")
      )
      out.append("</tr></thead>\n<tbody>\n")
      cast.foreach { member =>
        out.append("<tr>")
        out.append("<td class=\"who\">").append(CodexHtml.escapeText(member.label)).append("</td>")
        out.append("<td class=\"num\">").append(member.mentions).append("</td>")
        out.append("<td class=\"num\">s").append("%02d".format(member.firstUnit)).append("</td>")
        out.append("<td class=\"num\">s").append("%02d".format(member.lastUnit)).append("</td>")
        out.append("<td>")
        out.append(
          CodexHtml.escapeText(
            member.words.take(8).mkString(", ") + (if member.words.length > 8 then ", …" else "")
          )
        )
        out.append("</td></tr>\n")
      }
      out.append("</tbody>\n</table>\n")
    out.append("</section>\n")

  /** Every projection this edition built, so "one at a time" is not mistaken for "only one".
    *
    * Plain links to sibling files: the page carries no script, and a reader who wants another zoom
    * or lens can reach it without one.
    */
  private def projections(out: StringBuilder, scene: NarrativeScene): Unit =
    out.append("<nav class=\"projections\"><span class=\"navlabel\">Projections</span>\n")
    EditionSpec.zoomLevels.foreach { zoom =>
      val stem =
        s"atlas-${zoom.narrative.toString.toLowerCase}-${zoom.surface.toString.toLowerCase}"
      val here = zoom == scene.zoom
      out.append(
        if here then s"""<span class="here" data-name="$stem">"""
        else s"""<a href="$stem.svg">"""
      )
      out.append(CodexHtml.escapeText(s"${zoom.narrative}/${zoom.surface}"))
      out.append(if here then "</span>" else "</a>")
    }
    EditionSpec.lenses.foreach { lens =>
      out.append(s"""<a href="codex-${lens.toString.toLowerCase}.html">""")
      out.append(CodexHtml.escapeText(s"Codex $lens"))
      out.append("</a>")
    }
    out.append("\n<span class=\"navnote\">")
    out.append(
      CodexHtml.escapeText(
        "This workspace shows one projection; the edition built the rest beside it."
      )
    )
    out.append("</span></nav>\n")

  /** Question 5 of the recovery plan: what supports this claim, and on what basis.
    *
    * Every field is separate and every field is the model's own. Nothing here is bundled into a
    * single number, and nothing is called a probability that is not one.
    */
  private def inspector(
      out: StringBuilder,
      flow: CodexFlow,
      scene: NarrativeScene,
      focus: Option[Focus.Chosen]
  ): Unit =
    out.append("<section class=\"inspector\">\n<h2>Inspector <span class=\"sub\">")
    out.append("the selected object, field by field</span></h2>\n")
    focus match
      case None         => out.append("<p class=\"note\">Nothing is selected.</p>\n")
      case Some(chosen) =>
        val ids = Focus.annotations(flow, chosen.some)
        val annotations = flow.annotations.filter(a => ids.contains(a.id))
        out.append("<dl>\n")
        definition(out, "Address", chosen.address.render)
        definition(out, "Model label", chosen.label)
        definition(out, "Context frame", chosen.context.label)
        definition(out, "Lane", chosen.lane.toString)
        definition(
          out,
          "Atlas mark",
          Focus.marks(scene, chosen.some).map(_.value).toVector.sorted.mkString(", ")
        )
        annotations.zipWithIndex.foreach { (annotation, index) =>
          val n = if annotations.length == 1 then "" else s" ${index + 1}"
          definition(out, s"Annotation$n", annotation.id.value)
          definition(out, s"Kind$n", annotation.kind.wireName)
          definition(
            out,
            s"Exact support$n",
            annotation.support.spans.toVector
              .map(s => s"[${s.start},${s.endExclusive})")
              .mkString(", ")
          )
          definition(
            out,
            s"Words$n",
            annotation.support.spans.toVector
              .flatMap(_.slice(flow.source.canonicalText).toOption)
              .mkString(" … ")
          )
          definition(out, s"Priority$n", annotation.priority.value.toString)
          definition(
            out,
            s"Upstream$n",
            if annotation.audit.upstream.isEmpty then "none"
            else s"${annotation.audit.upstream.length} claims and evidence records"
          )
        }
        out.append("</dl>\n<p class=\"note\">")
        out.append(
          CodexHtml.escapeText(
            "No number on this page is a calibrated probability, and none is offered as one. " +
              "Priority is the channel's own ordering; upstream is a count of the records the " +
              "annotation cites, which the twin lists in full."
          )
        )
        out.append("</p>\n")
    out.append("</section>\n")

  extension [A](a: A) private def some: Option[A] = Some(a)

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
    out.append("<section class=\"plateblock\">\n")
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

  private def footer(out: StringBuilder, flow: CodexFlow): Unit =
    out.append("<footer>\n<details>\n<summary>Provenance</summary>\n<dl>\n")
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

  private def css(): String =
    val sb = new StringBuilder
    sb.append(":root { --paper: #faf8f2; --ink: #14140f; --muted: #4a4a44; --rule: #8e8e86;")
    sb.append(" --hair: #d9d6cd; --focus: #1b4f72; --absence: #7a5310; --speech: #ece4d3; }\n")
    sb.append("* { box-sizing: border-box; }\n")
    sb.append("body { margin: 0; padding: 24px; background: var(--paper); color: var(--ink);")
    sb.append(" font: 15px/1.55 Palatino, 'Palatino Linotype', 'Book Antiqua', Georgia, serif; }\n")
    sb.append("h1 { font-size: 22px; margin: 0 0 6px; font-weight: normal; }\n")
    sb.append("h2 { font-size: 15px; margin: 0 0 6px; }\n")
    sb.append("h2 .sub { font-weight: normal; color: var(--muted); }\n")
    sb.append(".note { margin: 0 0 10px; color: var(--muted); font-size: 13px; }\n")
    val width = EditionSpec.workspacePageWidthPx + EditionSpec.workspaceAtlasWidthPx + 60
    sb.append("header, .focus, .projections, .inspector, .unplaced, footer { max-width: ")
    sb.append(width).append("px; }\n")
    sb.append("header { border-bottom: 1px solid var(--rule); padding-bottom: 12px; }\n")
    sb.append(".identity { margin: 0 0 4px; }\n")
    sb.append(".checksums { margin: 0; font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo,")
    sb.append(" Consolas, monospace; color: var(--muted); }\n")
    sb.append(".checksums span { display: inline-block; margin-right: 22px; }\n")
    // Projections: plain links, so one at a time is never mistaken for only one.
    sb.append(".projections { padding: 10px 0; border-bottom: 1px solid var(--rule);")
    sb.append(" font-size: 12px; }\n")
    sb.append(".navlabel { color: var(--muted); margin-right: 10px; }\n")
    sb.append(".projections a, .projections .here { display: inline-block; padding: 2px 8px;")
    sb.append(" margin: 0 4px 4px 0; border: 1px solid var(--rule); text-decoration: none;")
    sb.append(" color: var(--muted); font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo,")
    sb.append(" Consolas, monospace; }\n")
    sb.append(".projections .here { border-color: var(--focus); color: var(--focus);")
    sb.append(" font-weight: bold; }\n")
    sb.append(".navnote { color: var(--muted); margin-left: 8px; }\n")
    sb.append(".focus, .inspector { border-bottom: 1px solid var(--rule); padding: 12px 0; }\n")
    sb.append(".focus dl, .inspector dl { display: grid;")
    sb.append(" grid-template-columns: max-content 1fr; gap: 2px 16px; margin: 0 0 8px;")
    sb.append(" font-size: 13px; }\n")
    sb.append("dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; }\n")
    sb.append(".focus dd, .inspector dd { font: 13px/1.5 ui-monospace, SFMono-Regular, Menlo,")
    sb.append(" Consolas, monospace; }\n")
    sb.append(".panes { display: flex; gap: 28px; align-items: flex-start; padding: 16px 0; }\n")
    sb.append(".pane { min-width: 0; }\n")
    sb.append(".codex { flex: 0 0 ")
    sb.append(EditionSpec.workspacePageWidthPx).append("px; }\n")
    sb.append(".atlas { flex: 0 0 ")
    sb.append(EditionSpec.workspaceAtlasWidthPx + 2).append("px; }\n")
    sb.append(".plate { border: 1px solid var(--rule); background: var(--paper); }\n")
    // Never scaled: the plate's type floor is stated in points, and a browser downscale would put
    // it below the floor the plate's own laws enforce.
    sb.append(".plate svg { display: block; }\n")
    // The reading surface. A real serif at a reading size and a reading leading; the gutter is
    // beside the words, never on them.
    sb.append(".reading { border: 1px solid var(--rule); background: #fffdf8; }\n")
    sb.append(".rowhead, .row { display: grid;")
    sb.append(" grid-template-columns: 3.2em 4.2em 3.6em 1fr; align-items: baseline; }\n")
    sb.append(".rowhead { border-bottom: 1px solid var(--rule); padding: 4px 10px;")
    sb.append(" font: 11px/1.4 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;")
    sb.append(" color: var(--muted); }\n")
    sb.append(".row { padding: 3px 10px; border-bottom: 1px solid var(--hair); }\n")
    sb.append(".row:last-child { border-bottom: 0; }\n")
    sb.append(".ord, .tal { font: 12px/1.62 ui-monospace, SFMono-Regular, Menlo, Consolas,")
    sb.append(" monospace; color: var(--muted); }\n")
    sb.append(".tal { text-align: right; padding-right: 0.9em; }\n")
    sb.append(".tal.law { color: var(--absence); }\n")
    sb.append(".tal.zero { color: var(--hair); }\n")
    sb.append(".prose { margin: 0; font: 17px/1.62 Palatino, 'Palatino Linotype',")
    sb.append(" 'Book Antiqua', Georgia, serif; color: var(--ink); }\n")
    sb.append(".prosehead { font-size: 11px; }\n")
    // Three treatments, each a shape or a weight and not a hue alone.
    sb.append(".sp { background: var(--speech); box-shadow: -0.18em 0 0 var(--absence),")
    sb.append(" 0.18em 0 0 var(--absence); }\n")
    sb.append(".en { text-decoration: underline; text-decoration-thickness: 1px;")
    sb.append(" text-underline-offset: 3px; text-decoration-color: var(--muted); }\n")
    sb.append(".sl { outline: 1.5px solid var(--focus); outline-offset: 1px;")
    sb.append(" font-weight: bold; }\n")
    sb.append(".key { list-style: none; margin: 0 0 10px; padding: 0; font-size: 13px;")
    sb.append(" color: var(--muted); }\n")
    sb.append(".key li { margin-bottom: 3px; }\n")
    sb.append(".swatch { font: 15px/1.5 Palatino, Georgia, serif; color: var(--ink);")
    sb.append(" margin-right: 6px; }\n")
    sb.append(".unavailable { margin: 0 0 12px; padding: 8px 10px; font-size: 13px;")
    sb.append(" color: var(--absence); border-left: 3px solid var(--absence);")
    sb.append(" background: #f4efe3; }\n")
    sb.append(".plateblock { margin-bottom: 18px; }\n")
    sb.append(".cast { margin-bottom: 18px; }\n")
    sb.append(".cast table { border-collapse: collapse; font-size: 13px; width: 100%; }\n")
    sb.append(".cast th, .cast td { text-align: left; vertical-align: top;")
    sb.append(" padding: 3px 12px 3px 0; border-bottom: 1px solid var(--hair); }\n")
    sb.append(".cast th { color: var(--muted); font-weight: bold; font-size: 12px; }\n")
    sb.append(".cast .who { font: 15px/1.5 Palatino, Georgia, serif; }\n")
    sb.append(".cast .num { font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, Consolas,")
    sb.append(" monospace; text-align: right; color: var(--muted); }\n")
    sb.append(".unplaced { border-top: 3px double var(--rule); margin-top: 8px;")
    sb.append(" padding-top: 14px; }\n")
    sb.append(".unplaced table { border-collapse: collapse; font-size: 13px; width: 100%; }\n")
    sb.append(".unplaced th, .unplaced td { text-align: left; vertical-align: top;")
    sb.append(" padding: 3px 14px 3px 0; border-bottom: 1px solid var(--hair); }\n")
    sb.append(".unplaced th { color: var(--absence); font-weight: bold; }\n")
    sb.append(".unplaced .mono { font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas,")
    sb.append(" monospace; }\n")
    sb.append("footer { border-top: 1px solid var(--rule); margin-top: 18px; padding-top: 12px;")
    sb.append(" font-size: 13px; }\n")
    sb.append("footer dl { display: grid; grid-template-columns: max-content 1fr; gap: 0 16px;")
    sb.append(" margin: 8px 0 0; font-size: 12px; }\n")
    sb.append("summary { cursor: pointer; }\n")
    sb.result()

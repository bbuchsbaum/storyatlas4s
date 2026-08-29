package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.edition.EditionSpec
import storyatlas4s.layout.{LayoutError, Measurer, MonospaceMeasurer}
import storymodel4s.core.Address
import storymodel4s.story.{ModelStatus, StoryModel}
import storymodel4s.view.*

/** The Laminar shell: one `Var[ViewChoice]`, one derived `Signal` of the compiled artifacts, and
  * three regions bound to it — the paginated Codex (DOM text rail under an SVG overlay per page,
  * ADR 0002 D6), the Atlas at the chosen zoom level, and a side panel with the selection's
  * placements, the textual twins, and the receipts. Every change (lens, zoom, horizon, selection,
  * measurer) recompiles both artifacts through storymodel4s's compilers under one
  * `CommonViewState`, so Codex and Atlas agree by construction (D2).
  */
object AppView:

  def apply(
      model: StoryModel[ModelStatus.Validated],
      domMeasurer: Either[LayoutError, Measurer]
  ): HtmlElement =
    val text = model.source.canonicalText
    // The choice never names a measurer that does not exist: when the DOM measurer is unavailable
    // the initial choice is the table and the Dom option is not offered; if it were chosen anyway,
    // compilation fails with the measurer's own reason instead of substituting another metric.
    val initial = domMeasurer.fold(
      _ => ViewChoice.initial.copy(measurer = MeasurerChoice.Monospace),
      _ => ViewChoice.initial
    )
    val choice = Var(initial)
    def measurerFor(pick: MeasurerChoice): Either[String, Measurer] = pick match
      case MeasurerChoice.Monospace => Right(MonospaceMeasurer.instance)
      case MeasurerChoice.Dom       => domMeasurer.left.map(_.message)
    val compiled: Signal[Either[String, Compiled]] =
      choice.signal.map(c =>
        measurerFor(c.measurer).flatMap(measurer => AppCompiler.compile(model, c, measurer))
      )

    /** Click or keyboard activation of a named element: resolve to an address, then select. */
    def select(address: Address, extend: Boolean): Unit =
      choice.update { c =>
        val next =
          if !extend then Set(address)
          else if c.selection.contains(address) then c.selection - address
          else c.selection + address
        c.copy(selection = next)
      }

    div(
      cls("shell"),
      dataAttr("state") <-- compiled.map(_.fold(_ => "error", _ => "ready")),
      dataAttr("horizon") <-- choice.signal.map(c => Playhead.position(text, c.horizon).toString),
      controls(text, choice, domMeasurer),
      child <-- compiled.map {
        case Left(problem) =>
          div(cls("error"), role("alert"), s"Compilation failed: $problem")
        case Right(c) =>
          div(
            cls("workspace"),
            codexSection(c, select),
            atlasSection(c, select),
            panel(c, choice, domMeasurer)
          )
      }
    )

  private def controls(
      text: String,
      choice: Var[ViewChoice],
      domMeasurer: Either[LayoutError, Measurer]
  ): HtmlElement =
    val length = text.length
    form(
      cls("controls"),
      onSubmit.preventDefault --> Observer.empty,
      label(
        "Codex lens ",
        select(
          idAttr("lens"),
          EditionSpec.lenses.map(l => option(value(l.toString), l.toString)),
          controlled(
            value <-- choice.signal.map(_.lens.toString),
            onChange.mapToValue --> { v =>
              EditionSpec.lenses
                .find(_.toString == v)
                .foreach(l => choice.update(_.copy(lens = l)))
            }
          )
        )
      ),
      label(
        "Atlas zoom ",
        select(
          idAttr("zoom"),
          EditionSpec.levels.map(l => option(value(l.toString), l.toString)),
          controlled(
            value <-- choice.signal.map(_.level.toString),
            onChange.mapToValue --> { v =>
              EditionSpec.levels
                .find(_.toString == v)
                .foreach(l => choice.update(_.copy(level = l)))
            }
          )
        )
      ),
      label(
        "Measurer ",
        select(
          idAttr("measurer"),
          MeasurerChoice.values.toVector
            .filter(m => m != MeasurerChoice.Dom || domMeasurer.isRight)
            .map(m => option(value(m.toString), m.label)),
          controlled(
            value <-- choice.signal.map(_.measurer.toString),
            onChange.mapToValue --> { v =>
              MeasurerChoice.values
                .find(_.toString == v)
                .foreach(m => choice.update(_.copy(measurer = m)))
            }
          )
        )
      ),
      fieldSet(
        cls("playhead"),
        legend("Epistemic playhead (reader horizon)"),
        label(
          forId("horizon"),
          "Reader at "
        ),
        input(
          idAttr("horizon"),
          typ("range"),
          minAttr("0"),
          maxAttr(length.toString),
          stepAttr("1"),
          aria.valueMin(0.0),
          aria.valueMax(length.toDouble),
          controlled(
            value <-- choice.signal.map(c => Playhead.position(text, c.horizon).toString),
            onInput.mapToValue --> { v =>
              v.toIntOption.foreach { raw =>
                val offset = Playhead.snap(text, raw)
                choice.update(_.copy(horizon = EpistemicHorizon.ReaderAt(offset)))
              }
            }
          )
        ),
        outputTag(
          cls("horizon-readout"),
          forId("horizon"),
          child.text <-- choice.signal.map(c => Playhead.describe(text, c.horizon))
        ),
        label(
          input(
            idAttr("omniscient"),
            typ("checkbox"),
            controlled(
              checked <-- choice.signal.map(_.horizon == EpistemicHorizon.Omniscient),
              onClick.mapToChecked --> { on =>
                choice.update(c =>
                  c.copy(horizon =
                    if on then EpistemicHorizon.Omniscient
                    else EpistemicHorizon.ReaderAt(Playhead.position(text, c.horizon))
                  )
                )
              }
            )
          ),
          " Omniscient (no horizon)"
        )
      )
    )

  private def codexSection(c: Compiled, select: (Address, Boolean) => Unit): HtmlElement =
    val receipt = c.placed.receipt
    val lineHeightPx = receipt.lineHeight.toDouble / receipt.unitsPerPixel
    val font = s"${receipt.style.sizePx}px/${lineHeightPx}px ${receipt.style.cssFamily}"
    def activate(target: dom.EventTarget, extend: Boolean): Unit =
      SvgDom.nameAt(target).flatMap(c.fragmentTargets.get).foreach(select(_, extend))
    sectionTag(
      cls("codex"),
      aria.label("Narrative Codex"),
      dataAttr("fragments") := c.pieces.toString,
      dataAttr("lines") := c.placed.lines.length.toString,
      h2(s"Narrative Codex — lens ${c.choice.lens}"),
      p(
        cls("legend"),
        if c.rows.isEmpty then "Overlay rows: none (no annotation is placed)."
        else
          "Overlay rows, top to bottom within each line: " +
            c.rows.zipWithIndex.map((row, i) => s"${i + 1}: ${row.label}").mkString("; ") + "."
      ),
      onClick --> (ev => activate(ev.target, ev.shiftKey)),
      onKeyDown.filter(ev => ev.key == "Enter" || ev.key == " ") --> { ev =>
        ev.preventDefault()
        activate(ev.target, ev.shiftKey)
      },
      div(
        cls("pages"),
        c.pages.map { page =>
          sectionTag(
            cls("page"),
            dataAttr("page") := page.index.toString,
            styleAttr := s"width:${receipt.page.widthPx}px;height:${receipt.page.heightPx}px",
            // The rail: one span per placed line, nothing else, so the spans' concatenated text
            // content is the canonical text exactly (V-T2).
            div(
              cls("text"),
              styleAttr := s"font:$font",
              page.lines.map { line =>
                span(
                  cls("line"),
                  cls(SvgDom.SelectedClass) := line.selected,
                  dataAttr("name") := line.id,
                  dataAttr("selected") := line.selected.toString,
                  styleAttr := s"height:${lineHeightPx}px",
                  line.text
                )
              }
            ),
            div(
              cls("overlay"),
              onMountCallback { ctx =>
                SvgDom.inject(
                  ctx.thisNode.ref,
                  page.overlay,
                  c.selectedFragments,
                  name =>
                    c.fragmentTargets.get(name).map(a => s"annotation piece $name → ${a.render}")
                )
              }
            ),
            div(
              cls("page-label"),
              s"Page ${page.index + 1} of ${c.pages.length} · ${c.flow.provenance.basis.label}"
            )
          )
        }
      )
    )

  private def atlasSection(c: Compiled, select: (Address, Boolean) => Unit): HtmlElement =
    def activate(target: dom.EventTarget, extend: Boolean): Unit =
      SvgDom
        .nameAt(target)
        .flatMap(name => MarkId.from(name).toOption)
        .flatMap(c.scene.navigation.addressOf.get)
        .foreach(select(_, extend))
    sectionTag(
      cls("atlas"),
      aria.label("Narrative Atlas"),
      dataAttr("marks") := c.scene.marks.length.toString,
      dataAttr("names") := c.atlasNames.toString,
      h2(s"Narrative Atlas — zoom ${c.scene.zoom.narrative}/${c.scene.zoom.surface}"),
      p(
        cls("legend"),
        s"${c.scene.marks.length} mark(s). x = ${c.scene.contract.x}; y = ${c.scene.contract.y}. " +
          "Click a mark (shift-click extends) or focus it and press Enter to select its address."
      ),
      onClick --> (ev => activate(ev.target, ev.shiftKey)),
      onKeyDown.filter(ev => ev.key == "Enter" || ev.key == " ") --> { ev =>
        ev.preventDefault()
        activate(ev.target, ev.shiftKey)
      },
      div(
        cls("atlas-svg"),
        onMountCallback { ctx =>
          SvgDom.inject(
            ctx.thisNode.ref,
            c.atlasSvg,
            c.selectedMarks,
            name =>
              MarkId
                .from(name)
                .toOption
                .flatMap(c.scene.navigation.addressOf.get)
                .map(a => s"mark $name → ${a.render}")
          )
        }
      )
    )

  private def panel(
      c: Compiled,
      choice: Var[ViewChoice],
      domMeasurer: Either[LayoutError, Measurer]
  ): HtmlElement =
    asideTag(
      cls("panel"),
      aria.label("Selection, twins, receipts"),
      sectionTag(
        cls("selection"),
        h2("Selection"),
        if c.choice.selection.isEmpty then
          p("Nothing selected. Selection is a set of addresses shared by Codex and Atlas (V-I3).")
        else
          div(
            button(
              typ("button"),
              "Clear selection",
              onClick --> (_ => choice.update(_.copy(selection = Set.empty)))
            ),
            ul(
              c.choice.selection.toVector.sortBy(_.render).map { address =>
                val codex = c.codexPlacements.collectFirst { case (a, p) if a == address => p }
                val atlas = c.atlasPlacements.collectFirst { case (a, p) if a == address => p }
                li(
                  code(address.render),
                  ul(
                    li("Codex: ", codex.fold("unresolved")(_.render)),
                    li("Atlas: ", atlas.fold("unresolved")(renderPlacement))
                  )
                )
              }
            )
          )
      ),
      sectionTag(
        cls("twins"),
        h2("Textual twins"),
        detailsTag(summaryTag("Codex twin"), pre(c.flow.textualTwin)),
        detailsTag(summaryTag("Paginated Codex twin"), pre(c.placed.textualTwin)),
        detailsTag(summaryTag("Atlas twin"), pre(c.scene.textualTwin))
      ),
      sectionTag(
        cls("receipts"),
        h2("Receipts"),
        dl(
          (c.receipts :+ ("domMeasurer" -> domMeasurer.fold(
            error => s"unavailable: ${error.message}",
            measurer => s"available: ${measurer.name}"
          ))).flatMap { (key, value) =>
            Vector(dt(key), dd(dataAttr("key") := key, value))
          }
        )
      )
    )

  private def renderPlacement(placement: SelectionPlacement): String = placement match
    case SelectionPlacement.OnMark(marks) =>
      s"on-mark (${marks.toVector.map(_.value).mkString(", ")})"
    case SelectionPlacement.ViaAncestor(ancestor) => s"via-ancestor (${ancestor.render})"
    case SelectionPlacement.OffProjection         => "off-projection"

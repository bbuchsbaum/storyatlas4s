package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.edition.EditionSpec
import storyatlas4s.layout.{FragmentId, LayoutError, Measurer, MonospaceMeasurer}
import storymodel4s.core.Address
import storymodel4s.story.{ModelStatus, StoryModel}
import storymodel4s.view.*

/** The Laminar shell: one exact `Var[ViewChoice]`, a last-intent-wins compilation runtime, and
  * three synchronized regions — the paginated Codex, the Atlas at a two-axis semantic zoom state,
  * and an audit panel. Continuous gesture coordinates never enter a receipt; they commit an exact
  * [[ZoomLevel]] through [[SemanticZoom]] before storymodel4s compiles both artifacts under one
  * `CommonViewState`.
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
    val display = Var[CompilationDisplay[ViewChoice, Either[String, Compiled]]](
      CompilationDisplay.Idle
    )
    def submit(
        intent: CompileIntent[ViewChoice],
        complete: Either[String, Compiled] => Unit
    ): Unit =
      val _ = dom.window.setTimeout(
        () =>
          complete(
            measurerFor(intent.request.measurer)
              .flatMap(measurer => AppCompiler.compile(model, intent.request, measurer))
          ),
        0
      )
    val runtime = CompilationRuntime(submit, display.set)
    val controlsElement = SemanticZoom
      .at(initial.zoom)
      .fold(
        error => div(cls("error"), role("alert"), s"Semantic zoom unavailable: ${error.message}"),
        gesture => controls(text, choice, domMeasurer, gesture)
      )

    /** Click or keyboard activation of a named element: resolve to an address, then select. */
    def select(address: Address, extend: Boolean): Unit =
      choice.update { c =>
        val next =
          if !extend then Set(address)
          else if c.selection.contains(address) then c.selection - address
          else c.selection + address
        c.copy(selection = next, focus = Some(address))
      }

    div(
      cls("shell"),
      onMountCallback(ctx => (choice.signal.foreach(runtime.request)(using ctx.owner): Unit)),
      dataAttr("state") <-- display.signal.map {
        case CompilationDisplay.Idle         => "idle"
        case CompilationDisplay.Pending(_)   => "compiling"
        case CompilationDisplay.Ready(value) => value.result.fold(_ => "error", _ => "ready")
      },
      dataAttr("intent-revision") <-- display.signal.map {
        case CompilationDisplay.Idle            => "none"
        case CompilationDisplay.Pending(intent) => intent.revision.value.toString
        case CompilationDisplay.Ready(value)    => value.intent.revision.value.toString
      },
      dataAttr("horizon") <-- choice.signal.map(c => Playhead.position(text, c.horizon).toString),
      dataAttr("zoom") <-- choice.signal.map(c => s"${c.zoom.narrative}/${c.zoom.surface}"),
      dataAttr("focus") <-- choice.signal.map(_.focus.fold("none")(_.render)),
      controlsElement,
      child <-- display.signal.map {
        case CompilationDisplay.Idle =>
          div(cls("compiling"), role("status"), "Waiting to compile the initial view.")
        case CompilationDisplay.Pending(intent) =>
          div(
            cls("compiling"),
            role("status"),
            s"Compiling intent ${intent.revision.value}: " +
              s"${intent.request.zoom.narrative}/${intent.request.zoom.surface}."
          )
        case CompilationDisplay.Ready(value) =>
          value.result match
            case Left(problem) =>
              div(cls("error"), role("alert"), s"Compilation failed: $problem")
            case Right(c) =>
              div(
                cls("workspace"),
                codexSection(c, select),
                atlasSection(c, select),
                panel(c, choice, domMeasurer, value.intent.revision),
                diagnosticCourt(c, select)
              )
      }
    )

  private def controls(
      text: String,
      choice: Var[ViewChoice],
      domMeasurer: Either[LayoutError, Measurer],
      initialGesture: SemanticZoom
  ): HtmlElement =
    val length = text.length
    var gesture = initialGesture
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
      fieldSet(
        cls("semantic-zoom"),
        dataAttr("hysteresis") := SemanticZoom.Hysteresis.toString,
        legend("Semantic zoom (continuous gesture; exact typed state)"),
        label(
          forId("narrative-zoom"),
          "Narrative level",
          input(
            idAttr("narrative-zoom"),
            typ("range"),
            minAttr("0"),
            maxAttr((EditionSpec.levels.length - 1).toString),
            stepAttr("0.01"),
            value(gesture.narrativePosition.toString),
            onInput.mapToValue --> { value =>
              value.toDoubleOption.foreach { position =>
                val before = gesture.committed
                gesture = gesture.moveNarrative(position)
                if gesture.committed != before then choice.update(_.copy(zoom = gesture.committed))
              }
            }
          ),
          outputTag(
            forId("narrative-zoom"),
            dataAttr("committed") <-- choice.signal.map(_.zoom.narrative.toString),
            child.text <-- choice.signal.map(_.zoom.narrative.toString)
          )
        ),
        label(
          forId("surface-zoom"),
          "Surface detail",
          input(
            idAttr("surface-zoom"),
            typ("range"),
            minAttr("0"),
            maxAttr((EditionSpec.surfaceDetails.length - 1).toString),
            stepAttr("0.01"),
            value(gesture.surfacePosition.toString),
            onInput.mapToValue --> { value =>
              value.toDoubleOption.foreach { position =>
                val before = gesture.committed
                gesture = gesture.moveSurface(position)
                if gesture.committed != before then choice.update(_.copy(zoom = gesture.committed))
              }
            }
          ),
          outputTag(
            forId("surface-zoom"),
            dataAttr("committed") <-- choice.signal.map(_.zoom.surface.toString),
            child.text <-- choice.signal.map(_.zoom.surface.toString)
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
    val fragmentIds = c.placed.annotationFragments.map(_.id.value).sorted
    val resolvedFragmentIds = c.fragmentTargets.names.toVector.map(_.value).sorted
    val lineHeightPx = receipt.lineHeight.toDouble / receipt.unitsPerPixel
    val font = s"${receipt.style.sizePx}px/${lineHeightPx}px ${receipt.style.cssFamily}"
    def activate(target: dom.EventTarget, extend: Boolean): Unit =
      SvgDom
        .nameAt(target)
        .flatMap(c.fragmentTargets.resolve)
        .map(_._2)
        .foreach(select(_, extend))
    sectionTag(
      cls("codex"),
      aria.label("Narrative Codex"),
      dataAttr("fragments") := c.pieces.toString,
      dataAttr("fragment-ids") := fragmentIds.mkString(" "),
      dataAttr("resolved-fragment-ids") := resolvedFragmentIds.mkString(" "),
      dataAttr("lines") := c.placed.lines.length.toString,
      h2(s"Narrative Codex — lens ${c.choice.lens}"),
      p(
        cls("legend"),
        if c.rows.isEmpty then "Overlay rows: none (no annotation is placed)."
        else
          "Overlay rows, top to bottom within each line: " +
            c.rows.zipWithIndex.map((row, i) => s"${i + 1}: ${row.label}").mkString("; ") + "."
        ,
        " Direct interaction and visible-ancestor proxy interaction use distinct line and SVG " +
          "patterns."
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
                  cls(SvgDom.FocusedClass) := line.focused,
                  cls(SvgDom.SelectionProxyClass) := line.selectionProxy,
                  cls(SvgDom.FocusProxyClass) := line.focusProxy,
                  dataAttr("name") := line.id,
                  dataAttr("selected") := line.selected.toString,
                  dataAttr("focused") := line.focused.toString,
                  dataAttr("selection-proxy") := line.selectionProxy.toString,
                  dataAttr("focus-proxy") := line.focusProxy.toString,
                  styleAttr := s"height:${lineHeightPx}px",
                  line.text
                )
              }
            ),
            div(
              cls("overlay"),
              onMountCallback { ctx =>
                mountSvg(
                  ctx.thisNode.ref,
                  page.overlay,
                  page.targets,
                  c.codexInteractions.filter(value => page.targets.contains(value.target)),
                  (name, address) => s"annotation piece ${name.value} → ${address.render}"
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
    val markIds = c.scene.marks.map(_.identity.mark.value).sorted
    val resolvedMarkIds = c.atlasTargets.names.map(_.value).toVector.sorted
    def activate(target: dom.EventTarget, extend: Boolean): Unit =
      SvgDom
        .nameAt(target)
        .flatMap(c.atlasTargets.resolve)
        .map(_._2)
        .foreach(select(_, extend))
    sectionTag(
      cls("atlas"),
      aria.label("Narrative Atlas"),
      dataAttr("marks") := c.scene.marks.length.toString,
      dataAttr("mark-ids") := markIds.mkString(" "),
      dataAttr("resolved-mark-ids") := resolvedMarkIds.mkString(" "),
      dataAttr("names") := c.atlasNames.toString,
      h2(s"Narrative Atlas — zoom ${c.scene.zoom.narrative}/${c.scene.zoom.surface}"),
      p(
        cls("legend"),
        s"${c.scene.marks.length} mark(s). x = ${c.scene.contract.x}; y = ${c.scene.contract.y}. " +
          "Click a mark (shift-click extends) or focus it and press Enter to select its address. " +
          "Direct interaction and visible-ancestor proxy interaction use distinct patterns."
      ),
      onClick --> (ev => activate(ev.target, ev.shiftKey)),
      onKeyDown.filter(ev => ev.key == "Enter" || ev.key == " ") --> { ev =>
        ev.preventDefault()
        activate(ev.target, ev.shiftKey)
      },
      div(
        cls("atlas-svg"),
        onMountCallback { ctx =>
          mountSvg(
            ctx.thisNode.ref,
            c.atlasSvg,
            c.atlasTargets,
            c.atlasInteractions,
            (name, address) => s"mark ${name.value} → ${address.render}"
          )
        }
      )
    )

  private def diagnosticCourt(c: Compiled, select: (Address, Boolean) => Unit): HtmlElement =
    val enabled = dom.window.location.search.contains("interaction-court=1")
    if !enabled then div(cls("interaction-court-disabled"), display.none)
    else
      c.codexProxyCourt.fold[HtmlElement](
        sectionTag(
          cls("codex-interaction-court"),
          role("alert"),
          dataAttr("origin") := "Diagnostic",
          "No real-fragment Codex ViaAncestor diagnostic could be constructed."
        )
      ) { court =>
        def activate(target: dom.EventTarget, extend: Boolean): Unit =
          SvgDom
            .nameAt(target)
            .flatMap(court.targets.resolve)
            .map(_._2)
            .foreach(select(_, extend))
        sectionTag(
          cls("codex-interaction-court"),
          aria.label("Diagnostic Codex interaction court"),
          dataAttr("origin") := "Diagnostic",
          dataAttr("original") := court.original.render,
          dataAttr("visible") := court.visible.render,
          onClick --> (ev => activate(ev.target, ev.shiftKey)),
          onKeyDown.filter(ev => ev.key == "Enter" || ev.key == " ") --> { ev =>
            ev.preventDefault()
            activate(ev.target, ev.shiftKey)
          },
          div(
            cls("overlay"),
            onMountCallback { ctx =>
              mountSvg(
                ctx.thisNode.ref,
                court.overlay,
                court.targets,
                court.interactions,
                (name: FragmentId, address: Address) =>
                  s"diagnostic annotation piece ${name.value} → ${address.render}"
              )
            }
          )
        )
      }

  private def mountSvg[Name](
      container: dom.Element,
      svg: String,
      targets: RenderedTargetIndex[Name],
      interactions: Vector[InteractionDecoration[Name, Address]],
      label: (Name, Address) => String
  ): Unit =
    SvgDom.inject(container, svg, targets, interactions, label) match
      case Right(())   => ()
      case Left(error) =>
        container.innerHTML = ""
        container.setAttribute("data-interaction-error", error.message)
        container.setAttribute("role", "alert")
        container.textContent = s"Interaction rendering failed: ${error.message}"

  private def panel(
      c: Compiled,
      choice: Var[ViewChoice],
      domMeasurer: Either[LayoutError, Measurer],
      revision: IntentRevision
  ): HtmlElement =
    def placement(address: Address): HtmlElement =
      val codex = c.codexPlacements.collectFirst { case (a, p) if a == address => p }
      val atlas = c.atlasPlacements.collectFirst { case (a, p) if a == address => p }
      li(
        code(address.render),
        ul(
          li(
            "Codex: ",
            codex.fold("unresolved")(value => renderPlacement(value, _.value))
          ),
          li(
            "Atlas: ",
            atlas.fold("unresolved")(value => renderPlacement(value, _.value))
          )
        )
      )
    asideTag(
      cls("panel"),
      aria.label("Focus, selection, twins, receipts"),
      sectionTag(
        cls("selection"),
        h2("Focus and selection"),
        c.choice.focus.fold[HtmlElement](p("No semantic focus."))(address =>
          div(cls("focus-address"), h3("Focus"), ul(placement(address)))
        ),
        if c.choice.selection.isEmpty && c.choice.focus.isEmpty then
          p("Nothing selected. Addresses are shared by Codex and Atlas (V-I3).")
        else
          button(
            typ("button"),
            "Clear focus and selection",
            onClick --> (_ => choice.update(_.copy(selection = Set.empty, focus = None)))
          )
        ,
        if c.choice.selection.isEmpty then p("Selection set: empty.")
        else
          div(
            h3("Selection set"),
            ul(c.choice.selection.toVector.sortBy(_.render).map(placement))
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
          (c.receipts ++ Vector(
            "intentRevision" -> revision.value.toString,
            "zoomHysteresis" -> SemanticZoom.Hysteresis.toString,
            "domMeasurer" -> domMeasurer.fold(
              error => s"unavailable: ${error.message}",
              measurer => s"available: ${measurer.name}"
            )
          )).flatMap { (key, value) =>
            Vector(dt(key), dd(dataAttr("key") := key, value))
          }
        )
      )
    )

  private def renderPlacement[Mark](
      placement: SelectionPlacement[Mark],
      renderMark: Mark => String
  ): String = placement match
    case SelectionPlacement.OnMark(marks) =>
      s"on-mark (${marks.toVector.map(renderMark).mkString(", ")})"
    case SelectionPlacement.ViaAncestor(ancestor) => s"via-ancestor (${ancestor.render})"
    case SelectionPlacement.OffProjection         => "off-projection"

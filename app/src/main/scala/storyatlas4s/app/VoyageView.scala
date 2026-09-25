package storyatlas4s.app

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import storyatlas4s.edition.VoyagePage
import storyatlas4s.intaglio.{RecallWindow, VoyageGroupComparison, VoyageLowering}
import storyatlas4s.shell.{
  VoyageExport,
  VoyageFilter,
  VoyagePosterior,
  WorkspaceAction,
  WorkspaceController
}
import storymodel4s.align.SourceNodeRef
import storymodel4s.codec.{VoyageCodecs, WorkspaceVoyage}
import storymodel4s.core.{Address, Addressable}
import storymodel4s.recall.{RecallRef, RecallUnitId}
import storymodel4s.view.*

/** The Recall Voyage pane (ADR 0002 §14 D4): one document, compiled in the browser by storymodel4s
  * `VoyageCompiler` under the live selection, lowered by `VoyageLowering`, drawn by intaglio's SVG
  * backend, and decorated here.
  *
  * The shell adds exactly what a static plate cannot carry: a hover card naming the unit's words
  * and the row's numbers, click and keyboard selection resolved through the scene's navigation from
  * `data-name` to `Address`, the posterior column for the focused unit, two toggles (the ghosts of
  * moved argmaxes, the columns of every unit), a plate as wide as its panel, and an inspector that
  * prints what the scene already holds. It computes no scientific estimate: every figure is read
  * from a mark or the compiled summary, and a bar's segments are the masses the marks carry.
  *
  * The skeleton is built once and stays in the DOM; a change of selection, toggle or width
  * re-lowers only the plate's SVG and re-renders the inspector, so a focused section keeps its
  * focus and the keyboard walk survives its own steps.
  */
object VoyageView:

  private final case class Frame(scene: VoyageScene, svg: String)

  /** What the shell may vary without touching the document. */
  private final case class Lens(
      selection: Set[Address],
      ghosts: Boolean,
      allColumns: Boolean,
      width: Option[Int],
      window: Option[RecallWindow],
      track: VoyageLowering.Track,
      /** The y camera: fitted to the recall window's placed and argmax anchors, or the whole film.
        * A display choice only; the selection never changes it.
        */
      fitFilm: Boolean = false,
      /** The inspection filter: a display choice that dims non-matches, never a selection. */
      filter: VoyageFilter = VoyageFilter.none
  )

  /** The plate follows its panel between these widths; narrower and the clocks lose their ticks,
    * wider and the marks drift apart for nothing.
    */
  private val MinWidth = 300
  private val MaxWidth = 1600

  def fromDocumentText(text: String): HtmlElement =
    VoyageCodecs.decode(text) match
      case Left(problem) =>
        div(cls("error"), role("alert"), s"The voyage document does not decode: ${problem.message}")
      case Right(document) => apply(document)

  def apply(document: RecallVoyageDocument): HtmlElement =
    document.compile(Set.empty) match
      case Left(problem) =>
        div(cls("error"), role("alert"), s"The voyage did not compile: ${problem.message}")
      case Right(scene) => pane(document, scene)

  /** Workspace-owned voyage state. The document is a conservative legacy projection, so every
    * interaction crosses its checked address bridge before reaching the controller.
    */
  def controlled(
      projection: WorkspaceVoyage.Projection,
      signal: Signal[WorkspaceController],
      current: () => WorkspaceController,
      dispatch: WorkspaceAction => Unit
  ): HtmlElement =
    projection.document match
      case None           => unavailable(projection, signal)
      case Some(document) =>
        document.compile(Set.empty) match
          case Left(problem) =>
            div(
              cls("error"),
              role("alert"),
              s"The workspace voyage did not compile: ${problem.message}"
            )
          case Right(scene) =>
            controlledPane(projection, document, scene, signal, current, dispatch)

  private def unavailable(
      projection: WorkspaceVoyage.Projection,
      signal: Signal[WorkspaceController]
  ): HtmlElement =
    div(
      cls("page voyage-unavailable"),
      headerTag(
        cls("top"),
        div(cls("masthead"), h1("Recall Voyage"), p("This workspace cannot supply a voyage plate."))
      ),
      p(
        role("status"),
        "No voyage plate is available; each unit's recorded disposition explains why."
      ),
      ul(
        projection.units.sortBy(_.ordinal).map { row =>
          li(
            dataAttr("recall-unit") := row.unit.value,
            s"${row.ordinal} · ${row.unit.value} · ${row.disposition}"
          )
        }
      ),
      dataAttr("selection") <-- signal.map(c =>
        projection.legacySelection(c.state.selection).toVector.map(_.render).sorted.mkString(" ")
      )
    )

  private def controlledPane(
      projection: WorkspaceVoyage.Projection,
      document: RecallVoyageDocument,
      base: VoyageScene,
      signal: Signal[WorkspaceController],
      current: () => WorkspaceController,
      dispatch: WorkspaceAction => Unit
  ): HtmlElement =
    val hovered = Var(Option.empty[MarkId])
    val tip = Var(Option.empty[(Double, Double)])
    val ghosts = Var(false)
    val allColumns = Var(false)
    val track = Var(VoyageLowering.Track.Masses)
    val fitFilm = Var(false)
    val width = Var(Option.empty[Int])
    val preview = Var(Option.empty[(Double, Double)])
    val rangeError = Var(Option.empty[String])
    val cursorError = Var(Option.empty[String])
    var dragStart = Option.empty[Double]
    val total = base.recallLength.value
    val sourceExtent = base.timeline.nodes.map(_.span.end.value).maxOption

    def recallWindow(controller: WorkspaceController): Option[RecallWindow] =
      controller.state.viewport.recallWindow.fold(RecallWindow.bounded(0, total, total))(span =>
        RecallWindow.bounded(span.start.value, span.length, total)
      )
    def focus(controller: WorkspaceController): Option[RecallUnitId] = controller.state.activeRecall
    def selection(controller: WorkspaceController): Set[Address] =
      projection.legacySelection(controller.state.selection)
    def setWindow(next: Option[RecallWindow]): Unit =
      next.flatMap(r => ClockSpan.of(r.start, r.end).toOption).foreach { span =>
        val old = current().state.viewport
        dispatch(WorkspaceAction.Viewport(old.copy(recallWindow = Some(span))))
      }
      hovered.set(None)
      tip.set(None)
      rangeError.set(None)
    def setCursor(source: Boolean, raw: String): Unit =
      val checked: Either[String, Option[Seconds]] = raw.trim match
        case ""   => Right(None)
        case text =>
          for
            value <- parseClock(text)
            _ <- Either.cond(
              value <= (if source then sourceExtent.getOrElse(-1.0) else total),
              (),
              "Cursor must be within the supplied clock extent."
            )
            seconds <- Seconds.of(value).left.map(_.message)
          yield Some(seconds)
      checked match
        case Left(problem) => cursorError.set(Some(problem))
        case Right(value)  =>
          val old = current().state.viewport
          val next =
            if source then old.copy(sourceCursor = value) else old.copy(recallCursor = value)
          dispatch(WorkspaceAction.Viewport(next))
          cursorError.set(None)
    def chooseUnit(id: RecallUnitId): Unit = dispatch(WorkspaceAction.Jump(id))
    def walk(delta: Int): Unit = dispatch(WorkspaceAction.Walk(delta))
    def activate(target: dom.EventTarget, extend: Boolean): Unit =
      comparisonUnitAt(target, base).foreach(chooseUnit)
      nameAt(target).flatMap(n => base.navigation.addressOf.get(MarkId.unsafe(n))).foreach {
        legacy =>
          val addresses = projection.workspaceSelection(legacy)
          val workspace = current().workspace
          val recall = addresses.flatMap(workspace.recallAddresses.get).headOption
          val source = addresses.flatMap(workspace.sourceAddresses.get).headOption
          (recall, source) match
            case (Some(unit), Some(ref)) => dispatch(WorkspaceAction.Inspect(unit, ref))
            case (Some(unit), None)      =>
              workspace
                .recallAddress(unit)
                .foreach(a => dispatch(WorkspaceAction.Select(a, extend)))
            case _ => ()
      }
    def fit(el: dom.Element): Unit =
      val w = el.clientWidth
      if w > 0 then width.set(Some(math.max(MinWidth, math.min(MaxWidth, w))))
    def pointerTime(ev: dom.PointerEvent): Double =
      val rect = ev.currentTarget.asInstanceOf[dom.Element].getBoundingClientRect()
      math.max(
        0,
        math.min(
          total,
          math.round((ev.clientX - rect.left) / math.max(1, rect.width) * total * 10) / 10.0
        )
      )

    val legacySelection = signal.map(selection)
    val active = signal.map(focus)
    val windows = signal.map(recallWindow)
    val sourceCursor = signal.map(_.state.viewport.sourceCursor)
    val recallCursor = signal.map(_.state.viewport.recallCursor)
    val lens = legacySelection
      .combineWith(
        ghosts.signal,
        allColumns.signal,
        width.signal.distinct,
        windows,
        track.signal,
        fitFilm.signal
      )
      .map { case (s, g, all, w, r, t, f) => Lens(s, g, all, w, r, t, f) }
    val visibleText = signal
      .map(c =>
        base.units
          .map(u =>
            u.id -> c.recallEvidence(u.id).toOption.toVector.flatten.map(_._2).mkString(" … ")
          )
          .toMap
      )
      .distinct
    val frames = lens.combineWith(visibleText).map { (l, text) =>
      l.width.map(w => compile(document, base, l, w, Some(text)))
    }
    val dispositions = projection.units.map(row => row.unit -> row).toMap

    val startField = input(
      idAttr("voyage-range-start"),
      typ("text"),
      aria.label("Detail start (m:ss)"),
      value <-- windows.map(_.fold("")(r => clockInput(r.start)))
    )
    val endField = input(
      idAttr("voyage-range-end"),
      typ("text"),
      aria.label("Detail end (m:ss)"),
      value <-- windows.map(_.fold("")(r => clockInput(r.end)))
    )
    val sourceCursorField = input(
      idAttr("voyage-source-cursor"),
      typ("text"),
      aria.label("Source cursor (m:ss)"),
      value <-- sourceCursor.map(_.fold("")(t => clockInput(t.value)))
    )
    val recallCursorField = input(
      idAttr("voyage-recall-cursor"),
      typ("text"),
      aria.label("Recall cursor (m:ss)"),
      value <-- recallCursor.map(_.fold("")(t => clockInput(t.value)))
    )
    def applyRange(): Unit =
      (for
        start <- parseClock(startField.ref.value)
        end <- parseClock(endField.ref.value)
        r <- RecallWindow.of(start, end)
        _ <- Either.cond(
          end <= total && start < end,
          (),
          "Use a nonempty range within the supplied recall extent."
        )
      yield r) match
        case Left(problem) => rangeError.set(Some(problem))
        case Right(r)      => setWindow(Some(r))

    val rangeControls = div(
      cls("voyage-navigation"),
      form(
        cls("range-form"),
        onSubmit --> { ev => ev.preventDefault(); applyRange() },
        label("From ", startField),
        label("To ", endField),
        button(typ("submit"), "Apply range")
      ),
      div(
        cls("cursor-form"),
        sourceExtent.fold[HtmlElement](span("No supplied source clock extent.")) { extent =>
          form(
            onSubmit --> { ev =>
              ev.preventDefault(); setCursor(true, sourceCursorField.ref.value)
            },
            label("Source cursor ", sourceCursorField),
            span(cls("cursor-extent"), s"0:00–${clockInput(extent)}"),
            button(typ("submit"), "Set source cursor")
          )
        },
        form(
          onSubmit --> { ev => ev.preventDefault(); setCursor(false, recallCursorField.ref.value) },
          label("Recall cursor ", recallCursorField),
          span(cls("cursor-extent"), s"0:00–${clockInput(total)}"),
          button(typ("submit"), "Set recall cursor")
        )
      ),
      div(
        cls("range-actions"),
        button(
          typ("button"),
          "Earlier",
          onClick --> (_ => {
            recallWindow(current()).foreach(r =>
              setWindow(RecallWindow.bounded(r.start - r.span / 2, r.span, total))
            )
          })
        ),
        button(
          typ("button"),
          "Later",
          onClick --> (_ => {
            recallWindow(current()).foreach(r =>
              setWindow(RecallWindow.bounded(r.start + r.span / 2, r.span, total))
            )
          })
        ),
        button(
          typ("button"),
          "Whole recall",
          onClick --> (_ => setWindow(RecallWindow.bounded(0, total, total)))
        )
      ),
      cameraControls(fitFilm),
      child.maybe <-- rangeError.signal.map(_.map(e => p(cls("range-error"), role("alert"), e))),
      child.maybe <-- cursorError.signal.map(_.map(e => p(cls("range-error"), role("alert"), e))),
      div(
        cls("overview-caption"),
        span(s"Whole recall · ${base.units.count(_.onset.nonEmpty)} timed onsets"),
        span("Drag to set detail · range fields support keyboard entry")
      ),
      div(
        cls("recall-overview"),
        role("group"),
        aria.label("Whole recall overview brush"),
        svg.svg(
          svg.viewBox := "0 0 1000 44",
          svg.preserveAspectRatio := "none",
          svg.width := "100%",
          svg.height := "44",
          base.units.flatMap(u =>
            u.onset.map(t =>
              svg.line(
                svg.x1 := (1000 * t.value / math.max(total, 1e-9)).toString,
                svg.x2 := (1000 * t.value / math.max(total, 1e-9)).toString,
                svg.y1 := "10",
                svg.y2 := "34",
                svg.cls := "onset-tick"
              )
            )
          )
        ),
        div(
          cls("brush-window"),
          left <-- windows.combineWith(preview.signal).map { case (r, p) =>
            s"${100 * p.fold(r.fold(0.0)(_.start))(_._1) / math.max(total, 1e-9)}%"
          },
          com.raquo.laminar.api.L.width <-- windows.combineWith(preview.signal).map { case (r, p) =>
            s"${100 * p.fold(r.fold(0.0)(_.span))(v => v._2 - v._1) / math.max(total, 1e-9)}%"
          }
        ),
        onPointerDown --> { ev =>
          if total > 0 && ev.button == 0 then
            ev.preventDefault(); val t = pointerTime(ev); dragStart = Some(t);
            preview.set(Some(t -> t))
            val _ = ev.currentTarget.asInstanceOf[js.Dynamic].setPointerCapture(ev.pointerId)
        },
        onPointerMove --> { ev =>
          dragStart.foreach(s => {
            val e = pointerTime(ev); preview.set(Some(math.min(s, e) -> math.max(s, e)))
          })
        },
        onPointerUp --> { ev =>
          dragStart.foreach { s =>
            val e = pointerTime(ev);
            RecallWindow.of(math.min(s, e), math.max(s, e)).foreach(r => setWindow(Some(r)))
          };
          dragStart = None; preview.set(None)
        },
        onPointerCancel --> (_ => { dragStart = None; preview.set(None) })
      ),
      div(
        cls("recall-picker"),
        label(
          "Inspect recall unit ",
          select(
            idAttr("voyage-unit"),
            aria.label("Inspect recall unit"),
            option(value := "", disabled := true, "Choose a unit"),
            projection.units
              .sortBy(_.ordinal)
              .map(row =>
                option(
                  value := row.unit.value,
                  s"${row.ordinal} · ${row.disposition} · ${row.unit.value}"
                )
              ),
            value <-- active.map(_.fold("")(_.value)),
            onChange.mapToValue --> (id =>
              projection.units.find(_.unit.value == id).foreach(row => chooseUnit(row.unit))
            )
          )
        )
      ),
      p(
        cls("selection-location"),
        role("status"),
        child.text <-- active.combineWith(windows).map { case (id, window) =>
          id.flatMap(dispositions.get)
            .fold(s"All ${projection.units.size} units remain available.") { row =>
              row.disposition match
                case WorkspaceVoyage.Disposition.Plotted =>
                  val within = base.units
                    .find(_.id == row.unit)
                    .flatMap(_.onset)
                    .exists(at => window.forall(_.contains(at.value)))
                  if within then s"Unit ${row.ordinal} is on the current Voyage projection."
                  else
                    s"Unit ${row.ordinal} is outside the current range (OffProjection); selection is retained."
                case WorkspaceVoyage.Disposition.Untimed =>
                  s"Unit ${row.ordinal} is untimed; no clock position is inferred."
                case WorkspaceVoyage.Disposition.Unsupported(reason) =>
                  s"Unit ${row.ordinal} is not plotted: $reason."
            }
        }
      )
    )

    div(
      cls("page"),
      styleTag(VoyagePage.inspectorCss),
      dataAttr("marks") := base.marks.size.toString,
      dataAttr("selection") <-- legacySelection.map(_.toVector.map(_.render).sorted.mkString(" ")),
      dataAttr("focus") <-- active.map(_.fold("none")(_.value)),
      dataAttr("range-start") <-- windows.map(_.fold("none")(_.start.toString)),
      dataAttr("range-end") <-- windows.map(_.fold("none")(_.end.toString)),
      dataAttr("source-cursor") <-- sourceCursor.map(_.fold("none")(_.value.toString)),
      dataAttr("recall-cursor") <-- recallCursor.map(_.fold("none")(_.value.toString)),
      header(ghosts, allColumns, track),
      stats(base),
      child <-- lens.map(l => trackSummary(base, l)),
      div(
        cls("panes"),
        sectionTag(
          cls("panel"),
          aria.label("Recall Voyage"),
          tabIndex(0),
          div(
            cls("head"),
            h2("Recall time against source time"),
            span(cls("hint"), "choose a unit · click to inspect · ← → all units")
          ),
          onClick --> (ev => activate(ev.target, ev.shiftKey)),
          onKeyDown --> { ev =>
            if ev.target.isInstanceOf[dom.Element] && !Set("INPUT", "SELECT", "TEXTAREA", "BUTTON")
                .contains(ev.target.asInstanceOf[dom.Element].tagName)
            then
              ev.key match
                case "ArrowRight" => ev.preventDefault(); walk(1)
                case "ArrowLeft"  => ev.preventDefault(); walk(-1)
                case "Enter"      =>
                  ev.preventDefault()
                  if nameAt(ev.target).nonEmpty || comparisonUnitAt(ev.target, base).nonEmpty then
                    activate(ev.target, ev.shiftKey)
                  else openInspector(ev.currentTarget)
                case _ => ()
          },
          rangeControls,
          div(
            cls("scroller"),
            onMountCallback { ctx =>
              fit(ctx.thisNode.ref)
              val _ = signal
                .map(_.state.mode)
                .distinct
                .foreach { _ =>
                  val _ = dom.window.requestAnimationFrame(_ => fit(ctx.thisNode.ref))
                }(using ctx.owner)
            },
            inContext { node => windowEvents(_.onResize).throttle(120) --> (_ => fit(node.ref)) },
            onMouseMove --> { ev =>
              val n = nameAt(ev.target); hovered.set(n.map(MarkId.unsafe));
              val box = ev.currentTarget.asInstanceOf[dom.Element].getBoundingClientRect();
              tip.set(n.map(_ => (ev.clientX - box.left + 14, ev.clientY - box.top + 14)))
            },
            onMouseLeave --> (_ => { hovered.set(None); tip.set(None) }),
            div(
              cls("plate"),
              inContext { node =>
                frames --> {
                  case None                => ()
                  case Some(Left(problem)) =>
                    node.ref.innerHTML = "";
                    node.ref.textContent = s"The voyage did not compile: $problem"
                  case Some(Right(frame)) => mount(node.ref, frame, selection(current()))
                }
              }
            ),
            child.maybe <-- hovered.signal.combineWith(tip.signal, visibleText).map {
              case (h, at, text) =>
                for id <- h; (x, y) <- at; card <- hoverCard(base, id, Some(text))
                yield div(cls("tip"), left := s"${x}px", top := s"${y}px", card)
            }
          ),
          detailsTag(cls("voyage-key"), summaryTag("Key"), child <-- lens.map(l => legend(base, l)))
        ),
        asideTag(
          cls("panel inspector"),
          onKeyDown --> (ev => if ev.key == "Escape" then returnToPlot(ev.currentTarget)),
          child <-- active.combineWith(visibleText).map { (id, text) =>
            id.flatMap(id => base.units.find(_.id == id))
              .fold[HtmlElement](
                div(cls("empty"), "Select a plotted unit to inspect its supplied voyage marks.")
              )(u => inspector(base, u, Some(text.getOrElse(u.id, ""))))
          }
        )
      ),
      provenance(base)
    )

  private def pane(document: RecallVoyageDocument, scene: VoyageScene): HtmlElement =
    val selection = Var(Set.empty[Address])
    val focus = Var(Option.empty[RecallUnitId])
    val hovered = Var(Option.empty[MarkId])
    val tip = Var(Option.empty[(Double, Double)])
    val ghosts = Var(false)
    val allColumns = Var(false)
    val track = Var(VoyageLowering.Track.Masses)
    val fitFilm = Var(false)
    val filter = Var(VoyageFilter.none)
    val announcement = Var("")
    // unmeasured until the panel is laid out, so the plate is lowered once, at its real width
    val width = Var(Option.empty[Int])
    val total = scene.recallLength.value
    val window = Var(RecallWindow.bounded(0, total, total))
    val rangeError = Var(Option.empty[String])
    val preview = Var(Option.empty[(Double, Double)])
    var dragStart = Option.empty[Double]
    val lens: Signal[Lens] = selection.signal
      .combineWith(
        ghosts.signal,
        allColumns.signal,
        width.signal.distinct,
        window.signal,
        track.signal,
        fitFilm.signal,
        filter.signal
      )
      .map { case (s, g, all, w, r, t, f, i) => Lens(s, g, all, w, r, t, f, i) }
    val frames: Signal[Option[Either[String, Frame]]] =
      lens.map(l => l.width.map(w => compile(document, scene, l, w)))
    val ordered = scene.units.sortBy(_.ordinal)

    def unitAddress(id: RecallUnitId): Address = Addressable[RecallRef].address(RecallRef.Unit(id))

    def unitOfAddress(address: Address): Option[RecallUnitId] =
      Addressable[RecallRef].parse(address).collect { case RecallRef.Unit(id) => id }

    def activate(target: dom.EventTarget, extend: Boolean): Unit =
      comparisonUnitAt(target, scene).foreach(chooseUnit)
      nameAt(target).flatMap(n => scene.navigation.addressOf.get(MarkId.unsafe(n))).foreach {
        addr =>
          selection.update { s =>
            if !extend then Set(addr) else if s.contains(addr) then s - addr else s + addr
          }
          focus.set(unitOfAddress(addr).orElse(focus.now()))
      }

    def walk(delta: Int): Unit =
      if ordered.nonEmpty then
        val at = focus.now().map(id => ordered.indexWhere(_.id == id)).getOrElse(-1)
        val next = math.max(0, math.min(ordered.size - 1, at + delta))
        val id = ordered(next).id
        selection.set(Set(unitAddress(id)))
        focus.set(Some(id))

    def chooseUnit(id: RecallUnitId): Unit =
      selection.set(Set(unitAddress(id)))
      focus.set(Some(id))

    // M / Shift+M: the next or previous filter match in recall order, and why when there is none
    def stepMatch(forward: Boolean): Unit =
      filter.now().step(scene, focus.now(), forward) match
        case VoyageFilter.Step.To(id) =>
          chooseUnit(id)
          val reasons = scene.marks
            .find(m => m.unit == id && !m.isInstanceOf[VoyageMark.Alternative])
            .map(filter.now().reasons(scene, _))
            .getOrElse(Vector.empty)
          val ordinal = scene.units.find(_.id == id).map(_.ordinal).getOrElse(-1)
          announcement.set(s"Unit $ordinal matches: ${reasons.mkString(", ")}.")
        case VoyageFilter.Step.NotSet =>
          announcement.set("No filter set. Choose criteria in the inspection filter first.")
        case VoyageFilter.Step.NoMatch       => announcement.set("No unit matches the filter.")
        case VoyageFilter.Step.AllUntimed(n) =>
          announcement.set(
            s"The $n matching units are untimed; they are listed in the unit selector."
          )
        case VoyageFilter.Step.NoFurther => announcement.set("No further match after this unit.")
        case VoyageFilter.Step.NoEarlier => announcement.set("No earlier match before this unit.")

    def fit(el: dom.Element): Unit =
      val w = el.clientWidth
      if w > 0 then
        // A first compact visit starts with readable detail. Subsequent resizes preserve the
        // user's range, selection and focus, rather than silently moving the investigation.
        if width.now().isEmpty && w < 640 then
          window.set(RecallWindow.bounded(0, math.min(120, total), total))
        width.set(Some(math.max(MinWidth, math.min(MaxWidth, w))))

    def setWindow(next: Option[RecallWindow]): Unit =
      window.set(next)
      hovered.set(None)
      tip.set(None)
      rangeError.set(None)

    def shift(fraction: Double): Unit = window.now().foreach { r =>
      setWindow(RecallWindow.bounded(r.start + r.span * fraction, r.span, total))
    }

    def zoom(factor: Double): Unit = window.now().foreach { r =>
      val span = math.min(total, math.max(math.min(1.0, total), r.span * factor))
      setWindow(RecallWindow.bounded((r.start + r.end - span) / 2, span, total))
    }

    def reveal(): Unit =
      for
        id <- focus.now()
        unit <- scene.units.find(_.id == id)
        onset <- unit.onset
        r <- window.now()
      do
        if !r.contains(onset.value) then
          setWindow(RecallWindow.bounded(onset.value - r.span / 2, r.span, total))

    def pointerTime(ev: dom.PointerEvent): Double =
      val rect = ev.currentTarget.asInstanceOf[dom.Element].getBoundingClientRect()
      math.max(
        0,
        math.min(
          total,
          math.round((ev.clientX - rect.left) / math.max(1, rect.width) * total * 10) / 10.0
        )
      )

    val startField = input(
      idAttr("voyage-range-start"),
      typ("text"),
      aria.label("Detail start (m:ss)"),
      value <-- window.signal.map(_.fold("")(r => clockInput(r.start)))
    )
    val endField = input(
      idAttr("voyage-range-end"),
      typ("text"),
      aria.label("Detail end (m:ss)"),
      value <-- window.signal.map(_.fold("")(r => clockInput(r.end)))
    )
    def applyRange(): Unit =
      val checked = for
        start <- parseClock(startField.ref.value)
        end <- parseClock(endField.ref.value)
        r <- RecallWindow.of(start, end)
        _ <- Either.cond(end <= total, (), "End must be within the supplied recall extent.")
      yield r
      checked match
        case Left(problem) => rangeError.set(Some(problem))
        case Right(r)      => setWindow(Some(r))

    val rangeControls = div(
      cls("voyage-navigation"),
      div(
        cls("range-toolbar"),
        form(
          cls("range-form"),
          onSubmit --> { ev => ev.preventDefault(); applyRange() },
          label("From ", startField),
          label("To ", endField),
          button(typ("submit"), "Apply range", disabled := window.now().isEmpty)
        ),
        div(
          cls("range-actions"),
          button(
            typ("button"),
            "Earlier",
            onClick --> (_ => shift(-0.5)),
            disabled <-- window.signal.map(_.forall(_.start <= 0))
          ),
          button(
            typ("button"),
            "Later",
            onClick --> (_ => shift(0.5)),
            disabled <-- window.signal.map(_.forall(_.end >= total))
          ),
          button(
            typ("button"),
            "Zoom in",
            onClick --> (_ => zoom(0.5)),
            disabled <-- window.signal.map(_.forall(_.span <= math.min(1.0, total)))
          ),
          button(
            typ("button"),
            "Zoom out",
            onClick --> (_ => zoom(2)),
            disabled <-- window.signal.map(_.forall(_.span >= total))
          ),
          button(
            typ("button"),
            "Whole recall",
            onClick --> (_ => setWindow(RecallWindow.bounded(0, total, total))),
            disabled := window.now().isEmpty
          )
        ),
        cameraControls(fitFilm)
      ),
      child.maybe <-- rangeError.signal.map(_.map(e => p(cls("range-error"), role("alert"), e))),
      div(
        cls("overview-caption"),
        span(s"Whole recall · ${scene.units.count(_.onset.nonEmpty)} timed onsets"),
        span("Drag to set detail · range fields support keyboard entry")
      ),
      div(
        cls("recall-overview"),
        role("group"),
        aria.label("Whole recall overview brush"),
        svg.svg(
          svg.viewBox := "0 0 1000 44",
          svg.preserveAspectRatio := "none",
          svg.width := "100%",
          svg.height := "44",
          scene.units.flatMap(u =>
            u.onset.map(t =>
              svg.line(
                svg.x1 := (1000 * t.value / math.max(total, 1e-9)).toString,
                svg.x2 := (1000 * t.value / math.max(total, 1e-9)).toString,
                svg.y1 := "10",
                svg.y2 := "34",
                svg.cls := "onset-tick"
              )
            )
          )
        ),
        div(
          cls("brush-window"),
          left <-- window.signal.combineWith(preview.signal).map { case (r, p) =>
            s"${100 * p.fold(r.fold(0.0)(_.start))(_._1) / math.max(total, 1e-9)}%"
          },
          com.raquo.laminar.api.L.width <-- window.signal.combineWith(preview.signal).map {
            case (r, p) =>
              val span = p.fold(r.fold(0.0)(_.span))(v => v._2 - v._1)
              s"${100 * span / math.max(total, 1e-9)}%"
          }
        ),
        onPointerDown --> { ev =>
          if total > 0 && ev.button == 0 then
            ev.preventDefault()
            val t = pointerTime(ev)
            dragStart = Some(t)
            preview.set(Some(t -> t))
            val _ = ev.currentTarget.asInstanceOf[js.Dynamic].setPointerCapture(ev.pointerId)
        },
        onPointerMove --> { ev =>
          dragStart.foreach { start =>
            val end = pointerTime(ev)
            preview.set(Some(math.min(start, end) -> math.max(start, end)))
          }
        },
        onPointerUp --> { ev =>
          dragStart.foreach { start =>
            val end = pointerTime(ev)
            if math.abs(end - start) >= math.min(1.0, total) then
              setWindow(RecallWindow.of(math.min(start, end), math.max(start, end)).toOption)
          }
          dragStart = None
          preview.set(None)
        },
        onPointerCancel --> { _ => dragStart = None; preview.set(None) }
      ),
      div(cls("overview-extents"), span("0:00"), span(clockInput(total))),
      div(
        cls("range-status"),
        role("status"),
        aria.live("polite"),
        child.text <-- window.signal.map { r =>
          val visible = scene.units.count(u => u.onset.exists(t => r.forall(_.contains(t.value))))
          r.fold("No timed recall extent supplied") { w =>
            s"Detail ${clockInput(w.start)}–${clockInput(w.end)} · $visible timed units in view"
          }
        }
      ),
      div(
        cls("recall-picker"),
        label(
          "Inspect recall unit ",
          select(
            idAttr("voyage-unit"),
            aria.label("Inspect recall unit"),
            option(value := "", disabled := true, "Choose a unit"),
            scene.units
              .sortBy(_.ordinal)
              .map(u =>
                option(
                  value := u.id.value,
                  s"${u.ordinal} · ${u.onset.fold("untimed")(t => clockInput(t.value))} · ${u.text.take(65)}"
                )
              ),
            value <-- focus.signal.map(_.fold("")(_.value)),
            onChange.mapToValue --> (id =>
              scene.units.find(_.id.value == id).foreach(u => chooseUnit(u.id))
            )
          )
        ),
        button(
          typ("button"),
          "Reveal selected",
          onClick --> (_ => reveal()),
          disabled <-- focus.signal.map(
            _.flatMap(id => scene.units.find(_.id == id)).forall(_.onset.isEmpty)
          )
        )
      ),
      p(
        cls("selection-location"),
        role("status"),
        child.text <-- focus.signal.combineWith(window.signal).map { case (f, r) =>
          f.flatMap(id => scene.units.find(_.id == id))
            .fold(
              s"All ${scene.units.size} units remain available; ${scene.units.count(_.onset.isEmpty)} untimed."
            )(u =>
              u.onset.fold(
                s"Unit ${u.ordinal} is untimed; inspect its text without a clock position."
              ) { t =>
                if r.exists(w => !w.contains(t.value)) then
                  s"Unit ${u.ordinal} is outside this detail window. Reveal selected to bring it into view."
                else s"Unit ${u.ordinal} is in the detail window."
              }
            )
        }
      )
    )

    val focusedUnit: Signal[Option[VoyageUnit]] =
      focus.signal.map(_.flatMap(id => scene.units.find(_.id == id)))

    div(
      cls("page"),
      dataAttr("marks") := scene.marks.size.toString,
      dataAttr("selection") <-- selection.signal.map(
        _.toVector.map(_.render).sorted.mkString(" ")
      ),
      dataAttr("focus") <-- focus.signal.map(_.fold("none")(_.value)),
      dataAttr("plate-width") <-- width.signal.map(_.fold("unmeasured")(_.toString)),
      dataAttr("range-start") <-- window.signal.map(_.fold("none")(_.start.toString)),
      dataAttr("range-end") <-- window.signal.map(_.fold("none")(_.end.toString)),
      header(ghosts, allColumns, track),
      stats(scene),
      child <-- lens.map(l => trackSummary(scene, l)),
      div(
        cls("panes"),
        sectionTag(
          cls("panel"),
          aria.label("Recall Voyage"),
          tabIndex(0),
          div(
            cls("head"),
            h2("Recall time against source time"),
            span(
              cls("hint"),
              "choose a unit · click to inspect · ← → all units · M next filter match"
            )
          ),
          onClick --> (ev => activate(ev.target, ev.shiftKey)),
          onKeyDown --> { ev =>
            val inPlot = ev.target match
              case el: dom.Element => el == ev.currentTarget || el.closest(".plate") != null
              case _               => false
            if inPlot then
              ev.key match
                case "ArrowRight" => ev.preventDefault(); walk(1)
                case "ArrowLeft"  => ev.preventDefault(); walk(-1)
                case "m" | "M"    =>
                  ev.preventDefault()
                  stepMatch(!ev.shiftKey)
                case "Enter" =>
                  ev.preventDefault()
                  if nameAt(ev.target).nonEmpty || comparisonUnitAt(ev.target, scene).nonEmpty then
                    activate(ev.target, ev.shiftKey)
                  else openInspector(ev.currentTarget)
                case _ => ()
          },
          rangeControls,
          filterControls(scene, filter),
          exportControls(scene, filter),
          span(
            cls("sr-only"),
            role("status"),
            aria.live("polite"),
            child.text <-- announcement.signal
          ),
          div(
            cls("scroller"),
            onMountCallback(ctx => fit(ctx.thisNode.ref)),
            inContext { node =>
              windowEvents(_.onResize).throttle(120) --> (_ => fit(node.ref))
            },
            onMouseMove --> { ev =>
              val name = nameAt(ev.target)
              hovered.set(name.map(MarkId.unsafe))
              val scroller = ev.currentTarget.asInstanceOf[dom.Element]
              val box = scroller.getBoundingClientRect()
              tip.set(
                name.map(_ =>
                  (ev.clientX - box.left + scroller.scrollLeft + 14, ev.clientY - box.top + 14)
                )
              )
            },
            onMouseLeave --> { _ =>
              hovered.set(None)
              tip.set(None)
            },
            div(
              cls("plate"),
              // arriving on the plot with the selection outside the window selects the first unit
              // in view, so the active option is always one the reader can see
              // (keyboard arrival only: a pointer press must never move the selection it is making)
              onFocus --> { ev =>
                val byKeyboard = ev.target match
                  case el: dom.Element => el.matches(":focus-visible")
                  case _               => false
                val inView =
                  ordered.filter(u => u.onset.exists(t => window.now().forall(_.contains(t.value))))
                if byKeyboard && !focus.now().exists(id => inView.exists(_.id == id)) then
                  inView.headOption.foreach(u => chooseUnit(u.id))
              },
              inContext { node =>
                frames --> {
                  case None                => ()
                  case Some(Left(problem)) =>
                    node.ref.innerHTML = ""
                    node.ref.textContent = s"The voyage did not compile: $problem"
                  case Some(Right(f)) => mount(node.ref, f, selection.now())
                }
              }
            ),
            child.maybe <-- hovered.signal
              .combineWith(tip.signal)
              .map { case (h, at) =>
                for
                  id <- h
                  (x, y) <- at
                  card <- hoverCard(scene, id)
                yield div(cls("tip"), left := s"${x}px", top := s"${y}px", card)
              }
          ),
          detailsTag(
            cls("voyage-key"),
            onMountCallback(ctx =>
              if dom.window.innerWidth > 640 then ctx.thisNode.ref.setAttribute("open", "")
            ),
            summaryTag("Key"),
            child <-- lens.map(l => legend(scene, l))
          )
        ),
        asideTag(
          cls("panel inspector"),
          onKeyDown --> (ev => if ev.key == "Escape" then returnToPlot(ev.currentTarget)),
          child <-- focusedUnit
            .combineWith(filter.signal)
            .map((focused, f) =>
              focused.fold[HtmlElement](
                div(
                  cls("empty"),
                  "Click a mark to inspect a unit; ",
                  span(cls("kbd"), "←"),
                  " ",
                  span(cls("kbd"), "→"),
                  " walk the recall in time order."
                )
              )(u => inspector(scene, u, filter = f))
            )
        )
      ),
      provenance(scene)
    )

  // ------------------------------------------------------------------ compile and draw

  private def compile(
      document: RecallVoyageDocument,
      base: VoyageScene,
      lens: Lens,
      width: Int,
      visibleRecallText: Option[Map[RecallUnitId, String]] = None
  ): Either[String, Frame] =
    for
      scene <- document.compile(lens.selection).left.map(_.message)
      selected = lens.selection.toVector
        .flatMap(a => Addressable[RecallRef].parse(a))
        .collect { case RecallRef.Unit(id) => id }
        .toSet
      context = if lens.allColumns then base.units.map(_.id).toSet -- selected else Set.empty
      box =
        if width < 640 then
          VoyageLowering.Box.default.copy(
            width = width,
            left = 46,
            right = 128,
            plotHeight = 380,
            trackHeight = 72
          )
        else if width < 1000 then VoyageLowering.Box.default.copy(width = width, trackHeight = 72)
        // wide plates carry the gutter: the selected unit's admitted anchors on the film axis
        else VoyageLowering.Box.default.copy(width = width, trackHeight = 72, gutter = 170)
      trackBox = box.copy(
        bottom = box.bottom + 46,
        gap = 40,
        trackHeight = if lens.track == VoyageLowering.Track.GroupDisagreements then 144 else 72
      )
      lowered <- VoyageLowering
        .lower(
          scene,
          alternativesFor = selected,
          box = trackBox,
          ghosts = lens.ghosts,
          contextFor = context,
          ghostsFor = selected,
          window = lens.window,
          includeUntimed = false,
          visibleRecallText = visibleRecallText,
          track = lens.track,
          film = if lens.fitFilm then VoyageLowering.fitFilm(scene, lens.window) else None,
          inspection = Option.when(lens.filter.isSet)(lens.filter.matches(scene).keySet),
          caption = caption(scene, lens.filter)
        )
        .left
        .map(_.message)
      options <- SvgOptions(trackBox.width, trackBox.height, Some("Recall Voyage")).left
        .map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
    yield Frame(scene, svg.value)

  /** The y camera. Fit to window reads only the recall window's supplied placements and argmaxes,
    * snapped to group bounds, and it states that rule; selecting a unit never rescales the axis.
    */
  private def cameraControls(fitFilm: Var[Boolean]): HtmlElement =
    div(
      cls("camera-actions"),
      role("group"),
      aria.label("Film axis"),
      button(
        typ("button"),
        "Fit to window",
        aria.pressed <-- fitFilm.signal.map(_.toString),
        onClick --> (_ => fitFilm.set(true))
      ),
      button(
        typ("button"),
        "Whole film",
        aria.pressed <-- fitFilm.signal.map(on => (!on).toString),
        onClick --> (_ => fitFilm.set(false))
      ),
      child.maybe <-- fitFilm.signal.map(on =>
        Option.when(on)(
          span(
            cls("camera-rule"),
            "film fitted to the window's placed and argmax anchors, snapped to scene bounds"
          )
        )
      )
    )

  private def mount(container: dom.Element, frame: Frame, selection: Set[Address]): Unit =
    // a mark reached by Tab and activated with Enter is about to be replaced; remember that the
    // keyboard was in the plate, so the walk can continue from the selected mark
    val active = dom.document.activeElement
    val keyboardInPlate = active != null && (container.contains(active) || container == active)
    container.innerHTML = frame.svg
    // the plot is one tab stop: a listbox whose options are the unit marks, with the selection as
    // its active descendant (workshop ruling 16); gutter bars and decorations are not options
    container.setAttribute("role", "listbox")
    container.setAttribute("tabindex", "0")
    container.setAttribute(
      "aria-label",
      "Recall units in the plot. Arrow keys move, M steps through filter matches, Enter opens the inspector."
    )
    val named = container.querySelectorAll("[data-name], [data-comparison-unit]")
    var i = 0
    while i < named.length do
      val el = named(i).asInstanceOf[dom.Element]
      val unitMark = el.closest(".voyage-anchor, .voyage-unanchored") != null
      if unitMark then
        el.setAttribute("role", "option")
        el.setAttribute("id", optionId(el.getAttribute("data-name")))
        Option(el.parentNode)
          .collect { case p: dom.Element => p }
          .flatMap(p => Option(p.querySelector(":scope > title")))
          .foreach(t => el.setAttribute("aria-label", t.textContent))
      else if !el.hasAttribute("data-comparison-unit") then el.setAttribute("role", "none")
      if el.hasAttribute("data-comparison-unit") then
        el.setAttribute("tabindex", "0")
        el.setAttribute("role", "button")
        el.setAttribute(
          "aria-label",
          Option(el.querySelector("title")).fold("Inspect group comparison")(_.textContent)
        )
        val unit = comparisonUnitAt(el, frame.scene)
        el.setAttribute(
          "aria-pressed",
          unit
            .exists(id =>
              selection.contains(
                Addressable[RecallRef].address(RecallRef.Unit(id))
              )
            )
            .toString
        )
        el.setAttribute("style", "cursor: pointer")
      i += 1
    decorate(container, frame.scene, selection)
    Option(container.querySelector(s"[role=option].${SvgDom.SelectedClass}")) match
      case Some(el) => container.setAttribute("aria-activedescendant", el.getAttribute("id"))
      case None     => container.removeAttribute("aria-activedescendant")
    if keyboardInPlate then container.asInstanceOf[dom.HTMLElement].focus()

  /** Enter on the plot opens the inspector: focus moves to the ranked anchor table when the unit
    * has one, otherwise to the inspector itself. Escape there returns to the plot.
    */
  private def openInspector(from: dom.EventTarget): Unit =
    from match
      case el: dom.Element =>
        Option(el.closest(".page"))
          .flatMap(page =>
            Option(page.querySelector(".inspector table, .inspector .inspector-content"))
          )
          .foreach { t =>
            t.setAttribute("tabindex", "-1")
            t.asInstanceOf[js.Dynamic].focus()
          }
      case _ => ()

  private def returnToPlot(from: dom.EventTarget): Unit =
    from match
      case el: dom.Element =>
        Option(el.closest(".page"))
          .flatMap(page => Option(page.querySelector(".plate[role=listbox]")))
          .foreach(_.asInstanceOf[js.Dynamic].focus())
      case _ => ()

  /** A DOM id for a mark's option: its `MarkId`, reduced to id-safe characters, with a prefix. */
  private def optionId(name: String): String =
    "voyage-option-" + name.map(c => if c.isLetterOrDigit || c == '-' then c else '_')

  private def decorate(container: dom.Element, scene: VoyageScene, selection: Set[Address]): Unit =
    val selectedMarks = selection.toVector.flatMap(scene.navigation.marksFor).map(_.value).toSet
    val named = container.querySelectorAll("[data-name]")
    var i = 0
    while i < named.length do
      val el = named(i).asInstanceOf[dom.Element]
      val on = selectedMarks.contains(el.getAttribute("data-name"))
      if on then el.classList.add(SvgDom.SelectedClass)
      else el.classList.remove(SvgDom.SelectedClass)
      if el.getAttribute("role") == "option" then el.setAttribute("aria-selected", on.toString)
      else if !el.hasAttribute("data-comparison-unit") then el.removeAttribute("aria-pressed")
      i += 1

  private def nameAt(target: dom.EventTarget): Option[String] =
    target match
      case el: dom.Element =>
        Option(el.closest("[data-name]")).map(_.getAttribute("data-name")).filter(_.nonEmpty)
      case _ => None

  // ------------------------------------------------------------------ chrome

  private def clock(t: Double): String = VoyageLowering.clock(t)

  private def clockInput(t: Double): String =
    val minutes = (t / 60).toLong
    val seconds = (BigDecimal(t.toString) - BigDecimal(
      minutes
    ) * 60).bigDecimal.stripTrailingZeros.toPlainString
    s"$minutes:${if t % 60 < 10 then "0" else ""}$seconds"

  private def parseClock(text: String): Either[String, Double] =
    val pieces = text.trim.split(":", -1)
    val parsed = pieces.toList match
      case seconds :: Nil            => seconds.toDoubleOption
      case minutes :: seconds :: Nil =>
        for
          m <- minutes.toDoubleOption.filter(v => v >= 0 && v == math.floor(v))
          s <- seconds.toDoubleOption.filter(v => v >= 0 && v < 60)
        yield m * 60 + s
      case _ => None
    parsed.filter(v => v.isFinite && v >= 0).toRight("Enter a nonnegative time as m:ss or seconds.")

  private def header(
      ghosts: Var[Boolean],
      allColumns: Var[Boolean],
      track: Var[VoyageLowering.Track]
  ): HtmlElement =
    headerTag(
      cls("top"),
      div(
        cls("masthead"),
        div(cls("eyebrow"), "storyatlas4s · recall voyage · saved decisions"),
        h1("Recall Voyage"),
        p(
          "Recall onsets against the supplied source display clock. Marks retain the loaded decisions " +
            "and their origins; model mass is not calibrated confidence. Decision-policy metadata " +
            "and native media-clock bindings are not supplied by this legacy document."
        )
      ),
      div(
        cls("controls"),
        toggle(ghosts, "all moved-argmax ghosts (selected units always shown)"),
        toggle(allColumns, "posterior columns for every unit"),
        label(
          "Under-plot track ",
          select(
            aria.label("Under-plot track"),
            option(value := "mass", "Anchor and external mass"),
            option(value := "groups", "Group disagreements"),
            value <-- track.signal.map(t =>
              if t == VoyageLowering.Track.Masses then "mass" else "groups"
            ),
            onChange.mapToValue --> (v =>
              track.set(
                if v == "groups" then VoyageLowering.Track.GroupDisagreements
                else VoyageLowering.Track.Masses
              )
            )
          )
        )
      )
    )

  private def trackSummary(scene: VoyageScene, lens: Lens): HtmlElement =
    val records = VoyageGroupComparison.records(scene)
    def count(status: VoyageGroupComparison.Status): Int = records.count(_.status == status)
    val shown = records.count(r =>
      r.status != VoyageGroupComparison.Status.Agreement &&
        r.onset.exists(t => lens.window.forall(_.contains(t.value)))
    )
    p(
      cls("note group-comparison-summary"),
      display := (if lens.track == VoyageLowering.Track.GroupDisagreements then "block"
                  else "none"),
      s"Whole recall: ${count(VoyageGroupComparison.Status.Disagreement)} different groups · " +
        s"${count(VoyageGroupComparison.Status.Agreement)} same group · " +
        s"${count(VoyageGroupComparison.Status.Unknown)} unknown (${records.count(_.onset.isEmpty)} untimed). " +
        s"$shown timed comparisons in this window. Same-group pairs are omitted from the strip. " +
        "Group spacing shows order, not time or distance." +
        (if scene.coding.nonEmpty then " Independent coding stays on the main plot." else "")
    )

  private def comparisonUnitAt(target: dom.EventTarget, scene: VoyageScene): Option[RecallUnitId] =
    target match
      case el: dom.Element =>
        Option(el.closest("[data-comparison-unit]")).flatMap { mark =>
          scene.units.find(_.id.value == mark.getAttribute("data-comparison-unit")).map(_.id)
        }
      case _ => None

  private def toggle(state: Var[Boolean], text: String): HtmlElement =
    label(
      input(typ("checkbox"), checked <-- state.signal, onInput.mapToChecked --> state.writer),
      text
    )

  private def stats(scene: VoyageScene): HtmlElement =
    val s = scene.summary
    val moved = s.decodeBound + s.decodeFilled
    val cells = Vector(
      (
        "coded group agreement",
        s.codedAgreement.fold("—")((a, t) => f"${100.0 * a / math.max(t, 1)}%.1f%%"),
        s.codedAgreement.fold("no independent coding in this document")((a, t) =>
          s"$a of $t timed anchors"
        )
      ),
      (
        "recall units",
        s.units.toString,
        s"${clock(scene.recallLength.value)} supplied recall extent; word onsets, not speech duration"
      ),
      (
        "moved by the decode",
        s"$moved/${s.units}",
        "supplied decisions differing from the posterior argmax"
      ),
      (
        "zero-mass fills",
        s.decodeFilled.toString,
        "drawn outside posterior support; mass remains zero"
      ),
      ("unanchored", s.unanchored.toString, "no source anchor; drawn on the row above the plot"),
      (
        "external-dominant",
        s.externalDominant.toString,
        "external mass exceeds source mass; hollow core and a second outline"
      )
    )
    sectionTag(
      cls("stats"),
      cells.map((k, v, note) =>
        div(cls("stat"), span(cls("k"), k), span(cls("v"), v), span(cls("s"), note))
      )
    )

  // ------------------------------------------------------------------ legend glyphs

  private val Model = "var(--model)"
  private val ModelSoft = "var(--model-soft)"
  private val External = "var(--external)"
  private val Raw = "var(--raw)"
  private val GoldBand = "var(--gold-band)"
  private val Surface = "var(--surface)"
  // the lowering's container outline (3.8:1 on the plate)
  private val Container = "#7b858d"
  private val Ink2 = "var(--ink-2)"

  private def glyph(shapes: Modifier[SvgElement]*): SvgElement =
    svg.svg(
      svg.viewBox := "0 0 26 16",
      svg.width := "26",
      svg.height := "16",
      svg.cls := "glyph",
      shapes
    )

  private def diamondPoints(cx: Double, cy: Double, d: Double): String =
    f"$cx%.1f,${cy - d}%.1f ${cx + d}%.1f,$cy%.1f $cx%.1f,${cy + d}%.1f ${cx - d}%.1f,$cy%.1f"

  private def legend(scene: VoyageScene, lens: Lens): HtmlElement =
    val visible = scene.units
      .filter(u => u.onset.exists(t => lens.window.forall(_.contains(t.value))))
      .map(_.id)
      .toSet
    val anchors = scene.marks.collect { case a: VoyageMark.UnitAnchor if visible(a.unit) => a }
    val alternatives = scene.marks.collect {
      case a: VoyageMark.Alternative if visible(a.unit) => a
    }
    val selected = lens.selection.flatMap(a =>
      Addressable[RecallRef].parse(a).collect { case RecallRef.Unit(id) =>
        id
      }
    )
    val hasCoding = scene.coding.exists(
      _.intervals.exists(iv =>
        lens.window.forall(r => iv.recall.end.value >= r.start && iv.recall.start.value <= r.end)
      )
    )
    val hasGhosts = anchors.exists(a =>
      a.origin != AnchorOrigin.PosteriorArgmax && a.argmax.flatMap(scene.timeline.node).nonEmpty &&
        (lens.ghosts || selected(a.unit))
    )
    def item(visible: Boolean)(mark: SvgElement, text: String): Vector[HtmlElement] =
      Option.when(visible)(span(mark, text)).toVector
    div(
      cls("legend-row"),
      item(anchors.exists(_.origin == AnchorOrigin.PosteriorArgmax))(
        glyph(
          svg.circle(
            svg.cx := "13",
            svg.cy := "8",
            svg.r := "6",
            svg.style := s"fill: $Surface; stroke: $Container"
          ),
          svg.circle(svg.cx := "13", svg.cy := "8", svg.r := "4.24", svg.style := s"fill: $Model")
        ),
        "posterior argmax; the outline is mass 1.0, the filled area the anchor's posterior mass (not calibrated confidence)"
      ),
      item(anchors.exists(_.origin == AnchorOrigin.DecodeBound))(
        glyph(
          svg.polygon(
            svg.points := diamondPoints(13, 8, 6 * 1.2533),
            svg.style := s"fill: $Surface; stroke: $Container"
          ),
          svg
            .polygon(svg.points := diamondPoints(13, 8, 4.24 * 1.2533), svg.style := s"fill: $Ink2")
        ),
        "decode-bound: the scene decode chose it, with posterior mass"
      ),
      item(anchors.exists(_.origin == AnchorOrigin.DecodeFilled))(
        glyph(
          svg.polygon(
            svg.points := diamondPoints(13, 8, 6 * 1.2533),
            svg.style := s"fill: $Surface; stroke: $Container; stroke-dasharray: 3 2"
          )
        ),
        "decode-filled: an empty container; mass zero, outside the posterior, not posterior evidence"
      ),
      item(anchors.exists(_.level > 0))(
        glyph(
          svg.line(
            svg.x1 := "13",
            svg.y1 := "1",
            svg.x2 := "13",
            svg.y2 := "15",
            svg.style := s"stroke: $Container; stroke-width: 3"
          )
        ),
        "group-level anchor: the model stops at the group; the heavy rule is the group's extent"
      ),
      item(anchors.exists(_.externalDominant))(
        glyph(
          svg.circle(
            svg.cx := "13",
            svg.cy := "8",
            svg.r := "7.6",
            svg.style := s"fill: none; stroke: $Container; stroke-width: 0.9"
          ),
          svg.circle(
            svg.cx := "13",
            svg.cy := "8",
            svg.r := "5.6",
            svg.style := s"fill: $Surface; stroke: $Container"
          ),
          svg.circle(
            svg.cx := "13",
            svg.cy := "8",
            svg.r := "3.9",
            svg.style := s"fill: $Surface; stroke: $Model; stroke-width: 1.2"
          )
        ),
        "hollow: the row's external mass exceeds its source mass; a second outline marks it at any mass"
      ),
      item(alternatives.exists(a => selected(a.unit)))(
        glyph(
          svg.line(
            svg.x1 := "13",
            svg.y1 := "1",
            svg.x2 := "13",
            svg.y2 := "15",
            svg.style := s"stroke: $Model; stroke-width: 0.8"
          ),
          svg.circle(
            svg.cx := "13",
            svg.cy := "4.5",
            svg.r := "3.2",
            svg.style := s"fill: none; stroke: $Model; stroke-width: 1.2; stroke-dasharray: 2 1.5"
          )
        ),
        if lens.width.exists(_ >= 1000) then
          "admitted anchors of the selected unit: bars in the gutter beside the plot, 100px = mass 1.0"
        else "posterior column of the focused unit: dashed rings, area is mass"
      ),
      item(hasCoding)(
        glyph(
          svg.rect(
            svg.x := "2",
            svg.y := "3",
            svg.width := "22",
            svg.height := "10",
            svg.style := s"fill: $GoldBand"
          )
        ),
        "independent coding: the coded group's span over the interval coded to it"
      ),
      item(hasGhosts)(
        glyph(
          svg.line(
            svg.x1 := "13",
            svg.y1 := "3",
            svg.x2 := "13",
            svg.y2 := "15",
            svg.style := s"stroke: $Raw; stroke-width: 0.8"
          ),
          svg.circle(svg.cx := "13", svg.cy := "3", svg.r := "2.2", svg.style := s"fill: $Raw")
        ),
        "ghost: the posterior argmax a decode moved away from"
      ),
      item(scene.marks.exists {
        case m: VoyageMark.Unanchored => visible(m.unit)
        case _                        => false
      })(
        glyph(
          svg.line(
            svg.x1 := "9",
            svg.y1 := "4",
            svg.x2 := "17",
            svg.y2 := "12",
            svg.style := s"stroke: $External; stroke-width: 1.4"
          ),
          svg.line(
            svg.x1 := "17",
            svg.y1 := "4",
            svg.x2 := "9",
            svg.y2 := "12",
            svg.style := s"stroke: $External; stroke-width: 1.4"
          )
        ),
        "unanchored row: a cross above the plot, anchored nowhere in the source"
      ),
      item(lens.allColumns && alternatives.exists(a => !selected(a.unit)))(
        glyph(
          svg.line(
            svg.x1 := "13",
            svg.y1 := "1",
            svg.x2 := "13",
            svg.y2 := "15",
            svg.style := s"stroke: $Model; stroke-width: 0.6; opacity: 0.35"
          ),
          svg.circle(
            svg.cx := "13",
            svg.cy := "5",
            svg.r := "3",
            svg.style := s"fill: none; stroke: $Model; stroke-width: 0.9; stroke-dasharray: 2 1.5; opacity: 0.4"
          )
        ),
        "context: every unit's column, faint, when toggled"
      ),
      item(visible.nonEmpty && lens.track == VoyageLowering.Track.Masses)(
        glyph(
          svg.line(
            svg.x1 := "8",
            svg.y1 := "3",
            svg.x2 := "8",
            svg.y2 := "15",
            svg.style := s"stroke: $Model; stroke-width: 3"
          ),
          svg.line(
            svg.x1 := "18",
            svg.y1 := "10",
            svg.x2 := "18",
            svg.y2 := "15",
            svg.style := s"stroke: $External; stroke-width: 3"
          )
        ),
        "mass tracks: drawn anchor and external, each fixed 0–1; not complements or calibrated confidence. " +
          "Baseline tick = zero; cross = no source anchor. Untimed units stay in the unit selector."
      ),
      item(
        lens.track == VoyageLowering.Track.GroupDisagreements &&
          VoyageGroupComparison
            .records(scene)
            .exists(r => visible(r.unit) && r.status == VoyageGroupComparison.Status.Disagreement)
      )(
        glyph(
          svg.line(
            svg.x1 := "13",
            svg.x2 := "13",
            svg.y1 := "3",
            svg.y2 := "13",
            svg.style := s"stroke: $Model"
          ),
          svg.line(
            svg.x1 := "9",
            svg.x2 := "17",
            svg.y1 := "3",
            svg.y2 := "3",
            svg.style := s"stroke: $Model"
          ),
          svg.polygon(svg.points := diamondPoints(13, 13, 3), svg.style := s"fill: $Model")
        ),
        "group disagreement: shaped endpoint = drawn group; cap = posterior-argmax group. Endpoint size carries no mass."
      ),
      item(
        lens.track == VoyageLowering.Track.GroupDisagreements &&
          VoyageGroupComparison
            .records(scene)
            .exists(r => visible(r.unit) && r.status == VoyageGroupComparison.Status.Unknown)
      )(
        glyph(
          svg.line(
            svg.x1 := "9",
            svg.x2 := "17",
            svg.y1 := "4",
            svg.y2 := "12",
            svg.style := s"stroke: $External"
          ),
          svg.line(
            svg.x1 := "9",
            svg.x2 := "17",
            svg.y1 := "12",
            svg.y2 := "4",
            svg.style := s"stroke: $External"
          )
        ),
        "group unknown: one or both groups are unavailable; not agreement."
      )
    )

  /** The figure's own caption, under the plot, so identities, caveats and the filter state travel
    * with a cropped figure (workshop ruling 13). Every count it prints is the filter's own.
    */
  private def caption(scene: VoyageScene, filter: VoyageFilter): Vector[String] =
    val prov = scene.provenance
    val state =
      if !filter.isSet then "no filter set"
      else
        val labels = filter.criteria.toVector.sortBy(_.ordinal).map(_.label) ++
          filter.argmaxMassBelow.map(t => s"argmax mass < $t") ++
          filter.externalMassAbove.map(t => s"external mass > $t") ++
          filter.localizabilityBelow.map(t => s"localizability < $t")
        val mode = filter.combine.toString.toLowerCase
        s"filter ($mode): ${labels.mkString(", ")}; ${filter.matches(scene).size} of ${scene.units.size} units match; grey marks do not match"
    Vector(
      s"source ${prov.sourceChecksum.hex.take(12)}… · ${prov.compilerVersion} · mass is model posterior mass, not calibrated confidence",
      "dashed diamonds are decode fills with mass 0, not posterior evidence · returns and omissions are outside the Recall Voyage contract",
      state
    )

  /** The inspection filter's controls: criteria with their counts, how they combine, thresholds
    * that start unset, and a status line written only when it changes.
    */
  private def filterControls(scene: VoyageScene, filter: Var[VoyageFilter]): HtmlElement =
    def threshold(labelText: String, set: (VoyageFilter, Option[Double]) => VoyageFilter) =
      label(
        cls("threshold"),
        labelText,
        input(
          typ("number"),
          minAttr("0"),
          maxAttr("1"),
          stepAttr("0.05"),
          placeholder("none"),
          onInput.mapToValue --> (v =>
            filter.update(f => set(f, v.trim.toDoubleOption.filter(x => x >= 0 && x <= 1)))
          )
        )
      )
    div(
      cls("inspection-filter"),
      role("group"),
      aria.label("Inspection filter"),
      span(cls("hint"), "Inspection filter"),
      VoyageFilter.Criterion.values.toVector.map { c =>
        val n = VoyageFilter.count(scene, c)
        val name = c.label.capitalize
        button(
          typ("button"),
          cls("chip"),
          s"$name ${n.value}" + (if n.countedByView then " (view count)" else ""),
          aria.label(
            s"$name, ${n.value} units" + (if n.countedByView then ", counted by this view" else "")
          ),
          aria.pressed <-- filter.signal.map(_.criteria(c).toString),
          onClick --> (_ => filter.update(_.toggled(c)))
        )
      },
      label(
        cls("threshold"),
        "combine ",
        select(
          option(value("any"), "any"),
          option(value("all"), "all"),
          onChange.mapToValue --> (v =>
            filter.update(
              _.copy(combine =
                if v == "all" then VoyageFilter.Combine.All else VoyageFilter.Combine.Any
              )
            )
          )
        )
      ),
      threshold("argmax mass below ", (f, v) => f.copy(argmaxMassBelow = v)),
      threshold("external mass above ", (f, v) => f.copy(externalMassAbove = v)),
      threshold("localizability below ", (f, v) => f.copy(localizabilityBelow = v)),
      span(
        cls("filter-count"),
        role("status"),
        child.text <-- filter.signal
          .map(f =>
            if !f.isSet then "no filter set"
            else
              s"${f.combine.toString.toLowerCase} · ${f.matches(scene).size} of ${scene.units.size} units match"
          )
          .distinct
      )
    )

  /** Both exports, generated from the scene the pane compiled and the live filter. */
  private def exportControls(scene: VoyageScene, filter: Var[VoyageFilter]): HtmlElement =
    def save(name: String, text: String): Unit =
      val blob = new dom.Blob(
        js.Array(text),
        new dom.BlobPropertyBag { `type` = "text/tab-separated-values" }
      )
      val url = dom.URL.createObjectURL(blob)
      val a = dom.document.createElement("a").asInstanceOf[dom.HTMLAnchorElement]
      a.href = url
      a.setAttribute("download", name)
      a.click()
      dom.URL.revokeObjectURL(url)
    div(
      cls("export-actions"),
      button(
        typ("button"),
        child.text <-- filter.signal.map(f =>
          if f.isSet then
            s"Export all ${scene.units.size} units (${f.matches(scene).size} matched) · TSV"
          else s"Export all ${scene.units.size} units · TSV"
        ),
        onClick --> (_ => save("voyage-units.tsv", VoyageExport.units(scene, filter.now())))
      ),
      button(
        typ("button"),
        "Export admitted anchors · TSV",
        onClick --> (_ => save("voyage-anchors.tsv", VoyageExport.anchors(scene, filter.now())))
      )
    )

  private def provenance(scene: VoyageScene): HtmlElement =
    val prov = scene.provenance
    footerTag(
      cls("prov"),
      p(
        s"Producer basis (legacy label): ${prov.basis.label}. Source checksum ${prov.sourceChecksum.hex.take(12)}…; " +
          s"configuration ${prov.configChecksum.hex.take(12)}…; compiler ${prov.compilerVersion}. " +
          scene.coding.fold("No independent coding.")(c =>
            s"Coding: ${c.name} (${c.checksum.hex.take(12)}…), ${c.intervals.size} intervals."
          )
      )
    )

  // ------------------------------------------------------------------ hover and inspector

  /** The unit before or after by ordinal, as context for reading the selected one. Drawn only when
    * the whole recall text is in view; under a reader horizon a neighbour's words may not be.
    */
  private def neighbour(scene: VoyageScene, u: VoyageUnit, delta: Int, show: Boolean): Node =
    scene.units
      .find(_.ordinal == u.ordinal + delta)
      .filter(_ => show)
      .fold[Node](emptyNode)(n =>
        p(
          cls("note neighbour"),
          s"${if delta < 0 then "previous" else "next"} · unit ${n.ordinal} · " +
            n.onset.fold("untimed")(t => clockInput(t.value)) + s" — ${n.text}"
        )
      )

  private def markOf(scene: VoyageScene, id: MarkId): Option[VoyageMark] =
    scene.marks.find(_.identity.mark == id)

  private def groupLabel(scene: VoyageScene, group: Option[Int]): String =
    group.flatMap(scene.timeline.byGroup.get).map(_.label).getOrElse("—")

  private def nodeLabel(scene: VoyageScene, ref: SourceNodeRef): String =
    scene.timeline.node(ref).map(_.label).getOrElse(ref.key)

  private def hoverCard(
      scene: VoyageScene,
      id: MarkId,
      visibleRecallText: Option[Map[RecallUnitId, String]] = None
  ): Option[HtmlElement] =
    markOf(scene, id).flatMap { m =>
      scene.units.find(_.id == m.unit).map { u =>
        val where = m match
          case a: VoyageMark.UnitAnchor =>
            s"${clock(a.at.value)} into recall → ${nodeLabel(scene, a.anchor)} · " +
              groupLabel(scene, a.group)
          case a: VoyageMark.Alternative =>
            f"alternative ${a.rank}: ${nodeLabel(scene, a.anchor)} · mass ${a.mass}%.2f"
          case a: VoyageMark.Unanchored =>
            s"${clock(a.at.value)} into recall → anchored nowhere in the source"
          case _: VoyageMark.Untimed => "no recall onset"
        val numbers = m match
          case a: VoyageMark.UnitAnchor =>
            f"anchor mass ${a.mass}%.2f · source ${a.sourceMass}%.2f · external ${a.externalMass}%.2f · ${a.origin.label}" +
              scene
                .codedGroupAt(a.at)
                .fold("")(g =>
                  s" · coded ${groupLabel(scene, Some(g))}${
                      if a.group.contains(g) then " ✓" else ""
                    }"
                )
          case a: VoyageMark.Unanchored => f"external ${a.externalMass}%.2f"
          case _                        => ""
        div(
          div(cls("q"), visibleRecallText.fold(u.text)(_.getOrElse(u.id, ""))),
          div(cls("m"), where),
          div(cls("m"), numbers)
        )
      }
    }

  /** Where in its group the anchor sits: the group's segments as a strip, the drawn one filled, the
    * alternatives in the same group soft, the posterior argmax it left (if in this group) dashed.
    * Every rectangle is a timeline node's span (the mark's own for the drawn one), and the "i of n"
    * is the anchor's position among the timeline's nodes of that group, read off, not modelled.
    */
  private def withinGroup(
      scene: VoyageScene,
      a: VoyageMark.UnitAnchor,
      alternatives: Vector[VoyageMark.Alternative]
  ): Vector[HtmlElement] =
    a.group.flatMap(g => scene.timeline.byGroup.get(g).map(g -> _)).toVector.map {
      case (g, group) =>
        val segments = scene.timeline.nodes
          .filter(n => n.level == 0 && n.group.contains(g))
          .sortBy(_.span.start.value)
        val s0 = group.span.start.value
        val len = math.max(group.span.end.value - s0, 1e-9)
        def x(t: Double): Double = 300.0 * (t - s0) / len
        def block(span: ClockSpan, y: Double, h: Double, style: String): SvgElement =
          svg.rect(
            svg.x := f"${x(span.start.value)}%.2f",
            svg.y := y.toString,
            svg.width := f"${math.max(1.0, x(span.end.value) - x(span.start.value))}%.2f",
            svg.height := h.toString,
            svg.style := style
          )
        val base = svg.rect(
          svg.x := "0",
          svg.y := "18",
          svg.width := "300",
          svg.height := "8",
          svg.style := "fill: var(--surface-2)"
        )
        val ticks = segments.map(n =>
          svg.line(
            svg.x1 := f"${x(n.span.start.value)}%.2f",
            svg.y1 := "16",
            svg.x2 := f"${x(n.span.start.value)}%.2f",
            svg.y2 := "28",
            svg.style := "stroke: var(--hair)"
          )
        )
        val soft = alternatives
          .filter(_.group.contains(g))
          .map(alt => block(alt.span, 18, 8, s"fill: $ModelSoft"))
        val left = a.argmax
          .filter(_ != a.anchor)
          .flatMap(scene.timeline.node)
          .filter(_.group.contains(g))
          .toVector
          .map(n =>
            block(
              n.span,
              14,
              16,
              s"fill: none; stroke: $Raw; stroke-width: 1; stroke-dasharray: 3 2"
            )
          )
        val drawn =
          if a.level > 0 then
            block(a.span, 14, 16, s"fill: $ModelSoft; stroke: $Model; stroke-width: 1")
          else block(a.span, 14, 16, s"fill: $Model")
        val index = segments.indexWhere(_.ref == a.anchor)
        val where =
          if a.level > 0 then
            s"the whole group, ${clock(a.span.start.value)}–${clock(a.span.end.value)}"
          else if index >= 0 then
            s"${nodeLabel(scene, a.anchor)} · ${clock(a.span.start.value)}–${clock(a.span.end.value)} · segment ${index + 1} of ${segments.size}"
          else
            s"${nodeLabel(scene, a.anchor)} · ${clock(a.span.start.value)}–${clock(a.span.end.value)}"
        div(
          cls("strip"),
          h2("Within the group"),
          svg.svg(
            svg.viewBox := "0 0 300 44",
            svg.preserveAspectRatio := "none",
            svg.role := "img",
            svg.titleTag(
              s"${group.label}: display clock (legacy), ${clockInput(s0)}–${clockInput(group.span.end.value)}"
            ),
            base,
            ticks,
            soft,
            left,
            drawn
          ),
          div(
            cls("group-extents num"),
            span(clockInput(s0)),
            span(clockInput(group.span.end.value))
          ),
          p(cls("segtext"), span(cls("lab"), group.label), where),
          p(
            cls("note"),
            "Display clock (legacy). Width represents the supplied annotation span, not verified media support. ",
            if a.level > 0 then "This choice locates the whole group, not an individual segment."
            else
              "Ticks mark segment starts; the solid span is the drawn choice. Pale spans are alternatives; a dashed outline marks a moved argmax in this group."
          )
        )
    }

  private def inspector(
      scene: VoyageScene,
      u: VoyageUnit,
      visibleRecallText: Option[String] = None,
      filter: VoyageFilter = VoyageFilter.none
  ): HtmlElement =
    val presentation = VoyagePosterior.forUnit(scene, u.id)
    val comparison = VoyageGroupComparison.records(scene).find(_.unit == u.id)
    val anchor = presentation.map(_.drawn)
    val alternatives = scene.marks.collect { case m: VoyageMark.Alternative if m.unit == u.id => m }
    val absence = scene.marks.collectFirst {
      case m: VoyageMark.Unanchored if m.unit == u.id => m: VoyageMark
      case m: VoyageMark.Untimed if m.unit == u.id    => m: VoyageMark
    }
    val onsetText = u.onset.fold("no onset")(t => clock(t.value)) +
      u.lastWordOnset.fold("")(t => s"–${clock(t.value)}")
    val placement: Vector[HtmlElement] = anchor match
      case Some(a) =>
        val coded = scene.codedGroupAt(a.at)
        Vector(
          div(cls("k"), "coding"),
          div(
            cls("v"),
            coded.fold("no coded group at this moment")(g =>
              s"${groupLabel(scene, Some(g))}${
                  if a.group.contains(g) then " · same group" else " · different group"
                }"
            )
          ),
          div(cls("k"), "external"),
          div(
            cls("v"),
            if a.externalDominant then "dominant: more mass outside the source than in it"
            else "does not exceed the source mass"
          ),
          div(cls("k"), "localizability"),
          div(
            cls("v"),
            a.localizability.fold("—")(l => f"$l%.2f"),
            span(
              cls("note"),
              s" 1 − H/log K over the admitted anchors; K = ${scene.timeline.nodes.size} timeline nodes (counted by this view)"
            )
          )
        )
      case None =>
        Vector(
          div(cls("k"), "placed at"),
          div(
            cls("v"),
            absence.fold("—") {
              case _: VoyageMark.Unanchored => "nowhere in the source"
              case _: VoyageMark.Untimed    => "not placed: no recall onset"
              case _                        => "—"
            }
          )
        )
    val decision = anchor.toVector.map { a =>
      div(
        cls("drawn-choice"),
        dataAttr("source-key") := a.anchor.key,
        h2("Drawn choice"),
        p(cls("choice-target"), b(nodeLabel(scene, a.anchor)), " · ", groupLabel(scene, a.group)),
        p(
          cls("note choice-span"),
          dataAttr("span-start") := a.span.start.value.toString,
          dataAttr("span-end") := a.span.end.value.toString,
          s"Annotation span ${clockInput(a.span.start.value)}–${clockInput(a.span.end.value)} · display clock (legacy)"
        ),
        p(cls("note"), a.origin.label),
        p(
          cls("note"),
          "Supplied anchor mass ",
          span(cls("num"), f"${a.mass}%.3f"),
          if a.mass == 0 then " · drawn (not in posterior)" else " · also ranked below"
        ),
        p(
          cls("note"),
          a.argmax.fold("No source argmax supplied.")(r =>
            s"Posterior argmax: ${nodeLabel(scene, r)}."
          )
        ),
        p(
          cls("note selected-group-comparison"),
          comparison.fold("Group comparison unavailable.") { r =>
            s"Drawn group: ${groupLabel(scene, r.drawn)}. Posterior-argmax group: ${groupLabel(scene, r.argmax)}. " +
              (r.status match
                case VoyageGroupComparison.Status.Agreement =>
                  "Same group; omitted from the disagreement strip."
                case VoyageGroupComparison.Status.Disagreement => "Different groups."
                case VoyageGroupComparison.Status.Unknown => "Comparison unknown, not agreement.")
          }
        )
      )
    }
    // These two totals are supplied by the compiler, not summed or normalized by the viewer.
    // All positive source candidates remain in the table, including a supported drawn choice.
    val posterior: Vector[HtmlElement] = presentation.toVector.flatMap { record =>
      val a = record.drawn
      val parts = Vector(("Source", a.sourceMass, Model), ("External", a.externalMass, External))
      val topAlternative = record.candidates.find(!_.drawn)
      Vector(
        div(
          cls("post"),
          h2("Supplied posterior mass"),
          p(cls("note"), "Fixed 0–1 scale · model mass, not calibrated confidence."),
          div(
            cls("bar"),
            role("img"),
            aria.label(
              f"Source mass ${a.sourceMass}%.3f; external mass ${a.externalMass}%.3f; fixed zero to one scale"
            ),
            parts.map((label, v, c) =>
              span(
                dataAttr("mass-kind") := label.toLowerCase,
                dataAttr("mass") := v.toString,
                width := s"${100 * v}%",
                backgroundColor := c
              )
            )
          ),
          div(cls("mass-extents num"), span("0"), span("1")),
          div(
            cls("legend"),
            parts.map((k, v, c) =>
              span(i(backgroundColor := c), s"$k ", span(cls("num"), f"$v%.3f"))
            )
          ),
          p(
            cls("note top-alternative"),
            topAlternative.fold("No other source candidate has positive mass.")(alt =>
              f"Top alternative: ${nodeLabel(scene, alt.ref)} · mass ${alt.mass}%.3f"
            )
          )
        ),
        detailsTag(
          cls("post posterior-candidates"),
          summaryTag(s"All ${record.candidates.size} source candidates, ranked"),
          p(cls("note"), VoyagePosterior.orderingNote, " External mass is reported separately."),
          table(
            cls("alts"),
            thead(tr(th("Rank"), th("Mass"), th("Source target"), th("Group"))),
            tbody(
              record.candidates.zipWithIndex.map((candidate, i) =>
                tr(
                  dataAttr("source-key") := candidate.ref.key,
                  dataAttr("mass") := candidate.mass.toString,
                  td(cls("num"), (i + 1).toString),
                  td(cls("num"), f"${candidate.mass}%.3f"),
                  td(nodeLabel(scene, candidate.ref), if candidate.drawn then " · drawn" else ""),
                  td(groupLabel(scene, candidate.group))
                )
              )
            )
          )
        )
      )
    }
    div(
      cls("inspector-content"),
      div(
        div(cls("where"), s"Unit ${u.ordinal} · ", span(cls("num"), onsetText), " into the recall"),
        neighbour(scene, u, -1, visibleRecallText.isEmpty),
        p(cls("quote"), s"“${visibleRecallText.getOrElse(u.text)}”"),
        neighbour(scene, u, 1, visibleRecallText.isEmpty)
      ),
      decision,
      div(
        cls("kv"),
        placement,
        div(cls("k"), "filter"),
        div(
          cls("v filter-reasons"),
          if !filter.isSet then "no filter set"
          else
            scene.marks
              .find(m => m.unit == u.id && !m.isInstanceOf[VoyageMark.Alternative])
              .map(filter.reasons(scene, _))
              .filter(_.nonEmpty)
              .fold("does not match")(r => s"matches: ${r.mkString(", ")}")
        )
      ),
      anchor.toVector.flatMap(a => withinGroup(scene, a, alternatives)),
      posterior,
      if anchor.isEmpty then
        div(
          cls("post posterior-unavailable"),
          h2("Posterior detail unavailable"),
          p(
            cls("note"),
            "This Voyage projection does not carry the complete posterior for untimed or unanchored units."
          ),
          p(
            cls("note selected-group-comparison"),
            "Group comparison unknown: this projection does not supply both groups."
          ),
          absence.toVector.collect { case a: VoyageMark.Unanchored =>
            p(cls("note"), "Supplied external mass: ", span(cls("num"), f"${a.externalMass}%.3f"))
          }
        )
      else emptyNode
    )

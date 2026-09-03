package storyatlas4s.app

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import storyatlas4s.intaglio.VoyageLowering
import storymodel4s.align.SourceNodeRef
import storymodel4s.codec.VoyageCodecs
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
  * prints what the scene already holds. It computes no number: every figure is read from a mark or
  * the compiled summary, and a bar's segments are the masses the marks carry.
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
      width: Option[Int]
  )

  /** The plate follows its panel between these widths; narrower and the clocks lose their ticks,
    * wider and the marks drift apart for nothing.
    */
  private val MinWidth = 720
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

  private def pane(document: RecallVoyageDocument, scene: VoyageScene): HtmlElement =
    val selection = Var(Set.empty[Address])
    val focus = Var(Option.empty[RecallUnitId])
    val hovered = Var(Option.empty[MarkId])
    val tip = Var(Option.empty[(Double, Double)])
    val ghosts = Var(true)
    val allColumns = Var(false)
    // unmeasured until the panel is laid out, so the plate is lowered once, at its real width
    val width = Var(Option.empty[Int])
    val lens: Signal[Lens] = selection.signal
      .combineWith(ghosts.signal, allColumns.signal, width.signal.distinct)
      .map { case (s, g, all, w) => Lens(s, g, all, w) }
    val frames: Signal[Option[Either[String, Frame]]] =
      lens.map(l => l.width.map(w => compile(document, scene, l, w)))
    val timed = scene.units.filter(_.onset.isDefined).sortBy(_.onset.map(_.value))

    def unitAddress(id: RecallUnitId): Address = Addressable[RecallRef].address(RecallRef.Unit(id))

    def unitOfAddress(address: Address): Option[RecallUnitId] =
      Addressable[RecallRef].parse(address).collect { case RecallRef.Unit(id) => id }

    def activate(target: dom.EventTarget, extend: Boolean): Unit =
      nameAt(target).flatMap(n => scene.navigation.addressOf.get(MarkId.unsafe(n))).foreach {
        addr =>
          selection.update { s =>
            if !extend then Set(addr) else if s.contains(addr) then s - addr else s + addr
          }
          focus.set(unitOfAddress(addr).orElse(focus.now()))
      }

    def walk(delta: Int): Unit =
      if timed.nonEmpty then
        val at = focus.now().map(id => timed.indexWhere(_.id == id)).getOrElse(-1)
        val next = math.max(0, math.min(timed.size - 1, at + delta))
        val id = timed(next).id
        selection.set(Set(unitAddress(id)))
        focus.set(Some(id))

    def fit(el: dom.Element): Unit =
      val w = el.clientWidth
      if w > 0 then width.set(Some(math.max(MinWidth, math.min(MaxWidth, w))))

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
      header(scene, ghosts, allColumns),
      stats(scene),
      div(
        cls("panes"),
        sectionTag(
          cls("panel"),
          aria.label("Recall Voyage"),
          tabIndex(0),
          div(
            cls("head"),
            h2("Recall time against source time"),
            span(cls("hint"), "hover a mark · click to inspect · ← → walk the recall")
          ),
          onClick --> (ev => activate(ev.target, ev.shiftKey)),
          onKeyDown --> { ev =>
            ev.key match
              case "ArrowRight" => ev.preventDefault(); walk(1)
              case "ArrowLeft"  => ev.preventDefault(); walk(-1)
              case "Enter"      => activate(ev.target, ev.shiftKey)
              case _            => ()
          },
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
          legend
        ),
        asideTag(
          cls("panel inspector"),
          aria.live("polite"),
          child <-- focusedUnit.map(
            _.fold[HtmlElement](
              div(
                cls("empty"),
                "Click a mark to inspect a unit; ",
                span(cls("kbd"), "←"),
                " ",
                span(cls("kbd"), "→"),
                " walk the recall in time order."
              )
            )(u => inspector(scene, u))
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
      width: Int
  ): Either[String, Frame] =
    for
      scene <- document.compile(lens.selection).left.map(_.message)
      selected = lens.selection.toVector
        .flatMap(a => Addressable[RecallRef].parse(a))
        .collect { case RecallRef.Unit(id) => id }
        .toSet
      context = if lens.allColumns then base.units.map(_.id).toSet -- selected else Set.empty
      box = VoyageLowering.Box.default.copy(width = width)
      lowered <- VoyageLowering
        .lower(
          scene,
          alternativesFor = selected,
          box = box,
          ghosts = lens.ghosts,
          contextFor = context
        )
        .left
        .map(_.message)
      options <- SvgOptions(box.width, box.height, Some("Recall Voyage")).left.map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
    yield Frame(scene, svg.value)

  private def mount(container: dom.Element, frame: Frame, selection: Set[Address]): Unit =
    // a mark reached by Tab and activated with Enter is about to be replaced; remember that the
    // keyboard was in the plate, so the walk can continue from the selected mark
    val active = dom.document.activeElement
    val keyboardInPlate = active != null && container.contains(active)
    container.innerHTML = frame.svg
    val named = container.querySelectorAll("[data-name]")
    var i = 0
    while i < named.length do
      val el = named(i).asInstanceOf[dom.Element]
      el.setAttribute("tabindex", "0")
      el.setAttribute("role", "button")
      i += 1
    decorate(container, frame.scene, selection)
    if keyboardInPlate then
      Option(container.querySelector(s".${SvgDom.SelectedClass}"))
        .foreach(_.asInstanceOf[js.Dynamic].focus())

  private def decorate(container: dom.Element, scene: VoyageScene, selection: Set[Address]): Unit =
    val selectedMarks = selection.toVector.flatMap(scene.navigation.marksFor).map(_.value).toSet
    val named = container.querySelectorAll("[data-name]")
    var i = 0
    while i < named.length do
      val el = named(i).asInstanceOf[dom.Element]
      val on = selectedMarks.contains(el.getAttribute("data-name"))
      if on then el.classList.add(SvgDom.SelectedClass)
      else el.classList.remove(SvgDom.SelectedClass)
      el.setAttribute("aria-pressed", on.toString)
      i += 1

  private def nameAt(target: dom.EventTarget): Option[String] =
    target match
      case el: dom.Element =>
        Option(el.closest("[data-name]")).map(_.getAttribute("data-name")).filter(_.nonEmpty)
      case _ => None

  // ------------------------------------------------------------------ chrome

  private def clock(t: Double): String = VoyageLowering.clock(t)

  private def header(
      scene: VoyageScene,
      ghosts: Var[Boolean],
      allColumns: Var[Boolean]
  ): HtmlElement =
    headerTag(
      cls("top"),
      div(
        cls("masthead"),
        div(cls("eyebrow"), s"storyatlas4s · recall voyage · ${scene.provenance.basis.label}"),
        h1("Recall Voyage"),
        p(
          "Each spoken recall unit is placed on the source clock. The mark carries the posterior " +
            "mass on the drawn anchor, how the anchor came to be, and where the independent coding " +
            "puts the same moment."
        )
      ),
      div(
        cls("controls"),
        toggle(ghosts, "ghosts of the posterior argmax"),
        toggle(allColumns, "posterior columns for every unit")
      )
    )

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
      ("recall units", s.units.toString, s"${clock(scene.recallLength.value)} of speech"),
      (
        "moved by the decode",
        moved.toString,
        s"${s.decodeFilled} of them filled outside the posterior (mass zero)"
      ),
      ("unanchored", s.unanchored.toString, "no source anchor; drawn on the absence rail"),
      (
        "external-dominant",
        s.externalDominant.toString,
        "external mass exceeds source mass; drawn hollow"
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
  private val Gold = "var(--gold)"
  private val GoldBand = "var(--gold-band)"
  private val Surface = "var(--surface)"

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

  private def item(mark: SvgElement, text: String): HtmlElement = span(mark, text)

  private def legend: HtmlElement =
    div(
      cls("legend-row"),
      item(
        glyph(
          svg.circle(
            svg.cx := "13",
            svg.cy := "8",
            svg.r := "6",
            svg.style := s"fill: $Model; stroke: $Surface; stroke-width: 1.2"
          )
        ),
        "posterior argmax; area is the anchor's posterior mass"
      ),
      item(
        glyph(
          svg.polygon(svg.points := diamondPoints(13, 8, 7.5), svg.style := s"fill: $Model")
        ),
        "decode-bound: the scene decode chose it, with posterior mass"
      ),
      item(
        glyph(
          svg.polygon(
            svg.points := diamondPoints(13, 8, 5.6),
            svg.style := s"fill: none; stroke: $Model; stroke-width: 1.4; stroke-dasharray: 2 1.5"
          )
        ),
        "decode-filled: mass zero, outside the posterior"
      ),
      item(
        glyph(
          svg.rect(
            svg.x := "10",
            svg.y := "1",
            svg.width := "6",
            svg.height := "14",
            svg.style := s"fill: $Model"
          )
        ),
        "group-level anchor: the model stops at the group; width is mass"
      ),
      item(
        glyph(
          svg.circle(
            svg.cx := "13",
            svg.cy := "8",
            svg.r := "6",
            svg.style := s"fill: none; stroke: $External; stroke-width: 1.6"
          )
        ),
        "hollow: the row's external mass exceeds its source mass"
      ),
      item(
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
        "posterior column of the focused unit: dashed rings, area is mass"
      ),
      item(
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
      item(
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
      item(
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
        "cross on the absence rail: anchored nowhere in the source"
      ),
      item(
        glyph(
          svg.text(
            svg.x := "2",
            svg.y := "12",
            svg.style := "font: 9px IBM Plex Mono, Menlo, monospace; fill: var(--ink-2)",
            "unit"
          )
        ),
        "margin row: a unit with no recall onset, its reason stated"
      ),
      item(
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
      item(
        glyph(
          svg.line(
            svg.x1 := "2",
            svg.y1 := "4",
            svg.x2 := "24",
            svg.y2 := "4",
            svg.style := s"stroke: $Gold; stroke-width: 4; opacity: 0.6"
          ),
          svg.line(
            svg.x1 := "2",
            svg.y1 := "9",
            svg.x2 := "24",
            svg.y2 := "9",
            svg.style := s"stroke: $Model; stroke-width: 1.6"
          ),
          svg.line(
            svg.x1 := "2",
            svg.y1 := "13.5",
            svg.x2 := "24",
            svg.y2 := "13.5",
            svg.style := s"stroke: $Raw; stroke-width: 1.2; stroke-dasharray: 3 2"
          )
        ),
        "group track: coding as a thick band, this placement thin, the posterior argmax dashed"
      )
    )

  private def provenance(scene: VoyageScene): HtmlElement =
    val prov = scene.provenance
    footerTag(
      cls("prov"),
      p(
        s"Basis: ${prov.basis.label}. Source checksum ${prov.sourceChecksum.hex.take(12)}…; " +
          s"configuration ${prov.configChecksum.hex.take(12)}…; compiler ${prov.compilerVersion}. " +
          scene.coding.fold("No independent coding.")(c =>
            s"Coding: ${c.name} (${c.checksum.hex.take(12)}…), ${c.intervals.size} intervals."
          )
      )
    )

  // ------------------------------------------------------------------ hover and inspector

  private def markOf(scene: VoyageScene, id: MarkId): Option[VoyageMark] =
    scene.marks.find(_.identity.mark == id)

  private def groupLabel(scene: VoyageScene, group: Option[Int]): String =
    group.flatMap(scene.timeline.byGroup.get).map(_.label).getOrElse("—")

  private def nodeLabel(scene: VoyageScene, ref: SourceNodeRef): String =
    scene.timeline.node(ref).map(_.label).getOrElse(ref.key)

  private def hoverCard(scene: VoyageScene, id: MarkId): Option[HtmlElement] =
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
        div(div(cls("q"), u.text), div(cls("m"), where), div(cls("m"), numbers))
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
            base,
            ticks,
            soft,
            left,
            drawn
          ),
          p(cls("segtext"), span(cls("lab"), group.label), where)
        )
    }

  private def inspector(scene: VoyageScene, u: VoyageUnit): HtmlElement =
    val anchor = scene.marks.collectFirst { case m: VoyageMark.UnitAnchor if m.unit == u.id => m }
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
          div(cls("k"), "placed at"),
          div(
            cls("v"),
            s"${nodeLabel(scene, a.anchor)} · ${clock(a.span.start.value)}–${clock(a.span.end.value)} · " +
              groupLabel(scene, a.group)
          ),
          div(cls("k"), "origin"),
          div(
            cls("v"),
            a.origin.label + (
              if a.origin != AnchorOrigin.PosteriorArgmax then
                a.argmax.fold("")(r => s" · posterior argmax was ${nodeLabel(scene, r)}")
              else ""
            )
          ),
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
            else "below the source mass"
          ),
          div(cls("k"), "localizability"),
          div(cls("v"), a.localizability.fold("—")(l => f"$l%.2f"))
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
    // The bar's segments are the masses the marks carry, nothing derived: the anchor, then each
    // alternative in rank order, then the external mass. Widths are shares of what is shown.
    val posterior: Vector[HtmlElement] = anchor.toVector.flatMap { a =>
      val parts =
        Vector(("anchor", a.mass, Model)) ++
          alternatives.zipWithIndex.map((alt, i) =>
            (s"alternative ${alt.rank}", alt.mass, if i == 0 then ModelSoft else "var(--hair)")
          ) :+ ("external", a.externalMass, External)
      val shown = parts.map(_._2).sum
      val legendParts = (parts.take(2) :+ parts.last).distinct
      Vector(
        div(
          cls("post"),
          h2("Posterior mass"),
          div(
            cls("bar"),
            parts.map((_, v, c) =>
              span(width := f"${100 * v / math.max(shown, 1e-9)}%.1f%%", backgroundColor := c)
            )
          ),
          div(
            cls("legend"),
            legendParts.map((k, v, c) =>
              span(i(backgroundColor := c), s"$k ", span(cls("num"), f"$v%.2f"))
            )
          )
        ),
        div(
          cls("post"),
          h2("The posterior, ranked"),
          table(
            cls("alts"),
            thead(tr(th("mass"), th("anchor"), th("group"))),
            tbody(
              tr(
                td(cls("num"), f"${a.mass}%.3f"),
                td(b(nodeLabel(scene, a.anchor)), " · drawn"),
                td(groupLabel(scene, a.group))
              ),
              alternatives.map(alt =>
                tr(
                  td(cls("num"), f"${alt.mass}%.3f"),
                  td(nodeLabel(scene, alt.anchor)),
                  td(groupLabel(scene, alt.group))
                )
              )
            )
          )
        )
      )
    }
    div(
      div(
        div(cls("where"), s"Unit ${u.ordinal} · ", span(cls("num"), onsetText), " into the recall"),
        p(cls("quote"), s"“${u.text}”")
      ),
      div(cls("kv"), placement),
      anchor.toVector.flatMap(a => withinGroup(scene, a, alternatives)),
      posterior
    )

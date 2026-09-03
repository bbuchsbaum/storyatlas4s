package storyatlas4s.app

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.intaglio.VoyageLowering
import storymodel4s.codec.Canonical
import storymodel4s.codec.VoyageCodecs.given
import storymodel4s.core.{Address, Addressable}
import storymodel4s.recall.{RecallRef, RecallUnitId}
import storymodel4s.view.*

/** The Recall Voyage pane (ADR 0002 §14 D4): one document, compiled in the browser by storymodel4s
  * `VoyageCompiler` under the live selection, lowered by `VoyageLowering`, drawn by intaglio's SVG
  * backend, and decorated here.
  *
  * The shell adds exactly what a static plate cannot carry: a hover card naming the unit's words
  * and the row's numbers, click and keyboard selection resolved through the scene's navigation from
  * `data-name` to `Address`, the posterior column for the selected unit, and an inspector that
  * prints what the scene already holds. It computes no number: every figure is read from a mark or
  * the compiled summary.
  */
object VoyageView:

  private final case class Frame(scene: VoyageScene, svg: String)

  def fromDocumentText(text: String): HtmlElement =
    Canonical.decode[RecallVoyageDocument](text) match
      case Left(problem) =>
        div(cls("error"), role("alert"), s"The voyage document does not decode: ${problem.message}")
      case Right(document) => apply(document)

  def apply(document: RecallVoyageDocument): HtmlElement =
    val selection = Var(Set.empty[Address])
    val hovered = Var(Option.empty[MarkId])
    val tip = Var(Option.empty[(Double, Double)])
    val frame: Signal[Either[String, Frame]] = selection.signal.map(compile(document, _))

    def selectedUnit(scene: VoyageScene, sel: Set[Address]): Option[VoyageUnit] =
      sel.toVector
        .flatMap(a => Addressable[RecallRef].parse(a))
        .collectFirst { case RecallRef.Unit(id) => id }
        .flatMap(id => scene.units.find(_.id == id))

    def unitAddress(id: RecallUnitId): Address = Addressable[RecallRef].address(RecallRef.Unit(id))

    def activate(scene: VoyageScene, target: dom.EventTarget, extend: Boolean): Unit =
      nameAt(target).flatMap(n => scene.navigation.addressOf.get(MarkId.unsafe(n))).foreach {
        addr =>
          selection.update { s =>
            if !extend then Set(addr) else if s.contains(addr) then s - addr else s + addr
          }
      }

    def walk(scene: VoyageScene, delta: Int): Unit =
      val timed = scene.units.filter(_.onset.isDefined).sortBy(_.onset.map(_.value))
      if timed.nonEmpty then
        val current = selectedUnit(scene, selection.now()).map(_.id)
        val at = current.map(id => timed.indexWhere(_.id == id)).getOrElse(-1)
        val next = math.max(0, math.min(timed.size - 1, at + delta))
        selection.set(Set(unitAddress(timed(next).id)))

    div(
      cls("page"),
      child <-- frame.map {
        case Left(problem) =>
          div(cls("error"), role("alert"), s"The voyage did not compile: $problem")
        case Right(f) =>
          val scene = f.scene
          div(
            cls("voyage"),
            dataAttr("marks") := scene.marks.size.toString,
            dataAttr("selection") <-- selection.signal.map(
              _.toVector.map(_.render).sorted.mkString(" ")
            ),
            header(scene),
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
                onClick --> (ev => activate(scene, ev.target, ev.shiftKey)),
                onKeyDown --> { ev =>
                  ev.key match
                    case "ArrowRight" => ev.preventDefault(); walk(scene, 1)
                    case "ArrowLeft"  => ev.preventDefault(); walk(scene, -1)
                    case "Enter"      => activate(scene, ev.target, ev.shiftKey)
                    case _            => ()
                },
                div(
                  cls("scroller"),
                  onMouseMove --> { ev =>
                    val name = nameAt(ev.target)
                    hovered.set(name.map(MarkId.unsafe))
                    val box = ev.currentTarget.asInstanceOf[dom.Element].getBoundingClientRect()
                    tip.set(name.map(_ => (ev.clientX - box.left + 14, ev.clientY - box.top + 14)))
                  },
                  onMouseLeave --> { _ =>
                    hovered.set(None)
                    tip.set(None)
                  },
                  div(
                    cls("plate"),
                    onMountCallback(ctx => mount(ctx.thisNode.ref, f, selection.now())),
                    // a re-render replaces the SVG string wholesale; selection classes follow
                    inContext(node => selection.signal --> (sel => decorate(node.ref, scene, sel)))
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
                child <-- selection.signal.map(sel =>
                  selectedUnit(scene, sel).fold[HtmlElement](
                    div(cls("empty"), "Click a mark to inspect a unit.")
                  )(u => inspector(scene, u))
                )
              )
            ),
            provenance(scene)
          )
      }
    )

  // ------------------------------------------------------------------ compile and draw

  private def compile(
      document: RecallVoyageDocument,
      selection: Set[Address]
  ): Either[String, Frame] =
    for
      scene <- document.compile(selection).left.map(_.message)
      units = selection.toVector
        .flatMap(a => Addressable[RecallRef].parse(a))
        .collect { case RecallRef.Unit(id) => id }
        .toSet
      lowered <- VoyageLowering.lower(scene, alternativesFor = units).left.map(_.message)
      options <- SvgOptions(
        VoyageLowering.Box.default.width,
        VoyageLowering.Box.default.height,
        Some("Recall Voyage")
      ).left.map(_.message)
      svg <- SvgRenderer.render(lowered, options).left.map(_.message)
    yield Frame(scene, svg.value)

  private def mount(container: dom.Element, frame: Frame, selection: Set[Address]): Unit =
    container.innerHTML = frame.svg
    val named = container.querySelectorAll("[data-name]")
    var i = 0
    while i < named.length do
      val el = named(i).asInstanceOf[dom.Element]
      el.setAttribute("tabindex", "0")
      el.setAttribute("role", "button")
      i += 1
    decorate(container, frame.scene, selection)

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

  private def header(scene: VoyageScene): HtmlElement =
    headerTag(
      cls("top"),
      div(
        cls("masthead"),
        div(cls("eyebrow"), s"storyatlas4s · recall voyage · ${scene.provenance.basis.label}"),
        h1("Recall Voyage"),
        p(
          "Each spoken recall unit is placed on the source clock. The mark carries the posterior mass " +
            "on the drawn anchor, how the anchor came to be, and where the independent coding puts the same moment."
        )
      )
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
      ("external-dominant", s.externalDominant.toString, "external mass above one half")
    )
    sectionTag(
      cls("stats"),
      cells.map((k, v, note) =>
        div(cls("stat"), span(cls("k"), k), span(cls("v"), v), span(cls("s"), note))
      )
    )

  private val legend: HtmlElement =
    div(
      cls("legend-row"),
      span("circle: posterior argmax; area grows with posterior mass on the drawn anchor"),
      span("diamond: decode-bound, the scene decode chose it, with posterior mass"),
      span("hollow dashed diamond: decode-filled, mass zero, outside the posterior"),
      span("bar: group-level anchor, the model stops at the group"),
      span("hollow: more mass outside the source than in it"),
      span("dashed rings: the posterior column of the selected unit, area by mass"),
      span("amber bands: the independent coding, the group's span over the interval coded to it"),
      span("grey ghost: the posterior argmax a decode moved away from")
    )

  private def provenance(scene: VoyageScene): HtmlElement =
    val prov = scene.provenance
    footerTag(
      cls("prov"),
      p(
        s"Basis: ${prov.basis.label}. Source checksum ${prov.sourceChecksum.hex.take(12)}…; configuration " +
          s"${prov.configChecksum.hex.take(12)}…; compiler ${prov.compilerVersion}. " +
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

  private def hoverCard(scene: VoyageScene, id: MarkId): Option[HtmlElement] =
    markOf(scene, id).flatMap { m =>
      scene.units.find(_.id == m.unit).map { u =>
        val where = m match
          case a: VoyageMark.UnitAnchor =>
            s"${clock(a.at.value)} into recall → ${scene.timeline.node(a.anchor).map(_.label).getOrElse(a.anchor.key)} · ${groupLabel(scene, a.group)}"
          case a: VoyageMark.Alternative =>
            s"alternative ${a.rank}: ${scene.timeline.node(a.anchor).map(_.label).getOrElse(a.anchor.key)} · mass ${f"${a.mass}%.2f"}"
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
        val node = scene.timeline.node(a.anchor)
        val coded = a.at.pipe(scene.codedGroupAt)
        Vector(
          div(cls("k"), "placed at"),
          div(
            cls("v"),
            s"${node.map(_.label).getOrElse(a.anchor.key)} · ${clock(a.span.start.value)}–${clock(a.span.end.value)} · ${groupLabel(scene, a.group)}"
          ),
          div(cls("k"), "origin"),
          div(
            cls("v"),
            a.origin.label + (if a.origin != AnchorOrigin.PosteriorArgmax then
                                a.argmax
                                  .map(r =>
                                    s" · posterior argmax was ${scene.timeline.node(r).map(_.label).getOrElse(r.key)}"
                                  )
                                  .getOrElse("")
                              else "")
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
    val posterior: Vector[HtmlElement] = anchor.toVector.flatMap { a =>
      val best = alternatives.headOption.map(_.mass).getOrElse(0.0)
      val other = math.max(0.0, a.sourceMass - a.mass - best)
      val parts = Vector(
        ("anchor", a.mass, "var(--model)"),
        ("best alternative", best, "var(--model-soft)"),
        ("other source", other, "var(--hair)"),
        ("external", a.externalMass, "var(--external)")
      )
      Vector(
        div(
          cls("post"),
          h2("Posterior mass"),
          div(
            cls("bar"),
            parts.map((_, v, c) => span(width := f"${100 * v}%.1f%%", backgroundColor := c))
          ),
          div(
            cls("legend"),
            parts.map((k, v, c) =>
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
                td(
                  b(scene.timeline.node(a.anchor).map(_.label).getOrElse(a.anchor.key)),
                  " · drawn"
                ),
                td(groupLabel(scene, a.group))
              ),
              alternatives.map(alt =>
                tr(
                  td(cls("num"), f"${alt.mass}%.3f"),
                  td(scene.timeline.node(alt.anchor).map(_.label).getOrElse(alt.anchor.key)),
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
      posterior
    )

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

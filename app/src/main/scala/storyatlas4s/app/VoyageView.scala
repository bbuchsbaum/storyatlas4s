package storyatlas4s.app

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import com.raquo.laminar.api.L.*
import org.scalajs.dom
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
  * `data-name` to `Address`, the posterior column for the focused unit, and an inspector that
  * prints what the scene already holds. It computes no number: every figure is read from a mark or
  * the compiled summary, and a bar's segments are the masses the marks carry.
  *
  * The skeleton is built once and stays in the DOM; a selection change re-lowers only the plate's
  * SVG and re-renders the inspector, so a focused section keeps its focus and the keyboard walk
  * survives its own steps.
  */
object VoyageView:

  private final case class Frame(scene: VoyageScene, svg: String)

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
    val frames: Signal[Either[String, Frame]] = selection.signal.map(compile(document, _))
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

    val focusedUnit: Signal[Option[VoyageUnit]] =
      focus.signal.map(_.flatMap(id => scene.units.find(_.id == id)))

    div(
      cls("page"),
      dataAttr("marks") := scene.marks.size.toString,
      dataAttr("selection") <-- selection.signal.map(
        _.toVector.map(_.render).sorted.mkString(" ")
      ),
      dataAttr("focus") <-- focus.signal.map(_.fold("none")(_.value)),
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
                frames --> { frame =>
                  frame match
                    case Left(problem) =>
                      node.ref.innerHTML = ""
                      node.ref.textContent = s"The voyage did not compile: $problem"
                    case Right(f) => mount(node.ref, f, selection.now())
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
            _.fold[HtmlElement](div(cls("empty"), "Click a mark to inspect a unit."))(u =>
              inspector(scene, u)
            )
          )
        )
      ),
      provenance(scene)
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
          "Each spoken recall unit is placed on the source clock. The mark carries the posterior " +
            "mass on the drawn anchor, how the anchor came to be, and where the independent coding " +
            "puts the same moment."
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

  private def legend: HtmlElement =
    div(
      cls("legend-row"),
      span("circle: posterior argmax; area grows with posterior mass on the drawn anchor"),
      span("diamond: decode-bound, the scene decode chose it, with posterior mass"),
      span("hollow dashed diamond: decode-filled, mass zero, outside the posterior"),
      span("bar: group-level anchor, the model stops at the group; width by mass"),
      span("hollow: the row's external mass exceeds its source mass"),
      span("dashed rings: the posterior column of the focused unit, area by mass"),
      span("amber bands: the independent coding, the group's span over the interval coded to it"),
      span("grey ghost: the posterior argmax a decode moved away from")
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
        Vector(("anchor", a.mass, "var(--model)")) ++
          alternatives.zipWithIndex.map((alt, i) =>
            (
              s"alternative ${alt.rank}",
              alt.mass,
              if i == 0 then "var(--model-soft)" else "var(--hair)"
            )
          ) :+ ("external", a.externalMass, "var(--external)")
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
      posterior
    )

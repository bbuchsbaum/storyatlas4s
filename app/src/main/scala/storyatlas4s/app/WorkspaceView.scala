package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.intaglio.MatrixLowering
import storyatlas4s.layout.DomMeasurer
import storyatlas4s.shell.*
import storymodel4s.align.*
import storymodel4s.codec.WorkspaceVoyage
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** Browser projections read one controller and dispatch intentions. No estimator runs here. */
object WorkspaceView:
  def apply(initial: WorkspaceController): HtmlElement =
    val controller = Var(initial)
    val notice = Var("")
    val workspace = initial.workspace
    def dispatch(action: WorkspaceAction): Unit = controller.now().dispatch(action) match
      case Left(reason) => notice.set(s"Action refused: $reason")
      case Right(next)  => controller.set(next); notice.set("")
    def exportEvidence(): Unit = WorkspaceExport.create(controller.now()) match
      case Left(reason)   => notice.set(s"Export refused: $reason")
      case Right(content) =>
        WorkspaceHost.download("storyatlas-evidence.json", content)
        notice.set("Evidence export prepared.")
    val source = AppView.imported(
      workspace.draft,
      DomMeasurer.canvas(),
      controller.signal.map(_.sourceChoice).distinct,
      () => controller.now().sourceChoice,
      choice => dispatch(WorkspaceAction.SourcePresentation(choice)),
      (address, extend) =>
        controller
          .now()
          .sourceQualified(address)
          .foreach(a => dispatch(WorkspaceAction.Select(a, extend))),
      () => dispatch(WorkspaceAction.Clear)
    )
    // A policy switch retains the fixed cut, while external alternatives can differ by policy.
    val destinations = (initial.policy.matrix.targets.map(t => Destination.Target(t.ref)) ++
      workspace.policies
        .flatMap(_.matrix.externals)
        .distinct
        .sortBy(_.ordinal)
        .map(Destination.External(_)))
    def activate(unit: RecallUnitId, destination: Destination): Unit = destination match
      case Destination.Target(target) => dispatch(WorkspaceAction.Inspect(unit, target))
      case Destination.External(_)    => dispatch(WorkspaceAction.Jump(unit))
    def move(event: dom.KeyboardEvent, row: Int, column: Int): Unit =
      val delta = event.key match
        case "ArrowLeft"  => Some(0 -> -1)
        case "ArrowRight" => Some(0 -> 1)
        case "ArrowUp"    => Some(-1 -> 0)
        case "ArrowDown"  => Some(1 -> 0)
        case _            => None
      delta.foreach { (dr, dc) =>
        event.preventDefault()
        val r = math.max(0, math.min(workspace.inventory.units.size - 1, row + dr))
        val c = math.max(0, math.min(destinations.size - 1, column + dc))
        for
          unit <- workspace.inventory.units.lift(r)
          destination <- destinations.lift(c)
        do
          activate(unit.id, destination)
          Option(dom.document.querySelector(s"[data-matrix-cell='$r-$c']"))
            .foreach(_.asInstanceOf[dom.html.Element].focus())
      }
    val matrix = div(
      cls("mapping-scroll"),
      role("region"),
      aria.label("Alignment matrix; scroll horizontally for all destinations"),
      table(
        cls("mapping-matrix"),
        caption(
          "Complete target cut. Missing measures remain Not supplied; selection never renormalizes values."
        ),
        thead(tr(th("Recall unit / outcome"), destinations.map(d => th(d.key)))),
        tbody(workspace.inventory.units.zipWithIndex.map { (unit, rowIndex) =>
          tr(
            dataAttr("recall-unit")(unit.id.value),
            th(
              button(
                typ("button"),
                s"${unit.ordinal + 1}. ${unit.id.value}",
                onClick --> (_ => dispatch(WorkspaceAction.Jump(unit.id))),
                aria.pressed <-- controller.signal.map(
                  _.state.activeRecall.contains(unit.id).toString
                )
              ),
              div(
                cls("row-status"),
                child.text <-- controller.signal.map(c =>
                  c.policy.matrix
                    .row(unit.id)
                    .fold("Outcome not supplied")(r =>
                      s"${r.outcome.processing} · ${r.outcome.localization}"
                    )
                )
              )
            ),
            destinations.zipWithIndex.map { (destination, columnIndex) =>
              td(
                button(
                  typ("button"),
                  cls("matrix-cell"),
                  dataAttr("matrix-cell")(s"$rowIndex-$columnIndex"),
                  aria.label(s"Inspect recall ${unit.ordinal + 1}, ${destination.key}"),
                  tabIndex <-- controller.signal.map { c =>
                    val activeRow =
                      c.state.activeRecall.getOrElse(workspace.inventory.units.head.id)
                    val activeColumn = c.state.correspondence
                      .map(x => Destination.Target(x.target))
                      .getOrElse(destinations.head)
                    if activeRow == unit.id && activeColumn == destination then 0 else -1
                  },
                  aria.pressed <-- controller.signal.map(c =>
                    val selected = c.state.activeRecall.contains(unit.id) && (destination match
                      case Destination.Target(target) =>
                        c.state.correspondence.contains(Correspondence(unit.id, target))
                      case Destination.External(_) => false)
                    selected.toString
                  ),
                  children <-- controller.signal.map { c =>
                    c.policy.matrix.row(unit.id).flatMap(_.cell(destination)) match
                      case None       => Vector(div("Not supplied in this result"))
                      case Some(cell) =>
                        MatrixLowering
                          .values(cell)
                          .map((kind, value) =>
                            div(span(cls("measure-kind"), kind), strong(value))
                          ) ++
                          Option
                            .when(cell.chosen)(div(cls("chosen-label"), "◇ Chosen destination"))
                            .toVector
                  },
                  onClick --> (_ => activate(unit.id, destination)),
                  onKeyDown --> (event => move(event, rowIndex, columnIndex))
                )
              )
            }
          )
        })
      )
    )
    div(
      cls("joined-workspace"),
      dataAttr("workspace-ready")("true"),
      dataAttr("policy") <-- controller.signal.map(_.state.policy.value),
      dataAttr("active-recall") <-- controller.signal.map(_.state.activeRecall.fold("")(_.value)),
      dataAttr("selection") <-- controller.signal.map(
        _.state.selection.toVector.sorted.map(_.render).mkString(" ")
      ),
      h1("Source + recall investigation"),
      p(
        cls("basis-note"),
        s"${workspace.origin} · imported draft source · ${workspace.inventory.units.size} recall units"
      ),
      div(
        cls("workspace-actions"),
        label(
          "Mapping result ",
          select(
            idAttr("workspace-policy"),
            workspace.policies.map(p => option(value(p.id.value), p.id.value)),
            controlled(
              value <-- controller.signal.map(_.state.policy.value),
              onChange.mapToValue --> (v =>
                workspace.policies
                  .find(_.id.value == v)
                  .foreach(p => dispatch(WorkspaceAction.Policy(p.id)))
              )
            )
          )
        ),
        WorkspaceMode.values.toVector.map(mode =>
          button(
            typ("button"),
            mode.toString,
            aria.pressed <-- controller.signal.map(c => (c.state.mode == mode).toString),
            onClick --> (_ => dispatch(WorkspaceAction.Mode(mode)))
          )
        ),
        button(
          typ("button"),
          "Save investigation",
          idAttr("workspace-save"),
          onClick --> (_ =>
            WorkspaceHost.download("investigation.json", WorkspaceSave.encode(controller.now()))
          )
        ),
        button(
          typ("button"),
          "Export evidence",
          idAttr("workspace-export"),
          onClick --> (_ => exportEvidence())
        ),
        button(typ("button"), "Clear selection", onClick --> (_ => dispatch(WorkspaceAction.Clear)))
      ),
      p(role("status"), aria.live("polite"), child.text <-- notice.signal),
      div(
        cls("recall-navigation"),
        button(
          typ("button"),
          "Previous recall",
          onClick --> (_ => dispatch(WorkspaceAction.Walk(-1))),
          disabled(workspace.inventory.units.isEmpty)
        ),
        label(
          "Recall unit ",
          select(
            idAttr("workspace-recall"),
            option(value(""), "Choose a recall unit"),
            workspace.inventory.units.map(u =>
              option(
                value(u.id.value),
                s"${u.ordinal + 1}. ${u.id.value} · ${workspace.timing(u.id)}"
              )
            ),
            controlled(
              value <-- controller.signal.map(_.state.activeRecall.fold("")(_.value)),
              onChange.mapToValue --> (v =>
                workspace.inventory.units
                  .find(_.id.value == v)
                  .foreach(u => dispatch(WorkspaceAction.Jump(u.id)))
              )
            )
          )
        ),
        button(
          typ("button"),
          "Next recall",
          onClick --> (_ => dispatch(WorkspaceAction.Walk(1))),
          disabled(workspace.inventory.units.isEmpty)
        ),
        label(
          "Show all recall evidence ",
          input(
            typ("checkbox"),
            checked <-- controller.signal.map(_.state.recallHorizon == EpistemicHorizon.Omniscient),
            onChange.mapToChecked --> (all =>
              dispatch(
                WorkspaceAction.RecallHorizon(
                  if all then EpistemicHorizon.Omniscient
                  else
                    EpistemicHorizon.ReaderAt(
                      Playhead.position(
                        workspace.recall.transcript.canonicalText,
                        controller.now().state.recallHorizon
                      )
                    )
                )
              )
            )
          )
        ),
        span(
          child.text <-- controller.signal.map(c =>
            Playhead.describe(workspace.recall.transcript.canonicalText, c.state.recallHorizon)
          )
        ),
        label(
          "Recall reader horizon ",
          input(
            idAttr("recall-horizon"),
            typ("range"),
            minAttr("0"),
            maxAttr(workspace.recall.transcript.canonicalText.length.toString),
            stepAttr("1"),
            controlled(
              value <-- controller.signal.map(c =>
                Playhead
                  .position(workspace.recall.transcript.canonicalText, c.state.recallHorizon)
                  .toString
              ),
              onInput.mapToValue --> (v =>
                v.toIntOption.foreach(n =>
                  dispatch(
                    WorkspaceAction.RecallHorizon(
                      EpistemicHorizon.ReaderAt(
                        Playhead.snap(workspace.recall.transcript.canonicalText, n)
                      )
                    )
                  )
                )
              )
            )
          )
        )
      ),
      div(
        cls("joined-columns"),
        sectionTag(cls("joined-source"), h2("Source"), source),
        sectionTag(
          cls("joined-recall"),
          h2("Recall and exact evidence"),
          inspector(controller.signal, dispatch)
        )
      ),
      sectionTag(
        display <-- controller.signal.map(c =>
          if c.state.mode == WorkspaceMode.Recall then "block" else "none"
        ),
        h2("Alignment matrix"),
        matrix
      ),
      sectionTag(
        display <-- controller.signal.map(c =>
          if c.state.mode == WorkspaceMode.Voyage then "block" else "none"
        ),
        h2("Recall Voyage"),
        child <-- controller.signal.map(_.state.policy).distinct.map { id =>
          WorkspaceVoyage.from(workspace, id) match
            case Left(reason)      => p(s"Voyage unavailable: $reason")
            case Right(projection) =>
              div(
                p("Clock projection availability for the complete recall inventory:"),
                ul(
                  projection.units
                    .map(u => li(s"${u.ordinal + 1}. ${u.unit.value}: ${u.disposition}"))
                )
              )
        }
      ),
      detailsTag(
        summaryTag("Artifact identities and view state"),
        p(s"Source SHA-256: ${workspace.modelArtifact.hex}"),
        p(s"Recall SHA-256: ${workspace.recallArtifact.hex}"),
        pre(idAttr("workspace-state"), child.text <-- controller.signal.map(WorkspaceSave.encode))
      )
    )

  private def inspector(
      signal: Signal[WorkspaceController],
      dispatch: WorkspaceAction => Unit
  ): HtmlElement =
    div(child <-- signal.map { c =>
      def pieces(values: Vector[(SpanRef, String)]): HtmlElement =
        if values.isEmpty then p("No complete evidence fragment is visible at this horizon.")
        else
          div(
            values.map((ref, text) =>
              blockQuote(
                p(text),
                small(s"Exact support: ${ref.span.start}–${ref.span.endExclusive}")
              )
            )
          )
      val target = c.state.correspondence
        .map(_.target)
        .orElse(c.state.focus.flatMap(c.workspace.sourceAddresses.get))
      div(
        c.state.activeRecall.toVector.map { unit =>
          val row = c.policy.matrix.row(unit).get
          div(
            h3(s"Recall ${row.unit.ordinal + 1}: ${unit.value}"),
            p(
              s"${row.outcome.processing} · ${row.outcome.localization} · ${c.workspace.timing(unit)}"
            ),
            c.recallEvidence(unit).fold(reason => p(s"Evidence unavailable: $reason"), pieces),
            p(
              row.outcome.decision.fold("Decision not supplied")(d =>
                s"Decision: ${d.chosen.fold("abstain")(_.key)} · origin ${d.origin} · ${d.basis.kind}"
              )
            ),
            p("Calibrated correctness probability: not supplied"),
            h3("Every candidate and external alternative"),
            ul(row.cells.map { cell =>
              li(
                button(
                  typ("button"),
                  cell.destination.key,
                  onClick --> (_ =>
                    cell.destination match
                      case Destination.Target(ref) => dispatch(WorkspaceAction.Inspect(unit, ref))
                      case Destination.External(_) => dispatch(WorkspaceAction.Jump(unit))
                  )
                ),
                span(
                  MatrixLowering.values(cell).map((kind, value) => s"$kind: $value").mkString(" · ")
                )
              )
            })
          )
        },
        Option.when(c.state.activeRecall.isEmpty)(
          p("Choose any recall unit to inspect its complete outcome and alternatives.")
        ),
        target.toVector.map { ref =>
          val references = c.workspace.inverse(c.state.policy, ref) match
            case Left(reason) => p(s"Inverse references unavailable: $reason")
            case Right(units) =>
              ul(units.map { unit =>
                li(
                  button(
                    typ("button"),
                    unit.value,
                    onClick --> (_ => dispatch(WorkspaceAction.Jump(unit)))
                  )
                )
              })
          div(
            h3(s"Source evidence: ${ref.key}"),
            c.sourceEvidence(ref)
              .fold(
                reason => p(s"Evidence unavailable: $reason"),
                _.fold(p("Source support unlocated"))(pieces)
              ),
            h3("All supplied references to this source"),
            references
          )
        }
      )
    })

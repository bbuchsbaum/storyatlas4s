package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
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
    val history = Var(WorkspaceHistory.open(initial))
    val restorations = new EventBus[Unit]
    val controller = history.signal.map(_.current)
    val notice = Var("")
    val narrowSurface = Var("list")
    val measure = Var(
      if initial.policy.matrix.rows.exists(_.cells.exists(_.normalized.nonEmpty))
      then WorkspaceLabels.Measure.Normalized
      else WorkspaceLabels.Measure.All
    )
    val displayed = controller.combineWith(measure.signal).map { (c, m) =>
      (c, m, MatrixCells.fill(c.policy.matrix))
    }
    val workspace = initial.workspace
    def dispatch(action: WorkspaceAction): Unit = history.now().dispatch(action) match
      case Left(reason) => notice.set(s"Action refused: $reason")
      case Right(next)  => history.set(next); notice.set("")
    def navigate(forward: Boolean): Unit =
      history.update(h => if forward then h.forward else h.back)
      restorations.writer.onNext(())
    def exportEvidence(): Unit = WorkspaceExport.create(history.now().current) match
      case Left(reason)   => notice.set(s"Export refused: $reason")
      case Right(content) =>
        WorkspaceHost.download("storyatlas-evidence.json", content)
        notice.set("Evidence export prepared.")
    val source = AppView.imported(
      workspace.draft,
      DomMeasurer.canvas(),
      controller.map(_.sourceChoice).distinct,
      () => history.now().current.sourceChoice,
      choice => dispatch(WorkspaceAction.SourcePresentation(choice)),
      (address, extend) =>
        history
          .now()
          .current
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
    def exactRecall(c: WorkspaceController, unit: RecallUnitId): Vector[HtmlElement] =
      c.recallEvidence(unit) match
        case Left(reason) => Vector(span(cls("evidence-absence"), s"Evidence unavailable: $reason"))
        case Right(pieces) if pieces.isEmpty =>
          Vector(span(cls("evidence-absence"), "No complete fragment at this horizon"))
        case Right(pieces) => pieces.map((_, text) => span(cls("recall-fragment"), text))

    val matrix = WorkspaceScroll.bind(
      div(
        idAttr("workspace-matrix-scroll"),
        cls("mapping-scroll"),
        role("region"),
        aria.label("Recall by supplied target cut; scroll for all destinations"),
        table(
          cls("mapping-matrix"),
          caption(
            child.text <-- displayed.map { (c, m, _) =>
              if m == WorkspaceLabels.Measure.Normalized then
                MatrixCells.fillLegend(c.policy.matrix) + " Blank measure: Not supplied."
              else s"${m.label} · exact supplied values; incomparable quantities stay separate."
            }
          ),
          thead(
            tr(
              th(cls("recall-heading"), "Recall", span("in transcript order")),
              destinations.map { destination =>
                th(
                  cls(
                    if destination.isInstanceOf[Destination.External] then "external-column"
                    else "target-column"
                  ),
                  title(destination.key),
                  WorkspaceLabels.destination(destination)
                )
              },
              th(cls("processing-column"), "Processing")
            )
          ),
          tbody(workspace.inventory.units.zipWithIndex.map { (unit, rowIndex) =>
            tr(
              dataAttr("recall-unit")(unit.id.value),
              dataAttr("row-selected") <-- controller.map(
                _.state.activeRecall.contains(unit.id).toString
              ),
              dataAttr("row-processing") <-- controller.map(c =>
                c.policy.matrix
                  .row(unit.id)
                  .fold("Unknown")(r => WorkspaceLabels.processing(r.outcome.processing))
              ),
              th(
                cls("recall-heading"),
                button(
                  typ("button"),
                  cls("recall-row-button"),
                  span(cls("recall-ordinal"), s"R${unit.ordinal + 1}"),
                  span(cls("recall-exact"), children <-- controller.map(exactRecall(_, unit.id))),
                  title(unit.id.value),
                  onClick --> (_ => dispatch(WorkspaceAction.Jump(unit.id))),
                  aria.pressed <-- controller.map(_.state.activeRecall.contains(unit.id).toString)
                )
              ),
              destinations.zipWithIndex.map { (destination, columnIndex) =>
                td(
                  cls(
                    if destination.isInstanceOf[Destination.External] then "external-column"
                    else "target-column"
                  ),
                  button(
                    typ("button"),
                    cls <-- displayed.map { (c, m, scale) =>
                      val cell = c.policy.matrix.row(unit.id).flatMap(_.cell(destination))
                      val fill = c.policy.matrix
                        .row(unit.id)
                        .flatMap(_.outcome.decision)
                        .exists(_.origin match
                          case DecisionOrigin.GapFill(_) => true
                          case _                         => false)
                      s"matrix-cell ${cell.fold("")(WorkspaceLabels.tone(scale, _, m))}${
                          if fill then " fill-origin" else ""
                        }"
                    },
                    dataAttr("matrix-cell")(s"$rowIndex-$columnIndex"),
                    aria.label <-- controller.map { c =>
                      val cell = c.policy.matrix.row(unit.id).flatMap(_.cell(destination))
                      s"Inspect recall ${unit.ordinal + 1}, ${WorkspaceLabels.destination(destination)}; ${WorkspaceLabels.spoken(cell)}"
                    },
                    tabIndex <-- controller.map { c =>
                      val activeRow =
                        c.state.activeRecall.getOrElse(workspace.inventory.units.head.id)
                      val activeColumn = c.state.correspondence
                        .map(x => Destination.Target(x.target))
                        .getOrElse(destinations.head)
                      if activeRow == unit.id && activeColumn == destination then 0 else -1
                    },
                    aria.pressed <-- controller.map(c =>
                      (c.state.activeRecall.contains(unit.id) && (destination match
                        case Destination.Target(target) =>
                          c.state.correspondence.contains(Correspondence(unit.id, target))
                        case Destination.External(_) => false)).toString
                    ),
                    children <-- displayed.map { (c, m, _) =>
                      c.policy.matrix.row(unit.id).flatMap(_.cell(destination)) match
                        case None       => Vector(span(cls("measure-absent"), "Not supplied"))
                        case Some(cell) =>
                          WorkspaceLabels.values(cell, m).map { (kind, value) =>
                            div(
                              cls(
                                if value == "Not supplied" then "measure-absent" else "cell-measure"
                              ),
                              span(
                                cls(
                                  if m == WorkspaceLabels.Measure.Normalized then "visually-hidden"
                                  else "measure-kind"
                                ),
                                kind
                              ),
                              strong(value)
                            )
                          } ++ Option
                            .when(cell.chosen)(
                              span(
                                cls("decision-mark"),
                                title("Supplied decision"),
                                span(cls("visually-hidden"), "Decision")
                              )
                            )
                            .toVector
                    },
                    onClick --> (_ => activate(unit.id, destination)),
                    onKeyDown --> (event => move(event, rowIndex, columnIndex))
                  )
                )
              },
              td(
                cls("processing-column"),
                child.text <-- controller.map(c =>
                  c.policy.matrix
                    .row(unit.id)
                    .fold("Not supplied")(r => WorkspaceLabels.processing(r.outcome.processing))
                )
              )
            )
          })
        )
      ),
      false,
      () => history.now().current,
      controller,
      dispatch,
      restorations.events
    )
    val sourcePane = WorkspaceScroll.bind(
      sectionTag(
        idAttr("workspace-source-scroll"),
        cls("joined-source"),
        h2("Source reading"),
        source
      ),
      true,
      () => history.now().current,
      controller,
      dispatch,
      restorations.events
    )
    val compactList = ol(
      cls("compact-recall-list"),
      aria.label("Recall units in transcript order"),
      workspace.inventory.units.map { unit =>
        li(
          dataAttr("row-selected") <-- controller.map(
            _.state.activeRecall.contains(unit.id).toString
          ),
          button(
            typ("button"),
            cls("compact-recall-button"),
            span(cls("recall-ordinal"), s"R${unit.ordinal + 1}"),
            span(cls("recall-exact"), children <-- controller.map(exactRecall(_, unit.id))),
            onClick --> { _ =>
              dispatch(WorkspaceAction.Jump(unit.id))
              narrowSurface.set("evidence")
              val _ = dom.window.requestAnimationFrame(_ =>
                Option(dom.document.getElementById("workspace-inspector-title"))
                  .foreach(_.asInstanceOf[dom.html.Element].focus())
              )
            }
          ),
          p(
            cls("compact-outcome"),
            child.text <-- controller.map(c =>
              c.policy.matrix.row(unit.id).fold("Outcome not supplied")(row => s"${WorkspaceLabels.processing(row.outcome.processing)} · ${row.outcome.decision.fold("No decision supplied")(d => d.chosen.fold("Abstain")(WorkspaceLabels.destination))}")
            )
          )
        )
      }
    )
    div(
      cls("joined-workspace design-workspace"),
      dataAttr("workspace-ready")("true"),
      dataAttr("mode") <-- controller.map(_.state.mode.toString.toLowerCase),
      dataAttr("narrow-surface") <-- narrowSurface.signal,
      dataAttr("policy") <-- controller.map(_.state.policy.value),
      dataAttr("active-recall") <-- controller.map(_.state.activeRecall.fold("")(_.value)),
      dataAttr("selection") <-- controller.map(
        _.state.selection.toVector.sorted.map(_.render).mkString(" ")
      ),
      headerTag(
        cls("investigation-bar"),
        div(
          cls("investigation-title"),
          h1("Story + recall"),
          span(s"${workspace.inventory.units.size} recall units · checked investigation")
        ),
        div(
          cls("task-switch"),
          role("group"),
          aria.label("Task"),
          Vector(
            WorkspaceMode.Recall -> "Story + recall",
            WorkspaceMode.Source -> "Source reading",
            WorkspaceMode.Voyage -> "Time Voyage"
          ).map { (mode, label) =>
            button(
              typ("button"),
              label,
              aria.pressed <-- controller.map(c => (c.state.mode == mode).toString),
              onClick --> (_ => dispatch(WorkspaceAction.Mode(mode)))
            )
          }
        ),
        div(
          cls("workspace-actions"),
          button(
            typ("button"),
            idAttr("workspace-back"),
            "Back",
            disabled <-- history.signal.map(!_.canBack),
            onClick --> (_ => navigate(false))
          ),
          button(
            typ("button"),
            idAttr("workspace-return"),
            "Return",
            disabled <-- history.signal.map(!_.canReturn),
            onClick --> (_ => navigate(true))
          ),
          button(
            typ("button"),
            idAttr("workspace-save"),
            "Save view",
            onClick --> (_ =>
              WorkspaceHost
                .download("investigation.json", WorkspaceSave.encode(history.now().current))
            )
          ),
          button(
            typ("button"),
            idAttr("workspace-export"),
            cls("primary-action"),
            "Export evidence",
            onClick --> (_ => exportEvidence())
          )
        )
      ),
      div(
        cls("view-controls"),
        label(
          "Display ",
          select(
            idAttr("workspace-measure"),
            WorkspaceLabels.Measure.values.toVector.map(m => option(value(m.toString), m.label)),
            controlled(
              value <-- measure.signal.map(_.toString),
              onChange.mapToValue --> (v =>
                WorkspaceLabels.Measure.values.find(_.toString == v).foreach(measure.set)
              )
            )
          )
        ),
        label(
          "Result ",
          select(
            idAttr("workspace-policy"),
            workspace.policies.map(p => option(value(p.id.value), p.id.value)),
            controlled(
              value <-- controller.map(_.state.policy.value),
              onChange.mapToValue --> (v =>
                workspace.policies
                  .find(_.id.value == v)
                  .foreach(p => dispatch(WorkspaceAction.Policy(p.id)))
              )
            )
          )
        ),
        span(
          cls("view-scope"),
          s"${initial.policy.matrix.targets.size} source targets · supplied cut · equal widths"
        ),
        span(cls("view-scope"), "Decisions shown · route off")
      ),
      p(cls("workspace-notice"), role("status"), aria.live("polite"), child.text <-- notice.signal),
      detailsTag(
        cls("inspection-controls"),
        summaryTag("Recall navigation and evidence horizon"),
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
                value <-- controller.map(_.state.activeRecall.fold("")(_.value)),
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
              checked <-- controller.map(_.state.recallHorizon == EpistemicHorizon.Omniscient),
              onChange.mapToChecked --> (all =>
                dispatch(
                  WorkspaceAction.RecallHorizon(
                    if all then EpistemicHorizon.Omniscient
                    else
                      EpistemicHorizon.ReaderAt(
                        Playhead.position(
                          workspace.recall.transcript.canonicalText,
                          history.now().current.state.recallHorizon
                        )
                      )
                  )
                )
              )
            )
          ),
          span(
            child.text <-- controller.map(c =>
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
                value <-- controller.map(c =>
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
        )
      ),
      div(
        cls("narrow-switch"),
        role("group"),
        aria.label("Presentation"),
        Vector("list" -> "List", "map" -> "Map", "evidence" -> "Evidence").map { (id, label) =>
          button(
            typ("button"),
            label,
            aria.pressed <-- narrowSurface.signal.map(v => (v == id).toString),
            onClick --> (_ => narrowSurface.set(id))
          )
        }
      ),
      div(
        cls("investigation-body"),
        mainTag(
          cls("investigation-main"),
          sectionTag(
            cls("matrix-pane"),
            h2("Recall correspondence"),
            matrix,
            compactList,
            p(
              cls("matrix-key"),
              span(cls("legend-decision")),
              "Supplied decision",
              span(cls("legend-fill")),
              "Gap fill",
              " · Selection uses an outline; values stay unchanged."
            )
          ),
          sourcePane,
          sectionTag(
            idAttr("workspace-voyage"),
            h2("Time Voyage"),
            child <-- controller.map(_.state.policy).distinct.map { id =>
              WorkspaceVoyage.from(workspace, id) match
                case Left(reason)      => p(s"Voyage unavailable: $reason")
                case Right(projection) =>
                  VoyageView
                    .controlled(projection, controller, () => history.now().current, dispatch)
            }
          )
        ),
        asideTag(
          cls("joined-recall workspace-inspector"),
          idAttr("workspace-inspector"),
          h2(idAttr("workspace-inspector-title"), tabIndex(-1), "Evidence inspector"),
          div(
            cls("inspector-navigation"),
            button(
              typ("button"),
              "Previous recall",
              disabled(workspace.inventory.units.isEmpty),
              onClick --> (_ => dispatch(WorkspaceAction.Walk(-1)))
            ),
            button(
              typ("button"),
              "Next recall",
              disabled(workspace.inventory.units.isEmpty),
              onClick --> (_ => dispatch(WorkspaceAction.Walk(1)))
            )
          ),
          WorkspaceInspector(controller, dispatch),
          button(
            typ("button"),
            cls("read-source-action"),
            "Read selected source",
            disabled <-- controller.map(_.state.correspondence.isEmpty),
            onClick --> (_ => dispatch(WorkspaceAction.Mode(WorkspaceMode.Source)))
          )
        )
      ),
      footerTag(
        cls("investigation-status"),
        span(
          child.text <-- controller.map(c =>
            c.state.activeRecall.fold("No recall selected")(u => s"Selected ${u.value}")
          )
        ),
        span(
          child.text <-- controller.map(c =>
            c.state.correspondence.fold("No source alternative in focus")(r =>
              s"Focus ${r.target.key}"
            )
          )
        ),
        button(typ("button"), "Clear selection", onClick --> (_ => dispatch(WorkspaceAction.Clear)))
      ),
      detailsTag(
        cls("workspace-receipts"),
        summaryTag("Artifact identities, horizons and saved view state"),
        p(s"${workspace.origin} · imported draft source"),
        p(s"Source SHA-256: ${workspace.modelArtifact.hex}"),
        p(s"Recall SHA-256: ${workspace.recallArtifact.hex}"),
        pre(idAttr("workspace-state"), child.text <-- controller.map(WorkspaceSave.encode))
      )
    )

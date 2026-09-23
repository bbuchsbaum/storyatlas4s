package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import storyatlas4s.intaglio.MatrixLowering
import storyatlas4s.shell.*
import storymodel4s.align.*
import storymodel4s.core.*

/** Exact permitted fragments and supplied results from the shared controller. */
private[app] object WorkspaceInspector:
  def apply(
      signal: Signal[WorkspaceController],
      dispatch: WorkspaceAction => Unit
  ): HtmlElement =
    def activate(attribute: String, key: String, action: WorkspaceAction): Unit =
      val active = Option(dom.document.activeElement).exists(_.getAttribute(attribute) == key)
      dispatch(action)
      if active then
        val _ = dom.window.requestAnimationFrame { _ =>
          val controls = dom.document.querySelectorAll(s"[$attribute]")
          Vector
            .tabulate(controls.length)(i => controls(i).asInstanceOf[dom.html.Element])
            .find(_.getAttribute(attribute) == key)
            .foreach(_.focus())
        }
    div(child <-- signal.map { c =>
      def pieces(values: Vector[(SpanRef, String)]): HtmlElement =
        if values.isEmpty then p("No complete evidence fragment is visible at this horizon.")
        else
          div(
            values.map((ref, text) =>
              blockQuote(
                p(cls("exact-evidence"), text),
                small(s"Exact support: ${ref.span.start}–${ref.span.endExclusive}")
              )
            )
          )
      val target = c.state.correspondence
        .map(_.target)
        .orElse(c.state.focus.flatMap(c.workspace.sourceAddresses.get))
      div(
        cls("inspection-content"),
        c.state.activeRecall.toVector.map { unit =>
          val row = c.policy.matrix.row(unit).get
          div(
            h3(cls("inspector-subject"), s"Recall ${row.unit.ordinal + 1}"),
            p(
              cls("inspection-status"),
              s"${WorkspaceLabels.processing(row.outcome.processing)} · ${c.workspace.timing(unit)}"
            ),
            c.recallEvidence(unit).fold(reason => p(s"Evidence unavailable: $reason"), pieces),
            h3("Supplied alternatives"),
            p(cls("inspection-caption"), "Each supplied measure is named separately. No values are renormalized."),
            ul(cls("alternative-list"), row.cells.filter(cell =>
              cell.links.nonEmpty || cell.raw.nonEmpty || cell.normalized.nonEmpty ||
                cell.transport.nonEmpty || cell.posterior.nonEmpty || cell.chosen
            ).map { cell =>
              li(
                cls("alternative-row"),
                dataAttr("candidate-focused")((cell.destination match
                  case Destination.Target(ref) => c.state.correspondence.contains(Correspondence(unit, ref))
                  case Destination.External(_) => false).toString),
                button(
                  typ("button"),
                  cls("alternative-target"),
                  WorkspaceLabels.destination(cell.destination),
                  dataAttr("inspect-destination")(cell.destination.key),
                  onClick --> (_ =>
                    cell.destination match
                      case Destination.Target(ref) =>
                        activate(
                          "data-inspect-destination",
                          cell.destination.key,
                          WorkspaceAction.Inspect(unit, ref)
                        )
                      case Destination.External(_) =>
                        activate(
                          "data-inspect-destination",
                          cell.destination.key,
                          WorkspaceAction.Jump(unit)
                        )
                  )
                ),
                Option.when(cell.chosen)(span(cls("candidate-decision"), "Decision")),
                div(cls("candidate-measures"), MatrixLowering.values(cell).map { (kind, value) =>
                  div(cls("candidate-measure"), span(kind), strong(value))
                }),
                detailsTag(
                  summaryTag("Support and fidelity"),
                  dataAttr("inspection-metadata")("true"),
                  if cell.links.isEmpty then Vector(p("Link support: not supplied"))
                  else
                    cell.links.map { link =>
                      val support = link.termSupport match
                        case absent: TermSupportStatus.NotComputed =>
                          s"Not computed (${absent.reason})"
                        case evaluated: TermSupportStatus.Evaluated => evaluated.assessment.toString
                      div(
                        p(s"Term support: $support"),
                        p(
                          s"Candidate set: ${link.candidateSet.digest.hex}; inference stage: ${link.inferenceStage.digest.hex}"
                        ),
                        p(link.gate match
                          case GateOutcome.NotGated => "Contradiction gate: not evaluated"
                          case _: GateOutcome.NoContradictionDetected =>
                            "Contradiction gate: no contradiction detected"
                          case contradicted: GateOutcome.Contradicted =>
                            s"Contradiction gate: ${contradicted.facets}"),
                        p(link.fidelity match
                          case absent: FidelityStatus.NotAssessed =>
                            s"Fidelity: not assessed (${absent.reason})"
                          case FidelityStatus.NotApplicable      => "Fidelity: not applicable"
                          case assessed: FidelityStatus.Assessed => s"Fidelity: ${assessed.report}")
                      )
                    }
                )
              )
            }),
            p(
              cls("decision-summary"),
              row.outcome.decision.fold("Decision not supplied")(d =>
                s"Decision: ${d.chosen.fold("abstain")(WorkspaceLabels.destination)} · ${WorkspaceLabels.origin(d.origin)}"
              )
            ),
            detailsTag(
              cls("inspection-provenance"),
              summaryTag("Processing, policy, coverage and calibration"),
              dataAttr("inspection-metadata")("true"),
              p(s"Processing: ${row.outcome.processing}"),
              p(s"Localization: ${row.outcome.localization}"),
              p(row.outcome.decision.fold("Decision basis: not supplied")(d => s"Decision basis: ${d.basis.kind}")),
              p(s"Inference policy: ${c.policy.record.policies.inference}"),
              p(s"Candidate coverage: ${c.policy.record.policies.candidate}"),
              p(c.policy.record.policies.candidate match
                case CandidatePolicy.Declared(_, CandidateCoverage.Complete) =>
                  "The producer declares complete candidate coverage."
                case _ => "Omitted-candidate probability: unknown; this is not known hidden mass."),
              p(s"Fixed target universe: ${c.policy.record.policies.universe.id.digest.hex}"),
              row.outcome.measures.normalized.fold(p("Normalized score mass: not supplied"))(m =>
                p(
                  s"Normalization universe: ${m.universe.digest.hex}; prior: ${m.prior}; temperature: ${m.temperature}; stage: ${m.stage.digest.hex}"
                )
              ),
              row.outcome.measures.transport.fold(p("Transport row budget: not supplied"))(m =>
                p(s"Transport row budget: ${m.rowBudget}; stage: ${m.stage.digest.hex}")
              ),
              p(
                row.outcome.decision.fold("Calibration: no decision supplied")(d =>
                  d.calibration match
                    case unavailable: DecisionCalibration.Unavailable =>
                      s"Calibrated correctness probability: unavailable (${unavailable.reason})"
                    case calibrated: DecisionCalibration.Calibrated =>
                      s"Calibrated correctness probability: ${calibrated.probability.probability.value}; artifact: ${calibrated.probability.artifact}"
                )
              )
            )
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
                    dataAttr("inverse-unit")(unit.value),
                    onClick --> (_ =>
                      activate("data-inverse-unit", unit.value, WorkspaceAction.Inspect(unit, ref))
                    )
                  )
                )
              })
          div(
            h3("Source evidence"),
            p(cls("inspection-caption"), ref.key),
            c.sourceEvidence(ref)
              .fold(
                reason => p(s"Evidence unavailable: $reason"),
                _.fold(p("Source support unlocated"))(pieces)
              ),
            h3("All supplied recall references"),
            p(cls("inspection-caption"), "Transcript order; this list is not a distribution over recall units."),
            references
          )
        }
      )
    })

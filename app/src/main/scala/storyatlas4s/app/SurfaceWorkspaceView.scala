package storyatlas4s.app

import com.raquo.laminar.api.L.*
import storyatlas4s.shell.*
import org.scalajs.dom

/** Imported claims stay inspectable alongside their exact source and recall evidence. */
object SurfaceWorkspaceView:
  def apply(initial: SurfaceWorkspaceController): HtmlElement =
    val controller = Var(initial)
    val notice = Var("")
    val temporalPage = Var(0)
    val temporalColumnPage = Var(0)
    def dispatch(action: SurfaceWorkspaceAction): Unit = controller.now().dispatch(action) match
      case Left(_)     => notice.set("This selection is unavailable in the opened investigation.")
      case Right(next) =>
        val focusId = Option(dom.document.activeElement).map(_.id).filter(_.nonEmpty)
        controller.set(next)
        notice.set("")
        val _ = dom.window.requestAnimationFrame { _ =>
          if dom.document.activeElement == dom.document.body then
            focusId.flatMap(id => Option(dom.document.getElementById(id))).foreach {
              case element: dom.html.Element => element.focus()
              case _                         => ()
            }
        }
    div(
      idAttr("surface-workspace"),
      cls("joined-workspace"),
      headerTag(
        cls("investigation-bar"),
        div(
          cls("investigation-title"),
          h1("Source + recall"),
          span(s"${initial.investigation.inventory.units.size} recall units · imported results")
        ),
        div(
          cls("workspace-actions"),
          button(
            typ("button"),
            "Save view",
            onClick --> { _ =>
              SurfaceWorkspaceController.encode(controller.now()) match
                case Left(_)      => notice.set("This packet does not permit saving this view.")
                case Right(bytes) =>
                  WorkspaceHost.download("storyatlas-view.json", bytes)
                  notice.set(
                    "View saved. Open it with the original investigation to restore selections, clocks and regions."
                  )
            }
          ),
          button(
            typ("button"),
            "Clear selection",
            onClick --> (_ => dispatch(SurfaceWorkspaceAction.Clear))
          )
        )
      ),
      p(
        "Inspect the supplied results and their evidence. Importing a result does not rerun or validate its inference."
      ),
      p(cls("workspace-notice"), role("status"), aria.live("polite"), child.text <-- notice.signal),
      div(
        cls("task-switch"),
        role("group"),
        aria.label("Mapping policy"),
        child <-- controller.signal.map { value =>
          div(value.investigation.policies.map { policy =>
            button(
              typ("button"),
              policy.id.value.capitalize,
              aria.pressed := (policy.id == value.state.policy).toString,
              onClick --> (_ => dispatch(SurfaceWorkspaceAction.Policy(policy.id)))
            )
          })
        }
      ),
      child <-- controller.signal.map(c =>
        TemporalWorkspaceView(c, dispatch, notice, temporalPage, temporalColumnPage)
      ),
      div(
        cls("joined-columns"),
        sectionTag(
          cls("joined-source"),
          h2("Source"),
          child <-- controller.signal.map { value =>
            ol(value.investigation.source.targets.map { target =>
              li(
                button(
                  typ("button"),
                  idAttr(s"surface-source-${target.ref.key}"),
                  value.investigation.sourceText(target.ref).getOrElse("Source text unavailable"),
                  aria.pressed := value.state.source.contains(target.ref).toString,
                  onClick --> (_ => dispatch(SurfaceWorkspaceAction.Source(target.ref)))
                )
              )
            })
          },
          p(
            initial.investigation.narrativeModel.fold(
              "Text inspection is available. A narrative model is needed for the graph view."
            )(_ =>
              "This view shows source text; the supplied narrative model is retained in the investigation."
            )
          )
        ),
        sectionTag(
          cls("joined-recall"),
          h2("Recall"),
          child <-- controller.signal.map { value =>
            ol(value.investigation.inventory.units.map { unit =>
              li(
                button(
                  typ("button"),
                  idAttr(s"surface-recall-${unit.id.value}"),
                  value.investigation.recallText(unit.id).getOrElse("Recall text unavailable"),
                  aria.pressed := value.state.recall.contains(unit.id).toString,
                  onClick --> (_ => dispatch(SurfaceWorkspaceAction.Recall(unit.id)))
                )
              )
            })
          }
        )
      ),
      sectionTag(
        cls("workspace-inspector"),
        h2("Mapping alternatives"),
        child <-- controller.signal.map { value =>
          val outcomes = value.policy.outcomes.filter(o =>
            value.state.recall.forall(id => o.hcursor.get[String]("unit").contains(id.value))
          )
          div(outcomes.map { outcome =>
            val cursor = outcome.hcursor
            val unit = cursor.get[String]("unit").toOption.get
            val label = value.investigation.inventory.units
              .find(_.id.value == unit)
              .flatMap(u => value.investigation.recallText(u.id))
              .getOrElse("Recall unit")
            val rows = cursor
              .get[Vector[io.circe.Json]]("mapping_links")
              .toOption
              .getOrElse(Vector.empty)
              .filterNot(_.hcursor.get[String]("measure_kind").contains("RawScore"))
            div(
              h3(label),
              if rows.isEmpty then p("No mapping mass was supplied for this unit.")
              else
                table(
                  thead(tr(th("Destination"), th("Measure"), th("Mass"))),
                  tbody(rows.map { row =>
                    val c = row.hcursor
                    val destination = c.get[String]("destination").toOption.get
                    val target = value.investigation.source.targets.find(_.ref.key == destination)
                    val description = target
                      .flatMap(t => value.investigation.sourceText(t.ref))
                      .getOrElse(destination.stripPrefix("ext:"))
                    val kind = c.get[String]("measure_kind").toOption.get
                    val bits = c.get[String]("raw_value").toOption.get.stripPrefix("0x")
                    val mass =
                      java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(bits, 16))
                    tr(
                      td(description),
                      td(
                        if kind == "ModelPosterior" then "Model posterior"
                        else "Normalized score mass"
                      ),
                      td(if mass == 0.0 then "0" else f"$mass%.4g")
                    )
                  })
                )
            )
          })
        }
      ),
      detailsTag(
        cls("workspace-receipts"),
        summaryTag("Original record and artifact identities"),
        p(
          child.text <-- controller.signal.map(c =>
            s"Record bytes SHA-256: ${c.policy.byteDigest.hex}"
          )
        ),
        pre(child.text <-- controller.signal.map(_.policy.bytes))
      )
    )

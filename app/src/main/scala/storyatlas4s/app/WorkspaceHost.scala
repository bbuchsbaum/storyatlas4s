package storyatlas4s.app

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Int8Array}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import storyatlas4s.edition.{ArtifactInput, ImportedSource}
import storyatlas4s.layout.DomMeasurer
import storyatlas4s.shell.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.view.WorkspaceRefusal

/** Browser-owned local file I/O. Canonical admission and replay finish before any replacement. */
object WorkspaceHost:
  /** Directory picks retain sidecar paths relative to the selected root. File picks stay literal;
    * no basename search can turn one sidecar into another same-named file.
    */
  private def relativePath(file: dom.File): String =
    val relative = file
      .asInstanceOf[js.Dynamic]
      .selectDynamic("webkitRelativePath")
      .asInstanceOf[js.UndefOr[String]]
      .toOption
      .filter(_.nonEmpty)
    relative.fold(file.name)(_.split('/').drop(1).mkString("/"))

  private def read(file: dom.File): Future[(String, Vector[Byte])] =
    val promise = Promise[(String, Vector[Byte])]()
    val reader = new dom.FileReader()
    reader.onload = _ =>
      val bytes = new Int8Array(reader.result.asInstanceOf[ArrayBuffer])
      val _ = promise.trySuccess(
        relativePath(file) -> Vector.tabulate(bytes.length)(i => bytes(i).toByte)
      )
    reader.onerror = _ =>
      val _ = promise.tryFailure(new IllegalArgumentException("Local file read failed"))
    reader.onabort = _ =>
      val _ = promise.tryFailure(new IllegalArgumentException("Local file read cancelled"))
    reader.readAsArrayBuffer(file)
    promise.future

  private[app] def download(name: String, content: String): Unit =
    val blob = new dom.Blob(
      js.Array(content),
      new dom.BlobPropertyBag { `type` = "application/json;charset=utf-8" }
    )
    val url = dom.URL.createObjectURL(blob)
    val anchor = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    anchor.href = url
    anchor.download = name
    val _ = dom.document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    val _ = dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 1000)

  def apply(): HtmlElement =
    val snapshot = Var(OpenSnapshot[WorkspaceSession](None, OpenDisplay.Idle))
    val runtime = new WorkspaceOpen[WorkspaceSession](snapshot.set)
    var sourceReading: Option[() => (ImportedSource, ViewChoice, Int)] = None
    def current: Option[WorkspaceController] = snapshot.now().current.collect {
      case WorkspaceSession.Investigation(controller) => controller
    }
    def open(files: Vector[dom.File], attach: Boolean = false): Unit =
      val reading = if attach && snapshot.now().current.exists {
          case WorkspaceSession.Source(_) => true
          case _                          => false
        }
      then sourceReading.map(_())
      else None
      val revision = runtime.begin()
      val previous = current
      if files.isEmpty then runtime.cancel()
      else if files.size > ArtifactInput.MaxFiles || files.map(_.size).sum > ArtifactInput.MaxBytes
      then runtime.complete(revision, Left(WorkspaceRefusal.UnsupportedContent))
      else
        val _ = Future
          .traverse(files)(read)
          .map { loaded =>
            val admitted = WorkspaceImport.open(loaded, previous).flatMap { session =>
              if !attach then Right(session)
              else
                reading.toRight(WorkspaceRefusal.SemanticJoinMismatch).flatMap {
                  (source, choice, offset) =>
                    WorkspaceImport.attachSource(source, choice, offset, session)
                }
            }
            runtime.complete(revision, admitted)
          }
          // Errors are intentionally content-free; failed bytes never enter diagnostics or the DOM.
          .recover { case _ =>
            runtime.complete(revision, Left(WorkspaceRefusal.UnsupportedContent))
          }
    div(
      cls("workspace-host"),
      div(
        cls("workspace-open-bar"),
        strong("StoryAtlas"),
        label(
          cls("open-files"),
          display <-- snapshot.signal.map(s =>
            if s.current.exists {
                case WorkspaceSession.Source(_) => true
                case _                          => false
              }
            then ""
            else "none"
          ),
          "Add recall workspace ",
          input(
            idAttr("workspace-attach"),
            typ("file"),
            onChange --> { event =>
              val element = event.target.asInstanceOf[dom.html.Input]
              val files =
                Option(element.files).toVector.flatMap(f => Vector.tabulate(f.length)(f(_)))
              open(files, attach = true)
              element.value = ""
            }
          )
        ),
        label(
          cls("open-files"),
          "Open local artifacts ",
          input(
            idAttr("workspace-open"),
            typ("file"),
            multiple(true),
            onChange --> { event =>
              val element = event.target.asInstanceOf[dom.html.Input]
              val files =
                Option(element.files).toVector.flatMap(f => Vector.tabulate(f.length)(f(_)))
              open(files)
              element.value = ""
            }
          )
        ),
        label(
          cls("open-files"),
          "Open source directory ",
          input(
            idAttr("workspace-open-directory"),
            typ("file"),
            multiple(true),
            onMountCallback(ctx => ctx.thisNode.ref.setAttribute("webkitdirectory", "")),
            onChange --> { event =>
              val element = event.target.asInstanceOf[dom.html.Input]
              val files =
                Option(element.files).toVector.flatMap(f => Vector.tabulate(f.length)(f(_)))
              open(files)
              element.value = ""
            }
          )
        ),
        button(
          typ("button"),
          "Cancel opening",
          onClick --> (_ => runtime.cancel()),
          disabled <-- snapshot.signal.map(_.display != OpenDisplay.Checking)
        ),
        span(
          role("status"),
          aria.live("polite"),
          dataAttr("open-status") <-- snapshot.signal.map(_.display.toString),
          child.text <-- snapshot.signal.map(_.display match
            case OpenDisplay.Idle =>
              "Open a workspace packet, source model, Voyage document, or saved investigation with its artifacts."
            case OpenDisplay.Checking        => "Checking identities and permissions…"
            case OpenDisplay.Cancelled       => "Opening cancelled."
            case OpenDisplay.Refused(reason) => s"Opening refused: ${reason.toString}."
            case OpenDisplay.Opened          => "Artifacts checked and opened.")
        )
      ),
      child <-- snapshot.signal.map(_.current).distinct.map {
        case None          => AppView(WarOfTheGhostsModel.model, DomMeasurer.canvas())
        case Some(session) =>
          Option(dom.document.getElementById("fixture-header"))
            .foreach(_.setAttribute("hidden", ""))
          session match
            case WorkspaceSession.Investigation(controller) => WorkspaceView(controller)
            case WorkspaceSession.Voyage(document)          => VoyageView(document)
            case WorkspaceSession.Source(source)            =>
              val choice = Var(ViewChoice.initial.copy(measurer = MeasurerChoice.Monospace))
              sourceReading = Some(() => {
                val pane = dom.document.getElementById("workspace-source-only")
                val offset = Option(pane)
                  .flatMap { element =>
                    val bounds = element.getBoundingClientRect()
                    val lines = element.querySelectorAll("[data-source-offset]")
                    (0 until lines.length).iterator
                      .map(i => lines(i).asInstanceOf[dom.Element])
                      .find(_.getBoundingClientRect().bottom > bounds.top)
                      .flatMap(_.getAttribute("data-source-offset").toIntOption)
                  }
                  .getOrElse(0)
                (source, choice.now(), offset)
              })
              div(
                idAttr("workspace-source-only"),
                cls("joined-source"),
                p("Imported source · draft authority · recall and mapping not supplied"),
                p(
                  idAttr("source-features"),
                  dataAttr("track-count")(
                    source.features.trackCount.fold("not-supplied")(_.toString)
                  ),
                  s"Measured features: ${source.features.render}"
                ),
                ul(source.features.spaces.map(space => li(cls("source-feature-space"), space))),
                AppView.imported(
                  source.draftModel,
                  DomMeasurer.canvas(),
                  choice.signal,
                  () => choice.now(),
                  choice.set,
                  (address, extend) => choice.update(_.activate(address, extend)),
                  () => choice.update(_.cleared)
                )
              )
      }
    )

package storyatlas4s.shell

import java.nio.charset.StandardCharsets.UTF_8
import io.circe.Json
import storyatlas4s.intaglio.MatrixLowering
import storymodel4s.codec.{Canonical, WorkspaceSubsetCodec}
import storymodel4s.core.Checksum
import storymodel4s.view.WorkspaceRefusal

/** Local export orchestration. The producer supplies the scientific subset and grants; the shell
  * supplies the figure and presentation descriptor. No displayed crop changes scientific values.
  */
object WorkspaceExport:
  val Version = "storyatlas-evidence-export/v1"

  def create(controller: WorkspaceController): Either[WorkspaceRefusal, String] =
    val workspace = controller.workspace
    for
      subset <- WorkspaceSubsetCodec.selected(
        workspace,
        controller.state.policy,
        controller.state.selection
      )
      drawing <- MatrixLowering
        .lower(
          controller.policy.matrix,
          controller.state.selection.flatMap(workspace.recallAddresses.get)
        )
        .left
        .map(_ => WorkspaceRefusal.UnsupportedContent)
      svg <- Plate(
        drawing.scene,
        drawing.width,
        drawing.height,
        "Complete fixed-cut mapping matrix"
      ).svg.left.map(_ => WorkspaceRefusal.UnsupportedContent)
    yield
      val matrixText = controller.policy.matrix.rows
        .map { row =>
          val cells = row.cells.map(cell =>
            s"${cell.destination.key}: " + MatrixLowering
              .values(cell)
              .map((k, v) => s"$k: $v")
              .mkString("; ") +
              (if cell.chosen then "; Chosen destination" else "")
          )
          (Vector(
            s"${row.unit.ordinal + 1}. ${row.unit.id.value}; ${row.outcome.processing}; ${row.outcome.localization}"
          ) ++ cells).mkString("\n")
        }
        .mkString("\n\n")
      val files = Vector(
        "matrix.svg" -> svg,
        "matrix.txt" -> matrixText,
        "investigation.json" -> WorkspaceSave.encode(controller),
        "selection.json" -> subset.dataJson,
        "selection.csv" -> subset.tableCsv,
        "selection.txt" -> subset.accessibleText,
        "selection-receipt.json" -> subset.receiptJson
      )
      Canonical.print(
        Json.obj(
          "schemaVersion" -> Json.fromString(Version),
          "scope" -> Json.fromString(
            "Complete fixed-cut matrix with selection outlines; scientific subset contains full permitted evidence for selected and inverse rows. Presentation horizons are recorded in investigation.json and do not change scientific values."
          ),
          "files" -> Json.fromValues(files.map { (name, content) =>
            val bytes = content.getBytes(UTF_8)
            Json.obj(
              "name" -> Json.fromString(name),
              "checksum" -> Json.fromString(Checksum.ofBytes(bytes).hex),
              "byteLength" -> Json.fromInt(bytes.length),
              "content" -> Json.fromString(content)
            )
          })
        )
      )

package storyatlas4s.shell

import java.nio.charset.StandardCharsets.UTF_8
import io.circe.Json
import munit.FunSuite
import storyatlas4s.edition.WorkspaceTestData
import storymodel4s.codec.{Canonical, WorkspaceCodecs, WorkspaceSubsetCodec}
import storymodel4s.core.Checksum
import storymodel4s.view.WorkspaceRefusal

class WorkspaceExportSuite extends FunSuite:
  private def open(text: String) =
    WorkspaceController.open(WorkspaceCodecs.decode(text).toOption.get).toOption.get
  private lazy val selected =
    open(WorkspaceTestData.archives("bell")).dispatch(WorkspaceAction.Walk(1)).toOption.get
  private def contents(text: String): Map[String, String] =
    val entries = Canonical.parse(text).toOption.get.hcursor.get[Vector[Json]]("files").toOption.get
    entries.map { entry =>
      val c = entry.hcursor
      val body = c.get[String]("content").toOption.get
      val bytes = body.getBytes(UTF_8)
      assertEquals(c.get[String]("checksum").toOption.get, Checksum.ofBytes(bytes).hex)
      assertEquals(c.get[Int]("byteLength").toOption.get, bytes.length)
      c.get[String]("name").toOption.get -> body
    }.toMap

  test("export binds figure, accessible twin, replay state and untouched provider subset") {
    val exported = WorkspaceExport.create(selected).toOption.get
    val files = contents(exported)
    val provider = WorkspaceSubsetCodec
      .selected(selected.workspace, selected.state.policy, selected.state.selection)
      .toOption
      .get
    assertEquals(
      files.keySet,
      Set(
        "matrix.svg",
        "matrix.txt",
        "investigation.json",
        "selection.json",
        "selection.csv",
        "selection.txt",
        "selection-receipt.json"
      )
    )
    assertEquals(files("selection.json"), provider.dataJson)
    assertEquals(files("selection.csv"), provider.tableCsv)
    assertEquals(files("selection.txt"), provider.accessibleText)
    assertEquals(files("selection-receipt.json"), provider.receiptJson)
    assertEquals(
      WorkspaceSave.decode(files("investigation.json"), selected.workspace).toOption.get.state,
      selected.state
    )
    assert(files("matrix.svg").contains("<svg"))
    assert(files("matrix.txt").contains("4. m1:u3"))
    assert(files("matrix.txt").contains("Not supplied"))
    assertEquals(WorkspaceExport.create(selected).toOption.get, exported)
  }

  test("export permission is checked before constructing an evidence download") {
    val archive = Canonical.parse(WorkspaceTestData.archives("bell")).toOption.get
    val entries = archive.hcursor.get[Vector[Json]]("entries").toOption.get
    val entry =
      entries.find(_.hcursor.downField("role").get[String]("kind").contains("Capabilities")).get
    val path = entry.hcursor.get[String]("path").toOption.get
    val files = archive.hcursor.get[Vector[Json]]("files").toOption.get
    val file = files.find(_.hcursor.get[String]("path").contains(path)).get
    val capability = Canonical.parse(file.hcursor.get[String]("utf8").toOption.get).toOption.get
    val changed = Canonical.print(capability.mapObject(_.add("export", Json.fromString("Denied"))))
    val bytes = changed.getBytes(UTF_8)
    val disposition = entry.hcursor.get[Json]("disposition").toOption.get
    val artifact = disposition.hcursor
      .get[Json]("artifact")
      .toOption
      .get
      .mapObject(
        _.add("checksum", Json.fromString(Checksum.ofBytes(bytes).hex))
          .add("byteLength", Json.fromInt(bytes.length))
      )
    val updated =
      entry.mapObject(_.add("disposition", disposition.mapObject(_.add("artifact", artifact))))
    val text = Canonical.print(
      archive.mapObject(
        _.add("entries", Json.fromValues(entries.map(e => if e == entry then updated else e)))
          .add(
            "files",
            Json.fromValues(
              files.map(f =>
                if f == file then f.mapObject(_.add("utf8", Json.fromString(changed))) else f
              )
            )
          )
      )
    )
    val inspectOnly = open(text).dispatch(WorkspaceAction.Walk(1)).toOption.get
    assert(WorkspaceSave.encode(inspectOnly).nonEmpty)
    assertEquals(WorkspaceExport.create(inspectOnly), Left(WorkspaceRefusal.PermissionDenied))
  }

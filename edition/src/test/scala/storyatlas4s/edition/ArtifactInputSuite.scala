package storyatlas4s.edition

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storymodel4s.codec.{WorkspaceCodecs, WorkspaceVoyage, VoyageCodecs}
import storymodel4s.view.ArtifactId
import storymodel4s.view.{WorkspaceRefusal, WorkspaceRole}

class ArtifactInputSuite extends FunSuite:
  private def bytes(value: String): Vector[Byte] = value.getBytes(UTF_8).toVector
  private lazy val raw = WorkspaceTestData.archives("bell")
  private lazy val workspace = WorkspaceCodecs.decode(raw).toOption.get
  private lazy val source = workspace.archive.manifest.bytes(WorkspaceRole.SourceModel).get

  test("local Open accepts producer workspace bytes regardless of file name") {
    assert(
      ArtifactInput
        .open(Vector("research.json" -> bytes(raw)))
        .toOption
        .get
        .isInstanceOf[ImportedArtifact.Workspace]
    )
    val changed = raw.replace("A bell rang.", "A gong rang.")
    assert(ArtifactInput.open(Vector("research.json" -> bytes(changed))).isLeft)
  }

  test("source-only and legacy Voyage use the canonical producer decoders") {
    val opened = ArtifactInput.open(Vector("storymodel.json" -> source)).toOption.get
    assert(opened.isInstanceOf[ImportedArtifact.Source])
    val document = WorkspaceVoyage
      .from(workspace, ArtifactId.unsafe("historical-lexical"))
      .toOption
      .get
      .document
      .get
    val encoded = VoyageCodecs.encode(document)
    assert(
      ArtifactInput
        .open(Vector("voyage.json" -> bytes(encoded)))
        .toOption
        .get
        .isInstanceOf[ImportedArtifact.Voyage]
    )
  }

  test("a multi-file source import cannot silently discard recall or capability artifacts") {
    assertEquals(
      ArtifactInput.open(
        Vector(
          "storymodel.json" -> source,
          "capabilities.json" -> bytes("{\"inspection\":\"Denied\"}")
        )
      ),
      Left(WorkspaceRefusal.UnexpectedBytes)
    )
    assertEquals(
      ArtifactInput.open(
        Vector(
          "storymodel.json" -> source,
          "recall.json" -> workspace.archive.manifest.bytes(WorkspaceRole.Recall).get
        )
      ),
      Left(WorkspaceRefusal.UnexpectedBytes)
    )
  }

  test("invalid UTF-8, duplicate paths and unknown formats produce content-free refusals") {
    assertEquals(
      ArtifactInput.open(Vector("bad.json" -> Vector(0xc0.toByte, 0x80.toByte))),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
    assertEquals(
      ArtifactInput.open(Vector("storymodel.json" -> source, "STORYMODEL.JSON" -> source)),
      Left(WorkspaceRefusal.DuplicatePath)
    )
    assertEquals(
      ArtifactInput.open(
        Vector("unknown.json" -> bytes("{\"schemaVersion\":\"future\",\"private\":\"canary\"}"))
      ),
      Left(WorkspaceRefusal.UnsupportedVersion)
    )
    Vector("I" -> "i", "À" -> "à").foreach { (upper, lower) =>
      assertEquals(
        ArtifactInput.open(Vector(upper -> source, lower -> source)),
        Left(WorkspaceRefusal.DuplicatePath)
      )
    }
    assertEquals(ArtifactInput.open(Vector.empty), Left(WorkspaceRefusal.UnsupportedContent))
  }

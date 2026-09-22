package storyatlas4s.edition

import munit.FunSuite
import storymodel4s.codec.{WorkspaceArchiveCodec, WorkspaceCodecs}
import storymodel4s.view.{DerivationRecord, WorkspaceRole}

class SourceInputSuite extends FunSuite:
  test("source-only decoding preserves an imported source without a recall or companion record") {
    WorkspaceTestData.archives.values.foreach { text =>
      val workspace = WorkspaceCodecs.decode(text).toOption.get
      val source = WorkspaceArchiveCodec.utf8(workspace.archive.manifest.bytes(WorkspaceRole.SourceModel).get).toOption.get
      val decoded = SourceInput.decode(source).toOption.get
      assertEquals(decoded.draft.source, workspace.draft.model.source)
      assertEquals(decoded.derivation, DerivationRecord.NotSupplied)
      assertEquals(decoded.features, FeatureRecord.NotSupplied)
      assertEquals(decoded.outcome.validated.isDefined, true)
      assert(SourceInput.decode(source, Some("{}")).isLeft)
    }
  }

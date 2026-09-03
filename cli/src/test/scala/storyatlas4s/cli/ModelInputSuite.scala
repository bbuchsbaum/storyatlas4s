package storyatlas4s.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import storyatlas4s.edition.EditionSpec
import storymodel4s.codec.StoryModelCodec
import storymodel4s.core.{BuildReceipt, Checksum, StageId}
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.story.{
  ModelStatus,
  Severity,
  StoryModel,
  StoryValidator,
  ValidationOutcome,
  ValidationPolicy,
  ValidationReport,
  Violation
}
import storymodel4s.view.{DerivationRecord, ViewBasis}

/** The V0 claim: the edition is compiled from a model read off disk, not only from the fixture
  * linked into this build. The route is storymodel4s `codec` and then `StoryValidator`; nothing in
  * `cli` may promote a model or skip a law.
  */
class ModelInputSuite extends FunSuite:
  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def deleteTree(path: Path): Unit =
    if Files.isDirectory(path) then
      val entries = Files.newDirectoryStream(path)
      try entries.forEach(child => deleteTree(child))
      finally entries.close()
    Files.delete(path)

  private val temp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("storyatlas4s-model"),
    teardown = deleteTree
  )

  /** The fixture's content under a build receipt, which is what makes it look like something the
    * pipeline wrote rather than something a researcher hand-authored. The linked fixture carries no
    * receipt — it was never built — and `ViewBasis.ValidatedBuild` may not be claimed without one,
    * which is the distinction these tests are about.
    */
  private val receiptedModel: StoryModel[ModelStatus.Validated] =
    val m = WarOfTheGhostsModel.model
    val receipt = BuildReceipt(
      m.source.id,
      m.source.canonicalChecksum,
      StoryModel.SchemaVersion,
      Vector(StageId.unsafe("test/rebuild") -> Checksum.ofText("model-input-suite")),
      createdAtEpochMillis = 0L
    )
    val draft = StoryModel.draft(
      m.source,
      m.atlas,
      m.graph,
      m.hierarchy,
      m.trajectory,
      m.featureSpaces,
      m.sidecars,
      m.featureRefs,
      m.descriptors,
      m.hypotheses,
      m.sensoryProfiles,
      Some(receipt)
    )
    StoryValidator
      .validate(draft, ValidationPolicy.default)
      .validated
      .getOrElse(fail("the receipted fixture content must still validate"))

  /** A `storymodel.json` on disk, written by the same codec the pipeline writes with. */
  private def writeModel(dir: Path, name: String, json: String): Path =
    Files.write(dir.resolve(name), json.getBytes(UTF_8))

  /** A model the validator did not promote, under a stated derivation record. The violations are
    * this suite's own, so the test exercises the draft edition rather than the validator.
    */
  private def unpromoted(dir: Path, derivation: DerivationRecord): ReadModel =
    ReadModel(
      dir.resolve("storymodel.json"),
      WarOfTheGhostsModel.draft,
      ValidationOutcome(
        ValidationReport(
          Vector(
            Violation("S3", Severity.Error, "situations/s1", "no supporting span"),
            Violation("S3", Severity.Error, "situations/s2", "no supporting span"),
            Violation("S7", Severity.Warning, "atlas", "surface unit unused")
          )
        ),
        validated = None
      ),
      derivation
    )

  temp.test("a storymodel.json round-trips through codec into a validated model"): dir =>
    val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(WarOfTheGhostsModel.model))
    val read = ok(ModelInput.read(path))
    assertEquals(read.path, path)
    assert(read.isValidated, read.report.render)
    assertEquals(read.report.errors, Vector.empty[Violation])
    // The decoded model is the same model, by the codec's own content checksum.
    assertEquals(
      StoryModelCodec.contentChecksum(read.draft),
      StoryModelCodec.contentChecksum(WarOfTheGhostsModel.model)
    )

  temp.test("an edition compiled from disk matches the linked fixture, on a different basis"):
    dir =>
      val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(receiptedModel))
      val read = ok(ModelInput.read(path))
      val fromDisk = ok(Edition.fromRead(read))
      val fromFixture = ok(Edition.warOfTheGhosts)

      assertEquals(fromDisk.files.map(_.name), fromFixture.files.map(_.name))
      assertEquals(fromDisk.sourceChecksum, fromFixture.sourceChecksum)
      assertEquals(fromDisk.model, "storymodel.json")
      // A machine-read model is never labelled a researcher-reviewed fixture (recovery plan §2: the
      // viewer may never out-claim the model), and the basis reaches every artifact and the receipt.
      assertEquals(fromDisk.provenanceBasis, ViewBasis.ValidatedBuild)
      assertEquals(fromFixture.provenanceBasis, ViewBasis.ResearcherReviewedFixture)
      fromDisk.files.foreach { f =>
        assert(f.content.contains(ViewBasis.ValidatedBuild.label), f.name)
        assert(!f.content.contains(ViewBasis.ResearcherReviewedFixture.label), f.name)
      }
      assert(fromDisk.receipt.contains(ViewBasis.ValidatedBuild.label), fromDisk.receipt)
      assert(fromDisk.receipt.contains("\"model\": \"storymodel.json\""), fromDisk.receipt)

  temp.test("`edition --model` writes an edition and reports what the validator found"): dir =>
    val model = writeModel(dir, "storymodel.json", StoryModelCodec.encode(receiptedModel))
    val outDir = dir.resolve("edition")
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val code = Main.run(
      List("edition", "--out", outDir.toString, "--model", model.toString),
      new PrintStream(out, true, UTF_8),
      new PrintStream(err, true, UTF_8)
    )
    assertEquals(code, 0, err.toString(UTF_8))
    assert(
      out.toString(UTF_8).contains("0 errors, 0 warnings, validated=true"),
      out.toString(UTF_8)
    )
    assert(Files.exists(outDir.resolve(Edition.ReceiptFile)))
    assert(Files.exists(outDir.resolve("codex-reading.html")))

  test("`edition` rejects a --model with no path and a bare --out"):
    val err = new ByteArrayOutputStream
    val stream = new PrintStream(err, true, UTF_8)
    val quiet = new PrintStream(new ByteArrayOutputStream, true, UTF_8)
    assertEquals(Main.run(List("edition", "--out", "x", "--model"), quiet, stream), 2)
    assert(err.toString(UTF_8).contains("missing path after --model"), err.toString(UTF_8))
    assertEquals(Main.run(List("edition"), quiet, stream), 2)
    assert(err.toString(UTF_8).contains("missing --out <dir>"), err.toString(UTF_8))

  temp.test("a validated model with no build receipt has no true basis and is refused"): dir =>
    // Re-encoding the linked fixture produces exactly this: it validates, and it was never built.
    val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(WarOfTheGhostsModel.model))
    val read = ok(ModelInput.read(path))
    assert(read.isValidated)
    val problem = Edition
      .fromRead(read)
      .left
      .getOrElse(fail("an unreceipted model must not be drawn as a validated build"))
    assert(problem.contains("no build receipt"), problem)

  temp.test("a model that does not validate is drawn as a draft, not refused"): dir =>
    // The path the machine-built War of the Ghosts takes: it decodes, and it fails its laws. The
    // draft compilers draw it as partial rather than refusing it or pretending it is whole.
    val edition = ok(Edition.fromRead(unpromoted(dir, DerivationRecord.NotSupplied)))
    assertEquals(edition.provenanceBasis, ViewBasis.DraftBuild)
    assertEquals(edition.promotion.map(_.promoted), Some(false))
    assertEquals(edition.promotion.map(_.violationCount), Some(3))
    // The plates, the reading surface, and the workspace that joins them. `compileDraft` takes the
    // same bundle on both compilers, so a partial model now has words as well as marks.
    assertEquals(
      edition.files.map(_.artifact).distinct.sorted,
      Vector(
        "atlas-draft",
        "atlas-draft-twin",
        "codex-draft",
        "codex-draft-pages",
        "codex-draft-pages-twin",
        "codex-draft-twin",
        "workspace"
      )
    )
    assertEquals(
      edition.files.length,
      EditionSpec.zoomLevels.length * 2 + EditionSpec.lenses.length * 4 + 1
    )
    edition.files.foreach { f =>
      assert(f.content.contains(ViewBasis.DraftBuild.label), f.name)
    }

  temp.test("no derivation record is never drawn or receipted as zero gaps"): dir =>
    // `NotSupplied` and `Reported(no gaps)` are different states and must not share a fingerprint:
    // one says nobody told the view anything, the other says the compiler derived everything.
    val absent = ok(Edition.fromRead(unpromoted(dir, DerivationRecord.NotSupplied)))
    val reported =
      ok(Edition.fromRead(unpromoted(dir, DerivationRecord.Reported(Vector.empty, Vector.empty))))

    assertEquals(absent.promotion.flatMap(_.gapCount), None)
    assertEquals(reported.promotion.flatMap(_.gapCount), Some(0))
    assert(absent.receipt.contains(ModelInput.derivationRecordNote), absent.receipt)
    assert(reported.receipt.contains("\"gaps\": 0"), reported.receipt)
    // The distinction reaches the drawn artifact, not only the receipt.
    assertNotEquals(absent.files.map(_.checksum), reported.files.map(_.checksum))

  temp.test("a file that is not a storymodel.json is refused with the codec's reason"): dir =>
    val garbage = writeModel(dir, "storymodel.json", """{"schemaVersion":"0.1.0"}""")
    val problem = ModelInput
      .read(garbage)
      .left
      .getOrElse(fail("an unsupported schema must not decode"))
    assert(problem.contains("not a readable storymodel.json"), problem)

  test("a missing file is refused before anything is compiled"):
    val problem = ModelInput
      .read(Path.of("/nonexistent/storymodel.json"))
      .left
      .getOrElse(fail("a missing file must not decode"))
    assert(problem.contains("cannot read"), problem)

  temp.test("violations are summarised worst law first, with one example each"): dir =>
    val read = ReadModel(
      dir.resolve("storymodel.json"),
      WarOfTheGhostsModel.draft,
      ValidationOutcome(
        ValidationReport(
          Vector(
            Violation("S3", Severity.Error, "situations/b", "second"),
            Violation("S3", Severity.Error, "situations/a", "first"),
            Violation("S1", Severity.Error, "entities/z", "only")
          )
        ),
        validated = None
      ),
      DerivationRecord.NotSupplied
    )
    assertEquals(read.violationsByLaw, Vector("S3" -> 2, "S1" -> 1))
    assertEquals(
      read.violationSummary,
      Vector(
        "  S3: 2 (e.g. situations/a: first)",
        "  S1: 1 (e.g. entities/z: only)"
      )
    )

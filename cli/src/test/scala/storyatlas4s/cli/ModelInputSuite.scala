package storyatlas4s.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import storyatlas4s.edition.EditionSpec
import storymodel4s.codec.{FeaturesArtifact, FeaturesRecordCodec, StoryModelCodec}
import storymodel4s.core.{BuildReceipt, Checksum, FeatureSpaceId, StageId}
import storymodel4s.features.{FeatureTarget, FeatureTrack}
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
  private val receiptedDraft: StoryModel[ModelStatus.Draft] =
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
    draft

  private val receiptedModel: StoryModel[ModelStatus.Validated] =
    StoryValidator
      .validate(receiptedDraft, ValidationPolicy.default)
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
      derivation,
      FeatureRecord.NotSupplied
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

  /** A derivation record for `model`, in the shape the pipeline writes: one attempted summary that
    * was not emitted, so the record reports exactly one gap.
    */
  private def derivationFor(model: StoryModel[?]): String =
    import storymodel4s.acquire.{ClaimFamily, ResolutionFailure}
    import storymodel4s.codec.{DerivationArtifact, DerivationRecordCodec, StoryModelCodec}
    import storymodel4s.core.{Checksum, StageId}
    import storymodel4s.document.*
    val target = NarrativeCandidateAddress.StorySummary(model.source.id)
    val reason = DerivationGapReason.Unresolved(ResolutionFailure.NoProposal)
    val artifact = DerivationArtifact
      .of(
        model.source.id,
        model.source.canonicalChecksum,
        StoryModelCodec.contentChecksum(model),
        Checksum.ofText("fingerprint"),
        Checksum.ofText("candidates"),
        Vector(
          DerivationAttempt(target, ClaimFamily.Summary, DerivationDisposition.NotEmitted(reason))
        ),
        Vector(
          DerivationGap(
            StageId.unsafe("test"),
            ClaimFamily.Summary,
            target,
            reason,
            Set.empty,
            Vector.empty
          )
        ),
        Vector.empty,
        SummaryCoverage.NoTitle
      )
      .fold(e => fail(e.message), identity)
    DerivationRecordCodec.encode(artifact)

  temp.test("a derivation.json beside the model is read as its record, bound to that model"): dir =>
    val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(receiptedModel))
    writeModel(dir, ModelInput.DerivationFile, derivationFor(receiptedModel))
    val read = ok(ModelInput.read(path))
    assertEquals(read.derivation.gapCount, Some(1))
    // The record reaches the receipt and the drawn files as one gap, not as "not supplied".
    val edition =
      ok(Edition.fromRead(read.copy(outcome = unpromoted(dir, read.derivation).outcome)))
    assertEquals(edition.promotion.flatMap(_.gapCount), Some(1))
    assert(!edition.receipt.contains(ModelInput.derivationRecordNote), edition.receipt)
    assert(edition.receipt.contains("\"gaps\": 1"), edition.receipt)

  temp.test("a derivation.json written for another model is refused, never paired"): dir =>
    val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(receiptedModel))
    // The fixture's own draft has the same story and source but different bytes (no receipt), so
    // its record binds to another model checksum.
    writeModel(dir, ModelInput.DerivationFile, derivationFor(WarOfTheGhostsModel.draft))
    ModelInput.read(path) match
      case Left(message) =>
        assert(message.contains("modelChecksum"), message)
        assert(message.contains("is not the derivation record of"), message)
      case Right(read) => fail(s"paired a foreign record: ${read.derivation.render}")

  temp.test("no derivation.json beside the model is not supplied, and says so"): dir =>
    val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(receiptedModel))
    val read = ok(ModelInput.read(path))
    assertEquals(read.derivation, DerivationRecord.NotSupplied)
    assertEquals(read.derivation.gapCount, None)

  /** A feature record for `draft` in the shape the pipeline writes: the token-length measure over
    * the fixture's surface and its mean per sentence, materialized onto the model as sidecars. The
    * record binds to the model that carries the manifests, so that is the model a bundle must
    * write, not the one before materialization.
    */
  private def measured(draft: StoryModel[ModelStatus.Draft]): Measured =
    import storymodel4s.codec.FeatureMaterializer
    import storymodel4s.core.SurfaceSequence
    import storymodel4s.features.{TokenLength, TokenTracks}
    val sequence = SurfaceSequence(draft.atlas)
    val raw = ok(TokenTracks.measure(sequence, TokenLength))
    val sentences = ok(TokenTracks.perSentence(raw, sequence))
    val materialized = ok(FeatureMaterializer.materialize(draft, Vector(raw, sentences)))
    val artifact = ok(FeaturesArtifact.of(materialized.model, materialized.tracks))
    Measured(materialized.model, artifact, materialized.sidecars, raw)

  private final case class Measured(
      model: StoryModel[ModelStatus.Draft],
      artifact: FeaturesArtifact,
      sidecars: Map[FeatureSpaceId, Array[Byte]],
      raw: FeatureTrack[FeatureTarget.Token, Double]
  )

  /** `storymodel.json`, `features.json` and every sidecar the record names, as the pipeline lays
    * them out; returns the model path.
    */
  private def writeBundle(
      dir: Path,
      model: StoryModel[?],
      artifact: FeaturesArtifact,
      sidecars: Map[FeatureSpaceId, Array[Byte]]
  ): Path =
    val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(model))
    writeModel(dir, ModelInput.FeaturesFile, FeaturesRecordCodec.encode(artifact))
    artifact.tracks.foreach { entry =>
      val file = dir.resolve(entry.file)
      Files.createDirectories(file.getParent)
      Files.write(file, sidecars(entry.track.space.id))
    }
    path

  temp.test("no features.json beside the model is not supplied, and says so"): dir =>
    val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(receiptedModel))
    val read = ok(ModelInput.read(path))
    assertEquals(read.features, FeatureRecord.NotSupplied)
    assertEquals(read.features.trackCount, None)
    val edition = ok(Edition.fromRead(read))
    assert(edition.receipt.contains(ModelInput.featureRecordNote), edition.receipt)

  temp.test("an empty feature record is supplied with no tracks, which is not 'not supplied'"):
    dir =>
      val path = writeModel(dir, "storymodel.json", StoryModelCodec.encode(receiptedModel))
      val empty = ok(FeaturesArtifact.of(receiptedModel, Vector.empty))
      writeModel(dir, ModelInput.FeaturesFile, FeaturesRecordCodec.encode(empty))
      val read = ok(ModelInput.read(path))
      assertEquals(read.features.trackCount, Some(0))
      val supplied = ok(Edition.fromRead(read))
      val absent = ok(Edition.fromRead(read.copy(features = FeatureRecord.NotSupplied)))
      assert(supplied.receipt.contains("\"tracks\": 0"), supplied.receipt)
      assert(!supplied.receipt.contains(ModelInput.featureRecordNote), supplied.receipt)
      assertNotEquals(supplied.receipt, absent.receipt)

  temp.test("features.json and its sidecars beside the model are bound, verified and materialized"):
    dir =>
      val m = measured(receiptedDraft)
      val path = writeBundle(dir, m.model, m.artifact, m.sidecars)
      val read = ok(ModelInput.read(path))
      assert(read.isValidated, read.report.render)
      read.features match
        case FeatureRecord.Supplied(record, tracks) =>
          assertEquals(record, m.artifact)
          assertEquals(tracks.map(_.space.id), m.artifact.tracks.map(_.track.space.id))
          // The values are the measure's own, back from the bytes, not the record's references.
          assert(m.raw.observed.nonEmpty)
          assertEquals(tracks(0).observed.map(_._2), m.raw.observed.map(_._2))
          assertEquals(tracks(1).size, m.model.atlas.sentences.size)
        case other => fail(s"expected a supplied record, got ${other.render}")
      val edition = ok(Edition.fromRead(read))
      assert(edition.receipt.contains("\"tracks\": 2"), edition.receipt)
      assert(edition.receipt.contains("measure:token-length/v1"), edition.receipt)
      assert(edition.receipt.contains(m.artifact.tracks.head.file), edition.receipt)

  temp.test("a features.json written for another model is refused, never paired"): dir =>
    val m = measured(receiptedDraft)
    // The record binds to the materialized model; the model on disk is the one before it, same
    // story and source, different content.
    val path = writeBundle(dir, receiptedModel, m.artifact, m.sidecars)
    ModelInput.read(path) match
      case Left(message) =>
        assert(message.contains("modelChecksum"), message)
        assert(message.contains("is not the feature record of"), message)
      case Right(read) => fail(s"paired a foreign feature record: ${read.features.render}")

  temp.test("a track whose manifest is not the model's sidecar for its space is refused"): dir =>
    val m = measured(receiptedDraft)
    val path = writeBundle(dir, m.model, m.artifact, m.sidecars)
    // Rewrite one track's manifest checksum and, consistently, the file it names, so the record
    // still decodes and still binds to this model, and put bytes under the new name: the only
    // thing wrong is that the model never declared this manifest.
    val entry = m.artifact.tracks.head
    val foreign = Checksum.ofText("a manifest the model never declared")
    val recordFile = dir.resolve(ModelInput.FeaturesFile)
    val renamed = entry.file.replace(entry.track.manifest.checksum.short(24), foreign.short(24))
    val text = new String(Files.readAllBytes(recordFile), UTF_8)
      .replace(entry.track.manifest.checksum.hex, foreign.hex)
      .replace(entry.file, renamed)
    Files.write(recordFile, text.getBytes(UTF_8))
    Files.write(dir.resolve(renamed), m.sidecars(entry.track.space.id))
    ModelInput.read(path) match
      case Left(message) => assert(message.contains("is not the model's sidecar"), message)
      case Right(_)      => fail("paired a track under a manifest the model does not carry")

  temp.test("a sidecar the record names but the bundle lacks is refused"): dir =>
    val m = measured(receiptedDraft)
    val path = writeBundle(dir, m.model, m.artifact, m.sidecars)
    Files.delete(dir.resolve(m.artifact.tracks.head.file))
    ModelInput.read(path) match
      case Left(message) => assert(message.contains("is not beside the model"), message)
      case Right(_)      => fail("read a record whose sidecar is missing")

  temp.test("sidecar bytes that do not verify against their manifest are refused"): dir =>
    val m = measured(receiptedDraft)
    val path = writeBundle(dir, m.model, m.artifact, m.sidecars)
    val file = dir.resolve(m.artifact.tracks.head.file)
    val bytes = Files.readAllBytes(file)
    bytes(bytes.length - 1) = (bytes(bytes.length - 1) ^ 0x01).toByte
    Files.write(file, bytes)
    ModelInput.read(path) match
      case Left(message) =>
        assert(message.contains("is not the sidecar its record describes"), message)
      case Right(_) => fail("materialized sidecar bytes that do not match their manifest")

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
      DerivationRecord.NotSupplied,
      FeatureRecord.NotSupplied
    )
    assertEquals(read.violationsByLaw, Vector("S3" -> 2, "S1" -> 1))
    assertEquals(
      read.violationSummary,
      Vector(
        "  S3: 2 (e.g. situations/a: first)",
        "  S1: 1 (e.g. entities/z: only)"
      )
    )

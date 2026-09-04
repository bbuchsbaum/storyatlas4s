package storyatlas4s.cli

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import storymodel4s.codec.{
  DerivationRecordCodec,
  FeaturesArtifact,
  FeaturesRecordCodec,
  SidecarCodec,
  StoryModelCodec
}
import storymodel4s.features.{FeatureTarget, FeatureTrack}
import storymodel4s.story.{
  ModelStatus,
  StoryModel,
  StoryValidator,
  ValidationOutcome,
  ValidationPolicy,
  ValidationReport,
  Violation
}
import storymodel4s.view.{DerivationRecord, DraftModel}

/** A `storymodel.json` the storymodel4s pipeline wrote, read back through storymodel4s `codec`, put
  * to the validator, and bound to whatever derivation record accompanied it.
  *
  * The wire carries no status: `StoryModelCodec.decode` returns a `StoryModel[ModelStatus.Draft]`,
  * and `Validated` is reachable only through `StoryValidator`, which is the point. A model the
  * pipeline built out of a real text is normally *not* validated, so this record keeps the draft,
  * the whole validation outcome, and the derivation record, and lets the caller decide which path
  * can draw it. Nothing here promotes a model, and nothing here hides a violation.
  *
  * `derivation` and `features` are what sat beside the model on disk, each bound to the model by
  * its own codec and each a typed absence when nothing did. See [[ModelInput.derivationRecordNote]]
  * and [[ModelInput.featureRecordNote]].
  */
final case class ReadModel(
    path: Path,
    draft: StoryModel[ModelStatus.Draft],
    outcome: ValidationOutcome,
    derivation: DerivationRecord,
    features: FeatureRecord
):
  def report: ValidationReport = outcome.report

  def validated: Option[StoryModel[ModelStatus.Validated]] = outcome.validated

  /** True when the validator promoted the model, so the validated compilers can draw it. */
  def isValidated: Boolean = validated.isDefined

  /** Structural promotion does not establish complete derivation. Retain the draft view whenever
    * the supplied record reports gaps or abstentions, even if the remaining graph validates.
    */
  def needsDraftView: Boolean =
    !isValidated || draftModel.gaps.nonEmpty || draftModel.abstentions.nonEmpty

  /** The bundle the draft compilers take: the model, what validation said, and what the derivation
    * record does or does not report. `DraftModel.of` derives the promotion, which cannot be forged.
    */
  def draftModel: DraftModel = DraftModel.of(draft, outcome, derivation)

  /** Violation counts per law, worst first then alphabetical: what stops this model validating. */
  def violationsByLaw: Vector[(String, Int)] =
    report.violations
      .groupBy(_.law)
      .toVector
      .map((law, vs) => law -> vs.length)
      .sortBy((law, n) => (-n, law))

  /** A short, deterministic account for a terminal: the counts, then one example per law. */
  def violationSummary: Vector[String] =
    val examples: Map[String, Violation] =
      report.violations.groupBy(_.law).map((law, vs) => law -> vs.minBy(_.path))
    violationsByLaw.map { (law, n) =>
      val e = examples(law)
      s"  $law: $n (e.g. ${e.path}: ${e.reason})"
    }

/** Reads a model from disk. The only supported route from a `storymodel.json` to a `StoryModel`. */
object ModelInput:

  /** The file the pipeline writes its derivation record to, beside `storymodel.json` (storymodel4s
    * ADR 0009, amendment of 2026-09-03; schema `derivation-record/v1`).
    */
  val DerivationFile: String = "derivation.json"

  /** Why a model read from disk can still arrive with no derivation record.
    *
    * Until 2026-09-03 no model could carry one: the pipeline's `compilation-report.json` wrote
    * upstream claims and evidence as counts and every address as a one-way render, so a record
    * could only have been fabricated, and a viewer that fabricated it would out-claim the model at
    * the input boundary. Since then the pipeline writes `derivation.json`, a typed record bound to
    * the model by story id, source checksum and the model's own content checksum, and [[read]]
    * decodes it when it sits beside the model. A model directory without one, or built before the
    * artifact existed, is still reported as not supplied, which `DerivationRecord` keeps distinct
    * from a record reporting zero gaps.
    */
  val derivationRecordNote: String =
    s"no derivation record: no $DerivationFile beside the model"

  /** The file the pipeline writes its feature record to, beside `storymodel.json` (storymodel4s ADR
    * 0011; schema `features-record/v1`), with each track's bytes under `features/`.
    */
  val FeaturesFile: String = "features.json"

  /** Why a model can arrive with no feature record: it was built before ADR 0011 (2026-09-03), or
    * the bundle was copied without the file. The pipeline writes `features.json` on every build
    * since then, with no tracks when none were requested, so a missing file is not "nothing was
    * measured"; that is a supplied record with zero tracks, and the two never share a receipt.
    */
  val featureRecordNote: String =
    s"no feature record: no $FeaturesFile beside the model"

  /** Decode `path`, then validate under `policy`. A decode failure is fatal; a validation failure
    * is not — it is reported, because a partial model is a fact about the pipeline, not an error in
    * the file.
    *
    * The derivation record is read from `derivation.json` beside the model when that file exists,
    * through the model-bound decoder, so a record written for another story, another source or
    * another build of the same text is refused rather than paired: pairing the wrong record would
    * out-claim the model exactly as fabricating one would. No file beside the model means
    * `NotSupplied`. A caller may still pass a record explicitly (tests do), which takes precedence
    * over the file.
    */
  def read(
      path: Path,
      policy: ValidationPolicy = ValidationPolicy.default,
      derivation: Option[DerivationRecord] = None,
      features: Option[FeatureRecord] = None
  ): Either[String, ReadModel] =
    for
      text <- slurp(path)
      draft <- StoryModelCodec
        .decode(text)
        .left
        .map(e => s"$path is not a readable storymodel.json: ${e.message}")
      record <- derivation.fold(readDerivation(path, draft))(r => Right(r))
      measured <- features.fold(readFeatures(path, draft))(f => Right(f))
    yield ReadModel(path, draft, StoryValidator.validate(draft, policy), record, measured)

  /** `derivation.json` beside `modelPath`, bound to `draft`; `NotSupplied` when absent. */
  def readDerivation(
      modelPath: Path,
      draft: StoryModel[ModelStatus.Draft]
  ): Either[String, DerivationRecord] =
    val sidecar = Option(modelPath.toAbsolutePath.getParent).map(_.resolve(DerivationFile))
    sidecar.filter(Files.isRegularFile(_)) match
      case None       => Right(DerivationRecord.NotSupplied)
      case Some(file) =>
        slurp(file).flatMap { text =>
          DerivationRecordCodec
            .decode(draft, text)
            .left
            .map(e => s"$file is not the derivation record of $modelPath: ${e.message}")
            .map(_.record)
        }

  /** `features.json` beside `modelPath`, bound to `draft`, with every sidecar it names read from
    * beside it and verified; `NotSupplied` when absent.
    *
    * Three refusals, each for what pairing would otherwise out-claim. A record for another story,
    * source or build is refused by the codec's binding, exactly as `derivation.json` is. A track
    * whose manifest is not the one the model carries for that space is refused here: the record's
    * decode checks the model's checksums but not its sidecar map, and values under a manifest the
    * model never declared would be values the model did not bind. Sidecar bytes that are missing,
    * or that do not verify block by block against their manifest, are refused by the materializer,
    * so every value a caller receives came through the checked prelude and digest index.
    */
  def readFeatures(
      modelPath: Path,
      draft: StoryModel[ModelStatus.Draft]
  ): Either[String, FeatureRecord] =
    Option(modelPath.toAbsolutePath.getParent) match
      case None      => Right(FeatureRecord.NotSupplied)
      case Some(dir) =>
        val file = dir.resolve(FeaturesFile)
        if !Files.isRegularFile(file) then Right(FeatureRecord.NotSupplied)
        else
          for
            text <- slurp(file)
            artifact <- FeaturesRecordCodec
              .decode(draft, text)
              .left
              .map(e => s"$file is not the feature record of $modelPath: ${e.message}")
            tracks <- artifact.tracks
              .foldLeft[Either[String, Vector[FeatureTrack[FeatureTarget, Double]]]](
                Right(Vector.empty)
              ) { (acc, entry) =>
                acc.flatMap(done => materialize(file, dir, draft, entry).map(done :+ _))
              }
          yield FeatureRecord.Supplied(artifact, tracks)

  private def materialize(
      record: Path,
      dir: Path,
      draft: StoryModel[ModelStatus.Draft],
      entry: FeaturesArtifact.Entry
  ): Either[String, FeatureTrack[FeatureTarget, Double]] =
    val space = entry.track.space.id
    val sidecar = dir.resolve(entry.file)
    if !draft.sidecars.get(space).contains(entry.track.manifest) then
      Left(s"$record: track ${space.value} is not the model's sidecar for that space")
    else if !Files.isRegularFile(sidecar) then
      Left(s"$record names $sidecar, which is not beside the model")
    else
      slurpBytes(sidecar).flatMap { bytes =>
        SidecarCodec
          .materializeScalarTrack(entry.track, bytes)
          .left
          .map(e =>
            s"$sidecar is not the sidecar its record describes for ${space.value}: " +
              e.message
          )
      }

  private[cli] def slurp(path: Path): Either[String, String] =
    slurpBytes(path).map(bytes => new String(bytes, UTF_8))

  private[cli] def slurpBytes(path: Path): Either[String, Array[Byte]] =
    try Right(Files.readAllBytes(path))
    catch case e: IOException => Left(s"cannot read $path: ${e.getMessage}")

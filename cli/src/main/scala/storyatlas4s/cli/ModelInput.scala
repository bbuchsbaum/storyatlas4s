package storyatlas4s.cli

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import storymodel4s.codec.{DerivationRecordCodec, StoryModelCodec}
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
  * `derivation` is `DerivationRecord.NotSupplied` for every model read from disk today, and that is
  * a fact rather than a default. See [[ModelInput.derivationRecordNote]].
  */
final case class ReadModel(
    path: Path,
    draft: StoryModel[ModelStatus.Draft],
    outcome: ValidationOutcome,
    derivation: DerivationRecord
):
  def report: ValidationReport = outcome.report

  def validated: Option[StoryModel[ModelStatus.Validated]] = outcome.validated

  /** True when the validator promoted the model, so the validated compilers can draw it. */
  def isValidated: Boolean = validated.isDefined

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
      derivation: Option[DerivationRecord] = None
  ): Either[String, ReadModel] =
    for
      text <- slurp(path)
      draft <- StoryModelCodec
        .decode(text)
        .left
        .map(e => s"$path is not a readable storymodel.json: ${e.message}")
      record <- derivation.fold(readDerivation(path, draft))(r => Right(r))
    yield ReadModel(path, draft, StoryValidator.validate(draft, policy), record)

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

  private def slurp(path: Path): Either[String, String] =
    try Right(new String(Files.readAllBytes(path), UTF_8))
    catch case e: IOException => Left(s"cannot read $path: ${e.getMessage}")

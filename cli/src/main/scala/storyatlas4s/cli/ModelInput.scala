package storyatlas4s.cli

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import storymodel4s.codec.StoryModelCodec
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

  /** Why every model read from disk arrives with no derivation record.
    *
    * The pipeline writes its gaps and its coverage ledger to `compilation-report.json` beside the
    * model, and that file is not an interchange artifact: it is `private[pipeline] BundleJson`'s
    * own account of a run. There are no circe codecs for `DerivationGap` or `SentenceCoverage`
    * anywhere in storymodel4s, no parser inverting `NarrativeCandidateAddress.render`,
    * `DerivationGapReason.render`, `AbstentionReason.render` or `ChartNodeRef.key`, and —
    * decisively — `upstreamClaims` and `evidence` are written as sizes rather than contents, so the
    * data is simply not in the file. Reconstructing a record from it would mean fabricating claim
    * and evidence ids of the right cardinality and guessing at ids whose own delimiters are legal
    * characters inside them.
    *
    * A viewer that did that would out-claim the model at the input boundary, which is the failure
    * the whole recovery plan exists to prevent. So the record is reported as not supplied, which is
    * a state `DerivationRecord` deliberately distinguishes from a record reporting zero gaps.
    * Reading a real record needs a decodable artifact from storymodel4s: circe codecs for those two
    * types in `codec`, or a `derivation.json` emitted next to the model.
    */
  val derivationRecordNote: String =
    "no derivation record: compilation-report.json is not a decodable artifact"

  /** Decode `path`, then validate under `policy`. A decode failure is fatal; a validation failure
    * is not — it is reported, because a partial model is a fact about the pipeline, not an error in
    * the file.
    *
    * `derivation` is the seam: when storymodel4s emits a decodable derivation artifact, a decoder
    * for it is the only new code, and everything downstream already carries a `Reported` record.
    */
  def read(
      path: Path,
      policy: ValidationPolicy = ValidationPolicy.default,
      derivation: DerivationRecord = DerivationRecord.NotSupplied
  ): Either[String, ReadModel] =
    for
      text <- slurp(path)
      draft <- StoryModelCodec
        .decode(text)
        .left
        .map(e => s"$path is not a readable storymodel.json: ${e.message}")
    yield ReadModel(path, draft, StoryValidator.validate(draft, policy), derivation)

  private def slurp(path: Path): Either[String, String] =
    try Right(new String(Files.readAllBytes(path), UTF_8))
    catch case e: IOException => Left(s"cannot read $path: ${e.getMessage}")

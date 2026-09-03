package storyatlas4s.cli

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import storymodel4s.codec.StoryModelCodec
import storymodel4s.story.{
  ModelStatus,
  StoryModel,
  StoryValidator,
  ValidationPolicy,
  ValidationReport,
  Violation
}

/** A `storymodel.json` the storymodel4s pipeline wrote, read back through storymodel4s `codec` and
  * put to the validator.
  *
  * The wire carries no status: `StoryModelCodec.decode` returns a `StoryModel[ModelStatus.Draft]`,
  * and `Validated` is reachable only through `StoryValidator`, which is the point. A model the
  * pipeline built out of a real text is normally *not* validated — the War of the Ghosts build
  * reports 135 structural errors — so this record keeps the draft, what validation said, and the
  * validated model when there is one, and lets the caller decide which path can draw it. Nothing
  * here promotes a model, and nothing here hides a violation.
  */
final case class ReadModel(
    path: Path,
    draft: StoryModel[ModelStatus.Draft],
    report: ValidationReport,
    validated: Option[StoryModel[ModelStatus.Validated]]
):
  /** True when the validator promoted the model, so the validated compilers can draw it. */
  def isValidated: Boolean = validated.isDefined

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

  /** Decode `path`, then validate under `policy`. A decode failure is fatal; a validation failure
    * is not — it is reported, because a partial model is a fact about the pipeline, not an error in
    * the file.
    */
  def read(
      path: Path,
      policy: ValidationPolicy = ValidationPolicy.default
  ): Either[String, ReadModel] =
    for
      text <- slurp(path)
      draft <- StoryModelCodec
        .decode(text)
        .left
        .map(e => s"$path is not a readable storymodel.json: ${e.message}")
    yield
      val outcome = StoryValidator.validate(draft, policy)
      ReadModel(path, draft, outcome.report, outcome.validated)

  private def slurp(path: Path): Either[String, String] =
    try Right(new String(Files.readAllBytes(path), UTF_8))
    catch case e: IOException => Left(s"cannot read $path: ${e.getMessage}")

package storyatlas4s.edition

import storymodel4s.codec.*
import storymodel4s.features.{FeatureTarget, FeatureTrack}
import storymodel4s.story.*
import storymodel4s.view.{DerivationRecord, DraftModel}

/** Portable result of canonical decoding and validation; an unpromoted source remains readable. */
final case class ImportedSource(
    draft: TextModel[ModelStatus.Draft],
    outcome: TextValidationOutcome,
    derivation: DerivationRecord,
    features: FeatureRecord
):
  def draftModel: DraftModel = DraftModel.of(draft, outcome, derivation)

/** Shared import policy. Hosts supply bytes; only the canonical producer decides scientific
  * validity. File paths, network access and user-visible diagnostics remain host responsibilities.
  */
object SourceInput:
  def decodeModel(text: String): Either[String, TextModel[ModelStatus.Draft]] =
    StoryModelCodec.decode(text).left.map(_.message)

  def decodeDerivation(
      draft: TextModel[ModelStatus.Draft],
      text: Option[String]
  ): Either[String, DerivationRecord] = text match
    case None => Right(DerivationRecord.NotSupplied)
    case Some(value) => DerivationRecordCodec.decode(draft, value).left.map(_.message).map(_.record)

  def decodeFeatures(
      draft: TextModel[ModelStatus.Draft],
      text: Option[String],
      bytes: String => Either[String, Array[Byte]]
  ): Either[String, FeatureRecord] = text match
    case None => Right(FeatureRecord.NotSupplied)
    case Some(value) =>
      for
        artifact <- FeaturesRecordCodec.decode(draft, value).left.map(_.message)
        tracks <- artifact.tracks.foldLeft[Either[String, Vector[FeatureTrack[FeatureTarget, Double]]]](
          Right(Vector.empty)
        ) { (acc, entry) =>
          for
            done <- acc
            _ <- Either.cond(
              draft.sidecars.get(entry.track.space.id).contains(entry.track.manifest), (),
              s"track ${entry.track.space.id.value} is not the model's sidecar for that space"
            )
            supplied <- bytes(entry.file)
            track <- SidecarCodec.materializeScalarTrack(entry.track, supplied).left.map(e =>
              s"${entry.file} is not the sidecar its record describes: ${e.message}")
          yield done :+ track
        }
      yield FeatureRecord.Supplied(artifact, tracks)

  def decode(
      model: String,
      derivation: Option[String] = None,
      features: Option[String] = None,
      bytes: String => Either[String, Array[Byte]] = _ => Left("required sidecar not supplied"),
      policy: ValidationPolicy = ValidationPolicy.default
  ): Either[String, ImportedSource] =
    for
      draft <- decodeModel(model)
      record <- decodeDerivation(draft, derivation)
      measured <- decodeFeatures(draft, features, bytes)
    yield ImportedSource(draft, StoryValidator.validate(draft, policy), record, measured)

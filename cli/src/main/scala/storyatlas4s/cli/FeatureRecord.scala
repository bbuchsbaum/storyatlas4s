package storyatlas4s.cli

import storymodel4s.codec.FeaturesArtifact
import storymodel4s.features.{FeatureTarget, FeatureTrack}

/** What the feature record beside a model does or does not report (storymodel4s ADR 0011:
  * `features.json`, schema `features-record/v1`, and one `SM4SFT02` sidecar per measured space).
  *
  * Three states, and the two that carry a record are kept apart from the one that does not.
  * `NotSupplied` says no `features.json` sat beside the model, so the view knows nothing about
  * measurement either way. `Supplied` with no tracks is the pipeline's own statement that nothing
  * was measured, which ADR 0011 writes on every build so that "nothing was measured" is a record
  * and not a missing file. A supplied record arrives with every track's sidecar bytes verified
  * against the manifest the model itself carries and materialized to values, so no number reaches a
  * caller that the record did not bind to this exact model.
  *
  * This is the reading half of the viewer's feature slice. Nothing here is drawn: the value-bearing
  * mark of ADR 0002 D11 does not exist yet, and until it does the record is reported in the
  * edition's receipt and nowhere in a picture.
  */
enum FeatureRecord:
  case NotSupplied
  case Supplied(
      artifact: FeaturesArtifact,
      tracks: Vector[FeatureTrack[FeatureTarget, Double]]
  )

  /** `Some(n)` only when a record was supplied; `None` is not zero. */
  def trackCount: Option[Int] = this match
    case Supplied(_, tracks) => Some(tracks.size)
    case NotSupplied         => None

  /** Feature space ids in record order; empty for an unsupplied record and for an empty one. */
  def spaces: Vector[String] = this match
    case Supplied(_, tracks) => tracks.map(_.space.id.value)
    case NotSupplied         => Vector.empty

  def render: String = trackCount.fold("not supplied")(n => s"$n tracks")

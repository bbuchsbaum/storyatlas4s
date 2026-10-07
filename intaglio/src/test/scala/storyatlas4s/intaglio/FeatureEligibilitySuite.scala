package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.value
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** Renderer-only outcomes use synthetic metadata; CLI courts separately verify real sidecar bytes.
  */
class FeatureEligibilitySuite extends FunSuite:
  private def ok[E, A](e: Either[E, A]): A = e.fold(x => fail(s"unexpected failure: $x"), identity)
  private val sequence = SurfaceSequence(Wog.draft.atlas)
  private val space = FeatureSpace[Double](
    FeatureSpaceId.unsafe("eligibility-lowering"),
    "synthetic eligibility outcomes",
    FeatureValueSchema.Scalar(None),
    None,
    Fingerprint.unsafe("test:eligibility:v1"),
    normalized = false
  )
  private val estimates: Vector[Estimate[Double]] = Vector(
    Estimate.observed(0.0),
    Estimate.missing(MissingReason.ProviderAbstained),
    Estimate.missing(MissingReason.Excluded),
    Estimate.Ineligible,
    Estimate.Ineligible
  )
  private val coverage = Vector(
    None,
    Some(Coverage.unsafe(1, 0)),
    Some(Coverage.unsafe(1, 0)),
    Some(Coverage.empty),
    None
  )
  private val observations = estimates.zipWithIndex.map { (e, i) =>
    FeatureObservation(
      FeatureTarget.Token(TokenIndex.unsafe(i)),
      e,
      Some(SpanSet.one(sequence.tokens(i).span)),
      coverage(i)
    )
  }
  private val track = FeatureTrack.raw(
    space,
    observations,
    TrackProvenance(
      Provenance.deterministic("eligibility-court", Checksum.ofText("five-outcomes")),
      Some(Wog.draft.source.canonicalChecksum)
    )
  )
  private val model = ok(
    StoryModel.draftText(
      Wog.draft.atlas,
      Wog.draft.graph,
      Wog.draft.hierarchy,
      Wog.draft.trajectory,
      featureSpaces = Map(space.id -> space),
      sidecars = Map(
        space.id -> SidecarManifest
          .unsafe(space.id, 1, 1, Dtype.Float64, Checksum.ofText("renderer-only-row"))
      ),
      featureRefs = Vector(FeatureRef.unsafe(observations.head.target, space.id, 0))
    )
  )
  private val draft =
    DraftModel.of(model, StoryValidator.validate(model), DerivationRecord.NotSupplied)
  private val state = ok(CommonViewState.of(feature = Some(FeatureRendering.selection(track))))
  private val spec = AtlasSpec(
    ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Tokens),
    ThreadPolicy.Selected,
    FeatureScale.SurfaceUnit(SurfaceUnitKind.Token)
  )
  private val provenance = ok(
    ViewProvenance.draftBuild(
      draft,
      "eligibility-lowering-court",
      AtlasCompiler.configurationChecksum(state, spec)
    )
  )
  private val scene = ok(AtlasCompiler(provenance).compileDraftFeatures(draft, state, spec, track))
  private val boxSize = ok(
    AtlasLowering.fitFeatureBox(
      scene,
      model.source.canonicalText.length,
      ok(PlateBox.of(1200, 1800))
    )
  )
  private val lowered = ok(AtlasLowering.lower(scene, model.source.canonicalText.length, boxSize))
  private val marks =
    scene.marks.collect { case f: VisualPrimitive.Feature => f }.sortBy(_.value.target)
  private def all(g: ig.Grob): Vector[ig.Grob] = g +: g.children.flatMap(all)
  private def grobs(i: Int): Vector[ig.Grob] =
    lowered.grobs
      .flatMap(all)
      .find(_.name.exists(_.value == marks(i).identity.mark.value))
      .map(all)
      .getOrElse(fail("feature was not drawn"))
  private def box(i: Int): ig.Grob.Polygon = grobs(i).collectFirst { case p: ig.Grob.Polygon =>
    p
  }.get

  test(
    "Ineligible has a hollow dashed box distinct from observed, crossed missing and dotted excluded"
  ) {
    assertEquals(marks.map(_.value.estimate), estimates)
    assertEquals(marks.map(_.value.support), observations.map(_.support.get))
    assertEquals(box(0).gp.lineType, ig.LineType.Solid)
    assert(box(0).gp.fill.nonEmpty)
    assertEquals(grobs(1).count(_.isInstanceOf[ig.Grob.Lines]), 2)
    assertEquals(box(2).gp.lineType, ig.LineType.Dotted)
    assertEquals(box(3).gp.lineType, ig.LineType.Dashed)
    assertEquals(box(3).gp.fill, None)
    assertEquals(grobs(3).count(_.isInstanceOf[ig.Grob.Lines]), 0)
    val annotation = lowered.grobs
      .flatMap(all)
      .collectFirst {
        case a: ig.Grob.Annotated if a.child.name.exists(_.value == marks(3).identity.mark.value) =>
          a
      }
      .getOrElse(fail("feature accessibility annotation disappeared"))
    assertEquals(annotation.meta.title, Some(marks(3).value.description))
  }

  test("unknown and zero-eligible coverage remain independent of the ineligible value glyph") {
    assertEquals(marks.map(_.value.coverage), coverage)
    val zero = grobs(3).collect { case p: ig.Grob.Points => p }
    assertEquals(zero.map(_.shape), Vector(ig.PointShape.Circle))
    val unknown = grobs(4).collect { case l: ig.Grob.Lines => l }
    assertEquals(unknown.size, 1)
    assertEquals(unknown.head.gp.lineType, ig.LineType.Dashed)
  }

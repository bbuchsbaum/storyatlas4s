package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.value
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** Lowering laws for the marks a partial model contributes: context bands, and the three absence
  * marks that carry no context lane.
  *
  * These are the marks that decide whether the picture out-claims the model. A hulled context band
  * would redraw the survivor's fabricated battle as narration; an absence given a lane would assert
  * a context the model never established.
  */
class DraftLoweringSuite extends FunSuite:
  private val model = Wog.model
  private val length = model.source.canonicalText.length

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private val state: CommonViewState = ok(CommonViewState.of())

  /** The fixture bound as a draft under a stated derivation record. The violations are this suite's
    * own, so what is exercised is the lowering rather than the validator.
    */
  private def draftModel(
      violations: Vector[Violation],
      derivation: DerivationRecord = DerivationRecord.NotSupplied
  ): DraftModel =
    DraftModel.of(
      Wog.draft,
      ValidationOutcome(ValidationReport(violations), validated = None),
      derivation
    )

  private def scene(draft: DraftModel): NarrativeScene =
    val spec = AtlasSpec(
      ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden),
      ThreadPolicy.All(PositiveInt.unsafe(3))
    )
    val provenance = ok(
      ViewProvenance.draftBuild(
        draft,
        "draft-lowering-suite",
        AtlasCompiler.configurationChecksum(state, spec)
      )
    )
    ok(AtlasCompiler(provenance).compileDraft(draft, state, spec))

  private def bands(s: NarrativeScene): Vector[VisualPrimitive.ContextBand] =
    s.marks.collect { case m: VisualPrimitive.ContextBand => m }

  private def marksOf(grob: ig.Grob): Vector[ig.Grob] =
    grob +: grob.children.flatMap(marksOf)

  /** Every grob under the group named for `mark`, at any depth. */
  private def loweredFor(lowered: ig.Scene, mark: VisualPrimitive): Vector[ig.Grob] =
    val wanted = mark.identity.mark.value
    lowered.grobs
      .flatMap(marksOf)
      .collectFirst { case g if g.name.exists(_.value == wanted) => marksOf(g) }
      .getOrElse(fail(s"no lowered group for $wanted"))

  test("a context band draws one shape per extent and never a hull over the gaps"):
    val s = scene(draftModel(Vector.empty))
    val lowered = ok(AtlasLowering.lower(s, length))
    val all = bands(s)
    assert(all.nonEmpty, "the fixture has context frames")

    all.foreach { band =>
      val polygons = loweredFor(lowered, band).count(_.isInstanceOf[ig.Grob.Polygon])
      // One polygon per extent, exactly. A hull would be one polygon for many extents, which is
      // precisely how the narrated world would swallow the speech frames inside it.
      assertEquals(
        polygons,
        band.extents.length,
        s"${band.kind.label} lane=${band.lane} has ${band.extents.length} extents"
      )
    }

    // The narrated world is drawn as many extents; a speech frame inside it is drawn as its own.
    val narrated = all.filter(_.kind == ContextKind.NarratedWorld)
    assertEquals(narrated.length, 1)
    assert(narrated.head.extents.length > 1, "the narrated world is not one contiguous stretch")
    assert(
      all.exists(b => b.kind != ContextKind.NarratedWorld && b.lane != narrated.head.lane),
      "a speech frame sits on its own lane, not on the narrated world's"
    )

  /** ADR 0002 §15. A region whose summary the model did not derive is lowered as its hull and no
    * words; a region with a stated summary carries that summary. Words on an unsummarized region
    * would be the plate's, not the model's.
    */
  test("an unsummarized region is a hull with no words; a stated one carries its summary"):
    val g = Wog.draft.graph
    val target = g.segments(Wog.G.sc1a)
    val unsummarized = StoryModel.draft(
      Wog.draft.source,
      Wog.draft.atlas,
      g.copy(segments =
        g.segments.updated(
          Wog.G.sc1a,
          target.copy(summary = SegmentSummary.Unsummarized(SummaryGap.NotProposed))
        )
      ),
      Wog.draft.hierarchy,
      Wog.draft.trajectory,
      receipt = Wog.draft.receipt
    )
    val draft = DraftModel.of(
      unsummarized,
      ValidationOutcome(ValidationReport(Vector.empty), validated = None),
      DerivationRecord.NotSupplied
    )
    val s = scene(draft)
    val lowered = ok(AtlasLowering.lower(s, length))
    val regions = s.marks.collect { case r: VisualPrimitive.Region => r }
    val quiet = regions
      .find(_.label == RegionLabel.Unsummarized(SummaryGap.NotProposed))
      .getOrElse(fail("the unsummarized scene region is not in the scene"))
    // The widest stated region: a narrow one elides its summary to nothing, which is the width
    // budget speaking, not the model.
    val spoken = regions
      .filter(r => r.label.text.nonEmpty)
      .maxByOption(r => r.extent.x1Exclusive - r.extent.x0)
      .getOrElse(fail("no region with a stated summary"))
    val quietGrobs = loweredFor(lowered, quiet)
    assertEquals(quietGrobs.count(_.isInstanceOf[ig.Grob.Polygon]), 1)
    assertEquals(quietGrobs.count(_.isInstanceOf[ig.Grob.Text]), 0)
    val spokenGrobs = loweredFor(lowered, spoken)
    assertEquals(spokenGrobs.count(_.isInstanceOf[ig.Grob.Polygon]), 1)
    assert(spokenGrobs.exists(_.isInstanceOf[ig.Grob.Text]), "a stated summary is drawn")

  test("a context band's own lane is the only lane it is drawn on"):
    val s = scene(draftModel(Vector.empty))
    bands(s).foreach { band =>
      band.extents.toVector.foreach { e =>
        assertEquals(e.lane0, band.lane, s"${band.kind.label}")
        assertEquals(e.lane1, band.lane, s"${band.kind.label}")
      }
    }

  test("an unsatisfied law is a mark on its own spans, in a non-colour channel"):
    val s = scene(
      draftModel(
        Vector(Violation("hierarchy.single-primary-root", Severity.Error, "segments", "none"))
      )
    )
    val laws = s.marks.collect { case m: VisualPrimitive.UnsatisfiedLaw => m }
    assertEquals(laws.length, 1)
    // The channel is a shape, not a hue: nothing in the lowered output distinguishes these marks
    // by colour, so the state survives a monochrome print and a colour-blind reader.
    assertEquals(laws.head.epistemicChannel, Some(EpistemicChannel.Bracket))
    val lowered = ok(AtlasLowering.lower(s, length))
    assert(loweredFor(lowered, laws.head).nonEmpty, "the absence is drawn, not left as a hole")

  test("an absence carries no context lane and never enters the lane plot"):
    val s = scene(
      draftModel(
        Vector(Violation("hierarchy.single-primary-root", Severity.Error, "segments", "none"))
      )
    )
    s.marks.foreach { mark =>
      if mark.epistemicPlacement.isDefined then
        assertEquals(
          mark.uncertainty.isDefined || mark.epistemicChannel.isDefined,
          true,
          mark.identity.mark.value
        )
    }
    // The lane count the plot is scaled to comes only from marks that have a lane.
    val laneBearing = s.marks.count(_.epistemicPlacement.isEmpty)
    assert(laneBearing > 0)
    assert(ok(AtlasLowering.lower(s, length)).grobs.nonEmpty)

  test("the lowering is deterministic for a draft scene"):
    val a = ok(AtlasLowering.lower(scene(draftModel(Vector.empty)), length))
    val b = ok(AtlasLowering.lower(scene(draftModel(Vector.empty)), length))
    assertEquals(a, b)

  test("a supplied record reporting no gaps is not the same scene as no record"):
    val absent = scene(draftModel(Vector.empty, DerivationRecord.NotSupplied))
    val reported =
      scene(draftModel(Vector.empty, DerivationRecord.Reported(Vector.empty, Vector.empty)))
    assertEquals(absent.provenance.draft.flatMap(_.gapCount), None)
    assertEquals(reported.provenance.draft.flatMap(_.gapCount), Some(0))
    assertNotEquals(absent.textualTwin, reported.textualTwin)

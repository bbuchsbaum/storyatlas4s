package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import _root_.intaglio.value
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** Lowering laws on the War of the Ghosts fixture: identity, determinism, twin consistency. */
class WarOfTheGhostsLoweringSuite extends FunSuite:
  private val model = Wog.model
  private val length = model.source.canonicalText.length
  private val levels = Vector(NarrativeLevel.Story, NarrativeLevel.Episode, NarrativeLevel.Scene)

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def state(layers: Set[RelationLayer] = Set.empty): CommonViewState =
    ok(CommonViewState.of(relationLayers = layers))

  private def scene(level: NarrativeLevel, st: CommonViewState = state()): NarrativeScene =
    val spec =
      AtlasSpec(ZoomLevel(level, SurfaceDetail.Hidden), ThreadPolicy.All(PositiveInt.unsafe(3)))
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "lowering-suite",
        AtlasCompiler.configurationChecksum(st, spec)
      )
    )
    ok(AtlasCompiler(provenance).compile(model, st, spec))

  private def flow(lens: CodexLens, st: CommonViewState = state()): CodexFlow =
    val spec = ok(CodexSpec.forLens(lens, ChannelBudget.All))
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "lowering-suite",
        CodexCompiler.configurationChecksum(st, spec)
      )
    )
    ok(CodexCompiler(provenance).compile(model, st, spec))

  private def svg(scene: ig.Scene): String =
    ok(SvgRenderer.render(scene, ok(SvgOptions(1200, 400)))).value

  private val dataName = """data-name="([^"]*)"""".r
  private def dataNames(svg: String): Vector[String] =
    dataName.findAllMatchIn(svg).map(_.group(1)).toVector

  private val relationState = state(Set(RelationLayer.Causal, RelationLayer.Reference))

  test("every Atlas mark is lowered exactly once, named by its MarkId, at each zoom level"):
    levels.foreach { level =>
      val s = scene(level, relationState)
      assert(s.marks.nonEmpty, s"no marks at $level")
      val names = GraphicsNames.collect(ok(AtlasLowering.lower(s, length))).map(_.value)
      assertEquals(names.sorted, s.marks.map(_.identity.mark.value).sorted, level.toString)
      assertEquals(names.distinct.length, names.length, level.toString)
    }

  test("the Scene level with relation layers lowers portals and routes under their own MarkIds"):
    val s = scene(NarrativeLevel.Scene, relationState)
    val edges = s.marks.collect {
      case p: VisualPrimitive.Portal => p.identity.mark.value
      case r: VisualPrimitive.Route  => r.identity.mark.value
    }
    assert(edges.nonEmpty)
    val names = GraphicsNames.collect(ok(AtlasLowering.lower(s, length))).map(_.value).toSet
    edges.foreach(id => assert(names.contains(id), id))

  test("Atlas lowering and SVG serialization are deterministic (V-D1)"):
    levels.foreach { level =>
      val a = ok(AtlasLowering.lower(scene(level), length))
      val b = ok(AtlasLowering.lower(scene(level), length))
      assertEquals(a, b)
      assertEquals(svg(a), svg(b))
    }

  test("the SVG carries exactly one data-name per mark, each resolving through SceneNavigation"):
    levels.foreach { level =>
      val s = scene(level, relationState)
      val names = dataNames(svg(ok(AtlasLowering.lower(s, length))))
      assertEquals(names.sorted, s.marks.map(_.identity.mark.value).sorted)
      names.foreach { name =>
        val address = s.navigation.addressOf.get(MarkId.unsafe(name))
        assert(address.isDefined, s"$name does not resolve to an address")
        assert(s.marks.exists(_.address == address.get))
      }
    }

  test("textual twin and lowered plate name the same marks; the page prints its basis (V-D2)"):
    levels.foreach { level =>
      val s = scene(level, relationState)
      val twin = s.textualTwin
      val rendered = svg(ok(AtlasLowering.lower(s, length)))
      dataNames(rendered).foreach(name => assert(twin.contains(name), s"twin lacks $name"))
      s.marks.foreach(m => assert(rendered.contains(m.identity.mark.value)))
      assert(rendered.contains(ViewBasis.ResearcherReviewedFixture.label))
      assert(rendered.contains(s.provenance.sourceChecksum.hex))
    }

  test("Codex overlay lowers every annotation exactly once, named by its AnnotationId"):
    val f = flow(CodexLens.Overview, relationState)
    assert(f.annotations.nonEmpty)
    val lowered = ok(CodexLowering.lower(f, length))
    val names = GraphicsNames.collect(lowered).map(_.value)
    assertEquals(names.sorted, f.annotations.map(_.id.value).sorted)
    assertEquals(names.distinct.length, names.length)
    val rendered = svg(lowered)
    assertEquals(dataNames(rendered).sorted, names.sorted)
    dataNames(rendered).foreach { name =>
      val target = f.navigation.targetOf(AnnotationId.unsafe(name))
      assert(target.isDefined, s"$name does not resolve to an address")
      assert(f.textualTwin.contains(name), s"twin lacks $name")
    }
    assert(rendered.contains(ViewBasis.ResearcherReviewedFixture.label))

  test("Codex lowering is deterministic and an overflow lane is still drawn (V-L5)"):
    val st = relationState
    val spec =
      ok(CodexSpec.of(CodexLens.Overview.channels, ChannelBudget.All, ok(LanePolicy.of(1))))
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "lowering-suite",
        CodexCompiler.configurationChecksum(st, spec)
      )
    )
    val f = ok(CodexCompiler(provenance).compile(model, st, spec))
    assert(f.lanes.overflow.nonEmpty, "expected overflow under a one-lane policy")
    val a = ok(CodexLowering.lower(f, length))
    val b = ok(CodexLowering.lower(f, length))
    assertEquals(a, b)
    assertEquals(svg(a), svg(b))
    val names = GraphicsNames.collect(a).map(_.value).toSet
    f.lanes.overflow.foreach(id => assert(names.contains(id.value), id.value))

  test("the Reading lens has no annotation channels: the overlay carries no names"):
    val f = flow(CodexLens.Reading)
    assertEquals(f.annotations, Vector.empty)
    val lowered = ok(CodexLowering.lower(f, length))
    assertEquals(GraphicsNames.collect(lowered), Vector.empty)
    assertEquals(dataNames(svg(lowered)), Vector.empty)

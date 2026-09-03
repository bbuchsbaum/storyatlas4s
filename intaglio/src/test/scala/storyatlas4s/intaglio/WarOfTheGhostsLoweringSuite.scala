package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import _root_.intaglio.value
import munit.FunSuite
import storyatlas4s.intaglio.AtlasLowering.Layer
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** Lowering laws on the War of the Ghosts fixture: identity, determinism, twin consistency. */
class WarOfTheGhostsLoweringSuite extends FunSuite:
  private val model = Wog.model
  private val length = model.source.canonicalText.length
  private val levels = Vector(NarrativeLevel.Story, NarrativeLevel.Episode, NarrativeLevel.Scene)
  private val surfaceDetails =
    Vector(SurfaceDetail.Hidden, SurfaceDetail.Sentences, SurfaceDetail.Tokens)

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def state(layers: Set[RelationLayer] = Set.empty): CommonViewState =
    ok(CommonViewState.of(relationLayers = layers))

  private def scene(
      level: NarrativeLevel,
      st: CommonViewState = state(),
      surface: SurfaceDetail = SurfaceDetail.Hidden
  ): NarrativeScene =
    val spec = AtlasSpec(ZoomLevel(level, surface), ThreadPolicy.All(PositiveInt.unsafe(3)))
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

  private def surfaceMarks(scene: NarrativeScene): Vector[VisualPrimitive.SurfaceUnit] =
    scene.marks.collect { case mark: VisualPrimitive.SurfaceUnit => mark }

  private def allGrobs(grob: ig.Grob): Vector[ig.Grob] =
    grob +: grob.children.flatMap(allGrobs)

  private def namedChildren(
      elements: Vector[ig.DeviceElement],
      wanted: String
  ): Option[Vector[ig.DeviceElement]] =
    elements.iterator
      .map {
        case ig.DeviceElement.Group(Some(name), _, _, children) if name.value == wanted =>
          Some(children)
        case ig.DeviceElement.Group(_, _, _, children) => namedChildren(children, wanted)
        case _: ig.DeviceElement.Mark                  => None
      }
      .collectFirst { case Some(children) => children }

  private def namedPolygon(scene: ig.DeviceScene, wanted: String): Vector[ig.DevicePoint] =
    namedChildren(scene.elements, wanted)
      .flatMap(
        _.collectFirst {
          case ig.DeviceElement.Mark(ig.DevicePrimitive.Polyline(points, true, _, _)) => points
          case ig.DeviceElement.Mark(ig.DevicePrimitive.CompoundPolygon(rings, _, _)) =>
            rings.flatten
        }
      )
      .getOrElse(fail(s"no closed polygon for $wanted"))

  private val relationState = state(Set(RelationLayer.Causal, RelationLayer.Reference))

  test("every Atlas mark is lowered exactly once, named by its MarkId, at each zoom level"):
    levels.foreach { level =>
      surfaceDetails.foreach { detail =>
        val s = scene(level, relationState, detail)
        assert(s.marks.nonEmpty, s"no marks at $level/$detail")
        val names = GraphicsNames.collect(ok(AtlasLowering.lower(s, length))).map(_.value)
        val clue = s"$level/$detail"
        assertEquals(names.sorted, s.marks.map(_.identity.mark.value).sorted, clue)
        assertEquals(names.distinct.length, names.length, clue)
      }
    }

  test("surface detail is a cumulative, separately framed, text-free layout rail"):
    val hidden = scene(NarrativeLevel.Scene, relationState, SurfaceDetail.Hidden)
    val sentences = scene(NarrativeLevel.Scene, relationState, SurfaceDetail.Sentences)
    val tokens = scene(NarrativeLevel.Scene, relationState, SurfaceDetail.Tokens)
    val hiddenSurface = surfaceMarks(hidden)
    val sentenceSurface = surfaceMarks(sentences)
    val tokenSurface = surfaceMarks(tokens)

    assertEquals(hiddenSurface, Vector.empty)
    assert(sentenceSurface.nonEmpty)
    assert(sentenceSurface.forall(_.kind == SurfaceUnitKind.Sentence))
    assert(tokenSurface.exists(_.kind == SurfaceUnitKind.Token))
    assertEquals(
      sentenceSurface.map(_.identity.mark.value).sorted,
      tokenSurface
        .filter(_.kind == SurfaceUnitKind.Sentence)
        .map(_.identity.mark.value)
        .sorted
    )

    // Every plate has the same five layers in the same order, whatever the model contains, so a
    // reader of the output addresses the lane plot by what it is rather than by what happened to
    // be emitted.
    assertEquals(ok(AtlasLowering.lower(hidden, length)).grobs.length, 5)
    val hiddenPlotViewport =
      AtlasLowering.layerOf(ok(AtlasLowering.lower(hidden, length)), Layer.LanePlot).viewport
    Vector(sentences, tokens).foreach { compiled =>
      val lowered = ok(AtlasLowering.lower(compiled, length))
      assertEquals(lowered.grobs.length, 5)
      val rail = AtlasLowering.layerOf(lowered, Layer.SurfaceRail)
      val plot = AtlasLowering.layerOf(lowered, Layer.LanePlot)
      assert(rail.viewport.exists(_.clip == ig.Clip.On))
      assert(plot.viewport.exists(_.clip == ig.Clip.Off))
      assertNotEquals(rail.viewport, plot.viewport)
      assertEquals(
        plot.viewport,
        hiddenPlotViewport,
        "surface detail must not change ContextLane plot geometry"
      )

      val expectedSurfaceNames = surfaceMarks(compiled).map(_.identity.mark.value).sorted
      val railNames = rail.children.flatMap(_.name).map(_.value).sorted
      assertEquals(railNames, expectedSurfaceNames)
      rail.children.foreach { markGroup =>
        assert(
          !allGrobs(markGroup).exists(_.isInstanceOf[ig.Grob.Text]),
          s"surface mark ${markGroup.name.map(_.value)} copied text into the graphics rail"
        )
      }
    }

  test("surface rail preserves exact discourse spans and uses non-metric kind subrows"):
    val compiled = scene(NarrativeLevel.Scene, relationState, SurfaceDetail.Tokens)
    val sentence =
      surfaceMarks(compiled).find(_.kind == SurfaceUnitKind.Sentence).getOrElse(fail("no sentence"))
    val token =
      surfaceMarks(compiled).find(_.kind == SurfaceUnitKind.Token).getOrElse(fail("no token"))
    val lowered = ok(AtlasLowering.lower(compiled, length))
    val device = ok(ig.DeviceScene.fromScene(lowered, ig.DeviceContext.unsafe(1200.0, 400.0)))
    val surfaceIds = surfaceMarks(compiled).map(_.identity.mark.value).toSet
    val clip = device.elements
      .collectFirst {
        case ig.DeviceElement.Group(_, Some(candidate), _, children)
            if surfaceIds.exists(id => namedChildren(children, id).nonEmpty) =>
          candidate
      }
      .getOrElse(fail("surface rail has no independent clipped viewport"))

    def checkSpan(mark: VisualPrimitive.SurfaceUnit): Vector[ig.DevicePoint] =
      val points = namedPolygon(device, mark.identity.mark.value)
      val minX = points.map(_.x).min
      val maxX = points.map(_.x).max
      val expectedMin = clip.x + clip.width * mark.span.start.toDouble / length.toDouble
      val expectedMax = clip.x + clip.width * mark.span.endExclusive.toDouble / length.toDouble
      assert(
        math.abs(minX - expectedMin) <= 1.0e-6,
        s"${mark.identity.mark}: $minX != $expectedMin"
      )
      assert(
        math.abs(maxX - expectedMax) <= 1.0e-6,
        s"${mark.identity.mark}: $maxX != $expectedMax"
      )
      points

    val pointsById = surfaceMarks(compiled).map(mark => mark.identity.mark -> checkSpan(mark)).toMap
    val sentencePoints = pointsById(sentence.identity.mark)
    val tokenPoints = pointsById(token.identity.mark)
    val sentenceMidY = (sentencePoints.map(_.y).min + sentencePoints.map(_.y).max) / 2.0
    val tokenMidY = (tokenPoints.map(_.y).min + tokenPoints.map(_.y).max) / 2.0
    assertNotEquals(sentenceMidY, tokenMidY, "unit kind must not be encoded by colour alone")

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
      surfaceDetails.foreach { detail =>
        val a = ok(AtlasLowering.lower(scene(level, surface = detail), length))
        val b = ok(AtlasLowering.lower(scene(level, surface = detail), length))
        assertEquals(a, b, s"$level/$detail")
        assertEquals(svg(a), svg(b), s"$level/$detail")
      }
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

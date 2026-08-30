package storyatlas4s.app

import cats.data.NonEmptyVector
import munit.FunSuite
import storyatlas4s.edition.EditionSpec
import storyatlas4s.layout.MonospaceMeasurer
import storymodel4s.core.{Addressable, SurfaceUnitKind}
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.StoryRef
import storymodel4s.view.*

/** The state → scene wiring under the publication measurer (no DOM under Node): what the shell
  * draws is a pure function of the choice, marks past the horizon do not exist, the rail always
  * tiles the text, and a selection resolves in both artifacts through the navigation indexes.
  */
class AppCompilerSuite extends FunSuite:
  private val model = Wog.model
  private val text = model.source.canonicalText

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def compile(choice: ViewChoice): Compiled =
    ok(AppCompiler.compile(model, choice, MonospaceMeasurer.instance))

  private val omniscient = ViewChoice.initial.copy(measurer = MeasurerChoice.Monospace)

  private def rail(c: Compiled): String = c.pages.flatMap(_.lines).map(_.text).mkString

  test("the omniscient Overview/Scene view has marks, pieces, one overlay per page, receipts"):
    val c = compile(omniscient)
    assert(c.scene.marks.nonEmpty)
    assert(c.pieces > 0)
    assertEquals(c.pages.length, c.placed.pages.length)
    assert(c.pages.length > 1)
    c.pages.foreach(p => assert(p.overlay.startsWith("<svg "), s"page ${p.index}"))
    assertEquals(c.atlasNames, c.scene.marks.length)
    assertEquals(
      c.scene.marks.map(_.identity.mark).toSet,
      c.scene.navigation.addressOf.keySet
    )
    assertEquals(
      c.placed.annotationFragments.map(_.id).toSet,
      c.fragmentTargets.names
    )
    val receipts = c.receipts.toMap
    assertEquals(receipts("sourceChecksum"), model.source.canonicalChecksum.hex)
    assertEquals(receipts("measurerInUse"), MonospaceMeasurer.instance.name)
    assertEquals(receipts("layout.measurer"), MonospaceMeasurer.instance.name)
    assertEquals(receipts("atlasMarks"), c.scene.marks.length.toString)
    assertEquals(receipts("codexAnnotationFragments"), c.pieces.toString)
    assert(receipts("compilerVersion").startsWith("storymodel4s@"))
    assert(receipts("horizon").startsWith("omniscient"))

  test("the rail tiles the canonical text under every lens, level, and horizon (V-T2 input)"):
    val horizons = Vector(
      EpistemicHorizon.Omniscient,
      EpistemicHorizon.ReaderAt(0),
      EpistemicHorizon.ReaderAt(text.length / 2),
      EpistemicHorizon.ReaderAt(text.length)
    )
    for
      lens <- EditionSpec.lenses
      level <- EditionSpec.levels
      horizon <- horizons
    do
      val zoom = omniscient.zoom.copy(narrative = level)
      val c = compile(omniscient.copy(lens = lens, zoom = zoom, horizon = horizon))
      assertEquals(rail(c), text, s"$lens/$zoom/$horizon")
      assertEquals(c.pages.flatMap(_.lines).length, c.placed.lines.length)

  test("marks and pieces not yet visible at the horizon do not exist; counts are monotone"):
    val full = compile(omniscient)
    val none = compile(omniscient.copy(horizon = EpistemicHorizon.ReaderAt(0)))
    val half = compile(omniscient.copy(horizon = EpistemicHorizon.ReaderAt(text.length / 2)))
    assertEquals(none.scene.marks.length, 0)
    assertEquals(none.pieces, 0)
    assert(half.scene.marks.length > 0)
    assert(half.scene.marks.length < full.scene.marks.length)
    assert(half.pieces > 0)
    assert(half.pieces < full.pieces)
    assert(none.receipts.toMap.apply("horizon").startsWith("reader at 0 of"))
    // The overlay of a horizon-empty codex names nothing; the atlas draws nothing.
    none.pages.foreach(p => assert(!p.overlay.contains("data-name"), s"page ${p.index}"))
    assert(!none.atlasSvg.contains("data-name"))

  test("the Reading lens has no annotation channel and so no piece, but the same pages"):
    val reading = compile(omniscient.copy(lens = CodexLens.Reading))
    val overview = compile(omniscient)
    assertEquals(reading.pieces, 0)
    assertEquals(reading.rows, Vector.empty)
    assertEquals(reading.pages.map(_.lines.map(_.text)), overview.pages.map(_.lines.map(_.text)))
    assertEquals(reading.receipts.toMap.apply("codexChannels"), "none")

  test("selecting a landmark's address places it on-mark in both compiled artifacts"):
    val base = compile(omniscient)
    val landmark = base.scene.marks
      .collectFirst { case m: VisualPrimitive.Landmark => m }
      .getOrElse(fail("no landmark at Scene zoom"))
    val address = landmark.address
    val c = compile(omniscient.copy(selection = Set(address), focus = Some(address)))
    assertEquals(c.state.selection, Set(address))
    assertEquals(c.state.focus, Some(address))
    assert(c.selectedMarks.contains(landmark.identity.mark))
    assert(c.focusedMarks.contains(landmark.identity.mark))
    assert(c.selectedProxyMarks.isEmpty)
    assert(c.focusedProxyMarks.isEmpty)
    c.atlasPlacements match
      case Vector((a, SelectionPlacement.OnMark(marks))) =>
        assertEquals(a, address)
        assert(marks.toVector.contains(landmark.identity.mark))
      case other => fail(s"expected one on-mark placement, got $other")
    c.codexPlacements match
      case Vector((a, SelectionPlacement.OnMark(annotations))) =>
        assertEquals(a, address)
        assertEquals(annotations.toVector, c.flow.navigation.annotationsFor(address))
      case other => fail(s"expected one Codex on-mark placement, got $other")
    assert(c.selectedFragments.nonEmpty)
    assertEquals(c.focusedFragments, c.selectedFragments)
    assert(c.selectedProxyFragments.isEmpty)
    assert(c.focusedProxyFragments.isEmpty)
    assert(
      (c.atlasInteractions ++ c.codexInteractions).forall(
        _.representation == SemanticRepresentation.Direct(address)
      )
    )
    val selectedLines = c.pages.flatMap(_.lines).filter(_.selected)
    assert(selectedLines.nonEmpty)
    assertEquals(c.pages.flatMap(_.lines).filter(_.focused), selectedLines)
    // The selected pieces sit on exactly the selected lines.
    val linesWithSelectedPiece = c.placed.lines
      .filter(_.annotations.exists(p => c.selectedFragments.contains(p.id)))
      .map(_.text.id.value)
      .toSet
    assertEquals(selectedLines.map(_.id).toSet, linesWithSelectedPiece)
    // Every piece resolves to an address through the navigation index, never a renderer name.
    assertEquals(c.fragmentTargets.names, c.placed.annotationFragments.map(_.id).toSet)
    // The same address under the Reading lens has no honest visual anchor.
    val reading = compile(
      omniscient.copy(
        lens = CodexLens.Reading,
        selection = Set(address),
        focus = Some(address)
      )
    )
    assertEquals(
      reading.codexPlacements,
      Vector(address -> SelectionPlacement.OffProjection)
    )

  test("V-L2: Story zoom uses a visible ancestor but will not climb from hidden evidence"):
    val address = Addressable[StoryRef]
      .address(StoryRef.Situation(Wog.S.battle))
    val story = compile(
      omniscient.copy(
        zoom = omniscient.zoom.copy(narrative = NarrativeLevel.Story),
        selection = Set(address)
      )
    )
    story.atlasPlacements match
      case Vector((a, SelectionPlacement.ViaAncestor(ancestor))) =>
        assertEquals(a, address)
        assertNotEquals(ancestor, address)
        val expected = story.scene.navigation.marksFor(ancestor).toSet
        assert(expected.nonEmpty)
        assertEquals(story.selectedMarks, Set.empty)
        assertEquals(story.selectedProxyMarks, expected)
        assertEquals(
          story.atlasInteractions.map(_.representation).distinct,
          Vector(SemanticRepresentation.Proxy(address, ancestor))
        )
        val court = story.codexProxyCourt.getOrElse(fail("no real-fragment Codex proxy court"))
        assertEquals(court.original, address)
        assertEquals(court.visible, ancestor)
        assert(court.targets.names.nonEmpty)
        assert(
          court.interactions.exists {
            case InteractionDecoration(
                  _,
                  InteractionRole.Selection,
                  SemanticRepresentation.Proxy(`address`, `ancestor`)
                ) =>
              true
            case _ => false
          }
        )

        val composite = compile(
          omniscient.copy(
            zoom = omniscient.zoom.copy(narrative = NarrativeLevel.Story),
            selection = Set(address, ancestor),
            focus = Some(address)
          )
        )
        val compositeCourt = composite.codexProxyCourt.getOrElse(fail("no composite court"))
        val sharedTarget = compositeCourt.interactions
          .groupBy(_.target)
          .values
          .find(states =>
            states.exists(_.representation == SemanticRepresentation.Direct(ancestor)) &&
              states.exists(_.representation == SemanticRepresentation.Proxy(address, ancestor))
          )
        assert(sharedTarget.nonEmpty, "one real fragment is direct and proxy simultaneously")
      case other => fail(s"expected one Atlas via-ancestor placement, got $other")

    val hiddenAt = model
      .supporting(StoryRef.Situation(Wog.S.battle))
      .getOrElse(fail("battle has no source support"))
      .minSpan
      .start
    val hidden = compile(
      omniscient.copy(
        zoom = omniscient.zoom.copy(narrative = NarrativeLevel.Story),
        horizon = EpistemicHorizon.ReaderAt(hiddenAt),
        selection = Set(address)
      )
    )
    assert(hidden.scene.marks.nonEmpty, "the horizon retains visible non-selected marks")
    assertEquals(
      hidden.atlasPlacements,
      Vector(address -> SelectionPlacement.OffProjection)
    )
    assertEquals(
      hidden.codexPlacements,
      Vector(address -> SelectionPlacement.OffProjection)
    )

  test("a selected address past the horizon is off-projection, shown as text"):
    val base = compile(omniscient)
    val last = base.scene.marks
      .collect { case m: VisualPrimitive.Landmark => m }
      .maxByOption(_.at.x)
      .getOrElse(fail("no landmark"))
    val c = compile(
      omniscient.copy(horizon = EpistemicHorizon.ReaderAt(1), selection = Set(last.address))
    )
    assertEquals(c.atlasPlacements, Vector(last.address -> SelectionPlacement.OffProjection))
    assertEquals(c.codexPlacements, Vector(last.address -> SelectionPlacement.OffProjection))
    assert(c.selectedMarks.isEmpty)
    assert(c.selectedProxyMarks.isEmpty)
    assert(c.selectedFragments.isEmpty)
    assert(c.selectedProxyFragments.isEmpty)

  test("direct, proxy, and off-projection decorations obey one cross-face law"):
    val original = Addressable[StoryRef].address(StoryRef.Situation(Wog.S.battle))
    val visible = Addressable[StoryRef].address(StoryRef.Segment(Wog.G.sc2c))
    val choice = omniscient.copy(selection = Set(original), focus = Some(original))

    def interactions(
        placement: SelectionPlacement[String],
        target: String
    ): Vector[InteractionDecoration[String, storymodel4s.core.Address]] =
      val expected = placement match
        case SelectionPlacement.OnMark(marks)  => marks.toVector.map(_ -> original)
        case SelectionPlacement.ViaAncestor(_) => Vector(target -> visible)
        case SelectionPlacement.OffProjection  => Vector.empty
      val index = ok(
        RenderedTargetIndex.build(
          InteractionSurface.Atlas,
          expected,
          expected.map(_._1),
          identity
        )
      )
      ok(
        AppCompiler.interactionsFor(
          choice,
          Map(original -> placement),
          mark => Vector(mark),
          ancestor => if ancestor == visible then Vector(target) else Vector.empty,
          index,
          identity,
          identity
        )
      )

    val direct = interactions(SelectionPlacement.OnMark(NonEmptyVector.one("direct")), "unused")
    assertEquals(direct.map(_.role).toSet, Set(InteractionRole.Selection, InteractionRole.Focus))
    assertEquals(
      direct.map(_.representation).distinct,
      Vector(SemanticRepresentation.Direct(original))
    )

    val atlasProxy = interactions(SelectionPlacement.ViaAncestor(visible), "atlas-proxy")
    val codexProxy = interactions(SelectionPlacement.ViaAncestor(visible), "codex-proxy")
    assertEquals(
      atlasProxy.map(value => value.role -> value.representation),
      codexProxy.map(value => value.role -> value.representation)
    )
    assertEquals(
      atlasProxy.map(_.representation).distinct,
      Vector(SemanticRepresentation.Proxy(original, visible))
    )
    assertEquals(
      interactions(SelectionPlacement.OffProjection, "unused"),
      Vector.empty
    )
    val emptyIndex = ok(
      RenderedTargetIndex.build[String](
        InteractionSurface.Atlas,
        Vector.empty,
        Vector.empty,
        identity
      )
    )
    assertEquals(
      AppCompiler.interactionsFor[String, String](
        choice,
        Map(original -> SelectionPlacement.ViaAncestor(visible)),
        mark => Vector(mark),
        _ => Vector.empty,
        emptyIndex,
        identity,
        identity
      ),
      Left(InteractionError.MissingProxyTarget(original, visible))
    )

  test("interaction target closure rejects phantom, partial, duplicate, and wrong identities"):
    val original = Addressable[StoryRef].address(StoryRef.Situation(Wog.S.battle))
    val visible = Addressable[StoryRef].address(StoryRef.Segment(Wog.G.sc2c))
    val choice = omniscient.copy(selection = Set(original))
    val exact = ok(
      RenderedTargetIndex.build(
        InteractionSurface.Atlas,
        Vector("known" -> original),
        Vector("known"),
        identity
      )
    )

    val oneMark = Map(original -> SelectionPlacement.OnMark(NonEmptyVector.one("m1")))
    assertEquals(
      AppCompiler.interactionsFor(
        choice,
        oneMark,
        _ => Vector("ghost"),
        _ => Vector.empty,
        exact,
        identity,
        identity
      ),
      Left(
        InteractionError.PhantomRenderedTarget(
          InteractionSurface.Atlas,
          "ghost"
        )
      )
    )

    val twoMarks = Map(original -> SelectionPlacement.OnMark(NonEmptyVector.of("m1", "m2")))
    assertEquals(
      AppCompiler.interactionsFor(
        choice,
        twoMarks,
        mark => if mark == "m1" then Vector("known") else Vector.empty,
        _ => Vector.empty,
        exact,
        identity,
        identity
      ),
      Left(InteractionError.MissingPlacementMemberTarget(original, "m2"))
    )

    assertEquals(
      AppCompiler.interactionsFor[String, String](
        choice,
        Map[storymodel4s.core.Address, SelectionPlacement[String]](
          original -> SelectionPlacement.ViaAncestor(visible)
        ),
        _ => Vector.empty,
        _ => Vector("ghost"),
        exact,
        identity,
        identity
      ),
      Left(
        InteractionError.PhantomRenderedTarget(
          InteractionSurface.Atlas,
          "ghost"
        )
      )
    )

    assertEquals(
      RenderedTargetIndex.build(
        InteractionSurface.Atlas,
        Vector("known" -> original),
        Vector("known", "known"),
        identity
      ),
      Left(
        InteractionError.DuplicateRenderedTarget(
          InteractionSurface.Atlas,
          "known",
          2
        )
      )
    )

    val wrongIdentity = ok(
      RenderedTargetIndex.build(
        InteractionSurface.Atlas,
        Vector("known" -> visible),
        Vector("known"),
        identity
      )
    )
    assertEquals(
      AppCompiler.interactionsFor(
        choice,
        oneMark,
        _ => Vector("known"),
        _ => Vector.empty,
        wrongIdentity,
        identity,
        identity
      ),
      Left(
        InteractionError.TargetIdentityMismatch(
          InteractionSurface.Atlas,
          "known",
          original,
          visible
        )
      )
    )

  test("the compiled view is a pure function of the choice (V-D1)"):
    val episodeZoom = omniscient.zoom.copy(narrative = NarrativeLevel.Episode)
    val a = compile(omniscient.copy(zoom = episodeZoom))
    val b = compile(omniscient.copy(zoom = episodeZoom))
    assertEquals(a.atlasSvg, b.atlasSvg)
    assertEquals(a.pages, b.pages)
    assertEquals(a.receipts, b.receipts)
    assertEquals(a.scene.textualTwin, b.scene.textualTwin)
    assertEquals(a.placed.textualTwin, b.placed.textualTwin)

  test("the configured two-axis zoom states compile and change representation"):
    val story = compile(
      omniscient.copy(zoom = ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Hidden))
    )
    val episode = compile(
      omniscient.copy(zoom = ZoomLevel(NarrativeLevel.Episode, SurfaceDetail.Hidden))
    )
    val scene = compile(
      omniscient.copy(zoom = ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden))
    )
    assert(story.scene.marks.nonEmpty)
    assert(!story.scene.marks.exists(_.isInstanceOf[VisualPrimitive.Landmark]))
    assert(scene.scene.marks.exists(_.isInstanceOf[VisualPrimitive.Landmark]))
    assertEquals(story.receipts.toMap.apply("atlasZoom"), "Story/Hidden")
    assertEquals(episode.receipts.toMap.apply("atlasZoom"), "Episode/Hidden")
    EditionSpec.levels.foreach { level =>
      val compiled = EditionSpec.surfaceDetails.map(surface =>
        compile(omniscient.copy(zoom = ZoomLevel(level, surface)))
      )
      val counts = compiled.map(_.scene.marks.length)
      assert(counts(0) < counts(1), s"$level Hidden/Sentences must differ: $counts")
      assert(counts(1) < counts(2), s"$level Sentences/Tokens must differ: $counts")
      assertEquals(
        compiled.map(_.receipts.toMap.apply("atlasZoom")),
        EditionSpec.surfaceDetails.map(surface => s"$level/$surface")
      )
    }

  test("surface refinement conserves a focused token Address through compiled placement"):
    val tokenView = compile(
      omniscient.copy(zoom = ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Tokens))
    )
    val token = tokenView.scene.marks
      .collectFirst {
        case mark: VisualPrimitive.SurfaceUnit if mark.kind == SurfaceUnitKind.Token => mark
      }
      .getOrElse(fail("no token surface mark"))
    val tracked = omniscient.copy(
      zoom = ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Tokens),
      selection = Set(token.address),
      focus = Some(token.address),
      horizon = EpistemicHorizon.ReaderAt(text.length)
    )
    val tokens = compile(tracked)
    val sentences = compile(
      tracked.copy(zoom = tracked.zoom.copy(surface = SurfaceDetail.Sentences))
    )
    val hidden = compile(tracked.copy(zoom = tracked.zoom.copy(surface = SurfaceDetail.Hidden)))

    assertEquals(tokens.choice.selection, sentences.choice.selection)
    assertEquals(tokens.choice.focus, sentences.choice.focus)
    assertEquals(tokens.choice.horizon, sentences.choice.horizon)
    tokens.atlasPlacements match
      case Vector((address, SelectionPlacement.OnMark(marks))) =>
        assertEquals(address, token.address)
        assert(marks.toVector.nonEmpty)
      case other => fail(s"expected token on-mark, got $other")
    sentences.atlasPlacements match
      case Vector((address, SelectionPlacement.ViaAncestor(ancestor))) =>
        assertEquals(address, token.address)
        assertNotEquals(ancestor, address)
        assert(sentences.scene.navigation.marksFor(ancestor).nonEmpty)
      case other => fail(s"expected sentence ancestor, got $other")
    assertEquals(
      hidden.atlasPlacements,
      Vector(token.address -> SelectionPlacement.OffProjection)
    )

  test("the playhead snaps to code point boundaries and clamps to the text"):
    val sample = "ab😀cd"
    assertEquals(Playhead.snap(sample, -5), 0)
    assertEquals(Playhead.snap(sample, 0), 0)
    assertEquals(Playhead.snap(sample, 2), 2)
    assertEquals(Playhead.snap(sample, 3), 4) // between the surrogates → after the pair
    assertEquals(Playhead.snap(sample, 4), 4)
    assertEquals(Playhead.snap(sample, 99), sample.length)
    (0 to text.length).foreach { offset =>
      val snapped = Playhead.snap(text, offset)
      assert(EvidenceVisibility.validateHorizon(text, EpistemicHorizon.ReaderAt(snapped)).isRight)
    }
    assertEquals(Playhead.position(text, EpistemicHorizon.Omniscient), text.length)
    assertEquals(Playhead.position(text, EpistemicHorizon.ReaderAt(7)), 7)

package storyatlas4s.app

import munit.FunSuite
import storyatlas4s.edition.EditionSpec
import storyatlas4s.layout.MonospaceMeasurer
import storymodel4s.core.Addressable
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
      c.placed.annotationFragments.map(_.id.value).toSet,
      c.fragmentTargets.keySet
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
      val c = compile(omniscient.copy(lens = lens, level = level, horizon = horizon))
      assertEquals(rail(c), text, s"$lens/$level/$horizon")
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
    val c = compile(omniscient.copy(selection = Set(address)))
    assertEquals(c.state.selection, Set(address))
    assert(c.selectedMarks.contains(landmark.identity.mark.value))
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
    val selectedLines = c.pages.flatMap(_.lines).filter(_.selected)
    assert(selectedLines.nonEmpty)
    // The selected pieces sit on exactly the selected lines.
    val linesWithSelectedPiece = c.placed.lines
      .filter(_.annotations.exists(p => c.selectedFragments.contains(p.id.value)))
      .map(_.text.id.value)
      .toSet
    assertEquals(selectedLines.map(_.id).toSet, linesWithSelectedPiece)
    // Every piece resolves to an address through the navigation index, never a renderer name.
    assertEquals(c.fragmentTargets.keySet, c.placed.annotationFragments.map(_.id.value).toSet)
    // The same address under the Reading lens has no honest visual anchor.
    val reading = compile(omniscient.copy(lens = CodexLens.Reading, selection = Set(address)))
    assertEquals(
      reading.codexPlacements,
      Vector(address -> SelectionPlacement.OffProjection)
    )

  test("V-L2: Story zoom uses a visible ancestor but will not climb from hidden evidence"):
    val address = Addressable[StoryRef]
      .address(StoryRef.Situation(Wog.S.battle))
    val story = compile(
      omniscient.copy(level = NarrativeLevel.Story, selection = Set(address))
    )
    story.atlasPlacements match
      case Vector((a, SelectionPlacement.ViaAncestor(ancestor))) =>
        assertEquals(a, address)
        assertNotEquals(ancestor, address)
        assert(story.scene.navigation.marksFor(ancestor).nonEmpty)
      case other => fail(s"expected one Atlas via-ancestor placement, got $other")

    val hiddenAt = model
      .supporting(StoryRef.Situation(Wog.S.battle))
      .getOrElse(fail("battle has no source support"))
      .minSpan
      .start
    val hidden = compile(
      omniscient.copy(
        level = NarrativeLevel.Story,
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

  test("the compiled view is a pure function of the choice (V-D1)"):
    val a = compile(omniscient.copy(level = NarrativeLevel.Episode))
    val b = compile(omniscient.copy(level = NarrativeLevel.Episode))
    assertEquals(a.atlasSvg, b.atlasSvg)
    assertEquals(a.pages, b.pages)
    assertEquals(a.receipts, b.receipts)
    assertEquals(a.scene.textualTwin, b.scene.textualTwin)
    assertEquals(a.placed.textualTwin, b.placed.textualTwin)

  test("the three zoom levels compile and differ in what they draw"):
    val story = compile(omniscient.copy(level = NarrativeLevel.Story))
    val episode = compile(omniscient.copy(level = NarrativeLevel.Episode))
    val scene = compile(omniscient.copy(level = NarrativeLevel.Scene))
    assert(story.scene.marks.nonEmpty)
    assert(!story.scene.marks.exists(_.isInstanceOf[VisualPrimitive.Landmark]))
    assert(scene.scene.marks.exists(_.isInstanceOf[VisualPrimitive.Landmark]))
    assertEquals(story.receipts.toMap.apply("atlasZoom"), "Story/Hidden")
    assertEquals(episode.receipts.toMap.apply("atlasZoom"), "Episode/Hidden")

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

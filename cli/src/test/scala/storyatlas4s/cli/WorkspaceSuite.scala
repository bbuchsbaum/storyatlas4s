package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import munit.FunSuite
import storyatlas4s.edition.{EditionSpec, Focus}
import storyatlas4s.intaglio.{AtlasLowering, PlateBox}
import storyatlas4s.layout.*
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** Laws of the two-pane workspace: the words, the shared selection, and the region that is not the
  * text.
  *
  * The reading pane is the one surface where the model's failures land on real prose, so the laws
  * that matter are the ones that stop it lying: the exact text survives being marked, a selection
  * is carried by both faces or reported as absent from one, an absence with no discourse position
  * never acquires an offset, and a channel that could not be compiled says so rather than reading
  * as a channel with nothing in it.
  */
class WorkspaceSuite extends FunSuite:

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def bundle(violations: Vector[Violation]): DraftModel =
    DraftModel.of(
      Wog.draft,
      ValidationOutcome(ValidationReport(violations), validated = None),
      DerivationRecord.NotSupplied
    )

  /** One situation of the fixture, so a violation can cite a subject that really does have spans.
    *
    * Taken from a compilation with no violations at all, because a violation's subject has to exist
    * before the violation can name it.
    */
  private val situation: Address =
    val seed = bundle(Vector.empty)
    val spec = AtlasSpec(EditionSpec.workspaceZoom, ThreadPolicy.All(PositiveInt.unsafe(3)))
    val state = ok(CommonViewState.of(relationLayers = EditionSpec.relationLayers))
    val provenance = ok(
      ViewProvenance.draftBuild(
        seed,
        "workspace-suite",
        AtlasCompiler.configurationChecksum(state, spec)
      )
    )
    ok(AtlasCompiler(provenance).compileDraft(seed, state, spec)).marks
      .collectFirst { case l: VisualPrimitive.Landmark => l.identity.address }
      .getOrElse(fail("the fixture has situations"))

  /** One violation that cites a subject with spans, and one that cites nothing.
    *
    * The pair is the point: the first must land on exactly its subject's words, the second must
    * never acquire an offset at all.
    */
  private val violations: Vector[Violation] = Vector(
    Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments"),
    Violation(
      "hierarchy.situation-root-reachable",
      Severity.Error,
      "situations/subject",
      "not under the primary root",
      Some(situation)
    )
  )

  private val draft: DraftModel = bundle(violations)

  private val length = draft.model.source.canonicalText.length

  private def sceneOf(state: CommonViewState): NarrativeScene =
    val spec = AtlasSpec(EditionSpec.workspaceZoom, ThreadPolicy.All(PositiveInt.unsafe(3)))
    val provenance = ok(
      ViewProvenance.draftBuild(
        draft,
        "workspace-suite",
        AtlasCompiler.configurationChecksum(state, spec)
      )
    )
    ok(AtlasCompiler(provenance).compileDraft(draft, state, spec))

  private def flowOf(state: CommonViewState): CodexFlow =
    val spec = ok(CodexSpec.forLens(EditionSpec.workspaceLens, ChannelBudget.All))
    val provenance = ok(
      ViewProvenance.draftBuild(
        draft,
        "workspace-suite",
        CodexCompiler.configurationChecksum(state, spec)
      )
    )
    ok(CodexCompiler(provenance).compileDraft(draft, state, spec))

  private def stateFor(focus: Option[Focus.Chosen]): CommonViewState =
    ok(
      CommonViewState.of(
        selection = focus.map(_.address).toSet,
        focus = focus.map(_.address),
        relationLayers = EditionSpec.relationLayers
      )
    )

  private val unfocused = ok(CommonViewState.of(relationLayers = EditionSpec.relationLayers))
  private val chosen: Option[Focus.Chosen] = Focus.choose(sceneOf(unfocused))
  private val state = stateFor(chosen)
  private val scene = sceneOf(state)
  private val flow = flowOf(state)

  /** The surface-bearing scene the reading pane's rows come from. */
  private val sentences: Vector[VisualPrimitive.SurfaceUnit] =
    val spec = AtlasSpec(
      ZoomLevel(EditionSpec.workspaceZoom.narrative, SurfaceDetail.Sentences),
      ThreadPolicy.All(PositiveInt.unsafe(3))
    )
    val provenance = ok(
      ViewProvenance.draftBuild(
        draft,
        "workspace-suite",
        AtlasCompiler.configurationChecksum(state, spec)
      )
    )
    ok(AtlasCompiler(provenance).compileDraft(draft, state, spec)).marks.collect {
      case m: VisualPrimitive.SurfaceUnit => m
    }

  private def plate: String =
    val box = ok(
      PlateBox.of(EditionSpec.workspaceAtlasWidthPx, EditionSpec.workspaceAtlasHeightPx)
    )
    val lowered = ok(AtlasLowering.lower(scene, length, box))
    val options = ok(
      SvgOptions(EditionSpec.workspaceAtlasWidthPx, EditionSpec.workspaceAtlasHeightPx)
    )
    ok(SvgRenderer.render(lowered, options)).value

  private def html: String =
    ok(Workspace.render(flow, scene, sentences, plate, chosen, "workspace-suite"))

  private val tag = """<[^>]*>""".r
  private val textRail = """(?s)<p class="prose">(.*?)</p>""".r

  private def unescape(value: String): String =
    value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

  /** Every character of the text rails, tags removed, in document order. */
  private def railText(document: String): String =
    textRail
      .findAllMatchIn(document)
      .map(m => unescape(tag.replaceAllIn(m.group(1), "")))
      .mkString

  // ------------------------------------------------------------------ the words

  test("marking the selection does not disturb one character of the exact text (V-T2)"):
    val document = html
    assertEquals(railText(document), flow.source.canonicalText)
    // And the marking really happened, so the law is not passing vacuously.
    assert(document.contains("sl\"") || document.contains("sl "), "no selection was marked")

  test("the words are set in a real face at a reading size and a reading leading"):
    val document = html
    // A generic `monospace` at 1.20 leading is a specimen, not a text; the pane's prose is a named
    // serif at 17px and 1.62, which is looser than the metadata describing it.
    assert(document.contains(".prose { margin: 0; font: 17px/1.62 Palatino"), "prose face")
    assert(!document.contains("font-family: monospace"), "the prose is still generic monospace")
    // The plate is never scaled to fit: a browser downscale would drop its type below the floor
    // its own laws enforce.
    assert(!document.contains(".plate svg { display: block; width"), "the plate is scaled")

  test("the selected words are exactly the support of the annotations the focus resolves to"):
    val selected = Focus.annotations(flow, chosen)
    assert(selected.nonEmpty, "the focus resolves to no annotation")
    val expected = flow.annotations
      .filter(a => selected.contains(a.id))
      .flatMap(_.support.spans.toVector)
      .map(span => ok(span.slice(flow.source.canonicalText)))
      .toSet
    val marked = """<span class="[^"]*\\bsl\\b[^"]*">(.*?)</span>""".r
      .findAllMatchIn(html)
      .map(m => unescape(m.group(1)))
      .toSet
    // Every marked run is a run the model actually supports; pieces are cut per line, so a marked
    // run is a subsequence of its annotation's support rather than always equal to it.
    marked.foreach(run =>
      assert(
        expected.exists(_.contains(run)),
        s"marked '$run' is not supported by any selected annotation"
      )
    )

  // ------------------------------------------------------------------ the shared selection

  test("the focus rule picks a situation outside the narrated world, and the first such one"):
    val survey = sceneOf(unfocused)
    val kinds =
      survey.marks.collect { case b: VisualPrimitive.ContextBand => b.lane -> b.kind }.toMap
    val ordered = survey.marks
      .collect { case l: VisualPrimitive.Landmark => l }
      .sortBy(l => (l.at.x, l.identity.mark.value))
    chosen match
      case None    => fail("the fixture has speech frames; the rule should find one")
      case Some(c) =>
        assertNotEquals(kinds.get(c.lane), Some(ContextKind.NarratedWorld))
        val index = ordered.indexWhere(_.identity.mark == c.mark)
        assert(index >= 0, "the chosen mark is not a landmark of this scene")
        // Everything earlier in discourse order is either narrated or on an unnamed lane, so the
        // rule really did take the first qualifying situation rather than a convenient one.
        ordered.take(index).foreach { earlier =>
          assert(
            kinds.get(earlier.at.lane).forall(_ == ContextKind.NarratedWorld),
            s"${earlier.identity.mark.value} at x=${earlier.at.x} also qualifies and comes first"
          )
        }

  test("one address is carried by both faces, and each says what it could do with it"):
    val document = html
    chosen match
      case None        => fail("no focus")
      case Some(focus) =>
        assert(document.contains(focus.address.render), "the workspace does not name the address")
        assert(document.contains(Focus.rule), "the workspace does not print the rule")
        val codex = Focus.placement(flow.selectionPlacements.get(focus.address))
        val atlas = Focus.placement(scene.selectionPlacements.get(focus.address))
        assert(document.contains(codex), s"the Codex placement '$codex' is not stated")
        assert(document.contains(atlas), s"the Atlas placement '$atlas' is not stated")
        // Both faces resolved it directly here; neither may claim a state it did not reach.
        assertEquals(codex, atlas)

  test("the plate draws the focus and states what it did with it"):
    val marks = Focus.marks(scene, chosen).map(_.value)
    assert(marks.nonEmpty, "the focus resolves to no mark")
    val svg = plate
    marks.foreach(mark => assert(svg.contains(mark), s"$mark is not drawn"))
    assert(svg.contains("shared selection:"), "the plate does not state the shared selection")
    chosen.foreach(c =>
      assert(svg.contains(c.address.render), "the plate does not name the selected address")
    )

  // ------------------------------------------------------------------ what is not the text

  test("an absence with no discourse position never acquires one"):
    val ledger = flow.draft.getOrElse(fail("a draft flow carries a ledger"))
    assert(ledger.unplaced.nonEmpty, "the fixture has a violation with no address")
    val document = html
    // Every unplaced record is listed, by subject, in the region that is not the text.
    ledger.unplaced.foreach { entry =>
      assert(document.contains(entry.subject.render), s"${entry.subject.render} is not listed")
      assert(document.contains(entry.reason.render), s"${entry.reason.render} is not stated")
    }
    // And none of them is an annotation, so none of them can be painted on a word.
    val ids = flow.annotations.map(_.id).toSet
    assertEquals(ledger.marked.filterNot(ids.contains), Vector.empty)
    assertEquals(ledger.total, ledger.marked.length + ledger.unplaced.length)

  test("the pane paints what discriminates, and counts what does not"):
    val document = html
    // The uniform channels are counted in the gutter, never painted on the words: sixty-five
    // identical marks on the prose would say nothing about the story and would hide what does.
    val frames = Reading.frames(scene)
    val painted = Reading.painted(flow, frames, Focus.annotations(flow, chosen))
    // Only three treatments exist, and each is accounted for by the annotations that may carry it.
    assertEquals(
      painted.map(_._2).distinct.sorted,
      Vector(Reading.Mention, Reading.Selected, Reading.Speech).sorted
    )
    val mentions = flow.annotations
      .filter(_.kind == AnnotationKind.Entity)
      .flatMap(_.support.spans.toVector)
    assertEquals(painted.count(_._2 == Reading.Mention), mentions.length)
    val speech = flow.annotations
      .filter(a =>
        a.kind == AnnotationKind.Context &&
          frames.get(a.target).exists(_ != ContextKind.NarratedWorld)
      )
      .flatMap(_.support.spans.toVector)
    assertEquals(painted.count(_._2 == Reading.Speech), speech.length)
    // The uniform channels reach the words through no treatment at all.
    val uniform = Set(AnnotationKind.Claim, AnnotationKind.UnsatisfiedLaw)
    assert(flow.annotations.count(a => uniform.contains(a.kind)) > 60, "the fixture is uniform")
    assert(document.contains("Painted on the words"), "the pane does not declare what it paints")
    assert(document.contains("class=\"tal"), "the uniform channels are not tallied in a gutter")

  test("the rows tile the canonical text and carry the sentence identities"):
    val rows = Reading.rows(sentences, flow.source.canonicalText.length)
    assert(rows.nonEmpty)
    assertEquals(rows.head.span.start, 0)
    assertEquals(rows.last.span.endExclusive, flow.source.canonicalText.length)
    rows.sliding(2).foreach {
      case Vector(a, b) => assertEquals(a.span.endExclusive, b.span.start, "the rows leave a gap")
      case _            => ()
    }
    val document = html
    rows.foreach(row => assert(document.contains(row.mark.value), s"${row.mark.value} is unnamed"))

  test("overlapping treatments segment rather than nest, and lose no character"):
    val text = flow.source.canonicalText
    val painted = Reading.painted(flow, Reading.frames(scene), Focus.annotations(flow, chosen))
    Reading.rows(sentences, text.length).foreach { row =>
      val runs = Reading.runs(row, text, painted)
      assertEquals(
        runs.map(_.text).mkString,
        text.substring(row.span.start, row.span.endExclusive),
        s"row ${row.ordinal} does not reassemble"
      )
      runs.foreach(run => assert(run.text.nonEmpty, "an empty run was emitted"))
    }

  test("the workspace shows every projection the edition built"):
    val document = html
    EditionSpec.zoomLevels.foreach { zoom =>
      val stem =
        s"atlas-${zoom.narrative.toString.toLowerCase}-${zoom.surface.toString.toLowerCase}"
      assert(document.contains(stem), s"$stem is not reachable from the workspace")
    }
    EditionSpec.lenses.foreach(lens =>
      assert(document.contains(s"codex-${lens.toString.toLowerCase}.html"), lens.toString)
    )

  test("the cast answers who is in this story and where, from the model's own labels"):
    val rows = Reading.rows(sentences, flow.source.canonicalText.length)
    val cast = Reading.cast(flow, scene, rows)
    val labels = scene.marks.collect { case t: VisualPrimitive.Thread => t.label }.toSet
    assert(cast.nonEmpty, "no entity carries both a label and a mention")
    cast.foreach { member =>
      assert(labels.contains(member.label), s"'${member.label}' is not an entity's own label")
      assert(member.mentions > 0)
      assert(member.firstUnit <= member.lastUnit)
      // Every word listed is a word of the source, at an offset the model cited.
      member.words.foreach(word =>
        assert(flow.source.canonicalText.contains(word), s"'$word' is not in the text")
      )
    }
    // In order of first appearance, so the table reads as the story does.
    assertEquals(cast.map(_.firstUnit), cast.map(_.firstUnit).sorted)
    val document = html
    cast.foreach(member => assert(document.contains(member.label), member.label))

  test("the workspace states the reader horizon it was compiled under"):
    val document = html
    assert(document.contains("Reader horizon"), "the horizon is not on screen")
    assert(document.contains("omniscient"), "the horizon is not named")

  test("the inspector exposes the selected object field by field"):
    val document = html
    assert(document.contains("<section class=\"inspector\">"))
    chosen.foreach { c =>
      assert(document.contains(c.address.render))
      assert(document.contains("Exact support"))
      assert(document.contains("Words"))
    }
    // No bundled number is offered as a confidence.
    assert(!document.toLowerCase.contains("confidence"), "the inspector claims a confidence")

  test("a channel that could not be compiled is named, and not left reading as empty"):
    val statement = Workspace
      .emptyChannels(flow)
      .getOrElse(fail("gap and abstention are declared and empty in a file-read model"))
    assert(statement.contains("gap"), statement)
    assert(statement.contains("abstention"), statement)
    assert(statement.contains("No derivation record"), statement)
    assert(html.contains("Declared channels with no annotation here"))

  test("the workspace carries no script and no external resource"):
    val document = html
    Vector("<script", "src=\"http", "href=\"http", "@import", "javascript:").foreach { forbidden =>
      assert(!document.toLowerCase.contains(forbidden), s"the workspace carries $forbidden")
    }

  test("the workspace is deterministic"):
    assertEquals(html, html)

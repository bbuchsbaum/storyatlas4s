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

  private def paginated: PaginatedCodex =
    val page = ok(
      PageSpec.of(EditionSpec.workspacePageWidthPx, EditionSpec.workspacePageHeightPx)
    )
    val style = ok(TextStyle.of(EditionSpec.fontFamily, EditionSpec.fontSizePx))
    ok(Paginator.layout(flow, page, style, MonospaceMeasurer.instance))

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
    ok(Workspace.render(paginated, scene, plate, chosen, "workspace-suite"))

  private val tag = """<[^>]*>""".r
  private val textRail = """(?s)<div class="text">(.*?)</div>""".r

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
    assert(document.contains("class=\"sel\""), "no selection was marked")

  test("the words are set at the size the brief makes acceptance-bearing, never clamped"):
    val document = html
    assertEquals(paginated.receipt.style.sizePx, 16)
    assert(document.contains("font-size: 16px"), "the reading pane is not at 16px")
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
    val marked = """<span class="sel">(.*?)</span>""".r
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

  test("the reading pane paints one annotation family and says which"):
    val document = html
    val painted = flow.annotations.filter(a => Workspace.PaintedKinds.contains(a.kind))
    assert(painted.nonEmpty)
    // Every named piece in the page overlay belongs to a painted annotation.
    val pieceNames = """data-name="([^"]*)/p\d+/l\d+/r\d+"""".r
      .findAllMatchIn(document)
      .map(_.group(1))
      .toSet
    assert(pieceNames.nonEmpty, "the overlay painted nothing")
    val paintedIds = painted.map(_.id.value).toSet
    assertEquals(pieceNames.diff(paintedIds), Set.empty[String])
    assert(document.contains("Painted here"), "the pane does not declare what it paints")

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

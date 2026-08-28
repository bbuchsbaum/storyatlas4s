package storyatlas4s.layout

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** The publication path on the fixture: a small page yields several pages that tile the text. */
class WarOfTheGhostsPaginationSuite extends FunSuite:
  private val model = Wog.model
  private val lenses = Vector(CodexLens.Reading, CodexLens.Overview)

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def flow(lens: CodexLens): CodexFlow =
    val state =
      ok(CommonViewState.of(relationLayers = Set(RelationLayer.Causal, RelationLayer.Reference)))
    val spec = ok(CodexSpec.forLens(lens, ChannelBudget.All))
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "pagination-suite",
        CodexCompiler.configurationChecksum(state, spec)
      )
    )
    ok(CodexCompiler(provenance).compile(model, state, spec))

  private val style = ok(TextStyle.of("monospace", 16))
  private val page = ok(PageSpec.of(480, 640))

  private def paginate(lens: CodexLens): PaginatedCodex =
    ok(Paginator.layout(flow(lens), page, style, MonospaceMeasurer.instance))

  test("a 480x640px monospace page breaks the fixture into several pages"):
    lenses.foreach { lens =>
      val placed = paginate(lens)
      assert(placed.pages.length > 1, s"$lens: ${placed.pages.length} page(s)")
      assertEquals(placed.receipt.linesPerPage, 33)
      assertEquals(placed.receipt.pages, placed.pages.length)
      assertEquals(placed.receipt.overflowingLines, 0)
    }

  test("the union of the text fragments is the canonical text, in order (V-T1)"):
    lenses.foreach { lens =>
      val placed = paginate(lens)
      val text = placed.flow.source.canonicalText
      assertEquals(placed.textFragments.map(f => ok(placed.text(f))).mkString, text)
      assertEquals(placed.textFragments.map(_.span.length).sum, text.length)
    }

  test("every annotation is placed at least once and every piece resolves to its address (V-I2)"):
    val placed = paginate(CodexLens.Overview)
    val f = placed.flow
    assert(f.annotations.nonEmpty)
    val placedIds = placed.annotationFragments.map(_.annotation).toSet
    f.annotations.foreach(a => assert(placedIds.contains(a.id), s"${a.id.value} not placed"))
    placed.annotationFragments.foreach { piece =>
      assert(f.navigation.targetOf(piece.annotation).isDefined, piece.id.value)
      assert(placed.annotationOf(piece).isDefined, piece.id.value)
      assert(piece.id.value.startsWith(piece.annotation.value + "/p"), piece.id.value)
    }
    val ids = placed.annotationFragments.map(_.id.value)
    assertEquals(ids.distinct.length, ids.length)

  test("the twin names every fragment, prints the basis, and is deterministic (V-D2)"):
    lenses.foreach { lens =>
      val placed = paginate(lens)
      val twin = placed.textualTwin
      assertEquals(twin, paginate(lens).textualTwin)
      assert(twin.contains(ViewBasis.ResearcherReviewedFixture.label))
      assert(twin.contains(placed.receipt.checksum.hex))
      (placed.textFragments.map(_.id.value) ++ placed.annotationFragments.map(_.id.value))
        .foreach(id => assert(twin.contains(id), s"twin lacks $id"))
      assert(twin.startsWith("Paginated Narrative Codex\n"))
    }

  test("the receipt carries the metrics identity and the paginator version"):
    val placed = paginate(CodexLens.Reading)
    val metrics = ok(TextMetrics.measure(placed.flow, style, MonospaceMeasurer.instance))
    assertEquals(placed.receipt.metricsChecksum, metrics.checksum)
    assertEquals(placed.receipt.paginator, Paginator.Version)
    assertEquals(placed.receipt.measurer, MonospaceMeasurer.instance.name)
    assertEquals(placed.receipt.sourceChecksum, model.source.canonicalChecksum)
    assert(placed.receipt.render.contains("metricsChecksum: " + metrics.checksum.hex))

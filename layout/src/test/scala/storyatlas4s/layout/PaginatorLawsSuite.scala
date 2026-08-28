package storyatlas4s.layout

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import storymodel4s.core.*
import storymodel4s.view.*

/** The pagination laws: determinism, text coverage, page shape, widths, annotation coverage, ids.
  */
class PaginatorLawsSuite extends ScalaCheckSuite:
  import LayoutGens.Case

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private def ok[A](either: Either[LayoutError, A]): A =
    either.fold(e => fail(s"unexpected failure: ${e.message}"), identity)

  private def paginate(c: Case): PaginatedCodex =
    ok(Paginator.layout(c.flow, c.page, c.style, c.measurer))

  private def isBoundary(text: String, offset: Int): Boolean =
    LayoutGens.boundaries(text).contains(offset)

  /** Non-whitespace code points in `text`. */
  private def glyphCount(text: String): Int =
    var index = 0
    var count = 0
    while index < text.length do
      val codePoint = text.codePointAt(index)
      if !Character.isWhitespace(codePoint) then count += 1
      index += Character.charCount(codePoint)
    count

  property("same inputs give identical output (V-D1)"):
    forAll(LayoutGens.cases) { c =>
      val metrics = ok(TextMetrics.measure(c.flow, c.style, c.measurer))
      val again = ok(TextMetrics.measure(c.flow, c.style, c.measurer))
      val first = ok(Paginator.paginate(c.flow, c.page, metrics))
      val second = ok(Paginator.paginate(c.flow, c.page, again))
      assertEquals(metrics, again)
      assertEquals(first, second)
      assertEquals(first.receipt.checksum, second.receipt.checksum)
      true
    }

  property("lines tile the canonical text: every character once, in order, no cut code points"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val text = c.flow.source.canonicalText
      val fragments = placed.textFragments
      assertEquals(fragments.map(f => ok(placed.text(f))).mkString, text)
      assertEquals(fragments.head.span.start, 0)
      assertEquals(fragments.last.span.endExclusive, text.length)
      fragments.zip(fragments.tail).foreach { (before, after) =>
        assertEquals(before.span.endExclusive, after.span.start)
      }
      fragments.foreach { f =>
        assert(!f.span.isEmpty, s"empty line ${f.id.value}")
        assert(isBoundary(text, f.span.start) && isBoundary(text, f.span.endExclusive), f.id.value)
      }
      true
    }

  property("pages are full except the last; indices are positions; ids follow the rule"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val perPage = placed.receipt.linesPerPage
      assert(placed.pages.nonEmpty)
      placed.pages.zipWithIndex.foreach { (page, index) =>
        assertEquals(page.index, index)
        assert(page.lines.nonEmpty)
        if index < placed.pages.length - 1 then assertEquals(page.lines.length, perPage)
        else assert(page.lines.length <= perPage)
        page.lines.zipWithIndex.foreach { (line, lineIndex) =>
          assertEquals(line.text.page, index)
          assertEquals(line.text.line, lineIndex)
          assertEquals(line.text.id.value, s"line/p$index/l$lineIndex")
          line.annotations.foreach { a =>
            assertEquals((a.page, a.line), (index, lineIndex))
            assertEquals(a.id.value, s"${a.annotation.value}/p$index/l$lineIndex/r${a.ref}")
          }
        }
      }
      true
    }

  property("a line's ink fits the page unless a single glyph is wider than the page"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val text = c.flow.source.canonicalText
      val widthUnits = c.page.widthPx * placed.metrics.unitsPerPixel
      placed.textFragments.foreach { f =>
        val slice = ok(placed.text(f))
        val visible = slice.reverse.dropWhile(c => Character.isWhitespace(c)).length
        val ink = placed.metrics.widthOf(f.span.start, f.span.start + visible)
        assertEquals(f.width, ink, s"width of ${f.id.value}")
        if f.overflow then
          assertEquals(
            glyphCount(slice),
            1,
            s"overflow line ${f.id.value} holds more than one glyph"
          )
          assert(ink > widthUnits)
        else assert(ink <= widthUnits, s"${f.id.value}: $ink > $widthUnits")
        // A hard break always ends its line.
        val newline = slice.indexOf('\n')
        assert(newline < 0 || newline == slice.length - 1, s"newline inside ${f.id.value}")
        assert(text.substring(f.span.start, f.span.endExclusive) == slice)
      }
      true
    }

  property("every annotation support ref is covered exactly by its pieces, none merged or lost"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val pieces = placed.annotationFragments
      assertEquals(pieces.map(_.id).distinct.length, pieces.length, "fragment ids collide")
      val lineOf = placed.lines.map(l => (l.text.page, l.text.line) -> l.text.span).toMap
      pieces.foreach { p =>
        assert(lineOf(p.page -> p.line).contains(p.span), s"${p.id.value} leaves its line")
        assert(!p.span.isEmpty)
      }
      c.flow.annotations.foreach { annotation =>
        annotation.support.refs.toVector.zipWithIndex.foreach { (ref, refIndex) =>
          val mine = pieces.filter(p => p.annotation == annotation.id && p.ref == refIndex)
          assert(mine.nonEmpty, s"${annotation.id.value}/r$refIndex has no piece")
          assertEquals(mine.head.span.start, ref.span.start)
          assertEquals(mine.last.span.endExclusive, ref.span.endExclusive)
          mine.zip(mine.tail).foreach { (before, after) =>
            assertEquals(before.span.endExclusive, after.span.start)
            assert(
              before.page < after.page || (before.page == after.page && before.line < after.line)
            )
          }
        }
      }
      assertEquals(
        pieces.length,
        placed.receipt.annotationFragments
      )
      true
    }

  property("the receipt records the metrics identity and the counts of what was placed"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val receipt = placed.receipt
      assertEquals(receipt.paginator, Paginator.Version)
      assertEquals(receipt.metricsChecksum, placed.metrics.checksum)
      assertEquals(receipt.measurer, c.measurer.name)
      assertEquals(receipt.pages, placed.pages.length)
      assertEquals(receipt.lines, placed.lines.length)
      assertEquals(receipt.annotations, c.flow.annotations.length)
      assertEquals(receipt.overflowingLines, placed.textFragments.count(_.overflow))
      assertEquals(receipt.sourceChecksum, c.flow.source.canonicalChecksum)
      assertEquals(receipt.render.linesIterator.length, receipt.fields.length)
      true
    }

  property("metrics identity changes when any advance changes"):
    forAll(LayoutGens.cases) { c =>
      val metrics = ok(TextMetrics.measure(c.flow, c.style, c.measurer))
      val bumped = metrics.advances.updated(0, metrics.advances(0) + 1)
      val other = ok(
        TextMetrics.of(
          metrics.measurer,
          metrics.style,
          metrics.unitsPerPixel,
          metrics.lineHeight,
          bumped
        )
      )
      metrics.checksum != other.checksum
    }

  test("metrics measured for another text are rejected, and a page shorter than a line"):
    val a = ok(source("alpha beta"))
    val b = ok(source("gamma"))
    val style = ok(TextStyle.of("monospace", 10))
    val page = ok(PageSpec.of(100, 100))
    val metricsB = ok(TextMetrics.measure(b, style, MonospaceMeasurer.instance))
    Paginator.paginate(a, page, metricsB) match
      case Left(LayoutError.MetricsMismatch(_)) => ()
      case other                                => fail(s"expected MetricsMismatch, got $other")
    val metricsA = ok(TextMetrics.measure(a, style, MonospaceMeasurer.instance))
    Paginator.paginate(a, ok(PageSpec.of(100, 5)), metricsA) match
      case Left(LayoutError.InvalidSpec("PageSpec.heightPx", _)) => ()
      case other => fail(s"expected InvalidSpec, got $other")

  test("greedy breaks: after whitespace when the next word does not fit, at newlines always"):
    // 6 units per glyph at 10px on the monospace table; a 30px page holds 5 glyphs.
    val flow = ok(source("abc de\nf\n\nghijklmn"))
    val placed = ok(
      Paginator.layout(
        flow,
        ok(PageSpec.of(30, 24)),
        ok(TextStyle.of("mono", 10)),
        MonospaceMeasurer.instance
      )
    )
    assertEquals(
      placed.textFragments.map(f => ok(placed.text(f))),
      Vector("abc ", "de\n", "f\n", "\n", "ghijk", "lmn")
    )
    assertEquals(placed.pages.map(_.lines.length), Vector(2, 2, 2))
    assert(placed.textFragments.forall(!_.overflow))

  test("a surrogate pair is never split by a mid-word break and carries its width once"):
    val flow = ok(source("a🙂b🙂c"))
    val table = ok(TableMeasurer.of("t/1", 1, 1, 1, Map(0x1f642 -> 3)))
    val placed = ok(Paginator.layout(flow, ok(PageSpec.of(4, 1)), ok(TextStyle.of("t", 1)), table))
    assertEquals(placed.textFragments.map(f => ok(placed.text(f))), Vector("a🙂", "b🙂", "c"))
    assertEquals(placed.textFragments.map(_.width), Vector(4, 4, 1))

  private def source(text: String): Either[LayoutError, CodexFlow] =
    for
      story <- StorySource.fromText(text, Some("example")).left.map(LayoutError.Domain.apply)
      provenance <- ViewProvenance
        .fixture(story.canonicalChecksum, "laws", LayoutGens.config)
        .left
        .map(LayoutError.Domain.apply)
      flow <- CodexFlow.exact(story, Vector.empty, provenance).left.map(LayoutError.Domain.apply)
    yield flow

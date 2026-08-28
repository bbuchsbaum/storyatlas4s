package storyatlas4s.cli

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import storyatlas4s.layout.*

/** The DOM laws of the paginated codex over generated flows (small alphabet, tabs, newlines, NBSP,
  * zero-width space, astral and lone surrogates, zero-width tables) and the fixture.
  */
class CodexHtmlSuite extends ScalaCheckSuite:
  import LayoutGens.Case

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def paginate(c: Case): PaginatedCodex =
    ok(Paginator.layout(c.flow, c.page, c.style, c.measurer))

  private val lineSpan = """(?s)<span class="line" data-name="([^"]*)">(.*?)</span>""".r
  private val dataName = """data-name="([^"]*)"""".r
  private val textRail = """(?s)<div class="text">(.*?)</div>""".r

  /** The inverse of the writer's escaping: the three entities it emits, `&amp;` last. */
  private def unescape(value: String): String =
    value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

  private def lines(html: String): Vector[(String, String)] =
    lineSpan.findAllMatchIn(html).map(m => m.group(1) -> m.group(2)).toVector

  private def pieceNames(html: String): Vector[String] =
    dataName.findAllMatchIn(html).map(_.group(1)).filterNot(_.startsWith("line/")).toVector

  private def count(haystack: String, needle: String): Int =
    var found = 0
    var at = haystack.indexOf(needle)
    while at >= 0 do
      found += 1
      at = haystack.indexOf(needle, at + 1)
    found

  property("the concatenated text of the line spans is the canonical text (V-T2)"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val html = ok(CodexHtml.render(placed, "laws"))
      val spans = lines(html)
      assertEquals(spans.map(_._1), placed.textFragments.map(_.id.value))
      assertEquals(unescape(spans.map(_._2).mkString), c.flow.source.canonicalText)
      // Each rail holds only its spans (the matches tile it), so the rail's own text content is
      // the page's text and the rails together are the canonical text.
      val rails = textRail.findAllMatchIn(html).map(_.group(1)).toVector
      assertEquals(rails.length, placed.pages.length)
      rails.foreach { rail =>
        val matches = lineSpan.findAllMatchIn(rail).toVector
        assertEquals(matches.map(_.matched.length).sum, rail.length, "characters between spans")
      }
      true
    }

  property("every annotation piece id appears once in the overlays and once in the twin (V-I2)"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val html = ok(CodexHtml.render(placed, "laws"))
      val twin = placed.textualTwin
      val expected = placed.annotationFragments.map(_.id.value)
      assertEquals(pieceNames(html), expected)
      expected.foreach { id =>
        assertEquals(count(html, s"""data-name="$id""""), 1, id)
        assertEquals(count(twin, s"  $id "), 1, id)
      }
      placed.textFragments.foreach(f =>
        assertEquals(count(html, s"""data-name="${f.id.value}""""), 1)
      )
      true
    }

  property("the document is deterministic, script-free, and self-contained"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      val html = ok(CodexHtml.render(placed, "laws"))
      assertEquals(html, ok(CodexHtml.render(placed, "laws")))
      assert(!html.contains("<script"))
      assert(!html.contains("<link"))
      assert(!html.contains("src=\""))
      assert(!html.contains("href=\""))
      assertEquals(count(html, "<section class=\"page\""), placed.pages.length)
      true
    }

  test("escaping is the identity away from &, <, > and round-trips through unescape"):
    val text = "a & b < c > d \" e \n\t ​🙂\ud83d"
    val escaped = CodexHtml.escapeText(text)
    assertEquals(escaped, "a &amp; b &lt; c &gt; d \" e \n\t ​🙂\ud83d")
    assertEquals(unescape(escaped), text)
    assertEquals(unescape(CodexHtml.escapeText("&lt;&amp;")), "&lt;&amp;")
    assertEquals(CodexHtml.escapeAttr("a\"b"), "a&quot;b")

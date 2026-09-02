package storyatlas4s.cli

import java.nio.charset.StandardCharsets.UTF_8
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

  /** Generated texts carry a lone high surrogate one character in about twenty, so long texts are
    * mostly refused; the laws about written documents draw from encodable cases.
    */
  private val encodableCases: org.scalacheck.Gen[Case] =
    LayoutGens.cases.retryUntil(
      c => CodexHtml.unencodable(c.flow.source.canonicalText).isEmpty,
      maxTries = 1000
    )

  /** Generated texts carry a lone high surrogate about one time in fifteen; those must be refused.
    */
  private def encodable(placed: PaginatedCodex): Boolean =
    CodexHtml.unencodable(placed.flow.source.canonicalText).isEmpty

  /** Render, asserting the refusal path for unencodable text; `None` when refused. */
  private def rendered(placed: PaginatedCodex): Option[String] =
    (CodexHtml.render(placed, "laws"), encodable(placed)) match
      case (Right(html), true)                                             => Some(html)
      case (Left(CodexHtml.Error.UnencodableText(offset, unit, _)), false) =>
        assert(
          unit == '\u0000' || Character.isSurrogate(unit),
          s"refused U+${unit.toInt.toHexString} at $offset"
        )
        assertEquals(placed.flow.source.canonicalText.charAt(offset), unit)
        None
      case (other, ok) => fail(s"encodable=$ok but render gave $other")

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

  property("the concatenated text of the line spans is the canonical text, through UTF-8 (V-T2)"):
    forAll(encodableCases) { c =>
      val placed = paginate(c)
      rendered(placed).foreach { html =>
        val onDisk = new String(html.getBytes(UTF_8), UTF_8)
        assertEquals(onDisk, html, "UTF-8 round trip changed the document")
        val spans = lines(onDisk)
        assertEquals(spans.map(_._1), placed.textFragments.map(_.id.value))
        assertEquals(unescape(spans.map(_._2).mkString), c.flow.source.canonicalText)
        railsTile(placed, onDisk)
      }
      true
    }

  private def railsTile(placed: PaginatedCodex, html: String): Unit =
    // Each rail holds only its spans (the matches tile it), so the rail's own text content is
    // the page's text and the rails together are the canonical text.
    val rails = textRail.findAllMatchIn(html).map(_.group(1)).toVector
    assertEquals(rails.length, placed.pages.length)
    rails.foreach { rail =>
      val matches = lineSpan.findAllMatchIn(rail).toVector
      assertEquals(matches.map(_.matched.length).sum, rail.length, "characters between spans")
    }

  property("every annotation piece id appears once in the overlays and once in the twin (V-I2)"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      rendered(placed).foreach { html =>
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
      }
      true
    }

  property("the document is deterministic, script-free, and self-contained"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      assertEquals(CodexHtml.render(placed, "laws"), CodexHtml.render(placed, "laws"))
      rendered(placed).foreach { html =>
        assert(!html.contains("<script"))
        assert(!html.contains("<link"))
        assert(!html.contains("src=\""))
        assert(!html.contains("href=\""))
        assert(html.contains("<details>\n<summary>Provenance and layout receipt</summary>"))
        assert(!html.contains("<details open"))
        assert(html.indexOf("<details>") < html.indexOf("<main>"))
        assert(html.contains("<p class=\"legend\"><strong>Overlay:</strong> "))
        assertEquals(count(html, "<section class=\"page\""), placed.pages.length)
      }
      true
    }

  property("a document is written exactly when the canonical text is encodable"):
    forAll(LayoutGens.cases) { c =>
      val placed = paginate(c)
      assertEquals(rendered(placed).isDefined, encodable(placed))
      true
    }

  test("the generators exercise both branches: refused and written documents (seeded)"):
    val sample = org.scalacheck.Gen
      .listOfN(200, LayoutGens.cases)
      .pureApply(
        org.scalacheck.Gen.Parameters.default.withSize(20),
        org.scalacheck.rng.Seed(20260828L)
      )
    val outcomes = sample.map(c => rendered(paginate(c)).isDefined)
    assert(outcomes.contains(false), "no generated text was refused")
    assert(outcomes.contains(true), "no generated text was written")

  test("unencodable text: a lone surrogate or U+0000 is refused with its offset"):
    assertEquals(CodexHtml.unencodable("ab\ud83dc").map(_.offset), Some(2))
    assertEquals(CodexHtml.unencodable("ab\udc00c").map(_.offset), Some(2))
    assertEquals(CodexHtml.unencodable("a\u0000").map(_.offset), Some(1))
    assertEquals(CodexHtml.unencodable("a🙂b\n\t\u00a0\u200b"), None)
    val lone = ok(
      storymodel4s.core.StorySource.fromText("The ghost \ud83d walked home.", Some("lone"))
    )
    assert(lone.canonicalText.contains('\ud83d'))
    val bad = new String(lone.canonicalText.getBytes(UTF_8), UTF_8)
    assertNotEquals(bad, lone.canonicalText, "UTF-8 would have substituted the surrogate")

  test("the CSS family is one escaped identifier and TextStyle refuses markup delimiters"):
    assertEquals(CodexHtml.cssFamily("monospace"), "monospace")
    assertEquals(CodexHtml.cssFamily("Fira Code"), "Fira\\20 Code")
    assertEquals(CodexHtml.cssFamily("a\"b;c}d"), "a\\22 b\\3b c\\7d d")
    assertEquals(CodexHtml.cssFamily("mono, serif"), "mono\\2c \\20 serif")
    assertEquals(CodexHtml.cssFamily("Ünïcode"), "\\dc n\\ef code")
    assert(!CodexHtml.cssFamily("</style><script>").contains("<"))
    assert(TextStyle.of("</style><script>", 16).isLeft)
    assert(TextStyle.of("a>b", 16).isLeft)
    assert(TextStyle.of("Fira Code", 16).isRight)

  test("escaping is the identity away from &, <, > and round-trips through unescape"):
    val text = "a & b < c > d \" e \n\t ​🙂\ud83d"
    val escaped = CodexHtml.escapeText(text)
    assertEquals(escaped, "a &amp; b &lt; c &gt; d \" e \n\t ​🙂\ud83d")
    assertEquals(unescape(escaped), text)
    assertEquals(unescape(CodexHtml.escapeText("&lt;&amp;")), "&lt;&amp;")
    assertEquals(CodexHtml.escapeAttr("a\"b"), "a&quot;b")

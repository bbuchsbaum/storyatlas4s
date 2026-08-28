package storyatlas4s.layout

import org.scalacheck.Gen
import storymodel4s.core.*
import storymodel4s.view.*

/** Small-alphabet generators for flows, measurers, and page specs that exercise every break rule.
  */
object LayoutGens:
  val config: Checksum = Checksum.ofText("layout-laws")

  private def lift[E, A](either: Either[E, A]): Gen[A] =
    either.fold(_ => Gen.fail[A], Gen.const)

  private def liftOption[A](option: Option[A]): Gen[A] =
    option.fold(Gen.fail[A])(Gen.const)

  /** `a`/`b`/`c` glyphs, spaces, tabs, newlines, NBSP, a zero-width space, an astral emoji (a
    * surrogate pair), and a lone high surrogate; tables are sometimes all zero.
    */
  val rawText: Gen[String] =
    Gen
      .nonEmptyListOf(
        Gen.frequency(
          6 -> Gen.const("a"),
          3 -> Gen.const("b"),
          2 -> Gen.const("c"),
          4 -> Gen.const(" "),
          1 -> Gen.const("\t"),
          1 -> Gen.const("\n"),
          1 -> Gen.const("\u00a0"),
          1 -> Gen.const("\u200b"),
          1 -> Gen.const("🙂"),
          1 -> Gen.const("\ud83d")
        )
      )
      .map(_.mkString)
      .suchThat(_.trim.nonEmpty)

  val source: Gen[StorySource] =
    rawText.flatMap(text => lift(StorySource.fromText(text, Some("laws"))))

  /** Offsets at which a span may start or end without cutting a surrogate pair. */
  def boundaries(text: String): Vector[Int] =
    (0 to text.length).toVector.filter(offset =>
      offset == 0 || offset == text.length ||
        !(Character.isHighSurrogate(text.charAt(offset - 1)) &&
          Character.isLowSurrogate(text.charAt(offset)))
    )

  def span(text: String): Gen[TextSpan] =
    val bounds = boundaries(text)
    for
      startIndex <- Gen.choose(0, bounds.length - 2)
      endIndex <- Gen.choose(startIndex + 1, bounds.length - 1)
      span <- lift(TextSpan.of(bounds(startIndex), bounds(endIndex)))
    yield span

  def claimAddress(index: Int): Address =
    Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe(s"claim:$index")))

  private val kinds = Vector(AnnotationKind.Claim, AnnotationKind.Entity, AnnotationKind.Context)

  def annotation(text: String): Gen[TextAnnotation] =
    for
      refCount <- Gen.frequency(4 -> 1, 1 -> 2)
      spans <- Gen.listOfN(refCount, span(text))
      support <- liftOption(SpanSet.of(spans.map(SpanRef(_))))
      target <- Gen.choose(0, 3).map(claimAddress)
      kind <- Gen.oneOf(kinds)
      priority <- Gen.choose(0, 5).flatMap(p => lift(AnnotationPriority.from(p)))
      annotation <- lift(
        TextAnnotation
          .of(target, support, kind, priority, AuditRecord.deterministic("layout-laws", config))
      )
    yield annotation

  /** Runs tiling the canonical text at one to three random boundaries. */
  def runs(text: String): Gen[Vector[SourceRun]] =
    val bounds = boundaries(text).drop(1).dropRight(1)
    for
      cutCount <- Gen.choose(0, math.min(2, bounds.length))
      cuts <- Gen.pick(cutCount, bounds).map(_.toVector.sorted)
      edges = (0 +: cuts) :+ text.length
      runs <- edges
        .zip(edges.tail)
        .foldRight[Gen[Vector[SourceRun]]](Gen.const(Vector.empty)) { case ((start, end), acc) =>
          lift(TextSpan.of(start, end).flatMap(SourceRun.of)).flatMap(run => acc.map(run +: _))
        }
    yield runs

  val flow: Gen[CodexFlow] =
    for
      story <- source
      text = story.canonicalText
      count <- Gen.choose(0, 6)
      raw <- Gen.listOfN(count, annotation(text))
      annotations <- lift(TextAnnotation.coalesce(raw.toVector))
      sourceRuns <- runs(text)
      provenance <- lift(ViewProvenance.fixture(story.canonicalChecksum, "layout-laws", config))
      flow <- lift(CodexFlow.of(story, sourceRuns, annotations, provenance))
    yield flow

  /** A table with one unit per em (so units per pixel is 1) and random glyph widths. */
  val measurer: Gen[TableMeasurer] =
    for
      zero <- Gen.frequency(9 -> false, 1 -> true)
      default <- if zero then Gen.const(0) else Gen.choose(0, 12)
      a <- if zero then Gen.const(0) else Gen.choose(0, 12)
      space <- if zero then Gen.const(0) else Gen.choose(0, 12)
      smile <- if zero then Gen.const(0) else Gen.choose(0, 24)
      lineHeight <- Gen.choose(1, 4)
      measurer <- lift(
        TableMeasurer.of(
          "laws-table/1",
          unitsPerEm = 1,
          lineHeightPerEm = lineHeight,
          defaultAdvancePerEm = default,
          overrides = Map('a'.toInt -> a, ' '.toInt -> space, 0x1f642 -> smile)
        )
      )
    yield measurer

  val style: Gen[TextStyle] =
    Gen.choose(1, 3).flatMap(size => lift(TextStyle.of("laws", size)))

  /** Page heights are whole numbers of lines so the spec is valid by construction. */
  def page(measurer: TableMeasurer, style: TextStyle): Gen[PageSpec] =
    for
      width <- Gen.choose(1, 40)
      lines <- Gen.choose(1, 5)
      page <- lift(PageSpec.of(width, lines * measurer.lineHeightPerEm * style.sizePx))
    yield page

  final case class Case(flow: CodexFlow, measurer: TableMeasurer, style: TextStyle, page: PageSpec)

  val cases: Gen[Case] =
    for
      f <- flow
      m <- measurer
      s <- style
      p <- page(m, s)
    yield Case(f, m, s, p)

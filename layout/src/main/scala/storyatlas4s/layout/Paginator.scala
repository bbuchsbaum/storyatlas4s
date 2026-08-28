package storyatlas4s.layout

import storymodel4s.core.{DomainError, TextSpan}
import storymodel4s.view.CodexFlow

/** The owned deterministic paginator: a pure function of `(flow, page, metrics)` (ADR 0002 D13).
  *
  * Line breaking is greedy first-fit over the metrics table: a line may end after any whitespace
  * (`Character.isWhitespace`, so tabs but not NBSP or U+200B; the whitespace hangs, counting toward
  * the span but not the width), must end after `\n`, and a word wider than the page breaks at the
  * last code point that fits. A glyph wider than the page is placed alone and the line is marked
  * `overflow`, so pagination is total. Leading whitespace is a break opportunity like any other:
  * `"  abc"` on a page three glyphs wide becomes the lines `"  "` (width 0) and `"abc"`, because
  * the whitespace run is an earlier break than the word that does not fit. Lines fill pages top to
  * bottom with no widow or orphan control. No character is added, dropped, or reordered.
  */
object Paginator:
  val Version: String = "storyatlas4s-paginator/1"

  /** Measure with `measurer`, then paginate. */
  def layout(
      flow: CodexFlow,
      page: PageSpec,
      style: TextStyle,
      measurer: Measurer
  ): Either[LayoutError, PaginatedCodex] =
    TextMetrics.measure(flow, style, measurer).flatMap(paginate(flow, page, _))

  def paginate(
      flow: CodexFlow,
      page: PageSpec,
      metrics: TextMetrics
  ): Either[LayoutError, PaginatedCodex] =
    val text = flow.source.canonicalText
    val units = metrics.unitsPerPixel
    if metrics.advances.length != text.length then
      Left(
        LayoutError.MetricsMismatch(
          s"metrics cover ${metrics.advances.length} code units, canonical text has ${text.length}"
        )
      )
    else
      for
        widthUnits <- Bounds
          .product(page.widthPx, units)
          .toRight(
            LayoutError
              .InvalidSpec("PageSpec.widthPx", s"${page.widthPx}px x $units units overflows")
          )
        heightUnits <- Bounds
          .product(page.heightPx, units)
          .toRight(
            LayoutError
              .InvalidSpec("PageSpec.heightPx", s"${page.heightPx}px x $units units overflows")
          )
        linesPerPage = heightUnits / metrics.lineHeight
        _ <-
          if linesPerPage >= 1 then Right(())
          else
            Left(
              LayoutError.InvalidSpec(
                "PageSpec.heightPx",
                s"${page.heightPx}px holds no line of height ${metrics.lineHeight}/${units}px"
              )
            )
        raw = breakLines(text, metrics, widthUnits)
        lines <- placeAnnotations(flow, raw, linesPerPage)
        pages = lines.grouped(linesPerPage).toVector.zipWithIndex.map((ls, i) => PlacedPage(i, ls))
      yield
        val receipt = LayoutReceipt(
          Version,
          page,
          linesPerPage,
          metrics.measurer,
          metrics.style,
          metrics.unitsPerPixel,
          metrics.lineHeight,
          metrics.checksum,
          flow.provenance.sourceChecksum,
          flow.provenance.configChecksum,
          pages.length,
          lines.length,
          flow.annotations.length,
          lines.iterator.map(_.annotations.length).sum,
          lines.count(_.text.overflow)
        )
        PaginatedCodex(flow, page, metrics, pages, receipt)

  /** A line before spans are typed: `[start, end)`, ink width, overflow flag. */
  private final case class RawLine(start: Int, end: Int, width: Int, overflow: Boolean)

  private def codePointLength(text: String, index: Int): Int =
    if Character.isHighSurrogate(text.charAt(index)) && index + 1 < text.length &&
      Character.isLowSurrogate(text.charAt(index + 1))
    then 2
    else 1

  private def breakLines(text: String, metrics: TextMetrics, widthUnits: Int): Vector[RawLine] =
    val n = text.length
    val out = Vector.newBuilder[RawLine]
    var lineStart = 0
    while lineStart < n do
      var cursor = lineStart
      var width = 0 // ink width through the last placed glyph
      var hanging = 0 // whitespace advances after that glyph, not yet committed
      var lastBreak = -1 // offset just after the last whitespace on this line
      var widthAtBreak = 0
      var overflow = false
      var lineEnd = -1
      while lineEnd < 0 do
        if cursor >= n then lineEnd = n
        else
          val c = text.charAt(cursor)
          val count = codePointLength(text, cursor)
          val advance = metrics.widthOf(cursor, cursor + count)
          if c == '\n' then lineEnd = cursor + 1
          else if Character.isWhitespace(c) then
            hanging += advance
            cursor += count
            lastBreak = cursor
            widthAtBreak = width
          else if width + hanging + advance > widthUnits && cursor > lineStart then
            if lastBreak > lineStart then
              lineEnd = lastBreak
              width = widthAtBreak
            else lineEnd = cursor
          else
            if width + hanging + advance > widthUnits then overflow = true
            width += hanging + advance
            hanging = 0
            cursor += count
      out += RawLine(lineStart, lineEnd, width, overflow)
      lineStart = lineEnd
    out.result()

  private def placeAnnotations(
      flow: CodexFlow,
      raw: Vector[RawLine],
      linesPerPage: Int
  ): Either[LayoutError, Vector[PlacedLine]] =
    val builders = Array.fill(raw.length)(Vector.newBuilder[AnnotationFragment])
    var problem: Option[DomainError] = None
    flow.annotations.foreach { annotation =>
      annotation.support.refs.toVector.zipWithIndex.foreach { (ref, refIndex) =>
        val span = ref.span
        var lineIndex = firstLineReaching(raw, span.start)
        while problem.isEmpty && lineIndex < raw.length && raw(lineIndex).start < span.endExclusive
        do
          val line = raw(lineIndex)
          val page = lineIndex / linesPerPage
          val onPage = lineIndex % linesPerPage
          TextSpan.of(math.max(span.start, line.start), math.min(span.endExclusive, line.end)) match
            case Left(error) => problem = Some(error)
            case Right(cut)  =>
              builders(lineIndex) += AnnotationFragment(
                FragmentId.ofAnnotation(annotation.id, page, onPage, refIndex),
                annotation.id,
                page,
                onPage,
                refIndex,
                cut
              )
          lineIndex += 1
      }
    }
    problem match
      case Some(error) => Left(LayoutError.Domain(error))
      case None        =>
        var failure: Option[DomainError] = None
        val lines = Vector.newBuilder[PlacedLine]
        var index = 0
        while failure.isEmpty && index < raw.length do
          val line = raw(index)
          val page = index / linesPerPage
          val onPage = index % linesPerPage
          TextSpan.of(line.start, line.end) match
            case Left(error) => failure = Some(error)
            case Right(span) =>
              lines += PlacedLine(
                TextFragment(
                  FragmentId.ofLine(page, onPage),
                  page,
                  onPage,
                  span,
                  line.width,
                  line.overflow
                ),
                builders(index).result()
              )
          index += 1
        failure.fold[Either[LayoutError, Vector[PlacedLine]]](Right(lines.result()))(error =>
          Left(LayoutError.Domain(error))
        )

  /** Index of the first line whose end is past `offset`; lines tile the text, so it exists. */
  private def firstLineReaching(raw: Vector[RawLine], offset: Int): Int =
    var low = 0
    var high = raw.length - 1
    while low < high do
      val mid = (low + high) / 2
      if raw(mid).end > offset then high = mid else low = mid + 1
    low

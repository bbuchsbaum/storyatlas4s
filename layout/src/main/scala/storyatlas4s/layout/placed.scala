package storyatlas4s.layout

import cats.{Hash, Order, Show}
import storymodel4s.core.{Checksum, TextSpan}
import storymodel4s.view.{AnnotationId, CodexFlow, TextAnnotation}

/** Identity of one placed piece: a text line, or one annotation's piece on one line (V-I2).
  *
  * Rule: a line is `line/p<page>/l<line>`; an annotation piece is
  * `<AnnotationId>/p<page>/l<line>/r<ref>` where `ref` is the 0-based index of the support
  * `SpanRef` (in `SpanSet` order) the piece cuts from. ADR 0002 names `(AnnotationId, page, line)`;
  * the `ref` term is added because a discontinuous support can put two pieces of one annotation on
  * one line, and it is always `r0` for contiguous annotations. Every term is a stable function of
  * the flow, the page spec, and the metrics, so ids survive re-pagination under equal inputs.
  */
object FragmentId:
  opaque type FragmentId = String

  def ofLine(page: Int, line: Int): FragmentId = s"line/p$page/l$line"

  def ofAnnotation(annotation: AnnotationId, page: Int, line: Int, ref: Int): FragmentId =
    s"${annotation.value}/p$page/l$line/r$ref"

  extension (id: FragmentId) def value: String = id

  given Show[FragmentId] = Show.show(identity)
  given Order[FragmentId] = Order[String]
  given Hash[FragmentId] = Hash[String]
  given Ordering[FragmentId] = Ordering.String
type FragmentId = FragmentId.FragmentId

/** One line of placed canonical text: an exact span (trailing whitespace and newline included), its
  * ink width, and whether its first glyph alone was wider than the page.
  *
  * Lines tile the canonical text, so the DOM text rail can be built from them alone (V-T2).
  */
final case class TextFragment private[layout] (
    id: FragmentId,
    page: Int,
    line: Int,
    span: TextSpan,
    width: Int,
    overflow: Boolean
)

/** The piece of one annotation's support that falls on one line; never merged, never dropped. */
final case class AnnotationFragment private[layout] (
    id: FragmentId,
    annotation: AnnotationId,
    page: Int,
    line: Int,
    ref: Int,
    span: TextSpan
)

/** One placed line with the annotation pieces that cover it, in flow order then support order. */
final case class PlacedLine private[layout] (
    text: TextFragment,
    annotations: Vector[AnnotationFragment]
)

/** One page: a nonempty run of consecutive lines; all pages but the last are full. */
final case class PlacedPage private[layout] (index: Int, lines: Vector[PlacedLine])

/** What a receipt must record for a page break to be reproducible without a browser (V-D3). */
final case class LayoutReceipt private[layout] (
    paginator: String,
    page: PageSpec,
    linesPerPage: Int,
    measurer: String,
    style: TextStyle,
    unitsPerPixel: Int,
    lineHeight: Int,
    metricsChecksum: Checksum,
    sourceChecksum: Checksum,
    configChecksum: Checksum,
    pages: Int,
    lines: Int,
    annotations: Int,
    annotationFragments: Int,
    overflowingLines: Int
):
  /** Ordered `(key, value)` pairs, the one rendering every twin and JSON receipt derives from. */
  def fields: Vector[(String, String)] =
    Vector(
      "paginator" -> paginator,
      "pageWidthPx" -> page.widthPx.toString,
      "pageHeightPx" -> page.heightPx.toString,
      "linesPerPage" -> linesPerPage.toString,
      "measurer" -> measurer,
      "fontFamily" -> style.family,
      "fontSizePx" -> style.sizePx.toString,
      "unitsPerPixel" -> unitsPerPixel.toString,
      "lineHeight" -> lineHeight.toString,
      "metricsChecksum" -> metricsChecksum.hex,
      "sourceChecksum" -> sourceChecksum.hex,
      "configChecksum" -> configChecksum.hex,
      "pages" -> pages.toString,
      "lines" -> lines.toString,
      "annotations" -> annotations.toString,
      "annotationFragments" -> annotationFragments.toString,
      "overflowingLines" -> overflowingLines.toString
    )

  def render: String =
    fields.map((key, value) => s"$key: $value").mkString("", "\n", "\n")

  def checksum: Checksum = Checksum.ofText(render)

/** A `CodexFlow` placed on pages: the paginated, model-annotated edition (ADR 0002 D3).
  *
  * It keeps the flow it was cut from, so every fragment resolves back to its annotation and its
  * exact text without any copy of the canonical text living here.
  */
final case class PaginatedCodex private[layout] (
    flow: CodexFlow,
    page: PageSpec,
    metrics: TextMetrics,
    pages: Vector[PlacedPage],
    receipt: LayoutReceipt
):
  def lines: Vector[PlacedLine] = pages.flatMap(_.lines)
  def textFragments: Vector[TextFragment] = lines.map(_.text)
  def annotationFragments: Vector[AnnotationFragment] = lines.flatMap(_.annotations)

  /** The exact canonical text of one line, sliced from the flow's source. */
  def text(fragment: TextFragment): Either[LayoutError, String] =
    fragment.span.slice(flow.source.canonicalText).left.map(LayoutError.Domain.apply)

  /** The annotation a piece was cut from; `None` only for a piece from another codex. */
  def annotationOf(fragment: AnnotationFragment): Option[TextAnnotation] =
    annotationsById.get(fragment.annotation)

  private lazy val annotationsById: Map[AnnotationId, TextAnnotation] =
    flow.annotations.iterator.map(a => a.id -> a).toMap

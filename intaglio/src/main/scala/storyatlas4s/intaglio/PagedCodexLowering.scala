package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storyatlas4s.layout.{AnnotationFragment, PaginatedCodex, PlacedLine, PlacedPage}
import storymodel4s.view.{AnnotationKind, LaneSlot}

/** Pure lowering of a [[PaginatedCodex]] to one Intaglio overlay scene per page.
  *
  * The DOM owns the prose (ADR 0002 D6): a page overlay is drawn in the page's own pixel frame and
  * sits over the text rail the HTML writer builds from the same `TextFragment`s, so the two agree
  * by construction. Every annotation piece becomes one named group (`data-name` = its `FragmentId`,
  * V-I2) holding one band at the piece's exact line and horizontal extent; nothing else is drawn,
  * and no text, address, or claim enters the scene.
  *
  * Geometry is the paginator's, converted once from layout units to CSS pixels by the metrics'
  * `unitsPerPixel`: a line's box is `[line * lineHeight, (line + 1) * lineHeight)`, a piece's x
  * extent is the advance sum from the line start to the piece's span ends (trailing whitespace
  * counts, as it does in a `white-space: pre` rail). Bands are stacked into rows inside the line
  * box, one row per `(kind, lane slot)` the flow actually allocated, ordered by kind then lane,
  * with the overflow slot last; the row a piece occupies is the only encoding of its kind and lane,
  * and the textual twin names both (V-U5: never colour-only).
  */
object PagedCodexLowering:

  /** The rows a flow's annotations occupy, in drawing order. Public so a page's legend can list
    * them in the same words the overlay draws them in.
    */
  final case class Row(kind: AnnotationKind, slot: Option[LaneSlot]):
    def label: String = slot match
      case Some(LaneSlot.Lane(index)) => s"${kind.wireName} lane ${index.value}"
      case Some(LaneSlot.Overflow)    => s"${kind.wireName} overflow"
      case None => s"${kind.wireName} — every lane on one rule; the lanes are in the twin"

  /** Which channels a page paints, and at what resolution.
    *
    * A reading pane has one line's leading to work in — about three pixels at 16px type — and a
    * flow with four kinds over four lanes each wants eleven rows in it. The design brief settles
    * that: annotations use gutters and underlines rather than fills over the prose, and additional
    * layers stay discoverable without being painted at once (§8.1). So a caller says what it is
    * painting, and the page says so too.
    */
  enum RowPolicy:
    /** One row per `(kind, lane)` the flow allocated: the full encoding, for a plate-sized page. */
    case PerLane

    /** One row per drawn kind. Lane stops being a visual and stays a fact of the twin. */
    case Collapsed

  /** Rows are a pure function of the flow: `(kind, slot)` pairs present in its allocation, sorted
    * by kind ordinal then lane index, overflow after every lane of its kind.
    */
  def rows(placed: PaginatedCodex): Either[GraphicsError, Vector[Row]] =
    rows(placed, AnnotationKind.values.toSet, RowPolicy.PerLane)

  def rows(
      placed: PaginatedCodex,
      kinds: Set[AnnotationKind],
      policy: RowPolicy
  ): Either[GraphicsError, Vector[Row]] =
    val flow = placed.flow
    val maxLane = flow.contract.lanePolicy.maxLanesPerKind
    flow.annotations
      .filter(annotation => kinds.contains(annotation.kind))
      .traverse { annotation =>
        flow.lanes.slotOf(annotation.id) match
          case Some(LaneSlot.Lane(index)) if index.value >= maxLane =>
            Left(
              GraphicsError.InvalidExtent(
                s"codex ${annotation.kind.wireName} lane index ${index.value} out of range [0, $maxLane)"
              )
            )
          case Some(slot) =>
            Right(
              Row(
                annotation.kind,
                policy match
                  case RowPolicy.PerLane   => Some(slot)
                  case RowPolicy.Collapsed => None
              )
            )
          case None =>
            Left(
              GraphicsError.InvalidExtent(
                s"codex ${annotation.kind.wireName} annotation ${annotation.id.value} has no lane slot"
              )
            )
      }
      .map(_.distinct.sortBy(row => (row.kind.ordinal, slotOrdinal(row.slot, maxLane))))

  private def slotOrdinal(slot: Option[LaneSlot], maxLane: Int): Int = slot match
    case Some(LaneSlot.Lane(index)) => index.value
    case Some(LaneSlot.Overflow)    => maxLane
    case None                       => 0

  /** One scene per page, in page order. */
  def lowerPages(placed: PaginatedCodex): Either[GraphicsError, Vector[ig.Scene]] =
    lowerPages(placed, AnnotationKind.values.toSet, RowPolicy.PerLane)

  def lowerPages(
      placed: PaginatedCodex,
      kinds: Set[AnnotationKind],
      policy: RowPolicy
  ): Either[GraphicsError, Vector[ig.Scene]] =
    for
      rowTable <- rows(placed, kinds, policy).map(_.zipWithIndex.toMap)
      style <- Style.params
      scenes <- placed.pages.traverse(page =>
        lowerPage(placed, page, kinds, policy, rowTable, style)
      )
    yield scenes

  def lowerPage(placed: PaginatedCodex, page: PlacedPage): Either[GraphicsError, ig.Scene] =
    for
      rowTable <- rows(placed).map(_.zipWithIndex.toMap)
      style <- Style.params
      scene <- lowerPage(
        placed,
        page,
        AnnotationKind.values.toSet,
        RowPolicy.PerLane,
        rowTable,
        style
      )
    yield scene

  private def lowerPage(
      placed: PaginatedCodex,
      page: PlacedPage,
      kinds: Set[AnnotationKind],
      policy: RowPolicy,
      rowTable: Map[Row, Int],
      style: Style.Params
  ): Either[GraphicsError, ig.Scene] =
    val geometry = Geometry(placed, rowTable.size, policy)
    for
      viewport <- pageViewport(placed)
      groups <- page.lines.flatTraverse(line =>
        line.annotations
          .filter(piece => placed.annotationOf(piece).exists(a => kinds.contains(a.kind)))
          .traverse(piece => pieceGroup(placed, line, piece, geometry, policy, rowTable, style))
      )
    yield ig.Scene(Vector(ig.Grob.group(groups, viewport = Some(viewport))))

  /** Pixel geometry of one paginated codex; `rowCount` is at least one so a row has a height.
    *
    * Under `Collapsed` the rows live in the line's **leading** — the strip below the glyph box that
    * CSS half-leading leaves empty — so an annotation underlines its words instead of being painted
    * over them. Under `PerLane` the rows still tile the whole line box: that form is a plate, read
    * as a chart rather than as prose, and it needs every row it can get.
    */
  private final case class Geometry(placed: PaginatedCodex, rowCount: Int, policy: RowPolicy):
    private val units = placed.metrics.unitsPerPixel.toDouble
    val lineHeight: Double = placed.metrics.lineHeight.toDouble / units
    private val glyphBottom: Double = (lineHeight + placed.receipt.style.sizePx) / 2.0
    private val bandTop: Double = policy match
      case RowPolicy.PerLane   => 0.0
      case RowPolicy.Collapsed => math.max(0.0, math.min(lineHeight - 1.0, glyphBottom - 1.2))
    private val bandHeight: Double = lineHeight - bandTop
    val rowHeight: Double = bandHeight / math.max(rowCount, 1)

    def top(line: PlacedLine): Double = line.text.line * lineHeight + bandTop

    /** Horizontal pixel offset from the line start to `offset` in the canonical text. */
    def x(line: PlacedLine, offset: Int): Double =
      placed.metrics.widthOf(line.text.span.start, offset).toDouble / units

  /** The page frame: native x and y are CSS pixels from the page's top-left corner. */
  private def pageViewport(placed: PaginatedCodex): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(0.0, 0.0)
      size <- ig.Size.npc(1.0, 1.0)
      xScale <- ig.Interval(0.0, placed.page.widthPx.toDouble)
      yScale <- ig.Interval(0.0, placed.page.heightPx.toDouble)
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  private def pieceGroup(
      placed: PaginatedCodex,
      line: PlacedLine,
      piece: AnnotationFragment,
      geometry: Geometry,
      policy: RowPolicy,
      rowTable: Map[Row, Int],
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    for
      annotation <- placed
        .annotationOf(piece)
        .toRight(GraphicsError.InvalidExtent(s"fragment ${piece.id.value} is not from this codex"))
      slot <- placed.flow.lanes
        .slotOf(annotation.id)
        .toRight(GraphicsError.InvalidExtent(s"fragment ${piece.id.value} has no lane slot"))
      row <- rowTable
        .get(
          Row(
            annotation.kind,
            policy match
              case RowPolicy.PerLane   => Some(slot)
              case RowPolicy.Collapsed => None
          )
        )
        .toRight(GraphicsError.InvalidExtent(s"fragment ${piece.id.value} has no row"))
      name <- GraphicsNames.ofFragment(piece.id)
      top = geometry.top(line) + row * geometry.rowHeight
      band <- rect(
        geometry.x(line, piece.span.start),
        top,
        geometry.x(line, piece.span.endExclusive),
        top + geometry.rowHeight,
        if annotation.kind.marksAbsence then style.absenceBand else style.band
      )
    yield ig.Grob.group(Vector(band), name = Some(name))

  private def rect(
      x0: Double,
      y0: Double,
      x1: Double,
      y1: Double,
      gp: ig.GraphicParams
  ): Either[GraphicsError, ig.Grob] =
    for
      corners <- Vector(
        ig.Point.native(x0, y0),
        ig.Point.native(x1, y0),
        ig.Point.native(x1, y1),
        ig.Point.native(x0, y1)
      ).sequence
      polygon <- ig.Grob.polygon(corners, gp = gp)
    yield polygon

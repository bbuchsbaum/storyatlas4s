package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storymodel4s.acquire.ClaimFamily
import storymodel4s.core.SurfaceUnitKind
import storymodel4s.view.*

/** Pure lowering of a compiled [[NarrativeScene]] to an Intaglio scene.
  *
  * The Discourse Atlas already placed every narrative mark: x is an exact discourse offset and y is
  * a context lane (ADR 0002 §2, D4). Surface marks deliberately carry no y coordinate. This
  * lowering puts them in a separate layout-only rail whose categorical subrows distinguish unit
  * kinds; the rail is outside the projection's context-lane coordinate and vertical distance in it
  * has no meaning. It computes no hull, narrative order, or scientific position of its own. Each
  * mark becomes one named group (`data-name` = `MarkId`); nothing else in the output carries a
  * name.
  *
  * `discourseLength` is the length of the canonical text in UTF-16 code units. It is passed as a
  * number because a scene does not carry its source and Intaglio must never see canonical text
  * (D6); the same length at every zoom level keeps x comparable across levels (V-I1).
  */
object AtlasLowering:

  def lower(scene: NarrativeScene, discourseLength: Int): Either[GraphicsError, ig.Scene] =
    val Marks(surfaceMarks, narrativeMarks, epistemicMarks) = partitionMarks(scene.marks)
    for
      style <- Style.params
      laneCount = narrativeMarks.flatMap(maxContextLane).maxOption.fold(1)(_ + 1)
      viewport <- Style.plotViewport(discourseLength.toDouble, laneCount.toDouble)
      header <- headerGrob(scene, style)
      surface <- surfaceRail(surfaceMarks, discourseLength, style)
      marks <- orderedNarrative(narrativeMarks).traverse(m => markGroup(m, style))
      plot = ig.Grob.group(marks, viewport = Some(viewport))
      epistemic <- epistemicRail(epistemicMarks, discourseLength, style)
    yield ig.Scene(Vector(header) ++ surface.toVector ++ Vector(plot) ++ epistemic.toVector)

  /** Draw order only (bands and regions under everything, landmarks on top); never an inference.
    *
    * Context bands sit at the bottom of the stack because they are the ground the rest is read
    * against: a landmark inside the survivor's retelling must be legible as sitting on that band.
    */
  private def orderedNarrative(marks: Vector[VisualPrimitive]): Vector[VisualPrimitive] =
    val rank: VisualPrimitive => Int =
      case _: VisualPrimitive.SurfaceUnit    => 0
      case _: VisualPrimitive.ContextBand    => 1
      case _: VisualPrimitive.Region         => 2
      case _: VisualPrimitive.Thread         => 3
      case _: VisualPrimitive.Route          => 4
      case _: VisualPrimitive.Portal         => 5
      case _: VisualPrimitive.Landmark       => 6
      case _: VisualPrimitive.Gap            => 7
      case _: VisualPrimitive.Abstention     => 7
      case _: VisualPrimitive.UnsatisfiedLaw => 7
    marks.zipWithIndex.sortBy((m, i) => (rank(m), i)).map(_._1)

  /** The three rails a scene draws into: the layout-only surface rail, the projection's own
    * context-lane plot, and the layout-only epistemic rail.
    */
  private final case class Marks(
      surface: Vector[VisualPrimitive.SurfaceUnit],
      narrative: Vector[VisualPrimitive],
      epistemic: Vector[VisualPrimitive]
  )

  /** Preserve exhaustivity at the public sibling ADT boundary: a new case must be classified.
    *
    * The three absence marks are separated out because they carry no context lane at all (ADR 0002
    * D9): their geometry is an `EpistemicPlacement` over exact spans, or the stated absence of any
    * honest discourse position. Putting them in the lane plot would invent a lane for them.
    */
  private def partitionMarks(marks: Vector[VisualPrimitive]): Marks =
    marks.foldLeft(Marks(Vector.empty, Vector.empty, Vector.empty)) { (acc, mark) =>
      mark match
        case m: VisualPrimitive.SurfaceUnit    => acc.copy(surface = acc.surface :+ m)
        case m: VisualPrimitive.Region         => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Landmark       => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Thread         => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Portal         => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Route          => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.ContextBand    => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Gap            => acc.copy(epistemic = acc.epistemic :+ m)
        case m: VisualPrimitive.Abstention     => acc.copy(epistemic = acc.epistemic :+ m)
        case m: VisualPrimitive.UnsatisfiedLaw => acc.copy(epistemic = acc.epistemic :+ m)
    }

  private def maxContextLane(mark: VisualPrimitive): Option[Int] = mark match
    case _: VisualPrimitive.SurfaceUnit                => None
    case VisualPrimitive.Region(_, extent, _, _)       => Some(extent.lane1)
    case VisualPrimitive.Landmark(_, at, _, _, _)      => Some(at.lane)
    case VisualPrimitive.Thread(_, _, points)          => points.map(_.lane).maxOption
    case VisualPrimitive.Portal(_, from, to, _)        => Some(math.max(from.lane, to.lane))
    case VisualPrimitive.Route(_, from, to, _, _)      => Some(math.max(from.lane, to.lane))
    case VisualPrimitive.ContextBand(_, _, _, e, _, _) => Some(e.toVector.map(_.lane1).max)
    case _: VisualPrimitive.Gap                        => None
    case _: VisualPrimitive.Abstention                 => None
    case _: VisualPrimitive.UnsatisfiedLaw             => None

  private def surfaceKindRank(kind: SurfaceUnitKind): Int = kind match
    case SurfaceUnitKind.Paragraph => 0
    case SurfaceUnitKind.Sentence  => 1
    case SurfaceUnitKind.Clause    => 2
    case SurfaceUnitKind.Token     => 3

  private def orderedSurface(
      marks: Vector[VisualPrimitive.SurfaceUnit]
  ): Vector[VisualPrimitive.SurfaceUnit] =
    marks.sortBy(mark =>
      (
        mark.span.start,
        mark.span.endExclusive,
        surfaceKindRank(mark.kind),
        mark.unitOrdinal,
        mark.identity.mark.value
      )
    )

  /** Device placement only: kind is a categorical subrow and y distance has no semantics. */
  private def surfaceBand(kind: SurfaceUnitKind): (Double, Double) = kind match
    case SurfaceUnitKind.Paragraph => (0.05, 0.20)
    case SurfaceUnitKind.Sentence  => (0.25, 0.45)
    case SurfaceUnitKind.Clause    => (0.50, 0.70)
    case SurfaceUnitKind.Token     => (0.75, 0.95)

  private def surfaceViewport(discourseLength: Int): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(0.06, 0.85)
      size <- ig.Size.npc(0.90, 0.07)
      xScale <- ig.Interval(0.0, math.max(discourseLength.toDouble, 1.0))
      yScale <- ig.Interval(0.0, 1.0)
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.On,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  private def surfaceRail(
      marks: Vector[VisualPrimitive.SurfaceUnit],
      discourseLength: Int,
      style: Style.Params
  ): Either[GraphicsError, Option[ig.Grob]] =
    if marks.isEmpty then Right(None)
    else
      for
        viewport <- surfaceViewport(discourseLength)
        children <- orderedSurface(marks).traverse(m => markGroup(m, style))
      yield Some(ig.Grob.group(children, viewport = Some(viewport)))

  private def headerGrob(
      scene: NarrativeScene,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val p = scene.provenance
    val text =
      s"Narrative Atlas · ${scene.contract.kind} · zoom ${scene.zoom.narrative}/${scene.zoom.surface}" +
        s" · basis: ${p.basis.label} · source ${p.sourceChecksum.hex} · configuration ${p.configChecksum.hex}"
    for
      at <- ig.Point.npc(0.02, 0.95)
      grob <- ig.Grob.text(text, at, Style.labelAnchor, gp = style.header)
    yield grob

  private def native(x: Int, lane: Double): Either[GraphicsError, ig.Point] =
    ig.Point.native(x.toDouble, lane)

  private def centre(a: Anchor): Either[GraphicsError, ig.Point] = native(a.x, a.lane + 0.5)

  /** A label beside a native point: offset by an absolute extent so it never scales with data. */
  private def labelAt(
      x: Int,
      lane: Double,
      dxPoints: Double,
      dyPoints: Double,
      text: String,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    for
      px <- ig.LengthExpr.native(x.toDouble)
      py <- ig.LengthExpr.native(lane)
      dx <- ig.ExtentExpr.points(math.abs(dxPoints))
      dy <- ig.ExtentExpr.points(math.abs(dyPoints))
      at = ig.Point(
        if dxPoints < 0 then px - dx else px + dx,
        if dyPoints < 0 then py - dy else py + dy
      )
      grob <- ig.Grob.text(text, at, Style.labelAnchor, gp = style.label)
    yield grob

  /** `slot` is the layout-only vertical position an absence mark with no discourse position takes
    * in the epistemic rail's margin row; every other mark ignores it.
    */
  private def markGroup(
      mark: VisualPrimitive,
      style: Style.Params,
      slot: Double = 0.5
  ): Either[GraphicsError, ig.Grob] =
    for
      name <- GraphicsNames.ofMark(mark.identity.mark)
      children <- shape(mark, style, slot)
    yield ig.Grob.group(children, name = Some(name))

  private def shape(
      mark: VisualPrimitive,
      style: Style.Params,
      slot: Double
  ): Either[GraphicsError, Vector[ig.Grob]] = mark match
    case VisualPrimitive.SurfaceUnit(_, span, kind, _, _) =>
      val (y0, y1) = surfaceBand(kind)
      for
        corners <- Vector(
          native(span.start, y0),
          native(span.endExclusive, y0),
          native(span.endExclusive, y1),
          native(span.start, y1)
        ).sequence
        polygon <- ig.Grob.polygon(corners, gp = style.band)
      yield Vector(polygon)

    case VisualPrimitive.Region(_, e, label, _) =>
      for
        corners <- Vector(
          native(e.x0, e.lane0.toDouble),
          native(e.x1Exclusive, e.lane0.toDouble),
          native(e.x1Exclusive, e.lane1 + 1.0),
          native(e.x0, e.lane1 + 1.0)
        ).sequence
        polygon <- ig.Grob.polygon(corners, gp = style.region)
        text <- labelAt(e.x0, e.lane0.toDouble, 2.0, 5.0, label, style)
      yield Vector(polygon, text)

    case VisualPrimitive.Landmark(_, at, label, kind, _) =>
      val shape = kind match
        case LandmarkKind.Event => ig.PointShape.Circle
        case LandmarkKind.State => ig.PointShape.Square
      for
        c <- centre(at)
        size <- ig.ExtentExpr.points(5.0)
        points <- ig.Grob.points(Vector(c), size, shape, gp = style.landmark)
        text <- labelAt(at.x, at.lane + 0.5, 5.0, 0.0, label, style)
      yield Vector(points, text)

    case VisualPrimitive.Thread(_, label, pts) =>
      for
        points <- pts.traverse(centre)
        lines <- ig.Grob.lines(points, gp = style.thread)
        text <- labelAt(pts.head.x, pts.head.lane + 0.5, 0.0, -6.0, label, style)
      yield Vector(lines, text)

    case VisualPrimitive.Portal(_, from, to, mode) =>
      edge(from, to, mode.toString, style.portal, style)

    case VisualPrimitive.Route(_, from, to, layer, status) =>
      edge(from, to, s"$layer $status", style.route, style)

    // One filled rectangle per extent, never a hull over the gaps between them (ADR 0002 D4 row 6).
    // The narrated world of the War of the Ghosts is 281 separate extents precisely so the five
    // speech frames inside it are not swallowed; hulling would redraw the fabricated battle as
    // narration. Kind is the label and the lane, never a colour (V-U5).
    case VisualPrimitive.ContextBand(_, kind, lane, extents, _, basis) =>
      val label = s"${kind.label} · ${extents.length} extents · ${bandBasis(basis)}"
      for
        boxes <- extents.toVector.traverse { e =>
          Vector(
            native(e.x0, e.lane0.toDouble + 0.08),
            native(e.x1Exclusive, e.lane0.toDouble + 0.08),
            native(e.x1Exclusive, e.lane1 + 0.92),
            native(e.x0, e.lane1 + 0.92)
          ).sequence.flatMap(corners => ig.Grob.polygon(corners, gp = style.contextBand))
        }
        text <- labelAt(extents.head.x0, lane + 0.5, 2.0, 0.0, label, style)
      yield boxes :+ text

    case mark @ VisualPrimitive.Gap(_, family, target, reason, _, placement) =>
      absence(
        mark,
        s"gap ${claimFamily(family)} ${target.render}: ${reason.render}",
        placement,
        slot,
        style
      )

    case mark @ VisualPrimitive.Abstention(_, unit, reason, placement) =>
      absence(mark, s"abstention ${unit.value}: ${reason.render}", placement, slot, style)

    case mark @ VisualPrimitive.UnsatisfiedLaw(_, violation, placement) =>
      absence(
        mark,
        s"unsatisfied ${violation.law} @ ${violation.path}: ${violation.reason}",
        placement,
        slot,
        style
      )

  /** `ClaimFamily.Custom` renders as a Scala product string upstream; name it ourselves so the twin
    * and the picture read the same and no compiler-generated text reaches a label.
    */
  private def claimFamily(family: ClaimFamily): String = family match
    case ClaimFamily.Custom(namespace, name) => s"$namespace/$name"
    case other                               => other.toString

  private def bandBasis(basis: ContextBandBasis): String = basis match
    case ContextBandBasis.ExactScopeEvidence => "exact scope evidence"

  /** The non-colour channel each epistemic state is drawn in (ADR 0002 D9, V-U5). Four states, four
    * shapes: the state is readable in monochrome and at a glance, and never from a hue.
    */
  private def channelShape(channel: EpistemicChannel): ig.PointShape = channel match
    case EpistemicChannel.OpenHatch   => ig.PointShape.Square
    case EpistemicChannel.Fan         => ig.PointShape.Triangle
    case EpistemicChannel.Placeholder => ig.PointShape.Circle
    case EpistemicChannel.Bracket     => ig.PointShape.Cross

  private def channelName(channel: EpistemicChannel): String = channel match
    case EpistemicChannel.OpenHatch   => "open-hatch"
    case EpistemicChannel.Fan         => "fan"
    case EpistemicChannel.Placeholder => "placeholder"
    case EpistemicChannel.Bracket     => "bracket"

  /** Absence is drawn, never left as a hole in the ink (recovery plan §2.3).
    *
    * A mark that cites spans is drawn at exactly those spans, one glyph per span with a rule under
    * its extent: it may not be summarised into a hull, because the absence is about that material
    * and no other. A mark with no honest discourse position is drawn in a margin row at x = 0 with
    * the stated reason in its label, so it is visible without being placed somewhere it does not
    * belong. `slot` spreads those margin marks vertically; that spacing is device layout and means
    * nothing.
    */
  private def absence(
      mark: VisualPrimitive,
      label: String,
      placement: EpistemicPlacement,
      slot: Double,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val channel = mark.epistemicChannel.getOrElse(EpistemicChannel.Bracket)
    val shape = channelShape(channel)
    val text = s"[${channelName(channel)}] $label"
    placement match
      case EpistemicPlacement.AtSpans(spans) =>
        val extents = spans.spans.toVector
        for
          size <- ig.ExtentExpr.points(4.0)
          glyphs <- extents.traverse { span =>
            for
              at <- native((span.start + span.endExclusive) / 2, Style.epistemicSpanRow)
              grob <- ig.Grob.points(Vector(at), size, shape, gp = style.epistemic)
            yield grob
          }
          rules <- extents.traverse { span =>
            for
              a <- native(span.start, Style.epistemicSpanRule)
              b <- native(span.endExclusive, Style.epistemicSpanRule)
              grob <- ig.Grob.lines(Vector(a, b), gp = style.epistemicRule)
            yield grob
          }
          caption <- labelAt(
            extents.head.start,
            Style.epistemicSpanRow,
            4.0,
            -5.0,
            text,
            style
          )
        yield glyphs ++ rules :+ caption

      case EpistemicPlacement.NoDiscoursePosition(reason) =>
        for
          size <- ig.ExtentExpr.points(4.0)
          at <- native(0, slot)
          glyph <- ig.Grob.points(Vector(at), size, shape, gp = style.epistemic)
          caption <- labelAt(
            0,
            slot,
            4.0,
            0.0,
            s"$text · no discourse position: ${reason.render}",
            style
          )
        yield Vector(glyph, caption)

  /** A layout-only rail for the marks that carry no context lane. Its y coordinate is device
    * placement and carries no meaning, exactly as the surface rail's does.
    */
  private def epistemicRail(
      marks: Vector[VisualPrimitive],
      discourseLength: Int,
      style: Style.Params
  ): Either[GraphicsError, Option[ig.Grob]] =
    if marks.isEmpty then Right(None)
    else
      val ordered = marks.sortBy(m => (epistemicSortKey(m), m.identity.mark.value))
      val margin = ordered.filter(_.epistemicPlacement.forall(_.spanSet.isEmpty))
      val slots =
        margin.zipWithIndex.map((m, i) => m.identity.mark.value -> slotFor(i, margin.length)).toMap
      for
        viewport <- Style.epistemicViewport(discourseLength)
        children <- ordered.traverse(m =>
          markGroup(m, style, slots.getOrElse(m.identity.mark.value, 0.5))
        )
      yield Some(ig.Grob.group(children, viewport = Some(viewport)))

  /** Evenly spread within the margin row; device layout only. */
  private def slotFor(index: Int, total: Int): Double =
    Style.epistemicMarginTop +
      (index + 0.5) / math.max(total, 1) * (Style.epistemicMarginBottom - Style.epistemicMarginTop)

  /** Spanned marks first, then the margin, so a reader meets the placed evidence before the
    * unplaceable; within each, discourse order. Draw order only.
    */
  private def epistemicSortKey(mark: VisualPrimitive): (Int, Int) =
    mark.epistemicPlacement.flatMap(_.spanSet) match
      case Some(spans) => (0, spans.minSpan.start)
      case None        => (1, 0)

  private def edge(
      from: Anchor,
      to: Anchor,
      label: String,
      gp: ig.GraphicParams,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    for
      a <- centre(from)
      b <- centre(to)
      lines <- ig.Grob.lines(Vector(a, b), gp = gp)
      text <- labelAt(
        (from.x + to.x) / 2,
        (from.lane + to.lane) / 2.0 + 0.5,
        0.0,
        -6.0,
        label,
        style
      )
    yield Vector(lines, text)

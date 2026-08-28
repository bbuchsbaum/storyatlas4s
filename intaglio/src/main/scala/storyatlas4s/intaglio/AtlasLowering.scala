package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storymodel4s.view.*

/** Pure lowering of a compiled [[NarrativeScene]] to an Intaglio scene.
  *
  * The Discourse Atlas already placed every mark: x is an exact discourse offset and y is a context
  * lane (ADR 0002 §2, D4). This lowering copies those coordinates into a native-unit viewport whose
  * x scale is `[0, discourseLength)` and whose y scale is one unit per lane; it computes no hull,
  * no ordering, and no position of its own. Each mark becomes one named group (`data-name` =
  * `MarkId`) holding its shape and its label; nothing else in the output carries a name.
  *
  * `discourseLength` is the length of the canonical text in UTF-16 code units. It is passed as a
  * number because a scene does not carry its source and Intaglio must never see canonical text
  * (D6); the same length at every zoom level keeps x comparable across levels (V-I1).
  */
object AtlasLowering:

  def lower(scene: NarrativeScene, discourseLength: Int): Either[GraphicsError, ig.Scene] =
    for
      style <- Style.params
      laneCount = scene.marks.foldLeft(1)((acc, m) => math.max(acc, maxLane(m) + 1))
      viewport <- Style.plotViewport(discourseLength.toDouble, laneCount.toDouble)
      header <- headerGrob(scene, style)
      marks <- ordered(scene.marks).traverse(m => markGroup(m, style))
      plot = ig.Grob.group(marks, viewport = Some(viewport))
    yield ig.Scene(Vector(header, plot))

  /** Draw order only (regions under everything, landmarks on top); never an inference. */
  private def ordered(marks: Vector[VisualPrimitive]): Vector[VisualPrimitive] =
    val rank: VisualPrimitive => Int =
      case _: VisualPrimitive.Region   => 0
      case _: VisualPrimitive.Thread   => 1
      case _: VisualPrimitive.Route    => 2
      case _: VisualPrimitive.Portal   => 3
      case _: VisualPrimitive.Landmark => 4
    marks.zipWithIndex.sortBy((m, i) => (rank(m), i)).map(_._1)

  private def maxLane(mark: VisualPrimitive): Int = mark match
    case VisualPrimitive.Region(_, extent, _, _)  => extent.lane1
    case VisualPrimitive.Landmark(_, at, _, _)    => at.lane
    case VisualPrimitive.Thread(_, _, points)     => points.map(_.lane).maxOption.getOrElse(0)
    case VisualPrimitive.Portal(_, from, to, _)   => math.max(from.lane, to.lane)
    case VisualPrimitive.Route(_, from, to, _, _) => math.max(from.lane, to.lane)

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

  private def markGroup(
      mark: VisualPrimitive,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    for
      name <- GraphicsNames.ofMark(mark.identity.mark)
      children <- shape(mark, style)
    yield ig.Grob.group(children, name = Some(name))

  private def shape(
      mark: VisualPrimitive,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] = mark match
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

    case VisualPrimitive.Landmark(_, at, label, kind) =>
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

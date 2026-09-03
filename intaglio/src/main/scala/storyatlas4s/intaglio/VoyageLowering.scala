package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** Pure lowering `VoyageScene → intaglio.Scene` (ADR 0002 §14 D4).
  *
  * It draws exactly the marks the compiler placed: a unit anchor as a point whose area is the
  * anchor's posterior mass, its origin as shape (circle for the posterior argmax, diamond for a
  * decode-bound anchor, hollow dashed diamond for a decode-filled one), a group-level anchor as the
  * group's span, an external-dominant unit hollow, an unanchored unit on the absence rail, and an
  * untimed unit in the margin row with its reason. Coded intervals from the independent coding are
  * bands; they are not marks and carry no name. Every mark's `data-name` is its `MarkId`.
  *
  * Nothing here decides anything: which units show their alternatives is an input, because it is
  * the shell's selection, never the lowering's judgement.
  */
object VoyageLowering:

  /** Pixel geometry of the plate; the same numbers the owner's reference page uses. */
  final case class Box(
      width: Int,
      left: Int,
      right: Int,
      top: Int,
      bottom: Int,
      plotHeight: Int,
      trackHeight: Int,
      gap: Int
  ):
    def plotWidth: Int = width - left - right
    def height: Int = top + plotHeight + gap + trackHeight + bottom
    def trackTop: Int = top + plotHeight + gap

  object Box:
    val default: Box = Box(
      width = 1100,
      left = 62,
      right = 132,
      top = 14,
      bottom = 28,
      plotHeight = 540,
      trackHeight = 110,
      gap = 26
    )

  /** Light-instrument palette: literal because intaglio paints literal colour; the shell's CSS may
    * restyle by class, but the edition must stand on its own.
    */
  private object Palette:
    val model = ig.Rgba.unsafe(0x1b, 0x7f, 0xa3)
    val modelSoft = ig.Rgba.unsafe(0x1b, 0x7f, 0xa3, 0.16)
    val gold = ig.Rgba.unsafe(0xc9, 0x92, 0x2e)
    val goldBand = ig.Rgba.unsafe(0xc9, 0x92, 0x2e, 0.17)
    val goldLine = ig.Rgba.unsafe(0xc9, 0x92, 0x2e, 0.55)
    val external = ig.Rgba.unsafe(0x8a, 0x93, 0x9d)
    val raw = ig.Rgba.unsafe(0x9a, 0xa3, 0xab)
    val ink = ig.Rgba.unsafe(0x1b, 0x21, 0x26)
    val ink2 = ig.Rgba.unsafe(0x4b, 0x56, 0x5f)
    val hair = ig.Rgba.unsafe(0xd5, 0xda, 0xd8)
    val surface = ig.Rgba.unsafe(0xff, 0xff, 0xff)

  private def params(
      stroke: Option[ig.Rgba],
      fill: Option[ig.Rgba],
      width: Double = 1.0,
      line: ig.LineType = ig.LineType.Solid,
      alpha: Double = 1.0,
      fontPx: Double = 10.5
  ): Either[GraphicsError, ig.GraphicParams] =
    ig.Length
      .points(fontPx * 0.75)
      .flatMap(size =>
        ig.GraphicParams.checked(
          stroke = stroke,
          fill = fill,
          lineWidth = width,
          lineType = line,
          alpha = alpha,
          fontFamily = Some("IBM Plex Mono, Menlo, monospace"),
          fontSize = size
        )
      )

  /** The whole plate in device pixels, y down; every coordinate below is a pixel. */
  private def viewport(box: Box): Either[GraphicsError, ig.Viewport] =
    for
      xScale <- ig.Interval(0.0, box.width.toDouble)
      yScale <- ig.Interval(0.0, box.height.toDouble)
      vp <- ig.Viewport.checked(
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield vp

  private def px(x: Double, y: Double): ig.Point = ig.Point.nativeUnsafe(x, y)

  /** Pixel scales of the two clocks. */
  final case class Scales(box: Box, recallLength: Double, sourceEnd: Double):
    def x(t: Double): Double = box.left + box.plotWidth * (t / math.max(recallLength, 1e-9))
    def y(s: Double): Double =
      box.top + box.plotHeight - box.plotHeight * (s / math.max(sourceEnd, 1e-9))
    def track(group: Int, groupCount: Int): Double =
      box.trackTop + box.trackHeight - box.trackHeight * ((group - 0.5) / math.max(groupCount, 1))

  def scales(scene: VoyageScene, box: Box): Scales =
    Scales(box, scene.recallLength.value, scene.timeline.end)

  /** Radius in pixels for a mass: area grows with mass, and a zero-mass anchor keeps a legible
    * hollow shape rather than vanishing.
    */
  def radius(mass: Double): Double = 2.6 + 8.0 * math.sqrt(math.max(0.0, mass))

  /** Lower a scene; `alternativesFor` names the units whose posterior columns are drawn. */
  def lower(
      scene: VoyageScene,
      alternativesFor: Set[RecallUnitId] = Set.empty,
      box: Box = Box.default
  ): Either[GraphicsError, ig.Scene] =
    val sc = scales(scene, box)
    for
      vp <- viewport(box)
      ground <- groundLayer(scene, sc, vp)
      bands <- codingLayer(scene, sc, vp)
      links <- linkLayer(scene, sc, vp, alternativesFor)
      marks <- markLayer(scene, sc, vp)
      track <- trackLayer(scene, sc, vp)
    yield ig.Scene(Vector(ground, bands, links, marks, track))

  // ------------------------------------------------------------------ layers

  private def groundLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val groups = scene.timeline.groups.sortBy(_.ordinal)
    for
      hair <- params(Some(Palette.hair), None)
      goldLine <- params(Some(Palette.goldLine), None, width = 0.6)
      label <- params(None, Some(Palette.ink2))
      boundaries <- groups.traverse { g =>
        val y = sc.y(g.span.start.value)
        ig.Grob.lines(Vector(px(box.left, y), px(box.width - box.right, y)), goldLine)
      }
      groupLabels <- groups
        .filter(g => sc.y(g.span.start.value) - sc.y(g.span.end.value) >= 11.0)
        .traverse { g =>
          val text = if g.label.length > 19 then g.label.take(18) + "…" else g.label
          ig.Grob.text(
            text,
            px(box.width - box.right + 6, sc.y(g.span.midpoint) + 3.5),
            ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
            gp = label
          )
        }
      yAxis <- ig.Grob.lines(
        Vector(px(box.left, box.top), px(box.left, box.top + box.plotHeight)),
        hair
      )
      xAxis <- ig.Grob.lines(
        Vector(
          px(box.left, box.top + box.plotHeight),
          px(box.width - box.right, box.top + box.plotHeight)
        ),
        hair
      )
      yTicks <- ticks(0.0, sc.sourceEnd, 300.0).traverse { s =>
        ig.Grob.lines(Vector(px(box.left - 4, sc.y(s)), px(box.left, sc.y(s))), hair)
      }
      yLabels <- ticks(0.0, sc.sourceEnd, 300.0).traverse { s =>
        ig.Grob.text(
          clock(s),
          px(box.left - 7, sc.y(s) + 3.5),
          ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
          gp = label
        )
      }
      xTicks <- ticks(0.0, sc.recallLength, 60.0).traverse { t =>
        ig.Grob.lines(
          Vector(px(sc.x(t), box.top + box.plotHeight), px(sc.x(t), box.top + box.plotHeight + 4)),
          hair
        )
      }
      xLabels <- ticks(0.0, sc.recallLength, 300.0).traverse { t =>
        ig.Grob.text(
          clock(t),
          px(sc.x(t), box.top + box.plotHeight + 16),
          ig.Anchor(ig.HJust.Center, ig.VJust.Bottom),
          gp = label
        )
      }
      axisNames <- Vector(
        ig.Grob.text(
          "source",
          px(12, box.top + 10),
          ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
          gp = label
        ),
        ig.Grob.text(
          "recall time",
          px(box.width - box.right, box.top + box.plotHeight + 16),
          ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
          gp = label
        )
      ).sequence
      layer = ig.Grob.group(
        boundaries ++ groupLabels ++ Vector(
          yAxis,
          xAxis
        ) ++ yTicks ++ yLabels ++ xTicks ++ xLabels ++ axisNames,
        viewport = Some(vp)
      )
    yield layer

  private def codingLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport
  ): Either[GraphicsError, ig.Grob] =
    val intervals = scene.coding.map(_.intervals).getOrElse(Vector.empty)
    for
      band <- params(None, Some(Palette.goldBand))
      rects <- intervals.traverse { iv =>
        scene.timeline.byGroup.get(iv.group) match
          case None    => Right(ig.Grob.group(Vector.empty))
          case Some(g) =>
            val x0 = sc.x(iv.recall.start.value)
            val x1 = sc.x(iv.recall.end.value)
            val y0 = sc.y(g.span.end.value)
            val y1 = sc.y(g.span.start.value)
            ig.Grob.polygon(Vector(px(x0, y0), px(x1, y0), px(x1, y1), px(x0, y1)), band)
      }
      layer = ig.Grob.group(rects, viewport = Some(vp))
    yield layer

  private def linkLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport,
      alternativesFor: Set[RecallUnitId]
  ): Either[GraphicsError, ig.Grob] =
    val anchors = scene.marks.collect { case m: VoyageMark.UnitAnchor => m }
    val alternatives = scene.marks.collect {
      case m: VoyageMark.Alternative if alternativesFor.contains(m.unit) => m
    }
    val anchorOf = anchors.map(m => m.unit -> m).toMap
    for
      ghostLine <- params(Some(Palette.raw), None, width = 0.8)
      ghostDot <- params(None, Some(Palette.raw))
      altLine <- params(Some(Palette.model), None, width = 0.8, alpha = 0.7)
      altRing <- params(Some(Palette.model), None, width = 1.2, line = ig.LineType.Dashed)
      ghosts <- anchors
        .filter(m => m.origin != AnchorOrigin.PosteriorArgmax)
        .flatMap(m => m.argmax.flatMap(scene.timeline.node).map(n => m -> n))
        .traverse { case (m, n) =>
          val x = sc.x(m.at.value)
          for
            line <- ig.Grob.lines(
              Vector(px(x, sc.y(n.span.midpoint)), px(x, sc.y(m.span.midpoint))),
              ghostLine
            )
            dot <- ig.Grob.points(
              Vector(px(x, sc.y(n.span.midpoint))),
              ig.ExtentExpr.nativeUnsafe(4.4),
              ig.PointShape.Circle,
              ghostDot
            )
          yield Vector(line, dot)
        }
      alts <- alternatives.traverse { a =>
        anchorOf.get(a.unit) match
          case None    => Right(ig.Grob.group(Vector.empty))
          case Some(m) =>
            val x = sc.x(m.at.value)
            for
              name <- GraphicsNames.ofMark(a.identity.mark)
              line <- ig.Grob.lines(
                Vector(px(x, sc.y(a.span.midpoint)), px(x, sc.y(m.span.midpoint))),
                altLine
              )
              ring <- ig.Grob.points(
                Vector(px(x, sc.y(a.span.midpoint))),
                ig.ExtentExpr.nativeUnsafe(2.0 * (2.0 + 7.0 * math.sqrt(a.mass))),
                ig.PointShape.Circle,
                altRing
              )
              g = ig.Grob.group(Vector(line, ring), name = Some(name))
            yield g
      }
      layer = ig.Grob.group(ghosts.flatten ++ alts, viewport = Some(vp))
    yield layer

  private def markLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val untimed = scene.marks.collect { case m: VoyageMark.Untimed => m }
    for
      grobs <- scene.marks.traverse {
        case m: VoyageMark.UnitAnchor =>
          val x = sc.x(m.at.value)
          val cy = sc.y(m.span.midpoint)
          val externalDominant = m.externalMass > 0.5
          val opacity = if externalDominant then 0.9 else 0.45 + 0.55 * m.sourceMass
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            grob <-
              if m.level > 0 then
                // a group-level anchor spans its whole group
                params(None, Some(Palette.model), alpha = 0.55).flatMap(gp =>
                  ig.Grob.polygon(
                    Vector(
                      px(x - 2.5, sc.y(m.span.end.value)),
                      px(x + 2.5, sc.y(m.span.end.value)),
                      px(x + 2.5, sc.y(m.span.start.value)),
                      px(x - 2.5, sc.y(m.span.start.value))
                    ),
                    gp,
                    name = Some(name)
                  )
                )
              else
                m.origin match
                  case AnchorOrigin.DecodeFilled =>
                    params(Some(Palette.model), None, width = 1.4, line = ig.LineType.Dashed)
                      .flatMap(gp => diamond(x, cy, 4.5, gp, name))
                  case AnchorOrigin.DecodeBound =>
                    val fill = if externalDominant then None else Some(Palette.model)
                    val stroke =
                      if externalDominant then Some(Palette.external) else Some(Palette.surface)
                    params(
                      stroke,
                      fill,
                      width = if externalDominant then 1.6 else 1.2,
                      alpha = opacity
                    )
                      .flatMap(gp => diamond(x, cy, radius(m.mass) * 1.2533, gp, name))
                  case AnchorOrigin.PosteriorArgmax =>
                    val fill = if externalDominant then None else Some(Palette.model)
                    val stroke =
                      if externalDominant then Some(Palette.external) else Some(Palette.surface)
                    params(
                      stroke,
                      fill,
                      width = if externalDominant then 1.6 else 1.2,
                      alpha = opacity
                    )
                      .flatMap(gp =>
                        ig.Grob.points(
                          Vector(px(x, cy)),
                          ig.ExtentExpr.nativeUnsafe(2.0 * radius(m.mass)),
                          ig.PointShape.Circle,
                          gp,
                          name = Some(name)
                        )
                      )
          yield grob
        case a: VoyageMark.Alternative =>
          // alternatives are drawn by the link layer only for the units the shell names
          GraphicsNames.ofMark(a.identity.mark).map(_ => ig.Grob.group(Vector.empty))
        case m: VoyageMark.Unanchored =>
          // the absence rail, just below the plot: a cross, hollow, at the unit's recall time
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            gp <- params(Some(Palette.external), None, width = 1.4)
            g <- ig.Grob.points(
              Vector(px(sc.x(m.at.value), box.top + box.plotHeight + 8)),
              ig.ExtentExpr.nativeUnsafe(7.0),
              ig.PointShape.Cross,
              gp,
              name = Some(name)
            )
          yield g
        case m: VoyageMark.Untimed =>
          // the margin row: no honest recall position, so a stated reason in the margin
          val row = untimed.indexWhere(_.identity.mark == m.identity.mark)
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            gp <- params(None, Some(Palette.ink2))
            g <- ig.Grob.text(
              s"unit ${m.unitOrdinal}: no recall onset",
              px(box.left + 4, box.top + 24 + 12 * row),
              ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
              gp = gp,
              name = Some(name)
            )
          yield g
      }
      layer = ig.Grob.group(grobs, viewport = Some(vp))
    yield layer

  private def trackLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val groupCount = scene.timeline.groups.size
    val anchors = scene.marks.collect { case m: VoyageMark.UnitAnchor => m }.sortBy(_.at.value)
    val intervals = scene.coding.map(_.intervals).getOrElse(Vector.empty)
    def step(groupOf: VoyageMark.UnitAnchor => Option[Int], gp: ig.GraphicParams) =
      anchors.zipWithIndex.flatMap { case (m, i) =>
        groupOf(m).map { g =>
          val x0 = sc.x(m.at.value)
          val x1 = anchors
            .lift(i + 1)
            .map(n => sc.x(n.at.value))
            .getOrElse(sc.x(math.min(sc.recallLength, m.at.value + 8)))
          val y = sc.track(g, groupCount)
          ig.Grob.lines(Vector(px(x0, y), px(x1, y)), gp)
        }
      }.sequence
    for
      hair <- params(Some(Palette.hair), None)
      label <- params(None, Some(Palette.ink2))
      goldGp <- params(Some(Palette.gold), None, width = 2.5)
      modelGp <- params(Some(Palette.model), None, width = 1.8)
      rawGp <- params(Some(Palette.raw), None, width = 1.2, line = ig.LineType.Dashed)
      axis <- ig.Grob.lines(
        Vector(px(box.left, box.trackTop), px(box.left, box.trackTop + box.trackHeight)),
        hair
      )
      tickLabels <- Vector(
        1,
        groupCount / 5,
        2 * groupCount / 5,
        3 * groupCount / 5,
        4 * groupCount / 5,
        groupCount
      )
        .filter(_ >= 1)
        .distinct
        .traverse(g =>
          ig.Grob.text(
            s"g $g",
            px(box.left - 7, sc.track(g, groupCount) + 3.5),
            ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
            gp = label
          )
        )
      caption <- ig.Grob.text(
        "group index · coding (amber) · this placement (blue) · posterior argmax (grey, dashed)",
        px(box.left + 6, box.trackTop + 10),
        ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
        gp = label
      )
      gold <- intervals.traverse(iv =>
        ig.Grob.lines(
          Vector(
            px(sc.x(iv.recall.start.value), sc.track(iv.group, groupCount)),
            px(sc.x(iv.recall.end.value), sc.track(iv.group, groupCount))
          ),
          goldGp
        )
      )
      raw <- step(m => m.argmax.flatMap(scene.timeline.node).flatMap(_.group), rawGp)
      model <- step(_.group, modelGp)
      layer = ig.Grob.group(
        Vector(axis, caption) ++ tickLabels ++ gold ++ raw ++ model,
        viewport = Some(vp)
      )
    yield layer

  // ------------------------------------------------------------------ helpers

  private def diamond(
      x: Double,
      cy: Double,
      d: Double,
      gp: ig.GraphicParams,
      name: ig.GraphicsName
  ): Either[GraphicsError, ig.Grob] =
    ig.Grob.polygon(
      Vector(px(x, cy - d), px(x + d, cy), px(x, cy + d), px(x - d, cy)),
      gp,
      name = Some(name)
    )

  private def ticks(from: Double, to: Double, step: Double): Vector[Double] =
    if to <= from then Vector(from)
    else Vector.iterate(from, ((to - from) / step).toInt + 1)(_ + step)

  /** `m:ss` on a clock, as the reference page prints it. */
  def clock(seconds: Double): String =
    val whole = math.round(seconds)
    val m = whole / 60
    val s = whole % 60
    s"$m:${if s < 10 then "0" else ""}$s"

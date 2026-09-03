package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError

/** Fixed graphical parameters of the lowering. Nothing here depends on the model: relation type and
  * epistemic status are rendered as text (ADR 0002 V-U5), and the only colour is a neutral fill
  * that distinguishes filled extents from strokes.
  */
private[intaglio] object Style:
  private def rgba(r: Int, g: Int, b: Int, a: Double): Either[GraphicsError, ig.Rgba] =
    ig.Rgba(r, g, b, a)

  private def font(points: Double): Either[GraphicsError, ig.Length] =
    ig.Length.points(points)

  final case class Params(
      region: ig.GraphicParams,
      run: ig.GraphicParams,
      annotation: ig.GraphicParams,
      band: ig.GraphicParams,
      contextBand: ig.GraphicParams,
      landmark: ig.GraphicParams,
      thread: ig.GraphicParams,
      portal: ig.GraphicParams,
      route: ig.GraphicParams,
      epistemic: ig.GraphicParams,
      epistemicRule: ig.GraphicParams,
      label: ig.GraphicParams,
      header: ig.GraphicParams
  )

  val params: Either[GraphicsError, Params] =
    for
      grey <- rgba(120, 120, 120, 1.0)
      fill <- rgba(160, 160, 160, 0.30)
      runFill <- rgba(60, 60, 60, 0.25)
      small <- font(7.0)
      base <- font(9.0)
      region <- ig.GraphicParams.checked(stroke = Some(grey), fill = Some(fill), lineWidth = 0.8)
      run <- ig.GraphicParams.checked(stroke = None, fill = Some(runFill))
      annotation <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), fill = Some(fill))
      // Page-overlay bands are fill-only: stacked in a line box they can be a pixel tall, and a
      // stroke on each would smear into one stripe.
      band <- ig.GraphicParams.checked(stroke = None, fill = Some(fill))
      // A context band is one extent per support span. It carries a stroke as well as a fill so a
      // 26-unit speech frame is still visible beside a 176-unit one, and so the gaps between the
      // narrated world's 281 extents read as gaps rather than as one washed stripe.
      contextBand <- ig.GraphicParams.checked(
        stroke = Some(grey),
        fill = Some(fill),
        lineWidth = 0.5
      )
      landmark <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), fill = Some(ig.Rgba.Black))
      thread <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), lineWidth = 1.5)
      portal <- ig.GraphicParams.checked(
        stroke = Some(ig.Rgba.Black),
        lineWidth = 1.0,
        lineType = ig.LineType.Dashed
      )
      route <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), lineWidth = 1.0)
      // Absence marks are unfilled: an open glyph is what distinguishes "we could not say" from a
      // solid landmark that asserts something. The shape carries the state, never a colour (V-U5).
      epistemic <- ig.GraphicParams.checked(
        stroke = Some(ig.Rgba.Black),
        fill = None,
        lineWidth = 0.9
      )
      epistemicRule <- ig.GraphicParams.checked(
        stroke = Some(grey),
        lineWidth = 0.7,
        lineType = ig.LineType.Dotted
      )
      label <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), fontSize = small)
      header <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), fontSize = base)
    yield Params(
      region,
      run,
      annotation,
      band,
      contextBand,
      landmark,
      thread,
      portal,
      route,
      epistemic,
      epistemicRule,
      label,
      header
    )

  /** The epistemic rail's own y coordinates, in its y-down [0,1] frame. Device layout only: no
    * vertical distance in this rail means anything, exactly as in the surface rail.
    */
  val epistemicSpanRow: Double = 0.30
  val epistemicSpanRule: Double = 0.46
  val epistemicMarginTop: Double = 0.62
  val epistemicMarginBottom: Double = 0.92

  /** The layout-only rail for marks that carry no context lane, below the plot. */
  def epistemicViewport(discourseLength: Int): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(0.06, 0.04)
      size <- ig.Size.npc(0.90, 0.16)
      xScale <- ig.Interval(0.0, math.max(discourseLength.toDouble, 1.0))
      yScale <- ig.Interval(0.0, 1.0)
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  /** Plot area inside the page: the root frame is y-up npc; the plot frame is y-down native. */
  def plotViewport(xUpper: Double, yUpper: Double): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(0.06, 0.22)
      size <- ig.Size.npc(0.90, 0.62)
      xScale <- ig.Interval(0.0, math.max(xUpper, 1.0))
      yScale <- ig.Interval(0.0, math.max(yUpper, 1.0))
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  val labelAnchor: ig.Anchor = ig.Anchor(ig.HJust.Left, ig.VJust.Center)
  val rowLabelAnchor: ig.Anchor = ig.Anchor(ig.HJust.Right, ig.VJust.Center)

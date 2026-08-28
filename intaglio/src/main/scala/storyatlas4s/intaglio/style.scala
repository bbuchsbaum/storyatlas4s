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
      landmark: ig.GraphicParams,
      thread: ig.GraphicParams,
      portal: ig.GraphicParams,
      route: ig.GraphicParams,
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
      landmark <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), fill = Some(ig.Rgba.Black))
      thread <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), lineWidth = 1.5)
      portal <- ig.GraphicParams.checked(
        stroke = Some(ig.Rgba.Black),
        lineWidth = 1.0,
        lineType = ig.LineType.Dashed
      )
      route <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), lineWidth = 1.0)
      label <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), fontSize = small)
      header <- ig.GraphicParams.checked(stroke = Some(ig.Rgba.Black), fontSize = base)
    yield Params(region, run, annotation, landmark, thread, portal, route, label, header)

  /** Plot area inside the page: the root frame is y-up npc; the plot frame is y-down native. */
  def plotViewport(xUpper: Double, yUpper: Double): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(0.06, 0.04)
      size <- ig.Size.npc(0.90, 0.80)
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

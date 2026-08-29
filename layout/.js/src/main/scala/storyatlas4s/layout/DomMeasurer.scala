package storyatlas4s.layout

import org.scalajs.dom
import scala.scalajs.js

/** The live DOM measurer (ADR 0002 D3): per-code-point advances from a 2-D canvas context, in the
  * browser's own font resolution. Identity-stable only, never a publication backend.
  *
  * Each code point is measured on its own (`measureText`, no kerning, no shaping), so the additive
  * width model the paginator assumes holds exactly over the table; a text rail set in the same
  * family and size under `white-space: pre` then breaks where the paginator broke it, up to the
  * browser's own kerning of the placed line. A newline has advance 0, a surrogate pair carries its
  * advance on the high surrogate, and the line height is `lineHeightPerEm / 1000` em, the same
  * convention as the table and AWT measurers, so a `LayoutReceipt` reads the same across all three.
  *
  * The width provider is a seam: [[DomMeasurer.canvas]] binds it to a canvas context, and
  * [[DomMeasurer.of]] takes any `(text, style) => width in CSS pixels`, which is how the measurer
  * is tested without a browser. Whatever the provider returns becomes data; nothing here is a
  * layout.
  */
final class DomMeasurer private (
    val name: String,
    unitsPerPixel: Int,
    lineHeightPerEm: Int,
    width: DomMeasurer.TextWidth
) extends Measurer:

  def measure(run: TextRun, style: TextStyle): Either[LayoutError, RunMetrics] =
    val text = run.text
    val advances = Vector.newBuilder[Int]
    var index = 0
    var problem: Option[LayoutError] = None
    while problem.isEmpty && index < text.length do
      val codePoint = text.codePointAt(index)
      val count = Character.charCount(codePoint)
      if codePoint == '\n'.toInt then advances += 0
      else
        width(text.substring(index, index + count), style) match
          case Left(error) => problem = Some(error)
          case Right(px)   =>
            if px.isNaN || px.isInfinite || px < 0.0 then
              problem = Some(
                LayoutError.Measurement(name, s"width $px of U+${codePoint.toHexString} at $index")
              )
            else
              val advance = math.round(px * unitsPerPixel)
              if advance > Int.MaxValue then
                problem = Some(
                  LayoutError
                    .Measurement(name, s"advances at ${style.sizePx}px exceed ${Int.MaxValue}")
                )
              else advances += advance.toInt
      if count == 2 then advances += 0
      index += count
    val lineHeight = lineHeightPerEm.toLong * style.sizePx
    problem match
      case Some(error)                       => Left(error)
      case None if lineHeight > Int.MaxValue =>
        Left(
          LayoutError
            .Measurement(name, s"line height at ${style.sizePx}px exceeds ${Int.MaxValue}")
        )
      case None => RunMetrics.of(advances.result(), lineHeight.toInt, unitsPerPixel)

object DomMeasurer:
  /** Width of one code point in CSS pixels under a style, or why it could not be measured. */
  type TextWidth = (String, TextStyle) => Either[LayoutError, Double]

  val Version: String = "dom-canvas/1"

  /** A measurer over any width provider; `name` must have no whitespace (it is a receipt field). */
  def of(
      name: String,
      unitsPerPixel: Int,
      lineHeightPerEm: Int,
      width: TextWidth
  ): Either[LayoutError, DomMeasurer] =
    if name.trim.isEmpty || name.exists(c => c.isWhitespace || c.isControl) then
      Left(LayoutError.InvalidSpec("DomMeasurer.name", "empty or contains whitespace"))
    else if unitsPerPixel <= 0 then
      Left(LayoutError.InvalidSpec("DomMeasurer.unitsPerPixel", s"$unitsPerPixel is not positive"))
    else if lineHeightPerEm <= 0 then
      Left(
        LayoutError.InvalidSpec("DomMeasurer.lineHeightPerEm", s"$lineHeightPerEm is not positive")
      )
    else Right(new DomMeasurer(name, unitsPerPixel, lineHeightPerEm, width))

  /** The browser measurer: a detached `<canvas>` 2-D context, one `measureText` per code point
    * (memoised per font string). Unavailable outside a document or where the context cannot be
    * created, rather than guessing widths.
    */
  def canvas(
      unitsPerPixel: Int = 1000,
      lineHeightPerEm: Int = 1200
  ): Either[LayoutError, DomMeasurer] =
    val name = s"$Version/$unitsPerPixel/$lineHeightPerEm"
    for
      context <- canvasContext(name)
      measurer <- of(name, unitsPerPixel, lineHeightPerEm, canvasWidth(name, context))
    yield measurer

  private def canvasContext(name: String): Either[LayoutError, dom.CanvasRenderingContext2D] =
    if js.typeOf(js.Dynamic.global.document) == "undefined" then
      Left(LayoutError.Unavailable(name, "no document (not running in a browser)"))
    else
      try
        val canvas = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
        val context = canvas.getContext("2d")
        if context == null then Left(LayoutError.Unavailable(name, "no 2-D canvas context"))
        else Right(context.asInstanceOf[dom.CanvasRenderingContext2D])
      catch
        case e: Throwable =>
          Left(LayoutError.Unavailable(name, Option(e.getMessage).getOrElse(e.getClass.getName)))

  private def canvasWidth(name: String, context: dom.CanvasRenderingContext2D): TextWidth =
    val cache = scala.collection.mutable.HashMap.empty[(String, String), Double]
    (text, style) =>
      val font = cssFont(style)
      cache.get((font, text)) match
        case Some(px) => Right(px)
        case None     =>
          try
            context.font = font
            // A font string the canvas rejects leaves the previous font in place; the size is the
            // one part of the request every accepted string echoes back, so its absence is refusal.
            if !context.font.contains(s"${style.sizePx}px") then
              Left(
                LayoutError
                  .Measurement(name, s"canvas rejected font '$font' (got '${context.font}')")
              )
            else
              val px = context.measureText(text).width
              cache.update((font, text), px)
              Right(px)
          catch
            case e: Throwable =>
              Left(
                LayoutError.Measurement(name, Option(e.getMessage).getOrElse(e.getClass.getName))
              )

  /** The canvas font shorthand for a style: a generic keyword or an identifier is written bare, any
    * other family is a quoted string with `\` and `"` escaped, so one `TextStyle` family is always
    * one family here too (never a fallback list).
    */
  private[layout] def cssFont(style: TextStyle): String =
    val family = style.family
    val bare = family.forall(c =>
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'
    ) && !family.head.isDigit
    val rendered =
      if bare then family
      else "\"" + family.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    s"${style.sizePx}px $rendered"

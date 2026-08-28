package storyatlas4s.layout

import java.awt.Font
import java.awt.font.FontRenderContext

/** Optional JVM measurer over `java.awt.Font` per-code-point advances, for proportional fonts.
  *
  * It measures each code point on its own (no kerning, no shaping), so the additive width model the
  * paginator assumes holds exactly; the resolved font name is part of `name`, because AWT silently
  * substitutes a logical font when the family is missing.
  */
final class AwtMeasurer private (family: String, unitsPerPixel: Int, lineHeightPerEm: Int)
    extends Measurer:

  private val context = new FontRenderContext(null, true, true)

  val name: String = s"awt/${family.replace(' ', '_')}/$unitsPerPixel"

  def measure(run: TextRun, style: TextStyle): Either[LayoutError, RunMetrics] =
    try
      val font = new Font(style.family, Font.PLAIN, style.sizePx)
      val text = run.text
      val advances = Vector.newBuilder[Int]
      var index = 0
      while index < text.length do
        val codePoint = text.codePointAt(index)
        val count = Character.charCount(codePoint)
        val advance =
          if codePoint == '\n'.toInt then 0
          else
            val width = font.getStringBounds(text, index, index + count, context).getWidth
            math.round(width * unitsPerPixel).toInt
        advances += advance
        if count == 2 then advances += 0
        index += count
      RunMetrics.of(advances.result(), lineHeightPerEm * style.sizePx, unitsPerPixel)
    catch
      case e: RuntimeException =>
        Left(LayoutError.Measurement(name, Option(e.getMessage).getOrElse(e.getClass.getName)))

object AwtMeasurer:
  /** `family` is the AWT family expected to resolve; `unitsPerPixel` the rounding grid. */
  def of(
      family: String,
      unitsPerPixel: Int = 1000,
      lineHeightPerEm: Int = 1200
  ): Either[LayoutError, AwtMeasurer] =
    if family.trim.isEmpty then Left(LayoutError.InvalidSpec("AwtMeasurer.family", "empty"))
    else if unitsPerPixel <= 0 then
      Left(LayoutError.InvalidSpec("AwtMeasurer.unitsPerPixel", s"$unitsPerPixel is not positive"))
    else if lineHeightPerEm <= 0 then
      Left(
        LayoutError.InvalidSpec("AwtMeasurer.lineHeightPerEm", s"$lineHeightPerEm is not positive")
      )
    else Right(new AwtMeasurer(family.trim, unitsPerPixel, lineHeightPerEm))

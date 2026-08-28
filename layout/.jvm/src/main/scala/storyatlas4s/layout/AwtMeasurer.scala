package storyatlas4s.layout

import java.awt.Font
import java.awt.font.FontRenderContext
import java.util.Locale

/** Optional JVM measurer over `java.awt.Font` per-code-point advances, for proportional fonts.
  *
  * It measures each code point on its own (no kerning, no shaping), so the additive width model the
  * paginator assumes holds exactly. AWT silently substitutes a logical font when a family is
  * missing, so a request whose resolved family is not the requested one fails with
  * [[LayoutError.Measurement]] instead of measuring the wrong font; the style in the receipt is
  * therefore the font that was measured.
  */
final class AwtMeasurer private (unitsPerPixel: Int, lineHeightPerEm: Int) extends Measurer:

  private val context = new FontRenderContext(null, true, true)

  val name: String = s"awt/$unitsPerPixel/$lineHeightPerEm"

  def measure(run: TextRun, style: TextStyle): Either[LayoutError, RunMetrics] =
    try
      val font = new Font(style.family, Font.PLAIN, style.sizePx)
      val resolved = font.getFamily(Locale.ROOT)
      if resolved != style.family then
        Left(LayoutError.Measurement(name, s"family '${style.family}' resolved to '$resolved'"))
      else
        val text = run.text
        val advances = Vector.newBuilder[Int]
        var index = 0
        var tooWide = false
        while index < text.length do
          val codePoint = text.codePointAt(index)
          val count = Character.charCount(codePoint)
          val advance =
            if codePoint == '\n'.toInt then 0L
            else
              val width = font.getStringBounds(text, index, index + count, context).getWidth
              math.round(width * unitsPerPixel)
          if advance > Int.MaxValue then tooWide = true
          advances += advance.toInt
          if count == 2 then advances += 0
          index += count
        val lineHeight = lineHeightPerEm.toLong * style.sizePx
        if tooWide || lineHeight > Int.MaxValue then
          Left(
            LayoutError.Measurement(name, s"advances at ${style.sizePx}px exceed ${Int.MaxValue}")
          )
        else RunMetrics.of(advances.result(), lineHeight.toInt, unitsPerPixel)
    catch
      case e: RuntimeException =>
        Left(LayoutError.Measurement(name, Option(e.getMessage).getOrElse(e.getClass.getName)))

object AwtMeasurer:
  /** `unitsPerPixel` is the rounding grid; the font family comes from each `TextStyle`. */
  def of(unitsPerPixel: Int = 1000, lineHeightPerEm: Int = 1200): Either[LayoutError, AwtMeasurer] =
    if unitsPerPixel <= 0 then
      Left(LayoutError.InvalidSpec("AwtMeasurer.unitsPerPixel", s"$unitsPerPixel is not positive"))
    else if lineHeightPerEm <= 0 then
      Left(
        LayoutError.InvalidSpec("AwtMeasurer.lineHeightPerEm", s"$lineHeightPerEm is not positive")
      )
    else Right(new AwtMeasurer(unitsPerPixel, lineHeightPerEm))

package storyatlas4s.layout

/** The page box in CSS pixels; the paginator scales it by the metrics' `unitsPerPixel`.
  *
  * Why pixels here and layout units inside: a caller thinks in page sizes, while every width the
  * paginator adds is an integer in the measurer's own subdivision of the pixel, so the sum is exact
  * and identical on JVM and JS.
  */
final case class PageSpec private (widthPx: Int, heightPx: Int)

object PageSpec:
  def of(widthPx: Int, heightPx: Int): Either[LayoutError, PageSpec] =
    if widthPx <= 0 then
      Left(LayoutError.InvalidSpec("PageSpec.widthPx", s"$widthPx is not positive"))
    else if heightPx <= 0 then
      Left(LayoutError.InvalidSpec("PageSpec.heightPx", s"$heightPx is not positive"))
    else Right(new PageSpec(widthPx, heightPx))

/** A font request in CSS pixels; the measurer decides what it resolves to and says so in its name.
  */
final case class TextStyle private (family: String, sizePx: Int)

object TextStyle:
  def of(family: String, sizePx: Int): Either[LayoutError, TextStyle] =
    if family.trim.isEmpty then Left(LayoutError.InvalidSpec("TextStyle.family", "empty"))
    else if family.exists(c => c.isControl) then
      Left(LayoutError.InvalidSpec("TextStyle.family", "contains control characters"))
    else if sizePx <= 0 then
      Left(LayoutError.InvalidSpec("TextStyle.sizePx", s"$sizePx is not positive"))
    else Right(new TextStyle(family.trim, sizePx))

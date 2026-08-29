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
  *
  * `family` is one family name (a generic keyword such as `monospace` or a single face), never a
  * comma-separated fallback list: the receipt names the metric a page was broken under, and a list
  * would leave that choice to the renderer. `<` and `>` are refused so a family can never close a
  * markup element it is written into.
  */
final case class TextStyle private (family: String, sizePx: Int):
  /** The family as one CSS family value: a generic keyword or identifier (`[A-Za-z][A-Za-z0-9-]*`)
    * bare, anything else as a quoted string with `\\` and `"` escaped — so it is always one family,
    * never a fallback list, wherever a font shorthand or `font-family` value is assembled.
    */
  def cssFamily: String =
    val identifier = family.headOption.exists(c => c.isLetter && c < 0x80) && family.forall(c =>
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'
    )
    if identifier then family
    else "\"" + family.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

object TextStyle:
  def of(family: String, sizePx: Int): Either[LayoutError, TextStyle] =
    if family.trim.isEmpty then Left(LayoutError.InvalidSpec("TextStyle.family", "empty"))
    else if family.exists(c => c.isControl) then
      Left(LayoutError.InvalidSpec("TextStyle.family", "contains control characters"))
    else if family.exists(c => c == '<' || c == '>') then
      Left(LayoutError.InvalidSpec("TextStyle.family", "contains markup delimiters"))
    else if sizePx <= 0 then
      Left(LayoutError.InvalidSpec("TextStyle.sizePx", s"$sizePx is not positive"))
    else Right(new TextStyle(family.trim, sizePx))

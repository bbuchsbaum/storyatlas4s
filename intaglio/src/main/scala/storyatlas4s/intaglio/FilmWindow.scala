package storyatlas4s.intaglio

/** A validated, half-open display window on the film (source) clock: the y camera of the Recall
  * Voyage. Like [[RecallWindow]] it is a viewport only; it never enters a projection, a selection,
  * or a receipt.
  */
final case class FilmWindow private (start: Double, end: Double):
  def span: Double = end - start
  def contains(t: Double): Boolean = t >= start && t <= end

object FilmWindow:

  def of(start: Double, end: Double): Either[String, FilmWindow] =
    if !start.isFinite || !end.isFinite then Left("film window bounds must be finite")
    else if start < 0.0 then Left("film window start must be nonnegative")
    else if start >= end then Left("film window start must be less than its end")
    else Right(new FilmWindow(start, end))

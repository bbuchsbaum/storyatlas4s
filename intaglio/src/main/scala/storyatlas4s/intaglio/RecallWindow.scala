package storyatlas4s.intaglio

/** A display-only interval on the recall clock.
  *
  * The interval is inclusive at both ends, so a mark at either supplied boundary remains visible.
  * It deliberately carries no source-time or scientific interpretation.
  */
final case class RecallWindow private (start: Double, end: Double):
  def span: Double = end - start
  def contains(t: Double): Boolean = t >= start && t <= end

object RecallWindow:

  def of(start: Double, end: Double): Either[String, RecallWindow] =
    if !start.isFinite || !end.isFinite then Left("recall window bounds must be finite")
    else if start < 0.0 then Left("recall window start must be nonnegative")
    else if start >= end then Left("recall window start must be less than its end")
    else Right(new RecallWindow(start, end))

  /** Fits a requested span into a finite positive recall extent, preserving its span and clamping
    * only the display origin. A span wider than the supplied extent has no bounded representation.
    */
  def bounded(start: Double, span: Double, total: Double): Option[RecallWindow] =
    if !start.isFinite || !span.isFinite || !total.isFinite || span <= 0.0 || total <= 0.0 ||
      span > total
    then None
    else
      val boundedStart = math.max(0.0, math.min(start, total - span))
      of(boundedStart, boundedStart + span).toOption

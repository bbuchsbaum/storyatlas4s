package storyatlas4s.app

import storyatlas4s.edition.EditionSpec
import scala.annotation.tailrec
import storymodel4s.view.{NarrativeLevel, SurfaceDetail, ZoomLevel}

/** Configuration failures that prevent a gesture from naming an exact supported zoom state. */
enum SemanticZoomError:
  case EmptyNarrativeAxis
  case EmptySurfaceAxis
  case UnsupportedNarrative(level: NarrativeLevel)
  case UnsupportedSurface(detail: SurfaceDetail)

  def message: String = this match
    case EmptyNarrativeAxis          => "the narrative zoom axis has no configured levels"
    case EmptySurfaceAxis            => "the surface zoom axis has no configured details"
    case UnsupportedNarrative(level) => s"narrative level $level is not configured for this edition"
    case UnsupportedSurface(detail)  => s"surface detail $detail is not configured for this edition"

/** A continuous gesture committed to one exact typed semantic zoom state.
  *
  * Why: wheel, pinch, and range input are noisy real values, while a scientific view and its
  * receipt must always name one finite [[ZoomLevel]]. The two positions remain independent; they
  * never imply that narrative and surface refinement are one ordinal hierarchy.
  */
final class SemanticZoom private (
    private val narrative: SemanticZoom.Cursor[NarrativeLevel],
    private val surface: SemanticZoom.Cursor[SurfaceDetail],
    val narrativePosition: Double,
    val surfacePosition: Double
):
  /** The only scientific zoom state represented by the current continuous coordinates. */
  val committed: ZoomLevel = ZoomLevel(narrative.current, surface.current)

  /** Move only the narrative gesture coordinate, committing across deterministic hysteresis. */
  def moveNarrative(position: Double): SemanticZoom =
    val (next, clamped) = narrative.move(position)
    new SemanticZoom(next, surface, clamped, surfacePosition)

  /** Move only the surface gesture coordinate, committing across deterministic hysteresis. */
  def moveSurface(position: Double): SemanticZoom =
    val (next, clamped) = surface.move(position)
    new SemanticZoom(narrative, next, narrativePosition, clamped)

object SemanticZoom:
  /** Dead band on either side of a midpoint after a semantic state has been committed. */
  val Hysteresis: Double = 0.15

  /** Start both continuous coordinates at the centres of an exact zoom state's bands. */
  def at(zoom: ZoomLevel): Either[SemanticZoomError, SemanticZoom] =
    for
      narrative <- cursor(
        EditionSpec.levels,
        zoom.narrative,
        SemanticZoomError.EmptyNarrativeAxis,
        SemanticZoomError.UnsupportedNarrative(zoom.narrative)
      )
      surface <- cursor(
        EditionSpec.surfaceDetails,
        zoom.surface,
        SemanticZoomError.EmptySurfaceAxis,
        SemanticZoomError.UnsupportedSurface(zoom.surface)
      )
    yield new SemanticZoom(narrative, surface, narrative.position, surface.position)

  /** A non-empty axis focused on one configured value, with no forgeable numeric index. */
  private final case class Cursor[A](left: Vector[A], current: A, right: Vector[A]):
    def position: Double = left.length.toDouble

    def move(raw: Double): (Cursor[A], Double) =
      val maximum = (left.length + right.length).toDouble
      val finite = if raw.isNaN then position else raw
      val clamped = math.max(0.0, math.min(finite, maximum))

      @tailrec
      def advance(cursor: Cursor[A]): Cursor[A] = cursor.right match
        case next +: remaining if clamped >= cursor.position + 0.5 + Hysteresis =>
          advance(Cursor(cursor.left :+ cursor.current, next, remaining))
        case _ => cursor

      @tailrec
      def retreat(cursor: Cursor[A]): Cursor[A] = cursor.left match
        case remaining :+ previous if clamped <= cursor.position - 0.5 - Hysteresis =>
          retreat(Cursor(remaining, previous, cursor.current +: cursor.right))
        case _ => cursor

      retreat(advance(this)) -> clamped

  private def cursor[A](
      values: Vector[A],
      selected: A,
      empty: => SemanticZoomError,
      unsupported: => SemanticZoomError
  ): Either[SemanticZoomError, Cursor[A]] =
    @tailrec
    def find(left: Vector[A], remaining: Vector[A]): Option[Cursor[A]] = remaining match
      case current +: right if current == selected => Some(Cursor(left, current, right))
      case current +: right                        => find(left :+ current, right)
      case _                                       => None

    if values.isEmpty then Left(empty)
    else find(Vector.empty, values).toRight(unsupported)

/** Monotone local identity of one requested compilation. */
opaque type IntentRevision = Long

object IntentRevision:
  private[app] def unsafe(value: Long): IntentRevision = value
  extension (revision: IntentRevision) def value: Long = revision

/** One immutable request binding a revision to the complete choice it must compile. */
final case class CompileIntent[+Request](revision: IntentRevision, request: Request)

/** A completion accepted only because its revision still names the latest requested intent. */
final case class AcceptedCompilation[+Request, +Result](
    intent: CompileIntent[Request],
    result: Result
)

/** Pure last-intent-wins gate for asynchronous view compilation.
  *
  * Why: a slow result for request `n` must never replace the visible result for a newer request
  * `n + 1`, even when the older computation completes last.
  */
final class LatestIntent[Request, Result] private (
    private val nextRevision: Long,
    private val latest: Option[CompileIntent[Request]]
):
  /** Register a complete request and invalidate every prior visible result. */
  def request(value: Request): (LatestIntent[Request, Result], CompileIntent[Request]) =
    val intent = CompileIntent(IntentRevision.unsafe(nextRevision), value)
    new LatestIntent(nextRevision + 1L, Some(intent)) -> intent

  /** Accept a result only when it belongs to the latest requested revision. */
  def complete(
      revision: IntentRevision,
      result: Result
  ): (LatestIntent[Request, Result], Option[AcceptedCompilation[Request, Result]]) =
    val accepted = latest
      .filter(_.revision == revision)
      .map(intent => AcceptedCompilation(intent, result))
    val next = if accepted.nonEmpty then new LatestIntent(nextRevision, None) else this
    next -> accepted

object LatestIntent:
  def empty[Request, Result]: LatestIntent[Request, Result] =
    new LatestIntent(0L, None)

/** What the shell may publish while a compilation request is in flight. */
enum CompilationDisplay[+Request, +Result]:
  case Idle
  case Pending(intent: CompileIntent[Request])
  case Ready(value: AcceptedCompilation[Request, Result])

/** Small injected runtime around [[LatestIntent]], usable with any asynchronous scheduler.
  *
  * The scheduler owns execution; this runtime alone owns publication. Tests can retain completion
  * callbacks and resolve them in adversarial order without a clock or browser.
  */
final class CompilationRuntime[Request, Result](
    submit: (CompileIntent[Request], Result => Unit) => Unit,
    publish: CompilationDisplay[Request, Result] => Unit
):
  private var gate = LatestIntent.empty[Request, Result]

  /** Submit one complete intent and publish no result until that exact revision completes. */
  def request(value: Request): Unit =
    val (next, intent) = gate.request(value)
    gate = next
    publish(CompilationDisplay.Pending(intent))
    submit(
      intent,
      result =>
        val (next, accepted) = gate.complete(intent.revision, result)
        gate = next
        accepted.foreach(value => publish(CompilationDisplay.Ready(value)))
    )

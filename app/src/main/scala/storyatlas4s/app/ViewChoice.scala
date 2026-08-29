package storyatlas4s.app

import storymodel4s.core.Address
import storymodel4s.view.{CodexLens, EpistemicHorizon, NarrativeLevel, SurfaceDetail, ZoomLevel}

/** Which measurer paginates the Codex: the fixed publication table, or the live DOM canvas. */
enum MeasurerChoice:
  case Monospace, Dom

  def label: String = this match
    case Monospace => "monospace table (publication)"
    case Dom       => "DOM canvas (live)"

/** Everything the shell lets a reader choose. `selection`, `focus`, and `horizon` become the
  * `CommonViewState` both compilers share; the rest picks a Codex lens, an exact two-axis semantic
  * zoom state, and a measurer.
  */
final case class ViewChoice(
    lens: CodexLens,
    zoom: ZoomLevel,
    horizon: EpistemicHorizon,
    selection: Set[Address],
    focus: Option[Address],
    measurer: MeasurerChoice
)

object ViewChoice:
  val initial: ViewChoice =
    ViewChoice(
      CodexLens.Overview,
      ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden),
      EpistemicHorizon.Omniscient,
      Set.empty,
      None,
      MeasurerChoice.Dom
    )

/** The epistemic playhead: a reader offset into the canonical text, always on a code point boundary
  * (`EvidenceVisibility.validateHorizon` refuses a split surrogate pair).
  */
object Playhead:
  /** Clamp `offset` to `[0, text.length]` and move it past a low surrogate it would split. */
  def snap(text: String, offset: Int): Int =
    val clamped = math.max(0, math.min(offset, text.length))
    if clamped > 0 && clamped < text.length &&
      Character.isHighSurrogate(text.charAt(clamped - 1)) &&
      Character.isLowSurrogate(text.charAt(clamped))
    then clamped + 1
    else clamped

  /** The slider position for a horizon: the reader offset, or the end of the text when omniscient
    * (the omniscient view is not "reader at the end" — see `EvidenceVisibility` — so the shell also
    * shows an explicit omniscient control).
    */
  def position(text: String, horizon: EpistemicHorizon): Int = horizon match
    case EpistemicHorizon.Omniscient       => text.length
    case EpistemicHorizon.ReaderAt(offset) => offset

  def describe(text: String, horizon: EpistemicHorizon): String = horizon match
    case EpistemicHorizon.Omniscient       => s"omniscient (no horizon; ${text.length} code units)"
    case EpistemicHorizon.ReaderAt(offset) =>
      s"reader at $offset of ${text.length} code units"

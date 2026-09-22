package storyatlas4s.shell

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
):

  /** Activate one address, the same way for every host and input device: a plain activation
    * replaces the selection, an extending one toggles the address in it, and either makes it the
    * semantic focus.
    */
  def activate(address: Address, extend: Boolean): ViewChoice =
    val next =
      if !extend then Set(address)
      else if selection.contains(address) then selection - address
      else selection + address
    copy(selection = next, focus = Some(address))

  /** No focus and an empty selection; every other choice is kept. */
  def cleared: ViewChoice = copy(selection = Set.empty, focus = None)

/** The one route from a host's hit to a semantic change.
  *
  * A host hit-tests in its own way (the DOM's nearest named ancestor, Intaglio picking on a canvas)
  * and reports only the rendered name it found. The name resolves through the checked index of the
  * plate it was drawn on; a name the index does not know changes nothing, so a host can never
  * select an address by parsing or guessing a renderer identity.
  */
object Activation:
  def apply[Name](
      choice: ViewChoice,
      targets: RenderedTargetIndex[Name],
      renderedName: String,
      extend: Boolean
  ): Option[ViewChoice] =
    targets.resolve(renderedName).map((_, address) => choice.activate(address, extend))

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

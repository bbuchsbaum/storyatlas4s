package storyatlas4s.shell

import storymodel4s.align.*
import storymodel4s.codec.SourceRecallWorkspace
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.story.StoryRef
import storymodel4s.view.*

enum WorkspaceMode:
  case Recall, Source, Voyage

/** Source and recall display coordinates are independent. None is not a zero-second cursor. */
final case class WorkspaceViewport(
    sourceOffset: Int = 0,
    recallOrdinal: Int = 0,
    sourceCursor: Option[Seconds] = None,
    recallCursor: Option[Seconds] = None,
    recallWindow: Option[ClockSpan] = None
)

/** A correspondence keeps its row when the source becomes the subject of inspection. */
final case class Correspondence(unit: RecallUnitId, target: SourceNodeRef)

/** Presentation and interaction state only; scientific records remain immutable in the packet. */
final case class WorkspaceState(
    policy: ArtifactId,
    selection: Set[Address],
    focus: Option[Address],
    activeRecall: Option[RecallUnitId],
    correspondence: Option[Correspondence],
    mode: WorkspaceMode,
    sourceLens: CodexLens,
    sourceZoom: ZoomLevel,
    sourceHorizon: EpistemicHorizon,
    recallHorizon: EpistemicHorizon,
    measurer: MeasurerChoice,
    viewport: WorkspaceViewport
)

enum WorkspaceAction:
  case Select(address: Address, extend: Boolean = false)
  case Inspect(unit: RecallUnitId, target: SourceNodeRef)
  case Walk(delta: Int)
  case Jump(unit: RecallUnitId)
  case Policy(id: ArtifactId)
  case Mode(value: WorkspaceMode)
  case SourcePresentation(choice: ViewChoice)
  case RecallHorizon(value: EpistemicHorizon)
  case Viewport(value: WorkspaceViewport)
  case Clear

/** One host-neutral transition owner. Hosts render this state and send actions, never synchronize
  * multiple writable selection stores. Every restored or externally requested state is rechecked.
  */
final class WorkspaceController private (
    val workspace: SourceRecallWorkspace,
    val state: WorkspaceState
):
  def policy: storymodel4s.codec.WorkspaceCodecs.Policy = workspace.policy(state.policy).get

  def dispatch(action: WorkspaceAction): Either[WorkspaceRefusal, WorkspaceController] =
    def selected(address: Address, extend: Boolean): WorkspaceState =
      val set = if !extend then Set(address)
      else if state.selection.contains(address) then state.selection - address
      else state.selection + address
      state.copy(selection = set, focus = Some(address),
        activeRecall = workspace.recallAddresses.get(address).orElse(state.activeRecall),
        correspondence = None)
    val next = action match
      case WorkspaceAction.Select(address, extend) => Right(selected(address, extend))
      case WorkspaceAction.Inspect(unit, target) =>
        for
          recall <- workspace.recallAddress(unit).toRight(WorkspaceRefusal.InvalidSelection)
          source <- workspace.sourceAddress(target).toRight(WorkspaceRefusal.InvalidSelection)
          _ <- Either.cond(policy.matrix.row(unit).exists(_.cell(Destination.Target(target)).isDefined),
            (), WorkspaceRefusal.InvalidSelection)
        yield state.copy(selection = Set(recall, source), focus = Some(source),
          activeRecall = Some(unit), correspondence = Some(Correspondence(unit, target)))
      case WorkspaceAction.Walk(delta) =>
        val units = workspace.inventory.units
        val current = state.activeRecall.map(id => units.indexWhere(_.id == id)).getOrElse(-1)
        val index = math.max(0L, math.min(units.size.toLong - 1L, current.toLong + delta.toLong)).toInt
        units.lift(index).flatMap(u => workspace.recallAddress(u.id))
          .toRight(WorkspaceRefusal.InvalidSelection).map(selected(_, false))
      case WorkspaceAction.Jump(unit) => workspace.recallAddress(unit)
        .toRight(WorkspaceRefusal.InvalidSelection).map(selected(_, false))
      case WorkspaceAction.Policy(id) =>
        workspace.policy(id).filter(_.record.policies.universe.id == policy.record.policies.universe.id)
          .toRight(WorkspaceRefusal.IncompatiblePolicy).map(_ => state.copy(policy = id))
      case WorkspaceAction.Mode(value) => Right(state.copy(mode = value))
      case WorkspaceAction.SourcePresentation(choice) => Right(state.copy(
        sourceLens = choice.lens, sourceZoom = choice.zoom, sourceHorizon = choice.horizon,
        measurer = choice.measurer))
      case WorkspaceAction.RecallHorizon(value) => Right(state.copy(recallHorizon = value))
      case WorkspaceAction.Viewport(value) => Right(state.copy(viewport = value))
      case WorkspaceAction.Clear => Right(state.copy(selection = Set.empty, focus = None,
        activeRecall = None, correspondence = None))
    next.flatMap(WorkspaceController.restore(workspace, _))

  /** Bridge semantic source targets through the existing source compiler's navigation vocabulary. */
  def sourceLocal(address: Address): Option[Address] = workspace.sourceAddresses.get(address).map {
    case SourceNodeRef.Situation(id) => Addressable[StoryRef].address(StoryRef.Situation(id))
    case SourceNodeRef.Segment(id) => Addressable[StoryRef].address(StoryRef.Segment(id))
  }
  def sourceQualified(address: Address): Option[Address] =
    Addressable[StoryRef].parse(address).flatMap {
      case StoryRef.Situation(id) => workspace.sourceAddress(SourceNodeRef.Situation(id))
      case StoryRef.Segment(id) => workspace.sourceAddress(SourceNodeRef.Segment(id))
      case _ => None
    }
  def sourceChoice: ViewChoice = ViewChoice(state.sourceLens, state.sourceZoom,
    state.sourceHorizon, state.selection.flatMap(sourceLocal), state.focus.flatMap(sourceLocal),
    state.measurer)

  /** Exact fragments are clipped through the producer's existing support rule, never hulled. */
  def recallEvidence(unit: RecallUnitId): Either[WorkspaceRefusal, Vector[(SpanRef, String)]] =
    workspace.recallEvidence(unit).map(visible(_, state.recallHorizon))
  def sourceEvidence(target: SourceNodeRef): Either[WorkspaceRefusal, Option[Vector[(SpanRef, String)]]] =
    workspace.sourceEvidence(target).map(_.map(visible(_, state.sourceHorizon)))
  private def visible(pieces: Vector[(SpanRef, String)], horizon: EpistemicHorizon): Vector[(SpanRef, String)] =
    SpanSet.of(pieces.map(_._1)).flatMap(EvidenceVisibility.clipSupport(_, horizon)) match
      case None => Vector.empty
      case Some(support) => pieces.filter(p => support.refs.toVector.contains(p._1))

object WorkspaceController:
  def open(workspace: SourceRecallWorkspace): Either[WorkspaceRefusal, WorkspaceController] =
    workspace.policies.headOption.toRight(WorkspaceRefusal.IncompatiblePolicy).flatMap { policy =>
      restore(workspace, WorkspaceState(policy.id, Set.empty, None, None, None,
        WorkspaceMode.Recall, ViewChoice.initial.lens, ViewChoice.initial.zoom,
        EpistemicHorizon.Omniscient, EpistemicHorizon.Omniscient,
        MeasurerChoice.Monospace, WorkspaceViewport()))
    }

  def restore(workspace: SourceRecallWorkspace, state: WorkspaceState): Either[WorkspaceRefusal, WorkspaceController] =
    val v = state.viewport
    val sourceText = workspace.model.source.canonicalText
    val recallText = workspace.recall.transcript.canonicalText
    val correspondenceValid = state.correspondence.forall(c =>
      state.activeRecall.contains(c.unit) && workspace.recallAddress(c.unit).isDefined &&
        workspace.policy(state.policy).exists(_.matrix.row(c.unit)
          .exists(_.cell(Destination.Target(c.target)).isDefined)))
    val sourceExtent = workspace.clocks.flatMap(_.sourceTimeline.nodes.map(_.span.end.value).maxOption)
    val recallExtent = workspace.clocks.map(_.recallExtent.value)
    val cursorsValid = v.sourceCursor.forall(t => sourceExtent.exists(t.value <= _)) &&
      v.recallCursor.forall(t => recallExtent.exists(t.value <= _)) &&
      v.recallWindow.forall(s => s.start.value < s.end.value && recallExtent.exists(s.end.value <= _))
    if workspace.policy(state.policy).isEmpty then Left(WorkspaceRefusal.IncompatiblePolicy)
    else if !(state.selection ++ state.focus).forall(workspace.contains) ||
      !state.activeRecall.forall(workspace.recallAddress(_).isDefined) || !correspondenceValid
    then Left(WorkspaceRefusal.InvalidSelection)
    else if EvidenceVisibility.validateHorizon(sourceText, state.sourceHorizon).isLeft ||
      EvidenceVisibility.validateHorizon(recallText, state.recallHorizon).isLeft ||
      v.sourceOffset != Playhead.snap(sourceText, v.sourceOffset) ||
      v.recallOrdinal < 0 || v.recallOrdinal >= math.max(1, workspace.inventory.units.size) || !cursorsValid
    then Left(WorkspaceRefusal.UnsupportedContent)
    else Right(new WorkspaceController(workspace, state))

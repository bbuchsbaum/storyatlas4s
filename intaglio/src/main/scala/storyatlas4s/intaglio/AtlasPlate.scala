package storyatlas4s.intaglio

import storymodel4s.acquire.ClaimFamily
import storymodel4s.story.{ContextHolder, ContextKind, HolderGap}
import storymodel4s.view.*

/** The composition of one Discourse Atlas plate, decided before anything is drawn.
  *
  * Every question a beautiful plate has to answer — how tall is a lane, which labels are drawn and
  * where, how 65 marks of one unsatisfied law become readable rather than a smear — is a decision
  * over measured extents, so it is taken here, once, as data. [[AtlasLowering]] then only draws.
  *
  * Two rules govern the whole plan and are why it is a plan at all:
  *
  *   - **No mark is dropped.** A label may be elided or withheld; the mark it belongs to is always
  *     drawn, always named, and always in the textual twin. What the plate withholds it says it is
  *     withholding, with a count.
  *   - **Nothing here is a claim.** A packing row, a label row, a margin column position: all are
  *     device layout over a coordinate the projection contract declares meaningless in y
  *     (`VisualInvariant.LaneHasNoMetric`). The plate says so on its face.
  */
private[intaglio] object AtlasPlate:

  /** Who holds a context frame, in words.
    *
    * A frame held by an entity is named by that entity's own label, which reaches a compiled scene
    * only through a `Thread` mark. When no thread carries it, or the model resolved no holder at
    * all, the lane says so in words: printing the content hash instead is what made the plate
    * unreadable, and a hash is an address, which belongs in the inspector.
    */
  enum LaneHolder:
    case Named(label: String)
    case Unnamed(reason: String)
    case Unheld

  /** One context lane of the plot. `kind` is absent when no visible band names the lane, which
    * happens when a frame's clipped support is empty; the lane is then numbered and not named,
    * because guessing its kind from a neighbouring landmark would invent a context.
    */
  final case class Lane(index: Int, kind: Option[ContextKind], holder: LaneHolder)

  /** A label the budget accepted: `dxPx` is the offset of its left edge from the mark's own x, in
    * device pixels, so the label never scales with the discourse axis.
    */
  final case class PlacedLabel(text: String, markX: Int, lane: Int, row: Int, dxPx: Double)

  /** What one lane's label budget spent, so the plate can print it. */
  final case class LaneBudget(lane: Int, labelled: Int, total: Int)

  /** One reason a model does not promote, and every mark that records it.
    *
    * Grouping is the whole treatment: 65 marks of `hierarchy.situation-root-reachable` are not 65
    * facts, they are one fact about 65 situations. The reason is written once with its count; the
    * marks keep their own identities and their own exact spans, so the reader sees precisely which
    * material each concerns.
    */
  final case class AbsenceGroup(
      headline: String,
      detail: String,
      count: Int,
      /** Packing row of each spanned mark, by `MarkId`. Layout only; it means nothing. */
      rowOf: Map[String, Int],
      /** Marks with no honest discourse position, in mark order, for the margin column. */
      unplaced: Vector[String],
      rowCount: Int,
      /** Top of this group within the absence band, in pixels. */
      topPx: Double,
      heightPx: Double
  )

  /** The plate plan. All pixel coordinates are measured from the top-left of the box. */
  final case class Plan(
      box: PlateBox,
      discourseLength: Int,
      lanes: Vector[Lane],
      contentLeftPx: Double,
      plotLeftPx: Double,
      rightPx: Double,
      headerTopPx: Double,
      contractTopPx: Double,
      laneTopPx: Double,
      laneHeightPx: Double,
      labelRows: Int,
      axisTopPx: Double,
      surfaceTopPx: Double,
      surfaceHeightPx: Double,
      featureRows: Map[String, Int],
      featureHeightPx: Double,
      absenceTopPx: Double,
      absenceHeightPx: Double,
      absence: Vector[AbsenceGroup],
      legendTopPx: Double,
      labels: Map[String, PlacedLabel],
      laneBudget: Vector[LaneBudget],
      /** Sub-row of a mark within its lane's situation band. Layout only; it means nothing.
        *
        * Fifty-three situations over nine hundred pixels put a median gap of zero between
        * consecutive marks, which is not a picture of anything. Packing them into sub-rows by first
        * fit, exactly as the absence rail packs its footprints, separates them without moving a
        * single x.
        */
      situationRow: Map[String, Int],
      situationRows: Int,
      /** `MarkId`s the shared selection resolves to directly, by `SelectionPlacement.OnMark`. */
      selected: Set[String],
      /** What the projection did with each selected address, in the contract's own words. */
      focusNote: String,
      /** Ticks of the discourse axis: value and label, from Intaglio's own break generator. */
      ticks: Vector[(Double, String)],
      /** What this narrative level draws, and how much of it the model actually has. */
      levelNote: String,
      /** The epistemic channels the contract declares, and which of them occur here. */
      channelNote: String
  ):
    def featureTopPx: Double = surfaceTopPx + surfaceHeightPx
    def laneCount: Int = lanes.length

    /** Centre of a lane's context-band ribbon, in pixels below the lane's own top edge. */
    def bandOffsetPx: Double = laneHeightPx - Metric.bandFromLaneBottomPx

    /** How much of a lane the situation band may take.
      *
      * It adapts, because a plate with many lanes in a short box would otherwise ask for more
      * height than the box has and run off the bottom. A squeezed lane loses sub-rows and then
      * label rows; it never overflows, and the budget prints what it withheld.
      */
    def situationBandPx: Double =
      math.min(Metric.situationFromLaneBottomPx, laneHeightPx * 0.55)

    /** The lane's situation row: landmark glyphs, thread rings, relation endpoints. */
    def situationOffsetPx: Double = laneHeightPx - situationBandPx

    /** Sub-rows this lane can hold without reaching the context-band ribbon below it. */
    def situationRowsAvailable: Int =
      math.max(
        1,
        math.min(
          Metric.situationRowsMax,
          ((situationBandPx - Metric.bandFromLaneBottomPx - 4.0) /
            Metric.situationRowStepPx).toInt
        )
      )

    /** Baseline of label row `row`, counting up from the situation row. */
    def labelOffsetPx(row: Int): Double =
      situationOffsetPx - Metric.labelRowLiftPx - row * Metric.labelRowStepPx

    /** Where a mark sits within its lane, once its sub-row is taken into account. */
    def markOffsetPx(mark: String): Double =
      situationOffsetPx + situationRow.getOrElse(mark, 0) * Metric.situationRowStepPx
    def plotWidthPx: Double = rightPx - plotLeftPx
    def laneBottomPx: Double = laneTopPx + laneHeightPx * laneCount
    def marginLeftPx: Double = contentLeftPx + Metric.laneNameWidthPx
    def marginWidthPx: Double = plotLeftPx - marginLeftPx

    /** npc y of a pixel measured down from the top of the box; the root frame is y-up. */
    def y(px: Double): Double = 1.0 - px / box.heightPx
    def x(px: Double): Double = px / box.widthPx

    /** Device x of an exact discourse offset. */
    def xOf(offset: Double): Double =
      plotLeftPx + offset / math.max(discourseLength.toDouble, 1.0) * plotWidthPx

    def labelsDrawn: Int = labels.size
    def labelsWithheld: Int = laneBudget.map(b => b.total - b.labelled).sum
    def labellable: Int = laneBudget.map(_.total).sum

  /** Compose a plate for `scene` in `box`. */
  def plan(scene: NarrativeScene, discourseLength: Int, box: PlateBox): Plan =
    val marks = scene.marks
    val laneCount = marks.flatMap(maxContextLane).maxOption.fold(1)(_ + 1)
    val bandKinds = marks
      .collect { case b: VisualPrimitive.ContextBand => b.lane -> b.kind }
      .sortBy(_._1)
      .toMap
    // An entity's label reaches the scene only through its thread; join on the entity address so
    // a frame held by "he" is a lane called "he" rather than a lane called c-entity:b679d…
    val entityLabels: Map[String, String] = marks.collect { case t: VisualPrimitive.Thread =>
      t.identity.address.key.render -> t.label
    }.toMap
    val lanes = (0 until laneCount).toVector.map { i =>
      val kind = bandKinds.get(i)
      Lane(i, kind, kind.fold(LaneHolder.Unheld)(holderOf(_, entityLabels)))
    }
    val selected = selectedMarks(scene)

    val contentLeftPx = Metric.gutterPx
    val plotLeftPx = contentLeftPx + Metric.laneNameWidthPx + Metric.marginColumnPx
    val rightPx = box.widthPx - Metric.rightPadPx
    val plotWidthPx = rightPx - plotLeftPx

    val absenceMarks = marks.filter(_.epistemicPlacement.isDefined)
    val groups = groupAbsences(absenceMarks, discourseLength, plotWidthPx)
    val absenceHeightPx =
      groups.lastOption.fold(Metric.absenceCaptionPx)(g => g.topPx + g.heightPx)

    val features = marks.collect { case f: VisualPrimitive.Feature => f }
    val ends = scala.collection.mutable.ArrayBuffer.empty[Int]
    val featureRows = features
      .sortBy(f => (f.value.support.spans.head.start, f.identity.mark.value))
      .map { f =>
        val start = f.value.support.spans.head.start
        val end = f.value.support.spans.toVector.last.endExclusive
        val available = ends.indexWhere(_ <= start)
        val row = if available < 0 then { ends += end; ends.size - 1 }
        else { ends(available) = end; available }
        f.identity.mark.value -> row
      }
      .toMap
    val featureHeightPx = if features.isEmpty then 0.0 else 62.0 + ends.size * 18.0
    val headerTopPx = Metric.topPadPx
    val contractTopPx = headerTopPx + Metric.headerPx
    val laneTopPx = contractTopPx + Metric.contractPx
    val fixedBelow =
      Metric.axisPx + Metric.surfacePx + featureHeightPx + absenceHeightPx + Metric.legendPx + Metric.bottomPadPx
    val lanesHeightPx = math.max(
      Metric.laneMinPx * laneCount,
      box.heightPx - laneTopPx - fixedBelow
    )
    val laneHeightPx = lanesHeightPx / laneCount
    val situationOffsetPx = laneHeightPx - Metric.situationFromLaneBottomPx
    val labelRows = math.max(
      1,
      math.min(
        Metric.maxLabelRows,
        ((situationOffsetPx - Metric.labelRowLiftPx + Metric.labelRowStepPx - 6.0) /
          Metric.labelRowStepPx).toInt
      )
    )
    val axisTopPx = laneTopPx + lanesHeightPx
    val surfaceTopPx = axisTopPx + Metric.axisPx
    val absenceTopPx = surfaceTopPx + Metric.surfacePx + featureHeightPx
    val legendTopPx = absenceTopPx + absenceHeightPx

    val partial = Plan(
      box = box,
      discourseLength = discourseLength,
      lanes = lanes,
      contentLeftPx = contentLeftPx,
      plotLeftPx = plotLeftPx,
      rightPx = rightPx,
      headerTopPx = headerTopPx,
      contractTopPx = contractTopPx,
      laneTopPx = laneTopPx,
      laneHeightPx = laneHeightPx,
      labelRows = labelRows,
      axisTopPx = axisTopPx,
      surfaceTopPx = surfaceTopPx,
      surfaceHeightPx = Metric.surfacePx,
      featureRows = featureRows,
      featureHeightPx = featureHeightPx,
      absenceTopPx = absenceTopPx,
      absenceHeightPx = absenceHeightPx,
      absence = groups,
      legendTopPx = legendTopPx,
      labels = Map.empty,
      laneBudget = Vector.empty,
      situationRow = Map.empty,
      situationRows = 1,
      selected = selected,
      focusNote = focusNote(scene),
      ticks = ticksFor(discourseLength),
      levelNote = levelNote(scene),
      channelNote = channelNote(scene)
    )
    val (rows, rowCount) = packSituations(marks, partial)
    val packed = partial.copy(situationRow = rows, situationRows = rowCount)
    val (labels, budget) = budgetLabels(marks, packed)
    packed.copy(labels = labels, laneBudget = budget)

  /** First-fit packing of a lane's situation marks into sub-rows.
    *
    * Two marks share a sub-row exactly when their glyphs, at the width this plate is composed for,
    * do not come within a glyph's diameter of each other. The row is device layout over an axis the
    * contract already declares metric-free; x is untouched.
    */
  private def packSituations(
      marks: Vector[VisualPrimitive],
      plan: Plan
  ): (Map[String, Int], Int) =
    val clear = Metric.situationClearPx
    val out = Map.newBuilder[String, Int]
    var deepest = 1
    marks
      .collect { case l: VisualPrimitive.Landmark => l }
      .groupBy(_.at.lane)
      .toVector
      .sortBy(_._1)
      .foreach { (_, lane) =>
        val available = plan.situationRowsAvailable
        val rowEnd = Array.fill(available)(Double.NegativeInfinity)
        lane
          .sortBy(l => (l.at.x, l.identity.mark.value))
          .zipWithIndex
          .foreach { (landmark, index) =>
            val x = plan.xOf(landmark.at.x.toDouble)
            val free = (0 until available).find(r => x >= rowEnd(r) + clear)
            val row = free.getOrElse(index % available)
            rowEnd(row) = x
            deepest = math.max(deepest, row + 1)
            out += landmark.identity.mark.value -> row
          }
      }
    (out.result(), deepest)

  /** Marks the shared selection resolves to directly. An address that resolves through an ancestor
    * or off the projection is preserved and reported, never redrawn as though it were on a mark.
    */
  private def selectedMarks(scene: NarrativeScene): Set[String] =
    scene.state.selection.toVector
      .flatMap(address =>
        scene.selectionPlacements.get(address) match
          case Some(SelectionPlacement.OnMark(marks)) => marks.toVector.map(_.value)
          case _                                      => Vector.empty
      )
      .toSet

  /** One line naming every selected address and what this projection could do with it. */
  private def focusNote(scene: NarrativeScene): String =
    // The selected object in the story's own word, not its address. An address is an identifier and
    // belongs in the inspector, which prints it in full beside the words it supports.
    val labels: Map[String, String] = scene.marks.collect {
      case l: VisualPrimitive.Landmark => l.identity.address.render -> l.label
      case t: VisualPrimitive.Thread   => t.identity.address.render -> t.label
      // An unsummarized region has no word of its own; its typed absence is the honest note.
      case r: VisualPrimitive.Region => r.identity.address.render -> r.label.render
    }.toMap
    val addresses = scene.state.selection.toVector.map(_.render).sorted
    if addresses.isEmpty then "no shared selection"
    else
      addresses
        .map { rendered =>
          val state = scene.state.selection
            .find(_.render == rendered)
            .flatMap(scene.selectionPlacements.get)
            .fold("off projection") {
              case SelectionPlacement.OnMark(marks) =>
                s"on ${marks.length} ${if marks.length == 1 then "mark" else "marks"}"
              case SelectionPlacement.ViaAncestor(a) =>
                s"via its containing ${labels.getOrElse(a.render, "segment")}"
              case SelectionPlacement.OffProjection => "off projection"
            }
          val name =
            labels.get(rendered).fold("an object this projection does not name")(l => s"“$l”")
          s"$name — $state"
        }
        .mkString("; ")

  /** What the level draws, and how much of it this model has.
    *
    * Story and Episode drew the same empty picture here, and the plate said nothing about why: a
    * level shows segments, and this model has none. The widest view of a story should not be the
    * one that tells a reader least without admitting it, so the level states its own grain and its
    * own count.
    */
  private def levelNote(scene: NarrativeScene): String =
    val level = scene.zoom.narrative
    val wanted = level.visibleSegments.toVector.map(_.toString).sorted
    val regions = scene.marks.count(_.isInstanceOf[VisualPrimitive.Region])
    val situations = scene.marks.count(_.isInstanceOf[VisualPrimitive.Landmark])
    val segments =
      s"$level draws ${wanted.mkString(" and ")} segments as regions: $regions in this model"
    val shown =
      if level.showsSituations then s"situations are drawn at this level: $situations"
      else "situations are not drawn above Scene, by the level's own definition"
    s"$segments · $shown"

  /** Which epistemic channels the contract declares, and which of them this model reaches.
    *
    * The contract advertises four non-colour channels. A model that mints one of them is not a
    * plate with three empty legend rows: it is a model that recorded one kind of failure, and the
    * plate says which and why the others did not arise.
    */
  private def channelNote(scene: NarrativeScene): String =
    val present = scene.marks.flatMap(_.epistemicChannel).distinct.sortBy(_.toString)
    val absent = EpistemicChannel.values.toVector.filterNot(present.contains).sortBy(_.toString)
    val counts = present
      .map(c => s"$c ${scene.marks.count(_.epistemicChannel.contains(c))}")
      .mkString(", ")
    val why =
      if absent.isEmpty then ""
      else
        val gaps = scene.provenance.draft.flatMap(_.gapCount)
        val reason =
          if gaps.isEmpty then
            "no derivation record reached this view, so no uncertainty state was minted"
          else "this model recorded no gap of those kinds"
        s" · declared and not reached here: ${absent.mkString(", ")} — $reason"
    // The situations themselves carry no epistemic status in this projection, and a plate whose
    // dots are all identical must say that rather than let a reader read uniformity as agreement.
    val routes = scene.marks.count(_.isInstanceOf[VisualPrimitive.Route])
    val situations = scene.marks.count(_.isInstanceOf[VisualPrimitive.Landmark])
    val perMark =
      if routes > 0 then
        s" · $routes relation edges carry an epistemic status and are drawn in its weight and dash"
      else if situations > 0 then
        s" · the $situations situation marks carry no epistemic status in this scene: a landmark " +
          "has a kind, a lane and a position and no claim status, so every dot is drawn alike"
      else ""
    if present.isEmpty then s"no epistemic channel is in use$why$perMark"
    else s"epistemic channels in use: $counts$why$perMark"

  private def maxContextLane(mark: VisualPrimitive): Option[Int] = mark match
    case _: VisualPrimitive.Feature                    => None
    case _: VisualPrimitive.SurfaceUnit                => None
    case VisualPrimitive.Region(_, extent, _, _)       => Some(extent.lane1)
    case VisualPrimitive.Landmark(_, at, _, _, _, _)   => Some(at.lane)
    case VisualPrimitive.Thread(_, _, points)          => points.map(_.lane).maxOption
    case VisualPrimitive.Portal(_, from, to, _)        => Some(math.max(from.lane, to.lane))
    case VisualPrimitive.Route(_, from, to, _, _)      => Some(math.max(from.lane, to.lane))
    case VisualPrimitive.ContextBand(_, _, _, e, _, _) => Some(e.toVector.map(_.lane1).max)
    case _: VisualPrimitive.Gap                        => None
    case _: VisualPrimitive.Abstention                 => None
    case _: VisualPrimitive.UnsatisfiedLaw             => None

  // ---------------------------------------------------------------- the discourse axis

  /** Ticks from Intaglio's own pretty-break generator, so the axis is not hand-chosen. */
  private def ticksFor(discourseLength: Int): Vector[(Double, String)] =
    val result =
      for
        range <- _root_.intaglio.Interval(0.0, math.max(discourseLength.toDouble, 1.0))
        values <- _root_.intaglio.Breaks.prettyUnsafe(9).generate(range)
        kept = values.filter(range.contains)
      yield kept.zip(_root_.intaglio.Labeler.default(kept))
    result.getOrElse(Vector.empty)

  // ---------------------------------------------------------------- the label budget

  /** A label candidate, in discourse order within its lane. */
  private final case class Candidate(markId: String, x: Int, lane: Int, text: String)

  /** Entity threads, then landmarks, then relation edges.
    *
    * The order is a declared rule over what the scene supplies, never a judgement of importance. A
    * thread is one entity's whole trajectory through the work and there are at most a handful, so
    * naming the cast costs a lane three slots; a landmark is one situation and there are dozens; an
    * edge is a claim about two landmarks and so cannot be read before them. Within each family the
    * order is discourse order, the only ordering the scene supplies.
    */
  private def candidates(marks: Vector[VisualPrimitive], selected: Set[String]): Vector[Candidate] =
    val landmarks = marks.collect { case VisualPrimitive.Landmark(id, at, label, _, _, _) =>
      Candidate(id.mark.value, at.x, at.lane, label)
    }
    val threads = marks.collect {
      case VisualPrimitive.Thread(id, label, points) if points.nonEmpty =>
        Candidate(id.mark.value, points.head.x, points.head.lane, label)
    }
    val edges = marks.collect {
      case VisualPrimitive.Portal(id, from, to, mode) =>
        Candidate(id.mark.value, (from.x + to.x) / 2, from.lane, s"portal · $mode")
      case VisualPrimitive.Route(id, from, to, layer, status) =>
        Candidate(id.mark.value, (from.x + to.x) / 2, from.lane, s"$layer · $status")
    }
    // A selected candidate is offered a row before any other, so the one object the whole view
    // shares is never the one the budget withholds.
    val (focused, rest) = landmarks.partition(c => selected.contains(c.markId))
    focused.sortBy(c => (c.x, c.markId)) ++
      threads.sortBy(c => (c.x, c.markId)) ++
      rest.sortBy(c => (c.x, c.markId)) ++
      edges.sortBy(c => (c.x, c.markId))

  /** Greedy, collision-free placement into a small number of label rows per lane.
    *
    * Each candidate is offered its rows in order and, within a row, the space to the right of its
    * mark and then the space to its left. A candidate that finds no clear interval is not drawn and
    * is counted; nothing is ever moved to a position that would misreport which mark it labels, and
    * nothing is shrunk to fit.
    */
  private def budgetLabels(
      marks: Vector[VisualPrimitive],
      plan: Plan
  ): (Map[String, PlacedLabel], Vector[LaneBudget]) =
    val gap = Metric.labelGapPx
    val byLane = candidates(marks, plan.selected).groupBy(_.lane)
    val placed = Map.newBuilder[String, PlacedLabel]
    val budgets = Vector.newBuilder[LaneBudget]

    plan.lanes.foreach { lane =>
      val here = byLane.getOrElse(lane.index, Vector.empty)
      // occupied[row] is the set of [start, end) pixel intervals already spoken for.
      val occupied = Array.fill(plan.labelRows)(Vector.empty[(Double, Double)])
      var labelled = 0
      here.foreach { candidate =>
        Measure.elideMiddle(candidate.text, Metric.labelMaxPx, Typeface.labelPt) match
          case None       => ()
          case Some(text) =>
            val width = Measure.widthPx(text, Typeface.labelPt)
            val markPx = plan.xOf(candidate.x.toDouble)
            val toRight = markPx + 5.0
            val toLeft = markPx - 5.0 - width
            val options =
              for
                row <- (0 until plan.labelRows).toVector
                start <- Vector(toRight, toLeft)
              yield (row, start)
            options
              .find { (row, start) =>
                start >= plan.plotLeftPx && start + width <= plan.rightPx &&
                !occupied(row).exists((a, b) => start - gap < b && start + width + gap > a)
              }
              .foreach { (row, start) =>
                occupied(row) = occupied(row) :+ (start, start + width)
                placed += candidate.markId -> PlacedLabel(
                  text,
                  candidate.x,
                  candidate.lane,
                  row,
                  start - markPx
                )
                labelled += 1
              }
      }
      if here.nonEmpty then budgets += LaneBudget(lane.index, labelled, here.length)
    }
    (placed.result(), budgets.result())

  // ---------------------------------------------------------------- recorded absence

  /** `ClaimFamily.Custom` renders as a Scala product string upstream; name it ourselves so the twin
    * and the picture read the same and no compiler-generated text reaches a label.
    */
  private def claimFamily(family: ClaimFamily): String = family match
    case ClaimFamily.Custom(namespace, name) => s"$namespace/$name"
    case other                               => other.toString

  /** The (headline, detail) a mark is grouped under: what it records, not which object it records
    * it about. Two marks share a group exactly when a reader would otherwise read the same sentence
    * twice.
    */
  private def reasonOf(mark: VisualPrimitive): Option[(String, String)] = mark match
    case VisualPrimitive.UnsatisfiedLaw(_, v, _) =>
      Some((s"${v.law} · ${v.severity}", v.reason))
    case VisualPrimitive.Gap(_, family, _, reason, state, _) =>
      Some((s"derivation gap · ${claimFamily(family)} · $state", reason.render))
    case VisualPrimitive.Abstention(_, _, reason, _) =>
      Some(("provider abstention", reason.render))
    case _ => None

  private def sortRank(mark: VisualPrimitive): Int = mark match
    case _: VisualPrimitive.UnsatisfiedLaw => 0
    case _: VisualPrimitive.Gap            => 1
    case _: VisualPrimitive.Abstention     => 2
    case _                                 => 3

  /** Most-repeated reason first, so what most stops the model promoting is read first. */
  private def groupAbsences(
      marks: Vector[VisualPrimitive],
      discourseLength: Int,
      plotWidthPx: Double
  ): Vector[AbsenceGroup] =
    val keyed = marks.flatMap(m => reasonOf(m).map(r => (r, sortRank(m), m)))
    val ordered = keyed
      .groupBy((reason, rank, _) => (rank, reason))
      .toVector
      .sortBy { case ((rank, (headline, detail)), members) =>
        (rank, -members.length, headline, detail)
      }
    var top = Metric.absenceCaptionPx
    ordered.map { case ((_, (headline, detail)), members) =>
      val group = pack(headline, detail, members.map(_._3), discourseLength, plotWidthPx, top)
      top += group.heightPx
      group
    }

  /** First-fit interval packing of a group's marks into rows.
    *
    * Two marks share a row exactly when their exact spans do not come within a few pixels of each
    * other, so a row reads as a stratum of disjoint footprints. Which row a mark lands in is
    * layout, and the plate says so.
    */
  private def pack(
      headline: String,
      detail: String,
      members: Vector[VisualPrimitive],
      discourseLength: Int,
      plotWidthPx: Double,
      topPx: Double
  ): AbsenceGroup =
    val maxRows = 6
    val gapPx = 5.0
    val scale = plotWidthPx / math.max(discourseLength.toDouble, 1.0)
    val spanned = members
      .flatMap(m => m.epistemicPlacement.flatMap(_.spanSet).map(s => (m.identity.mark.value, s)))
      .sortBy((id, s) => (s.spans.toVector.map(_.start).min, id))
    val unplaced = members
      .filter(_.epistemicPlacement.forall(_.spanSet.isEmpty))
      .map(_.identity.mark.value)
      .sorted

    val rowEnd = Array.fill(maxRows)(Double.NegativeInfinity)
    val rowOf = Map.newBuilder[String, Int]
    var used = 0
    spanned.zipWithIndex.foreach { case ((id, spans), index) =>
      val xs = spans.spans.toVector
      val startPx = xs.map(_.start).min * scale
      val endPx = xs.map(_.endExclusive).max * scale
      val free = (0 until maxRows).find(r => startPx >= rowEnd(r) + gapPx)
      val row = free.getOrElse(index % maxRows)
      rowEnd(row) = math.max(rowEnd(row), endPx)
      used = math.max(used, row + 1)
      rowOf += id -> row
    }
    val rowCount = math.max(used, if unplaced.isEmpty then 0 else 1)
    AbsenceGroup(
      headline = headline,
      detail = detail,
      count = members.length,
      rowOf = rowOf.result(),
      unplaced = unplaced,
      rowCount = rowCount,
      topPx = topPx,
      heightPx =
        Metric.absenceGroupHeadPx + rowCount * Metric.absenceRowPx + Metric.absenceGroupGapPx
    )

  // ---------------------------------------------------------------- lane naming

  /** The head of a context kind, as the model spells it. Never prettified: a reader who sees
    * `Speech` on the plate finds `Speech` in the twin and in the model.
    */
  def kindHead(kind: ContextKind): String = kind match
    case ContextKind.NarratedWorld  => "NarratedWorld"
    case ContextKind.Speech(_)      => "Speech"
    case ContextKind.Belief(_)      => "Belief"
    case ContextKind.Desire(_)      => "Desire"
    case ContextKind.Intention(_)   => "Intention"
    case ContextKind.Hypothetical   => "Hypothetical"
    case ContextKind.Counterfactual => "Counterfactual"
    case ContextKind.Memory(_)      => "Memory"
    case ContextKind.Imagination(_) => "Imagination"

  def kindHolder(kind: ContextKind): Option[String] = kind.heldBy.map(_.render)

  /** The holder of a frame, named from the scene or described in words. */
  private def holderOf(kind: ContextKind, labels: Map[String, String]): LaneHolder =
    kind.heldBy match
      case None                              => LaneHolder.Unheld
      case Some(ContextHolder.Named(entity)) =>
        labels
          .get(entity.value)
          .fold(LaneHolder.Unnamed("a speaker this plate draws no thread for"))(
            LaneHolder.Named.apply
          )
      case Some(ContextHolder.Unattributed(gap)) =>
        LaneHolder.Unnamed(gapWords(gap))

  /** A holder gap in words rather than in its wire form. */
  private def gapWords(gap: HolderGap): String = gap match
    case HolderGap.NoCandidate         => "no speaker was proposed"
    case HolderGap.SeveralCandidates   => "several speakers were proposed and none chosen"
    case HolderGap.UnresolvedCandidate => "a speaker was proposed and not resolved"

  /** The narrated world is the ground; everything else is a frame drawn on it. */
  def isNarrated(kind: ContextKind): Boolean = kind == ContextKind.NarratedWorld

package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storymodel4s.core.{EpistemicStatus, SurfaceUnitKind}
import storymodel4s.view.*

/** Pure lowering of a compiled [[NarrativeScene]] to an Intaglio scene.
  *
  * The Discourse Atlas already placed every narrative mark: x is an exact discourse offset and y is
  * a context lane (ADR 0002 §2, D4). This lowering composes those marks into a plate — a header
  * that names the build, a contract block that declares what every axis and region means, a lane
  * plot with named and separated context lanes, a discourse axis with ticks, a layout-only surface
  * rail, and an absence rail in which every reason the model does not promote is written once and
  * drawn on the exact material it concerns.
  *
  * The composition is decided in [[AtlasPlate]] and only drawn here. Two invariants hold across the
  * whole file:
  *
  *   - every mark becomes exactly one named group (`data-name` = `MarkId`) and nothing else in the
  *     output carries a name, so the plate's identity system is the model's;
  *   - no mark, and no label, is placed anywhere the scene does not put it. Labels are budgeted and
  *     elided, never moved off their mark and never shrunk below the type floor.
  *
  * `discourseLength` is the length of the canonical text in UTF-16 code units. It is passed as a
  * number because a scene does not carry its source and Intaglio must never see canonical text
  * (D6); the same length at every zoom level keeps x comparable across levels (V-I1). `box` is the
  * document box the plate is composed for: a label budget and a lane height are facts about a
  * physical extent, so the composition may not pretend not to know it.
  */
object AtlasLowering:

  /** The plate's fixed layer order. A scene always has all six, in this order, whatever the model
    * contains: a layer with nothing to draw is empty, never absent, so a caller can address the
    * lane plot's geometry without counting what happened to be emitted.
    */
  enum Layer:
    case Ground, Chrome, SurfaceRail, LanePlot, EpistemicRail, FeatureRail

  def layerOf(scene: ig.Scene, layer: Layer): ig.Grob = scene.grobs(layer.ordinal)

  /** Enlarge a feature plate vertically to retain every packed outcome and the bottom legend. */
  def fitFeatureBox(
      scene: NarrativeScene,
      discourseLength: Int,
      minimum: PlateBox
  ): Either[GraphicsError, PlateBox] =
    val plan = AtlasPlate.plan(scene, discourseLength, minimum)
    // The general plate's compressed lanes can fit glyphs but not the multi-line holder labels.
    // Feature editions grow vertically, so reserve readable lane space before adding absences.
    val labelRoom = math.max(0.0, 120.0 - plan.laneHeightPx) * plan.laneCount
    val needed = math.ceil(plan.legendTopPx + Metric.legendPx + Metric.bottomPadPx + labelRoom)
    PlateBox.of(minimum.widthPx.toInt, math.max(minimum.heightPx, needed).toInt)

  def lower(
      scene: NarrativeScene,
      discourseLength: Int,
      box: PlateBox = PlateBox.default
  ): Either[GraphicsError, ig.Scene] =
    val plan = AtlasPlate.plan(scene, discourseLength, box)
    val Marks(surfaceMarks, narrativeMarks, epistemicMarks) = partitionMarks(scene.marks)
    for
      _ <- Either.cond(
        plan.featureHeightPx == 0 ||
          plan.legendTopPx + Metric.legendPx + Metric.bottomPadPx <= box.heightPx + 0.001,
        (),
        GraphicsError.InvalidExtent(
          "feature outcomes exceed the plate; use fitFeatureBox before lowering"
        )
      )
      style <- Style.params
      ground <- groundLayer(plan, style)
      chrome <- chromeLayer(scene, plan, style, surfaceMarks, epistemicMarks)
      surface <- surfaceRail(surfaceMarks, plan, style)
      plot <- lanePlot(narrativeMarks, plan, style)
      epistemic <- epistemicRail(epistemicMarks, plan, style)
      feature <- featureRail(scene, plan, style)
    yield ig.Scene(Vector(ground, chrome, surface, plot, epistemic, feature))

  // ------------------------------------------------------------------ mark classification

  /** The three rails a scene draws into: the layout-only surface rail, the projection's own
    * context-lane plot, and the layout-only absence rail.
    */
  private final case class Marks(
      surface: Vector[VisualPrimitive.SurfaceUnit],
      narrative: Vector[VisualPrimitive],
      epistemic: Vector[VisualPrimitive]
  )

  /** Preserve exhaustivity at the public sibling ADT boundary: a new case must be classified.
    *
    * The three absence marks are separated out because they carry no context lane at all (ADR 0002
    * D9): their geometry is an `EpistemicPlacement` over exact spans, or the stated absence of any
    * honest discourse position. Putting them in the lane plot would invent a lane for them.
    */
  private def partitionMarks(marks: Vector[VisualPrimitive]): Marks =
    marks.foldLeft(Marks(Vector.empty, Vector.empty, Vector.empty)) { (acc, mark) =>
      mark match
        case _: VisualPrimitive.Feature        => acc
        case m: VisualPrimitive.SurfaceUnit    => acc.copy(surface = acc.surface :+ m)
        case m: VisualPrimitive.Region         => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Landmark       => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Thread         => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Portal         => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Route          => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.ContextBand    => acc.copy(narrative = acc.narrative :+ m)
        case m: VisualPrimitive.Gap            => acc.copy(epistemic = acc.epistemic :+ m)
        case m: VisualPrimitive.Abstention     => acc.copy(epistemic = acc.epistemic :+ m)
        case m: VisualPrimitive.UnsatisfiedLaw => acc.copy(epistemic = acc.epistemic :+ m)
    }

  /** Draw order only (bands and regions under everything, landmarks on top); never an inference.
    *
    * Context bands sit at the bottom of the stack because they are the ground the rest is read
    * against: a landmark inside the survivor's retelling must be legible as sitting on that band.
    */
  private def orderedNarrative(marks: Vector[VisualPrimitive]): Vector[VisualPrimitive] =
    val rank: VisualPrimitive => Int =
      case _: VisualPrimitive.Feature        => 0
      case _: VisualPrimitive.SurfaceUnit    => 0
      case _: VisualPrimitive.ContextBand    => 1
      case _: VisualPrimitive.Region         => 2
      case _: VisualPrimitive.Thread         => 3
      case _: VisualPrimitive.Route          => 4
      case _: VisualPrimitive.Portal         => 5
      case _: VisualPrimitive.Landmark       => 6
      case _: VisualPrimitive.Gap            => 7
      case _: VisualPrimitive.Abstention     => 7
      case _: VisualPrimitive.UnsatisfiedLaw => 7
    marks.zipWithIndex.sortBy((m, i) => (rank(m), i)).map(_._1)

  // ------------------------------------------------------------------ page-space primitives

  private def pt(px: Double): ig.ExtentExpr = ig.ExtentExpr.pointsUnsafe(math.abs(px) * 72.0 / 96.0)

  private def pageText(
      plan: AtlasPlate.Plan,
      xPx: Double,
      yPx: Double,
      label: String,
      gp: ig.GraphicParams,
      anchor: ig.Anchor = Style.labelAnchor
  ): Either[GraphicsError, ig.Grob] =
    for
      at <- ig.Point.npc(plan.x(xPx), plan.y(yPx))
      grob <- ig.Grob.text(label, at, anchor, gp = gp)
    yield grob

  private def pageRule(
      plan: AtlasPlate.Plan,
      x0Px: Double,
      x1Px: Double,
      yPx: Double,
      gp: ig.GraphicParams
  ): Either[GraphicsError, ig.Grob] =
    for
      a <- ig.Point.npc(plan.x(x0Px), plan.y(yPx))
      b <- ig.Point.npc(plan.x(x1Px), plan.y(yPx))
      grob <- ig.Grob.lines(Vector(a, b), gp = gp)
    yield grob

  private def pageStile(
      plan: AtlasPlate.Plan,
      xPx: Double,
      y0Px: Double,
      y1Px: Double,
      gp: ig.GraphicParams
  ): Either[GraphicsError, ig.Grob] =
    for
      a <- ig.Point.npc(plan.x(xPx), plan.y(y0Px))
      b <- ig.Point.npc(plan.x(xPx), plan.y(y1Px))
      grob <- ig.Grob.lines(Vector(a, b), gp = gp)
    yield grob

  private def pageBox(
      plan: AtlasPlate.Plan,
      x0Px: Double,
      y0Px: Double,
      x1Px: Double,
      y1Px: Double,
      gp: ig.GraphicParams
  ): Either[GraphicsError, ig.Grob] =
    for
      corners <- Vector(
        ig.Point.npc(plan.x(x0Px), plan.y(y0Px)),
        ig.Point.npc(plan.x(x1Px), plan.y(y0Px)),
        ig.Point.npc(plan.x(x1Px), plan.y(y1Px)),
        ig.Point.npc(plan.x(x0Px), plan.y(y1Px))
      ).sequence
      grob <- ig.Grob.polygon(corners, gp = gp)
    yield grob

  private def pageGlyph(
      plan: AtlasPlate.Plan,
      xPx: Double,
      yPx: Double,
      size: ig.ExtentExpr,
      shape: ig.PointShape,
      gp: ig.GraphicParams
  ): Either[GraphicsError, ig.Grob] =
    for
      at <- ig.Point.npc(plan.x(xPx), plan.y(yPx))
      grob <- ig.Grob.points(Vector(at), size, shape, gp = gp)
    yield grob

  // ------------------------------------------------------------------ ground

  /** Paper, the frame rules, and the lane separators: everything that is neither a mark nor a word.
    */
  private def groundLayer(
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val left = plan.contentLeftPx
    val right = plan.rightPx
    for
      paper <- pageBox(plan, 0.0, 0.0, plan.box.widthPx, plan.box.heightPx, style.ground)
      rules <- Vector(
        plan.contractTopPx - 8.0,
        plan.laneTopPx - 10.0,
        plan.surfaceTopPx - 8.0,
        plan.absenceTopPx - 8.0,
        plan.legendTopPx - 6.0
      ).traverse(y => pageRule(plan, left, right, y, style.rule))
      separators <- (0 to plan.laneCount).toVector.traverse(i =>
        pageRule(plan, left, right, plan.laneTopPx + i * plan.laneHeightPx, style.separator)
      )
      // The one vertical rule of the plate: everything right of it is on the discourse axis, and
      // everything left of it is deliberately not.
      axisEdge <- pageStile(
        plan,
        plan.plotLeftPx - 7.0,
        plan.laneTopPx,
        plan.absenceTopPx + plan.absenceHeightPx,
        style.separator
      )
    yield ig.Grob.group(paper +: (rules ++ separators :+ axisEdge))

  // ------------------------------------------------------------------ chrome

  private def chromeLayer(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params,
      surfaceMarks: Vector[VisualPrimitive.SurfaceUnit],
      epistemicMarks: Vector[VisualPrimitive]
  ): Either[GraphicsError, ig.Grob] =
    for
      header <- headerBlock(scene, plan, style)
      contract <- contractBlock(scene, plan, style)
      laneNames <- laneNameBlock(plan, style)
      axis <- axisBlock(scene, plan, style)
      surface <- surfaceCaption(scene, plan, style, surfaceMarks)
      absence <- absenceCaptions(scene, plan, style, epistemicMarks)
      legend <- legendBlock(scene, plan, style)
    yield ig.Grob.group(header ++ contract ++ laneNames ++ axis ++ surface ++ absence ++ legend)

  private def headerBlock(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val p = scene.provenance
    val left = plan.contentLeftPx
    val top = plan.headerTopPx
    val title = "Narrative Atlas"
    val titleWidth = Measure.widthPx(title, Typeface.titlePt)
    val draft = p.draft
    val promotion = draft.fold("") { d =>
      val gaps = d.gapCount.fold("derivation record not supplied")(n => s"$n derivation gaps")
      s" · ${if d.promoted then "promotable" else "does not promote"} · " +
        s"${d.violationCount} unsatisfied laws · $gaps"
    }
    val meta =
      s"${scene.contract.kind} · zoom ${scene.zoom.narrative} / ${scene.zoom.surface}" +
        s" · ${plan.laneCount} context lanes · ${scene.marks.length} marks$promotion"
    val receipt = p.modelReceiptChecksum.fold("not available")(_.hex)
    for
      titleGrob <- pageText(plan, left, top + 13.0, title, style.title)
      metaGrob <- pageText(plan, left + titleWidth + 10.0, top + 14.0, meta, style.meta)
      source <- pageText(
        plan,
        left,
        top + 38.0,
        s"source  ${p.sourceChecksum.hex}   ·   model receipt  $receipt",
        style.machine
      )
      config <- pageText(
        plan,
        left,
        top + 56.0,
        s"configuration  ${p.configChecksum.hex}",
        style.machine
      )
      compiler <- pageText(
        plan,
        left,
        top + 74.0,
        s"compiler  ${p.compilerVersion}",
        style.machine
      )
      chip <- basisChip(p.basis.label, plan, style, top + 20.0)
    yield Vector(titleGrob, metaGrob, source, config, compiler) ++ chip

  /** The basis, boxed at the top right, because a draft build and a reviewed fixture must never be
    * read off the same plate without noticing which one this is.
    */
  private def basisChip(
      label: String,
      plan: AtlasPlate.Plan,
      style: Style.Params,
      centreYPx: Double
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val width = Measure.widthPx(label, Typeface.metaPt) + 22.0
    val x1 = plan.rightPx
    val x0 = x1 - width
    for
      frame <- pageBox(plan, x0, centreYPx - 12.0, x1, centreYPx + 12.0, style.region)
      text <- pageText(plan, x0 + 11.0, centreYPx, label, style.section)
    yield Vector(frame, text)

  /** Every axis, distance, lane and region drawn on this plate has a declared meaning, and the
    * declaration is the scene's own `ProjectionContract`, printed rather than paraphrased.
    */
  private def contractBlock(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val c = scene.contract
    val left = plan.contentLeftPx
    val top = plan.contractTopPx
    val meanings = c.legend.map(m => m.channel -> m.meaning).toMap
    val axes =
      s"x: ${c.x}   ·   y: ${c.y}   ·   distance: ${c.distance}   ·   " +
        s"area: ${c.area.fold("not encoded")(_.toString)}"
    val unencoded = c.legend
      .filter(_.meaning == "no semantic interpretation")
      .map(_.channel.toString)
    val budget =
      s"labels are budgeted: ${plan.labelsDrawn} of ${plan.labellable} drawn, " +
        s"${plan.labelsWithheld} withheld; every mark is named and in the textual twin"
    for
      axesGrob <- pageText(plan, left, top + 10.0, axes, style.laneKind)
      xGrob <- pageText(
        plan,
        left,
        top + 28.0,
        s"x — ${meanings.getOrElse(VisualChannel.X, "")}",
        style.fine
      )
      yGrob <- pageText(
        plan,
        left,
        top + 44.0,
        s"y — ${meanings.getOrElse(VisualChannel.Y, "")}",
        style.fine
      )
      noneGrob <- pageText(
        plan,
        left,
        top + 60.0,
        s"no semantic interpretation: ${unencoded.mkString(" · ")}   ·   $budget",
        style.fine
      )
      // The shared focus contract, stated on the plate: what this projection did with the one
      // address every face of the view carries. Never silently dropped (design brief §6).
      focusGrob <- pageText(
        plan,
        left,
        top + 76.0,
        s"shared selection: ${plan.focusNote}",
        if plan.selected.isEmpty then style.fine else style.focusLabel
      )
      sourceGrob <- pageText(
        plan,
        left,
        top + 108.0,
        "a situation's label is the model's own description of it, not the story's words; " +
          "the words are in the Codex beside this plate",
        style.fine
      )
      levelGrob <- Measure
        .wrap(plan.levelNote, plan.rightPx - left, Typeface.finePt)
        .zipWithIndex
        .traverse((line, i) => pageText(plan, left, top + 92.0 + i * 14.0, line, style.fine))
    yield Vector(axesGrob, xGrob, yGrob, noneGrob, focusGrob, sourceGrob) ++ levelGrob

  /** Each lane is named where it is drawn, from the context frame that occupies it. A lane with no
    * visible band is numbered and left unnamed: naming it from a neighbour would invent a context.
    */
  private def laneNameBlock(
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val right = plan.contentLeftPx + Metric.laneNameWidthPx - 12.0
    val width = Metric.laneNameWidthPx - 16.0
    plan.lanes.flatTraverse { lane =>
      val mid = plan.laneTopPx + (lane.index + 0.5) * plan.laneHeightPx
      val head = lane.kind.fold("no band in view")(AtlasPlate.kindHead)
      // The holder in the story's own word where the scene carries it, in plain words where it
      // does not, and never as a content hash: an address belongs in the inspector.
      val (holder, holderStyle) = lane.holder match
        case AtlasPlate.LaneHolder.Named(label)    => (Some(s"“$label”"), style.laneName)
        case AtlasPlate.LaneHolder.Unnamed(reason) => (Some(reason), style.laneKind)
        case AtlasPlate.LaneHolder.Unheld          => (None, style.laneKind)
      for
        number <- pageText(
          plan,
          right,
          mid - 15.0,
          s"lane ${lane.index}",
          style.laneKind,
          Style.rowLabelAnchor
        )
        kind <- pageText(plan, right, mid, head, style.laneName, Style.rowLabelAnchor)
        heldBy <- holder.toVector
          .flatMap(h => Measure.wrap(h, width, Typeface.laneKindPt))
          .zipWithIndex
          .traverse((line, i) =>
            pageText(plan, right, mid + 15.0 + i * 12.0, line, holderStyle, Style.rowLabelAnchor)
          )
      yield Vector(number, kind) ++ heldBy
    }

  private def axisBlock(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val y = plan.axisTopPx + 3.0
    val meaning = scene.contract.legend
      .find(_.channel == VisualChannel.X)
      .fold("exact discourse offset")(_.meaning)
    for
      baseline <- pageRule(plan, plan.plotLeftPx, plan.rightPx, y, style.axisRule)
      ticks <- plan.ticks.flatTraverse { (value, label) =>
        val x = plan.xOf(value)
        for
          tick <- pageStile(plan, x, y, y + 5.0, style.axisRule)
          text <- pageText(plan, x, y + 15.0, label, style.axis, Style.centreAnchor)
        yield Vector(tick, text)
      }
      title <- pageText(plan, plan.plotLeftPx, y + 33.0, meaning, style.fine)
    yield baseline +: ticks :+ title

  /** The surface rail's own caption. "Hidden" is a state the reader must be able to tell from
    * "missing": the rail is drawn and labelled even when nothing was requested for it.
    */
  private def surfaceCaption(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params,
      marks: Vector[VisualPrimitive.SurfaceUnit]
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val kinds = marks.map(_.kind.toString).distinct.sorted
    val body =
      if marks.isEmpty then
        s"surface detail ${scene.zoom.surface} — no surface units are requested at this zoom. " +
          "This is a stated setting, not a missing measurement."
      else
        s"${marks.length} exact units (${kinds.mkString(", ")}) at their exact spans. " +
          "Kind is a subrow; vertical position in this rail is layout and means nothing."
    for
      head <- pageText(
        plan,
        plan.contentLeftPx,
        plan.surfaceTopPx + 8.0,
        "Surface rail",
        style.section
      )
      caption <- pageText(
        plan,
        plan.contentLeftPx + Measure.widthPx("Surface rail", Typeface.sectionPt) + 12.0,
        plan.surfaceTopPx + 8.0,
        body,
        style.fine
      )
      // The rail is reserved at every zoom, so its geometry never moves when surface detail
      // changes and an empty rail reads as reserved rather than as a void.
      baseline <- pageRule(
        plan,
        plan.plotLeftPx,
        plan.rightPx,
        plan.surfaceTopPx + plan.surfaceHeightPx - 4.0,
        style.hairline
      )
    yield Vector(head, caption, baseline)

  /** One line per reason, with its count, and nothing repeated.
    *
    * Sixty-five marks of one unsatisfied law are one fact about sixty-five situations. Writing the
    * sentence sixty-five times is what made the old rail a smear; writing it once, with the tally
    * and with every mark still drawn on its own exact spans, is what makes it readable.
    */
  private def absenceCaptions(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params,
      marks: Vector[VisualPrimitive]
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val left = plan.contentLeftPx
    val promotes = scene.provenance.draft.forall(_.promoted)
    val head = "Recorded absence"
    val body =
      if marks.isEmpty then
        "nothing recorded: this scene carries no gap, abstention or unsatisfied law."
      else if promotes then s"${marks.length} marks, each drawn on the exact material it concerns."
      else
        s"this model does not promote. ${marks.length} marks, each drawn on the exact material " +
          "it concerns. Read a row left to right for where a reason bites; the row a mark sits " +
          "in only keeps neighbours apart."
    for
      title <- pageText(plan, left, plan.absenceTopPx + 9.0, head, style.section)
      caption <- pageText(
        plan,
        left + Measure.widthPx(head, Typeface.sectionPt) + 12.0,
        plan.absenceTopPx + 9.0,
        body,
        style.fine
      )
      channels <- Measure
        .wrap(plan.channelNote, plan.rightPx - left, Typeface.finePt)
        .zipWithIndex
        .traverse((line, i) =>
          pageText(plan, left, plan.absenceTopPx + 24.0 + i * 14.0, line, style.fine)
        )
      groups <- plan.absence.flatTraverse { group =>
        val y = plan.absenceTopPx + group.topPx + 9.0
        val headWidth = Measure.widthPx(group.headline, Typeface.finePt)
        val detailAt = plan.plotLeftPx + headWidth + 14.0
        for
          count <- pageText(
            plan,
            plan.plotLeftPx - 12.0,
            y,
            s"${group.count} ×",
            style.accent,
            Style.rowLabelAnchor
          )
          headline <- pageText(plan, plan.plotLeftPx, y, group.headline, style.machine)
          detail <- Measure
            .elide(group.detail, plan.rightPx - detailAt, Typeface.finePt)
            .traverse(d => pageText(plan, detailAt, y, d, style.fine))
        yield Vector(count, headline) ++ detail
      }
      margin <- marginCaption(plan, style)
    yield Vector(title, caption) ++ channels ++ groups ++ margin

  /** The margin column's own label. Marks land here when the model states they have no honest
    * discourse position at all, which is a different thing from being placed at zero.
    */
  private def marginCaption(
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    if plan.absence.forall(_.unplaced.isEmpty) then Right(Vector.empty)
    else
      val top = plan.absenceTopPx + Metric.absenceCaptionPx - 2.0
      val bottom = plan.absenceTopPx + plan.absenceHeightPx - 8.0
      // Set up the column, in two short lines that fit its height: the caption then costs no width
      // at all, so the margin stays as narrow as the thing it holds. The root frame is y-up, where
      // Intaglio's positive angle reads upward.
      val spine = Vector("no discourse", "position").zipWithIndex
      for
        text <- spine.traverse { (word, column) =>
          for
            at <- ig.Point.npc(plan.x(plan.marginLeftPx + 16.0 + column * 13.0), plan.y(bottom))
            grob <- ig.Grob.text(
              word,
              at,
              Style.labelAnchor,
              rotationDegrees = 90.0,
              gp = style.laneKind
            )
          yield grob
        }
        edge <- pageStile(plan, plan.marginLeftPx + 4.0, top, bottom, style.separator)
      yield text :+ edge

  /** One legend entry: how wide its drawn key is, and how to draw it at a given left edge. */
  private final case class Key(
      widthPx: Double,
      label: String,
      draw: (Double, Double) => Either[GraphicsError, Vector[ig.Grob]]
  )

  /** What every mark and line on the plate is, drawn rather than described. */
  private def legendBlock(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val y = plan.legendTopPx + 12.0
    val keys = Vector(
      Key(
        12.0,
        "situation · Event",
        (x, ry) =>
          pageGlyph(plan, x + 6.0, ry, Metric.glyph, ig.PointShape.Circle, style.landmark)
            .map(Vector(_))
      ),
      Key(
        12.0,
        "situation · State",
        (x, ry) =>
          pageGlyph(plan, x + 6.0, ry, Metric.glyph, ig.PointShape.Square, style.landmark)
            .map(Vector(_))
      ),
      Key(
        30.0,
        "the narrated world, one extent per span of its exact scope evidence",
        // Drawn as what it is: a comb of separate extents, never one block. A solid swatch would
        // promise a continuous stretch the model does not claim.
        (x, ry) =>
          Vector(0.0, 6.0, 9.0, 15.0, 21.0, 24.0).grouped(2).toVector.traverse {
            case Vector(a, b) => pageBox(plan, x + a, ry - 5.0, x + b, ry + 5.0, style.contextBand)
            case _            => pageBox(plan, x, ry - 5.0, x + 4.0, ry + 5.0, style.contextBand)
          }
      ),
      Key(
        26.0,
        "a context frame that is not narration; the dashed ties are its extent on the axis",
        (x, ry) =>
          for
            band <- pageBox(plan, x, ry - 3.0, x + 26.0, ry + 7.0, style.speechFill)
            a <- pageStile(plan, x, ry - 10.0, ry - 3.0, style.tie)
            b <- pageStile(plan, x + 26.0, ry - 10.0, ry - 3.0, style.tie)
          yield Vector(a, b, band)
      ),
      Key(
        26.0,
        "entity thread; the path between rings asserts nothing",
        (x, ry) =>
          for
            line <- pageRule(plan, x, x + 26.0, ry, style.thread)
            a <- pageGlyph(plan, x, ry, Metric.threadRing, ig.PointShape.Circle, style.threadRing)
            b <- pageGlyph(
              plan,
              x + 26.0,
              ry,
              Metric.threadRing,
              ig.PointShape.Circle,
              style.threadRing
            )
          yield Vector(line, a, b)
      ),
      Key(
        30.0,
        "recorded absence, on its exact spans",
        (x, ry) =>
          for
            g <- pageGlyph(plan, x, ry, Metric.absenceGlyph, ig.PointShape.Cross, style.epistemic)
            r <- pageRule(plan, x + 4.0, x + 30.0, ry, style.epistemic)
          yield Vector(g, r)
      ),
      Key(
        24.0,
        "a leader, ending in an elbow at the one label it serves",
        (x, ry) =>
          for
            leader <- pageRule(plan, x + 4.0, x + 20.0, ry - 7.0, style.leader)
            stem <- pageStile(plan, x + 4.0, ry - 7.0, ry + 3.0, style.leader)
            glyph <- pageGlyph(
              plan,
              x + 4.0,
              ry + 5.0,
              Metric.glyph,
              ig.PointShape.Circle,
              style.landmark
            )
          yield Vector(leader, stem, glyph)
      )
    )
    // Wrapped by measurement, so no key ever runs off the plate.
    val placed = keys
      .foldLeft((Vector.empty[(Key, Double, Int)], plan.contentLeftPx, 0)) {
        case ((acc, x, row), key) =>
          val span = key.widthPx + 7.0 + Measure.widthPx(key.label, Typeface.finePt)
          if x + span > plan.rightPx && acc.nonEmpty then
            (acc :+ (key, plan.contentLeftPx, row + 1), plan.contentLeftPx + span + 22.0, row + 1)
          else (acc :+ (key, x, row), x + span + 22.0, row)
      }
      ._1
    val rows = placed.map(_._3).maxOption.getOrElse(0)
    for
      drawn <- placed.flatTraverse { (key, left, row) =>
        val rowY = y + row * 18.0
        for
          marks <- key.draw(left, rowY)
          text <- pageText(plan, left + key.widthPx + 7.0, rowY, key.label, style.fine)
        yield marks :+ text
      }
      note <- pageText(
        plan,
        plan.contentLeftPx,
        y + (rows + 1) * 18.0 + 4.0,
        "Vertical distance carries no meaning; a lane is a category, not a quantity. " +
          s"Every visual has a textual twin: this plate names ${scene.marks.length} marks and so " +
          "does the twin beside it.",
        style.fine
      )
    yield drawn :+ note

  // ------------------------------------------------------------------ the lane plot

  private def laneViewport(plan: AtlasPlate.Plan): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(plan.x(plan.plotLeftPx), plan.y(plan.laneBottomPx))
      size <- ig.Size.npc(
        plan.plotWidthPx / plan.box.widthPx,
        (plan.laneBottomPx - plan.laneTopPx) / plan.box.heightPx
      )
      xScale <- ig.Interval(0.0, math.max(plan.discourseLength.toDouble, 1.0))
      yScale <- ig.Interval(0.0, math.max(plan.laneCount.toDouble, 1.0))
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  private def lanePlot(
      marks: Vector[VisualPrimitive],
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    for
      viewport <- laneViewport(plan)
      children <- orderedNarrative(marks).traverse(m => laneMark(m, plan, style))
    yield ig.Grob.group(children, viewport = Some(viewport))

  private def natX(x: Double): ig.LengthExpr = ig.LengthExpr.nativeUnsafe(x)
  private def natY(lane: Double): ig.LengthExpr = ig.LengthExpr.nativeUnsafe(lane)

  /** A point in the lane plot: exact discourse x, and an absolute typographic distance below the
    * lane's own top edge, so nothing in the vertical composition scales with the number of lanes.
    */
  private def at(x: Double, lane: Double, dyPx: Double, dxPx: Double = 0.0): ig.Point =
    ig.Point(
      if dxPx < 0 then natX(x) - pt(dxPx) else natX(x) + pt(dxPx),
      if dyPx < 0 then natY(lane) - pt(dyPx) else natY(lane) + pt(dyPx)
    )

  private def laneMark(
      mark: VisualPrimitive,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    for
      name <- GraphicsNames.ofMark(mark.identity.mark)
      children <- laneShape(mark, plan, style)
    yield ig.Grob.group(children, name = Some(name))

  private def laneShape(
      mark: VisualPrimitive,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] = mark match

    // One filled rectangle per extent, never a hull over the gaps between them (ADR 0002 D4 row 6).
    // The narrated world of the War of the Ghosts is 281 separate extents precisely so the five
    // speech frames inside it are not swallowed; hulling would redraw the fabricated battle as
    // narration. A frame that is not the narrated world is hatched as well as drawn on its own
    // lane, so "this is not narration" reads in monochrome and never from opacity (V-U5).
    case _: VisualPrimitive.Feature                                 => Right(Vector.empty)
    case VisualPrimitive.ContextBand(_, kind, _, extents, _, basis) =>
      val narrated = AtlasPlate.isNarrated(kind)
      val gp = if narrated then style.contextBand else style.speechFill
      val h = Metric.bandHalfPx
      val centre = plan.bandOffsetPx
      for
        ribbon <- extents.toVector.traverse { e =>
          val lane = e.lane0.toDouble
          ig.Grob.polygon(
            Vector(
              at(e.x0.toDouble, lane, centre - h),
              at(e.x1Exclusive.toDouble, lane, centre - h),
              at(e.x1Exclusive.toDouble, lane, centre + h),
              at(e.x0.toDouble, lane, centre + h)
            ),
            gp = gp
          )
        }
        // A frame that is not the narrated world carries its own two edges up the plot, so the
        // discourse interval it occupies can be read against every lane at once — including
        // against the gap it leaves in the narrated ground. The tie's x is the frame's exact
        // support; it asserts nothing about any lane it crosses.
        ties <-
          if narrated then Right(Vector.empty)
          else
            extents.toVector.flatMap(e => Vector(e.x0, e.x1Exclusive)).traverse { x =>
              ig.Grob.lines(
                Vector(
                  at(x.toDouble, extents.head.lane0.toDouble, centre - h),
                  at(x.toDouble, 0.0, 2.0)
                ),
                gp = style.tie
              )
            }
        // The dominant object on the plate may not be an unnamed track. The ribbon says what it is
        // and how many extents it is, beside itself, in the lane it occupies.
        caption <-
          if extents.length < 8 then Right(Vector.empty)
          else
            ig.Grob
              .text(
                s"${extents.length} separate stretches of narration, each at its own exact " +
                  s"offsets (${bandBasis(basis)}); the gaps between them are where someone speaks",
                at(extents.head.x0.toDouble, extents.head.lane0.toDouble, centre + 13.0, 1.0),
                Style.labelAnchor,
                gp = style.machine
              )
              .map(Vector(_))
      yield ties ++ ribbon ++ caption

    // A region's hull is drawn where the model puts it — across the lanes of its visible children —
    // with its summary inside, elided to the region's own width rather than allowed to run over its
    // neighbours. A region whose summary the model did not derive (RegionLabel.Unsummarized, ADR
    // 0002 §15) gets the hull and no words: any words here would be the plate's, not the model's.
    case VisualPrimitive.Region(_, e, label, _) =>
      val widthPx = plan.xOf(e.x1Exclusive.toDouble) - plan.xOf(e.x0.toDouble)
      for
        polygon <- ig.Grob.polygon(
          Vector(
            at(e.x0.toDouble, e.lane0.toDouble, 4.0),
            at(e.x1Exclusive.toDouble, e.lane0.toDouble, 4.0),
            at(e.x1Exclusive.toDouble, (e.lane1 + 1).toDouble, -4.0),
            at(e.x0.toDouble, (e.lane1 + 1).toDouble, -4.0)
          ),
          gp = style.region
        )
        text <- label match
          case RegionLabel.Unsummarized(_) => Right(None)
          case RegionLabel.Summary(words)  =>
            Measure
              .elideMiddle(words, widthPx - 16.0, Typeface.labelPt)
              .traverse(t =>
                ig.Grob.text(
                  t,
                  at(e.x0.toDouble, e.lane0.toDouble, 15.0, 7.0),
                  Style.labelAnchor,
                  gp = style.label
                )
              )
      yield polygon +: text.toVector

    case VisualPrimitive.Landmark(id, anchor, _, kind, _, _) =>
      val shape = kind match
        case LandmarkKind.Event => ig.PointShape.Circle
        case LandmarkKind.State => ig.PointShape.Square
      val focused = plan.selected.contains(id.mark.value)
      val row = plan.markOffsetPx(id.mark.value)
      val where = at(anchor.x.toDouble, anchor.lane.toDouble, row)
      for
        halo <- ig.Grob.points(Vector(where), Metric.glyphHalo, shape, gp = style.landmarkHalo)
        // The focus ring is a second, dashed shape rather than a change of colour, so the shared
        // selection is still legible in monochrome (design brief §11).
        ring <-
          if !focused then Right(Vector.empty)
          else
            ig.Grob
              .points(Vector(where), Metric.focusRing, ig.PointShape.Square, gp = style.focusRing)
              .map(Vector(_))
        glyph <- ig.Grob.points(
          Vector(where),
          Metric.glyph,
          shape,
          gp = if focused then style.focus else style.landmark
        )
        label <- budgetedLabel(id.mark.value, anchor.x, anchor.lane.toDouble, plan, style, row)
      yield (halo +: ring) ++ (glyph +: label)

    // The rings carry the participations; the path between them is drawn dotted because the
    // contract says it asserts nothing, and a solid line would say otherwise.
    case VisualPrimitive.Thread(id, _, points) =>
      val row = plan.situationOffsetPx
      val pts = points.map(p => at(p.x.toDouble, p.lane.toDouble, row))
      for
        line <- ig.Grob.lines(pts, gp = style.thread)
        rings <- pts.traverse(where =>
          ig.Grob
            .points(Vector(where), Metric.threadRing, ig.PointShape.Circle, gp = style.threadRing)
        )
        label <- points.headOption
          .traverse(head =>
            budgetedLabel(
              id.mark.value,
              head.x,
              head.lane.toDouble,
              plan,
              style,
              plan.situationOffsetPx
            )
          )
          .map(_.getOrElse(Vector.empty))
      yield (line +: rings) ++ label

    case VisualPrimitive.Portal(id, from, to, _) =>
      edge(id.mark.value, from, to, style.portal, plan, style)

    // A stored relation edge carries the one epistemic status the scene actually supplies, and it
    // carries it in stroke weight and dash rather than in its label alone (V-U5). No other mark in
    // this scene has a status to draw: see `AtlasPlate.channelNote`.
    case VisualPrimitive.Route(id, from, to, _, status) =>
      edge(id.mark.value, from, to, routeStyle(status, style), plan, style)

    case other =>
      Left(
        GraphicsError.InvalidExtent(
          s"${other.getClass.getSimpleName} carries no context lane and cannot enter the lane plot"
        )
      )

  /** Weight and dash by epistemic status: observed edges are solid and heavy, derived edges
    * lighter, inferred and hypothesised edges broken. The order is the contract's own, not a
    * judgement.
    */
  private def routeStyle(status: EpistemicStatus, style: Style.Params): ig.GraphicParams =
    status match
      case EpistemicStatus.SurfaceExplicit        => style.routeObserved
      case EpistemicStatus.HumanAdjudicated       => style.routeObserved
      case EpistemicStatus.LinguisticallyEntailed => style.route
      case EpistemicStatus.StructurallyDerived    => style.route
      case EpistemicStatus.WorldKnowledgeInferred => style.routeInferred
      case EpistemicStatus.Hypothesized           => style.routeInferred

  private def bandBasis(basis: ContextBandBasis): String = basis match
    case ContextBandBasis.ExactScopeEvidence => "exact scope evidence"

  private def edge(
      markId: String,
      from: Anchor,
      to: Anchor,
      gp: ig.GraphicParams,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val row = plan.situationOffsetPx
    val a = at(from.x.toDouble, from.lane.toDouble, row)
    val b = at(to.x.toDouble, to.lane.toDouble, row)
    for
      line <- ig.Grob.lines(Vector(a, b), gp = gp)
      ends <- Vector(a, b).traverse(where =>
        ig.Grob
          .points(Vector(where), Metric.threadRing, ig.PointShape.Circle, gp = style.threadRing)
      )
      label <- budgetedLabel(
        markId,
        (from.x + to.x) / 2,
        from.lane.toDouble,
        plan,
        style,
        plan.situationOffsetPx
      )
    yield (line +: ends) ++ label

  /** The label the budget accepted for this mark, if any, with a leader from the mark to it.
    *
    * A withheld label is not a hidden mark: the mark is drawn, named and in the twin, and the
    * contract block prints how many labels were withheld.
    */
  private def budgetedLabel(
      markId: String,
      x: Int,
      lane: Double,
      plan: AtlasPlate.Plan,
      style: Style.Params,
      fromPx: Double
  ): Either[GraphicsError, Vector[ig.Grob]] =
    plan.labels.get(markId) match
      case None         => Right(Vector.empty)
      case Some(placed) =>
        val baseline = plan.labelOffsetPx(placed.row)
        val foot = placed.dxPx + (if placed.dxPx < 0.0 then -2.0 else 2.0)
        for
          // Up from the mark, then along the label's own baseline to its edge. A leader that runs
          // through four stacked rows without stopping cannot say which of them it serves; this one
          // ends where its label begins, and nowhere else.
          leader <- ig.Grob.lines(
            Vector(
              at(x.toDouble, lane, fromPx - 6.0),
              at(x.toDouble, lane, baseline + 4.5),
              at(x.toDouble, lane, baseline + 4.5, foot)
            ),
            gp = style.leader
          )
          text <- ig.Grob.text(
            placed.text,
            at(x.toDouble, lane, baseline, placed.dxPx),
            Style.labelAnchor,
            gp = if plan.selected.contains(markId) then style.focusLabel else style.label
          )
        yield Vector(leader, text)

  // ------------------------------------------------------------------ the surface rail

  /** Device placement only: kind is a categorical subrow and y distance has no semantics. */
  private def surfaceBand(kind: SurfaceUnitKind, heightPx: Double): (Double, Double) =
    val row = kind match
      case SurfaceUnitKind.Paragraph => 0
      case SurfaceUnitKind.Sentence  => 1
      case SurfaceUnitKind.Clause    => 2
      case SurfaceUnitKind.Token     => 3
    val top = heightPx * 0.36
    val slot = (heightPx * 0.94 - top) / 4.0
    (top + row * slot, top + row * slot + slot * 0.72)

  private def orderedSurface(
      marks: Vector[VisualPrimitive.SurfaceUnit]
  ): Vector[VisualPrimitive.SurfaceUnit] =
    val kindRank: SurfaceUnitKind => Int =
      case SurfaceUnitKind.Paragraph => 0
      case SurfaceUnitKind.Sentence  => 1
      case SurfaceUnitKind.Clause    => 2
      case SurfaceUnitKind.Token     => 3
    marks.sortBy(mark =>
      (
        mark.span.start,
        mark.span.endExclusive,
        kindRank(mark.kind),
        mark.unitOrdinal,
        mark.identity.mark.value
      )
    )

  private def railViewport(
      plan: AtlasPlate.Plan,
      topPx: Double,
      heightPx: Double,
      clip: ig.Clip
  ): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(plan.x(plan.plotLeftPx), plan.y(topPx + heightPx))
      size <- ig.Size.npc(plan.plotWidthPx / plan.box.widthPx, heightPx / plan.box.heightPx)
      xScale <- ig.Interval(0.0, math.max(plan.discourseLength.toDouble, 1.0))
      yScale <- ig.Interval(0.0, heightPx)
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = clip,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  private def surfaceRail(
      marks: Vector[VisualPrimitive.SurfaceUnit],
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val height = plan.surfaceHeightPx
    for
      viewport <- railViewport(plan, plan.surfaceTopPx, height, ig.Clip.On)
      children <- orderedSurface(marks).traverse { mark =>
        val (y0, y1) = surfaceBand(mark.kind, height)
        for
          name <- GraphicsNames.ofMark(mark.identity.mark)
          // The polygon's own corners stay the exact span; the separation between two adjacent
          // units comes from a paper-coloured hairline, which moves no edge.
          polygon <- ig.Grob.polygon(
            Vector(
              ig.Point(natX(mark.span.start.toDouble), natY(y0)),
              ig.Point(natX(mark.span.endExclusive.toDouble), natY(y0)),
              ig.Point(natX(mark.span.endExclusive.toDouble), natY(y1)),
              ig.Point(natX(mark.span.start.toDouble), natY(y1))
            ),
            gp = style.surfaceUnit
          )
        yield ig.Grob.group(Vector(polygon), name = Some(name))
      }
    yield ig.Grob.group(children, viewport = Some(viewport))

  // ------------------------------------------------------------------ the absence rail

  /** Absence is drawn, never left as a hole in the ink (recovery plan §2.3).
    *
    * A mark that cites spans is drawn at exactly those spans, one rule per span on the packing row
    * its reason group gave it, with its epistemic shape capping the first. A mark with no honest
    * discourse position is drawn in a margin column left of the discourse axis, so it is visible
    * without being placed somewhere it does not belong.
    */
  private def epistemicRail(
      marks: Vector[VisualPrimitive],
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val height = plan.absenceHeightPx
    val rowOf = plan.absence.flatMap(g => g.rowOf.toVector.map((id, r) => id -> (g, r))).toMap
    val marginSlot = plan.absence
      .flatMap(g => g.unplaced.map(id => id -> g))
      .zipWithIndex
      .map { case ((id, g), i) => id -> (g, i) }
      .toMap
    val marginCount = marginSlot.size
    val ordered = marks.sortBy(m => (epistemicSortKey(m), m.identity.mark.value))

    for
      spanViewport <- railViewport(plan, plan.absenceTopPx, height, ig.Clip.Off)
      marginViewport <- marginColumn(plan, height)
      spanned <- ordered
        .filter(m => rowOf.contains(m.identity.mark.value))
        .traverse { mark =>
          val (group, row) = rowOf(mark.identity.mark.value)
          spannedAbsence(mark, group, row, style)
        }
      unplaced <- ordered
        .filter(m => marginSlot.contains(m.identity.mark.value))
        .traverse { mark =>
          val (group, index) = marginSlot(mark.identity.mark.value)
          marginAbsence(mark, group, index, marginCount, style)
        }
    yield ig.Grob.group(
      Vector(
        ig.Grob.group(spanned, viewport = Some(spanViewport)),
        ig.Grob.group(unplaced, viewport = Some(marginViewport))
      )
    )

  private def marginColumn(
      plan: AtlasPlate.Plan,
      heightPx: Double
  ): Either[GraphicsError, ig.Viewport] =
    val left = plan.marginLeftPx
    val width = math.max(plan.marginWidthPx - 12.0, 8.0)
    for
      origin <- ig.Point.npc(plan.x(left), plan.y(plan.absenceTopPx + heightPx))
      size <- ig.Size.npc(width / plan.box.widthPx, heightPx / plan.box.heightPx)
      xScale <- ig.Interval(0.0, 1.0)
      yScale <- ig.Interval(0.0, heightPx)
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  private def rowY(group: AtlasPlate.AbsenceGroup, row: Int): Double =
    group.topPx + Metric.absenceGroupHeadPx + (row + 0.5) * Metric.absenceRowPx

  /** The non-colour channel each epistemic state is drawn in (ADR 0002 D9, V-U5). Four states, four
    * shapes: the state is readable in monochrome and at a glance, and never from a hue.
    */
  private def channelShape(channel: EpistemicChannel): ig.PointShape = channel match
    case EpistemicChannel.OpenHatch   => ig.PointShape.Square
    case EpistemicChannel.Fan         => ig.PointShape.Triangle
    case EpistemicChannel.Placeholder => ig.PointShape.Circle
    case EpistemicChannel.Bracket     => ig.PointShape.Cross

  private def spannedAbsence(
      mark: VisualPrimitive,
      group: AtlasPlate.AbsenceGroup,
      row: Int,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val shape = channelShape(mark.epistemicChannel.getOrElse(EpistemicChannel.Bracket))
    val y = rowY(group, row)
    val spans = mark.epistemicPlacement
      .flatMap(_.spanSet)
      .map(_.spans.toVector)
      .getOrElse(Vector.empty)
      .sortBy(s => (s.start, s.endExclusive))
    for
      name <- GraphicsNames.ofMark(mark.identity.mark)
      rules <- spans.traverse { span =>
        ig.Grob.lines(
          Vector(
            ig.Point(natX(span.start.toDouble), natY(y)),
            ig.Point(natX(span.endExclusive.toDouble), natY(y))
          ),
          gp = style.epistemic
        )
      }
      cap <- spans.headOption.traverse { span =>
        ig.Grob.points(
          Vector(ig.Point(natX(span.start.toDouble), natY(y))),
          Metric.absenceGlyph,
          shape,
          gp = style.epistemic
        )
      }
    yield ig.Grob.group(rules ++ cap.toVector, name = Some(name))

  private def marginAbsence(
      mark: VisualPrimitive,
      group: AtlasPlate.AbsenceGroup,
      index: Int,
      total: Int,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val shape = channelShape(mark.epistemicChannel.getOrElse(EpistemicChannel.Bracket))
    val y = rowY(group, 0)
    val x = (index + 0.5) / math.max(total, 1)
    for
      name <- GraphicsNames.ofMark(mark.identity.mark)
      glyph <- ig.Grob.points(
        Vector(ig.Point(natX(x), natY(y))),
        Metric.absenceGlyph,
        shape,
        gp = style.epistemic
      )
    yield ig.Grob.group(Vector(glyph), name = Some(name))

  /** Spanned marks first, then the margin, so a reader meets the placed evidence before the
    * unplaceable; within each, discourse order. Draw order only.
    */
  private def epistemicSortKey(mark: VisualPrimitive): (Int, Int) =
    mark.epistemicPlacement.flatMap(_.spanSet) match
      case Some(spans) => (0, spans.minSpan.start)
      case None        => (1, 0)

  /** Values, missingness and coverage occupy separate visual channels over exact support. */
  private def featureRail(
      scene: NarrativeScene,
      plan: AtlasPlate.Plan,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val marks = scene.marks.collect { case f: VisualPrimitive.Feature => f }
    val title = marks.headOption.toVector.flatMap { f =>
      val v = f.value
      val domain = v.domain.fold("no observed values")(d => s"${d.minimum} – ${d.maximum}")
      Vector(
        s"${v.space.description} · ${scene.featureLayer.scale.label}",
        s"$domain ${v.space.units.getOrElse("units unspecified")} · × missing · dotted excluded · ${v.circularity}",
        "Coverage below: outlined bar = eligible; fill = observed; dashed = not recorded; circle = no eligible units"
      )
    }
    for
      labels <- title.zipWithIndex.traverse((t, i) =>
        pageText(plan, plan.plotLeftPx, plan.featureTopPx + 12 + i * 13, t, style.fine)
      )
      children <- marks.traverse { mark =>
        val v = mark.value
        val y = plan.featureTopPx + 52 + plan.featureRows(mark.identity.mark.value) * 18.0
        val fraction = v.estimate.toOption.flatMap(n => v.domain.map(_.fraction(n)))
        val intensity = fraction.fold(245)(f => (235.0 - 150.0 * f).round.toInt)
        val missing = v.estimate match
          case storymodel4s.features.Estimate.Missing(r) => Some(r)
          case _                                         => None
        for
          colour <- ig.Rgba(intensity, intensity, intensity)
          ink <- ig.Rgba(45, 55, 52)
          gp <- ig.GraphicParams.checked(
            stroke = Some(ink),
            fill = Some(colour),
            lineType =
              if missing.contains(storymodel4s.features.MissingReason.Excluded) then
                ig.LineType.Dotted
              else ig.LineType.Solid
          )
          pieces <- v.support.spans.toVector.traverse { span =>
            val x0 = plan.xOf(span.start.toDouble)
            val x1 = plan.xOf(span.endExclusive.toDouble)
            for
              box <- pageBox(plan, x0, y, x1, y + 10, gp)
              mask <-
                if missing.isDefined && !missing.contains(
                    storymodel4s.features.MissingReason.Excluded
                  )
                then
                  Vector((x0, y, x1, y + 10), (x0, y + 10, x1, y)).traverse { (a, b, c, d) =>
                    for
                      from <- ig.Point.npc(plan.x(a), plan.y(b))
                      to <- ig.Point.npc(plan.x(c), plan.y(d))
                      line <- ig.Grob.lines(Vector(from, to), gp = style.axisRule)
                    yield line
                  }
                else Right(Vector.empty)
              empty <- ig.GraphicParams.checked(stroke = Some(ink), fill = None)
              unknown <- ig.GraphicParams.checked(
                stroke = Some(ink),
                fill = None,
                lineType = ig.LineType.Dashed
              )
              solid <- ig.GraphicParams.checked(stroke = None, fill = Some(ink))
              coverage <- v.coverage match
                case None => pageRule(plan, x0, x1, y + 14, unknown).map(Vector(_))
                case Some(c) if c.eligible == 0 =>
                  pageGlyph(plan, (x0 + x1) / 2, y + 14, pt(3), ig.PointShape.Circle, empty)
                    .map(Vector(_))
                case Some(c) =>
                  for
                    base <- pageBox(plan, x0, y + 12, x1, y + 16, empty)
                    fill <-
                      if c.observed > 0 then
                        pageBox(
                          plan,
                          x0,
                          y + 12,
                          x0 + (x1 - x0) * c.observed.toDouble / c.eligible,
                          y + 16,
                          solid
                        ).map(Vector(_))
                      else Right(Vector.empty)
                  yield Vector(base) ++ fill
            yield Vector(box) ++ mask ++ coverage
          }
          name <- GraphicsNames.ofMark(mark.identity.mark)
        yield ig.Grob.annotated(
          ig.Grob.group(pieces.flatten, name = Some(name)),
          ig.GrobMeta(title = Some(v.description))
        )
      }
    yield ig.Grob.group(labels ++ children)

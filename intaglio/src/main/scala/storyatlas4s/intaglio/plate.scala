package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError

/** The document box one plate is composed for, in CSS pixels.
  *
  * A plate that does not know its box cannot do typography: a label budget, a lane height and a
  * tick interval are all facts about a physical extent, and a lowering that emits only normalised
  * coordinates has to guess them. So the box is an input, and the same scene composed for a
  * different box is a different, deliberately different, composition.
  */
final case class PlateBox private (widthPx: Double, heightPx: Double)

object PlateBox:

  /** The Discourse Atlas plate box. `EditionSpec` states the same numbers for the SVG document; a
    * caller always passes its own box, and this is the value the lowering laws compose against.
    */
  val default: PlateBox = PlateBox(1600.0, 1080.0)

  def of(widthPx: Int, heightPx: Int): Either[GraphicsError, PlateBox] =
    if widthPx < 640 || heightPx < 480 then
      Left(
        GraphicsError.InvalidExtent(
          s"plate box ${widthPx}x${heightPx}px is smaller than the 640x480px minimum composition"
        )
      )
    else Right(PlateBox(widthPx.toDouble, heightPx.toDouble))

/** The plate's ink, as sRGB channels.
  *
  * Every value here is checked by `PlateContrastSuite`, which computes WCAG 2.2 relative luminance
  * rather than trusting the eye: text inks clear 4.5:1 against the paper, structural inks clear
  * 3:1. Hue is never the carrier of a scientific state — `absence` is paired with the epistemic
  * shape channel and with hatching, so the plate survives greyscale and colour deficiency (ADR 0002
  * V-U5, design brief §11/§12).
  */
private[intaglio] object Ink:
  type Rgb = (Int, Int, Int)

  /** The ground: a warm paper, not white, so hairlines and light fills stay visible. */
  val paper: Rgb = (250, 248, 242)

  /** Body ink: titles, mark labels, landmark glyphs. */
  val ink: Rgb = (20, 20, 15)

  /** Secondary ink: metadata, captions, declared meanings. */
  val muted: Rgb = (74, 74, 68)

  /** Structural strokes: lane separators, band frames, leaders. Never carries text. */
  val rule: Rgb = (142, 142, 134)

  /** The faintest structural stroke: the narrated ground's own frame. */
  val hairline: Rgb = (186, 186, 178)

  /** The one categorical accent, reserved for recorded absence, and never used alone. */
  val absence: Rgb = (122, 83, 16)

  /** Focus. Drawn only when the shared view state actually carries a focus address. */
  val focus: Rgb = (27, 79, 114)

  /** Fill of the narrated-world ground. Dark enough to clear 3:1 against the paper — a band a
    * reader is meant to read the plate against is a meaningful graphical object, not a wash — and
    * light enough that an inked glyph with a paper halo sits on it legibly.
    */
  val ground: Rgb = (138, 134, 124)

/** Type sizes in points. The SVG backend emits `fontSize * ppi / 72` device pixels, so at the 96dpi
  * default a point is 4/3 of a pixel and nothing here falls below 12px.
  *
  * Tiny type is forbidden as a way to simulate analytical density (design brief §15), so the floor
  * is a law (`PlateContrastSuite`), not a habit. Two families carry the distinction the brief asks
  * for: model text is set in a serif, machine strings — checksums, addresses, law names, counts —
  * in a monospace, so a reader can tell an assertion about the story from a fact about the build
  * without reading either.
  */
private[intaglio] object Typeface:
  val prose: String = "Palatino, 'Palatino Linotype', 'Book Antiqua', Georgia, serif"
  val machine: String = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

  val minimumPt: Double = 9.0

  /** Four roles, four sizes, two families. A plate whose type sits inside a 1.1:1 range has no
    * hierarchy at all, whatever its content: the design brief asks that exact text, model labels,
    * metadata and numeric readouts be typographically distinguished, and size alone will not do it,
    * so each role also carries a family and a weight of ink.
    *
    *   - **Title and section**, serif, the only steps above the body.
    *   - **Model text** — a landmark's description, an entity's label, a segment's summary — serif
    *     at reading size. These are assertions about the story.
    *   - **Prose metadata** — captions, declared meanings, budgets — serif, smaller and lighter.
    *   - **Machine strings** — checksums, addresses, law names, lane kinds — monospace.
    *   - **Numeric readouts** — axis ticks, tallies — monospace, so digits align in a column.
    */
  val titlePt: Double = 17.0
  val sectionPt: Double = 11.5
  val labelPt: Double = 10.5
  val laneNamePt: Double = 10.5
  val metaPt: Double = 9.5
  val finePt: Double = 9.5
  val laneKindPt: Double = 9.5
  val machinePt: Double = 9.0
  val numericPt: Double = 9.5
  val axisPt: Double = 9.5

/** Absolute, data-independent geometry of the plate, in points and pixels.
  *
  * Nothing here scales with the model: a label row is a typographic distance, not a fraction of a
  * lane, so a scene with two lanes and a scene with six set their labels at the same size.
  */
private[intaglio] object Metric:
  // Glyph radii, in points. `pointsUnsafe` on a positive finite literal is total; the checked
  // constructor would only move the same constants into an Either with no reader benefit.
  val glyph: ig.ExtentExpr = ig.ExtentExpr.pointsUnsafe(2.6)
  val glyphHalo: ig.ExtentExpr = ig.ExtentExpr.pointsUnsafe(3.4)
  val threadRing: ig.ExtentExpr = ig.ExtentExpr.pointsUnsafe(1.9)
  val absenceGlyph: ig.ExtentExpr = ig.ExtentExpr.pointsUnsafe(2.2)
  val focusRing: ig.ExtentExpr = ig.ExtentExpr.pointsUnsafe(5.6)

  /** Half-height of a context band ribbon, in device pixels. */
  val bandHalfPx: Double = 5.0

  /** A lane's internal composition, measured down from the lane's own top edge.
    *
    * The context-band ribbon and the situation row are separate sub-rows of one lane. Both are
    * exact in x; neither vertical position carries meaning, which the contract already says of the
    * whole y axis (`VisualInvariant.LaneHasNoMetric`). Separating them is what stops 281 narrated
    * extents and 53 situations on one line from reading as a single smear.
    */
  val bandFromLaneBottomPx: Double = 9.0
  val situationFromLaneBottomPx: Double = 42.0
  val labelRowStepPx: Double = 16.0

  /** Situation sub-rows: how far apart, how many at most, and how much clear space a glyph wants
    * before it will share a row with its neighbour.
    */
  val situationRowStepPx: Double = 10.0
  val situationRowsMax: Int = 3
  val situationClearPx: Double = 14.0
  val labelRowLiftPx: Double = 14.0
  val maxLabelRows: Int = 4

  /** Clear space kept between two labels in one row, in device pixels. */
  val labelGapPx: Double = 11.0

  /** Longest label drawn before it is elided; a longer one is elided, never shrunk. */
  val labelMaxPx: Double = 250.0

  // Pixels: the vertical stack.
  val topPadPx: Double = 22.0
  val headerPx: Double = 98.0
  val contractPx: Double = 110.0
  val axisPx: Double = 54.0
  val surfacePx: Double = 58.0
  val legendPx: Double = 66.0
  val bottomPadPx: Double = 16.0
  val laneMinPx: Double = 44.0

  /** The absence rail's own stack. */
  val absenceCaptionPx: Double = 58.0
  val absenceGroupHeadPx: Double = 19.0
  val absenceRowPx: Double = 11.0
  val absenceGroupGapPx: Double = 7.0

  // Pixels: the horizontal frame.
  val gutterPx: Double = 22.0
  val laneNameWidthPx: Double = 152.0
  val marginColumnPx: Double = 92.0
  val rightPadPx: Double = 26.0

/** Fixed graphical parameters of the lowering.
  *
  * Relation type and epistemic status are still rendered as text and shape (ADR 0002 V-U5); colour
  * separates ground from ink and marks one category — recorded absence — that always carries a
  * shape of its own as well.
  */
private[intaglio] object Style:
  private def rgb(c: Ink.Rgb, alpha: Double = 1.0): Either[GraphicsError, ig.Rgba] =
    ig.Rgba(c._1, c._2, c._3, alpha)

  private def font(points: Double): Either[GraphicsError, ig.Length] =
    ig.Length.points(points)

  final case class Params(
      region: ig.GraphicParams,
      run: ig.GraphicParams,
      annotation: ig.GraphicParams,
      band: ig.GraphicParams,
      absenceBand: ig.GraphicParams,
      contextBand: ig.GraphicParams,
      surfaceUnit: ig.GraphicParams,
      landmark: ig.GraphicParams,
      landmarkHalo: ig.GraphicParams,
      tie: ig.GraphicParams,
      focus: ig.GraphicParams,
      focusRing: ig.GraphicParams,
      focusLabel: ig.GraphicParams,
      thread: ig.GraphicParams,
      threadRing: ig.GraphicParams,
      portal: ig.GraphicParams,
      route: ig.GraphicParams,
      routeObserved: ig.GraphicParams,
      routeInferred: ig.GraphicParams,
      epistemic: ig.GraphicParams,
      epistemicRule: ig.GraphicParams,
      label: ig.GraphicParams,
      leader: ig.GraphicParams,
      header: ig.GraphicParams,
      title: ig.GraphicParams,
      section: ig.GraphicParams,
      meta: ig.GraphicParams,
      fine: ig.GraphicParams,
      machine: ig.GraphicParams,
      accent: ig.GraphicParams,
      laneName: ig.GraphicParams,
      laneKind: ig.GraphicParams,
      axis: ig.GraphicParams,
      numeric: ig.GraphicParams,
      axisRule: ig.GraphicParams,
      separator: ig.GraphicParams,
      rule: ig.GraphicParams,
      hairline: ig.GraphicParams,
      ground: ig.GraphicParams,
      /** Hatching for every context frame that is not the narrated world. */
      speechFill: ig.GraphicParams
  )

  val params: Either[GraphicsError, Params] =
    for
      paper <- rgb(Ink.paper)
      ink <- rgb(Ink.ink)
      muted <- rgb(Ink.muted)
      ruleInk <- rgb(Ink.rule)
      hairlineInk <- rgb(Ink.hairline)
      absenceInk <- rgb(Ink.absence)
      groundInk <- rgb(Ink.ground)
      legacyFill <- rgb(Ink.rule, 0.30)
      runFill <- rgb(Ink.ink, 0.25)
      hatch <- ig.PatternRecipe.angledHatch(38.0, 4.2, 0.9)
      cross <- ig.PatternRecipe.crossHatch(45.0, 5.0, 0.7)
      prose = Some(Typeface.prose)
      machineFamily = Some(Typeface.machine)
      region <- ig.GraphicParams.checked(stroke = Some(ruleInk), fill = None, lineWidth = 0.9)
      run <- ig.GraphicParams.checked(stroke = None, fill = Some(runFill))
      annotation <- ig.GraphicParams.checked(stroke = Some(ink), fill = Some(legacyFill))
      // Page-overlay bands are fill-only: stacked in a line box they can be a pixel tall, and a
      // stroke on each would smear into one stripe.
      band <- ig.GraphicParams.checked(stroke = None, fill = Some(legacyFill))
      // A recorded absence underlines its own words in the one accent the plate reserves for it,
      // so a reader sees where the model failed without the prose being painted over.
      absenceBand <- ig.GraphicParams.checked(stroke = None, fill = Some(absenceInk))
      // One extent of the narrated world. Fill-only: 281 of them each carrying a stroke is how the
      // ground became a black smear, and the gaps between them are what must stay visible.
      contextBand <- ig.GraphicParams.checked(stroke = None, fill = Some(groundInk))
      // A surface unit keeps its exact span as its geometry and takes a paper-coloured hairline as
      // its stroke: that is what keeps 286 adjacent sentences a ruler rather than one washed
      // stripe, without moving a single edge.
      surfaceUnit <- ig.GraphicParams.checked(
        stroke = Some(paper),
        fill = Some(groundInk),
        lineWidth = 0.7
      )
      // A context frame that is not the narrated world is hatched as well as framed, so "this is
      // not narration" survives a monochrome print and is never carried by opacity.
      speechBase <- ig.GraphicParams.checked(stroke = Some(ink), fill = None, lineWidth = 0.9)
      speechFill = speechBase.withPatternFill(ig.PatternPaint(hatch, ink, Some(paper)))
      landmark <- ig.GraphicParams.checked(stroke = Some(ink), fill = Some(ink), lineWidth = 0.8)
      // A paper disc under each glyph so a mark on the ground is separable from the ground.
      landmarkHalo <- ig.GraphicParams.checked(stroke = None, fill = Some(paper))
      // The two edges of a context frame, carried up the plot so the discourse interval it occupies
      // is readable against every lane at once. Its x coordinates are the frame's own exact support;
      // its vertical run is in the coordinate the contract already declares metric-free.
      // Dashed and faint, so it is never mistaken for a label leader, which is solid and ends in
      // an elbow at the label it serves.
      tie <- ig.GraphicParams.checked(
        stroke = Some(hairlineInk),
        lineWidth = 0.7,
        lineType = ig.LineType.Dashed
      )
      // The one place saturation is spent. It never carries the state alone: a focused mark is
      // also ringed, so the selection reads in greyscale and under colour deficiency.
      focusInk <- rgb(Ink.focus)
      focus <- ig.GraphicParams.checked(
        stroke = Some(focusInk),
        fill = Some(focusInk),
        lineWidth = 0.8
      )
      focusRing <- ig.GraphicParams.checked(
        stroke = Some(focusInk),
        fill = None,
        lineWidth = 1.3,
        lineType = ig.LineType.Dashed
      )
      thread <- ig.GraphicParams.checked(
        stroke = Some(ruleInk),
        lineWidth = 0.9,
        lineType = ig.LineType.Dotted
      )
      threadRing <- ig.GraphicParams.checked(
        stroke = Some(muted),
        fill = Some(paper),
        lineWidth = 0.9
      )
      portal <- ig.GraphicParams.checked(
        stroke = Some(muted),
        lineWidth = 1.0,
        lineType = ig.LineType.Dashed
      )
      route <- ig.GraphicParams.checked(stroke = Some(muted), lineWidth = 1.0)
      // The one epistemic status the scene supplies per mark, drawn in weight and dash so it
      // survives monochrome. Solid and heavy is observed; the default is derived; broken is
      // inferred or hypothesised.
      routeObserved <- ig.GraphicParams.checked(stroke = Some(ink), lineWidth = 1.8)
      routeInferred <- ig.GraphicParams.checked(
        stroke = Some(muted),
        lineWidth = 1.0,
        lineType = ig.LineType.Dotted
      )
      // Absence marks are unfilled: an open glyph is what distinguishes "we could not say" from a
      // solid landmark that asserts something. The shape carries the state, never a colour (V-U5).
      epistemic <- ig.GraphicParams.checked(
        stroke = Some(absenceInk),
        fill = None,
        lineWidth = 2.0
      )
      epistemicBase <- ig.GraphicParams.checked(
        stroke = Some(absenceInk),
        fill = None,
        lineWidth = 0.7
      )
      epistemicRule = epistemicBase.withPatternFill(
        ig.PatternPaint(cross, absenceInk, Some(paper))
      )
      labelSize <- font(Typeface.labelPt)
      label <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(ink),
        fontSize = labelSize,
        fontFamily = prose
      )
      focusLabel <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(focusInk),
        fontSize = labelSize,
        fontFamily = prose
      )
      // Lighter than a frame tie, which it would otherwise be mistaken for. A leader is
      // redundant — the label already sits beside its mark — so it may be the faintest line
      // on the plate.
      // A leader has to be followable to the label it serves, so it is a stroke a reader can see
      // and it stops at that label's baseline rather than running on through the block.
      leader <- ig.GraphicParams.checked(stroke = Some(ruleInk), lineWidth = 0.8)
      titleSize <- font(Typeface.titlePt)
      title <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(ink),
        fontSize = titleSize,
        fontFamily = prose
      )
      sectionSize <- font(Typeface.sectionPt)
      section <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(ink),
        fontSize = sectionSize,
        fontFamily = prose
      )
      metaSize <- font(Typeface.metaPt)
      meta <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(muted),
        fontSize = metaSize,
        fontFamily = prose
      )
      fineSize <- font(Typeface.finePt)
      fine <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(muted),
        fontSize = fineSize,
        fontFamily = prose
      )
      machineSize <- font(Typeface.machinePt)
      machine <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(muted),
        fontSize = machineSize,
        fontFamily = machineFamily
      )
      accentSize <- font(Typeface.numericPt)
      accent <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(absenceInk),
        fontSize = accentSize,
        fontFamily = machineFamily
      )
      laneNameSize <- font(Typeface.laneNamePt)
      laneName <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(ink),
        fontSize = laneNameSize,
        fontFamily = prose
      )
      laneKindSize <- font(Typeface.laneKindPt)
      laneKind <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(muted),
        fontSize = laneKindSize,
        fontFamily = machineFamily
      )
      axisSize <- font(Typeface.axisPt)
      axis <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(muted),
        fontSize = axisSize,
        fontFamily = machineFamily
      )
      numericSize <- font(Typeface.numericPt)
      numeric <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(ink),
        fontSize = numericSize,
        fontFamily = machineFamily
      )
      axisRule <- ig.GraphicParams.checked(stroke = Some(muted), lineWidth = 0.9)
      separator <- ig.GraphicParams.checked(stroke = Some(hairlineInk), lineWidth = 0.6)
      ruleParams <- ig.GraphicParams.checked(stroke = Some(ruleInk), lineWidth = 0.7)
      hairlineParams <- ig.GraphicParams.checked(stroke = Some(hairlineInk), lineWidth = 0.5)
      ground <- ig.GraphicParams.checked(stroke = None, fill = Some(paper))
      headerSize <- font(Typeface.metaPt)
      header <- ig.GraphicParams.checked(
        stroke = None,
        fill = Some(ink),
        fontSize = headerSize,
        fontFamily = prose
      )
    yield Params(
      region = region,
      run = run,
      annotation = annotation,
      band = band,
      absenceBand = absenceBand,
      contextBand = contextBand,
      surfaceUnit = surfaceUnit,
      landmark = landmark,
      landmarkHalo = landmarkHalo,
      tie = tie,
      focus = focus,
      focusRing = focusRing,
      focusLabel = focusLabel,
      thread = thread,
      threadRing = threadRing,
      portal = portal,
      route = route,
      routeObserved = routeObserved,
      routeInferred = routeInferred,
      epistemic = epistemic,
      epistemicRule = epistemicRule,
      label = label,
      leader = leader,
      header = header,
      title = title,
      section = section,
      meta = meta,
      fine = fine,
      machine = machine,
      accent = accent,
      laneName = laneName,
      laneKind = laneKind,
      axis = axis,
      numeric = numeric,
      axisRule = axisRule,
      separator = separator,
      rule = ruleParams,
      hairline = hairlineParams,
      ground = ground,
      speechFill = speechFill
    )

  /** Plot area inside the page: the root frame is y-up npc; the plot frame is y-down native.
    *
    * Kept for the Codex overlay, whose row plot is still a whole-page frame.
    */
  def plotViewport(xUpper: Double, yUpper: Double): Either[GraphicsError, ig.Viewport] =
    for
      origin <- ig.Point.npc(0.06, 0.22)
      size <- ig.Size.npc(0.90, 0.62)
      xScale <- ig.Interval(0.0, math.max(xUpper, 1.0))
      yScale <- ig.Interval(0.0, math.max(yUpper, 1.0))
      viewport <- ig.Viewport.checked(
        origin = origin,
        size = size,
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield viewport

  val labelAnchor: ig.Anchor = ig.Anchor(ig.HJust.Left, ig.VJust.Center)
  val rowLabelAnchor: ig.Anchor = ig.Anchor(ig.HJust.Right, ig.VJust.Center)
  val centreAnchor: ig.Anchor = ig.Anchor(ig.HJust.Center, ig.VJust.Center)

/** Measured text, so a label budget is decided rather than hoped for.
  *
  * The estimate is Intaglio's own, shared with its plot layout solver, and is linear in character
  * count: the width of one character at a size is therefore exact under it, and a fit is a
  * calculation rather than a guess.
  */
private[intaglio] object Measure:
  private val pxPerPoint: Double = 96.0 / 72.0

  def widthPx(text: String, fontPt: Double): Double =
    ig.TextMetrics.estimate.widthPt(text, fontPt) * pxPerPoint

  def charPx(fontPt: Double): Double = widthPx("n", fontPt)

  /** `text` if it fits `maxPx`, an elided prefix if a useful one does, otherwise nothing.
    *
    * Eliding is honest and shrinking is not: the design brief forbids tiny type as a way to
    * simulate density, so a label that does not fit loses characters, never points.
    */
  def elide(text: String, maxPx: Double, fontPt: Double): Option[String] =
    if widthPx(text, fontPt) <= maxPx then Some(text)
    else
      val budget = math.floor(maxPx / charPx(fontPt)).toInt - 1
      if budget < 8 then None else Some(text.take(budget) + "…")

  /** `text` if it fits, otherwise its head and its tail with the middle removed.
    *
    * Cutting the end is the wrong cut for almost everything this plate labels. An identity's
    * discriminating characters are its last ones (`c-entity:b679d64ced89`), and a model label's
    * qualifiers are at the end (`go man (custom(amr,purpose): help)`), so an end-elision takes
    * exactly what tells one label from another. Removing the middle keeps both ends.
    */
  /** `text` broken into lines that fit `maxPx`, at its own separators.
    *
    * A statement the plate owes a reader is not a candidate for elision: it has to be readable in
    * full. So a long one wraps at the separators it was written with, and only falls back to word
    * breaks when a single clause is itself too long.
    */
  def wrap(text: String, maxPx: Double, fontPt: Double): Vector[String] =
    val clauses = text.split(" · ").toVector
    val lines = clauses.foldLeft(Vector.empty[String]) { (acc, clause) =>
      acc.lastOption match
        case Some(line) if widthPx(s"$line · $clause", fontPt) <= maxPx =>
          acc.init :+ s"$line · $clause"
        case _ => acc :+ clause
    }
    lines.flatMap(line =>
      if widthPx(line, fontPt) <= maxPx then Vector(line) else words(line, maxPx, fontPt)
    )

  private def words(line: String, maxPx: Double, fontPt: Double): Vector[String] =
    line.split(" ").toVector.foldLeft(Vector.empty[String]) { (acc, word) =>
      acc.lastOption match
        case Some(head) if widthPx(s"$head $word", fontPt) <= maxPx => acc.init :+ s"$head $word"
        case _                                                      => acc :+ word
    }

  def elideMiddle(text: String, maxPx: Double, fontPt: Double): Option[String] =
    if widthPx(text, fontPt) <= maxPx then Some(text)
    else
      val budget = math.floor(maxPx / charPx(fontPt)).toInt - 1
      if budget < 10 then None
      else
        val head = (budget + 1) / 2
        val tail = budget - head
        Some(text.take(head) + "…" + text.takeRight(tail))

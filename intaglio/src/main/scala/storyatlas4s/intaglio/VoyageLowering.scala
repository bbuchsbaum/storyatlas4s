package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** Pure lowering `VoyageScene → intaglio.Scene` (ADR 0002 §14 D4).
  *
  * It draws exactly the marks the compiler placed: a unit anchor as a point whose area is the
  * anchor's posterior mass, its origin as shape (circle for the posterior argmax, diamond for a
  * decode-bound anchor, hollow dashed diamond for a decode-filled one), a group-level anchor as the
  * group's span, an external-dominant unit hollow, an unanchored unit on the absence rail, and an
  * untimed unit in the margin row with its reason. Coded intervals from the independent coding are
  * bands; they are not marks and carry no name. Every mark's `data-name` is its `MarkId`.
  *
  * Every mark also carries intaglio metadata that survives every backend: a title (the unit's words
  * and the row's numbers, so a static plate has native tooltips), a class naming its kind and
  * origin (so a stylesheet can restyle by meaning, never by colour), and the unit's ordinal as
  * data. The metadata restates the mark; it never adds a fact the mark does not carry.
  *
  * Nothing here decides anything: which units show their alternatives, whether the ghosts of moved
  * argmaxes are drawn, and how wide the plate is are inputs, because they are the shell's choices,
  * never the lowering's judgement.
  */
object VoyageLowering:

  /** Pixel geometry of the plate; the defaults are the numbers the owner's reference page uses. */
  final case class Box(
      width: Int,
      left: Int,
      right: Int,
      top: Int,
      bottom: Int,
      plotHeight: Int,
      trackHeight: Int,
      gap: Int
  ):
    def plotWidth: Int = width - left - right
    def height: Int = top + plotHeight + gap + trackHeight + bottom
    def trackTop: Int = top + plotHeight + gap

  object Box:
    val default: Box = Box(
      width = 1100,
      left = 62,
      right = 132,
      top = 14,
      bottom = 28,
      plotHeight = 540,
      trackHeight = 110,
      gap = 26
    )

  /** The classes the lowering attaches (intaglio `GrobMeta`), so a stylesheet and a test can name a
    * mark by what it is.
    */
  object Classes:
    val mark = "voyage-mark"
    val anchor = "voyage-anchor"
    val alternative = "voyage-alt"
    val unanchored = "voyage-unanchored"
    val untimed = "voyage-untimed"
    val ghost = "voyage-ghost"
    val coding = "voyage-coding"
    val track = "voyage-track"
    val externalDominant = "external-dominant"
    val groupLevel = "level-group"
    def origin(o: AnchorOrigin): String = o match
      case AnchorOrigin.PosteriorArgmax => "origin-argmax"
      case AnchorOrigin.DecodeBound     => "origin-bound"
      case AnchorOrigin.DecodeFilled    => "origin-filled"

  /** Light-instrument palette: literal because intaglio paints literal colour; the shell's CSS may
    * restyle by class, but the edition must stand on its own.
    */
  private object Palette:
    val model = ig.Rgba.unsafe(0x1b, 0x7f, 0xa3)
    val gold = ig.Rgba.unsafe(0xc9, 0x92, 0x2e)
    val goldBand = ig.Rgba.unsafe(0xc9, 0x92, 0x2e, 0.17)
    val goldLine = ig.Rgba.unsafe(0xc9, 0x92, 0x2e, 0.3)
    val external = ig.Rgba.unsafe(0x8a, 0x93, 0x9d)
    val raw = ig.Rgba.unsafe(0x9a, 0xa3, 0xab)
    val ghostLine = ig.Rgba.unsafe(0x9a, 0xa3, 0xab, 0.45)
    val ghostDot = ig.Rgba.unsafe(0x9a, 0xa3, 0xab, 0.7)
    val ink2 = ig.Rgba.unsafe(0x4b, 0x56, 0x5f)
    val hair = ig.Rgba.unsafe(0xd5, 0xda, 0xd8)
    val surface = ig.Rgba.unsafe(0xff, 0xff, 0xff)

  private def params(
      stroke: Option[ig.Rgba],
      fill: Option[ig.Rgba],
      width: Double = 1.0,
      line: ig.LineType = ig.LineType.Solid,
      alpha: Double = 1.0,
      fontPx: Double = 10.5
  ): Either[GraphicsError, ig.GraphicParams] =
    ig.Length
      .points(fontPx * 0.75)
      .flatMap(size =>
        ig.GraphicParams.checked(
          stroke = stroke,
          fill = fill,
          lineWidth = width,
          lineType = line,
          alpha = alpha,
          fontFamily = Some("IBM Plex Mono, Menlo, monospace"),
          fontSize = size
        )
      )

  /** Presentation-neutral metadata on a grob: a title for native tooltips, a class for meaning,
    * data for the unit. Titles are text a person wrote (the recall), so code points outside the XML
    * character set (control characters, lone surrogates, U+FFFE/FFFF) are dropped rather than
    * failing the plate.
    */
  private def annotated(
      grob: ig.Grob,
      title: Option[String],
      classes: String,
      data: (String, String)*
  ): Either[GraphicsError, ig.Grob] =
    for
      cls <- ig.CssClass(classes)
      keys <- data.toVector.traverse { case (k, v) => ig.DataKey(k).map(_ -> v) }
    yield ig.Grob.annotated(
      grob,
      ig.GrobMeta(title = title.map(xmlText), cssClass = Some(cls), data = keys)
    )

  private def xmlText(s: String): String =
    val out = new java.lang.StringBuilder
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      val legal = cp == 0x9 || cp == 0xa || cp == 0xd ||
        (cp >= 0x20 && cp <= 0xd7ff) || (cp >= 0xe000 && cp <= 0xfffd) ||
        (cp >= 0x10000 && cp <= 0x10ffff)
      if legal then out.appendCodePoint(cp)
      i += Character.charCount(cp)
    out.toString

  /** The whole plate in device pixels, y down; every coordinate below is a pixel. */
  private def viewport(box: Box): Either[GraphicsError, ig.Viewport] =
    for
      xScale <- ig.Interval(0.0, box.width.toDouble)
      yScale <- ig.Interval(0.0, box.height.toDouble)
      vp <- ig.Viewport.checked(
        xScale = xScale,
        yScale = yScale,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
    yield vp

  private def px(x: Double, y: Double): ig.Point = ig.Point.nativeUnsafe(x, y)

  /** Pixel scales of the two clocks. */
  final case class Scales(box: Box, recallLength: Double, sourceEnd: Double):
    def x(t: Double): Double = box.left + box.plotWidth * (t / math.max(recallLength, 1e-9))
    def y(s: Double): Double =
      box.top + box.plotHeight - box.plotHeight * (s / math.max(sourceEnd, 1e-9))
    def track(group: Int, groupCount: Int): Double =
      box.trackTop + box.trackHeight - box.trackHeight * ((group - 0.5) / math.max(groupCount, 1))

  def scales(scene: VoyageScene, box: Box): Scales =
    Scales(box, scene.recallLength.value, scene.timeline.end)

  /** Radius in pixels for a mass: area grows with mass, and a zero-mass anchor keeps a legible
    * hollow shape rather than vanishing.
    */
  def radius(mass: Double): Double = 2.6 + 8.0 * math.sqrt(math.max(0.0, mass))

  /** The fixed radius of a decode-filled mark: its mass is zero by definition, so no area encodes
    * it.
    */
  val filledRadius: Double = 4.5

  /** Lower a scene. `alternativesFor` names the units whose posterior columns are drawn in full;
    * `contextFor` names units whose columns are drawn faint, as context behind the focused ones;
    * `ghosts` says whether a decode-moved anchor shows the posterior argmax it left; `box` is the
    * plate.
    */
  def lower(
      scene: VoyageScene,
      alternativesFor: Set[RecallUnitId] = Set.empty,
      box: Box = Box.default,
      ghosts: Boolean = true,
      contextFor: Set[RecallUnitId] = Set.empty
  ): Either[GraphicsError, ig.Scene] =
    val sc = scales(scene, box)
    val words = Words(scene)
    for
      vp <- viewport(box)
      ground <- groundLayer(scene, sc, vp)
      bands <- codingLayer(scene, sc, vp, words)
      links <- linkLayer(scene, sc, vp, alternativesFor, contextFor, ghosts, words)
      marks <- markLayer(scene, sc, vp, words)
      track <- trackLayer(scene, sc, vp)
    yield ig.Scene(Vector(ground, bands, links, marks, track))

  // ------------------------------------------------------------------ titles

  /** What a title may say: only what the scene already holds, looked up once. */
  private final case class Words(scene: VoyageScene):
    private val units = scene.units.map(u => u.id -> u).toMap
    def text(id: RecallUnitId): String = units.get(id).map(_.text).getOrElse("")
    def ordinal(id: RecallUnitId): Int = units.get(id).map(_.ordinal).getOrElse(-1)
    def node(ref: storymodel4s.align.SourceNodeRef): String =
      scene.timeline.node(ref).map(_.label).getOrElse(ref.key)
    def group(g: Option[Int]): String =
      g.flatMap(scene.timeline.byGroup.get).map(_.label).getOrElse("no group")

    def anchor(m: VoyageMark.UnitAnchor): String =
      val coded = scene
        .codedGroupAt(m.at)
        .fold("")(g =>
          s"\ncoded here: ${group(Some(g))}${if m.group.contains(g) then " ✓" else " ≠"}"
        )
      val moved =
        if m.origin == AnchorOrigin.PosteriorArgmax then ""
        else m.argmax.fold("")(r => s" · the posterior argmax was ${node(r)}")
      f"“${text(m.unit)}”\nunit ${m.unitOrdinal} · ${clock(m.at.value)} into the recall → ${node(m.anchor)} · ${group(m.group)} (${clock(m.span.start.value)}–${clock(m.span.end.value)})\nanchor mass ${m.mass}%.2f · source ${m.sourceMass}%.2f · external ${m.externalMass}%.2f · ${m.origin.label}$moved$coded"

    def alternative(a: VoyageMark.Alternative): String =
      f"alternative ${a.rank} for unit ${ordinal(a.unit)}: ${node(a.anchor)} · ${group(a.group)} · mass ${a.mass}%.2f"

    def ghost(m: VoyageMark.UnitAnchor, argmax: SourceTimelineNode): String =
      s"unit ${m.unitOrdinal}: the posterior argmax was ${argmax.label} (${group(argmax.group)}); the decode drew ${node(m.anchor)}"

    def unanchored(m: VoyageMark.Unanchored): String =
      f"“${text(m.unit)}”\nunit ${m.unitOrdinal} · ${clock(m.at.value)} into the recall → anchored nowhere in the source · external ${m.externalMass}%.2f"

    def untimed(m: VoyageMark.Untimed): String =
      s"“${text(m.unit)}”\nunit ${m.unitOrdinal}: no recall onset, so no place on the recall clock"

    def coded(iv: CodedInterval): String =
      s"independent coding: ${group(Some(iv.group))} · ${clock(iv.recall.start.value)}–${clock(iv.recall.end.value)} of the recall"

  // ------------------------------------------------------------------ layers

  private def groundLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val groups = scene.timeline.groups.sortBy(_.ordinal)
    for
      hair <- params(Some(Palette.hair), None)
      goldLine <- params(Some(Palette.goldLine), None, width = 0.6)
      label <- params(None, Some(Palette.ink2))
      boundaries <- groups.traverse { g =>
        val y = sc.y(g.span.start.value)
        ig.Grob.lines(Vector(px(box.left, y), px(box.width - box.right, y)), gp = goldLine)
      }
      groupLabels <- groups
        .filter(g => sc.y(g.span.start.value) - sc.y(g.span.end.value) >= 11.0)
        .traverse { g =>
          val text = if g.label.length > 19 then g.label.take(18) + "…" else g.label
          ig.Grob.text(
            text,
            px(box.width - box.right + 6, sc.y(g.span.midpoint) + 3.5),
            ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
            gp = label
          )
        }
      yAxis <- ig.Grob.lines(
        Vector(px(box.left, box.top), px(box.left, box.top + box.plotHeight)),
        gp = hair
      )
      xAxis <- ig.Grob.lines(
        Vector(
          px(box.left, box.top + box.plotHeight),
          px(box.width - box.right, box.top + box.plotHeight)
        ),
        gp = hair
      )
      yTicks <- ticks(0.0, sc.sourceEnd, 300.0).traverse { s =>
        ig.Grob.lines(Vector(px(box.left - 4, sc.y(s)), px(box.left, sc.y(s))), gp = hair)
      }
      yLabels <- ticks(0.0, sc.sourceEnd, 300.0).traverse { s =>
        ig.Grob.text(
          clock(s),
          px(box.left - 7, sc.y(s) + 3.5),
          ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
          gp = label
        )
      }
      xTicks <- ticks(0.0, sc.recallLength, 60.0).traverse { t =>
        ig.Grob.lines(
          Vector(px(sc.x(t), box.top + box.plotHeight), px(sc.x(t), box.top + box.plotHeight + 4)),
          gp = hair
        )
      }
      xLabels <- ticks(0.0, sc.recallLength, 300.0).traverse { t =>
        ig.Grob.text(
          clock(t),
          px(sc.x(t), box.top + box.plotHeight + 16),
          ig.Anchor(ig.HJust.Center, ig.VJust.Bottom),
          gp = label
        )
      }
      axisNames <- Vector(
        ig.Grob.text(
          "source",
          px(12, box.top + 10),
          ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
          gp = label
        ),
        ig.Grob.text(
          "recall time",
          px(box.width - box.right, box.top + box.plotHeight + 16),
          ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
          gp = label
        ),
        ig.Grob.text(
          "group",
          px(box.left + 6, box.trackTop + 10),
          ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
          gp = label
        )
      ).sequence
      layer = ig.Grob.group(
        boundaries ++ groupLabels ++ Vector(
          yAxis,
          xAxis
        ) ++ yTicks ++ yLabels ++ xTicks ++ xLabels ++ axisNames,
        viewport = Some(vp)
      )
    yield layer

  private def codingLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport,
      words: Words
  ): Either[GraphicsError, ig.Grob] =
    val intervals = scene.coding.map(_.intervals).getOrElse(Vector.empty)
    for
      band <- params(None, Some(Palette.goldBand))
      rects <- intervals.traverse { iv =>
        scene.timeline.byGroup.get(iv.group) match
          case None    => Right(ig.Grob.group(Vector.empty))
          case Some(g) =>
            val x0 = sc.x(iv.recall.start.value)
            // a coding may run past the last word (its coder heard the recording end); clip
            val x1 = math.min(sc.x(iv.recall.end.value), sc.box.width - sc.box.right)
            val y0 = sc.y(g.span.end.value)
            val y1 = sc.y(g.span.start.value)
            ig.Grob
              .polygon(Vector(px(x0, y0), px(x1, y0), px(x1, y1), px(x0, y1)), band)
              .flatMap(
                annotated(_, Some(words.coded(iv)), Classes.coding, "group" -> iv.group.toString)
              )
      }
      layer = ig.Grob.group(rects, viewport = Some(vp))
    yield layer

  private def linkLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport,
      alternativesFor: Set[RecallUnitId],
      contextFor: Set[RecallUnitId],
      ghosts: Boolean,
      words: Words
  ): Either[GraphicsError, ig.Grob] =
    val anchors = scene.marks.collect { case m: VoyageMark.UnitAnchor => m }
    val alternatives = scene.marks.collect {
      case m: VoyageMark.Alternative
          if alternativesFor.contains(m.unit) || contextFor.contains(m.unit) =>
        m
    }
    val anchorOf = anchors.map(m => m.unit -> m).toMap
    val moved =
      if !ghosts then Vector.empty
      else
        anchors
          .filter(m => m.origin != AnchorOrigin.PosteriorArgmax)
          .flatMap(m => m.argmax.flatMap(scene.timeline.node).map(n => m -> n))
    for
      ghostLine <- params(Some(Palette.ghostLine), None, width = 0.7)
      ghostDot <- params(None, Some(Palette.ghostDot))
      altLine <- params(Some(Palette.model), None, width = 0.8, alpha = 0.7)
      altRing <- params(Some(Palette.model), None, width = 1.2, line = ig.LineType.Dashed)
      // context columns are the same marks, drawn so faint that the focused column still leads
      faintLine <- params(Some(Palette.model), None, width = 0.6, alpha = 0.22)
      faintRing <-
        params(Some(Palette.model), None, width = 0.9, line = ig.LineType.Dashed, alpha = 0.3)
      ghostGrobs <- moved.traverse { case (m, n) =>
        val x = sc.x(m.at.value)
        for
          line <- ig.Grob.lines(
            Vector(px(x, sc.y(n.span.midpoint)), px(x, sc.y(m.span.midpoint))),
            gp = ghostLine
          )
          dot <- ig.Grob.points(
            Vector(px(x, sc.y(n.span.midpoint))),
            ig.ExtentExpr.nativeUnsafe(2.0),
            ig.PointShape.Circle,
            ghostDot
          )
          g <- annotated(
            ig.Grob.group(Vector(line, dot)),
            Some(words.ghost(m, n)),
            Classes.ghost,
            "unit" -> m.unitOrdinal.toString
          )
        yield g
      }
      alts <- alternatives.traverse { a =>
        anchorOf.get(a.unit) match
          case None    => Right(ig.Grob.group(Vector.empty))
          case Some(m) =>
            val x = sc.x(m.at.value)
            val focused = alternativesFor.contains(a.unit)
            for
              name <- GraphicsNames.ofMark(a.identity.mark)
              line <- ig.Grob.lines(
                Vector(px(x, sc.y(a.span.midpoint)), px(x, sc.y(m.span.midpoint))),
                gp = if focused then altLine else faintLine
              )
              ring <- ig.Grob.points(
                Vector(px(x, sc.y(a.span.midpoint))),
                ig.ExtentExpr.nativeUnsafe(radius(a.mass)),
                ig.PointShape.Circle,
                if focused then altRing else faintRing
              )
              g <- annotated(
                ig.Grob.group(Vector(line, ring), name = Some(name)),
                Some(words.alternative(a)),
                s"${Classes.mark} ${Classes.alternative}" + (if focused then "" else " context"),
                "unit" -> words.ordinal(a.unit).toString,
                "rank" -> a.rank.toString
              )
            yield g
      }
      layer = ig.Grob.group(ghostGrobs ++ alts, viewport = Some(vp))
    yield layer

  private def markLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport,
      words: Words
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val untimed = scene.marks.collect { case m: VoyageMark.Untimed => m }
    for
      grobs <- scene.marks.traverse {
        case m: VoyageMark.UnitAnchor =>
          val x = sc.x(m.at.value)
          val cy = sc.y(m.span.midpoint)
          // external-dominant is the compiler's fact about the row, read from the mark (V-U5)
          val externalDominant = m.externalDominant
          val classes = Vector(
            Classes.mark,
            Classes.anchor,
            Classes.origin(m.origin)
          ) ++ Option.when(externalDominant)(Classes.externalDominant) ++
            Option.when(m.level > 0)(Classes.groupLevel)
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            grob <-
              if m.level > 0 then
                // a group-level anchor spans its whole group; its width carries the mass, its
                // outline the origin and the external share, exactly as a point does
                val half = radius(m.mass) * 0.6
                val hollow = externalDominant || m.origin == AnchorOrigin.DecodeFilled
                params(
                  if hollow then Some(if externalDominant then Palette.external else Palette.model)
                  else Some(Palette.surface),
                  if hollow then None else Some(Palette.model),
                  width = if hollow then 1.4 else 1.0,
                  line =
                    if m.origin == AnchorOrigin.DecodeFilled then ig.LineType.Dashed
                    else ig.LineType.Solid
                ).flatMap(gp =>
                  ig.Grob.polygon(
                    Vector(
                      px(x - half, sc.y(m.span.end.value)),
                      px(x + half, sc.y(m.span.end.value)),
                      px(x + half, sc.y(m.span.start.value)),
                      px(x - half, sc.y(m.span.start.value))
                    ),
                    gp,
                    name = Some(name)
                  )
                )
              else
                m.origin match
                  case AnchorOrigin.DecodeFilled =>
                    params(Some(Palette.model), None, width = 1.4, line = ig.LineType.Dashed)
                      .flatMap(gp => point(x, cy, filledRadius, ig.PointShape.Diamond, gp, name))
                  case AnchorOrigin.DecodeBound =>
                    val fill = if externalDominant then None else Some(Palette.model)
                    val stroke =
                      if externalDominant then Some(Palette.external) else Some(Palette.surface)
                    params(stroke, fill, width = if externalDominant then 1.6 else 1.2)
                      .flatMap(gp => point(x, cy, radius(m.mass), ig.PointShape.Diamond, gp, name))
                  case AnchorOrigin.PosteriorArgmax =>
                    val fill = if externalDominant then None else Some(Palette.model)
                    val stroke =
                      if externalDominant then Some(Palette.external) else Some(Palette.surface)
                    params(stroke, fill, width = if externalDominant then 1.6 else 1.2)
                      .flatMap(gp => point(x, cy, radius(m.mass), ig.PointShape.Circle, gp, name))
            titled <- annotated(
              grob,
              Some(words.anchor(m)),
              classes.mkString(" "),
              "unit" -> m.unitOrdinal.toString,
              "origin" -> Classes.origin(m.origin).stripPrefix("origin-")
            )
          yield titled
        case a: VoyageMark.Alternative =>
          // alternatives are drawn by the link layer only for the units the shell names
          GraphicsNames.ofMark(a.identity.mark).map(_ => ig.Grob.group(Vector.empty))
        case m: VoyageMark.Unanchored =>
          // the absence rail, just below the plot: a cross, hollow, at the unit's recall time
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            gp <- params(Some(Palette.external), None, width = 1.4)
            // a cross lowers to two strokes, and each would carry the name; the group carries it once
            cross <- ig.Grob.points(
              Vector(px(sc.x(m.at.value), box.top + box.plotHeight + 8)),
              ig.ExtentExpr.nativeUnsafe(3.5),
              ig.PointShape.Cross,
              gp
            )
            g = ig.Grob.group(Vector(cross), name = Some(name))
            titled <- annotated(
              g,
              Some(words.unanchored(m)),
              s"${Classes.mark} ${Classes.unanchored}",
              "unit" -> m.unitOrdinal.toString
            )
          yield titled
        case m: VoyageMark.Untimed =>
          // the margin row: no honest recall position, so a stated reason in the margin
          val row = untimed.indexWhere(_.identity.mark == m.identity.mark)
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            gp <- params(None, Some(Palette.ink2))
            g <- ig.Grob.text(
              s"unit ${m.unitOrdinal}: no recall onset",
              px(box.left + 4, box.top + 24 + 12 * row),
              ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
              gp = gp,
              name = Some(name)
            )
            titled <- annotated(
              g,
              Some(words.untimed(m)),
              s"${Classes.mark} ${Classes.untimed}",
              "unit" -> m.unitOrdinal.toString
            )
          yield titled
      }
      layer = ig.Grob.group(grobs, viewport = Some(vp))
    yield layer

  private def trackLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val groupCount = scene.timeline.groups.size
    val anchors = scene.marks.collect { case m: VoyageMark.UnitAnchor => m }.sortBy(_.at.value)
    val intervals = scene.coding.map(_.intervals).getOrElse(Vector.empty)
    def step(groupOf: VoyageMark.UnitAnchor => Option[Int], gp: ig.GraphicParams) =
      anchors.zipWithIndex.flatMap { case (m, i) =>
        groupOf(m).map { g =>
          val x0 = sc.x(m.at.value)
          val x1 = anchors
            .lift(i + 1)
            .map(n => sc.x(n.at.value))
            .getOrElse(sc.x(math.min(sc.recallLength, m.at.value + 8)))
          val y = sc.track(g, groupCount)
          ig.Grob.lines(Vector(px(x0, y), px(x1, y)), gp = gp)
        }
      }.sequence
    for
      hair <- params(Some(Palette.hair), None)
      label <- params(None, Some(Palette.ink2))
      // three encodings that survive monochrome: the coding is a thick band, the placement a thin
      // solid line, the argmax a dashed one; colour restates, never carries
      goldGp <- params(Some(Palette.gold), None, width = 4.0, alpha = 0.6)
      modelGp <- params(Some(Palette.model), None, width = 1.6)
      rawGp <- params(Some(Palette.raw), None, width = 1.2, line = ig.LineType.Dashed)
      axis <- ig.Grob.lines(
        Vector(px(box.left, box.trackTop), px(box.left, box.trackTop + box.trackHeight)),
        gp = hair
      )
      tickLabels <- Vector(
        1,
        groupCount / 5,
        2 * groupCount / 5,
        3 * groupCount / 5,
        4 * groupCount / 5,
        groupCount
      )
        .filter(_ >= 1)
        .distinct
        .traverse(g =>
          ig.Grob.text(
            g.toString,
            px(box.left - 7, sc.track(g, groupCount) + 3.5),
            ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
            gp = label
          )
        )
      gold <- intervals.traverse(iv =>
        ig.Grob.lines(
          Vector(
            px(sc.x(iv.recall.start.value), sc.track(iv.group, groupCount)),
            px(
              math.min(sc.x(iv.recall.end.value), sc.box.width - sc.box.right),
              sc.track(iv.group, groupCount)
            )
          ),
          gp = goldGp
        )
      )
      raw <- step(m => m.argmax.flatMap(scene.timeline.node).flatMap(_.group), rawGp)
      model <- step(_.group, modelGp)
      goldTrack <- annotated(ig.Grob.group(gold), None, s"${Classes.track} track-coding")
      rawTrack <- annotated(ig.Grob.group(raw), None, s"${Classes.track} track-argmax")
      modelTrack <- annotated(ig.Grob.group(model), None, s"${Classes.track} track-placement")
      layer = ig.Grob.group(
        Vector(axis) ++ tickLabels ++ Vector(goldTrack, rawTrack, modelTrack),
        viewport = Some(vp)
      )
    yield layer

  // ------------------------------------------------------------------ helpers

  /** One named point mark; `size` is intaglio's point size, the device radius; a diamond of that
    * size has the circle's area.
    */
  private def point(
      x: Double,
      cy: Double,
      size: Double,
      shape: ig.PointShape,
      gp: ig.GraphicParams,
      name: ig.GraphicsName
  ): Either[GraphicsError, ig.Grob] =
    ig.Grob.points(
      Vector(px(x, cy)),
      ig.ExtentExpr.nativeUnsafe(size),
      shape,
      gp,
      name = Some(name)
    )

  private def ticks(from: Double, to: Double, step: Double): Vector[Double] =
    if to <= from then Vector(from)
    else Vector.iterate(from, ((to - from) / step).toInt + 1)(_ + step)

  /** `m:ss` on a clock, as the reference page prints it. */
  def clock(seconds: Double): String =
    val whole = math.round(seconds)
    val m = whole / 60
    val s = whole % 60
    s"$m:${if s < 10 then "0" else ""}$s"

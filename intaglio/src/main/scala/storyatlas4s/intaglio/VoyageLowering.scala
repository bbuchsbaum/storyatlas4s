package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storymodel4s.core.Score
import storymodel4s.align.SourceNodeRef
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** Pure lowering `VoyageScene → intaglio.Scene` (ADR 0002 §14 D4).
  *
  * It draws exactly the marks the compiler placed. A unit anchor is a container glyph (the Recall
  * Voyage workshop rulings, `docs/delivery/voyage-workshop`): a fixed outline that stands for mass
  * 1.0, an inner mark whose area is the anchor's posterior mass (no floor), and the anchor's exact
  * source extent as a rule (heavier for a group-level anchor). Origin is shape: circle for the
  * posterior argmax, diamond of equal area for a decode-bound anchor, an empty dashed diamond for a
  * decode-filled one (its mass is zero). An external-dominant unit has a hollow inner mark and a
  * second outline, so the flag reads at any mass. An unanchored unit sits on the labelled row above
  * the plot, and an untimed unit in the margin row with its reason. Coded intervals from the
  * independent coding are bands; they are not marks and carry no name. Every mark's `data-name` is
  * its `MarkId`.
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

  /** The historical group track remains the publication default. Masses is a display of the
    * compiler's two supplied quantities, not a new projection or a normalization.
    */
  enum Track:
    case Groups, Masses, GroupDisagreements

  /** Pixel geometry of the plate; the defaults are the numbers the owner's reference page uses. */
  final case class Box(
      width: Int,
      left: Int,
      right: Int,
      top: Int,
      bottom: Int,
      plotHeight: Int,
      trackHeight: Int,
      gap: Int,
      gutter: Int = 0
  ):
    /** The plot's width; a gutter (the selected unit's admitted anchors) sits between it and the
      * scene rail.
      */
    def plotWidth: Int = width - left - right - gutter
    def plotRight: Int = left + plotWidth
    def railLeft: Int = width - right
    def height: Int = top + plotHeight + gap + trackHeight + bottom
    def trackTop: Int = top + plotHeight + gap

  object Box:
    val default: Box = Box(
      width = 1100,
      left = 62,
      right = 196,
      top = 34,
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
    val groupLabel = "voyage-group-label"
    val groupRange = "voyage-group-range"
    val gutter = "voyage-gutter"
    val matched = "matched"
    val unmatched = "unmatched"
    val matchTick = "voyage-match-tick"
    val caption = "voyage-caption"
    val gutterLabels = "voyage-gutter-labels"
    val gutterLeader = "voyage-gutter-leader"
    val groupBand = "voyage-group-band"
    val mass = "voyage-mass"
    val massAnchor = "mass-anchor"
    val massExternal = "mass-external"
    val massZero = "mass-zero"
    val massUnavailable = "mass-unavailable"
    val groupComparison = "voyage-group-comparison"
    val glyphContainer = "voyage-container"
    val glyphCore = "voyage-core"
    val glyphHalo = "voyage-halo"
    val glyphExtent = "voyage-extent"
    val unanchoredRow = "voyage-unanchored-row"
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
    val groupBand = ig.Rgba.unsafe(0xf4, 0xf6, 0xf5)
    val external = ig.Rgba.unsafe(0x8a, 0x93, 0x9d)
    val raw = ig.Rgba.unsafe(0x9a, 0xa3, 0xab)
    val ink2 = ig.Rgba.unsafe(0x4b, 0x56, 0x5f)
    val hair = ig.Rgba.unsafe(0xd5, 0xda, 0xd8)
    val surface = ig.Rgba.unsafe(0xff, 0xff, 0xff)
    // container outlines: 3.8:1 on the white plate, above the 3:1 non-text minimum
    val container = ig.Rgba.unsafe(0x7b, 0x85, 0x8d)

  /** The workshop's dash rhythms, stated rather than chosen from the two named ones. */
  private object Dash:
    val fill: ig.LineType = ig.LineType.Custom(ig.DashPattern.unsafe(3.0, 2.0))
    val route: ig.LineType = ig.LineType.Custom(ig.DashPattern.unsafe(4.0, 3.0))
    val guide: ig.LineType = ig.LineType.Custom(ig.DashPattern.unsafe(2.0, 3.0))

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
      if legal then
        val _ = out.appendCodePoint(cp)
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
  final case class Scales(
      box: Box,
      recallLength: Double,
      sourceEnd: Double,
      recallStart: Double = 0.0,
      recallEnd: Option[Double] = None,
      filmStart: Double = 0.0,
      filmEnd: Option[Double] = None
  ):
    def rangeStart: Double = recallStart
    def rangeEnd: Double = recallEnd.getOrElse(recallLength)
    def visible(t: Double): Boolean = t >= rangeStart && t <= rangeEnd
    def x(t: Double): Double =
      box.left + box.plotWidth * ((t - rangeStart) / math.max(rangeEnd - rangeStart, 1e-9))
    def filmLow: Double = filmStart
    def filmHigh: Double = filmEnd.getOrElse(sourceEnd)
    def y(s: Double): Double =
      box.top + box.plotHeight - box.plotHeight * ((s - filmLow) / math.max(
        filmHigh - filmLow,
        1e-9
      ))

    /** A source time pinned to the shown film window, for extents that run past it. */
    def yc(s: Double): Double = y(math.max(filmLow, math.min(filmHigh, s)))
    def filmVisible(s: Double): Boolean = s >= filmLow && s <= filmHigh
    def filmIntersects(start: Double, end: Double): Boolean = end > filmLow && start < filmHigh

    /** The midpoint of the part of an extent the film window shows. */
    def shownMidpoint(start: Double, end: Double): Double =
      (math.max(start, filmLow) + math.min(end, filmHigh)) / 2
    def track(group: Int, groupCount: Int): Double =
      box.trackTop + box.trackHeight - box.trackHeight * ((group - 0.5) / math.max(groupCount, 1))

  def scales(
      scene: VoyageScene,
      box: Box,
      window: Option[RecallWindow] = None,
      film: Option[FilmWindow] = None
  ): Scales =
    Scales(
      box,
      scene.recallLength.value,
      scene.timeline.end,
      window.fold(0.0)(_.start),
      window.map(_.end),
      film.fold(0.0)(_.start),
      film.map(_.end)
    )

  /** The "fit to window" y camera: the film extent of the placed and posterior-argmax anchors of
    * the units whose onsets the recall window shows, widened to the bounds of the groups that
    * contain its ends. It reads only supplied spans, and it depends on the recall window, never on
    * the selection, so choosing a unit never rescales the axis. `None` when no anchor is in view.
    */
  def fitFilm(scene: VoyageScene, window: Option[RecallWindow]): Option[FilmWindow] =
    val spans = scene.marks.collect {
      case m: VoyageMark.UnitAnchor if window.forall(_.contains(m.at.value)) =>
        Vector(m.span) ++ m.argmax.flatMap(scene.timeline.node).map(_.span).toVector
    }.flatten
    Option
      .when(spans.nonEmpty) {
        val lo = spans.map(_.start.value).min
        val hi = spans.map(_.end.value).max
        val groups = scene.timeline.groups.sortBy(_.span.start.value)
        val snappedLo =
          groups.find(g => g.span.end.value > lo).fold(lo)(g => math.min(lo, g.span.start.value))
        val snappedHi =
          groups.reverse
            .find(g => g.span.start.value < hi)
            .fold(hi)(g => math.max(hi, g.span.end.value))
        FilmWindow.of(snappedLo, snappedHi).toOption
      }
      .flatten

  /** The container's radius: its outline stands for mass 1.0. */
  val containerRadius: Double = 8.0

  /** Radius in pixels for a mass: the area is the mass's share of the container, with no floor. A
    * renderer-chosen minimum would be a threshold the model never supplied.
    */
  def radius(mass: Double): Double = containerRadius * math.sqrt(math.max(0.0, mass))

  /** The second outline of an external-dominant mark: fixed, so the flag never depends on mass. */
  val haloRadius: Double = containerRadius + 2.6

  /** The unanchored row sits this far above the plot. */
  val unanchoredRowOffset: Double = 12.0

  /** Lower a scene. `alternativesFor` names the units whose posterior columns are drawn in full;
    * `contextFor` names units whose columns are drawn faint, as context behind the focused ones;
    * `ghosts` opts into all moved argmaxes; otherwise only `ghostsFor` are shown. Labels prioritize
    * the groups of `alternativesFor`, then the number of drawn anchors, without moving their source
    * coordinates. `film` is the y camera (see `fitFilm`); `inspection` names the units an
    * inspection filter matched, so the rest are drawn without hue and none is dropped; `caption` is
    * the shell's own caption, drawn verbatim in the bottom margin; a `box.gutter` of at least 180px
    * holds the one selected unit's admitted anchors. These are display choices only; `box` is the
    * plate.
    */
  def lower(
      scene: VoyageScene,
      alternativesFor: Set[RecallUnitId] = Set.empty,
      box: Box = Box.default,
      ghosts: Boolean = false,
      contextFor: Set[RecallUnitId] = Set.empty,
      ghostsFor: Set[RecallUnitId] = Set.empty,
      window: Option[RecallWindow] = None,
      includeUntimed: Boolean = true,
      visibleRecallText: Option[Map[RecallUnitId, String]] = None,
      track: Track = Track.Groups,
      film: Option[FilmWindow] = None,
      inspection: Option[Set[RecallUnitId]] = None,
      caption: Vector[String] = Vector.empty
  ): Either[GraphicsError, ig.Scene] =
    window match
      case _ if track == Track.Masses && box.trackHeight <= 16 =>
        Left(
          GraphicsError.InvalidExtent("mass tracks require a height greater than their 16px gap")
        )
      case _ if track == Track.GroupDisagreements && box.trackHeight <= 32 =>
        Left(GraphicsError.InvalidExtent("group disagreements require room for an unknown rail"))
      case Some(w) if w.start < 0.0 || w.end > scene.recallLength.value =>
        Left(
          GraphicsError.InvalidExtent(
            s"recall window ${w.start}–${w.end} lies outside recall extent 0–${scene.recallLength.value}"
          )
        )
      case _ if film.exists(_.end > scene.timeline.end) =>
        Left(
          GraphicsError.InvalidExtent(
            s"film window ends after the source extent 0–${scene.timeline.end}"
          )
        )
      case _ =>
        val sc = scales(scene, box, window, film)
        val words = Words(scene, visibleRecallText)
        for
          vp <- viewport(box)
          ground <- groundLayer(scene, sc, vp, alternativesFor, track)
          bands <- codingLayer(scene, sc, vp, words)
          links <- linkLayer(scene, sc, vp, alternativesFor, contextFor, ghosts, ghostsFor, words)
          marks <- markLayer(scene, sc, vp, words, includeUntimed, inspection)
          auxiliary <- track match
            case Track.Groups             => trackLayer(scene, sc, vp)
            case Track.Masses             => massLayer(scene, sc, vp, alternativesFor)
            case Track.GroupDisagreements => disagreementLayer(scene, sc, vp, alternativesFor)
          captionLayer <- captionGrobs(caption, sc, vp)
        yield ig.Scene(Vector(ground, bands, links, marks, auxiliary) ++ captionLayer.toVector)

  // ------------------------------------------------------------------ titles

  /** What a title may say: only what the scene already holds, looked up once. */
  private final case class Words(
      scene: VoyageScene,
      visibleRecallText: Option[Map[RecallUnitId, String]]
  ):
    private val units = scene.units.map(u => u.id -> u).toMap
    def text(id: RecallUnitId): String =
      visibleRecallText.fold(units.get(id).map(_.text).getOrElse(""))(_.getOrElse(id, ""))
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
      vp: ig.Viewport,
      selected: Set[RecallUnitId],
      track: Track
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    // the groups the film window shows; a clipped group is labelled at the middle of its shown part
    val groups = scene.timeline.groups
      .sortBy(_.ordinal)
      .filter(g => sc.filmIntersects(g.span.start.value, g.span.end.value))
    def mid(g: SourceTimelineGroup): Double =
      sc.shownMidpoint(g.span.start.value, g.span.end.value)
    val anchors = scene.marks.collect {
      case a: VoyageMark.UnitAnchor if sc.visible(a.at.value) => a
    }
    val selectedGroups = anchors.filter(a => selected(a.unit)).flatMap(_.group).toSet
    // Fixed-width font; wrap instead of losing the end of a supplied label. Only the choice of
    // visible labels is layout policy. Their centers stay exactly at the supplied span midpoint.
    val columns = math.max(8, (box.right - 18) / 7)
    def codePoints(word: String): Iterator[String] = Iterator.unfold(0) { i =>
      if i >= word.length then None
      else
        val end = i + Character.charCount(word.codePointAt(i))
        Some(word.substring(i, end) -> end)
    }
    def length(text: String): Int = text.codePointCount(0, text.length)
    def wrap(text: String): Vector[String] =
      text
        .split("\\s+")
        .toVector
        .filter(_.nonEmpty)
        .flatMap(word => codePoints(word).grouped(columns).map(_.mkString))
        .foldLeft(
          Vector.empty[String]
        ) { (lines, word) =>
          if lines.nonEmpty && length(lines.last) + length(word) + 1 <= columns then
            lines.init :+ (lines.last + " " + word)
          else lines :+ word
        }
    val density = anchors.flatMap(_.group).groupMapReduce(identity)(_ => 1)(_ + _)
    // The rail (the workshop's ruling): every group in view is named or covered by a printed range,
    // and nothing is dropped. Each group starts as its own named label at its supplied midpoint;
    // only neighbours that collide merge. A merged cluster keeps one named head — a selected group
    // first, otherwise the group with the most anchors in view — and prints the rest as a range
    // line just below it; groups with no anchor in view that collide become one range line.
    final case class Cluster(
        members: Vector[SourceTimelineGroup],
        head: Option[SourceTimelineGroup],
        y: Double,
        h: Double
    )
    def lines(g: SourceTimelineGroup): Double = wrap(g.label).size * 13.0
    def rank(g: SourceTimelineGroup): (Int, Int) =
      (if selectedGroups(g.ordinal) then 1 else 0, density.getOrElse(g.ordinal, 0))
    def isSelected(c: Cluster): Boolean = c.head.exists(g => selectedGroups(g.ordinal))
    @annotation.tailrec
    def settle(cs: Vector[Cluster]): Vector[Cluster] =
      cs.indices.drop(1).find { k =>
        val (a, b) = (cs(k - 1), cs(k))
        !(isSelected(a) && isSelected(b)) && b.y - a.y < (a.h + b.h) / 2 + 3
      } match
        case None    => cs
        case Some(k) =>
          val (a, b) = (cs(k - 1), cs(k))
          val members = a.members ++ b.members
          val head = (a.head.toVector ++ b.head.toVector)
            .filter(g => rank(g) != (0, 0))
            .sortBy(g => (-rank(g)._1, -rank(g)._2, g.ordinal))
            .headOption
          val merged = head match
            case Some(g) => Cluster(members, Some(g), sc.y(mid(g)), lines(g) + 26)
            case None    =>
              Cluster(members, None, members.map(g => sc.y(mid(g))).sum / members.size, 13.0)
          settle(cs.patch(k - 1, Vector(merged), 2))
    val clusters = settle(
      groups.sortBy(g => sc.y(mid(g))).map(g => Cluster(Vector(g), Some(g), sc.y(mid(g)), lines(g)))
    )
    // two selected groups too close to merge keep their names; their ticks stay at their midpoints
    val labels = clusters.flatMap(_.head).map(g => (g, wrap(g.label))).sortBy(_._1.ordinal)
    val ranges = clusters.flatMap { c =>
      val rest = c.members.filterNot(c.head.contains).sortBy(_.ordinal)
      if rest.isEmpty then Vector.empty
      else
        c.head match
          case None    => Vector(rest -> c.y)
          case Some(g) => Vector(rest -> (sc.y(mid(g)) + wrap(g.label).size * 6.5 + 9))
    }
    // whole film keeps its five-minute ticks; a fitted window of half an hour or less ticks every two
    val filmStep = if sc.filmHigh - sc.filmLow > 1800.0 then 300.0 else 120.0
    val filmTicks =
      ticks(math.ceil(sc.filmLow / filmStep) * filmStep, sc.filmHigh, filmStep)
        .filter(sc.filmVisible)
    for
      hair <- params(Some(Palette.hair), None)
      band <- params(None, Some(Palette.groupBand))
      label <- params(None, Some(Palette.ink2))
      groupLabel <- params(None, Some(Palette.ink2), fontPx = 11)
      selectedLabel = groupLabel.withFontWeight(ig.FontWeight.unsafe(600))
      boundaries <- groups.zipWithIndex.filter(_._2 % 2 == 0).traverse { case (g, _) =>
        val top = sc.yc(g.span.end.value)
        val bottom = sc.yc(g.span.start.value)
        ig.Grob
          .polygon(
            Vector(
              px(box.left, top),
              px(box.plotRight, top),
              px(box.plotRight, bottom),
              px(box.left, bottom)
            ),
            gp = band
          )
          .flatMap(annotated(_, Some(g.label), Classes.groupBand, "group" -> g.ordinal.toString))
      }
      groupLabels <- labels.traverse { case (g, lines) =>
        val y = sc.y(mid(g))
        for
          tick <- ig.Grob.lines(
            Vector(px(box.railLeft - 3, y), px(box.railLeft + 5, y)),
            gp = hair
          )
          text <- lines.zipWithIndex.traverse { case (line, i) =>
            ig.Grob.text(
              line,
              px(box.railLeft + 9, y + (i - (lines.size - 1) / 2.0) * 13 + 3.5),
              ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
              gp = if selectedGroups(g.ordinal) then selectedLabel else groupLabel
            )
          }
          result <- annotated(
            ig.Grob.group(Vector(tick) ++ text),
            Some(g.label),
            Classes.groupLabel,
            "group" -> g.ordinal.toString
          )
        yield result
      }
      rangeLabels <- ranges.traverse { case (run, y) =>
        val ys = run.map(g => sc.y(mid(g)))
        val text =
          ordinalRuns(run.map(_.ordinal))
        for
          bracket <- ig.Grob.lines(
            Vector(
              px(box.railLeft + 2, ys.min),
              px(box.railLeft + 2, ys.max)
            ),
            gp = hair
          )
          label <- ig.Grob.text(
            text,
            px(box.railLeft + 9, y + 3.5),
            ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
            gp = groupLabel
          )
          result <- annotated(
            ig.Grob.group(Vector(bracket, label)),
            Some(run.map(_.label).mkString("; ")),
            Classes.groupRange,
            "groups" -> run.map(_.ordinal).mkString(" ")
          )
        yield result
      }
      yAxis <- ig.Grob.lines(
        Vector(px(box.left, box.top), px(box.left, box.top + box.plotHeight)),
        gp = hair
      )
      xAxis <- ig.Grob.lines(
        Vector(
          px(box.left, box.top + box.plotHeight),
          px(box.plotRight, box.top + box.plotHeight)
        ),
        gp = hair
      )
      yTicks <- filmTicks.traverse { s =>
        ig.Grob.lines(Vector(px(box.left - 4, sc.y(s)), px(box.left, sc.y(s))), gp = hair)
      }
      yLabels <- filmTicks.traverse { s =>
        ig.Grob.text(
          clock(s),
          px(box.left - 7, sc.y(s) + 3.5),
          ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
          gp = label
        )
      }
      xTicks <- timeTicks(sc.rangeStart, sc.rangeEnd, box.plotWidth, 44.0).traverse { t =>
        ig.Grob.lines(
          Vector(px(sc.x(t), box.top + box.plotHeight), px(sc.x(t), box.top + box.plotHeight + 4)),
          gp = hair
        )
      }
      xLabels <- timeLabels(sc.rangeStart, sc.rangeEnd, box.plotWidth, 160.0, 40.0).traverse { t =>
        ig.Grob.text(
          tickLabel(
            t,
            sc.rangeEnd - sc.rangeStart,
            t == sc.rangeStart || t == sc.rangeEnd
          ),
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
          px(box.left + box.plotWidth / 2.0, box.top + box.plotHeight + 27),
          ig.Anchor(ig.HJust.Center, ig.VJust.Bottom),
          gp = label
        )
      ).sequence
      // the unanchored row: named once, and drawn only when the scene carries unanchored units
      unanchoredRow <- Option
        .when(scene.marks.exists {
          case _: VoyageMark.Unanchored => true
          case _                        => false
        }) {
          val rowY = box.top - unanchoredRowOffset
          for
            rule <- ig.Grob.lines(
              Vector(px(box.left, rowY), px(box.left + box.plotWidth, rowY)),
              gp = hair
            )
            caption <- ig.Grob.text(
              "unanchored units (no film anchor)",
              px(box.left + 4, rowY - 7),
              ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
              gp = label
            )
            row <- annotated(ig.Grob.group(Vector(rule, caption)), None, Classes.unanchoredRow)
          yield row
        }
        .sequence
      trackName <- Option
        .when(track == Track.Groups)(
          ig.Grob.text(
            "group",
            px(box.left + 6, box.trackTop + 10),
            ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
            gp = label
          )
        )
        .sequence
      layer = ig.Grob.group(
        boundaries ++ groupLabels ++ rangeLabels ++ Vector(
          yAxis,
          xAxis
        ) ++ yTicks ++ yLabels ++ xTicks ++ xLabels ++ axisNames ++ unanchoredRow.toVector ++
          trackName,
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
      rects <- intervals
        .filter(iv => intersects(iv.recall.start.value, iv.recall.end.value, sc))
        .traverse { iv =>
          scene.timeline.byGroup.get(iv.group) match
            case None    => Right(ig.Grob.group(Vector.empty))
            case Some(g) =>
              val x0 = sc.x(math.max(iv.recall.start.value, sc.rangeStart))
              // A coding may run past the last word (its coder heard the recording end). Its geometry
              // is clipped to this display window; the interval itself remains compiler-owned data.
              val x1 = sc.x(math.min(iv.recall.end.value, sc.rangeEnd))
              val y0 = sc.yc(g.span.end.value)
              val y1 = sc.yc(g.span.start.value)
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
      ghostsFor: Set[RecallUnitId],
      words: Words
  ): Either[GraphicsError, ig.Grob] =
    val anchors = scene.marks.collect {
      case m: VoyageMark.UnitAnchor if sc.visible(m.at.value) => m
    }
    val anchorAt = scene.marks.collect { case a: VoyageMark.UnitAnchor =>
      a.unit -> a.at.value
    }.toMap
    val alternatives = scene.marks.collect {
      case m: VoyageMark.Alternative
          if anchorAt.get(m.unit).exists(sc.visible) &&
            (alternativesFor.contains(m.unit) || contextFor.contains(m.unit)) =>
        m
    }
    val anchorOf = anchors.map(m => m.unit -> m).toMap
    // the gutter shows one unit's admitted anchors, when the plate has room for it
    val gutterUnit =
      Option
        .when(sc.box.gutter >= 180 && alternativesFor.size == 1)(alternativesFor.head)
        .flatMap(anchorOf.get)
    val moved = anchors
      .filter(m => m.origin != AnchorOrigin.PosteriorArgmax && (ghosts || ghostsFor(m.unit)))
      .flatMap(m => m.argmax.flatMap(scene.timeline.node).map(n => m -> n))
      .filter { case (m, n) => sc.filmVisible(m.span.midpoint) && sc.filmVisible(n.span.midpoint) }
    for
      altLine <- params(Some(Palette.model), None, width = 0.8, alpha = 0.7)
      altRing <- params(Some(Palette.model), None, width = 1.2, line = ig.LineType.Dashed)
      // context columns are the same marks, drawn so faint that the focused column still leads
      faintLine <- params(Some(Palette.model), None, width = 0.6, alpha = 0.22)
      faintRing <-
        params(Some(Palette.model), None, width = 0.9, line = ig.LineType.Dashed, alpha = 0.3)
      // the moved argmax: a thin neutral ring where the posterior put the unit, joined to the placed
      // container by the declared route (dashed); never a filled mark, since it is not a placement
      routeLine <- params(Some(Palette.ink2), None, width = 1.0, line = Dash.route)
      ghostRing <- params(Some(Palette.ink2), None, width = 1.2)
      ghostGrobs <- moved.traverse { case (m, n) =>
        val x = sc.x(m.at.value)
        val from = sc.y(n.span.midpoint)
        val to = sc.y(m.span.midpoint)
        val gap = containerRadius + 1
        val dir = if to > from then 1.0 else -1.0
        for
          line <- ig.Grob.lines(
            Vector(px(x, from + dir * (containerRadius + 3)), px(x, to - dir * gap)),
            gp = routeLine
          )
          dot <- ig.Grob.points(
            Vector(px(x, from)),
            ig.ExtentExpr.nativeUnsafe(containerRadius + 2),
            ig.PointShape.Circle,
            ghostRing
          )
          g <- annotated(
            ig.Grob.group(Vector(line, dot)),
            Some(words.ghost(m, n)),
            Classes.ghost,
            "unit" -> m.unitOrdinal.toString
          )
        yield g
      }
      alts <- alternatives.filterNot(a => gutterUnit.exists(_.unit == a.unit)).traverse { a =>
        anchorOf.get(a.unit) match
          case None => Right(ig.Grob.group(Vector.empty))
          case Some(m) if !sc.filmVisible(a.span.midpoint) || !sc.filmVisible(m.span.midpoint) =>
            // an alternative outside the shown film keeps its name (the inspector lists it) but draws
            // nothing on the plate
            GraphicsNames.ofMark(a.identity.mark).map(_ => ig.Grob.group(Vector.empty))
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
      gutter <- gutterUnit.fold(Right(Vector.empty))(m =>
        gutterGrobs(m, alternatives.filter(_.unit == m.unit), sc, words, scene)
      )
      layer = ig.Grob.group(ghostGrobs ++ alts ++ gutter, viewport = Some(vp))
    yield layer

  /** Ordinals as printed runs: `3–5, 7, 9–10`. A range never names an ordinal it does not hold. */
  private[intaglio] def ordinalRuns(ordinals: Vector[Int]): String =
    ordinals.distinct.sorted
      .foldLeft(Vector.empty[(Int, Int)]) {
        case (acc, o) if acc.nonEmpty && acc.last._2 + 1 == o => acc.init :+ (acc.last._1 -> o)
        case (acc, o)                                         => acc :+ (o -> o)
      }
      .map((a, b) => if a == b then a.toString else s"$a–$b")
      .mkString(", ")

  /** A mass as the gutter prints it: three decimals, and a small non-zero mass in scientific form
    * rather than a rounded zero. Built by hand so JVM and Scala.js print the same bytes.
    */
  private[intaglio] def massLabel(m: Double): String =
    if m == 0.0 then "0"
    else if m >= 0.001 then f"$m%.3f"
    else
      // two significant digits by decimal rounding, not by log10/pow, whose last bits may differ
      // between the JVM and JavaScript and whose rounding can print a mantissa of 10.0
      val rounded = BigDecimal(m).round(new java.math.MathContext(2)).bigDecimal
      val digits = rounded.unscaledValue.abs.toString.padTo(2, '0')
      val exponent = rounded.precision - rounded.scale - 1
      s"${digits.head}.${digits(1)}e$exponent"

  /** The gutter: the selected unit's admitted anchors on the film axis, beside the plot. Each is
    * its `Alternative` mark (named, so selection and hit-testing work), drawn as its exact extent
    * and a bar whose length is its mass (100px = 1.0 when the gutter has room). Every value in view
    * is printed at its bar end; crowded labels keep a 12px pitch and carry a leader back to their
    * bar. A decode fill's placement is not an admitted anchor, so it is drawn as a dashed extent
    * with mass 0 and no name. The external mass the row supplies sits above, as its own bar.
    */
  private def gutterGrobs(
      m: VoyageMark.UnitAnchor,
      alts: Vector[VoyageMark.Alternative],
      sc: Scales,
      words: Words,
      scene: VoyageScene
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val box = sc.box
    val gx = box.plotRight + 12.0
    // the bar scale leaves room for the longest label ("0.900 argmax · placed") before the rail
    val scale = math.min(100.0, box.gutter - 140.0)
    // the placed anchor is the unit's own mark (its name is on the glyph); the compiler lists every
    // other admitted anchor as an Alternative. A decode fill's placement has no posterior mass and
    // is not an admitted anchor, so it is drawn apart, below.
    final case class Entry(
        anchor: SourceNodeRef,
        span: ClockSpan,
        mass: Double,
        alternative: Option[VoyageMark.Alternative]
    )
    val entries =
      Option
        .when(m.origin != AnchorOrigin.DecodeFilled)(Entry(m.anchor, m.span, m.mass, None))
        .toVector ++ alts.map(a => Entry(a.anchor, a.span, a.mass, Some(a)))
    val (shown, outside) =
      entries.partition(e => sc.filmIntersects(e.span.start.value, e.span.end.value))
    def role(a: Entry): String =
      val argmax = m.argmax.contains(a.anchor)
      val placed = a.anchor == m.anchor
      if argmax && placed then "argmax · placed"
      else if argmax then "argmax"
      else if placed then "placed"
      else ""
    final case class Label(cy: Double, x: Double, text: String, strong: Boolean)
    val fill = Option.when(
      m.origin == AnchorOrigin.DecodeFilled && sc.filmIntersects(
        m.span.start.value,
        m.span.end.value
      )
    )(m)
    val labels0 =
      shown.map { a =>
        val cy = sc.y(sc.shownMidpoint(a.span.start.value, a.span.end.value))
        val r = role(a)
        Label(
          cy,
          gx + 10 + a.mass * scale,
          massLabel(a.mass) + (if r.isEmpty then "" else s" $r"),
          r.nonEmpty
        )
      } ++ fill.map(f =>
        Label(
          sc.y(sc.shownMidpoint(f.span.start.value, f.span.end.value)),
          gx + 8,
          "0 placed · fill",
          true
        )
      )
    // every label printed, inside the plot's height: a label moves only as far as its neighbours
    // demand (down from the plot top, then up from the plot floor), never by a shared shift, so an
    // isolated label stays at its bar; a stack taller than the plot shares the height evenly
    val ceiling = box.top + 4.0
    val floor = (box.top + box.plotHeight).toDouble
    val pitch = math.min(12.0, (floor - ceiling) / math.max(1, labels0.size - 1))
    val down = labels0.sortBy(_.cy).foldLeft(Vector.empty[(Label, Double)]) { (acc, l) =>
      val lowest = acc.lastOption.fold(ceiling)((_, prev) => prev + pitch)
      acc :+ (l -> math.max(l.cy, lowest))
    }
    val placed = down.foldRight(Vector.empty[(Label, Double)]) { case ((l, y), acc) =>
      val highest = acc.headOption.fold(floor)((_, next) => next - pitch)
      (l -> math.min(y, highest)) +: acc
    }
    val leaderX = placed.filter((l, y) => math.abs(y - l.cy) > 1).map(_._1.x).maxOption
    for
      ink <- params(None, Some(Palette.ink2))
      strong = ink.withFontWeight(ig.FontWeight.unsafe(600))
      muted <- params(None, Some(Palette.container))
      bar <- params(None, Some(Palette.model))
      extentGp <- params(Some(Palette.model), None, width = 3.0)
      fillGp <- params(Some(Palette.ink2), None, width = 1.5, line = Dash.fill)
      leaderGp <- params(Some(Palette.container), None, width = 0.75)
      externalGp <- params(None, Some(Palette.container))
      guideGp <- params(Some(Palette.container), None, width = 0.75, line = Dash.guide)
      // guides from the selected mark (and its moved argmax) across to the gutter
      guideYs = (Vector(m.span.midpoint) ++ m.argmax
        .filter(_ != m.anchor)
        .flatMap(scene.timeline.node)
        .map(_.span.midpoint)
        .toVector).filter(sc.filmVisible).map(sc.y)
      guides <- guideYs.traverse(y =>
        ig.Grob.lines(
          Vector(px(sc.x(m.at.value) + containerRadius + 5, y), px(gx - 3, y)),
          gp = guideGp
        )
      )
      heading <- ig.Grob.text(
        s"unit ${m.unitOrdinal} admitted anchors · ${scale.toInt}px = mass 1.0",
        px(gx, box.top - 22),
        ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
        gp = muted
      )
      external <- ig.Grob.polygon(
        Vector(
          px(gx + 6, box.top - 15),
          px(gx + 6 + m.externalMass * scale, box.top - 15),
          px(gx + 6 + m.externalMass * scale, box.top - 9),
          px(gx + 6, box.top - 9)
        ),
        gp = externalGp
      )
      externalText <- ig.Grob.text(
        s"${massLabel(m.externalMass)} external",
        px(gx + 10 + m.externalMass * scale, box.top - 8),
        ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
        gp = muted
      )
      bars <- shown.traverse { a =>
        val top = sc.yc(a.span.end.value)
        val bottom = sc.yc(a.span.start.value)
        val cy = (top + bottom) / 2
        val w = a.mass * scale
        for
          extent <- ig.Grob.lines(Vector(px(gx, top), px(gx, bottom)), gp = extentGp)
          length <- ig.Grob.polygon(
            Vector(
              px(gx + 6, cy - 3),
              px(gx + 6 + w, cy - 3),
              px(gx + 6 + w, cy + 3),
              px(gx + 6, cy + 3)
            ),
            gp = bar
          )
          g <- a.alternative match
            case Some(alt) =>
              GraphicsNames
                .ofMark(alt.identity.mark)
                .flatMap(name =>
                  annotated(
                    ig.Grob.group(Vector(extent, length), name = Some(name)),
                    Some(words.alternative(alt)),
                    s"${Classes.mark} ${Classes.alternative} ${Classes.gutter}",
                    "unit" -> words.ordinal(alt.unit).toString,
                    "rank" -> alt.rank.toString
                  )
                )
            case None =>
              // the placed anchor's bar restates the glyph's own mass; it carries no second name
              annotated(
                ig.Grob.group(Vector(extent, length)),
                None,
                s"${Classes.gutter} gutter-placed",
                "unit" -> m.unitOrdinal.toString
              )
        yield g
      }
      fillMark <- fill.traverse(f =>
        ig.Grob.lines(
          Vector(px(gx + 1, sc.yc(f.span.end.value)), px(gx + 1, sc.yc(f.span.start.value))),
          gp = fillGp
        )
      )
      texts <- placed.traverse { (l, y) =>
        val moved = math.abs(y - l.cy) > 1
        val x = if moved then leaderX.fold(l.x)(lx => math.max(lx, l.x)) + 8 else l.x
        for
          leader <- Option
            .when(moved)(
              ig.Grob
                .lines(Vector(px(l.x - 3, l.cy), px(x - 10, l.cy), px(x - 3, y)), gp = leaderGp)
                .flatMap(annotated(_, None, Classes.gutterLeader))
            )
            .sequence
          t <- ig.Grob.text(
            l.text,
            px(x, y + 3.5),
            ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
            gp = if l.strong then strong else ink
          )
        yield leader.toVector :+ t
      }
      outsideNote <- Option
        .when(outside.nonEmpty)(
          ig.Grob.text(
            s"+${outside.size} admitted anchor${
                if outside.size > 1 then "s" else ""
              } outside the shown film",
            px(gx, floor + 30),
            ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
            gp = muted
          )
        )
        .sequence
      decoration <- annotated(
        ig.Grob.group(
          guides ++ Vector(
            heading,
            external,
            externalText
          ) ++ fillMark.toVector ++ texts.flatten ++ outsideNote.toVector
        ),
        None,
        Classes.gutterLabels,
        "unit" -> m.unitOrdinal.toString
      )
    yield bars :+ decoration

  private def markLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport,
      words: Words,
      includeUntimed: Boolean,
      inspection: Option[Set[RecallUnitId]]
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val anchorAt = scene.marks.collect { case a: VoyageMark.UnitAnchor =>
      a.unit -> a.at.value
    }.toMap
    val displayed = scene.marks.filter {
      // an anchor placed outside the shown film is not drawn; "fit to window" never does this,
      // because its film window covers every placement in the recall window
      case m: VoyageMark.UnitAnchor  => sc.visible(m.at.value) && sc.filmVisible(m.span.midpoint)
      case m: VoyageMark.Unanchored  => sc.visible(m.at.value)
      case m: VoyageMark.Alternative => anchorAt.get(m.unit).exists(sc.visible)
      case _: VoyageMark.Untimed     => includeUntimed
    }
    val untimed = displayed.collect { case m: VoyageMark.Untimed => m }
    for
      grobs <- displayed.traverse {
        case m: VoyageMark.UnitAnchor =>
          val x = sc.x(m.at.value)
          // external-dominant is the compiler's fact about the row, read from the mark (V-U5)
          val externalDominant = m.externalDominant
          val classes = Vector(
            Classes.mark,
            Classes.anchor,
            Classes.origin(m.origin)
          ) ++ Option.when(externalDominant)(Classes.externalDominant) ++
            Option.when(m.level > 0)(Classes.groupLevel) ++
            inspection.map(set => if set(m.unit) then Classes.matched else Classes.unmatched)
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            grob <- anchorGlyph(m, x, sc, name, inspection.map(_(m.unit)))
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
          // the unanchored row above the plot: a cross, hollow, at the unit's recall time
          for
            name <- GraphicsNames.ofMark(m.identity.mark)
            gp <- params(Some(Palette.external), None, width = 1.4)
            // a cross lowers to two strokes, and each would carry the name; the group carries it once
            cross <- ig.Grob.points(
              Vector(px(sc.x(m.at.value), box.top - unanchoredRowOffset)),
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

  /** Two independent fixed 0–1 tracks. Their x coordinates use the same Scales instance as the
    * plate. These are unnamed decorations of existing marks: no duplicate scientific identities, no
    * quoted text beyond a horizon, and no invented position or mass for an untimed unit.
    */
  private def massLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport,
      selected: Set[RecallUnitId]
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val rowHeight = (box.trackHeight - 16.0) / 2.0
    val rows = Vector(
      (Classes.massAnchor, "Drawn anchor", box.trackTop.toDouble),
      (Classes.massExternal, "External", box.trackTop + rowHeight + 16.0)
    )
    def sample(
        unit: RecallUnitId,
        ordinal: Int,
        onset: Double,
        row: Int,
        value: Option[Double],
        origin: Option[AnchorOrigin]
    ): Either[GraphicsError, ig.Grob] =
      val (channel, label, top) = rows(row)
      val x = sc.x(onset)
      val bottom = top + rowHeight
      val status = value.fold("unavailable")(v => if v == 0.0 then "zero" else "measured")
      val explanation =
        value.fold("unavailable: no source anchor")(v => f"$v%.6f on the fixed 0–1 scale")
      for
        bar <- params(None, Some(if row == 0 then Palette.model else Palette.external))
        line <- params(Some(Palette.ink2), None, width = 1.0)
        outline <- params(Some(Palette.ink2), None, width = 1.0, line = ig.LineType.Dashed)
        mark <- value match
          case Some(v) if v > 0.0 =>
            ig.Grob.polygon(
              Vector(
                px(x - 1.5, bottom),
                px(x + 1.5, bottom),
                px(x + 1.5, bottom - rowHeight * v),
                px(x - 1.5, bottom - rowHeight * v)
              ),
              gp = bar
            )
          case Some(_) =>
            ig.Grob.lines(Vector(px(x - 2, bottom), px(x + 2, bottom)), gp = line)
          case None =>
            // The cross is deliberately away from the numeric baseline: missing is not zero.
            ig.Grob.points(
              Vector(px(x, top + rowHeight / 2)),
              ig.ExtentExpr.nativeUnsafe(2.5),
              ig.PointShape.Cross,
              line
            )
        focus <- Option
          .when(selected(unit))(
            ig.Grob.polygon(
              Vector(px(x - 4, top), px(x + 4, top), px(x + 4, bottom), px(x - 4, bottom)),
              gp = outline
            )
          )
          .sequence
        result <- annotated(
          ig.Grob.group(Vector(mark) ++ focus),
          Some(
            s"Unit $ordinal · $label mass $explanation" + origin.fold("")(o => s" · ${o.label}")
          ),
          s"${Classes.mass} $channel" +
            (if status == "zero" then s" ${Classes.massZero}" else "") +
            (if status == "unavailable" then s" ${Classes.massUnavailable}" else ""),
          (Vector(
            "unit" -> ordinal.toString,
            "channel" -> channel,
            "status" -> status,
            "onset-bits" -> Score.hexBits(onset)
          ) ++
            value.map(v => "mass-bits" -> Score.hexBits(v)) ++
            origin.map(o => "origin" -> Classes.origin(o).stripPrefix("origin-")))*
        )
      yield result
    for
      hair <- params(Some(Palette.hair), None)
      label <- params(None, Some(Palette.ink2), fontPx = 11)
      guides <- rows.traverse { case (_, title, top) =>
        for
          baseline <- ig.Grob.lines(
            Vector(px(box.left, top + rowHeight), px(box.plotRight, top + rowHeight)),
            gp = hair
          )
          ceiling <- ig.Grob.lines(
            Vector(px(box.left, top), px(box.plotRight, top)),
            gp = hair
          )
          name <- ig.Grob.text(
            title,
            px(box.plotRight + 8, top + 12),
            ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
            gp = label
          )
          scale <- Vector(0.0, 1.0).traverse(v =>
            ig.Grob.text(
              v.toInt.toString,
              px(box.left - 7, top + rowHeight * (1 - v) + 3),
              ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
              gp = label
            )
          )
        yield ig.Grob.group(Vector(baseline, ceiling, name) ++ scale)
      }
      samples <- scene.marks.traverse {
        case m: VoyageMark.UnitAnchor if sc.visible(m.at.value) =>
          Vector(
            sample(m.unit, m.unitOrdinal, m.at.value, 0, Some(m.mass), Some(m.origin)),
            sample(m.unit, m.unitOrdinal, m.at.value, 1, Some(m.externalMass), None)
          ).sequence
        case m: VoyageMark.Unanchored if sc.visible(m.at.value) =>
          Vector(
            sample(m.unit, m.unitOrdinal, m.at.value, 0, None, None),
            sample(m.unit, m.unitOrdinal, m.at.value, 1, Some(m.externalMass), None)
          ).sequence
        case _ => Right(Vector.empty[ig.Grob])
      }
    yield ig.Grob.group(guides ++ samples.flatten, viewport = Some(vp))

  /** Categorical group order on y, supplied recall onset on x. Only unequal known pairs have
    * endpoints. Unknown timed records get a separate non-metric rail; untimed records stay in the
    * shell's inventory. Decorations carry no new MarkId and cannot replace the source marks.
    */
  private def disagreementLayer(
      scene: VoyageScene,
      sc: Scales,
      vp: ig.Viewport,
      selected: Set[RecallUnitId]
  ): Either[GraphicsError, ig.Grob] =
    val box = sc.box
    val groups = scene.timeline.groups.sortBy(_.ordinal)
    val indices = groups.zipWithIndex.map((g, i) => g.ordinal -> i).toMap
    val anchors = scene.marks.collect { case a: VoyageMark.UnitAnchor => a.unit -> a }.toMap
    val groupHeight = box.trackHeight - 24.0
    val unknownY = box.trackTop + box.trackHeight - 6.0
    def y(group: Int): Double =
      box.trackTop + groupHeight * (1 - (indices(group) + 0.5) / math.max(groups.size, 1))
    val records = VoyageGroupComparison
      .records(scene)
      .flatMap(r =>
        r.onset
          .filter(t => sc.visible(t.value))
          .filter(_ => r.status != VoyageGroupComparison.Status.Agreement)
          .map(r -> _)
      )
    val hasUnknown = records.exists(_._1.status == VoyageGroupComparison.Status.Unknown)
    for
      hair <- params(Some(Palette.hair), None)
      text <- params(None, Some(Palette.ink2), fontPx = 11)
      line <- params(Some(Palette.ink2), None, width = 1)
      outline <- params(Some(Palette.ink2), None, width = 1, line = ig.LineType.Dashed)
      axis <- ig.Grob.lines(
        Vector(px(box.left, box.trackTop), px(box.left, box.trackTop + groupHeight)),
        gp = hair
      )
      rail <- Option
        .when(hasUnknown)(
          ig.Grob.lines(
            Vector(px(box.left, unknownY), px(box.plotRight, unknownY)),
            gp = hair
          )
        )
        .sequence
      labels <- (0 until 6).toVector
        .map(i => i * math.max(groups.size - 1, 0) / 5)
        .distinct
        .flatMap(groups.lift)
        .traverse { group =>
          ig.Grob.text(
            group.ordinal.toString,
            px(box.left - 7, y(group.ordinal) + 3),
            ig.Anchor(ig.HJust.Right, ig.VJust.Bottom),
            gp = text
          )
        }
      captions <- (Vector("Group order" -> (box.trackTop + 12.0)) ++
        Option.when(hasUnknown)("Unknown" -> (unknownY + 3))).traverse { (caption, at) =>
        ig.Grob.text(
          caption,
          px(box.plotRight + 8, at),
          ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
          gp = text
        )
      }
      samples <- records.traverse { (r, onset) =>
        val x = sc.x(onset.value)
        val anchor = anchors.get(r.unit)
        val pair = for drawn <- r.drawn; raw <- r.argmax yield (y(drawn), y(raw))
        val origin = anchor.map(_.origin)
        val hollow = anchor.exists(a => a.externalDominant || a.origin == AnchorOrigin.DecodeFilled)
        val classes = Vector(
          Classes.groupComparison,
          if pair.nonEmpty then "group-disagreement" else "group-unknown"
        ) ++
          origin.map(Classes.origin) ++
          Option.when(anchor.exists(_.externalDominant))(Classes.externalDominant) ++
          Option.when(anchor.exists(_.level > 0))(Classes.groupLevel)
        val title = s"Unit ${r.ordinal}: drawn group ${r.drawn.fold("unknown")(_.toString)}; " +
          s"posterior-argmax group ${r.argmax.fold("unknown")(_.toString)}. " +
          (if pair.nonEmpty then "Different groups." else "Comparison unknown, not agreement.") +
          " Group spacing is ordinal, not time or distance; endpoint size carries no mass."
        for
          gp <- params(
            Some(if hollow then Palette.ink2 else Palette.model),
            if hollow then None else Some(Palette.model),
            width = 1.4,
            line =
              if origin.contains(AnchorOrigin.DecodeFilled) then ig.LineType.Dashed
              else ig.LineType.Solid
          )
          marks <- pair match
            case Some((drawn, raw)) =>
              for
                stem <- ig.Grob.lines(Vector(px(x, drawn), px(x, raw)), gp = line)
                cap <- ig.Grob.lines(Vector(px(x - 4, raw), px(x + 4, raw)), gp = line)
                endpoint <- ig.Grob.points(
                  Vector(px(x, drawn)),
                  ig.ExtentExpr.nativeUnsafe(3.5),
                  if origin.contains(AnchorOrigin.PosteriorArgmax) then ig.PointShape.Circle
                  else ig.PointShape.Diamond,
                  gp
                )
              yield Vector(stem, cap, endpoint)
            case None =>
              ig.Grob
                .points(
                  Vector(px(x, unknownY)),
                  ig.ExtentExpr.nativeUnsafe(3),
                  ig.PointShape.Cross,
                  line
                )
                .map(Vector(_))
          focus <- Option
            .when(selected(r.unit)) {
              val (low, high) =
                pair.fold((unknownY, unknownY))((a, b) => (math.min(a, b), math.max(a, b)))
              ig.Grob.polygon(
                Vector(
                  px(x - 6, low - 6),
                  px(x + 6, low - 6),
                  px(x + 6, high + 6),
                  px(x - 6, high + 6)
                ),
                gp = outline
              )
            }
            .sequence
          result <- annotated(
            ig.Grob.group(marks ++ focus),
            Some(title),
            classes.mkString(" "),
            (Vector(
              "unit" -> r.ordinal.toString,
              "comparison-unit" -> r.unit.value,
              "status" -> r.status.toString.toLowerCase,
              "onset-bits" -> Score.hexBits(onset.value)
            ) ++
              r.drawn.map(g => "drawn-group" -> g.toString) ++ r.argmax
                .map(g => "argmax-group" -> g.toString))*
          )
        yield result
      }
    yield ig.Grob.group(Vector(axis) ++ rail ++ labels ++ captions ++ samples, viewport = Some(vp))

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
        groupOf(m).flatMap { g =>
          val end = anchors
            .lift(i + 1)
            .map(_.at.value)
            .getOrElse(math.min(sc.recallLength, m.at.value + 8))
          val y = sc.track(g, groupCount)
          clip(m.at.value, end, sc).map { case (start, stop) =>
            ig.Grob.lines(Vector(px(sc.x(start), y), px(sc.x(stop), y)), gp = gp)
          }
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
      gold <- intervals
        .filter(iv => intersects(iv.recall.start.value, iv.recall.end.value, sc))
        .traverse(iv =>
          ig.Grob.lines(
            Vector(
              px(
                sc.x(math.max(iv.recall.start.value, sc.rangeStart)),
                sc.track(iv.group, groupCount)
              ),
              px(
                sc.x(math.min(iv.recall.end.value, sc.rangeEnd)),
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

  /** One unit anchor as a single named group: the exact extent rule, the second outline when the
    * row is external-dominant, the container (mass 1.0), and the inner mark (the anchor mass). Each
    * part carries a class, so a stylesheet can restyle the container of a selected mark without
    * touching its mass.
    */
  private def anchorGlyph(
      m: VoyageMark.UnitAnchor,
      x: Double,
      sc: Scales,
      name: ig.GraphicsName,
      matched: Option[Boolean]
  ): Either[GraphicsError, ig.Grob] =
    val cy = sc.y(m.span.midpoint)
    val shape =
      if m.origin == AnchorOrigin.PosteriorArgmax then ig.PointShape.Circle
      else ig.PointShape.Diamond
    val filled = m.origin == AnchorOrigin.DecodeFilled
    // an inspection filter's non-match keeps its shape and container but loses its hue: the core is
    // drawn in the container grey (3.8:1), never so pale that the mark disappears
    val dimmed = matched.contains(false)
    val core =
      if dimmed then Palette.container
      else if m.origin == AnchorOrigin.PosteriorArgmax then Palette.model
      else Palette.ink2
    def dot(size: Double, gp: ig.GraphicParams) =
      ig.Grob.points(Vector(px(x, cy)), ig.ExtentExpr.nativeUnsafe(size), shape, gp)
    for
      extentGp <- params(Some(Palette.container), None, width = if m.level > 0 then 3.0 else 1.5)
      extent <- ig.Grob.lines(
        Vector(px(x, sc.yc(m.span.end.value)), px(x, sc.yc(m.span.start.value))),
        gp = extentGp
      )
      extentPart <- annotated(extent, None, Classes.glyphExtent)
      halo <- Option
        .when(m.externalDominant)(
          params(Some(Palette.container), None, width = 0.9)
            .flatMap(dot(haloRadius, _))
            .flatMap(annotated(_, None, Classes.glyphHalo))
        )
        .sequence
      containerGp <- params(
        Some(Palette.container),
        Some(Palette.surface),
        line = if filled then Dash.fill else ig.LineType.Solid
      )
      container <- dot(containerRadius, containerGp).flatMap(
        annotated(_, None, Classes.glyphContainer)
      )
      inner <- Option
        .when(!filled && m.mass > 0.0) {
          val gp =
            if m.externalDominant then params(Some(core), Some(Palette.surface), width = 1.2)
            else params(None, Some(core))
          gp.flatMap(dot(radius(m.mass), _)).flatMap(annotated(_, None, Classes.glyphCore))
        }
        .sequence
      tick <- Option
        .when(matched.contains(true))(
          params(None, Some(Palette.ink2)).flatMap(gp =>
            ig.Grob
              .polygon(
                Vector(
                  px(x - 4, cy + haloRadius + 3),
                  px(x + 4, cy + haloRadius + 3),
                  px(x + 4, cy + haloRadius + 5),
                  px(x - 4, cy + haloRadius + 5)
                ),
                gp = gp
              )
              .flatMap(annotated(_, None, Classes.matchTick))
          )
        )
        .sequence
    yield ig.Grob.group(
      Vector(extentPart) ++ halo.toVector ++ Vector(container) ++ inner.toVector ++ tick.toVector,
      name = Some(name)
    )

  /** Caption lines the shell supplies (identities, caveats, filter state), drawn in the plate's
    * bottom margin so they travel with a cropped figure. The lowering draws them; it composes none.
    * The host gives the box a bottom margin of at least 16px per line.
    */
  private def captionGrobs(
      lines: Vector[String],
      sc: Scales,
      vp: ig.Viewport
  ): Either[GraphicsError, Option[ig.Grob]] =
    val box = sc.box
    Option
      .when(lines.nonEmpty) {
        for
          gp <- params(None, Some(Palette.ink2))
          texts <- lines.zipWithIndex.traverse { (line, i) =>
            ig.Grob.text(
              line,
              px(box.left, box.height - box.bottom + 18 + 14 * i),
              ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
              gp = gp
            )
          }
          g <- annotated(ig.Grob.group(texts, viewport = Some(vp)), None, Classes.caption)
        yield g
      }
      .sequence

  private def ticks(from: Double, to: Double, step: Double): Vector[Double] =
    if to <= from then Vector(from)
    else Vector.iterate(from, ((to - from) / step).toInt + 1)(_ + step)

  private def intersects(start: Double, end: Double, sc: Scales): Boolean =
    end >= sc.rangeStart && start <= sc.rangeEnd

  private def clip(start: Double, end: Double, sc: Scales): Option[(Double, Double)] =
    val clippedStart = math.max(start, sc.rangeStart)
    val clippedEnd = math.min(end, sc.rangeEnd)
    Option.when(clippedStart <= clippedEnd)(clippedStart -> clippedEnd)

  /** Ticks retain the actual recall values at both edges; only their density is display policy. */
  private def timeTicks(
      from: Double,
      to: Double,
      width: Int,
      minimumSpacing: Double
  ): Vector[Double] =
    if to <= from then Vector(from)
    else
      val span = to - from
      val maximumIntervals = math.max(1, math.floor(width / minimumSpacing).toInt)
      val target = math.max(span / maximumIntervals, if span >= 1.0 then 1.0 else 0.0)
      val magnitude = math.pow(10.0, math.floor(math.log10(target)))
      val step =
        Vector(1.0, 2.0, 5.0, 10.0).map(_ * magnitude).find(_ >= target).getOrElse(magnitude)
      val first = math.ceil(from / step) * step
      val interior = Iterator
        .iterate(first)(_ + step)
        .takeWhile(_ < to - 1e-9)
        .filter(_ > from + 1e-9)
        .toVector
      Vector(from) ++ interior ++ Vector(to)

  /** Labels need more room than their ticks. Exact endpoints stay visible; an interior label that
    * would collide with either endpoint is omitted rather than relabelled or shifted.
    */
  private def timeLabels(
      from: Double,
      to: Double,
      width: Int,
      minimumSpacing: Double,
      endpointClearance: Double
  ): Vector[Double] =
    val ticks = timeTicks(from, to, width, minimumSpacing)
    val span = to - from
    if span <= 0.0 then ticks
    else
      ticks.filter { t =>
        t == from || t == to ||
        ((t - from) / span * width >= endpointClearance &&
          (to - t) / span * width >= endpointClearance)
      }

  private def tickLabel(seconds: Double, visibleSpan: Double, endpoint: Boolean): String =
    if visibleSpan < 1.0 then f"$seconds%.2fs"
    else if endpoint && math.abs(seconds - math.rint(seconds)) > 1e-9 then clockFraction(seconds)
    else clock(seconds)

  private def clockFraction(seconds: Double): String =
    val minute = math.floor(seconds / 60.0).toLong
    val second = seconds - minute * 60.0
    val rendered = f"$second%.3f".reverse.dropWhile(_ == '0').reverse.stripSuffix(".")
    s"$minute:${if second < 10.0 then "0" else ""}$rendered"

  /** `m:ss` on a clock, as the reference page prints it. */
  def clock(seconds: Double): String =
    val whole = math.round(seconds)
    val m = whole / 60
    val s = whole % 60
    s"$m:${if s < 10 then "0" else ""}$s"

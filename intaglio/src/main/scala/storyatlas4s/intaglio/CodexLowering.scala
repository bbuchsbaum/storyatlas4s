package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import cats.syntax.all.*
import storymodel4s.view.*

/** Pure lowering of a [[CodexFlow]] to an Intaglio annotation overlay.
  *
  * The DOM owns the prose (ADR 0002 D6); this overlay draws only what the flow already allocated:
  * one row band per `(AnnotationKind, lane)` with the explicit overflow slot as the last row of
  * each kind, and one extent per support span at exact discourse offsets. Each annotation becomes
  * one named group (`data-name` = `AnnotationId`). Until `PlacedCodex` exists an annotation is one
  * fragment; with pagination the group name becomes the fragment id (V-I2).
  *
  * Row 0 is an unnamed rail of the source runs (exact spans, no text), so the overlay is legible on
  * its own; rows for a kind start at `1 + ordinal * (maxLanesPerKind + 1)`.
  */
object CodexLowering:

  def lower(flow: CodexFlow): Either[GraphicsError, ig.Scene] =
    val rowsPerKind = flow.contract.lanePolicy.maxLanesPerKind + 1
    val rowCount = 1 + AnnotationKind.values.length * rowsPerKind
    for
      style <- Style.params
      viewport <- Style.plotViewport(flow.source.canonicalText.length.toDouble, rowCount.toDouble)
      header <- headerGrob(flow, style)
      rail <- flow.runs.traverse(run => band(run.span.start, run.span.endExclusive, 0, style.run))
      rowLabels <- kindLabels(rowsPerKind, style)
      groups <- flow.annotations.traverse(a => annotationGroup(a, flow.lanes, rowsPerKind, style))
      plot = ig.Grob.group(rail ++ rowLabels ++ groups, viewport = Some(viewport))
    yield ig.Scene(Vector(header, plot))

  private def rowOf(kind: AnnotationKind, slot: Option[LaneSlot], rowsPerKind: Int): Int =
    val lane = slot match
      case Some(LaneSlot.Lane(index)) => math.min(index.value, rowsPerKind - 1)
      case Some(LaneSlot.Overflow)    => rowsPerKind - 1
      case None                       => rowsPerKind - 1
    1 + kind.ordinal * rowsPerKind + lane

  private def band(
      start: Int,
      endExclusive: Int,
      row: Int,
      gp: ig.GraphicParams
  ): Either[GraphicsError, ig.Grob] =
    for
      corners <- Vector(
        ig.Point.native(start.toDouble, row + 0.15),
        ig.Point.native(endExclusive.toDouble, row + 0.15),
        ig.Point.native(endExclusive.toDouble, row + 0.85),
        ig.Point.native(start.toDouble, row + 0.85)
      ).sequence
      polygon <- ig.Grob.polygon(corners, gp = gp)
    yield polygon

  private def annotationGroup(
      annotation: TextAnnotation,
      lanes: LaneAllocation,
      rowsPerKind: Int,
      style: Style.Params
  ): Either[GraphicsError, ig.Grob] =
    val row = rowOf(annotation.kind, lanes.slotOf(annotation.id), rowsPerKind)
    for
      name <- GraphicsNames.ofAnnotation(annotation.id)
      bands <- annotation.support.refs.toVector.traverse(ref =>
        band(ref.span.start, ref.span.endExclusive, row, style.annotation)
      )
    yield ig.Grob.group(bands, name = Some(name))

  private def kindLabels(
      rowsPerKind: Int,
      style: Style.Params
  ): Either[GraphicsError, Vector[ig.Grob]] =
    val rows = ("runs", 0) +: AnnotationKind.values.toVector.map(k =>
      k.wireName -> (1 + k.ordinal * rowsPerKind)
    )
    rows.traverse { (label, row) =>
      for
        px <- ig.LengthExpr.native(0.0)
        py <- ig.LengthExpr.native(row + 0.5)
        dx <- ig.ExtentExpr.points(3.0)
        grob <- ig.Grob.text(label, ig.Point(px - dx, py), Style.rowLabelAnchor, gp = style.label)
      yield grob
    }

  private def headerGrob(flow: CodexFlow, style: Style.Params): Either[GraphicsError, ig.Grob] =
    val p = flow.provenance
    val horizon = flow.contract.horizon match
      case EpistemicHorizon.Omniscient       => "omniscient"
      case EpistemicHorizon.ReaderAt(offset) => s"reader-at-$offset"
    val channels = flow.contract.activeKinds.toVector.map(_.wireName).sorted.mkString(",")
    val text =
      s"Narrative Codex overlay · ${flow.source.title.getOrElse(flow.source.id.value)}" +
        s" · horizon $horizon · channels ${if channels.isEmpty then "none" else channels}" +
        s" · basis: ${p.basis.label} · source ${p.sourceChecksum.hex} · configuration ${p.configChecksum.hex}"
    for
      at <- ig.Point.npc(0.02, 0.95)
      grob <- ig.Grob.text(text, at, Style.labelAnchor, gp = style.header)
    yield grob

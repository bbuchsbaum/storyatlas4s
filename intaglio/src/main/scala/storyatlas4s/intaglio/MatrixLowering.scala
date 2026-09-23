package storyatlas4s.intaglio

import _root_.intaglio as ig
import cats.syntax.all.*
import storymodel4s.align.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.MappingMatrix

/** The fixed-cut matrix is already a checked producer projection. Device rows and columns are
  * layout only. Values are copied separately by measure and posterior fidelity; no aggregation,
  * normalization, candidate filtering or chosen-destination substitution occurs here.
  */
object MatrixLowering:
  final case class CellTarget(unit: RecallUnitId, destination: Destination)
  final case class Drawing(
      scene: ig.Scene,
      width: Int,
      height: Int,
      targets: Map[String, CellTarget]
  )

  def values(cell: MappingMatrix.Cell): Vector[(String, String)] =
    def number(value: Double): String = value.toString.stripSuffix(".0")
    val supplied = cell.raw.map((raw, value) =>
      s"Raw ${raw.channel} (${raw.scale}; ${raw.direction})" -> number(value)
    ) ++
      cell.normalized.toVector.map(v => "Normalized score mass" -> number(v)) ++
      cell.transport.toVector.map(v => "Transport mass" -> number(v)) ++
      cell.posterior.map { (state, value) =>
        val fidelity = state match
          case AlignState.Source(_)          => "source"
          case AlignState.Distorted(_, kind) => s"distorted $kind"
          case AlignState.External(kind)     => s"external $kind"
        s"Model posterior: $fidelity" -> number(value)
      }
    if supplied.isEmpty then Vector("Measure" -> "Not supplied") else supplied

  /** Compact text does not carry a color scale: incomparable measure kinds remain labeled. */
  def lower(matrix: MappingMatrix, selected: Set[RecallUnitId]): Either[ig.GraphicsError, Drawing] =
    val columns = matrix.targets.map(t => Destination.Target(t.ref)) ++ matrix.externals.map(
      Destination.External(_)
    )
    def lines(cell: MappingMatrix.Cell): Vector[String] = values(cell).flatMap { (kind, value) =>
      kind.grouped(40).toVector :+ value
    }
    val lineHeight = 20
    val rowHeight = math.max(
      104,
      matrix.rows.flatMap(_.cells).map(lines(_).size * lineHeight + 36).maxOption.getOrElse(104)
    )
    val left = 260
    val top = 28 + columns.map(_.key.grouped(40).size * lineHeight).maxOption.getOrElse(40)
    val cellWidth = 330
    val width = left + math.max(1, columns.size) * cellWidth
    val height = top + math.max(1, matrix.rows.size) * rowHeight + 26
    def point(x: Double, y: Double) = ig.Point.nativeUnsafe(x, y)
    val ink = ig.Rgba.unsafe(0x20, 0x33, 0x2e)
    val pale = ig.Rgba.unsafe(0xf4, 0xf5, 0xf1)
    val white = ig.Rgba.unsafe(0xff, 0xff, 0xff)
    for
      font <- ig.Length.points(9.75)
      textStyle <- ig.GraphicParams.checked(
        fill = Some(ink),
        stroke = None,
        fontSize = font,
        fontFamily = Some("sans-serif")
      )
      border <- ig.GraphicParams.checked(
        stroke = Some(ig.Rgba.unsafe(0xdc, 0xe4, 0xde)),
        fill = Some(white)
      )
      selectedBorder <- ig.GraphicParams.checked(
        stroke = Some(ink),
        fill = Some(pale),
        lineWidth = 2.0
      )
      chosenStyle <- ig.GraphicParams.checked(stroke = Some(ink), fill = None, lineWidth = 2.0)
      xs <- ig.Interval(0, width.toDouble)
      ys <- ig.Interval(0, height.toDouble)
      vp <- ig.Viewport.checked(
        xScale = xs,
        yScale = ys,
        clip = ig.Clip.Off,
        yDirection = ig.YDirection.Down
      )
      headers <- columns.zipWithIndex.traverse { (destination, column) =>
        destination.key
          .grouped(40)
          .toVector
          .zipWithIndex
          .traverse { (label, line) =>
            ig.Grob.text(
              label,
              point(left + column * cellWidth + 10, 24 + line * lineHeight),
              ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
              gp = textStyle
            )
          }
          .map(ig.Grob.group(_))
      }
      rowLabels <- matrix.rows.zipWithIndex.traverse { (row, index) =>
        Vector(
          s"${row.unit.ordinal + 1}. ${row.unit.id.value}",
          row.outcome.processing match
            case ProcessingStatus.Complete                 => "Processing: complete"
            case ProcessingStatus.Failed(_)                => "Processing: failed"
            case ProcessingStatus.ExcludedByInputPolicy(_) => "Processing: excluded",
          s"Localization: ${row.outcome.localization}"
        ).zipWithIndex
          .traverse { (label, line) =>
            ig.Grob.text(
              label,
              point(8, top + index * rowHeight + 28 + line * lineHeight),
              ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
              gp = textStyle
            )
          }
          .map(ig.Grob.group(_))
      }
      cells <- matrix.rows.zipWithIndex
        .flatMap { (row, index) =>
          row.cells.zipWithIndex.map((cell, column) => (row, index, cell, column))
        }
        .traverse { (row, index, cell, column) =>
          val x = left + column * cellWidth
          val y = top + index * rowHeight
          val rendered = s"matrix-r$index-c$column"
          for
            name <- ig.GraphicsName(rendered, "matrix-cell")
            ground <- ig.Grob.polygon(
              Vector(
                point(x, y),
                point(x + cellWidth, y),
                point(x + cellWidth, y + rowHeight),
                point(x, y + rowHeight)
              ),
              if selected.contains(row.unit.id) then selectedBorder else border
            )
            texts <- lines(cell).zipWithIndex.traverse { case (label, line) =>
              ig.Grob.text(
                label,
                point(x + 10, y + 24 + line * lineHeight),
                ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
                gp = textStyle
              )
            }
            decision <-
              if !cell.chosen then Right(Vector.empty[ig.Grob])
              else
                for
                  diamond <- ig.Grob.polygon(
                    Vector(
                      point(x + 12, y + rowHeight - 20),
                      point(x + 18, y + rowHeight - 14),
                      point(x + 12, y + rowHeight - 8),
                      point(x + 6, y + rowHeight - 14)
                    ),
                    chosenStyle
                  )
                  label <- ig.Grob.text(
                    "Chosen destination",
                    point(x + 26, y + rowHeight - 9),
                    ig.Anchor(ig.HJust.Left, ig.VJust.Bottom),
                    gp = textStyle
                  )
                yield Vector(diamond, label)
          yield (
            ig.Grob.group(Vector(ground) ++ texts ++ decision, name = Some(name)),
            rendered -> CellTarget(row.unit.id, cell.destination)
          )
        }
    yield Drawing(
      ig.Scene(Vector(ig.Grob.group(headers ++ rowLabels ++ cells.map(_._1), viewport = Some(vp)))),
      width,
      height,
      cells.map(_._2).toMap
    )

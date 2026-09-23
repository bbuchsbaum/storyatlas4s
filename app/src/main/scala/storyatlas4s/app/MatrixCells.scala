package storyatlas4s.app

import storyatlas4s.intaglio.MatrixLowering
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.view.MappingMatrix

/** Presentation of one matrix cell. Fill encodes exactly one named, single-valued measure on a
  * fixed 0–1 scale, and only when every supplied value of that measure lies in [0, 1]. Posterior
  * vectors are never collapsed into a fill; other measures remain labelled text. A blank cell is
  * "no supplied value", never a zero.
  */
object MatrixCells:
  enum Fill:
    case Normalized
    case TextOnly

  final case class View(shown: String, classes: List[String], spoken: String, chosen: Boolean)

  def fill(matrix: MappingMatrix): Fill =
    val values = matrix.rows.flatMap(_.cells.flatMap(_.normalized))
    if values.nonEmpty && values.forall(v => v >= 0.0 && v <= 1.0) then Fill.Normalized
    else Fill.TextOnly

  /** Bin 0 is a supplied zero; bins 1–5 are fixed thresholds shared by every row and policy. */
  def bin(value: Double): Int =
    if value <= 0.0 then 0
    else if value <= 0.1 then 1
    else if value <= 0.25 then 2
    else if value <= 0.5 then 3
    else if value <= 0.75 then 4
    else 5

  private def abbreviation(kind: String): String =
    if kind.startsWith("Raw") then "raw"
    else if kind.startsWith("Normalized") then "norm"
    else if kind.startsWith("Transport") then "transport"
    else if kind.startsWith("Model posterior") then "post"
    else kind

  def of(matrix: MappingMatrix, cell: Option[MappingMatrix.Cell]): View = cell match
    case None =>
      View("", List("cell-absent"), "not supplied in this result", chosen = false)
    case Some(c) =>
      val values = MatrixLowering.values(c)
      val supplied = values != Vector("Measure" -> "Not supplied")
      val spoken =
        (if supplied then values.map((k, v) => s"$k $v").mkString("; ") else "no supplied value") +
          (if c.chosen then "; supplied decision" else "")
      val decided = if c.chosen then List("decided") else Nil
      val (shown, classes) = (fill(matrix), c.normalized) match
        case (Fill.Normalized, Some(v)) =>
          f"$v%.2f" -> (s"mass-${bin(v)}" :: decided)
        case _ if supplied =>
          val (kind, value) = values.head
          val more = if values.size > 1 then " …" else ""
          s"${abbreviation(kind)} $value$more" -> ("cell-text" :: decided)
        case _ =>
          "" -> ("cell-absent" :: decided)
      View(shown, classes, spoken, c.chosen)

  def fillLegend(matrix: MappingMatrix): String = fill(matrix) match
    case Fill.Normalized =>
      "Fill: normalized score mass, fixed 0–1, not calibrated. Other measures are in the inspector."
    case Fill.TextOnly =>
      "No comparable 0–1 measure supplied: cells show labelled values without fill."

  def recallText(pieces: Vector[(SpanRef, String)]): String =
    if pieces.isEmpty then "Not visible at the current reader horizon"
    else pieces.map(_._2).mkString(" … ")

  def shortLabel(destination: Destination): String =
    val local =
      destination.key.split("[:/]").filter(_.nonEmpty).lastOption.getOrElse(destination.key)
    destination match
      case Destination.Target(_)   => local
      case Destination.External(_) => s"Outside · $local"

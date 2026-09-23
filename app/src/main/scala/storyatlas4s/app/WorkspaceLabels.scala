package storyatlas4s.app

import storyatlas4s.intaglio.MatrixLowering
import storymodel4s.align.*
import storymodel4s.view.MappingMatrix

/** Display choices select supplied quantities. They never combine or normalize them. */
private[app] object WorkspaceLabels:
  enum Measure(val label: String):
    case All extends Measure("All supplied measures")
    case Normalized extends Measure("Normalized score mass")
    case Raw extends Measure("Raw scores")
    case Transport extends Measure("Transport mass")
    case Posterior extends Measure("Model posteriors · separate states")

  def values(cell: MappingMatrix.Cell, measure: Measure): Vector[(String, String)] =
    val supplied = MatrixLowering.values(cell)
    val selected = measure match
      case Measure.All        => supplied
      case Measure.Normalized => supplied.filter(_._1 == "Normalized score mass")
      case Measure.Raw        => supplied.filter(_._1.startsWith("Raw "))
      case Measure.Transport  => supplied.filter(_._1 == "Transport mass")
      case Measure.Posterior  => supplied.filter(_._1.startsWith("Model posterior:"))
    if selected.isEmpty then Vector("Measure" -> "Not supplied") else selected

  def processing(status: ProcessingStatus): String = status match
    case ProcessingStatus.Complete                 => "Complete"
    case ProcessingStatus.Failed(_)                => "Failed"
    case ProcessingStatus.ExcludedByInputPolicy(_) => "Excluded"

  def origin(value: DecisionOrigin): String = value match
    case DecisionOrigin.RawArgmax                => "Supplied argmax"
    case DecisionOrigin.StructuredDecode(policy) => s"Reconstruction · ${policy.value}"
    case DecisionOrigin.GapFill(policy)          => s"Gap fill · ${policy.value}"
    case DecisionOrigin.Abstention(reason)       => s"Abstention · $reason"

  def destination(value: Destination): String = value match
    case Destination.Target(ref)    => ref.key
    case Destination.External(kind) => s"Non-source · $kind"

  def spoken(cell: Option[MappingMatrix.Cell]): String = cell match
    case None        => "not supplied in this result"
    case Some(value) =>
      values(value, Measure.All).map((kind, number) => s"$kind $number").mkString("; ") +
        (if value.chosen then "; supplied decision" else "")

  def tone(fill: MatrixCells.Fill, cell: MappingMatrix.Cell, measure: Measure): String =
    // The sole color scale is explicitly named normalized score mass on fixed 0–1.
    // Raw scores, transport and separate posterior states remain labeled numbers.
    if measure != Measure.Normalized || fill != MatrixCells.Fill.Normalized then ""
    else
      cell.normalized.fold("") { value =>
        s"mass-${MatrixCells.bin(value)}"
      }

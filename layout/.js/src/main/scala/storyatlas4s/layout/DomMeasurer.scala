package storyatlas4s.layout

/** The DOM measurer seam (ADR 0002 D3): identity-stable only, never a publication backend.
  *
  * The real implementation belongs to the app bead (it needs `scalajs-dom`, fonts awaited, and a
  * measurement after layout); the layout module fixes only the contract, so this stub reports
  * itself unavailable rather than guessing widths.
  */
object DomMeasurer extends Measurer:
  val name: String = "dom-measurer/unavailable"

  def measure(run: TextRun, style: TextStyle): Either[LayoutError, RunMetrics] =
    Left(
      LayoutError.Unavailable(
        name,
        s"no DOM measurement in storyatlas4s-layout (${run.text.length} code units, ${style.family})"
      )
    )

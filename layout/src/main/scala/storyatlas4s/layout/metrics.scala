package storyatlas4s.layout

import cats.syntax.all.*
import storymodel4s.core.Checksum
import storymodel4s.view.CodexFlow

/** Integer bounds shared by every metric table so no width sum can silently wrap. */
private[layout] object Bounds:
  val SumReason: String = s"advances sum beyond ${Int.MaxValue}"

  /** True when the sum of `advances` fits an `Int`, computed in `Long`. */
  def sumFits(advances: Vector[Int]): Boolean =
    var total = 0L
    var index = 0
    while index < advances.length && total <= Int.MaxValue do
      total += advances(index)
      index += 1
    total <= Int.MaxValue

  /** `a * b` when it fits an `Int`. */
  def product(a: Int, b: Int): Option[Int] =
    val value = a.toLong * b.toLong
    if value > Int.MaxValue || value < Int.MinValue then None else Some(value.toInt)

/** One nonempty piece of text handed to a measurer; a source run's text, never a copy kept. */
final case class TextRun private (text: String)

object TextRun:
  def of(text: String): Either[LayoutError, TextRun] =
    if text.isEmpty then Left(LayoutError.InvalidSpec("TextRun", "empty"))
    else Right(new TextRun(text))

/** Advance widths of one run, one integer per UTF-16 code unit, in the measurer's layout units.
  *
  * A surrogate pair carries its advance on the high surrogate and zero on the low one, so summing
  * over any code-point-aligned span gives that span's width exactly.
  */
final case class RunMetrics private (advances: Vector[Int], lineHeight: Int, unitsPerPixel: Int)

object RunMetrics:
  def of(
      advances: Vector[Int],
      lineHeight: Int,
      unitsPerPixel: Int
  ): Either[LayoutError, RunMetrics] =
    if advances.isEmpty then Left(LayoutError.InvalidSpec("RunMetrics.advances", "empty"))
    else if advances.exists(_ < 0) then
      Left(LayoutError.InvalidSpec("RunMetrics.advances", "negative advance"))
    else if !Bounds.sumFits(advances) then
      Left(LayoutError.InvalidSpec("RunMetrics.advances", Bounds.SumReason))
    else if lineHeight <= 0 then
      Left(LayoutError.InvalidSpec("RunMetrics.lineHeight", s"$lineHeight is not positive"))
    else if unitsPerPixel <= 0 then
      Left(LayoutError.InvalidSpec("RunMetrics.unitsPerPixel", s"$unitsPerPixel is not positive"))
    else Right(new RunMetrics(advances, lineHeight, unitsPerPixel))

/** The measurement capability (ADR 0002 D3): it turns text into numbers and nothing else.
  *
  * A measurer is the only place platform geometry enters; whatever it returns becomes data the pure
  * paginator consumes. `name` identifies the measurer and its version in the receipt.
  */
trait Measurer:
  def name: String
  def measure(run: TextRun, style: TextStyle): Either[LayoutError, RunMetrics]

/** The measured text of one flow as data: per-code-unit advances over the whole canonical text.
  *
  * `checksum` is the identity of this metric table (measurer, style, units, every advance), so a
  * receipt can say exactly which numbers produced a page break.
  */
final case class TextMetrics private (
    measurer: String,
    style: TextStyle,
    unitsPerPixel: Int,
    lineHeight: Int,
    advances: Vector[Int],
    checksum: Checksum
):
  /** Width of the code units in `[start, endExclusive)`; total for any arguments, and exact because
    * the constructor bounds the sum of all advances by `Int.MaxValue`.
    */
  def widthOf(start: Int, endExclusive: Int): Int =
    var index = math.max(start, 0)
    val end = math.min(endExclusive, advances.length)
    var total = 0
    while index < end do
      total += advances(index)
      index += 1
    total

object TextMetrics:
  def of(
      measurer: String,
      style: TextStyle,
      unitsPerPixel: Int,
      lineHeight: Int,
      advances: Vector[Int]
  ): Either[LayoutError, TextMetrics] =
    if measurer.trim.isEmpty then Left(LayoutError.InvalidSpec("TextMetrics.measurer", "empty"))
    else if measurer.exists(c => c.isWhitespace || c.isControl) then
      Left(
        LayoutError.InvalidSpec("TextMetrics.measurer", "contains whitespace or control characters")
      )
    else if unitsPerPixel <= 0 then
      Left(LayoutError.InvalidSpec("TextMetrics.unitsPerPixel", s"$unitsPerPixel is not positive"))
    else if lineHeight <= 0 then
      Left(LayoutError.InvalidSpec("TextMetrics.lineHeight", s"$lineHeight is not positive"))
    else if advances.exists(_ < 0) then
      Left(LayoutError.InvalidSpec("TextMetrics.advances", "negative advance"))
    else if !Bounds.sumFits(advances) then
      Left(LayoutError.InvalidSpec("TextMetrics.advances", Bounds.SumReason))
    else
      Right(
        new TextMetrics(
          measurer.trim,
          style,
          unitsPerPixel,
          lineHeight,
          advances,
          identity(measurer.trim, style, unitsPerPixel, lineHeight, advances)
        )
      )

  /** Measure every source run of `flow` with `measurer` and assemble the flow-wide table.
    *
    * Runs are measured one at a time so a measurer never sees more than one run's text; their
    * tables must agree on line height and units, or the measurer is inconsistent.
    */
  def measure(
      flow: CodexFlow,
      style: TextStyle,
      measurer: Measurer
  ): Either[LayoutError, TextMetrics] =
    for
      runs <- flow.runs.traverse { run =>
        for
          text <- flow.text(run).left.map(LayoutError.Domain.apply)
          textRun <- TextRun.of(text)
          metrics <- measurer.measure(textRun, style)
          _ <-
            if metrics.advances.length == text.length then Right(())
            else
              Left(
                LayoutError.Measurement(
                  measurer.name,
                  s"run ${run.span} has ${text.length} code units, table has ${metrics.advances.length}"
                )
              )
        yield metrics
      }
      first <- runs.headOption.toRight(LayoutError.Measurement(measurer.name, "flow has no runs"))
      lineHeights = runs.map(_.lineHeight).distinct
      units = runs.map(_.unitsPerPixel).distinct
      _ <-
        if lineHeights.length == 1 && units.length == 1 then Right(())
        else
          Left(
            LayoutError.Measurement(
              measurer.name,
              s"runs disagree on line height (${lineHeights.mkString(",")}) or units (${units.mkString(",")})"
            )
          )
      metrics <- of(
        measurer.name,
        style,
        first.unitsPerPixel,
        first.lineHeight,
        runs.flatMap(_.advances)
      )
    yield metrics

  private def identity(
      measurer: String,
      style: TextStyle,
      unitsPerPixel: Int,
      lineHeight: Int,
      advances: Vector[Int]
  ): Checksum =
    val sep = 0.toChar
    val out = new StringBuilder
    out.append("text-metrics/1").append(sep)
    out.append(measurer).append(sep)
    out.append(style.family).append(sep).append(style.sizePx).append(sep)
    out.append(unitsPerPixel).append(sep).append(lineHeight).append(sep)
    advances.foreach(advance => out.append(advance).append(','))
    Checksum.ofText(out.result())

/** A measurer that is a lookup table: one advance per code point, a default for the rest.
  *
  * Why: a table has no platform, so its metrics are the same on JVM and JS by construction. It is
  * the deterministic publication backend's measurer and the generator behind the pagination laws.
  */
final case class TableMeasurer private[layout] (
    name: String,
    unitsPerEm: Int,
    lineHeightPerEm: Int,
    defaultAdvancePerEm: Int,
    overrides: Map[Int, Int]
) extends Measurer:

  /** Layout units per CSS pixel: advances are `perEm * sizePx`, so the unit is 1/unitsPerEm px. */
  def unitsPerPixel: Int = unitsPerEm

  def measure(run: TextRun, style: TextStyle): Either[LayoutError, RunMetrics] =
    val text = run.text
    val advances = Vector.newBuilder[Int]
    var index = 0
    var tooWide = false
    while index < text.length do
      val codePoint = text.codePointAt(index)
      val advance = advancePerEm(codePoint).toLong * style.sizePx
      if advance > Int.MaxValue then tooWide = true
      advances += advance.toInt
      if Character.charCount(codePoint) == 2 then
        advances += 0
        index += 2
      else index += 1
    val lineHeight = lineHeightPerEm.toLong * style.sizePx
    if tooWide || lineHeight > Int.MaxValue then
      Left(LayoutError.Measurement(name, s"advances at ${style.sizePx}px exceed ${Int.MaxValue}"))
    else RunMetrics.of(advances.result(), lineHeight.toInt, unitsPerEm)

  def advancePerEm(codePoint: Int): Int =
    if codePoint == '\n'.toInt then 0 else overrides.getOrElse(codePoint, defaultAdvancePerEm)

object TableMeasurer:
  def of(
      name: String,
      unitsPerEm: Int,
      lineHeightPerEm: Int,
      defaultAdvancePerEm: Int,
      overrides: Map[Int, Int] = Map.empty
  ): Either[LayoutError, TableMeasurer] =
    if name.trim.isEmpty || name.exists(_.isWhitespace) then
      Left(LayoutError.InvalidSpec("TableMeasurer.name", "empty or contains whitespace"))
    else if unitsPerEm <= 0 then
      Left(LayoutError.InvalidSpec("TableMeasurer.unitsPerEm", s"$unitsPerEm is not positive"))
    else if lineHeightPerEm <= 0 then
      Left(
        LayoutError
          .InvalidSpec("TableMeasurer.lineHeightPerEm", s"$lineHeightPerEm is not positive")
      )
    else if defaultAdvancePerEm < 0 then
      Left(LayoutError.InvalidSpec("TableMeasurer.defaultAdvancePerEm", "negative"))
    else if overrides.exists((_, advance) => advance < 0) then
      Left(LayoutError.InvalidSpec("TableMeasurer.overrides", "negative advance"))
    else Right(new TableMeasurer(name, unitsPerEm, lineHeightPerEm, defaultAdvancePerEm, overrides))

/** The fixed monospace metric: every code point 0.6 em wide, lines 1.2 em tall, 1000 units per em.
  *
  * It is the default publication measurer because it needs no font on any platform and a reader can
  * check a line break by counting characters.
  */
object MonospaceMeasurer:
  val UnitsPerEm: Int = 1000
  val CellPerEm: Int = 600
  val LineHeightPerEm: Int = 1200

  val instance: TableMeasurer =
    new TableMeasurer("monospace-table/1", UnitsPerEm, LineHeightPerEm, CellPerEm, Map.empty)

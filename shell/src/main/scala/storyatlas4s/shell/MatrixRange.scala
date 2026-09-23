package storyatlas4s.shell

/** An inclusive, zero-based range of matrix rows or columns that is actually displayed. */
final case class MatrixSpan(first: Int, last: Int, total: Int):
  def contains(index: Int): Boolean = index >= first && index <= last
  def size: Int = if total == 0 then 0 else last - first + 1

/** Viewport arithmetic for the matrix camera. Every result is a display coordinate only: moving a
  * range never changes the mapping result, the target cut, a value, the focus or the selection.
  */
object MatrixRange:
  /** A span clamped to a non-empty inventory; an empty inventory has no displayable range. */
  def span(first: Int, last: Int, total: Int): MatrixSpan =
    if total <= 0 then MatrixSpan(0, -1, 0)
    else
      val a = clamp(first, total)
      MatrixSpan(a, math.max(a, clamp(last, total)), total)

  /** Exact displayed range in one-based reader terms, e.g. "Rows 5–12 of 84". */
  def label(noun: String, span: MatrixSpan): String =
    if span.total == 0 then s"No ${noun.toLowerCase}"
    else s"$noun ${span.first + 1}–${span.last + 1} of ${span.total}"

  /** First index of the adjacent page. A page is the displayed size, at least one. */
  def page(span: MatrixSpan, forward: Boolean): Int =
    if span.total == 0 then 0
    else
      val step = math.max(1, span.size)
      clamp(if forward then span.first + step else span.first - step, span.total)

  /** Neutral navigator blocks covering every index once, in order. With at most `maxBlocks` indices
    * each block is one index; otherwise contiguous ranges of equal length (the last may be
    * shorter). Blocks carry no quantity: they only name a range to reveal.
    */
  def blocks(total: Int, maxBlocks: Int): Vector[MatrixSpan] =
    if total <= 0 || maxBlocks <= 0 then Vector.empty
    else
      val size = (total + maxBlocks - 1) / maxBlocks
      Vector
        .range(0, total, size)
        .map(start => MatrixSpan(start, math.min(total - 1, start + size - 1), total))

  private def clamp(index: Int, total: Int): Int = math.max(0, math.min(total - 1, index))

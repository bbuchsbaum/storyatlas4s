package storyatlas4s.cli

import storymodel4s.core.{Address, SurfaceUnitKind, TextSpan}
import storymodel4s.story.ContextKind
import storymodel4s.view.*

/** The reading surface's own composition: sentences, and what each run of words carries.
  *
  * The first version of this pane painted the wrong layer. It underlined every recorded absence on
  * the words, and since this model records one kind of failure sixty-five times, the page came out
  * carpeted in identical marks that all said the same thing about the model's bookkeeping and
  * nothing about the story. A uniform wash makes a page look analysed while carrying no
  * discriminating information.
  *
  * What is painted now is what discriminates, and all of it is about the material:
  *
  *   - **a context frame that is not the narrated world**, on exactly the words inside it, so a
  *     reader sees which sentences are spoken rather than narrated;
  *   - **an entity mention**, so the cast is visible moving through the text;
  *   - **the shared selection**, so the one object every face carries is legible in the prose.
  *
  * The uniform channels — one claim per situation, one unsatisfied law per situation — become
  * per-sentence tallies in the gutter, which is where a count belongs.
  */
private[cli] object Reading:

  /** Treatment names, which are also the CSS classes. Each is a shape or a weight, never a hue
    * alone: a tint carries a bracket, a mention carries an underline, the selection an outline.
    */
  val Speech: String = "sp"
  val Mention: String = "en"
  val Selected: String = "sl"

  /** One row of the pane: a sentence, and the material between it and the next sentence.
    *
    * Rows are cut this way so they tile the canonical text exactly. A sentence's own span does not
    * include the space that follows it, and a pane built from sentence spans alone would silently
    * drop that space — which is a character of the source, and the source is the observation.
    */
  final case class Row(mark: MarkId, address: Address, ordinal: Int, span: TextSpan)

  /** One run of a row's text and the treatments covering it. */
  final case class Run(text: String, classes: Vector[String])

  /** Rows over the whole text, from the compiled sentence marks of a surface-bearing scene. */
  def rows(sentences: Vector[VisualPrimitive.SurfaceUnit], length: Int): Vector[Row] =
    val ordered = sentences
      .filter(_.kind == SurfaceUnitKind.Sentence)
      .sortBy(m => (m.span.start, m.span.endExclusive, m.unitOrdinal))
    ordered.zipWithIndex.map { (mark, index) =>
      val end = ordered.lift(index + 1).map(_.span.start).getOrElse(length)
      val span = TextSpan
        .of(mark.span.start, math.max(end, mark.span.endExclusive))
        .getOrElse(mark.span)
      Row(mark.identity.mark, mark.identity.address, mark.unitOrdinal, span)
    }

  /** The painted spans of a flow: what each treatment covers, over the whole text.
    *
    * `speechFrames` maps a context annotation's target to its kind, taken from the Atlas scene's
    * own context bands, so a frame is called speech only when the model calls it that.
    */
  def painted(
      flow: CodexFlow,
      speechFrames: Map[Address, ContextKind],
      selected: Set[AnnotationId]
  ): Vector[(TextSpan, String)] =
    flow.annotations.flatMap { annotation =>
      val treatment = annotation.kind match
        case AnnotationKind.Context =>
          speechFrames
            .get(annotation.target)
            .filter(_ != ContextKind.NarratedWorld)
            .map(_ => Speech)
        case AnnotationKind.Entity => Some(Mention)
        case _                     => None
      val base =
        treatment.toVector.flatMap(name => annotation.support.spans.toVector.map(_ -> name))
      val focus =
        if selected.contains(annotation.id) then
          annotation.support.spans.toVector.map(_ -> Selected)
        else Vector.empty
      base ++ focus
    }

  /** A row's text cut at every treatment boundary inside it.
    *
    * Segmenting rather than nesting is what makes overlapping treatments safe: an entity mention
    * inside a speech frame, or a selection crossing both, needs no containment to hold. Every
    * character of the row appears in exactly one run, so concatenating the runs is the row and
    * concatenating the rows is the canonical text (V-T2).
    */
  def runs(row: Row, text: String, painted: Vector[(TextSpan, String)]): Vector[Run] =
    val here = painted.filter((span, _) => span.overlaps(row.span))
    val cuts = (Vector(row.span.start, row.span.endExclusive) ++ here.flatMap((span, _) =>
      Vector(span.start, span.endExclusive)
    )).filter(o => o >= row.span.start && o <= row.span.endExclusive).distinct.sorted
    cuts
      .sliding(2)
      .collect { case Vector(from, to) if to > from => (from, to) }
      .map { (from, to) =>
        val classes = here
          .collect { case (span, name) if span.start < to && span.endExclusive > from => name }
          .distinct
          .sorted
        Run(text.substring(from, to), classes)
      }
      .toVector

  /** How many annotations of `kind` touch this row: a count belongs in a gutter, not on the words.
    */
  def tally(flow: CodexFlow, row: Row, kind: AnnotationKind): Int =
    flow.annotations.count(a =>
      a.kind == kind && a.support.spans.toVector.exists(_.overlaps(row.span))
    )

  /** The context frames the Atlas scene named, so the pane can tell speech from narration without
    * guessing at an address.
    */
  def frames(scene: NarrativeScene): Map[Address, ContextKind] =
    scene.marks.collect { case b: VisualPrimitive.ContextBand =>
      b.identity.address -> b.kind
    }.toMap

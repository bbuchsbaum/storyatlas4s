package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.GraphicsError
import storyatlas4s.layout.FragmentId
import storymodel4s.view.{AnnotationId, MarkId}

/** The one naming rule of the renderer boundary (ADR 0002 §6).
  *
  * A rendered element is named by the identity of the **mark, annotation, or placed fragment** it
  * draws, never by an [[storymodel4s.core.Address]]: one address yields many marks across zoom
  * levels and many annotations, so addresses are not injective within a figure. Semantic selection
  * stays `Set[Address]` and resolves a name through `SceneNavigation` / `NavigationIndex`.
  */
object GraphicsNames:
  def ofMark(id: MarkId): Either[GraphicsError, ig.GraphicsName] =
    ig.GraphicsName(id.value, "mark")

  def ofAnnotation(id: AnnotationId): Either[GraphicsError, ig.GraphicsName] =
    ig.GraphicsName(id.value, "annotation")

  /** A placed piece of an annotation (V-I2): the name a page overlay shares with the DOM text rail.
    */
  def ofFragment(id: FragmentId): Either[GraphicsError, ig.GraphicsName] =
    ig.GraphicsName(id.value, "fragment")

  /** Every name in a scene in document order, descending through groups. */
  def collect(scene: ig.Scene): Vector[ig.GraphicsName] =
    scene.grobs.flatMap(collect)

  def collect(grob: ig.Grob): Vector[ig.GraphicsName] =
    grob.name.toVector ++ grob.children.flatMap(collect)

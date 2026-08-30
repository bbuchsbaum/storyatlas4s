package storyatlas4s.app

import munit.FunSuite
import storymodel4s.core.Address

/** Direct and proxy interaction states must produce distinct, truthful DOM attributes. */
class SvgDomSuite extends FunSuite:
  private def address(value: String): Address =
    Address.parse(value).fold(error => fail(error.message), identity)

  private val original = address("story/situation/wog:sit:battle")
  private val visible = address("story/segment/wog:seg:sc2c-journey-battle")

  test("direct selection and focus claim the directly represented address"):
    val presentation = SvgDom.presentationFor(
      "battle mark",
      Vector(
        InteractionDecoration(
          "mark",
          InteractionRole.Selection,
          SemanticRepresentation.Direct(original)
        ),
        InteractionDecoration(
          "mark",
          InteractionRole.Focus,
          SemanticRepresentation.Direct(original)
        )
      )
    )

    assert(presentation.directlySelected)
    assert(presentation.directlyFocused)
    assertEquals(presentation.selectionProxyFor, Vector.empty)
    assertEquals(presentation.focusProxyFor, Vector.empty)
    assert(presentation.label.contains(s"directly selected ${original.render}"))
    assert(presentation.label.contains(s"semantic focus ${original.render}"))

  test("an ancestor proxy explains the preserved address without claiming direct state"):
    val presentation = SvgDom.presentationFor(
      "scene mark",
      Vector(
        InteractionDecoration(
          "mark",
          InteractionRole.Selection,
          SemanticRepresentation.Proxy(original, visible)
        ),
        InteractionDecoration(
          "mark",
          InteractionRole.Focus,
          SemanticRepresentation.Proxy(original, visible)
        )
      )
    )

    assert(!presentation.directlySelected)
    assert(!presentation.directlyFocused)
    assertEquals(presentation.selectionProxyFor, Vector(original.render))
    assertEquals(presentation.focusProxyFor, Vector(original.render))
    assert(
      presentation.label.contains(
        s"selection proxy for ${original.render} via ${visible.render}"
      )
    )
    assert(
      presentation.label.contains(s"focus proxy for ${original.render} via ${visible.render}")
    )

  test("one visible target may truthfully be direct for one address and proxy for another"):
    val presentation = SvgDom.presentationFor(
      "shared mark",
      Vector(
        InteractionDecoration(
          "mark",
          InteractionRole.Selection,
          SemanticRepresentation.Direct(visible)
        ),
        InteractionDecoration(
          "mark",
          InteractionRole.Selection,
          SemanticRepresentation.Proxy(original, visible)
        )
      )
    )

    assert(presentation.directlySelected)
    assertEquals(presentation.selectionProxyFor, Vector(original.render))
    assert(presentation.selectionIsComposite)
    assert(!presentation.focusIsComposite)
    assert(presentation.label.contains(s"directly selected ${visible.render}"))
    assert(presentation.label.contains(s"selection proxy for ${original.render}"))

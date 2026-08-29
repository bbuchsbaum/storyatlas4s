package storyatlas4s.app

import munit.FunSuite
import storymodel4s.core.Addressable
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.StoryRef

/** Direct and proxy interaction states must produce distinct, truthful DOM attributes. */
class SvgDomSuite extends FunSuite:
  private val original = Addressable[StoryRef].address(StoryRef.Situation(Wog.S.battle))
  private val visible = Addressable[StoryRef].address(StoryRef.Segment(Wog.G.sc2c))

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
    assert(presentation.label.contains(s"directly selected ${visible.render}"))
    assert(presentation.label.contains(s"selection proxy for ${original.render}"))

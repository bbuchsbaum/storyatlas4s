package storyatlas4s.shell

import munit.FunSuite
import storyatlas4s.layout.MonospaceMeasurer
import storymodel4s.core.Address
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.view.CodexLens

/** The one route from a host's hit to a semantic change, and the presentation every host draws.
  *
  * A host reports only a rendered name. These laws hold on the JVM and on Scala.js, so any host —
  * the DOM, a canvas with Intaglio picking, a native toolkit — inherits them unchanged.
  */
class ActivationSuite extends FunSuite:
  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private def address(value: String): Address =
    Address.parse(value).fold(error => fail(error.message), identity)

  private val a = address("story/situation/wog:sit:battle")
  private val b = address("story/segment/wog:seg:sc2c-journey-battle")
  private val start = ViewChoice.initial.copy(measurer = MeasurerChoice.Monospace)

  private lazy val compiled: Compiled =
    ok(AppCompiler.compile(Wog.model, start, MonospaceMeasurer.instance))

  test("a plain activation replaces the selection and focuses the address"):
    val chosen = start.copy(selection = Set(b), focus = Some(b)).activate(a, extend = false)
    assertEquals(chosen.selection, Set(a))
    assertEquals(chosen.focus, Some(a))

  test("an extending activation toggles the address and still focuses it"):
    val added = start.copy(selection = Set(b)).activate(a, extend = true)
    assertEquals(added.selection, Set(a, b))
    assertEquals(added.focus, Some(a))
    val removed = added.activate(a, extend = true)
    assertEquals(removed.selection, Set(b))
    assertEquals(removed.focus, Some(a))

  test("clearing drops focus and selection and keeps every other choice"):
    val busy = start.copy(lens = CodexLens.Reading, selection = Set(a, b), focus = Some(a))
    assertEquals(busy.cleared, busy.copy(selection = Set.empty, focus = None))

  test("a rendered name resolves only through the plate's checked index"):
    val targets = compiled.atlasTargets
    val rendered = targets.renderedNames.toVector.sorted
    assert(rendered.nonEmpty)
    rendered.foreach { name =>
      val expected = targets.resolve(name).map(_._2).getOrElse(fail(s"$name does not resolve"))
      val chosen = Activation(start, targets, name, extend = false)
      assertEquals(chosen.map(_.selection), Some(Set(expected)), name)
      assertEquals(chosen.flatMap(_.focus), Some(expected), name)
    }
    assertEquals(Activation(start, targets, "not-a-rendered-name", extend = false), None)
    // A fragment name is not an Atlas target: the wrong plate's index refuses it.
    compiled.fragmentTargets.renderedNames.headOption.foreach { fragment =>
      assertEquals(Activation(start, targets, fragment, extend = false), None)
    }

  test("every rendered target has a presentation, and only decorated ones claim a state"):
    val selected = compiled.atlasTargets.renderedNames.toVector.sorted.head
    val (_, address) = compiled.atlasTargets.resolve(selected).getOrElse(fail("no target"))
    val c = ok(
      AppCompiler.compile(
        Wog.model,
        start.copy(selection = Set(address), focus = Some(address)),
        MonospaceMeasurer.instance
      )
    )
    val label =
      (name: storymodel4s.view.MarkId, at: Address) => s"mark ${name.value} → ${at.render}"
    val presentations =
      ok(InteractionPresentation.forTargets(c.atlasTargets, c.atlasInteractions, label))
    assertEquals(presentations.keySet, c.atlasTargets.renderedNames)
    presentations.foreach { (rendered, presentation) =>
      val (name, at) = c.atlasTargets.resolve(rendered).getOrElse(fail(rendered))
      val states = c.atlasInteractions.filter(_.target == name)
      assertEquals(presentation, InteractionPresentation.of(label(name, at), states), rendered)
      if states.isEmpty then
        assertEquals(presentation.label, label(name, at))
        assert(!presentation.directlySelected && !presentation.directlyFocused, rendered)
    }
    assert(presentations.values.exists(p => p.directlySelected || p.selectionProxyFor.nonEmpty))

  test("a decoration naming the wrong address is refused, never presented"):
    val targets = compiled.atlasTargets
    val name = targets.names.toVector.sortBy(_.value).head
    val actual = targets.addressOf(name).getOrElse(fail("no address"))
    val wrong = if actual == a then b else a
    val claimed = InteractionDecoration(
      name,
      InteractionRole.Selection,
      SemanticRepresentation.Direct(wrong)
    )
    val label = (n: storymodel4s.view.MarkId, at: Address) => s"${n.value} ${at.render}"
    assertEquals(
      InteractionPresentation.forTargets(targets, Vector(claimed), label),
      Left(
        InteractionError.TargetIdentityMismatch(
          InteractionSurface.Atlas,
          targets.rendered(name).getOrElse(fail("unrendered")),
          wrong,
          actual
        )
      )
    )

  test("a plate pairs only with the index of exactly the names drawn on it"):
    val c = compiled
    assertEquals(TargetedPlate.of(c.atlas.plate, c.atlas.targets), Right(c.atlas))
    c.pages.headOption.foreach { page =>
      val mismatch = TargetedPlate.of(c.atlas.plate, page.targets)
      assert(mismatch.isLeft, "a Codex page index cannot be paired with the Atlas plate")
    }

  test("a hit resolves through the plate it landed on, never another plate's index"):
    val rendered = compiled.atlas.targets.renderedNames.toVector.sorted.head
    val expected = compiled.atlas.targets.resolve(rendered).map(_._2)
    assertEquals(
      Activation(start, compiled.atlas, rendered, extend = false).flatMap(_.focus),
      expected
    )
    compiled.pages.headOption.foreach { page =>
      assertEquals(Activation(start, page.overlay, rendered, extend = false), None)
    }

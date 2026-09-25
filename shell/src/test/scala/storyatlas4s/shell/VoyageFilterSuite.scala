package storyatlas4s.shell

import munit.FunSuite

class VoyageFilterSuite extends FunSuite:
  import VoyageShellFixture.*

  import VoyageFilter.*

  test("an unset filter matches nothing and dims nothing") {
    assertEquals(VoyageFilter.none.matches(scene), Map.empty)
    assertEquals(VoyageFilter.none.step(scene, None, forward = true), Step.NotSet)
  }

  test("each criterion matches exactly the marks that carry its fact") {
    def only(c: Criterion) = VoyageFilter.none.toggled(c).matches(scene).keySet
    assertEquals(only(Criterion.DecodeFilled), Set(u1))
    assertEquals(only(Criterion.DecodeBound), Set(u5))
    assertEquals(only(Criterion.ExternalDominant), Set(u5))
    assertEquals(only(Criterion.GroupGrain), Set(u4))
    assertEquals(only(Criterion.Untimed), Set(u3))
    assertEquals(only(Criterion.Unanchored), Set(u2))
  }

  test("each supplied chip count equals the units its criterion filters") {
    Criterion.values.foreach { c =>
      val n = count(scene, c)
      assertEquals(n.value, VoyageFilter.none.toggled(c).matches(scene).size, c.label)
      assertEquals(n.countedByView, c == Criterion.GroupGrain, c.label)
    }
  }

  test("thresholds read the supplied argmax mass, external mass and localizability") {
    val low = VoyageFilter(argmaxMassBelow = Some(0.4)).matches(scene)
    assertEquals(low.keySet, Set(u5), "u5's argmax a carries 0.3; u1's carries 0.5")
    assertEquals(low(u5), Vector("argmax mass below 0.4"))
    assertEquals(VoyageFilter(externalMassAbove = Some(0.5)).matches(scene).keySet, Set(u5))
    assertEquals(
      VoyageFilter(localizabilityBelow = Some(1.01)).matches(scene).keySet,
      Set(u1, u4, u5)
    )
  }

  test("any unions the criteria; all requires every set criterion") {
    val both = VoyageFilter(criteria = Set(Criterion.DecodeBound, Criterion.ExternalDominant))
    assertEquals(both.matches(scene)(u5), Vector("decode-bound", "external-dominant"))
    val any = VoyageFilter(criteria = Set(Criterion.DecodeBound, Criterion.DecodeFilled))
    assertEquals(any.matches(scene).keySet, Set(u1, u5))
    assertEquals(any.copy(combine = Combine.All).matches(scene), Map.empty)
    assertEquals(both.copy(combine = Combine.All).matches(scene).keySet, Set(u5))
  }

  test("stepping through matches says why it cannot move") {
    val f = VoyageFilter(criteria = Set(Criterion.DecodeBound, Criterion.DecodeFilled))
    assertEquals(f.step(scene, None, forward = true), Step.To(u1))
    assertEquals(f.step(scene, Some(u1), forward = true), Step.To(u5))
    assertEquals(f.step(scene, Some(u5), forward = true), Step.NoFurther)
    assertEquals(f.step(scene, Some(u1), forward = false), Step.NoEarlier)
    assertEquals(
      VoyageFilter.none.toggled(Criterion.Untimed).step(scene, None, true),
      Step.AllUntimed(1)
    )
    val nothing = VoyageFilter(externalMassAbove = Some(0.99))
    assertEquals(nothing.step(scene, None, forward = true), Step.NoMatch)
  }

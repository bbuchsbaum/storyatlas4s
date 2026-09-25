package storyatlas4s.shell

import munit.FunSuite

class VoyageExportSuite extends FunSuite:
  import VoyageShellFixture.*

  private def body(tsv: String): Vector[Vector[String]] =
    tsv.split("\n").toVector.filterNot(_.startsWith("#")).map(_.split("\t", -1).toVector)

  test("numbers are the shortest plain decimal, identical on every platform") {
    assertEquals(VoyageExport.number(1.0), "1")
    assertEquals(VoyageExport.number(0.0001), "0.0001")
    assertEquals(VoyageExport.number(1.0e-7), "0.0000001")
    assertEquals(VoyageExport.number(0.1 + 0.2), "0.30000000000000004")
    assertEquals(VoyageExport.number(0.0), "0")
  }

  test("the units export has one row per unit, supplied values unrounded, kinds kept apart") {
    val rows = body(VoyageExport.units(scene, VoyageFilter.none))
    assertEquals(rows.head, VoyageExport.unitColumns)
    val data = rows.tail
    assertEquals(data.size, scene.units.size)
    assert(data.forall(_.size == VoyageExport.unitColumns.size))
    val ix = VoyageExport.unitColumns.zipWithIndex.toMap
    val byId = data.map(r => r(ix("unit_id")) -> r).toMap
    assertEquals(byId("u1")(ix("origin")), "decode-filled")
    assertEquals(byId("u1")(ix("anchor_mass")), "0")
    assertEquals(byId("u1")(ix("argmax_mass")), "0.5")
    assertEquals(byId("u5")(ix("external_dominant")), "true")
    assertEquals(byId("u4")(ix("grain")), "group")
    assertEquals(byId("u2")(ix("kind")), "unanchored")
    assertEquals(byId("u2")(ix("external_mass")), "1")
    assertEquals(byId("u2")(ix("anchor_mass")), "", "an unanchored unit carries no anchor mass")
    assertEquals(byId("u3")(ix("kind")), "untimed")
    assert(
      byId("u3").slice(ix("onset_s"), ix("matched")).forall(_.isEmpty),
      "an untimed unit carries no onset, anchor, origin or mass"
    )
    assertEquals(byId("u1")(ix("matched")), "", "no filter, no match column value")
  }

  test("the header names the provenance and every derived column") {
    val tsv = VoyageExport.units(scene, VoyageFilter.none)
    assert(tsv.contains(s"# source checksum: ${scene.provenance.sourceChecksum.hex}"))
    assert(tsv.contains("not calibrated confidence"))
    assert(tsv.contains("# derived by this view: timed (onset present), admitted_anchors"))
    assert(tsv.contains("# filter: none set"))
  }

  test("matched rows equal the filter's matches") {
    val f = VoyageFilter.none.toggled(VoyageFilter.Criterion.DecodeBound)
    val rows = body(VoyageExport.units(scene, f)).tail
    val ix = VoyageExport.unitColumns.zipWithIndex.toMap
    assertEquals(rows.count(_(ix("matched")) == "true"), f.matches(scene).size)
    assertEquals(rows.find(_(ix("unit_id")) == "u5").map(_(ix("matched_by"))), Some("decode-bound"))
  }

  test("the anchors export lists every admitted anchor in supplied order, and never a fill") {
    val rows = body(VoyageExport.anchors(scene, VoyageFilter.none))
    assertEquals(rows.head, VoyageExport.anchorColumns)
    val data = rows.tail
    val ix = VoyageExport.anchorColumns.zipWithIndex.toMap
    val u1 = data.filter(_(ix("unit_id")) == "u1")
    assertEquals(
      u1.map(_(ix("ref"))),
      Vector(a.key, b.key),
      "a 0.5 then b 0.2; the fill at c has no row"
    )
    assertEquals(u1.map(_(ix("rank"))), Vector("1", "2"))
    assertEquals(u1.map(_(ix("is_argmax"))), Vector("true", "false"))
    assertEquals(u1.map(_(ix("is_placed"))), Vector("false", "false"))
    val u5 = data.filter(_(ix("unit_id")) == "u5")
    assertEquals(u5.map(_(ix("mass"))), Vector("0.3", "0.1"))
    assertEquals(u5.map(_(ix("is_placed"))), Vector("false", "true"))
    assert(data.forall(r => r(ix("unit_id")) != "u2" && r(ix("unit_id")) != "u3"))
  }

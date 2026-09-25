package storyatlas4s.shell

import storymodel4s.align.SourceNodeRef
import storymodel4s.view.{AnchorOrigin, VoyageMark, VoyageScene}

/** The Recall Voyage exports (workshop ruling 15): one row per unit, and one row per admitted
  * anchor in the supplied rank order. Values are the scene's own, unrounded; every column this view
  * derives is named in the header; nothing is invented (no address format, no distance, no
  * recomputed quantity). Numbers print identically on the JVM and in the browser.
  */
object VoyageExport:

  /** A double as the shortest decimal that reads back to it, in plain notation, identically on
    * every platform: `Double.toString` spells `1.0E-4` on the JVM and `0.0001` in JavaScript, and
    * whole numbers as `1.0` and `1`.
    */
  def number(d: Double): String =
    if d == 0.0 then "0"
    else BigDecimal(d.toString).bigDecimal.stripTrailingZeros.toPlainString

  private def header(scene: VoyageScene, title: String, filter: VoyageFilter, derived: String) =
    val prov = scene.provenance
    Vector(
      s"# StoryAtlas recall voyage export: $title",
      s"# basis: ${prov.basis.label}; compiler: ${prov.compilerVersion}",
      s"# source checksum: ${prov.sourceChecksum.hex}; configuration checksum: ${prov.configChecksum.hex}",
      "# time base: onset_s is seconds from the start of the recall recording (word onset); spans are seconds on the supplied source clock",
      "# masses are model posterior mass as supplied, unrounded; they are not calibrated confidence",
      s"# filter: ${filterState(filter)}",
      s"# derived by this view: $derived; every other column is supplied"
    )

  private def filterState(f: VoyageFilter): String =
    if !f.isSet then "none set"
    else
      (f.criteria.toVector.sortBy(_.ordinal).map(_.label) ++
        f.argmaxMassBelow.map(t => s"argmax mass below ${number(t)}") ++
        f.externalMassAbove.map(t => s"external mass above ${number(t)}") ++
        f.localizabilityBelow.map(t => s"localizability below ${number(t)}"))
        .mkString(s"${f.combine.toString.toLowerCase}: ", ", ", "")

  private def grain(level: Int): String = if level > 0 then "group" else "segment"

  private def origin(o: AnchorOrigin): String = o match
    case AnchorOrigin.PosteriorArgmax => "argmax"
    case AnchorOrigin.DecodeBound     => "decode-bound"
    case AnchorOrigin.DecodeFilled    => "decode-filled"

  private def ref(r: SourceNodeRef): String = r.key

  private def row(cells: Vector[String]): String =
    cells.map(_.replace('\t', ' ').replace('\n', ' ')).mkString("\t")

  val unitColumns: Vector[String] = Vector(
    "unit_ordinal",
    "unit_id",
    "kind",
    "timed",
    "onset_s",
    "origin",
    "grain",
    "placed_ref",
    "placed_start_s",
    "placed_end_s",
    "anchor_mass",
    "argmax_ref",
    "argmax_mass",
    "source_mass",
    "external_mass",
    "external_dominant",
    "localizability",
    "admitted_anchors",
    "matched",
    "matched_by"
  )

  /** One row per unit in recall order. `timed` (an onset is present), `admitted_anchors` (the size
    * of the ranked list), `matched` and `matched_by` (the filter) are derived by this view.
    */
  def units(scene: VoyageScene, filter: VoyageFilter): String =
    val matches = filter.matches(scene)
    val rows = scene.units.sortBy(_.ordinal).map { u =>
      val mark = scene.marks.find(m => m.unit == u.id && !m.isInstanceOf[VoyageMark.Alternative])
      val admitted = VoyagePosterior.forUnit(scene, u.id).map(_.candidates.size)
      val m = matches.get(u.id)
      val matched = if filter.isSet then m.nonEmpty.toString else ""
      val matchedBy = m.fold("")(_.mkString("|"))
      val onset = u.onset.fold("")(t => number(t.value))
      val timed = u.onset.nonEmpty.toString
      mark match
        case Some(a: VoyageMark.UnitAnchor) =>
          Vector(
            u.ordinal.toString,
            u.id.value,
            "anchor",
            timed,
            onset,
            origin(a.origin),
            grain(a.level),
            ref(a.anchor),
            number(a.span.start.value),
            number(a.span.end.value),
            number(a.mass),
            a.argmax.fold("")(ref),
            VoyageFilter.argmaxMass(scene, a).fold("")(number),
            number(a.sourceMass),
            number(a.externalMass),
            a.externalDominant.toString,
            a.localizability.fold("")(number),
            admitted.fold("")(_.toString),
            matched,
            matchedBy
          )
        case Some(x: VoyageMark.Unanchored) =>
          Vector(u.ordinal.toString, u.id.value, "unanchored", timed, onset) ++
            Vector.fill(9)("") ++ Vector(number(x.externalMass), "", "", "", matched, matchedBy)
        case _ =>
          Vector(u.ordinal.toString, u.id.value, "untimed", timed, onset) ++
            Vector.fill(13)("") ++ Vector(matched, matchedBy)
    }
    (header(
      scene,
      "units",
      filter,
      "timed (onset present), admitted_anchors (size of the ranked list), matched and matched_by (the filter)"
    ) ++ Vector(row(unitColumns)) ++ rows.map(row)).mkString("", "\n", "\n")

  val anchorColumns: Vector[String] = Vector(
    "unit_ordinal",
    "unit_id",
    "rank",
    "ref",
    "grain",
    "group",
    "start_s",
    "end_s",
    "mass",
    "is_argmax",
    "is_placed"
  )

  /** One row per admitted anchor with posterior mass, in the supplied order (descending mass, ties
    * by source key). `rank` (the row's position), `is_argmax` and `is_placed` are derived. A decode
    * fill's placement has mass 0 and is not an admitted anchor, so it has no row here.
    */
  def anchors(scene: VoyageScene, filter: VoyageFilter): String =
    val spans = scene.timeline.nodes.map(n => n.ref -> n).toMap
    val rows = scene.units.sortBy(_.ordinal).flatMap { u =>
      VoyagePosterior.forUnit(scene, u.id).toVector.flatMap { p =>
        p.candidates.zipWithIndex.map { (c, i) =>
          val node = spans.get(c.ref)
          Vector(
            u.ordinal.toString,
            u.id.value,
            (i + 1).toString,
            ref(c.ref),
            node.fold("")(n => grain(n.level)),
            c.group.fold("")(_.toString),
            node.fold("")(n => number(n.span.start.value)),
            node.fold("")(n => number(n.span.end.value)),
            number(c.mass),
            p.drawn.argmax.contains(c.ref).toString,
            c.drawn.toString
          )
        }
      }
    }
    (header(
      scene,
      "admitted anchors, long format",
      filter,
      "rank (row position), is_argmax, is_placed"
    ) ++
      Vector(row(anchorColumns)) ++ rows.map(row)).mkString("", "\n", "\n")

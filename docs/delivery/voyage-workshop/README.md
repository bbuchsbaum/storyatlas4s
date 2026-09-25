# Recall Voyage workshop (V0–V7)

This folder holds the converged design prototype for the Recall Voyage view
(ADR 0002 §14). The prototype came out of a critic-panel workshop, and the
rulings below are what the Scala port (`VoyageLowering`, `VoyageView`) has to
honour. The rulings are the contract. The prototype's code is not: it is a
single HTML file over a synthetic fixture, and is not a library surface.

- **Progression canvas:** <https://claude.ai/artifact/U2PajJp2zEUxTkpoJsPVbY>,
  page "Voyage workshop". It shows each version's boards, the critic verdicts,
  and a change note per round. The canvas is private to its owner.
- **Prototype:** `prototype.html` with `data.js`, the synthetic "Silent River"
  fixture. The fixture is producer-shaped, and its generator follows the
  producer rules listed under Fixture below. No participant data is included.
- **Court:** `check.cjs`, 47 browser checks.

  ```sh
  PLAYWRIGHT_DIR=<dir whose node_modules has playwright> \
  PLAYWRIGHT_CHROMIUM=<chromium executable, optional> \
  node check.cjs prototype.html <out-dir>
  ```

- **Screenshots:** `v7.png` (default state) and `v7-filter.png`
  (decode-bound ∪ decode-filled).

## Panel and verdicts

The panel had five critics: an information designer, a scientific-integrity
reviewer, an interaction/accessibility reviewer, a memory scientist (PI), and
an art director. Integrity and interaction voted *not converged* on V4 (6/10
each). They re-reviewed V5 and gave 8/10. V6 closed their remaining must-fixes
and V7 closed the recorded nits; the critics did not review V6 or V7
themselves. Both are verified only by the court.

## Rulings

### Marks

1. **The glyph is a container.**
   - A fixed outline stands for mass 1.0, and the inner area is proportional
     to the anchor mass. The inner mark's radius is `R·√m` for every `m > 0`.
   - There is no renderer floor on the inner mark, and no minimum bar width.
2. **Shape carries origin.**
   - Posterior argmax: a circle.
   - Decode-bound: a diamond with the same area as the circle.
   - Decode-filled: a dashed, empty diamond container. It has no inner mark,
     because its mass is 0.
3. **External-dominant** (`externalMass > sourceMass`, as supplied) has two
   marks together:
   - a hollow inner mark (V-U5), with the same area as the filled mark would
     have;
   - a second outline around the container, so the flag can be read at any
     mass, including zero-mass fills and masses below about 0.04.
4. **Unanchored and untimed units.**
   - Unanchored units sit on a labelled top row: "unanchored units (no film
     anchor)".
   - Untimed units are never plotted. They are listed separately and carry no
     anchor, origin, or mass.
5. **Colour is never the only channel.**
   - Matches carry full ink or teal plus a tick below the glyph.
   - Non-matches are dimmed to `#8a8477`, which is 3.4:1 on the paper and so
     meets the 3:1 minimum.
   - Every outline has the same weight whatever the origin.

### Selection, camera, rail

6. **Selection** shows:
   - a ring;
   - a thin neutral ring at the posterior argmax, joined by the declared route
     (dashed);
   - a dashed focus ring only under keyboard focus;
   - the unit's admitted anchors in a gutter on the film axis, drawn as bars
     at 100 px = mass 1.0.
7. **Every in-view gutter value is printed.**
   - Crowded labels keep a 12 px pitch.
   - A displaced label carries a leader back to its bar end.
8. **The scene rail names every scene in view**, or covers it with a printed
   range (`35–36`).
   - Ranges form only where names would collide.
   - A selected scene keeps its name and absorbs a colliding neighbour as a
     suffix.
   - Nothing is dropped.
9. **Y camera:** either *Fit to window* or *Whole film*.
   - Fit to window uses the placed and argmax anchors of the units in the
     window, snapped to scene bounds. The rule is printed.
   - Selecting a unit never rescales the axis.

### Claims

10. **No view-side inference.**
    - The view makes no route, backtracking, omission, or "distance from
      argmax" arithmetic, and chooses no thresholds of its own.
    - Returns and omissions are "outside the Recall Voyage contract". They are
      never shown as "absent from this document" or turned into a request.
11. **Every count the view makes is labelled "counted by view".** The header
    counts are `VoyageSummary` fields.
12. **Model mass is "not calibrated confidence".** The legend and the figure
    caption both say so.
13. **The figure caption sits under the x-axis**, so it survives a crop. It
    carries:
    - the recall and film identities and a checksum prefix;
    - the calibration caveat;
    - what a dashed diamond means ("decode fills with mass 0, not posterior
      evidence");
    - the scope of returns and omissions;
    - the filter state.

### Filter and exports

14. **Inspection filter.**
    - Criteria chips carry supplied counts: decode-bound, decode-filled,
      external-dominant, group-grain, untimed, unanchored.
    - Thresholds start empty and can be combined with *any* or *all*.
    - The live count is written only when it changes.
    - M and Shift+M step through matches. They announce when there is no
      further match, or when every match is untimed.
15. **Two exports**, each with a provenance header (basis, identities, full
    checksum, time base):
    - **Units TSV:** one row per unit, with masses unrounded. The derived
      columns are named: `timed`, `admitted_anchors`, `matched`, `matched_by`.
      There is no invented address column.
    - **Admitted-anchors TSV:** long format, one row per admitted anchor, in
      rank order. `rank`, `is_argmax` and `is_placed` are marked as derived.

### Interaction and access

16. **The plot is a listbox of options.**
    - It uses `aria-activedescendant` and short live announcements, for
      example "R21, 2:03, decode-bound."
    - Focusing the plot while the selection is outside the window selects a
      unit inside it.
    - Enter moves to the anchor table, and Escape returns.
    - Enter on a unit with no anchors, and ‹ or › at the ends of the list, are
      announced.
17. **The window can be changed without dragging.**
    - The −/+ buttons, PageDown/`−` and PageUp/`+` resize it, in matching
      directions.
    - The overview is a slider with Home/End.
    - Handle hit targets are at least 24 px.
18. **Reflow.**
    - The page stacks to one column below 1540 px and never scrolls
      horizontally at 1280, 640 or 320 px.
    - The charts keep their full size (1120 px) and scroll inside their own
      box, so text and targets never shrink under zoom.

## Mapping to the Scala provider

Checked against storymodel4s `view/voyage.scala` at the pinned revision.

- **Supplied** on `UnitAnchor`: `mass`, `sourceMass`, `externalMass`,
  `externalDominant`, `localizability`, `origin`, `argmax`, `level`, `group`,
  and `span`. The admitted anchors come as ranked `Alternative`s, for anchored
  units only. Text and onset come from `VoyageUnit`, and scene names from
  `SourceTimelineGroup.label`.
- **Not supplied: K.** Localizability's K is `timeline.nodes.size` inside the
  compiler, but it is not a field on the scene. The port must either get it
  from upstream or print it as "counted by view".
- **Not supplied: unanchored masses.** Unanchored marks carry only
  `externalMass`. The prototype's fixture gave them `sourceMass`; the Scala
  inspector prints only what the mark carries.
- **Group grain** is `level > 0`. Nothing else flags it.

## Fixture

`data.js` generates 173 units: 164 anchored, 3 unanchored, 6 untimed. Its
producer rules:

- untimed units carry no anchor;
- a decode-bound unit is placed at the best anchor of a different group that
  carries mass;
- a decode-filled unit is placed in a group with zero posterior mass;
- localizability is `1 − H/log K`, where K is all 456 timeline nodes;
- external-dominant means `external > source`;
- every onset is ≥ 0;
- mass ties are broken by key.

## Port status (Scala, branch `claude/voyage-v6`)

The Scala port implements rulings 1–18 in `VoyageLowering`, `shell/VoyageFilter`,
`shell/VoyageExport`, and `VoyageView`. It departs from the prototype where the
provider or the existing product requires, as follows:

- **Localizability's K.** The prototype printed K. The scene does not supply
  it, and the model's K can differ from the timeline this view receives. The
  inspector therefore restates the model's rule and prints no K.
- **Y camera default.** The default is *Whole film*, and *Fit to window* is an
  explicit choice. The existing viewport court pins the invariant that zooming
  the recall window never moves marks vertically.
- **Group-comparison marks.** These marks, in the optional disagreement track,
  are pointer shortcuts inside the plot's listbox. The unit they name is
  already a listbox option.
- **Compact plates** (below 640px wide) are re-lowered at real pixel sizes, as
  before. Text never shrinks, which is what ruling 18 protects. The gutter
  appears only on plates at least 1000px wide.
- **Not ported.**
  - The prototype's count line in place of the KPI cards.
  - The paper, ink and teal palette. The plate keeps the lowering's own
    palette, which a stylesheet can restyle by class.
  - The overview's localizability ribbon.

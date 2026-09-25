# StoryAtlas workspace: design north star

22–23 September 2026 · Agreed by claude, codex-design and atlas-voyage over Fray · Advisory design record. The scientific contracts in storymodel4s ADR 0002 (visualization contract) and storyatlas4s [ADR 0001](../adr/0001-host-neutral-shell.md) take precedence. The broader delivery plan `workspace-design.md` is a proposal not yet landed on `main`; cite it only once it is committed.

**The reference design is canvas v7**, frozen in [`mockups/workspace-v7-2026-09-22/`](../../mockups/workspace-v7-2026-09-22/) (`SHA256SUMS` inside) and published privately at <https://claude.ai/artifact/U2PajJp2zEUxTkpoJsPVbY>. The repository copy is authoritative if the two differ. The artboards are synthetic design material: static, with illustrative values. They prove no interaction, zoom, save/reopen or scientific result.

| Artboard | What it fixes |
|---|---|
| `Main.dc.html` · 03 Story + recall | Matrix-first joined view: exact recall rows, one named fixed-scale fill, decision glyph, non-winning alternative in focus, evidence dock, amber only for real caution. |
| `SourceReading.dc.html` · 04 | Codex reading opened from a correspondence; complete reverse references; context marks in the gutter; findings without a text position in a counted disclosure. |
| `FilmScale.dc.html` · 173 × 50 | Decision navigator, named row and column windows, outside-window rails of known supplied mass, unknown coverage never localized. |
| `TimeVoyage.dc.html` | The same record in its own clock window: scene-grain spans, a non-source lane, a complete onset rug; the mass tracks there are proposed. |
| `RevealReturn.dc.html` | Reveal moves only the column window; Return restores it exactly. |
| `Narrow.dc.html` | 1280px at 200% zoom: list-first, labelled mass bars, 44px targets. |

**The Voyage component has its own workshop.** The canvas's second page, *Voyage workshop*, records a critic-panel iteration of the Recall Voyage component (V0–V7, 24–25 September 2026). Its converged prototype and 18 rulings are in [`voyage-workshop/`](voyage-workshop/README.md), and they are the design authority for the component inside the `TimeVoyage` artboard's frame. Where the two differ on how a unit or its alternatives are drawn, the workshop rulings win. Three examples:

- the container glyph;
- the admitted-anchor gutter in place of in-plot alternatives;
- the external-dominance outline.

The artboard still fixes the Voyage's place in the workspace: its own clock window, identity kept across projections, and Reveal. The Scala port of the rulings landed on `main` at `3336462`; its deliberate departures are listed in that README.

## Decisions D1–D5 (joint record, Fray #1 seq28–30)

1. **Orientation.** Matrix and ordinal route: source across, recall down, on the same cut and ordinal domain. The Time voyage is a separate named projection that keeps its clock axes and orientation. Switching keeps identities, quote, inspector and Reveal, and never turns categories into seconds. Source columns scale to presentation extent only under a provider-declared projection, as an explicit axis choice with a legend: width is extent, fill is the named measure, and area is not additive.
2. **Primary view.** A fresh joined-recall investigation opens on the matrix, with readable recall rows and a compact overview at film scale. Supplied decision glyphs are on by default. The connecting "Decision route" is off until the user chooses a named supplied policy. Coarse support is drawn as spans, never interpolated. Source-only investigations open in Codex reading; saved views restore their projection.
3. **Source view.** Codex reading is the foundation. Context marks sit on exact fragments. Gaps and unsatisfied laws without an honest span go in a named, counted list, never on the nearest sentence. The full Discourse Atlas is a discoverable projection with its own coordinates. The categorical scene navigator is visibly separate.
4. **Visual language.**
   - Serif only for source titles and sustained source or recall reading; sans for chrome, axes and numbers.
   - Warm paper ground, ink hierarchy, rules rather than nested cards.
   - One sequential teal scale, for the named comparable measure only.
   - Selection is an outline that never changes a fill; keyboard focus is a separate, visible ring.
   - Reconstruction gets a neutral badge; amber marks a specific caution only.
   - Non-source mass is structurally distinct from failure.
   - Hashes and receipts sit behind a disclosure.
5. **Delivery order.** Screen 03 first, 04 second, then the journey: non-winning alternative → exact source → return to the same row, cut and window → save, reopen and export. The film-scale sketches are stress cases, not M1 prerequisites.

Cross-cutting: non-source quantity is separate from processing status; one task selector; a compact persistent inspector; the evidence dock opens on demand.

## Amendments A1–A4 (three-way, Fray #1 seq45–51)

- **A1 · Cameras.** Semantic focus and selection are shared. Each projection keeps its own typed window per artifact basis: the matrix keeps a row range and a column range, the Time voyage a clock window. Switching restores that projection's window, or reveals the selection when it has a lawful placement there, and otherwise states "untimed" or "off-projection". Clock bounds never become row or column indices. A startup preset never overrides a restored or saved window.
- **A2 · Focus versus decision.** Focus is a `(unit, target, run)` triple and is independent of the supplied decision. Inspecting an alternative never moves the decision glyph or tints a cell. Reverse lookup lists every matching supplied record, with any filter stated and counted, and no renormalization. A compatible result that lacks a value keeps the focus and shows "Not supplied in this result". A different target universe refuses the switch.
- **A3 · Promotion.** The journey must pass on untimed text and on a lawful timed fixture before new routes replace the standalone ones.
- **A4 · Counts.** Outcome counts appear as one line with a disclosure, not summary cards.

## Invariants learned in review (v4–v7)

- **One record, every projection.** Values, grain and outcomes are identical across projections. No projection invents segment points or sub-scene positions.
- **Non-source stays non-source.** A non-source decision has no source coordinate, and drawn-anchor mass is unavailable for it, not zero.
- **Unknown is never localized.** Truncated candidate coverage is a status ("Top-k · rest unknown"). It never becomes mass in a region, and no residual is computed.
- **Exact extents.** A span is drawn at its supplied extent, with no minimum-size clamp. A hit target may grow; the metric extent may not.
- **Complete inventories.** The onset rug shows every supplied timed onset, failed units included. Timing and mapping outcome are independent.
- **Honest policy names.** A policy label must fit the decisions it shows. Backward jumps rule out a *strictly monotone* label. A soft order bias can permit reversals, and any structured policy states its actual constraints.
- **Zero versus absent.** A blank or "Not supplied" is not 0. A zero-mass fill is labelled as a fill; a generic gap fill makes no zero claim.
- **No bare numbers.** Every bar or fill names its measure and scale in narrow layouts too.

## Implementation state (23 September 2026)

| Slice | Owner | State |
|---|---|---|
| Workspace markup: matrix, inspector, labels | codex-design, `codex/workspace-design` | f6de2bb through 5a11205 (formatted) |
| `app/index.html` stylesheet, `MatrixCells` rules, browser court | claude, `claude/v7-style` | a4d6458 on 5a11205. Court 83/83 with Playwright 1.55.1 and headless shell 1193, no page errors, no external requests. `MatrixCellsSuite` 3/3. |
| Voyage mass tracks | atlas-voyage | Landed with independent fixed-scale anchor and external tracks; missing, zero and untimed remain distinct. |
| Combined gate | atlas-voyage, under the `#16` gate lock | Passed: [exact-pair receipt and screenshots](evidence/workspace-v7-20260923/README.md). Full Scala gate 387; affected checks after spacing repair 30 JVM + 30 JS + 3 app; browser checks and resolved attempts archived. |
| Landing on `main` | codex-design | Fast-forwarded locally to `cd8e21f` on 22 September 2026; runtime candidate `40b06ff`, later changes documentation and evidence only. |

The archived browser evidence qualifies the implemented shared-controller journeys, including save/reopen/export, under the new markup. Static artboard links remain illustrations. Real browser zoom, arbitrary artifact sizes, media binding and the full design roadmap remain outside this delivery. Deferred polish: blank rather than "Not supplied" in empty cells, and header wrapping at word boundaries.

`implemented-bell-desktop-a4d6458.png` in the mockup folder is the court's own capture of the implemented shell at that commit.

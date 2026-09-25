# StoryAtlas: a granular plan toward the north-star workspace

Owner-approved 2026-09-25. Status lives in Mote (`mote ls --tag north-star-plan`); this document is the plan, not a status report.

## Context

The target is the private canvas <https://claude.ai/artifact/U2PajJp2zEUxTkpoJsPVbY>, which has two pages:

- **North star v7.** Six boards (Main/03, SourceReading/04, FilmScale, TimeVoyage, RevealReturn, Narrow), plus the four vision screens (01 whole-movie recall, 02 movie source, 03 text story+recall, 04 source-only).
- **Voyage workshop.** V0–V7, whose Scala port landed on local `main` at `3336462`.

The goal is a workspace that is fully functional and an excellent representation of the structures storymodel4s produces, built so that every board can be replicated with Intaglio graphics.

What the three surveys found:

- **M1 is closed.** The packet, open, shared selection and matrix, save/export and the two-fixture journey all exist. The remaining delivery is landings 6–9 of `workspace-design.md` §10. About 30 open Mote beads cover them.
- **The UI is almost entirely DOM.** Intaglio draws only the Codex overlays, the Discourse Atlas and the Voyage plate. The on-screen matrix is an HTML table, and `MatrixLowering` has one caller: the exported `matrix.svg`, whose design differs from the screen. Its selected cell tints its fill, which violates D4.
- **Board fidelity gaps on the implemented screens:**
  - Main lacks the outcome count line, two-row scene/event headers, an evidence dock, alternative mass bars, an accessible-table toggle, a tied-alternatives note and the failed-row span.
  - SourceReading lacks the outline, projection toggle, "Arrived from" line, scene strip, counted findings list and relations.
  - FilmScale lacks the decision navigator, outside-window rails with known mass, and the top-k unknown status.
  - RevealReturn lacks Return: viewport moves never enter history.
  - Narrow lacks in-place row expansion and the Menu collapse.
- **storymodel4s structures are only partly represented, even at the current pin:**
  - Landmark context/status, secondary hierarchies and `BoundaryBelief` ghosts are missing.
  - Relation subtypes and modes, participant roles, and a non-colour epistemic channel on landmarks are missing.
  - Context kinds are split only narrated/non-narrated.
  - The Codex draws relations generically.
  - Chronology, `TransitionFlow`/population, and film/media have no view type.
  - storymodel4s HEAD is 177 commits past the pin and adds `TemporalPartitionView` and `TextSourcePackage`; existing view types are unchanged.
- **Mote hygiene:**
  - `workspace-design.md`, `sherlock-dogfood.md` and both mockup folders are untracked, yet about 20 beads cite them.
  - Stale beads: MGDP (doing since Aug 30), 31K5, 19K4 and TVXX (names Intaglio `1742ce2`).
  - TW7D's `aria-pressed` wording predates the listbox port.
  - M21HQ predates the Voyage port.
  - The film branch and the four-screen gate are held by storymodel4s work: DSD, AX3T, 354J.

**User decisions (2026-09-25):**

1. **Graphics in Intaglio.** Every graphic is an Intaglio plate: matrix cells and glyphs, mass bars, navigators, rails, legend keys, onset rugs and scene strips. Prose, forms and inspector text stay DOM (ADR 0002 D6). Intaglio grows upstream in `~/code/scala/intaglio` where needed.
2. **`workspace-design.md` and the vision mockups are committed as design authority**, after the current editor confirms they are ready.

Bead aliases below use the distinctive characters after `bd-01M…`. Full ids are in `mote ls`.

---

## Operating rules (every task)

- **Writers and builds.** One implementation writer and one heavy build at a time.
- **Workspace.** Work in an isolated clone (`/private/tmp/storyatlas4s-<slice>`), because git worktrees break sbt-typelevel's jgit. Reserve paths with `mote begin`, announce on fray, and never touch others' uncommitted edits. AppView, ViewChoice, Plate, ActivationSuite and ADR 0001 currently carry another agent's work.
- **Landing gate:** `sbt <overrides> compileAll testAll scalafmtCheckAll app/fastLinkJS`, with 0 compiler warnings. Also run the affected browser courts:
  - `app/smoke/smoke.cjs`;
  - `workspace.cjs` (fixtures in `target/inspector-20260924`);
  - the `voyage-*.cjs` courts on locally generated NN03 editions.
- **Replication court.** From P1 on, every board-facing task also passes the design-replication court described there.
- **Independent review.** Each phase gets a fresh-context review before it closes.
- **Intaglio changes.** A missing capability is fixed in `~/code/scala/intaglio` on a branch, with its own contribution bar: a conformance case in every backend, a law where one applies, JVM/JS byte identity and the compatibility gate. The storyatlas4s pin then moves in a separate commit with the full gate. Never work around it in a lowering.
- **Provider gaps.** A missing structure or quantity is filed as a storymodel4s bead and drawn as an explicit unavailable state. It is never computed in Atlas.
- **Commits and pushes.** No pushes or merges to `main` without the user's word. Commit identity is `canardlapin`, repo-local.

---

## P0 · Foundations and hygiene (small, first)

| # | Task | Mote | Acceptance |
|---|---|---|---|
| P0.1 | Commit `docs/delivery/workspace-design.md`, `sherlock-dogfood.md`, `mockups/workspace-vision-2026-09-22/` and `mockups/sherlock-voyage-ideal-2026-09-22/`. Ask on fray who owns the uncommitted edits first; the Sherlock mockups contain real NN03 material, so keep them local-only unless the owner rules otherwise. Commit only the design doc and the synthetic vision mockups. | new `[Docs] Commit the workspace design authority` | The files are on `main`, and cited beads resolve. The north star's "proposal not yet landed" line is updated. |
| P0.2 | Save this plan as `docs/plans/2026-09-25-north-star-plan.md` and cite it from the north star. | same bead | Linked from `workspace-north-star.md`. |
| P0.3 | Clean up stale Mote work, with a note on each: close MGDP and 31K5 as superseded by the v7 canvas; bring 19K4 to review or close it; re-point TVXX at `2fa5c68`; reword TW7D to the listbox model (`b8017bf`); re-scope M21HQ to build on the Voyage port (glyphs, VoyageFilter, VoyageExport); correct `sherlock-dogfood.md`'s status list. | existing beads | `mote ls --status doing` shows only live work. |
| P0.4 | Pin hygiene: move the storymodel4s pin from `7b2f076a` to current HEAD in a consumer-only slice. Check the sealed `Admissibility`/`FlowStep`/`TransitionFlow` constructors for breakage (none found). | new `[Build] Move the storymodel4s pin` | Full gate, all courts and a clean external-consumer compile. `TemporalPartitionView` and `TextSourcePackage` become importable. |
| P0.5 | Fix the frozen-mockup render dependency: the v7 boards need `support.js`, so copy it from `mockups/v2/` with a checksum. | P0.1 bead | Every `.dc.html` renders headless from the repository alone. |

## P1 · The design-replication court (the process you asked for)

Build it once. Then run it for every board-facing task.

**P1.1 · Fixture parity.** Board and plate must draw the same data.

- Author two producer-generated synthetic packets in storymodel4s `WorkspaceFixtures`:
  - the "forgotten bag" story behind Main/SourceReading/RevealReturn/Narrow (10 recall rows, the board's scenes and events);
  - "Silent River" (173 units × 50 scenes) behind FilmScale/TimeVoyage and the Voyage workshop.
- Port the boards' inline JS data (`Main.dc.html:269-280`, the `FilmScale.dc.html:233-239` generators) to read those packets. The inline values are illustrative, and design-workspace §11.1 requires generated equivalents.
- Beads: storymodel4s `[Fixtures] forgotten-bag and Silent River workspace packets` (new), and Atlas `[Court] board fixture parity` (new).
- Acceptance: both fixtures decode through the storymodel4s codec and validator, and each board renders from its packet with no hand-typed values.

**P1.2 · Board inventory.** For each board, a checked-in `docs/design/boards/<board>.inventory.json` lists every region and element:

- id and region;
- kind (mark / text / control / rail / legend);
- visual attributes (shape, dash, weight, fill channel, size rule);
- semantic hooks (role, label, `data-name`);
- the Intaglio primitive it maps to, or "DOM (prose/form)".

The inventory doubles as the capability matrix. Draft it by extracting from the boards' semantic hooks (`role=grid/row/gridcell`, aria-labels), then complete it by hand.

**P1.3 · Plate verb.** A CLI verb `cli/run board --board <name> --fixture <packet> --out <dir>` lowers the Intaglio plate(s) for a board from the same packet. It writes SVG, plus PNG through intaglio's `Java2DRenderer.renderPng`. The app's live render of the same fixture comes from the existing `workspace.cjs` harness.

**P1.4 · Court script.** `app/smoke/replicate.cjs <board> <fixture>` renders three sources headless (Playwright 1.55.1, shell 1193, 1440×900, DPR 1): the board, the Intaglio plate, and the live app. It then compares them:

- **Structure diff.** Extract a per-region inventory from each source: text content, glyph kind, counts, rail and status, and `data-name` mapped to `Address`. Diff it against the checked inventory. Every divergence must be listed as either a fix or a recorded, reviewed exception.
- **Pixel diff.** Pin the fonts first (P1.5). Compare per region with a documented threshold (pixelmatch, pinned in `e2e/static`), writing a heat-map PNG and a region table.
- **Receipt.** `docs/delivery/evidence/replicate-<board>-<date>/` with digests, commands, both renders and the diff.

**P1.5 · Deterministic type.** Bundle and pin an open font pair: the serif for quotes and the sans for UI, loaded from local files with no CDN (AGENTS forbids external resources). Use it in the boards, the app and the plates, so the pixels are comparable. This is an Intaglio font-family pass-through only; embedding is not needed for SVG.

**P1.6 · Canvas publication.** Each receipt adds a "Replica · <board>" artboard beside its design board on the canvas, with the diff table as a note. The fixtures are synthetic, so this is publishable. The pilot is the **Voyage V7 board**: the Silent River fixture renders through the merged port and is diffed against `docs/delivery/voyage-workshop/prototype.html`.

**P1.7 · Gate.** Add the replication receipt to `workspace-design.md` §11.5 as a required receipt for any landing that claims a board.

Beads: `[Court] design-replication court` (new, under TVXX / EWX). Pilot acceptance: the V7 replica is published, and any structure diff is either fixed or recorded as an exception.

**P1.8 · Intaglio capabilities** (upstream, `~/code/scala/intaglio`). Each capability is one branch/PR with a conformance case, then a pin move. Order them by first need:

| Capability | First needed by | Minimal API |
|---|---|---|
| Mixed-style text runs (fill, weight, italic, underline per span) | Matrix two-row headers (bold id + label), exact-support underline, "R05 · 5 of 10 · untimed" | `Grob.textRuns(runs: Vector[TextRun], at, anchor, gp)`, measured per backend |
| Tabular numerals | Every numeric column, axis and bar value | `GraphicParams.fontFeatures` (`tnum`). For backends without a route (JavaFX), document it and make it a typed capability, not a silent no-op; or use a monospaced-digit face |
| Per-grob ARIA role / label / id | Matrix gridcells, navigator marks and the Voyage listbox (replacing host post-processing in `mount`) | `GrobMeta.aria: Aria(role: AriaRole, label, id)`, a closed enum, validated |
| Root role override, `preserveAspectRatio`, omit root size | Plates in scroll boxes, decorative strips | `SvgOptions` fields |
| Hatch-span across cells / clip-by-window | Failed-row span, outside-window rails | Reuse `fillPattern` and `Clip.On`; add a test only if something is missing |

Each capability gets an intaglio bead and a storyatlas4s consumer task that moves the pin with the full gate.

## P2 · Screen 03 (Main, Story + recall) to board fidelity. Landing 4 completion, graphics in Intaglio

Entry: P1 court, plus text runs and ARIA from P1.8.

| # | Task | Files (representative) | Board acceptance |
|---|---|---|---|
| P2.1 | **One matrix lowering.** Replace the DOM `<table>` cell graphics with an Intaglio plate lowered per row/column window. `MatrixLowering` becomes the only matrix drawing, for screen and export alike. Fix the selected-cell fill, which breaks D4: selection becomes an outline. The DOM keeps an accessible-table twin behind the board's "Accessible table" button. | `intaglio/MatrixLowering.scala`, `app/WorkspaceView.scala:212-235`, `app/MatrixCells.scala` (retired into the lowering), `shell/WorkspaceExport.scala:24` | Structure diff clean on teal bins, decision diamond, dashed zero-mass fill, one Non-source column, Status column; export SVG equals the screen plate. |
| P2.2 | Two-row headers: scene group above event id and label. Uses text runs. Replaces raw keys such as `sit:bell:sit:quiet`. | lowering header layer | Header text matches the board; ids bold. |
| P2.3 | Outcome count line with disclosure (A4), replacing any card use. | `WorkspaceView.scala` | "N decided · …" with a counted disclosure. |
| P2.4 | Failed row as one hatched span with a single message (not per-cell "Not supplied"); zero vs absent kept distinct. | lowering | Board failed-row inventory matches. |
| P2.5 | Legend with the five-step ramp and "fixed scale · not calibrated" as an Intaglio key. | lowering legend | Matches. |
| P2.6 | Decision route control: "Off" plus named supplied policies only (D2). No policy is invented, and the route is off by default. | `WorkspaceView.scala:499`, controller | Toggle works on a fixture supplying a policy; absent otherwise. |
| P2.7 | Inspector to board: alternative rows with 0–1 mass bars (Intaglio mini-plates), "in focus"/"decision" chips, dashed Non-source row, "Supplied total · coverage" line, decision grid (target · origin · grain), amber "Tied alternatives" note. Focus ≠ decision (A2). | `WorkspaceInspector.scala:44-176` | Board inspector inventory matches; inspecting an alternative never moves the glyph. |
| P2.8 | Evidence dock: source ∥ recall with exact-support underline (text runs) and "Read in source →". | new `EvidenceDock` in app; lowering for underline marks | Journey: nonwinning alternative → exact source → return. |
| P2.9 | RevealReturn: Reveal moves only the column window; Return restores it exactly. Push viewport moves into history with a return target. | `shell/WorkspaceController.scala:14-22,254`, `WorkspaceView.scala:105-109,433-446` | Board sequence reproduced; shell unit tests on JVM and JS. |
| P2.10 | Narrow (1280 at 200%): selected row expands in place with bars, note and "Read in source"; Menu collapses Open/Save/Export; 44px targets. | `WorkspaceView.scala:366-396,596-608`, `index.html` | Narrow board replicated at 1280/200%. |

Bead mapping: re-open work under the closed M1 selection parent as new children of the epic, `[Main] …` × 10. P2.9 extends TW7D.

## P3 · Screen 04 (Source reading) and faithful story structures. Landing 6

Entry: P0.4 pin; C3D qualification. storymodel4s structures are drawn as supplied, never inferred.

| # | Task | Mote | Acceptance |
|---|---|---|---|
| P3.1 | Qualify the hierarchy, context and relation capabilities at the new pin, and record the producer API. Where a structure has no view type, file a storymodel4s bead: BoundaryBelief ghost marks; secondary hierarchies (GoalArc/EntityThread/LocationThread/Theme); participant-role marks; landmark epistemic channel (ADR §13 C3); context-membership bases; chronology/Loom projection kind; population view type. | C3D + new storymodel4s beads | Capability table in the bead; every gap has a producer id. |
| P3.2 | **Atlas lowering fidelity at the current pin.** Landmark context and status (currently discarded at `AtlasLowering.scala:853`) are drawn as a non-colour channel, and the stale `AtlasPlate.scala:389-398` comment is fixed. The Portal's `NarrativeReference` mode and the causal subtype are drawn on routes, portals and threads. The nine `ContextKind`s are distinguished by lane shape or label glyph, not by the narrated/non-narrated fill alone. Frame nesting is drawn. Everything reads supplied fields. | new `[Atlas] Draw supplied landmark status, relation subtypes and context kinds` | Lowering tests on JVM and JS; textual twin lists the same; monochrome legibility court (V-U5). |
| P3.3 | Codex relation and claim annotations get typed rows per relation layer (7 layers), instead of generic kind rows. | new `[Codex] Typed relation annotations` | Codex twin and overlay show layer and subtype. |
| P3.4 | SourceReading shell: outline nav, projection toggle (Codex ↔ Discourse Atlas as its own projection, D3), reading-horizon control, "Arrived from …" line with Return, and a scene strip as an Intaglio plate with the focused event underlined. | C9M | SourceReading board replicated. |
| P3.5 | "Findings without a text position · N ▸" counted disclosure in the app, reusing `cli/Workspace.scala:467 unplacedLedger` logic moved to `shell`. | C9M | Count equals the supplied ledger; never placed on the nearest sentence. |
| P3.6 | Narrative score + scene→event drilldown; selection survives collapse. | C9M | Existing acceptance. |
| P3.7 | Exact-text search. | CGK | Existing acceptance. |
| P3.8 | Typed relationships inspector + complete list (12-neighbour budget + omitted count). Adopt TTW0's decision first. | TTW0 → CQV | Existing acceptance + board relations list. |
| P3.9 | Recall references in the inspector with mass, text and "you came from here". | C9M | Board inspector matches. |
| P3.10 | Second structurally distinct narrative (upstream fixture), then source-only + recall attach on unfamiliar text, adopting `TextSourcePackage`. | TVHC → CYN | Existing acceptance; replication court on the second text. |

## P4 · Whole-inventory navigation (FilmScale + TimeVoyage). Landing 7

| # | Task | Mote | Acceptance |
|---|---|---|---|
| P4.1 | **Decision navigator** as an Intaglio plate: one mark per unit on the S-axis; row and column window boxes; legend; collapse; policy label "Decision navigator · [policy]". Scale slice 2 (524×1000) fits here. | D5D | All 84 ids once; 524×1000 exact ranges; FilmScale board replicated. |
| P4.2 | Outside-window rails (◀ S1–4 / S17–50 ▶) with *known supplied* mass only, and "Top-k · rest unknown" status plus "?" cell. No residual is computed. | new `[Scale] Outside-window rails and unknown coverage` | Rails sum only supplied values; the unknown remainder is never localized. |
| P4.3 | "Reveal S2" on an off-window decision from the inspector. | D5D | Board behaviour. |
| P4.4 | Sparse/windowed rendering, then perf budgets (p95 ≤100 ms, ≤3 s load). | DCJ → DJX | Existing acceptance. |
| P4.5 | Voyage leftovers from the workshop port: count line in place of the KPI cards (A4); paper/ink/teal palette via a stylesheet by class; overview localizability ribbon; onset rug marks failed units (TimeVoyage "complete inventories"); rug and brush drawn by Intaglio instead of Laminar SVG. | new `[Voyage] Close the workshop port leftovers` | TimeVoyage + Voyage V7 replicas clean. |
| P4.6 | Voyage at scale: 524-unit Voyage fixture through the navigator budgets. | new under DJX | Budgets met with identity intact. |
| P4.7 | Matched full-vs-content Voyage comparison, rebuilt on the port. | M21HQ (re-scoped) | Existing acceptance on the port. |
| P4.8 | Consume `TemporalPartitionView` (after P0.4) for the temporal readout, once the producer's media-supported source package exists. | 354J (blocked) | Blocked until producer; unavailable state drawn meanwhile. |

## P5 · Film and media (screens 01/02). Landing 8. Producer-gated

Nothing here closes without the storymodel4s compiled-film route (`…MK5N6F…` and children, AC4-S2/S3, movie-time courts). Until then, the UI is built and tested against the P1.1 synthetic packets, and the absent/unqualified state is shown honestly.

| # | Task | Mote |
|---|---|---|
| P5.1 | Film-strip navigator with representative frames as an Intaglio plate, using `SampledFrameSet` identities from the new pin, over synthetic frames. | new under DSD |
| P5.2 | Independent video/audio controls: only explicit Play/Seek moves a player. | E24 |
| P5.3 | Scene exploration with retained recall context. | E8A |
| P5.4 | Whole-movie end-to-end on synthetic media plus one admitted compiled film. | EEC (blocked) |
| P5.5 | Open studies built from ordinary inputs. | AX3T (blocked) |

## P6 · Four-screen qualification. Landing 9

| # | Task | Mote |
|---|---|---|
| P6.1 | Early scientist comprehension on 03. Needs 5–8 people; recruitment is the user's call. | ENQ |
| P6.2 | Four configurations × widths 1600/1440/1024/narrow/200%, keyboard evidence → export. The replication court runs on every board. | EWX |
| P6.3 | Final comprehension (≥90% of critical tasks without rescue). | F38 |
| P6.4 | Release documentation: local opening, missing capabilities, reproducible exports. | F9A |
| P6.5 | Accept the four-mockup workspace (courts A–K + replication receipts for all boards). | FFN |

Parallel and extension tracks, scheduled when the writer is free, not on the critical path: TTHJ static preview, 19JQ portable bundle, FYH feature editions, FPS recall comparison, 16M12 interview workspace, TVC7 chronology (needs a producer projection kind).

---

## Sequencing and critical path

```
P0 (hygiene, pin) ─▶ P1 (court + fixtures + Intaglio runs/ARIA/tnum) ─▶ P2 (03 fidelity)
                                                             └─▶ P3 (04 + structures) ─┐
                                               P4 (navigation, Voyage leftovers) ─────┼─▶ P6
                         storymodel4s film route ─▶ P5 (film) ────────────────────────┘
```

- **Single writer.** Order: P0 → P1 (pilot on Voyage V7) → P2 → P3 → P4, then P5 when the producer route exists, then P6.
- **Parallel work.** Fixture authoring in storymodel4s and Intaglio upstream PRs can proceed in parallel, because they are separate repositories.
- **Blocked on the user.** Recruiting for comprehension; pushing to remotes.
- **Blocked on the producer.** Film (P5), temporal readout (P4.8), studies from ordinary inputs (P5.5), chronology/Loom and population view types.

## Mote beads filed for this plan (2026-09-25)

| Bead | Plan items |
|---|---|
| `bd-01M3DE2HCSMG8VS049KY551EEQ` [Docs] design authority and this plan | P0.1, P0.2, P0.5 |
| `bd-01M3DE2HHPJ062KTBF37ZNW2T1` [Build] storymodel4s pin | P0.4 |
| `bd-01M3DE2HNP77Z24CCQNCCF4T7M` [Court] board fixture parity | P1.1 |
| `bd-01M3DE2HSZ1PWHF8YV26SF5X3P` [Court] design-replication court | P1.2–P1.7 |
| `bd-01M3DE2HXT9RHA2BXDFEP69EYM` … `bd-01M3DE2K547D7K7FWWGBTHVYM7` [Main] ×10 | P2.1–P2.10 (blocked by the court) |
| `bd-01M3DE2K8Z9ARTPMGFRYE098FF` [Atlas] supplied structures | P3.2 (after C3D) |
| `bd-01M3DE2KCB3FF72V5MYXXNCK1G` [Codex] typed relation annotations | P3.3 |
| `bd-01M3DE2KGH1V20P7X1P1AXTMRM` [Scale] outside-window rails | P4.2 (after D5D) |
| `bd-01M3DE2KMRWNPP3874G5PS506B` [Voyage] port leftovers | P4.5 (after the court) |
| `bd-01M3DE2KRSPMFDFCKJGM158APA` [Voyage] 524 units at budget | P4.6 (after DJX) |
| `bd-01M3DE2KX4W8WJ6WZKZMF94SJS` [Film] film strip over synthetic frames | P5.1 |

Intaglio and storymodel4s beads are filed in their own stores when each task starts, and linked from these.

The frozen v7 boards need the Design artifact type's runtime to render. It is never vendored: the court supplies it at render time and commits only the resulting reference renders and DOM extracts (P1.2).

## Verification (per phase)

- **Unit.** `sbt <overrides> compileAll testAll scalafmtCheckAll app/fastLinkJS`, green with 0 warnings. New shell and lowering behaviour is tested on JVM and JS, and determinism is checked as byte-identical SVG across platforms.
- **Browser courts:**
  - `smoke.cjs` and `workspace.cjs` (100+ checks);
  - `voyage-{review,disagreement,mass,inspector,viewport}.cjs` on locally generated NN03 editions (never published);
  - new behaviour gets new checks. Measure the property; never check that something merely exists.
- **Replication.** `replicate.cjs <board>` produces a structure diff that is clean or has recorded exceptions, plus a pixel-diff report under the threshold. The receipt goes in `docs/delivery/evidence/`, and a replica artboard goes on the canvas.
- **Intaglio.** Its own `scalafmtCheckAll testAll`, `tools/check-docs.sh` and `tools/check-compatibility.sh`, then the storyatlas4s full gate at the new pin.
- **Review.** A fresh-context review of each phase's diff before its beads close. Mote progress notes and fray posts go with each landing.

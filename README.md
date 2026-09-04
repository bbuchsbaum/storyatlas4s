# storyatlas4s

Application and renderer adapters for the **Narrative Codex** and **Narrative
Atlas** of [storymodel4s](https://github.com/bbuchsbaum/storymodel4s), per ADR
0002 (visualization contract). storymodel4s compiles every artifact
(`CodexFlow`, `NarrativeScene`) and owns every claim; this repository lowers
those artifacts to [Intaglio](https://github.com/canardlapin/intaglio) scenes
and writes figures, textual twins, and receipts. It infers nothing and lays out
nothing.

Status: prototype, slice 1 (static and live War of the Ghosts edition). Unpublished.

## Modules

| Module | Platforms | Depends on | Owns |
|---|---|---|---|
| `storyatlas4s-layout` | JVM, JS | storymodel4s `view` (SHA pin) | `Paginator.paginate(flow, page, metrics)`: pure pagination of a `CodexFlow` into pages, lines, and annotation fragments with V-I2 ids; `Measurer` seam with metrics-as-data (`TextMetrics`), the fixed `MonospaceMeasurer` (publication), an optional AWT measurer (JVM), a DOM measurer stub (JS); `PaginatedCodex` twin and `LayoutReceipt` |
| `storyatlas4s-intaglio` | JVM, JS | `layout`, storymodel4s `view` (SHA pin), intaglio `core`/`svg` (SHA pin) | pure lowering `NarrativeScene → intaglio.Scene`, `CodexFlow → intaglio.Scene`, and `PaginatedCodex → Vector[intaglio.Scene]` (one page overlay per page); `GraphicsName` = `MarkId` / `AnnotationId` / `FragmentId` |
| `storyatlas4s-edition` | JVM, JS | `layout` | `EditionSpec`: the edition's fixed configuration (page box, font, relation layers, thread budget, lenses, zoom levels, SVG boxes) and `Pins` (sibling revisions, generated from `build.sbt`), shared by `cli` and `app` so both compile the same artifacts |
| `storyatlas4s-cli` | JVM | `edition` + above + storymodel4s `fixtures` and `codec` (SHA pin) | `edition --out <dir> [--model <storymodel.json>]`: atlas SVGs at every configured NarrativeLevel × SurfaceDetail state, Codex overlays and paginated `codex-<lens>.html` pages for the Reading and Overview lenses, textual twins, `receipt.json`. Without `--model` the model is the linked War of the Ghosts fixture; with it, a `storymodel.json` the storymodel4s pipeline wrote, decoded through `codec` and put to `StoryValidator` before anything is drawn |
| `storyatlas4s-app` | JS | `edition`, `intaglio`, `layout`, storymodel4s `fixtures` (SHA pin), intaglio `svg`, Laminar 17.2.1, scalajs-dom 2.8.1 | the browser shell: the same compilers, paginator, and lowerings run in the browser over the War of the Ghosts fixture; DOM text rail + Intaglio SVG overlay per page, Reading/Overview lenses, independent continuous narrative/surface zoom controls that commit exact typed states with hysteresis, the epistemic playhead (`ReaderAt` horizon), semantic focus plus selection as addresses shared by Codex and Atlas, last-intent-wins compilation, textual twins and receipts as text; the live `DomMeasurer` |

## Identity and the renderer protocol

Intaglio serializes a `GraphicsName` as the SVG `data-name` attribute. Here
that name is always the rendered **mark or annotation identity**, never an
`Address` (ADR 0002 §6): one address yields many marks across zoom levels, so
`Address` is not injective within a figure. Semantic selection stays
`Set[Address]`; a `data-name` resolves to an address through
`NarrativeScene.navigation` (Atlas) or `CodexFlow.navigation` (Codex).

The flow-level Codex overlay in `intaglio` names one group per annotation by
its `AnnotationId`. The paginated form uses the `layout` module's fragment ids:
a text line is `line/p<page>/l<line>`, and an annotation piece on a line is
`<AnnotationId>/p<page>/l<line>/r<ref>` (V-I2; `ref` is the index of the
support `SpanRef` the piece is cut from, always `r0` for contiguous
annotations). In `codex-<lens>.html` the line ids are the `data-name` of the
text spans and the piece ids are the `data-name` of the groups in each page's
inline SVG overlay, so one name resolves the same way in both layers.

## Pagination

`storyatlas4s-layout` is the owned deterministic publication backend of ADR
0002 D3 on the JVM and the seam the browser's `DomMeasurer` implements on JS.
A `Measurer` turns each source run into `RunMetrics` — one integer
advance per UTF-16 code unit in the measurer's layout units (`unitsPerPixel`),
plus a line height — and `TextMetrics` assembles the flow-wide table with a
SHA-256 identity. `Paginator.paginate` is then a pure function of
`(CodexFlow, PageSpec, TextMetrics)`: greedy first-fit line breaking (break
after whitespace, hard break at newline, code-point break inside an overlong
word, a glyph wider than the page placed alone and flagged), lines filled into
pages top to bottom. Lines tile the canonical text exactly; every annotation
support span is cut into one piece per line it crosses, never merged or
dropped. Integer arithmetic throughout makes the result byte-identical on JVM
and JS; the `LayoutReceipt` records the paginator version, page spec,
measurer, style, units, metrics checksum, and counts.

## Building

Sibling repositories are consumed as immutable git-SHA `ProjectRef` pins
(`storymodel4sRevision`, `intaglioRevision` in `build.sbt`). storymodel4s's own
build pins grakern the same way, and grakern has no remote yet, so a local
build must supply three overrides:

```sh
sbt -Dstoryatlas4s.storymodel4s.build=/path/to/storymodel4s \
    -Dstorymodel4s.grakern.build=/path/to/grakern \
    -Dstoryatlas4s.intaglio.build=/path/to/intaglio \
    compileAll testAll scalafmtCheckAll
```

(`STORYATLAS4S_STORYMODEL4S_BUILD`, `STORYMODEL4S_GRAKERN_BUILD`,
`STORYATLAS4S_INTAGLIO_BUILD` are the environment equivalents.) Once the
pinned storymodel4s commit is on its remote, the first override is optional;
the grakern override stays required until grakern is pushed.

## The edition

```sh
sbt <overrides> "cli/run edition --out target/edition"
sbt <overrides> "cli/run edition --out target/edition --model /path/to/storymodel.json"
```

Without `--model` the model is the researcher-reviewed *War of the Ghosts*
fixture linked into this build; the fixture is the fast case and is no longer
the only one. With `--model` the model is a `storymodel.json` the storymodel4s
pipeline wrote: it is decoded through storymodel4s `codec`, put to
`StoryValidator`, and its violation counts are printed before anything is
drawn. A model the validator promotes renders through exactly the path the
fixture takes, on `ViewBasis.ValidatedBuild` rather than
`ResearcherReviewedFixture`, so the receipt never calls a machine build a
reviewed fixture.

A model the validator does **not** promote is drawn as a draft rather than
refused, on `ViewBasis.DraftBuild`, through `AtlasCompiler.compileDraft`: its
unsatisfied promotion laws become marks, so a partial model is legible as
partial. A draft edition writes atlas SVGs and twins only, because
`CodexCompiler.compile` still takes a validated model and an empty Codex would
claim the reading view had been compiled and had nothing to say.

The derivation record — the pipeline's gaps and its coverage ledger — is read
from `derivation.json` beside the model when the pipeline wrote one, through a
decoder bound to the model's own content checksum, so a record for another
story, source or build is refused rather than paired. Without that file the
record is reported as **not supplied**, and the receipt prints that rather than
zero. Those are different states and `DerivationRecord` keeps them apart: a
scene saying "0 gaps" claims the compiler derived everything, while a scene
with no record knows nothing about derivation either way. The pipeline's
`compilation-report.json` is never read: it writes `upstreamClaims` and
`evidence` as sizes rather than contents and every address as a one-way
render.

The feature record — every measured track in its sidecar-backed form
(storymodel4s ADR 0011) — is read from `features.json` beside the model the
same way, through the decoder bound to the model's content checksum, and each
sidecar it names is read from `features/` beside it and verified block by
block against the manifest the model itself carries before its values are
materialized. A track under a manifest the model does not carry, a sidecar
the record names but the bundle lacks, and bytes that do not verify are each
refused rather than paired. Three states reach the receipt and never share a
line: **not supplied** (no file), a supplied record with no tracks (the
pipeline's own statement that nothing was measured), and a supplied record
with its tracks, listed by space and sidecar file. This is the reading half of
the feature slice: nothing drawn consumes the values yet, because the
value-bearing mark of ADR 0002 D11 has not been minted, and the receipt is
where the record is visible until it is.

### The Recall Voyage

```sh
sbt <overrides> "cli/run voyage --document /path/to/recall-map-NNxx.tsv.voyage.json --out target/voyage-NNxx"
```

A `voyage.json` is the Recall Voyage document (ADR 0002 §14) the storymodel4s
pipeline writes beside every recall-to-video report: the recall's units with
their word timings, the aligner's posterior rows, every segment and scene on
one source clock, one decision per unit with its origin, and, when the run
named it, the released scene coding as an independent coding. `voyage` decodes
it through storymodel4s `codec`, which re-proves its join, compiles the scene
through storymodel4s `VoyageCompiler`, so the evidence law runs here, lowers it
through `VoyageLowering`, and writes `voyage.svg`, its twin `voyage.txt`, the
standalone `voyage.html`, and `voyage-receipt.json`. Put `app.js` beside
`voyage.html` (`app/editionBundle` writes it to `target/edition/`) and the
page mounts the interactive pane over the same document, replacing the static
plate: hover a mark for the unit's words and the row's numbers, click or walk
with the arrow keys to inspect a unit and see its whole posterior column, area
by mass, and where in its group the anchor sits; two toggles show the ghosts
of the argmaxes a decode moved away from and the posterior columns of every
unit at once; the plate is lowered again to the width its panel affords, so
nothing scrolls sideways. Without `app.js` the page shows the static plate,
whose marks still carry native tooltips: every mark is annotated (intaglio
`GrobMeta`) with a title stating the unit's words and the row's numbers, a
class naming its kind and origin, and its unit ordinal as `data-unit`.

Either way, `edition --out <dir>` writes (relative paths resolve against the
repository root):

- `atlas-<story|episode|scene>-<hidden|sentences|tokens>.svg` and their `.txt`
  twins (the full configured two-axis semantic-zoom cross product; surface
  marks are compiled by storymodel4s, x = exact discourse offset, and the
  surface rail's vertical coordinate is layout-only);
- `codex-reading.svg`, `codex-overview.svg` and `.txt` twins (annotation
  overlay over discourse offsets; the Reading lens has no annotation channels);
- `codex-reading.html`, `codex-overview.html` and their `-pages.txt` twins:
  the paginated Codex, one document per lens (a 480x640px page of 16px
  monospace under the fixed `MonospaceMeasurer`). Each page is a text rail of
  one `<span class="line" data-name="line/p<page>/l<line>">` per placed line —
  the spans' concatenated text content is the canonical text exactly (V-T2;
  only `&`, `<`, `>` are escaped, newlines survive under `white-space: pre`) —
  under an inline SVG overlay lowered from the same `PaginatedCodex`, one
  `<g data-name="<piece id>">` per annotation piece at the paginator's pixel
  geometry. No script, no external resource; kind and lane are the band's row
  within the line (fill-only bands stacked in the line box), spelled out in the
  page legend and the twin, never a colour (V-U5). With many rows the bands are
  about a pixel tall; drawing lanes in the leading or a gutter with a minimum
  row height is a follow-up. A canonical text with an unpaired surrogate or
  U+0000 is refused rather than written with a substitution, since neither
  survives UTF-8 encoding and HTML parsing (V-T2 on disk). The `-pages.txt`
  twin is `PaginatedCodex.textualTwin`;
- `receipt.json`: basis, source checksum, sibling pins, the derivation and
  feature records as read (or **not supplied**), the Atlas, Codex overlay, and
  page boxes (`EditionSpec`), and per-file configuration checksums, mark
  counts, SHA-256 of the written text, and — for the paginated files — the
  `LayoutReceipt` fields (V-D3).

The edition is byte-identical across runs (the suite writes it twice and
compares).

### Static-edition browser laws (L1(a))

```sh
sbt <overrides> "cli/run edition --out target/edition"
node e2e/static/static.cjs target/edition
```

drives headless Chromium through Playwright **1.55.1** (the pin in
`e2e/static/package.json`; Chromium is the browser that pin installs —
`npm --prefix e2e/static i && npx --prefix e2e/static playwright install chromium`
once). The suite opens each `codex-*.html` over `file://` and again through an
in-process static server. It hashes the live `.line` rail against
`receipt.json`'s `sourceChecksum` (V-T2, no story text in this repository),
checks `data-name` uniqueness and piece counts against the `LayoutReceipt`
(V-I2), checks that the header prints the receipt fields (V-D3), and asserts
there is no `<script>` and no external `href`/`src`. Screenshots under
`e2e/static/diagnostics/` are failure diagnostics only; they are not a visual
golden and are not a merge gate.

## The app

```sh
sbt <overrides> "cli/run edition --out target/edition" app/editionBundle
open target/edition/index.html        # file:// works; so does any static server
```

`app/editionBundle` fast-links `storyatlas4s-app` and copies `app.js` and the
static shell `app/index.html` next to the edition files, which the shell links
to. The page then mounts a Laminar shell into `#app`:

- **Model.** The shell compiles `WarOfTheGhostsModel.model` from storymodel4s
  `fixtures` — the same object `cli/Edition` compiles — linked into `app.js` at
  build time. No fetch, no JSON bundle, no server, and no story text in this
  repository: the fixture is data in storymodel4s and reaches the browser only
  through the linked output under `target/`. (`fixtures` is a pure cross
  project, so no codec step was needed; if the fixture ever becomes JVM-only,
  the fallback is a `model.json` written by the CLI and decoded with
  storymodel4s `codec`.)
- **Codex.** Each page is the edition's structure exactly: a `.text` rail of
  one `<span class="line" data-name="line/p<page>/l<line>">` per placed line
  and nothing else, so the spans' concatenated text content is the canonical
  text (V-T2), under an inline SVG overlay from `PagedCodexLowering` with one
  `<g data-name="<piece id>">` per annotation piece (V-I2). Lens switches
  (Reading / Overview) recompile through `CodexCompiler` in the browser.
- **Measurer.** `layout/.js`'s `DomMeasurer` is now real: a detached
  `<canvas>` 2-D context measures each code point (`measureText`, memoised per
  font), so the paginator's additive width model holds exactly and the rail
  breaks where the paginator broke it. It is identity-stable only (ADR 0002
  D3); the receipts panel names it (`dom-canvas/1/<unitsPerPixel>/<lineHeightPerEm>`).
  A control switches to the fixed `MonospaceMeasurer` (the publication
  metric); if the canvas is unavailable the shell says so and falls back.
- **Epistemic playhead.** A slider over `[0, discourse length]` sets
  `EpistemicHorizon.ReaderAt(offset)` (snapped to a code point boundary) on the
  shared `CommonViewState`; both artifacts recompile under
  `EvidenceVisibility`, so a mark or piece not yet visible does not exist in
  the DOM. An explicit **Omniscient** control restores the horizon-free view
  (reader-at-the-end is not omniscient: an ungrounded claim is invisible at any
  offset). The twins for the current state are in the side panel.
- **Atlas, semantic zoom, focus, and selection.** Independent continuous
  controls commit Story / Episode / Scene and Hidden / Sentences / Tokens
  through deterministic midpoint thresholds with hysteresis. Each committed
  `ZoomLevel` recompiles through storymodel4s and renders through
  `AtlasLowering`; gesture coordinates never enter the scientific receipt.
  Clicking a mark (or focusing it and pressing Enter; shift extends) resolves
  its `data-name` through `SceneNavigation` to an `Address` and sets semantic
  focus plus `CommonViewState.selection`; clicking an overlay band resolves
  through `NavigationIndex` the same way. The Codex marks focused and selected
  lines only for direct `on-mark` placements. A `via-ancestor` placement uses a
  separate dotted/double proxy treatment whose accessible label names both the
  preserved original address and the visible ancestor; it never sets the
  ancestor's `aria-pressed` or `aria-current` state as though the identities
  were equal. `off-projection` remains panel-only. Focus, selection, and
  horizon survive every representation change. A monotone intent revision
  prevents a delayed compilation from replacing a newer requested state.
- **Receipts.** Source and configuration checksums, the compiler version and
  sibling pins, the shared-state parts, the current horizon, the
  `LayoutReceipt` fields, and the measurer in use, as text.

No inference happens in the shell: every element is read off a compiled
`CodexFlow`, `PaginatedCodex`, or `NarrativeScene`. No external resource:
no font, no stylesheet, no CDN; the only script is `app.js`
(`ModuleKind.NoModule`, so `file://` works). The build needs the root
`.jvmopts` (4g heap, G1): linking the app after a full-repo compile and test
run exceeds the sbt launcher's 1g default.

Follow-ups recorded from review (not implemented):

- Two `asInstanceOf` sites remain in `layout/.js` and `app`: the canvas idiom
  (`createElement("canvas")` returns `Element`; `getContext("2d")` returns
  `js.Dynamic`) has no typed facade for the 2-D context, and `SvgDom.inject`
  could use the typed `querySelectorAll` instead of casting each node.
- Content security: ship `app.css` instead of the inline `<style>`, set the
  rail's font and line height through the CSSOM instead of the `style`
  attribute string, and add a `<meta http-equiv="Content-Security-Policy">`
  so the page can declare that it loads nothing external.
- Move book-scale compilation into a worker and add measured range/progressive
  materialization. The current WOG shell schedules work off the input handler,
  absorbs threshold jitter, and rejects stale completions, but compilation
  itself still runs on the browser's main thread.

### Browser smoke

```sh
node app/smoke/smoke.cjs target/edition/index.html
```

drives headless Chromium through Playwright at the exact pin in `e2e/static`
(`npm --prefix e2e/static ci` and
`npx --prefix e2e/static playwright install --only-shell chromium` once) and
checks against the live DOM that the rail's text
content hashes to the source checksum the receipts print (V-T2 without any
copy of the text), that the reader-at-0 view has no Atlas mark and no Codex
piece while the rail is unchanged, that a mid-text horizon lies strictly
between, that the Reading lens and the measurer switch leave the rail
unchanged, that surface zoom creates real sentence and token marks, that
hysteresis absorbs threshold jitter, that exact identities restore, that a
focused selection traverses `on-mark`, `via-ancestor`, and `off-projection`,
that an ancestor proxy is visually and accessibly distinct from direct
selection, and that the page logs no error.

Every page prints its basis ("researcher-reviewed narrative acceptance
fixture"). Story text is never copied into this repository; the twins render
it from the storymodel4s fixture at run time.

## Workspace registration

Registered in the workspace catalog (`../packages.toml`) as package
`storyatlas4s` (kind application, stage prototype, JVM + Scala.js) with two
`source-pin` dependency edges: `storymodel4s` at `5ca2f79…` (`viewJVM`,
`viewJS`, `fixturesJVM`, `fixturesJS`) and `intaglio` at `52dddee…`
(`coreJVM`, `coreJS`, `svgJVM`, `svgJS`). `tools/workspace.py check` reports
one policy warning for this repository — storymodel4s is a prototype-stage
provider — which is expected until storymodel4s advances a lifecycle stage.

## License

Apache-2.0

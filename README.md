# storyatlas4s

Application and renderer adapters for the **Narrative Codex** and **Narrative
Atlas** of [storymodel4s](https://github.com/bbuchsbaum/storymodel4s), per ADR
0002 (visualization contract). storymodel4s compiles every artifact
(`CodexFlow`, `NarrativeScene`) and owns every claim; this repository lowers
those artifacts to [Intaglio](https://github.com/canardlapin/intaglio) scenes
and writes figures, textual twins, and receipts. It infers nothing and lays out
nothing.

Status: prototype, slice 1 (static War of the Ghosts edition). Unpublished.

## Modules

| Module | Platforms | Depends on | Owns |
|---|---|---|---|
| `storyatlas4s-layout` | JVM, JS | storymodel4s `view` (SHA pin) | `Paginator.paginate(flow, page, metrics)`: pure pagination of a `CodexFlow` into pages, lines, and annotation fragments with V-I2 ids; `Measurer` seam with metrics-as-data (`TextMetrics`), the fixed `MonospaceMeasurer` (publication), an optional AWT measurer (JVM), a DOM measurer stub (JS); `PaginatedCodex` twin and `LayoutReceipt` |
| `storyatlas4s-intaglio` | JVM, JS | `layout`, storymodel4s `view` (SHA pin), intaglio `core`/`svg` (SHA pin) | pure lowering `NarrativeScene → intaglio.Scene`, `CodexFlow → intaglio.Scene`, and `PaginatedCodex → Vector[intaglio.Scene]` (one page overlay per page); `GraphicsName` = `MarkId` / `AnnotationId` / `FragmentId` |
| `storyatlas4s-cli` | JVM | above + storymodel4s `fixtures` | `edition --out <dir>`: atlas SVGs at Story/Episode/Scene zoom, Codex overlays and paginated `codex-<lens>.html` pages for the Reading and Overview lenses, textual twins, `receipt.json` |
| `storyatlas4s-app` | JS | `intaglio`, `layout`, storymodel4s `fixtures` (SHA pin), intaglio `svg`, Laminar 17.2.1, scalajs-dom 2.8.1 | the browser shell: the same compilers, paginator, and lowerings run in the browser over the War of the Ghosts fixture; DOM text rail + Intaglio SVG overlay per page, Reading/Overview lenses, Story/Episode/Scene zoom, the epistemic playhead (`ReaderAt` horizon), selection as `Set[Address]` shared by Codex and Atlas, textual twins and receipts as text; the live `DomMeasurer` |

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
```

writes (relative paths resolve against the repository root), for the
researcher-reviewed *War of the Ghosts* fixture:

- `atlas-story.svg`, `atlas-episode.svg`, `atlas-scene.svg` and their `.txt`
  twins (Discourse Atlas at the three narrative levels the fixture suite
  exercises; x = exact discourse offset, y = context lane);
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
- `receipt.json`: basis, source checksum, sibling pins, and per-file
  configuration checksums, mark counts, SHA-256 of the written text, and — for
  the paginated files — the `LayoutReceipt` fields (V-D3).

The edition is byte-identical across runs (the suite writes it twice and
compares).

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
- **Atlas and selection.** The Story / Episode / Scene zoom levels render
  from `AtlasLowering`. Clicking a mark (or focusing it and pressing Enter;
  shift extends) resolves its `data-name` through `SceneNavigation` to an
  `Address` and sets `CommonViewState.selection`; clicking an overlay band
  resolves through `NavigationIndex` the same way. The Codex outlines the
  lines carrying the selected annotation's pieces, and the panel prints the
  placement in both artifacts as text — `on-mark` / `via-ancestor` /
  `off-projection` (V-L2) for the Atlas, `on-annotation` / `not-annotated`
  for the Codex — never as a colour alone (V-U5).
- **Receipts.** Source and configuration checksums, the compiler version and
  sibling pins, the shared-state parts, the current horizon, the
  `LayoutReceipt` fields, and the measurer in use, as text.

No inference happens in the shell: every element is read off a compiled
`CodexFlow`, `PaginatedCodex`, or `NarrativeScene`. No external resource:
no font, no stylesheet, no CDN; the only script is `app.js`
(`ModuleKind.NoModule`, so `file://` works).

### Browser smoke

```sh
node app/smoke/smoke.cjs target/edition/index.html
```

drives headless Chromium through Playwright (resolved from the working tree,
`NODE_PATH`, or the global npm root; `npm i -g playwright && npx playwright
install chromium` once) and checks against the live DOM that the rail's text
content hashes to the source checksum the receipts print (V-T2 without any
copy of the text), that the reader-at-0 view has no Atlas mark and no Codex
piece while the rail is unchanged, that a mid-text horizon lies strictly
between, that the Reading lens and the measurer switch leave the rail
unchanged, that a click on a mark yields an `on-mark` / `on-annotation`
placement in the panel, and that the page logs no error. Without Playwright,
do the same by hand: open `index.html`, compare the receipts' `sourceChecksum`
with `sha256` of the rail text (select all text inside a page), drag the
playhead to 0 and back, and click a mark.

Every page prints its basis ("researcher-reviewed narrative acceptance
fixture"). Story text is never copied into this repository; the twins render
it from the storymodel4s fixture at run time.

## Workspace registration

Registered in the workspace catalog (`../packages.toml`) as package
`storyatlas4s` (kind application, stage prototype, JVM + Scala.js) with two
`source-pin` dependency edges: `storymodel4s` at `8492e43…` (`viewJVM`,
`viewJS`, `fixturesJVM`, `fixturesJS`) and `intaglio` at `596b398…`
(`coreJVM`, `coreJS`, `svgJVM`, `svgJS`). `tools/workspace.py check` reports
one policy warning for this repository — storymodel4s is a prototype-stage
provider — which is expected until storymodel4s advances a lifecycle stage.

## License

Apache-2.0

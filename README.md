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
| `storyatlas4s-intaglio` | JVM, JS | storymodel4s `view` (SHA pin), intaglio `core`/`svg` (SHA pin) | pure lowering `NarrativeScene → intaglio.Scene` and `CodexFlow → intaglio.Scene`; `GraphicsName` = `MarkId` / `AnnotationId` |
| `storyatlas4s-layout` | JVM, JS | storymodel4s `view` (SHA pin) | `Paginator.paginate(flow, page, metrics)`: pure pagination of a `CodexFlow` into pages, lines, and annotation fragments with V-I2 ids; `Measurer` seam with metrics-as-data (`TextMetrics`), the fixed `MonospaceMeasurer` (publication), an optional AWT measurer (JVM), a DOM measurer stub (JS); `PaginatedCodex` twin and `LayoutReceipt` |
| `storyatlas4s-cli` | JVM | above + storymodel4s `fixtures` | `edition --out <dir>`: atlas SVGs at Story/Episode/Scene zoom, Codex overlays for the Reading and Overview lenses, textual twins, `receipt.json` |

Planned (later beads): `codex.html` from the paginated codex in the edition, and
`storyatlas4s-app` (Laminar shell with the real DOM measurer).

## Identity and the renderer protocol

Intaglio serializes a `GraphicsName` as the SVG `data-name` attribute. Here
that name is always the rendered **mark or annotation identity**, never an
`Address` (ADR 0002 §6): one address yields many marks across zoom levels, so
`Address` is not injective within a figure. Semantic selection stays
`Set[Address]`; a `data-name` resolves to an address through
`NarrativeScene.navigation` (Atlas) or `CodexFlow.navigation` (Codex).

The Codex overlay in `intaglio` still names one group per annotation by its
`AnnotationId`. The `layout` module already produces the paginated form: a text
line is `line/p<page>/l<line>`, and an annotation piece on a line is
`<AnnotationId>/p<page>/l<line>/r<ref>` (V-I2; `ref` is the index of the
support `SpanRef` the piece is cut from, always `r0` for contiguous
annotations). Wiring those ids into the overlay group names and `codex.html`
is the next bead.

## Pagination

`storyatlas4s-layout` is the owned deterministic publication backend of ADR
0002 D3. A `Measurer` turns each source run into `RunMetrics` — one integer
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
- `receipt.json`: basis, source checksum, sibling pins, and per-file
  configuration checksums, mark counts, and SHA-256 of the written text.

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

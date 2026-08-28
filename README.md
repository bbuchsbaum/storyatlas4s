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
| `storyatlas4s-cli` | JVM | above + storymodel4s `fixtures` | `edition --out <dir>`: atlas SVGs at Story/Episode/Scene zoom, Codex overlays for the Reading and Overview lenses, textual twins, `receipt.json` |

Planned (later beads): `storyatlas4s-layout` (`TextLayoutCapability`
implementations) and `storyatlas4s-app` (Laminar shell).

## Identity and the renderer protocol

Intaglio serializes a `GraphicsName` as the SVG `data-name` attribute. Here
that name is always the rendered **mark or annotation identity**, never an
`Address` (ADR 0002 §6): one address yields many marks across zoom levels, so
`Address` is not injective within a figure. Semantic selection stays
`Set[Address]`; a `data-name` resolves to an address through
`NarrativeScene.navigation` (Atlas) or `CodexFlow.navigation` (Codex).

Until `PlacedCodex` exists in storymodel4s, a Codex annotation is one fragment,
named by its `AnnotationId`; when pagination lands, fragment ids derive from
`(AnnotationId, page, line)` (V-I2) and the group name follows.

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

writes, for the researcher-reviewed *War of the Ghosts* fixture:

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

## License

Apache-2.0

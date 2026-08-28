# AGENTS.md

storyatlas4s is the application and renderer-adapter repository for the
Narrative Codex and Narrative Atlas of storymodel4s (ADR 0002). It owns no
science: every artifact it draws is compiled in storymodel4s `view`; every
address it selects is a `storymodel4s.core.Address`; every figure carries the
`ViewProvenance` it was given. storymodel4s never depends on this repository.

## Layout

- `intaglio`: `crossProject(JVM, JS)`, `CrossType.Pure`, package
  `storyatlas4s.intaglio`, depends on `layout`. Pure lowering
  `NarrativeScene → intaglio.Scene`, `CodexFlow → intaglio.Scene` (annotation
  overlay), and `PaginatedCodex → Vector[intaglio.Scene]` (one page-framed
  overlay per page, `PagedCodexLowering`). `GraphicsName` is the `MarkId`
  (Atlas), `AnnotationId` (flow-level Codex), or `FragmentId` (paginated
  Codex), never an `Address`.
- `layout`: `crossProject(JVM, JS)`, `CrossType.Pure`, package
  `storyatlas4s.layout`. Pure `Paginator` over metrics-as-data (`TextMetrics`
  from a `Measurer`), `PaginatedCodex` with V-I2 fragment ids, its textual
  twin, and `LayoutReceipt`. Platform sources: `layout/.jvm` (optional
  `AwtMeasurer`), `layout/.js` (`DomMeasurer` stub until the app bead). No
  doubles and no JVM-only API in the shared sources.
- `cli`: JVM only, package `storyatlas4s.cli`. `edition --out <dir>` compiles
  the War of the Ghosts fixture (from storymodel4s `fixtures`) to atlas SVGs,
  Codex overlays, paginated `codex-<lens>.html` documents (`CodexHtml`, a pure
  `PaginatedCodex → String`; DOM text rail + inline SVG overlay per page),
  textual twins, and `receipt.json` with the `LayoutReceipt` folded in.
- Sibling sources are immutable git-SHA `ProjectRef` pins declared in
  `build.sbt` (`storymodel4sRevision`, `intaglioRevision`). No `../sibling`
  composite builds, no `-SNAPSHOT` dependencies.

## Build and test

- Scala 3.7.4, sbt 1.12.14, sbt-typelevel 0.8.7, munit 1.3.4.
- storymodel4s's own build pins grakern by `ProjectRef`; grakern has no remote
  yet, so every sbt invocation that loads storymodel4s must pass
  `-Dstorymodel4s.grakern.build=<grakern checkout>` in addition to this
  repository's own overrides. Until the storymodel4s pin is pushed, also pass
  `-Dstoryatlas4s.storymodel4s.build=<storymodel4s checkout>`:

  ```sh
  sbt -Dstoryatlas4s.storymodel4s.build=/path/to/storymodel4s \
      -Dstorymodel4s.grakern.build=/path/to/grakern \
      -Dstoryatlas4s.intaglio.build=/path/to/intaglio \
      compileAll testAll scalafmtCheckAll
  ```

  Environment equivalents: `STORYATLAS4S_STORYMODEL4S_BUILD`,
  `STORYATLAS4S_INTAGLIO_BUILD`, `STORYMODEL4S_GRAKERN_BUILD`.
- Run `sbt <overrides> compileAll testAll scalafmtCheckAll` before declaring
  work complete. Platform-independent tests live in `intaglio/src/test` and
  `layout/src/test` and must pass on both JVM and Scala.js.
- Keep `-Wunused:all -Wvalue-discard` warning-clean.
- Do not run sbt inside the sibling checkouts you point the overrides at while
  other work is gating there; point the overrides at an isolated clone instead.

## GitHub identity

- This repository belongs to `canardlapin`. Keep `github.account`, commit
  identity, and `origin` repo-local; never change the machine-wide GitHub
  account for storyatlas4s.
- Git pushes use the `github-canardlapin` SSH alias.

## Design contract

- No view-side inference (ADR 0002 D4): the lowering draws exactly the marks
  and annotations it is given, at the offsets and lanes it is given. It never
  computes a layout, hull, or coordinate that `view` did not.
- Identity: one SVG `data-name` per mark or annotation, equal to its
  `MarkId`/`AnnotationId`; semantic selection stays `Set[Address]` and resolves
  through `SceneNavigation`/`NavigationIndex`, never through renderer names.
- Intaglio never sees canonical text, addresses, claims, or projection kinds;
  the Atlas lowering takes the discourse length as an integer, nothing more.
- Determinism (V-D1/V-D2): same input, byte-identical output on JVM and JS.
- Prefer precise ADTs, smart constructors, and `Either` over exceptions.
- WOG story text stays data in storymodel4s; never copy it here.

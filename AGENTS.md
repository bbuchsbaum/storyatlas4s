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
- `app`: Scala.js only (`ModuleKind.NoModule`, Laminar 17.2.1, scalajs-dom
  2.8.1), package `storyatlas4s.app`. `AppCompiler` is the pure step from a
  `ViewChoice` (lens, zoom level, horizon, selection, measurer) to everything
  drawn — the same compiler, paginator, and lowering calls `cli/Edition` makes,
  under one `CommonViewState`; `AppView` binds it to the DOM with one
  `Var[ViewChoice]`. `EditionSpec` mirrors `cli/Edition`'s constants (page,
  font, relation layers, thread budget) because `cli` is JVM-only; keep them
  in step. The model is `WarOfTheGhostsModel.model` from storymodel4s
  `fixtures`, linked into `app.js`; never a copy. `app/index.html` is the
  static shell; `app/editionBundle` copies it and `app.js` into
  `target/edition`; `app/smoke/smoke.cjs` is the Playwright browser smoke.
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
- Run `sbt <overrides> compileAll testAll scalafmtCheckAll app/fastLinkJS`
  before declaring work complete. Platform-independent tests live in
  `intaglio/src/test` and `layout/src/test` and must pass on both JVM and
  Scala.js; `app/test` and `layout/.js` tests run under Node (no DOM). For a
  change that touches the shell, also run the browser smoke:
  `sbt <overrides> "cli/run edition --out target/edition" app/editionBundle`
  then `node app/smoke/smoke.cjs target/edition/index.html`.
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
- The shell infers nothing and never encodes meaning in colour alone: kind and
  lane are the overlay row and the legend; selection is an outline, a dashed
  stroke, `aria-pressed`, and the panel's placement text; the horizon and every
  checksum are printed. No external resource (font, stylesheet, CDN, fetch).
- Determinism (V-D1/V-D2): same input, byte-identical output on JVM and JS.
- Prefer precise ADTs, smart constructors, and `Either` over exceptions.
- WOG story text stays data in storymodel4s; never copy it here.

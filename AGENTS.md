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
  Codex), never an `Address`. An Atlas scene lowers into three rails: the
  projection's own context-lane plot, the layout-only surface rail, and the
  layout-only epistemic rail for the marks that carry no lane. A
  `ContextBand` draws one shape per extent and **never a hull** over the gaps
  between them; hulling the narrated world would swallow the speech frames
  inside it. `Gap`, `Abstention` and `UnsatisfiedLaw` are placed from their
  `EpistemicPlacement`: on the exact spans they cite, or in a margin row
  carrying the stated reason when the model gives them no honest discourse
  position. Their epistemic state is a point shape, never a colour, so each
  channel is legible in monochrome (V-U5, D9).
- `layout`: `crossProject(JVM, JS)`, `CrossType.Pure`, package
  `storyatlas4s.layout`. Pure `Paginator` over metrics-as-data (`TextMetrics`
  from a `Measurer`), `PaginatedCodex` with V-I2 fragment ids, its textual
  twin, and `LayoutReceipt`. Platform sources: `layout/.jvm` (optional
  `AwtMeasurer`), `layout/.js` (`DomMeasurer` stub until the app bead). No
  doubles and no JVM-only API in the shared sources.
- `edition`: `crossProject(JVM, JS)`, `CrossType.Pure`, package
  `storyatlas4s.edition`, depends on `layout`. `EditionSpec` (page box, font,
  relation layers, thread budget, lenses, both zoom axes, SVG boxes) and the
  generated `Pins`; the one place an edition constant lives, consumed by both
  `cli` and `app`.
- `cli`: JVM only, package `storyatlas4s.cli`. `edition --out <dir>` compiles a
  model to atlas SVGs, Codex overlays, paginated `codex-<lens>.html` documents
  (`CodexHtml`, a pure `PaginatedCodex → String`; DOM text rail + inline SVG
  overlay per page), textual twins, and `receipt.json` with the
  `LayoutReceipt` folded in. Without `--model` the model is the War of the
  Ghosts fixture (storymodel4s `fixtures`), the fast case and no longer the
  only one; `--model <storymodel.json>` reads a model the storymodel4s
  pipeline wrote through `ModelInput`, which decodes it with storymodel4s
  `codec` and puts it to `StoryValidator`. `cli` never promotes a model and
  never parses one itself: `Validated` comes only from the validator. A model
  the validator promotes but that carries no build receipt is refused, since
  no `ViewBasis` is true of it. A model it does not promote is compiled as a
  draft (`ViewBasis.DraftBuild`, `AtlasCompiler.compileDraft`), atlases only,
  with its unsatisfied laws as marks. `ReadModel` carries a `DerivationRecord`
  read from `derivation.json` beside the model when that file exists
  (storymodel4s `DerivationRecordCodec`, model-bound: a record for another
  story, source or build is refused, never paired), and `NotSupplied` when it
  does not; the receipt then prints `ModelInput.derivationRecordNote` rather
  than zero gaps. `compilation-report.json` is never read: it writes counts and
  one-way renders, and reconstructing a record from it would mean fabricating
  claim and evidence ids, which is the out-claiming the recovery plan exists to
  prevent.
- `app`: Scala.js only (`ModuleKind.NoModule`, Laminar 17.2.1, scalajs-dom
  2.8.1), package `storyatlas4s.app`. `AppCompiler` is the pure step from a
  `ViewChoice` (lens, exact `ZoomLevel`, horizon, focus, selection, measurer) to
  everything drawn — the same compiler, paginator, and lowering calls
  `cli/Edition` makes, under one `CommonViewState`; `AppView` binds it to the
  DOM with one `Var[ViewChoice]`. Continuous gesture positions commit through
  deterministic hysteresis and never enter the receipt; a monotone intent
  revision rejects stale compilation results. Every edition constant comes
  from `edition/EditionSpec`,
  shared with `cli`. The model is `WarOfTheGhostsModel.model` from storymodel4s
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

- No view-side scientific inference (ADR 0002 D4): the lowering draws exactly
  the marks and annotations it is given and preserves every provider-supplied
  projection coordinate. It never invents semantic order, hulls, or positions.
  When a projection contract explicitly declares that a mark has no projection
  coordinate, the lowering may assign deterministic layout-only device
  placement for legibility only when the contract also declares that placement
  non-metric and meaningless. Device placement never feeds back into the model,
  identity, selection, or another projection.
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

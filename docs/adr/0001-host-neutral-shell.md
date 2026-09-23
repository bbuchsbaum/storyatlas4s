# StoryAtlas ADR 0001: One host-neutral shell core, several thin hosts

22 September 2026 · Status: accepted for the extraction slice · Mote `bd-01M35KZHNDM6E2NAQBN8N4YPD8`

Numbering is local to storyatlas4s. References to "ADR 0002" elsewhere in this repository mean
storymodel4s ADR 0002, the visualization contract.

## Context

The interactive application is a Scala.js/Laminar shell (`app`). The workspace design
([workspace-design.md](../delivery/workspace-design.md) §2) kept that stack and declined a
replacement frontend. We now also want to be able to ship the same application as a desktop
program — a JavaFX host, an Electron or other web-view wrapper, or a locally hosted page — and to
make that choice later without a rewrite.

Most of what the shell decides was already pure but lived in the JavaScript-only `app` project:
the view choice and playhead, the semantic-zoom hysteresis, the last-intent-wins compilation gate,
the checked renderer-name index, the direct/proxy interaction decorations, and the compile step
itself. The compile step also rendered SVG strings, so its output could only be drawn by a DOM.

Intaglio already draws one `Scene` on several backends (SVG and Canvas on Scala.js; SVG, Java2D,
PDF and JavaFX on the JVM), so the renderer is not the constraint. The constraint is where the
shell's decisions live.

## Decision

1. **A cross-built `shell` module (JVM and Scala.js) owns every host-independent decision.** It
   contains the view choice and its transitions, semantic zoom, the compilation intent gate, the
   compile step, the renderer-name index, and the interaction presentation of each named target.
   It has no DOM, Laminar, JavaFX or file-system dependency. Its suites run on both platforms;
   that dual run is the portability witness.
2. **The compile step produces renderer-neutral plates.** A `Plate` is an Intaglio `Scene`, its
   pixel box and its accessible title. A host chooses the backend: the web host renders SVG, a
   JavaFX host would draw the same scene on a canvas. A rendering failure is reported by the host
   for that plate (the web host shows an alert carrying the renderer's stated reason); it never
   changes scientific state. Compilation itself no longer renders, so the shell's compilation state
   (`data-state` in the web host) reports compile success only; a plate that cannot be drawn is a
   separate, visible host failure.
3. **A host only translates.** It turns platform input into shell transitions (activate a
   rendered name, extend a selection, move a gesture, choose a lens) and draws what the shell
   returns. Hit-testing is the host's job and yields a rendered name; the shell resolves that name
   through the checked index to an `Address`. Presentations are computed, and every decoration
   validated against that index, in `shell` (`InteractionPresentation.forTargets`). A behaviour
   that exists in one host only is either moved into `shell` or not shipped.
4. **Hosts are equals.** `app` (Laminar) is the first host. A desktop host is a new module that
   depends on `shell` and one Intaglio backend. Nothing in `shell`, `intaglio`, `layout` or
   `edition` may depend on a host.

This amends workspace-design §2 ("no replacement frontend") to: *no second implementation of any
shell decision*. Additional hosts are allowed; duplicated decisions are not. The §7.2 controller
(artifact, scientific-view and interaction state) is to be built in `shell`, not in `app`.

## What each host must still provide

| Concern | Web host (`app`) | A canvas host (e.g. JavaFX) |
|---|---|---|
| Drawing | `SvgRenderer` into the DOM | `JavaFxRenderer` onto a `Canvas` |
| Hit-testing → rendered name | `closest("[data-name]")` | Intaglio picking over the plate's scene — **not yet available**, see below |
| Selection/focus emphasis | CSS on the named SVG groups, driven by the shell's `InteractionPresentation` | Must be drawn in the scene; see below |
| Accessible names, tooltips | SVG `GrobMeta` + ARIA from `InteractionPresentation.label` | Native controls (table/list, inspector) from the same labels; Intaglio drops `GrobMeta` off SVG |
| Exact text, Codex rail | DOM text | Native text nodes; the paginated layout is shared, the text widget is not |
| File open, media | Browser file chooser, browser media | Native chooser reusing the JVM readers in `cli` |

## Known gaps before a non-DOM host can ship

1. **Picking named scenes.** Intaglio's portable picking (`intaglio.interaction`) routes only
   plans compiled from a `Plot`; the scene-level entry point is package-private. StoryAtlas
   lowerings build scenes directly and name them with `GraphicsName`. An additive Intaglio API that
   compiles a `PickingPlan` from a `Scene` routed by grob name is required. It is tracked in the
   Intaglio repository; StoryAtlas does not bump its Intaglio pin until it uses that API.
2. **Emphasis as scene content.** The web host shows selection and focus with CSS applied after
   rendering. A canvas cannot. The lowering (or a pure post-lowering pass in `shell`) must accept
   the shell's presentations and emit the emphasis as marks, with the unselected output
   byte-identical to today's publication plates.
3. **Host-specific names still in the core.** `MeasurerChoice.Dom` names the web host's live
   measurer. The shell only carries the choice (the measurer itself is injected), but a second
   host needs it generalized to a host-supplied live measurer.
4. **Plate and index travel separately.** `Compiled` holds each plate beside its checked
   `RenderedTargetIndex`; nothing in the types stops a host from resolving a hit against the
   wrong plate's index. Bundle them when the first non-DOM host is written.
5. **Recall Voyage.** `VoyageView`'s pure parts (compile under a selection, unit ordering and
   walking, inspector and hover-card content) move into `shell` in a follow-up, after the current
   Voyage presentation work releases those paths.

## Consequences

- Choosing Electron or a hosted desktop is cheap: both reuse `app` unchanged.
- Choosing JavaFX costs a host module plus the two gaps above, not a port of shell logic.
- `shell` is the host contract, so its types are public; it stays pre-release and may change.
- Publication (the CLI edition) is unaffected: it never used the shell, and its bytes are pinned by
  existing courts.

### M1 composition — 22 September 2026

Accepted: the portable `edition.SourceInput` owns the existing canonical model,
derivation and feature admission policy; the CLI remains its filesystem adapter.
`ImportedSource` retains the validation outcome and draft record. `shell.AppCompiler`
adds a draft entry point using the existing producer compilers and `DraftBuild`
provenance, without changing the explicit built-in fixture route.

`WorkspaceController` owns one artifact-qualified `WorkspaceState`. Source,
recall, matrix and Voyage hosts send `WorkspaceAction`s; they do not synchronize
independent selection stores. Display horizons, cursors and viewport coordinates
remain separate. `Correspondence` retains the active row while inspecting its
source target. Mapping changes select an already checked artifact with the same
fixed universe; the controller never executes an estimator. `WorkspaceSave`
serializes a canonical, versioned descriptor bound to every manifest member and
the fixed cut. Reopening requires the exact admitted artifacts and revalidation;
the saved descriptor carries neither narrative payloads nor permission grants.

Rejected: putting these policies in Laminar components or duplicating the CLI's
reader in the browser. Both would permit hosts to disagree about admission and
semantic identity. No new module or dependency is introduced by this composition.

`edition.ArtifactInput` dispatches canonical local workspace/source/Voyage inputs
under explicit byte/file limits with content-free refusals. Source-only groups
accept only their declared companions/sidecars, so an incompatible or denied
workspace cannot silently fall back to a source route. `shell.WorkspaceOpen`
reuses `LatestIntent` for atomic last-request-wins admission and cancellation;
failed or obsolete reads retain the previous admitted artifact. It stores no
unadmitted bytes in published snapshots.

`intaglio.MatrixLowering` draws the producer's fixed-cut matrix as a labeled
numeric plate. Each supplied raw, normalized, transport and posterior-fidelity
value remains separate. Renderer cell names resolve through the drawing's checked
row/destination index; they are never saved semantic addresses. Equal-width
columns are display geometry, not elapsed time or inferred narrative order.

`WorkspaceImport` composes checked artifact admission with optional saved-state
replay as one transaction; `WorkspaceSession` is the admitted host mode. The
alternative of loading a descriptor into a separate mutable pending-state store
was rejected because cancellation or a late file read could pair state with the
wrong packet. `WorkspaceOpen[A]` only publishes a fully admitted host value.
`WorkspaceExport` checks the producer's export capability first, then packages
its exact subset with the matrix figure/twin and the identity-bound descriptor.
The full permitted scientific subset and the recorded presentation horizons are
labeled separately. The browser owns file picking/downloads; the shell owns no
filesystem, fetch, provider invocation or new inference.

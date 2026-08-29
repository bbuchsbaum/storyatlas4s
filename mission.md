# Mission

The mission of storyatlas4s is to turn compiled, evidence-backed narrative
artifacts into deterministic, accessible, and publication-grade instruments
for reading stories and studying recall.

storyatlas4s is the application and rendering sibling of storymodel4s. The
boundary is deliberate:

- **storymodel4s owns scientific meaning.** It compiles exact source support,
  semantic addresses, narrative structure, uncertainty, feature derivations,
  recall assessments, and projection contracts.
- **storyatlas4s owns presentation and use.** It paginates exact text, applies
  page and device geometry, lowers typed artifacts to renderers, manages
  interaction and viewport policy, produces accessible textual twins and
  publication artifacts, and records layout and export receipts. It does not
  alter the scientific placement semantics compiled into a projection.

StoryAtlas never repairs, completes, smooths, aggregates, or reinterprets a
scientific artifact merely to make a cleaner figure. If a needed quantity is
missing, unresolved, unauthorized, or not yet compiled, the instrument shows
that state.

## What we are building

The product follows one accountable path:

```text
validated or explicitly diagnostic narrative artifacts
  -> packet-specific scientific compilation under packet-appropriate state
     (story views through storymodel4s.view;
      recall and interview through their own typed provider artifacts)
  -> Codex, projection, feature, recall, or interview packets
  -> deterministic pagination and presentation geometry
  -> renderer lowering and coordinated interaction
  -> interactive workspace, textual twin, figure, and receipt
```

The primary product is a split overview-and-detail workspace, not five peer
applications:

- The **Narrative Codex** preserves exact, selectable, searchable text and
  local annotations.
- The **projection pane** can show the Discourse Atlas, Chronology Loom,
  bounded relation views, feature scale-space, a source–recall matrix, or a
  recall voyage.
- The **inspector** exposes identity, source support, claims, alternatives,
  uncertainty, feature provenance, and layout meaning.
- One workspace state coordinates typed selection and focus across all visible
  faces, while each scientific packet retains its own evidence horizon,
  relation policy, feature choice, and privacy boundary.

The resulting instrument must be fully automatic in ordinary use. Given an
admissible compiled model, policy, and view specification, producing the
workspace and publication artifacts must not require hand-authored AMRs,
events, scene layouts, SVG edits, or story-specific code. Human expertise
belongs in standards, prompts, gold fixtures, calibration, adversarial review,
and scientific adjudication—not in routine rendering of each input.

## Operating commitments

We will:

1. **Keep exact text primary.** Prose remains real DOM text for reading,
   selection, copying, search, and accessibility. Renderer overlays never own
   canonical text or offsets. Source text is supplied by the provider artifact
   and is not copied into this repository.

2. **Preserve semantic identity across every face.** Selection and focus use
   typed `Address` values. Renderer identities such as marks, annotations, and
   fragments remain distinct and map back to addresses. Reflow, zoom,
   projection changes, and export may change geometry but not semantic
   identity.

3. **Conserve the scientific record.** A change of scale or projection must
   preserve placement, coverage, missingness, alternatives, external or
   abstained mass, and evidence descent. A selected object is `OnMark`,
   `ViaAncestor`, or `OffProjection`; it is never silently lost.

4. **Keep coordinate systems honest.** Every projection declares what its
   position, distance, area, width, opacity, texture, colour, and motion mean.
   Discourse order, story-world partial time, recall order, interview time,
   and analytical scale remain distinguishable. Layout-only coordinates and
   bundles are labelled as such.

5. **Implement semantic zoom as representation change.** Narrative level and
   surface detail remain independent typed axes. Feature scale remains a
   separate measurement basis, and screen-space density remains presentation
   policy. Continuous gestures commit deterministic discrete semantic states
   with hysteresis; asynchronous compilation is last-intent-wins. The exact
   Codex reading focus survives every transition.

6. **Render numerical features without view-side analysis.** The instrument
   may resolve checked sidecar rows and request model-owned derivations. It may
   not invent a moving average, impute missing values, change a reducer, or
   renormalize a track. Every feature view exposes its space, units, target,
   reducer, window or narrative basis, coverage, missingness, normalization,
   circularity status, and receipt.

7. **Make known-source recall mapping posterior-faithful.** The alignment
   matrix is the first mapping view because it can retain hierarchy,
   multimodality, distortion, external states, `Unranked`, abstention, and
   unresolved mass. Dual rails and route views are coordinated explanations,
   not replacements for the matrix. Story and recall keep independent scale,
   evidence horizons, and compilation receipts. Misordering is a transition
   property and omission is source-side missing mass; neither becomes a
   synthetic utterance in the recall rail. Population summaries declare their
   estimand, denominator, contributing and contentless subjects, support, and
   treatment of external or abstained mass.

8. **Treat recall identity and privacy as separate gates.** Source and recall
   artifacts are compiled separately and connected through a pure,
   match-checked trace. The join binds the recall checksum and view
   fingerprint and carries no copied text. Authorization to resolve recall
   spans remains an explicit privacy capability; identity matching alone does
   not reveal participant speech.

9. **Support autobiographical interviews without inventing a source.** A typed
   interview packet preserves the two-speaker transcript, cue, probes, phases,
   and prompt provenance. Only participant details contribute memory-address
   mass. The transcript is the observational rail; a latent episode is an
   explicitly hypothesized projection. `MemoryAddress` is the mapping
   vocabulary, and `DetailAssessment` is the basic analytical row. Any
   recall-unit aggregation must be named, receipted, mass-conserving, and
   descend to its contributing details. Conventional internal/external scores
   are named, versioned policy projections. Phenomenology and ratings are
   separate channels with missing distinct from low.

10. **Scale by explicit policy, not semantic compromise.** Novel-scale views
    use range queries, mark budgets, stable label priorities, bounded
    neighbourhoods, and layout-only aggregation that retains every member id
    and descent path. Tiles, GPU rendering, workers, or new storage schemes
    are introduced only after named benchmarks demonstrate a need.

11. **Use the existing renderer boundary.** Intaglio is the first dogfood
    backend behind the renderer protocol; it is not the ontology. We will add
    generic renderer mechanisms only when typed spikes demonstrate them and
    extract a separately published abstraction only when multiple real
    consumers prove its value.

12. **Make scientific interaction accessible and reproducible.** Every
    artifact has a deterministic textual twin. Type and status never depend on
    colour alone. Keyboard, focus, screen-reader, reduced-motion, and export
    paths remain complete. Quiet defaults enforce a declared annotation-channel
    budget rather than shrinking text around every available layer. Saved views
    record model identity, semantic state, specification, projection contract,
    camera, layout metrics, feature receipts, and software versions.

13. **Test visible truth, not only data structure.** Laws cover exact-text
    tiling, identity round trips, semantic-selection conservation, evidence
    reachability, refinement honesty, occlusion and pickability, deterministic
    JVM/JS output, stale-result rejection, and preservation of hidden bundle
    members. Browser tests exercise real interaction, while textual twins and
    publication artifacts provide independent, inspectable oracles.

## Delivery sequence

The sequence is driven by scientific contracts rather than spectacle:

1. harden the current Codex/Atlas tracer and its browser, accessibility,
   identity, evidence-horizon, and publication laws;
2. consume `storymodel4s.view`-compiled surface primitives, then expose dynamic
   semantic zoom and prove address conservation across both axes;
3. materialize one receipted feature from raw words through declared windows
   into Codex and feature scale-space;
4. add a chronology projection that represents world-time constraints and
   context-scoped insets without inventing a total clock;
5. add the known-source mapping workspace after the recall-side trace and
   privacy witness are sound, beginning with a matrix fixture containing both
   distortion and external material;
6. add the autobiographical-interview packet and `MemoryAddress` workspace
   without treating an induced episode as observed fact;
7. validate long-story, novel-scale, multi-recall, and population views under
   named latency, memory, mark-budget, evidence, and usability gates.

Each slice must use the same focus and navigation laws while keeping
packet-specific horizons and policies explicit. If a new view requires private
selection state, unreceipted aggregation, copied text, or an ad hoc mark
vocabulary, the architecture has failed early and the slice must be revised.

## Boundaries and current status

storyatlas4s is not a story parser, AMR system, feature estimator, aligner,
scoring engine, graph database, or source of scientific claims. It does not
decide what happened, infer a scene boundary, establish causality, estimate
imageability, induce an autobiographical episode, or decide whether a memory
is true. It renders the typed and receipted results of those processes and
makes their limits inspectable.

The current prototype proves a narrower set of capabilities on the
researcher-reviewed *War of the Ghosts* fixture: deterministic pagination,
exact DOM text, Intaglio overlays, three narrative levels, independently
controlled Hidden, Sentences, and Tokens surface detail, a reader horizon,
shared address focus and selection, textual twins, and receipts. The pinned
storymodel4s revision compiles checked surface marks, and the live and static
StoryAtlas artifacts exercise the configured two-axis zoom states. Continuous
input commits finite states through hysteresis, and stale compilation results
cannot replace a newer intent. Feature values, Chronology Loom, recall mapping,
autobiographical interviews, population views, general model loading, and
novel-scale interaction are not yet implemented. They must remain described as
targets until their contracts and acceptance laws pass.

The governing visualization contract remains storymodel4s ADR 0002. These
statements define the product destination and the work we undertake to reach
it; they do not transfer scientific ownership into the renderer.

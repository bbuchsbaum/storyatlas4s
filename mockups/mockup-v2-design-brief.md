# StoryAtlas mockup v2: professional UI design brief

- **Status:** Independent-review repair candidate; not accepted and not runtime acceptance
- **Audience:** Product, interaction, information-visualization, and UI designer
- **Reference image:** [`mockup1.png`](mockup1.png)
- **Product direction:** [`../vision.md`](../vision.md) and [`../mission.md`](../mission.md)
- **Design target:** A high-fidelity, evidence-faithful research-instrument mockup
- **Review surface:** [`v2/review/index.html`](v2/review/index.html), bound by
  [`v2/review/manifest.json`](v2/review/manifest.json) and scoped by
  [`v2/PROOF-BOUNDARY.md`](v2/PROOF-BOUNDARY.md)

## 1. The assignment

Design the next StoryAtlas mockup: an interface for reading a narrative, inspecting its computational representation, and comparing a recall with that narrative.

StoryAtlas is not a generic graph viewer and not a dashboard of detached metrics. It is a scientific reading instrument. Its central promise is that a researcher can move between exact words, narrative structure, quantitative features, and recall evidence without losing the identity of the thing under inspection.

The interface should feel calm, exact, and unusually legible despite the richness of the material. At every moment, the user should be able to answer:

1. What am I looking at?
2. Where is it in the source or transcript?
3. At what narrative scale am I looking?
4. What is observed, what is computed, and what is inferred?
5. How certain is the interpretation, and what evidence supports it?
6. What changed when I moved to another view?

The current mockup is useful as a compositional sketch. Preserve its strongest idea—a coordinated workspace with shared focus and overview/detail navigation—but do not treat its words, claims, coordinates, confidence values, or visual encodings as real model output.

## 2. What success looks like

The finished concept should make a complex story feel traversable rather than flattened. It should accommodate a short experimental story now and remain conceptually credible for a novel later.

A successful design will:

- keep the exact text prominent and readable;
- show one primary analytical projection at a time;
- coordinate every view through one persistent semantic selection;
- allow meaningful movement among story, episode, scene, event, sentence, and token scales;
- make scene changes, temporal jumps, returns, and time reversals visually intelligible;
- distinguish data, derivation, inference, uncertainty, and missingness;
- make a story–recall alignment inspectable without reducing it to nearest-neighbour links;
- work for an ordinary known-source recall and for an autobiographical interview, where no independently observed source event may exist;
- remain usable by keyboard, at 200% text zoom, without color, and with reduced motion;
- provide a credible path to implementation as DOM text plus SVG-based scientific graphics.

## 3. Primary user and core tasks

The primary user is a narrative-memory researcher or human adjudicator. They are comfortable with scientific concepts but should not need to understand internal Scala types.

Their core tasks are:

- orient within a story and recognize its broad structure;
- read the exact source or transcript without annotation obscuring it;
- select a word, span, proposition, event, scene, episode, relation, or recall unit;
- see all views update to that same selected object;
- inspect the evidence and provenance for a model claim;
- compare discourse order with story-world time;
- examine a word-level or event-level feature over multiple window sizes;
- see how a recall unit distributes over possible source targets;
- identify omissions, blends, distortions, associations, backtracks, and uncertainty;
- export a defensible scientific figure or return later to the same saved state.

## 4. The conceptual model the interface must respect

StoryAtlas coordinates several related representations. They are not interchangeable.

| Representation | What it answers |
|---|---|
| Exact source or transcript | What words were actually present? |
| Surface atlas | Where are the paragraphs, sentences, clauses, tokens, and spans? |
| Narrative graph | What entities, situations, contexts, and typed relations are proposed? |
| Narrative hierarchy | What counts as an event, scene, episode, and story-level unit? |
| Discourse trajectory | How do meaning, cast, place, affect, and other features change as the words unfold? |
| Recall alignment | Which source targets could each recalled proposition refer to, with what posterior mass? |
| Claim ledger | What evidence, method, uncertainty, alternatives, and corrections support each claim? |

The interface must never imply that a visualization is the story itself. Each projection is a declared view of a versioned model, and the exact words remain the observation to which explicit claims must be anchored.

### Three kinds of time

The design must make room for three clocks:

- **Discourse time:** where something appears in the telling or presentation.
- **Story-world time:** when it occurs in the represented world.
- **Recall time:** when it is produced during recall.

A flashback can move forward in discourse time while moving backward in story-world time. A person can then recall it at a third position. Avoid any visualization that labels one undifferentiated horizontal axis as simply “time.”

## 5. Default workspace architecture

Use a restrained, coordinated shell:

1. **A thin global overview** for orientation, focus position, and major structure.
2. **A persistent Codex** occupying roughly half the usable width. It contains the exact text and conservative inline or gutter annotations.
3. **One primary projection** occupying the other half. The user changes the projection without losing semantic focus.
4. **A collapsible inspector** for evidence, alternatives, definitions, and provenance. It is not a permanently equal third panel.
5. **Quiet utilities** for view choice, semantic scale, feature choice, export, and saved state.

Do not default to four or five equally loud panes. The reading surface and the current analytical question should dominate; the rest should support them.

### Required workspace modes

Design at least one key screen for each mode.

| Mode | Persistent observation | Primary projection | Essential distinction |
|---|---|---|---|
| Source exploration | Exact story Codex | Discourse Atlas, chronology, hierarchy, or feature scale-space | Source wording versus model interpretation |
| Known-source recall | Exact story plus exact recall access | Recall–source posterior matrix, with rails or route on demand | Content match versus transition/order properties |
| Autobiographical interview | Two-speaker transcript | Hypothesized episode structure and detail-address matrix | Recalled narrative versus an inferred, not observed, episode |

The modes should feel like one product. Shared interactions, typography, selection, inspector behavior, and evidence language should remain stable.

## 6. The shared focus contract

The most important interaction is not a hover effect; it is a persistent semantic focus.

When a user selects an object, every visible projection should show how that same object appears there. A useful focus breadcrumb is:

> Story › Episode › Scene › Event › Source span

Selection must survive:

- changing projections;
- moving between hierarchy levels;
- expanding the inspector;
- switching between source and recall views;
- paging or moving through a long work;
- saving and reopening a workspace.

When the selected object is not directly drawable in the current projection, show one of three explicit states:

- **On mark:** the selected object has a direct visual mark.
- **Via ancestor:** its containing scene or episode is shown instead.
- **Off projection:** it remains selected but is outside the current projection or filter.

Never silently drop selection.

## 7. Semantic zoom, pagination, and traversal

StoryAtlas needs both ordinary text traversal and model-aware semantic zoom. Do not conflate them.

### Ordinary traversal

The Codex should provide a simple, dependable way to move through words and source units:

- scroll or page through the exact text;
- jump to a sentence, clause, event, scene, episode, or search match;
- move to previous/next unit at the active scale;
- show current location in the whole work;
- preserve text selection and provide stable links to selected spans;
- support keyboard traversal without requiring a pointer.

For a long novel, the interface may virtualize or paginate content, but the experience should still feel like reading a continuous work.

### Semantic zoom

Treat semantic scale as discrete, meaningful states rather than optical enlargement.

Two independent controls are required:

1. **Narrative level:** Story, Episode, Scene, Event.
2. **Surface detail:** Hidden, Sentences, Tokens.

Feature aggregation scale is a third, separate choice. A user may inspect a scene-level narrative unit while looking at an imageability signal aggregated over 25-word windows.

Wheel or pinch gestures may provide a continuous-feeling transition, but they must settle on declared semantic states. The selected object and its semantic address remain stable through the transition.

Use geometric morphing only when correspondence between old and new marks is real and explainable. Otherwise prefer a brief crossfade plus a visible tether to the preserved selection. Provide an immediate reduced-motion alternative.

Page navigation is not semantic zoom. A control that merely shows more text should not be labeled as a change in narrative level.

## 8. Projection briefs

### 8.1 The Codex

The Codex is the stable reading surface and evidential anchor.

Requirements:

- exact text remains selectable, searchable, and copyable;
- annotations use gutters, underlines, brackets, or selective emphasis rather than saturated paragraph fills;
- source text is never rewritten to explain an omission or model state;
- scene and episode boundaries can be revealed without overwhelming ordinary reading;
- sentence and token marks can appear at higher surface-detail settings;
- speaker, interview phase, and probe provenance are clear in transcript modes;
- selecting an annotation opens a field-addressable claim in the inspector;
- dimming unrelated material must not imply that it is unimportant or unsupported.

Default annotation budget:

- one hierarchy or context layer;
- one relation family;
- one quantitative feature;
- optionally one recall-comparison layer.

Additional layers should be discoverable but not simultaneously painted over the prose by default.

### 8.2 Discourse Atlas

This is the default structural overview of how the story is presented.

Its axes and geometry must be declared. A defensible starting point is:

- horizontal position = exact discourse/source position;
- vertical lane = context, speaker, or another explicitly named grouping;
- nested regions = scene and episode membership;
- marks = situations or other admitted model objects;
- lines = one selected relation family, not a universal graph hairball.

Show the current Codex viewport and current selection. Make discontinuous support, retrospective reference, and recurrence possible without implying that distance on an undeclared dimension has scientific meaning.

### 8.3 Chronology Loom

This view compares presentation order with story-world order.

Requirements:

- do not invent precise clock coordinates when the model only contains a partial order;
- show incomparable, overlapping, or uncertain events as such;
- reveal flashbacks, anticipations, temporal jumps, returns, and reversals;
- distinguish an announced future event from its later realization;
- preserve modal or speech contexts rather than promoting embedded propositions to world fact;
- make the relation between a temporal constraint and its textual evidence inspectable.

The designer may explore lanes, ribbons, paired axes, slope graphs, or partial-order layouts, but the meaning of position and line must be written directly into the design specification.

### 8.4 Feature scale-space

StoryAtlas must support signals defined over words, spans, situations, or segments. Imageability over words is the canonical example.

The design should allow a user to:

- choose one feature space, such as imageability, affect, semantic surprise, entity turnover, or sensory modality;
- inspect the exact value and target at the cursor or selection;
- see raw word- or token-level values where available;
- aggregate values over declared rolling windows;
- compare several window sizes or hierarchy-based reducers;
- relate peaks, troughs, and discontinuities to proposed boundaries;
- distinguish a signal used to help infer structure from a signal displayed after structure was inferred.

Every quantitative track must expose, at minimum:

- feature name and definition;
- target type, such as token, sentence, event, or scene;
- units or score range;
- provider/model and version;
- reducer, weighting, and window definition;
- coverage and missingness;
- normalization or calibration;
- build or feature receipt.

Visually distinguish:

- zero;
- missing;
- not requested;
- unavailable;
- low coverage;
- unresolved;
- low magnitude.

These are different scientific states. Do not map them all to an empty or gray mark.

### 8.5 Known-source recall alignment

The primary view should be a recall-unit × source-target posterior matrix. It should support hierarchical and many-to-many alignment.

The matrix should make visible:

- exact recall units in recall order;
- source targets at event, scene, and episode levels;
- posterior mass rather than only a winning match;
- multimodal, broad, and uncertain mappings;
- external and abstention states;
- the selected cell's content and structural compatibility;
- row and column summaries with declared denominators.

Required external or non-source destinations include:

- association;
- commentary or task discourse;
- unsupported intrusion;
- source-consistent inference;
- uninterpretable content;
- unranked or abstained content.

Do not collapse all of these into “external,” “irrelevant,” or “error.”

Keep fidelity facets distinct from faithful source mass. The typed alignment state must preserve
`Source(ref)` separately from `Distorted(ref, facets)`, even when both share one source address. A
recall unit may identify the correct event while reversing actor and patient, changing polarity,
moving it to the wrong location, or asserting reported content as fact.

Misordering is a property of transitions between aligned recall units. It should appear as a path, transition overlay, or derived diagnostic—not as a property of one matrix cell.

Omission is insufficient aggregate source-side mass. It should not be represented by inserting `[skipped]` or any other synthetic words into the recall transcript.

Ribbons or a route through source space can provide an evocative secondary view, especially for jumps and returns. They should not replace the matrix as the primary auditable representation.

### 8.6 Population recall

If a population layer is shown, it must not be decorative density.

Display alongside it:

- sample size;
- estimand;
- denominator;
- weighting policy;
- coverage;
- uncertainty;
- treatment of unranked mass and participants with no content;
- model or build fingerprint.

The interface should make clear whether a mark represents frequency of mention, expected alignment mass, transition flow, memorability, or another quantity.

### 8.7 Autobiographical interview mode

An ordinary autobiographical interview does not have a known source story. The transcript is observed; the target episode is inferred from the participant's narrative and prompts.

The design must therefore:

- present participant and interviewer turns as an exact two-speaker transcript;
- distinguish free recall, general probes, and specific probes;
- treat interviewer turns as protocol context, not memory-detail rows;
- label the episode projection as **hypothesized** or **structurally derived**;
- support alternatives, uncertainty, and a legitimate “no coherent episode model” state;
- use a detail-assessment × memory-address matrix rather than a source-alignment matrix;
- keep target episode, other specific episode, extended episode, repeated/categoric event, personal knowledge, general knowledge, discourse, and unresolved destinations distinct;
- show traditional internal/external scores only as a named, versioned overlay;
- keep phenomenological ratings and source-monitoring statements separate from detail counts;
- distinguish missing subjective ratings from low ratings.

Never draw an inferred autobiographical episode as though it were an independently observed historical record.

## 9. Inspector and evidence loop

The inspector should enable a complete evidence check in one or two actions.

For the selected object, expose fields separately:

- canonical label or proposition;
- semantic address and hierarchy level;
- exact supporting span or spans;
- relation type and endpoints, when applicable;
- context, polarity, modality, and epistemic status;
- raw score;
- calibrated probability, only when one exists;
- calibration model;
- provider and version;
- transformation or reducer;
- alternatives and their status;
- human correction or adjudication history;
- build receipt and stable identifier.

Do not show one “Confidence 0.89” for a bundle of unrelated claims. If a number is an uncalibrated model score, label it exactly that. Do not call it a probability or confidence.

Use clear language for status, for example:

- Source explicit
- Linguistically entailed
- Structurally derived
- World-knowledge inference
- Hypothesized
- Human adjudicated

The distinction between evidence and inference should remain visible even in a compact inspector.

## 10. Content and data policy for the mockup

The mockup itself must model scientific honesty.

Use one of these two content strategies:

1. Exact, admitted source and model fixtures supplied by the engineering team, with real stable identifiers and receipts; or
2. Clearly synthetic content carrying a dominant, persistent label:

   > SYNTHETIC PLACEHOLDER — NOT SOURCE OR MODEL OUTPUT

Do not invent text and present it as *The War of the Ghosts*. Do not invent source offsets, event labels, causal claims, goals, emotions, locations, feature values, recall alignments, or population results without the synthetic label.

Do not use real participant recall data unless it has been explicitly approved and de-identified for design use. Synthetic recall should be labeled and privacy-safe.

The reference image is read-only design research. Do not “clean it up” in place; produce a new v2 artifact.

## 11. Visual language

Aim for the character of a beautifully made scientific edition: quiet, exact, spatially disciplined, and rewarding at close inspection.

### Typography

- Give source prose generous line height and a comfortable measure.
- Keep ordinary reading text at a practical desktop size; do not miniaturize it to make more panels fit.
- Distinguish exact text, model labels, metadata, and numeric readouts typographically.
- Use tabular numerals for aligned values where useful.
- Avoid all-caps micro-labels as the main way to establish hierarchy.

### Color and marks

- Establish whether color semantics are global or projection-local; do not silently change the meaning of a hue.
- Pair color with shape, line style, position, pattern, or label.
- Reserve strong saturation for current focus or a small number of categorical distinctions.
- Use uncertainty encodings that remain legible without opacity alone.
- Keep selection clearly visible in light, dark, grayscale, and color-deficiency simulations.
- Do not let “faded” become an ambiguous synonym for missing, uncertain, external, or unimportant.

### Geometry

- Every axis, distance, lane, region, and line must have a declared meaning.
- Avoid a global force-directed hairball.
- Prefer bounded projections, sparse relations, focus-plus-context, and explicit filtering.
- If two layouts cannot preserve literal geometry during a transition, preserve semantic identity and state that the geometry changed.

## 12. Accessibility requirements

Treat WCAG 2.2 Level AA as the minimum product target, not an afterthought.

- Normal text must meet at least 4.5:1 contrast.
- Interactive targets should be at least 24 × 24 CSS pixels or satisfy the standard's spacing or other exceptions.
- Text must scale to 200% without loss of content or function.
- Text, controls, and the inspector must reflow at the equivalent of a 320 CSS-pixel viewport. A genuinely two-dimensional scientific projection may remain pannable, but it must have an accessible textual or tabular counterpart.
- Every drag interaction must have a single-pointer and keyboard alternative.
- All functions must be available without hover.
- Keyboard focus order must follow the visible task flow, and focus must never be obscured.
- SVG graphics must have a navigable list or table representation, meaningful labels, and a concise announced summary of selection changes.
- Respect reduced-motion preferences. Semantic transitions must still be understandable with animation disabled.
- Do not encode any scientific state by color alone.

Relevant standards:

- [Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/)
- [Resize Text](https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html)
- [Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html)
- [Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum)
- [Animation from Interactions](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions)

## 13. Implementation constraints to design for

The mockup is not an implementation specification, but it should be buildable within these boundaries:

- ordinary UI, exact text, forms, tables, and accessibility structure belong in DOM-based components;
- scientific graphics belong in an SVG-based rendering layer;
- the product should not become one giant canvas;
- exact source and recall text must remain real DOM text;
- every selectable semantic object has a stable semantic identity independent of its current rendered mark;
- the rendering layer receives compiled display objects, not authority to invent source claims or scientific interpretation;
- the same saved semantic state should compile deterministically on JVM and JavaScript targets;
- the design should not assume Canvas, WebGL, or a new general-purpose visualization library;
- exports must distinguish a pure scientific plate, a hybrid page with text and graphics, and a saved interactive workspace state.

The designer does not need to reproduce internal type names in the UI. The important experience-level consequence is that a user selects a semantic object—not a disposable pixel or path—and that selection survives rerendering and projection changes.

## 14. Required states and edge cases

Include these in a component or state board, even if only a subset appears in the hero screens:

- loading and compilation;
- no data;
- not requested;
- unavailable or unauthorized;
- missing value;
- low feature coverage;
- unresolved interpretation;
- stale or incompatible build receipt;
- selected object directly visible, visible via ancestor, and off projection;
- reader-at-current-point versus omniscient model view;
- one clear multimodal alignment;
- one precise distortion, such as role reversal or polarity conflict;
- external association;
- intrusion;
- commentary;
- source-consistent inference;
- uninterpretable recall;
- unranked or abstained recall;
- source omission;
- autobiographical interview with no accepted episode model;
- privacy-blocked transcript content;
- long labels and overlapping annotations;
- very long work with a dense global overview.

## 15. Explicit anti-patterns

Do not:

- present synthetic source or recall material as genuine data;
- turn an inferred hero's journey, causal chain, emotional arc, or scene outline into fact;
- display unlabelled numeric tracks;
- use a generic “external/irrelevant” bin;
- insert `[skipped]` into participant speech;
- call a layout chronological without defining its temporal axis;
- call optical zoom semantic zoom;
- show every available pane and annotation layer at once;
- use tiny type to simulate analytical density;
- rely on color, hover, or dragging alone;
- hide posterior or abstention mass that does not fit the preferred source match;
- represent a natural autobiographical interview as though a source episode had been observed;
- make graph geometry decorative while allowing users to read scientific meaning into it.

## 16. Required design deliverables

Please provide:

1. A short written concept statement explaining the chosen information hierarchy and interaction model.
2. A reusable component and design-token sheet.
3. A high-fidelity 1536 × 1024 source-exploration screen.
4. A high-fidelity known-source recall screen with the posterior matrix as the primary projection.
5. A high-fidelity autobiographical-interview screen.
6. A compact 1280 × 800 variant.
7. A 200%-text-zoom or narrow-reflow treatment.
8. A keyboard focus-order diagram.
9. A reduced-motion transition treatment.
10. A grayscale and color-deficiency check.
11. A state board covering uncertainty, missingness, abstention, errors, and loading.
12. An interactive prototype for selection, projection switching, semantic zoom, inspector disclosure, and basic text traversal.
13. Annotation notes defining every non-obvious axis, mark, color, line, and numerical encoding.
14. Exportable PNG or PDF review plates plus the editable design source.

The prototype does not need to implement scientific computation. It does need to show where real data, receipts, uncertainty, and alternatives would appear.

## 17. Acceptance checklist

The mockup is ready for engineering and scientific review when all of the following are true:

- [ ] Exact text is the dominant evidential surface.
- [ ] One shared selection is legible across all visible views.
- [ ] The default workspace has one primary projection, not a wall of panels.
- [ ] Story, episode, scene, event, sentence, token, and feature-window scales are not conflated.
- [ ] Discourse time, story-world time, and recall time are distinguishable.
- [ ] Scene changes, jumps, returns, and reversals have declared encodings.
- [ ] Every quantitative feature shows its definition, reducer, coverage, missingness, and provenance.
- [ ] Recall alignment preserves posterior mass, hierarchy, external states, and abstention.
- [ ] Misordering is represented as a transition property and omission as source-side missing mass.
- [ ] Autobiographical interview mode does not fabricate a source event.
- [ ] Evidence and inference are visually distinct.
- [ ] No bundled or uncalibrated number is labeled simply “confidence.”
- [ ] Synthetic content is unmistakably labeled.
- [ ] Keyboard, 200% text zoom, reflow, reduced motion, and non-color encodings are demonstrated.
- [ ] Every visualization has a textual or tabular accessible counterpart.
- [ ] The design can be implemented as DOM text and controls plus SVG scientific graphics.

## 18. Independent-review amendments

The v2 review round made the following constraints acceptance-bearing for the
repair candidate.

### Exact observations and scientific state

- Source, recall, and interview are three independent text identities with exact
  bytes, UTF-16 coordinate systems, hashes, unit spans, and storage pointers.
- Passing a fixture hash establishes identity only. All three remain synthetic,
  unadmitted, and not human adjudicated.
- Exact text uses at least 16px type with 24.8px line height and cannot be clamped
  or hidden. If it does not fit, it lives in a declared, executable scroll region.
- Feature receipts name raw and derived target grains, lexical-token universe,
  window, step, typed edge policy, reducer, missing-value policy, observed and missing counts,
  provider, calibration status, and use ledger. One token-ordinal ledger must conserve sentence,
  selected-window, and whole-track counts.
- `Source(ref)` and `Distorted(ref, facets)` are distinct `AlignState` identities.
  The matrix includes a counterexample where 0.30 faithful and 0.41 Attribute-
  distorted mass share `s05`; their 0.71 anchor total must never masquerade as
  source-faithful mass.
- Interview address projections conserve Target episode, Other specific episode,
  Extended episode, Repeated/categoric, Personal knowledge, General knowledge,
  Discourse, and Unresolved mass. The proposed StoryAtlas view state `Unestablished` makes source
  coverage uncomputable rather than zero; it is not presented as a type already supplied by the
  pinned StoryModel revision.
- Interpretive summaries are decomposed into field-addressable claim ids, status,
  evidence or upstream claims, and receipts. In particular, “alone,” the `s02` /
  `s09` relation, and intentional withholding at `s10` stay weaker than the
  source-explicit action fields.

### Frozen output and accessibility

- The review deliverable is a bundle, not one magic file: editable source,
  synthetic fixture registry, frozen HTML/CSS plates, PNG plates, courts, and a
  manifest with reproducible and diagnostic portions.
- Frozen HTML contains no JavaScript or external resource, opens from the
  filesystem, visibly declares that controls are inert, and removes focus and
  button semantics. It is design evidence, not an interactive prototype.
- A live two-dimensional projection uses inert SVG paint plus one visible DOM
  composite twin with `aria-activedescendant`. The matrix twin exposes all 11 × 16
  cells, including zero, off-source, and Unranked states. Runtime accessibility
  remains an open application-level court.
- Printable-character shortcuts require a modifier, are scoped to the focused
  instrument, are remappable and disableable, and are suspended while text entry
  or editing owns the keyboard.
- Every plate declares exactly one fixed or flowing artboard. Courts check exact
  evidence, cumulative visibility through every clipping ancestor, artboard extent, frozen
  semantics, portable replay, feature conservation, claim span/upstream integrity, fixture
  mutations, and manifest mutations. The verifier checks reproducible source/output bytes and
  derives that receipt from the authenticated artifact rather than trusting parallel arrays.

### Graph-over-text spike

The design does not assume that text-lane overlays can carry every nonlocal
relation. The working hypothesis is two synchronized projections with one identity
system: the Codex carries local annotations and typed portal endpoints; the Atlas
or Loom carries cross-page causal and world-time geometry. The O3 spike must draw
at least one cross-page causal relation and one world-time reversal, then compare
comprehension, evidence access, crossings, and keyboard traversal against a
Codex-only lane overlay. Until that spike is evaluated, “text alone is sufficient”
is not an accepted claim.

## 19. Design north star

The ideal StoryAtlas view should let a researcher move from a sentence to an event, from an event to a scene, from a scene to the story's changing trajectory, and from any of those back to the exact words and evidence—without ever confusing a compelling picture with a scientific fact.

The experience should make complex narrative structure feel navigable while keeping uncertainty honest. That combination—orientation without oversimplification—is the standard for this mockup.

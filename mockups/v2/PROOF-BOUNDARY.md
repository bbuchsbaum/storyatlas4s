# Mockup v2 proof boundary

The mockup is a design artifact, not a scientific result and not an executable
acceptance certificate for the StoryAtlas application.

The plates use three separately hashed observations where their mode requires
them: `synthetic/two-boats-source@v1`,
`synthetic/two-boats-recall-p01@v1`, and
`synthetic/two-boats-interview-p07@v1`. They are never collapsed into one text
identity. No passage, address, score, alignment, claim, or receipt shown inside a
screen is admitted source data or model output. The dominant synthetic banner is
part of every affected plate.

Every exported HTML plate is pure HTML/CSS: no JavaScript, no external resource,
and no network dependency. It opens directly from the filesystem. The exporter
also adds a dominant `FROZEN DESIGN SPECIFICATION — CONTROLS ARE INERT` notice and
removes active semantics. Drawn controls specify a live design; they do not act.

## What the frozen artifact proves

The review generator proves only that, for the exact files and environment in
`review/manifest.json`:

- each editable `.dc.html` source resolves without a leftover template token and
  unknown or partial directives fail closed under mutation;
- each frozen HTML plate has no script or network dependency;
- each PNG is captured from its frozen HTML twin at the declared viewport and
  device-pixel ratio;
- the three primary workspaces and the accessibility/specification plates are
  populated and reviewable outside the design editor;
- each plate has exactly one declared visual artboard and no undeclared sibling;
- fixed and flowing artboards obey their separate extent contracts;
- fixture hashes, packet hashes, exact observation bytes, declared text
  typography, feature-ledger conservation, claim/upstream/span integrity, and scroll reachability
  pass executable courts with killing mutations;
- no content-bearing exact-evidence element clips its own exact text, and no such element is
  silently clipped by its scroll viewport or any outer clipping ancestor;
- no rendered descendant crosses the declared artboard bounds in that frozen
  default state;
- the manifest binds every source and output byte with SHA-256, authenticates its own digest
  rules, and verifies that the reproducible receipt is the declared projection of the verified
  artifact rather than an independently trusted array. Machine-dependent timings, browser
  version, platform, and pre-commit generation context are explicitly unsigned diagnostics.

The renderer-gap benchmark is a diagnostic court over one deliberately stated
workload. Its structural counts and bytes are reproducible; timing is tied to
the recorded browser and machine class and is not a portable performance
claim.

For the recorded 2,300-mark workload, ten clipped hatch segments per mark
produce 32,202 SVG elements and 2,497,582 serialized bytes. That misses the
declared gates of 10,000 elements and 2 MiB. The one-shared-pattern control
produces 2,305 elements and 296,925 bytes and passes both gates. The evidence is
`review/renderer-gap-benchmark.json`; its rendered court is
`review/renderer-gap-benchmark.png`. This supports evaluating a generic compact
paint/pattern vocabulary in Intaglio. It does not establish a production
interaction-performance claim or authorize an upstream API change.

## What remains a design specification

These plates specify desired product behaviour but do not prove that the live
Scala/Scala.js application implements it:

| Plate or visible control | What a production acceptance court must still prove |
|---|---|
| Focus order | The plate specifies an inert SVG plus one visible DOM composite twin using `aria-activedescendant`. Keyboard traversal, accessibility-tree shape, matrix-cell announcements, no obscured focus, and truthful on-mark/via-ancestor/off-projection state remain live-application courts. |
| Reflow and Compact | Browser behaviour at 200% text zoom and a 320 CSS-pixel equivalent, with an accessible counterpart for every two-dimensional figure |
| Grayscale | Computed styles and non-colour discrimination for every scientific state, not merely the proposed palette |
| Motion | `prefers-reduced-motion`, last-intent-wins compilation, and preserved semantic selection during real projection transitions |
| Export controls | Actual plate, hybrid-page, and saved-state serialization with checksum/refusal courts |
| Saved-state labels | Reopening against exact model, compiler, renderer, source, and layout identities; mismatches must refuse |
| Hidden-mark descent | Deterministic navigation through provider-compiled `SelectionPlacement`; the UI may not infer a nearest mark |
| Feature and recall receipts | Field-for-field rendering of real provider receipts, coverage, missingness, alternatives, full typed alignment state, external mass, and abstention |
| Interview privacy state | A provider-supplied privacy witness and typed visibility policy before any participant prose reaches the renderer |

No control drawn in a plate is runtime evidence. No static screenshot proves an
interaction, accessibility tree, semantic acceptance, scientific calculation,
performance target, or privacy guarantee. Passing a content hash proves identity,
not truth or admissibility.

## Artifact roles

- `*.dc.html` and `canvas.json`: editable design source.
- `support.js`: local resolver for the deliberately small directive vocabulary
  used by these sources; it has no role in the StoryAtlas product.
- `export.cjs`: maps design-canvas directives to standards-valid HTML
  `template` elements in a generator-owned temporary directory before browser
  parsing. This preserves the editable sources and avoids HTML table foster
  parenting. The temporary directory is always removed when the browser closes.
- `storyatlas-mockup-v2.html`: design-editor package, retained and synchronized
  for editability; it is not the portable review surface.
- `review/*.html`: frozen, script-free review plates.
- `review/*.png`: pixel review plates captured from the frozen HTML.
- `fixtures.json`: exact synthetic observation registry; its hashes establish
  identity only.
- `fixture-court.cjs`: registry, span, reference, and mutation court.
- `review/manifest.json`: bundle receipt with distinct artifact and reproducible
  digests plus explicitly unsigned diagnostics.
- `renderer-gap-benchmark.*`: source and runner for the bounded diagnostic used
  to disposition compact pattern vocabulary.
- `review/renderer-gap-benchmark.*`: measured diagnostic receipt and rendered
  court; neither is a production renderer benchmark.

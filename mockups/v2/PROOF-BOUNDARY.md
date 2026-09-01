# Mockup v2 proof boundary

The mockup is a design artifact, not a scientific result and not an executable
acceptance certificate for the StoryAtlas application.

Every screen uses `synthetic/two-boats@v0`. No passage, address, score,
alignment, receipt, or checksum shown inside a screen is source data or model
output. The dominant banner is part of every affected plate.

## What the frozen artifact proves

The review generator proves only that, for the exact files and environment in
`review/manifest.json`:

- each editable `.dc.html` source resolves without a leftover template token;
- each frozen HTML plate has no script or network dependency;
- each PNG is captured from its frozen HTML twin at the declared viewport and
  device-pixel ratio;
- the three primary workspaces and the accessibility/specification plates are
  populated and reviewable outside the design editor;
- no rendered descendant crosses the declared artboard bounds in that frozen
  default state;
- the manifest binds every source and output byte with SHA-256 and binds the
  fixture policy, typography, renderer, browser, platform, and viewports.

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
| Focus order | Keyboard traversal through the compiled live application, including no obscured focus and truthful direct/proxy/off-projection state |
| Reflow and Compact | Browser behaviour at 200% text zoom and a 320 CSS-pixel equivalent, with an accessible counterpart for every two-dimensional figure |
| Grayscale | Computed styles and non-colour discrimination for every scientific state, not merely the proposed palette |
| Motion | `prefers-reduced-motion`, last-intent-wins compilation, and preserved semantic selection during real projection transitions |
| Export controls | Actual plate, hybrid-page, and saved-state serialization with checksum/refusal courts |
| Saved-state labels | Reopening against exact model, compiler, renderer, source, and layout identities; mismatches must refuse |
| Hidden-mark descent | Deterministic navigation through provider-compiled `SelectionPlacement`; the UI may not infer a nearest mark |
| Feature and recall receipts | Field-for-field rendering of real provider receipts, coverage, missingness, alternatives, external mass, and abstention |
| Interview privacy state | A provider-supplied privacy witness and typed visibility policy before any participant prose reaches the renderer |

No control drawn in a plate is runtime evidence. No static screenshot proves an
interaction, accessibility tree, scientific calculation, performance target,
or privacy guarantee.

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
- `review/manifest.json`: actual build/view/artifact receipt.
- `renderer-gap-benchmark.*`: source and runner for the bounded diagnostic used
  to disposition compact pattern vocabulary.
- `review/renderer-gap-benchmark.*`: measured diagnostic receipt and rendered
  court; neither is a production renderer benchmark.

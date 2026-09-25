# Voyage decision and posterior inspector — 2026-09-24

Mote: `bd-01M35M218HT43D95GX4PN962HB`.

The drawn choice now has its own card, with its origin, supplied mass and annotation
span. A zero-mass fill stays outside the ranked posterior. Positive candidates,
including a supported drawn choice, appear once in descending supplied mass order;
ties use the source key lexicographically. The full list remains keyboard-accessible.

The mass bar uses the compiler's source and external totals on a fixed 0–1 scale.
It neither rescales candidates nor estimates a tail. Group strips state their
annotation-clock bounds and support grain. An ungrouped anchor retains its own
span. Untimed and unanchored units explicitly identify the missing posterior
payload; an unanchored unit still reports its supplied external mass.

The standalone page and controlled workspace share scoped inspector styles.
The StoryModel compiler, mapping values, lowerer and static publication are unchanged.

## Evidence

Exact dependency pins, source-file hashes, document hashes and check counts are in
[qualification.json](qualification.json). The parent is `39b7f27`; the file hashes
identify the tested changes independently of the eventual commit ID.

| Check | Result |
| --- | --- |
| `compileAll testAll scalafmtCheckAll app/fastLinkJS` | 414 tests passed |
| Final annotation-span addition | App tests, formatting and linking passed again |
| Inspector browser court | 171 checks passed |
| Joined workspace court | 98 checks passed |
| Existing Voyage display / viewport / mass courts | 62 / 61 / 69 checks passed |
| Original source-shell browser smoke | Passed |
| Sherlock static SVG and textual twins, both arms | Byte-identical to prior qualified edition |
| Prior inspector regression | Fails: posterior starts with drawn zero-mass choice |

The final inspector and workspace courts ran after the annotation-span addition.
Earlier broad courts cover unchanged neighboring behavior. Browser checks used
Playwright 1.55.1 and Chromium headless shell 1193; no external page requests or
browser errors occurred. The browser ownership audit found no remaining automated
browser processes. Desktop and 390px inspector captures were inspected; the
[committed screenshot](synthetic-workspace-inspector.png) contains only the public
synthetic Bell fixture.

Raw logs and their command/exit-status sidecars are under `logs/`. The inspector
court reads expected ranks and totals independently from the canonical document.
Its checked synthetic cases cover ties, coarse support, external-only fills,
unanchored units, missing timing and ungrouped annotation spans. The previous
inspector fails the same ordering assertion before any new markup is required.

## Reproduction and limits

Use the exact pins from the qualification record in isolated checkouts. The gate
log sidecars contain the executed sbt arguments. Generate both frozen NN03 Voyage
editions with `cli/run voyage`, place the linked `app.js` beside each, then run:

```sh
node app/smoke/voyage-inspector.cjs <directory-containing-nn03-full-and-nn03-content>
```

The browser installer stalled during archive extraction on this machine. The
same pinned headless-shell archive was extracted using macOS `ditto` into a
session-owned temporary browser directory. No system browser or user profile was used.

This is a consumer display qualification over frozen artifacts, not an inference
rerun, calibrated confidence claim, native media qualification or completion of
the broader workspace roadmap. No source-video or evidence-binding capability
was added. Local generated previews remain under `target/inspector-20260924/`;
participant quotations and real Sherlock screenshots are not in this evidence package.

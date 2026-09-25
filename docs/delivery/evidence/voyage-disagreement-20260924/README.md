# Voyage group disagreement strip — 2026-09-24

Mote: `bd-01M35M21SFKS0ZJFSECJFCQBE4`.

The browser's **Under-plot track → Group disagreements** control replaces the
auxiliary mass tracks with a comparison of the supplied drawn and posterior-argmax
groups. A shaped endpoint identifies the drawn group; a cap identifies the argmax
group. Group positions follow the supplied ordinal inventory, with equal spacing;
neither separation nor endpoint size represents a measured quantity.

Same-group records remain counted but are omitted from the strip. Missing groups
remain unknown, with timed unknowns on a separate rail. Untimed units remain in
canonical navigation and in the complete accounting, without invented coordinates.
Clicking or keyboard-activating a comparison selects its exact recall unit through
the existing navigation. The inspector reports both groups and comparison status.

The mass tracks remain the browser default. The historical static group staircase
remains the publication default. The main scientific marks, group spans, supplied
values, origin shapes, external-dominant hollows, coarse support and independent
coding layer are preserved. Coding is not an input to this comparison.

## Evidence

Exact revisions, artifact hashes, tested source-file hashes and check counts are in
[qualification.json](qualification.json). The parent is `94ba5c3`; source hashes
identify the tested implementation independently of its eventual commit ID.

| Check | Result |
| --- | --- |
| Required compile, full test, format, app link and source edition gates | Passed; 420 tests |
| Final lowering suite, including two additional edge cases | 35 tests on JVM and 35 on JS; 424 distinct tests covered overall |
| Final app copy clarification | App compile, 4 tests, formatting and linking passed |
| New disagreement browser court | 82 checks |
| Shared workspace browser court | 100 checks |
| Existing display / viewport / mass / inspector courts | 62 / 61 / 69 / 171 checks |
| Original source-shell smoke | Passed |
| Both Sherlock static SVGs and scientific textual twins | Byte-identical to the prior qualified edition |

The browser court computes expected group pairs independently from the admitted
documents. Both real NN03 arms account for all 173 units: full has 108 disagreements
and 65 agreements; content has 112 and 61. Both have zero unknowns in this legacy
projection. These are comparisons within each supplied document, not the proposed
cross-arm decision-diff feature.

Synthetic witnesses exercise agreement, disagreement, coding, untimed and
unanchored payloads, an existing anchor whose argmax has no group, and noncontiguous
group ordinals. The shared workspace witness has no groups: its four unknowns are
explicit rather than being misreported as agreement. Its selection and complete
workspace state survive track switching.

Desktop and 390px captures were inspected with a 4:00–10:00 detail window. The
[committed screenshot](synthetic-workspace.png) contains only the public synthetic
Bell fixture. Real Sherlock screenshots and participant text stay in the local
generated preview directory, `target/disagreement-20260924/`.

Raw logs and command/exit-status sidecars are in `logs/`. No test failures, browser
errors or external page requests occurred. CLI generation emits the existing Java
25/Scala runtime `sun.misc.Unsafe` deprecation warning. The final browser ownership
audit found no remaining automated browser processes. Testing used Playwright
1.55.1 and its exact Chromium headless shell 1193 in a task-owned temporary directory.

## Reproduce and roll back

Use isolated dependency checkouts at the recorded pins. The log sidecars contain
the sbt arguments. Generate the two frozen NN03 editions, place the linked `app.js`
beside each, and run:

```sh
node app/smoke/voyage-review.cjs <edition-directory>
node app/smoke/voyage-disagreement.cjs <edition-directory>
```

The first script creates the checked synthetic missingness/coding witness consumed
by the second. The workspace court separately proves controlled navigation.

This is a consumer display qualification, not an inference rerun, scientific
validation, native-media qualification or completion of the workspace roadmap.
The track selector is a local display preference, like the existing ghost controls;
it does not create a scientific policy or add a saved-workspace schema field.
Rollback is to withhold the optional control and keep the existing mass/default
publication paths. Original artifacts are never rewritten.

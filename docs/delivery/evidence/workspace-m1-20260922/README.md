# M1 acceptance: one source-plus-recall investigation

The local M1 product court passes for producer-generated Bell and War of the
Ghosts packets through the same compiled application. The workspace opens checked
artifacts, preserves qualified selection across source, matrix and Voyage, exposes
all supplied destinations and exact permitted evidence, and saves, reopens and
exports the investigation. This receipt closes the bounded M1 journey; the public
preparation, mapping, exchange and release work for 1.0 remains separate.

## Exact qualification

| Input | Qualified revision |
|---|---|
| StoryAtlas consumer and browser witnesses | `f74573eb7606ef0243b0bb75b93b4c54b54c995a` |
| StoryModel producer package | `7b2f076a57bde1ec540090c43debfec51c8d44d4` |
| StoryModel executable source | `f4af92e8fccf805f34a23d48ca1e9c08d084df96` |
| Intaglio | `4eb566d9208f474d64d61e778e084dee2ddbaa76` |
| grakern | `0329c43c88a0b71e9aa4456723bb16bac2fa3841` |

[qualification.json](qualification.json) binds the clean trees, actual local
overrides, declared pins, generated fixture hashes and compiled application hash.
The evidence-only commit containing this directory descends from the consumer
revision above. Producer qualification is retained in StoryModel at
`docs/refactor/evidence/workspace-m1-packet-20260922/`: 8,230 tests, 8,225 passed,
five optional live-inference skips, and 514 independent-reader checks. That producer
court was not rerun during consumer acceptance.

## Executed courts

| Court | Result | Receipt |
|---|---|---|
| Compile, JVM/JS tests, formatting, app link and edition bundle | 376 tests; 45 fresh reports; no failures, errors or skips | [gate](logs/consumer-restored-final.log), [command/exit](logs/consumer-restored-final.log.meta.json), [totals](test-totals.json) |
| Joined workspace | 83 checks passed; no browser errors, console errors or network requests | [report](browser/browser.json), [command/exit](logs/workspace-browser-final.log.meta.json) |
| Standalone Voyage public file-opening route | 19 checks passed, including untimed first/last units in both traversal directions | [report](legacy-browser.json), [command/exit](logs/legacy-browser-final.log.meta.json) |
| Source application preservation | 260 checks passed | [log](logs/source-browser-final.log) |
| Static edition preservation | 255 checks passed | [log](logs/static-browser-final.log) |
| Feature edition preservation | Two pages, 567 outcomes, no failures | [log](logs/feature-browser-final.log) |
| Independent export reader | Bell and WOG accepted; ten resigned corruptions refused | [log](logs/independent-reader-final.log) |
| Source mutations | Five compiled mutants killed; restored full gate and browser controls passed | [first three](source-mutants.json), [last two](final-source-mutants.json) |

The source/static/feature preservation courts used runtime revision `4707968e`.
The complete diff from that revision to `f74573e` changes only
`app/smoke/legacy-workspace.cjs` and `app/smoke/workspace.cjs`; their executable
inputs are identical. The final 376-test gate and joined/legacy browser courts
were rerun at `f74573e` after every mutation was restored.

The joined court covers arbitrary file names, draft authority, every recall
outcome and candidate, typed missingness, independent clocks and horizons,
precomputed policy selection, inverse references, exact discontiguous evidence,
source attachment with foreign-edition refusal, stale/cancelled/failed opening,
denied-content absence, nested feature sidecars, save/replay, and exact producer
subset export. Back/Return checks assert actual source and matrix scroll positions.
Keyboard/focus, non-color semantics, 390-pixel layout, 200-percent zoom and DPR 2
are included. [mobile-inspection.png](mobile-inspection.png) shows the inspector
after scrolling its pane into view; a separate viewport probe confirms its exact
quote is rendered.

The source mutants restore horizon-leaking quotations, timed-only joined traversal,
first-policy export, timed-only legacy traversal, and foreign-model attachment.
Each fails its relevant control. The independent Python reader imports no Atlas
code. Its resigned corruptions change policy, selected text, CSV rows, SVG numeric
values and values swapped between cells; both fixtures refuse all five.

## Preservation inventory and review

Baseline `abfb6dd4` retained the source Codex/Atlas routes, source pagination,
feature pages and standalone Voyage. The peer viewport implementation was imported
unchanged at `99f4a53` before the controlled adapter was added.

| Existing capability | Disposition and evidence |
|---|---|
| Codex/Atlas source rendering and navigation | Retained; source 260 and static 255 checks plus layout tests |
| Source pagination and exact text | Retained; layout tests and static/source browser courts |
| Static feature pages and sidecars | Retained; two-page/567-outcome court and joined directory-sidecar checks |
| Standalone Voyage and peer viewport | Retained; real public file-open route with 19 checks. Timed-only traversal deliberately corrected and falsified |
| Voyage inside the joined investigation | Controlled by the shared semantic state; all-outcome picker, independent clocks, horizon-safe text and off-projection placement in the 83-check court |
| Permissions, joins and metadata | Checked before activation/export; distinct denied WOG content tested against an admitted Bell workspace |

Separate cold review passes found and led to repairs for sidecar paths, missing
support metadata, focus restoration, uncaptured viewport anchors, dropped source
addresses on Voyage alternative clicks, horizon-leaking titles, hidden-view width,
and DOM scroll restoration. Review did not substitute for the final gates above.
No private Sherlock corpus or scientific efficacy experiment was rerun here.

## Reproduction and limitations

The `*.meta.json` files record exact argv, working directory, exit status and elapsed
time for each attempt. Failed development attempts are preserved beside the final
controls. [probes/m1-atlas-gate.py](probes/m1-atlas-gate.py) records the actual pinned
checkout overrides and command wrapper; substitute equivalent checkout paths when
reproducing. Browser commands and their fixture paths are recorded in the matching
command receipts. Use the project's Playwright 1.55.1 and owned Chromium build 1193.
[browser-audit-final.txt](browser-audit-final.txt) confirms no automated top-level
browser remained after the session's contexts were closed.

Run the independent reader from the qualified Atlas checkout:

```sh
python3 app/smoke/workspace-export-reader.py \
  --fixtures /path/to/storymodel4s/docs/refactor/evidence/workspace-m1-packet-20260922/fixtures \
  --evidence docs/delivery/evidence/workspace-m1-20260922/browser --self-test
```

`artifact-index.json` binds every file in this directory except itself by byte
length and SHA-256. Its entries include the original JUnit reports, which may be
ignored by ordinary source-file listing tools.

There are no Scala compiler warnings in the restored consumer gate. Java 25 emits
a Scala LazyVals `sun.misc.Unsafe` deprecation warning from the forked CLI; the
producer retains its recorded Clang advisory and five optional skips. These are
preserved in the logs. Qualification is local: no hosted CI, push, deployment,
1.0 release certification, calibration or inference-superiority claim follows.
Capability checks bind the supplied artifacts, not a live grant-revocation
service. Replay restores semantic state; responsive pixels may differ.

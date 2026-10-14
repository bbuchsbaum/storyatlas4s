# Temporal workspace adoption

The candidate consumes StoryModel `a2e023f1e2f8333cc7be8209838d02129522c88d` through the
immutable default pin. Its imported temporal packet preserves source, policy, recall inventory
and optional original word timing. Source ticks and recall seconds remain independent clocks;
no display action estimates synchronization, word duration or a new mapping.

The viewer supports source part/occurrence selection, explicit allocation assumptions,
source bins, a disjoint region, top-k evidence with omitted quantity, and an independent recall
window. Failed/excluded rows and unavailable allocation stay visible. CSV exports retain raw
Double round trips, allocation status and the original ledger; the displayed figure carries
its replay descriptor. Custom and per-target allocations retain their supplied labels until
the user explicitly changes them. The conditional precision is the producer's shortest
interval containing 50% of allocated source quantity, with its target stated in the UI.

Semantic selections and saved views bind the exact admitted session, including timing bytes.
Both the combined saved view and standalone temporal receipt reopen against that session.
A foreign clock or source refuses without replacing the current workspace. Denied packets
cannot save or export. Interactive requests admit at most 128 bins and 128 region pieces
before traversal; the screen renders at most 24 recalls by 32 bins at a time. This limit
refuses excess inputs and never truncates or renormalizes an admitted quantity.

## Reproduction and qualification

The synthetic Anna/Bob controls are copied from admitted producer fixtures, with their
provenance stated in `TemporalArtifactSuite`. Generate their browser inputs from the exact
consumer checkout after running its tests:

```sh
sbt 'shellJVM/Test/runMain storyatlas4s.shell.TemporalBrowserFixtures' > target/temporal-fixtures.log
sbt app/editionBundle
npm ci --ignore-scripts --prefix e2e/static
node tools/temporal-browser-smoke.mjs target/edition/index.html target/temporal-fixtures.log target/temporal-browser
```

Run the session browser-policy audit before and after the browser check. The runner owns and
closes its project-pinned Playwright Chromium. It checks both receipt forms, original numeric
values, missing/partial allocation, custom assumptions, failed rows, independent clocks,
permission refusals, mobile overflow and offline operation. Screenshot review and the exact
producer/consumer full gate remain required before acceptance.

At initial checkpoint `76046a24ce1f5cea7b79e48ba0c055d7ac7618e0`, independent review found
four BLOCKs: standalone receipt replay lost its clock/selection, CSV reported unavailable
allocation as zero, custom mixtures were mislabeled, and region admission lacked an
unconditional resource limit. The repairs have 11 focused passing checks. Four compiled
mutants each fail exactly its named regression (10 pass, one fail). Evidence is in
[evidence/temporal-adoption-20261014](evidence/temporal-adoption-20261014/).
The repaired exact-commit review, full consumer gate and rendered browser results are pending.
No consumer main update or publication has occurred.

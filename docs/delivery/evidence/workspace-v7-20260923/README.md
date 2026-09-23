# Workspace presentation and Voyage mass tracks: qualification

This is the combined delivery from Claude (matrix styling and browser court),
Codex Design (workspace markup and inspector), and Atlas Voyage (mass tracks,
independent review and combined qualification). The implementation extends the
existing application and controller.

The exact producer, renderer and input hashes are in [qualification.json](qualification.json).
The runtime candidate is `40b06ff`; later commits in this slice contain documentation
and evidence only. Codex Design owns the final local landing.

- Full required Scala gate: **387 passed**, including compilation, formatting and JS linking.
- After the visual spacing repair: **30 JVM + 30 JS + 3 app tests passed**, with formatting and linking.
- Browser: **83 workspace, 19 legacy, 58 Sherlock display, 61 viewport, 69 mass-track and 8 style checks passed**, plus the original shell smoke.
- Six independent comparisons preserve both static SVGs and textual twins byte for byte; scientific receipt fields agree after checking the exact expected producer revision change.
- The final browser audit found no automated top-level browser processes.

The new mass tracks read supplied anchor and external quantities independently on
fixed 0–1 scales. Zero, unavailable and untimed remain distinct. Selection and
zoom do not renormalize values. Static publication retains its existing group track.

The desktop and compact screenshots here use the public synthetic Bell fixture.
Sherlock screenshots remain local in `target/voyage-mass`; they are not included
in this evidence archive. Real media binding, generic artifact preparation and
the full design roadmap are not claimed complete.

## Reproduce

Use the exact dependency revisions and input hashes in the receipt, and the
repository build overrides. Run the required Scala commands, generate the default
edition and the two Sherlock Voyage editions, then run the checked-in scripts:
`app/smoke/smoke.cjs`, `workspace.cjs`, `legacy-workspace.cjs`,
`voyage-review.cjs`, `voyage-viewport.cjs`, and `voyage-mass.cjs`.
The latter two consume the synthetic witness written by `voyage-review.cjs`.
Use project Playwright **1.55.1** and its Chromium **1193**, never a substituted browser.

The additional style probe is parameterized:

```sh
node probes/workspace-style.cjs <atlas-checkout> <producer-fixtures-directory> <output-directory>
```

Raw logs retain the initial failed metadata assertion, the explicitly explained
baseline revision difference and the browser witness capitalization correction.
They are resolved attempts, not omitted failures. The final reports and numeric
style samples are adjacent JSON files. `SHA256SUMS` covers this archive.

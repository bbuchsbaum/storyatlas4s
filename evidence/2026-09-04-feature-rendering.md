# Measured feature rendering — 2026-09-04

This continuation consumes the value-bearing feature primitive in storymodel4s
ADR 0002 §16. The scientific values come from the bound and block-verified
feature record. The viewer supplies layout and rendering only.

The edition has one reading page, Atlas SVG and textual twin for each supplied
Token, Sentence and Situation track. Exact support, missingness, coverage,
recipe, basis, sidecar checksum and aggregate circularity survive to the
inspector. Every measure and grain has its own display range. A constant range
is centered as a display convention. No feature-use ledger arrives with the
record, so aggregate circularity remains unassessed. Whole-story measurements
require an omniscient horizon.

The replay also exposed a distinction at the input boundary. Its original
compiler reported three required-derivation failures, while a fresh structural
validator accepted the remaining graph. The viewer now retains its draft route
when the supplied record has gaps or abstentions. It does not invent the
original compiler policy or its violations. CLI output and the receipt identify
local structural validation separately.

## Executable evidence

- Model `tools/feature-mutation-check.py`: six compiling mutations caught by
  the intended assertions: wrong basis resolution; dropped missing outcomes;
  dropped coverage; aggregate independence incorrectly asserted; foreign source
  accepted; foreign support accepted. Mutation receipts bind to
  `12f48a1efb79ab5c640586f0ed6f8b513d58e775`; the production files are unchanged
  on the final model pin. The later test correction parses published decimal
  zero rather than requiring JVM's `0.0` spelling on Scala.js.
- Viewer `e2e/static/features.cjs`: compares the generated edition with the
  actual bundle. It checks exact source recovery, all observation addresses,
  reading support pieces, SVG support geometry, missingness crosses, exclusion
  outlines, coverage bars, receipt hashes and browser overflow.
- Viewer `e2e/static/feature-mutations.py`: runs an unmutated Scala and browser
  baseline first, then checks named assertion failures for omitted feature rail,
  omitted derivation gaps and omitted abstentions; separate browser failures
  detect support hulling and removed missingness crosses. Source bytes are
  restored in `finally`. Compile errors are not accepted as mutation kills.

## Replay specimen

The captured fifty-sentence War of the Ghosts parser recordings were replayed
with zero live provider calls. The canonical source checksum is
`36c52efe94a95da4b1b6ba1df479f53bf51a74b751acfa75d2d84dea5924d93e`.
Token length, within-text type frequency, and a deliberately partial synthetic
lexicon produced nine tracks: 517 Token, 50 Sentence and 65 Situation outcomes
per measure; 1,896 total. All 65 Situation supports are discontinuous, giving
195 such observations across the three measures. The derivation record contains
84 gaps and one provider abstention. The final viewer retains that partiality.

The synthetic lexicon is a test instrument, not scientific norms: `people=0`,
`war=2`, `ghosts=4`, `river=1`. Its missing entries exercise missingness and zero
coverage. The source comes from storymodel4s's own fixture, not a copied story
embedded in the viewer.

Generate the specimen with storymodel4s's `pipeline/runMain
storymodel4s.pipeline.storyBuild replay` and its
`pipeline/src/test/resources/recordings/wog-captured` directory, requesting
`--feature token-length --feature type-frequency --feature
lexicon=feature-court@/path/to/test-lexicon.tsv`. Then run this repository's
`cli/run edition --model /path/to/bundle/storymodel.json --out /path/to/edition`
and `node e2e/static/features.cjs /path/to/bundle /path/to/edition`.

## Gate receipts

Model revision `3a6d73d82a52d8e297a827750af7f4502036e01e` passed `sbt
checkAll`: 52 module runs, 5,791 tests, zero failures or errors, captured exit0.
The gate ran on a clean standalone clone using grakern
`d736dc565d97f617726bad0a9d1ba2fdeae58dd2`. The command, HEAD and aggregate
TEST TOTALS are bound in `feature-values-model-checkAll-final.log`. Local HEAD,
tracking main and live GitHub main were equal after the non-force push.

The viewer pins that exact model revision and intaglio
`4eb566d9208f474d64d61e778e084dee2ddbaa76`. Viewer gate receipts follow after
execution.

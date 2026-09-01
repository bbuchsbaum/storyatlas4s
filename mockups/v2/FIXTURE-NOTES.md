# Synthetic fixtures used in mockup v2

The design uses three distinct observations. None is *The War of the Ghosts*, an
admitted StoryModel fixture, model output, or human adjudication. The registry at
[`fixtures.json`](fixtures.json) is authoritative for exact text and coordinates;
this file explains the deliberately synthetic scientific probes built over it.

## Observation registry

| Observation | Identity | Exact-text SHA-256 | Length and storage |
|---|---|---|---|
| Source | `synthetic/two-boats-source@v1` | `40c889d9eab7b6b42189602730ef7bc402afac1007709230dcfff8924c2a43cc` | 584 UTF-8 bytes / 584 UTF-16 code units; `/fixtures/0/canonicalText` |
| Recall | `synthetic/two-boats-recall-p01@v1` | `9612ddc1d09738008851ef6dfde8691f49c68878e4960cd4417c6a01e352052b` | 455 UTF-8 bytes / 451 UTF-16 code units; `/fixtures/1/canonicalText` |
| Interview | `synthetic/two-boats-interview-p07@v1` | `c04885273ab43eb5978ba5b14d468319186576d4dc12f17ca208b9eeb79b3c86` | 702 UTF-8 bytes / 696 UTF-16 code units; `/fixtures/2/canonicalText` |

Source and recall are linked by the recall record's exact `sourceRef`. The
interview instead has `sourceState = Unestablished`: the transcript exists, but
there is no observed source episode and therefore no source-coverage denominator. This is a
proposed StoryAtlas view/compiler state (`proposed-storyatlas-view-state@v1`), not a type claimed
to exist at the pinned StoryModel provider revision.
The fixture court verifies text hashes, packet hashes, half-open UTF-16 spans,
reference integrity, and a byte-changing mutation.

## Source probe

The source has ten exact sentence units (`s01`–`s10`). In particular, `s04` is:

> The older of the two boats belonged to his uncle, who had not yet come to the water.

The mockup does not call the fixture researcher reviewed. Its situations and
claims are synthetic design inputs. Three deliberately difficult claim probes are:

- `s05`: the push action is source explicit; “alone” retains unresolved
  `unaccompanied` and `unaided` alternatives.
- `s09`: an opposite-polarity recurrence to the reported content at `s02` is
  structurally derived. Contradiction requires identity and persistence premises
  that this fixture does not establish.
- `s10`: turning and going back, and not saying what was seen, are explicit. Completion of a
  return and intentional withholding are separate hypothesised interpretations.

The inspector renders claim id, field value, status, evidence or upstream claims,
and receipt separately. A bundled “confidence” is intentionally absent.

## Feature probe

The source contains 117 lexical tokens under the fixture tokenizer. The authoritative
`featureProbe` ledger in `fixtures.json` marks 25 exact lexical ordinals missing; the fixture
court derives every sentence, selected-window, and whole-track count from that ledger. The
synthetic imageability track declares:

- raw target: `Token`; derived target: `Window(TokenRange)`;
- window: 25 lexical tokens, step 1, centred;
- edge policy: `EdgePolicy.KeepPartial`;
- reducer: `ScalarReducer.Mean` with `IgnoreMissing`; no separate edge-policy value is invented
  for the mean's observed-sample denominator;
- track coverage: 92/117 = 0.7863; 25 missing tokens;
- selected window coverage: 21/25 = 0.840;
- aggregate missing reason at `s07`: `MissingReason.AllMissing`; the underlying provider receipt
  records `MissingReason.ProviderAbstained` for its thirteen requested token targets;
- provider: `syn-imag@v3`; no calibration model;
- use ledger: display only, not used to infer the shown boundaries.

These scores are invented and uncalibrated. They are not probabilities.

## Known-source recall probe

The recall has eleven exact units (`r01`–`r11`). Its matrix ranges over the full
typed alignment state, not merely source addresses. The discriminating row is:

```text
r02 "the guy took the bigger one"
  Source(s05)                         0.30
  Distorted(s05, {Object})            0.41
  Source(s04)                         0.19
  Unranked                            0.10
                                      ----
                                      1.00
```

The two `s05` states share an anchor but must remain distinct. Aggregating them to
one 0.71 “source” mark would erase the fidelity finding. Other rows exercise
multimodality, Association, Commentary, Intrusion, `SourceConsistentInference`,
Uninterpretable, and Unranked states.

Misordering is a transition property: `r02 -> r03` moves from `s05` back to `s02`.
Source omission is a column property: `s03` and `s09` receive zero aggregate mass.
Neither inserts synthetic words into the recall transcript.

## Interview probe

The interview contains ten exact turns (`t01`–`t10`), including both later probes:

- `t07`: “Was anyone with you?”
- `t09`: “Roughly how long were you looking for the way out?”

`Okay. Um.` remains exact transcript and discourse evidence. It is outside the
`DetailAssessment` grain rather than being silently deleted or forced into a
memory-address row.

The twelve detail assessments preserve the complete `MemoryAddress` distribution:
Target episode, Other specific episode, Extended episode, Repeated/categoric,
Personal knowledge, General knowledge, Discourse, and Unresolved. The named
`syn-address-scope@v1` overlay is a view-only partition that conserves all mass;
it is not an intrinsic detail label or a traditional score silently redefined.

The default `d09` detail has `Other specific episode = 0.74`, so its target-episode
placement is `off-projection`, not `via-ancestor`. Since the source universe is
the proposed `Unestablished` state, its declared compiler rule forbids computing a source coverage
rate. The mockup does not claim that the pinned provider already enforces that rule.

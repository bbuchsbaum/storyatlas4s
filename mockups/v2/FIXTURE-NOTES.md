# Synthetic fixture used in mockup v2

Content strategy 2 from the brief (§10): clearly synthetic content under a dominant,
persistent label. Nothing here is *The War of the Ghosts*, admitted source text, or
model output. Offsets, addresses, scores and receipts are internally consistent
inventions that exist so the encodings have something to encode.

Fixture id: `synthetic/two-boats@v0`  (deliberately NOT a WOG identifier)

## Source text (10 sentences, synthetic)

s01 At first light two boats were drawn up on the shingle below the village.
s02 Erran had been told, the winter before, that the far shore was empty.
s03 He did not believe it.
s04 The older of the two boats belonged to his uncle, who had not come down to the water.
s05 Erran pushed the smaller boat out alone.
s06 Fog stood on the channel and the far shore was not visible.
s07 Someone on the shingle called after him, but the words did not carry.
s08 By the time the fog lifted he was past the middle of the channel.
s09 The far shore was not empty.
s10 He turned the boat and went back, and did not say what he had seen.

Why these ten: they carry, by construction, every phenomenon the brief requires a
declared encoding for.

- s02 is a flashback: later in discourse time than s01, earlier in story-world time.
  It is also reported speech ("had been told") - a modal/speech context whose embedded
  proposition must not be promoted to world fact.
- s02 announces a future-resolvable claim; s09 realises it, with opposite polarity.
- s05 opens a new scene (village shingle -> channel).
- s07 is unresolved: an utterance occurs, its content is not recoverable from the source.
- s10 is an ellipsis: the source withholds content it marks as existing.
- s03/s09 give a polarity pair for the distortion facets.

## Synthetic recall units (participant recall of the above)

r01 "there were two boats on the beach"                      -> s01, concentrated mass
r02 "the guy took the bigger one"                            -> s05, ATTRIBUTE DISTORTION (smaller->bigger)
r03 "his uncle told him the other side was empty"            -> s02, SOURCE-CONSISTENT INFERENCE (source does not name the teller)
r04 "there was a lot of fog, you couldn't see anything"      -> s06/s08, MULTIMODAL (mass split across two targets)
r05 "somebody was shouting at him from the shore"            -> s07, intensity distortion
r06 "this is like that story you did last week"              -> COMMENTARY / TASK DISCOURSE
r07 "it reminded me of a ferry I took once"                  -> ASSOCIATION
r08 "and then, I don't know, something about a bird?"        -> UNSUPPORTED INTRUSION
r09 "he went back but he never said what happened"           -> s10, concentrated mass
r10 "there was something else at the end but I can't get it" -> UNRANKED / ABSTAINED
r11 "the— with the— you know"                                -> UNINTERPRETABLE

Transition property (NOT a cell property): r02 -> r03 inverts source order
(s05 then s02). Drawn as a transition overlay, never as a cell class.

Source-side omission (NOT a fabricated recall row): s09 receives near-zero aggregate
mass. It is shown as a column-side missing-mass gauge. There is no [skipped] token
anywhere in the recall rail.

## Synthetic interview transcript (autobiographical mode, separate packet)

Cue: "a time you got lost".  No independently observed source episode exists.

I  (free recall)    Tell me about a time you got lost. Take as long as you need.
P                   Okay. Um. There was a market - this was maybe two years ago - and
                    I went in through one entrance and I could not find the way back out.
I  (general probe)  Can you tell me anything more about that?
P                   It was hot. There were stalls with fabric hanging down so you
                    couldn't see over them.
I  (specific probe) Do you remember what time of day it was?
P                   Afternoon, I think. I usually go in the afternoon.
P                   Markets in that region are always laid out in rings.
P                   My sister was with me - no, wait, that was a different trip.
P                   I remember my chest going tight.
P                   I don't know how long it took. Honestly I couldn't tell you.

Detail addresses exercised: target episode; repeated/categoric ("I usually go");
general knowledge ("always laid out in rings"); other specific episode ("a different
trip"); discourse ("Okay. Um."); unresolved ("I couldn't tell you").
Interviewer turns are protocol context and contribute no memory-address mass.

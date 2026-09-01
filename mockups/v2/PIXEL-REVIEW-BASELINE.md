# Pixel-review baseline

This ledger preserves the exact review surface that motivated the DPR 2 repair.
It is evidence about an earlier immutable candidate, not a claim that those pixels
are correct.

## Reviewed candidate

- candidate: `cand-3CM1JDY29SG3WPSV74GVS08WNF`
- commit: `dda53463a1556f1b18bfd8a24c937d439fc89dfc`
- tree: `7fd21cafc60e317f24044bf44fb38a6081606357`
- base: `003f12b21e1b9793fea479e104c2e2eff3e55fe5`
- artifact digest: `3dd04832c0f0406bcb743effc582f9a78fdbe4f9c092e6de914d6443efaa1f32`
- reproducible digest: `d3a378c361802a78e62ec6221d96e3dcbfa42287344a4e2fce5b91caa29a1ec2`

The implementation/science checkpoint used as the base of this repair is
`84554a52a839d485e8c010c33c39f0de9169d594`. It fixes the formal clipping,
manifest, facet, and claim-status courts without changing the pixel baseline
recorded here.

## Binding findings

1. `compact.png` clipped the Atlas maximum from `584` to `58`.
2. `state-board.png` clipped `1 inversion / 5 transitions`, overprinted its
   inversion glyph, and truncated the multimodal readout to `.44/`.
3. `motion.png` translated the landmark group by 3 px between the final two
   frames even though both represent the same rest position and the plate says
   that no mark travels.
4. The five named off-source recall destinations had no individual column rules,
   so a mark could not be assigned to a named destination.
5. Atlas marks and portal paths overprinted the labels that give them meaning.
6. The State board's `never confuse with` line was distinguished by hue alone.
7. `directions.png` lacked the global synthetic banner required by
   `PROOF-BOUNDARY.md`.
8. Chronology Loom was a dead tab rather than a frozen plate.
9. Feature scale-space was a dead tab rather than a frozen plate.
10. Consequently the three-clock view and a legible flashback crossing were not
    delivered at all.

The craft pass also found sixteen text/mark collisions across six plates, a
1440 x 731 px zero-ink band in `tokens.png`, and 105 px of empty space inside
the Focus-order border. Every baseline PNG was captured at DPR 1, making 6 px
labels, hairlines, and 0.95 px hatch strokes unfit for the intended pixel gate.
DPR 2 is therefore a prerequisite of the successor court, not a cosmetic
upgrade.

## Baseline output hashes

SHA-256, in manifest path order:

```text
compact.html 3c22056539b398a9e489975c7fb1c6d640a0937fc1657013d696fbd48bc2f658
compact.png 62ff0458d8813a2567f80a14ae4560b0a1c60f9a91318db08f127e850d03ad6c
concept.html b4652a8c7a8f9a013092b5d861d269c0a2d11349c2342ab54e9857ab956bb323
concept.png c51dd88174ca5825cd993a1ae07ebd09d9d6aac1aa0a786c35f3d69f3a31eeb5
directions.html 7ee09e80a7f296db2ead9d596de7dac38e0c80f0b58e2821708301b671775caa
directions.png 23bdc33c7ef9b2a392ff8ec42339ab3650e8196166286d1798b1188e3a018b89
focus-order.html af297a2fabcd31bd855d1ad514c80e9db68517773db9b907a626cf1f645f127d
focus-order.png 378e6eef08e538cad502007e305aaf050d2b2885f772d077d4c1ae5cdd90739d
grayscale.html 956db38bf0f074c2d8602e653f01acc6206a173b7f30c38043c9d2b496ee6553
grayscale.png 5f6e40213d12d4605be208e63cf7cd7cac0f826d67b512737bc2d38834ec2cf2
index.html 8f0d38afb94f124698860b84d787e936704ac83cf2efe83df568d76d90ad9feb
interview-mode.html 634e4ffe63d4f63171229cc396d383a8f2c8bb3354d23977b67d63f3c3de782d
interview-mode.png c2731a07d2f5c2d6043ce7376f82f8830d14e6260af54dcdb67c96410831dda6
main.html 8eb4e5ee785b43a27dd4a561b57e3b5d91349ff3ea79674a9555511010a487bf
main.png 0f5d0dbfc553987f81b1a047d736eaf9e4a091d93e308b696dbeb68d07589641
motion.html 0d9db2c23de806110af75cf6f6ab545054874ccbc5b1f20c406698a7bcecc009
motion.png 07220e404bd9c92ddd28c924147d4e17afdcf03a05a47927fa5a258c99660d03
recall-matrix.html 5e6b6e0b1ea78b88e933bb13e84f7a159d7f217899311a6117174d528f07a660
recall-matrix.png 8baf3989f0cb971afe261b39447ae0813ca6b9ad8d775635420f5f6eca0b7c1a
reflow.html 505262b809da502e8b85e7f35658da8e44f5362c96f4c6a390037965f03b3e98
reflow.png 95545152e09d63805f04bf87dde310ce3f25d27fc17d3b7d95c0d166f2af0e1c
renderer-gap-benchmark.json 49f5fa7a3fb80a094984fb02601e05cfc72679922bb6fdd72b1af794d22a442d
renderer-gap-benchmark.png f38e6ca8c085d5c8ab489343e09b2b7dfdc9eb125fc11af5c9208ab34091b674
renderer-gap.html 8b36ee145cc670a4b1b3dcb18f9a8525a1e198a66b83c0f973750f93712d278e
renderer-gap.png 466fd4445cc9555b326b9351bc0be75528b947d8ade4471e358368dc1fefaae8
state-board.html 4855999bea175151fcd4fae124ddd2577920bc029292ed925716fa6b6d2eddea
state-board.png 75257ad367e40600584e60a2b40cac6a9b5f096ad54f3252f0c644ab5200b3c2
tokens.html 8fd2e83d77864ccaf7fa280db0ba67f6ecaa094a8759d4ef98f5f73ceda3096e
tokens.png d9a35ae7d8ef14adb43500dbb85cd132ce6f37fd354db7368d1d04e4df3ce9d2
```

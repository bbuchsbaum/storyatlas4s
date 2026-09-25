// Synthetic Recall Voyage fixture v4 (producer rules): "The Silent River". Producer-shaped rows (unique anchors,
// argmax = highest mass, group-grain anchors reference groups). Every value is invented.
// Shape mirrors the Sherlock NN03 legacy document (173 units, 50 source groups, returns,
// decode fills, external-dominant units) without any real recall text or result.
(function () {
  function rng(seed) {
    return function () {
      seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const R = rng(20260924);
  const SOURCE_END = 49 * 60 + 30;   // seconds of supplied source display clock
  const RECALL_END = 17 * 60 + 48.4; // recall onset extent
  const names = ['Arrival', 'The old mill', 'Downriver', 'Mill keeper', 'Morning fog', 'The letter',
    'Ferry crossing', 'Market day', 'The bridge', 'Press gathering', 'Lantern shop', 'Night watch',
    'Police station', 'The ledger', 'Flood warning', 'Lab visit', 'Two strangers', 'Signal fire',
    'Flat on the quay', 'The missing key', 'Radio report', 'Second report', 'Boat house',
    'The archivist', 'Questions', 'Rain', 'Sally goes home', 'Pier at dusk', 'The photograph',
    'Crime scene', 'Silent room', 'An opinion', 'Conclusion', 'The suitcase', 'Warning',
    'Phone booth', 'Dry dock', 'A meeting', 'Refusal', 'Left hand', 'The ride back', 'Night train',
    'Patch problem', 'Harbour master', 'The number', 'Old friends', 'Her phone', 'Pink suitcase',
    'Last bell', 'Leaving'];
  // 50 source groups with unequal durations.
  let t = 0;
  const groups = names.map((name, i) => {
    const d = 25 + Math.floor(R() * 70);
    const g = { id: i + 1, name, start: t, end: t + d };
    t += d;
    return g;
  });
  const scale = SOURCE_END / t;
  groups.forEach(g => { g.start = Math.round(g.start * scale); g.end = Math.round(g.end * scale); });
  groups[groups.length - 1].end = SOURCE_END;
  // Segments: each group splits into 3–14 segments (fine grain, numbered globally).
  let seg = 1;
  groups.forEach(g => {
    const n = 3 + Math.floor(R() * 12);
    g.segments = [];
    for (let k = 0; k < n; k++) {
      const a = g.start + (g.end - g.start) * k / n, b = g.start + (g.end - g.start) * (k + 1) / n;
      g.segments.push({ id: seg++, group: g.id, start: a, end: b });
    }
  });
  const segments = groups.flatMap(g => g.segments);
  const words = ['she', 'went', 'back', 'to', 'the', 'river', 'and', 'then', 'there', 'was', 'a', 'man',
    'with', 'lantern', 'I', 'think', 'it', 'rained', 'they', 'found', 'letter', 'on', 'boat', 'someone',
    'called', 'police', 'mill', 'old', 'key', 'maybe', 'later', 'night', 'bridge', 'photograph', 'quiet'];
  function phrase(n) {
    const w = [];
    for (let i = 0; i < n; i++) w.push(words[Math.floor(R() * words.length)]);
    const s = w.join(' ');
    return s.charAt(0).toUpperCase() + s.slice(1) + '.';
  }
  // 173 recall units: a stepped, mostly forward walk through the groups with returns.
  const N = 173;
  const units = [];
  let cursor = 0; // group index
  for (let i = 0; i < N; i++) {
    const onset = Math.max(0.4, Math.min(RECALL_END - 2, i * (RECALL_END / N) + (R() - 0.5) * 3));
    if (R() < 0.55) cursor = Math.min(49, cursor + (R() < 0.8 ? 0 : 1) + (R() < 0.35 ? 1 : 0));
    let g = cursor;
    const isReturn = R() < 0.07 && cursor > 6;
    if (isReturn) g = Math.max(0, cursor - 3 - Math.floor(R() * 12));
    const grp = groups[g];
    const s = grp.segments[Math.floor(R() * grp.segments.length)];
    // Producer-shaped rows (StoryModel voyage rules, as reviewed):
    //  - untimed units carry no anchor, origin or mass (Untimed mark);
    //  - a unit with no source mass is Unanchored;
    //  - admitted anchors are unique and sum to sourceMass; the argmax is the highest-mass anchor;
    //  - decode-bound: the decode chose another group that carries mass; the anchor is that group's best anchor;
    //  - decode-filled: the decode chose a group carrying zero posterior mass; anchor mass is exactly 0;
    //  - localizability = 1 - H(source-normalised anchors) / log K, K = all timeline nodes.
    const timed = !(i % 29 === 3);
    if (!timed) { units.push({ ordinal: i + 1, onset: null, text: phrase(4 + Math.floor(R() * 9)), kind: 'untimed' }); continue; }
    if (i % 57 === 20) { units.push({ ordinal: i + 1, onset, text: phrase(4 + Math.floor(R() * 9)), kind: 'unanchored', external: 1, sourceMass: 0 } /* this fixture's unanchored rows carry all their mass outside the source */); continue; }
    const grain = R() < 0.15 ? 'group' : 'segment';
    const ref = x => grain === 'group' ? groups[x.group - 1] : x;
    const key = x => grain === 'group' ? 'g' + x.id : 's' + x.id;
    const groupOf = n => grain === 'group' ? n : groups[n.group - 1];
    const external = R() < 0.12 ? 0.5 + R() * 0.4 : R() * 0.35;
    const source = 1 - external;
    const raw = new Map();
    const add = (node, w) => { const k = key(node); raw.set(k, { node, w: (raw.get(k)?.w || 0) + w }); };
    add(ref(s), 1.2 + R() * 2.5);
    const k = 2 + Math.floor(R() * 11);
    for (let j = 0; j < k; j++) {
      const gg = groups[Math.max(0, Math.min(49, g + Math.floor((R() - 0.5) * 16)))];
      add(ref(gg.segments[Math.floor(R() * gg.segments.length)]), R() * R() * 1.1);
    }
    const tot = [...raw.values()].reduce((a, b) => a + b.w, 0);
    const cands = [...raw.values()].map(e => ({ node: e.node, mass: source * e.w / tot })).sort((a, b) => b.mass - a.mass || (a.node.id < b.node.id ? -1 : 1));
    const argmax = cands[0];
    const massGroups = new Map();
    cands.forEach(c => { const gid = groupOf(c.node).id; if (!massGroups.has(gid)) massGroups.set(gid, c); });
    let origin = 'argmax', drawn = argmax.node, drawnMass = argmax.mass;
    const roll = R();
    const others = [...massGroups.entries()].filter(([gid]) => gid !== groupOf(argmax.node).id);
    if (roll < 0.28 && others.length) {
      origin = 'decode-bound';
      const [, best] = others[Math.floor(R() * others.length)];
      drawn = best.node; drawnMass = best.mass;
    } else if (roll < 0.37) {
      origin = 'decode-filled';
      let gz = null;
      for (let back = 1; back < 50 && !gz; back++) { const gg = groups[(g - back + 50) % 50]; if (!massGroups.has(gg.id)) gz = gg; }
      drawn = grain === 'group' ? gz : gz.segments[0]; drawnMass = 0;
    }
    const K = groups.length + segments.length; // all timeline nodes (VoyageCompiler: row.localizability(timeline.nodes.size))
    const H = -cands.reduce((a, c) => { const p = c.mass / source; return a + (p > 0 ? p * Math.log(p) : 0); }, 0);
    units.push({
      ordinal: i + 1, onset, text: phrase(4 + Math.floor(R() * 9)), kind: 'anchor',
      drawn, drawnMass, origin, grain, argmax: argmax.node, argmaxMass: argmax.mass, candidates: cands,
      external, sourceMass: source, localizability: 1 - H / Math.log(K),
      externalDominant: external > source
    });
  }
  window.VOYAGE = { groups, segments, units, SOURCE_END, RECALL_END };
  window.fmt = function (s) {
    if (s == null) return '—';
    const m = Math.floor(s / 60), r = Math.floor(s % 60);
    return `${m}:${String(r).padStart(2, '0')}`;
  };
})();

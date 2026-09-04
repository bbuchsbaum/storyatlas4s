#!/usr/bin/env node
// Checked bundle -> exact-support reading links, SVG geometry, masks, and source recovery.
// Usage: node e2e/static/features.cjs BUNDLE_DIR EDITION_DIR
// Use this directory's pinned Playwright. Optional PLAYWRIGHT_CHROMIUM_EXECUTABLE selects
// a task-owned installation of that pin; never point it at a user browser/profile.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');
assert.equal(require('playwright/package.json').version, '1.55.1');
const digest = s => createHash('sha256').update(s).digest('hex');
const [bundle, edition] = process.argv.slice(2).map(p => path.resolve(p));
assert(bundle && edition, 'supply bundle and edition directories');
const record = JSON.parse(fs.readFileSync(path.join(bundle, 'features.json'), 'utf8'));
const receipt = JSON.parse(fs.readFileSync(path.join(edition, 'receipt.json'), 'utf8'));
assert.equal(record.canonicalSourceChecksum, receipt.sourceChecksum);
const source = JSON.parse(fs.readFileSync(path.join(bundle, 'storymodel.json'), 'utf8')).source.canonicalText;
assert.equal(digest(source), receipt.sourceChecksum);
const tracks = record.tracks.filter(e => e.track.observations.length &&
  ['Token', 'Sentence', 'Situation'].includes(e.track.observations[0].target.type));
assert(tracks.length > 0, 'this court requires nonempty measured tracks');
function missingReason(r) {
  if (typeof r === 'string') return r;
  if (r.type === 'Custom') return `Custom(${r.namespace},${r.label ?? r.name})`;
  assert(['Undefined', 'Malformed'].includes(r.type), 'unrecognized missing reason');
  return `${r.type}(${missingReason(r.reason)})`;
}
const escapePart = s => encodeURIComponent(String(s)).replaceAll('%3A', ':')
  .replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase());
function address(space, t) {
  return `features/observation/${escapePart(space)}/${t.type.toLowerCase()}/${escapePart(t.index ?? t.unit ?? t.id)}`;
}

(async () => {
  const browser = await chromium.launch({headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? {executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE} : {})});
  const context = await browser.newContext({viewport: {width: 1440, height: 1100}});
  const page = await context.newPage();
  const errors = []; page.on('pageerror', e => errors.push(String(e)));
  let surfacePages = 0, outcomes = 0, disjoint = 0;
  try {
    for (const {track} of tracks) {
      const file = `feature-${digest(track.space.id)}.html`;
      const bytes = fs.readFileSync(path.join(edition, file));
      assert.equal(digest(bytes), receipt.files.find(f => f.file === file).sha256);
      await page.goto(pathToFileURL(path.join(edition, file)).href);
      assert.equal(await page.locator('nav a').count(), tracks.length);
      const data = await page.evaluate(() => ({
        source: document.querySelector('article').textContent,
        overflow: document.documentElement.scrollWidth > innerWidth,
        height: document.querySelector('svg').viewBox.baseVal.height,
        marks: [...document.querySelectorAll('details')].map(d => {
          const g = [...document.querySelectorAll('svg [data-name]')].find(e => e.getAttribute('data-name') === d.id);
          const rects = g ? [...g.querySelectorAll('polygon')].filter(p => p.getAttribute('stroke') !== 'none' && p.getAttribute('fill') !== 'none') : [];
          return {address: d.querySelector('code').textContent, description: d.querySelector('p').textContent,
            pieces: [...document.querySelectorAll('article a')].filter(a => a.hash === '#' + d.id).map(a => a.textContent),
            boxes: rects.map(p => {const pts = [...p.points]; return {x0: pts[0].x, x1: pts[1].x, y0: pts[0].y, y1: pts[2].y, dotted: p.hasAttribute('stroke-dasharray')};}),
            crosses: g ? [...g.querySelectorAll('polyline:not([stroke-dasharray])')].map(p => [...p.points].map(q => [q.x,q.y])) : [],
            drawn: !!g, title: g?.parentElement.querySelector('title')?.textContent,
            dashed: g?.querySelectorAll('[stroke-dasharray]').length || 0,
            polygons: g?.querySelectorAll('polygon').length || 0};
        })
      }));
      assert(!data.overflow, `horizontal overflow: ${file}`);
      assert.equal(data.marks.length, track.observations.length);
      const family = track.observations[0].target.type;
      if (family === 'Token' || family === 'Sentence') {
        assert.equal(digest(data.source), receipt.sourceChecksum, `exact source recovery: ${family}`);
        surfacePages++;
      }
      const byAddress = new Map(data.marks.map(m => [m.address, m]));
      let xScale;
      for (const observation of track.observations) {
        const mark = byAddress.get(address(track.space.id, observation.target));
        assert(mark?.drawn, 'recorded outcome absent from SVG');
        assert.equal(mark.title, mark.description, 'inspector and SVG describe different measurements');
        const spans = observation.support.map(s => s.span);
        assert.equal(mark.boxes.length, spans.length, 'support was hulled or dropped');
        assert.equal(mark.pieces.length, spans.length, 'source support pieces were hulled or dropped');
        spans.forEach((s, i) => {
          const b = mark.boxes[i];
          assert(b.y0 >= 0 && b.y1 <= data.height, 'feature row clipped by plate');
          if (!xScale) xScale = {slope: (b.x1 - b.x0) / (s.end - s.start), start: s.start, x: b.x0};
          const x = n => xScale.x + (n - xScale.start) * xScale.slope;
          assert(Math.abs(b.x0 - x(s.start)) < 0.1 && Math.abs(b.x1 - x(s.end)) < 0.1, 'support coordinates differ from record');
          assert.equal(mark.pieces[i], source.slice(s.start, s.end), 'support text changed');
        });
        if (spans.length > 1) disjoint++;
        if (observation.estimate.missing) {
          const reason = missingReason(observation.estimate.missing);
          assert(mark.description.includes(`missing=${reason}`));
          if (reason === 'Excluded') assert(mark.boxes.every(b => b.dotted), 'excluded dotted outlines absent');
          else {
            assert.equal(mark.crosses.length, spans.length * 2, 'missingness crosses absent');
            mark.boxes.forEach((b,i) => assert.deepEqual(mark.crosses.slice(i*2,i*2+2),
              [[[b.x0,b.y0],[b.x1,b.y1]],[[b.x0,b.y1],[b.x1,b.y0]]], 'missingness cross geometry changed'));
          }
        }
        const c = observation.coverage;
        assert(mark.description.includes(c ? `coverage=${c.observed}/${c.eligible}` : 'coverage=not recorded'));
        if (!c) assert(mark.dashed > 0, 'unrecorded coverage lost its independent mask');
        else if (c.eligible > 0) assert.equal(mark.polygons, spans.length * (c.observed > 0 ? 3 : 2), 'coverage bar lost its eligible outline or observed fill');
        outcomes++;
      }
      console.log(JSON.stringify({file, family, outcomes: data.marks.length, basis: receipt.basis}));
    }
    assert.equal(surfacePages, tracks.filter(t => ['Token', 'Sentence'].includes(t.track.observations[0].target.type)).length);
    await page.setViewportSize({width: 390, height: 844});
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'mobile horizontal overflow');
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({pages: tracks.length, surfacePages, outcomes, disjoint, failures: 0}));
  } finally { await page.close(); await context.close(); await browser.close(); }
})().catch(e => {console.error(e); process.exitCode = 1;});

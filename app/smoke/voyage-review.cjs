#!/usr/bin/env node
// Frozen Sherlock display regression. Generate nn03-full/ and nn03-content/ first.
// node app/smoke/voyage-review.cjs <edition-directory> [baseline-directory]
// Uses the project's pinned browser; no system browser or external requests.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { pathToFileURL } = require('node:url');
const modules = path.resolve(__dirname, '../../e2e/static');
assert.equal(require(require.resolve('playwright/package.json', { paths: [modules] })).version, '1.55.1');
const { chromium } = require(require.resolve('playwright', { paths: [modules] }));
const out = path.resolve(process.argv[2]);
const baseline = process.argv[3] && path.resolve(process.argv[3]);
const report = { checks: [], errors: [], network: [] };
function check(ok, name) { assert.ok(ok, name); report.checks.push(name); }
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
// The canonical wire encodes doubles as IEEE-754 hex strings, not decimal JSON numbers.
const number = value => Buffer.from(value.slice(2), 'hex').readDoubleBE();
function wire(value) { const bytes = Buffer.alloc(8); bytes.writeDoubleBE(value); return '0x' + bytes.toString('hex'); }

async function inspect(page, arm) {
  const dir = path.join(out, arm);
  await page.goto(pathToFileURL(path.join(dir, 'voyage.html')).href);
  await page.locator('.plate svg').waitFor();
  const doc = JSON.parse(await page.locator('#voyage-document').textContent());
  const names = await page.locator('.plate [data-name]').evaluateAll(els => els.map(e => e.dataset.name));
  check(names.length === 173 && new Set(names).size === 173, `${arm}: complete named unit inventory`);
  check(await page.locator('.voyage-ghost').count() === 0, `${arm}: no default ghost fence`);
  const legend = () => page.locator('.legend-row').innerText();
  check(!/independent coding|absence rail|margin row|ghost:|context:/.test(await legend()), `${arm}: absent encodings omitted`);
  check((await legend()).includes('hollow:'), `${arm}: external-dominant hollow retained`);
  const externalDominant = arm === 'nn03-full' ? 35 : 36;
  check(await page.locator('.plate .external-dominant').count() === externalDominant, `${arm}: all ${externalDominant} external-dominant marks retained`);
  check(await page.locator('.plate .origin-filled').count() === 44, `${arm}: all 44 fills retained`);
  check(!(await page.locator('.masthead').innerText()).includes('raw posterior'), `${arm}: header does not misname decoded route`);
  check((await page.locator('.stats').innerText()).includes(arm === 'nn03-full' ? '108/173' : '112/173'), `${arm}: moved proportion`);
  const pane = page.locator('section[aria-label="Recall Voyage"]');
  await pane.focus();
  for (let i = 0; i <= 5; i++) await page.keyboard.press('ArrowRight');
  await page.waitForFunction(id => document.querySelector('.page[data-focus]')?.dataset.focus === id, doc.units[5].id);
  check(await page.locator('.voyage-ghost').count() === 1, `${arm}: only selected moved unit has a ghost`);
  check(await page.locator('.voyage-ghost').getAttribute('data-unit') === '5', `${arm}: ghost belongs to selected unit`);
  check((await legend()).includes('ghost:'), `${arm}: visible ghost has key`);
  check((await page.locator('.inspector .quote').textContent()) === `“${doc.units[5].text}”`, `${arm}: exact selected text`);
  check((await page.locator('.inspector').innerText()).includes('0.000'), `${arm}: zero-mass inspector witness`);
  const label = page.locator('.voyage-group-label[data-group="4"]');
  check(await label.count() === 1, `${arm}: short selected group label remains visible`);
  check((await label.locator('title').textContent()) === '4. Watson Morning', `${arm}: original numbered label`);
  const g4 = doc.timeline.groups.find(g => g.ordinal === 4);
  const sourceEnd = Math.max(...[...doc.timeline.nodes, ...doc.timeline.groups].map(n => number(n.span.end)));
  // plate top is 34 (VoyageLowering.Box.default.top): the unanchored row sits above the plot
  const expectedY = 34 + 540 - 540 * ((number(g4.span.start) + number(g4.span.end)) / 2) / sourceEnd;
  const tick = (await label.locator('polyline').getAttribute('points')).split(' ')[0].split(',').map(Number);
  check(Math.abs(tick[1] - expectedY) < 0.001, `${arm}: label at supplied span, not redistributed row`);
  const allGhosts = page.locator('.controls input').nth(0);
  await allGhosts.check();
  check(await page.locator('.voyage-ghost').count() === (arm === 'nn03-full' ? 108 : 112), `${arm}: all ghosts remain opt-in`);
  await allGhosts.uncheck();
  check(await page.locator('.voyage-ghost').count() === 1, `${arm}: return to selected-only ghosts`);
  await page.locator('.controls input').nth(1).check();
  check((await legend()).includes('context:'), `${arm}: context key follows control`);
  await page.locator('.controls input').nth(1).uncheck();
  check(!(await legend()).includes('context:'), `${arm}: context key removed`);
  check(await page.locator('.page[data-focus]').getAttribute('data-focus') === doc.units[5].id, `${arm}: controls preserve focus identity`);
  await page.screenshot({ path: path.join(dir, 'desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => Number(document.querySelector('.page[data-plate-width]')?.dataset.plateWidth) < 390);
  await page.screenshot({ path: path.join(dir, 'compact.png'), fullPage: true });
  check(await page.locator('.page[data-focus]').getAttribute('data-focus') === doc.units[5].id, `${arm}: resize preserves identity`);
  await page.setViewportSize({ width: 1600, height: 1000 });
  const receipt = JSON.parse(fs.readFileSync(path.join(dir, 'voyage-receipt.json')));
  for (const file of receipt.files) check(digest(fs.readFileSync(path.join(dir, file.file))) === file.sha256, `${arm}: receipt ${file.file}`);
  if (baseline) {
    const prior = path.join(baseline, arm);
    check(fs.readFileSync(path.join(prior, 'voyage.txt'), 'utf8') === fs.readFileSync(path.join(dir, 'voyage.txt'), 'utf8'), `${arm}: byte-identical scientific textual twin`);
    const old = JSON.parse(fs.readFileSync(path.join(prior, 'voyage-receipt.json')));
    const semantic = r => Object.fromEntries(Object.entries(r).filter(([k]) => !['files', 'document', 'plateBoxPx'].includes(k)));
    assert.deepEqual(semantic(receipt), semantic(old));
    check(true, `${arm}: unchanged scientific receipt fields`);
  }
  return doc;
}

(async () => {
  let browser;
  try {
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
    try {
      const page = await context.newPage();
      page.on('pageerror', e => report.errors.push(String(e)));
      page.on('console', m => { if (m.type() === 'error') report.errors.push(m.text()); });
      page.on('request', r => { if (/^https?:/.test(r.url())) report.network.push(r.url()); });
      const doc = await inspect(page, 'nn03-full');
      await inspect(page, 'nn03-content');
      // Explicit test-only variant: exercise legend channels absent from the frozen real case.
      doc.provenance.configChecksum = digest('synthetic browser legend variant');
      doc.coding = { name: 'SYNTHETIC browser witness', checksum: digest('synthetic coding'), intervals: [{ recall: { start: wire(0), end: wire(4) }, group: 1 }] };
      delete doc.units[0].onset;
      delete doc.units[0].lastWordOnset;
      delete doc.decisions[1].anchor;
      delete doc.decisions[1].group;
      doc.decisions[1].origin = 'posterior_argmax';
      doc.rows.find(r => r.unit === doc.decisions[1].unit).mass = [{
        state: { type: 'External', state: 'Unranked' }, mass: wire(1)
      }];
      const original = fs.readFileSync(path.join(out, 'nn03-full/voyage.html'), 'utf8');
      const variant = original.replace(/(<script type="application\/json" id="voyage-document">)[\s\S]*?(<\/script>)/, (_, a, b) => a + JSON.stringify(doc).replace(/</g, '\\u003c') + b);
      const file = path.join(out, 'nn03-full/synthetic-legend-witness.html');
      fs.writeFileSync(file, variant);
      await page.goto(pathToFileURL(file).href);
      await page.waitForFunction(() => document.querySelector('.plate svg') || document.querySelector('.error'));
      check(await page.locator('.error').count() === 0, 'synthetic: checked document decodes: ' + await page.locator('.error').allTextContents());
      await page.locator('.plate svg').waitFor();
      const key = await page.locator('.legend-row').innerText();
      check(key.includes('independent coding:'), 'synthetic: present independent coding has key');
      check(key.includes('unanchored row:'), 'synthetic: present unanchored mark has key');
      check(!(key.includes('margin row:')), 'synthetic: no margin key for untimed units outside the plot');
      check((await page.locator('#voyage-unit option').allTextContents()).some(t => t.includes('untimed')), 'synthetic: untimed units remain in the selector');
      check(await page.locator('.voyage-coding').count() === 1, 'synthetic: coding band retained');
      check(report.errors.length === 0, 'no browser or console errors');
      check(report.network.length === 0, 'no HTTP(S) requests');
    } finally { await context.close(); }
  } catch (e) { report.failure = String(e); process.exitCode = 1; }
  finally {
    if (browser) await browser.close();
    fs.writeFileSync(path.join(out, 'browser-review.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
  }
})();

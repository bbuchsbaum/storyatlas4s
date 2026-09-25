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
  const selStroke = await page.locator('.plate .selected .voyage-container').evaluateAll(es => es.map(e => getComputedStyle(e.querySelector('circle,polygon,path') || e).strokeWidth));
  check(selStroke.length === 1 && selStroke[0] === '2px', `${arm}: the selected mark's container carries the selection stroke (${selStroke})`);
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
  // the gutter holds the selected unit's admitted anchors; every in-view value is printed
  const gutterBars = await page.locator('.plate .voyage-gutter').count();
  check(gutterBars >= 1, `${arm}: the selected unit's admitted anchors sit in the gutter (${gutterBars})`);
  check(await page.locator('.plate .voyage-alt:not(.voyage-gutter)').count() === 0, `${arm}: no admitted anchor is drawn on the recall axis beside the gutter`);
  // the plot is one tab stop: a listbox of unit options with the selection as active descendant
  const plate = page.locator('.plate[role=listbox]');
  check(await plate.count() === 1 && await plate.getAttribute('tabindex') === '0', `${arm}: the plot is a focusable listbox`);
  check(await page.locator('.plate [tabindex="0"]').count() === 0, `${arm}: nothing inside the plot is a separate tab stop`);
  const optionCount = await page.locator('.plate [role=option]').count();
  check(optionCount === 173, `${arm}: every unit mark in view is an option (${optionCount})`);
  const activeId = await plate.getAttribute('aria-activedescendant');
  check(!!activeId && await page.locator(`#${activeId}`).getAttribute('aria-selected') === 'true', `${arm}: the active descendant is the selected option`);
  check(/\S/.test(await page.locator(`#${activeId}`).getAttribute('aria-label')), `${arm}: the active option has a label`);
  check(await page.locator('.inspector[aria-live]').count() === 0, `${arm}: the inspector is not re-announced on every move`);
  // arriving by keyboard with the selection outside the window selects a unit in view
  await page.getByLabel('Detail start (m:ss)').fill('15:00');
  await page.getByLabel('Detail end (m:ss)').fill('17:00');
  await page.getByRole('button', { name: 'Apply range', exact: true }).click();
  await page.waitForTimeout(150);
  await page.getByRole('button', { name: 'Whole film', exact: true }).focus();
  for (let i = 0; i < 40 && !(await page.evaluate(() => document.activeElement.matches('.plate[role=listbox]'))); i++) await page.keyboard.press('Tab');
  await page.waitForTimeout(150);
  const arrived = await page.locator('.page[data-focus]').getAttribute('data-focus');
  const arrivedOnset = doc.units.find(u => u.id === arrived)?.onset;
  check(arrived !== doc.units[5].id && arrivedOnset && number(arrivedOnset) >= 900 && number(arrivedOnset) <= 1020, `${arm}: tabbing onto the plot selects a unit in the window`);
  await page.getByRole('button', { name: 'Whole recall', exact: true }).click();
  await page.locator(`.plate .voyage-anchor[data-unit="${doc.units[5].ordinal}"] [data-name]`).first().dispatchEvent('click');
  await page.waitForTimeout(150);
  await plate.focus();
  await page.keyboard.press('Enter');
  await page.waitForTimeout(100);
  check(await page.evaluate(() => !!document.activeElement.closest('.inspector')), `${arm}: Enter on the plot opens the inspector`);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(100);
  check(await page.evaluate(() => document.activeElement.matches('.plate[role=listbox]')), `${arm}: Escape returns to the plot`);
  // inspection filter: supplied chip counts, dimming without dropping, a tick per match, M steps
  const fills = page.getByRole('button', { name: /^Decode-filled, 44 units/ });
  check(await fills.count() === 1, `${arm}: the decode-filled chip carries the supplied count`);
  check(await page.locator('.plate .unmatched, .plate .matched').count() === 0, `${arm}: no dimming before a filter is set`);
  await fills.click();
  await page.waitForTimeout(150);
  check((await page.locator('.filter-count').textContent()) === 'any · 44 of 173 units match', `${arm}: the live count reports the matches`);
  check(await page.locator('.plate .voyage-anchor.matched').count() === 44, `${arm}: every fill is marked as a match`);
  check(await page.locator('.plate .voyage-anchor.unmatched').count() === 173 - 44, `${arm}: every other mark is dimmed, none dropped`);
  check(await page.locator('.plate .voyage-anchor.matched .voyage-match-tick').count() === 44, `${arm}: each match carries a tick`);
  check((await page.locator('.plate .voyage-caption').textContent()).includes('filter (any): decode-filled; 44 of 173 units match'), `${arm}: the caption carries the filter state`);
  await page.locator('section[aria-label="Recall Voyage"]').focus();
  await page.keyboard.press('m');
  await page.waitForTimeout(150);
  check(/matches: decode-filled/.test(await page.locator('.sr-only[role=status]').textContent()), `${arm}: M steps to a match and says why it matches`);
  // exports: every unit, every admitted anchor, provenance header, matched rows equal the filter
  const download = async name => { const [d] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name }).click()]); return fs.readFileSync(await d.path(), 'utf8'); };
  const unitsTsv = await download(/^Export all 173 units \(44 matched\)/);
  const unitRows = unitsTsv.trim().split('\n').filter(l => !l.startsWith('#'));
  const cols = unitRows[0].split('\t');
  check(unitRows.length - 1 === 173, `${arm}: the units export has one row per unit`);
  check(unitsTsv.includes(`# source checksum: `) && /# derived by this view: timed/.test(unitsTsv) && unitsTsv.includes('not calibrated confidence'), `${arm}: the units export names provenance and derived columns`);
  check(unitRows.slice(1).filter(r => r.split('\t')[cols.indexOf('matched')] === 'true').length === 44, `${arm}: matched rows equal the on-screen matches`);
  check(!cols.includes('unit_address'), `${arm}: no invented address column`);
  const anchorsTsv = await download('Export admitted anchors · TSV');
  const anchorRows = anchorsTsv.trim().split('\n').filter(l => !l.startsWith('#')).slice(1);
  check(anchorRows.length > 173 && anchorRows.every(r => Number(r.split('\t')[8]) > 0), `${arm}: the anchors export lists admitted anchors with posterior mass only (${anchorRows.length})`);
  // inspector: filter reasons, K stated as a view count, neighbours by ordinal
  check((await page.locator('.inspector .filter-reasons').textContent()) === `matches: decode-filled`, `${arm}: the inspector states why the selected fill matches`);
  check((await page.locator('.inspector').innerText()).includes("which this document does not supply") && !/K = \d/.test(await page.locator('.inspector').innerText()), `${arm}: localizability states the model's rule and claims no K it cannot vouch for`);
  check(await page.locator('.inspector .neighbour').count() >= 1, `${arm}: neighbouring units by ordinal are shown`);
  await fills.click();
  await page.waitForTimeout(150);
  check(await page.locator('.plate .unmatched').count() === 0 && (await page.locator('.filter-count').textContent()) === 'no filter set', `${arm}: clearing the chip clears the dimming`);
  await page.locator('section[aria-label="Recall Voyage"]').focus();
  await page.keyboard.press('m');
  await page.waitForTimeout(100);
  check(/No filter set/.test(await page.locator('.sr-only[role=status]').textContent()), `${arm}: M with no filter says so`);
  await page.locator(`.plate .voyage-anchor[data-unit="${doc.units[5].ordinal}"] [data-name]`).first().dispatchEvent('click');
  await page.waitForTimeout(150);
  // y camera: Fit to window states its rule; choosing another unit never rescales the axis
  const yLabels = () => page.locator('.plate svg text').evaluateAll(ts => ts.map(t => t.textContent).filter(t => /^\d+:\d\d$/.test(t)).join(' '));
  // the camera, in 4:00–8:00, where the window's anchors do not span the whole film
  await page.getByLabel('Detail start (m:ss)').fill('240');
  await page.getByLabel('Detail end (m:ss)').fill('480');
  await page.getByRole('button', { name: 'Apply range', exact: true }).click();
  await page.waitForTimeout(150);
  // a unit outside the window has no option; choosing it says so
  const outside = doc.units.find(u => u.onset && number(u.onset) > 490);
  await page.getByLabel('Inspect recall unit').selectOption(outside.id);
  await page.waitForTimeout(150);
  check(/outside the detail window/.test(await page.locator('.sr-only[role=status]').textContent()), `${arm}: choosing a unit outside the window is announced`);
  const inWindow = doc.units.filter(u => u.onset && number(u.onset) >= 250 && number(u.onset) <= 470);
  await page.getByLabel('Inspect recall unit').selectOption(inWindow[0].id);
  await page.waitForTimeout(150);
  const whole = await yLabels();
  await page.getByRole('button', { name: 'Fit to window', exact: true }).click();
  await page.waitForTimeout(150);
  check(await page.getByRole('button', { name: 'Fit to window', exact: true }).getAttribute('aria-pressed') === 'true', `${arm}: Fit to window is pressed`);
  check((await page.locator('.camera-rule').textContent()).includes('snapped to scene bounds'), `${arm}: the fit rule is printed`);
  const fitted = await yLabels();
  check(fitted !== whole, `${arm}: in 4:00–8:00 the fitted axis differs from the whole film`);
  await page.getByLabel('Inspect recall unit').selectOption(inWindow.at(-1).id);
  await page.waitForTimeout(150);
  check(await page.locator('.page[data-focus]').getAttribute('data-focus') === inWindow.at(-1).id, `${arm}: another unit in the window is selected`);
  check(await yLabels() === fitted, `${arm}: selecting another unit does not rescale the fitted film axis`);
  await page.getByRole('button', { name: 'Whole film', exact: true }).click();
  await page.waitForTimeout(150);
  check(await yLabels() === whole, `${arm}: Whole film restores the whole-film axis`);
  await page.getByRole('button', { name: 'Whole recall', exact: true }).click();
  await page.getByLabel('Inspect recall unit').selectOption(doc.units[5].id);
  await page.waitForTimeout(150);
  check(await page.locator('.page[data-focus]').getAttribute('data-focus') === doc.units[5].id, `${arm}: the original unit is selected again`);
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
    const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1, acceptDownloads: true });
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

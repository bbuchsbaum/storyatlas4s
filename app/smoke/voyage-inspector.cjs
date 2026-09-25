#!/usr/bin/env node
// node app/smoke/voyage-inspector.cjs <directory containing nn03-full/ and nn03-content/>
// Reads expected ranks and totals independently from the checked input document.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { pathToFileURL } = require('node:url');
const modules = path.resolve(__dirname, '../../e2e/static');
assert.equal(require(require.resolve('playwright/package.json', { paths: [modules] })).version, '1.55.1');
const { chromium } = require(require.resolve('playwright', { paths: [modules] }));
const out = path.resolve(process.argv[2]);
const report = { checks: [], errors: [], requests: [] };
const check = (ok, name) => { assert.ok(ok, name); report.checks.push(name); };
const number = value => Buffer.from(value.slice(2), 'hex').readDoubleBE();
const key = ref => `${ref.type === 'Situation' ? 'sit' : 'seg'}:${ref.id}`;
const close = (a, b) => Math.abs(a - b) < 1e-12;
function wire(value) { const bytes = Buffer.alloc(8); bytes.writeDoubleBE(value); return '0x' + bytes.toString('hex'); }
const go = (page, arm, name = 'voyage.html') => page.goto(pathToFileURL(path.join(out, arm, name)).href);

async function select(page, doc, ordinal) {
  await page.getByLabel('Inspect recall unit').selectOption(doc.units[ordinal].id);
  await page.waitForFunction(id => document.querySelector('.page[data-focus]')?.dataset.focus === id, doc.units[ordinal].id);
}

async function checkPosterior(page, doc, ordinal, label) {
  await select(page, doc, ordinal);
  const unit = doc.units[ordinal], row = doc.rows.find(r => r.unit === unit.id);
  const decision = doc.decisions.find(d => d.unit === unit.id);
  const source = row.mass.filter(m => m.state.type === 'Source' && number(m.mass) > 0)
    .map(m => ({ key: key(m.state.ref), mass: number(m.mass) }))
    .sort((a, b) => b.mass - a.mass || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  const external = row.mass.filter(m => m.state.type === 'External').reduce((sum, m) => sum + number(m.mass), 0);
  const sourceTotal = source.reduce((sum, c) => sum + c.mass, 0);
  if (source.length) {
    const firstLabel = doc.timeline.nodes.find(n => key(n.ref) === source[0].key).label;
    check((await page.locator('table.alts tbody tr').first().textContent()).includes(firstLabel),
      `${label}: posterior starts with the largest supplied mass, not the drawn choice`);
  }
  const card = page.locator('.drawn-choice');
  check(await card.count() === 1, `${label}: drawn choice is separate from posterior ranking`);
  check(await card.getAttribute('data-source-key') === key(decision.anchor), `${label}: drawn identity preserved`);
  const node = doc.timeline.nodes.find(n => key(n.ref) === key(decision.anchor));
  const span = await card.locator('.choice-span').evaluate(e => [Number(e.dataset.spanStart), Number(e.dataset.spanEnd)]);
  assert.deepEqual(span, [number(node.span.start), number(node.span.end)]);
  check(true, `${label}: drawn annotation span retained independently of group support`);
  const chosenMass = source.find(c => c.key === key(decision.anchor))?.mass || 0;
  check((await card.innerText()).includes(chosenMass.toFixed(3)), `${label}: drawn mass preserved`);
  check((await card.innerText()).includes('drawn (not in posterior)') === (chosenMass === 0), `${label}: zero-mass support status explicit`);
  const summary = page.locator('.posterior-candidates summary');
  await summary.focus();
  await page.keyboard.press('Enter');
  check(await page.locator('.posterior-candidates').getAttribute('open') !== null, `${label}: complete list opens with keyboard`);
  const actual = await page.locator('table.alts tbody tr').evaluateAll(rows => rows.map(r => ({
    key: r.dataset.sourceKey, mass: Number(r.dataset.mass), rank: Number(r.cells[0].textContent), drawn: r.cells[2].textContent.includes(' · drawn')
  })));
  assert.deepEqual(actual.map(c => ({key: c.key, mass: c.mass})), source);
  check(true, `${label}: all positive candidates in independent mass/key order without normalization`);
  check(actual.every((c, i) => c.rank === i + 1), `${label}: contiguous visible ranks`);
  check(actual.filter(c => c.drawn).length === (chosenMass > 0 ? 1 : 0), `${label}: positive drawn choice ranked once; zero fill excluded`);
  const bars = await page.locator('.post .bar span').evaluateAll(es => es.map(e => ({
    kind: e.dataset.massKind, mass: Number(e.dataset.mass), width: parseFloat(e.style.width),
    fraction: e.getBoundingClientRect().width / e.parentElement.getBoundingClientRect().width
  })));
  check(close(bars[0].mass, sourceTotal) && close(bars[1].mass, external), `${label}: source and external totals account for the complete input`);
  check(bars.every(b => Math.abs(b.width - 100 * b.mass) < 0.0001 && Math.abs(b.fraction - b.mass) < 0.001), `${label}: fixed 0–1 CSS widths with no gaps or flex renormalization`);
  const top = source.find(c => c.key !== key(decision.anchor));
  const topText = await page.locator('.top-alternative').innerText();
  check(top ? topText.startsWith('Top alternative:') && topText.includes(top.mass.toFixed(3)) : topText.includes('No other source candidate'), `${label}: top alternative has a clear label`);
  const group = doc.timeline.groups.find(g => g.ordinal === decision.group);
  if (group) {
    const bounds = await page.locator('.group-extents').innerText();
    const seconds = text => { const p = text.trim().split(':').map(Number); return p[0] * 60 + p[1]; };
    check(bounds.split(/\s+/).map(seconds).every((v, i) => close(v, number(i === 0 ? group.span.start : group.span.end))), `${label}: within-group bounds use exact supplied legacy clock`);
    check((await page.locator('.strip').innerText()).includes('not verified media support'), `${label}: annotation extent is not claimed as media support`);
  } else check(await page.locator('.strip').count() === 0, `${label}: no group strip is fabricated`);
  check(await page.locator('.inspector .quote').textContent() === `“${unit.text}”`, `${label}: exact recall text retained`);
  await summary.click();
}

(async () => {
  let browser, context;
  try {
    browser = await chromium.launch({ headless: true });
    context = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
    const page = await context.newPage();
    page.on('pageerror', e => report.errors.push(String(e)));
    page.on('console', m => { if (m.type() === 'error') report.errors.push(m.text()); });
    page.on('request', r => { if (/^https?:/.test(r.url())) report.requests.push(r.url()); });
    let original, doc;
    for (const arm of ['nn03-full', 'nn03-content']) {
      await go(page, arm); await page.locator('.plate svg').waitFor();
      doc = JSON.parse(await page.locator('#voyage-document').textContent());
      await checkPosterior(page, doc, 5, `${arm} unit 5`);
      const first = await page.locator('table.alts tbody tr').first().getAttribute('data-source-key');
      check(first === 'sit:sherlock:row:0820', `${arm}: unit 5 starts with segment 820, not filled segment 24`);
      const bound = doc.decisions.findIndex(d => d.origin === 'decode_bound');
      await checkPosterior(page, doc, bound, `${arm} supported decision`);
      await select(page, doc, 5);
      await page.locator('.inspector').screenshot({ path: path.join(out, arm, 'inspector-desktop.png') });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.waitForFunction(() => Number(document.querySelector('.page').dataset.plateWidth) < 390);
      await checkPosterior(page, doc, 5, `${arm} compact`);
      check(await page.evaluate(() => document.documentElement.scrollWidth <= 390), `${arm}: compact page does not overflow`);
      await page.locator('.inspector').screenshot({ path: path.join(out, arm, 'inspector-compact.png') });
      await page.setViewportSize({ width: 1600, height: 1100 });
    }
    original = fs.readFileSync(path.join(out, 'nn03-full/voyage.html'), 'utf8');
    doc = JSON.parse(original.match(/id="voyage-document">([\s\S]*?)<\/script>/)[1]);
    doc.units.forEach(u => { u.text = `Synthetic inspector witness ${u.ordinal}.`; });
    doc.provenance.configChecksum = createHash('sha256').update('synthetic inspector cases').digest('hex');
    const nodes = doc.timeline.nodes.filter(n => n.level === 0).slice(0, 2);
    const coarse = doc.timeline.nodes.find(n => n.level > 0);
    function assign(i, chosen, origin, masses) {
      const id = doc.units[i].id;
      doc.decisions[i] = { unit: id, origin, ...(chosen ? { anchor: chosen.ref, group: chosen.group } : {}) };
      // Canonical wire order is External then Source, with each family's keys sorted.
      const stateKey = m => m.state.type === 'Source' ? `source:${key(m.state.ref)}` : `external:${m.state.state}`;
      doc.rows.find(r => r.unit === id).mass = masses.sort((a, b) => stateKey(a) < stateKey(b) ? -1 : stateKey(a) > stateKey(b) ? 1 : 0);
    }
    const at = (node, mass) => ({ state: { type: 'Source', ref: node.ref }, mass: wire(mass) });
    const ext = mass => ({ state: { type: 'External', state: 'Unranked' }, mass: wire(mass) });
    assign(0, nodes[1], 'decode_bound', [at(nodes[1], .25), at(nodes[0], .25), ext(.5)]);
    assign(1, coarse, 'posterior_argmax', [at(coarse, .75), ext(.25)]);
    assign(2, null, 'posterior_argmax', [ext(1)]);
    delete doc.units[3].onset; delete doc.units[3].lastWordOnset;
    assign(4, nodes[0], 'decode_filled', [ext(1)]);
    const ungrouped = {...nodes[0], ref: {type:'Situation',id:'synthetic:ungrouped'}, label:'Ungrouped annotation'};
    delete ungrouped.group;
    doc.timeline.nodes.push(ungrouped);
    assign(6, ungrouped, 'posterior_argmax', [at(ungrouped, .75), ext(.25)]);
    const html = original.replace(/(<script type="application\/json" id="voyage-document">)[\s\S]*?(<\/script>)/,
      (_, a, b) => a + JSON.stringify(doc).replace(/</g, '\\u003c') + b);
    fs.writeFileSync(path.join(out, 'nn03-full/synthetic-inspector.html'), html);
    await go(page, 'nn03-full', 'synthetic-inspector.html');
    await page.waitForFunction(() => document.querySelector('.plate svg') || document.querySelector('.error'));
    check(await page.locator('.error').count() === 0, 'synthetic document passes checked decoding: ' + await page.locator('.error').allTextContents());
    await checkPosterior(page, doc, 0, 'synthetic tied candidates');
    await checkPosterior(page, doc, 1, 'synthetic group support');
    check((await page.locator('.strip').innerText()).includes('whole group, not an individual segment'), 'coarse support explicitly stays coarse');
    for (const ordinal of [2, 3]) {
      await select(page, doc, ordinal);
      check(await page.locator('.posterior-unavailable').count() === 1, `synthetic ${ordinal}: missing posterior payload is explicit`);
      check(await page.locator('.drawn-choice, table.alts, .post .bar').count() === 0, `synthetic ${ordinal}: no fabricated decision, ranking or bar`);
    }
    await select(page, doc, 2);
    check((await page.locator('.posterior-unavailable').innerText()).includes('1.000'), 'unanchored unit retains supplied external mass');
    await checkPosterior(page, doc, 4, 'synthetic external-only fill');
    await checkPosterior(page, doc, 6, 'synthetic ungrouped annotation');
    check(report.errors.length === 0, 'no browser or console errors');
    check(report.requests.length === 0, 'no external network requests');
  } catch (e) { report.failure = String(e); process.exitCode = 1; }
  finally {
    if (context) await context.close();
    if (browser) await browser.close();
    fs.writeFileSync(path.join(out, 'inspector-browser.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
  }
})();

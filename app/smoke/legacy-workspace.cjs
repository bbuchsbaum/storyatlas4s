#!/usr/bin/env node
// Public/synthetic legacy Voyage regression through the same edition shell as local workspace open.
// node app/smoke/legacy-workspace.cjs target/edition/index.html <producer-fixtures-dir> <evidence-dir>
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const modules = path.resolve(__dirname, '../../e2e/static');
assert.equal(require(require.resolve('playwright/package.json', {paths:[modules]})).version, '1.55.1');
const { chromium } = require(require.resolve('playwright', {paths:[modules]}));
const [entry, fixtures, output] = process.argv.slice(2).map(value => path.resolve(value));
assert.ok(entry && fixtures && output, 'usage: legacy-workspace.cjs <edition-index.html> <fixtures> <output>');
fs.mkdirSync(output, {recursive:true});
const report = {checks:[], errors:[], requests:[], fixture:null};
const check = (ok, description) => { report.checks.push({ok, description}); assert.ok(ok, description); };
const load = name => fs.readFileSync(path.join(fixtures, name), 'utf8');
const input = (name, text) => ({name, mimeType:'application/json', buffer:Buffer.from(text)});

function voyageFromPacket() {
  for (const name of ['bell', 'wog']) {
    const archive = JSON.parse(load(`${name}.workspace.json`));
    const entry = archive.entries.find(item => item.role.kind === 'Voyage');
    if (entry) return {name: `${name}.workspace.json:${entry.path}`, text: archive.files.find(file => file.path === entry.path).utf8};
  }
  // The current public packet indexes its legacy Voyage separately while its workspace archives
  // carry projections. This remains a producer-generated Bell fixture, never Sherlock/private data.
  return {name: 'bell.voyage.json', text: load('bell.voyage.json')};
}

(async()=>{
  let browser, context, page;
  try {
    const fixture = voyageFromPacket();
    const document = JSON.parse(fixture.text);
    const units = [...document.units].sort((left, right) => left.ordinal - right.ordinal);
    const untimed = units.find(unit => !Object.hasOwn(unit, 'onset'));
    assert.ok(untimed, 'producer legacy Voyage includes an untimed canonical unit; no fake variant needed');
    report.fixture = fixture.name;
    browser = await chromium.launch({headless:true});
    context = await browser.newContext({viewport:{width:1600,height:1100}});
    page = await context.newPage();
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => report.errors.push(String(error)));
    page.on('console', message => { if (message.type() === 'error') report.errors.push(message.text()); });
    page.on('request', request => { if (/^https?:/.test(request.url())) report.requests.push(request.url()); });
    // The edition's app.js is loaded from the supplied index.html. setInputFiles then embeds the
    // producer legacy JSON through the host's real FileReader/admission route, not a static plate.
    await page.goto(pathToFileURL(entry).href);
    await page.locator('#workspace-open').waitFor();
    await page.locator('#workspace-open').setInputFiles(input('legacy-voyage.json', fixture.text));
    await page.waitForFunction(()=>document.querySelector('[data-open-status]')?.getAttribute('data-open-status')?.startsWith('Opened'));
    await page.locator('.plate svg').waitFor();
    check(await page.locator('.plate svg').count() === 1, 'legacy checked route renders one real SVG plate in the edition shell');
    check(await page.locator('.error').count() === 0, 'legacy checked route has no decode or compile error');
    check(await page.locator('#voyage-unit option').count() === units.length + 1, 'legacy picker retains every canonical unit plus its placeholder');
    check(await page.locator('.page[data-plate-width]').evaluate(element => Number(element.dataset.plateWidth) > 0), 'legacy default layout has a measured plate width');
    check(await page.locator('.selection-location').innerText().then(text => text.includes(`${units.filter(unit => !Object.hasOwn(unit, 'onset')).length} untimed`)), 'legacy default layout reports supplied timed inventory without inventing clocks');
    const pane = page.locator('section[aria-label="Recall Voyage"]');
    await pane.focus();
    for (const unit of units) {
      await page.keyboard.press('ArrowRight');
      await page.waitForFunction(id => document.querySelector('.page[data-focus]')?.dataset.focus === id, unit.id);
      check(true, `legacy keyboard traversal reaches canonical ordinal ${unit.ordinal}`);
    }
    for (const unit of [...units].reverse().slice(1)) {
      await page.keyboard.press('ArrowLeft');
      await page.waitForFunction(id => document.querySelector('.page[data-focus]')?.dataset.focus === id, unit.id);
      check(true, `legacy reverse traversal reaches canonical ordinal ${unit.ordinal}`);
    }
    await page.keyboard.press('ArrowLeft');
    check(await page.locator('.page[data-focus]').getAttribute('data-focus') === units[0].id, 'legacy first endpoint retains canonical selection');
    await page.locator('#voyage-unit').selectOption(untimed.id);
    await page.waitForFunction(id => document.querySelector('.page[data-focus]')?.dataset.focus === id, untimed.id);
    check((await page.locator('.inspector .quote').textContent()) === `“${untimed.text}”`, 'legacy untimed inspector quote is exact producer text');
    check((await page.locator('.selection-location').innerText()).includes('untimed'), 'legacy untimed selection states that no clock position is inferred');
    check(await page.getByRole('button',{name:'Reveal selected',exact:true}).isDisabled(), 'legacy untimed selection cannot fabricate a reveal clock position');
    if (await page.locator('#voyage-range-start').count()) {
      const before = await page.locator('.page[data-range-start]').evaluate(element => [element.dataset.rangeStart, element.dataset.rangeEnd]);
      await page.getByRole('button',{name:'Whole recall',exact:true}).click();
      const after = await page.locator('.page[data-range-start]').evaluate(element => [element.dataset.rangeStart, element.dataset.rangeEnd]);
      assert.deepEqual(after, before);
      check(true, 'legacy recall-range controls leave the untimed canonical selection without a fabricated clock');
    }
    check(report.errors.length === 0, 'legacy route has no browser or console errors');
    check(report.requests.length === 0, 'legacy route makes no HTTP(S) requests');
    report.exit = 0;
  } catch (error) {
    report.exit = 1; report.failure = String(error.stack || error); process.exitCode = 1;
  } finally {
    if (context) await context.close();
    if (browser) await browser.close();
    fs.writeFileSync(path.join(output, 'legacy-workspace-browser.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({checks:report.checks.length, exit:report.exit, failure:report.failure || null}));
  }
})();

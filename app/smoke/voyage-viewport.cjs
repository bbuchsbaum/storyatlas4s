#!/usr/bin/env node
// Real viewport acceptance over generated nn03-full/ and nn03-content/ editions.
// Requires the exact project Playwright browser; all pages/artifacts remain local.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const modules = path.resolve(__dirname, '../../e2e/static');
assert.equal(require(require.resolve('playwright/package.json', { paths: [modules] })).version, '1.55.1');
const { chromium } = require(require.resolve('playwright', { paths: [modules] }));
const out = path.resolve(process.argv[2]);
const report = { checks: [], errors: [], requests: [] };
function check(ok, message) { assert.ok(ok, message); report.checks.push(message); }
const number = value => Buffer.from(value.slice(2), 'hex').readDoubleBE();
const file = (arm, name = 'voyage.html') => pathToFileURL(path.join(out, arm, name)).href;
const visible = page => page.locator('.plate .voyage-anchor, .plate .voyage-unanchored').evaluateAll(es => es.map(e => Number(e.dataset.unit)).sort((a,b) => a-b));
const selected = page => page.locator('.page[data-focus]').getAttribute('data-focus');
async function range(page) {
  return page.locator('.page[data-range-start]').evaluate(e => [Number(e.dataset.rangeStart), Number(e.dataset.rangeEnd)]);
}
async function expectRange(page, start, end) {
  await page.waitForFunction(([a,b]) => {
    const e = document.querySelector('.page[data-range-start]');
    return Math.abs(Number(e.dataset.rangeStart)-a) < 0.11 && Math.abs(Number(e.dataset.rangeEnd)-b) < 0.11;
  }, [start,end]);
}
async function apply(page, a, b) {
  await page.getByLabel('Detail start (m:ss)').fill(a);
  await page.getByLabel('Detail end (m:ss)').fill(b);
  await page.getByRole('button', { name: 'Apply range', exact: true }).click();
}
async function separatedClockLabels(page) {
  return page.locator('.plate svg text').evaluateAll(es => {
    const clocks = es.filter(e => /^\d+:\d/.test(e.textContent)).map(e => e.getBoundingClientRect());
    const bottom = Math.max(...clocks.map(b => b.y));
    const labels = clocks.filter(b => Math.abs(b.y-bottom)<1).sort((a,b)=>a.x-b.x);
    return labels.length>=2 && labels.every((b,i)=>i===0 || b.x>=labels[i-1].right+3);
  });
}
function observe(page) {
  page.on('pageerror', e => report.errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') report.errors.push(m.text()); });
  page.on('request', r => { if (/^https?:/.test(r.url())) report.requests.push(r.url()); });
}
(async () => {
  let browser;
  try {
    browser = await chromium.launch({ headless: true });
    const desktop = await browser.newContext({ viewport: { width:1600, height:1100 } });
    try {
      const page = await desktop.newPage(); observe(page);
      for (const arm of ['nn03-full', 'nn03-content']) {
        await page.goto(file(arm)); await page.locator('.plate svg').waitFor();
        const doc = JSON.parse(await page.locator('#voyage-document').textContent());
        const extent = number(doc.recallLength);
        const yBefore = await page.locator('.plate .voyage-anchor').evaluateAll(es => Object.fromEntries(es.map(e => { const b=e.getBBox();return [e.dataset.unit,b.y+b.height/2]; })));
        await page.getByLabel('Inspect recall unit').selectOption(doc.units[5].id);
        await apply(page,'4:00','10:00'); await expectRange(page,240,600);
        check(await selected(page) === doc.units[5].id, `${arm}: range change preserves off-window selection`);
        check((await page.locator('.selection-location').innerText()).includes('outside'), `${arm}: off-window selection explained`);
        const expected = doc.units.filter(u => u.onset && number(u.onset)>=240 && number(u.onset)<=600).map(u=>u.ordinal).sort((a,b)=>a-b);
        assert.deepEqual(await visible(page), expected); check(true, `${arm}: exact window inventory, no points clamped to edges`);
        check(await page.locator('.plate [data-unit="5"]').count() === 0, `${arm}: off-window anchor and alternatives absent`);
        const yAfter = await page.locator('.plate .voyage-anchor').evaluateAll(es => Object.fromEntries(es.map(e => { const b=e.getBBox();return [e.dataset.unit,b.y+b.height/2]; })));
        check(Object.entries(yAfter).every(([k,v])=>Math.abs(v-yBefore[k])<1e-5), `${arm}: source coordinates unchanged by recall zoom`);
        const ticks = await page.locator('.plate svg text').allTextContents();
        check(ticks.includes('4:00') && ticks.includes('10:00'), `${arm}: main axis labels the actual 4–10 minute window`);
        check(await separatedClockLabels(page), `${arm}: desktop time labels do not overlap`);
        const brushGeometry = await page.locator('.recall-overview').evaluate(el => {
          const b=el.getBoundingClientRect(), w=el.querySelector('.brush-window').getBoundingClientRect();return {start:(w.x-b.x)/b.width,span:w.width/b.width};
        });
        check(Math.abs(brushGeometry.start-240/extent)<0.005 && Math.abs(brushGeometry.span-360/extent)<0.005, `${arm}: overview brush agrees with detail`);
        await page.screenshot({path:path.join(out,arm,'viewport-desktop.png'),fullPage:true});
        await page.getByLabel('Detail start (m:ss)').press('ArrowRight');
        check(await selected(page)===doc.units[5].id, `${arm}: text-field arrows do not traverse recall`);
        await apply(page,'10:00','4:00');
        check(await page.locator('.range-error').count()===1, `${arm}: reversed range refused`);
        assert.deepEqual(await range(page),[240,600]);
        await apply(page,'0','999999');
        check(await page.locator('.range-error').count()===1, `${arm}: range beyond supplied extent refused`);
        assert.deepEqual(await range(page),[240,600]);
        await page.getByRole('button',{name:'Reveal selected',exact:true}).click();
        check((await visible(page)).includes(5), `${arm}: explicit Reveal restores selected mark`);
        check(await selected(page)===doc.units[5].id, `${arm}: Reveal preserves identity`);
        await page.getByRole('button',{name:'Whole recall',exact:true}).click(); await expectRange(page,0,extent);
        check((await visible(page)).length===173, `${arm}: Whole recall restores complete timed inventory`);
        // Pointer brush computes the same domain as the keyboard-accessible range fields.
        const box=await page.locator('.recall-overview').boundingBox();
        await page.mouse.move(box.x+box.width*240/extent,box.y+box.height/2);
        await page.mouse.down();
        await page.mouse.move(box.x+box.width*600/extent,box.y+box.height/2,{steps:8});
        check((await range(page))[0]===0, `${arm}: brush preview does not change committed viewport`);
        await page.mouse.up(); await expectRange(page,240,600);
        check(await selected(page)===doc.units[5].id, `${arm}: pointer brush leaves semantic selection intact`);
        await page.getByRole('button',{name:'Later',exact:true}).click(); await expectRange(page,420,780);
        await page.getByRole('button',{name:'Earlier',exact:true}).click(); await expectRange(page,240,600);
        check(true, `${arm}: keyboard pan controls preserve span`);
        await page.getByRole('button',{name:'Zoom in',exact:true}).click(); await expectRange(page,330,510);
        await page.getByRole('button',{name:'Zoom out',exact:true}).click(); await expectRange(page,240,600);
        check(true, `${arm}: zoom controls preserve center`);
        const last = expected.at(-1);
        const boundaryMark=page.locator(`.plate .voyage-anchor[data-unit="${last}"] [data-name]`);
        await boundaryMark.focus();
        check(await boundaryMark.evaluate(e=>document.activeElement===e),`${arm}: boundary test begins on the keyboard-focusable mark`);
        await page.keyboard.press('Enter');
        await page.keyboard.press('ArrowRight');
        check(await selected(page)===doc.units[last+1].id,`${arm}: keyboard crosses visible boundary`);
        await page.keyboard.press('ArrowRight');
        check(await selected(page)===doc.units[last+2].id,`${arm}: stable panel focus keeps off-window traversal working`);
        await expectRange(page,240,600);
        await page.getByLabel('Inspect recall unit').selectOption(doc.units[5].id);
        await page.setViewportSize({width:390,height:844});
        await page.waitForFunction(()=>Number(document.querySelector('.page[data-plate-width]').dataset.plateWidth)<390);
        await expectRange(page,240,600);
        check(await selected(page)===doc.units[5].id, `${arm}: compact resize preserves range and selection`);
        check(await page.evaluate(()=>document.documentElement.scrollWidth<=390),`${arm}: no page-level horizontal overflow`);
        const textPx = await page.locator('.plate svg text').first().evaluate(e=>{const scale=e.ownerSVGElement.getBoundingClientRect().width/e.ownerSVGElement.viewBox.baseVal.width;return parseFloat(getComputedStyle(e).fontSize)*scale;});
        check(textPx>=10.4, `${arm}: plot labels retain their CSS pixel size`);
        check(await separatedClockLabels(page), `${arm}: compact time labels do not overlap`);
        await page.screenshot({path:path.join(out,arm,'viewport-compact.png'),fullPage:true});
        await page.setViewportSize({width:1600,height:1100});
      }
      // The preceding display regression creates this explicitly synthetic checked document.
      await page.goto(file('nn03-full','synthetic-legend-witness.html')); await page.locator('.plate svg').waitFor();
      const doc=JSON.parse(await page.locator('#voyage-document').textContent());
      await apply(page,'4:00','10:00'); await expectRange(page,240,600);
      check(!(await page.locator('.legend-row').innerText()).includes('independent coding:'),'off-window coding does not leave a misleading legend entry');
      await page.getByLabel('Inspect recall unit').selectOption(doc.units[0].id);
      check((await page.locator('.inspector .quote').textContent())===`“${doc.units[0].text}”`, 'untimed unit remains inspectable through canonical inventory');
      check(await page.getByRole('button',{name:'Reveal selected',exact:true}).isDisabled(), 'untimed selection never receives a fabricated clock position');
      check((await page.locator('.selection-location').innerText()).includes('untimed'), 'untimed state is explicit');
      assert.deepEqual(await range(page),[240,600]);check(true,'untimed selection does not move the viewport');
    } finally { await desktop.close(); }
    const compact = await browser.newContext({ viewport:{width:390,height:844},hasTouch:true,isMobile:true });
    try {
      const page=await compact.newPage();observe(page);
      await page.goto(file('nn03-full'));await page.locator('.plate svg').waitFor();
      await expectRange(page,0,120); check(true,'fresh compact visit starts with two-minute detail');
      check(!(await page.locator('.voyage-key').evaluate(e=>e.open)), 'compact Key initially collapsed');
      check((await visible(page)).length<173,'compact detail has fewer onsets than the full inventory');
      await page.getByRole('button',{name:'Later',exact:true}).tap();await expectRange(page,60,180);
      check(true,'touch can pan through the same viewport controls');
      await page.screenshot({path:path.join(out,'nn03-full','viewport-compact-initial.png'),fullPage:true});
    } finally { await compact.close(); }
    check(report.errors.length===0,'no page or console errors');
    check(report.requests.length===0,'no external network requests');
  } catch(e) { report.failure=String(e);process.exitCode=1; }
  finally { if(browser)await browser.close();fs.writeFileSync(path.join(out,'viewport-browser.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report)); }
})();

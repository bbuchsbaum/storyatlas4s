#!/usr/bin/env node
// Run voyage-review.cjs first to create its checked coding/missing/untimed witness.
// node app/smoke/voyage-disagreement.cjs <edition-directory>
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {pathToFileURL} = require('node:url');
const modules = path.resolve(__dirname, '../../e2e/static');
assert.equal(require(require.resolve('playwright/package.json', {paths:[modules]})).version, '1.55.1');
const {chromium} = require(require.resolve('playwright', {paths:[modules]}));
const out = path.resolve(process.argv[2]);
const report = {checks:[], counts:{}, errors:[], requests:[]};
const check = (ok, message) => { assert.ok(ok, message); report.checks.push(message); };
const number = v => Buffer.from(v.slice(2), 'hex').readDoubleBE();
const key = ref => `${ref.type==='Situation'?'sit':'seg'}:${ref.id}`;
const file = (arm,name='voyage.html') => pathToFileURL(path.join(out,arm,name)).href;

// Independent expected answer from the admitted document, including the source
// argmax's mass/key tie-break. Missing legacy mark payloads remain unknown.
function expected(doc) {
  return doc.units.map(u=>{
    const d=doc.decisions.find(d=>d.unit===u.id), row=doc.rows.find(r=>r.unit===u.id);
    const ranked=row.mass.filter(m=>m.state.type==='Source' && number(m.mass)>0)
      .sort((a,b)=>number(b.mass)-number(a.mass) || (key(a.state.ref)<key(b.state.ref)?-1:1));
    const available=!!u.onset && !!d.anchor;
    const drawn=available ? d.group??null : null;
    const argmax=available && ranked.length ? doc.timeline.nodes.find(n=>key(n.ref)===key(ranked[0].state.ref)).group??null : null;
    return {id:u.id,unit:u.ordinal,onset:u.onset??null,drawn,argmax,
      status:drawn===null || argmax===null ? 'unknown' : drawn===argmax ? 'agreement' : 'disagreement'};
  });
}
const mainMarks = page => page.locator('.plate [data-name]').evaluateAll(es=>es.map(e=>e.outerHTML));
async function verify(page,doc,start,end,label) {
  const records=expected(doc), shown=records.filter(r=>r.onset && number(r.onset)>=start && number(r.onset)<=end && r.status!=='agreement');
  const actual=await page.locator('.plate .voyage-group-comparison').evaluateAll(es=>es.map(e=>({
    id:e.dataset.comparisonUnit,unit:Number(e.dataset.unit),onset:e.dataset.onsetBits,
    drawn:e.hasAttribute('data-drawn-group')?Number(e.dataset.drawnGroup):null,
    argmax:e.hasAttribute('data-argmax-group')?Number(e.dataset.argmaxGroup):null,status:e.dataset.status
  })));
  assert.deepEqual(actual,shown);
  check(true,`${label}: only supplied disagreements and timed unknowns; exact groups and onsets`);
  const counts=Object.fromEntries(['disagreement','agreement','unknown'].map(s=>[s,records.filter(r=>r.status===s).length]));
  check(Object.values(counts).reduce((a,b)=>a+b,0)===doc.units.length,`${label}: every unit accounted for`);
  const summary=await page.locator('.group-comparison-summary').innerText();
  check(summary.includes(`${counts.disagreement} different groups`) && summary.includes(`${counts.agreement} same group`) && summary.includes(`${counts.unknown} unknown`),`${label}: whole-recall accounting remains visible`);
  check(summary.includes('order, not time or distance'),`${label}: ordinal spacing declared`);
  const names=await page.locator('.plate [data-name]').evaluateAll(es=>es.map(e=>e.dataset.name));
  check(new Set(names).size===names.length,`${label}: no duplicate scientific mark identities`);
  check(await page.locator('.plate .voyage-mass').count()===0,`${label}: comparison replaces only the auxiliary mass tracks`);
  const legend=await page.locator('.legend-row').textContent();
  check(legend.includes('group disagreement:')===shown.some(r=>r.status==='disagreement'),`${label}: disagreement key follows visible content`);
  check(legend.includes('group unknown:')===shown.some(r=>r.status==='unknown'),`${label}: unknown key follows visible content`);
  report.counts[label]=counts;
  return shown;
}
(async()=>{
  let browser,context;
  try {
    browser=await chromium.launch({headless:true});
    context=await browser.newContext({viewport:{width:1600,height:1100}});
    const page=await context.newPage();
    page.on('pageerror',e=>report.errors.push(String(e)));
    page.on('console',m=>{if(m.type()==='error')report.errors.push(m.text());});
    page.on('request',r=>{if(/^https?:/.test(r.url()))report.requests.push(r.url());});
    for(const arm of ['nn03-full','nn03-content']) {
      await page.goto(file(arm));await page.locator('.plate svg').waitFor();
      const doc=JSON.parse(await page.locator('#voyage-document').textContent());
      const selector=page.getByLabel('Under-plot track');
      check(await selector.inputValue()==='mass',`${arm}: mass tracks still default`);
      const before=await mainMarks(page);
      await selector.selectOption('groups');
      assert.deepEqual(await mainMarks(page),before);
      check(true,`${arm}: track change preserves every primary mark and supplied geometry`);
      const shown=await verify(page,doc,0,number(doc.recallLength),`${arm} whole`);
      check(!(await page.locator('.legend-row').textContent()).includes('independent coding:'),`${arm}: no absent coding key`);
      const selected=shown.find(r=>r.unit===5);
      assert.ok(selected,'unit5 has a group disagreement');
      await page.locator('.voyage-group-comparison[data-unit="5"]').click();
      check(await page.locator('.page').getAttribute('data-focus')===selected.id,`${arm}: comparison click selects exact recall unit`);
      check(await page.locator('.voyage-group-comparison[data-unit="5"]').getAttribute('aria-pressed')==='true',`${arm}: selected comparison has accessible state`);
      check((await page.locator('.selected-group-comparison').innerText()).includes('Different groups.'),`${arm}: inspector explains selected group comparison`);
      const other=shown.find(r=>r.unit!==5);
      await page.locator(`.voyage-group-comparison[data-unit="${other.unit}"]`).focus();await page.keyboard.press('Enter');
      check(await page.locator('.page').getAttribute('data-focus')===other.id,`${arm}: keyboard comparison selection works`);
      await page.getByLabel('Inspect recall unit').selectOption(selected.id);
      await page.getByLabel('Detail start (m:ss)').fill('4:00');
      await page.getByLabel('Detail end (m:ss)').fill('10:00');
      await page.getByRole('button',{name:'Apply range',exact:true}).click();
      await verify(page,doc,240,600,`${arm} detail`);
      const selection=await page.locator('.page').getAttribute('data-focus'), detailMarks=await mainMarks(page);
      await selector.selectOption('mass');await selector.selectOption('groups');
      assert.deepEqual(await mainMarks(page),detailMarks);
      check(await page.locator('.page').getAttribute('data-focus')===selection,`${arm}: track roundtrip keeps off-window selection`);
      await page.screenshot({path:path.join(out,arm,'disagreement-desktop.png'),fullPage:true});
      await page.setViewportSize({width:390,height:844});
      await page.waitForFunction(()=>Number(document.querySelector('.page').dataset.plateWidth)<390);
      await verify(page,doc,240,600,`${arm} compact`);
      check(await page.evaluate(()=>document.documentElement.scrollWidth<=390),`${arm}: compact view does not overflow`);
      await page.screenshot({path:path.join(out,arm,'disagreement-compact.png'),fullPage:true});
      await page.setViewportSize({width:1600,height:1100});
    }
    await page.goto(file('nn03-full','synthetic-legend-witness.html'));await page.locator('.plate svg').waitFor();
    const doc=JSON.parse(await page.locator('#voyage-document').textContent());
    await page.getByLabel('Under-plot track').selectOption('groups');
    const shown=await verify(page,doc,0,number(doc.recallLength),'synthetic coding/untimed/unanchored');
    check(shown.every(r=>r.unit!==0),'untimed unit has no invented comparison coordinate');
    check(shown.find(r=>r.unit===1)?.status==='unknown','unanchored unit is unknown, not agreement');
    check(await page.locator('.voyage-coding').count()===1 && (await page.locator('.legend-row').textContent()).includes('independent coding:'),'independent coding remains a distinct main-plot layer');
    await page.locator('.voyage-group-comparison[data-unit="1"]').click();
    check(await page.locator('.page').getAttribute('data-focus')===doc.units[1].id,'unknown comparison also selects its unit');
    check((await page.locator('.selected-group-comparison').textContent()).includes('unknown'),'inspector explains unknown group comparison');
    await page.getByLabel('Inspect recall unit').selectOption(doc.units[0].id);
    check((await page.locator('.inspector').innerText()).includes('no recall onset'),'untimed comparison remains inspectable through canonical navigation');
    check(report.errors.length===0,'no page or console errors');
    check(report.requests.length===0,'no external requests');
  } catch(e) {report.failure=String(e);process.exitCode=1;}
  finally {
    if(context)await context.close();if(browser)await browser.close();
    fs.writeFileSync(path.join(out,'disagreement-browser.json'),JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify(report));
  }
})();

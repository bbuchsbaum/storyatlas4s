#!/usr/bin/env node
// Real Sherlock fixed-scale mass tracks. Run voyage-review.cjs first for its checked
// synthetic missing/untimed witness. node app/smoke/voyage-mass.cjs <edition-directory>
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {pathToFileURL} = require('node:url');
const modules = path.resolve(__dirname, '../../e2e/static');
assert.equal(require(require.resolve('playwright/package.json', {paths:[modules]})).version, '1.55.1');
const {chromium} = require(require.resolve('playwright', {paths:[modules]}));
const out = path.resolve(process.argv[2]);
const report = {checks:[], errors:[], requests:[]};
const check = (ok, message) => { assert.ok(ok, message); report.checks.push(message); };
const number = value => Buffer.from(value.slice(2), 'hex').readDoubleBE();
const close = (a,b) => Math.abs(a-b)<1e-12;
// SVG's deterministic renderer rounds coordinates to four decimals; browser
// getBBox adds float32 error. This tolerance never applies to supplied masses.
const geometryClose = (a,b) => Math.abs(a-b)<0.0002;
const file = (arm, name='voyage.html') => pathToFileURL(path.join(out,arm,name)).href;
async function samples(page) {
  const raw=await page.locator('.plate .voyage-mass').evaluateAll(es=>es.map(e=>{
    const bar=e.querySelector('polygon'), tick=e.querySelector('polyline');
    const b=(bar||tick||e).getBBox();
    return {unit:Number(e.dataset.unit),channel:e.dataset.channel,status:e.dataset.status,
      mass:e.hasAttribute('data-mass-bits')?e.dataset.massBits:null, origin:e.dataset.origin,
      x:b.x+b.width/2,height:b.height,bar:!!bar};
  }));
  return raw.map(s=>({...s,mass:s.mass===null?null:number(s.mass)}));
}
function expected(doc,start,end) {
  return doc.units.filter(u=>u.onset && number(u.onset)>=start && number(u.onset)<=end).flatMap(u=>{
    const decision=doc.decisions.find(d=>d.unit===u.id), row=doc.rows.find(r=>r.unit===u.id);
    const anchor=decision.anchor ? row.mass.filter(m=>m.state.type==='Source' &&
      m.state.ref.type===decision.anchor.type && m.state.ref.id===decision.anchor.id)
      .reduce((a,m)=>a+number(m.mass),0) : null;
    const external=row.mass.filter(m=>m.state.type==='External').reduce((a,m)=>a+number(m.mass),0);
    return [['mass-anchor',anchor],['mass-external',external]].map(([channel,mass])=>({unit:u.ordinal,channel,mass}));
  });
}
async function verify(page,doc,start,end,label) {
  const actual=await samples(page), supplied=expected(doc,start,end);
  assert.deepEqual(actual.map(s=>[s.unit,s.channel]),supplied.map(s=>[s.unit,s.channel]));
  check(true,`${label}: exact timed unit inventory on both tracks`);
  check(actual.every((s,i)=>s.mass===null ? supplied[i].mass===null : close(s.mass,supplied[i].mass)),`${label}: masses agree with independent document accounting`);
  check(actual.every(s=>s.mass===null ? s.status==='unavailable' : s.status===(s.mass===0?'zero':'measured')),`${label}: missing and measured zero remain distinct`);
  // The lowerer declares two 28px rows, even after compact reflow. Selection is
  // an extra outline; inspect the first (quantity) shape, not the group's extent.
  check(actual.filter(s=>s.mass>0).every(s=>s.bar && geometryClose(s.height,28*s.mass)),`${label}: independent fixed 0–1 heights without row normalization`);
  check(actual.filter(s=>s.mass===0).every(s=>!s.bar && close(s.height,0)),`${label}: zero has no positive area`);
  const marks=await page.locator('.plate .voyage-anchor, .plate .voyage-unanchored').evaluateAll(es=>Object.fromEntries(es.map(e=>{
    const b=e.getBBox();return [e.dataset.unit,b.x+b.width/2];
  })));
  check(actual.every(s=>geometryClose(s.x,marks[s.unit])),`${label}: tracks and route share exact recall x coordinates`);
  const scales=await page.locator('.plate svg text').evaluateAll(es=>es
    .filter(e=>/^[01]$/.test(e.textContent)).map(e=>{
      const b=e.getBoundingClientRect();return {top:b.top,bottom:b.bottom};
    }).sort((a,b)=>a.top-b.top));
  check(scales.length===4 && scales.every((b,i)=>i===0 || b.top>=scales[i-1].bottom+1),`${label}: both 0–1 scales have readable separated labels`);
  const named=await page.locator('.plate [data-name]').evaluateAll(es=>es.map(e=>e.dataset.name));
  check(new Set(named).size===named.length,`${label}: auxiliary tracks add no duplicate scientific identities`);
  return actual;
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
      await verify(page,doc,0,number(doc.recallLength),`${arm} whole`);
      const stats=await page.locator('.stats').innerText();
      check(/zero-mass fills\s+44/i.test(stats),`${arm}: fills have an explicit summary count`);
      check(/unanchored\s+0/i.test(stats),`${arm}: zero-count outcomes remain accounted for`);
      await page.getByLabel('Inspect recall unit').selectOption(doc.units[5].id);
      const fill=await page.locator('.mass-anchor[data-unit="5"]').getAttribute('data-origin');
      check(fill==='filled',`${arm}: unit 5 keeps decode-filled origin with zero mass`);
      await page.screenshot({path:path.join(out,arm,'mass-desktop.png'),fullPage:true});
      await page.getByLabel('Detail start (m:ss)').fill('4:00');
      await page.getByLabel('Detail end (m:ss)').fill('10:00');
      await page.getByRole('button',{name:'Apply range',exact:true}).click();
      await verify(page,doc,240,600,`${arm} detail`);
      await page.setViewportSize({width:390,height:844});
      await page.waitForFunction(()=>Number(document.querySelector('.page').dataset.plateWidth)<390);
      await verify(page,doc,240,600,`${arm} compact`);
      check(await page.evaluate(()=>document.documentElement.scrollWidth<=390),`${arm}: compact tracks cause no page overflow`);
      await page.screenshot({path:path.join(out,arm,'mass-compact.png'),fullPage:true});
      await page.setViewportSize({width:1600,height:1100});
    }
    await page.goto(file('nn03-full','synthetic-legend-witness.html'));await page.locator('.plate svg').waitFor();
    const doc=JSON.parse(await page.locator('#voyage-document').textContent());
    const actual=await verify(page,doc,0,number(doc.recallLength),'synthetic missing/untimed');
    check(actual.every(s=>s.unit!==0),'untimed unit receives no fabricated track x');
    check(actual.find(s=>s.unit===1 && s.channel==='mass-anchor').mass===null,'unanchored source mass remains unavailable');
    check(actual.find(s=>s.unit===1 && s.channel==='mass-external').mass===1,'unanchored external mass remains measured one');
    check(report.errors.length===0,'no page or console errors');
    check(report.requests.length===0,'no external network requests');
  } catch(e) {report.failure=String(e);process.exitCode=1;}
  finally {
    if(context)await context.close();if(browser)await browser.close();
    fs.writeFileSync(path.join(out,'mass-browser.json'),JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify(report));
  }
})();

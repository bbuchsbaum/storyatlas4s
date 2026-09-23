#!/usr/bin/env node
// Exact-pair M1 browser court. Only producer-generated redistributable/synthetic fixtures.
// node app/smoke/workspace.cjs target/edition/index.html <producer-fixtures-dir> <evidence-dir> <generated-feature-dir>
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const {pathToFileURL} = require('node:url');
const modules = path.resolve(__dirname, '../../e2e/static');
const pkg = require(require.resolve('playwright/package.json', {paths:[modules]}));
assert.equal(pkg.version, '1.55.1');
const {chromium} = require(require.resolve('playwright', {paths:[modules]}));
const [entry, fixtures, output, featureDir] = process.argv.slice(2).map(p => path.resolve(p));
fs.mkdirSync(output, {recursive:true});
const report = {checks:[], errors:[], requests:[], playwright:pkg.version};
const check = (ok, description) => { report.checks.push({ok, description}); assert.ok(ok, description); };
const sha = x => createHash('sha256').update(x).digest('hex');
const load = name => fs.readFileSync(path.join(fixtures, name), 'utf8');
const input = (name, text) => ({name, mimeType:'application/json', buffer:Buffer.from(text)});
const canonical = x => JSON.stringify(sort(x));
function sort(x) { return Array.isArray(x) ? x.map(sort) : x && typeof x==='object' ? Object.fromEntries(Object.keys(x).sort().map(k=>[k,sort(x[k])])) : x; }
function replace(archive, kind, text) {
  const result = JSON.parse(JSON.stringify(archive));
  const e = result.entries.find(e=>e.role.kind===kind);
  result.files.find(f=>f.path===e.path).utf8 = text;
  Object.assign(e.disposition.artifact,{checksum:sha(text),byteLength:Buffer.byteLength(text)});
  return result;
}
function member(a,kind) { const e=a.entries.find(e=>e.role.kind===kind); return a.files.find(f=>f.path===e.path).utf8; }
(async()=>{
  let browser, context, page;
  try {
    browser = await chromium.launch({headless:true});
    context = await browser.newContext({viewport:{width:1600,height:1100},acceptDownloads:true});
    page = await context.newPage();
    page.on('pageerror',e=>report.errors.push(String(e)));
    page.on('console',m=>{if(m.type()==='error')report.errors.push(m.text());});
    page.on('request',r=>{if(/^https?:/.test(r.url()))report.requests.push(r.url());});
    await page.goto(pathToFileURL(entry).href);
    await page.locator('.shell[data-state=ready]').waitFor();
    const state = async()=>JSON.parse(await page.locator('#workspace-state').textContent());
    const open = async(files, status='Opened')=>{
      await page.locator('#workspace-open').setInputFiles(files);
      await page.waitForFunction(s=>document.querySelector('[data-open-status]')?.getAttribute('data-open-status')?.startsWith(s), status);
    };
    const download = async(button,name)=>{
      const waiting=page.waitForEvent('download'); await page.locator(button).click();
      const item=await waiting; const file=path.join(output,name); await item.saveAs(file); return fs.readFileSync(file,'utf8');
    };
    for(const name of ['bell','wog']) {
      await open(input('arbitrary-input',load(`${name}.workspace.json`)));
      await page.locator('[data-workspace-ready=true] .shell[data-state=ready]').waitFor();
      check(await page.locator('.mapping-matrix tbody tr').count()===4,`${name}: all four outcomes rendered`);
      check(await page.locator('#fixture-header').getAttribute('hidden')!==null,`${name}: old fixture authority removed`);
      for(let ordinal=0;ordinal<4;ordinal++) {
        await page.getByRole('button',{name:'Next recall',exact:true}).click();
        check((await state()).activeRecall===`m1:u${ordinal}`,`${name}: canonical walk reaches ordinal${ordinal}`);
      }
      await page.locator('#workspace-recall').selectOption('m1:u0');
      const selected=(await state()).selection;
      await page.getByRole('button',{name:'Source',exact:true}).click();
      assert.deepEqual((await state()).selection,selected); check(true,`${name}: source projection retains selection`);
      await page.getByRole('button',{name:'Recall',exact:true}).click();
      const first = await page.locator('[data-matrix-cell="0-0"]').innerText();
      await page.locator('#workspace-policy').selectOption('authored-b');
      check((await state()).policy==='authored-b',`${name}: checked policy switched`);
      assert.deepEqual((await state()).selection,selected);
      const exported=JSON.parse(await download('#workspace-export',`${name}-export.json`));
      assert.equal(exported.schemaVersion,'storyatlas-evidence-export/v1');
      const files=Object.fromEntries(exported.files.map(f=>{
        assert.equal(sha(f.content),f.checksum); assert.equal(Buffer.byteLength(f.content),f.byteLength); return [f.name,f.content];
      }));
      for(const [actual,suffix] of [['selection.json','json'],['selection.csv','csv'],['selection.txt','txt'],['selection-receipt.json','receipt.json']])
        assert.equal(files[actual],load(`${name}-authored-b-u0.${suffix}`));
      check(true,`${name}: independent producer subset/twin/receipt byte parity and export hashes`);
      const beforeSave=await state();
      const saved=await download('#workspace-save',`${name}-investigation.json`);
      await page.getByRole('button',{name:'Clear selection',exact:true}).click();
      await open(input('saved-state',saved)); assert.deepEqual(await state(),beforeSave);
      check(true,`${name}: save and exact-artifact reopen reproduces full state`);
      await page.locator('#workspace-policy').selectOption('authored-a');
      assert.equal(await page.locator('[data-matrix-cell="0-0"]').innerText(),first);
      check(true,`${name}: switching back restores exact original values`);
      await page.screenshot({path:path.join(output,`${name}-desktop.png`),fullPage:true});
    }
    await open(input('bell',load('bell.workspace.json')));
    await page.locator('#workspace-recall').selectOption('m1:u0');
    await page.locator('[data-matrix-cell="0-0"]').focus();
    await page.keyboard.press('ArrowDown');
    check((await state()).activeRecall==='m1:u1','matrix arrow key changes canonical row');
    check(await page.evaluate(()=>document.activeElement?.matches('[data-matrix-cell="1-0"]')),'matrix keeps visible keyboard focus');
    const stable=await state();
    const bell=JSON.parse(load('bell.workspace.json')), wog=JSON.parse(load('wog.workspace.json'));
    await open(input('foreign',canonical(replace(bell,'Recall',member(wog,'Recall')))),'Refused');
    assert.deepEqual(await state(),stable); check(true,'foreign recall refuses before replacing current investigation');
    const caps=JSON.parse(member(bell,'Capabilities')); caps.inspection='Denied';
    await open(input('denied',canonical(replace(bell,'Capabilities',canonical(caps)))),'Refused');
    assert.deepEqual(await state(),stable); check(true,'re-signed inspection denial preserves current state');
    const savedForeign=fs.readFileSync(path.join(output,'wog-investigation.json'),'utf8');
    await open(input('stale-state',savedForeign),'Refused');
    assert.deepEqual(await state(),stable); check(true,'foreign saved state refuses');
    await page.locator('#workspace-policy').selectOption('historical-lexical');
    await page.getByRole('button',{name:'Voyage',exact:true}).click();
    await page.locator('.joined-workspace .plate svg').waitFor(); // Must draw the real controlled projection.
    await page.locator('#workspace-recall').selectOption('m1:u3');
    check((await state()).activeRecall==='m1:u3','untimed unit stays reachable in Voyage');
    await page.getByRole('button',{name:'Recall',exact:true}).click();
    for(const [label,width,zoom] of [['mobile',390,1],['zoom200',1600,2]]) {
      await page.setViewportSize({width,height:1000});
      await page.evaluate(z=>document.body.style.zoom=String(z),zoom);
      check(await page.evaluate(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth+2),`${label}: no document horizontal overflow`);
      await page.screenshot({path:path.join(output,`${label}.png`),fullPage:true});
    }
    assert.ok(featureDir, 'pass the generated feature directory as argument4');
    const featureExpected=JSON.parse(fs.readFileSync(featureDir+'.expected.json','utf8'));
    await page.locator('#workspace-open-directory').setInputFiles(featureDir);
    await page.locator('#source-features').waitFor();
    assert.equal(await page.locator('#source-features').getAttribute('data-track-count'),String(featureExpected.trackCount));
    assert.deepEqual(await page.locator('.source-feature-space').allTextContents(),featureExpected.spaces);
    check(true,'directory preserves exact nested sidecars and agrees with filesystem consumer');
    const incomplete=path.join(output,'incomplete-feature-bundle');
    fs.cpSync(featureDir,incomplete,{recursive:true,errorOnExist:true});
    const featureRecord=JSON.parse(fs.readFileSync(path.join(incomplete,'features.json'),'utf8'));
    fs.unlinkSync(path.join(incomplete,featureRecord.tracks[0].file));
    await page.locator('#workspace-open-directory').setInputFiles(incomplete);
    await page.waitForFunction(()=>document.querySelector('[data-open-status]')?.getAttribute('data-open-status')?.startsWith('Refused'));
    assert.equal(await page.locator('#source-features').getAttribute('data-track-count'),String(featureExpected.trackCount));
    check(true,'missing sidecar refuses and retains the previous admitted source');
    check(report.errors.length===0,'no browser or console errors');
    check(report.requests.length===0,'no network request from the local workspace');
    report.exit=0;
  } catch(error) {
    report.exit=1; report.failure=String(error.stack||error);
    if(page) { await page.screenshot({path:path.join(output,'failure.png'),fullPage:true}).catch(()=>{}); fs.writeFileSync(path.join(output,'failure.html'),await page.content().catch(()=>'')); }
    process.exitCode=1;
  } finally {
    if(context)await context.close(); if(browser)await browser.close();
    fs.writeFileSync(path.join(output,'browser.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify({checks:report.checks.length,exit:report.exit,failure:report.failure||null}));
  }
})();

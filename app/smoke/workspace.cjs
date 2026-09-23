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
function mappingMember(a,id) {
  const e=a.entries.find(e=>e.role.kind==='Mapping' && e.role.id===id);
  return e && a.files.find(f=>f.path===e.path).utf8;
}
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
    const setRange = async(selector,value)=>{
      await page.locator(selector).evaluate((element,next)=>{
        element.value=String(next);
        element.dispatchEvent(new Event('input',{bubbles:true}));
      },value);
    };
    const delayReaders = async(delays)=>page.evaluate(delays=>{
      const original=FileReader.prototype.readAsArrayBuffer;
      FileReader.prototype.readAsArrayBuffer=function(file) {
        window.setTimeout(()=>original.call(this,file),delays[file.name]||0);
      };
      window.__restoreWorkspaceReaders=()=>{FileReader.prototype.readAsArrayBuffer=original;};
    },delays);
    const restoreReaders = async()=>page.evaluate(()=>{
      if (window.__restoreWorkspaceReaders) {
        window.__restoreWorkspaceReaders();
        delete window.__restoreWorkspaceReaders;
      }
    });
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
      const metadata=page.locator('[data-inspection-metadata]');
      await page.waitForFunction(()=>document.querySelectorAll('[data-inspection-metadata]').length>0);
      const metadataText=()=>metadata.allTextContents().then(parts=>parts.join('\n'));
      const initialPolicy=(await state()).policy;
      const initialFixture=mappingMember(JSON.parse(load(`${name}.workspace.json`)),initialPolicy);
      const initialMetadata=await metadataText();
      for(const fragment of ['Candidate coverage','Fixed target universe','Normalization universe','prior:','temperature:'])
        check(initialMetadata.includes(fragment),`${name}: inspection metadata retains ${fragment}`);
      if(initialFixture.includes('Omitted candidate probability is unknown'))
        check(initialMetadata.includes('Omitted-candidate probability: unknown'),`${name}: non-complete candidate coverage stays explicitly unknown`);
      if(initialFixture.includes('"status":"not-computed"'))
        check(initialMetadata.includes('Term support: Not computed'),`${name}: authored term support stays not computed`);
      await page.locator('#workspace-policy').selectOption('historical-lexical');
      const historicalFixture=mappingMember(JSON.parse(load(`${name}.workspace.json`)),'historical-lexical');
      const historicalMetadata=await metadataText();
      if(historicalFixture.includes('"type":"NotApplicable"'))
        check(historicalMetadata.includes('Term support: NotApplicable'),`${name}: historical non-applicable term support remains distinct`);
      if(historicalFixture.includes('"type":"Assessed"'))
        check(historicalMetadata.includes('Term support: Assessed'),`${name}: historical assessed term support remains distinct`);
      await page.locator('#workspace-policy').selectOption(initialPolicy);
      const outcomes=JSON.parse(initialFixture).outcomes;
      const candidateKey=outcomes.find(o=>o.unit==='m1:u0').links.map(l=>l.destination)
        .find(key=>key.startsWith('sit:') && outcomes.filter(o=>o.links.some(l=>l.destination===key)).length>1);
      assert.ok(candidateKey,`${name}: producer supplies a repeated inverse-reference witness`);
      const candidate=page.locator(`button[data-inspect-destination=${JSON.stringify(candidateKey)}]`);
      assert.ok(candidateKey,`${name}: a source candidate is keyboard addressable`);
      await candidate.focus();
      await page.keyboard.press('Enter');
      await page.waitForFunction(unit=>{
        const state=JSON.parse(document.querySelector('#workspace-state').textContent);
        return state.activeRecall===unit && state.focus && state.correspondence;
      },'m1:u0');
      await page.waitForFunction(key=>document.activeElement?.getAttribute('data-inspect-destination')===key,candidateKey);
      check(true,`${name}: keyboard candidate activation restores its semantic control`);
      const inverse=page.locator("button[data-inverse-unit]:not([data-inverse-unit='m1:u0'])").first();
      const inverseUnit=await inverse.getAttribute('data-inverse-unit');
      assert.ok(inverseUnit,`${name}: inverse unit is keyboard addressable after candidate inspection`);
      await inverse.focus();
      await page.keyboard.press('Enter');
      await page.waitForFunction(unit=>{
        const state=JSON.parse(document.querySelector('#workspace-state').textContent);
        return state.activeRecall===unit && state.focus && state.correspondence;
      },inverseUnit);
      await page.waitForFunction(unit=>document.activeElement?.getAttribute('data-inverse-unit')===unit,inverseUnit);
      check(true,`${name}: keyboard inverse activation restores its semantic control`);
      await page.locator('#workspace-recall').selectOption('m1:u0');
      const support=page.locator('.joined-recall small').filter({hasText:'Exact support:'}).first();
      const supportText=await support.textContent();
      const supportMatch=/Exact support: (\d+)–(\d+)/.exec(supportText||'');
      assert.ok(supportMatch,`${name}: exact horizon support is rendered with its producer span`);
      const supportStart=Number(supportMatch[1]), supportEnd=Number(supportMatch[2]);
      assert.ok(supportEnd>supportStart,`${name}: horizon witness has a nonempty exact span`);
      await setRange('#recall-horizon',supportEnd-1);
      await page.waitForFunction(text=>!document.querySelector('.joined-recall')?.textContent?.includes(text),supportText);
      await setRange('#recall-horizon',supportEnd);
      await page.waitForFunction(text=>document.querySelector('.joined-recall')?.textContent?.includes(text),supportText);
      check(true,`${name}: recall horizon exposes whole producer fragments only at their exact boundary`);
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
      let viewportWitness;
      if(name==='wog') {
        const sourceScroll=page.locator('#workspace-source-scroll');
        await sourceScroll.evaluate(pane=>{
          const anchors=[...pane.querySelectorAll('[data-source-offset]')];
          const target=anchors[Math.floor(anchors.length/2)];
          pane.scrollTop+=target.getBoundingClientRect().top-pane.getBoundingClientRect().top;
          pane.dispatchEvent(new Event('scroll'));
        });
        await page.waitForFunction(()=>{
          const state=JSON.parse(document.querySelector('#workspace-state').textContent);
          const pane=document.querySelector('#workspace-source-scroll');
          const first=[...pane.querySelectorAll('[data-source-offset]')]
            .find(anchor=>anchor.getBoundingClientRect().bottom>pane.getBoundingClientRect().top+1);
          return pane.scrollTop>0 && first && state.viewport.sourceOffset===Number(first.getAttribute('data-source-offset'));
        });
        const matrixScroll=page.locator('#workspace-matrix-scroll');
        await matrixScroll.evaluate(pane=>{
          const cells=[...pane.querySelectorAll('[data-matrix-cell]')];
          const rows=Math.max(...cells.map(cell=>Number(cell.getAttribute('data-matrix-cell').split('-')[0])))+1;
          const columns=Math.max(...cells.map(cell=>Number(cell.getAttribute('data-matrix-cell').split('-')[1])))+1;
          const target=pane.querySelector(`[data-matrix-cell='${Math.floor(rows/2)}-${Math.floor(columns/2)}']`);
          pane.scrollTop+=target.getBoundingClientRect().top-pane.getBoundingClientRect().top;
          pane.scrollLeft+=target.getBoundingClientRect().left-pane.getBoundingClientRect().left;
          pane.dispatchEvent(new Event('scroll'));
        });
        await page.waitForFunction(()=>{
          const state=JSON.parse(document.querySelector('#workspace-state').textContent);
          const pane=document.querySelector('#workspace-matrix-scroll');
          const first=[...pane.querySelectorAll('[data-matrix-cell]')].find(cell=>{
            const rect=cell.getBoundingClientRect(), viewport=pane.getBoundingClientRect();
            return rect.bottom>viewport.top+1 && rect.right>viewport.left+1;
          });
          const [row,column]=first.getAttribute('data-matrix-cell').split('-').map(Number);
          return (pane.scrollTop>0 || pane.scrollLeft>0) && state.viewport.matrixRow===row && state.viewport.matrixColumn===column;
        });
        viewportWitness=await state();
        check(true,`${name}: actual source and matrix scroll viewports update semantic state`);
      }
      const beforeSave=await state();
      const saved=await download('#workspace-save',`${name}-investigation.json`);
      await page.getByRole('button',{name:'Clear selection',exact:true}).click();
      await open(input('saved-state',saved)); assert.deepEqual(await state(),beforeSave);
      if(viewportWitness) await page.waitForFunction(viewport=>{
        const intersects=(pane,selector)=>{
          const anchor=pane.querySelector(selector), a=anchor.getBoundingClientRect(), b=pane.getBoundingClientRect();
          return a.bottom>b.top && a.top<b.bottom && a.right>b.left && a.left<b.right;
        };
        const source=document.querySelector('#workspace-source-scroll');
        const matrix=document.querySelector('#workspace-matrix-scroll');
        const state=JSON.parse(document.querySelector('#workspace-state').textContent);
        return state.viewport.sourceOffset===viewport.sourceOffset &&
          state.viewport.matrixRow===viewport.matrixRow && state.viewport.matrixColumn===viewport.matrixColumn &&
          intersects(source,`[data-source-offset='${viewport.sourceOffset}']`) &&
          intersects(matrix,`[data-matrix-cell='${viewport.matrixRow}-${viewport.matrixColumn}']`);
      },viewportWitness.viewport);
      check(true,`${name}: save and exact-artifact reopen reproduces full state including viewport`);
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
    const exportDenied=JSON.parse(member(bell,'Capabilities')); exportDenied.export='Denied';
    await open(input('export-denied',canonical(replace(bell,'Capabilities',canonical(exportDenied)))));
    let deniedDownload=false;
    page.once('download',()=>{deniedDownload=true;});
    await page.locator('#workspace-export').click();
    await page.getByText('Export refused:',{exact:false}).waitFor();
    await page.waitForTimeout(100);
    check(!deniedDownload,'re-signed export-denied packet blocks download');
    await delayReaders({'delayed-stale-wog':600,'latest-bell':0,'cancelled-wog':600});
    await page.locator('#workspace-open').setInputFiles(input('delayed-stale-wog',load('wog.workspace.json')));
    await page.locator('#workspace-open').setInputFiles(input('latest-bell',load('bell.workspace.json')));
    await page.waitForFunction(()=>document.querySelector('[data-open-status]')?.getAttribute('data-open-status')?.startsWith('Opened'));
    const latest=await state();
    await page.waitForTimeout(750);
    assert.deepEqual(await state(),latest);
    check(true,'delayed FileReader stale response cannot replace the latest admitted view');
    const beforeCancel=await state();
    await page.locator('#workspace-open').setInputFiles(input('cancelled-wog',load('wog.workspace.json')));
    await page.waitForFunction(()=>document.querySelector('[data-open-status]')?.getAttribute('data-open-status')?.startsWith('Checking'));
    await page.getByRole('button',{name:'Cancel opening',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-open-status]')?.getAttribute('data-open-status')?.startsWith('Cancelled'));
    await page.waitForTimeout(750);
    assert.deepEqual(await state(),beforeCancel);
    check(true,'cancelled delayed FileReader response preserves the prior view');
    await restoreReaders();
    await page.locator('#workspace-recall').selectOption('m1:u0');
    await page.locator('#workspace-policy').selectOption('historical-lexical');
    await page.getByRole('button',{name:'Voyage',exact:true}).click();
    await page.locator('#workspace-voyage .plate svg').waitFor(); // The source plate cannot satisfy the controlled Voyage witness.
    check(await page.locator('#workspace-voyage .plate svg').count()===1,'Voyage has one controlled SVG projection');
    await page.locator('#workspace-recall').selectOption('m1:u1');
    await page.locator('#workspace-voyage .voyage-anchor').first().click();
    const anchorTarget=(await state()).correspondence?.target;
    assert.ok(anchorTarget,'Voyage anchor establishes exact source correspondence');
    await page.locator('#workspace-voyage .voyage-alt').first().click();
    const alternativeState=await state();
    assert.equal(alternativeState.activeRecall,'m1:u1');
    assert.ok(alternativeState.correspondence?.target && alternativeState.correspondence.target!==anchorTarget);
    check(true,'Voyage alternative activates both qualified recall and exact source evidence');
    const quote=await page.locator('#workspace-voyage .quote').textContent();
    assert.ok(quote.length>2,'Voyage horizon witness starts with supplied quotation');
    await setRange('#recall-horizon',0);
    await page.waitForFunction(text=>!document.querySelector('#workspace-voyage').textContent.includes(text),quote);
    check(true,'Voyage inspector and SVG titles respect recall evidence horizon');
    await setRange('#recall-horizon',Number(await page.locator('#recall-horizon').getAttribute('max')));
    await page.locator('#voyage-range-start').fill('0:00');
    await page.locator('#voyage-range-end').fill('0:00.1');
    await page.getByRole('button',{name:'Apply range',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('#workspace-voyage .selection-location').textContent.includes('OffProjection'));
    assert.equal((await state()).activeRecall,'m1:u1');
    check(true,'cropped Voyage selection remains identified as OffProjection');

    await page.locator('#workspace-recall').selectOption('m1:u3');
    check((await state()).activeRecall==='m1:u3','untimed unit stays reachable in Voyage');
    await page.locator('#voyage-source-cursor').fill('0:20');
    await page.getByRole('button',{name:'Set source cursor',exact:true}).click();
    await page.locator('#voyage-recall-cursor').fill('0:02.5');
    await page.getByRole('button',{name:'Set recall cursor',exact:true}).click();
    await page.locator('#voyage-range-start').fill('0:01');
    await page.locator('#voyage-range-end').fill('0:05');
    await page.getByRole('button',{name:'Apply range',exact:true}).click();
    const clockState=await state();
    assert.equal(clockState.viewport.sourceCursor,20);
    assert.equal(clockState.viewport.recallCursor,2.5);
    assert.deepEqual(clockState.viewport.recallWindow,{start:1,end:5});
    await page.locator('#workspace-policy').selectOption('authored-a');
    await page.locator('#workspace-policy').selectOption('historical-lexical');
    assert.deepEqual((await state()).viewport,clockState.viewport);
    const clockSave=await download('#workspace-save','independent-clocks.json');
    await open(input('clock-save',clockSave));
    await page.locator('#workspace-voyage .plate svg').waitFor();
    assert.deepEqual(await state(),clockState);
    check(true,'independent clocks and recall window survive policy projection and saved-state replay');

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

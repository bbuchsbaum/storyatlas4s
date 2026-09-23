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
const report = {checks:[], errors:[], console:[], requests:[], playwright:pkg.version};
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
    page.on('console',m=>{report.console.push(m.text()); if(m.type()==='error')report.errors.push(m.text());});
    page.on('request',r=>{if(/^https?:/.test(r.url()))report.requests.push(r.url());});
    await page.goto(pathToFileURL(entry).href);
    await page.locator('.shell[data-state=ready]').waitFor();
    // v7 keeps recall navigation and the horizon in a disclosure; the court opens it once per workspace.
    const openNavigation = async()=>page.evaluate(()=>{
      const d=[...document.querySelectorAll('details')].find(e=>e.querySelector('#workspace-recall'));
      if(d) d.open=true;
    });
    const settle = async()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r)))));
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
      await page.locator('[data-workspace-ready=true] .shell[data-state=ready]').waitFor({state:'attached'});
      await openNavigation();
      check(await page.locator('.mapping-matrix tbody tr').count()===4,`${name}: all four outcomes rendered`);
      check(await page.locator('#fixture-header').getAttribute('hidden')!==null,`${name}: old fixture authority removed`);
      for(let ordinal=0;ordinal<4;ordinal++) {
        await page.locator('.inspector-navigation').getByRole('button',{name:'Next recall',exact:true}).click();
        check((await state()).activeRecall===`m1:u${ordinal}`,`${name}: canonical walk reaches ordinal${ordinal}`);
      }
      await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u0');
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
      check(!/StageEntryId\s*[@({]|(?:inference|transport) stage\s*:\s*\{/.test(initialMetadata),`${name}: inspection metadata renders stage digests, not raw stage objects`);
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
      await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u0');
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
      await page.getByRole('button',{name:'Source reading',exact:true}).click();
      assert.deepEqual((await state()).selection,selected); check(true,`${name}: source projection retains selection`);
      await page.getByRole('button',{name:'Story + recall',exact:true}).click();
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
        await page.getByRole('button',{name:'Source reading',exact:true}).click(); // v7: one task pane at a time
        const sourceScroll=page.locator('#workspace-source-scroll');
        await sourceScroll.locator('[data-source-offset]').first().waitFor(); await settle(); // mode switch restores on the next frames
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
        await page.getByRole('button',{name:'Story + recall',exact:true}).click();
        const matrixScroll=page.locator('#workspace-matrix-scroll');
        await matrixScroll.locator('[data-matrix-cell]').first().waitFor(); await settle();
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
      // v7 shows one task pane at a time: verify each restored viewport in its own mode.
      const restored = async(selectorOf, viewport)=>page.waitForFunction(([selectorOf,viewport])=>{
        const [paneId,anchor]=selectorOf==='matrix'
          ? ['#workspace-matrix-scroll',`[data-matrix-cell='${viewport.matrixRow}-${viewport.matrixColumn}']`]
          : ['#workspace-source-scroll',`[data-source-offset='${viewport.sourceOffset}']`];
        const pane=document.querySelector(paneId), a=pane.querySelector(anchor).getBoundingClientRect(), b=pane.getBoundingClientRect();
        const state=JSON.parse(document.querySelector('#workspace-state').textContent);
        return state.viewport.sourceOffset===viewport.sourceOffset &&
          state.viewport.matrixRow===viewport.matrixRow && state.viewport.matrixColumn===viewport.matrixColumn &&
          b.width>0 && a.bottom>b.top && a.top<b.bottom && a.right>b.left && a.left<b.right;
      },[selectorOf,viewport]);
      if(viewportWitness) {
        await restored('matrix',viewportWitness.viewport);
        await page.getByRole('button',{name:'Source reading',exact:true}).click();
        await restored('source',viewportWitness.viewport);
        await page.getByRole('button',{name:'Story + recall',exact:true}).click();
      }
      check(true,`${name}: save and exact-artifact reopen reproduces full state including viewport`);
      await page.locator('#workspace-policy').selectOption('authored-a');
      assert.equal(await page.locator('[data-matrix-cell="0-0"]').innerText(),first);
      check(true,`${name}: switching back restores exact original values`);
      await page.screenshot({path:path.join(output,`${name}-desktop.png`),fullPage:true});
    }
    await open(input('bell',load('bell.workspace.json')));
    await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u0');
    await page.locator('[data-matrix-cell="0-0"]').focus();
    await page.keyboard.press('ArrowDown');
    check((await state()).activeRecall==='m1:u1','matrix arrow key changes canonical row');
    check(await page.evaluate(()=>document.activeElement?.matches('[data-matrix-cell="1-0"]')),'matrix keeps visible keyboard focus');
    let stable=await state();
    const bell=JSON.parse(load('bell.workspace.json')), wog=JSON.parse(load('wog.workspace.json'));
    const wogOnly=[...new Set(load('wog.workspace.json').match(/[A-Za-z][A-Za-z-]{7,}/g)||[])]
      .find(token=>token==='hear-war-cries' && !load('bell.workspace.json').includes(token));
    assert.ok(wogOnly,'fixtures provide a WOG-only denied-content sentinel');
    await open(input('admitted-wog-sentinel',load('wog.workspace.json')));
    check((await page.content()).includes(wogOnly),'admitted WOG control proves the denied-content sentinel detector');
    await open(input('bell-after-sentinel-control',load('bell.workspace.json')));
    stable=await state();
    const bellBeforeDenial=await download('#workspace-export','bell-before-denied-wog.json');
    check(!bellBeforeDenial.includes(wogOnly),'current admitted Bell export excludes the WOG-only sentinel');
    await open(input('foreign',canonical(replace(bell,'Recall',member(wog,'Recall')))),'Refused');
    assert.deepEqual(await state(),stable); check(true,'foreign recall refuses before replacing current investigation');
    const denialCaps=JSON.parse(member(wog,'Capabilities')); denialCaps.inspection='Denied';
    const consoleBeforeDenied=report.console.length, errorsBeforeDenied=report.errors.length;
    let deniedPacketDownload=false;
    const deniedPacketDownloadListener=()=>{deniedPacketDownload=true;};
    page.once('download',deniedPacketDownloadListener);
    await open(input('inspection-denied-wog',canonical(replace(wog,'Capabilities',canonical(denialCaps)))),'Refused');
    await page.waitForTimeout(100);
    assert.deepEqual(await state(),stable); check(true,'re-signed inspection-denied WOG preserves the admitted Bell state');
    check(!deniedPacketDownload,'inspection-denied WOG packet creates no download');
    page.off('download',deniedPacketDownloadListener);
    check(!(await page.content()).includes(wogOnly),'inspection-denied WOG sentinel is absent from DOM including SVG titles');
    const accessibilitySurface=await page.evaluate(()=>[...document.querySelectorAll('*')].flatMap(element=>[
      element.textContent||'', ...[...element.attributes].map(attribute=>attribute.value)
    ]).join('\n'));
    check(!accessibilitySurface.includes(wogOnly),'inspection-denied WOG sentinel is absent from accessible text and attributes');
    check(!report.console.slice(consoleBeforeDenied).join('\n').includes(wogOnly) &&
      !report.errors.slice(errorsBeforeDenied).join('\n').includes(wogOnly),'inspection-denied WOG sentinel is absent from browser console and error capture');
    const bellAfterDenial=await download('#workspace-export','bell-after-denied-wog.json');
    check(!bellAfterDenial.includes(wogOnly) && bellAfterDenial===bellBeforeDenial,'permitted Bell export after denied WOG remains byte-identical and sentinel-free');
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
    await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u0');
    await page.locator('#workspace-policy').selectOption('historical-lexical');
    await page.getByRole('button',{name:'Time Voyage',exact:true}).click();
    await page.locator('#workspace-voyage .plate svg').waitFor(); // The source plate cannot satisfy the controlled Voyage witness.
    check(await page.locator('#workspace-voyage .plate svg').count()===1,'Voyage has one controlled SVG projection');
    await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u1');
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

    await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u3');
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

    await page.getByRole('button',{name:'Story + recall',exact:true}).click();
    for(const [label,width,zoom] of [['mobile',390,1],['zoom200',1600,2]]) {
      await page.setViewportSize({width,height:1000});
      await page.evaluate(z=>document.body.style.zoom=String(z),zoom);
      check(await page.evaluate(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth+2),`${label}: no document horizontal overflow`);
      await page.screenshot({path:path.join(output,`${label}.png`),fullPage:true});
    }
    await page.evaluate(()=>document.body.style.zoom='1');
    await page.setViewportSize({width:1600,height:1100});
    await open(input('source-only',member(wog,'SourceModel')));
    await page.locator('#workspace-source-only .shell[data-state=ready]').waitFor();
    check(await page.locator('.mapping-matrix').count()===0,'source-only opening presents reading without an empty matrix');
    await page.locator('#workspace-source-only [data-source-offset]').first().waitFor();
    const sourceOffset=await page.locator('#workspace-source-only').evaluate(pane=>{
      const anchors=[...pane.querySelectorAll('[data-source-offset]')];
      const target=anchors[Math.floor(anchors.length/2)];
      pane.scrollTop+=target.getBoundingClientRect().top-pane.getBoundingClientRect().top;
      const top=pane.getBoundingClientRect().top;
      return Number(anchors.find(el=>el.getBoundingClientRect().bottom>top).dataset.sourceOffset);
    });
    assert.ok(sourceOffset>0,'attachment witness has a nonzero reading position');
    await page.locator('#workspace-attach').setInputFiles(input('wrong-edition',load('bell.workspace.json')));
    await page.waitForFunction(()=>document.querySelector('[data-open-status]').dataset.openStatus.startsWith('Refused'));
    check(await page.locator('#workspace-source-only').count()===1 && await page.locator('.mapping-matrix').count()===0,'attaching a foreign edition refuses and keeps the current source');
    await page.locator('#workspace-attach').setInputFiles(input('same-edition',load('wog.workspace.json')));
    await page.locator('#workspace-state').waitFor({state:'attached'});
    assert.equal((await state()).viewport.sourceOffset,sourceOffset);
    assert.equal((await state()).mode,'Source');
    await page.waitForFunction(offset=>{
      const pane=document.getElementById('workspace-source-scroll');
      const line=pane?.querySelector(`[data-source-offset="${offset}"]`);
      if(!line)return false;
      const p=pane.getBoundingClientRect(),l=line.getBoundingClientRect();
      return l.bottom>p.top && l.top<p.bottom;
    },sourceOffset);
    check(true,'compatible recall attachment preserves the exact source reading position');
    await page.getByRole('button',{name:'Story + recall',exact:true}).click();
    await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u0');
    const scrollMatrix=async(column)=>{
      await page.waitForTimeout(50);
      await page.locator('#workspace-matrix-scroll').evaluate((pane,column)=>{
        const cell=pane.querySelector(`[data-matrix-cell="0-${column}"]`);
        pane.scrollLeft+=cell.getBoundingClientRect().left-pane.getBoundingClientRect().left;
        pane.dispatchEvent(new Event('scroll'));
      },column);
      await page.waitForFunction(()=>{
        const pane=document.getElementById('workspace-matrix-scroll'),box=pane.getBoundingClientRect();
        const first=[...pane.querySelectorAll('[data-matrix-cell]')].find(e=>e.getBoundingClientRect().bottom>box.top+1 && e.getBoundingClientRect().right>box.left+1);
        const state=JSON.parse(document.getElementById('workspace-state').textContent);
        return first.dataset.matrixCell===`${state.viewport.matrixRow}-${state.viewport.matrixColumn}`;
      });
    };
    const restoredMatrix=async(expected)=>page.waitForFunction(expected=>{
      const pane=document.getElementById('workspace-matrix-scroll'),box=pane.getBoundingClientRect();
      const first=[...pane.querySelectorAll('[data-matrix-cell]')].find(e=>e.getBoundingClientRect().bottom>box.top+1 && e.getBoundingClientRect().right>box.left+1);
      const s=JSON.parse(document.getElementById('workspace-state').textContent);
      return s.activeRecall===expected.activeRecall && first.dataset.matrixCell===`${expected.viewport.matrixRow}-${expected.viewport.matrixColumn}`;
    },expected);
    await scrollMatrix(Math.floor((await page.locator('.mapping-matrix thead th').count()-1)/2));
    const historyA=await state();
    await (await openNavigation(), page.locator('#workspace-recall')).selectOption('m1:u1');
    await scrollMatrix(0);
    const historyB=await state();
    assert.notEqual(historyA.viewport.matrixColumn,historyB.viewport.matrixColumn,'history witness uses distinct matrix positions');
    await page.locator('#workspace-back').click();
    await restoredMatrix(historyA);
    await page.locator('#workspace-return').click();
    await restoredMatrix(historyB);
    check(true,'Back and Return restore qualified selection and actual matrix viewport');
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
    await context.close();
    context=await browser.newContext({viewport:{width:1600,height:1100},deviceScaleFactor:2});
    page=await context.newPage();
    page.on('pageerror',e=>report.errors.push(String(e)));
    page.on('console',m=>{report.console.push(m.text());if(m.type()==='error')report.errors.push(m.text());});
    page.on('request',r=>{if(/^https?:/.test(r.url()))report.requests.push(r.url());});
    await page.goto(pathToFileURL(entry).href);
    await open(input('retina-bell',load('bell.workspace.json')));
    await page.locator('[data-matrix-cell="0-0"]').focus();
    await page.keyboard.press('ArrowDown');
    check(await page.evaluate(()=>devicePixelRatio===2 && document.activeElement?.matches('[data-matrix-cell="1-0"]')),'DPR2 keyboard focus retains qualified matrix identity');
    const focusStyle=await page.evaluate(()=>{const s=getComputedStyle(document.activeElement);return {width:s.outlineWidth,style:s.outlineStyle};});
    check(parseFloat(focusStyle.width)>0 && focusStyle.style!=='none','DPR2 keyboard focus has a visible non-color-only outline');
    const contrast=await page.locator('.joined-workspace h1').first().evaluate(element=>{
      const rgb=s=>(s.match(/[\d.]+/g)||[]).slice(0,3).map(Number);
      const luminance=a=>a.map(v=>v/255).map(v=>v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4)).reduce((sum,v,i)=>sum+v*[0.2126,0.7152,0.0722][i],0);
      const fg=rgb(getComputedStyle(element).color);let parent=element,bg='rgba(0, 0, 0, 0)';
      while(parent && bg==='rgba(0, 0, 0, 0)'){bg=getComputedStyle(parent).backgroundColor;parent=parent.parentElement;}
      const a=luminance(fg),b=luminance(rgb(bg));return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05);
    });
    check(contrast>=4.5,'workspace primary text meets measured contrast floor');
    await page.screenshot({path:path.join(output,'dpr2.png'),fullPage:true});
    check(report.errors.length===0,'no browser or console errors');
    check(report.requests.length===0,'no network request from the local workspace');
    report.exit=0;
  } catch(error) {
    report.exit=1; report.failure=String(error.stack||error);
    if(page) { report.viewportDebug=await page.evaluate(()=>({state:document.querySelector('#workspace-state')?.textContent,panes:['workspace-source-scroll','workspace-matrix-scroll'].map(id=>{const p=document.getElementById(id);return p&&{id,top:p.scrollTop,left:p.scrollLeft,width:p.clientWidth,height:p.clientHeight};})})).catch(()=>null); await page.screenshot({path:path.join(output,'failure.png'),fullPage:true}).catch(()=>{}); fs.writeFileSync(path.join(output,'failure.html'),await page.content().catch(()=>'')); }
    process.exitCode=1;
  } finally {
    if(context)await context.close(); if(browser)await browser.close();
    fs.writeFileSync(path.join(output,'browser.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify({checks:report.checks.length,exit:report.exit,failure:report.failure||null}));
  }
})();

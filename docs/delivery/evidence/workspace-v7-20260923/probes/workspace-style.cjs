const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const repo=path.resolve(process.argv[2]);
const fixtures=path.resolve(process.argv[3]);
const modules=path.join(repo,'e2e/static');
const {chromium}=require(require.resolve('playwright',{paths:[modules]}));
const output=path.resolve(process.argv[4]);
const report={checks:[],errors:[]};
const check=(ok,label)=>{assert.ok(ok,label);report.checks.push(label);};
function luminance(s){const a=s.match(/[\d.]+/g).slice(0,3).map(Number).map(x=>{x/=255;return x<=0.04045?x/12.92:((x+0.055)/1.055)**2.4;});return .2126*a[0]+.7152*a[1]+.0722*a[2];}
function contrast(c){const a=luminance(c.color),b=luminance(c.background);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);}
(async()=>{let browser,context;
try{
 browser=await chromium.launch({headless:true});context=await browser.newContext({viewport:{width:1600,height:1100}});const page=await context.newPage();
 page.on('pageerror',e=>report.errors.push(String(e)));
 await page.goto(pathToFileURL(path.join(repo,'target/edition/index.html')).href);
 await page.locator('.shell[data-state=ready]').waitFor();
 await page.locator('#workspace-open').setInputFiles(path.join(fixtures,'bell.workspace.json'));
 await page.locator('.design-workspace .mapping-matrix').waitFor();
 await page.locator('.recall-row-button').first().click();
 await page.screenshot({path:path.join(output,'workspace-desktop.png'),fullPage:true});
 await page.keyboard.press('Tab');const selected=page.locator('.task-switch button[aria-pressed=true]');await selected.focus();
 const focus=await selected.evaluate(e=>({visible:e.matches(':focus-visible'),width:getComputedStyle(e).outlineWidth,style:getComputedStyle(e).outlineStyle}));
 check(focus.visible && parseFloat(focus.width)>=3 && focus.style==='solid','selected task retains a visible keyboard focus ring');
 // Explicit synthetic CSS specimens, not a mapping or generated scientific result.
 const styles=await page.evaluate(()=>{
  const specimen=document.createElement('section');specimen.className='design-workspace';specimen.id='css-specimen';
  specimen.innerHTML='<table class="mapping-matrix"><tbody><tr><td class="target-column"><button id="css-target" class="matrix-cell mass-4">0.6</button></td><td class="external-column"><button id="css-external" class="matrix-cell mass-4">0.6</button></td><td><button class="matrix-cell mass-5 fill-origin"><span id="css-fill" class="decision-mark"></span></button></td><td><button class="matrix-cell mass-5"><span id="css-decision" class="decision-mark"></span></button></td></tr></tbody></table>';
  document.body.append(specimen);
  const read=id=>{const e=document.getElementById(id),s=getComputedStyle(e);return {color:s.color,background:s.backgroundColor,border:s.borderTopStyle};};
  const target=read('css-target'),external=read('css-external'),fill=read('css-fill'),decision=read('css-decision');
  document.getElementById('css-target').dataset.focused='true';const focused=read('css-target');specimen.remove();return {target,external,fill,decision,focused};
 });
 report.styles=styles;
 check(contrast(styles.target)>=4.5,'target mass-4 numerals meet 4.5:1 contrast');
 check(contrast(styles.external)>=4.5,'non-source mass-4 numerals meet 4.5:1 contrast');
 check(styles.fill.background==='rgba(0, 0, 0, 0)' && styles.fill.border==='dashed','gap-fill decision remains hollow and dashed on dark mass');
 check(styles.decision.background==='rgb(255, 255, 255)','ordinary supplied decision stays solid on dark mass');
 check(styles.focused.background===styles.target.background,'correspondence focus does not change scientific fill');
 await page.setViewportSize({width:390,height:844});
 await page.screenshot({path:path.join(output,'workspace-compact.png'),fullPage:true});
 check(await page.evaluate(()=>document.documentElement.scrollWidth<=390),'narrow workspace has no page-level overflow');
 check(report.errors.length===0,'no browser errors');
}catch(e){report.failure=String(e);process.exitCode=1;}
finally{if(context)await context.close();if(browser)await browser.close();fs.writeFileSync(path.join(output,'workspace-style.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));}
})();

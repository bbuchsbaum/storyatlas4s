// Behavioural court for V7: node check-v4.cjs <page.html> <out-dir>
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {pathToFileURL} = require('node:url');
const pw = require(require.resolve('playwright', {paths: [process.env.PLAYWRIGHT_DIR || __dirname]}));
const exe = process.env.PLAYWRIGHT_CHROMIUM || undefined;
const [page, out] = [path.resolve(process.argv[2]), path.resolve(process.argv[3])];
fs.mkdirSync(out, {recursive: true});
const checks = [];
const check = (ok, what) => { checks.push({ok, what}); assert.ok(ok, what); };
(async () => {
  const browser = await pw.chromium.launch({headless: true, executablePath: exe});
  try {
    const p = await browser.newPage({viewport: {width: 1600, height: 1000}, acceptDownloads: true});
    const errors = [];
    p.on('pageerror', e => errors.push(String(e)));
    p.on('request', r => { if (/^https?:/.test(r.url())) errors.push('network ' + r.url()); });
    await p.goto(pathToFileURL(page).href);
    const sel = () => p.evaluate(() => st.sel);
    const count = () => p.locator('#count').textContent();
    const live = async () => { await p.waitForTimeout(60); return p.locator('#live').textContent(); };
    // defaults
    check(await count() === 'no filter set', 'no filter and no dimming by default');
    check(await p.locator('#criteria').isHidden(), 'thresholds panel starts closed');
    // plot keyboard: Tab into plot shows the focus ring before any arrow key
    await p.locator('#export').focus();
    await p.keyboard.press('Tab'); await p.keyboard.press('Tab');
    const onPlot = await p.evaluate(() => document.activeElement.id);
    await p.locator('#plot').focus(); await p.keyboard.press('Shift+Tab'); await p.keyboard.press('Tab');
    check(await p.evaluate(() => document.activeElement.id === 'plot' && document.getElementById('plot').classList.contains('kbd')), 'arriving on the plot by Tab shows the keyboard focus ring');
    const s0 = await sel();
    await p.keyboard.press('ArrowRight');
    const s1 = await sel();
    check(s1 !== s0 && (await live()).startsWith(`R${s1},`), 'ArrowRight selects the next unit and announces it');
    await p.keyboard.press('ArrowLeft');
    check(await sel() === s0, 'ArrowLeft returns');
    // camera independent of selection
    const dom0 = await p.locator('#dsub').textContent();
    await p.keyboard.press('ArrowRight'); await p.keyboard.press('ArrowRight');
    check(await p.locator('#dsub').textContent() === dom0, 'changing the selection inside the window does not rescale the film axis');
    // End then ArrowRight pages the window forward
    const w0 = await p.evaluate(() => [...st.win]);
    await p.keyboard.press('End'); await p.keyboard.press('ArrowRight');
    const w1 = await p.evaluate(() => [...st.win]);
    check(w1[0] > w0[0], 'ArrowRight at the window edge pages the window to the next timed unit');
    // M with no filter explains itself
    await p.keyboard.press('m');
    check(/No filter set/.test(await live()), 'M with no filter announces why it does nothing');
    // filter chips keep focus across renders
    await p.locator('#chip-filled').focus(); await p.keyboard.press('Enter');
    check(await p.evaluate(() => document.activeElement.id === 'chip-filled'), 'toggling a chip by keyboard keeps focus on that chip');
    check(/\d+ of 173 units match/.test(await count()), 'the live count reports matches');
    const filledOnly = await p.evaluate(() => U.filter(isMatch).length);
    const summaryFilled = await p.evaluate(() => S.filled);
    check(filledOnly === summaryFilled, `the Decode-filled chip count (${summaryFilled}) equals the rows it filters`);
    // M steps through matches and says when it runs out
    await p.locator('#plot').focus();
    await p.evaluate(() => { const last = TIMED.filter(isMatch).pop(); select(last.ordinal, true); });
    await p.keyboard.press('m');
    check(/No further match/.test(await live()), 'M at the last match announces that there is no further match');
    // untimed-only filter
    await p.locator('#chip-filled').click(); await p.locator('#chip-untimed').click();
    await p.locator('#plot').focus(); await p.keyboard.press('m');
    check(/untimed; see the untimed list/.test(await live()), 'M explains when every match is untimed');
    await p.locator('#chip-untimed').click();
    // window clamping and empty-window safety
    for (let i = 0; i < 12; i++) await p.locator('#later').click();
    const wEnd = await p.evaluate(() => [...st.win]);
    check(wEnd[1] <= await p.evaluate(() => V.RECALL_END) + 1e-9, 'Later never moves the window past the end of the recall');
    await p.locator('#plot').focus(); await p.keyboard.press('Home'); await p.keyboard.press('End');
    check(errors.length === 0, 'Home and End at the recall end raise no error');
    // overview slider semantics and drag
    await p.locator('#whole').click(); await p.locator('#earlier').click();
    await p.evaluate(() => { setWin(240, 360); render(); });
    const ov = p.locator('#overview');
    check(await ov.getAttribute('aria-valuenow') === '240' && await ov.getAttribute('aria-valuemin') === '0' && +(await ov.getAttribute('aria-valuemax')) > 0, 'the overview exposes slider value, min and max');
    const box = await p.locator('#brush').boundingBox();
    await p.mouse.move(box.x + box.width / 2, box.y + 10); await p.mouse.down(); await p.mouse.move(box.x + box.width / 2 + 120, box.y + 10, {steps: 5}); await p.mouse.up();
    check((await p.evaluate(() => st.win[0])) > 240, 'dragging the brush pans the window');
    await ov.focus(); await p.keyboard.press('Home');
    check(await p.evaluate(() => st.win[0]) === 0, 'Home on the overview moves the window to the start');
    // inspector table: headers, caption, every admitted anchor
    const t = await p.evaluate(() => { const u = cur(); const tb = document.getElementById('anchors'); return tb ? {rows: tb.tBodies[0].rows.length, n: u.candidates.length, th: tb.querySelectorAll('th').length, cap: !!tb.caption} : null; });
    check(t && t.rows === t.n && t.th === 4 && t.cap, 'the anchor table lists every admitted anchor with a caption and column headers');
    await p.locator('#plot').focus(); await p.keyboard.press('Enter');
    check(await p.evaluate(() => document.activeElement.id === 'anchors'), 'Enter moves focus to the anchor table, as the hint says');
    // untimed unit carries no anchor anywhere
    await p.locator('#untimed summary').click(); await p.locator('#untimed button').first().click();
    check(/no anchor, origin or mass is supplied/.test(await p.locator('#insp').textContent()), 'an untimed unit shows its text and no anchor, origin or mass');
    // accessibility tree: options belong to the listbox
    await p.evaluate(() => { setWin(240, 360); select(ANCH.find(u => u.onset > 300).ordinal, false); });
    const snap = await p.locator('#plot').ariaSnapshot();
    check(/listbox/.test(snap) && /option/.test(snap), 'the plot exposes a listbox of options');
    // export: every unit, supplied columns, provenance header
    await p.locator('#chip-bound').click();
    const [dl] = await Promise.all([p.waitForEvent('download'), p.locator('#export').click()]);
    const file = path.join(out, 'export.tsv'); await dl.saveAs(file);
    const lines = fs.readFileSync(file, 'utf8').trim().split('\n');
    const header = lines.filter(l => l.startsWith('#')), body = lines.filter(l => !l.startsWith('#'));
    const cols = body[0].split('\t'), data = body.slice(1).map(l => l.split('\t'));
    check(data.length === 173, 'export writes all 173 units');
    check(header.some(l => /checksum: [0-9a-f]{64}/.test(l)) && header.some(l => /time base/.test(l)) && header.some(l => /derived by this view/.test(l)), 'export header carries full checksum, time base and the derived-column note');
    const ix = n => cols.indexOf(n);
    const untimedRows = data.filter(r => r[ix('kind')] === 'untimed');
    check(untimedRows.length === 6 && untimedRows.every(r => r[ix('anchor_mass')] === '' && r[ix('origin')] === ''), 'untimed rows carry no anchor fields');
    const small = data.find(r => r[ix('anchor_mass')] !== '' && +r[ix('anchor_mass')] > 0 && +r[ix('anchor_mass')] < 0.001);
    check(!small || !/^0\.0+$/.test(small[ix('anchor_mass')]), 'tiny non-zero masses are exported unrounded');
    check(data.filter(r => r[ix('matched')] === 'true').length === await p.evaluate(() => U.filter(isMatch).length), 'matched rows equal the on-screen match count');
    // V5 additions
    const [dl2] = await Promise.all([p.waitForEvent('download'), p.locator('#exportAnchors').click()]);
    const f2 = path.join(out, 'anchors.tsv'); await dl2.saveAs(f2);
    const al = fs.readFileSync(f2, 'utf8').trim().split('\n').filter(l => !l.startsWith('#'));
    check(al.length - 1 === await p.evaluate(() => ANCH.reduce((a, u) => a + u.candidates.length, 0)), 'the anchors export lists every admitted anchor of every unit, long format');
    check(!cols.includes('unit_address') && header.some(l => /derived by this view: timed, admitted_anchors/.test(l) || /timed \(onset present\)/.test(l)), 'the units export names every derived column and invents no address');
    check(await p.evaluate(() => U.every(u => u.onset == null || u.onset >= 0)), 'every onset is non-negative (Seconds.of refuses negatives)');
    check(await p.evaluate(() => { const K = V.groups.length + V.segments.length; return ANCH.every(u => { const H = -u.candidates.reduce((a, c) => { const q = c.mass / u.sourceMass; return a + (q > 0 ? q * Math.log(q) : 0); }, 0); return Math.abs(u.localizability - (1 - H / Math.log(K))) < 1e-9; }); }), 'localizability uses K = all timeline nodes');
    check(await p.evaluate(() => { const g = glyph({origin: 'argmax', drawnMass: 0.005}, 10, 10); return (g.match(/<circle/g) || []).length === 2; }), 'no renderer mass floor: a mass of 0.005 still draws its inner mark');
    check(await p.evaluate(() => /fill="#f7f5f0" stroke=/.test(glyph({origin: 'argmax', drawnMass: .4, externalDominant: true}, 10, 10).split('/>')[1])), 'external-dominant is drawn as a hollow inner mark on the glyph');
    check(!/TransitionFlow is absent/.test(await p.locator('#absent').textContent()) && await p.locator('#req').count() === 0, 'no request for quantities outside the contract');
    // a11y: focus into the plot brings a unit into view; resize without drag; popover closes; Enter on a non-anchor speaks
    await p.evaluate(() => { setWin(600, 120); const u = TIMED.find(x => x.onset < 100); st.sel = u.ordinal; render(); });
    await p.locator('#yfit').focus(); for (let i = 0; i < 12; i++) { await p.keyboard.press('Tab'); if (await p.evaluate(() => document.activeElement.id) === 'plot') break; }
    check(await p.evaluate(() => document.activeElement.id === 'plot' && !!document.getElementById('plot').getAttribute('aria-activedescendant')), 'tabbing to the plot with the selection off-window selects a unit in the window');
    const wa = await p.evaluate(() => st.win[1] - st.win[0]);
    await p.locator('#overview').focus(); await p.keyboard.press('PageUp');
    const wb = await p.evaluate(() => st.win[1] - st.win[0]);
    await p.locator('#narrower').click();
    check(wb > wa && await p.evaluate(() => st.win[1] - st.win[0]) < wb, 'the window widens by key (PageUp) and narrows by button (−)');
    await p.locator('#more').click(); await p.keyboard.press('Tab'); await p.keyboard.press('Tab'); await p.keyboard.press('Tab'); await p.keyboard.press('Tab'); await p.keyboard.press('Tab'); await p.keyboard.press('Tab');
    check(await p.locator('#criteria').isHidden(), 'the thresholds popover closes when focus leaves it');
    await p.evaluate(() => select(U.find(u => u.kind === 'unanchored').ordinal, true)); await p.locator('#plot').focus(); await p.keyboard.press('Enter');
    check(/No anchor table/.test(await live()), 'Enter on an unanchored unit explains that there is no anchor table');
    await p.evaluate(() => { setWin(240, 120); render(); }); await p.locator('#overview').focus(); await p.keyboard.press('-');
    const wm = await p.evaluate(() => st.win[1] - st.win[0]); await p.keyboard.press('+');
    check(wm < 120 && await p.evaluate(() => st.win[1] - st.win[0]) > wm, 'the − key narrows and + widens, matching the − and + buttons');
    check(await p.evaluate(() => { const lum = h => { const v = [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255).map(c => c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; }; const cr = (a, b) => (Math.max(lum(a), lum(b)) + 0.05) / (Math.min(lum(a), lum(b)) + 0.05); return cr(DIM, PAPER) >= 3 && cr(DIM_STROKE, PAPER) >= 3; }), 'dimmed marks keep at least 3:1 against the paper');
    check(await p.evaluate(() => (glyph({origin: 'decode-filled', drawnMass: 0, externalDominant: true}, 10, 10).match(/<rect/g) || []).length === 2 && (glyph({origin: 'argmax', drawnMass: 0.001, externalDominant: true}, 10, 10).match(/<circle/g) || []).length === 3), 'external-dominance reads at any mass: zero-mass fills and tiny masses carry the second outline');
    // V7: nothing on the rail or in the gutter is dropped, across many selections and both cameras
    const cover = await p.evaluate(() => { const bad = [];
      for (const y of ['fit', 'whole']) { st.y = y; for (const u of ANCH.filter((_, i) => i % 7 === 0)) { select(u.ordinal, false);
        const [d0, d1] = domain(); const want = V.groups.filter(g => g.end > d0 && g.start < d1).map(g => g.id);
        const got = new Set([...document.querySelectorAll('#plot text[data-scenes]')].flatMap(t => t.dataset.scenes.split(' ').map(Number)));
        if (!want.every(id => got.has(id))) bad.push(`rail R${u.ordinal} ${y}`);
        const shown = u.candidates.filter(c => !(c.node.end <= d0 || c.node.start >= d1)).length + (u.origin === 'decode-filled' ? 1 : 0);
        const texts = [...document.querySelectorAll('#plot g[aria-hidden] text')].length - 2 - (/outside the shown film/.test(document.getElementById('plot').textContent) ? 1 : 0);
        if (texts !== shown) bad.push(`gutter R${u.ordinal} ${y}: ${texts}/${shown}`);
        const ys = [...document.querySelectorAll('#plot text[data-scenes]')].map(t => +t.getAttribute('y')).sort((a, b) => a - b);
        if (ys.some((v, i) => i && v - ys[i - 1] < 11.5)) bad.push(`rail overlap R${u.ordinal} ${y}`); } }
      st.y = 'fit'; render(); return bad; });
    check(cover.length === 0, 'every scene in view is named or inside a printed range, every in-view anchor value is printed, and rail labels never overlap: ' + cover.slice(0, 6).join('; '));
    const q2 = await browser.newPage({viewport: {width: 1280, height: 900}}); await q2.goto(pathToFileURL(page).href);
    check(await q2.evaluate(() => document.documentElement.scrollWidth <= 1280), 'no horizontal scroll at 1280px');
    await q2.setViewportSize({width: 640, height: 900});
    check(await q2.evaluate(() => document.documentElement.scrollWidth <= 640), 'no page-level horizontal scroll at 640px (200% zoom)');
    await q2.setViewportSize({width: 320, height: 900});
    check(await q2.evaluate(() => document.documentElement.scrollWidth <= 320), 'no page-level horizontal scroll at 320px (400% zoom)'); await q2.setViewportSize({width: 640, height: 900});
    check(await q2.evaluate(() => Math.round(document.querySelector('#plot svg').getBoundingClientRect().width) === 1120 && document.getElementById('plot').scrollWidth > document.getElementById('plot').clientWidth), 'at 640px the charts keep full size and scroll inside their own box (text and targets do not shrink)'); await q2.close();
    check(errors.length === 0, 'no page errors and no network requests: ' + errors.join('; '));
    // screenshots: default with keyboard focus, and filter state
    for (const [hash, name] of [['#kbd', 'prototype.png'], ['#filter', 'prototype-filter.png']]) {
      const q = await browser.newPage({viewport: {width: 1600, height: 1000}});
      await q.goto(pathToFileURL(page).href + hash); await q.waitForTimeout(200);
      await q.screenshot({path: path.join(out, name), fullPage: true}); await q.close();
    }
    console.log(JSON.stringify({passed: checks.length, checks: checks.map(c => c.what)}, null, 1));
  } catch (e) {
    console.log(JSON.stringify({passed: checks.filter(c => c.ok).length, failure: String(e.stack || e)}, null, 1));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();

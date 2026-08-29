#!/usr/bin/env node
// Browser smoke for the storyatlas4s app shell (headless Chromium via Playwright).
//
//   sbt <overrides> "cli/run edition --out target/edition" app/editionBundle
//   node app/smoke/smoke.cjs target/edition/index.html
//
// Checks, against the live DOM:
//   1. the Codex rail hashes to both the source and layout source receipts after every state change
//      (V-T2, without a copy of the story text in this repository);
//   2. live Atlas MarkIds equal the compiled mark set and its navigation keys, while live Codex
//      FragmentIds independently equal the compiled fragment set and its navigation keys (V-I2);
//   3. playhead identity sets are empty at 0, capacity-bounded mid-story, and restore exactly;
//   4. Reading and measurer changes preserve the rail and their own compiled identity contracts;
//   5. both continuous zoom controls commit exact typed states with hysteresis, real surface mark
//      changes, and exact restoration;
//   6. one semantic focus and selection is OnMark, ViaAncestor, or OffProjection exactly as the
//      two storymodel4s compilers report, including the hidden-selection horizon shape (V-L2);
//   7. no page error and no console error.
// Playwright must resolve from e2e/static at the exact pin below; no global-package fallback and no
// system Chrome. Install its browser with `npx --prefix e2e/static playwright install chromium`.

const { createHash } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const PLAYWRIGHT_PIN = "1.55.1";

function loadPlaywright() {
  const candidates = ["playwright", "@playwright/test"];
  const projectModules = path.resolve(__dirname, "../../e2e/static");
  for (const name of candidates) {
    try {
      const resolved = require.resolve(name, { paths: [projectModules] });
      const packageJson = require.resolve(`${name}/package.json`, { paths: [projectModules] });
      const version = require(packageJson).version;
      if (version !== PLAYWRIGHT_PIN) {
        throw new Error(`resolved ${name} ${version}; expected ${PLAYWRIGHT_PIN}`);
      }
      return require(resolved);
    } catch (_) {
      /* try the next candidate */
    }
  }
  throw new Error(
    `cannot resolve project Playwright ${PLAYWRIGHT_PIN}: tried ${candidates.join(", ")} under ` +
      `${projectModules}; install with \`npm --prefix e2e/static ci\` and ` +
      "`npx --prefix e2e/static playwright install chromium`"
  );
}

const failures = [];
function check(condition, message) {
  if (!condition) failures.push(message);
  console.log(`${condition ? "ok  " : "FAIL"} ${message}`);
}

async function screenshotOnFailure(page) {
  const diagnosticsDir =
    process.env.STORYATLAS4S_SMOKE_DIAGNOSTICS_DIR || path.join(__dirname, "diagnostics");
  fs.mkdirSync(diagnosticsDir, { recursive: true });
  const dest = path.join(diagnosticsDir, "live-shell-failure.png");
  try {
    await page.screenshot({ path: dest, fullPage: true });
    console.log(`diagnostic screenshot ${dest}`);
  } catch (err) {
    console.log(`diagnostic screenshot failed: ${err}`);
  }
}

async function main() {
  const target = process.argv[2];
  if (!target) {
    console.error("usage: node app/smoke/smoke.cjs <path-or-url to index.html>");
    process.exit(2);
  }
  const url = /^[a-z]+:\/\//.test(target) ? target : "file://" + path.resolve(target);
  const { chromium } = loadPlaywright();
  let browser;
  let context;
  let page;
  try {
    browser = await chromium.launch({ headless: true });
    context = await browser.newContext({ viewport: { width: 1600, height: 1200 } });
    page = await context.newPage();
    const pageErrors = [];
    const consoleErrors = [];
    page.on("pageerror", (e) => pageErrors.push(String(e)));
    page.on("console", (m) => {
      if (m.type() === "error") consoleErrors.push(m.text());
    });
    if (process.env.STORYATLAS4S_SMOKE_FAIL_AFTER_LAUNCH === "1") {
      throw new Error("deliberate smoke failure after browser launch");
    }

    const ready = '.shell[data-state="ready"]';
    const mutationMode = process.env.STORYATLAS4S_SMOKE_MUTATION || "none";
    const railText = () =>
      page.$$eval(".codex .page .text .line", (spans) =>
        spans.map((s) => s.textContent).join("")
      );
    const receipt = (key) =>
      page.$eval(`.panel .receipts dd[data-key="${key}"]`, (dd) => dd.textContent);
    const attr = (selector, name) =>
      page.$eval(selector, (el, n) => el.getAttribute(n), name);
    const names = (selector) =>
      page.$$eval(selector, (xs) => xs.map((x) => x.getAttribute("data-name")));
    const encodedIds = async (selector, name) => {
      const encoded = await attr(selector, name);
      return encoded ? encoded.split(" ").filter(Boolean).sort() : [];
    };
    const sameIds = (left, right) =>
      JSON.stringify([...left].sort()) === JSON.stringify([...right].sort());
    const checkIdentityDomain = async ({
      label,
      liveSelector,
      section,
      compiledAttr,
      resolvedAttr,
      nonEmpty,
    }) => {
      const live = await names(liveSelector);
      const compiled = await encodedIds(section, compiledAttr);
      const resolved = await encodedIds(section, resolvedAttr);
      check(live.length === new Set(live).size, `${label}: live identities are unique`);
      check(
        compiled.length === new Set(compiled).size,
        `${label}: compiled identities are unique`
      );
      check(sameIds(compiled, resolved), `${label}: every compiled identity resolves`);
      check(sameIds(live, compiled), `${label}: live identities equal compiled identities`);
      if (nonEmpty) check(live.length > 0, `${label}: identity set is non-empty`);
      return [...live].sort();
    };
    const checkRail = async (label, baseline = null) => {
      const text = await railText();
      const sha = createHash("sha256").update(text, "utf8").digest("hex");
      const sourceChecksum = await receipt("sourceChecksum");
      const layoutChecksum = await receipt("layout.sourceChecksum");
      check(text.length > 0, `${label}: rail has text (${text.length} code units)`);
      check(sha === sourceChecksum, `${label}: rail sha256 equals source receipt`);
      check(sha === layoutChecksum, `${label}: rail sha256 equals layout source receipt`);
      if (baseline !== null) check(text === baseline, `${label}: canonical rail is unchanged`);
      return text;
    };
    const placementTexts = () =>
      page.$$eval(".panel .selection li li", (xs) => xs.map((x) => x.textContent));
    const mutateFirstName = (selector, replacement) =>
      page.$eval(
        selector,
        (element, value) => element.setAttribute("data-name", value),
        replacement
      );
    const setRange = async (value) => {
      await page.$eval(
        "#horizon",
        (el, v) => {
          el.value = String(v);
          el.dispatchEvent(new Event("input", { bubbles: true }));
        },
        value
      );
      await page.waitForFunction(
        (v) =>
          document
            .querySelector('.receipts dd[data-key="horizon"]')
            ?.textContent?.startsWith(`reader at ${v} of`),
        value
      );
    };
    const setSemanticZoom = async (selector, value, expected) => {
      await page.$eval(
        selector,
        (el, v) => {
          el.value = String(v);
          el.dispatchEvent(new Event("input", { bubbles: true }));
        },
        value
      );
      await page.waitForFunction(
        (zoom) =>
          document.querySelector('.receipts dd[data-key="atlasZoom"]')?.textContent === zoom,
        expected
      );
      check((await attr(".shell", "data-zoom")) === expected, `${selector}: ${expected}`);
      check((await receipt("atlasZoom")) === expected, `${selector}: receipt names ${expected}`);
    };

    await page.goto(url);
    await page.waitForSelector(ready, { timeout: 60000 });
    if (!["none", "orphan", "horizon"].includes(mutationMode)) {
      throw new Error(`unknown STORYATLAS4S_SMOKE_MUTATION=${mutationMode}`);
    }

    // 1. V-T2: the live rail hashes to both independently carried source receipts.
    const text = await checkRail("initial");
    const lines = await page.$$eval(".codex .line", (xs) => xs.length);
    check(
      String(lines) === (await receipt("layout.lines")),
      `rail has layout.lines spans (${lines})`
    );
    const measurer0 = await receipt("measurerInUse");
    check(
      measurer0.startsWith("dom-canvas/"),
      `the DOM canvas measurer is in use (${measurer0})`
    );
    check(
      (await receipt("domMeasurer")).startsWith("available: dom-canvas/"),
      "the panel reports the DOM measurer available"
    );
    check((await receipt("atlasBoxPx")) === "1600x420px", "the receipts print the Atlas box");
    const pagesDom = await page.$$eval(".codex .page", (xs) => xs.length);
    check(
      String(pagesDom) === (await receipt("layout.pages")),
      `one DOM page per layout page (${pagesDom})`
    );

    // 2. V-I2 and the epistemic playhead: identity sets, never counts alone.
    if (mutationMode === "orphan") {
      await mutateFirstName(
        ".atlas .atlas-svg svg [data-name]",
        "mutation:orphan-mark"
      );
    }
    const atlasFullIds = await checkIdentityDomain({
      label: "omniscient Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    const fragmentFullIds = await checkIdentityDomain({
      label: "omniscient Codex",
      liveSelector: ".codex .overlay svg [data-name]",
      section: ".codex",
      compiledAttr: "data-fragment-ids",
      resolvedAttr: "data-resolved-fragment-ids",
      nonEmpty: true,
    });
    check(
      String(atlasFullIds.length) === (await attr(".atlas", "data-marks")),
      "Atlas identity count agrees with its compiled count"
    );
    check(
      String(fragmentFullIds.length) === (await attr(".codex", "data-fragments")),
      "Codex identity count agrees with its compiled count"
    );
    if (mutationMode === "orphan") {
      throw new Error(
        failures.length
          ? "orphan MarkId mutation was rejected by identity equality"
          : "orphan MarkId mutation survived"
      );
    }

    // 2b. ZOOM-4: independent continuous surface input commits finite typed states. A midpoint
    // jitter inside the dead band causes no new intent; real sentence/token states add real marks.
    check((await receipt("atlasZoom")) === "Scene/Hidden", "initial exact zoom is Scene/Hidden");
    const initialIntent = await receipt("intentRevision");
    await setSemanticZoom("#surface-zoom", 0.64, "Scene/Hidden");
    check(
      (await receipt("intentRevision")) === initialIntent,
      "sub-threshold surface motion does not request compilation"
    );
    await setSemanticZoom("#surface-zoom", 0.66, "Scene/Sentences");
    const sentenceIds = await checkIdentityDomain({
      label: "Scene/Sentences Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    check(sentenceIds.length > atlasFullIds.length, "Sentences adds provider-compiled marks");
    await checkRail("Scene/Sentences", text);
    const sentenceIntent = await receipt("intentRevision");
    for (const jitter of [0.61, 0.58, 0.63, 0.55, 0.62]) {
      await setSemanticZoom("#surface-zoom", jitter, "Scene/Sentences");
    }
    check(
      (await receipt("intentRevision")) === sentenceIntent,
      "surface threshold jitter is absorbed by hysteresis"
    );
    await setSemanticZoom("#surface-zoom", 2, "Scene/Tokens");
    const tokenIds = await checkIdentityDomain({
      label: "Scene/Tokens Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    check(tokenIds.length > sentenceIds.length, "Tokens adds provider-compiled token marks");
    await checkIdentityDomain({
      label: "Scene/Tokens Codex",
      liveSelector: ".codex .overlay svg [data-name]",
      section: ".codex",
      compiledAttr: "data-fragment-ids",
      resolvedAttr: "data-resolved-fragment-ids",
      nonEmpty: true,
    });
    await checkRail("Scene/Tokens", text);
    await setSemanticZoom("#surface-zoom", 0, "Scene/Hidden");
    const surfaceRestoredIds = await checkIdentityDomain({
      label: "restored Scene/Hidden Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    check(sameIds(surfaceRestoredIds, atlasFullIds), "Hidden Atlas identities restore exactly");
    await checkRail("restored Scene/Hidden", text);

    await setRange(0);
    const atlas0Ids = await checkIdentityDomain({
      label: "reader at 0 Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: false,
    });
    const fragment0Ids = await checkIdentityDomain({
      label: "reader at 0 Codex",
      liveSelector: ".codex .overlay svg [data-name]",
      section: ".codex",
      compiledAttr: "data-fragment-ids",
      resolvedAttr: "data-resolved-fragment-ids",
      nonEmpty: false,
    });
    check(atlas0Ids.length === 0, "reader at 0: Atlas identity set is empty");
    check(fragment0Ids.length === 0, "reader at 0: Codex identity set is empty");
    await checkRail("reader at 0", text);

    const max = Number(await attr("#horizon", "max"));
    const halfway = Math.floor(max / 2);
    await setRange(halfway);
    const expectedHalfIds = await encodedIds(".atlas", "data-mark-ids");
    if (mutationMode === "horizon") {
      const future = atlasFullIds.find((id) => !expectedHalfIds.includes(id));
      if (!future) throw new Error("fixture has no future MarkId for the horizon mutation");
      await mutateFirstName(".atlas .atlas-svg svg [data-name]", future);
    }
    const atlasHalfIds = await checkIdentityDomain({
      label: `reader at ${halfway} Atlas`,
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    const fragmentHalfIds = await checkIdentityDomain({
      label: `reader at ${halfway} Codex`,
      liveSelector: ".codex .overlay svg [data-name]",
      section: ".codex",
      compiledAttr: "data-fragment-ids",
      resolvedAttr: "data-resolved-fragment-ids",
      nonEmpty: true,
    });
    check(
      atlasHalfIds.length < atlasFullIds.length,
      `reader at ${halfway}: Atlas identity capacity is below omniscient`
    );
    check(
      fragmentHalfIds.length < fragmentFullIds.length,
      `reader at ${halfway}: Codex identity capacity is below omniscient`
    );
    await checkRail(`reader at ${halfway}`, text);
    if (mutationMode === "horizon") {
      throw new Error(
        failures.length
          ? "future MarkId substitution was rejected by horizon identity equality"
          : "future MarkId substitution survived"
      );
    }

    await page.check("#omniscient");
    await page.waitForFunction(() =>
      document
        .querySelector('.receipts dd[data-key="horizon"]')
        ?.textContent?.startsWith("omniscient")
    );
    const atlasRestoredIds = await checkIdentityDomain({
      label: "restored omniscient Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    const fragmentRestoredIds = await checkIdentityDomain({
      label: "restored omniscient Codex",
      liveSelector: ".codex .overlay svg [data-name]",
      section: ".codex",
      compiledAttr: "data-fragment-ids",
      resolvedAttr: "data-resolved-fragment-ids",
      nonEmpty: true,
    });
    check(sameIds(atlasRestoredIds, atlasFullIds), "omniscient Atlas identities restore exactly");
    check(
      sameIds(fragmentRestoredIds, fragmentFullIds),
      "omniscient Codex identities restore exactly"
    );
    await checkRail("restored omniscient", text);

    // 3. Reading removes only the Codex identity domain.
    await page.selectOption("#lens", "Reading");
    await page.waitForFunction(
      () => document.querySelector(".codex")?.getAttribute("data-fragments") === "0"
    );
    const readingFragments = await checkIdentityDomain({
      label: "Reading Codex",
      liveSelector: ".codex .overlay svg [data-name]",
      section: ".codex",
      compiledAttr: "data-fragment-ids",
      resolvedAttr: "data-resolved-fragment-ids",
      nonEmpty: false,
    });
    check(readingFragments.length === 0, "Reading lens: Codex identity set is empty");
    await checkIdentityDomain({
      label: "Reading Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    await checkRail("Reading lens", text);
    await page.selectOption("#lens", "Overview");
    await page.waitForFunction(
      (n) =>
        document.querySelector(".codex")?.getAttribute("data-fragments") === String(n),
      fragmentFullIds.length
    );

    // 4. Re-pagination may change FragmentIds, but each live set must match its own compiler output.
    await page.selectOption("#measurer", "Monospace");
    await page.waitForFunction(() =>
      document
        .querySelector('.receipts dd[data-key="measurerInUse"]')
        ?.textContent?.startsWith("monospace-table")
    );
    await checkIdentityDomain({
      label: "monospace Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    await checkIdentityDomain({
      label: "monospace Codex",
      liveSelector: ".codex .overlay svg [data-name]",
      section: ".codex",
      compiledAttr: "data-fragment-ids",
      resolvedAttr: "data-resolved-fragment-ids",
      nonEmpty: true,
    });
    await checkRail("monospace measurer", text);
    check(
      String(await page.$$eval(".codex .line", (xs) => xs.length)) ===
        (await receipt("layout.lines")),
      "monospace measurer: line count matches its receipt"
    );
    await page.selectOption("#measurer", "Dom");
    await page.waitForFunction(() =>
      document
        .querySelector('.receipts dd[data-key="measurerInUse"]')
        ?.textContent?.startsWith("dom-canvas")
    );
    await checkRail("restored DOM measurer", text);

    // 5. V-L2: one persistent semantic selection exercises all three compiler placements.
    const atlasBeforeSelection = await checkIdentityDomain({
      label: "before selection Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    // Landmarks are last in draw order, so this click is hit-tested rather than dispatched.
    await page.locator(".atlas .atlas-svg svg g[data-name]").last().click();
    await page.waitForSelector(".panel .selection li code");
    const address = await page.$eval(".panel .selection li code", (c) => c.textContent);
    const placements = await placementTexts();
    check(address.startsWith("story/"), `selected address ${address}`);
    check(
      placements.some((p) => p.startsWith("Atlas: on-mark")),
      `Atlas placement as text: ${placements.find((p) => p.startsWith("Atlas:"))}`
    );
    check(
      placements.some((p) => p.startsWith("Codex: on-mark")),
      `Codex placement as text: ${placements.find((p) => p.startsWith("Codex:"))}`
    );
    const selectedMarks = await page.$$eval(".atlas svg .selected", (xs) => xs.length);
    check(selectedMarks >= 1, `selected mark(s) marked in the Atlas (${selectedMarks})`);
    const focusedMarks = await page.$$eval(".atlas svg .focused", (xs) => xs.length);
    check(focusedMarks >= 1, `focused mark(s) marked in the Atlas (${focusedMarks})`);
    check((await attr(".shell", "data-focus")) === address, "semantic focus is the Address");
    const afterSelection = await checkIdentityDomain({
      label: "selected Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    check(
      sameIds(afterSelection, atlasBeforeSelection),
      "selection does not change the Atlas identity set"
    );
    await checkRail("on-mark selection", text);
    const pressed = await page.$$eval('.atlas svg [aria-pressed="true"]', (xs) => xs.length);
    check(pressed === selectedMarks, "aria-pressed matches the selected marks");
    const current = await page.$$eval('.atlas svg [aria-current="true"]', (xs) => xs.length);
    check(current === focusedMarks, "aria-current matches the focused marks");

    await setSemanticZoom("#narrative-zoom", 0, "Story/Hidden");
    const storyPlacements = await placementTexts();
    check(
      storyPlacements.some((p) => p.startsWith("Atlas: via-ancestor")),
      `Story zoom: ${storyPlacements.find((p) => p.startsWith("Atlas:"))}`
    );
    check(
      storyPlacements.some((p) => p.startsWith("Codex: on-mark")),
      `Codex retains its own compiled placement: ${storyPlacements.find((p) =>
        p.startsWith("Codex:")
      )}`
    );
    check(
      (await page.$eval(".panel .selection li code", (c) => c.textContent)) === address,
      "zoom preserves the semantic selection address"
    );
    check((await attr(".shell", "data-focus")) === address, "zoom preserves semantic focus");
    await checkIdentityDomain({
      label: "Story zoom Atlas",
      liveSelector: ".atlas .atlas-svg svg [data-name]",
      section: ".atlas",
      compiledAttr: "data-mark-ids",
      resolvedAttr: "data-resolved-mark-ids",
      nonEmpty: true,
    });
    await checkRail("via-ancestor selection", text);

    // Find the canonical fixture interval where the selected late mark is hidden while some
    // ancestor-level marks remain visible. A climb through hidden evidence would remain
    // via-ancestor here; the compiler must instead say off-projection.
    const step = Math.max(1, Math.floor(max / 24));
    let hiddenSelection = null;
    for (let candidate = max - step; candidate > 0 && hiddenSelection === null; candidate -= step) {
      await setRange(candidate);
      const candidatePlacements = await placementTexts();
      const candidateMarks = await names(".atlas .atlas-svg svg [data-name]");
      if (
        candidateMarks.length > 0 &&
        candidatePlacements.some((p) => p.startsWith("Atlas: off-projection"))
      ) {
        hiddenSelection = { candidate, placements: candidatePlacements };
      }
    }
    check(hiddenSelection !== null, "fixture exposes a visible-ancestor/hidden-selection horizon");
    if (hiddenSelection !== null) {
      check(
        hiddenSelection.placements.some((p) => p.startsWith("Codex: on-mark")),
        `independent Codex placement remains compiled: ${hiddenSelection.placements.find((p) =>
          p.startsWith("Codex:")
        )}`
      );
      check(
        (await page.$eval(".panel .selection li code", (c) => c.textContent)) === address,
        `reader at ${hiddenSelection.candidate}: selection persists without leaking placement`
      );
      await checkIdentityDomain({
        label: `reader at ${hiddenSelection.candidate} Atlas`,
        liveSelector: ".atlas .atlas-svg svg [data-name]",
        section: ".atlas",
        compiledAttr: "data-mark-ids",
        resolvedAttr: "data-resolved-mark-ids",
        nonEmpty: true,
      });
      await checkIdentityDomain({
        label: `reader at ${hiddenSelection.candidate} Codex`,
        liveSelector: ".codex .overlay svg [data-name]",
        section: ".codex",
        compiledAttr: "data-fragment-ids",
        resolvedAttr: "data-resolved-fragment-ids",
        nonEmpty: true,
      });
      await checkRail(`reader at ${hiddenSelection.candidate} hidden selection`, text);
    }

    await page.check("#omniscient");
    await page.waitForFunction(() =>
      document
        .querySelector('.receipts dd[data-key="horizon"]')
        ?.textContent?.startsWith("omniscient")
    );
    await setSemanticZoom("#narrative-zoom", 2, "Scene/Hidden");
    const restoredPlacements = await placementTexts();
    check(
      restoredPlacements.some((p) => p.startsWith("Atlas: on-mark")),
      "restored Scene zoom returns the selection to on-mark"
    );
    await checkRail("restored selected Scene", text);

    // 5b. Selection through the keyboard uses the same persistent Set[Address].
    await page.click(".panel .selection button");
    await page.waitForFunction(() => !document.querySelector(".panel .selection li code"));
    await page.locator(".atlas .atlas-svg svg g[data-name]").first().focus();
    await page.keyboard.press("Enter");
    await page.waitForSelector(".panel .selection li code");
    const keyboardAddress = await page.$eval(
      ".panel .selection li code",
      (c) => c.textContent
    );
    const keyboardPlacements = await placementTexts();
    check(keyboardAddress.startsWith("story/"), `keyboard-selected address ${keyboardAddress}`);
    check(
      keyboardPlacements.some((p) => p.startsWith("Atlas: on-mark")),
      "keyboard selection: Atlas on-mark"
    );
    await checkRail("keyboard selection", text);

    // 6. Errors.
    check(
      pageErrors.length === 0,
      `no page error ${pageErrors.length ? JSON.stringify(pageErrors) : ""}`
    );
    check(
      consoleErrors.length === 0,
      `no console error ${consoleErrors.length ? JSON.stringify(consoleErrors) : ""}`
    );

    if (failures.length) {
      await screenshotOnFailure(page);
      console.error(`\n${failures.length} smoke check(s) failed`);
      process.exitCode = 1;
    } else {
      console.log("\nsmoke passed");
    }
  } catch (err) {
    if (page) await screenshotOnFailure(page);
    throw err;
  } finally {
    const cleanupErrors = [];
    for (const [label, resource] of [
      ["page", page],
      ["context", context],
      ["browser", browser],
    ]) {
      if (resource) {
        try {
          await resource.close();
        } catch (err) {
          cleanupErrors.push(`${label}: ${String(err)}`);
        }
      }
    }
    if (cleanupErrors.length) {
      console.error(`browser cleanup failed: ${cleanupErrors.join("; ")}`);
      process.exitCode = 1;
    }
  }
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});

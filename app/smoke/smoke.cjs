#!/usr/bin/env node
// Browser smoke for the storyatlas4s app shell (headless Chromium via Playwright).
//
//   sbt <overrides> "cli/run edition --out target/edition" app/editionBundle
//   node app/smoke/smoke.cjs target/edition/index.html
//
// Checks, against the live DOM:
//   1. the Codex rail's text content (every `.line` span, in order) hashes to the source checksum
//      the receipts panel prints (V-T2: the DOM text is the canonical text, verified without any
//      copy of the text in this repository);
//   2. moving the epistemic playhead to 0 removes every Atlas mark and every Codex piece, and
//      moving it back restores them, with the rail unchanged;
//   3. switching the lens to Reading leaves no piece and the rail unchanged;
//   4. switching the measurer changes the receipt's measurer and leaves the rail unchanged;
//   5. clicking a mark selects its address and the panel shows an on-mark / on-annotation
//      placement as text;
//   6. no page error and no console error.
// `playwright` (or `@playwright/test`, which re-exports it) is resolved from the working tree and
// NODE_PATH first, then from the global npm root. Install once with
// `npm i -g playwright && npx playwright install chromium`.

const { createHash } = require("node:crypto");
const { execSync } = require("node:child_process");
const path = require("node:path");

function loadPlaywright() {
  const candidates = ["playwright", "@playwright/test"];
  let root = null;
  for (const name of candidates) {
    try {
      return require(name);
    } catch (_) {
      /* try the next candidate */
    }
  }
  root = execSync("npm root -g").toString().trim();
  for (const name of candidates) {
    try {
      return require(path.join(root, name));
    } catch (_) {
      /* try the next candidate */
    }
  }
  throw new Error(
    `cannot resolve playwright: tried ${candidates.join(", ")} locally and under ${root}; ` +
      "install with `npm i -g playwright && npx playwright install chromium`"
  );
}

const failures = [];
function check(condition, message) {
  if (!condition) failures.push(message);
  console.log(`${condition ? "ok  " : "FAIL"} ${message}`);
}

async function main() {
  const target = process.argv[2];
  if (!target) {
    console.error("usage: node app/smoke/smoke.cjs <path-or-url to index.html>");
    process.exit(2);
  }
  const url = /^[a-z]+:\/\//.test(target) ? target : "file://" + path.resolve(target);
  const { chromium } = loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
  const pageErrors = [];
  const consoleErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  page.on("console", (m) => {
    if (m.type() === "error") consoleErrors.push(m.text());
  });

  const ready = '.shell[data-state="ready"]';
  const railText = () =>
    page.$$eval(".codex .page .text .line", (spans) => spans.map((s) => s.textContent).join(""));
  const receipt = (key) =>
    page.$eval(`.panel .receipts dd[data-key="${key}"]`, (dd) => dd.textContent);
  const marks = () => page.$$eval(".atlas .atlas-svg svg [data-name]", (xs) => xs.length);
  const pieces = () => page.$$eval(".codex .overlay svg [data-name]", (xs) => xs.length);
  const attr = (selector, name) => page.$eval(selector, (el, n) => el.getAttribute(n), name);
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
      (v) => document.querySelector(".shell")?.getAttribute("data-horizon") === String(v),
      value
    );
  };

  await page.goto(url);
  await page.waitForSelector(ready, { timeout: 60000 });

  // 1. V-T2 through the checksum the app prints.
  const text = await railText();
  const sha = createHash("sha256").update(text, "utf8").digest("hex");
  const sourceChecksum = await receipt("sourceChecksum");
  check(text.length > 0, `rail has text (${text.length} code units)`);
  check(sha === sourceChecksum, `sha256(rail text) == sourceChecksum (${sourceChecksum.slice(0, 12)}…)`);
  const lines = await page.$$eval(".codex .line", (xs) => xs.length);
  check(String(lines) === (await receipt("layout.lines")), `rail has layout.lines spans (${lines})`);
  const measurer0 = await receipt("measurerInUse");
  check(measurer0.startsWith("dom-canvas/"), `the DOM canvas measurer is in use (${measurer0})`);
  const pagesDom = await page.$$eval(".codex .page", (xs) => xs.length);
  check(String(pagesDom) === (await receipt("layout.pages")), `one DOM page per layout page (${pagesDom})`);

  // 2. The playhead.
  const marksFull = await marks();
  const piecesFull = await pieces();
  check(marksFull > 0, `omniscient Atlas has marks (${marksFull})`);
  check(piecesFull > 0, `omniscient Codex has pieces (${piecesFull})`);
  check(String(marksFull) === (await attr(".atlas", "data-marks")), "DOM mark count equals the compiled count");
  check(String(piecesFull) === (await attr(".codex", "data-fragments")), "DOM piece count equals the compiled count");
  await setRange(0);
  const marks0 = await marks();
  const pieces0 = await pieces();
  check(marks0 === 0, `reader at 0: no Atlas mark (${marks0})`);
  check(pieces0 === 0, `reader at 0: no Codex piece (${pieces0})`);
  check((await railText()) === text, "reader at 0: rail unchanged");
  check((await receipt("horizon")).startsWith("reader at 0 of"), "receipt records the horizon");
  const max = Number(await attr("#horizon", "max"));
  await setRange(Math.floor(max / 2));
  const marksHalf = await marks();
  check(marksHalf > 0 && marksHalf < marksFull, `reader at ${Math.floor(max / 2)}: ${marksHalf} marks, between 0 and ${marksFull}`);
  await page.check("#omniscient");
  await page.waitForFunction(
    (v) => document.querySelector(".shell")?.getAttribute("data-horizon") === String(v),
    max
  );
  check((await marks()) === marksFull, "omniscient again: mark count restored");
  check((await pieces()) === piecesFull, "omniscient again: piece count restored");
  check((await railText()) === text, "omniscient again: rail unchanged");

  // 3. The Reading lens.
  await page.selectOption("#lens", "Reading");
  await page.waitForFunction(() => document.querySelector(".codex")?.getAttribute("data-fragments") === "0");
  check((await pieces()) === 0, "Reading lens: no piece");
  check((await railText()) === text, "Reading lens: rail unchanged");
  await page.selectOption("#lens", "Overview");
  await page.waitForFunction((n) => document.querySelector(".codex")?.getAttribute("data-fragments") === String(n), piecesFull);

  // 4. The measurer.
  await page.selectOption("#measurer", "Monospace");
  await page.waitForFunction(() => document.querySelector('.receipts dd[data-key="measurerInUse"]')?.textContent?.startsWith("monospace-table"));
  check((await railText()) === text, "monospace measurer: rail unchanged");
  check(String(await page.$$eval(".codex .line", (xs) => xs.length)) === (await receipt("layout.lines")), "monospace measurer: line count matches its receipt");
  await page.selectOption("#measurer", "Dom");
  await page.waitForFunction(() => document.querySelector('.receipts dd[data-key="measurerInUse"]')?.textContent?.startsWith("dom-canvas"));

  // 5. Selection through a click on a mark.
  const marksBefore = await marks();
  await page.click(".atlas .atlas-svg svg g[data-name]", { force: true });
  await page.waitForSelector(".panel .selection li code");
  const address = await page.$eval(".panel .selection li code", (c) => c.textContent);
  const placements = await page.$$eval(".panel .selection li li", (xs) => xs.map((x) => x.textContent));
  check(address.startsWith("story/"), `selected address ${address}`);
  check(placements.some((p) => p.startsWith("Atlas: on-mark")), `Atlas placement as text: ${placements.find((p) => p.startsWith("Atlas:"))}`);
  check(placements.some((p) => p.startsWith("Codex: ")), `Codex placement as text: ${placements.find((p) => p.startsWith("Codex:"))}`);
  const selectedMarks = await page.$$eval(".atlas svg .selected", (xs) => xs.length);
  check(selectedMarks >= 1, `selected mark(s) marked in the Atlas (${selectedMarks})`);
  check((await marks()) === marksBefore, "selection does not change the mark count");
  check((await railText()) === text, "selection: rail unchanged");
  const pressed = await page.$$eval('.atlas svg [aria-pressed="true"]', (xs) => xs.length);
  check(pressed === selectedMarks, "aria-pressed matches the selected marks");

  // 6. Errors.
  check(pageErrors.length === 0, `no page error ${pageErrors.length ? JSON.stringify(pageErrors) : ""}`);
  check(consoleErrors.length === 0, `no console error ${consoleErrors.length ? JSON.stringify(consoleErrors) : ""}`);

  await browser.close();
  if (failures.length) {
    console.error(`\n${failures.length} smoke check(s) failed`);
    process.exit(1);
  }
  console.log("\nsmoke passed");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

"use strict";

const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { pathToFileURL } = require("url");

const root = path.resolve(__dirname, "../..");
const reviewDir = path.join(__dirname, "review");
const sourcePath = path.join(__dirname, "renderer-gap-benchmark.html");

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function playwright() {
  return require(require.resolve("playwright", { paths: [path.join(root, "e2e/static")] }));
}

async function main() {
  fs.mkdirSync(reviewDir, { recursive: true });
  const { chromium } = playwright();
  let browser;
  let context;
  let version;
  let result;
  const errors = [];
  try {
    browser = await chromium.launch({ headless: true });
    version = browser.version();
    context = await browser.newContext({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1, reducedMotion: "reduce" });
    const page = await context.newPage();
    page.on("pageerror", (error) => errors.push(String(error)));
    page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
    await page.goto(pathToFileURL(sourcePath).href, { waitUntil: "load" });
    await page.waitForFunction(() => document.documentElement.dataset.benchmarkDone === "true");
    result = await page.evaluate(() => window.__rendererGapResult);
    await page.screenshot({ path: path.join(reviewDir, "renderer-gap-benchmark.png"), fullPage: true, animations: "disabled" });
  } finally {
    if (context) await context.close().catch(() => undefined);
    if (browser) await browser.close().catch(() => undefined);
  }
  if (errors.length) throw new Error(errors.join(" | "));

  const receipt = {
    ...result,
    environment: { browser: { engine: "Chromium", version }, platform: { os: os.platform(), arch: os.arch() }, devicePixelRatio: 1 },
    source: { path: "renderer-gap-benchmark.html", sha256: sha256(fs.readFileSync(sourcePath)) }
  };
  const geometric = receipt.results.geometric;
  const shared = receipt.results.sharedPattern;
  if (geometric.gates.elementBudget && geometric.gates.byteBudget) {
    throw new Error("Geometric court unexpectedly passed both structural gates; RendererGap disposition must be revisited");
  }
  if (!shared.gates.elementBudget || !shared.gates.byteBudget) {
    throw new Error("Shared-pattern control failed the structural gates");
  }
  fs.writeFileSync(path.join(reviewDir, "renderer-gap-benchmark.json"), `${JSON.stringify(receipt, null, 2)}\n`);
  process.stdout.write(`${JSON.stringify(receipt, null, 2)}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});

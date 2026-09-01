"use strict";

const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { pathToFileURL } = require("url");

const root = path.resolve(__dirname, "../..");
const sourceDir = __dirname;
const reviewDir = path.join(sourceDir, "review");

const plates = [
  "Main",
  "RecallMatrix",
  "InterviewMode",
  "Compact",
  "Reflow",
  "FocusOrder",
  "Grayscale",
  "Motion",
  "StateBoard",
  "Tokens",
  "Concept",
  "Directions",
  "RendererGap"
];

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function playwright() {
  const searchRoot = path.join(root, "e2e/static");
  return require(require.resolve("playwright", { paths: [searchRoot] }));
}

function previewOf(source, sourceName) {
  const match = source.match(/data-props='([^']+)'/);
  if (!match) throw new Error(`${sourceName}: missing data-props preview`);
  const props = JSON.parse(match[1]);
  const preview = props.$preview;
  if (!preview || !Number.isInteger(preview.width) || !Number.isInteger(preview.height)) {
    throw new Error(`${sourceName}: invalid preview dimensions`);
  }
  return preview;
}

function browserAdaptedSource(source) {
  return source
    .replace(/<sc-(for|if)\b/gi, (_, kind) => `<template data-dc-directive="${kind.toLowerCase()}"`)
    .replace(/<\/sc-(?:for|if)>/gi, "</template>");
}

function syncCanvasPackage() {
  const wrapperPath = path.join(sourceDir, "storyatlas-mockup-v2.html");
  let wrapper = fs.readFileSync(wrapperPath, "utf8");
  const pattern = /(<script[^>]*id="appifact-doc"[^>]*>)([\s\S]*?)(<\/script>)/;
  const match = wrapper.match(pattern);
  if (!match) throw new Error("Published design package has no appifact-doc record");
  const document = JSON.parse(match[2]);
  for (const plate of plates) {
    const name = `${plate}.dc.html`;
    document.content.files[name] = fs.readFileSync(path.join(sourceDir, name), "utf8");
  }
  document.content.files["canvas.json"] = fs.readFileSync(
    path.join(sourceDir, "canvas.json"),
    "utf8"
  );
  const encoded = JSON.stringify(document).replace(/</g, "\\u003c");
  wrapper = wrapper.replace(pattern, `$1\n${encoded}\n$3`);
  fs.writeFileSync(wrapperPath, wrapper);
}

function indexHtml(entries) {
  const rows = entries
    .map(
      (entry) => `<li><a href="${entry.html}">${entry.name}</a> · ` +
        `<a href="${entry.png}">PNG plate</a> · ${entry.viewport.width}×${entry.viewport.height}</li>`
    )
    .join("\n");
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>StoryAtlas mockup v2 — frozen review export</title>
<style>
body{max-width:820px;margin:48px auto;padding:0 24px;color:#221d19;background:#f9f5ef;font:16px/1.6 system-ui,sans-serif}
h1{font-size:28px;line-height:1.2}code{font-family:ui-monospace,monospace}li{margin:8px 0}a{color:#24356f}
.notice{border:2px solid #221d19;padding:12px 16px;background:#f0ebe3;font-weight:650}
</style>
</head>
<body>
<p class="notice">SYNTHETIC PLACEHOLDER — NOT SOURCE OR MODEL OUTPUT</p>
<h1>StoryAtlas mockup v2</h1>
<p>This directory is the portable, frozen review export. The HTML plates contain no script,
network dependency, unresolved design-canvas directive, or runtime token. PNGs were captured from
these frozen HTML files, not from the editable design runtime.</p>
<p>This is design evidence only. Consult <a href="../PROOF-BOUNDARY.md">PROOF-BOUNDARY.md</a> and
<a href="manifest.json">manifest.json</a> before citing it as implementation evidence.</p>
<ol>${rows}</ol>
</body>
</html>
`;
}

async function frozenHtml(page, sourceName) {
  return page.evaluate((name) => {
    const clone = document.documentElement.cloneNode(true);
    clone.querySelectorAll("script").forEach((node) => node.remove());
    clone.removeAttribute("data-dc-resolved");
    clone.removeAttribute("data-dc-error");
    const meta = document.createElement("meta");
    meta.setAttribute("name", "storyatlas-frozen-source");
    meta.setAttribute("content", name);
    clone.querySelector("head").appendChild(meta);
    return `<!doctype html>\n${clone.outerHTML}\n`;
  }, sourceName);
}

async function overflowReport(page, expected) {
  return page.evaluate((size) => {
    const artboard = document.querySelector("x-dc > div");
    if (!artboard) return { error: "missing-artboard" };
    const box = artboard.getBoundingClientRect();
    const offenders = [];
    for (const element of artboard.querySelectorAll("*")) {
      const style = getComputedStyle(element);
      if (style.position === "fixed") continue;
      const rect = element.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) continue;
      if (rect.left < box.left - 1 || rect.right > box.right + 1 || rect.top < box.top - 1 || rect.bottom > box.bottom + 1) {
        offenders.push({ tag: element.tagName.toLowerCase(), left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom });
        if (offenders.length === 12) break;
      }
    }
    return {
      viewport: size,
      artboard: { width: box.width, height: box.height },
      scroll: { width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight },
      offenders
    };
  }, expected);
}

async function main() {
  fs.mkdirSync(reviewDir, { recursive: true });
  syncCanvasPackage();

  const { chromium } = playwright();
  let browser;
  let browserVersion;
  let context;
  let temporarySourceDir;
  const entries = [];
  try {
    temporarySourceDir = fs.mkdtempSync(path.join(os.tmpdir(), "storyatlas-v2-export-"));
    fs.copyFileSync(path.join(sourceDir, "support.js"), path.join(temporarySourceDir, "support.js"));
    browser = await chromium.launch({ headless: true });
    browserVersion = browser.version();
    context = await browser.newContext({ deviceScaleFactor: 1, reducedMotion: "reduce" });
    const page = await context.newPage();
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(String(error)));
    page.on("console", (message) => {
      if (message.type() === "error") pageErrors.push(`console: ${message.text()}`);
    });

    for (const name of plates) {
      pageErrors.length = 0;
      const sourceName = `${name}.dc.html`;
      const sourcePath = path.join(sourceDir, sourceName);
      const source = fs.readFileSync(sourcePath, "utf8");
      const viewport = previewOf(source, sourceName);
      const runtimeSourcePath = path.join(temporarySourceDir, sourceName);
      fs.writeFileSync(runtimeSourcePath, browserAdaptedSource(source));
      await page.setViewportSize(viewport);
      await page.goto(pathToFileURL(runtimeSourcePath).href, { waitUntil: "load" });
      await page.waitForFunction(
        () => ["true", "false"].includes(document.documentElement.dataset.dcResolved),
        null,
        { timeout: 10000 }
      ).catch((error) => {
        throw new Error(`${sourceName}: resolver did not report completion: ${error.message}`);
      });
      const resolutionError = await page.getAttribute("html", "data-dc-error");
      if (resolutionError) throw new Error(`${sourceName}: ${resolutionError}`);
      const resolved = (await frozenHtml(page, sourceName)).replace(/[ \t]+$/gm, "");
      if (
        resolved.includes("{{") ||
        /<sc-(?:for|if)\b/i.test(resolved) ||
        /<template\b[^>]*data-dc-directive/i.test(resolved)
      ) {
        throw new Error(`${sourceName}: frozen output retains an unresolved directive`);
      }

      const stem = name.replace(/([a-z])([A-Z])/g, "$1-$2").toLowerCase();
      const htmlName = `${stem}.html`;
      const pngName = `${stem}.png`;
      const htmlPath = path.join(reviewDir, htmlName);
      const pngPath = path.join(reviewDir, pngName);
      fs.writeFileSync(htmlPath, resolved);

      pageErrors.length = 0;
      await page.goto(pathToFileURL(htmlPath).href, { waitUntil: "load" });
      const portable = await page.evaluate(() => ({
        scripts: document.scripts.length,
        directives: document.querySelectorAll("sc-for,sc-if,template[data-dc-directive]").length,
        unresolved: document.documentElement.outerHTML.includes("{{"),
        textLength: (document.body.textContent || "").trim().length
      }));
      if (portable.scripts !== 0 || portable.directives !== 0 || portable.unresolved || portable.textLength < 100) {
        throw new Error(`${sourceName}: portable replay failed ${JSON.stringify(portable)}`);
      }
      const overflow = await overflowReport(page, viewport);
      if (overflow.error || overflow.offenders.length > 0) {
        throw new Error(`${sourceName}: clipping/overflow court failed ${JSON.stringify(overflow)}`);
      }
      if (pageErrors.length > 0) {
        throw new Error(`${sourceName}: browser errors ${pageErrors.join(" | ")}`);
      }
      await page.screenshot({ path: pngPath, fullPage: true, animations: "disabled" });
      entries.push({ name, sourceName, html: htmlName, png: pngName, viewport, portable, overflow });
    }
  } finally {
    if (context) await context.close().catch(() => undefined);
    if (browser) await browser.close().catch(() => undefined);
    if (temporarySourceDir) fs.rmSync(temporarySourceDir, { recursive: true, force: true });
  }

  const index = indexHtml(entries);
  fs.writeFileSync(path.join(reviewDir, "index.html"), index);

  const sourceNames = [
    "FIXTURE-NOTES.md",
    "PROOF-BOUNDARY.md",
    "canvas.json",
    "export.cjs",
    "renderer-gap-benchmark.cjs",
    "renderer-gap-benchmark.html",
    "support.js",
    "storyatlas-mockup-v2.html",
    ...plates.map((name) => `${name}.dc.html`)
  ];
  const sources = sourceNames.sort().map((name) => {
    const bytes = fs.readFileSync(path.join(sourceDir, name));
    return { path: name, bytes: bytes.length, sha256: sha256(bytes) };
  });
  const outputNames = [
    "index.html",
    "renderer-gap-benchmark.json",
    "renderer-gap-benchmark.png",
    ...entries.flatMap((entry) => [entry.html, entry.png])
  ];
  const outputs = outputNames.sort().map((name) => {
    const bytes = fs.readFileSync(path.join(reviewDir, name));
    return { path: name, bytes: bytes.length, sha256: sha256(bytes) };
  });
  const payload = {
    schema: "storyatlas4s.mockup-review-artifact.v1",
    fixture: "synthetic/two-boats@v0",
    sourcePolicy: "SYNTHETIC PLACEHOLDER — NOT SOURCE OR MODEL OUTPUT",
    typography: "system-ui with platform fallbacks; ui-monospace with platform fallbacks",
    renderer: "storyatlas4s mockup DC resolver v1 to frozen DOM; Chromium PNG at DPR 1",
    generatorBaseRevision: require("child_process")
      .execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" })
      .trim(),
    browser: { engine: "Chromium", version: browserVersion },
    platform: { os: os.platform(), arch: os.arch() },
    views: entries,
    sources,
    outputs
  };
  const artifactDigest = sha256(Buffer.from(canonical(payload), "utf8"));
  const receipt = Object.assign({ artifactDigest, digestRule: "sha256(canonical-json(payload-without-artifactDigest))" }, payload);
  fs.writeFileSync(path.join(reviewDir, "manifest.json"), `${JSON.stringify(receipt, null, 2)}\n`);
  process.stdout.write(`${JSON.stringify({ artifactDigest, plates: entries.length, browserVersion }, null, 2)}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});

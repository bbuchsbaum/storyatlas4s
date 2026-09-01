#!/usr/bin/env node
// L1(a) static-edition Playwright laws (ADR 0002 V-T2 / V-I2 / V-D3).
//
//   sbt <overrides> "cli/run edition --out target/edition"
//   node e2e/static/static.cjs target/edition
//
// Against the live DOM of `preview.html` and each `codex-*.html` (file:// and an in-process static
// server), in a DPR-2 browser context and again at 200% CSS zoom:
//   1. sha256 of concatenated `.line` text content == receipt.json sourceChecksum (V-T2;
//      no copy of the story text lives in this repository);
//   2. every `data-name` is unique; line ids are `line/…`; piece count == the file's
//      layout.annotationFragments / receipt `names` (V-I2);
//   3. page / line counts match the LayoutReceipt; the header prints the source checksum
//      and every layout field (V-D3);
//   4. no <script>, no external resource (href/src/srcset on any element);
//   5. Reading has zero overlay pieces; Overview has some;
//   6. DOM read order is header/main/footer and overlay meaning is stated in text, not colour alone;
//   7. no dependent request, page error, or console error.
// Failures write a PNG under e2e/static/diagnostics/ (diagnostic only; not a gold).
//
// Playwright is resolved from this directory, NODE_PATH, then the global npm root.
// Install once: `npm --prefix e2e/static ci` (or `npm i`) then `npx playwright install chromium`.
// The Chromium pin is the browser Playwright 1.55.1 installs.

const { createHash } = require("node:crypto");
const { execSync } = require("node:child_process");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const { pathToFileURL } = require("node:url");

const PLAYWRIGHT_PIN = "1.55.1";
const HTML_FILES = ["preview.html", "codex-reading.html", "codex-overview.html"];

function loadPlaywright() {
  const candidates = ["playwright", "@playwright/test"];
  const here = __dirname;
  for (const name of candidates) {
    try {
      return require(require.resolve(name, { paths: [here] }));
    } catch (_) {
      /* next */
    }
  }
  for (const name of candidates) {
    try {
      return require(name);
    } catch (_) {
      /* next */
    }
  }
  const root = execSync("npm root -g").toString().trim();
  for (const name of candidates) {
    try {
      return require(path.join(root, name));
    } catch (_) {
      /* next */
    }
  }
  throw new Error(
    `cannot resolve playwright (want ${PLAYWRIGHT_PIN}): install with ` +
      "`npm --prefix e2e/static i` and `npx --prefix e2e/static playwright install chromium`"
  );
}

const failures = [];
function check(condition, message) {
  if (!condition) failures.push(message);
  console.log(`${condition ? "ok  " : "FAIL"} ${message}`);
}

function sha256Utf8(text) {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

function loadReceipt(editionDir) {
  const raw = fs.readFileSync(path.join(editionDir, "receipt.json"), "utf8");
  const receipt = JSON.parse(raw);
  if (receipt.edition !== "storyatlas4s") {
    throw new Error(`receipt.edition is ${receipt.edition}, not storyatlas4s`);
  }
  if (typeof receipt.sourceChecksum !== "string" || receipt.sourceChecksum.length !== 64) {
    throw new Error("receipt.sourceChecksum is missing or not a 64-char hex");
  }
  return receipt;
}

function fileEntry(receipt, name) {
  const entry = (receipt.files || []).find((f) => f.file === name);
  if (!entry) throw new Error(`receipt.json has no file entry for ${name}`);
  if (!entry.layout) throw new Error(`${name} has no layout receipt`);
  return entry;
}

function startStaticServer(root) {
  const mime = {
    ".html": "text/html; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".txt": "text/plain; charset=utf-8"
  };
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const urlPath = decodeURIComponent((req.url || "/").split("?")[0]);
      if (urlPath === "/favicon.ico") {
        res.writeHead(204).end();
        return;
      }
      const rel = urlPath === "/" ? "index.html" : urlPath.replace(/^\/+/, "");
      const file = path.normalize(path.join(root, rel));
      if (!file.startsWith(root)) {
        res.writeHead(403).end();
        return;
      }
      fs.readFile(file, (err, data) => {
        if (err) {
          res.writeHead(404).end("not found");
          return;
        }
        res.writeHead(200, { "Content-Type": mime[path.extname(file)] || "application/octet-stream" });
        res.end(data);
      });
    });
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      resolve({ server, origin: `http://127.0.0.1:${port}` });
    });
    server.on("error", reject);
  });
}

async function screenshotOnFailure(page, diagnosticsDir, label) {
  if (failures.length === 0) return;
  fs.mkdirSync(diagnosticsDir, { recursive: true });
  const dest = path.join(diagnosticsDir, `${label.replace(/[^\w.-]+/g, "_")}.png`);
  try {
    await page.screenshot({ path: dest, fullPage: true });
    console.log(`diagnostic screenshot ${dest}`);
  } catch (err) {
    console.log(`diagnostic screenshot failed: ${err}`);
  }
}

async function assertHtml(page, url, receipt, entry, transport) {
  const name = entry.file;
  const layout = entry.layout;
  const pageErrors = [];
  const consoleErrors = [];
  const requests = [];
  const onPageError = (e) => pageErrors.push(String(e));
  const onRequest = (request) => requests.push(request.url());
  const onConsole = (m) => {
    if (m.type() !== "error") return;
    const text = m.text();
    // Chromium always requests /favicon.ico; the edition has no external resource by design.
    if (text.includes("favicon.ico")) return;
    consoleErrors.push(text);
  };
  page.on("pageerror", onPageError);
  page.on("console", onConsole);
  page.on("request", onRequest);

  await page.goto(url, { waitUntil: "domcontentloaded" });
  await page.waitForSelector("section.page .text .line", { timeout: 15000 });

  const rail = await page.$$eval("section.page .text .line", (spans) =>
    spans.map((s) => s.textContent).join("")
  );
  const railSha = sha256Utf8(rail);
  check(rail.length > 0, `${transport} ${name}: rail has text (${rail.length} code units)`);
  check(
    railSha === receipt.sourceChecksum,
    `${transport} ${name}: sha256(rail) == receipt.sourceChecksum`
  );
  check(
    railSha === layout.sourceChecksum,
    `${transport} ${name}: sha256(rail) == layout.sourceChecksum`
  );

  const headerChecksum = await page.$eval("header dl", (dl) => {
    const dts = [...dl.querySelectorAll("dt")];
    const dt = dts.find((el) => el.textContent === "Source checksum");
    return dt && dt.nextElementSibling ? dt.nextElementSibling.textContent : "";
  });
  check(
    headerChecksum === receipt.sourceChecksum,
    `${transport} ${name}: header Source checksum matches receipt`
  );

  const lines = await page.$$eval("section.page .text .line", (xs) =>
    xs.map((el) => el.getAttribute("data-name"))
  );
  const pages = await page.$$eval("section.page", (xs) => xs.length);
  const svgs = await page.$$eval("section.page .overlay svg", (xs) => xs.length);
  check(String(lines.length) === layout.lines, `${transport} ${name}: ${lines.length} lines == layout.lines`);
  check(String(pages) === layout.pages, `${transport} ${name}: ${pages} pages == layout.pages`);
  check(svgs === pages, `${transport} ${name}: one overlay svg per page (${svgs})`);
  check(
    lines.every((n) => typeof n === "string" && n.startsWith("line/")),
    `${transport} ${name}: every line data-name starts with line/`
  );
  check(new Set(lines).size === lines.length, `${transport} ${name}: line data-names are unique`);

  const pieces = await page.$$eval("section.page .overlay [data-name]", (xs) =>
    xs.map((el) => el.getAttribute("data-name"))
  );
  check(
    String(pieces.length) === layout.annotationFragments,
    `${transport} ${name}: ${pieces.length} pieces == layout.annotationFragments`
  );
  check(
    pieces.length === entry.names,
    `${transport} ${name}: ${pieces.length} pieces == receipt names`
  );
  check(
    pieces.every((n) => typeof n === "string" && n.length > 0 && !n.startsWith("line/")),
    `${transport} ${name}: overlay data-names are piece ids, not line ids`
  );
  check(new Set(pieces).size === pieces.length, `${transport} ${name}: piece data-names are unique`);

  const allNames = await page.$$eval("[data-name]", (xs) => xs.map((el) => el.getAttribute("data-name")));
  check(new Set(allNames).size === allNames.length, `${transport} ${name}: all data-name values unique`);
  check(
    allNames.length === lines.length + pieces.length,
    `${transport} ${name}: data-name count is lines + pieces`
  );

  if (name === "codex-reading.html") {
    check(pieces.length === 0, `${transport} ${name}: Reading lens has no overlay piece`);
  } else {
    check(pieces.length > 0, `${transport} ${name}: Overview lens has overlay pieces`);
  }

  const resources = await page.evaluate(() => {
    const scripts = document.querySelectorAll("script").length;
    const external = [];
    for (const el of document.querySelectorAll("[href], [src], [srcset]")) {
      const href = el.getAttribute("href");
      const src = el.getAttribute("src");
      const srcset = el.getAttribute("srcset");
      if (href) external.push(`${el.tagName.toLowerCase()}[href=${href}]`);
      if (src) external.push(`${el.tagName.toLowerCase()}[src=${src}]`);
      if (srcset) external.push(`${el.tagName.toLowerCase()}[srcset=${srcset}]`);
    }
    const links = document.querySelectorAll("link").length;
    return { scripts, external, links };
  });
  check(resources.scripts === 0, `${transport} ${name}: no <script>`);
  check(resources.links === 0, `${transport} ${name}: no <link>`);
  check(
    resources.external.length === 0,
    `${transport} ${name}: no href/src/srcset ${resources.external.length ? JSON.stringify(resources.external) : ""}`
  );
  const unexpectedRequests = requests.filter(
    (requestUrl) => requestUrl !== url && !requestUrl.endsWith("/favicon.ico")
  );
  check(
    unexpectedRequests.length === 0,
    `${transport} ${name}: no dependent request ${unexpectedRequests.length ? JSON.stringify(unexpectedRequests) : ""}`
  );

  const documentOrder = await page.$$eval("body > *", (elements) =>
    elements.map((element) => element.tagName.toLowerCase())
  );
  check(
    documentOrder.join(",") === "header,main,footer",
    `${transport} ${name}: DOM read order is header, main, footer`
  );

  const layoutKeys = [
    "paginator",
    "pageWidthPx",
    "pageHeightPx",
    "linesPerPage",
    "measurer",
    "fontFamily",
    "fontSizePx",
    "unitsPerPixel",
    "pages",
    "lines",
    "annotationFragments"
  ];
  const header = await page.$$eval("header dl dt", (dts) =>
    dts.map((dt) => [dt.textContent, dt.nextElementSibling ? dt.nextElementSibling.textContent : ""])
  );
  const headerMap = Object.fromEntries(header);
  for (const key of layoutKeys) {
    check(
      headerMap[key] === layout[key],
      `${transport} ${name}: header ${key} == layout.${key}`
    );
  }
  check(
    typeof headerMap.Basis === "string" && headerMap.Basis.includes("researcher-reviewed"),
    `${transport} ${name}: header prints the researcher-reviewed basis`
  );
  check(
    typeof headerMap["Overlay rows"] === "string" && headerMap["Overlay rows"].length > 0,
    `${transport} ${name}: overlay meaning is stated in text, not colour alone`
  );
  const visibleLegend = await page.$eval("header .legend", (legend) => ({
    text: legend.textContent || "",
    visible: legend.getBoundingClientRect().height > 0
  }));
  check(
    visibleLegend.visible && visibleLegend.text.startsWith("Overlay:"),
    `${transport} ${name}: compact overlay legend is visible without opening the receipt`
  );
  const receiptDisclosure = await page.$eval("header details", (details) => ({
    open: details.open,
    summary: details.querySelector("summary")?.textContent || ""
  }));
  check(!receiptDisclosure.open, `${transport} ${name}: detailed receipt is initially collapsed`);
  check(
    receiptDisclosure.summary === "Provenance and layout receipt",
    `${transport} ${name}: receipt disclosure has an explicit accessible label`
  );

  const devicePixelRatio = await page.evaluate(() => window.devicePixelRatio);
  check(devicePixelRatio === 2, `${transport} ${name}: devicePixelRatio is 2`);
  let qaDir;
  if (transport === "file" && name === "preview.html" && process.env.STORYATLAS_VISUAL_QA_DIR) {
    qaDir = path.resolve(process.env.STORYATLAS_VISUAL_QA_DIR);
    fs.mkdirSync(qaDir, { recursive: true });
    const screenshot = path.join(qaDir, "preview-file-dpr2.png");
    await page.screenshot({ path: screenshot });
    console.log(`visual QA screenshot ${screenshot}`);
  }
  await page.evaluate(() => {
    document.documentElement.style.zoom = "2";
  });
  const zoomed = await page.evaluate(() => ({
    rail: [...document.querySelectorAll("section.page .text .line")]
      .map((line) => line.textContent)
      .join(""),
    headerVisible: document.querySelector("header")?.getBoundingClientRect().height > 0,
    mainVisible: document.querySelector("main")?.getBoundingClientRect().height > 0
  }));
  check(sha256Utf8(zoomed.rail) === receipt.sourceChecksum, `${transport} ${name}: 200% zoom preserves canonical DOM text`);
  check(zoomed.headerVisible && zoomed.mainVisible, `${transport} ${name}: 200% zoom keeps header and preview visible`);
  if (qaDir) {
    const screenshot = path.join(qaDir, "preview-file-dpr2-zoom200.png");
    await page.screenshot({ path: screenshot });
    console.log(`visual QA screenshot ${screenshot}`);
  }
  await page.evaluate(() => {
    document.documentElement.style.zoom = "";
  });

  check(pageErrors.length === 0, `${transport} ${name}: no page error ${pageErrors.length ? JSON.stringify(pageErrors) : ""}`);
  check(
    consoleErrors.length === 0,
    `${transport} ${name}: no console error ${consoleErrors.length ? JSON.stringify(consoleErrors) : ""}`
  );

  page.off("pageerror", onPageError);
  page.off("console", onConsole);
  page.off("request", onRequest);
}

function diskSha(editionDir, name) {
  const bytes = fs.readFileSync(path.join(editionDir, name));
  return createHash("sha256").update(bytes).digest("hex");
}

async function main() {
  const editionArg = process.argv[2];
  if (!editionArg) {
    console.error("usage: node e2e/static/static.cjs <edition-dir>");
    process.exit(2);
  }
  const editionDir = path.resolve(editionArg);
  const receiptPath = path.join(editionDir, "receipt.json");
  if (!fs.existsSync(receiptPath)) {
    console.error(`no receipt.json in ${editionDir}; run: sbt <overrides> "cli/run edition --out ${editionDir}"`);
    process.exit(2);
  }
  for (const name of HTML_FILES) {
    if (!fs.existsSync(path.join(editionDir, name))) {
      console.error(`missing ${name} in ${editionDir}`);
      process.exit(2);
    }
  }

  const receipt = loadReceipt(editionDir);
  console.log(`playwright pin ${PLAYWRIGHT_PIN}; edition ${editionDir}`);
  console.log(`sourceChecksum ${receipt.sourceChecksum.slice(0, 12)}…`);

  for (const name of HTML_FILES) {
    const entry = fileEntry(receipt, name);
    const hex = diskSha(editionDir, name);
    check(hex === entry.sha256, `on disk ${name}: sha256 == receipt files[].sha256`);
  }

  const { chromium } = loadPlaywright();
  const home = process.env.HOME || "";
  const fallbackChrome = [
    process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,
    path.join(home, "Library/Caches/ms-playwright/chromium-1193/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
    path.join(home, "Library/Caches/ms-playwright/chromium-1200/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
    path.join(home, "Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  ].find((p) => p && fs.existsSync(p));
  const launch = { headless: true };
  if (fallbackChrome) {
    launch.executablePath = fallbackChrome;
    console.log(`chromium executable ${fallbackChrome}`);
  }
  const browser = await chromium.launch(launch);
  const context = await browser.newContext({
    viewport: { width: 900, height: 1200 },
    deviceScaleFactor: 2
  });
  const page = await context.newPage();
  const diagnosticsDir = path.join(__dirname, "diagnostics");

  try {
    for (const name of HTML_FILES) {
      const entry = fileEntry(receipt, name);
      const fileUrl = pathToFileURL(path.join(editionDir, name)).href;
      const before = failures.length;
      await assertHtml(page, fileUrl, receipt, entry, "file");
      if (failures.length > before) await screenshotOnFailure(page, diagnosticsDir, `file-${name}`);
    }

    const { server, origin } = await startStaticServer(editionDir);
    try {
      for (const name of HTML_FILES) {
        const entry = fileEntry(receipt, name);
        const before = failures.length;
        await assertHtml(page, `${origin}/${name}`, receipt, entry, "http");
        if (failures.length > before) await screenshotOnFailure(page, diagnosticsDir, `http-${name}`);
      }
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }
  } finally {
    await context.close();
    await browser.close();
  }

  if (failures.length) {
    console.error(`\n${failures.length} static-edition check(s) failed`);
    process.exit(1);
  }
  console.log("\nstatic-edition playwright laws passed");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

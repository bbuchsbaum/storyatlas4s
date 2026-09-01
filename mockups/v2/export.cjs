"use strict";

const childProcess = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { pathToFileURL } = require("url");
const {
  ARTIFACT_RULE,
  REPRODUCIBLE_RULE,
  artifactTarget,
  digest,
  reproducibleProjection,
  reproducibleTarget,
  sha256,
  verifyManifest
} = require("./manifest-digest.cjs");
const { loadAndVerifyFixtures, mutationCourt: fixtureMutationCourt } = require("./fixture-court.cjs");

const root = path.resolve(__dirname, "../..");
const sourceDir = __dirname;
const reviewDir = path.join(sourceDir, "review");
const DEVICE_SCALE_FACTOR = 2;

const plateSpecs = [
  { name: "Main", source: "Main" },
  { name: "ChronologyLoom", source: "Main", state: { projection: "chronology" } },
  { name: "FeatureScaleSpace", source: "Main", state: { projection: "feature" } },
  { name: "RecallMatrix", source: "RecallMatrix" },
  { name: "InterviewMode", source: "InterviewMode" },
  { name: "Compact", source: "Compact" },
  { name: "Reflow", source: "Reflow" },
  { name: "FocusOrder", source: "FocusOrder" },
  { name: "Grayscale", source: "Grayscale" },
  { name: "Motion", source: "Motion" },
  { name: "StateBoard", source: "StateBoard" },
  { name: "Tokens", source: "Tokens" },
  { name: "Concept", source: "Concept" },
  { name: "Directions", source: "Directions" },
  { name: "RendererGap", source: "RendererGap" }
];
const sourcePlates = [...new Set(plateSpecs.map((plate) => plate.source))];

const plateObservations = {
  Main: ["synthetic/two-boats-source@v1"],
  ChronologyLoom: [
    "synthetic/two-boats-source@v1",
    "synthetic/two-boats-recall-p01@v1"
  ],
  FeatureScaleSpace: ["synthetic/two-boats-source@v1"],
  RecallMatrix: [
    "synthetic/two-boats-source@v1",
    "synthetic/two-boats-recall-p01@v1"
  ],
  InterviewMode: ["synthetic/two-boats-interview-p07@v1"],
  Compact: ["synthetic/two-boats-source@v1"],
  Reflow: ["synthetic/two-boats-source@v1"],
  FocusOrder: ["synthetic/two-boats-source@v1"],
  Grayscale: ["synthetic/two-boats-source@v1"],
  StateBoard: [],
  Tokens: ["synthetic/two-boats-source@v1"],
  Concept: ["synthetic/two-boats-source@v1"],
  Directions: [],
  Motion: [],
  RendererGap: []
};

const requiredObservationRefs = {
  Main: {
    "synthetic/two-boats-source@v1": ["s01", "s02", "s03", "s04", "s05", "s06", "s07", "s08", "s09", "s10"]
  },
  ChronologyLoom: {
    "synthetic/two-boats-source@v1": ["s01", "s02", "s03", "s04", "s05", "s06", "s07", "s08", "s09", "s10"]
  },
  FeatureScaleSpace: {
    "synthetic/two-boats-source@v1": ["s01", "s02", "s03", "s04", "s05", "s06", "s07", "s08", "s09", "s10"]
  },
  RecallMatrix: {
    "synthetic/two-boats-source@v1": ["s01", "s02", "s03", "s04", "s05", "s06", "s07", "s08", "s09", "s10"],
    "synthetic/two-boats-recall-p01@v1": ["r01", "r02", "r03", "r04", "r05", "r06", "r07", "r08", "r09", "r10", "r11"]
  },
  InterviewMode: {
    "synthetic/two-boats-interview-p07@v1": ["t01", "t02", "t03", "t04", "t05", "t06", "t07", "t08", "t09", "t10"]
  },
  Compact: {
    "synthetic/two-boats-source@v1": ["s01", "s02", "s03", "s04", "s05", "s06", "s07", "s08", "s09", "s10"]
  },
  Reflow: {
    "synthetic/two-boats-source@v1": ["s04", "s05", "s06"]
  }
};

const requiredFixtureReceipts = {
  ChronologyLoom: [
    "synthetic/two-boats-source@v1",
    "synthetic/two-boats-recall-p01@v1"
  ],
  FeatureScaleSpace: ["synthetic/two-boats-source@v1"]
};

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
  for (const plate of sourcePlates) {
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
        `<a href="${entry.png}">PNG plate</a> · CSS ${entry.viewport.width}×${entry.viewport.height} · ` +
        `DPR ${entry.deviceScaleFactor} · pixels ${entry.screenshot.width}×${entry.screenshot.height}</li>`
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

async function frozenHtml(page, sourceName, plateName) {
  return page.evaluate(({ name, plate }) => {
    const liveArtboard = document.querySelector("[data-dc-artboard]");
    if (!liveArtboard) throw new Error(`${name}: missing declared artboard before freeze`);
    const liveArtboardWidth = liveArtboard.getBoundingClientRect().width;
    const clone = document.documentElement.cloneNode(true);
    clone.querySelectorAll("script").forEach((node) => node.remove());
    clone.removeAttribute("data-dc-resolved");
    clone.removeAttribute("data-dc-error");
    clone.setAttribute("lang", "en");
    clone.setAttribute("data-storyatlas-frozen", "true");

    for (const element of Array.from(clone.querySelectorAll("*"))) {
      for (const attribute of Array.from(element.attributes || [])) {
        if (/^on/i.test(attribute.name)) element.removeAttribute(attribute.name);
      }
      element.removeAttribute("tabindex");
      element.removeAttribute("aria-pressed");
      element.removeAttribute("aria-selected");
      element.removeAttribute("aria-expanded");
      element.removeAttribute("aria-controls");
      element.removeAttribute("aria-haspopup");
      element.removeAttribute("aria-activedescendant");
      element.removeAttribute("aria-live");
      if (element.getAttribute("role") === "button") element.removeAttribute("role");
      if (element.style) element.style.removeProperty("cursor");
    }

    const head = clone.querySelector("head");
    const oldTitle = head.querySelector("title");
    if (oldTitle) oldTitle.remove();
    const title = document.createElement("title");
    title.textContent = `StoryAtlas mockup v2 — ${plate} — frozen design specification`;
    head.appendChild(title);
    if (!head.querySelector('meta[name="viewport"]')) {
      const viewport = document.createElement("meta");
      viewport.setAttribute("name", "viewport");
      viewport.setAttribute("content", "width=device-width, initial-scale=1");
      head.appendChild(viewport);
    }
    const inertStyle = document.createElement("style");
    inertStyle.setAttribute("data-frozen-inert-style", "true");
    inertStyle.textContent = "*{cursor:default!important}";
    head.appendChild(inertStyle);
    const meta = document.createElement("meta");
    meta.setAttribute("name", "storyatlas-frozen-source");
    meta.setAttribute("content", name);
    head.appendChild(meta);

    const artboard = clone.querySelector("[data-dc-artboard]");
    if (!artboard) throw new Error(`${name}: missing declared artboard during freeze`);
    artboard.style.position = "relative";
    const notice = document.createElement("div");
    notice.setAttribute("data-frozen-notice", "true");
    notice.style.cssText =
      "margin-left:auto;flex:none;padding:2px 7px;border:1px solid currentColor;" +
      "background:transparent;color:inherit;font:700 9.5px/1.25 system-ui,sans-serif;" +
      "letter-spacing:.045em;text-align:center;white-space:nowrap";
    notice.textContent = "FROZEN DESIGN SPECIFICATION — CONTROLS ARE INERT";
    const bannerLeaf = Array.from(artboard.querySelectorAll("span,div")).find((element) =>
      element.children.length === 0 &&
      (element.textContent || "").trim().startsWith("SYNTHETIC PLACEHOLDER — NOT SOURCE OR MODEL OUTPUT")
    );
    const banner = bannerLeaf && bannerLeaf.parentElement;
    if (banner && banner !== artboard) {
      banner.appendChild(notice);
    } else if (liveArtboardWidth < 700) {
      notice.style.cssText =
        "position:static;z-index:9999;box-sizing:border-box;width:100%;padding:7px 12px;" +
        "border:3px double #221D19;background:#F7E6C3;color:#221D19;" +
        "font:700 11.5px/1.35 system-ui,sans-serif;letter-spacing:.045em;text-align:center;flex:none";
      artboard.insertBefore(notice, artboard.children[1] || null);
    } else {
      notice.style.cssText += ";position:absolute;right:0;top:0;background:#F7E6C3;color:#221D19";
      artboard.appendChild(notice);
    }
    return `<!doctype html>\n${clone.outerHTML}\n`;
  }, { name: sourceName, plate: plateName });
}

async function plateCourt(page, expected, plateName, fixtureRegistry) {
  return page.evaluate(async ({ size, plate, fixtures, required, requiredReceipts }) => {
    const xdcRoots = Array.from(document.querySelectorAll("x-dc"));
    const artboards = Array.from(document.querySelectorAll("[data-dc-artboard]"));
    if (xdcRoots.length !== 1) return { error: `expected-one-x-dc:${xdcRoots.length}` };
    if (artboards.length !== 1) return { error: `expected-one-artboard:${artboards.length}` };
    const directVisualChildren = Array.from(xdcRoots[0].children).filter((element) => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0;
    });
    if (directVisualChildren.length !== 1 || directVisualChildren[0] !== artboards[0]) {
      return { error: `expected-one-direct-visual-root:${directVisualChildren.length}` };
    }
    const artboard = artboards[0];
    const box = artboard.getBoundingClientRect();
    const offenders = [];
    for (const element of artboard.querySelectorAll("*")) {
      const style = getComputedStyle(element);
      if (style.position === "fixed" || style.position === "absolute") continue;
      const scrollAncestor = element.closest("[data-dc-scroll-region]");
      if (scrollAncestor && scrollAncestor !== element) continue;
      const rect = element.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) continue;
      if (
        rect.left < box.left - 1 ||
        rect.right > box.right + 1 ||
        rect.top < box.top - 1 ||
        rect.bottom > box.bottom + 1
      ) {
        offenders.push({ tag: element.tagName.toLowerCase(), left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom });
        if (offenders.length === 12) break;
      }
    }

    const active = Array.from(document.querySelectorAll("*")).filter((element) =>
      element.getAttribute("role") === "button" ||
      element.hasAttribute("aria-pressed") ||
      element.hasAttribute("tabindex") ||
      Array.from(element.attributes || []).some((attribute) => /^on/i.test(attribute.name)) ||
      getComputedStyle(element).cursor === "pointer"
    );
    const metadata = {
      lang: document.documentElement.lang,
      title: document.title,
      viewport: Boolean(document.querySelector('meta[name="viewport"]')),
      frozenNotice: Boolean(document.querySelector("[data-frozen-notice]")),
      syntheticBanner: Array.from(artboard.querySelectorAll("*")).some((element) =>
        element.children.length === 0 &&
        (element.textContent || "").trim().startsWith(
          "SYNTHETIC PLACEHOLDER — NOT SOURCE OR MODEL OUTPUT"
        )
      ),
      devicePixelRatio: window.devicePixelRatio
    };

    function visible(element) {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return (
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        Number.parseFloat(style.opacity || "1") > 0 &&
        rect.width > 0 &&
        rect.height > 0
      );
    }

    function overlap(a, b) {
      return {
        width: Math.min(a.right, b.right) - Math.max(a.left, b.left),
        height: Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
      };
    }

    const svgTextClipping = [];
    const svgTextOverlaps = [];
    for (const svg of artboard.querySelectorAll("svg")) {
      if (!visible(svg)) continue;
      const sr = svg.getBoundingClientRect();
      const texts = Array.from(svg.querySelectorAll("text")).filter(visible);
      for (const node of texts) {
        const tr = node.getBoundingClientRect();
        if (
          tr.left < sr.left - 0.75 ||
          tr.right > sr.right + 0.75 ||
          tr.top < sr.top - 0.75 ||
          tr.bottom > sr.bottom + 0.75
        ) {
          svgTextClipping.push({
            text: (node.textContent || "").trim(),
            textRect: { left: tr.left, top: tr.top, right: tr.right, bottom: tr.bottom },
            svgRect: { left: sr.left, top: sr.top, right: sr.right, bottom: sr.bottom }
          });
        }
      }
      for (let left = 0; left < texts.length; left += 1) {
        const lr = texts[left].getBoundingClientRect();
        for (let right = left + 1; right < texts.length; right += 1) {
          const rr = texts[right].getBoundingClientRect();
          const intersection = overlap(lr, rr);
          if (intersection.width > 0.75 && intersection.height > 0.75) {
            svgTextOverlaps.push({
              left: (texts[left].textContent || "").trim(),
              right: (texts[right].textContent || "").trim(),
              width: intersection.width,
              height: intersection.height
            });
          }
        }
      }
    }

    const labelMarkOverlaps = [];
    for (const label of artboard.querySelectorAll("[data-clear-label]")) {
      if (!visible(label)) continue;
      const group = label.dataset.clearLabel;
      const lr = label.getBoundingClientRect();
      for (const mark of artboard.querySelectorAll(`[data-clear-mark="${group}"]`)) {
        if (!visible(mark)) continue;
        const intersection = overlap(lr, mark.getBoundingClientRect());
        if (intersection.width > 0.75 && intersection.height > 0.75) {
          labelMarkOverlaps.push({ group, width: intersection.width, height: intersection.height });
        }
      }
    }

    const blankBands = [];
    if (plate === "Tokens") {
      const boundary = artboard.querySelector("[data-no-blank-band]");
      const previous = boundary && boundary.previousElementSibling;
      if (boundary && previous) {
        const gap = boundary.getBoundingClientRect().top - previous.getBoundingClientRect().bottom;
        if (gap > 32) blankBands.push({ location: "before-footer", pixels: gap });
      }
    }
    if (plate === "FocusOrder") {
      const visibleChildren = Array.from(artboard.children).filter(visible);
      const lastBottom = Math.max(...visibleChildren.map((element) => element.getBoundingClientRect().bottom));
      const gap = box.bottom - lastBottom;
      if (gap > 32) blankBands.push({ location: "artboard-tail", pixels: gap });
    }

    const fixtureById = Object.fromEntries(fixtures.map((fixture) => [fixture.id, fixture]));
    const renderedReceiptIds = new Set(
      Array.from(document.querySelectorAll("[data-fixture-receipts]"))
        .flatMap((element) => (element.dataset.fixtureReceipts || "").split("|"))
        .filter(Boolean)
    );
    const fixtureReceiptChecks = [];
    for (const fixtureId of requiredReceipts || []) {
      if (!fixtureById[fixtureId]) return { error: `unknown-receipt-fixture:${fixtureId}` };
      if (!renderedReceiptIds.has(fixtureId)) return { error: `missing-fixture-receipt:${fixtureId}` };
      fixtureReceiptChecks.push(fixtureId);
    }
    const observationGroups = new Map();
    for (const element of document.querySelectorAll("[data-observation-fixture][data-observation-ref]")) {
      const key = `${element.dataset.observationFixture}|${element.dataset.observationRef}`;
      if (!observationGroups.has(key)) observationGroups.set(key, []);
      observationGroups.get(key).push(element);
    }
    const observationChecks = [];
    for (const [fixtureId, refs] of Object.entries(required || {})) {
      const fixture = fixtureById[fixtureId];
      if (!fixture) return { error: `unknown-required-fixture:${fixtureId}` };
      const unitById = Object.fromEntries(fixture.units.map((unit) => [unit.id, unit]));
      for (const ref of refs) {
        const nodes = observationGroups.get(`${fixtureId}|${ref}`) || [];
        if (nodes.length === 0) return { error: `missing-observation:${fixtureId}/${ref}` };
        const actual = nodes.map((node) => (node.textContent || "").trim()).join(" ");
        const expectedText = unitById[ref] && unitById[ref].text;
        if (actual !== expectedText) {
          return { error: `observation-byte-drift:${fixtureId}/${ref}`, actual, expected: expectedText };
        }
        observationChecks.push(`${fixtureId}/${ref}`);
      }
    }

    const featureLedgerChecks = [];
    if (plate === "Main" || plate === "FeatureScaleSpace") {
      const sourceFixture = fixtureById["synthetic/two-boats-source@v1"];
      const probe = sourceFixture && sourceFixture.featureProbe;
      if (!probe) return { error: "missing-feature-probe" };
      const declaredByUnit = Object.fromEntries(
        probe.sentenceCoverage.map((coverage) => [coverage.unit, coverage])
      );
      const unitNodes = Array.from(document.querySelectorAll("[data-feature-unit]"));
      if (unitNodes.length !== probe.sentenceCoverage.length) {
        return { error: "feature-unit-count", actual: unitNodes.length };
      }
      for (const node of unitNodes) {
        const declared = declaredByUnit[node.dataset.featureUnit];
        if (
          !declared ||
          Number(node.dataset.featureEligible) !== declared.eligible ||
          Number(node.dataset.featureObserved) !== declared.observed
        ) {
          return { error: "feature-unit-drift", unit: node.dataset.featureUnit };
        }
      }
      const trackNode = document.querySelector(
        `[data-feature-track="${probe.id}"]`
      );
      if (
        !trackNode ||
        Number(trackNode.dataset.featureEligible) !== probe.trackCoverage.eligible ||
        Number(trackNode.dataset.featureObserved) !== probe.trackCoverage.observed ||
        !trackNode.textContent.includes("92/117") ||
        !trackNode.textContent.includes("missing 25")
      ) {
        return { error: "feature-track-rendering-drift" };
      }
      const windowNode = document.querySelector(
        `[data-feature-window="${probe.selectedWindow.id}"]`
      );
      if (
        !windowNode ||
        Number(windowNode.dataset.featureEligible) !== probe.selectedWindow.eligible ||
        Number(windowNode.dataset.featureObserved) !== probe.selectedWindow.observed ||
        !windowNode.textContent.includes("KeepPartial")
      ) {
        return { error: "feature-window-rendering-drift" };
      }
      featureLedgerChecks.push({
        id: probe.id,
        units: unitNodes.length,
        trackCoverage: probe.trackCoverage,
        selectedWindow: probe.selectedWindow
      });
    }

    const exactTypography = [];
    for (const element of document.querySelectorAll("[data-dc-exact-evidence]")) {
      const style = getComputedStyle(element);
      const sizePx = Number.parseFloat(style.fontSize);
      const linePx = Number.parseFloat(style.lineHeight);
      const clamp = style.getPropertyValue("-webkit-line-clamp");
      if (sizePx < 16 || linePx < 24.7 || (clamp && clamp !== "none")) {
        return {
          error: "exact-evidence-typography",
          ref: element.dataset.observationRef || null,
          sizePx,
          linePx,
          clamp
        };
      }
      exactTypography.push({ ref: element.dataset.observationRef || null, sizePx, linePx });
    }

    function clippingAxes(element) {
      const style = getComputedStyle(element);
      return {
        x: ["auto", "scroll", "hidden", "clip"].includes(style.overflowX),
        y: ["auto", "scroll", "hidden", "clip"].includes(style.overflowY)
      };
    }

    function clippingChain(item) {
      const chain = [];
      for (let element = item; element; element = element.parentElement) {
        if (!artboard.contains(element) && element !== artboard) break;
        const axes = clippingAxes(element);
        if (axes.x || axes.y) chain.push({ element, axes, self: element === item });
        if (element === artboard) break;
      }
      return chain;
    }

    const clippedEvidence = [];
    for (const item of document.querySelectorAll("[data-observation-ref]")) {
      for (const clipping of clippingChain(item)) {
        const element = clipping.element;
        const overflows =
          (clipping.axes.y && element.scrollHeight > element.clientHeight + 1) ||
          (clipping.axes.x && element.scrollWidth > element.clientWidth + 1);
        // A scroll viewport may intentionally clip descendants, but an evidence element must
        // never clip its own exact text. Scroll regions therefore have to remain ancestors.
        if (
          overflows &&
          (clipping.self || !element.hasAttribute("data-dc-scroll-region"))
        ) {
          clippedEvidence.push({
            ref: item.dataset.observationRef || null,
            tag: element.tagName.toLowerCase(),
            id: element.id || null,
            self: clipping.self
          });
          break;
        }
      }
    }

    const reachability = [];
    for (const region of document.querySelectorAll("[data-dc-scroll-region]")) {
      const style = getComputedStyle(region);
      if (!["auto", "scroll"].includes(style.overflowY)) {
        return { error: "scroll-region-not-scrollable", region: region.dataset.dcScrollRegion };
      }
      const oldTop = region.scrollTop;
      for (const item of region.querySelectorAll("[data-observation-ref]")) {
        item.scrollIntoView({ block: "nearest", inline: "nearest" });
        await new Promise((resolve) => requestAnimationFrame(resolve));
        const rr = region.getBoundingClientRect();
        const ir = item.getBoundingClientRect();
        const visible = { left: rr.left, top: rr.top, right: rr.right, bottom: rr.bottom };
        for (const clipping of clippingChain(item)) {
          const ar = clipping.element.getBoundingClientRect();
          if (clipping.axes.x) {
            visible.left = Math.max(visible.left, ar.left);
            visible.right = Math.min(visible.right, ar.right);
          }
          if (clipping.axes.y) {
            visible.top = Math.max(visible.top, ar.top);
            visible.bottom = Math.min(visible.bottom, ar.bottom);
          }
        }
        const fullyVisible =
          visible.right - visible.left > 1 &&
          visible.bottom - visible.top > 1 &&
          ir.left >= visible.left - 1 &&
          ir.right <= visible.right + 1 &&
          ir.top >= visible.top - 1 &&
          ir.bottom <= visible.bottom + 1;
        if (!fullyVisible) {
          return {
            error: "observation-clipped-by-ancestor",
            region: region.dataset.dcScrollRegion,
            ref: item.dataset.observationRef,
            item: { left: ir.left, top: ir.top, right: ir.right, bottom: ir.bottom },
            visible
          };
        }
      }
      region.scrollTop = oldTop;
      reachability.push(region.dataset.dcScrollRegion || "unnamed");
    }

    const layout = artboard.dataset.dcLayout;
    const fixed = layout === "fixed";
    const flowing = layout === "flowing";
    if (!fixed && !flowing) return { error: `unknown-layout-contract:${layout}` };
    const extent = {
      width: document.documentElement.scrollWidth,
      height: document.documentElement.scrollHeight
    };
    if (Math.abs(box.left) > 1 || Math.abs(box.top) > 1 || Math.abs(box.width - size.width) > 1) {
      return { error: "artboard-origin-or-width", box: { left: box.left, top: box.top, width: box.width }, size };
    }
    if (fixed && (Math.abs(box.height - size.height) > 1 || Math.abs(extent.height - size.height) > 1)) {
      return { error: "fixed-artboard-extent", box: { height: box.height }, extent, size };
    }
    if (flowing && (box.height + 1 < size.height || Math.abs(extent.height - box.height) > 1)) {
      return { error: "flowing-artboard-extent", box: { height: box.height }, extent, size };
    }
    return {
      plate,
      viewport: size,
      layout,
      artboard: { width: box.width, height: box.height },
      scroll: extent,
      directVisualRoots: directVisualChildren.length,
      metadata,
      activeSemantics: active.length,
      observationChecks,
      fixtureReceiptChecks,
      featureLedgerChecks,
      exactTypography,
      clippedEvidence,
      reachability,
      svgTextClipping,
      svgTextOverlaps,
      labelMarkOverlaps,
      blankBands,
      offenders
    };
  }, {
    size: expected,
    plate: plateName,
    fixtures: fixtureRegistry.fixtures,
    required: requiredObservationRefs[plateName] || {},
    requiredReceipts: requiredFixtureReceipts[plateName] || []
  });
}

function pngDimensions(bytes) {
  if (bytes.length < 24 || bytes.toString("ascii", 1, 4) !== "PNG") {
    throw new Error("screenshot is not a PNG");
  }
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

async function directiveCourt(context, temporarySourceDir) {
  const cases = [
    {
      name: "valid-nested-for-if",
      shouldPass: true,
      body:
        '<template data-dc-directive="for" list="{{ items }}" as="item"><template data-dc-directive="if" value="{{ item.show }}"><span class="value">{{ item.name }}</span></template></template>',
      values: "{items:[{name:'alpha',show:true},{name:'beta',show:false},{name:'gamma',show:true}]}"
    },
    {
      name: "unknown-template-kind",
      shouldPass: false,
      body: '<template data-dc-directive="mystery" list="{{ items }}" as="item"><span>{{ item }}</span></template>',
      values: "{items:['x']}"
    },
    {
      name: "unknown-sc-tag",
      shouldPass: false,
      body: '<sc-unless value="{{ ok }}"><span>bad</span></sc-unless>',
      values: "{ok:true}"
    },
    {
      name: "partial-list-expression",
      shouldPass: false,
      body: '<template data-dc-directive="for" list="prefix {{ items }}" as="item"><span>{{ item }}</span></template>',
      values: "{items:['x']}"
    }
  ];
  const results = [];
  for (const testCase of cases) {
    const filename = `directive-${testCase.name}.html`;
    const source = `<!doctype html><html><head><meta charset="utf-8"><script src="./support.js"></script></head><body><x-dc><div data-dc-artboard data-dc-layout="fixed" style="width:320px;height:180px">${testCase.body}</div></x-dc><script data-dc-script>class Component extends DCLogic { renderVals(){ return ${testCase.values}; } }</script></body></html>`;
    const target = path.join(temporarySourceDir, filename);
    fs.writeFileSync(target, source);
    const page = await context.newPage();
    try {
      await page.setViewportSize({ width: 320, height: 180 });
      await page.goto(pathToFileURL(target).href, { waitUntil: "load" });
      await page.waitForFunction(
        () => ["true", "false"].includes(document.documentElement.dataset.dcResolved),
        null,
        { timeout: 5000 }
      );
      const resolved = await page.getAttribute("html", "data-dc-resolved");
      const error = await page.getAttribute("html", "data-dc-error");
      if (testCase.shouldPass) {
        const text = await page.locator(".value").allTextContents();
        if (resolved !== "true" || error || text.join(",") !== "alpha,gamma") {
          throw new Error(`${testCase.name} positive control failed`);
        }
        results.push({ name: testCase.name, pass: true, rendered: text });
      } else {
        if (resolved !== "false" || !error) {
          throw new Error(`${testCase.name} mutation survived`);
        }
        results.push({ name: testCase.name, killed: true, diagnostic: error });
      }
    } finally {
      await page.close();
    }
  }
  return results;
}

async function mainClaimCourt(page, fixtureRegistry) {
  const source = fixtureRegistry.fixtures.find(
    (fixture) => fixture.id === "synthetic/two-boats-source@v1"
  );
  if (!source) throw new Error("claim court has no canonical source fixture");
  const records = [];
  const summaries = [];
  for (const unit of source.units) {
    await page.evaluate((unitId) => {
      const row = document.querySelector(`[data-main-sentence="${unitId}"]`);
      if (!row) throw new Error(`missing selectable sentence ${unitId}`);
      row.click();
    }, unit.id);
    await page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    );
    const claims = await page.locator("[data-claim-id]").evaluateAll((nodes, unitId) =>
      nodes.map((node) => ({
        unit: unitId,
        id: node.dataset.claimId || "",
        value: node.dataset.claimValue || "",
        status: node.dataset.claimStatus || "",
        support: node.dataset.claimSupport || "",
        supportText: node.dataset.claimSupportText || "",
        upstream: node.dataset.claimUpstream || "",
        receipt: node.dataset.claimReceipt || "",
        alternativeGroup: node.dataset.claimAlternativeGroup || ""
      })), unit.id);
    if (claims.length === 0) throw new Error(`claim court found no claims for ${unit.id}`);
    const summaryWhy =
      (await page.locator("[data-main-status-why]").getAttribute("data-main-status-why")) || "";
    summaries.push({ unit: unit.id, why: summaryWhy });
    records.push(...claims);
  }
  await page.evaluate(() => document.querySelector('[data-main-sentence="s05"]').click());
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
  );

  const allowedStatuses = new Set([
    "SurfaceExplicit",
    "LinguisticallyEntailed",
    "WorldKnowledgeInferred",
    "StructurallyDerived",
    "Hypothesized",
    "HumanAdjudicated"
  ]);
  function validate(claims, summaryRecords) {
    const ids = new Set();
    const alternativeGroups = new Map();
    const statusesByUnit = new Map();
    for (const claim of claims) {
      if (!claim.id || ids.has(claim.id)) throw new Error(`duplicate or empty claim id ${claim.id}`);
      ids.add(claim.id);
      if (!allowedStatuses.has(claim.status)) {
        throw new Error(`${claim.id} uses noncanonical EpistemicStatus ${claim.status}`);
      }
      if (!statusesByUnit.has(claim.unit)) statusesByUnit.set(claim.unit, new Set());
      statusesByUnit.get(claim.unit).add(claim.status);
      if (!claim.receipt) throw new Error(`${claim.id} has no receipt`);
      if (claim.value.includes("|")) throw new Error(`${claim.id} collapses alternatives`);
      const spans = claim.support
        ? claim.support.split(";").map((part) => part.split(":").map(Number))
        : [];
      const expectedTexts = claim.supportText ? claim.supportText.split(" | ") : [];
      if (spans.length !== expectedTexts.length) {
        throw new Error(`${claim.id} support/text arity mismatch`);
      }
      for (let index = 0; index < spans.length; index += 1) {
        const [start, end] = spans[index];
        if (
          !Number.isInteger(start) ||
          !Number.isInteger(end) ||
          start < 0 ||
          end <= start ||
          source.canonicalText.slice(start, end) !== expectedTexts[index]
        ) {
          throw new Error(`${claim.id} source span does not round-trip`);
        }
      }
      const upstream = claim.upstream ? claim.upstream.split(",") : [];
      if (spans.length === 0 && upstream.length === 0) {
        throw new Error(`${claim.id} has neither spans nor upstream claims`);
      }
      if (claim.status === "SurfaceExplicit" && spans.length === 0) {
        throw new Error(`${claim.id} violates the SurfaceExplicit span law`);
      }
      if (claim.alternativeGroup) {
        if (!alternativeGroups.has(claim.alternativeGroup)) {
          alternativeGroups.set(claim.alternativeGroup, []);
        }
        alternativeGroups.get(claim.alternativeGroup).push(claim.id);
      }
    }
    for (const claim of claims) {
      for (const upstream of claim.upstream ? claim.upstream.split(",") : []) {
        if (!ids.has(upstream)) throw new Error(`${claim.id} cites missing upstream ${upstream}`);
      }
    }
    for (const [group, members] of alternativeGroups) {
      if (members.length < 2) throw new Error(`${group} does not retain separate alternatives`);
    }
    for (const summary of summaryRecords) {
      const unitStatuses = statusesByUnit.get(summary.unit) || new Set();
      for (const status of allowedStatuses) {
        if (summary.why.includes(status) && !unitStatuses.has(status)) {
          throw new Error(`${summary.unit} summary cites absent status ${status}`);
        }
      }
    }
    return { ids: [...ids].sort(), alternativeGroups: Object.fromEntries(alternativeGroups) };
  }

  const checked = validate(records, summaries);
  const mutation = JSON.parse(JSON.stringify(records));
  const alone = mutation.find((claim) => claim.id === "c-s05-alone-unaccompanied");
  alone.support = "283:290";
  let mutationKilled = false;
  try {
    validate(mutation, summaries);
  } catch (error) {
    mutationKilled = /source span does not round-trip/.test(String(error.message));
  }
  if (!mutationKilled) throw new Error("claim support-span mutation survived");
  const summaryMutation = JSON.parse(JSON.stringify(summaries));
  const s03Summary = summaryMutation.find((summary) => summary.unit === "s03");
  s03Summary.why = s03Summary.why.replace("He→Erran is Hypothesized", "He→Erran is StructurallyDerived");
  let summaryMutationKilled = false;
  try {
    validate(records, summaryMutation);
  } catch (error) {
    summaryMutationKilled = /s03 summary cites absent status StructurallyDerived/.test(
      String(error.message)
    );
  }
  if (!summaryMutationKilled) throw new Error("claim-summary status mutation survived");
  return {
    claims: records.length,
    ids: checked.ids,
    alternativeGroups: checked.alternativeGroups,
    mutation: { name: "claim-support-span", killed: true },
    summaryMutation: { name: "claim-summary-status", killed: true }
  };
}

async function recallSemanticCourt(page) {
  const allowedFacets = new Set([
    "Actor",
    "Action",
    "Object",
    "Location",
    "Outcome",
    "Cause",
    "Context",
    "RoleReversal",
    "Polarity",
    "Modality"
  ]);

  function validate(snapshot) {
    const distorted = snapshot.alignState.match(/^Distorted\([^,]+,\{([^}]+)\}\)$/);
    const stateFacets = distorted ? distorted[1].split(",") : [];
    for (const facet of [...stateFacets, ...snapshot.facets]) {
      if (!allowedFacets.has(facet)) throw new Error(`noncanonical Facet ${facet}`);
    }
  }

  async function snapshot(rowId) {
    await page.locator(`[data-recall-row="${rowId}"]`).click();
    await page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    );
    return page.evaluate(() => ({
      alignState:
        document.querySelector("[data-recall-align-state]")?.dataset.recallAlignState || "",
      facets: Array.from(document.querySelectorAll("[data-recall-facet]"), (node) =>
        node.dataset.recallFacet || ""
      ),
      compatibility: document.querySelector("[data-recall-compat]")?.textContent?.trim() || "",
      recallText:
        document.querySelector('[data-dc-exact-evidence="recall-selection"]')?.textContent?.trim() ||
        ""
    }));
  }

  async function matrixGeometrySnapshot() {
    return page.evaluate(() => ({
      labels: Array.from(document.querySelectorAll("[data-off-source-column]"), (node) =>
        node.dataset.offSourceColumn || ""
      ),
      rules: Array.from(document.querySelectorAll("[data-off-source-rule]"), (node) =>
        node.dataset.offSourceRule || ""
      )
    }));
  }

  function validateMatrixGeometry(geometry) {
    const expectedLabels = [
      "Association",
      "Commentary",
      "Intrusion",
      "Inference*",
      "Uninterpretable",
      "Unranked"
    ];
    const expectedRules = [
      "Association",
      "Commentary",
      "Intrusion",
      "Inference",
      "Uninterpretable"
    ];
    if (geometry.labels.join("|") !== expectedLabels.join("|")) {
      throw new Error(`off-source label geometry drifted: ${geometry.labels.join("|")}`);
    }
    if (geometry.rules.join("|") !== expectedRules.join("|")) {
      throw new Error(`off-source column rules drifted: ${geometry.rules.join("|")}`);
    }
  }

  const r02 = await snapshot("r02");
  validate(r02);
  if (r02.alignState !== "Distorted(s05,{Object})") {
    throw new Error(`r02 exact AlignState drifted: ${r02.alignState}`);
  }

  const r03 = await snapshot("r03");
  validate(r03);
  if (r03.alignState !== "Source(s02)" || !r03.recallText.includes("told him")) {
    throw new Error(`r03 reporting-frame probe drifted: ${JSON.stringify(r03)}`);
  }
  if (
    !r03.compatibility.includes("retains “told him”") ||
    !r03.compatibility.includes("External(SourceConsistentInference)") ||
    /report asserted as event|assert(?:s|ed)? the .* as (?:narrated-world )?fact/i.test(
      r03.compatibility
    )
  ) {
    throw new Error(`r03 compatibility overclaims: ${r03.compatibility}`);
  }

  const mutation = { ...r02, alignState: "Distorted(s05,{Attribute})" };
  let mutationKilled = false;
  try {
    validate(mutation);
  } catch (error) {
    mutationKilled = /noncanonical Facet Attribute/.test(String(error.message));
  }
  if (!mutationKilled) throw new Error("noncanonical recall Facet mutation survived");

  const matrixGeometry = await matrixGeometrySnapshot();
  validateMatrixGeometry(matrixGeometry);
  await page.evaluate(() => {
    document.querySelector("[data-off-source-rule]").dataset.offSourceRule = "mutated";
  });
  let columnRuleMutationKilled = false;
  try {
    validateMatrixGeometry(await matrixGeometrySnapshot());
  } catch (error) {
    columnRuleMutationKilled = /column rules drifted/.test(String(error.message));
  }
  if (!columnRuleMutationKilled) throw new Error("off-source column-rule mutation survived");
  await page.evaluate(() => {
    document.querySelector('[data-off-source-rule="mutated"]').dataset.offSourceRule =
      "Association";
  });

  await snapshot("r02");
  return {
    r02: { alignState: r02.alignState, facets: r02.facets },
    r03: {
      alignState: r03.alignState,
      recallText: r03.recallText,
      compatibility: r03.compatibility
    },
    matrixGeometry,
    mutation: { name: "noncanonical-facet-attribute", killed: true },
    columnRuleMutation: { name: "missing-named-off-source-rule", killed: true }
  };
}

async function chronologyCourt(page) {
  async function snapshot() {
    return page.evaluate(() => {
      const lines = Object.fromEntries(
        Array.from(document.querySelectorAll("[data-chron-link]"), (line) => [
          line.dataset.chronLink,
          ["x1", "y1", "x2", "y2"].map((name) => Number(line.getAttribute(name)))
        ])
      );
      const receipt = document.querySelector("[data-chronology-receipt]");
      return {
        clocks: Array.from(document.querySelectorAll("[data-clock]"), (node) =>
          node.dataset.clock || ""
        ),
        lines,
        receipts: (receipt?.dataset.fixtureReceipts || "").split("|").filter(Boolean),
        visibleReceipt: (receipt?.textContent || "").trim()
      };
    });
  }

  function validate(value) {
    if (value.clocks.join("|") !== "discourse|story-world|recall") {
      throw new Error(`chronology clocks drifted: ${value.clocks.join("|")}`);
    }
    const first = value.lines.s01;
    const flashback = value.lines.s02;
    if (!first || !flashback) throw new Error("chronology crossing links missing");
    const leftOrder = Math.sign(first[1] - flashback[1]);
    const rightOrder = Math.sign(first[3] - flashback[3]);
    if (leftOrder === 0 || rightOrder === 0 || leftOrder === rightOrder) {
      throw new Error("chronology flashback crossing missing");
    }
    const expectedReceipts = [
      "synthetic/two-boats-source@v1",
      "synthetic/two-boats-recall-p01@v1"
    ];
    if (value.receipts.join("|") !== expectedReceipts.join("|")) {
      throw new Error("chronology fixture receipt drifted");
    }
    if (!value.visibleReceipt.includes("source") || !value.visibleReceipt.includes("recall p01")) {
      throw new Error("chronology receipt is not visibly printed");
    }
  }

  const checked = await snapshot();
  validate(checked);
  await page.evaluate(() => {
    document.querySelector('[data-chron-link="s02"]').setAttribute("y2", "120");
  });
  let mutationKilled = false;
  try {
    validate(await snapshot());
  } catch (error) {
    mutationKilled = /flashback crossing missing/.test(String(error.message));
  }
  if (!mutationKilled) throw new Error("chronology crossing mutation survived");
  await page.evaluate(() => {
    document.querySelector('[data-chron-link="s02"]').setAttribute("y2", "56");
  });
  return {
    clocks: checked.clocks,
    receipts: checked.receipts,
    flashbackCrossing: "s01 x s02",
    mutation: { name: "remove-flashback-crossing", killed: true }
  };
}

async function featureScaleCourt(page) {
  const checked = await page.evaluate(() => {
    const receipt = document.querySelector("[data-feature-receipt]");
    return {
      view: document.querySelectorAll("[data-feature-scale-space]").length,
      rawMarks: document.querySelectorAll("[data-feature-raw-marks] line").length,
      missingRegions: document.querySelectorAll("[data-feature-missing-region]").length,
      windowStyles: Array.from(document.querySelectorAll("[data-feature-window-style]"), (node) =>
        node.dataset.featureWindowStyle || ""
      ),
      receipts: (receipt?.dataset.fixtureReceipts || "").split("|").filter(Boolean),
      visibleReceipt: (receipt?.textContent || "").trim()
    };
  });
  if (
    checked.view !== 1 ||
    checked.rawMarks !== 25 ||
    checked.missingRegions !== 1 ||
    checked.windowStyles.join("|") !== "9|25|61" ||
    checked.receipts.join("|") !== "synthetic/two-boats-source@v1" ||
    !checked.visibleReceipt.includes("source")
  ) {
    throw new Error(`feature scale-space court failed ${JSON.stringify(checked)}`);
  }
  return checked;
}

async function motionGeometryCourt(page) {
  async function snapshot() {
    return page.evaluate(() => {
      function geometry(phase, selector) {
        const svg = document.querySelector(`[data-motion-frame="${phase}"]`);
        const node = svg && svg.querySelector(selector);
        if (!svg || !node) return null;
        const sr = svg.getBoundingClientRect();
        const nr = node.getBoundingClientRect();
        return {
          x: nr.left - sr.left,
          y: nr.top - sr.top,
          width: nr.width,
          height: nr.height
        };
      }
      return {
        fadeLandmarks: geometry("fade-in", "[data-motion-landmarks]"),
        restLandmarks: geometry("event-rest", "[data-motion-landmarks]"),
        fadeSelection: geometry("fade-in", "[data-motion-selection]"),
        restSelection: geometry("event-rest", "[data-motion-selection]")
      };
    });
  }

  function validate(value) {
    for (const pair of [
      [value.fadeLandmarks, value.restLandmarks, "landmarks"],
      [value.fadeSelection, value.restSelection, "selection"]
    ]) {
      if (!pair[0] || !pair[1]) throw new Error(`motion ${pair[2]} geometry missing`);
      for (const field of ["x", "y", "width", "height"]) {
        if (Math.abs(pair[0][field] - pair[1][field]) > 0.25) {
          throw new Error(`motion ${pair[2]} translated at rest: ${field}`);
        }
      }
    }
  }

  const checked = await snapshot();
  validate(checked);
  await page.evaluate(() => {
    document.querySelector('[data-motion-frame="event-rest"] [data-motion-landmarks]').style.transform =
      "translateX(3px)";
  });
  let mutationKilled = false;
  try {
    validate(await snapshot());
  } catch (error) {
    mutationKilled = /translated at rest/.test(String(error.message));
  }
  if (!mutationKilled) throw new Error("motion 3px translation mutation survived");
  await page.evaluate(() => {
    document.querySelector('[data-motion-frame="event-rest"] [data-motion-landmarks]').style.removeProperty(
      "transform"
    );
  });
  return { ...checked, mutation: { name: "three-pixel-rest-translation", killed: true } };
}

async function stateBoardSemanticCourt(page) {
  async function snapshot() {
    return page.evaluate(() => {
      const nodes = Array.from(document.querySelectorAll(".not"));
      return {
        cards: nodes.length,
        labelled: nodes.filter((node) =>
          getComputedStyle(node, "::before").content.includes("Never confuse with")
        ).length,
        ruled: nodes.filter((node) => getComputedStyle(node).borderTopStyle !== "none").length
      };
    });
  }
  function validate(value, expectedCards) {
    if (
      value.cards === 0 ||
      value.cards !== expectedCards ||
      value.labelled !== value.cards ||
      value.ruled !== value.cards
    ) {
      throw new Error(`state-board non-colour distinction drifted ${JSON.stringify(value)}`);
    }
  }
  const checked = await snapshot();
  validate(checked, checked.cards);
  await page.evaluate(() => {
    const node = document.querySelector(".not");
    node.dataset.mutatedNot = "true";
    node.classList.remove("not");
  });
  let mutationKilled = false;
  try {
    validate(await snapshot(), checked.cards);
  } catch (error) {
    mutationKilled = /non-colour distinction drifted/.test(String(error.message));
  }
  if (!mutationKilled) throw new Error("state-board hue-only mutation survived");
  await page.evaluate(() => {
    const node = document.querySelector('[data-mutated-not="true"]');
    if (!node) throw new Error("state-board mutation restore target missing");
    delete node.dataset.mutatedNot;
    node.classList.add("not");
  });
  return { ...checked, mutation: { name: "remove-never-confuse-structure", killed: true } };
}

async function main() {
  fs.mkdirSync(reviewDir, { recursive: true });
  syncCanvasPackage();
  const { registry: fixtureRegistry, receipt: fixtureReceipt } = loadAndVerifyFixtures();
  const fixtureMutation = fixtureMutationCourt();

  const { chromium } = playwright();
  let browser;
  let browserVersion;
  let context;
  let temporarySourceDir;
  let directiveChecks;
  let clipAncestorMutation;
  let clipSelfMutation;
  let claimChecks;
  let recallChecks;
  let chronologyChecks;
  let featureScaleChecks;
  let motionGeometryChecks;
  let stateBoardChecks;
  let svgClipMutation;
  const entries = [];
  try {
    temporarySourceDir = fs.mkdtempSync(path.join(os.tmpdir(), "storyatlas-v2-export-"));
    fs.copyFileSync(path.join(sourceDir, "support.js"), path.join(temporarySourceDir, "support.js"));
    browser = await chromium.launch({ headless: true });
    browserVersion = browser.version();
    context = await browser.newContext({
      deviceScaleFactor: DEVICE_SCALE_FACTOR,
      reducedMotion: "reduce"
    });
    directiveChecks = await directiveCourt(context, temporarySourceDir);
    const page = await context.newPage();
    const pageErrors = [];
    const requests = [];
    page.on("pageerror", (error) => pageErrors.push(String(error)));
    page.on("console", (message) => {
      if (message.type() === "error") pageErrors.push(`console: ${message.text()}`);
    });
    page.on("request", (request) => requests.push(request.url()));

    for (const spec of plateSpecs) {
      const name = spec.name;
      pageErrors.length = 0;
      const sourceName = `${spec.source}.dc.html`;
      const sourcePath = path.join(sourceDir, sourceName);
      const source = fs.readFileSync(sourcePath, "utf8");
      const viewport = previewOf(source, sourceName);
      const runtimeSourcePath = path.join(temporarySourceDir, `${name}.runtime.html`);
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
      if (spec.state) {
        await page.evaluate((state) => window.__dcComponent.setState(state), spec.state);
        await page.evaluate(
          () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
        );
      }
      if (name === "Main") claimChecks = await mainClaimCourt(page, fixtureRegistry);
      if (name === "RecallMatrix") recallChecks = await recallSemanticCourt(page);
      if (name === "ChronologyLoom") chronologyChecks = await chronologyCourt(page);
      if (name === "FeatureScaleSpace") featureScaleChecks = await featureScaleCourt(page);
      if (name === "Motion") motionGeometryChecks = await motionGeometryCourt(page);
      if (name === "StateBoard") stateBoardChecks = await stateBoardSemanticCourt(page);
      const resolved = (await frozenHtml(page, sourceName, name)).replace(/[ \t]+$/gm, "");
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
      requests.length = 0;
      await page.goto(pathToFileURL(htmlPath).href, { waitUntil: "load" });
      const portable = await page.evaluate(() => ({
        scripts: document.scripts.length,
        directives: document.querySelectorAll("sc-for,sc-if,template[data-dc-directive],sc-unless").length,
        unresolved: document.documentElement.outerHTML.includes("{{"),
        textLength: (document.body.textContent || "").trim().length,
        externalResourceAttributes: document.querySelectorAll("script[src],img[src],link[href],iframe[src],object[data]").length,
        cssImports: Array.from(document.querySelectorAll("style")).filter((style) => /@import/i.test(style.textContent || "")).length
      }));
      if (
        portable.scripts !== 0 ||
        portable.directives !== 0 ||
        portable.unresolved ||
        portable.textLength < 100 ||
        portable.externalResourceAttributes !== 0 ||
        portable.cssImports !== 0 ||
        requests.length !== 1 ||
        requests[0] !== pathToFileURL(htmlPath).href
      ) {
        throw new Error(`${sourceName}: portable replay failed ${JSON.stringify(portable)}`);
      }
      const court = await plateCourt(page, viewport, name, fixtureRegistry);
      if (
        court.error ||
        court.offenders.length > 0 ||
        court.activeSemantics !== 0 ||
        court.clippedEvidence.length > 0 ||
        court.svgTextClipping.length > 0 ||
        court.svgTextOverlaps.length > 0 ||
        court.labelMarkOverlaps.length > 0 ||
        court.blankBands.length > 0 ||
        court.metadata.lang !== "en" ||
        !court.metadata.title ||
        !court.metadata.viewport ||
        !court.metadata.frozenNotice ||
        !court.metadata.syntheticBanner ||
        court.metadata.devicePixelRatio !== DEVICE_SCALE_FACTOR
      ) {
        throw new Error(`${sourceName}: plate court failed ${JSON.stringify(court)}`);
      }
      if (pageErrors.length > 0) {
        throw new Error(`${sourceName}: browser errors ${pageErrors.join(" | ")}`);
      }

      await page.evaluate(() => {
        const sibling = document.createElement("div");
        sibling.id = "dc-mutated-visible-sibling";
        sibling.style.cssText = "width:10px;height:10px;display:block";
        document.querySelector("x-dc").appendChild(sibling);
      });
      const siblingMutation = await plateCourt(page, viewport, name, fixtureRegistry);
      if (!siblingMutation.error || !siblingMutation.error.startsWith("expected-one-direct-visual-root")) {
        throw new Error(`${sourceName}: visible-sibling mutation survived`);
      }
      await page.evaluate(() => document.querySelector("#dc-mutated-visible-sibling").remove());

      const mutations = [{ name: "visible-artboard-sibling", killed: true }];
      if (name === "Compact") {
        await page.evaluate(() => {
          const region = document.querySelector('[data-dc-scroll-region="compact-codex"]');
          const wrapper = document.createElement("div");
          wrapper.id = "dc-mutated-outer-clip";
          wrapper.style.cssText = "height:1px;overflow:hidden;flex:none";
          region.parentElement.insertBefore(wrapper, region);
          wrapper.appendChild(region);
          region.style.height = "200px";
          region.style.flexGrow = "0";
        });
        const outerClipMutation = await plateCourt(page, viewport, name, fixtureRegistry);
        const killed =
          outerClipMutation.error === "observation-clipped-by-ancestor" ||
          (outerClipMutation.clippedEvidence || []).length > 0;
        if (!killed) throw new Error(`${sourceName}: outer clipping ancestor mutation survived`);
        clipAncestorMutation = {
          name: "one-pixel-outer-clipping-ancestor",
          killed: true,
          diagnostic: outerClipMutation.error || "clippedEvidence"
        };
        mutations.push(clipAncestorMutation);
        await page.evaluate(() => {
          const wrapper = document.querySelector("#dc-mutated-outer-clip");
          const region = wrapper.firstElementChild;
          region.style.removeProperty("height");
          region.style.removeProperty("flex-grow");
          wrapper.replaceWith(region);
        });

        const selfClipState = await page.evaluate(() => {
          const item = document.querySelector('[data-observation-ref="s01"]');
          const style = item.getAttribute("style");
          item.style.cssText +=
            ";display:inline-block;width:1px;overflow:hidden;white-space:nowrap";
          return { style };
        });
        const selfClipMutationResult = await plateCourt(page, viewport, name, fixtureRegistry);
        const selfClipFinding = (selfClipMutationResult.clippedEvidence || []).find(
          (finding) => finding.ref === "s01" && finding.self === true
        );
        const selfClipReachabilityFailure =
          selfClipMutationResult.error === "observation-clipped-by-ancestor" &&
          selfClipMutationResult.ref === "s01";
        if (!selfClipFinding && !selfClipReachabilityFailure) {
          throw new Error(`${sourceName}: self-clipping evidence mutation survived`);
        }
        clipSelfMutation = {
          name: "one-pixel-self-clipping-evidence",
          killed: true,
          diagnostic: selfClipMutationResult.error || "clippedEvidence:self"
        };
        mutations.push(clipSelfMutation);
        await page.evaluate(({ style }) => {
          const item = document.querySelector('[data-observation-ref="s01"]');
          if (style === null) item.removeAttribute("style");
          else item.setAttribute("style", style);
        }, selfClipState);
      }

      if (name === "Compact") {
        const clippedTickState = await page.evaluate(() => {
          const tick = Array.from(document.querySelectorAll("svg text")).find(
            (node) => (node.textContent || "").trim() === "584" &&
              node.closest("svg")?.getAttribute("width") === "596"
          );
          if (!tick) throw new Error("compact terminal tick mutation target missing");
          const x = tick.getAttribute("x");
          tick.setAttribute("x", "640");
          return { x };
        });
        const clippedTickMutation = await plateCourt(page, viewport, name, fixtureRegistry);
        if ((clippedTickMutation.svgTextClipping || []).length === 0) {
          throw new Error(`${sourceName}: clipped terminal-tick mutation survived`);
        }
        svgClipMutation = {
          name: "terminal-axis-label-outside-svg",
          killed: true,
          diagnostic: "svgTextClipping"
        };
        mutations.push(svgClipMutation);
        await page.evaluate(({ x }) => {
          const tick = Array.from(document.querySelectorAll("svg text")).find(
            (node) => (node.textContent || "").trim() === "584" &&
              node.closest("svg")?.getAttribute("width") === "596"
          );
          tick.setAttribute("x", x);
        }, clippedTickState);
      }

      await page.screenshot({ path: pngPath, fullPage: true, animations: "disabled" });
      const dimensions = pngDimensions(fs.readFileSync(pngPath));
      const expectedHeight = court.layout === "fixed" ? viewport.height : Math.round(court.artboard.height);
      const expectedPixels = {
        width: viewport.width * DEVICE_SCALE_FACTOR,
        height: expectedHeight * DEVICE_SCALE_FACTOR
      };
      if (
        dimensions.width !== expectedPixels.width ||
        Math.abs(dimensions.height - expectedPixels.height) > DEVICE_SCALE_FACTOR
      ) {
        throw new Error(
          `${sourceName}: screenshot dimensions ${JSON.stringify(dimensions)} do not match ${expectedPixels.width}x${expectedPixels.height}`
        );
      }
      entries.push({
        name,
        sourceName,
        html: htmlName,
        png: pngName,
        viewport,
        observations: plateObservations[name],
        portable,
        court,
        screenshot: dimensions,
        deviceScaleFactor: DEVICE_SCALE_FACTOR,
        mutations
      });
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
    "PIXEL-REVIEW-BASELINE.md",
    "PROOF-BOUNDARY.md",
    "canvas.json",
    "export.cjs",
    "fixture-court.cjs",
    "fixtures.json",
    "manifest-digest.cjs",
    "renderer-gap-benchmark.cjs",
    "renderer-gap-benchmark.html",
    "support.js",
    "storyatlas-mockup-v2.html",
    "verify-manifest.cjs",
    ...sourcePlates.map((name) => `${name}.dc.html`)
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
  const benchmark = JSON.parse(
    fs.readFileSync(path.join(reviewDir, "renderer-gap-benchmark.json"), "utf8")
  );
  if (benchmark.schema !== "storyatlas4s.renderer-gap-court.v2") {
    throw new Error("renderer-gap benchmark must be regenerated with the v2 structural/diagnostic split");
  }
  const artifact = {
    schema: "storyatlas4s.mockup-review-artifact.v2",
    sourcePolicy:
      "SYNTHETIC DESIGN FIXTURES — NOT ADMITTED SOURCE, MODEL OUTPUT, OR HUMAN ADJUDICATION",
    observations: fixtureReceipt,
    typography:
      "exact evidence uses ui-monospace platform fallbacks at 16px/24.8px; chrome uses system-ui",
    renderer:
      "storyatlas4s mockup DC resolver v2 to frozen inert DOM; Chromium PNG at DPR 2",
    courts: {
      directives: directiveChecks,
      fixtureMutation,
      claimChecks,
      recallChecks,
      chronologyChecks,
      featureScaleChecks,
      motionGeometryChecks,
      stateBoardChecks,
      visibleSiblingMutation: "killed independently on every plate",
      clipAncestorMutation,
      clipSelfMutation,
      svgClipMutation,
      rendererStructuralDigest: benchmark.structuralDigest
    },
    views: entries,
    sources,
    outputs
  };
  const reproducible = reproducibleProjection(artifact);
  const sourceSetDigest = reproducible.sourceSetDigest;
  const manifest = {
    schema: "storyatlas4s.mockup-review-manifest.v2",
    digestRule: ARTIFACT_RULE,
    artifact,
    artifactDigest: "",
    reproducibleRule: REPRODUCIBLE_RULE,
    reproducible,
    reproducibleDigest: "",
    diagnostics: {
      meaning:
        "Unsigned generation context and machine-dependent timing. Exact output bytes remain bound by artifactDigest.",
      browser: { engine: "Chromium", version: browserVersion },
      platform: { os: os.platform(), arch: os.arch() },
      generatorContext: {
        preCommitSourceHead: childProcess
          .execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" })
          .trim(),
        worktreeDirty:
          childProcess.execFileSync("git", ["status", "--porcelain"], {
            cwd: root,
            encoding: "utf8"
          }).trim().length > 0,
        sourceSetDigest
      },
      rendererGap: benchmark.diagnostics
    }
  };
  manifest.artifactDigest = digest(artifactTarget(manifest));
  manifest.reproducibleDigest = digest(reproducibleTarget(manifest));

  const manifestPath = path.join(reviewDir, "manifest.json");
  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  const serialized = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const verified = verifyManifest(serialized, sourceDir, reviewDir);

  const ruleMutation = JSON.parse(JSON.stringify(serialized));
  ruleMutation.digestRule = `${ruleMutation.digestRule}:mutated`;
  let ruleMutationKilled = false;
  try {
    verifyManifest(ruleMutation, sourceDir, reviewDir);
  } catch (error) {
    ruleMutationKilled = /digest rule/.test(String(error.message));
  }
  if (!ruleMutationKilled) throw new Error("manifest digest-rule mutation survived");

  const duplicateMutation = JSON.parse(JSON.stringify(serialized));
  duplicateMutation.artifact.outputs.push(duplicateMutation.artifact.outputs[0]);
  duplicateMutation.artifactDigest = digest(artifactTarget(duplicateMutation));
  let duplicateMutationKilled = false;
  try {
    verifyManifest(duplicateMutation, sourceDir, reviewDir);
  } catch (error) {
    duplicateMutationKilled = /duplicate path/.test(String(error.message));
  }
  if (!duplicateMutationKilled) throw new Error("manifest duplicate-output mutation survived");

  const sourceSetMutation = JSON.parse(JSON.stringify(serialized));
  sourceSetMutation.reproducible.sourceSetDigest = "0".repeat(64);
  sourceSetMutation.reproducibleDigest = digest(reproducibleTarget(sourceSetMutation));
  let sourceSetMutationKilled = false;
  try {
    verifyManifest(sourceSetMutation, sourceDir, reviewDir);
  } catch (error) {
    sourceSetMutationKilled = /reproducible projection/.test(String(error.message));
  }
  if (!sourceSetMutationKilled) throw new Error("reproducible source-set mutation survived");

  const reproducibleSourceMutation = JSON.parse(JSON.stringify(serialized));
  reproducibleSourceMutation.reproducible.sources[0].sha256 = "0".repeat(64);
  reproducibleSourceMutation.reproducibleDigest = digest(
    reproducibleTarget(reproducibleSourceMutation)
  );
  let reproducibleSourceMutationKilled = false;
  try {
    verifyManifest(reproducibleSourceMutation, sourceDir, reviewDir);
  } catch (error) {
    reproducibleSourceMutationKilled = /reproducible sources/.test(String(error.message));
  }
  if (!reproducibleSourceMutationKilled) {
    throw new Error("reproducible-only source-hash mutation survived");
  }

  const reproducibleOutputMutation = JSON.parse(JSON.stringify(serialized));
  reproducibleOutputMutation.reproducible.outputs[0].sha256 = "0".repeat(64);
  reproducibleOutputMutation.reproducibleDigest = digest(
    reproducibleTarget(reproducibleOutputMutation)
  );
  let reproducibleOutputMutationKilled = false;
  try {
    verifyManifest(reproducibleOutputMutation, sourceDir, reviewDir);
  } catch (error) {
    reproducibleOutputMutationKilled = /reproducible outputs/.test(String(error.message));
  }
  if (!reproducibleOutputMutationKilled) {
    throw new Error("reproducible-only output-hash mutation survived");
  }

  serialized.diagnostics.manifestVerifier = {
    verified,
    mutations: [
      { name: "digest-rule", killed: true },
      { name: "duplicate-output-path", killed: true },
      { name: "reproducible-source-set", killed: true },
      { name: "reproducible-only-source-hash", killed: true },
      { name: "reproducible-only-output-hash", killed: true }
    ]
  };
  fs.writeFileSync(manifestPath, `${JSON.stringify(serialized, null, 2)}\n`);
  verifyManifest(JSON.parse(fs.readFileSync(manifestPath, "utf8")), sourceDir, reviewDir);
  process.stdout.write(
    `${JSON.stringify({ artifactDigest: manifest.artifactDigest, reproducibleDigest: manifest.reproducibleDigest, plates: entries.length, browserVersion }, null, 2)}\n`
  );
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});

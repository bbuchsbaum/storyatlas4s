"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const fixturePath = path.join(__dirname, "fixtures.json");

function sha256(text) {
  return crypto.createHash("sha256").update(text, "utf8").digest("hex");
}

function fail(message) {
  throw new Error(`fixture court: ${message}`);
}

function exactTextOf(fixture) {
  const separator = fixture.kind === "canonical-source" ? " " : "\n";
  return fixture.units.map((unit) => unit.text).join(separator);
}

function packetTextOf(fixture) {
  return fixture.units
    .map((unit) => fixture.recordFields.map((field) => unit[field]).join("\t"))
    .join("\n");
}

function verifyFeatureProbe(source) {
  const probe = source.featureProbe;
  if (!probe) fail("canonical source has no feature probe ledger");
  const lexical = Array.from(
    source.canonicalText.matchAll(/\p{L}+(?:['’]\p{L}+)?/gu),
    (match, ordinal) => ({ ordinal, start: match.index, end: match.index + match[0].length })
  );
  const missing = new Set(probe.missingLexicalOrdinals);
  if (missing.size !== probe.missingLexicalOrdinals.length) {
    fail("feature probe repeats a missing lexical ordinal");
  }
  if (
    probe.missingLexicalOrdinals.some(
      (ordinal) => !Number.isInteger(ordinal) || ordinal < 0 || ordinal >= lexical.length
    )
  ) {
    fail("feature probe has an out-of-range missing lexical ordinal");
  }

  const declaredByUnit = new Map(
    probe.sentenceCoverage.map((coverage) => [coverage.unit, coverage])
  );
  if (declaredByUnit.size !== source.units.length) {
    fail("feature probe sentence coverage must name every source unit exactly once");
  }
  let eligible = 0;
  let observed = 0;
  for (const unit of source.units) {
    const declared = declaredByUnit.get(unit.id);
    if (!declared) fail(`feature probe omits ${unit.id}`);
    const ordinals = lexical
      .filter((token) => token.start >= unit.span[0] && token.end <= unit.span[1])
      .map((token) => token.ordinal);
    const actualObserved = ordinals.filter((ordinal) => !missing.has(ordinal)).length;
    if (declared.eligible !== ordinals.length || declared.observed !== actualObserved) {
      fail(`${unit.id} feature coverage does not derive from the token ledger`);
    }
    eligible += ordinals.length;
    observed += actualObserved;
  }
  const track = probe.trackCoverage;
  if (
    track.eligible !== eligible ||
    track.observed !== observed ||
    track.missing !== eligible - observed
  ) {
    fail("track feature coverage does not conserve sentence coverage");
  }

  const window = probe.selectedWindow;
  const [start, end] = window.lexicalRange;
  const windowObserved = lexical
    .slice(start, end)
    .filter((token) => !missing.has(token.ordinal)).length;
  if (
    end - start !== window.eligible ||
    window.eligible !== probe.windowPlan.width ||
    window.observed !== windowObserved ||
    window.missing !== window.eligible - window.observed
  ) {
    fail("selected feature window does not derive from the token ledger");
  }
  if (
    probe.windowPlan.basis !== "LexicalTokens" ||
    probe.windowPlan.edgePolicy !== "KeepPartial" ||
    probe.reducer !== "ScalarReducer.Mean" ||
    probe.missingValuePolicy !== "IgnoreMissing" ||
    probe.aggregateMissingReason !== "MissingReason.AllMissing" ||
    probe.providerOutcome !== "MissingReason.ProviderAbstained"
  ) {
    fail("feature probe vocabulary does not match the pinned typed contract");
  }
  return {
    id: probe.id,
    lexicalLedgerEntries: lexical.length,
    missingOrdinals: missing.size,
    trackCoverage: track,
    selectedWindow: window,
    windowPlan: probe.windowPlan,
    reducer: probe.reducer,
    missingValuePolicy: probe.missingValuePolicy,
    aggregateMissingReason: probe.aggregateMissingReason,
    providerOutcome: probe.providerOutcome
  };
}

function verifyFixtureRegistry(registry) {
  if (registry.schema !== "storyatlas4s.mockup-fixtures.v1") {
    fail(`unsupported schema ${String(registry.schema)}`);
  }
  if (!Array.isArray(registry.fixtures) || registry.fixtures.length !== 3) {
    fail("expected exactly three independently identified fixtures");
  }

  const byId = new Map();
  for (const fixture of registry.fixtures) {
    if (byId.has(fixture.id)) fail(`duplicate fixture id ${fixture.id}`);
    byId.set(fixture.id, fixture);

    const exactText = exactTextOf(fixture);
    if (exactText !== fixture.canonicalText) fail(`${fixture.id} canonicalText drift`);
    if (Buffer.byteLength(exactText, "utf8") !== fixture.byteLength) {
      fail(`${fixture.id} UTF-8 byte length drift`);
    }
    if (exactText.length !== fixture.utf16CodeUnits) {
      fail(`${fixture.id} UTF-16 code-unit length drift`);
    }
    if (sha256(exactText) !== fixture.textSha256) fail(`${fixture.id} text digest drift`);

    const packetText = packetTextOf(fixture);
    if (sha256(packetText) !== fixture.packetSha256) fail(`${fixture.id} packet digest drift`);

    const separatorWidth = fixture.kind === "canonical-source" ? 1 : 1;
    let offset = 0;
    for (let index = 0; index < fixture.units.length; index += 1) {
      const unit = fixture.units[index];
      if (!Array.isArray(unit.span) || unit.span.length !== 2) {
        fail(`${fixture.id}/${unit.id} has no half-open span`);
      }
      if (unit.span[0] !== offset || unit.span[1] !== offset + unit.text.length) {
        fail(`${fixture.id}/${unit.id} span does not round-trip to canonicalText`);
      }
      if (exactText.slice(unit.span[0], unit.span[1]) !== unit.text) {
        fail(`${fixture.id}/${unit.id} text does not round-trip`);
      }
      offset = unit.span[1] + (index + 1 < fixture.units.length ? separatorWidth : 0);
    }
    if (offset !== exactText.length) fail(`${fixture.id} final offset drift`);
  }

  const source = byId.get("synthetic/two-boats-source@v1");
  const recall = byId.get("synthetic/two-boats-recall-p01@v1");
  const interview = byId.get("synthetic/two-boats-interview-p07@v1");
  if (!source || !recall || !interview) fail("canonical fixture ids are missing");
  if (
    recall.sourceRef.id !== source.id ||
    recall.sourceRef.textSha256 !== source.textSha256
  ) {
    fail("recall source identity does not bind the canonical source bytes");
  }
  if (
    interview.sourceState !== "Unestablished" ||
    interview.sourceStateContract !== "proposed-storyatlas-view-state@v1"
  ) {
    fail("source-free interview must retain the explicitly proposed Unestablished view state");
  }

  const lexicalTokens = source.canonicalText.match(/\p{L}+(?:['’]\p{L}+)?/gu) || [];
  if (lexicalTokens.length !== 117) fail(`expected 117 lexical tokens, got ${lexicalTokens.length}`);

  const featureLedgerCourt = verifyFeatureProbe(source);

  return {
    schema: registry.schema,
    fixtures: registry.fixtures.map((fixture) => ({
      id: fixture.id,
      kind: fixture.kind,
      storage: fixture.storage,
      byteLength: fixture.byteLength,
      utf16CodeUnits: fixture.utf16CodeUnits,
      coordinateBasis: fixture.coordinateBasis,
      textSha256: fixture.textSha256,
      packetSha256: fixture.packetSha256,
      sourceRef: fixture.sourceRef || null,
      sourceState: fixture.sourceState || null,
      sourceStateContract: fixture.sourceStateContract || null
    })),
    lexicalTokenCourt: {
      basis: "Unicode letter runs with optional internal apostrophe",
      eligible: lexicalTokens.length
    },
    featureLedgerCourt
  };
}

function loadAndVerifyFixtures() {
  const registry = JSON.parse(fs.readFileSync(fixturePath, "utf8"));
  return { registry, receipt: verifyFixtureRegistry(registry) };
}

function mutationCourt() {
  const registry = JSON.parse(fs.readFileSync(fixturePath, "utf8"));
  const mutated = JSON.parse(JSON.stringify(registry));
  mutated.fixtures[0].units[0].text = mutated.fixtures[0].units[0].text.replace(
    "first",
    "FIRST"
  );
  let killed = false;
  try {
    verifyFixtureRegistry(mutated);
  } catch (error) {
    killed = /drift|round-trip/.test(String(error.message));
  }
  if (!killed) fail("one-character observation mutation survived");

  const ledgerMutated = JSON.parse(JSON.stringify(registry));
  ledgerMutated.fixtures[0].featureProbe.trackCoverage.observed += 1;
  let ledgerKilled = false;
  try {
    verifyFixtureRegistry(ledgerMutated);
  } catch (error) {
    ledgerKilled = /coverage does not conserve/.test(String(error.message));
  }
  if (!ledgerKilled) fail("feature-ledger conservation mutation survived");
  return {
    name: "fixture-mutation-court",
    mutations: [
      { name: "fixture-character", killed: true },
      { name: "feature-ledger-conservation", killed: true }
    ]
  };
}

if (require.main === module) {
  const { receipt } = loadAndVerifyFixtures();
  process.stdout.write(`${JSON.stringify({ receipt, mutation: mutationCourt() }, null, 2)}\n`);
}

module.exports = {
  exactTextOf,
  loadAndVerifyFixtures,
  mutationCourt,
  packetTextOf,
  sha256,
  verifyFixtureRegistry
};

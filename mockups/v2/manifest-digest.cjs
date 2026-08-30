"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const ARTIFACT_RULE = "storyatlas4s-canonical-json-sha256-v2:target=schema+digestRule+artifact";
const REPRODUCIBLE_RULE =
  "storyatlas4s-canonical-json-sha256-v1:target=schema+reproducibleRule+reproducible";

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function canonical(value) {
  if (value === null) return "null";
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(",")}}`;
  }
  if (typeof value === "number" && !Number.isFinite(value)) {
    throw new Error("manifest canonicalization rejects non-finite numbers");
  }
  if (!["string", "number", "boolean"].includes(typeof value)) {
    throw new Error(`manifest canonicalization rejects ${typeof value}`);
  }
  return JSON.stringify(value);
}

function digest(value) {
  return sha256(Buffer.from(canonical(value), "utf8"));
}

function artifactTarget(manifest) {
  return {
    schema: manifest.schema,
    digestRule: manifest.digestRule,
    artifact: manifest.artifact
  };
}

function reproducibleTarget(manifest) {
  return {
    schema: manifest.schema,
    reproducibleRule: manifest.reproducibleRule,
    reproducible: manifest.reproducible
  };
}

function sourceSetTarget(sources) {
  return {
    schema: "storyatlas4s.mockup-source-set.v1",
    sources
  };
}

function reproducibleProjection(artifact) {
  return {
    schema: "storyatlas4s.mockup-reproducible-build.v1",
    sourcePolicy: artifact.sourcePolicy,
    observations: artifact.observations,
    sourceSetDigest: digest(sourceSetTarget(artifact.sources)),
    rendererStructuralDigest: artifact.courts.rendererStructuralDigest,
    views: artifact.views.map((entry) => ({
      name: entry.name,
      sourceName: entry.sourceName,
      html: entry.html,
      viewport: entry.viewport,
      observations: entry.observations,
      portable: entry.portable,
      court: entry.court
    })),
    sources: artifact.sources,
    outputs: artifact.outputs.filter(
      (entry) => entry.path.endsWith(".html") && entry.path !== "renderer-gap-benchmark.html"
    )
  };
}

function assertSafeUniquePaths(entries, label) {
  const seen = new Set();
  for (const entry of entries) {
    if (!entry || typeof entry.path !== "string" || entry.path.length === 0) {
      throw new Error(`${label} contains an invalid path`);
    }
    if (path.isAbsolute(entry.path) || entry.path.split(/[\\/]/).includes("..")) {
      throw new Error(`${label} contains unsafe path ${entry.path}`);
    }
    if (seen.has(entry.path)) throw new Error(`${label} contains duplicate path ${entry.path}`);
    seen.add(entry.path);
  }
}

function verifyFiles(entries, baseDir, label) {
  assertSafeUniquePaths(entries, label);
  for (const entry of entries) {
    const bytes = fs.readFileSync(path.join(baseDir, entry.path));
    if (bytes.length !== entry.bytes) throw new Error(`${label}/${entry.path} byte count drift`);
    if (sha256(bytes) !== entry.sha256) throw new Error(`${label}/${entry.path} digest drift`);
  }
}

function verifyManifest(manifest, sourceDir, reviewDir) {
  if (manifest.schema !== "storyatlas4s.mockup-review-manifest.v2") {
    throw new Error(`unsupported manifest schema ${String(manifest.schema)}`);
  }
  if (manifest.digestRule !== ARTIFACT_RULE) {
    throw new Error(`unsupported artifact digest rule ${String(manifest.digestRule)}`);
  }
  if (manifest.reproducibleRule !== REPRODUCIBLE_RULE) {
    throw new Error(`unsupported reproducible digest rule ${String(manifest.reproducibleRule)}`);
  }
  const actualArtifact = digest(artifactTarget(manifest));
  if (actualArtifact !== manifest.artifactDigest) {
    throw new Error(`artifact digest mismatch: expected ${manifest.artifactDigest}, got ${actualArtifact}`);
  }
  const actualReproducible = digest(reproducibleTarget(manifest));
  if (actualReproducible !== manifest.reproducibleDigest) {
    throw new Error(
      `reproducible digest mismatch: expected ${manifest.reproducibleDigest}, got ${actualReproducible}`
    );
  }
  verifyFiles(manifest.artifact.sources, sourceDir, "sources");
  verifyFiles(manifest.artifact.outputs, reviewDir, "outputs");
  verifyFiles(manifest.reproducible.sources, sourceDir, "reproducible sources");
  verifyFiles(manifest.reproducible.outputs, reviewDir, "reproducible outputs");
  const expectedReproducible = reproducibleProjection(manifest.artifact);
  if (canonical(manifest.reproducible) !== canonical(expectedReproducible)) {
    throw new Error("reproducible projection does not match the verified artifact");
  }
  return {
    artifactDigest: actualArtifact,
    reproducibleDigest: actualReproducible,
    sources: manifest.artifact.sources.length,
    outputs: manifest.artifact.outputs.length,
    reproducibleSources: manifest.reproducible.sources.length,
    reproducibleOutputs: manifest.reproducible.outputs.length
  };
}

module.exports = {
  ARTIFACT_RULE,
  REPRODUCIBLE_RULE,
  artifactTarget,
  canonical,
  digest,
  reproducibleProjection,
  reproducibleTarget,
  sha256,
  sourceSetTarget,
  verifyManifest
};

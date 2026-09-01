"use strict";

const fs = require("fs");
const path = require("path");
const { verifyManifest } = require("./manifest-digest.cjs");

const sourceDir = __dirname;
const reviewDir = path.join(sourceDir, "review");
const manifestPath = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(reviewDir, "manifest.json");
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const result = verifyManifest(manifest, sourceDir, reviewDir);
process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);

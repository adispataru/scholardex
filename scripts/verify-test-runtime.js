#!/usr/bin/env node
/* eslint-disable no-console */
const fs = require("fs");
const path = require("path");

const RESULTS_DIR = path.join(process.cwd(), "build", "test-results", "test");
const SUITE_WARN_SECONDS = 0.5;
const TOTAL_WARN_SECONDS = 10.0;
const TOP_COUNT = 5;

function parseAttr(line, attr) {
  const re = new RegExp(`${attr}="([^"]+)"`);
  const match = line.match(re);
  return match ? match[1] : null;
}

function fail(message) {
  console.error(`[runtime] ERROR: ${message}`);
  process.exit(1);
}

if (!fs.existsSync(RESULTS_DIR)) {
  fail(`Missing test results directory: ${RESULTS_DIR}. Run tests first.`);
}

const files = fs
  .readdirSync(RESULTS_DIR)
  .filter((file) => file.startsWith("TEST-") && file.endsWith(".xml"));

if (files.length === 0) {
  fail(`No TEST-*.xml files found in ${RESULTS_DIR}.`);
}

const suites = [];
let totalTests = 0;
let totalTime = 0;

for (const file of files) {
  const filePath = path.join(RESULTS_DIR, file);
  const content = fs.readFileSync(filePath, "utf8");
  const suiteLine = content
    .split("\n")
    .find((line) => line.includes("<testsuite "));

  if (!suiteLine) {
    fail(`Could not parse testsuite line in ${filePath}.`);
  }

  const name = parseAttr(suiteLine, "name") || file;
  const tests = Number.parseInt(parseAttr(suiteLine, "tests") || "0", 10);
  const time = Number.parseFloat(parseAttr(suiteLine, "time") || "0");

  if (Number.isNaN(tests) || Number.isNaN(time)) {
    fail(`Invalid tests/time values in ${filePath}.`);
  }

  totalTests += tests;
  totalTime += time;
  suites.push({ name, tests, time });
}

suites.sort((a, b) => b.time - a.time);
const topSuites = suites.slice(0, TOP_COUNT);

const totalWarn = totalTime > TOTAL_WARN_SECONDS;
const slowSuites = suites.filter((suite) => suite.time > SUITE_WARN_SECONDS);
const hasWarnings = totalWarn || slowSuites.length > 0;

console.log(
  `[runtime] suites=${suites.length} tests=${totalTests} total_reported_seconds=${totalTime.toFixed(3)}`
);

if (totalWarn) {
  console.log(
    `[runtime] WARN: total reported suite time ${totalTime.toFixed(3)}s exceeds soft target ${TOTAL_WARN_SECONDS.toFixed(3)}s`
  );
} else {
  console.log(
    `[runtime] PASS: total reported suite time within soft target (${TOTAL_WARN_SECONDS.toFixed(3)}s)`
  );
}

if (slowSuites.length > 0) {
  console.log(
    `[runtime] WARN: ${slowSuites.length} suite(s) exceed per-suite soft threshold ${SUITE_WARN_SECONDS.toFixed(3)}s`
  );
} else {
  console.log(
    `[runtime] PASS: no suite exceeds per-suite soft threshold (${SUITE_WARN_SECONDS.toFixed(3)}s)`
  );
}

console.log(`[runtime] top_${TOP_COUNT}_slowest_suites:`);
for (const suite of topSuites) {
  console.log(`- ${suite.time.toFixed(3)}s ${suite.name}`);
}

if (hasWarnings) {
  console.log("[runtime] Completed with warnings (soft budget policy: non-blocking).");
} else {
  console.log("[runtime] Completed cleanly.");
}

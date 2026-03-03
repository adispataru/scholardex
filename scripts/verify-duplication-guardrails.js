const fs = require('fs');
const path = require('path');

const errors = [];

function exists(filePath) {
  return fs.existsSync(filePath);
}

function read(filePath) {
  return fs.readFileSync(filePath, 'utf8');
}

function requirePattern(content, pattern, message) {
  if (!pattern.test(content)) {
    errors.push(message);
  }
}

function forbidPattern(content, pattern, message) {
  if (pattern.test(content)) {
    errors.push(message);
  }
}

const legacyCnfisPaths = [
  'src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService.java',
  'src/main/java/ro/uvt/pokedex/core/model/reporting/CNFISReport.java'
];

for (const legacyPath of legacyCnfisPaths) {
  if (exists(legacyPath)) {
    errors.push(`${legacyPath}: legacy CNFIS artifact must not be reintroduced`);
  }
}

const scoringFactoryPath = 'src/main/java/ro/uvt/pokedex/core/service/reporting/ScoringFactoryService.java';
if (!exists(scoringFactoryPath)) {
  errors.push(`${scoringFactoryPath}: file not found`);
} else {
  const factoryContent = read(scoringFactoryPath);
  forbidPattern(
    factoryContent,
    /\breturn\s+null\s*;/,
    `${scoringFactoryPath}: null fallback is forbidden; use explicit exception`
  );
}

const csScoringPath = 'src/main/java/ro/uvt/pokedex/core/service/reporting/ComputerScienceScoringService.java';
if (!exists(csScoringPath)) {
  errors.push(`${csScoringPath}: file not found`);
} else {
  const csContent = read(csScoringPath);
  requirePattern(
    csContent,
    /case\s+"bk"\s*,\s*"ch"\s*->\s*bookScoringService\.getScore\(publication,\s*indicator\)/,
    `${csScoringPath}: publication dispatch must route bk/ch to ComputerScienceBookService`
  );
  requirePattern(
    csContent,
    /case\s+"Book"\s*,\s*"Book Series"\s*->\s*bookScoringService\.getScore\(activity,\s*indicator\)/,
    `${csScoringPath}: activity dispatch must route Book/Book Series to ComputerScienceBookService`
  );
}

const buildAssetsPath = 'scripts/build-assets.js';
const verifyAssetsPath = 'scripts/verify-assets.js';
const sharedContractImport = /require\('\.\/assets-contract'\)/;

if (!exists(buildAssetsPath)) {
  errors.push(`${buildAssetsPath}: file not found`);
} else {
  requirePattern(
    read(buildAssetsPath),
    sharedContractImport,
    `${buildAssetsPath}: must import shared assets-contract module`
  );
}

if (!exists(verifyAssetsPath)) {
  errors.push(`${verifyAssetsPath}: file not found`);
} else {
  requirePattern(
    read(verifyAssetsPath),
    sharedContractImport,
    `${verifyAssetsPath}: must import shared assets-contract module`
  );
}

if (errors.length > 0) {
  console.error('Duplication/drift guardrails check failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('Duplication/drift guardrails passed.');

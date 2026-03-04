const { execFileSync } = require('child_process');
const fs = require('fs');

const errors = [];

function runRg(pattern, paths) {
  try {
    const output = execFileSync(
      'rg',
      ['-n', pattern, ...paths],
      { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
    );
    return output
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean);
  } catch (error) {
    if (typeof error.status === 'number' && error.status === 1) {
      return [];
    }
    throw error;
  }
}

function pathFromRgLine(line) {
  const firstColon = line.indexOf(':');
  return firstColon > 0 ? line.slice(0, firstColon) : line;
}

const controllerRoots = [
  'src/main/java/ro/uvt/pokedex/core/view',
  'src/main/java/ro/uvt/pokedex/core/controller'
];

const allowlistedMutatingGetFiles = new Set([
  'src/main/java/ro/uvt/pokedex/core/view/AdminActivityController.java',
  'src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java',
  'src/main/java/ro/uvt/pokedex/core/view/AdminGroupReportsController.java',
  'src/main/java/ro/uvt/pokedex/core/view/AdminIndividualReportsController.java',
  'src/main/java/ro/uvt/pokedex/core/view/AdminViewController.java',
  'src/main/java/ro/uvt/pokedex/core/view/user/ActivityInstanceController.java'
]);

const mutatingGetMatches = runRg(
  '@GetMapping\\(".*(delete|duplicate)/',
  controllerRoots
);
const mutatingGetFiles = new Set(mutatingGetMatches.map(pathFromRgLine));

for (const file of mutatingGetFiles) {
  if (!allowlistedMutatingGetFiles.has(file)) {
    errors.push(
      `${file}: new mutating GET route detected (delete/duplicate). Use POST/PUT/PATCH/DELETE.`
    );
  }
}

const allowlistedPrintStackTraceFiles = new Set([]);

const printStackTraceMatches = runRg('printStackTrace\\(', controllerRoots);
const printStackTraceFiles = new Set(printStackTraceMatches.map(pathFromRgLine));
for (const file of printStackTraceFiles) {
  if (!allowlistedPrintStackTraceFiles.has(file)) {
    errors.push(
      `${file}: new printStackTrace usage detected in transport layer; use structured logging.`
    );
  }
}

const allowlistedYearParseFiles = new Set([
]);

const riskyYearParseMatches = runRg(
  'Integer\\.parseInt\\((startYear|endYear)\\)',
  controllerRoots
);
const riskyYearParseFiles = new Set(riskyYearParseMatches.map(pathFromRgLine));
for (const file of riskyYearParseFiles) {
  if (!allowlistedYearParseFiles.has(file)) {
    errors.push(
      `${file}: new unsafe start/end year parsing detected; add validated parsing + deterministic 400 behavior.`
    );
  }
}

function emitAllowlistShrinkHint(currentFiles, allowlist, label) {
  const missingAllowlisted = [...allowlist].filter((file) => !currentFiles.has(file));
  if (missingAllowlisted.length > 0) {
    console.log(
      `H07 guardrail note: ${missingAllowlisted.length} ${label} allowlisted file(s) no longer match. Consider shrinking allowlist.`
    );
  }
}

emitAllowlistShrinkHint(mutatingGetFiles, allowlistedMutatingGetFiles, 'mutating-GET');
emitAllowlistShrinkHint(printStackTraceFiles, allowlistedPrintStackTraceFiles, 'printStackTrace');
emitAllowlistShrinkHint(riskyYearParseFiles, allowlistedYearParseFiles, 'year-parse');

const userControllerPath = 'src/main/java/ro/uvt/pokedex/core/controller/UserController.java';
const adminResearcherControllerPath = 'src/main/java/ro/uvt/pokedex/core/controller/AdminResearcherController.java';
const requestBodyWithoutValidMatches = runRg(
  '@RequestBody',
  [userControllerPath, adminResearcherControllerPath]
).filter((line) => !line.includes('@Valid'));

if (requestBodyWithoutValidMatches.length > 0) {
  requestBodyWithoutValidMatches.forEach((line) => {
    errors.push(`${line}: @RequestBody in targeted admin API controllers must use @Valid.`);
  });
}

const exportControllerPath = 'src/main/java/ro/uvt/pokedex/core/controller/ExportController.java';
const exportGenericCatch = runRg('catch \\(Exception', [exportControllerPath]);
if (exportGenericCatch.length > 0) {
  errors.push(
    `${exportControllerPath}: generic exception catch is forbidden in export endpoint logic; rely on centralized mapping.`
  );
}

const loginTemplatePath = 'src/main/resources/templates/login.html';
const loginTemplate = fs.readFileSync(loginTemplatePath, 'utf8');
if (!/name="username"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login form must keep name=\"username\" for Spring form-login compatibility.`);
}
if (!/autocomplete="username"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login username/email input must declare autocomplete=\"username\".`);
}
if (!/name="password"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login form must keep name=\"password\" for Spring form-login compatibility.`);
}
if (!/autocomplete="current-password"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login password input must declare autocomplete=\"current-password\".`);
}

if (errors.length > 0) {
  console.error('H07 guardrail verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H07 guardrail verification passed.');

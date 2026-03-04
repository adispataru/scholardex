const { execFileSync } = require('child_process');

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

function emitAllowlistShrinkHint(currentFiles, allowlist, label) {
  const missingAllowlisted = [...allowlist].filter((file) => !currentFiles.has(file));
  if (missingAllowlisted.length > 0) {
    console.log(
      `H08 guardrail note: ${missingAllowlisted.length} ${label} allowlisted file(s) no longer match. Consider shrinking allowlist.`
    );
  }
}

const runtimeRoots = ['src/main/java'];

const allowlistedPrintStackTraceFiles = new Set([]);

const printStackTraceMatches = runRg('^[^/]*printStackTrace\\(', runtimeRoots);
const printStackTraceFiles = new Set(printStackTraceMatches.map(pathFromRgLine));
for (const file of printStackTraceFiles) {
  if (!allowlistedPrintStackTraceFiles.has(file)) {
    errors.push(
      `${file}: new runtime printStackTrace usage detected; use structured logger + mapped error behavior.`
    );
  }
}
emitAllowlistShrinkHint(printStackTraceFiles, allowlistedPrintStackTraceFiles, 'printStackTrace');

const allowlistedStdIoFiles = new Set([]);

const stdIoMatches = runRg('^[^/]*System\\.(out|err)\\.println\\(', runtimeRoots);
const stdIoFiles = new Set(stdIoMatches.map(pathFromRgLine));
for (const file of stdIoFiles) {
  if (!allowlistedStdIoFiles.has(file)) {
    errors.push(
      `${file}: new runtime System.out/System.err usage detected; use structured logging.`
    );
  }
}
emitAllowlistShrinkHint(stdIoFiles, allowlistedStdIoFiles, 'System.out/System.err');

const schedulerFile = ['src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java'];
const schedulerSignalPatterns = [
  '@Scheduled\\(',
  'Publication task \\{\\} failed',
  'Citations task \\{\\} failed'
];

for (const pattern of schedulerSignalPatterns) {
  const matches = runRg(pattern, schedulerFile);
  if (matches.length === 0) {
    errors.push(
      `ScopusUpdateScheduler missing expected diagnostics/operability signal pattern: ${pattern}`
    );
  }
}

if (errors.length > 0) {
  console.error('H08 observability guardrail verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H08 observability guardrail verification passed.');

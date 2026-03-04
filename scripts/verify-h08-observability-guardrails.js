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

const requestCorrelationFilterFile = ['src/main/java/ro/uvt/pokedex/core/config/RequestCorrelationFilter.java'];
const requestCorrelationPatterns = [
  'class RequestCorrelationFilter',
  'X-Request-Id',
  'MDC\\.put\\(\"requestId\"',
  'response\\.setHeader\\((\"X-Request-Id\"|REQUEST_ID_HEADER)'
];

for (const pattern of requestCorrelationPatterns) {
  const matches = runRg(pattern, requestCorrelationFilterFile);
  if (matches.length === 0) {
    errors.push(
      `RequestCorrelationFilter missing expected B08 correlation pattern: ${pattern}`
    );
  }
}

const schedulerCorrelationPatterns = [
  'MDC\\.put\\(\"jobType\"|SchedulerCorrelationSupport\\.withSchedulerContext',
  'MDC\\.put\\(\"taskId\"|SchedulerCorrelationSupport\\.withSchedulerContext',
  'MDC\\.put\\(\"phase\"|SchedulerCorrelationSupport\\.withSchedulerContext'
];

for (const pattern of schedulerCorrelationPatterns) {
  const matches = runRg(pattern, schedulerFile);
  if (matches.length === 0) {
    errors.push(
      `ScopusUpdateScheduler missing expected B08 correlation context pattern: ${pattern}`
    );
  }
}

if (errors.length > 0) {
  console.error('H08 observability guardrail verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H08 observability guardrail verification passed.');

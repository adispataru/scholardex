const { execFileSync } = require('child_process');

const errors = [];

function runRg(pattern, paths) {
  const runners = [
    { cmd: 'rg', args: ['-n', pattern, ...paths] },
    { cmd: 'grep', args: ['-R', '-n', '-E', pattern, ...paths] }
  ];

  let missingToolCount = 0;
  for (const runner of runners) {
    try {
      const output = execFileSync(
        runner.cmd,
        runner.args,
        { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
      );
      return output
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean);
    } catch (error) {
      if (error.code === 'ENOENT') {
        missingToolCount += 1;
        continue;
      }
      if (typeof error.status === 'number' && error.status === 1) {
        return [];
      }
      throw error;
    }
  }

  if (missingToolCount === runners.length) {
    errors.push(
      'H08 observability guardrail verification requires either `rg` (ripgrep) or `grep` to be available in PATH.'
    );
    process.exit(1);
  }

  return [];
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

const gradleFile = ['build.gradle'];
if (runRg('spring-boot-starter-actuator', gradleFile).length === 0) {
  errors.push('build.gradle: actuator dependency missing for H08-P2 baseline.');
}

const propertiesFile = ['src/main/resources/application.properties'];
const managementPatterns = [
  '^management\\.endpoints\\.web\\.base-path=/actuator$',
  '^management\\.endpoint\\.health\\.probes\\.enabled=true$',
  '^management\\.endpoint\\.health\\.group\\.liveness\\.include=',
  '^management\\.endpoint\\.health\\.group\\.readiness\\.include='
];
for (const pattern of managementPatterns) {
  if (runRg(pattern, propertiesFile).length === 0) {
    errors.push(`application.properties missing H08-P2 management config pattern: ${pattern}`);
  }
}

const securityFile = ['src/main/java/ro/uvt/pokedex/core/config/WebSecurityConfig.java'];
const actuatorSecurityPatterns = [
  '"/actuator/health"',
  '"/actuator/health/liveness"',
  '"/actuator/health/readiness"',
  '"/actuator/\\*\\*"'
];
for (const pattern of actuatorSecurityPatterns) {
  if (runRg(pattern, securityFile).length === 0) {
    errors.push(`WebSecurityConfig missing expected actuator policy marker: ${pattern}`);
  }
}

const startupTrackerFile = ['src/main/java/ro/uvt/pokedex/core/observability/StartupReadinessTracker.java'];
if (runRg('class StartupReadinessTracker', startupTrackerFile).length === 0) {
  errors.push('StartupReadinessTracker missing for H08-P2 startup readiness baseline.');
}

const startupHealthFile = ['src/main/java/ro/uvt/pokedex/core/observability/StartupHealthIndicator.java'];
if (runRg('class StartupHealthIndicator', startupHealthFile).length === 0) {
  errors.push('StartupHealthIndicator missing for H08-P2 readiness health contributor.');
}

const metricsMarkers = [
  { file: 'src/main/java/ro/uvt/pokedex/core/DataLoaderNew.java', pattern: 'core\\.startup\\.phase\\.duration' },
  { file: 'src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java', pattern: 'core\\.scheduler\\.scopus\\.poll\\.duration' },
  { file: 'src/main/java/ro/uvt/pokedex/core/observability/H19CanonicalMetrics.java', pattern: 'core\\.h19\\.canonical\\.build\\.duration' },
  { file: 'src/main/java/ro/uvt/pokedex/core/observability/H19CanonicalMetrics.java', pattern: 'core\\.h19\\.source_link\\.transitions' },
  { file: 'src/main/java/ro/uvt/pokedex/core/observability/H19CanonicalMetrics.java', pattern: 'core\\.h19\\.identity_conflict\\.created' },
  { file: 'src/main/java/ro/uvt/pokedex/core/observability/ScholardexOperabilityGaugeBinder.java', pattern: 'core\\.h19\\.identity_conflicts\\.open' },
  { file: 'src/main/java/ro/uvt/pokedex/core/observability/ScholardexOperabilityGaugeBinder.java', pattern: 'core\\.h19\\.source_links\\.state' }
];
for (const marker of metricsMarkers) {
  if (runRg(marker.pattern, [marker.file]).length === 0) {
    errors.push(`${marker.file} missing expected metrics marker: ${marker.pattern}`);
  }
}

const h19TriageLogMarkers = [
  { file: 'src/main/java/ro/uvt/pokedex/core/service/importing/scopus/ScopusCanonicalMaterializationService.java', pattern: 'H19_TRIAGE canonical_materialization' },
  { file: 'src/main/java/ro/uvt/pokedex/core/service/application/ScopusBigBangMigrationService.java', pattern: 'H19_TRIAGE canonical_build' },
  { file: 'src/main/java/ro/uvt/pokedex/core/service/application/ScholardexSourceLinkService.java', pattern: 'H19_TRIAGE source_link_reconcile' },
  { file: 'src/main/java/ro/uvt/pokedex/core/service/application/ScholardexEdgeReconciliationService.java', pattern: 'H19_TRIAGE edge_reconcile' }
];
for (const marker of h19TriageLogMarkers) {
  if (runRg(marker.pattern, [marker.file]).length === 0) {
    errors.push(`${marker.file} missing expected H19 triage log marker: ${marker.pattern}`);
  }
}

if (errors.length > 0) {
  console.error('H08 observability guardrail verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H08 observability guardrail verification passed.');

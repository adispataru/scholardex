const { execFileSync } = require('child_process');

const errors = [];

function runSearch(pattern, paths) {
  const runners = [
    { cmd: 'rg', args: ['-n', pattern, ...paths] },
    { cmd: 'grep', args: ['-R', '-n', '-E', pattern, ...paths] }
  ];

  let lastError = null;

  for (const runner of runners) {
    let output = '';
    try {
      output = execFileSync(runner.cmd, runner.args, {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe']
      });
    } catch (error) {
      if (error.code === 'ENOENT') {
        lastError = error;
        continue;
      }
      if (typeof error.status === 'number' && error.status === 1) {
        return [];
      }
      throw error;
    }

    return output
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean);
  }

  try {
    throw lastError ?? new Error('No search backend available (rg/grep).');
  } catch (error) {
    if (error && error.code === 'ENOENT') {
      errors.push(
        'Architecture guardrail requires either `rg` (ripgrep) or `grep` to be available in PATH.'
      );
      process.exit(1);
    }
    throw error;
  }
}

function runRg(pattern, paths) {
  try {
    return runSearch(pattern, paths);
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
const reportingRoot = ['src/main/java/ro/uvt/pokedex/core/service/reporting'];

const allowedControllerRepositoryImports = new Set([]);

const controllerRepositoryMatches = runRg(
  '^import ro\\.uvt\\.pokedex\\.core\\.repository',
  controllerRoots
);
const controllerRepositoryFiles = new Set(
  controllerRepositoryMatches.map(pathFromRgLine)
);

for (const file of controllerRepositoryFiles) {
  if (!allowedControllerRepositoryImports.has(file)) {
    errors.push(
      `${file}: new controller/view repository import is forbidden (Z1 -> Z4).`
    );
  }
}

const missingAllowed = [...allowedControllerRepositoryImports].filter(
  (file) => !controllerRepositoryFiles.has(file)
);
if (missingAllowed.length > 0) {
  console.log(
    `Architecture note: ${missingAllowed.length} allowlisted controller file(s) no longer import repositories. Consider shrinking allowlist.`
  );
}

const reportingImportsInControllers = runRg(
  '^import ro\\.uvt\\.pokedex\\.core\\.service\\.reporting',
  controllerRoots
);
if (reportingImportsInControllers.length > 0) {
  reportingImportsInControllers.forEach((line) =>
    errors.push(`${line}: direct Z1 -> Z3 reporting import is forbidden.`)
  );
}

const cacheServiceInReporting = runRg(
  'CacheService',
  reportingRoot
);
if (cacheServiceInReporting.length > 0) {
  cacheServiceInReporting.forEach((line) =>
    errors.push(`${line}: reporting must depend on ReportingLookupPort, not CacheService.`)
  );
}

if (errors.length > 0) {
  console.error('Architecture boundary verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('Architecture boundary verification passed.');

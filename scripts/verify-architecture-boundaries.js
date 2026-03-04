const { execSync } = require('child_process');

const errors = [];

function runRg(pattern, paths) {
  try {
    const output = execSync(
      `rg -n "${pattern}" ${paths.join(' ')}`,
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

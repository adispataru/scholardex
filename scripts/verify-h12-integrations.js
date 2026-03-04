const fs = require('fs');
const { execFileSync } = require('child_process');

const errors = [];

function readFile(path) {
  return fs.readFileSync(path, 'utf8');
}

function runRg(pattern, paths) {
  try {
    const output = execFileSync('rg', ['-n', pattern, ...paths], {
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe']
    });
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

const schedulerPath =
  'src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java';
const scopusDataServicePath =
  'src/main/java/ro/uvt/pokedex/core/service/importing/ScopusDataService.java';
const taskModelPath =
  'src/main/java/ro/uvt/pokedex/core/model/tasks/Task.java';
const rankingImporterPaths = [
  'src/main/java/ro/uvt/pokedex/core/service/importing/RankingService.java',
  'src/main/java/ro/uvt/pokedex/core/service/importing/CoreConferenceRankingService.java',
  'src/main/java/ro/uvt/pokedex/core/service/importing/URAPRankingService.java'
];

const schedulerContent = readFile(schedulerPath);
const scopusDataServiceContent = readFile(scopusDataServicePath);
const taskContent = readFile(taskModelPath);

if (schedulerContent.includes('minusYears(50)')) {
  errors.push(`${schedulerPath}: forced full reimport override detected (minusYears(50)).`);
}

if (schedulerContent.includes('parseCoverDate("1970-01-01")')) {
  errors.push(`${schedulerPath}: hardcoded citation date fallback detected.`);
}

const requiredTaskFields = [
  'attemptCount',
  'maxAttempts',
  'nextAttemptAt',
  'lastErrorCode',
  'lastErrorMessage'
];
for (const field of requiredTaskFields) {
  if (!taskContent.includes(field)) {
    errors.push(`${taskModelPath}: missing retry metadata field ${field}.`);
  }
}

const schedulerPatterns = [
  'computeBackoffSeconds',
  'isReadyForAttempt',
  'RETRY_SCHEDULED',
  'mapIntegrationException'
];
for (const pattern of schedulerPatterns) {
  if (!schedulerContent.includes(pattern)) {
    errors.push(`${schedulerPath}: missing expected retry/integration pattern '${pattern}'.`);
  }
}

const scopusSafetyPatterns = [
  'readRequiredText(',
  'readRequiredIndexedText(',
  'ImportProcessingResult',
  'markSkipped('
];
for (const pattern of scopusSafetyPatterns) {
  if (!scopusDataServiceContent.includes(pattern)) {
    errors.push(`${scopusDataServicePath}: missing expected mapping hardening marker '${pattern}'.`);
  }
}

if (runRg('rootNode\\.get\\(\"[^\"]+\"\\)\\.get\\(', [scopusDataServicePath]).length > 0) {
  errors.push(`${scopusDataServicePath}: unsafe chained rootNode.get(...).get(...) access detected.`);
}

for (const filePath of rankingImporterPaths) {
  const content = readFile(filePath);
  if (!content.includes('ImportProcessingResult')) {
    errors.push(`${filePath}: missing import result accounting.`);
  }
}

if (errors.length > 0) {
  console.error('H12 integration guardrail verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H12 integration guardrail verification passed.');

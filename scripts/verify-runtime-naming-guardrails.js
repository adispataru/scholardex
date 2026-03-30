const fs = require('fs');
const path = require('path');

const roots = [
  'src/main/java',
  'src/test/java',
  '.github/workflows',
  'scripts'
];

const explicitFiles = [
  'package.json',
  'build.gradle',
  'docs/quality-gates.md',
  'docs/failure-triage.md'
];

const forbiddenMarkers = [
  'H22OperationalStatusService',
  'DefaultH22OperationalStatusService',
  'H22OperationalStatusSnapshot',
  'H19CanonicalMetrics',
  'H19_TRIAGE',
  'h09-quality-gates.yml',
  'h09-security-gates.yml',
  'verify-h06-persistence',
  'verify-h07-guardrails',
  'verify-h08-observability-guardrails',
  'verify-h08-baseline',
  'verify-h09-baseline',
  'verify-h12-integrations',
  'verify-h19-baseline',
  'verify-h23-ui-guardrails',
  'verify-h23-ui',
  'verify-h25-route-guardrails',
  'verify-h06-persistence.js',
  'verify-h07-guardrails.js',
  'verify-h08-observability-guardrails.js',
  'verify-h12-integrations.js',
  'verify-h19-baseline.js',
  'verify-h23-ui-guardrails.js',
  'verify-h25-route-guardrails.js'
];

function listFiles(dir) {
  if (!fs.existsSync(dir)) {
    return [];
  }
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...listFiles(full));
    } else if (entry.isFile()) {
      out.push(full);
    }
  }
  return out;
}

const files = new Set([
  ...roots.flatMap((root) => listFiles(path.join(process.cwd(), root))),
  ...explicitFiles.map((file) => path.join(process.cwd(), file))
]);

const errors = [];
const selfPath = path.join(process.cwd(), 'scripts', 'verify-runtime-naming-guardrails.js');
for (const file of files) {
  if (file === selfPath) {
    continue;
  }
  const content = fs.readFileSync(file, 'utf8');
  const relative = path.relative(process.cwd(), file);
  for (const marker of forbiddenMarkers) {
    if (content.includes(marker)) {
      errors.push(`${relative}: contains retired H28 naming marker '${marker}'`);
    }
  }
}

if (errors.length > 0) {
  console.error('Runtime naming guardrail verification failed:');
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log('Runtime naming guardrail verification passed.');

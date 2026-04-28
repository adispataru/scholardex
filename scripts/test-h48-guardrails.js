const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const repoRoot = path.join(__dirname, '..');

function runNodeScript(relativeScriptPath) {
  execFileSync(process.execPath, [path.join(repoRoot, relativeScriptPath)], {
    cwd: repoRoot,
    stdio: 'pipe'
  });
}

function withFileContent(relativePath, content, fn) {
  const fullPath = path.join(repoRoot, relativePath);
  const existed = fs.existsSync(fullPath);
  const previous = existed ? fs.readFileSync(fullPath, 'utf8') : null;
  fs.mkdirSync(path.dirname(fullPath), { recursive: true });
  fs.writeFileSync(fullPath, content, 'utf8');
  try {
    fn();
  } finally {
    if (existed) {
      fs.writeFileSync(fullPath, previous, 'utf8');
    } else {
      fs.rmSync(fullPath, { force: true });
    }
  }
}

function withPatchedFile(relativePath, mutate, fn) {
  const fullPath = path.join(repoRoot, relativePath);
  const original = fs.readFileSync(fullPath, 'utf8');
  const updated = mutate(original);
  fs.writeFileSync(fullPath, updated, 'utf8');
  try {
    fn();
  } finally {
    fs.writeFileSync(fullPath, original, 'utf8');
  }
}

function expectScriptFailure(relativeScriptPath, expectedMessageFragment) {
  let failed = false;
  try {
    runNodeScript(relativeScriptPath);
  } catch (error) {
    failed = true;
    const stderr = String(error.stderr || '');
    assert(
      stderr.includes(expectedMessageFragment),
      `Expected ${relativeScriptPath} failure output to include "${expectedMessageFragment}", got:\n${stderr}`
    );
  }
  assert(failed, `Expected ${relativeScriptPath} to fail`);
}

function testTemplateAssetsCoversRankingsTemplates() {
  withFileContent(
    'src/main/resources/templates/rankings/__h48_guardrail_fixture__.html',
    '<!DOCTYPE html><html><head><title>Fixture</title></head><body><main>Missing asset contracts</main></body></html>',
    () => {
      expectScriptFailure(
        'scripts/verify-template-assets.js',
        'src/main/resources/templates/rankings/__h48_guardrail_fixture__.html: missing core style contract'
      );
    }
  );
}

function testUiGuardrailsCoverPublicationsListSurface() {
  withPatchedFile(
    'src/main/resources/templates/publications/list.html',
    (content) => `${content}\n<!-- H48.6 fixture /rankings/wos" -->\n`,
    () => {
      expectScriptFailure(
        'scripts/verify-ui-guardrails.js',
        "src/main/resources/templates/publications/list.html: contains forbidden canonical-route regression '/rankings/wos\"'"
      );
    }
  );
}

function testRouteGuardrailsCoverLandingAndPublicationsTemplates() {
  withPatchedFile(
    'src/main/resources/templates/landing.html',
    (content) => `${content}\n<!-- H48.6 fixture admin/activity-indicator-edit -->\n`,
    () => {
      expectScriptFailure(
        'scripts/verify-route-guardrails.js',
        "src/main/resources/templates/landing.html: contains forbidden stale view/template naming token 'admin/activity-indicator-edit'"
      );
    }
  );
}

function run() {
  testTemplateAssetsCoversRankingsTemplates();
  testUiGuardrailsCoverPublicationsListSurface();
  testRouteGuardrailsCoverLandingAndPublicationsTemplates();
  console.log('H48 guardrail regression tests passed.');
}

run();

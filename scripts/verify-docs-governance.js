const fs = require('fs');
const path = require('path');

const root = process.cwd();
const docsDir = path.join(root, 'docs');
const readmePath = path.join(docsDir, 'README.md');
const tasksPath = path.join(root, 'TASKS.md');
const tasksDonePath = path.join(root, 'TASKS-done.md');

const allowedTopLevelDocs = new Set([
  'docs/README.md',
  'docs/architecture.md',
  'docs/contracts.md',
  'docs/workflows.md',
  'docs/operational-playbook.md',
  'docs/frontend-conventions.md',
  'docs/quality-gates.md',
  'docs/failure-triage.md',
  'docs/doc-governance.md',
  'docs/release-hygiene.md',
  'docs/c01-cnfis-rule-spec.md'
]);

function listFiles(dir, extension) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...listFiles(full, extension));
    } else if (entry.isFile() && full.endsWith(extension)) {
      out.push(full);
    }
  }
  return out;
}

function rel(file) {
  return path.relative(root, file).replace(/\\/g, '/');
}

function firstLines(file, count = 8) {
  return fs.readFileSync(file, 'utf8').split('\n').slice(0, count).join('\n');
}

function collectTaskIds(file) {
  if (!fs.existsSync(file)) return new Set();
  const content = fs.readFileSync(file, 'utf8');
  const ids = content.match(/\bH\d+(?:\.\d+|-[A-Z]\d+)?\b/g) || [];
  return new Set(ids.map((id) => id.toLowerCase()));
}

function taskIdFromDoc(file) {
  const base = path.basename(file, '.md').toLowerCase();
  const match = base.match(/^(h\d+(?:\.\d+)?)/);
  return match ? match[1] : null;
}

const errors = [];
const activeTaskIds = collectTaskIds(tasksPath);
const closedTaskIds = collectTaskIds(tasksDonePath);

if (!fs.existsSync(readmePath)) {
  errors.push('docs/README.md is missing');
} else {
  const readme = fs.readFileSync(readmePath, 'utf8');
  if (!readme.includes('Project Docs')) {
    errors.push('docs/README.md must index the curated top-level project docs');
  }
}

const topLevelDocs = fs.readdirSync(docsDir, { withFileTypes: true })
  .filter((entry) => entry.isFile() && entry.name.endsWith('.md') && entry.name !== 'README.md')
  .map((entry) => path.join(docsDir, entry.name))
  .sort();

const readmeContent = fs.existsSync(readmePath) ? fs.readFileSync(readmePath, 'utf8') : '';
for (const file of topLevelDocs) {
  const relative = rel(file);
  if (!allowedTopLevelDocs.has(relative)) {
    errors.push(`${relative}: top-level docs must be part of the curated project-doc set`);
  }
  if (/\/h\d+/i.test(relative)) {
    errors.push(`${relative}: task-named docs must not live at top level`);
  }
  const header = firstLines(file);
  if (!header.includes('Status:')) {
    errors.push(`${relative}: top-level docs must carry an explicit Status header`);
  }
  if (!readmeContent.includes(`\`${relative}\``)) {
    errors.push(`${relative}: top-level docs must be indexed in docs/README.md`);
  }
}

for (const relative of allowedTopLevelDocs) {
  if (!fs.existsSync(path.join(root, relative))) {
    errors.push(`${relative}: curated project doc is missing`);
  }
}

for (const dirName of ['active', 'closed']) {
  const dir = path.join(docsDir, 'tasks', dirName);
  for (const file of listFiles(dir, '.md')) {
    const relative = rel(file);
    const taskId = taskIdFromDoc(file);
    if (!taskId) {
      errors.push(`${relative}: task doc under docs/tasks/${dirName} must start with a task id`);
      continue;
    }
    const header = firstLines(file);
    if (!header.includes('Status:')) {
      errors.push(`${relative}: task docs must carry an explicit Status header`);
    }
    if (dirName === 'active' && !activeTaskIds.has(taskId)) {
      errors.push(`${relative}: lives under docs/tasks/active but ${taskId.toUpperCase()} is not active in TASKS.md`);
    }
    if (dirName === 'closed' && !closedTaskIds.has(taskId)) {
      errors.push(`${relative}: lives under docs/tasks/closed but ${taskId.toUpperCase()} is not archived in TASKS-done.md`);
    }
  }
}

for (const file of topLevelDocs) {
  const taskId = taskIdFromDoc(file);
  if (taskId && (activeTaskIds.has(taskId) || closedTaskIds.has(taskId))) {
    errors.push(`${rel(file)}: task-derived docs must live under docs/tasks/active or docs/tasks/closed`);
  }
}

for (const file of listFiles(path.join(docsDir, 'archive'), '.md')) {
  const relative = rel(file);
  const header = firstLines(file);
  if (!header.includes('Status:')) {
    errors.push(`${relative}: archived docs must carry an explicit Status header`);
    continue;
  }
  if (!/(historical|superseded|exploratory)/i.test(header)) {
    errors.push(`${relative}: archived docs must declare historical, superseded, or exploratory status`);
  }
}

const closedReferenceAllowlist = new Set([
  'docs/README.md',
  'docs/architecture.md',
  'docs/contracts.md',
  'docs/workflows.md',
  'docs/operational-playbook.md',
  'docs/frontend-conventions.md',
  'docs/quality-gates.md',
  'docs/failure-triage.md',
  'docs/doc-governance.md',
  'docs/release-hygiene.md',
  'TASKS-done.md',
  'scripts/verify-docs-governance.js',
  'scripts/verify-h25-route-guardrails.js'
]);

const activeDocs = [readmePath, ...topLevelDocs];
for (const file of activeDocs) {
  const relative = rel(file);
  const content = fs.readFileSync(file, 'utf8');
  if (content.includes('docs/tasks/closed/') && !closedReferenceAllowlist.has(relative)) {
    errors.push(`${relative}: active docs must not reference closed task docs unless explicitly allowlisted`);
  }
}

for (const file of listFiles(path.join(root, 'scripts'), '.js')) {
  const relative = rel(file);
  const content = fs.readFileSync(file, 'utf8');
  if (content.includes('docs/tasks/closed/') && !closedReferenceAllowlist.has(relative)) {
    errors.push(`${relative}: scripts must not reference closed task docs unless explicitly allowlisted`);
  }
}

if (errors.length > 0) {
  console.error('Documentation governance verification failed:');
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log('Documentation governance verification passed.');

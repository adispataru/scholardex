const fs = require('fs');
const path = require('path');

const appEntrypointPath = path.join('frontend', 'src', 'app.js');
const templatesRoots = [
  path.join('src', 'main', 'resources', 'templates', 'admin'),
  path.join('src', 'main', 'resources', 'templates', 'user'),
  path.join('src', 'main', 'resources', 'templates', 'events')
];

function listHtmlFiles(dir) {
  const files = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...listHtmlFiles(full));
    } else if (entry.isFile() && full.endsWith('.html')) {
      files.push(full);
    }
  }
  return files;
}

const errors = [];
const appEntrypoint = fs.readFileSync(appEntrypointPath, 'utf8');

if (!appEntrypoint.includes("datatables.net-bs5")) {
  errors.push(`${appEntrypointPath}: expected Bootstrap 5 DataTables integration import`);
}
if (!appEntrypoint.includes('initSharedDataTables')) {
  errors.push(`${appEntrypointPath}: expected shared DataTables initializer`);
}

const htmlFiles = templatesRoots.flatMap(listHtmlFiles);
const datatablesFreePages = new Set([
  path.join('src', 'main', 'resources', 'templates', 'user', 'indicators-apply-publications.html'),
  path.join('src', 'main', 'resources', 'templates', 'user', 'indicators-apply-activities.html')
]);
for (const file of htmlFiles) {
  const content = fs.readFileSync(file, 'utf8');

  if (content.includes('/js/demo/datatables-demo.js')) {
    errors.push(`${file}: authenticated templates must not include the legacy DataTables bootstrap script`);
  }

  const tableTags = [...content.matchAll(/<table\b[^>]*>/g)];
  for (const match of tableTags) {
    const tag = match[0];
    const hasDataTableId = /(?:\bid=|th:id=)"[^"]*dataTable[^"]*"/.test(tag);
    if (!hasDataTableId) {
      continue;
    }

    if (datatablesFreePages.has(file)) {
      continue;
    }

    const classMatch = tag.match(/\bclass="([^"]*)"/);
    const classValue = classMatch ? classMatch[1] : '';
    if (!classValue.split(/\s+/).includes('js-datatable')) {
      errors.push(`${file}: table with dataTable id/th:id is missing 'js-datatable' class`);
    }
  }
}

if (errors.length > 0) {
  console.error('DataTables opt-in verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('DataTables opt-in verification passed.');

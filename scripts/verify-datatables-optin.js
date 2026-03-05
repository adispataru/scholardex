const fs = require('fs');
const path = require('path');

const datatablesBootstrapPath = path.join('src', 'main', 'resources', 'static', 'js', 'demo', 'datatables-demo.js');
const templatesRoot = path.join('src', 'main', 'resources', 'templates');

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
const bootstrap = fs.readFileSync(datatablesBootstrapPath, 'utf8');

if (bootstrap.includes('[id^="dataTable"]') || bootstrap.includes("[id^='dataTable']")) {
  errors.push(`${datatablesBootstrapPath}: forbidden ID-prefix auto-init selector found`);
}
if (!bootstrap.includes('table.js-datatable')) {
  errors.push(`${datatablesBootstrapPath}: expected explicit opt-in selector 'table.js-datatable'`);
}

const htmlFiles = listHtmlFiles(templatesRoot);
const datatablesFreePages = new Set([
  path.join('src', 'main', 'resources', 'templates', 'rankings', 'wos.html'),
  path.join('src', 'main', 'resources', 'templates', 'rankings', 'core.html'),
  path.join('src', 'main', 'resources', 'templates', 'rankings', 'urap.html'),
  path.join('src', 'main', 'resources', 'templates', 'admin', 'rankings.html'),
  path.join('src', 'main', 'resources', 'templates', 'admin', 'rankings-core.html'),
  path.join('src', 'main', 'resources', 'templates', 'admin', 'rankings-urap.html'),
  path.join('src', 'main', 'resources', 'templates', 'admin', 'scopus-venues.html'),
  path.join('src', 'main', 'resources', 'templates', 'admin', 'scopus-authors.html'),
  path.join('src', 'main', 'resources', 'templates', 'admin', 'scopus-affiliations.html'),
  path.join('src', 'main', 'resources', 'templates', 'admin', 'scopus-editAuthor.html'),
  path.join('src', 'main', 'resources', 'templates', 'user', 'indicators-apply-publications.html'),
  path.join('src', 'main', 'resources', 'templates', 'user', 'indicators-apply-activities.html')
]);
for (const file of htmlFiles) {
  const content = fs.readFileSync(file, 'utf8');

  if (datatablesFreePages.has(file) && content.includes('/js/demo/datatables-demo.js')) {
    errors.push(`${file}: API-driven large-table pages must not include DataTables bootstrap`);
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

const fs = require('fs');
const path = require('path');

const roots = [
  'src/main/resources/templates/admin',
  'src/main/resources/templates/user',
  'src/main/resources/templates/rankings',
  'src/main/resources/templates/scholardex'
];

const allowlistedExternalAssetReferences = new Map([
  [
    'src/main/resources/templates/user/publications-add-step1.html',
    new Set(['https://code.jquery.com/ui/1.13.2/themes/base/jquery-ui.css'])
  ],
  [
    'src/main/resources/templates/user/publications-add-step2.html',
    new Set(['https://code.jquery.com/ui/1.13.2/themes/base/jquery-ui.css'])
  ],
  [
    'src/main/resources/templates/admin/rankings-urap-details.html',
    new Set(['https://cdn.jsdelivr.net/npm/chart.js'])
  ],
  [
    'src/main/resources/templates/admin/rankings-view.html',
    new Set(['https://unpkg.com/frappe-charts@1.6.2/dist/frappe-charts.min.umd.js'])
  ],
  [
    'src/main/resources/templates/rankings/urap-detail.html',
    new Set(['https://cdn.jsdelivr.net/npm/chart.js'])
  ],
  [
    'src/main/resources/templates/rankings/wos-detail.html',
    new Set(['https://unpkg.com/frappe-charts@1.6.2/dist/frappe-charts.min.umd.js'])
  ],
  [
    'src/main/resources/templates/scholardex/forum-detail.html',
    new Set(['https://unpkg.com/frappe-charts@1.6.2/dist/frappe-charts.min.umd.js'])
  ]
]);

const allowlistedInlineScriptFiles = new Set([
  'src/main/resources/templates/admin/citations.html',
  'src/main/resources/templates/admin/indicators.html',
  'src/main/resources/templates/admin/scopus-citations.html',
  'src/main/resources/templates/admin/scholardex-citations.html',
  'src/main/resources/templates/admin/scopus-venues.html',
  'src/main/resources/templates/rankings/wos-detail.html',
  'src/main/resources/templates/user/citations.html',
  'src/main/resources/templates/user/criteria-apply.html',
  'src/main/resources/templates/user/profile.html',
  'src/main/resources/templates/user/publications-add-step1.html',
  'src/main/resources/templates/user/publications-add-step2.html',
  'src/main/resources/templates/user/publications.html'
]);

function listHtmlFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...listHtmlFiles(full));
    } else if (entry.isFile() && full.endsWith('.html')) {
      out.push(full);
    }
  }
  return out;
}

const files = roots.flatMap(listHtmlFiles);
const errors = [];

for (const file of files) {
  if (file.endsWith('-bak.html')) {
    errors.push(`${file}: backup templates are forbidden in runtime template directories`);
    continue;
  }
  const content = fs.readFileSync(file, 'utf8');
  const hasCoreStyles = content.includes('/assets/app.css') || content.includes('fragments :: core-styles');
  const hasCoreScripts = content.includes('/assets/app.js') || content.includes('fragments :: core-scripts');
  const externalAssetReferences = [...content.matchAll(/<(?:script|link)\b[^>]+(?:src|href)\s*=\s*"(https?:\/\/[^"]+)"/gi)];
  const inlineScriptMatches = [...content.matchAll(/<script(?![^>]*\bsrc\s*=)(?![^>]*\bth:inline\s*=)[^>]*>/gi)];

  if (content.includes('/vendor/')) {
    errors.push(`${file}: contains forbidden /vendor/ reference`);
  }
  if (content.includes('/js/demo/dataTables-demo.js')) {
    errors.push(`${file}: uses non-canonical DataTables path '/js/demo/dataTables-demo.js' (expected '/js/demo/datatables-demo.js')`);
  }
  if (!hasCoreStyles) {
    errors.push(`${file}: missing core style contract (/assets/app.css or fragments :: core-styles)`);
  }
  if (!hasCoreScripts) {
    errors.push(`${file}: missing core script contract (/assets/app.js or fragments :: core-scripts)`);
  }
  for (const match of externalAssetReferences) {
    const externalReference = match[1];
    const allowedReferences = allowlistedExternalAssetReferences.get(file);
    if (!allowedReferences || !allowedReferences.has(externalReference)) {
      errors.push(`${file}: non-allowlisted external asset reference '${externalReference}'`);
    }
  }
  if (inlineScriptMatches.length > 0 && !allowlistedInlineScriptFiles.has(file)) {
    errors.push(`${file}: contains inline behavior script outside H05 allowlist`);
  }
}

if (errors.length > 0) {
  console.error('Template asset verification failed:');
  errors.forEach((e) => console.error(`- ${e}`));
  process.exit(1);
}

console.log('Template asset verification passed.');

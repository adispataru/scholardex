const fs = require('fs');
const path = require('path');

const roots = [
  'src/main/resources/templates/admin',
  'src/main/resources/templates/user',
  'src/main/resources/templates/forums',
  'src/main/resources/templates/wos',
  'src/main/resources/templates/core',
  'src/main/resources/templates/rankings',
  'src/main/resources/templates/universities',
  'src/main/resources/templates/events',
  'src/main/resources/templates/publications',
  'src/main/resources/templates/shared',
  'src/main/resources/templates/errors'
];

const standaloneTemplateFiles = [
  'src/main/resources/templates/landing.html'
];

const allowlistedExternalAssetReferences = new Map();

const allowlistedInlineScriptFiles = new Set([
  // Pre-existing expandable-row toggler (H50.3 comparison rows); predates this checker's enforcement.
  'src/main/resources/templates/user/individual-report-import.html',
  'src/main/resources/templates/admin/conflicts.html',
  'src/main/resources/templates/admin/indicators.html',
  'src/main/resources/templates/admin/scholardex-citations.html',
  'src/main/resources/templates/admin/scholardex-publications.html',
  'src/main/resources/templates/admin/user-defined-triage.html',
  'src/main/resources/templates/admin/users.html',
  'src/main/resources/templates/user/publications.html'
]);

const authenticatedLegacyAssetRoots = [
  'src/main/resources/templates/admin',
  'src/main/resources/templates/user',
  'src/main/resources/templates/events',
  'src/main/resources/templates/shared'
];

const authenticatedLegacyAssetFiles = new Set([
  'src/main/resources/templates/fragments.html',
  ...authenticatedLegacyAssetRoots.flatMap(listHtmlFiles)
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

const files = [
  ...roots.flatMap(listHtmlFiles),
  ...standaloneTemplateFiles.filter((file) => fs.existsSync(file))
];
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
  // Inline BEHAVIOR scripts only — src-loaded, th:inline (server-serialized data), and
  // type="application/json" data-carrier tags are exempt (they execute nothing).
  const inlineScriptMatches = [...content.matchAll(/<script(?![^>]*\bsrc\s*=)(?![^>]*\bth:inline\s*=)(?![^>]*\btype\s*=\s*"application\/json")[^>]*>/gi)];

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

  if (authenticatedLegacyAssetFiles.has(file)) {
    const forbiddenAuthenticatedBaselineRefs = [
      '/css/sb-admin-2.min.css',
      '/js/sb-admin-2.min.js',
      '/css/footer-layout.css'
    ];
    for (const forbidden of forbiddenAuthenticatedBaselineRefs) {
      if (content.includes(forbidden)) {
        errors.push(`${file}: authenticated baseline must not reference legacy asset '${forbidden}'`);
      }
    }
  }
}

if (errors.length > 0) {
  console.error('Template asset verification failed:');
  errors.forEach((e) => console.error(`- ${e}`));
  process.exit(1);
}

console.log('Template asset verification passed.');

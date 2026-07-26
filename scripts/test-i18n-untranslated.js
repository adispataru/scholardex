/**
 * H87 — fail the build on user-visible English left in the localized surfaces.
 *
 * WHY THIS EXISTS: the i18n conversion was done by pattern-matching, and each pattern missed a class the
 * previous one did not cover — quoted JS strings, then HTML attributes, then text nodes inside template
 * literals, then text nodes separated from their tag by an inline icon and a newline. Every round shipped
 * looking complete and was caught only by a human opening the app. This lint replaces that human.
 *
 * It flags any English-looking text that is NOT already routed through `#{...}` (templates) or `t()` (JS),
 * in the surfaces the project scoped for localization: public + researcher templates and the workspace
 * modules. Admin templates are deliberately out of scope.
 *
 * ALLOWLIST: identifiers and standards vocabulary stay English/Romanian-neutral by design (DOI, ISSN, CORE,
 * quartiles, "Perspectiva D", …). Add to ALLOWED only for genuine identifiers — not to silence real copy.
 */
const fs = require('fs');
const path = require('path');

const ALLOWED = new Set([
    'ScholarDex', 'DOI', 'ISSN', 'E-ISSN', 'eISSN', 'ORCID', 'OpenAlex', 'Scopus', 'WoS', 'CNFIS',
    'SENSE', 'CORE', 'URAP', 'ARWU', 'QS', 'DBLP', 'Crossref', 'PubMed', 'Excel', 'CSV', 'PDF',
    'Scopus IDs', 'WoS IDs', 'Scopus ID', 'WoS ID', 'Q1', 'Q2', 'Q3', 'Q4', 'AIS', 'IF',
    'Perspectiva D', 'FV Info 2016', 'FV Info 2026', 'Grant Cercetare',
    // Proper nouns and source vocabulary: database names and URAP's own indicator labels are not UI copy.
    'Web of Science', 'Google Scholar',
    'Article, Citation, Total Document, AIT, CIT, Collaboration, Total Score',
    'Total Document',   // a URAP metric name, i.e. source data rather than UI copy
    'Notifications,',   // fragment of an aria-label assembled from a localized key + a count
]);

const TEMPLATE_DIRS = [
    'src/main/resources/templates/user',
    'src/main/resources/templates/supervisor',
    'src/main/resources/templates/reports',
    'src/main/resources/templates/publications',
    'src/main/resources/templates/forums',
    'src/main/resources/templates/authors',
    'src/main/resources/templates/rankings',
    'src/main/resources/templates/universities',
    'src/main/resources/templates/core',
    'src/main/resources/templates/errors',
];
// The six org-unit report templates live under admin/ but are gated
// `hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')` — they are the supervisor's daily
// drill-in surface, so they are in scope. The rest of admin/ is admin-only and deliberately is not.
const TEMPLATE_FILES = [
    'src/main/resources/templates/landing.html',
    'src/main/resources/templates/changelog.html',
    'src/main/resources/templates/admin/orgunit-reports-list.html',
    'src/main/resources/templates/admin/orgunit-report-view.html',
    'src/main/resources/templates/admin/orgunit-report-compare.html',
    'src/main/resources/templates/admin/orgunit-promotions.html',
    'src/main/resources/templates/admin/division-report-select.html',
    'src/main/resources/templates/admin/department-report-visibility.html',
];
const JS_DIRS = ['frontend/src/modules/workspace'];
// individual-report-dashboard.js is served straight from static/ and is NOT part of the npm bundle, so it
// sits outside frontend/src and was invisible to this lint while the whole public surface was localised
// (H91). It renders the researcher's own scoring drilldown — including the sentences explaining why a
// score moved — so it is squarely in scope even though it lives elsewhere.
const JS_FILES = [
    'frontend/src/modules/admin/orgUnitReportDashboard.js',
    'src/main/resources/static/js/individual-report-dashboard.js',
];

// Two capitals or a capital + lowercase word, i.e. prose rather than a code/acronym token.
const ENGLISH_LIKE = /^[A-Z][a-z][A-Za-z0-9 ,&;:/()'’.\-]{2,120}$/;

function walk(dir, ext, out = []) {
    if (!fs.existsSync(dir)) return out;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full, ext, out);
        else if (full.endsWith(ext)) out.push(full);
    }
    return out;
}

function scan(file, isJs) {
    const src = fs.readFileSync(file, 'utf8');
    const hits = [];
    for (const m of src.matchAll(/>([^<>{}]{2,120})</gs)) {
        const raw = m.group ? m.group(1) : m[1];
        const text = raw.replace(/\s+/g, ' ').trim();
        if (!text || text.includes('${') || ALLOWED.has(text)) continue;
        if (!ENGLISH_LIKE.test(text)) continue;
        // templates: the owning tag may already carry th:text / th:utext
        if (!isJs) {
            // Look back far enough to cover a multi-line th:text expression: a 400-char window silently
            // reported long ternaries (core/ranking-detail.html) as untranslated.
            const head = src.slice(Math.max(0, m.index - 4000), m.index);
            const tag = head.slice(head.lastIndexOf('<'));
            if (tag.includes('th:text') || tag.includes('th:utext')) continue;
        }
        hits.push({ line: src.slice(0, m.index).split('\n').length, text });
    }
    return hits;
}

// Fifth class, found in production: an English literal concatenated INSIDE a th:text expression
// (`th:text="'You have ' + ${n} + ' reports'"`). The tag has th:text, so the text-node scan skips it.
function scanExpressions(file) {
    const src = fs.readFileSync(file, 'utf8');
    const hits = [];
    for (const m of src.matchAll(/th:(?:text|utext|attr)="([^"]*)"/g)) {
        for (const lit of m[1].matchAll(/'([^']{3,90})'/g)) {
            const text = lit[1].replace(/\s+/g, ' ').trim();
            if (!text || ALLOWED.has(text) || !ENGLISH_LIKE.test(text)) continue;
            hits.push({ line: src.slice(0, m.index).split('\n').length, text });
        }
    }
    return hits;
}

const problems = [];
for (const file of [...TEMPLATE_DIRS.flatMap(d => walk(d, '.html')), ...TEMPLATE_FILES]) {
    scan(file, false).forEach(h => problems.push(`${file}:${h.line}  "${h.text}"`));
    scanExpressions(file).forEach(h => problems.push(`${file}:${h.line}  (in th:* expression) "${h.text}"`));
}
for (const file of [...JS_DIRS.flatMap(d => walk(d, '.js')), ...JS_FILES]) {
    scan(file, true).forEach(h => problems.push(`${file}:${h.line}  "${h.text}"`));
}

if (problems.length) {
    console.error(`Untranslated user-visible text (${problems.length}):`);
    problems.forEach(p => console.error(`  - ${p}`));
    console.error('\nRoute it through #{...} (templates) or t() (JS), or add a genuine identifier to ALLOWED.');
    process.exit(1);
}
console.log('i18n: no untranslated user-visible text in the localized surfaces');

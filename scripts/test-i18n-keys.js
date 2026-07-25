/**
 * H87 — every message key referenced by a template or a JS module must exist in the bundle.
 *
 * A missing key does not fail at render time: Thymeleaf prints `??key_ro??` and the JS helper prints the key
 * itself. Both are silent in tests that only assert status codes, and both are invisible to anyone reviewing
 * the diff — so this static check is the only thing standing between a typo and a researcher seeing
 * "workspace.pubs.pendingReview" in their workspace. It also covers markup that cannot be rendered without
 * fixture state (the report-import tables need an uploaded file).
 */
const fs = require('fs');
const path = require('path');

function walk(dir, ext, out = []) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full, ext, out);
        else if (full.endsWith(ext)) out.push(full);
    }
    return out;
}

const keys = new Set(
    fs.readFileSync('src/main/resources/messages.properties', 'utf8')
        .split('\n')
        .filter(l => l.includes('=') && !l.trim().startsWith('#'))
        .map(l => l.split('=', 1)[0].trim())
);

const problems = [];

for (const file of walk('src/main/resources/templates', '.html')) {
    const src = fs.readFileSync(file, 'utf8');
    for (const m of src.matchAll(/#\{([a-zA-Z0-9_.]+)/g)) {
        const key = m[1];
        // `#{__${expr}__}` is Thymeleaf preprocessing for a DYNAMIC key (used for plural selection),
        // not a literal key — the family it resolves to is checked by MessageBundleParityTest.
        if (key.startsWith('__')) continue;
        if (!keys.has(key)) problems.push(`${path.relative('.', file)}: ${key}`);
    }
}

for (const file of walk('frontend/src/modules', '.js')) {
    const src = fs.readFileSync(file, 'utf8');
    for (const m of src.matchAll(/tPlural\(\s*'([^']+)'/g)) {
        for (const suffix of ['.one', '.few', '.other']) {
            if (!keys.has(m[1] + suffix)) problems.push(`${path.relative('.', file)}: ${m[1]}${suffix}`);
        }
    }
    for (const m of src.matchAll(/(?<!\w)t\(\s*'([^']+)'/g)) {
        if (!keys.has(m[1])) problems.push(`${path.relative('.', file)}: ${m[1]}`);
    }
}

if (problems.length) {
    console.error('Missing message keys:');
    problems.forEach(p => console.error(`  - ${p}`));
    process.exit(1);
}
console.log(`i18n keys: all template and JS references resolve (${keys.size} keys in the bundle)`);

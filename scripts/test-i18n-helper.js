/**
 * H87 S3a — tests for the client-side i18n helper.
 *
 * The plural table here MUST match PluralRules.java. Romanian selects a third form from 20 up and that form
 * carries a particle ("20 de publicații"), so a drift between the two implementations shows up as broken
 * grammar in exactly the cases nobody clicks through during review.
 */
const fs = require('fs');
const assert = require('assert');
const Module = require('module');

function loadHelper(locale) {
    globalThis.window = { appLocale: locale, appI18n: {
        'workspace.pubs.count.one': '{0} publicație',
        'workspace.pubs.count.few': '{0} publicații',
        'workspace.pubs.count.other': '{0} de publicații',
        'workspace.greeting': 'Salut, {0}! Ai {1} mesaje.',
        'workspace.partial.one': '{0} element',
    } };
    const src = fs.readFileSync('frontend/src/modules/shared/i18n.js', 'utf8')
        .replace(/export function/g, 'function')
        .replace(/export const/g, 'const')
        + '\nmodule.exports = { t, tPlural, pluralCategory, currentLocale, formatNumber };';
    const m = new Module('i18n-test');
    m._compile(src, 'i18n.js');
    return m.exports;
}

let failures = 0;
function check(name, fn) {
    try {
        fn();
        console.log(`  ok  ${name}`);
    } catch (err) {
        failures++;
        console.error(`  FAIL ${name}: ${err.message}`);
    }
}

console.log('i18n helper');

check('romanian plural categories match the Java rules', () => {
    const { pluralCategory } = loadHelper('ro');
    const expected = { 0: 'few', 1: 'one', 2: 'few', 19: 'few', 20: 'other', 21: 'other', 100: 'other', 101: 'few', 119: 'few', 120: 'other' };
    for (const [n, want] of Object.entries(expected)) {
        assert.strictEqual(pluralCategory(Number(n)), want, `n=${n} expected ${want}`);
    }
});

check('english is two-form', () => {
    const { pluralCategory } = loadHelper('en');
    assert.strictEqual(pluralCategory(1), 'one');
    assert.strictEqual(pluralCategory(0), 'other');
    assert.strictEqual(pluralCategory(20), 'other');
});

check('tPlural picks the particle form from twenty up', () => {
    const { tPlural } = loadHelper('ro');
    assert.strictEqual(tPlural('workspace.pubs.count', 1), '1 publicație');
    assert.strictEqual(tPlural('workspace.pubs.count', 5), '5 publicații');
    assert.strictEqual(tPlural('workspace.pubs.count', 20), '20 de publicații');
    assert.strictEqual(tPlural('workspace.pubs.count', 0), '0 publicații');
});

check('t substitutes positional arguments', () => {
    const { t } = loadHelper('ro');
    assert.strictEqual(t('workspace.greeting', 'Florin', 3), 'Salut, Florin! Ai 3 mesaje.');
});

check('a missing key returns the key rather than a blank', () => {
    const { t } = loadHelper('ro');
    assert.strictEqual(t('workspace.nope'), 'workspace.nope');
});

check('an incomplete plural family degrades to a present form instead of rendering a key', () => {
    const { tPlural } = loadHelper('ro');
    // only '.one' exists; n=5 would select '.few'
    assert.strictEqual(tPlural('workspace.partial', 5), '5 element');
});

check('formatNumber follows the active locale', () => {
    const { formatNumber } = loadHelper('ro');
    assert.strictEqual(formatNumber(1234).replace(/ | /g, ' '), '1.234');
});

if (failures > 0) {
    console.error(`\n${failures} i18n helper test(s) failed`);
    process.exit(1);
}
console.log('i18n helper: all tests passed');

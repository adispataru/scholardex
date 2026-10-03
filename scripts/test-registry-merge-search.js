/**
 * H142 — tests for the registry review's merge search (frontend/src/modules/shared/registryMergeSearch.js).
 *
 * The normalisation MUST match ArtisticEventRankSupport.normalize: the experts type names without diacritics
 * ("garana", "timisoara"), and the registry's own keys are built the same way.
 */
const fs = require('fs');
const assert = require('assert');
const Module = require('module');

function loadModule() {
    const src = fs.readFileSync('frontend/src/modules/shared/registryMergeSearch.js', 'utf8')
        .replace(/export function/g, 'function')
        + '\nmodule.exports = { normalizeForSearch, rankMatches };';
    const m = new Module('registry-merge-search-test');
    m._compile(src, 'registryMergeSearch.js');
    return m.exports;
}

const { normalizeForSearch, rankMatches } = loadModule();

function entry(id, name, aliases = []) {
    return { id, name, label: `${name} (internațional)`, group: 'Muzică', aliases,
        nameKey: normalizeForSearch(name), aliasKeys: aliases.map(normalizeForSearch) };
}

const entries = [
    entry('1', 'Gărâna Jazz Festival', ['Festivalul Internațional de Jazz de la Gărâna']),
    entry('2', 'Festivalul „Remus Georgescu” (Timișoara)', ['FCI Remus Georgescu, 22 octombrie, Biserica Piarista Timisoara']),
    entry('3', 'Festivalul „George Enescu” (România)'),
    entry('4', 'Festivalul Meridian (București)', ['Festivalul Internațional Meridian']),
    entry('5', 'Opera Națională Română din Timișoara', ['Opera Română Timișoara']),
    entry('6', 'Zilele Muzicii Noi (Chișinău)'),
];

let failures = 0;
function check(name, fn) {
    try {
        fn();
        console.log(`ok   ${name}`);
    } catch (err) {
        failures += 1;
        console.error(`FAIL ${name}\n     ${err.message}`);
    }
}

const ids = (query) => rankMatches(entries, query).map(m => m.entry.id);

check('normalisation drops diacritics, case and punctuation, like ArtisticEventRankSupport.normalize', () => {
    assert.strictEqual(normalizeForSearch('Festivalul „Remus Georgescu” (Timișoara)'), 'festivalul remus georgescu timisoara');
    assert.strictEqual(normalizeForSearch('Iaşi  —  ŢARA'), 'iasi tara');
    assert.strictEqual(normalizeForSearch(null), '');
});

check('a name typed without diacritics is found', () => {
    assert.deepStrictEqual(ids('garana'), ['1']);
    assert.deepStrictEqual(ids('enescu'), ['3']);
});

check('every word typed must match, in any order', () => {
    assert.deepStrictEqual(ids('timisoara remus'), ['2']);
    assert.deepStrictEqual(ids('remus bucuresti'), []);
});

check('another spelling finds the entry, and says which', () => {
    const matches = rankMatches(entries, 'piarista');
    assert.deepStrictEqual(matches.map(m => m.entry.id), ['2']);
    assert.strictEqual(matches[0].alias, 'FCI Remus Georgescu, 22 octombrie, Biserica Piarista Timisoara');
});

check('a match on the name ranks before a match through a spelling, a name start first', () => {
    // «timisoara»: two names hold it; «Opera Română Timișoara» is also a spelling of entry 5
    assert.deepStrictEqual(ids('timisoara'), ['2', '5']);
    assert.deepStrictEqual(ids('opera'), ['5']);
    assert.deepStrictEqual(ids('festivalul'), ['3', '4', '2', '1'],
        'names starting with it, by their letters (quotes ignored); then Gărâna, through its other spelling');
});

check('nothing typed, nothing listed; the count is capped', () => {
    assert.deepStrictEqual(ids('   '), []);
    const many = Array.from({ length: 40 }, (_, i) => entry(String(i), `Festival ${i}`));
    assert.strictEqual(rankMatches(many, 'festival').length, 15);
});

if (failures > 0) {
    console.error(`\n${failures} check(s) failed`);
    process.exit(1);
}
console.log('\nregistry merge search: all checks passed');

// H73 S2.2 dry-run: would OpenAlex precedence on shared-DOI pubs be an improvement?
// Compares the Scopus vs OpenAlex SOURCE facts by normalized DOI (un-enriched), read-only.
function ndoi(d) {
  if (!d) return null;
  d = String(d).toLowerCase().trim();
  d = d.replace(/^https?:\/\/(dx\.)?doi\.org\//, '');
  return d || null;
}

var oa = {};
db.getCollection('openalex.publication_facts').find({ doi: { $ne: null } },
  { doi: 1, citedByCount: 1, authorCount: 1, authorships: 1 }).forEach(d => {
  var k = ndoi(d.doi); if (!k) return;
  var corr = (d.authorships || []).some(a => a && a.corresponding === true);
  var orcid = (d.authorships || []).some(a => a && a.orcid);
  oa[k] = { c: d.citedByCount || 0, a: d.authorCount || 0, corr: corr, orcid: orcid };
});
print('openalex DOIs indexed: ' + Object.keys(oa).length);

var shared = 0, oaCiteHigher = 0, scCiteHigher = 0, citeDeltaSum = 0,
    oaMoreAuthors = 0, scMoreAuthors = 0, eqAuthors = 0,
    scHasCorr = 0, oaAddsCorr = 0, oaAddsOrcid = 0;
db.getCollection('scopus.publication_facts').find({ doi: { $ne: null } },
  { doi: 1, citedByCount: 1, authorCount: 1, correspondingAuthors: 1 }).forEach(d => {
  var k = ndoi(d.doi); if (!k) return;
  var o = oa[k]; if (!o) return;
  shared++;
  var sc = d.citedByCount || 0;
  if (o.c > sc) { oaCiteHigher++; citeDeltaSum += (o.c - sc); }
  else if (sc > o.c) scCiteHigher++;
  var sa = d.authorCount || 0;
  if (o.a > sa) oaMoreAuthors++; else if (o.a < sa) scMoreAuthors++; else eqAuthors++;
  var scCorr = d.correspondingAuthors && d.correspondingAuthors.length > 0;
  if (scCorr) scHasCorr++;
  if (o.corr && !scCorr) oaAddsCorr++;
  if (o.orcid) oaAddsOrcid++;
});

print('=== S2.2 shared-DOI pubs (in BOTH Scopus + OpenAlex): ' + shared + ' ===');
print('CITATIONS:');
print('  OpenAlex count > Scopus: ' + oaCiteHigher + ' (' + (100*oaCiteHigher/shared).toFixed(1) + '%), total extra citations OpenAlex sees: ' + citeDeltaSum);
print('  Scopus count > OpenAlex: ' + scCiteHigher + ' (' + (100*scCiteHigher/shared).toFixed(1) + '%)');
print('AUTHOR LIST completeness:');
print('  OpenAlex more authors: ' + oaMoreAuthors + ' (' + (100*oaMoreAuthors/shared).toFixed(1) + '%)');
print('  Scopus more authors:   ' + scMoreAuthors + ' (' + (100*scMoreAuthors/shared).toFixed(1) + '%)');
print('  equal:                 ' + eqAuthors + ' (' + (100*eqAuthors/shared).toFixed(1) + '%)');
print('CORRESPONDING AUTHOR / ORCID (what OpenAlex adds that Scopus lacks):');
print('  Scopus already has corresponding: ' + scHasCorr + ' (' + (100*scHasCorr/shared).toFixed(1) + '%)');
print('  OpenAlex adds corresponding where Scopus has none: ' + oaAddsCorr + ' (' + (100*oaAddsCorr/shared).toFixed(1) + '%)');
print('  OpenAlex carries >=1 author ORCID: ' + oaAddsOrcid + ' (' + (100*oaAddsOrcid/shared).toFixed(1) + '%)');

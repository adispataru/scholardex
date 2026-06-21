// H73 S2.3 dry-run: full author inversion — merge population + false-merge risk. Read-only.
var coll = db.getCollection('scholardex.author_facts');
var total = coll.countDocuments({});
var withScopus = coll.countDocuments({ 'scopusAuthorIds.0': { $exists: true } });
var withOpenAlex = coll.countDocuments({ 'openAlexAuthorIds.0': { $exists: true } });
var withBoth = coll.countDocuments({ 'scopusAuthorIds.0': { $exists: true }, 'openAlexAuthorIds.0': { $exists: true } });
var withOrcid = coll.countDocuments({ 'orcidIds.0': { $exists: true } });
var scopusOnly = coll.countDocuments({ 'scopusAuthorIds.0': { $exists: true }, 'openAlexAuthorIds.0': { $exists: false } });
var scopusWithOrcid = coll.countDocuments({ 'scopusAuthorIds.0': { $exists: true }, 'orcidIds.0': { $exists: true } });
var scopusOnlyNoOrcid = coll.countDocuments({ 'scopusAuthorIds.0': { $exists: true }, 'openAlexAuthorIds.0': { $exists: false }, 'orcidIds.0': { $exists: false } });

print('=== S2.3 author population (' + total + ' canonical authors) ===');
print('  Scopus-origin (scopusAuthorIds):     ' + withScopus);
print('  OpenAlex-origin (openAlexAuthorIds): ' + withOpenAlex);
print('  CROSS-SOURCE MERGED (both ids):      ' + withBoth + '  <- already unified (ORCID bridge)');
print('  with >=1 ORCID:                      ' + withOrcid);
print('  Scopus-origin WITH orcid (bridgeable): ' + scopusWithOrcid);
print('  Scopus-ONLY, NO orcid (cannot id-merge safely): ' + scopusOnlyNoOrcid);

// Name-collision (homonym) risk for any name-based matching: how many normalized names are shared by >=2 DISTINCT
// canonical authors, and the worst clusters. High numbers => name+affiliation matching is dangerous.
var dupNames = coll.aggregate([
  { $match: { nameNormalized: { $ne: null, $ne: '' } } },
  { $group: { _id: '$nameNormalized', n: { $sum: 1 } } },
  { $match: { n: { $gt: 1 } } },
  { $group: { _id: null, sharedNames: { $sum: 1 }, authorsInCollisions: { $sum: '$n' }, maxCluster: { $max: '$n' } } }
], { allowDiskUse: true }).toArray();
if (dupNames.length) {
  var d = dupNames[0];
  print('NAME-COLLISION (homonym) risk:');
  print('  normalized names shared by >=2 authors: ' + d.sharedNames);
  print('  authors sitting in a name collision:    ' + d.authorsInCollisions + ' (' + (100*d.authorsInCollisions/total).toFixed(1) + '% of all authors)');
  print('  largest single-name cluster:            ' + d.maxCluster + ' authors');
}
print('--- top homonym clusters ---');
coll.aggregate([
  { $match: { nameNormalized: { $ne: null, $ne: '' } } },
  { $group: { _id: '$nameNormalized', n: { $sum: 1 } } },
  { $sort: { n: -1 } }, { $limit: 5 }
], { allowDiskUse: true }).forEach(d => print('  "' + d._id + '": ' + d.n));

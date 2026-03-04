const fs = require('fs');

const errors = [];

function readFile(path) {
  return fs.readFileSync(path, 'utf8');
}

function extractMethodSlice(content, signature, nextSignatureHints = []) {
  const start = content.indexOf(signature);
  if (start < 0) {
    return null;
  }
  let end = content.length;
  for (const hint of nextSignatureHints) {
    const idx = content.indexOf(hint, start + signature.length);
    if (idx > -1 && idx < end) {
      end = idx;
    }
  }
  return content.slice(start, end);
}

function assertContains(haystack, needle, message) {
  if (!haystack.includes(needle)) {
    errors.push(message);
  }
}

function assertNotContains(haystack, needle, message) {
  if (haystack.includes(needle)) {
    errors.push(message);
  }
}

function assertNotRegex(haystack, regex, message) {
  if (regex.test(haystack)) {
    errors.push(message);
  }
}

const userReportFacadePath =
  'src/main/java/ro/uvt/pokedex/core/service/application/UserReportFacade.java';
const groupCnfisFacadePath =
  'src/main/java/ro/uvt/pokedex/core/service/application/GroupCnfisExportFacade.java';
const cacheServicePath =
  'src/main/java/ro/uvt/pokedex/core/service/CacheService.java';
const userPublicationFacadePath =
  'src/main/java/ro/uvt/pokedex/core/service/application/UserPublicationFacade.java';
const adminScopusFacadePath =
  'src/main/java/ro/uvt/pokedex/core/service/application/AdminScopusFacade.java';
const yearParsingGuardFiles = [
  'src/main/java/ro/uvt/pokedex/core/service/reporting/AbstractForumScoringService.java',
  'src/main/java/ro/uvt/pokedex/core/service/reporting/AbstractWoSForumScoringService.java',
  'src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService2025.java',
  'src/main/java/ro/uvt/pokedex/core/service/application/GroupReportFacade.java',
  'src/main/java/ro/uvt/pokedex/core/service/application/AdminInstitutionReportFacade.java',
  'src/main/java/ro/uvt/pokedex/core/service/application/UserReportFacade.java',
  'src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISReportExportService.java',
  'src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java',
  'src/main/java/ro/uvt/pokedex/core/view/AdminViewController.java'
];

const userReportContent = readFile(userReportFacadePath);
const groupCnfisContent = readFile(groupCnfisFacadePath);
const cacheServiceContent = readFile(cacheServicePath);
const userPublicationFacadeContent = readFile(userPublicationFacadePath);
const adminScopusFacadeContent = readFile(adminScopusFacadePath);
const rawYearPattern = /(substring\(\s*0\s*,\s*4\s*\))|(split\(\s*"-"\s*\)\s*\[\s*0\s*\])/;

const userCnfisMethod = extractMethodSlice(
  userReportContent,
  'public UserWorkbookExportResult buildUserCnfisWorkbookExport(',
  ['public UserWorkbookExportResult buildLegacyUserCnfisWorkbookExport(']
);
if (userCnfisMethod == null) {
  errors.push(`${userReportFacadePath}: missing buildUserCnfisWorkbookExport method.`);
} else {
  assertContains(
    userCnfisMethod,
    'PersistenceYearSupport.extractYear(',
    `${userReportFacadePath}: buildUserCnfisWorkbookExport must use PersistenceYearSupport.extractYear for year filtering.`
  );
  assertNotContains(
    userCnfisMethod,
    'substring(0, 4)',
    `${userReportFacadePath}: buildUserCnfisWorkbookExport must not use substring(0, 4) year parsing.`
  );
}

const groupFilterMethod = extractMethodSlice(
  groupCnfisContent,
  'private List<Publication> filterPublicationsByYear(',
  ['private List<CNFISReport2025> generateReports(']
);
if (groupFilterMethod == null) {
  errors.push(`${groupCnfisFacadePath}: missing filterPublicationsByYear method.`);
} else {
  assertContains(
    groupFilterMethod,
    'PersistenceYearSupport.extractYear(',
    `${groupCnfisFacadePath}: filterPublicationsByYear must use PersistenceYearSupport.extractYear.`
  );
  assertNotContains(
    groupFilterMethod,
    'substring(0, 4)',
    `${groupCnfisFacadePath}: filterPublicationsByYear must not use substring(0, 4) year parsing.`
  );
}

assertNotContains(
  cacheServiceContent,
  'rankingCacheByIssn.put(r.getId(), List.of(r));',
  `${cacheServicePath}: ISSN cache must not be prefilled with ranking id keys.`
);
assertContains(
  cacheServiceContent,
  'cacheRankingByIssnKey(r.getIssn(), r);',
  `${cacheServicePath}: cacheRankings must prefill ISSN key path from ranking.issn.`
);
assertContains(
  cacheServiceContent,
  'cacheRankingByIssnKey(r.getEIssn(), r);',
  `${cacheServicePath}: cacheRankings must prefill ISSN key path from ranking.eIssn.`
);
assertContains(
  cacheServiceContent,
  'rankingRepository.findAllByIssn(key)',
  `${cacheServicePath}: getCachedRankingsByIssn must query findAllByIssn on cache miss.`
);
assertContains(
  cacheServiceContent,
  'rankingRepository.findAllByeIssn(key)',
  `${cacheServicePath}: getCachedRankingsByIssn must query findAllByeIssn on cache miss.`
);

assertContains(
  userPublicationFacadeContent,
  'putIfAbsent(publication.getId(), publication)',
  `${userPublicationFacadePath}: buildUserPublicationsView must dedupe by publication id.`
);
assertContains(
  userPublicationFacadeContent,
  'findById(publicationId)',
  `${userPublicationFacadePath}: edit/update flow must use canonical findById(publicationId).`
);
assertNotContains(
  userPublicationFacadeContent,
  'findByEid(',
  `${userPublicationFacadePath}: user edit/update flow must not use findByEid.`
);
assertContains(
  adminScopusFacadeContent,
  'findByTitleContainsOrderByCoverDateDesc',
  `${adminScopusFacadePath}: publication search must use ordered repository method.`
);

for (const filePath of yearParsingGuardFiles) {
  const content = readFile(filePath);
  assertNotRegex(
    content,
    rawYearPattern,
    `${filePath}: raw year parsing via substring/split is forbidden; use PersistenceYearSupport.`
  );
}

if (errors.length > 0) {
  console.error('H06 persistence verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H06 persistence verification passed.');

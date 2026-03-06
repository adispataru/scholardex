const fs = require('fs');
const { execFileSync } = require('child_process');

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
const userPublicationFacadePath =
  'src/main/java/ro/uvt/pokedex/core/service/application/UserPublicationFacade.java';
const adminScopusFacadePath =
  'src/main/java/ro/uvt/pokedex/core/service/application/AdminScopusFacade.java';
const rankingRepositoryPath =
  'src/main/java/ro/uvt/pokedex/core/repository/reporting/RankingRepository.java';
const forumExportFacadePath =
  'src/main/java/ro/uvt/pokedex/core/service/application/ForumExportFacade.java';
const scopusPublicationUpdateModelPath =
  'src/main/java/ro/uvt/pokedex/core/model/tasks/ScopusPublicationUpdate.java';
const scopusCitationsUpdateModelPath =
  'src/main/java/ro/uvt/pokedex/core/model/tasks/ScopusCitationsUpdate.java';
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
const userPublicationFacadeContent = readFile(userPublicationFacadePath);
const adminScopusFacadeContent = readFile(adminScopusFacadePath);
const rankingRepositoryContent = readFile(rankingRepositoryPath);
const forumExportFacadeContent = readFile(forumExportFacadePath);
const scopusPublicationUpdateModelContent = readFile(scopusPublicationUpdateModelPath);
const scopusCitationsUpdateModelContent = readFile(scopusCitationsUpdateModelPath);
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

assertContains(
  rankingRepositoryContent,
  '@Query("{ \'eIssn\': ?0 }")',
  `${rankingRepositoryPath}: findAllByEIssn must be query-annotated to avoid Spring derived-property parsing ambiguity.`
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
const findForEditMethod = extractMethodSlice(
  userPublicationFacadeContent,
  'public Optional<Publication> findPublicationForEdit(',
  ['public void updatePublicationMetadata(']
);
if (findForEditMethod == null) {
  errors.push(`${userPublicationFacadePath}: missing findPublicationForEdit method.`);
} else {
  assertContains(
    findForEditMethod,
    'findById(publicationId)',
    `${userPublicationFacadePath}: findPublicationForEdit must use canonical findById(publicationId).`
  );
  assertNotContains(
    findForEditMethod,
    'findByEid(',
    `${userPublicationFacadePath}: findPublicationForEdit must not use findByEid compatibility fallback.`
  );
}

const updateMetadataMethod = extractMethodSlice(
  userPublicationFacadeContent,
  'public void updatePublicationMetadata(',
  ['private int computeHIndex(']
);
if (updateMetadataMethod == null) {
  errors.push(`${userPublicationFacadePath}: missing updatePublicationMetadata method.`);
} else {
  assertContains(
    updateMetadataMethod,
    'findById(publicationId)',
    `${userPublicationFacadePath}: updatePublicationMetadata must use canonical findById(publicationId).`
  );
  assertNotContains(
    updateMetadataMethod,
    'findByEid(',
    `${userPublicationFacadePath}: updatePublicationMetadata must not use findByEid compatibility fallback.`
  );
}

const citationsViewMethod = extractMethodSlice(
  userPublicationFacadeContent,
  'public Optional<UserPublicationCitationsViewModel> buildCitationsView(',
  ['// Uses canonical Mongo `id`; EID-based lookup belongs to importer/scopus integration paths.']
);
if (citationsViewMethod == null) {
  errors.push(`${userPublicationFacadePath}: missing buildCitationsView method.`);
} else {
  assertContains(
    citationsViewMethod,
    'findById(publicationId)',
    `${userPublicationFacadePath}: buildCitationsView must attempt canonical id lookup first.`
  );
  assertContains(
    citationsViewMethod,
    '.or(() -> scopusPublicationRepository.findByEid(publicationId))',
    `${userPublicationFacadePath}: buildCitationsView must keep explicit id/eid compatibility fallback.`
  );
}
assertContains(
  adminScopusFacadeContent,
  'findByTitleContainingIgnoreCaseOrderByCoverDateDesc',
  `${adminScopusFacadePath}: publication search must use case-insensitive ordered repository method.`
);
assertNotContains(
  adminScopusFacadeContent,
  'findByTitleContainsOrderByCoverDateDesc',
  `${adminScopusFacadePath}: publication search must not use case-sensitive title contains query path.`
);
assertNotContains(
  forumExportFacadeContent,
  '"null-"',
  `${forumExportFacadePath}: export dedupe must not rely on sentinel literal checks.`
);
assertContains(
  scopusPublicationUpdateModelContent,
  '@Document(collection = "scholardex.tasks.scopusPublicationUpdate")',
  `${scopusPublicationUpdateModelPath}: task collection namespace must be scholardex.tasks.*`
);
assertContains(
  scopusCitationsUpdateModelContent,
  '@Document(collection = "scholardex.tasks.scopusCitationsUpdate")',
  `${scopusCitationsUpdateModelPath}: task collection namespace must be scholardex.tasks.*`
);
assertNotContains(
  scopusPublicationUpdateModelContent,
  'schodardex.tasks.',
  `${scopusPublicationUpdateModelPath}: schodardex namespace must be retired.`
);
assertNotContains(
  scopusCitationsUpdateModelContent,
  'schodardex.tasks.',
  `${scopusCitationsUpdateModelPath}: schodardex namespace must be retired.`
);

for (const filePath of yearParsingGuardFiles) {
  const content = readFile(filePath);
  assertNotRegex(
    content,
    rawYearPattern,
    `${filePath}: raw year parsing via substring/split is forbidden; use PersistenceYearSupport.`
  );
}

function runRg(pattern, paths) {
  try {
    const output = execFileSync(
      'rg',
      ['-n', pattern, ...paths],
      { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
    );
    return output
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean);
  } catch (error) {
    if (typeof error.status === 'number' && error.status === 1) {
      return [];
    }
    throw error;
  }
}

const typoMatches = runRg('findAllByeIssn\\(', ['src/main/java', 'src/test/java']);
if (typoMatches.length > 0) {
  typoMatches.forEach((line) => {
    errors.push(`${line}: Use findAllByEIssn instead; findAllByeIssn is retired.`);
  });
}

if (errors.length > 0) {
  console.error('H06 persistence verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H06 persistence verification passed.');

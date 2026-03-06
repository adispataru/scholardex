const { execFileSync } = require('child_process');
const fs = require('fs');

const errors = [];

function runRg(pattern, paths) {
  const runners = [
    { cmd: 'rg', args: ['-n', pattern, ...paths] },
    { cmd: 'grep', args: ['-R', '-n', '-E', pattern, ...paths] }
  ];

  let missingToolCount = 0;
  for (const runner of runners) {
    try {
      const output = execFileSync(
        runner.cmd,
        runner.args,
        { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
      );
      return output
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean);
    } catch (error) {
      if (error.code === 'ENOENT') {
        missingToolCount += 1;
        continue;
      }
      if (typeof error.status === 'number' && error.status === 1) {
        return [];
      }
      throw error;
    }
  }

  if (missingToolCount === runners.length) {
    errors.push(
      'H07 guardrail verification requires either `rg` (ripgrep) or `grep` to be available in PATH.'
    );
    process.exit(1);
  }

  return [];
}

function pathFromRgLine(line) {
  const firstColon = line.indexOf(':');
  return firstColon > 0 ? line.slice(0, firstColon) : line;
}

const controllerRoots = [
  'src/main/java/ro/uvt/pokedex/core/view',
  'src/main/java/ro/uvt/pokedex/core/controller'
];

const allowlistedMutatingGetFiles = new Set([
]);

const mutatingGetMatches = runRg(
  '@GetMapping\\(".*(delete|duplicate)/',
  controllerRoots
);
const mutatingGetFiles = new Set(mutatingGetMatches.map(pathFromRgLine));

for (const file of mutatingGetFiles) {
  if (!allowlistedMutatingGetFiles.has(file)) {
    errors.push(
      `${file}: new mutating GET route detected (delete/duplicate). Use POST/PUT/PATCH/DELETE.`
    );
  }
}

const allowlistedPrintStackTraceFiles = new Set([]);

const printStackTraceMatches = runRg('printStackTrace\\(', controllerRoots);
const printStackTraceFiles = new Set(printStackTraceMatches.map(pathFromRgLine));
for (const file of printStackTraceFiles) {
  if (!allowlistedPrintStackTraceFiles.has(file)) {
    errors.push(
      `${file}: new printStackTrace usage detected in transport layer; use structured logging.`
    );
  }
}

const allowlistedYearParseFiles = new Set([
]);

const riskyYearParseMatches = runRg(
  'Integer\\.parseInt\\((startYear|endYear)\\)',
  controllerRoots
);
const riskyYearParseFiles = new Set(riskyYearParseMatches.map(pathFromRgLine));
for (const file of riskyYearParseFiles) {
  if (!allowlistedYearParseFiles.has(file)) {
    errors.push(
      `${file}: new unsafe start/end year parsing detected; add validated parsing + deterministic 400 behavior.`
    );
  }
}

function emitAllowlistShrinkHint(currentFiles, allowlist, label) {
  const missingAllowlisted = [...allowlist].filter((file) => !currentFiles.has(file));
  if (missingAllowlisted.length > 0) {
    console.log(
      `H07 guardrail note: ${missingAllowlisted.length} ${label} allowlisted file(s) no longer match. Consider shrinking allowlist.`
    );
  }
}

emitAllowlistShrinkHint(mutatingGetFiles, allowlistedMutatingGetFiles, 'mutating-GET');
emitAllowlistShrinkHint(printStackTraceFiles, allowlistedPrintStackTraceFiles, 'printStackTrace');
emitAllowlistShrinkHint(riskyYearParseFiles, allowlistedYearParseFiles, 'year-parse');

const userControllerPath = 'src/main/java/ro/uvt/pokedex/core/controller/UserController.java';
const adminResearcherControllerPath = 'src/main/java/ro/uvt/pokedex/core/controller/AdminResearcherController.java';
const requestBodyWithoutValidMatches = runRg(
  '@RequestBody',
  [userControllerPath, adminResearcherControllerPath]
).filter((line) => !line.includes('@Valid'));

if (requestBodyWithoutValidMatches.length > 0) {
  requestBodyWithoutValidMatches.forEach((line) => {
    errors.push(`${line}: @RequestBody in targeted admin API controllers must use @Valid.`);
  });
}

const exportControllerPath = 'src/main/java/ro/uvt/pokedex/core/controller/ExportController.java';
const exportGenericCatch = runRg('catch \\(Exception', [exportControllerPath]);
if (exportGenericCatch.length > 0) {
  errors.push(
    `${exportControllerPath}: generic exception catch is forbidden in export endpoint logic; rely on centralized mapping.`
  );
}

const loginTemplatePath = 'src/main/resources/templates/login.html';
const loginTemplate = fs.readFileSync(loginTemplatePath, 'utf8');
if (!/name="username"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login form must keep name=\"username\" for Spring form-login compatibility.`);
}
if (!/autocomplete="username"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login username/email input must declare autocomplete=\"username\".`);
}
if (!/name="password"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login form must keep name=\"password\" for Spring form-login compatibility.`);
}
if (!/autocomplete="current-password"/.test(loginTemplate)) {
  errors.push(`${loginTemplatePath}: login password input must declare autocomplete=\"current-password\".`);
}

const securityConfigPath = 'src/main/java/ro/uvt/pokedex/core/config/WebSecurityConfig.java';
const securityConfig = fs.readFileSync(securityConfigPath, 'utf8');
if (/csrf\(AbstractHttpConfigurer::disable\)/.test(securityConfig)) {
  errors.push(`${securityConfigPath}: global CSRF disable is forbidden in H07-R4.`);
}
const csrfIgnoresApiOnlyAntMatcher = /ignoringRequestMatchers\(new AntPathRequestMatcher\("\/api\/\*\*"\)\)/.test(securityConfig);
const csrfIgnoresApiOnlyPathPattern = /ignoringRequestMatchers\(PathPatternRequestMatcher\.pathPattern\("\/api\/\*\*"\)\)/.test(securityConfig);
if (!csrfIgnoresApiOnlyAntMatcher && !csrfIgnoresApiOnlyPathPattern) {
  errors.push(`${securityConfigPath}: CSRF config must explicitly ignore /api/** only.`);
}

const adminGroupControllerPath = 'src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java';
const adminGroupController = fs.readFileSync(adminGroupControllerPath, 'utf8');
if (!/h07\.groups\.import\.max-bytes/.test(adminGroupController)
    || !/h07\.groups\.import\.allowed-content-types/.test(adminGroupController)) {
  errors.push(`${adminGroupControllerPath}: import boundary validation properties must be wired.`);
}
if (!/hasCsvExtension\(/.test(adminGroupController) || !/isAllowedContentType\(/.test(adminGroupController)) {
  errors.push(`${adminGroupControllerPath}: import endpoint must enforce extension and content-type validation.`);
}

const groupServicePath = 'src/main/java/ro/uvt/pokedex/core/service/importing/GroupService.java';
const groupService = fs.readFileSync(groupServicePath, 'utf8');
if (!/h07\.groups\.import\.required-column-count/.test(groupService)) {
  errors.push(`${groupServicePath}: CSV schema required-column-count property must be wired.`);
}
if (!/parseAndValidateCsv\(/.test(groupService) || !/SIMPLE_EMAIL_PATTERN/.test(groupService)) {
  errors.push(`${groupServicePath}: strict CSV schema/row validation must be implemented.`);
}

const userServicePath = 'src/main/java/ro/uvt/pokedex/core/service/UserService.java';
const userService = fs.readFileSync(userServicePath, 'utf8');
if (/public Optional<User> createUser\(User user\)[\s\S]*?return null;/.test(userService)) {
  errors.push(`${userServicePath}: createUser(User) must not return null; use Optional.empty().`);
}
if (/public Optional<User> createUser\(String email, String password, List<String> roles\)[\s\S]*?return null;/.test(userService)) {
  errors.push(`${userServicePath}: createUser(email,password,roles) must not return null; use Optional.empty().`);
}

const publicationWizardFacadePath = 'src/main/java/ro/uvt/pokedex/core/service/application/PublicationWizardFacade.java';
const publicationWizardFacade = fs.readFileSync(publicationWizardFacadePath, 'utf8');
if (/public Optional<String> resolveForumId\([\s\S]*?return null;/.test(publicationWizardFacade)) {
  errors.push(`${publicationWizardFacadePath}: resolveForumId must not return null; use Optional.empty().`);
}

const globalControllerAdvicePath = 'src/main/java/ro/uvt/pokedex/core/config/GlobalControllerAdvice.java';
const globalControllerAdvice = fs.readFileSync(globalControllerAdvicePath, 'utf8');
if (/@ModelAttribute\("currentUser"\)[\s\S]*?return null;/.test(globalControllerAdvice)) {
  errors.push(`${globalControllerAdvicePath}: currentUser model attribute must not return null.`);
}

const userControllerApiPath = 'src/main/java/ro/uvt/pokedex/core/controller/UserController.java';
const userController = fs.readFileSync(userControllerApiPath, 'utf8');
if (!/ResponseEntity\.status\(HttpStatus\.CONFLICT\)/.test(userController)) {
  errors.push(`${userControllerApiPath}: duplicate user create path must map to HTTP 409.`);
}

if (errors.length > 0) {
  console.error('H07 guardrail verification failed:');
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}

console.log('H07 guardrail verification passed.');

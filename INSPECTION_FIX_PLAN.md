# IntelliJ Inspection Fix Plan

> Generated from 108 inspection report files. Total actionable issues across all categories: ~1,200+
> (Spell-check, JS/HTML/CSS, and Markdown noise excluded from this plan — those are low-value and numerous.)

---

## Priority 1 — Security & Breaking Changes

These should be fixed before anything else — they carry real runtime risk or will break when dependencies are upgraded.

### 1.1 Vulnerable Dependencies (`VulnerableLibrariesLocal` — 6 CVEs)

**File:** `build.gradle`, `package.json`

| Dependency | Current | CVEs | Severity |
|---|---|---|---|
| `org.jsoup:jsoup` | 1.13.1 | CVE-2021-37714 (Infinite Loop), CVE-2022-36033 (XSS) | 7.5 / 6.1 |
| `org.apache.poi:poi` | 5.2.2 | Transitive: commons-io CVE-2024-47554 (Resource Exhaustion) | 4.3 |
| `org.apache.poi:poi-ooxml` | 5.2.2 | CVE-2025-31672 + commons-compress CVE-2024-25710 (Infinite Loop), CVE-2024-26308 | 8.1 / 6.5 / 5.5 |
| `com.opencsv:opencsv` | 5.8 | Transitive: commons-beanutils CVE-2025-48734 | 8.8 |
| `npm:bootstrap` | 4.6.2 | CVE-2024-6531 (XSS) | 6.4 |

**Fix:** Bump each to a patched version in `build.gradle` / `package.json`.

```groovy
// build.gradle
'org.jsoup:jsoup:1.17.2'          // or latest
'org.apache.poi:poi:5.3.0'
'org.apache.poi:poi-ooxml:5.3.0'
'com.opencsv:opencsv:5.9'
```

```json
// package.json
"bootstrap": "^5.3.3"
```

> Note: Bootstrap 5.x is a major version bump — verify Thymeleaf templates still render correctly after upgrading.

---

### 1.2 APIs Marked for Removal (`MarkedForRemoval` — 48 occurrences)

The most impactful cluster is `MongoTemplate.ensureIndex()` which is gone in Spring Data MongoDB 4.5+. There are also two Spring Security classes.

**`ensureIndex` → `indexOps().ensureIndex()`** — affects:
- `CitationUniquenessMigrationService.java:77`
- `ScopusCanonicalIndexMaintenanceService.java:584`
- `WosIndexMaintenanceService.java:216`
- `ScopusCitationRepositoryIntegrationTest.java:86`
- `ScopusCanonicalIndexMaintenanceServiceTest.java` (multiple lines)

**Fix pattern:**
```java
// Before
mongoTemplate.ensureIndex(indexDef, CollectionName.class);

// After
mongoTemplate.indexOps(CollectionName.class).ensureIndex(indexDef);
```

**`DelegatingAuthenticationEntryPoint`** — affects `WebSecurityConfig.java:121-122`

**Fix:** Replace with a programmatic `if/else` entry point selector, or use the Spring Security 6.x `exceptionHandling(ex -> ex.authenticationEntryPoint(...))` DSL pattern.

---

### 1.3 HTTP Links (`HttpUrlsUsage` — 2 occurrences)

- `CoreApplication.java:31` — hardcoded `http://` URL
- `WoSExtractor.java:20` — hardcoded `http://` URL

**Fix:** Replace both with `https://` equivalents.

---

### 1.4 Unknown HTTP Headers (`UastIncorrectHttpHeaderInspection` — 2 occurrences)

- `ScopusService.java:47` and `:97` — unknown header names being set on HTTP requests

**Fix:** Verify the header names against the Scopus API docs. These may be typos or custom headers that need the correct capitalization.

---

## Priority 2 — Probable Bugs

These are logic errors that won't crash the app today but indicate broken assumptions.

### 2.1 Always-True / Always-False Conditions (`ConstantValue` — 43 occurrences)

The hotspot is `CoreConferenceRankingService.java` where `id`, `fieldsOfResearch`, and `fieldsOfResearchNames` are always `null` at the point of use (lines 140, 155, 168, 182, 197). This means those fields are never actually being populated, which is almost certainly a data mapping bug.

Other notable cases:
- `JdbcDualReadGateService.java:425` — `mismatchSample == null` is always true (dead branch)
- `JdbcDualReadGateService.java:967` — `rows == null` is always false (dead guard)
- `ScholardexSourceLinkService.java:477` — `STATE_LINKED.equals(next)` is always false (logic bug — state transition never fires)
- `RequestCorrelationFilter.java:72` — `details.getUsername() != null` always true (safe to remove the guard)
- `AsyncConfiguration.java:58,64` — null check on `getThreadPoolExecutor()` always false

**Fix strategy:**
1. Start with `CoreConferenceRankingService` — the always-null fields suggest a builder or CSV-parsing path is not assigning these values.
2. Review `ScholardexSourceLinkService:477` carefully — it looks like a state machine that has a dead transition.
3. The remaining cases are defensive null guards that can be removed.

---

### 2.2 Nullability Annotation Gaps (`NullableProblems` — 24 occurrences)

The pattern here is consistent: your code uses `@NullMarked` (via JSpecify or similar) but override methods in several classes don't carry the annotation, breaking the null contract.

**Hotspot:** `JdbcPostgresReportingProjectionService.java` has ~10 unannotated override parameters.

**Other affected files:**
- `ApiAccessDeniedHandler.java`, `ApiAuthenticationEntryPoint.java`, `CustomAccessDeniedHandler.java`
- `User.java`, `CustomUserDetailsService.java`
- `ScholardexOperabilityGaugeBinder.java`
- Several `Runner` classes (`AdminUserBootstrapRunner`, `CitationUniquenessMigrationRunner`, etc.)

**Fix:** Add `@Nullable` or `@NonNull` to the overriding parameters/return types to match the parent interface's contract.

---

### 2.3 Data Flow Issues (`DataFlowIssue` — 22 occurrences)

Key issues:

- **`UserRankingFacade.java:33`** — `forum.getIssn()` might be null when passed as an argument. Add a null check or use `Optional`.
- **`UserIndicatorResultService.java:170`** — variable assigned to the same value it already holds (redundant assignment).
- **`RankingService.java`** — switch label `"AIS"` is the only reachable case in the switch — other cases are dead.
- **Tests (multiple)** — `.getStatus()` / `.getDetails()` calls on potentially-null `Health` objects. Wrap in `assertNotNull` or use `AssertJ` assertions that handle null gracefully.

---

### 2.4 Logging Placeholder Mismatch (`LoggingPlaceholderCountMatchesArgumentCount` — 2)

These are silent bugs — the `{}` placeholder exists in the message but no argument is passed, so the exception detail is lost in the log.

- `CoreConferenceRankingService.java:84` — `log.error("Error reading the CSV file: {}")` — missing the exception argument
- `RankingService.java:56` — `log.error("Error reading the Excel file: {}")` — same issue

**Fix:**
```java
// Before
log.error("Error reading the CSV file: {}");

// After
log.error("Error reading the CSV file: {}", e.getMessage(), e);
```

---

### 2.5 Spring Data Repository Parameter Type Mismatch (`SpringDataRepositoryMethodParametersInspection` — 6)

IntelliJ flags parameter type mismatches in Spring Data query methods. These may work at runtime via type coercion but are fragile.

**Affected repositories:**
- `RankingRepository.java` — expected `Map<String, Rank>`
- `ScopusPublicationRepository.java` — expected `Collection<List<String>>` (two methods)
- `ScholardexAffiliationFactRepository.java`, `ScholardexAuthorFactRepository.java`, `ScholardexPublicationViewRepository.java` — same pattern

**Fix:** Align the method parameter types with what Spring Data expects, or add explicit `@Query` annotations to take control of the binding.

---

### 2.6 Ignored Return Values (`UnusedReturnValue` — 19)

19 method calls whose return value is never used. This is especially risky for methods that return error indicators or modified state (e.g., collection operations, builder methods).

**Fix:** Use IntelliJ's bulk fix ("Introduce variable" or annotate method with `@SuppressWarnings` if intentional). Filter to production code first — test code often legitimately discards returns.

---

## Priority 3 — Code Quality

These don't risk correctness but create noise and maintenance burden.

### 3.1 Dead Code — Unused Elements (`unused` — 403 occurrences)

This is the single biggest cluster. Key categories:

**Unused interfaces (never implemented):**
- `ActivityIndicatorRepository.java`
- `ActivityInstanceRepository.java`
- `ActivityRepository.java`
- `InstitutionRepository.java`

These look like planned features that were never wired up. Consider removing or marking with `// TODO` if future-planned.

**Unused model class:**
- `ArtisticEvent.java` — field assigned but never accessed, constructor never reachable. Candidate for deletion.
- `WoSExtractor.java` — class never instantiated anywhere.

**Unused enum constants in `ScopusImportEntityType`** — 4 constants that are never referenced.

**Unused repository methods** — several methods on `ResearcherRepository`, `CNCSISPublisherRepository`, `CoreConferenceRankingRepository`.

**Fix strategy:** Start with the clearly dead classes (`ArtisticEvent`, `WoSExtractor`). Then prune unused interface methods. Be more cautious with enum constants — they may be used by serialized data.

---

### 3.2 Unused Imports (`UNUSED_IMPORT` — 94)

94 unused imports across the codebase. This is mechanical — IntelliJ can fix all of these in one pass via **Code → Optimize Imports** (or `Ctrl+Alt+O` / `Cmd+Alt+O` on the whole project scope).

---

### 3.3 Field Injection → Constructor Injection (`SpringJavaAutowiredFieldsWarningInspection` — 39)

10 service classes use `@Autowired` field injection, which makes them harder to test and hides dependencies.

**Affected services (main production code):**
`CustomUserDetailsService`, `AdminUserService`, `CNCSISService`, `CoreConferenceRankingService`, `GroupService`, `RankingService`, `ScopusDataService`, `SenseRankingService`, `ScientificProductionService`, `ScoringFactoryService`

**Fix pattern (IntelliJ can do this automatically via "Convert to constructor injection"):**
```java
// Before
@Autowired
private SomeRepository repo;

// After
private final SomeRepository repo;

public MyService(SomeRepository repo) {
    this.repo = repo;
}
```

Since you're already using Lombok elsewhere, you can add `@RequiredArgsConstructor` at the class level and make fields `private final`.

---

### 3.4 Commented-Out Code (`CommentedOutCode` — 10)

10 blocks of dead commented code scattered across the codebase. These should either be restored (with a TODO) or deleted — they add noise without value since git history preserves them anyway.

---

### 3.5 Unchecked Warnings (`UNCHECKED_WARNING` — 53)

53 raw-type / unchecked cast warnings. These are medium-risk — they suppress generic type safety. Review each and either add proper generic bounds or `@SuppressWarnings("unchecked")` with a comment explaining why.

---

## Priority 4 — Modernization & Style

These are quick wins — low risk, high signal-to-noise.

### 4.1 Lombok Simplification (`LombokGetterMayBeUsed` — 19, `LombokSetterMayBeUsed` — 9)

28 classes have manually written getters/setters that could be replaced with `@Getter`/`@Setter`. Since Lombok is already on the classpath, this is pure cleanup. IntelliJ can apply these automatically.

### 4.2 Redundant Path Variable Names (`SpringMvcPathVariableDeclarationInspection` — 13)

In `AdminGroupController.java` (7 occurrences) and `UserViewController.java` (6 occurrences), the `@PathVariable` name parameter repeats the variable name unnecessarily.

**Fix:**
```java
// Before
@PathVariable("groupId") String groupId

// After
@PathVariable String groupId
```

### 4.3 Java Modernization — Quick Wins

| Inspection | Count | Fix |
|---|---|---|
| `TextBlockMigration` | 6 | Replace multi-line String concatenations with `"""..."""` text blocks |
| `Convert2MethodRef` | 8 | Replace lambdas like `x -> foo(x)` with `this::foo` |
| `SequencedCollectionMethodCanBeUsed` | 55 | Use `.getFirst()` / `.getLast()` instead of `.get(0)` / `.get(size()-1)` |
| `UnnecessaryModifier` | 12 | Remove implicit modifiers (e.g., `public` in interfaces) |
| `StringEqualsEmptyString` | 2 | Replace `str.equals("")` with `str.isEmpty()` |
| `SizeReplaceableByIsEmpty` | 1 | Replace `.size() == 0` with `.isEmpty()` |
| `RedundantCast` | 9 | Remove casts that are unnecessary given the inferred type |
| `UnnecessaryLocalVariable` | 9 | Inline variables returned immediately after assignment |

### 4.4 `@PathVariable` & Spring MVC Annotation Cleanup

Already covered above. IntelliJ can auto-apply all 13 with a single inspection quick fix.

---

## What to Skip (or Defer)

| Inspection | Count | Why |
|---|---|---|
| `SpellCheckingInspection` | 1,983 | Almost entirely domain terminology (e.g., "Scopus", "Scholardex", field names). Add domain words to the IntelliJ dictionary instead. |
| `JSUnresolvedReference` | 403 | Legacy JS/jQuery frontend — low value to fix unless modernizing the frontend. |
| `HtmlUnknownTag` / `HtmlDeprecatedAttribute` | 320 / 68 | Thymeleaf attributes are flagged by the HTML inspector but are valid. Suppress or ignore. |
| `GrazieInspection` / `GrazieStyle` | 57 | Grammar in comments/Javadoc — low priority. |
| `DuplicatedCode` | 307 | Review manually — some duplication is intentional in domain services. |
| `MarkdownIncorrectTableFormatting` | 45 | Doc-only, low risk. |
| `ES6ConvertVarToLetConst` | 187 | Good JS hygiene but frontend appears to be legacy — defer to a frontend modernization effort. |

---

## Suggested Sprint Ordering

**Sprint 1 (1–2 days):** P1 — Bump vulnerable dependencies, fix `ensureIndex` deprecations, fix HTTP links, fix 2 logging bugs.

**Sprint 2 (2–3 days):** P2 — Address `ConstantValue` logic bugs (especially `CoreConferenceRankingService` and `ScholardexSourceLinkService`), add null annotations, fix repository parameter types.

**Sprint 3 (1–2 days):** P3 — Run "Optimize Imports" globally (kills 94 issues in one shot), convert field injection to constructor injection, remove dead code (`ArtisticEvent`, `WoSExtractor`, unused repo interfaces).

**Sprint 4 (1–2 days):** P4 — Lombok simplification, `@PathVariable` cleanup, Java modernization quick wins (IntelliJ can auto-apply most of these).

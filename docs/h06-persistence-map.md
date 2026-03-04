# H06-S01 Persistence Architecture Map and Ownership Baseline

Date: 2026-03-03  
Scope: Mongo-backed model/repository/service/write-read ownership baseline.

## 1. Method

- Enumerated model classes and repository interfaces under:
  - `src/main/java/ro/uvt/pokedex/core/model/**`
  - `src/main/java/ro/uvt/pokedex/core/repository/**`
- Mapped primary write paths from current `save/insert/saveAll/delete*` callsites across:
  - `core/service/**`
  - `core/view/**`
  - `core/controller/**`
- Verified startup data bootstrap and scheduled sync flows:
  - `DataLoaderNew`
  - `ScopusDataService`
  - `ScopusUpdateScheduler`

## 2. Persistence Topology (Current State)

- Database style: Spring Data Mongo repositories only (`MongoRepository<..., String>`).
- Schema migration framework: none detected (no Flyway/Liquibase changelog path in repo).
- Data bootstrap style: startup import and cache warm-up in `DataLoaderNew`.
- Async/scheduled writes:
  - async Scopus import (`ScopusDataService`)
  - scheduled Scopus task polling (`ScopusUpdateScheduler`)
- Transaction annotations:
  - only `URAPRankingService` is annotated with `@Transactional`.

## 3. Entity and Collection Map

## 3.1 Core persisted entities (with repository)

| Entity | Collection | Repository |
|---|---|---|
| `User` | `scholardex.users` | `UserRepository` |
| `Researcher` | `scholardex.researchers` | `ResearcherRepository` |
| `Institution` | `institutions` | `InstitutionRepository` |
| `Group` | `scholardex.groups` | `GroupRepository` |
| `Domain` | `domains` | `DomainRepository` |
| `Indicator` | `indicators` | `IndicatorRepository` |
| `IndividualReport` | `individualReports` | `IndividualReportRepository` |
| `GroupReport` | `groupReports` | `GroupReportRepository` |
| `Activity` | `activities` | `ActivityRepository` |
| `ActivityInstance` | `activityInstances` | `ActivityInstanceRepository` |
| `ActivityIndicator` | `scholardex.activityIndicators` | `ActivityIndicatorRepository` |
| `Publication` | `scopus.publications2025` | `ScopusPublicationRepository` |
| `Citation` | `scopus.citations2025` | `ScopusCitationRepository` |
| `Author` | `scopus.authors` | `ScopusAuthorRepository` |
| `Forum` | `scopus.forums` | `ScopusForumRepository` |
| `Affiliation` | `scopus.affiliations` | `ScopusAffiliationRepository` |
| `Funding` | `scopus.funding` | `ScopusFundingRepository` |
| `WoSRanking` | `wos.rankings` | `RankingRepository` |
| `CoreConferenceRanking` | default (no explicit name) | `CoreConferenceRankingRepository` |
| `SenseBookRanking` | `senseRankings` | `SenseRankingRepository` |
| `URAPUniversityRanking` | `urap.rankings` | `URAPUniversityRankingRepository` |
| `CNCSISPublisher` | `scholardex.cncsisList` | `CNCSISPublisherRepository` |
| `ArtisticEvent` | `scholardex.artisticEvent` | `ArtisticEventRepository` |
| `ScopusPublicationUpdate` | `scholardex.tasks.scopusPublicationUpdate` | `ScopusPublicationUpdateRepository` |
| `ScopusCitationsUpdate` | `scholardex.tasks.scopusCitationsUpdate` | `ScopusCitationUpdateRepository` |

## 3.2 Non-persisted/support models (no repository)

- Reporting/support DTO or utility-like models:
  - `CNFISReport2025`
  - `WoSExtractor`
  - `Position`
  - `AbstractReport.Criterion/Threshold` (embedded under report roots)
  - `Task` base class (abstract; concrete subclasses are persisted)

## 4. Repository Query Surface (High-Impact)

- `ScopusPublicationRepository`:
  - `findByEid`, `findByDoi`, `findAllByAuthorsIn`, `findAllByAuthorsInAndTitleContains`, `findTopByAuthorsContainsOrderByCoverDateDesc`
- `ScopusCitationRepository`:
  - `findByCitedIdAndCitingId`, `findAllByCitedIdIn`, `countAllByCitedId`
- `ScopusForumRepository`:
  - `findByIdIn`, `findByScopusIdIn`, `findAllByAggregationTypeIn`
- `RankingRepository`:
  - `findAllByIssn`, `findAllByEIssn`, `findAllByWebOfScienceCategoryIndex`
- Task repositories:
  - ordered task dequeue by `findByStatusOrderByInitiatedDate(...)`

These methods are currently the highest-impact persistence contract surface for reporting/export/update flows.

## 5. Primary Write Ownership Matrix (Current State)

| Entity family | Primary write owners | Notes |
|---|---|---|
| `User`, `Researcher` | `UserService`, `ResearcherServiceImpl`, startup `AdminUserService` | service-owned writes are present. |
| `Publication`, `Citation`, `Author`, `Forum`, `Affiliation`, `Funding` | `ScopusDataService`, `ScopusUpdateScheduler`, plus selective facade writes (`UserReportFacade`, `GroupCnfisExportFacade`, `UserPublicationFacade`) | write paths are spread across import/scheduler/facade flows. |
| Reporting metadata (`Domain`, `Indicator`, `IndividualReport`, `GroupReport`) | Mixed: direct controller writes (`AdminViewController`, `AdminIndividualReportsController`, `AdminGroupReportsController`), plus startup domain seed in `DataLoaderNew` | controller-level direct writes remain a persistence ownership smell for later H06 slices. |
| Group management (`Group`) | `GroupManagementFacade` + import `GroupService` | mostly service/facade owned. |
| Activities (`Activity`, `ActivityIndicator`, `ActivityInstance`) | `AdminActivityController`, `ActivityInstanceController` | direct controller writes are current baseline. |
| Rankings (`WoSRanking`, `CoreConferenceRanking`, `SenseBookRanking`, `URAPUniversityRanking`, `CNCSISPublisher`) | importer services (`RankingService`, `CoreConferenceRankingService`, `SenseRankingService`, `URAPRankingService`, `CNCSISService`), plus maintenance/caching (`RankingMaintenanceFacade`, `CacheService`) | ranking writes have both import-time and maintenance-time paths. |
| Task queues (`ScopusPublicationUpdate`, `ScopusCitationsUpdate`) | `UserScopusTaskFacade`, `ScopusUpdateScheduler` | expected producer/consumer split. |
| Artistic events (`ArtisticEvent`) | `ArtisticEventsService`, direct read/write in `AdminViewController` | dual write ownership paths. |

## 6. High-Impact Read Ownership (Application Layer)

- User flows:
  - `UserPublicationFacade` (`Publication/Citation/Author/Forum`)
  - `UserReportFacade` (`Indicator/IndividualReport/ActivityInstance + Scopus data + reporting services`)
- Admin/group flows:
  - `GroupReportFacade`, `GroupExportFacade`, `GroupCnfisExportFacade`
  - `AdminInstitutionReportFacade`, `AdminScopusFacade`
- Reporting/scoring read dependencies:
  - now through `ReportingLookupPort` (V04 outcome), adapter-backed by cache service.

## 7. Bootstrap and Lifecycle Flows

- Startup (`DataLoaderNew`):
  - ensure default admin user
  - Scopus data load-if-empty
  - domain `"ALL"` seed
  - artistic events import
  - URAP + CNCSIS imports
- Scheduled:
  - `ScopusUpdateScheduler` processes publication/citation update tasks and upserts Scopus entities.
- Cache persistence hooks:
  - `CacheService` can flush ranking/conference/forum/author/affiliation caches to repositories.

## 8. Baseline Observations for H06-S02/S03

- Collection naming consistency drift candidates exist:
  - mixed prefixes (`scopus.*`, `scholardex.*`, plain names like `domains`, `institutions`)
  - task collections were migrated from `schodardex.*` to `scholardex.*` under H06-R4.
- Write ownership is not yet uniformly service/facade-owned:
  - several controllers still write directly to repositories (known baseline debt for H06 planning).
- No explicit migration/versioning mechanism is present:
  - persistence shape changes rely on code compatibility and loader behavior.

These are not remediation decisions yet; they are baseline inputs for `H06-S02` and `H06-S03`.

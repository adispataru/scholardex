# H06-S02 Schema and Data-Shape Drift Inventory

Date: 2026-03-03  
Scope: model field semantics, collection naming, shape assumptions, and runtime/template data-shape compatibility.

## 1. Method

- Reviewed persisted model definitions (`@Document`, field types, ID strategy).
- Scanned runtime/template usage for shape assumptions and mismatches.
- Correlated import/scheduler write paths to detect semantic duplication drift.

## 2. Drift Clusters (Risk-Ranked)

| ID | Severity | Drift cluster | Evidence |
|---|---|---|---|
| `D-H06-01` | high | **Template-model contract mismatch for activity indicators** | `admin/activity-indicators.html` renders `${indicator.activity.name}`, but `ActivityIndicator` model has no `activity` field (`src/main/resources/templates/admin/activity-indicators.html:65`, `src/main/java/ro/uvt/pokedex/core/model/reporting/ActivityIndicator.java:10-18`). |
| `D-H06-02` | high | **Duplicate subtype semantic fields on publication (`scopusSubtype` vs `subtype`)** | Same semantic value duplicated at write time in import (`ScopusDataService` sets both), then read with dual-source fallbacks (`PublicationSubtypeSupport`, CNFIS local resolver) (`src/main/java/ro/uvt/pokedex/core/model/scopus/Publication.java:24-27`, `src/main/java/ro/uvt/pokedex/core/service/importing/ScopusDataService.java:199-220`, `src/main/java/ro/uvt/pokedex/core/service/reporting/PublicationSubtypeSupport.java:14-18`). |
| `D-H06-03` | high | **Inconsistent task collection prefix (`schodardex` typo-like) vs main `scholardex` namespace** | Task collections use `schodardex.tasks.*` while user/group/reporting collections use `scholardex.*` (`src/main/java/ro/uvt/pokedex/core/model/tasks/ScopusPublicationUpdate.java:10`, `src/main/java/ro/uvt/pokedex/core/model/tasks/ScopusCitationsUpdate.java:10`, `src/main/java/ro/uvt/pokedex/core/model/user/User.java:17`, `src/main/java/ro/uvt/pokedex/core/model/reporting/Group.java:14`). |
| `D-H06-04` | high | **Unsafe year parsing assumptions on string dates** | Multiple flows parse year via `substring(0,4)`/`Integer.parseInt` without guard; malformed/null `coverDate`/`date` can break processing (`src/main/java/ro/uvt/pokedex/core/service/application/UserReportFacade.java:141`, `src/main/java/ro/uvt/pokedex/core/service/application/GroupCnfisExportFacade.java:113`, `src/main/java/ro/uvt/pokedex/core/service/reporting/AbstractForumScoringService.java:232`, `src/main/java/ro/uvt/pokedex/core/model/activities/ActivityInstance.java:25`). |
| `D-H06-05` | medium-high | **Mixed relation encoding strategy across adjacent entities (ID strings vs DBRef)** | Example: `Publication.authors` is `List<String>` IDs while `Author.affiliations` uses `@DBRef`; report/group entities rely heavily on DBRefs. This increases shape coupling and partial-load risk (`src/main/java/ro/uvt/pokedex/core/model/scopus/Publication.java:31-33`, `src/main/java/ro/uvt/pokedex/core/model/scopus/Author.java:19-21`, `src/main/java/ro/uvt/pokedex/core/model/reporting/Group.java:20-23`). |
| `D-H06-06` | medium-high | **Citation uniqueness enforced in code, not schema index** | `Citation` checks duplicates via repository lookup before insert, but no declared compound unique index for (`citedId`,`citingId`) (`src/main/java/ro/uvt/pokedex/core/model/scopus/Citation.java:10-18`, `src/main/java/ro/uvt/pokedex/core/repository/scopus/ScopusCitationRepository.java:17-21`, `src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java:210-216`). |
| `D-H06-07` | medium | **Collection naming convention drift across domains** | Mixed naming styles (`scopus.*`, `scholardex.*`, camel-case plural, plain names like `domains`, `institutions`) complicate ops tooling and migration consistency (`src/main/java/ro/uvt/pokedex/core/model/**` `@Document(collection=...)`). |
| `D-H06-08` | medium | **Publication identity semantics are split across `id`, `eid`, and `doi`** | Repository lookups use `findByEid`/`findByDoi`; importer comments indicate alternative ID strategies were considered then disabled, leaving implicit assumptions (`src/main/java/ro/uvt/pokedex/core/model/scopus/Publication.java:15-18`, `src/main/java/ro/uvt/pokedex/core/repository/scopus/ScopusPublicationRepository.java:14-15`, `src/main/java/ro/uvt/pokedex/core/service/importing/ScopusDataService.java:90-92,186-188`). |
| `D-H06-09` | medium | **CNFIS report model is non-persisted but coupled to persisted publication/forum shape** | `CNFISReport2025` has report flags that depend on normalized subtype/forum/year fields; no persisted audit schema for generated result snapshots, raising reproducibility drift risk (`src/main/java/ro/uvt/pokedex/core/model/reporting/CNFISReport2025.java`, `src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService2025.java`). |

## 3. Priority Next-Look List (for H06-S03/S04)

1. `D-H06-01` fix activity indicator shape mismatch (model or template alignment decision).
2. `D-H06-02` define canonical subtype/source persistence contract and migration strategy.
3. `D-H06-04` normalize date/year parsing safety contract across services.
4. `D-H06-06` decide index-level uniqueness contract for citation pairs.
5. `D-H06-03` decide canonical collection namespace policy (`scholardex` vs current mixed state).

## 4. Open Decisions Seeded for H06-S03/H06-S04

- Should `ActivityIndicator` own an explicit `Activity` reference, or should templates stop rendering activity directly?
- Should publication canonical subtype become a single persisted field with derived compatibility accessors only?
- Should task collections be migrated/aliased to a canonical prefix, and if yes, how to preserve running queue compatibility?
- Which date fields remain free-form strings vs normalized date types (or validated ISO string contracts)?
- Should citation uniqueness be guaranteed by Mongo compound unique index in addition to application checks?

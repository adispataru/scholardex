# WoS Ranking Optimization - Approach 2 (Balanced Split Write/Read Model) [Recommended]
Status: exploratory archived proposal; retained for context only.

## Summary
This approach splits WoS storage into normalized fact collections plus read-optimized projections. AIS/RIS remain operational metrics from government files, IF is retained as reference-only from official WoS extracted JSON, and API contracts stay unchanged.

It offers the best balance between migration feasibility, query performance, and removal of `CacheService` from ranking/report calculation paths.

## Target Architecture
- Canonical identity collection:
  - `wos.journals`
    - `journalId`, `primaryIssn`, `eIssn`, `aliasIssns[]`, `title`, `abbrTitle`, `editionCoverage[]`.
- Normalized metric facts:
  - `wos.metric_facts`
    - `{ journalId, year, metricType: AIS|RIS|IF, value, sourceType, sourceFile, sourceVersion, isOfficialWosExtract }`.
- Normalized category facts:
  - `wos.category_facts`
    - `{ journalId, year, categoryName, edition, metricType, quarter, rank, sourceType, sourceVersion }`.
- Read projections:
  - `wos.ranking_view` for UI paging/search (`/api/rankings/wos`).
  - `wos.scoring_view` for reporting/domain/category-year lookups.

## Ingestion Strategy
- Government files:
  - Keep AIS/RIS operational importer only.
  - Remove JIF gov import branch from runtime ingestion.
- Official WoS extracted JSON (`data/wos-json-1997-2019`):
  - Parse SCIE+SSCI yearly files.
  - Ingest `articleInfluenceScore` as AIS where valid.
  - Ingest `journalImpactFactor` as IF with `isOfficialWosExtract=true`.
  - Normalize `-999` sentinel values to missing and skip invalid facts.
- Identity resolution:
  - deterministic ISSN/eISSN keying,
  - alias linking table for collisions/splits,
  - unresolved records emitted to import reconciliation log.

## Indicator.Strategy.IMPACT_FACTOR Handling
- Keep strategy available in read-only official WoS extracted data mode.
- Score service reads IF only from `metric_facts` where `isOfficialWosExtract=true` or explicitly enabled source policy.
- Add deprecation marker and usage telemetry.
- No dependency on future government IF files.

## DB Access Contract (CacheService Phase-Out)
- API and scoring readers query projections/facts directly:
  - ranking list/search/sort -> `wos.ranking_view`.
  - score computation by domain/category/year -> `wos.scoring_view` / `category_facts`.
- Compound indexes:
  - `metric_facts(journalId, year, metricType)` unique.
  - `category_facts(categoryName, year, metricType, quarter, rank)`.
  - `ranking_view(name, issn, eIssn, aliasIssns)` for search.
- `CacheService` becomes optional short-lived memoization for high-frequency report batches, not system-of-record.

## Big-Bang Migration Plan
1. Define canonical schemas + normalization/identity rules.
2. Build full import pipeline:
   - official WoS extracted JSON replay (1997-2019),
   - AIS/RIS gov ingestion.
3. Populate fact collections and build projections.
4. Run parity verification:
   - counts, sample journal histories, score outputs.
5. Switch readers (API/reporting lookup port) to new projections.
6. Remove `CacheService` dependencies from ranking/report paths.
7. Keep rollback snapshot and one-command re-materialization procedure.

## Testing and Validation
- Import correctness:
  - JSON parsing and sentinel normalization,
  - AIS/RIS year-specific column mapping.
- Data quality:
  - dedupe and alias collision checks,
  - category canonicalization and edition consistency.
- Performance:
  - `/api/rankings/wos` p95 latency at full dataset size,
  - report scoring lookup p95 without full-cache scans.
- Regression:
  - AIS/RIS score parity with baseline,
  - deterministic behavior for missing IF.
- Migration safety:
  - dry-run import reports,
  - reconciliation metrics (facts, journals, categories),
  - rollback/cutover rehearsal in staging.

## Risks and Tradeoffs
- Pros:
  - Cleaner data model for calculations.
  - Better indexability and smaller query payloads.
  - Strong path to decommission cache-first behavior.
- Cons:
  - More moving parts than in-place model.
  - Requires projection rebuild tooling and migration orchestration.

## Final Recommendation and Roadmap
This is the recommended target approach.

Execution roadmap:
1. Implement canonical schemas + import normalizers.
2. Run big-bang migration in staging from official WoS extracted JSON (1997-2019) + AIS/RIS files.
3. Switch API/report readers to projections (`ranking_view`, `scoring_view`).
4. Remove `CacheService` dependencies in ranking/report paths.
5. Mark `IMPACT_FACTOR` strategy deprecated and keep read-only fallback sourced from official WoS extracted data.

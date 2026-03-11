# H06-S04 Canonical Persistence Contracts

Date: 2026-03-03  
Status: active baseline for H06 remediation and future persistence changes.

## 1. Purpose

Define explicit persistence contracts for data identity, date/time shape, query semantics, and write ownership so new changes do not reintroduce drift identified in:

- `docs/h06-persistence-map.md`
- `docs/h06-schema-drift-inventory.md`
- `docs/h06-query-consistency-findings.md`

## 2. Contract Rules (Normative)

## C1. Write Ownership Boundary

- Controllers (`Z1`) must not introduce new direct repository writes.
- New write paths must go through service/facade orchestration (`Z2`).
- Existing direct controller writes are legacy debt to be migrated in H06 remediation slices; they are not the target pattern.

## C2. Entity Identity Contract

- Every persisted entity has one canonical Mongo `_id` (`@Id`).
- For Scopus `Publication`:
  - canonical persistence identity remains `id`.
  - `eid` and `doi` are domain identifiers and must not be treated as interchangeable primary IDs.
- Query policy:
  - use `findById` only when caller truly has canonical ID.
  - use `findByEid` / `findByDoi` only when caller explicitly has those identifiers.

## C3. Date and Year Contract

- Date-like fields used for filtering/ranking must follow an ISO-compatible `yyyy-MM-dd` or `yyyy` string convention until a type migration is approved.
- Parsing policy:
  - no new direct `substring(0,4)` or ad-hoc year parsing in business code.
  - use a shared safe year extraction helper (to be introduced in remediation).
  - malformed/null input must fail safe (skip + warn), not crash full flow.

## C4. Subtype Source Contract (Publication)

- Canonical runtime read policy:
  - prefer `scopusSubtype`, fallback `subtype` (already implemented in `PublicationSubtypeSupport`).
- No new logic may branch directly on raw `publication.getSubtype()` without using the shared resolver.
- Transitional storage policy:
  - dual fields are tolerated during migration.
  - remediation target is one canonical persisted source + compatibility fallback strategy.

## C5. Query Ordering Contract

- Any user-visible list/export/report query must produce deterministic order.
- If repository methods do not encode ordering, service/facade layer must apply explicit sorting before returning.
- Default repository natural order must not be assumed stable.

## C6. Query Text-Matching Contract

- User/admin-facing search queries must have explicit case-sensitivity policy documented at callsite.
- Default contract for new search behavior: case-insensitive matching unless domain explicitly requires exact matching.

## C7. Uniqueness and Index Contract

- Uniqueness requirements relied on by business logic must have a DB-level backing plan.
- Priority index contract:
  - citation edge uniqueness: (`citedId`, `citingId`) unique.
- Application-level pre-insert checks remain allowed but do not replace DB uniqueness guarantees.

## C8. Cache-to-Repository Key Contract

- Cache keys must match repository query semantics.
- If a cache is named/used as ISSN-indexed, it must be keyed by ISSN/EISSN-compatible values (not unrelated IDs).
- Mixed key semantics in a single cache map are forbidden for new code.

## C9. Collection Naming Contract

- New collections must follow one canonical namespace strategy.
- Default contract:
  - domain-scoped prefix where appropriate (`scopus.*`, `scholardex.*`, `wos.*`, `urap.*`), with no typo variants.
- Typos/inconsistent prefixes in existing collections are legacy debt; remediation requires migration/backward-compatibility plan.

## C10. Reference Modeling Contract

- For new/changed entity relationships, choose one reference style per relation:
  - explicit ID references, or
  - `@DBRef`
- Mixed encoding of the same relation semantics across nearby entities requires explicit rationale in PR notes.

## 3. Applied Decisions for Current Drift Items

- `D-H06-01` (activity indicator template mismatch): **model/template alignment required**; template must not assume fields absent from persisted model.
- `D-H06-03` (task collection `schodardex`): **canonical namespace is `scholardex`**; migration plan required before rename.
- `Q-H06-01` (ranking cache key drift): **cache key must align with lookup semantics**; ID-keyed prefill in ISSN cache is not contract-compliant.
- `Q-H06-02` (citation uniqueness): **DB-level uniqueness is required** (with safe migration path).

## 4. Review Checklist (Persistence Changes)

1. Does this change introduce any new controller-to-repository write?
2. Are identity lookups using the correct identifier (`id` vs `eid` vs `doi`)?
3. Is date/year parsing safe and shared (no raw substring parsing)?
4. Are query results deterministic for UI/export usage?
5. Is text matching case policy explicit?
6. If uniqueness is assumed, is there an index or migration plan?
7. Do cache keys and repository lookup keys match semantics?
8. Does the collection name follow canonical namespace rules?

## 5. Out-of-Scope for S04

- No runtime behavior changes in this slice.
- No schema migrations in this slice.
- No test additions in this slice (covered in `H06-S05`).

## 6. S05 Status Notes

- `C3` date/year contract: partially implemented in high-impact CNFIS export filters.
  - Added shared helper: `PersistenceYearSupport.extractYear(...)`.
  - Applied in `UserReportFacade#buildUserCnfisWorkbookExport` and `GroupCnfisExportFacade#filterPublicationsByYear`.
  - Invalid/malformed dates now follow `skip + warn` behavior in these paths.
- `C8` cache key contract: implemented for ranking ISSN lookup path.
  - `CacheService.cacheRankings()` now pre-fills ISSN cache only with normalized `issn`/`eIssn` keys.
  - `getCachedRankingsByIssn` now normalizes keys, performs dual repository fallback (`issn` + `eIssn`), and deduplicates by ranking ID.

## 7. B04 Status Notes (2026-03-04)

- `C3` date/year contract rollout is complete for high-impact reporting/export flows (`R2`):
  - Added `PersistenceYearSupport.extractYearString(...)` for rendering/export-safe year extraction.
  - Replaced raw year parsing in scoring bases, grouping facades, and workbook/CSV exporters with shared helper usage.
  - Added `ActivityInstance#getYearOptional()` for safe call-site migration while preserving legacy `getYear()` compatibility.

## 8. B05 Status Notes (2026-03-04)

- `C2` identity contract rollout is complete for `R3` high-impact publication flows:
  - user publication edit/save path now uses explicit canonical-id naming (`publicationId`) with `findById` semantics.
  - importer/scheduler EID-based semantics remain unchanged and explicitly scoped to integration flows.
- `C5` ordering contract rollout is complete for `R3` hotspot outputs:
  - deterministic publication comparator adopted (`coverDate desc`, `title asc`, `id asc`) across user/admin/group publication and citation lists.
  - hybrid policy applied:
    - canonical case-insensitive ordered read-service method for admin title search (`findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc`),
    - service-level sorting for aggregated multi-query flows.
- Duplicate amplification control for author-iterative publication aggregation is implemented:
  - user publication aggregation dedupes by publication ID before downstream metrics (h-index, citation totals, lookup maps).

## 9. B10 API Naming Hygiene Notes (2026-03-04)

- Canonical eISSN repository query API is now `findAllByEIssn`.
- Canonical method is explicitly query-annotated (`@Query("{ 'eIssn': ?0 }")`) to avoid Spring Data derived-name ambiguity on `eIssn`.
- Legacy typo method `findAllByeIssn` is retired (`B10A` complete).
- New usage policy:
  - service/test call sites must use `findAllByEIssn`;
  - any `findAllByeIssn` usage is guardrail-blocked.

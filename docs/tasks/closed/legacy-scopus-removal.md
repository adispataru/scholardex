# Legacy `scopus.Publication` Removal from Scoring Services

**Status:** Completed
**Created:** 2026-04-02
**Completed:** 2026-04-03

## Problem (historical)

All scoring services in `ro.uvt.pokedex.core.service.reporting` consumed the legacy `Publication` model (`ro.uvt.pokedex.core.model.scopus.Publication`), a MongoDB-era DTO originally mapped to the `scopus.publications2025` collection. `PostgresScholardexProjectionReadPort.mapPublication()` manually mapped ~40 PostgreSQL columns back into a legacy `Publication` object before handing it to scoring logic, discarding canonical metadata in the process.

## Handover

The migration is complete. Key outcomes:

- `ScoringPublicationReadModel` interface introduced in `ro.uvt.pokedex.core.model.reporting` with a `ScoringPublication` record implementation.
- `PostgresScholardexProjectionReadPort` now maps directly to `ScoringPublication` via `mapScoringPublication()`; the old `mapPublication()` method has been removed.
- All scoring services (`ScoringService` interface, both abstract base classes, all concrete scorers) accept `ScoringPublicationReadModel`.
- `ScientificProductionService`, `ScoringFactoryService`, and `CNFISReportExportService` all use the new interface.
- `PublicationSubtypeSupport` accepts `ScoringPublicationReadModel`.
- `Publication.NON_WOS_ID` was moved to `CanonicalPublicationConstants.NON_WOS_ID` in `ro.uvt.pokedex.core.model.reporting`.
- `Publication.java` has been deleted entirely — zero imports remain in `src/`.

## Scope

Replace all usage of `ro.uvt.pokedex.core.model.scopus.Publication` in the scoring and reporting layer with a purpose-built read model backed directly by canonical Scholardex data. The legacy `Publication` class itself may survive for other consumers (e.g., direct MongoDB reads elsewhere), but the scoring/reporting package should be fully decoupled from it.

## Field Usage Audit

The scoring layer accesses a narrow subset of Publication fields:

| Field | Access pattern | Canonical equivalent |
|---|---|---|
| `id` | Tracking, logging, cache keys | `ScholardexPublicationFact.id` |
| `forum` | ISSN lookup for WoS/CORE rankings | `ScholardexPublicationFact.forumId` |
| `coverDate` | Year extraction for ranking queries | `ScholardexPublicationFact.coverDate` |
| `scopusSubtype` / `subtype` | Route to specialized scorers | `ScholardexPublicationFact.scopusSubtype` / `.subtype` |
| `authors` (via `.size()`) | Author count for citation formulas | `ScholardexPublicationFact.authorIds` |
| `doi`, `wosId` | Export filtering, CNFIS report | `ScholardexPublicationFact.doi` / `.wosId` |
| `title` | Display in reports | `ScholardexPublicationFact.title` |
| `citedBy` / `citedbyCount` | Citation aggregation | Postgres `citing_publication_ids` / `cited_by_count` |

Fields like `description`, `authKeywords`, `freetoread`, `fundingId`, `pageRange` are either unused or only used in export and can be added to the new model on demand.

## Migration Strategy

Introduce a `ScoringPublicationReadModel` interface containing only the fields the scoring layer needs, then wire the PostgreSQL read port to produce instances of it directly. This avoids a big-bang rewrite: the interface can initially be backed by the same `mapPublication()` result set, then the legacy `Publication` dependency is removed once all consumers compile against the interface.

## Task Breakdown

### Phase 1: Introduce the read-model interface

- [x] `R1.1` **Define `ScoringPublicationReadModel` interface.**
  Create a read-only interface in `ro.uvt.pokedex.core.model.reporting` (or `.service.reporting`) exposing the fields listed in the audit above: `getId()`, `getForumId()`, `getCoverDate()`, `getSubtype()`, `getScopusSubtype()`, `getAuthorIds()`, `getAuthorCount()`, `getDoi()`, `getWosId()`, `getTitle()`, `getCitedByCount()`, `getCitingPublicationIds()`. Keep it minimal; add fields later if export services need them.

- [x] `R1.2` **Create a JDBC-backed implementation of `ScoringPublicationReadModel`.**
  In `PostgresScholardexProjectionReadPort`, add a new `mapScoringPublication()` row mapper that returns a concrete `ScoringPublication` (record or POJO) implementing the interface. This mapper reads from the same `scholardex_publication_view` but skips fields the scoring layer never uses. Keep `mapPublication()` intact for now so nothing breaks.

- [x] `R1.3` **Add parallel query methods returning `ScoringPublicationReadModel`.**
  Add `findScoringPublicationsByAuthorIdsIn()`, `findScoringPublicationById()`, etc. alongside the existing `findPublications*()` methods on `PostgresScholardexProjectionReadPort` and surface them through `ScholardexProjectionReadService`.

### Phase 2: Migrate the scoring interface and base classes

- [x] `R2.1` **Update `ScoringService` interface.**
  Change `getScore(Publication publication, Indicator indicator)` to `getScore(ScoringPublicationReadModel publication, Indicator indicator)`. Since `ScoringService` is internal (no external API consumers), this can be done in one commit across the interface and all implementations.

- [x] `R2.2` **Migrate `AbstractForumScoringService` and `AbstractWoSForumScoringService`.**
  Update field access from `publication.getForum()` to `publication.getForumId()`, `publication.getAuthors().size()` to `publication.getAuthorCount()`, etc. These abstract classes propagate the change to all concrete scorers that extend them.

- [x] `R2.3` **Migrate concrete scoring services.**
  Update each scorer to compile against `ScoringPublicationReadModel`. Services to touch (non-exhaustive):
  - `ComputerScienceScoringService`
  - `ComputerScienceJournalScoringService`
  - `ComputerScienceConferenceScoringService`
  - `ImpactFactorJournalScoringService`
  - `AISJournalScoringService`
  - `RISJournalScoringService`
  - `EconomicsJournalScoringService`
  - `UniversityRankScoringService`
  - `ArtEventScoringService`

- [x] `R2.4` **Migrate `PublicationSubtypeSupport` utility.**
  This helper resolves the `scopusSubtype`/`subtype` duality. Update it to accept `ScoringPublicationReadModel` and use the canonical subtype directly when available, falling back to `scopusSubtype` only for legacy compatibility.

### Phase 3: Migrate reporting consumers

- [x] `R3.1` **Migrate `ScientificProductionService`.**
  Change `calculateScientificProductionScore(List<Publication>, Indicator)` to accept `List<ScoringPublicationReadModel>`. Update the MVEL formula evaluation that accesses `cited.getAuthors().size()` to use `cited.getAuthorCount()`.

- [x] `R3.2` **Migrate `ScoringFactoryService`.**
  Ensure the router passes `ScoringPublicationReadModel` through to delegated scorers. This should mostly be a type-signature change since the factory just dispatches.

- [x] `R3.3` **Migrate `CNFISReportExportService`.**
  This service accesses additional fields for Excel export (`title`, `doi`, `wosId`, `pageRange`, etc.). If any fields are missing from `ScoringPublicationReadModel`, extend the interface or introduce a `ReportingPublicationReadModel` that extends it with export-specific fields. Update the `NON_WOS_ID` filtering logic.

### Phase 4: Remove the legacy bridge

- [x] `R4.1` **Remove `mapPublication()` from `PostgresScholardexProjectionReadPort`.**
  Once no consumer references the legacy `Publication`-returning query methods, delete `mapPublication()` and the old `findPublications*()` methods that return `Publication`. This is the point of no return for the scoring layer.

- [x] `R4.2` **Remove `Publication` import from the reporting package.**
  Verify with a grep that no file under `service/reporting` or `model/reporting` imports `ro.uvt.pokedex.core.model.scopus.Publication`. If the legacy class is still needed by non-scoring code (e.g., direct MongoDB reads), leave it in place but document its reduced scope.

### Phase 5: Validation and cleanup

- [x] `R5.1` **Update or create unit tests for scoring services.**
  Replace test fixtures that construct `Publication` objects with `ScoringPublicationReadModel` fixtures (or use the concrete record). Verify score calculations produce identical results before and after migration.

- [x] `R5.2` **Integration test: scoring results parity.**
  Run the full scoring pipeline against a known dataset and compare output (scores, categories, quarters) with the pre-migration baseline. This can be a one-off verification script or a persistent regression test.

- [x] `R5.3` **Assess whether `Publication.java` can be deleted entirely.**
  Search the codebase for any remaining consumers outside the scoring/reporting layer. If none exist, delete the class. If consumers remain, add a deprecation annotation and a doc comment pointing to the canonical models.

## Risks (resolved)

- **DBLP evidence repository** — confirmed clean; `ScholardexPublicationDblpEvidenceRepository` does not depend on the legacy `Publication`.
- **MVEL formulas** — working against the new model's getter names.
- **CS journal caching** — deferred; the existing `TODO: better caching mechanism` is unrelated to the model migration.
- **`PersistenceYearSupport`** — model-agnostic (takes `String`), no change needed.
- **`NON_WOS_ID` constant** — moved to `CanonicalPublicationConstants` and referenced consistently across `CNFISReportExportService`, `UserReportFacade`, `GroupCnfisExportFacade`, `WosScholardexOnboardingService`, and `PublicationEnrichmentLinkerService`.

# H17.1 Canonical Scopus Data Contract (Amended For Multi-Source Publication Projection)

## Summary
This document locks the canonical Scopus schema and source-policy contract for H17 before H17.2+ implementation changes.

Operational policy lock:
- Canonical Scopus pipeline is `events -> facts -> views`.
- Canonical Scopus facts are ingestion-authoritative (event-derived).
- Runtime publication reads converge to a derived merged projection: `scholardex.publication_view`.
- `scholardex.publication_view` merges source-specific enrichments from Scopus, WoS, and Google Scholar under explicit field ownership boundaries.
- H17 assumption lock (2026-03-06): big-bang cutover in clean state, without historical backfill.
- Amendment lock (2026-03-06): H17.1 remains complete and now includes cross-source ownership and merged publication projection constraints.
- Amendment lock (2026-03-07): WoS category ranking semantics are explicit: `rank` is category+edition rank, `quartileRank` is rank within quartile.

## Scope
This contract covers Scopus entities used by active runtime paths:
- publications
- citations
- forums
- authors
- affiliations
- funding

This contract also locks:
- immutable import-event lineage requirements,
- canonical identity keys and idempotence keys,
- cross-source field ownership boundaries,
- merged publication projection compatibility for existing reads.

Google Scholar note:
- Scholar is included as a supported enrichment source in contract form.
- Operational Scholar ingestion is out of scope for this H17.1 amendment and is handled in later H17 implementation slices.

## Canonical Collections and Views

### `scopus.import_events`
- Purpose: immutable ingestion ledger used for replay, idempotence, and audit lineage.
- Required fields:
  - `id`
  - `entityType` (`PUBLICATION|CITATION|FORUM|AUTHOR|AFFILIATION|FUNDING`)
  - `source` (for example `SCOPUS_JSON_BOOTSTRAP|SCOPUS_PYTHON_AUTHOR_WORKS|SCOPUS_PYTHON_CITATIONS`)
  - `sourceRecordId` (source-level entity reference; typically EID/source_id/afid/author_id)
  - `batchId` (ingestion batch run id)
  - `correlationId` (task/request trace id)
  - `payloadFormat` (for example `json-object`)
  - `payload` (normalized serialized source payload)
  - `payloadHash` (sha256 of normalized payload)
  - `ingestedAt`
- Canonical identity key:
  - immutable source identity tuple: `(entityType, source, sourceRecordId, batchId, payloadHash)`
- Idempotence/upsert uniqueness key:
  - `(entityType, source, sourceRecordId, payloadHash)`
- Lineage fields:
  - collection itself is lineage root; all downstream facts/views must reference event lineage.
- Immutability:
  - import events are append-only; no mutable in-place payload edits.

### `scopus.publication_facts`
- Purpose: canonical normalized publication facts derived from Scopus import events.
- Required fields:
  - `id` (canonical Scopus publication fact id)
  - `eid`
  - `title`
  - `authors` (author ids)
  - `affiliations` (affiliation ids)
  - `forumId`
  - `coverDate`
  - `citedByCount`
  - `sourceEventId`
  - `source`
  - `sourceRecordId`
  - `sourceBatchId`
  - `sourceCorrelationId`
  - `createdAt`
  - `updatedAt`
- Canonical identity key:
  - `eid`
- Idempotence/upsert uniqueness key:
  - `(eid)`
- Lineage fields:
  - `sourceEventId`, `source`, `sourceRecordId`, `sourceBatchId`, `sourceCorrelationId`

### `scopus.citation_facts`
- Purpose: canonical citation-edge facts between cited and citing publications.
- Required fields:
  - `id`
  - `citedEid`
  - `citingEid`
  - `sourceEventId`
  - `source`
  - `sourceRecordId`
  - `sourceBatchId`
  - `sourceCorrelationId`
  - `createdAt`
  - `updatedAt`
- Canonical identity key:
  - `(citedEid, citingEid)`
- Idempotence/upsert uniqueness key:
  - `(citedEid, citingEid)`
- Lineage fields:
  - `sourceEventId`, `source`, `sourceRecordId`, `sourceBatchId`, `sourceCorrelationId`

### `scopus.forum_facts`
- Purpose: canonical forum identity/metadata facts for venue resolution and search.
- Required fields:
  - `id`
  - `sourceId`
  - `publicationName`
  - `issn`
  - `eIssn`
  - `aggregationType`
  - `sourceEventId`
  - `source`
  - `sourceRecordId`
  - `sourceBatchId`
  - `sourceCorrelationId`
  - `createdAt`
  - `updatedAt`
- Canonical identity key:
  - `sourceId`
- Idempotence/upsert uniqueness key:
  - `(sourceId)`
- Lineage fields:
  - `sourceEventId`, `source`, `sourceRecordId`, `sourceBatchId`, `sourceCorrelationId`

### `scopus.author_facts`
- Purpose: canonical author facts for lookup/report joins.
- Required fields:
  - `id`
  - `authorId` (Scopus author id)
  - `name`
  - `affiliationIds`
  - `sourceEventId`
  - `source`
  - `sourceRecordId`
  - `sourceBatchId`
  - `sourceCorrelationId`
  - `createdAt`
  - `updatedAt`
- Canonical identity key:
  - `authorId`
- Idempotence/upsert uniqueness key:
  - `(authorId)`
- Lineage fields:
  - `sourceEventId`, `source`, `sourceRecordId`, `sourceBatchId`, `sourceCorrelationId`

### `scopus.affiliation_facts`
- Purpose: canonical affiliation facts for filtering/reporting.
- Required fields:
  - `id`
  - `afid`
  - `name`
  - `city`
  - `country`
  - `sourceEventId`
  - `source`
  - `sourceRecordId`
  - `sourceBatchId`
  - `sourceCorrelationId`
  - `createdAt`
  - `updatedAt`
- Canonical identity key:
  - `afid`
- Idempotence/upsert uniqueness key:
  - `(afid)`
- Lineage fields:
  - `sourceEventId`, `source`, `sourceRecordId`, `sourceBatchId`, `sourceCorrelationId`

### `scopus.funding_facts`
- Purpose: canonical funding facts linked from Scopus publications.
- Required fields:
  - `id`
  - `acronym`
  - `number`
  - `sponsor`
  - `fundingKey` (normalized fingerprint of acronym+number+sponsor)
  - `sourceEventId`
  - `source`
  - `sourceRecordId`
  - `sourceBatchId`
  - `sourceCorrelationId`
  - `createdAt`
  - `updatedAt`
- Canonical identity key:
  - `fundingKey` where key is derived from normalized `(acronym, number, sponsor)`
- Idempotence/upsert uniqueness key:
  - `(fundingKey)`
- Lineage fields:
  - `sourceEventId`, `source`, `sourceRecordId`, `sourceBatchId`, `sourceCorrelationId`

### `scopus.forum_search_view`
- Purpose: `/api/scopus/forums` paging/sort/search contract.
- Minimum contract fields:
  - `id`
  - `publicationName`
  - `issn`
  - `eIssn`
  - `aggregationType`
  - `buildVersion`
  - `buildAt`
  - `updatedAt`
  - lineage reference set

### `scopus.author_search_view`
- Purpose: `/api/scopus/authors` paging/sort/search contract.
- Minimum contract fields:
  - `id`
  - `name`
  - `affiliationIds`
  - `buildVersion`
  - `buildAt`
  - `updatedAt`
  - lineage reference set

### `scopus.affiliation_search_view`
- Purpose: `/api/scopus/affiliations` paging/sort/search contract.
- Minimum contract fields:
  - `id` (`afid`)
  - `name`
  - `city`
  - `country`
  - `buildVersion`
  - `buildAt`
  - `updatedAt`
  - lineage reference set

### `scholardex.publication_view`
- Purpose: derived merged publication projection for admin/user/reporting/export/scoring reads.
- Contract position:
  - derived/materialized projection only; not an authoritative source collection.
  - source-specific facts remain authoritative (Scopus/WoS/Scholar by ownership).
- Minimum contract fields:
  - `id` (stable merged publication id)
  - `doi`
  - `eid` (Scopus key, when present)
  - `googleScholarId` (Scholar-owned enrichment key, when present)
  - `title`
  - subtype/editability fields consumed by current reads:
    - `subtype`
    - `subtypeDescription`
    - `scopusSubtype`
    - `scopusSubtypeDescription`
    - `approved`
  - `coverDate`
  - supporting bibliographic fields used in exports/reports:
    - `coverDisplayDate`
    - `volume`
    - `issueIdentifier`
    - `description`
    - `creator`
    - `articleNumber`
    - `pageRange`
  - `authorIds`
  - `authorCount`
  - `correspondingAuthors`
  - `affiliationIds`
  - `forumId`
  - funding/access fields used by current reporting:
    - `fundingId`
    - `openAccess`
    - `freetoread`
    - `freetoreadLabel`
  - `citingPublicationIds` (or equivalent edge materialization)
  - `citedByCount`
  - `wosId` (WoS-owned enrichment field)
  - optional Scholar-owned enrichment fields (for example scholar citation/profile enrichments)
  - `buildVersion`
  - `buildAt`
  - `updatedAt`
  - per-source lineage references:
    - `scopusLineage`
    - `wosLineage`
    - `scholarLineage`
  - enrichment linkage lineage:
    - `linkerVersion`
    - `linkerRunId`
    - `linkedAt`

## Field Ownership Matrix (Locked)
- Scopus-owned core fields:
  - `eid`, Scopus bibliographic core (`title`, `coverDate`), `authorIds`, `affiliationIds`, `forumId`, citation graph base (`citedEid`, `citingEid`, `citedByCount`), Scopus funding keys.
- WoS-owned enrichment fields:
  - `wosId`, WoS-derived ranking/metric enrichments, WoS-specific classification enrichments.
- Scholar-owned enrichment fields:
  - Google Scholar profile/publication/citation enrichments when available.

Ownership rules:
- Scopus builders may update only Scopus-owned fields.
- WoS enrichment builders may update only WoS-owned fields.
- Scholar enrichment builders may update only Scholar-owned fields.
- Cross-source overwrite of non-owned fields is forbidden.

## Source-Policy Rules (Locked)
- Ingestion is authoritative for source-specific canonical facts.
- Admin/manual updates do not rewrite ingested lineage fields.
- Admin/manual updates are represented only as explicit override fields in canonical views/facts where needed (for example workflow approval flags), and must keep separate override lineage (`overrideBy`, `overrideAt`, `overrideReason`).
- WoS/Scholar enrichment writes (for example `wosId`) persist in enrichment-owned collections/projections (`scholardex.publication_view`) and must not rewrite Scopus-owned fact fields.
- If two ingestion events resolve to the same canonical identity key:
  - winner selection is deterministic by source policy and lineage ordering,
  - replaying identical event set must produce identical fact state.

## Identity, Replay, and Enrichment Rules
- Event identity fields are mandatory: `source`, `sourceRecordId`, `batchId`, `correlationId`, `payloadHash`.
- Facts must always carry event lineage references (at minimum `sourceEventId` plus source reference fields).
- Views must carry projection lineage (`buildVersion`, `buildAt`) and retain back-references to source/fact lineage.
- Replay policy:
  - replay of Scopus events rebuilds Scopus facts deterministically;
  - merged projection rebuild must preserve ownership boundaries and must not clobber WoS/Scholar-owned fields;
  - enrichment fields must either persist outside Scopus rebuild scope or be deterministically re-applied by linker/rebuilder runs using lineage-backed inputs.

## WoS Canonical Interop Amendment (2026-03-07)
- WoS canonical facts are split by responsibility:
  - score fact key: `journalId + year + metricType` (`WosMetricFact.value` + lineage).
  - category ranking fact key: `journalId + year + metricType + categoryNameCanonical + editionNormalized` (`wos.category_facts.quarter/quartileRank/rank` + lineage).
  - source availability:
    - official WoS JSON provides `rank` (category+edition rank),
    - government AIS/RIS files provide `quarter` (quartile rank is usually unavailable and remains null).
- WoS scoring/ranking projections are built by joining score facts with category ranking facts.
- `wos.category_facts` is canonical runtime storage for category ranking facts.

## H17.10 Linker and Merge Rules (Locked)
- Linker write authority:
  - `PublicationEnrichmentLinkerService` is the only runtime writer for WoS/Scholar-owned keys in `scholardex.publication_view`.
  - permitted linker-owned fields: `wosId`, `googleScholarId`, `wosLineage`, `scholarLineage`, `linkerVersion`, `linkerRunId`, `linkedAt`.
  - linker must not mutate Scopus-owned fields.
- Deterministic link key precedence:
  - `id` first;
  - then `eid`;
  - then exact `doiNormalized`.
- DOI matching:
  - DOI values are normalized before lookup (`https://doi.org/...` and `doi:` stripped, lowercase).
  - link resolution uses exact normalized DOI token match only.
- Conflict policy:
  - conflicts are quarantined in `scholardex.publication_link_conflicts`.
  - conflict conditions include key collision (`wosId`/`googleScholarId` already linked to another publication) and ambiguous DOI resolution.
  - conflict outcome is non-mutating for target publication rows (quarantine only, no reassignment).
- NON-WOS handling:
  - `Publication.NON_WOS_ID` is treated as sentinel/skip.
  - sentinel values are never persisted to `scholardex.publication_view.wosId`.
- Replay safety:
  - Scopus fact/view rebuild remains deterministic for Scopus-owned fields.
  - enrichment keys survive rebuild through ownership-safe preservation or deterministic linker reapplication using lineage metadata.
- Scholar scope in H17:
  - linker-facing Scholar interface is locked (`linkScholarEnrichment`).
  - Scholar ingestion pipeline rollout is outside this slice.

## Compatibility Rule
The canonical projections must preserve fields required by current read paths:
- publication title/date/authors/forum/citation linkage for admin/user/report flows;
- forum search/sort fields: `publicationName`, `issn`, `eIssn`, `aggregationType`;
- author search/sort fields: `id`, `name`, affiliations linkage;
- affiliation search/sort fields: `afid`, `name`, `city`, `country`.

Transition compatibility:
- runtime reads must resolve publication identity with:
  - primary lookup key: `id`
  - compatibility lookup keys: `eid`, `wosId`, `googleScholarId`
- all compatibility lookups must normalize internally to merged publication `id` before downstream read processing.

## H17.6 Operational Cutover Surface
- Canonical admin operational surface for big-bang initialization is `GET/POST /admin/initialization`.
- The initialization page exposes deterministic WoS and Scopus step execution:
  - WoS: ensure indexes, rebuild projections, run big-bang (`dryRun` or full run).
  - Scopus: ingest events, build facts, build projections/views, ensure indexes, run full big-bang.
- Rankings UI is read/browsing-focused and must not expose WoS/Scopus maintenance action controls.
- Big-bang actions are synchronous in admin flows and must return structured step summaries (processed/imported/updated/skipped/errors plus verification counts).

## H17.1 Completion Gate
- `docs/h17-scopus-canonical-contract.md` exists and is the contract source of truth.
- All six Scopus entity fact contracts (publications/citations/forums/authors/affiliations/funding) define:
  - required fields,
  - canonical identity key,
  - lineage fields,
  - idempotence/upsert key,
  - source-policy behavior.
- Cross-source ownership matrix and merged publication projection contract are explicitly locked.
- `wosId` is locked as WoS-owned enrichment (not Scopus-owned).

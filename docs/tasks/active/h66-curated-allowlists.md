# H66 Canonical Forum registry (multi-source identity + rankings + indexing)

**Status:** Planning — **data model locked 2026-06-16**, T0 done, ready to build A1
**Created:** 2026-06-16 · **Reshaped 2026-06-16** (curated allowlists → forum-first registry) ·
**Re-grounded 2026-06-16** against the actual pipeline code (the canonical forum + a standalone forum-import
path already exist; scope is narrower than the first reshape implied) · **Data model verified against the
live DB 2026-06-16** (edition + category + quartile are all year-dimensioned; forum/metric views never joined)
**Relates to:** [standards capability assessment](../../standards-capability-assessment.md)

## The rethink: forums first, resolve-or-enrich

Forum identity is the system's recurring failure point (the WoS ISSN-resolution bug that silently scored 39
forums 0; FEAA publisher matching; allowlist matching). Root cause is **not** that forums are second-class —
they aren't — but that **rankings/indexing are matched lazily at scoring time by fuzzy ISSN/name** instead
of being attributes the canonical forum already carries via a stable FK.

Fix at the root: seed the canonical forum from the authoritative venue lists *before/independently of*
publications, populate its multi-source id lists, and **attach rankings + indexing as forum attributes**.
Publication ingestion then **resolves-or-enriches** against the registry (resolved → link + blank-fill;
unmatched long-tail venue → thin forum to enrich later). NOT a strict "all forums before any publication"
gate — pubs/OpenAlex/DBLP will always add long-tail venues.

## What already exists (verified in code — do NOT rebuild)

The first reshape framed this as building a first-class forum and a new pipeline. The code already has both:

- **Canonical forum is first-class and multi-source.** `ScholardexForumFact` (`scholardex.forum_facts`)
  already carries `scopusForumIds[]`, `wosForumIds[]`, `googleScholarForumIds[]`, `userSourceForumIds[]`,
  `issn`/`eIssn`/`aliasIssns[]`, `name`/`nameNormalized`, `aggregationType`, `publisher`, review/moderation
  state, provenance. The multi-source identity model is **in place, just thinly populated.**
- **A standalone forum-import path already exists.** `ScopusImportEntityType.FORUM` is a real event type;
  `ScopusDataService.importUploadedPublisherCsvSync()` emits FORUM events (publisher CSV) processed by
  `ScopusFactBuilderService.processForumChunks()` — **no publication required.** New list sources feed this
  existing door, not new infrastructure.
- **Publication-path emission is already resolve-or-enrich.** `ScopusFactBuilderService.upsertForumFact`
  keys by `source_id` with a **blank-tolerant merge** (never erases learned attributes). Once a forum is
  pre-seeded, the publication event resolves by `source_id` and only fills blanks / links — it doesn't
  re-create. So we **keep** publication forum emission (long-tail venues need it); it just becomes
  near-idempotent linking when the registry is rich. **Nothing to remove.**
- **Canonicalization + dedup exist.** `UserDefinedCanonicalizationService.canonicalizeForums()` merges by
  ISSN token then `(name, aggregationType)`; `ScholardexForumDeduplicationService` union-finds by shared
  ISSN with `ForumMergeSafetyRule` quarantining unsafe merges. Reuse — extend match to the new id sources.
- **Projection exists.** `ScholardexProjectionBuilderService` → `reporting_read.scholardex_forum_view`
  (id, publication_name, issn, e_issn, isbn, aggregation_type, publisher, …).

## The actual gap

**WoS rankings/indexing are a sibling stream, not forum attributes.** `WosMetricFact` / `WosCategoryFact`
are keyed by `journalId` (`{journalId, year, metricType}`); WoS **never emits a forum fact** (`wosForumIds`
is populated only via the onboarding service). The forum↔metric link is resolved **at scoring time** by the
fuzzy ISSN/name path (`AbstractWoSForumScoringService` / the `getRankingsByForum` patch). That late fuzzy
join is the recurring bug.

**Confirmed structurally (2026-06-16):** the *only* `forum_id` column in all of `reporting_read` is on
`scholardex_publication_view`. The entire WoS side — `wos_metric_fact`, `wos_category_fact`, `wos_ranking_view`,
`wos_scoring_view` — is keyed by `journalId` with **no `forum_id`**. There is no stored FK between the
canonical forum and its metrics; the forum→journalId bridge exists *only* at query time. **We never built
forum-keyed metric/category views** — that absence *is* the resolution issue. Move B creates them.

## Scope — three concrete moves (not a pipeline rewrite)

**A. New list sources → the existing FORUM import path.** Each authoritative list emits forum source facts
that canonicalize into `scholardex.forum_facts` by ISSN/eISSN (primary) → normalized name (fallback),
retaining native ids:
  - **Scopus CiteScore** (`data/scopus/CiteScore 2023 per Nov 2024.csv`, 29,777 sources) → `scopusForumIds`
    via **Scopus Source ID** + ISSN/eISSN, title, publisher, ASJC subject, **type** (j/k/p/d). The clean FK
    for resolving Scopus pubs; fills what the publication-derived seed only partially populated.
  - **WoS MJL** (`data/wos/mjl/`, SCIE/SSCI/AHCI/ESCI, 24,123 journals) → `wosForumIds` + **index
    membership** + WoS categories + publisher.
  - **DOAJ** (`https://doaj.org/csv`) → open-access flag, ISSN.
  - **ERIH+** (Typesense `erihplus_tidsskrift_cache`, pinned) → ERIH id + disciplines + DOAJ flag.

**B. Build the forum-keyed metric/category views (the bug-killer).** The forum→metric bridge exists only at
query time today (fuzzy ISSN/name). Build it once at projection time instead: complete the `forum.wosForumIds`
FK (already ~79% populated), then add a **new projection step** that reads the **Mongo canonical facts
directly** (`wos.metric_facts`/`wos.category_facts`) through that FK and emits the year-true
`scholardex_forum_metric_view` + `scholardex_forum_category_view` (keyed by `forum_id`). `getRankingsByForum`
becomes a trivial FK read; the fuzzy resolver drops to a logged fallback. Sibling projection from the same
source facts — **no rewrite** of the existing journalId-keyed WoS projection (which stays for its own consumers).

**C. Publication path: confirm resolve-or-enrich.** Already true via `source_id` blank-tolerant merge.
Verify it links (not duplicates) once the registry is rich; ensure `ScholardexForumDeduplicationService` +
`ForumMergeSafetyRule` cover the new id sources (Scopus Source ID, WoS journalId, ERIH id).

## Model deltas — indexing is YEAR-DIMENSIONED, not a scalar (verified 2026-06-16)

Investigation killed the "membership is a per-forum scalar" assumption. WoS edition membership **changes over
time** — ESCI is Clarivate's evaluation/holding tier and journals get promoted to SCIE/SSCI/AHCI (and
demoted/de-listed). Evidence in our own data: **1,362 journals appear in >1 edition across years**
(`wos_category_fact`). The MJL files we hold are a single **2025 snapshot** (no year column) — they assert
*current* membership only and cannot answer "what edition in 2015". So a 2025-snapshot scalar would
mis-attribute today's SCIE status to old papers.

Not just edition — **WoS category also changes over years** (3,070 / 25,545 journals have a changing category
set; e.g. EVOLUTIONARY BIOLOGY through 2013 → + ZOOLOGY from 2014). And quartile is assigned **per category
per year**, so category drift moves the applicable quartile/zone. We hold the per-year truth in
`wos_category_fact(journal_id, year, edition_normalized, category_name_canonical, quartile_rank, rank)`
(SCIE/SSCI 1997–2024; **AHCI 2021+, ESCI 2023+** — JCR categorized those editions late; the MJL 2025 snapshot
is the current-only fallback for older AHCI/ESCI).

**Principle: time-resolution follows the data, not the attribute.** Store an attribute year-dimensioned iff
we hold a year-resolved source for it; otherwise current-snapshot with `as_of` (accept snapshot semantics,
upgrade later if a dated source arrives). Almost nothing classificatory is a true forum scalar.

- **A. Year-true (JCR `wos_metric_fact` + `wos_category_fact`)** → siblings keyed by `(forum_id, year)`:
  - `scholardex_forum_metric_view(forum_id, year, metric_type, value, source)` — AIS/IF/RIS.
  - `scholardex_forum_category_view(forum_id, year, edition, category, quartile, rank, source)` — edition +
    category + quartile travel together (re-key of `wos_category_fact`); answers "in SCIE in year Y, category
    C, quartile Q?".
- **B. Snapshot-only (MJL 2025, DOAJ, ERIH, CiteScore, CNCS)** → `scholardex_forum_membership_view(forum_id,
  database, member, as_of, source)` with `database ∈ {SCIE,SSCI,AHCI,ESCI(current),DOAJ,ERIH,SCOPUS,
  CNCS:<tier>}`; no `year` (current-only; MJL fills historical AHCI/ESCI as a flagged fallback).
- **C. True forum scalars (single snapshot, single-valued)** → on `ScholardexForumFact`: `type`
  (journal/book-series/conference, CiteScore), `asjc[]` (Scopus subject, snapshot), publisher, `erihIds[]` +
  the existing id lists. **`wosCategories[]` is NOT here** — it's year-true (the category view).

Expose to scoring/eligibility: "indexed in DB X **as of year Y** (or currently)", "indexed in ≥N of {set}",
"WoS quartile in year Y for category C", "CNCS tier" — plus AIS/IF/RIS by FK, not fuzzy match.

**Lookup semantics (resolved 2026-06-16) — three modes, all served by the year-keyed views:**
1. **Publication-year** (*most* standards, *„în anul publicării"*) → look up the category/metric view at
   `year = article.publicationYear`.
2. **Rolling N-year window** (*some* — "indexed/ranked in the past 5 years, regardless of publication date")
   → `EXISTS`/best over the category/metric view where `year ∈ [refYear−N+1, refYear]`. Needs a **reference
   year** (the evaluation/report year) as an indicator/report parameter — the one genuinely new input here.
3. **Current snapshot** → the membership view (`as_of` rows; MJL/DOAJ/ERIH).

This vindicates the year-true model: modes 1–2 are impossible against a flattened scalar. Per-indicator
selection of the mode (+ the reference year for mode 2) is config, wired per domain — ties to [H68].

## Sources (all in hand)

| source | native id → forum field | adds | form |
|---|---|---|---|
| **Scopus CiteScore** (`data/scopus/CiteScore 2023 per Nov 2024.csv`) | Scopus Source ID → `scopusForumIds` | ISSN/eISSN, title, publisher, ASJC, **type**, CiteScore/SNIP/SJR/quartile | CSV ✅ |
| **WoS MJL** (`data/wos/mjl/`) | ISSN → match (no journalId in MJL) | **current** edition membership + publisher → membership view (snapshot) | CSV ✅ |
| **WoS metrics/categories** (Mongo `wos.metric_facts`/`wos.category_facts`) | journalId → `wosForumIds` FK | **AIS/IF/RIS + edition + category + quartile, BY YEAR** (already ingested; today joined fuzzily) | in DB ✅ |
| **ERIH+** (Typesense `erihplus_tidsskrift_cache`) | ERIH id → `erihIds` | disciplines, DOAJ flag | API pinned ✅ |
| **DOAJ** (`https://doaj.org/csv`) | ISSN → match | open-access | CSV ✅ |
| **CNCSIS / SENSE / CORE** (DB) | ISSN/name → match | publisher tiers / book / conference rankings | in DB ✅ |

The CiteScore scores themselves aren't used by any domain (all standards use Clarivate AIS/IF) — its
**Source ID + ISSN/eISSN + ASJC + type** are the identity/classification backbone. CiteScore/SJR/SNIP/
quartile stored opportunistically.

## Pipeline (mapped to existing stages)

Each list source → forum source facts (reuse `scopus.forum_facts` for the Scopus-keyed CiteScore; new
`<source>.forum_facts` for MJL/DOAJ/ERIH) **via a FORUM-type import event** → existing canonical merge in
`scholardex.forum_facts` (identity + blank-tolerant attribute aggregation + provenance; extend match to new
id sources) → `reporting_read.scholardex_forum_view` (C-scalars) + the membership view (snapshot rows from
MJL/DOAJ/ERIH). Separately, move B adds a projection step from the WoS Mongo facts through `wosForumIds` →
the year-true metric + category views. Publication canonicalization resolves its venue against the registry
by Scopus Source ID → ISSN → name (already does the first/second via `source_id`/ISSN; near-idempotent once seeded).

## Open decisions

- Full FORUM-event pipeline vs lighter reference-import for the slow-moving list sources (reuse the existing
  publisher-CSV ingestion shape — it already emits FORUM events).
- ~~Rankings/indexing embedded vs sibling~~ **RESOLVED** → three forum-id-keyed sibling views (metric +
  category year-true, membership snapshot); time-resolution follows the data (see "Model deltas").
- Still-to-acquire: ERIH+ pull (fetchable), CNCS A/B/C tiers (UEFISCDI / transcribe from standards),
  domain-embedded prestige publisher lists (transcribe from `data/standards/`), vendor title lists for the
  "≥N DBs" predicate (EBSCO/ProQuest/JSTOR/MUSE) — add as needed by the next report.
- **Master Book List**: no longer downloadable (Clarivate redirect); only needed for physics A1–A6
  (deferred). Substitute with SENSE/CNCSIS/derived-from-MJL-publishers or admin-maintained.
- Sequencing: **(A)** seed the registry from the in-hand lists (CiteScore + WoS MJL + DOAJ), then **(B)** fold
  WoS metrics/indexing onto the forum by FK (retires the fuzzy resolver), then **(C)** verify pub
  resolve-or-enrich + dedup; finally ERIH+/CNCS/embedded lists.

## Implementation task series

Each task is independently shippable and testable. Numbering = intended order; dependencies noted. Every
task runs against the isolated test DBs from **T0** so prod `core` / `test` stay untouched.

### T0 — Isolated test environment (setup, no code) — ✅ DONE 2026-06-16 (recipe corrected 2026-06-16)
Decision: **start empty** (exercise the seed path, no copy). Recipe (reproducible):
- `core` role lacks CREATEDB → create as the local superuser, owned by `core`:
  `psql -U adispataru -d postgres -c "CREATE DATABASE core_h66 OWNER core;"`

⚠️ **CRITICAL Mongo-isolation gotcha (cost a prod write before it was caught):**
- This app is on **Spring Boot 4.0**, where the Mongo config prefix was renamed
  `spring.data.mongodb.*` → **`spring.mongodb.*`**. The old key is an inert legacy alias — it lands in the
  environment (actuator shows it) but binds to nothing, so the app silently falls back to the default db
  `test`. (This also means the repo's own `.env` `SPRING_DATA_MONGODB_URI=…/scholardex` is **dead** — prod
  actually runs on `test`, not `scholardex`. Worth fixing separately.)
- `--spring.data.mongodb.uri` / `SPRING_DATA_MONGODB_URI` overrides are therefore IGNORED → writes hit prod.
- **Use `spring.mongodb.uri` (the live key) AND a throwaway mongo on a separate port** so prod (27017) is
  physically unreachable even if a key is wrong. Prod Mongo is the Docker container `some-mongo` (`mongo:latest`):
  ```bash
  docker run -d --name h66-mongo-test -p 27018:27017 mongo:latest   # ephemeral, no volume
  ```
  Tear down with `docker rm -f h66-mongo-test` (discards all test data). Verify isolation after boot by
  checking that startup index reconciliation created collections in `27018/scholardex_h66` and prod
  `27017/test` is unchanged.
- **Empty-DB boot gotcha:** `PostgresReadCutoverGuard` (an unconditional `ApplicationRunner`) refuses to
  boot unless `reporting_read.projection_checkpoint` has `wos` + `scopus` rows. An empty DB has none, so
  seed two "empty projection complete" rows once (a real projection run later overwrites them):
  ```sql
  INSERT INTO reporting_read.projection_checkpoint
    (slice_name, source_fingerprint, last_run_id, last_success_at, last_mode)
  VALUES ('wos','empty-h66-bootstrap','h66-bootstrap',now(),'FULL'),
         ('scopus','empty-h66-bootstrap','h66-bootstrap',now(),'FULL')
  ON CONFLICT (slice_name) DO NOTHING;
  ```
- Boot (free port 8282; **`spring.mongodb.uri`** → throwaway 27018; Postgres → core_h66):
  ```bash
  JAVA_TOOL_OPTIONS=-Xmx6g ./gradlew bootRun --args='--spring.profiles.active=agent-dev \
    --server.port=8282 \
    --spring.mongodb.uri=mongodb://localhost:27018/scholardex_h66 \
    --spring.datasource.url=jdbc:postgresql://localhost:5432/core_h66'
  ```
- **Verified:** Flyway applied migrations to `reporting_read` on `core_h66`; with the corrected `spring.mongodb.uri`
  key the app connects to the isolated **27018** instance (startup created 45 collections there; prod
  `27017/test` unchanged). `/actuator/health` → 200. **Prod physically untouched.**

**A2.1 — Forum canonicalization perf (found via A2's bulk load). — ✅ DONE 2026-06-16 (index + read preloads)**
Bulk-loading 29,777 forums exposed `WosScholardexOnboardingService` canonicalization was **O(n²)** +
per-row DB round-trip heavy (**348s**). Fixed in stages, each byte-identical (imported=29,775, updated=2),
onboarding unit tests green, measured on the isolated 27018:

| stage | time |
|---|---|
| original | 348s |
| **CanonicalForumIndex** — incremental ISSN-token + name\|agg index replaces the O(n²) `findCanonicalCandidates` linear scan (both Scopus + WoS paths) | 70.5s (4.9×) |
| **conflict-resolve preload** — load OPEN forum-conflict keys once; per-row `resolveOpenForumAmbiguityConflict` skips its 2 DB reads unless a key matches (always-miss on rebuild) | 38.4s |
| **findByKey preload** — preload existing FORUM/SCOPUS source links keyed by recordId; per-row re-run-idempotency check is an in-memory lookup | 32.8s |
| **source-link write batch** — accumulate LINKED/CONFLICT commands, flush once via the existing `batchUpsertWithState(commands, preloadedByKey)` (writes are post-loop; nothing reads links mid-loop) | **17.6s (≈20×)** |

Verified at 17.6s: 29,775 canonical forums, 29,777 FORUM/SCOPUS source links all LINKED (written by the batch).
Remaining 17.6s is the **per-row forum `save`** (kept per-item by decision — batching it needs a saveAll +
per-item dup-fallback two-pass that restructures `persistForumOrRecordConflict`'s conflict-quarantine path,
highest risk for the smallest gain, ~4s). Pick it up only if 17.6s/rebuild still bites.

### Move A — seed the registry from the in-hand lists

**A1 — Forum model + projection deltas. — ✅ DONE 2026-06-16**
Shipped: `ScholardexForumFact` gained `erihIds[]`, `forumType`, `asjc[]`; `ScholardexForumView` gained
`forumType`, `asjc`; `toCanonicalForumView` + the forum insert/upsert SQL map them; migration
`V13__h66_1_forum_registry_views.sql` adds `forum_type`/`asjc` to `scholardex_forum_view` and creates the
three forum-id-keyed sibling tables (`scholardex_forum_metric_view`, `_category_view`, `_membership_view`)
with unique constraints + `forum_id` indexes, **no FK** (the forum view is rebuilt via a single TRUNCATE,
which Postgres rejects on any FK-referenced table). Verified: `ScholardexProjectionBuilderServiceTest` green
(24 tests, incl. new C-scalar + null-asjc cases); Flyway applied V13 on `core_h66` (38ms); all columns/tables/
indexes confirmed present; app boots clean. Tables empty until A2–A4/B2.

- **Do:** extend `ScholardexForumFact` with the **C-scalars** only — `erihIds[]`, `type`
  (journal/book-series/conference/trade), `asjc[]` (NOT `wosCategories[]` — that's year-true). Add a Flyway
  migration creating the three forum-id-keyed sibling tables (see "Model deltas"):
  `scholardex_forum_metric_view(forum_id, year, metric_type, value, source)`,
  `scholardex_forum_category_view(forum_id, year, edition, category, quartile, rank, source)` (year-true),
  and `scholardex_forum_membership_view(forum_id, database, member, as_of, source)` (snapshot-only). Add the
  C-scalar columns to `scholardex_forum_view` + extend `toCanonicalForumView`. Keep blank-tolerant merge.
- **Verify:** unit test the projection mapping; migration applies on `core_h66`; existing forum view
  unaffected (new columns nullable; new tables empty until A2–A4/B2).
- **Dep:** T0.

**A2 — Scopus CiteScore loader (the FK backbone). — ✅ DONE 2026-06-16**
Shipped: `ScopusForumFact` + `upsertForumFact` (hash + blank-tolerant merge + absorbed-gate) + the
Scopus→canonical `mergeForumFromScopus` fold now carry `forumType` + `asjc`; `ScopusDataService
.importCiteScoreCsvFromPath` (RFC-4180, group-by-source, ASJC union, j/k/p/d→type map, scores skipped);
admin endpoint `POST /admin/initialization/scopus/importCiteScore?path=&batchId=`. Tests green
(`ScopusDataServiceTest` grouping/union/type/RFC-4180 + missing-file; fixed `ScopusFactBuilderServiceTest`
forum-hash seeds + the controller-constructor test). **End-to-end verified** on the real file against the
isolated 27018: 71,609 rows → 29,777 FORUM events → **29,777 `ScopusForumFact`, all with `forumType` +
non-empty `asjc`** (journal 28,243 / book-series 1,143 / conference 221 / trade 169 / 1 unmapped); ASJC union
confirmed (source 29348 "Energy" = 12 codes). Prod `test` physically untouched. Canonical-level fold
(`mergeForumFromScopus`) is unit-covered; the full onboarding→`ScholardexForumFact` run is exercised by B/C.

Decisions (2026-06-16): **reuse the FORUM-event pipeline** (events → source facts → canonical, full
provenance/idempotency); **admin path-based import** (file is ~7MB and gitignored — not classpath-seedable
like the 50-row FEAA list, and too big for upload UX); **skip scores** (CiteScore/SNIP/SJR/quartile unused by
any domain; load identity + classification only). Findings: CiteScore is **one row per (source × ASJC
sub-subject)** → 71,609 rows = 29,777 sources; group by Source ID, **union the ASJC sub-subject codes** into
`asjc[]`; journal-level fields (Source ID, ISSN/eISSN, Title, Publisher, Type) are constant across a source's
rows. CSV has **quoted fields with embedded commas** → needs an RFC-4180 parser, not a split.
- **Do:**
  1. Extend `ScopusForumFact` (source fact) with `forumType` + `asjc`; extend `upsertForumFact` to read them
     from the FORUM payload; extend the Scopus→canonical mapping to carry them onto `ScholardexForumFact`
     (the `forumType`/`asjc`/`erihIds` added in A1). **Reused by A3.**
  2. Add an admin path-based import (mirror the publisher-CSV ingestion shape in `ScopusDataService`, but
     read a configured filesystem path). Group rows by Source ID; emit one `ScopusImportEntityType.FORUM`
     event per source with `{source_id, publicationName=title, issn, eIssn, publisher, forumType, asjc[]}`.
     Type map: `j`→journal, `k`→book-series, `p`→conference, `d`→trade.
  3. Canonicalize by ISSN/eISSN → name (into the empty registry → all-new canonical forums; A3 does the
     first merges).
- **Verify:** load count = 29,777 distinct canonical forums; a multi-area journal (e.g. Source 12091) carries
  one forum with all its ASJC sub-subject codes unioned + Type + ISSN; re-run idempotent (source_id unique).
- **Dep:** A1.

**A3 — WoS MJL loader (current edition coverage). — DESIGN LOCKED 2026-06-16**
Design (after code inspection): **MJL is a WoS source**, not a bespoke stream. It rides the existing WoS
pipeline — `WosImportEvent` → `WosFactBuilderService` (identity resolution mints journalIds from ISSN) →
`wos.journal_identity` → the **existing `upsertForumFromWos` onboarding** turns identities into forums
(verified: onboarding does `journalIdentityRepository.findAll()`, no metric filter, so MJL-only journals
with no AIS/IF still become forums). So **no `MjlForumFact`, no `upsertForumFromMjl`.** The only genuinely-new
fact is **`wos.coverage_facts(journalId, year, edition, category, source)`** — edition coverage doesn't fit
`wos.category_facts` (its `metricType` is NOT NULL + in the unique key, and the scoring read switches
exhaustively on metricType — adding a `MetricType.MJL` would break those switches + need a Postgres enum
migration; rejected). Coverage stays isolated; **unification happens at B2 projection** (B2 projects
`category_facts` JCR year-true 1997–2024 **and** `coverage_facts` MJL 2025 into the same forum
category/membership view by `wosForumIds`), leaving the JCR scoring path untouched.

Sub-steps:
- **A3.1 — ✅ DONE 2026-06-16.** `WosSourceType.MJL_COVERAGE`; `WosImportEventIngestionService.ingestMjlDirectory`
  reads the 4 edition CSVs (skips `JCR 2025.csv` via `editionFromMjlFileName`), emits `WosImportEvent`s via the
  existing `processEventFast` supersede core, `sourceFile=<edition CSV>`, `sourceRowItem=<issn-or-eissn>|<edition>`,
  payloadFormat `mjl-csv-row`, payload `{edition, title, issn, eIssn, publisher, categories}`; admin endpoint
  `POST /admin/initialization/wos/importMjl?dir=data/wos/mjl&sourceVersion=2025`. Unit test green
  (`ingestMjlDirectoryImportsEditionEventsAndSkipsMatrix` — incl. eISSN-only keying + JCR-matrix skip).
  **Verified e2e on isolated 27018: 24,123 events** (SCIE 9430 / SSCI 3538 / AHCI 1799 / ESCI 9356, exact),
  payload correct, prod untouched.
- **A3.2 — ✅ DONE 2026-06-16.** `MjlImportEventParser` (supports `MJL_COVERAGE` + `mjl-csv-row`; splits the
  pipe-separated WoS-categories column → one record per category, `metricType=null`, year = sourceVersion);
  new `WosCoverageFact` (`wos.coverage_facts`, unique `(journalId, year, edition, category)`) + repo;
  `WosFactBuilderService` routes MJL records around the metric-source policy gate → `resolveJournalId`
  (mints/finds journalId by ISSN) → `wos.journal_identity`, then `upsertCoverageFact` → `wos.coverage_facts`
  (batched saveAll). Unit tests green (`MjlImportEventParserTest`; existing builder + ingestion suites);
  fixed the 3 builder-constructor sites for the new repo dep. **Verified e2e (isolated 27018):** 24,123 MJL
  events → **22,974 `wos.journal_identity`** (deduped across editions) + **33,193 `wos.coverage_facts`**
  (SCIE 15,020 / ESCI 11,100 / SSCI 5,035 / AHCI 2,038; categories split), sample correct, prod untouched.
- **A3.3 — ✅ CONFIRMED 2026-06-16 (no new code).** The same e2e run shows the *existing* `upsertForumFromWos`
  onboarding canonicalized all **22,974** MJL identities into forums (each with a `wosForumId`), with **zero
  metrics present** — proving MJL-only journals become forums through the existing path. (In a real A2→A3 run,
  MJL journals matching CiteScore forums by ISSN merge instead of creating thin forums.)
- **A3 perf — ✅ DONE 2026-06-16.** A3.3 surfaced that the A2.1 onboarding batching (Opt 2 findByKey preload +
  Opt 4 source-link batch) had been applied only to the *Scopus* path, not the symmetric WoS path that A3.3
  rides. Applied the same to `runWosOnboarding`/`upsertForumFromWos`: preload FORUM/WOS source links, accumulate
  LINKED/CONFLICT `linkCommands`, one `batchUpsertWithState` flush. Byte-identical (22,974 journal_identity /
  33,193 coverage / 22,974 forums); onboarding unit tests green (same conversions as the Scopus tests). buildFacts
  **~40s → ~28s** (onboarding ~25s → ~18s). Residual ~18s is the **per-item forum save** — deliberately left
  un-batched on both paths (the forum-save dup-fallback restructure was declined as highest-risk/smallest-gain).
  Fact-build (~10s) is per-journal identity creates (~unavoidable). Both onboarding paths now at the same tier.
- **A3.4** — B2-style projection of `coverage_facts` → `membership_view` by `wosForumIds`.
- **Decisions locked:** dedicated loader (MJL CSV ≠ the Excel/JSON directory scanner); extend the builder
  (reuse identity resolution); coverage_facts **includes category**; one event/coverage row per (journal,
  edition); `sourceVersion`/`asOf` = "2025" (loader param). MJL-only journals → new identities → new forums.
- **Dep:** A1; A2 recommended first (CiteScore forums as ISSN match targets).

**A4 — DOAJ loader (open-access flag).**
- **Do:** fetch/parse DOAJ CSV → set the DOAJ indexing flag on matching forums by ISSN.
- **Verify:** known OA journal flagged; non-OA unaffected.
- **Dep:** A1.

### Move B — fold rankings + indexing onto the forum (the bug-killer)

**B1 — Complete + harden the WoS-journalId ↔ forum link.**
- **Do:** `forum.wosForumIds` is already ~79% populated (25,862/32,714 canonical forums) via the onboarding
  service — complete it (A3's MJL ISSN matching closes most of the remainder; the rest are non-WoS venues
  with no metrics anyway) and make it authoritative/deduped. This is the FK the forum metric/category views
  join through.
- **Verify:** report `wosForumIds` coverage before/after; the unlinked remainder is dominated by non-WoS
  venues (sanity-check a sample).
- **Dep:** A3.

  Note: the journalId-keyed Postgres views (`wos_metric_fact`, `wos_category_fact`, `wos_ranking_view`,
  `wos_scoring_view`) **stay** — they back WoS-identity tooling (`AdminCatalogFacade`,
  `WosForumResolutionService`, `WosScholardexOnboardingService`, `WosParityReconciliationService`, the WoS
  read ports) and the transitional fuzzy fallback. The forum views are an *addition*, not a replacement.

**A3/B perf — `findScopusCandidates` O(n²) fix. — ✅ DONE 2026-06-17.** The full A2→A3 rebuild's wos/buildFacts
ran ~16 min in onboarding because `mergeForum` → `findScopusCandidates` linear-scanned all 29,777 scopus
forums per WoS journal (≈26.9k×29.8k ≈ 800M comparisons) — the same O(n²) class as the original
`findCanonicalCandidates`, but in a sibling method, invisible until CiteScore+MJL ran together (MJL-only tests
had an empty scopus-forum list). Fixed with a `ScopusForumIndex` (ISSN-token + name|agg, mirrors
`CanonicalForumIndex`), threaded `runWosOnboarding → upsertForumFromWos → mergeForum`. Byte-identical by
construction (same candidate set; only `size()==1` affects merge). **wos/buildFacts (CiteScore+MJL) ~16 min →
~27s.** Onboarding tests green (reflective white-box tests updated to build the index). Aside: the earlier
"17,531 merged" merge-check number was a *partial* mid-onboarding snapshot; the complete merge is 19,850.
(NOTE: the separate ~20-min full-rebuild figure was also inflated by laptop sleep — HikariPool clock-leap
warnings — and the ~6.5-min event re-parse, which the incremental/checkpoint path avoids.)

**B2 routing (settled 2026-06-16):** place the projection in `ScholardexProjectionBuilderService.rebuildViews`
(it already iterates canonical forums; by projection time onboarding has set `wosForumIds`; reads WoS Mongo
facts via injected repos). Build `Map<journalId → forumId>` from `wosForumIds`, then:
`wos.metric_facts → scholardex_forum_metric_view` (AIS/IF/RIS; collapse **max** per (forum,year,metric) —
only 9 prod forums carry >1 wosForumId so this is a non-issue); `wos.category_facts → scholardex_forum_category_view`
(JCR, year-true, has metricType + quartile); `wos.coverage_facts → scholardex_forum_membership_view`
(`database=<edition>`, `member=true`, `as_of=year`, `source=MJL`, deduped to (forum, edition) — MJL category
deferred). NOTE: coverage goes to membership, NOT category — `forum_category_view.metric_type` is NOT NULL
(mirrors `wos_category_fact`), and MJL coverage is metric-less; this is the original A/B/C snapshot split.
Verified prerequisite: a real A2→A3 run merges by ISSN — 17,531 forums carry both scopus + wos ids (only 9
forums have >1 wosForumId). Tested by **rebuilding from files** (`data/loaded` AIS/IF + `data/wos-json-1997-2019`
official JSON) so real `wos.metric_facts` exist in the isolated DB.

**B2 — Project year-true metrics + edition/category + MJL coverage keyed by forum id. — ✅ DONE 2026-06-16 (incl. A3.4)**
Shipped in `ScholardexProjectionBuilderService.rebuildViews`: inject the 3 WoS fact repos; build
`Map<journalId → forumId>` from `wosForumIds`; project `wos.metric_facts → scholardex_forum_metric_view`
(collapse max), `wos.category_facts → scholardex_forum_category_view` (JCR, year-true), `wos.coverage_facts →
scholardex_forum_membership_view` (MJL edition, deduped to (forum,edition), source=MJL) — **A3.4 folded in**;
all three added to the TRUNCATE + batched inserts. Updated 19 test construction sites + the integration test.
Projection-builder unit tests green. **Verified e2e by rebuild-from-files** (CiteScore + MJL + the full
`data/loaded` AIS/IF + official JSON → 596,601 metric / 797,827 category / 33,193 coverage facts): projection
(~75s) produced **596,211 `forum_metric_view`** rows (AIS 291,937 / IF 209,257 / RIS 95,017), **797,456
`forum_category_view`**, **24,112 `forum_membership_view`**. **Bug-killer spot-check:** the "Energy" forum
(merges Scopus 29348 + a WoS journalId) now carries its AIS by `forum_id` (2024=1.377 … 2020=1.19) via the FK
join — exactly what the fuzzy resolver computed at query time. Prod untouched (V13 + B2 only on `core_h66`).

- **Do:** add a **new projection step** reading the **Mongo canonical facts directly** (`wos.metric_facts`,
  `wos.category_facts`) joined through `forum.wosForumIds` — NOT routed through the journalId Postgres views
  (sibling projections from the same source of truth). Emit `scholardex_forum_metric_view` (AIS/IF/RIS-by-year)
  and `scholardex_forum_category_view` (edition+category+quartile+rank-by-year; SCIE/SSCI 1997–2024, AHCI
  2021+, ESCI 2023+), keyed by **forum id**. (Snapshot membership from MJL/DOAJ/ERIH lands in
  `scholardex_forum_membership_view` via A3/A4.) No rewrite of the existing WoS projection.
- **Verify:** projected AIS/IF matches `wos_metric_fact` for a sample forum; a known edition-changer (of the
  1,362) shows correct per-year edition; a category-changer (of the 3,070) shows correct per-year category —
  not a flattened current value.
- **Dep:** B1.

**B3 — Rewire scoring lookup to FK; retire the fuzzy resolver. — DONE**
- **Do:** change `getRankingsByForum` (and `AbstractWoSForumScoringService`) to read rankings by the forum's
  FK instead of fuzzy ISSN/name. Keep the fuzzy path only as a fallback for not-yet-seeded forums (log when
  used, so we can watch it trend to zero).
- **Verify:** the 39-forums-scored-0 regression case now scores correctly via FK; existing scoring tests
  pass; a recompute on copied data matches or improves prior scores (no silent regressions).
- **Dep:** B2.
- **Done (2026-06-17):**
  - `PostgresReportingLookupFacade.getRankingsByForum` now tries `loadRankingsByForumId(forum)` first —
    reads `scholardex_forum_metric_view` + `scholardex_forum_category_view` (category filtered to
    `edition IN ('SCIE','SSCI')` for parity) by `forum.getId()`, feeds the existing `toLegacyRanking`,
    memoized under `rankingsByForumId`. Falls back to the legacy ISSN then name (`resolveJournalId`)
    resolution, both logged at `debug` so the fuzzy path is observable and trends to zero.
  - `CNFISScoringService2025` was the one scorer still calling `getRankingsByIssn` directly with a forum in
    hand → routed through `getRankingsByForum(forum)` (the other scorers already go via
    `AbstractForumScoringService.getRankingsForForum`).
  - Test contract: scoring unit tests stubbed only `getRankingsByIssn`, but scoring resolves via the
    `getRankingsByForum` interface default (which a Mockito `@Mock` does not run) — the forum-scoring suite
    was committed red. Added `testsupport/ReportingLookupTestSupport.delegateForumLookupToIssn(port)` and
    wired it into the 7 scoring tests so the mock mirrors the default (issn→e-issn, blank-skipping). Suite
    now green (191 reporting+lookup tests), incl. the frozen-baseline `ComputerScienceScoringPipelineParityTest`.
  - Live FK read verified against `core_h66` (B2 projection): "Energy" forum AIS by `forum_id` matches the
    B2 baseline (2024=1.377 … 2020=1.19); category view returns SCIE rows with quartiles.
  - **Follow-up (not B3-critical):** `UserReportFacade.resolveWosJournalId`/`buildForumWosLinkMap` still does
    fuzzy ISSN→journalId for the display-only `forumWosLinkMap` attr (no template consumes it; the view has no
    `wosForumIds` column). Wiring the stored FK there needs projecting `wos_forum_ids` into
    `scholardex_forum_view` — defer to C2 reconcile.

### Move C — publication path + dedup hardening

**C1 — Confirm resolve-or-enrich + extend dedup to new id sources.** — **part 1 DONE; part 2 deferred to A5.**
- **Do (part 1, DONE):** confirmed `upsertForumFact` links (not duplicates) when a seeded forum already
  matches by `source_id` — `ScopusFactBuilderService` resolves via `state.forumBySourceId`
  (`findBySourceIdIn`) → reuse + blank-tolerant merge; WoS path links via the source-link /
  `canonicalIdByScopusForumId` map. Added `ScopusFactBuilderServiceTest
  .buildFactsFromImportEventsResolveOrEnrichDoesNotDuplicateSeededForumAndPreservesAttributes` (publication
  whose venue is already seeded → one forum saved, eIssn enriched, all other CiteScore attrs preserved).
  Canonicalization-path link is already covered by `WosScholardexOnboardingServiceTest
  .runScopusForumCanonicalizationOnlyLinksScopusForumAlreadyFoldedIntoCanonical`.
- **Part 2 deferred to A5 (extend dedup keys):** `ScholardexForumFact` carries **unique partial indexes on
  `scopusForumIds` and `wosForumIds`** (`uniq_scholardex_forum_scopus_id` / `_wos_id`), so two canonical
  forums physically cannot share a Scopus/WoS id — the collision is resolved at *write* time
  (`DuplicateKeyException` → conflict opened in `WosScholardexOnboardingService`), not at dedup time.
  Therefore adding scopus/wos id to the dedup clustering/safe-merge keys finds **zero new clusters today**;
  the only un-indexed id lists are `googleScholarForumIds` (never onboarded) and `erihIds` (not populated
  until **A5**). Building it now would be dormant code — fold the safe-merge-by-external-id rule into A5
  when `erihIds` lands and actually needs it.
- **Dep:** A2/A3 (part 1 done); part 2 → A5.

**C2 — Backfill/reconcile existing forums against the seeded registry.**
- **Do:** one-time reconcile of pre-existing (publication-derived) forums into the seeded registry by ISSN/
  Source ID; re-point publication links to the canonical winner.
- **Verify:** count of forums before/after; no orphaned publication→forum links; rankings now resolve by FK
  for previously-fuzzy cases.
- **Dep:** B3, C1.

### Deferred (after the in-hand lists prove out)
- **A5 — ERIH+ Typesense fetcher** → `erihIds` + disciplines (pinned endpoint; needs the pull). **Folds in
  C1 part 2:** once `erihIds` is populated, extend `ScholardexForumDeduplicationService` clustering +
  `ForumMergeSafetyRule` to treat a shared external id (erih, and gs if onboarded) as a safe-merge key —
  scopus/wos are already write-time-unique so they need no dedup-key handling.
- CNCS A/B/C tiers (transcribe/UEFISCDI), embedded prestige publisher lists (`data/standards/`), vendor
  title-lists for the "≥N DBs" predicate — pulled in by the first non-STEM report that needs them.

## Consumers

The forum-resolution fix (`getRankingsByForum` fuzzy path) becomes obsolete (clean FK). Unblocks the
"indexed in WoS/ERIH/Scopus/≥N-DBs", quartile, and CNCS-tier predicates across humanities/social/law/arts +
STEM; sharpens FEAA/physics book scoring. Sibling to [H67](h67-h-index.md), [H68](h68-criteria-extensions.md),
[H64](h64-canonical-projects.md).

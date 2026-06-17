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

| source | native id → field | role | form |
|---|---|---|---|
| **Scopus Source List** (`data/scopus/ext_list_May_2026.xlsx`) | Sourcerecord ID → `scopusForumIds` | **serial forum backbone** — 48,580 serials + 1,019 conf-proc; ISSN/EISSN, type, publisher, ASJC, active | XLSX ✅ (Move D / A6) |
| **Scopus Book List** (`data/scopus/Scopus_Books_list_February.xlsx`) | Scopus book ID / ISBN → `bookId` | **book registry** — 475,453 books; ISBN, publisher, ASJC, year (separate `book_facts`, not forums) | XLSX ✅ (Move E) |
| **Scopus CiteScore** (`data/scopus/CiteScore 2023 per Nov 2024.csv`) | Scopus Source ID → forum metrics | **rankings layer** (like WoS metrics) — CiteScore/SNIP/SJR/quartile; does NOT create forums | CSV ✅ |
| **WoS MJL** (`data/wos/mjl/`) | ISSN → match (no journalId in MJL) | **current** edition membership + publisher → membership view (snapshot) | CSV ✅ |
| **WoS metrics/categories** (Mongo `wos.metric_facts`/`wos.category_facts`) | journalId → `wosForumIds` FK | **AIS/IF/RIS + edition + category + quartile, BY YEAR** | in DB ✅ |
| **ERIH+** (Typesense `erihplus_tidsskrift_cache`) | ERIH id → `erihIds` | disciplines, DOAJ flag | API pinned ✅ |
| **DOAJ** (`https://doaj.org/csv`) | ISSN → match | open-access membership | CSV ✅ |
| **CNCSIS / SENSE / CORE** (DB) | ISSN/name → match | publisher tiers / book / conference rankings | in DB ✅ |

**Coverage vs metrics (refined 2026-06-17):** the **Scopus Source List** (`ext_list`) is the authoritative
*coverage* backbone that creates serial forums; **CiteScore** is a *metrics* subset (29,777 scored vs
48,580 indexed) repositioned as a rankings layer keyed by Source ID — it no longer defines forum identity.
The **Book List** populates a separate `book_facts` registry (Move E), not forums.

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

**A4 — DOAJ loader (open-access flag).** — **DONE.**
- **Design (match-only, ISSN-resolved at projection):** DOAJ carries **no native forum id** (only ISSN),
  so unlike CiteScore/MJL it does **not** ride the FORUM-event→canonical-merge pipeline — that would mint
  ~20k bare ISSN-only forums for OA journals absent from Scopus/WoS. Instead it is reference data:
  `DoajDataService.importDoajCsvFromPath` parses the DOAJ CSV → `doaj.journal_facts` (one fact per journal,
  keyed by normalized ISSN; blank-tolerant upsert). `doaj.journal_facts` is **owned but intentionally
  outside `MANAGED_DERIVED_COLLECTIONS`** (persists across a full rebuild — it is an external snapshot, not
  replayed from source; re-import to refresh). Admin endpoint `POST /admin/initialization/forum/importDoaj?
  path=&asOf=`.
- **Projection:** `ScholardexProjectionBuilderService.buildDoajMembershipRows` matches DOAJ ISSNs to forums
  by normalized ISSN token (issn/eIssn/aliases; collision → smallest forum id, deterministic) and emits
  `scholardex_forum_membership_view(forum_id, database='DOAJ', member=true, as_of, source='DOAJ')`. No new
  constructor dep — reads via the already-injected `MongoTemplate`. No migration (membership view exists).
- **Verify:** `DoajDataServiceTest` (ISSN normalize, no-ISSN rows skipped, blank-tolerant upsert preserves
  createdAt) + `ScholardexProjectionBuilderServiceTest.buildDoajMembershipRowsMatchesForumsByIssnAndIsMatchOnly`
  (matches by print+e ISSN, unmatched DOAJ journals create no forum). DOAJ CSV (24MB, ~20k journals) placed
  at `data/doaj/` (Cloudflare blocks scripted fetch — download via browser).
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
- **Part 2 DONE in A5 (extend dedup keys):** the erihId safe-merge key + `erih:`-token clustering landed
  with A5 (see the A5 entry). Original rationale retained below. `ScholardexForumFact` carries **unique partial indexes on
  `scopusForumIds` and `wosForumIds`** (`uniq_scholardex_forum_scopus_id` / `_wos_id`), so two canonical
  forums physically cannot share a Scopus/WoS id — the collision is resolved at *write* time
  (`DuplicateKeyException` → conflict opened in `WosScholardexOnboardingService`), not at dedup time.
  Therefore adding scopus/wos id to the dedup clustering/safe-merge keys finds **zero new clusters today**;
  the only un-indexed id lists are `googleScholarForumIds` (never onboarded) and `erihIds` (not populated
  until **A5**). Building it now would be dormant code — fold the safe-merge-by-external-id rule into A5
  when `erihIds` lands and actually needs it.
- **Dep:** A2/A3 (part 1 done); part 2 → A5.

**C2 — Backfill/reconcile existing forums against the seeded registry.** — **decided: one-time full
rebuild + report-only residual.**
- **Decision (the reframe):** the canonical build is *deterministic and auto-reconciles* on a full rebuild —
  a paper's venue and its CiteScore entry share the same Scopus Source ID, so they canonicalize to ONE
  forum (there is no separate "publication-derived forum" to merge); dedup-by-ISSN + the WoS↔Scopus fold
  already run inside `buildFacts`. So C2 is **not new merge code** — it is a guarded one-time full rebuild
  on prod (`reset canonical checkpoints → runFull → buildProjections`) plus verification, with the
  un-reconcilable residual surfaced as conflicts (report-only), never silently fuzzy-merged.
- **C2.1 — migration runbook + count snapshot** — **DONE.** Added the "H66 release — one-time
  forum-registry rebuild (migration)" section to [docs/rebuild-runbook.md](../../rebuild-runbook.md): ordered
  steps (snapshot before → ingest CiteScore `/scopus/importCiteScore` + MJL `/wos/importMjl` → reset
  checkpoints → full `PipelineRebuildService` rebuild → `buildProjections` → snapshot after) plus the C2.2
  verification gate (block release unless `healthy:true` / `orphanedPublicationForumLinks:0`; record
  `forumsTotal`). No new merge code — reconcile = the deterministic full rebuild.
- **C2.2 — reconcile verification audit** — **DONE.** `ForumReconcileAuditService` + read-only admin
  endpoint `GET /admin/initialization/forum/reconcileAudit` → `ForumReconcileAuditReport`: forum
  id-composition (scopus-only / wos-only / both / none), **zero orphaned** publication→forum links
  (`healthy` gate), and FK coverage of the B2/B3 forum-keyed views (distinct forum_id in
  metric/category/membership views; WoS-linked forums resolving metrics by FK). Postgres SQL verified live
  vs `core_h66` (metric=25,637 / category=25,314 / membership=22,963); Mongo composition/orphan logic
  unit-tested in `ForumReconcileAuditServiceTest`. Live full run is a deploy-time activity.
- **C2.3 — residual report → conflicts** — **DONE (closed as confirmation; no new code).** The onboarding
  fold matches on ISSN-token OR **exact** normalized-name+agg; `ForumMergeSafetyRule.namesMatch`
  (abbreviation/expansion) is only a *safety gate* on an already-found ISSN candidate, never a detector. The
  existing machinery already opens `/admin/conflicts` entries for every residual with a reliable signal:
  ambiguous ISSN/name (`REASON_AMBIGUOUS_ISSN` / `REASON_AMBIGUOUS_NAME_AGG`), cross-journal eISSN bridge
  (`REASON_FORUM_CROSS_JOURNAL_ISSN`, both Scopus + WoS paths), and dedup ISSN-cluster name-mismatch
  (`FORUM_DEDUP_NAME_MISMATCH`). The **only** un-surfaced residual is same-journal forums with *disjoint*
  ISSNs and non-exact names (`candidates.isEmpty()` → new forum, silent). Detecting it requires fuzzy name
  matching — exactly what H66 retired — and `namesMatch` is an abbreviation-prefix matcher noisy enough to
  conflate distinct journals ("J. Phys." ⇒ both *Physics* and *Physiology*). Decision: do **not** build a
  noisy report-only detector; leave this residual to organic discovery (a wrong score is reported → manual
  merge via the existing conflict-resolution UI).
- **Verify:** count of forums before/after; no orphaned publication→forum links (C2.2 `healthy`); rankings
  now resolve by FK for previously-fuzzy cases (C2.2 `wosLinkedResolvingMetricsByFk`).
- **Dep:** B3, C1 (both done).

**A5 — ERIH+ loader (erihIds FK + membership + DOAJ flag).** — **DONE (incl. C1 part 2).**
- **Data:** ERIH PLUS Typesense (`erihplus_tidsskrift_cache` @ `5tv6sfnzrjemi3h2p.a1.typesense.net`, public
  search-only key) — 12,768 journals pulled to `data/erih/erihplus.jsonl` (all carry ≥1 ISSN; 5,012 OA).
- **A5.1 ingest:** `ErihJournalFact` (`erih.journal_facts`, reference data, outside
  `MANAGED_DERIVED_COLLECTIONS`) + `ErihDataService.importErihJsonlFromPath` (erihId, normalized ISSNs,
  title, `discipline_ids`, `oa_doaj`). Admin `POST /forum/importErih`.
- **A5.2 onboarding (full erihIds population):** `ErihOnboardingService.onboardErih` matches ERIH journals
  to **existing** forums by ISSN and writes `erihIds` (match-only — never mints forums; ERIH-only-journal
  forum creation is a separate registry-expansion decision). Admin `POST /forum/onboardErih`.
- **A5.3 / C1 part 2 DONE:** `ScholardexForumDeduplicationService` now clusters by ISSN **+ `erih:` token**
  and folds `erihIds` on merge; `ForumMergeSafetyRule.isSafeToMergeCluster` treats a shared erihId as a
  definitive same-journal safe-merge key. Standalone `POST /forum/dedup` so the pass can run after
  onboarding. (scopus/wos stay write-time-unique, so no dedup-key handling needed for them — A5 only adds
  erih.)
- **A5.4 projection:** `buildErihMembershipRows` emits `membership_view database='ERIH'` from the stored
  `erihIds`, plus `database='DOAJ', source='ERIH'` for OA-flagged forums (coexists with A4's `source='DOAJ'`).
- **Verify:** `ErihDataServiceTest`, `ErihOnboardingServiceTest` (ISSN match, split-journal double-tag,
  no-match), `ForumMergeSafetyRuleTest` + `ScholardexForumDeduplicationServiceTest` (shared-erihId
  safe-merge), projection test (ERIH + DOAJ-from-ERIH). Live ingest is a deploy step.
- **Workflow integration (not gated on a consumer):** `ScopusBigBangMigrationService.runFull` (driven by
  `PipelineRebuildService`, the deploy path) now runs `onboardErih()` + a conditional erih-dedup pass right
  before its final projection — so a single full rebuild produces the complete registry (erihIds + ERIH/DOAJ
  membership) automatically. `importErih`/`importDoaj` are the only manual pre-rebuild steps (reference data,
  persists across the wipe). The standalone `/forum/onboardErih` + `/forum/dedup` endpoints remain for the
  step-wise admin path and re-runs.

### Move D — forums-first serial registry from the Scopus Source List

**Why (proven by the 2026-06-17 live multi-source rebuild on `scholardex_h66`/`core_h66`).** Two findings:
1. The current pipeline derives a `ScopusForumFact` from *every* publication venue (`upsertForumFact` at
   the publication path, `ScopusFactBuilderService:501`), so publications **define and mutate** forums —
   inverting H66's "forums first" thesis. Measured cost: the full `PipelineRebuildService` rebuild re-ingests
   only Scopus JSON + WoS (not CiteScore/MJL) and **wipes** their ledgers, forcing a manual bolt-on + a
   second `buildFacts`; folding CiteScore after publications added ~7,490 forums and produced **421
   `FORUM_EXTERNAL_ID_ALREADY_LINKED`** conflicts (of 448) — almost all churn; and `/wos/buildFacts` left
   `wos.coverage_facts=0` (no MJL membership).
2. **CiteScore is not Scopus coverage — it's a metrics *subset*** (29,777 scored sources; 5,149 publication
   venues were absent from it). The authoritative coverage list is the **Scopus Source List**
   (`data/scopus/ext_list_May_2026.xlsx`): sheet *Scopus Sources* = **48,580** serials + *Serial Conf. Proc.
   with Profile* = **1,019**, keyed by `Sourcerecord ID` with `ISSN`/`EISSN`/`Source Type`/`Active`/
   `Publisher`/`ASJC`. It carries the e-ISSNs publications were gap-filling (754 cases), so seeding from it
   makes strict link-only viable.

**Target model:** the **serial** forum registry (journals / conf-series / book-series) is built from the
Scopus Source List + WoS journal identity + MJL + ERIH + DOAJ + user-defined. **CiteScore is repositioned as
a rankings/metrics source** (like WoS metrics — attached to forums by Source ID, never creating them).
Publications **resolve-and-link** only. Books move to their own registry (Move E).

- **D1 — A6: Scopus Source List loader.** — **DONE (loader + endpoint + tests).**
  `ScopusDataService.importSourceListXlsxFromPath` reads `ext_list_*.xlsx` (POI), emitting FORUM events keyed
  by `Sourcerecord ID` from the *Scopus Sources* sheet (45,143 Journal / 2,644 Book Series / 793 Trade) +
  *Serial Conf. Proc.* sheet (→ forumType `conference`) into the existing FORUM-event pipeline →
  `scopus.forum_facts` → canonical serial forums (~49,600). Carries ISSN/EISSN (normalized, leading-zero
  safety net), source type (mapped to journal/book-series/trade/conference), publisher, ASJC (`;`-joined).
  Admin `POST /scopus/importSourceList?path=&batchId=`; source `SCOPUS_SOURCE_LIST`. Verified column/type/
  ISSN formats against the real file. Config key (`scopus.source-list.file`) + rebuild wiring deferred to
  D5/D7.
- **D2 — Reposition CiteScore (A2) as rankings, not forum creation.** CiteScore stops defining forum
  identity; its CiteScore/SJR/SNIP/quartile attach as a **forum-keyed metrics layer** (mirrors
  `wos.metric_facts` → forum views). Forum identity/type/ASJC come from the Source List.
- **D3 — Build forums before publications.** — **DONE.** `buildFactsFromImportEvents` now runs
  `processForumChunks` → `processPublicationChunks` → `processCitationChunks` (was publications → citations →
  forums). FORUM events (Source List + CiteScore) seed `scopus.forum_facts` before the publication chunk's
  `findBySourceIdIn` preload runs.
- **D4 — Publication importer = resolve-and-link (strict (i)).** — **DONE.** `upsertForumFact` gained a
  `fromPublication` flag: the FORUM-event caller passes `false` (create-or-enrich, authoritative); the two
  publication-path callers pass `true` → if the forum already exists, **return immediately (no mutation, no
  save)** — strict link-only; if absent, create a minimal option-B forum whose provenance is the publication
  event's lineage source (distinct from `SCOPUS_SOURCE_LIST`/`SCOPUS_CITESCORE_LIST` — no new field needed).
  The venue link is resolved at stage-3 canonicalization by source id, so link-only needs no stage-2 write.
  Tests flipped to the new contract (seeded forum → link-only, publication does not enrich; a publication
  replay no longer refreshes its forum's lineage). Verified: strict link-only is safe because the Source List
  supplies the e-ISSNs publications were gap-filling.
- **D5 — Adapt `PipelineRebuildService`/`runFull` to forums-first + fold the feeds in.** One rebuild: wipe →
  ingest Source List + CiteScore + MJL + WoS + Scopus → `buildFacts` (forums-first) → publication
  resolve-and-link → ERIH onboard → dedup → projections. No manual afterthought, no double build.
- **D6 — Fix MJL coverage in the rebuild.** Ensure the rebuild's WoS path builds `wos.coverage_facts` from
  MJL events (the full WoS rebuild did in B2; step-wise `/wos/buildFacts` did not), so SCIE/SSCI/AHCI/ESCI
  membership lands.
- **D7 — Config** keys for the feed paths (`scopus.source-list.file`, `scopus.citescore.file`, `wos.mjl.dir`).
- **Subsumes the deferred "ERIH-only-journal forum creation":** with ERIH (and the Source List) as
  authoritative sources, venues that matched no existing forum become real forums.
- **Verify:** one full rebuild yields the complete serial registry, **~0** `FORUM_EXTERNAL_ID_ALREADY_LINKED`
  conflicts (vs 448 baseline 2026-06-17), MJL coverage present, publications linked by FK, reconcile audit
  `healthy=true`.
- **Dep:** A–C (done).

### Move E — book registry (books as a first-class entity, separate from forums)

**Why.** Books are a different *kind* of venue than serials: one-off monographs/edited volumes keyed by
ISBN + Scopus book ID, with publisher/ASJC/year but **no ISSN, no serial rankings, no indexing membership**.
Today `ScholardexPublicationFact` has only `forumId`, so book chapters are mis-modeled as forums; yet the
scoring layer already branches book vs journal (`FeaaBookScoringService`, `ComputerScienceBookService`, on
`aggregationType`). The Scopus Book List (`data/scopus/Scopus_Books_list_February.xlsx`, sheet
`Scopus_Books`) is **475,453** books: `TITLE, PRINT ISBN, ELECTRONIC ISBN, PUBLISHER, PUBLICATION YEAR,
ASJC, SCOPUS ID`. Folding 475k books into `forum_facts` would pollute the serial forum-keyed views and bury
the ~50k real serial forums; they belong in their own collection.

- **E1 — `ScholardexBookFact` (`scholardex.book_facts`).** New canonical entity keyed by Scopus book ID /
  ISBN, from the Book List: title, print/electronic ISBN, publisher, publication year, ASJC. No
  metric/category/membership views (books carry no serial rankings). Config `scopus.book-list.file`.
  **Open: pre-create all 475k (isolated reference registry, acceptable now that it's separate) vs
  resolve-on-demand (load as an ISBN/Scopus-book-id lookup; create the book fact only when a publication
  resolves to it).** Lean resolve-on-demand unless complete-registry value is needed.
- **E2 — Publication venue polymorphism.** Add `bookId` to `ScholardexPublicationFact`; venue resolution
  branches on `aggregationType` — Journal / Conference Proceeding / Book Series → `forumId` (serial
  registry); **Book → `bookId`** (book registry, by ISBN / Scopus book ID).
- **E3 — Point book scoring at the registry.** Book-scoring services resolve `bookId` against `book_facts`
  instead of treating the book as a forum.
- **E4 — Rebuild integration.** Book List ingest + book resolution slot into the forums-first rebuild
  (after the serial registry, before/with publication resolution).
- **Verify:** book-chapter publications resolve to `book_facts` by ISBN/Scopus book id; the forum-keyed
  views stay serial-only; book scoring resolves the book registry; serial forum count unaffected by books.
- **Dep:** D (reuses the loader + resolve-and-link patterns).

### Deferred (after the in-hand lists prove out)
- CNCS A/B/C tiers (transcribe/UEFISCDI), embedded prestige publisher lists (`data/standards/`), vendor
  title-lists for the "≥N DBs" predicate — pulled in by the first non-STEM report that needs them.

## Consumers

The forum-resolution fix (`getRankingsByForum` fuzzy path) becomes obsolete (clean FK). Unblocks the
"indexed in WoS/ERIH/Scopus/≥N-DBs", quartile, and CNCS-tier predicates across humanities/social/law/arts +
STEM; sharpens FEAA/physics book scoring. Sibling to [H67](h67-h-index.md), [H68](h68-criteria-extensions.md),
[H64](h64-canonical-projects.md).

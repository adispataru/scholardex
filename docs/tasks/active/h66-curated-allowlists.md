# H66 Canonical Forum registry (multi-source identity + rankings + indexing)

**Status:** Planning
**Created:** 2026-06-16 · **Reshaped 2026-06-16** (curated allowlists → forum-first registry) ·
**Re-grounded 2026-06-16** against the actual pipeline code (the canonical forum + a standalone forum-import
path already exist; scope is narrower than the first reshape implied)
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

**B. Fold rankings + indexing onto the canonical forum (the bug-killer).** Make the AIS/IF/RIS/quartile +
index-membership lookup go through the forum's `wosForumIds` FK instead of fuzzy ISSN/name at scoring time.
Concretely: link `WosMetricFact.journalId` ↔ canonical forum at *ingest/onboarding* time (the link already
exists via the onboarding service — make it authoritative and complete), then project rankings + indexing
onto `scholardex_forum_view` (or a sibling table keyed by **forum id**, not journalId). Existing
`getRankingsByForum` becomes a trivial FK read; the fuzzy resolver is retired.

**C. Publication path: confirm resolve-or-enrich.** Already true via `source_id` blank-tolerant merge.
Verify it links (not duplicates) once the registry is rich; ensure `ScholardexForumDeduplicationService` +
`ForumMergeSafetyRule` cover the new id sources (Scopus Source ID, WoS journalId, ERIH id).

## Model deltas (small)

`ScholardexForumFact` already has the id lists. Add: `erihIds[]`, `type` (journal/book-series/conference/
trade, from CiteScore), subject classification (`asjc[]`, `wosCategories[]`), indexing membership flags
(WoS SCIE/SSCI/AHCI/ESCI, ERIH, DOAJ, Scopus, CNCS tier), and rankings (AIS/IF/RIS/quartile by year) —
either embedded or a sibling keyed by forum id. Project the new attributes onto the forum view.

Expose to scoring/eligibility: "in list X", "indexed in ≥N of {set}", "WoS/Scopus quartile", "CNCS tier",
"WoS category" — plus the AIS/IF/RIS base scores by FK, not fuzzy match.

## Sources (all in hand)

| source | native id → forum field | adds | form |
|---|---|---|---|
| **Scopus CiteScore** (`data/scopus/CiteScore 2023 per Nov 2024.csv`) | Scopus Source ID → `scopusForumIds` | ISSN/eISSN, title, publisher, ASJC, **type**, CiteScore/SNIP/SJR/quartile | CSV ✅ |
| **WoS MJL** (`data/wos/mjl/`) | ISSN → match; journalId → `wosForumIds` | WoS index membership, categories, publisher | CSV ✅ |
| **WoS metrics** (Postgres `reporting_read.wos_metric_fact`) | journalId → `wosForumIds` FK | **AIS / IF / RIS + quartiles** (already ingested; today joined fuzzily) | in DB ✅ |
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
id sources) → `reporting_read.scholardex_forum_view` (+ rankings/indexing columns or sibling keyed by forum
id). WoS metric/category facts get linked to the forum by journalId at onboarding (move B). Publication
canonicalization resolves its venue against the registry by Scopus Source ID → ISSN → name (already does the
first/second via `source_id`/ISSN; this just becomes near-idempotent once seeded).

## Open decisions

- Full FORUM-event pipeline vs lighter reference-import for the slow-moving list sources (reuse the existing
  publisher-CSV ingestion shape — it already emits FORUM events).
- Rankings/indexing on the forum: **embedded** on `ScholardexForumFact` vs **sibling table keyed by forum id**
  (year-dimensioned metrics argue for a sibling; indexing-membership flags can embed).
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

### T0 — Isolated test environment (setup, no code)
- **Do:** `createdb core_h66`; boot with `--spring.data.mongodb.uri=mongodb://localhost:27017/scholardex_h66
  --spring.datasource.url=jdbc:postgresql://localhost:5432/core_h66` under `agent-dev`. Optionally seed from
  current state (`mongodump|mongorestore` ns-remap, `pg_dump|psql`) or start empty to exercise the seed path.
- **Verify:** app boots; Flyway creates `reporting_read` on `core_h66`; existing report renders against the
  copied data (smoke). **No prod DB touched.**
- **Dep:** none.

### Move A — seed the registry from the in-hand lists

**A1 — Forum model + projection deltas.**
- **Do:** extend `ScholardexForumFact` with `erihIds[]`, `type` (journal/book-series/conference/trade),
  `asjc[]`, `wosCategories[]`, indexing-membership flags (SCIE/SSCI/AHCI/ESCI/ERIH/DOAJ/Scopus), and a
  rankings holder (decide embedded vs sibling — see open decisions; year-dimensioned → lean sibling table
  `scholardex_forum_ranking_view` keyed by forum id). Add matching columns/tables to the Postgres projection
  in `ScholardexProjectionBuilderService` + a Flyway migration. Keep blank-tolerant merge semantics.
- **Verify:** unit test the projection mapping; migration applies on `core_h66`; existing forum view
  unaffected (new columns nullable).
- **Dep:** T0.

**A2 — Scopus CiteScore loader (the FK backbone).**
- **Do:** parse `data/scopus/CiteScore 2023 per Nov 2024.csv` → emit `ScopusImportEntityType.FORUM` events
  (reuse the publisher-CSV ingestion shape in `ScopusDataService` / `processForumChunks`). Map Source ID →
  `scopusForumIds`, ISSN/eISSN, title, publisher, ASJC → `asjc`, type. Canonicalize by ISSN/eISSN → name.
- **Verify:** load count = 29,777 distinct sources; spot-check a known journal resolves to one canonical
  forum with Source ID + ISSN + ASJC + type set; re-run is idempotent (no dupes).
- **Dep:** A1.

**A3 — WoS MJL loader (index membership + categories).**
- **Do:** parse the four `data/wos/mjl/*` CSVs → forum source facts (new `wos.forum_facts` source stream, or
  reuse the FORUM event with a WoS source tag) → canonicalize by ISSN, setting `wosForumIds`, WoS index
  flags (SCIE/SSCI/AHCI/ESCI), `wosCategories`, publisher. Merge onto the CiteScore-seeded forums by ISSN.
- **Verify:** journals present in both CiteScore and MJL land on **one** canonical forum carrying both
  `scopusForumIds` and `wosForumIds`; index flags set; MJL-only journals create new forums.
- **Dep:** A1 (A2 recommended first so merges have targets).

**A4 — DOAJ loader (open-access flag).**
- **Do:** fetch/parse DOAJ CSV → set the DOAJ indexing flag on matching forums by ISSN.
- **Verify:** known OA journal flagged; non-OA unaffected.
- **Dep:** A1.

### Move B — fold rankings + indexing onto the forum (the bug-killer)

**B1 — Authoritative WoS-metric ↔ forum link at onboarding.**
- **Do:** complete + make authoritative the `WosMetricFact.journalId` ↔ canonical-forum mapping (today partial
  via the onboarding service). After A3, `wosForumIds` exists on forums, so link metrics by journalId → forum.
- **Verify:** % of `wos_metric_fact` rows linked to a canonical forum (target: high coverage); report the
  unlinked remainder.
- **Dep:** A3.

**B2 — Project rankings + indexing keyed by forum id.**
- **Do:** project AIS/IF/RIS/quartile-by-year + indexing membership onto the forum view (or the sibling
  ranking table from A1), keyed by **forum id**, not journalId.
- **Verify:** for a sample forum, the projected AIS/IF matches the underlying `wos_metric_fact` values.
- **Dep:** B1.

**B3 — Rewire scoring lookup to FK; retire the fuzzy resolver.**
- **Do:** change `getRankingsByForum` (and `AbstractWoSForumScoringService`) to read rankings by the forum's
  FK instead of fuzzy ISSN/name. Keep the fuzzy path only as a fallback for not-yet-seeded forums (log when
  used, so we can watch it trend to zero).
- **Verify:** the 39-forums-scored-0 regression case now scores correctly via FK; existing scoring tests
  pass; a recompute on copied data matches or improves prior scores (no silent regressions).
- **Dep:** B2.

### Move C — publication path + dedup hardening

**C1 — Confirm resolve-or-enrich + extend dedup to new id sources.**
- **Do:** verify `upsertForumFact` links (not duplicates) when a seeded forum already matches by `source_id`/
  ISSN; extend `ScholardexForumDeduplicationService` + `ForumMergeSafetyRule` to treat shared Scopus Source
  ID / WoS journalId / ERIH id as safe-merge keys (not just ISSN).
- **Verify:** import a publication whose venue is already seeded → no new forum, attributes preserved; dedup
  run merges Source-ID-shared forums and quarantines unsafe ones.
- **Dep:** A2/A3.

**C2 — Backfill/reconcile existing forums against the seeded registry.**
- **Do:** one-time reconcile of pre-existing (publication-derived) forums into the seeded registry by ISSN/
  Source ID; re-point publication links to the canonical winner.
- **Verify:** count of forums before/after; no orphaned publication→forum links; rankings now resolve by FK
  for previously-fuzzy cases.
- **Dep:** B3, C1.

### Deferred (after the in-hand lists prove out)
- **A5 — ERIH+ Typesense fetcher** → `erihIds` + disciplines (pinned endpoint; needs the pull).
- CNCS A/B/C tiers (transcribe/UEFISCDI), embedded prestige publisher lists (`data/standards/`), vendor
  title-lists for the "≥N DBs" predicate — pulled in by the first non-STEM report that needs them.

## Consumers

The forum-resolution fix (`getRankingsByForum` fuzzy path) becomes obsolete (clean FK). Unblocks the
"indexed in WoS/ERIH/Scopus/≥N-DBs", quartile, and CNCS-tier predicates across humanities/social/law/arts +
STEM; sharpens FEAA/physics book scoring. Sibling to [H67](h67-h-index.md), [H68](h68-criteria-extensions.md),
[H64](h64-canonical-projects.md).

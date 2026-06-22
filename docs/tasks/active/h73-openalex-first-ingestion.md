# H73 — OpenAlex-first ingestion (UVT 1-hop corpus + ROR affiliation backbone)

## Goal

Ingest the locally-held OpenAlex UVT neighborhood and the OpenAlex institution
backbone into the canonical layer, **from files already on disk** — no further API
fetching, no further API spend. This turns the "OpenAlex-FIRST" exploration into a
durable, replayable pipeline:

- the UVT publication corpus (full records),
- the incoming-citation graph (who cites UVT),
- the outgoing reference DNA (UVT's reference lists, IDs only),
- ROR onto verified Scopus affiliations via the OpenAlex institution alias graph.

This **supersedes the H72 slice-3 positional ROR bridge** (noisy) and **absorbs the
H63 corresponding/last-author/ORCID backfill** (the bulk works already carry it).

## Data on disk (all under `data/openalex/`, git-ignored, ~2.7 GB)

| File | Records | Role |
|---|---|---|
| `uvt_works.jsonl` | 11,656 | UVT publications, full records |
| `uvt_citing_works.jsonl` | 105,766 | incoming citers, full records (4,404 are UVT; 101,362 external) |
| `institutions/*.gz` (50) | 121,512 | ROR/alias backbone (121,511 ROR, 121,509 alias-bearing) |
| reference edges (inside works) | 321,810 | outgoing DNA (203,721 unique targets; 8,756 already held) |

Regenerable from `scopus-python/openalex_fetch_uvt.py` and
`scopus-python/openalex_fetch_citations.py` (temp-file + atomic-rename safe; API
key from `.env`; draws from the free 10k/window so $0 actual spend).

## Key findings that shape the design

- **No `scopus` id on any OpenAlex institution record (0/121,512).** There is no
  afid→ROR id-join; the Scopus-affiliation → OpenAlex-institution bridge **must** be
  name/alias-based. This locks in the 3-tier alias matcher and is the reason the H72
  positional bridge is retired. See [[scopus-entity-resolution-tiers]].
- **Citation totals are modest:** 148,973 mentions → **105,766 unique** citers (heavy
  dedup; one citer often cites several UVT papers). 65% of UVT works are cited; only 86
  exceed one page of citers.
- **Outgoing DNA is overwhelmingly external (94.7%).** Of 203,721 unique referenced
  works, only 8,756 (4.3%) are works we already hold (UVT core + citers). Full external
  hydration (194,965 bare IDs, ~$0.39) is **out of scope** — store edges only.
- **Full-rebuild does not re-run OpenAlex sync**, so the bulk import must be a
  **replayable rebuild step** or the OpenAlex layer vanishes on `rebuildAllDerived`.

## Existing code this lands on

- `OpenAlexImportService.importByOrcid(...)` (per-researcher API) and
  `upsertNeighborWorks(List<OpenAlexWork>...)` already map `OpenAlexWork` DTO →
  `OpenAlexPublicationFact`. The bulk importer **reuses that DTO + upsert mapping** —
  new *source* (file), not a new mapping. Per-researcher API stays for live/incremental.
- `OpenAlexCitationCanonicalizationService` + the citation-graph machinery (tasks
  #16–22) already exist — H73 **feeds** them from files, does not rebuild them.
- `ScholardexAffiliationRorBridgeService` (H72 slice 3) — **retired** by slice 2 here.

## Decisions settled (2026-06-21)

- **OpenAlex-first affiliation backbone (the load-bearing decision).** OpenAlex
  institutions are the **primary** source for `ScholardexAffiliationFact` — they derive
  the backbone (ROR-keyed) **first**; Scopus affiliation facts then resolve **into** it
  (alias-match → add afid to `scopusAffiliationIds`) or mint their own if Scopus-only.
  ROR is the canonical key; afid is a secondary source id. This is NOT "Scopus spine +
  ROR tag" (rejected) — it inverts the polarity so the clean, deduped, multilingual
  OpenAlex graph is the spine and Scopus's noisy afid strings plug in.
  - The model already supports it: `ScholardexAffiliationFact` carries
    `scopusAffiliationIds` / `wosAffiliationIds` / `googleScholarAffiliationIds` /
    `userSourceAffiliationIds` / `rorIds` / `aliases`. Direct precedent: **authors**
    already canonicalize from Scopus + OpenAlex into one `ScholardexAuthorFact`
    (`ScholardexAuthorCanonicalizationService` + `OpenAlexAuthorResolver`).
  - **Delivers the affiliation merges afid-keying structurally blocked** (two afids →
    one ROR), the original H72 entity-resolution goal; fixes the e-Austria/UVT cross-tag
    (distinct RORs); cross-language names ("Universitatea de Vest" ↔ "West University of
    Timișoara") resolve because OpenAlex holds both as aliases under one ROR.
  - Ordering: only the **narrow** "OpenAlex-institution canon before Scopus-affiliation
    canon" change — NOT the broad forums-first reorder (that stays H74).
- **Backbone scope: referenced-only, works-seeded.** The OpenAlex works define which
  institutions matter — the `uvt_works` + `uvt_citing_works` authorships reference
  **24,296** distinct institution ids, of which **24,233 (99.7%)** exist in the
  snapshot. The importer reads the works first (collect referenced ids), then reads the
  institutions zip and stores backbone metadata **only for those referenced ids**
  (~24,233, vs 121,512 full snapshot). The 63 referenced-but-missing institutions are
  backboned inline from the work record (authorship carries `ror` + `display_name`).
  Full fixture stays on disk for lookup; widen later without re-fetching.
- **H63 backfill absorbed into H73** (the bulk-import slice). H63 shrinks to the
  incremental DOI-keyed path for newly-added pubs only.
- **Pipeline reorder is NOT in H73** → **H74** (broad forums-first reconcile/rebuild
  reorder). H73 makes only the narrow affiliation-source ordering change above.
- **H72 slice-3 positional ROR bridge retired** here; the noisy positional `rorIds` are
  cleared — ROR now comes from the OpenAlex institution backbone, not positional
  inference. H72 closeout to note this.

## Current ingestion & ordering (why institutions go first)

- **Scopus affiliations are already first-class, independent of publications.** The
  Scopus dump carries standalone affiliation records → `ScopusAffiliationFact` (source,
  via `ScopusFactBuilderService`) → `ScholardexAffiliationFact` (canonical, via
  `ScholardexAffiliationCanonicalizationService`), all inside `scopusRebuild.runFull()`.
  Affiliations are afid-keyed, **not** derived from pub→author edges.
- **OpenAlex institutions (the 121,512 fixture) are not ingested at all** — net-new here.
- Today ROR enrichment runs **after** the Scopus build (the positional bridge in
  `reconcile()`), which is what made it positional/noisy. Building the institution/ROR
  backbone **up front** is independent of the OpenAlex pub import and benefits everything
  downstream (the author over-split merge keys on *verified affiliation*). The deeper
  "institutions canonicalize before pubs at the rebuild-chain level" is **H74**.
- Rebuild order today (`PipelineRebuildService.rebuildAllDerivedFromSource`): WoS build →
  DOAJ/ERIH feeds → `scopusRebuild.runFull()` → `forumReconcileService.reconcile()`
  (forum dedup → author ORCID/fuzzy/over-split → ROR bridge → projections).

## Slices (OpenAlex backbone first)

### Slice 1 — OpenAlex bulk importer (works + citers + referenced institutions) — DONE (live-validated 2026-06-21)
Shipped: `OpenAlexBulkImportService` + `OpenAlexInstitutionRecord` DTO +
`OpenAlexImportService.importFullWork(...)`; wired into `PipelineRebuildService`
(`ingestOpenAlexBulkIfConfigured()`, before `scopusRebuild.runFull()`, config-gated by
`core.openalex.bulk.{works-file,citers-file,institutions-dir}`, blank skips); manual trigger
`POST /admin/initialization/openalex/bulkImport`. Unit tests in `OpenAlexBulkImportServiceTest`.
**Live run (114s):** works=11,656 (full, H63 corresponding backfilled on 6,723), citers=105,766
(bare), backbone=24,232 ROR-keyed affiliations (from 24,296 referenced ids; ~64 had no
snapshot ROR match). Mongo after: `openalex.publication_facts` 588→113,499;
`scholardex.affiliation_facts` 16,427→40,659 (16,427 Scopus untouched + 24,232 OPENALEX backbone,
all with a rorId, no index conflict — backbone records carry empty `scopusAffiliationIds`). UVT
backbone: `name="Universitatea de Vest din Timișoara"`, English form in aliases — the
cross-language pair slice 2 will match Scopus afids against.

One replayable importer reading the three local inputs in a single flow:
- **Pass 1 — works:** stream `uvt_works.jsonl` + `uvt_citing_works.jsonl` (records are
  large — do not slurp) into `OpenAlexWork` DTOs and upsert via the existing
  `OpenAlexImportService` mapping into `openalex.*` source facts (publication / author /
  affiliation refs). **Absorb H63 backfill** — populate corresponding-author /
  last-author / ORCID / `author_position` from `authorships[]` in the same upsert. While
  streaming, collect the distinct referenced institution ids from
  `authorships[].institutions[]` (24,296 distinct).
- **Pass 2 — institutions:** stream `institutions/*.gz`, keep only the referenced ids
  (~24,233 = 99.7%), and derive backbone `ScholardexAffiliationFact`s — ROR-keyed `@Id`,
  `rorIds=[ror]`, `name=display_name`, `aliases=display_name_alternatives +
  display_name_acronyms`, city/country from `geo`. The 63 referenced-but-missing
  institutions are backboned inline from the work's authorship (`ror` + `display_name`).
- Wire as a **replayable rebuild step** reading `data/openalex/` so a full
  `rebuildAllDerived` reconstitutes the OpenAlex layer; the backbone is derived **before**
  Scopus affiliation canonicalization (narrow ordering change in the Scopus build /
  `PipelineRebuildService`).
- Tests: streaming parse + upsert idempotency; corresponding/last-author populated;
  referenced-id collection from authorships; institution → backbone mapping (ROR id,
  aliases, geo); referenced-only filter; missing-institution inline fallback; rebuild
  replay includes the step and the backbone precedes Scopus affiliation canon.

### Slice 2 — OpenAlex is the backbone for publications, authors, AND affiliations (EPIC, decided 2026-06-21)
Expanded from "Scopus affiliations resolve into the ROR backbone" to **full OpenAlex-as-backbone**: where
OpenAlex has data, its identities (DOI / ORCID / ROR) and fields are authoritative; Scopus plugs in or fills the
gaps. Then a **rebuild-from-scratch** validates the new order.

**Grounding facts (verified):**
- **Publications already merge by DOI** — `buildCanonicalPublicationId` is DOI-first (`doi|…` → eid → wos → title),
  so Scopus + OpenAlex with the same DOI are already ONE `spub_<hash(doi)>`. So pub "backbone" = **field
  precedence**, not identity. The **OpenAlex canon already runs LAST** (after the Scopus build), so it can *take
  over* authority by overwriting — no risky reorder of the Scopus monolith needed for pubs.
- OpenAlex is UVT-scoped (11,656 UVT works) vs Scopus full corpus (92,526 canonical pubs); OpenAlex is **not** a
  superset. Backbone = OpenAlex-authoritative-where-present, Scopus fills the no-DOI / OpenAlex-absent tail.
- **Authors are the hard case** — Scopus AU-ID-keyed (`sauth_<hash(scopus|auid)>`) vs OpenAlex ORCID/OpenAlex-id-keyed;
  different schemes → no auto-merge, only the ORCID positional bridge. H72 found cross-source author dedup
  low-yield/unsafe. **Decided: full author inversion** (OpenAlex authors primary, Scopus AU-IDs resolve in) — highest
  risk; gated behind a dry-run.

**Dry-run findings (2026-06-21, read-only on the live canonical data):**
- **S2.2 pub precedence is strongly justified.** Over 55,855 shared-DOI pubs: OpenAlex citation count > Scopus on
  **77.8%** (+560,684 citations Scopus misses); author lists **98.7% equal**; corresponding author Scopus 0% →
  OpenAlex **72.7%**; ≥1 author ORCID on **95.5%**.
- **Author inversion is safe via the POSITIONAL bridge, not name matching.** The earlier homonym dry-run
  (10.3% shared names, clusters to 54) measured corpus-wide name matching — which is NOT what we do. The
  positional bridge compares author *i* of *one shared paper* across both sources (equal-count + per-position
  surname guard), so homonyms never collide. The 98.7% equal-author-count means nearly all shared-pub authors
  bridge cleanly; Scopus-only-pub authors just mint (no inversion).

**Sub-slices (corrected — pub precedence + author attach are ONE operation, both need the reorder):**
- **S2.1 — Affiliations → ROR backbone. DONE (unit-tested 2026-06-21, commit 87f7b73; live via S2.3 rebuild).**
  `ScopusAffiliationRorMatcher` (3-tier: exact alias → simplification → country-gated Jaccard≥0.8+city) +
  `ScholardexAffiliationCanonicalizationService` integration: first-seen verified afid alias-matches the backbone →
  `saff_<ror>` + plug afid into `scopusAffiliationIds`, enrich-only (OpenAlex name/country authoritative); no match →
  mint afid-keyed. Config `scopus.affiliation.ror-backbone-match` (default on). Retired
  `ScholardexAffiliationRorBridgeService` (deleted; removed from reconcile chain + endpoint); noisy positional
  `rorIds` clear on the S2.3 rebuild.
- **S2.2 — Pub + author inversion (the coupled core).** Reorder so the OpenAlex canon mints canonical pubs +
  authors **first**; then the Scopus pub canon **resolves into** the existing canonical pub by DOI: OpenAlex
  field precedence (title/venue/citations + author list authoritative; Scopus adds `eid` + Scopus-only fields)
  **AND** positional attachment of each Scopus AU-ID to the existing scholardex author at position *i*
  (equal-count + surname guard). Scopus-only / OpenAlex-absent pubs mint as today. Pub-resolve, author-attach,
  and the reorder are inseparable — a partial state duplicates authors. (Absorbs the old S2.2 + S2.3 + reorder.)
- **S2.2 — DONE (2026-06-22).** Shipped as three coupled changes, all unit/integration-tested:
  - **S2.2a** forum build moved ahead of the affiliation/author/publication canon block in `runFull` (forums have
    no dependency on canonical author/affiliation/pub facts — verified), so a single early OpenAlex-first canon pass
    can stamp `forumId` against a complete forum registry.
  - **S2.2b** the OpenAlex canon now runs **before** the Scopus canon block and, in one bulk pass, indexes the
    Scopus pub source-facts by DOI (+ AU-ID→name) and positionally writes `(AUTHOR, SCOPUS, auid) → OpenAlex-author`
    **LINKED source-links** (equal-count + surname guard; ambiguous AU-IDs dropped). The later Scopus author/pub
    canon resolves each AU-ID into the OpenAlex author via those links with **no code change** (they already prefer
    an existing source-link over minting). `OpenAlexAuthorResolver`/`OpenAlexCanonicalizationService`.
  - **S2.2c** `ScholardexPublicationCanonicalizationService` defers to OpenAlex: when it resolves a Scopus pub into
    an OpenAlex-owned DOI pub, it enriches-only (adds `eid` + Scopus-only fields, monotonic-max citation count) and
    keeps OpenAlex's title/venue/owning source; the SCOPUS source-link is still recorded.
- **S2.3 — Validation.** **DONE (integration test, 2026-06-22):** `OpenAlexFirstInversionIntegrationTest`
  (Testcontainers `mongo:7.0` + `@SpringBootTest` with Postgres autoconfig excluded, mirroring `CoreApplicationTests`)
  runs the real OpenAlex canon → Scopus author canon → Scopus pub canon on a shared-DOI fixture and asserts the
  AU-ID folds into the OpenAlex-keyed author (no Scopus-keyed twin), the inversion source-link persists, and the pub
  keeps OpenAlex title/source while gaining the Scopus `eid`. ~15s, deterministic, sleep-proof.
  **DEFERRED:** one full caffeinated `rebuildAllDerived` (~90 min) for production data + count validation — see the
  rebuild-fragility note below.

**Rebuild-fragility note (2026-06-22):** a full from-scratch `rebuildAllDerived` is ~90 min, not resumable (the
endpoint wipes first), and dominated by the **citer graph** (~105k of ~117k OpenAlex pubs are citers, irrelevant to
the S2.2 inversion which is UVT-works ↔ Scopus only). Operationally it is fragile on a laptop: `bootRun` under the
agent harness gets reaped when its launcher task ends/caps, and laptop **sleep** (lid close / low battery) aborts
in-flight Mongo ops mid-rebuild. Run it: app daemonized via a Python `os.setsid` double-fork (escapes the harness
process group); `POST /admin/initialization/rebuildAllDerived` needs `confirmation=RESET` and is **synchronous**, so
fire a short-`--max-time` curl (the server completes regardless of client disconnect) and poll the log for
`Pipeline rebuild complete.`; wrap in `caffeinate -dimsu` + keep on power. For a faster validation run, blank
`core.openalex.bulk.citers-file` (works-only → canon ~12k pubs not ~117k).

Implementation order: **S2.1 → S2.2 → S2.3 rebuild.** Start with the standalone affiliation pass, then the
coupled inversion, then the rebuild.

### (original) Slice 2 — Scopus affiliations resolve INTO the backbone (alias matcher inverted)
- Change `ScholardexAffiliationCanonicalizationService` so each **verified** Scopus
  affiliation (`afid ^60`) resolves against the backbone via the 3-tier alias matcher:
  1. exact alias (display_name + alternatives + acronyms),
  2. simplification (strip "the"/parenthetical-acronyms/faculty-prefix),
  3. country-gated token-Jaccard ≥0.8 + city tiebreak.
  On match → **add the afid to `scopusAffiliationIds`** of the ROR-keyed backbone record
  (plug in). No match → **mint** an afid-keyed record (Scopus-only institution); optional
  lazy-expand from the on-disk snapshot if the afid alias-matches an institution not yet
  in the backbone.
- Ad-hoc afids (`1xxxxxxxx`) stay dropped (preserve H72 slice 1); backbone makes a
  future rescue possible but out of scope here.
- **Retire `ScholardexAffiliationRorBridgeService`** from the reconcile chain; clear the
  noisy positional `rorIds`.
- Validate: e-Austria vs UVT resolve to distinct RORs; cross-language afids collapse to
  one ROR; the pub→author→affiliation edges resolve to the ROR-keyed canonical.
- Tests: plug-in (afid added to backbone), mint (Scopus-only), cross-language collapse,
  country gate rejects same-token cross-country, edges point at ROR-keyed canonical.

### Slice 3 — OpenAlex pub→author→affiliation edges (UVT works **and** citers) — DONE (live-validated 2026-06-21)
Shipped: citers imported full; `OpenAlexCanonicalizationService` emits author→affiliation +
pub→author→affiliation edges resolving `institutionRors` → ROR backbone. **Bulk-mode canon**
(`OpenAlexAuthorResolver.BulkContext` in-memory author index + batched affiliation-edge inserts)
+ the missing `orcidIds`/`openAlexAuthorIds` indexes + `doiNormalized` made non-unique took the
full canon from a projected ~10h (O(N²) unindexed author scans) to **~30.6 min**.
**Live run:** 166,224 OpenAlex authors (total 217k→381,120); **337,397 pub→author→affiliation**
+ 247,743 author→affiliation edges resolving to **21,032 ROR backbone institutions** (citer
institutions — the "who cites UVT, from where" graph); 38,994 UVT works contribute
corresponding-author edges; 301,621 authorship edges. Two follow-ups noted below.

**Follow-ups:**
- **S3.5 — batch authorship edges. DONE (unit-tested 2026-06-21, no live run yet).**
  `batchUpsertAuthorshipEdges` gained an optional `correspondingKeys` Set (stamps
  `corresponding=true` for `pub|author` keys in the set); bulk canon accumulates +
  batches authorship edges alongside affiliation edges. Closes the last per-record
  hot path; live timing to be confirmed on the next bulk run.
- Malformed-DOI normalization (`10.5380/raega` carried a concatenated `eissn:…`) — minor
  data-quality cleanup in `normalizeDoi`. Still open.

Original plan:
The "authored this paper while at this institution" edge
(`ScholardexPublicationAuthorAffiliationFact`) is built from **Scopus only** today —
`OpenAlexCanonicalizationService` calls only `upsertAuthorshipEdge` (pub→author), never
`upsertAuthorAffiliationEdge`. This slice builds it from OpenAlex, for both UVT works and
citers (decided 2026-06-21: citers carry the edge too).
- **Make bulk citers FULL** (reverses slice-1 bare neighbors): import citers via the full
  upsert so their `authorships[]` + `institutionRors` are stored. Cost: ~295,167 citer
  authors minted, ~707,835 pub→author→affiliation edges — roughly **doubles** the author
  graph (214,934→~510k) and the affiliation-edge graph (710,097→~1.42M).
- Extend `OpenAlexCanonicalizationService` to also emit author→affiliation +
  pub→author→affiliation edges (`EdgeWriterService.upsertAuthorAffiliationEdge`),
  resolving each authorship's `institutionRors` → the ROR backbone affiliation
  (`saff_<hash(ror)>`). 551,101 of 645,622 citer authorship rows carry an institution.
- **Citer authors: full reconcile participation** (decided 2026-06-21) — no external flag;
  they flow through the ORCID/fuzzy/over-split passes like any author. Note: the reconcile
  passes now run over ~510k authors (slower; the H72 name/`>20-author` guards still apply).
- Citation edges (citer→UVT) are already built by `OpenAlexCitationCanonicalizationService`
  in `runFull()` (existing machinery) — this slice adds the *affiliation* edge, not citations.
- Tests: citer full-import stores authorships; author→affiliation + pub→author→affiliation
  edges emitted for an OpenAlex authorship; institutionRor resolves to the backbone
  affiliation id; uvt + citer both covered.

### Slice 4 — DNA edge layer (free, no minting)
- Persist `referenced_works` IDs on each UVT publication fact.
- Materialize the **internal UVT↔UVT** citation edges (17,130 outgoing / 4,404→3,929
  works; both ends already canonical) as resolved links.
- Enables bibliographic-coupling / self-citation / internal-influence analytics with
  zero foreign-fact minting.
- Tests: edge extraction count; internal-edge resolution (both ends UVT); zero
  foreign facts minted.

## Out of scope
- External reference hydration (194,965 bare IDs) — deferred, store edges only.
- Pipeline reorder — **H74**.
- Cross-source author dedup — covered/dropped under H71/H72.

## Downstream
- **H69** (scoring rework) and **H67** (h-index) consume the citation graph this lands
  — H73 is upstream; no conflict.

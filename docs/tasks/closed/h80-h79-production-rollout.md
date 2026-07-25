# H80 — H79 production rollout (Informatică 2026 report go-live)

**Status:** SCOPED 2026-07-04. H79 code is merged to `main` and verified against local Mongo/PG; production still needs
the APC read model produced + the report made visible/signable. This doc covers how that plugs into the existing
ingest→project pipeline, plus the deferred H79 sub-rules.

## Goal

Make the Informatică 2026 report (`FV Info 2026`) correct and live in production: fee-journal (APC) exclusion active
from **both** DOAJ and OpenAlex, the 2026 conference-workshop rules already in the merged scorers, the report assigned
to the right divisions and signable. Do it through the standard pipeline, not a bespoke manual dance.

## The ingest→project pipeline today (`PipelineRebuildService`)

`rebuildAllDerivedFromSource(forceReingest)` is the unified DAG:

```
reset canonical state → wipe managed derived collections
  → WoS run (files → ledger → facts → projections)
  → ingestReferenceFeeds        (DOAJ + ERIH CSV → reference facts)      ← DOAJ APC captured here
  → ingestOpenAlexBulk          (works + citers → openalex.*; institutions → ROR backbone)
  → ingestBrainmapProjects      (project source facts)
  → Scopus runFull              (stage-3 canonical + stage-4 Postgres projections)   ← OPENALEX membership emitted here
  → rebuildCanonicalProjects    (project canon + project projection → director_signature etc.)
```

- **Derive-only fast path** (`sourceFactsPresent()` true, `forceReingest=false`, ~5 min): wipes ONLY the canonical
  (stage-3) collections and re-derives them + re-runs projections from surviving source/stage-2 facts. Skips WoS run,
  OpenAlex bulk, and reference-feed re-import.
- `openalex.*` collections (`publication_facts`, `institution_facts`, and the new `source_facts`) are in **neither**
  wipe list — the OpenAlex ingest upserts them (idempotent), and they are spared on derive-only.

## What is ALREADY plumbed (no work)

- **DOAJ APC** — `ingestReferenceFeeds` → `DoajDataService.importDoajCsvFromPath` already parses the `APC` column
  (`COL_APC`/`parseApc`, tri-state Yes/No/blank). Any rebuild captures it.
- **OPENALEX fee-journal membership** — `ScholardexProjectionBuilderService.buildOpenAlexApcMembershipRows` already
  runs inside the Scopus stage-4 projection; `isFeeJournal` already unions any `apc IS TRUE` (DOAJ ∪ OpenAlex).
- **Project projection / `director_signature`** — `rebuildCanonicalProjects()` already runs the project projection in
  the DAG, so project indicators + the director signature populate on any rebuild.
- **Report visibility mechanism** — `scholardex.division_report_selections` (one doc per `{divisionId, reportId}`);
  `FV Info 2026` already has a selection locally mirroring `FV Info 2016`.

## The single code gap

`OpenAlexSourceApcImportService` (per-venue APC derivation from the works dumps) is a **standalone admin endpoint**
(`POST /openalex/importSourceApc`) — it is **NOT** a pipeline step. So a rebuild produces DOAJ APC but leaves
`openalex.source_facts` empty, and the OPENALEX membership rows come out empty unless the endpoint is run by hand. That
is the only thing preventing "one rebuild → complete APC-aware read model".

---

## Slice A — fold source-APC derivation into the OpenAlex bulk import (CODE) — **DONE 2026-07-04**

**Decision (2026-07-04): fold into the bulk importer** (single works-stream pass, no second ~23 s scan).

**Done:** extracted the per-work aggregation into a shared stateful `OpenAlexSourceApcAggregator` (`observe(work)` →
`toFacts(batchId, corrId, now)`); both `OpenAlexSourceApcImportService` (standalone endpoint) and
`OpenAlexBulkImportService` now use it. `importAll` threads one aggregator through `importWorksFile` + `importCitersFile`
(mirroring the existing `Set<String> referenced` institution-id threading), calls `sourceApc.observe(work)` per work, and
upserts the facts after both streams (`persistSourceApc`). `BulkImportResult` gained `apcSources` + `apcFeeJournals`;
`PipelineRebuildService` logs them. **No pipeline-wiring change needed** — the DAG already calls `importAll`, so a rebuild
now produces `openalex.source_facts` in-DAG before the stage-4 projection. Unit test
(`importAllDerivesPerVenueApcFactsFromWorksAndCiters`: gold vs hybrid across works+citers); full suite green (2545).
End-to-end validation happens naturally during the Slice B full rebuild.

Original design (kept for reference):

`OpenAlexBulkImportService.importAll` already threads a `Set<String> referenced` (institution ids) through both
`importWorksFile` and `importCitersFile` via `collectInstitutionIds(work, referenced)`. Mirror that pattern exactly:

- Thread a `Map<String, SourceApcAggregate> sourceApc` accumulator through both file loops; populate it per work with
  `collectSourceApc(work, sourceApc)` (extract `primary_location.source` id/issns/`is_oa`/`is_in_doaj` + work-level
  `apc_list.value_usd`; OR is_oa, max apc across works). Covers works **and** citers (perspective-b own pubs +
  perspective-c citing venues).
- After both streams, upsert one `OpenAlexSourceFact` per source (`isFeeJournal = isOa && apcUsd > 0`).
- Extend `BulkImportResult` with `sourcesUpserted` + `feeJournals`; log them.
- **Share the aggregation logic** with the existing `OpenAlexSourceApcImportService` (extract the `Aggregate`
  observe/toFact primitive into a shared helper both call) — keep the standalone service + endpoint for manual re-runs,
  but the bulk importer becomes the canonical producer.

**Ordering is already correct:** `ingestOpenAlexBulk` runs BEFORE `Scopus runFull`, so `openalex.source_facts` exist by
the time the stage-4 projection reads them → membership emitted in the same rebuild.

**Derive-only caveat (document, don't fix):** the bulk import runs only on the FULL path; `source_facts` are spared on
derive-only. So `source_facts` refresh only on a full ingest (which is exactly when the works dumps change) — the same
lifecycle as `openalex.publication_facts`. A first-ever derive-only before any full ingest would see empty membership;
in prod the full rollout (Slice B) seeds them.

**Test:** the existing PipelineRebuildService / OpenAlexBulkImportService tests — assert the bulk import upserts
`source_facts` and flags fee journals from a small fixture works file (gold vs hybrid vs diamond, as in
`OpenAlexSourceApcImportServiceTest`).

---

## Slice B — production rollout via full rebuild (OPS) — **DONE 2026-07-04**

**The local `scholardex` Mongo/PG IS the production DB** (no separate prod yet). So the full rebuild run on 2026-07-04
*was* the rollout — the live read model is now APC-aware. Deploying to stage/prod is a later **data migration** (gated on
clearing the Informatică backlog + public-UI polish), not a re-run.

**Done (2026-07-04):** `POST /admin/initialization/rebuildAllDerived?confirmation=RESET&reingest=true` (caffeinated +
daemonized, ~34 min, 0 errors). The fold produced `source_facts` in-DAG — `apcSources=18575 apcFeeJournals=2140` from a
cleared-to-0 collection — the projection emitted **2,140 `OPENALEX` apc=true** membership rows (+ 8,427 DOAJ), MDPI
*Electronics* resolves `isFeeJournal=true`, and florin's Electronics paper is zeroed in `FV Info 2026` (authorScore 0.0,
total 10.655) with `FV Info 2016` unchanged. Whole DAG intact (forums 74,919 · pubs 149,899 · citations 512,195).
Report visibility: `FV Info 2026` has a `division_report_selections` entry mirroring `FV Info 2016` (re-verify the full
division set — SCIA/TDIS — carries over at stage/prod migration time).

**Original rollout playbook (for the stage/prod migration):**

**Decision (2026-07-04): full rebuild** (`rebuildAllDerivedFromSource(forceReingest=true)`) with Slice A wired, so one
run regenerates everything from source including `source_facts` → APC membership → APC-aware scoring.

Steps:
1. **Pre-flight:** confirm the DOAJ CSV (`core.*.doaj` path) and OpenAlex works/citers dumps are the intended editions
   on the prod host; back up Mongo + Postgres (the rebuild wipes managed derived collections).
2. **Run the full rebuild** off-hours. Heed the local-rebuild-fragility notes: it is long (~30–90 min) and
   non-resumable, needs `confirmation=RESET`, must run with schedulers controlled (no second instance grabbing tasks —
   kill zombies by `CoreApplication` + free the port first), and the host must not sleep (caffeinate + power). Daemonize
   so the harness/session can't reap it.
3. **Report visibility:** create `division_report_selections` docs assigning `FV Info 2026` to the real Informatică
   divisions (SCIA + TDIS, + Math if applicable) — mirror the divisions that currently have `FV Info 2016`.
4. **Verify** (spot checks, not a full diff):
   - An APC-only forum absent from DOAJ (e.g. MDPI *Electronics*, issn 2079-9292) resolves `isFeeJournal = true` via an
     `OPENALEX | apc=t` membership row.
   - A spot Informatică researcher's `FV Info 2026` differs from `FV Info 2016` **only** in the documented ways: gold-OA
     APC papers zeroed; conference workshops relabelled to category C and dropped from the top A*/A/B criteria.
   - `FV Info 2016` output is unchanged (version-never-mutate holds end-to-end in prod).

Risk note: prefer scheduling the rebuild when no evaluation runs are in flight; the projection is a full-replacement
write, so live report reads during the write see the prior snapshot until it swaps.

---

## Slice C — remaining Informatică 2026 sub-rules (re-checked against the standard 2026-07-04)

The residual `informatica-2026` scoring rules not yet built. Re-verified against `standarde-conf-2025.html`:

- **C1 — Posters & system demonstrations (`id_parA82`). DONE 2026-07-04.** Data check first: neither Scopus (`cp`) nor
  OpenAlex (`article`) distinguishes posters/demos — verified against the raw works dump (no `poster`/`demo` in either
  vocabulary), and forums carry no poster signal either (0). The **only** discriminator is a self-labelling title prefix
  (`Poster:` / `Demo:` / `Demonstration:` — 4 such items in the corpus, all at A*/A venues; the loose `demonstration`
  *word* is 25 real research papers and must be avoided). Built a strict high-precision title detector
  (`POSTER_DEMO_TITLE`, `isPosterOrDemo`) in `ComputerScienceConferenceScoringService`, threaded a `posterOrDemo` flag
  through `resolveConferenceScore`/`scoreResolvedConference`, and reused the slice-6 reduced path:
  `reduced = workshopAdjusted || (posterOrDemo && workshop2026)` → same category downgrade (A*/A/B→C, C→D), same 6/4/2/1
  points, same `topAB` exclusion from the top A*/A/B criteria. **2026-gated** (no verifiable 2016 poster clause → posters
  keep full parent under FV Info 2016; version-never-mutate). No indicator/Mongo/seed change — reuses the existing
  `workshopCategory2026` flag. Unit tests: poster@A* 2026 → C/6 + reduced flag; poster@A* 2016 → full A*/12 unchanged;
  "Demonstration of quantum synchronization…" → full A*/12 (loose word not matched). Live check not run — no scorable
  user has a poster paper, and the reduced path itself was already live-verified end-to-end for workshops (dana.petcu).

- **C2 — Per-pub-year APC resolution. DROPPED — the standard contradicts it.** `id_parA20`/`A28`/`A91` all key the APC
  exclusion to *"în momentul depunerii dosarului"* (at dossier-submission time = **current** state), not the
  publication's year. So the v1 single-current-snapshot is exactly correct; per-year resolution would be wrong. Removed.

- **C3 — b↔c 20% compensation (`id_parA118`). NOT a platform feature — committee exception (decided 2026-07-04).**
  The clause (*"…se pot modifica **doar prin transfer** de la perspectiva c) la perspectiva b)…"*) is **discretionary**:
  up to 20% of the perspective-b thresholds MAY be met by moving surplus perspective-c points across, keeping forum
  category — a marginal eligibility judgment the CNATDCU committee makes after analysing the exported numbers, not a
  computed score. Auto-applying it would have the platform silently flip a criterion NU→DA (the committee's call). Same
  class as **perspectiva a (research ethics)** — a manual check, not a gate. The platform already delivers what's needed:
  accurate perspective-b and perspective-c totals + per-criterion îndeplinit DA/NU. **Not built.** Optional future
  UI-polish (not scoring): a passive decision-support hint surfacing the transferable headroom ("b short by X; c surplus
  Y; ≤3.2 pts transferable") — informs the human, decides nothing. Build only if explicitly wanted.

  *Note:* `id_parA167` ("APC articles ≤ 1/3 of total") appears in the file but under a non-CS id range (CS perspective-b
  is `A71`–`A92`, whose APC rule is the hard exclusion `A91` we built) — likely another domain's section. Worth a 2-min
  confirm it does not apply to Informatică.

- **C4 — CORE national/regional conferences → category C (`id_parA81`). DONE 2026-07-04.** Surfaced by a completeness
  check for other exclusions (there is **no** short-paper/abstract exclusion in the CS standard). `id_parA81`:
  *"Workshop-urile/conferințele clasificate de CORE ca naționale sau regionale sunt considerate de categorie C."* Our
  loader `CoreConferenceRankingService.parseRank` collapsed every `National*`/`Regional` CORE value to **D** at ingest,
  losing the tier — so those venues scored 1 pt (D) instead of 2 pt (C). Confirmed 2016 = D, 2026 = C (user), so
  version-gated like the workshop relabel. Fix: `parseRank` now preserves `Rank.National`/`Rank.National_Regional`; the
  scorer remaps the parent rank `National/National_Regional → C (workshop2026) / D (else)` before points/reduction, so all
  downstream (points, category, workshop/poster reduction) follows. No other rank consumer breaks (only the scorer scores
  it; `RankingViewController` just displays it). Unit tests: national@2026 → C/2; national@2016 → D/1. **Live-validated:**
  CORE re-import re-persisted **124 National + 7 National_Regional** rankings (were 0). **Deploy step (Slice B / stage+prod):
  a CORE re-import (`POST /admin/initialization/general/coreConference`) is required** — the fix only takes effect once
  the persisted ranks are re-parsed; a Scopus/WoS rebuild does NOT re-import CORE reference data.

## Dependencies / references

- Closed task doc (full H79 slice record): `docs/tasks/closed/h79-informatica-2026-report.md`.
- Pipeline: `PipelineRebuildService`, `OpenAlexBulkImportService`, `DoajDataService`,
  `ScholardexProjectionBuilderService.buildOpenAlexApcMembershipRows`, `PostgresReportingLookupFacade.isFeeJournal`.
- No new engine; Slice A is a small ingest change, Slice B is ops, Slice C is separable feature work.

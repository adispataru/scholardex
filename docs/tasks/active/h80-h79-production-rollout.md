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

## Slice A — fold source-APC derivation into the OpenAlex bulk import (CODE)

**Decision (2026-07-04): fold into the bulk importer** (single works-stream pass, no second ~23 s scan).

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

## Slice B — production rollout via full rebuild (OPS)

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

## Slice C — deferred H79 sub-rules (SEPARATE feature work, not deploy)

Not required for go-live; split out so they don't block the rollout:
- **Posters & system demonstrations** — same `id_parA82` reduction as workshops (currently only the `workshop` token is
  detected; posters/demos score full parent points). Needs poster/demo detection + the same category relabel + `topAB`.
- **Per-pub-year APC edition resolution** — resolve the fee flag against the closest-earlier DOAJ/OpenAlex edition for
  the publication year (v1 uses the single current snapshot as a retroactive floor). Needs historical editions.
- **b↔c 20% compensation** — up to 20% of perspective-b thresholds transferable from perspective c (niche).

## Dependencies / references

- Closed task doc (full H79 slice record): `docs/tasks/closed/h79-informatica-2026-report.md`.
- Pipeline: `PipelineRebuildService`, `OpenAlexBulkImportService`, `DoajDataService`,
  `ScholardexProjectionBuilderService.buildOpenAlexApcMembershipRows`, `PostgresReportingLookupFacade.isFeeJournal`.
- No new engine; Slice A is a small ingest change, Slice B is ops, Slice C is separable feature work.

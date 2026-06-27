# H64 Canonical projects (unification across sources)

**Status:** Planning
**Created:** 2026-06-16

## Goal

Introduce a canonical **`ScholardexProject`** entity that researchers reference across **every report that
scores projects** (physics A9/A10, FEAA project budget, CS Info_D, etc.), unifying project identity +
attribution from multiple sources. **The primary value is project *unification* (one trusted project
record + who led it), not budget** — budget is one opportunistic attribute.

## Why (investigation 2026-06-16)

Projects today are loose, free-text `Grant Cercetare` activity instances (`Rol`/`Buget`/`Nume Proiect`
strings) — no shared identity, no trust, re-typed per report. Source landscape (all verified live):

| project type | identity/metadata | director/role (attribution) | per-partner budget |
|---|---|---|---|
| **EU (EC)** | CORDIS + OpenAIRE | ✗ org-only → user-declared | ✅ CORDIS `ecContribution` |
| **RO national (UEFISCDI/PN-III)** | **brainmap only** | ✅ **brainmap** (director person) | ✗ → admin/user |

- **OpenAIRE has NO Romanian national projects** (OPTILAB / `PN-III-P2-2.1-BG-2016-0046` / title all → 0;
  SERRANO → 1, so search works). OpenAIRE = EU-funder only.
- **CORDIS** has EU projects with **per-partner `ecContribution`** (confirmed UVT/SERRANO = €270k, matching
  the user-entered figure) + activityType/PIC/role + EuroSciVoc classification; free bulk CSV, no key.
- **brainmap** is the *only* programmatic source for RO national projects and is rich: acronym, code, plan,
  programme, competition, funder, domains, coordinator + partners with roles, **director as a person+role**,
  contract no./date, dates, website, bilingual abstract. **No budget shown.**

## Source strategy (post-roast)

- **brainmap → offline dump generator** (the `scopus-python/dumper.py` model), NOT a live dependency.
  Playwright login validated (natural homepage→Log In flow defeats the WAF `550`; creds from gitignored
  `scopus-python/.env`, never logged). **Gentle pacing to avoid account lock**; ToS caveat; prefer a
  UEFISCDI data-export if obtainable.
- **CORDIS → bulk import** (EU metadata + per-partner budget). Highest-value, cleanest source.
- **OpenAIRE → deferred** to "EU discovery + publication↔project links" (nice-to-have, not core).

## Pipeline (same 4 stages as publications/affiliations)

| stage | Project addition |
|---|---|
| **Events** | `cordis.import_events`, `brainmap.import_events` (+ user/admin) |
| **Source facts** | `cordis.project_facts`, `brainmap.project_facts`, `user_defined.project_facts` (+ per-source partner/contribution) |
| **Canonical facts** | `scholardex.project_facts` + **`scholardex.project_partner_facts`** (project↔affiliation+contribution, like `author_affiliation_facts`); merge by grant code with **per-field provenance**; resolve each partner to a canonical `scholardex.affiliation_facts` |
| **Projection** | `reporting_read.scholardex_project_view` + `scholardex_project_partner_fact` (keyed by affiliation) — what scoring reads |

- **Affiliation tie** reuses the existing canonical multi-source identity (`ScholardexAffiliationFact`:
  `scopusAffiliationIds[]`/`wosAffiliationIds[]`/`nameNormalized`/`aliases`/`country`): add **PIC** /
  brainmap-org-id as cross-source ids; match **PIC primary, normalized-name+country fallback** (CORDIS uses
  the Romanian legal name → normalizes onto ours).
- **Researcher↔project** = a join fact (declared role: director/responsabil/membru), mirroring
  `authorship_facts`. brainmap supplies the director person for RO projects; EU projects stay user-declared
  with **CORDIS "UVT participated ✓" verification**.

## Open decisions (settle before building — from the roast)

1. **Budget semantics**: does the indicator want the **org's contribution** or the **person's led-team
   share**? If team-share, *no source gives it* (CORDIS is org-level) → it stays user-declared and the
   "trusted budget" framing is dropped. **Resolve first** — it decides whether budget ingest is worth it.
2. **Architecture weight**: full event-sourced pipeline vs. a lighter **periodic reference-import + picker**
   (projects are low-volume slow reference data, unlike streaming publications). Pipeline gives
   provenance/auditability consistency; the lighter path is far cheaper. Justify the choice explicitly.
3. **brainmap production mechanism**: admin-run import tool vs. UEFISCDI data-export request (not a live scraper).
4. **Currency normalization** (RON↔EUR, bnr.ro rate per year — already specified in the physics methodology).
5. **Attribution trust**: keep researcher↔project role explicit + verified (existence/budget trusted, role audited).

## Decoupling

Physics A9/A10 + FEAA project indicators **ship now on the existing `Grant Cercetare`/`Buget` activity** —
this task is independent and must not block the reports.

## Exit criteria

- Canonical `ScholardexProject` exists with partners tied to canonical affiliations; CORDIS (EU) +
  brainmap (RO) ingested via offline dumps → the pipeline; per-field provenance; researcher↔project
  references with role.
- Reports that score projects can reference a canonical project; budget resolved by precedence
  (CORDIS → admin → user) with provenance shown; admin can edit/add budget + create manual projects.
- brainmap dump generation is offline, rate-limited, credential-safe; no live dependency.

## Status (2026-06-27) — brainmap source dump DONE; CORDIS hand-entry; importer next

- **brainmap (RO national) — DONE.** `scopus-python/brainmap_dump.py --manual --extract` produced
  `data/brainmap/uvt_projects.jsonl` = **341 UVT projects** (gitignored; PII = director names). Coverage:
  code/pkXProiectId/funder/competition/plan/detailHref 100%, title/coordinator 95%, **director 89%** (305/341 — the
  rest are institutional programs with no personal director, legitimate), start/end year ~45% (sparse on the list;
  recover from the code call-year or a phase-2 detail pass). Funders: UEFISCDI 241, MEd 64, **EC 22**, IFA 8, ROSA 2.
  Per-record fields: `pkXProiectId, code, title, detailHref, plan, competition, directorFirst/Last/Role, coordinator,
  funder, startYear, endYear`.
  - **How it works (hard-won — see memory):** brainmap is a stateful "jas" JS app. Bare `?we=<module>` errors; navigate
    by following the page's own token-bearing link. The institution filter is a jQuery-UI autocomplete fragile to
    script → `--manual` headful: a human picks UVT + Caută, then the script harvests the **searchAdvanced results
    LIST** (fields are `<prefix>_list.<field>@<row>`, 10/page, ~35 pages) — NOT 341 detail pages. Resume-safe.
- **CORDIS (EU) — hand-entry.** Only **19** UVT projects + the per-project detail download is manual, so they go in
  via the app's admin/user entry rather than a bulk importer (the user supplies + a CORDIS update comes from the user).
- **EU cross-check (2026-06-27, brainmap 22 EC funder=`EC` vs the 19-row CORDIS export):**
  - **Join key (confirmed clean):** the brainmap code's trailing numeric segment IS the EU grant id =
    CORDIS `ID` (e.g. `Horizon-239038-101061610` → `101061610`; `FP7-86416-211338` → `211338`). Dedup/merge by
    that grant id — no fuzzy matching needed.
  - **Overlap = 9** (all H2020/Horizon): 101003517, 101035810, 101036006, 101061610, 101094529, 101126643,
    101131420, 101177908, 101236475.
  - **CORDIS-only = 10** — modern EU projects brainmap doesn't list at all (incl. **SERRANO 101017168**, the €270k
    benchmark; LEARNVUL, SMARTEES, Dynamics 777911, SESAME NET, VI-SEEM, CONNECTING Nature, …).
  - **brainmap-only = 13** — all **FP7** (pre-H2020; the CORDIS export was scoped to H2020/Horizon).
  - **Implications:** neither source is complete for EU (union = **32** distinct: 9+10+13). **CORDIS is authoritative**
    for modern EU + is the *only* source of per-partner € budget. brainmap adds the FP7 historical tail (no budget).
    **EU projects carry NO director in brainmap** (`dir=None` for all 22 — the 89% director coverage is the RO
    national UEFISCDI/MEd projects), so **EU attribution stays user-declared** regardless of source — as planned.
  - **Importer rule:** ingest both; dedupe by grant id; for the 9 overlap **prefer CORDIS** (it carries budget +
    metadata; brainmap adds neither director nor budget for EU rows).
- **NEXT — the importer:** ingest `uvt_projects.jsonl` → `brainmap.project_facts` → canonical `ScholardexProject`
  (+ `project_partner_facts`), researcher↔project join (director from brainmap), partners→canonical affiliation.
  **Settle open decision #1 (budget semantics) first** — brainmap has no budget, so A10 budget stays
  CORDIS/admin/user-declared regardless; that argues for the lighter reference-import + picker over a full pipeline.

## Importer scope (2026-06-27) — decisions locked + slices

**Decisions (this settles open-decisions #1 + #2):**
- **Budget = declared-only.** brainmap has NO budget (verified: not in the 341 list records nor the detail page).
  CORDIS budget is org-level, not a person's led-team share → the canonical project carries `budget = null`; A10 €
  stays **user/admin/CORDIS-declared on the activity**. The canonical project supplies identity + director, not budget.
- **Architecture = canonical + projection** (mirror the OpenAlex *bulk* path — no separate event stage):
  `data/brainmap/uvt_projects.jsonl` → `brainmap.project_facts` (Mongo source) → canonical `ScholardexProjectFact`
  (merge-ready by EU grant id) → `reporting_read.scholardex_project_view` (Postgres) → read-port → entity-picker.
- **Attribution = researcher self-links** via the picker (declare role director/member on a project activity); auto
  director→researcher name-matching deferred to a later enrichment slice.
- **Sources = brainmap-only first** (341); CORDIS (19 hand-entry) + user-defined projects are a follow-up slice.

**Reference templates (from the code map):** `OpenAlexBulkImportService` (JSONL stream → source facts, config-keyed
file) · `CanonicalGraphBuilder.buildAffiliations` (source→canonical + `ScopusAffiliationRorMatcher.match(name,city,
country)` for partner/coordinator resolution) · `ScholardexProjectionBuilderService.rebuildViews` (Mongo→Postgres JDBC
batch, FULL_REPLACEMENT tables) · `PostgresScholardexProjectionReadPort` + `EntityAffiliationApiController`
(`/api/entities/affiliations`) for the read-port + picker · `ActivityInstance.referenceFields[PROJECT_GRANT_ID]` (the
project-reference slot already exists) · Flyway `db/migration/` for the view table.

**Identity / merge key:** canonical id `sproj_<hash(key)>` where key = **EU grant id** (the brainmap code's trailing
numeric segment) for `funder=EC`, else the **brainmap code** (`PN-III-…`) for RO national. Store both `euGrantId`
(nullable) + `code` so CORDIS/user later merge by `euGrantId`. (See the EU cross-check above for the join key.)

### Slices
> **Slice 1 — DONE (2026-06-27).** Full chain shipped + tested (3 commits on `codex/h66b-builders`):
> - **1a** `BrainmapProjectRecord` DTO + `BrainmapProjectFact` (`brainmap.project_facts`) + repo +
>   `BrainmapProjectImportService.importAll` (streamed, idempotent by `pkXProiectId`, no-budget) + config
>   `core.brainmap.bulk.projects-file` + `PipelineRebuildService.ingestBrainmapProjectsIfConfigured()`.
> - **1b** `ScholardexProjectFact` (`scholardex.project_facts`) + repo + `ProjectCanonicalizationService.rebuild()`
>   (wipe-first; id `sproj_<hash(euGrantId|code)>`; `euGrantId` = code trailing segment for `funder=EC` only;
>   coordinator→affiliation via a **token-signature index** over name+aliases — robust to the brainmap "Universitatea
>   de Vest Timisoara" vs OpenAlex "…de Vest **din** Timișoara" gap the ROR matcher's name-only fuzzy tier misses;
>   `budget=null`). Wired into both rebuild paths after the affiliation backbone; added to the managed + canonical wipe sets.
> - **1c** Flyway **V19** `reporting_read.scholardex_project_view` + `ProjectProjectionService.rebuildView()`
>   (Mongo→Postgres JDBC batch, full-replacement) wired after canonicalization. **End-to-end Testcontainers test**
>   (Mongo+Postgres) green: JSONL→source→canonical→view, coordinator resolves to UVT, euGrantId for EC, budget null,
>   arrays land, idempotent. (Note: the live ~341 land on the next full/derive rebuild — the import is config-on.)
>
> Original slice-1 plan (for reference):
1. **Data layer (brainmap → canonical → projection).** `BrainmapProjectRecord` DTO; `BrainmapProjectFact`
   (`brainmap.project_facts`) + repo; `BrainmapBulkImportService.importAll(projectsFile, batchId, correlationId)`
   (config `core.brainmap.bulk.projects-file=data/brainmap/uvt_projects.jsonl`); canonical `ScholardexProjectFact`
   (`scholardex.project_facts`) + repo; `CanonicalGraphBuilder.buildProjects(...)` (1:1 brainmap→canonical, merge by
   `euGrantId`, coordinator name → canonical affiliation via the ROR matcher; `budget=null`); Flyway
   `scholardex_project_view` + `ScholardexProjectionBuilderService.buildProjectViews()` (add to FULL_REPLACEMENT);
   wire into `PipelineRebuildService.rebuildAllDerivedFromSource()` after OpenAlex. **Test:** Testcontainers
   end-to-end JSONL → facts → canonical → view; coordinator resolves to the UVT canonical affiliation; 341 land.
2. **Picker + reference (self-link).** `PostgresScholardexProjectReadPort.search(q,page,size)` + `findProjectById`;
   `EntityProjectApiController` `/api/entities/projects?q=`; workspace UI to attach a canonical project to a project
   activity via `PROJECT_GRANT_ID` + declare role (director/member) + optional budget; researcher↔project = the
   activity reference (reuse `ActivityInstance.referenceFields`, no new join model). **Test:** search + reference round-trip.
3. **Consume in reports (decouple-safe).** Physics A9 (director count) + A10 (declared budget) read the referenced
   canonical project where present (title/code/funder/director display from canonical), free-text fallback otherwise.
4. **(Later) CORDIS + user-defined + auto-attribution.** CORDIS hand-entry model + user-created projects → merge into
   canonical by `euGrantId` (budget from CORDIS/user → A10 trusted budget); auto director→researcher name-match + confirm UI.

## Dependencies / relation

Builds on the existing ingestion pipeline + canonical affiliation identity. Sibling to [H63](h63-openalex-enrichment.md)
(OpenAlex enrichment). Consumers: physics, FEAA, CS project indicators.

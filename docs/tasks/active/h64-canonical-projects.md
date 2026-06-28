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

## Slice 4 — scope (2026-06-27): trusted budget, director attribution, funder gating

Slice 4 turns the canonical link from *display* (slice 3) into a *scoring input*. **The linchpin (4a)** is one seam: today
`ActivityReportingService.calculateActivityScore` builds formula variables only from the activity's declared `fields`
(`Rol`, `Buget`, …); `referenceFields` are captured but never resolved. Resolving `PROJECT_GRANT_ID` → canonical
project and injecting its fields (`proj_budget`, `proj_funder`, `proj_director`, …) unlocks budget-prefer, funder
gating, AND director cross-check at once. Decouple-safe: existing formulas don't reference the injected vars, so totals
are unchanged until an indicator opts in.

### Sub-slices (independent; pick per appetite)

- **4a — scoring-time reference injection (enabler).** In `ActivityReportingService`, when an activity carries a
  `PROJECT_GRANT_ID` ref, resolve it (via `ScholardexProjectReadPort`, mirroring slice 3a) and inject canonical fields
  into the `FormulaContext` under a stable prefix (`proj_*`). Add an optional **budget-prefer** rule: when the linked
  project has a non-null canonical budget, expose it as `proj_budget` so an indicator can prefer it over declared
  `Buget`. Ship with zero formula changes (pure capability); tests assert injection + that un-opted formulas are
  unaffected.
- **4b — manual/admin projects + budget (A10 trusted budget).** `UserDefinedProjectFact` (`user_defined.project_facts`,
  mirrors `UserDefinedPublicationFact`: `approved`/`reviewState`/submitter) + repo + admin create/edit controller
  (mirror `AdminResearcherProfileController`) + DTO; merge into `ProjectCanonicalizationService.rebuild()` alongside
  brainmap **by `euGrantId` (else code)**, with user-defined **winning for budget** (brainmap has none). This is where
  the **19 CORDIS** EU projects are hand-entered with their per-partner € (`ecContribution`). Then A10 uses
  `proj_budget` (via 4a) when the activity references a project, else declared `Buget`.
  **⚠ Decision — precious vs wiped:** `user_defined.publication_facts` is currently in `MANAGED_DERIVED_COLLECTIONS`
  (wiped on full rebuild). User-entered project budgets must SURVIVE rebuilds → `user_defined.project_facts` must be a
  *source/precious* collection (not in the managed wipe set; re-read each rebuild). Confirm how user-defined pubs avoid
  data loss today and follow the safe path.
- **4c — director → researcher attribution (A9).** Add `directorResearcherId` to `ScholardexProjectFact`; a matching
  pass (brainmap `directorFirst/Last` → `ResearcherProfile` via `UserRepository.findByResearcherProfileNameContaining
  IgnoreCase`, with a confidence gate) sets it; **homonym risk → a confirm/review step** (admin or the researcher's own
  workspace "is this your project?"). Keep A9 **activity-declared** (researcher self-declares `Rol=Director` on a Grant
  Cercetare referencing the project) and use the canonical director as **verification + auto-suggest** (e.g. pre-offer
  the activity), NOT a silent score change. A pure-canonical A9 (derive directorships solely from
  `directorResearcherId`) is a larger scoring-path redesign — out of scope unless explicitly chosen.

### Decision (2026-06-27): build **4a + 4b** (A10 trusted budget). 4c (director attribution) + funder gating deferred.
**CORDIS entry mechanism chosen: a live admin "search CORDIS online" endpoint.** ✅ **Feasibility CONFIRMED (no auth):**
the per-project XML export `https://cordis.europa.eu/project/id/{ID}?format=xml` is public and carries everything —
per-organization `<organization type="coordinator|participant" ecContribution="…"><legalName/><shortName/>
<address><country/>>` plus project `<acronym>/<title>/<startDate>/<endDate>/<totalCost>/<ecMaxContribution>/
<frameworkProgramme>`. So the admin endpoint: given a CORDIS grant ID → `WebClient` GET the XML → parse → match the UVT
org (legalName → our affiliation, mirroring slice-1b's coordinator resolution) → take its `ecContribution` as the
budget → preview → admin confirms → write a `user_defined.project_fact` (euGrantId = the ID). (The registered CORDIS
JSON API + SPARQL also exist but need a key; the open XML export avoids that.) Free-text/acronym search → ID is a later
enhancement; **ID-based lookup is primary** (we already have all 19 IDs from the EU cross-check / cordis CSV).
Build order: **4a (enabler, no external deps) → 4b model + admin manual create/edit → 4b CORDIS-XML fetch endpoint.**

### Recommended order & forks
- **Value pairing:** 4a + 4b together deliver A10 trusted budget (the concrete need behind the CORDIS effort). Funder
  gating is a small add on 4a (one indicator opts into `proj_funder`). 4c is independent and the riskiest (matching).
- **CORDIS entry mechanism (fork):** the 19 are hand-entry, but `cordis-search-results.csv` already has their identity
  (ID/acronym/title/dates) — a thin CSV importer could mint the 19 `user_defined.project_facts` (identity only) so admin
  only adds the € per project, vs. fully manual creation of each. (Per-partner budget isn't in that CSV.)
- **Read source for injection (fork):** scoring-time resolution via the Postgres `scholardex_project_view` read port
  (consistent with slice 3, but couples scoring to the projection being fresh) vs. the Mongo canonical repo (always
  current). Recommend the read port for consistency.

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

> **Slice 2 — DONE (2026-06-27).** 2 commits: **2a** `ScholardexProject{ListItem,Page}Response` DTOs +
> `ScholardexProjectReadPort`/`PostgresScholardexProjectReadPort` (search ILIKE title/code/funder/eu_grant_id/
> coordinator_name; `findById`; director = `CONCAT_WS`) + `EntityProjectApiController` `GET /api/entities/projects`
> (+`/{id}`); tested via @WebMvcTest contract + Postgres Testcontainers read-port. **2b** workspace
> `workspaceActivities.js` renders a `PROJECT_GRANT_ID` reference as a debounced `/api/entities/projects` autocomplete
> (stores `sproj_` id in a hidden input keeping the `data-(create-)ref-field` attr → unchanged save handlers persist
> it; edit form resolves id→label via `/{id}`); dropdown CSS; bundle rebuilt; assets/UI/route guardrails pass.
> **Verified:** backend integration + contract tests green, bundle guardrails green. **Live browser happy-path not yet
> run** — needs (a) a pipeline rebuild to populate `scholardex_project_view` and (b) an Activity defining
> `PROJECT_GRANT_ID` (config). (Pre-existing unrelated `verify-duplication-guardrails` failure in
> `ComputerScienceScoringService` — fails on the base commit too, not from this work.)

### Slice 2 — scope (2026-06-27) + CS/Informatică alignment

**The reference slot is already universal.** Every project indicator across reports uses the **same**
`Activity.ReferenceField.PROJECT_GRANT_ID` slot on its project activity, stored on
`ActivityInstance.referenceFields[PROJECT_GRANT_ID]`:
- **CS / `informatica-2016`** → activity **"Granturi"** (binding role `activities-perspectiva-d`, STACKED_BLOCKS;
  cols C=description, H=category, K=score). Scored by the generic activity formula on declared fields; **CS does NOT
  gate on role or funder** today, so the canonical reference is purely an **identity/verification upgrade** (link the
  free-text grant to a trusted `sproj_` record + show code/title/funder) — the score still comes from the declared
  fields. No CS-specific code.
- **Physics** → `Grant Cercetare` (A9 director count via `Rol`, A10 € via `Buget`).
- **FEAA** → its project/budget activity.

⇒ **Slice 2 is report-agnostic**: one picker bound to the `PROJECT_GRANT_ID` field type serves CS + physics + FEAA at
once. **Config prerequisite (not code):** each report's project Activity *definition* must declare `PROJECT_GRANT_ID`
in its `referenceFields` for the picker to render (admin/seed config; verify per deployment).

**Build (mirror the affiliation picker `EntityAffiliationApiController` + `PostgresScholardexAffiliationReadPort`):**
- **Backend** — `ScholardexProjectListItemResponse`/`PageResponse` DTOs (id, code, title, funder, director,
  startYear/endYear, coordinatorName); `PostgresScholardexProjectReadPort.search(q,page,size,sort,direction)` (ILIKE
  over title/code/funder/eu_grant_id/coordinator_name on `scholardex_project_view`) + `findById`; `EntityProjectApi
  Controller` `GET /api/entities/projects` (+ `/{id}` for label resolution).
- **Frontend** (`frontend/src/modules/workspace/workspaceActivities.js`; `app.js` is the generated bundle → edit
  source then `npm run build` + the `verify-assets`/guardrail scripts) — render a `PROJECT_GRANT_ID` reference field as
  a **debounced autocomplete** against `/api/entities/projects` (dropdown "code — title (funder, years)"; select →
  store `sproj_` id) instead of the current plain `<input data-(create-)ref-field>`; resolve a stored id → label via
  `/{id}` on render. Other reference types stay text inputs for now. (No existing client autocomplete to copy — small
  debounce+dropdown helper.)
- **Tests** — read-port (Postgres Testcontainers: search by code/title/funder, findById, paging); controller slice
  (mind the `@WebMvcTest` `@MockitoBean` completeness gotcha); reuse the asset-verify guardrails for the bundle.
- **Out of scope → slice 3:** resolving the reference to canonical metadata *at scoring* (A9 director cross-check,
  funder gating) and report-export showing "code — title" instead of the raw id (cheap; can ride along if time).

Researcher↔project stays the activity reference; role/budget stay declared on the activity fields (matches the
self-link decision).
3. **Consume in reports (decouple-safe).** Physics A9 (director count) + A10 (declared budget) read the referenced
   canonical project where present (title/code/funder/director display from canonical), free-text fallback otherwise.

> **Slice 3 — DONE (2026-06-27).** 1 commit. **3a** `ActivityBlockProjector` injects `ScholardexProjectReadPort` and
> resolves a `PROJECT_GRANT_ID` reference in report output to `code — title (funder) — Director: First Last` (raw-id
> fallback when unresolved → export never breaks); scores untouched. **3b** `ProjectReferenceFieldSeedRunner`
> (CommandLineRunner, additive+idempotent, `core.projects.reference-activity-names`, blank=inert) + `ActivityRepository.
> findByName` enables `PROJECT_GRANT_ID` on the named project activities. Tested: projector (label/fallback/passthrough)
> + seed runner (add/idempotent/preserve/blank/missing); context loads; reporting-transfer suite green. **To turn on
> per deployment:** set `core.projects.reference-activity-names=Grant Cercetare,…` (was inert/empty by default).

### Slice 3 — scope (2026-06-27)

**Principle: scores do NOT change.** Role (A9) and budget (A10/CS) stay declared on the activity fields (`Rol`,
`Buget`); brainmap has no budget and CORDIS/auto-attribution are slice 4. So Slice 3 surfaces the **verified project
identity/provenance** in report output + enables the config — it must not alter any indicator total (the doc's
decouple invariant). Confirmed against the consumption path:
- `ActivityReportingService.calculateActivityScore` scores from declared fields only — untouched.
- `ActivityBlockProjector.buildActivityInstanceDescription` currently appends the **raw `sproj_` id** verbatim to the
  activity description (the only place a `PROJECT_GRANT_ID` reference reaches output today).

**Build:**
- **3a — display resolution.** Inject `PostgresScholardexProjectReadPort` into `ActivityBlockProjector` (a `@Component`;
  inject directly — do NOT route through `ReportingLookupPort`, to avoid the dual-facade `@Primary` delegator churn).
  In `buildActivityInstanceDescription`, when a reference key is `PROJECT_GRANT_ID`, resolve the `sproj_` id via
  `findById` → render `code — title (funder)` (and optionally the canonical director) instead of the raw hash; fall
  back to the raw value when unresolved (pre-rebuild / deleted). Batch or memoize if a report has many project rows
  (low volume — likely unnecessary). **Test:** projector unit test (resolved label vs raw-id fallback) + a small
  integration check that a linked activity renders the canonical label.
- **3b — config enablement.** Add `PROJECT_GRANT_ID` to the project Activity definitions so the picker (slice 2) and
  this resolution actually engage: physics `Grant Cercetare` (A9/A10), CS `Granturi`, FEAA's project activity. These
  are Mongo activity *definitions* (per-deployment data) — **decision:** an idempotent, additive startup seed runner
  driven by a config list (`core.projects.reference-activity-names=…`, mirroring `OrgSeedRunner`/`AdminUserBootstrap
  Runner`) that ensures the ref field on the named activities if absent (logged, skip-if-present), **vs** a one-shot
  admin action. Recommend the seed runner (version-controlled, deterministic, additive-only — never removes fields).
  The exact activity-name set is the deployment's call (the live run showed prod has `Grant Cercetare` but not a
  `Granturi` activity yet).

**Out of scope → slice 4:** auto director→researcher matching (A9 auto-attribution), CORDIS + user-defined budget
merge (A10 trusted budget), EU-vs-national funder gating. Slice 3 leaves every score identical; only the report's
project *labels* become trusted identities.
4. **(Later) CORDIS + user-defined + auto-attribution.** CORDIS hand-entry model + user-created projects → merge into
   canonical by `euGrantId` (budget from CORDIS/user → A10 trusted budget); auto director→researcher name-match + confirm UI.

## Dependencies / relation

Builds on the existing ingestion pipeline + canonical affiliation identity. Sibling to [H63](h63-openalex-enrichment.md)
(OpenAlex enrichment). Consumers: physics, FEAA, CS project indicators.

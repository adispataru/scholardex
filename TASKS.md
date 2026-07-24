# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H84` Researcher-flagged publication merges (durable across rebuilds).
  Consumer: Florin's FedCSIS duplicate — no DOI (FedCSIS never assigned), arrived via Scopus + a second
  route; canonical identity is titleNormalized+coverDate+creator and the two routes carry DIFFERENT
  coverDates (2011-12-14 vs 2011-01-01) → distinct identities, near-identical citation lists split.
  Confirmed locally + found a SECOND latent pair the same way ("Enabling model driven engineering…",
  2012-08-14 vs 2011-01-01). Design (user direction 2026-07-24): researcher flags a merge candidate from
  the publications screen (+ auto-SUGGEST same-normalized-title pairs there); admin approves; the approved
  decision persists in a durable side-table (pattern: publication_authorship_decisions / DBLP evidence)
  consulted during canonicalization — so full rebuilds RE-APPLY merges and the decision acts as an
  identity hint (title+creator match with coverDate tolerance), enriching rather than fighting rebuilds.

- [ ] `H85` OM 2026 conference-list amendments: ACM/EPTCS → C; UCC Companion mislabeling.
  **SCOPED 2026-07-24** (`docs/tasks/active/h85-om2026-acm-eptcs-c-floor.md`).
  The 2026 OM amends the CORE list: "categoria C va include și lucrările publicate în ACM, EPTCS și LNCS
  care nu sunt în categoriile A*, A și B" — we implement only the LNCS→C floor (correct for 2016, whose
  amendment is LNCS-only). UCC is CORE-Unranked (2021/2023/2026), so the amendment decides: ACM-published
  from 2013+ (IEEE/ACM co-sponsorship; NOTE the DOI stays IEEE-branded, so detect via proceedings-name
  tokens/publisher, not DOI prefix) → C in the 2026 fișă, D in 2016. Implement as a 2026-scoped floor next
  to the LNCS special case (per-standard gating precedent: workshopCategory2026). Second facet: Florin's
  UCC entries display as "UCC Companion" though published in the MAIN volume — investigate on prod (Scopus
  venue assignment vs our forum merge); near-term remedy exists via admin bulk reassign-forum.
  **Slice A DONE 2026-07-24** (floor + 4 clones, deployed + prod data applied). **Slice B DONE** —
  Companion mislabel not present in current prod data (ask Florin where he sees it).
  **Slice C DONE locally 2026-07-24** — the sweep's conf/X re-stamp destroyed the ACM name signal
  ("UCC", empty publisher), so Slice A missed exactly Florin's papers; fix preserves the displaced raw
  proceedings forum as `originalForumId` (fact → view → Postgres V25 → ScoringPublication) and the floor
  consults it. Rollout: deploy, then admin "Full derived-data rebuild" backfills; then Florin's refresh
  should show UCC 2012/2014 = C (quarter ACM), UCC 2011 = D.

- [ ] `H83` University rankings — QS + ARWU ingestion, best-of resolution, URAP back-catalog.
  **SCOPED 2026-07-24** (`docs/tasks/active/h83-university-rankings-best-of.md`). OM 3019/2025 footnote *3
  (same in 2016): D(viii)/D(ix)/D(xi) score by the BEST position across QS/URAP/ARWU — we only have URAP
  (2018–2024). S1: scrape URAP 2010–2017 archives → same xlsx shape → re-run `/general/urap` (closest-year
  becomes exact for old visits). S2: generic `UniversityRanking{name, source, year→rank}` + loaders for
  ARWU (Kaggle 2003–2025 full back-catalog) and QS (2017–2022 + 2025/2026; older partial). S3: best-of
  lookup facade (min rank across sources, closest-year per source, provenance in scoringInfo) consumed by
  `UniversityRankScoringService` — bracket formulas untouched. S4 optional: UNIVERSITY_NAME autocomplete
  picker mirroring the CORE conference picker. Verification: Florin's Pisa / Aix-Marseille cases.

- [x] `H82` **DONE (2026-07-24, `39a1bbf6`)** Scopus re-sync re-enriches EXISTING publications (narrow-first).
  The coverDate precedence fix (`069153f3`) promised "existing wrong dates heal on the next Scopus author
  sync" — false in practice: a FULL publication sync reports "Imported 0 items" because already-imported pubs
  are skipped before `ScholardexPublicationCanonicalizationService` runs, so the enrichment that would claim
  Scopus's coverDate/coverDisplayDate never touches them (verified in prod 2026-07-24 on the FGCS 2008→2009
  case; healed via the full derived-data rebuild). Shipped: FULL mode collects all seen eids and
  `ScopusExistingPublicationReenrichmentService` re-claims coverDate/coverDisplayDate on drifted canonical
  pubs (narrow field list by design — broad re-enrichment risks re-clobbering DBLP forums/admin fixes),
  dirty-marks with the pubs' own sourceBatchIds and pushes the per-batch partial projection rebuild.
  Widening the field list (title/authors/forum with clobber guards) stays open for a future consumer.

- [ ] `H76` WoS CPCI onboarding — **MVP done + live; only blocked/attributed remainders open.**
  Plan: `docs/tasks/active/h76-wos-cpci-onboarding.md`. Background: `wosForumIds` come only from the WoS **journal**
  MJL/JCR, so WoS-indexed *conferences* were misclassified as non-WoS (1,014 Scopus-only conference forums),
  undercounting the WoS h-index (`H67`) — material for CS.
  **S1+S2 DONE + live (2026-06-25):** DOI→publication→forum (then ISSN/ISBN/title) matcher over UVT's own WoS
  **Records** export; `WosCpciOnboardingService` + `POST /admin/initialization/wos/cpci/{dryRun,apply}`. 1,302/1,984
  UVT proceedings matched → **211 conference forums tagged `wosCpciIndexed=true`** (new boolean, read only by
  `applyCitationSourceSplit`). Projection refresh lifted `wos_citation_count` **+9,909 across +503 pubs**.
  **Remaining (both out of the MVP's hands):**
  - **Physics/FF forum-scoring** — `wosCpciIndexed` feeds ONLY the citation source-split, NOT forum-membership/WoS-forum
    *paper* scoring; wiring the paper-count read to honor the flag (or projecting a CPCI membership row) is **`H65`
    work**.
  - **S3 broad citing coverage — BLOCKED on a WoS API key.** The UVT-scoped roster covers venues UVT publishes in; the
    full WoS citation graph (all citing venues) needs a programmatic Core-Collection pull (UI Records export caps
    ~1,000/file). Revisit when an Expanded/Starter API key is available.

- [ ] `H68` Advanced criteria / threshold extensions (foundational, from the standards assessment).
  Goal: extend the criteria engine for recurring patterns — **post-PhD temporal anchor**, per-indicator/
  per-group **caps (plafoane)**, **best-of single-indicator assignment**, **count + point** mixed criteria,
  **Da/Nu** qualitative gates, cross-criterion compensation. Modest config-level extensions on the existing
  per-position threshold model. Consumers: FSGC, drept, FLIT, FAD, FSP, sport, fizica.
  **SCOPED 2026-06-30** (`docs/tasks/active/h68-criteria-extensions.md`).
  **DONE (2026-06-30, `bdd4379b`):** per-indicator caps (`applyPointsCap`); slice 1 — criterion `mode`
  count-vs-points + unified the two criterion-score paths (fixed the H65 weights bug: was applied at render/export but
  not at compute); slice 2 — criterion-level cap (`maxTotal`). `phdAwardYear` profile field added (`6fe81f97`) as the
  post-PhD anchor's data hook.
  **Remaining — all DEFERRED to first consumer** (ambiguous semantics until a real report needs them): post-PhD
  temporal anchor (field exists, no scoring use yet), Da/Nu qualitative gates, best-of single-indicator assignment,
  cross-criterion compensation. No active consumer — revisit when FSGC/drept/FLIT/FAD/FSP/sport/fizica needs one.
  **Slice 3 DONE (2026-07-24, `4c86671b`): percent-of-criterion caps.** OM 3019/2025 Informatică D caps D(x)/D(xiv)
  (+D(xvii) later) at 10% of the perspective total; the 2016 standard has the same caps — both FV Info
  reports get flagged. Semantics pinned (user decision): **fixed point** — `capped_i = min(c_i, p_i·T)`
  with T the final total, water-filling closed form; candidate-favorable and the only reading satisfying
  the OM constraint against the final total. `Criterion.maxPercentOfTotal` map, second phase in the single
  `computeCriterionScores` core, order weights → percent caps → maxTotal. Full scope + numeric pins:
  `docs/tasks/active/h68-criteria-extensions.md` (Slice 3). Data change deploys AFTER code.

- [ ] `H81` Informatică 2026 Fișă (xlsx export/import). **SCOPED 2026-07-04**
  (`docs/tasks/active/h81-informatica-2026-fisa-xlsx.md`). A 2026 version of the `informatica-2016` xlsx Fișă, adapted
  from the 2016 template. Structure barely changes (FV Info 2026 has identical export roles); the deltas are the A*+A
  publication criterion (a `Centralizator` template formula) and a new perspective-d **director-project count**
  criterion (`Minim un proiect` as director, ≥50k EUR).
  - **Slice 1 — DONE 2026-07-04:** GenericActivity indicator `Info_D_Proiecte_Director` counts grants where
    `Rol != 'Membru' && budget >= 50000` (reuses Info_D_v's `B` pattern → no `Buget` field-type change, 2016 frozen).
    Added to FV Info 2026 only; live-verified florin count=1. Team-size/competition not in our data (self-declared).
  - **Slice 2 — DONE 2026-07-04:** `INDICATOR_TOTAL` scalar-cell export policy — `TemplateXlsxRenderer` 4-arg overload
    stamps a template cell with the run's per-role total (`snapshot.getTotals()`, already keyed by role for every report
    type). MANUAL cells untouched; missing total/sheet = warn-skip. Unit-tested; transfer suite green.
  - **Slice 3 — DONE 2026-07-04:** `report-templates/informatica-2026/template.xlsx` — `D-Perspectiva D!K24`
    "Număr proiecte ca director" (outside the points SUM, filled by the scalar cell) + `Centralizator` A*+A criterion
    (correct `J17+J18+K19+K20` subtotal; 2016's A*-only `E10` left frozen) + director-project `count>=1` criterion +
    an **Abilitare** block (rows 35–39: B 44/A*+A+B 28/A*+A 12, C 84/26, D 48, combined `AND` — no Total-points gate;
    references stable `D7`/`D11`/`D15`, doesn't touch Hirsch cells). **Perspectiva-B per-rank *Publicații de top* gates
    corrected to 2026** (CONF 16 / PROF 40 / HABIL 28) — aligns formulas with the sheet's own labels the 2016 template
    contradicted (`E10` checked 16 though `C10` said 40; `E8` gated lector though `C8` said "oricare"); prof A*+A subtotal
    fixed A*-only→A*+A.
  - **Slice 4 — DONE 2026-07-04:** `informatica-2026/binding.json` (+ `INDICATOR_TOTAL` scalar cell, quoted sheet name)
    + `Informatica2026ReportTypeImportSupport` (registry auto-discovered). FV Info 2026 already keyed `informatica-2026`
    (export was failing on the unregistered support — now resolves). Seed consistent. Unit-tested.
  - **Live E2E — DONE 2026-07-04:** booted `agent-dev`/8181, exported florin's FV Info 2026 Fișă (run
    `directorScore=1`) → HTTP 200 xlsx with `D-Perspectiva D!K24 = 1.0` (director count stamped end-to-end) + corrected
    perspectiva-B / A*+A / abilitare formulas intact; POI FormulaShifter correctly followed the B-Conferinte table
    expansion (>10 conf pubs) so the A*+A refs stayed semantically right. **H81 fully done.**

- [ ] `H50` Individual report export / read-only score-verification import.
  **STATUS (2026-06-30): mostly done — H62/H65 overtook most of the "remaining" list. The genuine gap is docx *import*
  verification (H50.6). Entry below refreshed.**
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Done since (via H62/H65): **docx export (H50.4)** is wired — `ReportExportFacade` renders any format the support declares, with the DOCX content-type + `TemplateDocxRenderer`. **Report-type coverage (H50.5) largely done** — bindings now exist for `informatica-2016`, `matematica-2016`, **`feaa-2024`**, **`fizica-ff`** (4 of the report types), each with a `ReportTypeImportSupport` that renders docx.
  Remaining: **docx *import*/verify (H50.6)** — the docx supports' `parse()` still throw `UnsupportedOperationException` (Fizica/Feaa), so score-verification upload is xlsx-only; implement docx parsing for the docx report types. Plus any report definitions still lacking a binding (the remainder of H50.5).
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

- [ ] `H80` H79 production rollout (get the Informatică 2026 report live). **SCOPED 2026-07-04**
  (`docs/tasks/active/h80-h79-production-rollout.md`). H79 code is merged + verified locally; the ingest→project pipeline
  (`PipelineRebuildService`) already folds in DOAJ APC + the OPENALEX membership projection + the project projection —
  the **only code gap** is that the OpenAlex source-APC derivation is a standalone endpoint, not a pipeline step.
  - **Slice A (code) — DONE 2026-07-04:** shared `OpenAlexSourceApcAggregator`; `OpenAlexBulkImportService.importAll`
    folds the per-venue APC derivation into the works/citers stream (mirroring the `referenced` institution-id threading)
    → `openalex.source_facts` produced in-DAG before the stage-4 projection. Standalone service + endpoint kept for
    manual re-runs. `BulkImportResult` gained `apcSources`/`apcFeeJournals` (logged in the rebuild). Unit-tested; full
    suite green. No pipeline-wiring change (the DAG already calls `importAll`).
  - **Slice B (ops) — DONE 2026-07-04:** the local `scholardex` Mongo/PG **is** the prod DB, so the full rebuild
    (`rebuildAllDerived?confirmation=RESET&reingest=true`, caffeinated+daemonized, ~34 min, 0 errors) **was** the rollout.
    Fold produced `source_facts` in-DAG (`apcSources=18575 apcFeeJournals=2140` from cleared-0); projection emitted 2,140
    OPENALEX apc=true rows; MDPI *Electronics* → `isFeeJournal=true`; florin's Electronics zeroed in FV Info 2026 (2016
    unchanged). Deploy to stage/prod is a later **data migration** (gated on Informatică backlog + public-UI polish).
  - **Slice C — closed 2026-07-04. Informatică scoring backlog now clear.** **C1 posters/system-demos — DONE:** neither
    Scopus (`cp`) nor OpenAlex (`article`) distinguishes them (verified in the raw dump), so a strict title-prefix detector
    (`Poster:`/`Demo:`/`Demonstration:`) reuses the slice-6 reduced path (category A*/A/B→C, C→D + 6/4/2/1 pts + `topAB`
    exclusion), 2026-gated (2016 unchanged), no indicator/seed change, unit-tested. **C2 per-pub-year APC — DROPPED**
    (standard keys APC to "momentul depunerii dosarului" = current state). **C3 b↔c 20% compensation — NOT a platform
    feature** (`id_parA118` discretionary — a committee exception like perspectiva-a ethics). **C4 CORE national/regional
    → C (`id_parA81`) — DONE:** completeness check found `parseRank` collapsed National/Regional→D; now preserved as
    `Rank.National`/`National_Regional`, scorer remaps →C (2026)/D (2016), version-gated; live-validated (124 National +
    7 Regional re-imported). **No short-paper exclusion exists in the CS standard** (the `rezumate/abstract` list is a
    different domain). **Deploy step:** stage/prod migration needs a CORE re-import
    (`POST …/general/coreConference`) — a Scopus/WoS rebuild does not re-import CORE reference data.

# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H106` Citations round 2 — Florin Fortiș's review of Alexandra Fortiș's FV Info 2026 export
  (2026-09-12). Two families: the xlsx export of the citations block is broken in prod, and citation
  COVERAGE misses what Scopus does not link by EID. Evidence gathered the same day (prod reads + local
  repro); every slice below is a separate discussion point.
  - S1 — **DONE 2026-09-12.** Export: writes into the 2026 template were silently lost. The 2026 `template.xlsx` was re-saved
    in H81 (`d41f0d0a`) with inline strings (`t="inlineStr"`); the 2016 template carries shared strings.
    POI's `setCellValue` on an inline-string cell is a no-op even in memory (verified: `setBlank()` first
    makes the write stick). Effect: every tile keeps the placeholder title "Articol Revista 1 (Nume
    Jurnal, 2017)" (Florin's b) and the citing rows keep the sample row's values "Titlu articol care
    citeaza"/"(niciunul)" (his c, "informații aleatorii"). `TemplateXlsxRendererCitationsTest` passes
    only because it loads the 2016 binding. Fix: `setBlank()` before every write in
    `TemplateXlsxRenderer` (title, scalars, `writeColumns`, copied sample rows), normalise the 2026
    template to shared strings, add the 2026 binding to the renderer test; check B-Reviste/B-Conferinte
    sample rows for the same loss. **Shipped:** `TemplateXlsxRenderer.normaliseInlineStrings` runs on every
    loaded template (402 cells rewritten for 2026), `writeCellValue`/`writeStringAt` reset the cell first,
    `TemplateXlsxRendererCitationsTest` is parametrised over both bindings, and
    `TemplateXlsxRendererTemplateWritabilityTest` stamps a sentinel into every bound cell of every xlsx
    binding (negative check done: both fail on the 2026 file without the fix). Verified live on Florin
    Spataru's local run: B-Reviste/B-Conferinte were ALSO affected (only numbers landed) — every FV Info
    2026 xlsx exported from prod between 2026-07-04 and this fix has template text in its string columns.
  - S2 — **DONE 2026-09-12.** Export: one sheet per work, even without citations (Florin's a).
    `RunIndicatorSnapshotProjector.projectCitations` emitted a tile for every entry of the stored `scores`
    map (one per confirmed publication, only a "total"); now cited works without citing rows are not tiles.
    Decision (Adrian): export ONE citations sheet with every tile stacked — the shape hand-filled Fișe have
    and the parser already accepted. **Shipped:** `BindingRole.tileLayout` (SHEET_PER_TILE | STACKED) +
    `stackedSheetName`; both Informatică bindings = STACKED / `C-Citari`; `renderStackedTiles` replicates the
    template block before filling so POI shifts each copy's COUNTIF/SUMIF, expansion pushes later blocks,
    summary = `SUM('C-Citari'!I22,'C-Citari'!I47,…)`, sheet kept at the template's slot; parser accepts the
    stacked name. Tests: stacked geometry + expansion + zero tiles + parser round-trip (both bindings),
    projector empty-tile skip; per-sheet tests kept by forcing SHEET_PER_TILE. Verified live on Florin
    Spataru's local run: 17 tiles (was 24 sheets), one sheet, no sample text.
  - S3 — **DONE 2026-09-12.** Export from a run had no forum/year/authors: the citations branch of
    `UserReportFacade.buildReportScopedIndicatorDetail` stored only `scores`/`total`/`totalCit`, so the
    tile header read "Title (, )" and rows showed only citing title + category. **Shipped:** the run
    graph now carries `publications` + `citationMap` as SLIM slices (`RunGraphPublicationSlice`: id,
    title, doi, author ids, forum id, volume, coverDate, authorCount — ~120 KB vs ~250 KB for the full
    views the apply-page cache stores; doi kept for S4); `RunIndicatorSnapshotProjector` resolves author
    and forum names at export time (same helpers as the publications path) and SKIPS citations carrying
    a `zeroReason` (excluded self-citations are mirrored into the score map with a zero Score for the
    drilldown and have no slice — they printed as bare titles with no category). Trap found on the way:
    `IndicatorPayloadSerializer` turns score-shaped maps into `Score` BEANS on read, so any projector
    helper must accept both; the projector test now round-trips through the serializer. Verified live on
    a fresh local run: 19 tiles / 92 rows, no header gaps, no missing year/category; the only blank
    forum/author cells are citing works that have none in the corpus. NOTE: runs created before this
    ship still export with blank forum/year/authors — a refresh (new run) is needed per researcher.
  - S4 — **DONE 2026-09-12.** DOI everywhere a work is listed (Florin: "nu era util să fie și DOI în
    interfață, cel puțin cu link bazat pe DOI resolver?"). Decisions (Adrian): workspace table shows the
    DOI on EXPAND only; the xlsx gets a HYPERLINK on the title cell (official form untouched, parser reads
    text); one normaliser. **Shipped:** `DoiLinks` (bare/URL/`doi:` shapes → `https://doi.org/…`, bean +
    statics, tested); `ScoredItem.doi` + citing-paper id/DOI resolved from the graph's `citationMap`
    (live views or S3 slices) in `IndicatorDetailResponseAssembler`; a "doi" affordance after the title
    in the evidence list, the evidence table and the citation modal (`individual-report-dashboard.js`);
    a DOI section in the workspace publication detail panel (bundle rebuilt); `publications/detail.html`
    link now normalised (URL computed in `RankingViewController` — Thymeleaf 3.1 forbids `T()`/bean
    calls in that context); export: `PublicationSnapshotItem.doi`, `CitationSnapshotItem.publicationDoi`
    + `CitingPublication.doi` set by live and run projectors, `TemplateXlsxRenderer` hyperlinks the key
    column of FIXED_TABLE/STACKED_BLOCKS rows, the tile title and the tile inner rows. Caveat as S3: runs
    older than S3 have no citing-paper DOI in the export.
  - S5 — **Coverage: Scopus reverse-title citation search.** Root cause of items 1–4: the Scopus
    citations task queries `REF(<eid>)`, so (i) works WITHOUT a Scopus EID (arXiv items such as
    "Considerations on Construction Ontologies" and "Workflow Patterns in Process Modeling", present via
    OpenAlex only) can never get Scopus citations, and (ii) EID works miss citing papers whose reference
    Scopus failed to link (the FSI Digital Investigation 2026 paper with mangled diacritics "Forti��").
    Florin's manual recipe: `REFTITLE("<exact title>")` (+ `REF("Fortis A.E.")` to disambiguate). Design:
    a per-user task (`ScopusReferenceTitleSearch`) run after the EID pass, for every confirmed work,
    ingesting hits as citation facts with provenance `SCOPUS_REFTITLE`; false-positive control = exact
    title (quoted) AND author surname in `REF`, plus an admin/user review queue for short or generic
    titles. All 7 DOIs Florin listed are absent from the corpus, so this is new intake, not linking.
    **Decisions 2026-09-12 (Adrian), final:** the REFTITLE pass runs for ALL confirmed works, every sync,
    incremental by year floor, with the exact-reference check before auto-ingest. Why not "no-EID works
    only" or a `citedby_count`-mismatch trigger: Scopus's cited-by count counts only LINKED references —
    the same set `REF(eid)` returns — so an unlinked reference (the FSI paper citing Hybrid Microservices
    with mangled diacritics; the two 2026 Springer papers citing Barcode Scanning) leaves NO count
    mismatch (prod: Hybrid 3/3, Barcode 1/1) and can only be found by searching the reference text.
    Cost: one extra search per work per sync (doubles the search calls), plus one retrieval per NEW hit.
    (b) OpenAlex checked the same day: it holds 6 of the 7 citing works (the ACM 10.5555 PLoP paper is absent) and NONE of their
    `referenced_works` name the cited works; its cited_by_count for the four works (6/3/1/2) equals ours.
    OpenAlex cannot close this gap — Scopus reference-text search is the only source. Design as above:
    `REFTITLE("<exact title>") AND REFAUTH(<surnames>)`, exact reference check on each hit before
    auto-ingest, provenance `SCOPUS_REFTITLE`, incremental by PUBYEAR. **IN PROGRESS 2026-09-12.**
  - S6 — **Policy: the Springer ISBN-DOI floor is broader than "LNCS".** `ComputerScienceConferenceScoringService`
    floors any citing paper with a `10.1007/978…` DOI to C/2p (`DoiVenueSupport.isSpringerBookSeriesProceedings`).
    Florin's case: "Web Service Based Approach for Viral Hepatitis Ontology…" is Communications in Computer
    and Information Science (Crossref), not LNCS → he scores it D/1p. The OM text names LNCS (2016) and ACM,
    EPTCS, LNCS (2026); the DOI prefix also covers CCIS, AISC, LNNS, SIST. Options: (a) keep "best category"
    (current policy, deliberate since the H92 sweep); (b) require series-name evidence (forum name or
    Crossref `container-title` containing "Lecture Notes") and demote the DOI prefix to a hint. (b) lowers
    scores across the board — measure before deciding (dry-run count of citing papers currently floored
    via DOI-only whose container title is not Lecture Notes).
  - S7 — **Reply to Florin's closing observation.** Perspectiva c) is maximal: citations of ANY publication
    of the candidate count (incl. out-of-list forums), from citing works in A*–D, theses, reports,
    out-of-list forums and monographs, minus any common author. The platform already collects for all
    confirmed works regardless of cited venue (the arXiv items prove it) and excludes self-citations
    per the same rule (his "Barcode Scanning" has exactly one citing paper — Alexandra's own Hybrid paper —
    and "Workflow Patterns" has two, both by Fortiș). The gap is SOURCE coverage (S5), not the rule.
    Ask him whether he meant that.
  Facts for the discussion: Alexandra = `alexandra.fortis@e-uvt.ro` (author `sauth_a929bbe2f3087d10dca72278`,
  citations task FULL completed 2026-09-11); "Hybrid Microservices" has 3 citations in corpus (missing:
  `10.1016/j.fsidi.2026.302128`); "Barcode Scanning" 1 (self); the two arXiv works have 6 and 2 citations
  via OpenAlex. Order proposed: S1+S2+S3 together (one sitting), S4, then S5 as the real feature; S6/S7
  are decisions.

- [ ] `H105` External (non-UVT) accounts for candidates to any UVT position (abilitare, concurs) —
  the usual rich interface, minus the licensed RAW layer; full view for UVT staff. **RAISED 2026-09-11**
  (dean's request: Vlad Drăgoi, Arad, Scopus 57202987286, target department SCIA; widened the same day
  to any candidate). Decisions 2026-09-11: NO paid/subscription product (licensing makes it impractical);
  UVT staff evaluating a UVT candidate is legitimate institutional use — **UVT library confirmed**;
  identity = REALM-LOCAL Keycloak users in aai.rdi created MANUALLY by Adrian (username = email, email
  verified ON; Google IdP, browser flow and client untouched); the app auto-provisions RESEARCHER on
  first verified-email login. NOTE: `POST /api/admin/researcher-profiles` does NOT create a user — it
  throws for an unknown email — so the profile can only be filled after his first login (or by mongosh).
  Slices:
  - S1 — token marker: Keycloak group `external` + client protocol mapper emitting a `groups` (or
    `account_kind`) claim; `KeycloakOAuth2LoginSuccessHandler` stamps `User.accountKind`
    INSTITUTIONAL|EXTERNAL at provisioning (absent claim → INSTITUTIONAL, so UVT tokens are unchanged)
    plus `User.validUntil` for externals — login locks the account once passed (supervisor view stays).
  - S2 — "applicant affiliation" (DECIDED over per-user report assignment): the external candidate gets
    a `department_affiliations` row for the TARGET department, so fișe resolve through the normal
    division-selection path and that department's head + dean see him as supervisors. Required
    counterpart: EXCLUDE EXTERNAL accounts from department/division roll-ups and batch refresh; hide the
    global landing, org-unit and supervisor surfaces for EXTERNAL (pin in security config + contract
    tests). Today a user with no affiliation/group sees NO reports (`listVisibleReportsForUser` → empty).
  - S3 — viewer-role filter on the DETAIL rendering, not on the scores: the run is identical, the
    response assembler drops licensed evidence fields for EXTERNAL viewers — metric values/ranks (AIS,
    JIF, JCR quartile tables), citing-document lists from Scopus/WoS edges, forum explorers and any
    browse/search beyond the user's own pubs. Kept: per-item category + points, criterion totals,
    threshold status, open-source evidence (CORE rank, DBLP match, SENSE tier, OpenAlex citing works).
    Rationale: categories/points are what UEFISCDI publishes and what the candidate must put on the
    fișă anyway; a point value cannot be reversed into an AIS/JIF or a citing document.
  - S4 (optional) — admin "provisional report for these author ids" action so an external candidate
    can be pre-scored from declared Scopus/ORCID ids without the department-roster route (H77 path is
    roster-only today and scores the WHOLE department).
  - S5 (only if a case appears) — admin ACCOUNT MERGE (external local account → later UVT identity).
    Primary answer is IdP-side: aai.rdi links the Google identity to the existing local user, the token
    keeps the local email, zero app work. The app-side merge is a ~12-collection email re-key: users
    (`_id`), PublicationAuthorshipDecision, ActivityInstance.researcherId, UserIndividualReportRun,
    UserIndicatorResult, EvaluationSnapshot, DepartmentAffiliation.userId, Membership, Task.initiator,
    WorkspacePreferences, user_defined facts (submitter), venue-claim/merge requests (requestedByEmail)
    — model it like H103 (side-table decision, re-applied) rather than a one-shot script.
  Immediate (no code) — ops script `h105_dragoi_prescore.js` (rke2-overmind/feaa-2026-scripts, phased +
  idempotent): Scopus pubs task FULL → citations task FULL → SCIA roster row → `/admin/provisional-report`
  (dept SCIA × FV Info 2026) gives perspectivele b/c; perspectiva d is his own entry after login.

- [ ] `H102` Edit flow for user-added (wizard) publications (Florin's 1997/1999 typo, 2026-09-02).
  A USER_DEFINED pub is currently immutable from the workspace — a typo means an admin mongosh edit
  (three places: user_defined fact + canonical pub + book entity). Feature: an "Editează" action on
  source=USER_DEFINED rows (submitter-only), reopening the wizard prefilled and resubmitting through
  the SAME ingest path with the PINNED original sourceRecordId — the essential subtlety, since the
  derived id includes coverDate and a naive re-derive on an edited date mints a duplicate. The ingest
  pipeline is already idempotent on (sourceRecordId, payload-hash), so the backend is mostly free;
  the work is the workspace action + prefill + the pin. Delete (tombstone semantics) deliberately
  out of scope for the first slice.

- [ ] `H101` Fee-journal (APC) status must be time-aware — **FEASIBILITY INVESTIGATED 2026-09-04.**
  Origin: Florin's IJCCC papers (2013–14, free-OA era) declassified by TODAY'S apc flag.
  **Findings:** (a) OpenAlex is PROVEN useless for history — the works dumps stamp the venue's CURRENT
  list price onto every work uniformly (IJCCC 2012/2013/2014/2026 all carry apc_list=apc_paid=539 USD),
  so no per-year signal exists there. (b) DOAJ historic snapshots via archive.org would work (the MBL
  Wayback recovery is the precedent) but are a bulk pipeline with era-varying CSV formats. (c) OpenAPC
  gives positive-only evidence (payment happened ≠ absence means free). (d) **The stakes don't justify
  bulk reconstruction**: measured in prod, registered researchers' pre-2015 pubs on currently-fee-flagged
  forums = ~5 publications across exactly THREE journals (IJCCC, IEEE JSTARS, J. of Cloud Computing) —
  citations skew recent, so the citing side is smaller still.
  **Recommended design (small, claim-based):** a spared `forum_apc_exemptions` collection (ISSN-anchored,
  H93-style, admin-approved, evidenceUrl = wayback trace), consulted by a year-aware
  `isFeeJournal(forumId, year)` overload — the call site in ScientificProductionService already holds the
  pub year; remember the @Primary delegator (add the overload to BOTH facades). Researcher-facing claim
  flow deferred; admin-entered rows suffice for 3 journals.
  **STILL PARKED pending Florin's announced follow-up on the broader APC-declassification interpretation
  dispute** — if the comisie reading changes the gate semantics wholesale (his argument: Springer/Elsevier
  Q1/Q2 APC journals shouldn't be declassified at all), per-year exemptions may be moot. Decide after his
  message; implementation is a one-sitting job either way.
  Side observation worth a look someday: IEEE JSTARS (hybrid, not gold-OA) being apc-flagged suggests the
  OpenAlex fee signal may over-reach into hybrids.

- [ ] `H100` Future-dated activity instances must not score (from H99 item 3, Florin's suggestion).
  A researcher records an activity now, dated in the future (doctorand cu susținerea programată — D_xii
  keyed by the DEFENSE date), and it starts counting only once the date passes; until then it shows as a
  zero row with a "din viitor" reason (the H99-item-4 excluded-items surface already exists for this).
  Platform-wide gate in ActivityReportingService (instance date > reference date → excluded), so every
  activity indicator gets it for free; needs a decision on which date field anchors ("Data" vs year
  fields) and a check that no existing legitimate entry is future-dated before enabling.

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
    *paper* scoring; wiring the paper-count read to honor the flag (or projecting a CPCI membership row) is the
    genuine open remainder. **This used to say "is `H65` work" — that pointer is dead:** H65 was archived
    2026-06-30 on a different scope (Physics DOCX export), so the item had no owner. It lives here now.
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
  `docs/tasks/active/h68-criteria-extensions.md` (Slice 3).
  **Data change VERIFIED IN PROD 2026-07-25** — both FV Info 2016 and 2026 carry
  `Perspectiva D.maxPercentOfTotal = {13: 10, 17: 10}`. Nothing pending here.

- [ ] `H94` Indicator descriptions from the standards text.
  **RAISED + S1 DONE locally 2026-07-27 (user ask).** A researcher opening the drilldown saw an indicator
  NAME and a number; everything explaining the rule lived in the OM PDF. Now `Indicator.description`
  renders under the indicator header — static and server-side, deliberately a SIBLING of the JS-owned
  `.indicator-detail-content` (the dashboard replaces that div's innerHTML on every detail load; pinned by
  a contract test that anchors on class attributes after a first version matched its own comment).
  Round-tripped through the admin `IndicatorForm` (the persisted-only-fields wipe trap the form itself
  documents) with an edit textarea. Content: **36 FV Info descriptions** (both fișe), grounded in the
  actual standards text — `data/standards/2026/standarde-conf-2025.html` for 2026, the 2016 PDF for 2016 —
  covering categories/points, the max(1, n−2) divisor, and the gates that most often explain a surprising
  score (workshop reduction, fee-journal exclusion, the D(ix) 24-point and D(x)/D(xiv) 10% caps; 2016 vs
  2026 differences kept distinct: LNCS-only vs ACM/EPTCS/LNCS, one-category-lower vs C-mapping for
  workshops, UEFISCDI zones vs WoS quartiles, books A=16 vs 12). Shipped as committed
  `indicator-descriptions/info.json` + `POST /admin/indicators/descriptions/apply?dryRun=` (data-after-code,
  own controller to dodge the constructor-arg slice trap); unmatched names on either side are REPORTED.
  Applied + verified live on agent-dev (36/36 matched; renders under the header; collapse hides/restores
  it). Seed synced (36 of 74). **Remaining:** descriptions for the other domains' 45 indicators
  (FEAA/Mate/Fizica/Psiho/Arte) — same mechanism, content only; and the prod apply after deploy.

- [ ] `H97` Matematică — the 2026 fișă (COMISIA 1, OM 3.019/2025).
  **RAISED 2026-08-02.** The 2016 fișă is done and faithful (names/perspectives/descriptions, the
  nᵢ=1-for-Lector note, C4's condiția b). 2026 is a REWRITE, not a tweak: N = 2·Q1 + Q2 ≥ 7 (Conf) /
  12 (Prof), N_recent = 2·Q1_recent + Q2_recent ≥ 2, S1 ≥ 2,5 and S2 ≥ 1,75 (Conf only), Q1 ≥ 1
  (Prof only), C1 ≥ 16/32 and C2 ≥ 8/16. Definitions: L = SCIE minus journals that charge an APC;
  M1 = L ∩ (Q1|Q2|Q3), M2 = L ∩ (Q1|Q2) over the UEFISCDI AIS lists of t‑1…t‑5; A/A1/A2 = the
  candidate's articles in L/M1/M2; A_recent = A from t‑7 (maternity extension in the text);
  sᵢ = MAX AIS across those five lists REGARDLESS of publication year; S1/S2 = Σ sᵢ/nᵢ over A1/A2;
  C1/C2 = citations from M1/M2 journals, excluding any citing article that has the candidate as
  author or coauthor.
  Machinery is almost entirely in place from the Info/FEAA work: `LatestNRankings(5)` IS the sᵢ
  rule, `feeJournal` IS L's exclusion, `PreviousNYears(7)` is A_recent, `SelfCitationPolicy.
  ANY_COAUTHOR` is C1/C2's exclusion, `Q` + `Q=="Q1"?2:Q=="Q2"?1:0` gives N directly, perspectives
  give the cumulative verdict.
  - [x] **S1 — the SCIE gate — DONE 2026-08-02.** `scieIndexed` formula variable (bound lazily from
    the existing `ReportingLookupPort.isForumInScie(forumId, publicationYear)` — the delegator
    already forwards it; house convention taken from PdWosEligibilityScoringService), declared in
    FormulaVariableContract for every kind. `scieIndexed && !feeJournal` expresses list L exactly.
    Tests: SCIE vs SSCI-only vs SCIE-with-APC, plus "formulas that don't reference it never query
    coverage". No data changes.
  - [x] **S2 — the fișă — DONE 2026-08-02** (`mate_2026_report.js`, local + seeds; prod pending).
    "FV Matematică 2026": 7 indicators (Mate26_N, _N_recent, _S1, _S2, _Q1, _C1, _C2), all gated by
    `scieIndexed && !feeJournal` (list L) with AIS + `LatestNRankings(5)` for sᵢ/quartile;
    N_recent adds `PreviousNYears(7)`; C1/C2 are Citations + ANY_COAUTHOR on the CITING journal's
    quartile. Criteria carry the three position sets INCLUDING abilitare, which has its own numbers
    (S1 4, S2 2,5, C1 20, C2 10, N_recent 2 — and NO N, NO Q1; verified in the abilitare standard).
    Two perspectives mirroring the fișă's own tables: "Articole (tabelul 1)" and "Citări (tabelul 2)".
    No reportTypeKey (no export binding yet), like FEAA 2026. Descriptions written + applied.
    Verified live on a real run (florin, CS researcher on the math fișă via a temporary division
    selection, reverted): N 2.00 with Q1 1.00 — the invariant N = 2·Q1 + Q2 holds — N_recent 2.00,
    S1 = S2 = 0.045, C1 11 / C2 6, i.e. every subset relation (Q1 ⊆ N, S2 ≤ S1, C2 ≤ C1) is
    respected; HABIL thresholds render exactly as the abilitare standard, with N/Q1 inapplicable.
    The SCIE gate demonstrably bites: the same researcher counts 2 papers under the 2016 C4
    (fee+quartile only) but 1 here, the difference being a non-SCIE-covered venue.
    NOT derivable, left to the comisie and documented in the descriptions: the taught semester
    courses (1 Conf / 2 Prof), the abilitation certificate, and A_recent's maternity extension.
  - [ ] **S3 — optional** — reuse `scieIndexed` to tighten the 2016 C4 gate if the comisie wants
    L-membership there too (today C4 checks fee + quartile only).

- [ ] `H98` Fizică — the fișă (COMISIA 3). **RAISED 2026-08-02.**
  Investigation finding: there was NEVER a physics report — none in `scholardex`, `test` or
  `scholardex_h66`, none in the seed, none in git history. Only export scaffolding existed
  (`Fizica2024ReportTypeImportSupport` + template.docx + binding.json declaring A1–A10, the two
  article tables and a 6-value summary), written against the standard rather than against a report.
  The 2016 doc in `data/standards/fizica/` is UVT's own faculty procedure; the 2026 one is national
  (OM 3.019/2025, mirrored at `data/standards/2026/OM3019-2025-anexe.pdf` — the authoritative
  annexes for EVERY commission, worth keeping). Same scheme both years — A (professional) + I/P
  (research) + C/h (impact) + `T = A + P/2 + I/2 + C/20 + h/5` — but 2026 moved four coefficients
  (A1 4→5, A3 0.5→1, A4 0.5→1, A10 /100.000→/50.000), raised A to ≥1, added explicit I/P minimums,
  and set T 5 / 12.5 / 11.5 (conf/prof/abilitare). So the coefficients live inside the formulas and
  the two years need separate indicator sets.
  Machinery was already there, some of it built for this standard by name: `Nef` (H65 — the
  nᵢᵉᶠ 5/15/75 step), `Criterion.weights` (H65's javadoc literally cites the physics T),
  `AuthorRole.FIRST_OR_CORRESPONDING` for P, `ANY_COAUTHOR` citations for C, `HIRSCH`/`WOS_VENUE`
  for h, `Buget` on Grant Cercetare for A10, Brevet + Proiect educational activities for A7–A9.
  - [x] **S1 — the 2026 fișă core — DONE 2026-08-02** (`fizica_2026_report.js`, local + seeds; prod
    pending). "FV Fizică 2026": Fiz26_I (`S/Nef`), _P (`S`, FIRST_OR_CORRESPONDING), _C
    (`S > 0 ? 1/Nef : 0`, Citations+ANY_COAUTHOR — Nef binds from the CITED publication, which is
    what cᵢ/nᵢᵉᶠ means), _h (HIndex WOS_VENUE, exclude-self), _A9, _A10 (`Buget/50000`, null-safe).
    Six criteria incl. the weighted T; three perspectives (the standard's own sections) with T
    unbundled as the headline tile. Verified live: T computed 5.6300 against a hand-check of
    4.5 + 0.6917/2 + 7.6839/20 + 2/5 = 5.630045 — the weighted-sum criterion is exact to the digit;
    rail reads 2/4 with T DA below the three groups. Descriptions written + applied.
  - [x] **S2 — A1/A2/A4/A5 on the ARCHIVED Master Book List — DONE 2026-08-02** (a demo for the
    Fizică committee to accept or reject). The list Clarivate discontinued survives in the Internet
    Archive: 834 publishers, A→Z, one table, recovered from the 2026-02-20 snapshot and committed as
    `report-data/wos-master-book-list-publishers.csv` (raw page mirrored at
    `data/standards/fizica/wos-master-book-list-archived-20260220.html`) so it no longer depends on
    the archive staying up. `WosMasterBookListService` + `wosBookPublisher` formula variable, bound
    lazily through the shared book→publisher path — extracted from FeaaBookScoringService into
    `PublicationPublisherSupport` rather than written twice.
    The matching is the substance: WoS shouts and abbreviates ("OXFORD UNIV PRESS", "JOHN WILEY &
    SONS LTD") where Scopus writes prose ("Oxford University Press", "wiley"), so EXACT matching hit
    none of the majors and everything fell into the A4/A5 complement — the demo would have looked
    broken. Names now compare as canonical TOKEN SETS (abbreviations expanded, legal forms dropped,
    subset match requiring one identifying non-generic token, so a bare "Press" matches nothing).
    Indicators score off FEAA_BOOK, the only base scorer returning > 0 for any book/chapter — AIS is
    0 for books and GENERIC_COUNT short-circuits BEFORE the formula runs (which is also why the old
    Mate C4 counted every publication). Verified end-to-end on real data: the same chapters moved
    from A5 (0.0764, "other publishers") to A2 (0.3818, "recognised") — exactly 5×, matching the
    1/nᵢᵉᶠ vs 0.2/nᵢᵉᶠ coefficients — lifting A to 4.8818 and T to 6.0118.
    Ask Fizică: accept a frozen 2026 snapshot with a manual-claim escape hatch for publishers
    admitted later?
  - [x] **S3 — brevete A7/A8 — DONE 2026-08-03** (`fizica_2026_brevete.js`). A7 = Σ3/nᵢᵉᶠ for granted
    international patents (Brevet.Tip ∈ {Triadic, European, International}), A8 = Σ0.5/nᵢᵉᶠ for
    national ones — an explicit allowlist rather than `!= "National"`, so a missing Tip scores 0
    instead of counting as international. The only real gap was one boolean: `Nef` is ALREADY bound
    for activities (H65 added it for "physics didactic activities A1–A8"), but only when `N_autori`
    binds as a NUMBER, and Brevet declared it as text — so Nef never appeared. Flipped to numeric;
    zero Brevet instances existed, so no migration (and `parseNumberOrNull` would have coerced
    "3"→3.0 anyway). Seed edit done line-scoped: the snapshot script pretty-prints activities.json
    while the committed file is compact, so a full snapshot would have reformatted all 18 activities
    to change one flag. Verified with two temporary instances (European/2 authors → 1.5000 = 3/2;
    National/4 authors → 0.1250 = 0.5/4), both removed after; A rose to 6.5068 and T to 7.6368,
    reconciling exactly.
    Still open: A3 (book editorship — not in our data at all) and A6 (ISI proceedings ≥3 pages,
    needs a CPCI test cf. H76 plus a page-count rule).
  - [ ] **S3 — the 2016 UVT fișă**, if Fizică still needs it: same indicators with the old
    coefficients (A1 4, A3/A4 0.5, A10 /100.000) and T ≥ 5 / 12; adds a Lector tier (I ≥ 1, P ≥ 1,
    A ≥ 0.5) the national standard has no equivalent for.
  - [ ] **S4 — validate against a real filled fișă**: `doctorat.uvt.ro` publishes one for a UVT
    physicist (Conf. dr. O. M. Bunoiu, 07.07.2025) computed under these 2026 rules — the same kind
    of ground truth the Info excel provided.

- [ ] `H50` Individual report export / read-only score-verification import.
  **STATUS (2026-06-30): mostly done — H62/H65 overtook most of the "remaining" list. The genuine gap is docx *import*
  verification (H50.6). Entry below refreshed.**
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Done since (via H62/H65): **docx export (H50.4)** is wired — `ReportExportFacade` renders any format the support declares, with the DOCX content-type + `TemplateDocxRenderer`. **Report-type coverage (H50.5) largely done** — bindings now exist for `informatica-2016`, `matematica-2016`, **`feaa-2024`**, **`fizica-ff`** (4 of the report types), each with a `ReportTypeImportSupport` that renders docx.
  Remaining: **docx *import*/verify (H50.6)** — the docx supports' `parse()` still throw `UnsupportedOperationException` (Fizica/Feaa), so score-verification upload is xlsx-only; implement docx parsing for the docx report types. Plus any report definitions still lacking a binding (the remainder of H50.5).
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H123` Heads can open the reports of their faculty or department — **FIXED and DEPLOYED 2026-09-29** (image
  `d58bbdaf`, `deploy-prod` succeeded 15:01). Found
  while drafting onboarding instructions for FPSE: appointing a head gave that person access to nothing. Three
  separate causes, all fixed. (1) Headship granted no role: `User.getAuthorities()` was the stored roles only, and
  every head in prod stored `RESEARCHER`. Now a user who heads a faculty or department, or supervises a group, signs
  in as SUPERVISOR — derived in `KeycloakOAuth2LoginSuccessHandler` from `SupervisorWorkspaceService`, carried by the
  transient `User.supervisorByPosition`, NEVER stored (the signed-in object is saved back by the profile pages, so
  a role added to `roles` would have been persisted). It follows the appointment from the next sign-in; external
  candidates never get it; a stored SUPERVISOR role keeps working. (2) The org-unit report pages live under
  `/admin/**`, which the URL rule reserves for platform admins, so the handlers' "supervisors allowed" never
  mattered; `/admin/divisions/*/reports/**` and `/admin/departments/*/reports/**` are now open to supervisors and
  nothing else under `/admin` is. (3) Those handlers checked the role, not the unit: they now use
  `@orgUnitAccess.canManageDepartment` (director, or a head of the faculty above) and the new `canManageDivision`
  (heads of that faculty only — a director does not see the faculty roll-up). Also: a refusal raised inside MVC
  (`@PreAuthorize` with a bean check, or a handler's own `AccessDeniedException`) was answered by the catch-all as
  "500 unexpected error" with a stack trace in the log; `MvcExceptionHandler` now hands it back to Spring Security
  (access-denied page). Back links and the sidebar no longer offer a head admin pages. Pinned by
  `OrgUnitReportPagesSecurityContractTest`, which runs the REAL filter chain and templates — the page tests of
  these controllers run with filters off, which is how this stayed unnoticed. **Not checked in a browser as a
  head:** `agent-dev` signs every request in as an admin. **Not changed:** `ApiExceptionHandler` has the same
  catch-all for `/api`; the admin users page shows stored roles only, so a head appears there as a researcher.
  Heads see the individual reports of their staff (user decision 2026-09-29). The three FPSE department directors
  were appointed as heads by the user the same day. **Left to confirm:** a head signing in to prod and opening the
  report of the unit — a person has to do it; tooling does not touch production (audit decision, see `H119`–`H122`).
- [ ] `H119` Public pages: keep the UVT publication showcase, drop what the licences do not allow — **BUILT 2026-09-29, not pushed;
  from the compliance audit of that day.** How it was built: `PublicCatalogScope` answers, per request, either "no
  restriction" (signed in) or the set of author ids a visitor is limited to; "the university's authors" is the
  platform's own notion (`CacheService.getUniversityAuthorIds()`: every researcher account that is not an
  external candidate — the one the CNFIS export counts by), NOT an affiliation match, so a UVT author without an
  account is not on the public pages yet. A publication or author outside that set answers "not found" to a
  visitor. Points (1)–(6) below are all done; the citation column and the "sort by citations" option are absent
  from the public list, and the JSON carries `citedByCount: null`. Pinned by `PublicCatalogContractTest` (real
  security chain, real templates). Not checked in a browser: the `agent-dev` profile signs every request in, so
  it cannot show the visitor's view. Original entry: `/publications/**`, `/authors/**`, `/api/entities/authors/**` and
  `/api/rankings/**` are `permitAll` (`WebSecurityConfig`), and today they serve the WHOLE corpus (including the
  ~150k third-party citing papers and their authors), per-author total citations and h-index
  (`AuthorViewController` → `authors/detail.html`), per-publication citation counts of unknown source, and WoS
  category metrics (`/api/rankings/categories`). Decision: the pages stay public, the content narrows.
  (1) public publication list and detail only for publications with at least one UVT-affiliated author
  (`ScholardexPublicationMvcService.search`, `RankingViewController`); (2) author directory, author page and the
  authors API only for UVT authors; (3) total citations, h-index and per-publication citation counts hidden for
  anonymous visitors, unchanged after login; (4) the Scopus EID becomes a link to the Scopus record, labelled
  "Scopus"; (5) `/api/rankings/categories` requires login; (6) the positive "WoS indexed" labels on `/forums/**`
  get the "according to the platform data" qualifier. **Follow-up, not part of this task:** a per-source citation
  count on the canonical publication (`citedByCount` is a max over Scopus and OpenAlex with no record of which
  won), so the public pages can show the OpenAlex count labelled as such. Public staff pages also need a line in
  the privacy notice, which does not exist yet.
- [ ] `H120` Scopus: fetch and keep only the fields the CRIS policy allows — **OPEN, from the compliance audit of
  2026-09-29.** The wrapper calls Abstract Retrieval `view=FULL` for every own AND every citing record, because
  `include_enrichment` defaults to true in the three request DTOs (`AuthorWorksRequest`, `CitationsByEidRequest`,
  `CitationsByTitleRequest`) and no planner overrides it. Stored outside the allowed list: abstract
  (`description`), author keywords, funding (`fund_acr`/`fund_no`/`fund_sponsor`, plus the `scopus.funding_facts`
  collection), open-access flags, corresponding authors, PII, matched reference text. They sit in the raw
  `scopus.import_events` payload, `scopus.publication_facts`, `scholardex.publication_facts`, the Postgres
  `scholardex_publication_view`, the local dump files under `data/scopus/` and the pybliometrics cache in the
  scopus-python pod. Abstracts and keywords are not rendered but reach the browser through
  `GET /user/workspace/publications/{id}/citations`, which serialises the whole `ScholardexPublicationView`;
  funding and open access are displayed (`user/publications.html`, `publications/detail.html`). Work: (1) stop
  fetching — enrichment off, or reduced to the allowed fields; (2) stop storing — drop the fields at ingestion,
  raw event payload included; (3) stop sending — a DTO for the citations endpoint; (4) purge what is stored, in
  Mongo, Postgres, the pod cache and the dump files, and check the backups; (5) open access and funding may come
  back from OpenAlex, labelled as such. Both canon paths copy these fields (`applyOpenAlexFields` and
  `CanonicalGraphBuilder`), so both need the change. Open-access status feeds the APC/fee gate — check what reads
  it before removing.
- [ ] `H121` Repository housekeeping after the compliance audit — **DONE 2026-09-29, not pushed.** Done as listed
  below, plus: the secret scan is the `secret-scan` job of `security-gates.yml` (gitleaks over the checked-out
  tree, config `.gitleaks.toml`; NOT over the history, which holds the dead key); the replay fixture keeps its
  captured shape and carries generated values (it also held abstracts); the two JCR harvest scripts left the
  repository for the ignored `scopus-python/_local/` (`H122` b). Original list: (1) The deactivated Scopus key
  (`H88`, Elsevier's written confirmation 2026-07-28) is quoted in full in `TASKS-done.md`; truncate it to its
  first four characters. No history rewrite: the key is dead. (2) Secret scanning in CI (gitleaks) —
  `security-gates.yml` runs only dependency review and CodeQL. (3) `src/test/resources/h52/replay-fixture.json`
  (1.5 MB, 97 Scopus EIDs with titles and authors, captured from real indicator results) sits in a public
  repository; replace it with synthetic records. (4) Remove the dead `scopus.api.*` properties from
  `application.properties` and the legacy `charts/core` chart. (5) Stale docs found along the way:
  `docs/authentication.md` (describes the password login), `docs/c01-cnfis-rule-spec.md` §5 (says the JCR year
  caps at 2023; the code uses `maxAvailableYear()`).
- [ ] `H122` Licence questions left by the compliance audit (2026-09-29) — **OPEN, waits on written answers.**
  **(a) Scopus citing papers.** The wrapper asks `REF(eid)` for every UVT paper and stores each citing paper as a
  record (~150k), which the citation indicators need (citing venue, citing authors, citing year). Elsevier's
  published CRIS policy (dev.elsevier.com/policy.html) says the application may query "to identify publications
  written by its researchers" and lists citation COUNT among the permitted metadata; citing documents are neither
  permitted nor forbidden there. Adrian recalls Elsevier allowing the extraction of citing papers for this API
  key; public display was not discussed. Needed: that permission in writing (find the message, or ask
  integration support again), stating storage of citing-paper METADATA and its use in internal scoring. Until
  then citing papers stay out of the public pages (`H119`). Fallback if the answer is no: OpenAlex-only citing
  works — measure first (edges by source, re-score the persisted runs without the Scopus edges). The same policy
  also narrows `H120`: abstracts "may not be displayed publicly", stored metadata may be kept in perpetuity and
  shown to any user, and cited-by counts may not be aggregated for external display.
  **(b) JCR data.** `scopus-python/jcr_discover.py` + `jcr_dump.py` page the whole JCR journal list (2020–2025)
  through a signed-in browser session. UEFISCDI publishes AIS and RIS but NOT the impact factor, so the impact
  factor (Psihologie, Științe ale Educației, the best-of in Informatică) and the AHCI/ESCI editions come only
  from there. Ask the library, which holds the Clarivate contract, to confirm in writing that loading JCR data
  into an internal evaluation platform is covered, and how it may be obtained. The repository is public: the
  scripts carry no Clarivate data, but they document the method; move them out of the public repository.
  **(c) WoS accession sweep** — weekly since 2026-09-29 (`wos.openurl.sweep.cron`), was nightly; still only UVT's
  own papers, 400 per run. **(d) AI tooling is a developer only:** no access to Scopus dumps, the
  personal-data backups or production. Enforce it: move `data/scopus/`, `data/backups/` and the prod kubeconfig
  out of reach, drop the kubectl/mongosh/psql permission from `.claude/settings.local.json`, develop on
  synthetic or OpenAlex-only data.
- [ ] `H118` FV Științe ale Educației 2026 (OM 3.019/2025, COMISIA 28) — **BUILT, DEPLOYED and LOADED in prod 2026-09-29**
  (image `7a7bb39a`; `h118_comisia28_2026.js` renamed 12 activity types, changed the fields of 2, created 11, updated
  10 Psychology indicators, created the domain, 42 indicators and report `6abb957433192ec40a8e072f`; read back
  clean, 0 errors after restart). Still to do in the admin pages: select the report for the FPSE division and score
  the departments provisionally. Same annex
  and indicator table as Psychology (`H115`); what differs is values, thirteen extra indicators and the criteria.
  Decisions taken as defaults, all reversible by data: (1) the strict indicators I1A/I1B/I5 count journals in the
  education categories (Education & Educational Research; Education, Scientific Disciplines; Education, Special;
  Psychology, Educational — any edition) and every other Web of Science journal scores at I2/I6, the reading already
  used for Psychology; of the 229 publications the 40 FPSE staff have in the platform 61 are articles in such
  other-category journals, so this is THE question to put to the faculty (switching = pointing the indicators at
  domain `ALL`); (2) CrossRef, JSTOR, CEEOL and Ovid are not applied as recognised databases; (3) activity types
  are shared by both reports, so one declared entry scores in both. Engine: `Comisia28Rules.STIINTE_EDUCATIEI`
  (p = 0,10, no above-median exception, DOAJ recognised, own A2/B publisher lists) behind the new
  `Indicator.stiinteEducatiei2026` flag. Config: domain `Educational Sciences`, 42 `Edu26_*` indicators, 11 new
  activity types, report with 10 criteria (C3 = books at A1/A2 publishers; C6 = the GOOGLE SCHOLAR h-index squared,
  self-declared) and 4 perspectives; thresholds total 105 / 200 / 180. **Corrections to the Psychology report made
  along the way:** grants count from 20.000 EUR upward (a rule of the annex missed in `H115`; `Buget_eur` must be
  known, so a grant with no budget declared scores nothing), the twelve activity types are renamed "(Comisia 28, …)"
  and the editorial one now takes the journal's impact factor so it can serve both thresholds. Both definitions are
  pinned by tests that score declared activities through the real `ActivityReportingService`
  (`SeedReportDefinition`). **Not computed:** N (principal-author count, ≠ 1 only from four), the C8 compensation
  (9 of 12 points from Q1/Q2 articles), the two-per-edition proceedings cap ACROSS I8 and I9 (each is capped at two
  on its own), whether a conference is international, the pre-PN II exemption from the grant floor. Prod: deploy,
  then `h118_comisia28_2026.js --restart`, then select the report for the FPSE division.
- [ ] `H117` FV Psihologie 2026 skipped the ESCI edition on its strict indicators — **FIXED, DEPLOYED and LOADED in prod
  2026-09-29** (with `H118`). Measured on the 33 staff of Departamentul de Psihologie (507 journal articles in the
  platform): 8 articles move to the full rate, all through the ESCI edition.
  Found while investigating Științe ale Educației, before any run existed. The 2026 annex spells "Web of Science Core
  Collection" as SCIE, SSCI, AHCI and ESCI, but `ImpactFactorJournalScoringService` admits only SCIE/SSCI category
  keys and the Psychology domain lists no ESCI key, so an article in a psychology journal that sits only in ESCI
  scored 3 + IF at I2/I6 instead of 3 + 3×IF at I1A/I1B/I5 (119 such journals with IF ≥ 1 in 2024). Fix:
  `Comisia28Rules` now carries the per-domain values of the annex (threshold p, the above-median exception, the
  recognised databases, the publisher list) and the scorers read them instead of their own constants. For a flagged
  indicator the IMPACT_FACTOR scorer scores against a copy of the domain widened with the ESCI edition of each
  category (the STORED domain is untouched — the 2016 report shares it), takes the last available IF when the
  publication year has none ("ultimul IF disponibil"), and ignores an ESCI quartile from before the unified ranking
  (2023). The I2/I6 scorer applies the very same gate in its "counted by the stricter indicator" check, and — 2026
  only — gives the base 3 points to a Web of Science journal that had no impact factor at all (ESCI before JCR
  2023, AHCI). Data: `psihologie2026` set on `Psiho26_I1A`, `Psiho26_I1B`, `Psiho26_I5`; descriptions of I1A and I2
  updated. Local rescoring of 38 researchers: no score moved, no errors (nobody in the local corpus publishes in
  such journals); the proof on real data comes from the Psychology staff in prod. Prod: deploy, then
  `h118_comisia28_2026.js --restart` (one script covers H117 and H118).
- [ ] `H116` Admin report edit form wiped script-set fields on save — **FIXED and DEPLOYED 2026-09-29** (image `68d3f657`). The form
  binds the whole `IndividualReport` and saved it as-is, but has no inputs for `perspectives` nor for a criterion's
  `weights`, `maxPercentOfTotal` and `thresholdCapAdditions`, so ANY save through the page dropped them (seven
  reports carry them: FV Matematică, FV Info 2016/2026, FEAA 2026, FV Matematică 2026, FV Fizică 2026, FV Psihologie
  2026). Found while building `H115`; confirmed first by a test that renders the page, reads its form as a browser
  submits it and posts that back. Fix: the update path goes through
  `IndividualReportsManagementFacade.saveIndividualReportFromForm`, which carries those fields over from the stored
  report (`ReportFormCarryOver`). What the request states wins, field by field, so direct POSTs with
  `perspectives[…]` keep working; clearing such a field stays a script's job. Everything carried is keyed by
  POSITION, and the form carries no identity for criteria or indicators, so the carry-over happens only while every
  stored criterion is still at its position (same name OR same indicators — a rename is fine, a changed indicator
  set is fine, both at once is not) and, when weights/caps are at stake, every stored indicator too; additions at
  the end are fine. Otherwise the save is REFUSED with the reason shown on the edit page and nothing is written.
  Reports without script-set fields behave exactly as before. **Follow-up 2026-09-29 (same task): phantom
  criterion after "Remove Criterion".** The page renumbered inputs named `criteria…` but not the hidden checkbox
  markers Thymeleaf renders (`_criteria[n].contributesToTotal`), and the binder grows a list up to the highest index
  it is told about, so a removal stored an empty criterion at the end. The same script left gaps when an indicator
  or threshold ROW inside a criterion was removed (stored as a `null` indicator index / an empty threshold), and
  "Add Indicator" could then reuse an index. Fixed twice: the page renumbers cards, markers and rows through one
  `reindexCriteria()` after every removal; and the controller drops what only a gap can produce
  (`dropBinderLeftovers`, for tabs opened before the deploy and hand-made requests) — null indices and empty
  thresholds anywhere, empty criteria ONLY FROM THE END, because positions are what perspectives and cap additions
  point at; a criterion with just a name, a plafon or the total flag is kept. Prod and local checked read-only:
  no report stores a phantom, a null index or an empty threshold, so nothing needs cleaning. **Left open:**
  (1) `edit-groupReport.html` carries a copy of the old script and `AdminGroupReportsController.update` the old
  save-as-posted shape — no group report exists today; `ReportFormCarryOver` works on `AbstractReport`; (2) a
  first save through the form of a script-created report still normalises `reportTypeKey` null → "" and the two
  binding maps to empty maps; (3) on server-rendered cards the label of "Contributes to total score" is not
  linked to its checkbox (Thymeleaf suffixes the checkbox id), cosmetic; (4) a scripted report whose STORED
  criteria already end in an empty one would be refused on every form save, since the net trims the posted
  copy — none exists.
- [ ] `H115` FV Psihologie 2026 (OM 3.019/2025, COMISIA 28, domeniul Psihologie) — **BUILT, DEPLOYED and LOADED in prod
  2026-09-29** (image `68d3f657`; `h115_psihologie_2026.js` created 12 activities, 30 indicators and report
  `6abb7bad05b6723f97a6377f`; read back clean, 0 errors after restart). Still to do in the admin pages: select the
  report for the FPSE division, hide it for Științe ale Educației and DPPD, score Departamentul de Psihologie
  provisionally (29 of its 33 members already have publications in the corpus).
  Asked for the management demo once FPSE was onboarded (`H111`); only the 2016 fișă existed. The 2026 standard is a
  rewrite, not a re-thresholding: three areas (A1 realizări, A2 vizibilitate, A3 competitivitate), I1 split by
  publication fee (I1A/I1B), books 16×m and chapters 4×m with no author split, WoS citations + squared WoS h-index
  as criteria, a grants criterion. One indicator table serves conferențiar / profesor / abilitare (totals 85 / 250 /
  220); the standard sets nothing for asistent/lector. Engine: `AuthorRole.NOT_FIRST_NOR_CORRESPONDING` (the exact
  complement of first-or-corresponding — `CO` counted a corresponding author twice), `ScoringStrategy.WOS_INDEXED`
  (SCIE/SSCI/AHCI/ESCI membership in the item year, for I11), h-index indicators now evaluate their formula with
  `S = h` (`HIndexScoreSupport`; physics' `S` is unchanged, Psychology uses `S * S`), and an `Indicator.psihologie2026`
  flag that switches `PSYCH_BOOK` to the 2026 publisher lists (classpath CSV) with an indicative A1 tier from the WoS
  Master Book List, `PSYCH_BDI_JOURNAL` to "one recognised database is enough" without DOAJ, and the WoS h-index to
  admit ESCI. Config: 30 `Psiho26_*` indicators, 12 new activity types, one new role on the shared «Grant
  Cercetare» («Coordonator local (proiect național)», I24.4), report with 9 criteria + 4 perspectives, descriptions
  in `indicator-descriptions/psihologie-2026.json`; all in `seed/precious-config`, pinned by
  `Psihologie2026ReportDefinitionTest`. **Platform readings, stated in the descriptions:** N (principal-author count)
  = 1; Google Scholar (I12, I14), the preregistration bonus and I15–I27 are self-declared; WoS values are
  indicative (citation graph, no CPCI/BKCI); A1 is a publisher-level stand-in for a per-publication WorldCat rule;
  the C7 compensation (18 of 27 points via three principal-author AIS-Q1 articles) is not computed. **Left open:**
  Științe ale Educației is built as `H118`; Educație fizică și sport (its own I32, author rules and thresholds)
  is not; the admin report edit form does not round-trip `perspectives`/`weights`,
  so a report carrying them must not be saved through it. Prod: deploy, then load the definitions with
  `h115_psihologie_2026.js` and select the report for the FPSE division.
- [ ] `H114` WoS accession numbers (UT) for the CNFIS exports — **BUILT 2026-09-24, prod pending.** The resolver
  shelled out to `curl` (absent in the container) against Clarivate's keyless OpenURL gateway, so 0 of 166,105
  prod publications carry a `wosId` and Anexa 5's WoS-code column was empty. Verified 2026-09-24 that the gateway
  still answers: `GET ws.isiknowledge.com/cps/openurl/service?rft_id=info:doi/<doi>` → 302 with `KeyUT=WOS:…`
  (journal articles, Springer chapters AND IEEE/CPCI proceedings), or `OpenURLNoRecord.html`. Shipped
  `service/wos/`: `WosOpenUrlClient` (WebClient, no redirect-follow, Location parsed, deadlines, 250 ms throttle,
  FOUND/NOT_FOUND/UNAVAILABLE), spared `scholardex.wos_accession_lookups` + `WosAccessionService` (found = final,
  no-record re-asked after 90 days, unavailable never cached), `WoSExtractor` delegates (the facades' injection
  point is unchanged), nightly `WosAccessionSweepScheduler` (03:50, 400/night) over the platform researchers'
  papers linking ids through `PublicationEnrichmentLinkerService` — which also makes conference papers eligible for
  the "ISI Proceedings" flag. Properties `wos.openurl.*`. Tests: client (4 response shapes), cache policy, sweep.

- [ ] `H113` CNFIS export assessment (2026-09-24, asked before the management demo) — **FIXED, prod pending.**
  The 2025 templates (Anexa 5 individual, Anexa 6 institutional) are the current ones — cnfis.ro still lists
  2025 as the latest round — and the writer's column mapping matches them cell for cell. Found on a live local
  export with a real researcher's confirmed papers: (1) the export answered 500 for anyone with a conference paper
  (null e-ISSN `.contains`) — both the researcher button and the group export; (2) «număr autori din universitate»
  was built from GROUP memberships (7 of 82 researchers) → 0 for almost everyone, now every profiled non-EXTERNAL
  user; (3) ESCI/AHCI journals without a quartile row for the paper's year exported unflagged → year-true edition
  membership fallback (+ ERIH from membership); (4) a forum without a name NPE'd the cp/ch path. Not fixed, noted:
  the WoS accession-number column and the conference "ISI Proceedings" flag depended on a dead resolver → `H114`.
  Tests for all four; live export verified (17 rows).

- [ ] `H111` Faculty onboarding for the management demo (2026-09-24, presentation in 5 days; a competitor platform is
  being pitched). Readiness: Informatică ready; **Fizică** — faculty division + Matematică dept existed, no Fizică
  department/staff, FV Fizică 2026 in prod but not selected for the faculty. Vice dean's sheet (`ORCID_Fizica.xlsm`,
  24 staff, 19 ORCID-only / 5 Scopus-only) resolved to ids: ORCID public API (external-identifiers) + OpenAlex by
  ORCID gave Scopus ids for 18, Ștefu's via Scopus DOI search in-pod; OpenAlex `filter=scopus:` for the 5; emails
  from physics.uvt.ro (Călin Avram inferred). Ops files in rke2-overmind/feaa-2026-scripts: `fizica_staff_2026-09.csv`
  (staff import at /admin/divisions, institution inst-uvt; imported 24 OK) + phased `h111_fizica_bootstrap.js`
  (ORCID stamp → Scopus pubs FULL → citations FULL + OpenAlex; skips FAILED). **Trap found:** Paul Grăvilă is an
  ATLAS collaboration author (Scopus 16318633900: 971 papers × ~3,000 authors) — the author-works call hung 40+ min
  and parked the single-threaded scheduler; excluded from automatic import (HEPP exception is the commission's),
  task closed via H109 recovery after a restart. **FEAA / Psihologie:** fișe in prod, but no FEAA faculty at all and
  Psihologie = one test person under the environment institute → need staff lists from the deans (same 3 steps).
  Fișe selected on the faculty (done). Syncs finished 2026-09-24 ~19:00 UTC: 25 publication, 23 citation, 20 OpenAlex
  tasks completed (Grăvilă's ATLAS id excluded; his two other Scopus ids hold no documents). Left: one bootstrap run
  (prints DONE), score-provisional on the Fizică department (FV Fizică 2026), tell management perspectiva d is
  self-entered.
  **2026-09-29 — FPSE, FSAS, FMT (structure only; decision: each researcher triggers their own syncs, no tasks queued).**
  Sheets: `FPSE_Lista_ORCID.xlsx` (3 sheets = Psihologie 33 / DSE 16 / DPPD 24, with emails + positions),
  `2026_ORCID ID_CD_FSAS.xlsx` (39, names + ORCID only), `FMT_Conturi Orcid_titulari.xlsx` (59, positions + ORCID, no
  emails). Emails/positions for FSAS from the two staff pages (titles parse cleanly, 39 = 39); FMT emails by
  matching sheet names to the site's e-uvt addresses on name tokens (the table keeps name and email in separate
  columns). Scopus ids: ORCID person API + OpenAlex, then Scopus Author Search in-pod by `ORCID()` and, conservatively,
  by `AUTHLASTNAME AND AUTHFIRST AND AF-ID(60000434)` (single full-name hit only) → FPSE 62/73, FSAS 29/39, FMT 2/59.
  Ops files (rke2-overmind/feaa-2026-scripts): `fpse_staff_2026-09.csv` (73), `fsas_staff_2026-09.csv` (38),
  `fmt_staff_2026-09.csv` (54), `h111_orcid_stamp_fpse_fsas_fmt.js` (stamps ORCID only where absent, queues nothing),
  `staff_pending_confirmation_2026-09.csv` (6 people whose e-uvt address is unconfirmed — do NOT import as is).
  Dry-run on the local app: 165 rows, 3 faculties, 7 departments, 0 skipped; script idempotent. Five of them already
  have prod accounts (ids agree). Open: the 6 emails; which fișă each faculty sees (only FV Psihologie 2016 exists —
  Anexa 28 also covers Științe ale Educației but with p = 0.10, our report uses p = 1.00; FSAS and FMT have none).
- [ ] `H112` Deadlines on the core → scopus-python calls — **BUILT 2026-09-24, prod pending.** Same class as the DBLP
  (452a6266) and OpenAlex (f935d97a) holes: `scopusPythonClient` had no connector timeouts and the three scheduler
  calls (author-works, citations by-eid, by-title) `block()`ed without `.timeout()`. Now connect 10 s / idle-read
  15 min on the bean + `.timeout(scopus.python.request-timeout-ms)` (15 min) per call, mapped to a retryable
  `EXTERNAL_TIMEOUT`. Test: a never-answering author-works call fails the attempt at the deadline. NOTE: all
  @Scheduled jobs (Scopus pubs, Scopus citations, OpenAlex) share ONE `scheduling-1` thread — everything serialises.

- [ ] `H110` Category D journals on D(iv) editorial activity — Florin Fortiș, 2026-09-18. **BUILT 2026-09-18, prod pending.**
  His reading is the standard's own text (2026): «Categoria D: revistele ce nu se găsesc în categoriile A*, A, B și
  C», Beall's list excepted — no indexing condition, unlike conferences. The three roles (Director/editor/membru)
  share one score by design; only guest editor has its own scale. No effect on perspectiva c (a citation from D or
  from outside the lists is already 1) nor on b (A*–C only).
  Found in prod: an editorial entry names its journal by typed ISSN and the scorer only ever looked up WoS quartiles,
  so EVERY journal without a quartile scored 0 — Scopus-only journals (should be C = 6) and D alike; the old
  "SCOPUS fallback" on that path was dead code (the synthetic forum has no aggregation type). Alexandra Fortiș's two
  entries (Anale. Seria Informatică 1583-7165; GeoGebra 2068-3227) scored 0 instead of 3 each.
  Decisions (Adrian): D = any valid non-indexed ISSN; an ISSN the register could not be asked about scores NOW as D,
  carries an «ISSN neverificat» marker and is retried nightly; a clear "no such ISSN" is rejected at save time.
  Shipped: `ReportingLookupPort.findForumIdsByIssn` (interface + Postgres + @Primary delegator);
  `ComputerScienceJournalScoringService.scoreUnrankedJournalActivity` (excluded venue → 0; corpus forum in
  Scopus/ESCI/AHCI → C; valid ISSN not denied → D with `issnStatus`/`issnKeyTitle`; zero reasons `ISSN_INVALID`,
  `ISSN_NOT_FOUND` with dashboard labels); `service/issn/*` — `IssnSupport` (check digit), `IssnPortalClient`
  (portal.issn.org: 200 + `<title>ISSN … - key title</title>` / 404 unassigned / 400 bad check digit; anything
  else, incl. a bot wall under 200, = could not ask), `IssnVerificationService` + spared collection
  `scholardex.issn_verifications` + nightly retry (03:40), static `IssnRegistrySupport` seam for the scorers;
  save-time validation in `UserActivityInstanceFacade` (normalizes, rejects typos, skips the register for journals
  we hold) → 422 with a localized sentence the workspace form now shows (bundle rebuilt). OpenAlex was rejected as
  the authority: it did not know the Tibiscus annals and holds a junk record for 1234-5678.
  After deploy: `POST /admin/indicators/descriptions/apply` (Info_D_iv text), then refresh Alexandra's FV Info runs
  (+6 in perspectiva D). Her two entries predate the validation: they score D as «ISSN neverificat» at once, and the
  nightly job (03:40) queues every valid ISSN found on existing entries that the register was never asked about, so
  they become verified on the first night without anyone re-saving them.

- [ ] `H107` Citations not in Scopus: user-asserted / BibTeX citation import + review queue for unverified
  reference-title hits (spin-off of `H106`, 2026-09-13). Florin's case: the EMAC Insights book chapter and
  the ACM PLoP paper cite Alexandra's works but are not Scopus documents, so neither the EID pass nor the
  REFTITLE pass can ever find them; he suggests BibTeX. Shape: a researcher-submitted citing record
  (BibTeX/DOI/manual) with provenance `USER_ASSERTED`, admin approval like H93 venue claims, scored by
  the citing forum as any citation; second part: surface the REFTITLE hits the reference check rejected
  (today only logged and counted in the task message) as an admin/user review queue with one-click accept.

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
  - S2 — **BUILT 2026-09-14 (decisions: user-level marker set by admin; roll-ups exclude, refresh includes).**
    `AccountKind` INSTITUTIONAL|EXTERNAL on `User` (field default → every pre-H105 doc is institutional);
    `User.getAuthorities()` keeps only RESEARCHER for EXTERNAL, so supervisor/admin surfaces are closed at
    the authority level whichever way the account authenticates (the org-unit surfaces are all under
    `/admin/**` and `/supervisor/**`; the "global landing" is public, nothing to hide). `OrgUnitRosterService`
    gets a `RosterScope` — STAFF (default, drops externals: division/department/group roll-ups, comparison,
    promotion board, cockpit strip and unit rows all go through it) vs ALL (only the batch refresh, so the
    candidate's run stays current for the head). The supervisor department roster lists the candidate with a
    "Candidat extern" badge; admin Users page shows an "External" badge + a toggle
    (`POST /admin/users/account-kind/{email}?kind=`). Provisional scoring reads affiliations directly and still
    includes the candidate. Tests: roster scope, authorities, roster-page badge, refresh stubs. Ops after
    deploy: mark Drăgoi EXTERNAL (mongosh or the admin toggle) and delete his stray zero FV Matematică run.
    Deferred: an affiliation-level APPLICANT kind for the internal-candidate-to-another-department case
    (none seen yet). Original text of the slice follows.
    "applicant affiliation" (DECIDED over per-user report assignment): the external candidate gets
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

- [ ] `H50` Individual report export / read-only score-verification import.
  **STATUS (2026-06-30): mostly done — H62/H65 overtook most of the "remaining" list. The genuine gap is docx *import*
  verification (H50.6). Entry below refreshed.**
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Done since (via H62/H65): **docx export (H50.4)** is wired — `ReportExportFacade` renders any format the support declares, with the DOCX content-type + `TemplateDocxRenderer`. **Report-type coverage (H50.5) largely done** — bindings now exist for `informatica-2016`, `matematica-2016`, **`feaa-2024`**, **`fizica-ff`** (4 of the report types), each with a `ReportTypeImportSupport` that renders docx.
  Remaining: **docx *import*/verify (H50.6)** — the docx supports' `parse()` still throw `UnsupportedOperationException` (Fizica/Feaa), so score-verification upload is xlsx-only; implement docx parsing for the docx report types. Plus any report definitions still lacking a binding (the remainder of H50.5).
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

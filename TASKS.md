# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H134` The institution page shows the publications of its faculties' staff — **BUILT and DEPLOYED 2026-09-30**
  (image `bda96cdc`, GitHub `deploy-prod`: STATUS deployed, revision 78, 13:52 UTC; read from the deploy log). After H133 the page rendered but showed 0 publications: it found them only through the Scopus
  affiliations linked to the institution record, and `inst-uvt` was linked to none. Linking "West University of
  Timişoara" (Scopus 60000434, canonical `saff_e5c95b34ac384552866dc020`) would have walked 4,466 authors and
  18,712 publications (prod, 2026-09-30) and rendered them all on one page. **Decision (user):** derive the page
  from the faculties' staff instead. **Now:** every faculty (division) of the institution → its departments'
  current staff (`OrgUnitRosterService.divisionRoster`, STAFF scope) → each researcher's effective publications
  (`EffectiveAuthorshipReadService.findEffectivePublicationsForUser`: profile identifiers with the researcher's
  confirmations and rejections applied); a publication shared by two researchers counts once for the
  institution and once per faculty. The overview shows faculties, staff and publications and a table per
  faculty; the publications tab shows one link per year with its count and the list of ONE year (`?year=`,
  newest by default; undated papers under "No year"). The Excel export draws on the same corpus. The
  affiliation walk is gone from the page; the institution form still keeps the affiliation links for other uses.
  **Local check** (2 faculties, 56 staff, 1,430 publications): the page answers in ~1 s, a year's list in
  0.4 s, the export in 5 s. Prod has ~250 staff, so expect a few seconds: the page resolves each researcher
  separately (three queries per person). **Not done:** no caching; a researcher affiliated to a department
  without a faculty is not counted; the 2016 affiliation-based export columns are unchanged.
  **H127 in use (2026-09-30):** Andrei Rusu declared equal contribution on one article, Adrian approved it as
  admin, two refreshes later the article moved from I6 to I2 (+6.2, its journal is a management one, so not I1A) —
  the flow works end to end; the head could not see the change because the run is only rebuilt by the Refresh button.
- [ ] `H133` The institution page failed on a record without Scopus affiliations — **FIXED and DEPLOYED 2026-09-30**
  (image `f95628a3`, GitHub `deploy-prod`: STATUS deployed, revision 77, 08:27 UTC; read from the deploy log).
  `/admin/institutions/inst-uvt` answered 500 (request `3d8abfbc…`, 07:10 UTC): a NullPointerException in
  `AdminInstitutionReportFacade.loadInstitutionPublications`, which iterated the institution's Scopus
  affiliations, and the seeded `inst-uvt` record has none (only the edit form sets them, nobody ever did).
  Not a regression: the page had failed in that state since the seed. The list is now read as empty, so the
  page renders with no publications; regression test in `AdminInstitutionReportFacadeTest`. **Superseded by H134:**
  the page no longer reads the affiliations at all.
- [ ] `H132` Staff import reads quoted fields; FEAA starts with three test users — **BUILT and DEPLOYED 2026-09-30**
  (image `d2d29af1`); **the file is imported in prod and the ORCID script stamped 3** (2026-09-30 morning, user-run). Two of the four FEAA departments have commas in their names ("Finanțe, Sisteme Informaționale și
  Modelare pentru Afaceri", "Marketing, Afaceri Internaționale și Economie") and the staff import split every
  line at every comma, so such a row came out with shifted columns. **Fix:** `StaffImportService.splitCsvLine`
  reads a field between double quotes as one value (a quote inside it is written twice), for the header too;
  a byte order mark in front of the header is dropped; a row whose quotes do not match is skipped with its own
  message and the rest of the file is imported. Files without quotes are read as before. The help text on
  `/admin/divisions` shows the quoted form. **FEAA:** prod has the reports (FEAA 2016, FEAA 2026) and no FEAA
  faculty. Test users chosen by Adrian, all professors in the Finance department: Cosmin Eugen Enache, Flavia
  Barna, Marilen Gabriel Pirtea. Identifiers from public sources only: the ORCID records of Enache and Pirtea
  carry a Scopus Author ID asserted by Scopus; Flavia Barna's record has none and OpenAlex gives none either,
  so her Scopus id is empty in the file. Ops files (rke2-overmind/feaa-2026-scripts):
  `feaa_test_users_2026-09.csv`, `h132_orcid_stamp_feaa_test_users.js` (stamps only where the profile has no
  ORCID, queues nothing). **Dry run on the local app:** 3 rows, 1 faculty, 1 department with its full name, 3
  professors, 3 affiliations; a second import and a second run of the script changed nothing; the records were
  removed afterwards. **Done in prod:** image deployed, file imported at `/admin/divisions` (institution
  `inst-uvt`), script run. **Left:** select FEAA 2026 for the new faculty. **Open:** Flavia Barna's Scopus id; the
  other staff of the four departments (the site lists 136, without academic rank).
- [ ] `H131` Citation counts with a recorded source — **OPEN, from the compliance audit of 2026-09-29 (question 8).**
  The canonical `citedByCount` is the maximum over Scopus and OpenAlex (`CanonicalGraphBuilder.buildPublicationFact`,
  `ScholardexPublicationCanonicalizationService`, `OpenAlexCanonicalizationService.enrichForeignPublication`,
  `PublicationMergeService`), the projection falls back to the number of edges when there is none, and nothing
  records which source the number came from — so a record badged "Scopus" can show OpenAlex's count. Users also
  see two different totals: the Publications tab and the author page sum the scalar, the workspace overview
  counts edges. Wanted: a count per source on the canonical publication (Scopus, OpenAlex) beside the edge count,
  carried into the read model, and every displayed number labelled with its source; the Scopus count links to
  the cited-by list on Scopus (Elsevier's attribution rule), and the public pages may then show the OpenAlex
  count labelled as such (`H119` hides counts until this exists). One definition of "total citations" for the
  workspace. Both canon paths change together; needs a rebuild, so plan it with the next one.
- [ ] `H130` Privacy notice and complete deletion of a user — **OPEN, from the compliance audit of 2026-09-29
  (question 29).** **(a) Notice.** The platform documents no legal basis, retention period or list of processed
  fields, and has no privacy page. Build the page (public, linked from the footer, RO + EN) and draft the field
  list from the models (`User` + `ResearcherProfile`: email, names, PhD year, position, Scopus / WoS / Google
  Scholar ids, ORCID, affiliations; report runs, indicator results, authorship decisions, evaluation snapshots,
  department affiliations, memberships, workspace preferences, declared activities). The legal basis, the
  retention period and the contact are the university's to state: text from the data protection officer. The
  page also says that staff publication pages are public (`H119`). **(b) Deletion.** `UserService` deletes only
  the `users` document; `OrgHeadPruningListener` removes the person from head lists; report runs, indicator
  results, authorship decisions, snapshots, affiliations, memberships, preferences and activity instances stay,
  keyed by the email. **Decided with Adrian 2026-09-29: keep the scores, anonymised.** So deletion removes the
  profile, the preferences, the declared activities and the authorship decisions, and re-keys the report runs
  and indicator results to an opaque id that cannot be traced back, keeping the org unit they count for — the
  unit roll-ups (`OrgUnitRunRollupService`) must give the same numbers before and after. Check what inside a
  stored run still names the person (evidence rows, activity text) and drop it. Also wanted: an export of a
  person's own data on request.
- [ ] `H129` CNFIS reporting: its own sidebar entry, editions, the sheets of Anexa 5 and 6 — **OPEN; redesigned
  with Adrian 2026-09-30 (supersedes the first entry of 2026-09-29); SLICE 1 BUILT 2026-09-30, not pushed.**
  Slice 1 as built: `CnfisEdition` (2025, and 2027 provisional; an unknown window makes a provisional edition
  of itself); `CNFISScoringService2025.getReport(publication, domain, edition)` with rules (1)–(5); each row
  records the list year and what classified it, each left-out publication its reason; the workbook gets a last
  sheet "Neincluse (platforma)" with the left-out publications — the platform's note, to delete before the file
  is handed in (it moves to the page in slice 2); `ReportingLookupPort.isForumCpciIndexed`, answered by the
  primary facade from the canonical forum; both CNFIS facades look nothing up and write nothing;
  `WosAccessionSearchService` (was the sweep scheduler) behind `POST /admin/initialization/wos/accession/search`
  (`limit`, default 400, at most 2000); `WoSExtractor` and the `wos.openurl.sweep.*` properties are gone.
  **Changes a user will see in the file:** a publication in none of the categories no longer gets a row without
  a mark; conference papers are "ISI Proceedings" by their venue, so a paper with a WoS code in a venue outside
  the conference index loses the mark and one without a code in an indexed venue gains it; book chapters are
  out. **To run on real data before relying on it (Adrian):** the count of rows per category before and
  after, for one faculty. **Not built:** a button for the admin operation (it is an endpoint).
  **Decided 2026-09-30:** the sheet of left-out publications stays in the file until the page shows them; the
  CNFIS sheets classify from the quartiles already loaded (the lists on cnfis.ro are not loaded).
  **SLICE 2a BUILT 2026-09-30, not pushed.** `ReportAuthority` (CNATDCU, UEFISCDI) and
  `IndividualReport.authority`, read through `effectiveAuthority()` — a stored report that names none counts as
  CNATDCU, so nothing changes until the three UEFISCDI reports are marked. The admin report form has the input
  ("Applies the rules of"), pinned by the round-trip test. `/user/evaluation?authority=…` shows the reports of
  one authority; a report asked for by id brings its own. The sidebar has three entries: CNATDCU, UEFISCDI,
  CNFIS. `/user/cnfis` lists the editions with their window and the download of Anexa 5 — the page slice 2b
  grows into. **At deploy:** run `scripts/ops/set-report-authority.sh` (marks Eligibilitate PD, Eligibilitate PD 2026
  and Eligibilitate Tinere Echipe as UEFISCDI, by title, keeping the previous values), or set the authority
  of each in the admin report form (until then they show under CNATDCU and the UEFISCDI
  entry is empty). **Left as it was:** the delegated view of a supervisor lists all the reports of a researcher
  together; the preferred report is one per person, so it applies only under the entry it belongs to.
  **Not checked in a browser** (a run needs real data).
  **SLICE 2b BUILT 2026-09-30, not pushed** — the CNFIS page proper (`/user/cnfis?edition=`), per edition:
  (1) the preview of Anexa 5: rows with the list year and what classified each, the left-out publications with
  their reason, the totals by column (`CnfisReportingFacade.buildSheet`, on `UserReportFacade.buildCnfisSheet`,
  which the download uses too); (2) the head of the sheet the person fills in (`cnfisSheets`): the CNATDCU
  domain from the template's own "Domenii-CNATDCU" list (`CnfisDomainCatalog`, 84 codes), the CNATDCU score
  from a chosen CNATDCU report (the sum of its contributing criteria, the evaluation page's rule) or typed in,
  the unmet criterion, the three Hirsch values typed from the print screens; (3) patents from the declared
  activity "Brevet", which gains the fields "Cod brevet", "Oficiu", "N_autori_universitate" (seed +
  `scripts/ops/add-brevet-fields.sh`); written to the form after the publications; (4) `User.staffRecord`
  (employment type of the CNFIS staff sheet + from/to), filled by the head on the department roster page;
  "authors from the university" = co-authors with an account who are staff at the reference date; a
  co-author without a record is counted and NAMED on the page; (5) frozen copies (`cnfisSheetSnapshots`):
  the sheet as handed in, rows + left-out + patents + head + score, downloadable from what it holds; the
  person releases a copy unless `lockedByUnitSheetId` is set (slice 3 sets it when Anexa 6 is built).
  Decisions: the head fills the staff fields, the person sees them; the left-out sheet stays in the file; the
  person releases until Anexa 6 is built, then only the head. **At deploy:** run `add-brevet-fields.sh`.
  **Not done:** the Hirsch values of the platform are not shown beside the typed ones; the domain does not yet
  hide sheets 5.1–5.3 (they do not exist); the old `/user/exports/cnfis` download stays (no patents in it).
  **Not checked in a browser.**
  **SLICE 3 BUILT 2026-09-30, not pushed** — Anexa 6 for heads: `/supervisor/departments/{id}/cnfis` (the
  department's heads and the faculty's, as the roster) and `/supervisor/divisions/{id}/cnfis` (the faculty's
  heads only), linked from the supervisor workspace rows. The page lists the members with the sheet that
  represents each: their own latest FROZEN copy; failing that, the latest PROVISIONAL copy a head generated
  (`CnfisReportingFacade.freezeProvisional`, marked and signed by the head, shown on the member's own page with
  a badge; the member's own frozen copy replaces it). The head generates provisional copies one by one or for
  everyone without a sheet. "Build Anexa 6" (`cnfisUnitSheets`, `CnfisUnitFacade.buildTable`) takes the
  representing sheets, LOCKS them (`lockedByUnitSheetId` — the member can no longer release), names the
  members without any sheet, and downloads as the institutional template with a paper two members share
  ONCE (and a patent once). Deleting a table releases its sheets. A faculty is one table over all its
  departments, with the department shown per member on the page (the template has no such column).
  Anexa 6 lists a paper two members share ONCE (confirmed by Adrian 2026-09-30: duplicates are removed). Source of the rules: the
  CNFIS guide "Cerințe și recomandări privind raportarea datelor … IC2" (January 2025, cnfis.ro).
  **The shape.** Three sidebar entries instead of "Evaluation": **CNATDCU** (the domain reports: every FV, FEAA
  included), **UEFISCDI** (Eligibilitate PD, PD 2026, Tinere Echipe) and **CNFIS**. A report carries a new field
  naming the authority it applies to — it must get an input on the admin report form (`H116`: the form wipes
  what it has no input for) and a value on every stored report, by script, at deploy. CNFIS is
  **edition × sheet**. Reporting happens every two years over a four-year window, so windows overlap:
  edition 2025 = reference date 1 January 2025, window 2021–2024; edition 2027 = 1 January 2027, window
  2023–2026, PROVISIONAL (the 2025 rules on the new window) until CNFIS publishes its guide and templates.
  Editions are defined in code (a few parameters + the template files), not as editable data. Sheets: **Anexa 5**
  (articles and patents, everybody), **5.1** (artistic creation), **5.2** (sport), **5.3** (humanities: Scopus
  articles by CiteScore quartile + books, edited volumes, chapters, critical editions, translations); the
  institutional tables **6, 6.1, 6.2, 6.3** are the sums of the individual sheets. Which sheets a person fills
  follows from their CNATDCU domain: the person picks it (list = the "Domenii-CNATDCU" sheet of the template),
  the head sees the picks of the unit's members. Anexa 5 also asks for the CNATDCU score and the unmet
  criterion: pre-filled from the person's CNATDCU report when the platform has one for the domain, typed in
  otherwise; and for the three Hirsch values, which the platform shows for orientation only (`H128`).
  **The rules of Anexa 5.** (1) list by year: JCR of the publication year, and for the LAST year of the window
  the list of the year before ("pentru articolele publicate în anul 2024 … lista JCR din 2023"); a rule of the
  edition, not of what is loaded — so the same 2024 article is ranked by JCR 2023 in edition 2025 and by JCR
  2024 in edition 2027, both correct, and the page says why. (2) best classification of that year "indiferent
  de clasificare IF sau AIS sau de categorie": the better of the AIS and the impact-factor quartile, over all
  categories (the export read AIS only). (3) document types: Article, Review, Proceedings Paper; the chapter
  path goes away — it was there for proceedings indexed as chapters, which are now found by their venue.
  (4) ISI Proceedings = the conference-index flag of `H76` (`wosCpciIndexed`), no longer "has a WoS code"; IEEE
  Proceedings by the resolved venue, no longer by "IEEE" in the name. (5) a row needs a DOI or a WoS code: the
  rows without are listed to the user with the reason, not dropped after a blank row was copied. (6) patents
  come from the declared activity type "Brevet" (its four types ARE the four CNFIS columns), which gains the
  optional fields the form asks for: patent code, issuing office, authors from the university. (7) "authors
  from the university" means the staff of Anexa 1 at the reference date (tenured or full-time fixed-term;
  no doctoral students, associates, retirees) — the platform counts every non-external account. The staff
  import carries no employment type nor dates: add the fields and let the users fill them in.
  **No side effects in a download.** The export resolved WoS codes against Clarivate's link resolver and wrote
  them back; it now uses only what is stored. Codes are found by an ADMIN OPERATION ("search WoS codes for the
  new publications"), which REPLACES the scheduled sweep of `H114`/`H122` (c): only publications of the
  university's own authors, never citing papers. A second admin operation refreshes the conference index from a
  new library export, before each edition.
  **Anexa 6** is a supervisor operation for the head's own unit (`@orgUnitAccess`, `H123`), built from the sheets
  the members FROZE (the evaluation snapshots, reused); for a member without one the head generates a
  PROVISIONAL sheet, marked as such, to hand over for signature. Frozen and provisional rows are told apart.
  **Slices.** 1 = editions + rules (1)–(5) behind the existing download, no lookups in exports, the admin
  operation for WoS codes. 2 = authority field, three sidebar entries, the CNFIS page with Anexa 5 (preview,
  reasons, domain, CNATDCU score, patents, staff fields, snapshots). 3 = Anexa 6 for heads. 4 = Anexa 5.3.
  5 = Anexa 5.1 and 5.2. Slices 4–5 wait for the templates (Adrian prepares them when the time comes).
  **Classification source, to decide:** cnfis.ro/clasificare-reviste-isi publishes the lists CNFIS itself
  expects (PDF, IF and AIS, 2020–2023 by CNFIS, earlier years by UEFISCDI). Loading them would make the CNFIS
  sheets independent of the JCR harvest (`H122` b); slice 1 classifies from the quartiles already loaded.
  Also noted by the audit: `CNFISScoringService2025` set `erihPlus` for SCIE/SSCI categories too.
- [ ] `H128` Hardening after the compliance audit — **BUILT 2026-09-30, not pushed.** As listed below, with
  these differences: (4) the `agent-dev` guard looks at `KUBERNETES_SERVICE_HOST` (a pod always has it, a
  developer's machine never), not at the Keycloak issuer — a local `.env` may name the staging realm, and the
  documented local run must keep working; (5) the allow-list gates only the CREATION of an account at first
  sign-in, so existing accounts of any domain sign in as before; (6) the note went into the per-indicator
  workbook (a "Notes" sheet) — the fișa de verificare exports are official forms and were left alone; (7) the
  key travels as an `Authorization: Bearer` header, never in the URL (request URLs end up in the error message
  stored on the sync task), and a 429 is retried inside the call (5 s, 10 s, 20 s) before the task fails into
  its own backoff. New error code `EXTERNAL_RATE_LIMITED`. The wrapper logs the remaining Scopus quota after
  every call (warning under 500). **Not done:** the h-index stat of the workspace Publications tab is drawn by
  the frontend bundle and has no caption to carry the notice (the overview card and the publications page do).
  **To do at deploy:** put `OPENALEX_API_KEY` into the application secret. **Known limit of (1):** the Scopus
  ids of a profile are self-declared, so the check makes a pull attributable, not impossible. Original list —
  seven small items, none needs a decision.
  (1) **Scopus sync only for one's own ids** (question 4): `ResearcherWorkspaceController.triggerSyncPublications`
  / `triggerSyncCitations` and the form endpoints in `UserViewController` take a Scopus author id from the
  request, and `UserScopusTaskFacade` checks nothing — any signed-in user can make the platform pull another
  person's record. Accept only ids on the caller's own profile; platform admins exempt (external candidates,
  `H105`). (2) **Scopus rate limits** (13): the wrapper answers 429, and `ScopusIntegrationExceptionMapper` maps
  it to a non-retryable failure; make it retryable with backoff, and log the remaining quota and reset time
  (pybliometrics exposes both) so the weekly limits are visible before they are hit. (3) **Hirsch index
  notice** (27): the CNFIS guide asks for signed print screens from Google Scholar, Web of Science and Scopus;
  say next to every h-index and on the CNFIS page that the platform's values are for orientation and
  pre-filling only. (4) **`agent-dev` cannot start in production** (28): today only a log warning; refuse to
  start when the profile is active together with a configured Keycloak issuer. (5) **sign-in limited to the
  university's accounts** (28): `KeycloakOAuth2LoginSuccessHandler` provisions any verified email; the
  restriction lives only in the realm (confirmed by Adrian). Add the in-app allow-list as a second barrier,
  reusing `app.roster.invite-allowed-domains`; existing accounts and EXTERNAL accounts created by an admin still
  sign in. (6) **estimates labelled in the exports** (24): the indicator description ("valoare orientativă")
  shows on the report page but not in the exported file. (7) **OpenAlex API key in the app** (16): the Java
  client sends only `mailto`; send the key the Python scripts use (`OPENALEX_API_KEY`, from the secret, never
  from a committed file), and handle 429 / Retry-After instead of failing the whole sync.
- [ ] `H127` Declarations of principal authorship, approved by a head — **BUILT and DEPLOYED 2026-09-29** (image
  `05218a47`, GitHub `deploy-prod`: STATUS deployed, revision 74, 20:02 UTC; read from the deploy log, the
  cluster was not queried). From the
  first feedback on FV Psihologie 2026 (Andrei Rusu, director of the Psychology department): most of his papers
  as corresponding or co-first author counted as co-author ones. **Cause:** the annex (Comisia 28) names five
  kinds of principal author — single, first, corresponding, equal contribution with the first author, and last
  author for sport only. The platform knew the first author and, of the corresponding ones, only those OpenAlex
  marks (`is_corresponding`, a minority of papers); Scopus gives none through the bridge, and equal contribution
  is in no source. **Flow:** on the publication row of the workspace a co-author declares "corresponding
  author" or "equal contribution", says where the article states it (10–1000 characters) and may add a link.
  The declaration is PENDING until a head of the researcher's department or of the faculty above it, or a
  platform admin, approves or rejects it on `/supervisor/declarations` (rejection and revocation need a reason).
  Nobody decides on their own declaration; a group supervisor is not enough. **Effect:** only APPROVED ones
  count. `EffectiveAuthorshipReadService.findConfirmedPublicationsForScoring` hands the declared publication on
  as a COPY that lists the researcher among its corresponding authors, which is what the role filter reads —
  so every report with a principal/co-author split follows (Psihologie 2026, Științe ale Educației 2026, Fizică
  2026, PD 2026) and nothing stored changes. Scores change at the next refresh of the report. **Record:**
  `scholardex.principal_author_declarations`, a side collection that is never rebuilt, one document per
  researcher and publication with its whole history (declared, approved, rejected, withdrawn, revoked: who,
  when, note); the publication is also remembered by DOI, title and year and found again after a rebuild.
  **Decisions (user, 2026-09-29):** either head approves, admins always; evidence is a short text plus an
  optional link; do not wait for Scopus to supply corresponding authors. **Checked on the local instance:** the
  form, the refusals, declare → pending → approve → take back, the review page at phone width. Found there and
  fixed: a message with double quotes cut the placeholder attribute short, so attribute values are escaped.
  **Not done:** no notification to the head when a declaration arrives; an approval does not refresh the report
  by itself; a researcher without a department affiliation can only be decided by an admin; the 2016 Psychology
  report (roles MAIN/CO) is not affected.
- [ ] `H126` The language backfill covers the works nobody synced — **BUILT and DEPLOYED 2026-09-29** (image
  `392280bb`, GitHub `deploy-prod`: helm `--wait`, STATUS deployed, revision 73, 19:09 UTC; read from the deploy
  log, the cluster was not queried). The user started the job from the admin page the same evening. **Result
  in prod:** DONE after 8 passes — 3.520 works, language found for 3.396 (96 %), not given by OpenAlex for 124;
  947 venues asked, 806 with a country (85 %). The remaining venues give m = 1,5, never 2. Reports take the
  new coefficient when they are refreshed. Found
  the evening `H125` went live: a researcher needs no personal sync to get a report — the publications of the
  author ids on the profile are in the corpus from the bulk import, the researcher confirms them and is scored.
  Those works were stored before the platform read the language and carry no `syncedResearchers`, so the `H125`
  backfill (synced works only) never reached them and their coefficient m stayed 1. Now a pass takes the synced
  works first and then the works of the platform's researchers, however they came: researcher → author ids
  (`CacheService.getUniversityAuthorIds`) → publication ids (one projected query) → OPENALEX source links →
  works without a language, in chunks of 500, leaving as soon as the pass is full. Works of third parties stay
  out. **Background job** (`OpenAlexLanguageBackfillJob`): passes until one finds nothing, one job at a time,
  state in memory (IDLE / RUNNING / DONE / STOPPED_AT_LIMIT / FAILED); started and read from the admin
  initialization page, section "Publication Language", JSON at `/admin/openalex/language/backfill/status`.
  Checked on the local instance: both kinds of works are taken, 100 works in about 3 s, the second start while
  running changes nothing. In that sample OpenAlex had a country for roughly 8 venues in 10; the rest give
  m = 1,5 ("place unknown"), never 2. **Prod:** deploy, press Start once, refresh the reports afterwards.
- [ ] `H125` Coefficient m from the language and place of publication (Comisia 25) — **BUILT and DEPLOYED
  2026-09-29** (image `1cadccb2`, with `H124`). Replaces the provisional m = 1 of `H124`. **Data:** OpenAlex gives the language of a work
  (`language`, ISO 639-1) and, on the SOURCE entity only, the country of the venue (`country_code`; the source
  embedded in a work does not carry it — checked against the live API). The sync now stores the language on
  `openalex.publication_facts` (a payload without one never erases a stored one) and asks `/sources` once per
  venue, into the side collection `openalex.source_countries` (never rebuilt; "no country" is re-asked after
  `openalex.source-country.retry-days`). A sync does not fail when that call does. **Scoring:**
  `PublicationCoefficientService` registers as the resolver of `Coef_m` and reads only what is stored:
  canonical publication → OPENALEX source links → work → venue country. International language (en, fr, de, it,
  es) + venue abroad → 2; international language + Romania, or place unknown → 1,5; other language → 1; language
  unknown → 1 (`NOT_DETERMINED`). Every unknown resolves downward. "International peer review", which the annex
  also asks for m = 2, cannot be looked up. **Shown to the researcher:** a badge "m = …" next to each publication
  of an indicator that uses the coefficient, the basis in the tooltip. **Backfill** for works synced earlier:
  `POST /admin/openalex/language/backfill?limit=500` (platform admin; bounded; repeat until `candidates` is 0;
  a work OpenAlex has no language for is marked `und` so it is not asked about again). It reached only the
  works a researcher had synced personally — widened by `H126`. **Limits:** a publication the platform holds from Scopus only has no
  OpenAlex record and stays at 1; books rarely have a venue in OpenAlex, so they reach 1,5 at most; declared
  entries keep the coefficient the candidate picks. **Prod:** the five descriptions that mention the coefficient
  and the description of the report are brought up to date by `h124_sociologie_2026.js` (regenerated; safe to
  run again after an earlier run). **Not done:** no scheduled sweep — the sync covers new works and the backfill
  is a one-off.
- [ ] `H124` FV Sociologie și Asistență Socială 2026 (OM 3.019/2025, COMISIA 25) — **BUILT, DEPLOYED and LOADED in
  prod 2026-09-29.** Image `1cadccb2` (GitHub `deploy-prod`: helm `--wait`, STATUS deployed, revision 71, 17:24
  UTC; read from the deploy log, the cluster was not queried). `h124_sociologie_2026.js` run by the user with
  `--restart`: 18 activity types, 2 domains, 42 indicators (14 flagged) and the report created, 0 unresolved
  references, indicators in the committed order; report id `6abbfc470faa5f42002d72e0`, FSAS division
  `6abb581c2fb0d9482997faec`. **Left for the user:** select the report for the FSAS division; the faculty's
  answers on the readings listed below.
  One report for the whole FSAS faculty: the annex treats Asistență Socială as part of the group "Sociologie"
  (same core/related categories, same column of the A2 publisher list, same thresholds). 42 indicators `Soc26_*`
  (29 that score, 13 that only count or take a share), 18 activity types "(Comisia 25, …)", «Grant Cercetare»
  reused, 2 domains, 10 criteria, 5 perspectives. Thresholds conf/prof/abilitare: C.1 I.1 10/15/15 · C.2 core share
  -/30/30 % · C.3 core+related 50/70/70 % · C.4 articles+chapters 5/10/10 · C.5 books 1/2/2 · C.6 I.1–I.8 50/100/100 ·
  C.7 I.9 10/20/20 · C.8 citations 25/50/50 · C.9 total 130/200/200 · C.10 after habilitation, professor only.
  **Engine.** (1) Share criteria: `Criterion.shareOfIndicatorIndices` turns a criterion into a percentage of the
  sum of the listed indicators (0 when that sum is 0); computed at the one aggregation point
  (`ReportingComputationSupport`), carried over by the edit form like the weights. (2) Indicator flag
  `sociologie2026` → `Comisia25Rules`: an impact factor counts in EVERY Web of Science edition (the related list
  holds AHCI-only categories), exact publication year, latest value only for an article newer than the published
  data. (3) `SOC_INDEXED_JOURNAL` (I.2): Scopus journal 4, three recognised databases 2, an article with impact
  factor is left to I.1. (4) `CITING_IMPACT_FACTOR` (I.9): S = 0,2 + 4·f of the CITING journal, never 0, so a
  citation from a book or a journal without impact factor reaches the formula. (5) `PSYCH_BOOK` gets a Comisia 25
  branch (A2 list of the group, WoS Master Book List as A1, S = 1 for both). (6) Formula variable `Coef_m`,
  bound from `PublicationCoefficientSupport`. C.5 "one A1 book or two A2 books" is a weighted count (A1 = 2).
  **Decisions (user, 2026-09-29):** list A1 = WoS Master Book List; an article with impact factor goes to I.1;
  a MEMBER of a project above 30.000 EUR gets 4 points (the annex prints a value for coordination only);
  WorldCat holdings are declared by the candidate. **Chosen without being asked, to confirm with the faculty:**
  C.4 counts only what is scored at I.2 and I.6 (the letter of "(I.2+I.6)"), not the articles placed at I.1;
  a grant without a stated budget scores at the first tier; "Planning & Development" (the name Development
  Studies had until 2017) is in the core; the related list repeats "Language Linguistics" where it may have meant
  Linguistics — only the category printed is applied. **Provisional:** `Coef_m` is 1 for every publication the
  platform finds (it holds neither the language nor the country of the publisher), so I.2–I.8 can only rise;
  declared entries carry the coefficient the candidate picks. **Not computable, declared instead:** journals
  reaching three databases through EBSCO/CEEOL/ProQuest/… (the platform knows WoS, Scopus, DOAJ, ERIH Plus),
  books and chapters the platform does not hold, coordinated books, reviews, translations, citations outside the
  platform; ISI Proceedings papers score at I.8 (1 point), not at I.2. **Found while building:** in an
  ASSIGNMENT the formula engine takes the type of the variable from the LAST literal of a conditional —
  `m = c ? 2 : (d ? 1.5 : 1)` fails on the 1.5 branch and the item silently scores 0; every branch is written as
  a decimal, and `SeedFormulaLiteralTypesTest` guards all committed formulas (none was affected). Prod: script
  `h124_sociologie_2026.js` (deploy guard, idempotent, rehearsed on a scratch database: 0 type differences,
  earlier documents untouched), then select the report for the FSAS division. The provisional coefficient is
  replaced by `H125`. **Next:** Științe Administrative and Științe ale Comunicării are one `Comisia25Rules`
  value, one publisher column and two domains away.
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
- [ ] `H120` Scopus: fetch and keep only the fields the CRIS policy allows — **BUILT 2026-09-29 (stop sending +
  stop collecting). No purge — decided 2026-09-29, see the end of this entry.** Decided with Adrian: PII stays (it is
  a merge key), funding and open access come from OpenAlex. **What changed:** (1) the wrapper no longer returns
  abstract, funding, open access nor the matched reference text — the Abstract Retrieval call STAYS, because the
  STANDARD search gives only the first author and the call is what supplies the author list, the affiliations
  and the venue of fresh papers; (2) `ScopusLicensedFields` strips those fields from every Scopus publication and
  citation payload where it enters the platform (`ScopusImportEventIngestionService`), so the dump import and the
  raw event layer are covered too — a re-sync replaces a stored event with its stripped form; (3)
  `ScopusFactBuilderService` clears them on the Scopus fact and no longer writes `scopus.funding_facts`; (4) the
  canonical record takes none of them from Scopus, in BOTH paths (`ScholardexPublicationCanonicalizationService`
  and `CanonicalGraphBuilder`); (5) funding is read from OpenAlex (`OpenAlexFunding`: "Funder (award); Funder",
  from `grants`, else `funders`) and, with open access, written by OpenAlex also onto records Scopus owns; (6)
  `ScholardexPublicationView` never serialises abstract and keywords, which closes the workspace citations
  response. **No purge (decided with Adrian, 2026-09-29):** the fields are neither collected nor
  displayed, the policy restricts the public DISPLAY of abstracts and lets stored metadata be kept, and a
  re-sync overwrites a stored record with its stripped form — a full rebuild for this alone is not worth it.
  What stays as stored: the fields on records nobody re-syncs, the open-access flag of records OpenAlex does
  not know, `scopus.funding_facts`. The wrapper pod's pybliometrics cache has no volume and empties at every
  deploy. The local dump files are a different matter (a plain file on a laptop) and moved to `H122` (d). **Not verified:** that the live OpenAlex API still names the field `grants` — the documentation page
  had moved; check one real work before relying on the funding column. Original entry, from the compliance
  audit of 2026-09-29: The wrapper calls Abstract Retrieval `view=FULL` for every own AND every citing record, because
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
  integration support again), stating storage of citing-paper METADATA and its use in internal scoring. Ask
  in the same message whether abstracts stored before `H120` must be deleted (they are no longer collected
  nor displayed). Until
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
  own papers, 400 per run. **(d) Real data out of the development directory; AI tooling is a
  developer only:** no access to Scopus dumps, the personal-data backups or production. Enforce it: move
  `data/scopus/`, `data/backups/` and the prod kubeconfig out of reach, drop the kubectl/mongosh/psql
  permission from `.claude/settings.local.json`, develop on synthetic or OpenAlex-only data. The Scopus dump
  files (`data/scopus/complete_scopus_*.json`, 483 MB, ~150k abstracts of citing papers, and the copy under
  `scopus-python/`) are what is left of the out-of-list fields outside the application's access control —
  keep them on the server or on an encrypted volume, not on a laptop.
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

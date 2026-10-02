# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H136` UEFISCDI eligibility for the social/economic and the humanities families — **SLICE 1 DEPLOYED and LOADED in prod 2026-10-02 (with `H137`); slice 2 (humanities) open, on request.** Built as scoped: `AIS` formula variable (the PD_WOS placement records the journal's AIS
  value; bound lazily in `ScientificProductionService`), `Editura_7c` and `An_activitate` activity variables
  (`ActivityReportingService`; the 253 publishers of Anexa 7c bundled as
  `report-data/uefiscdi-anexa7c-publishers-2026.csv`, exact-name match with «Univ.»/«&» folded —
  `UefiscdiPublisherSupport`), activity type «Carte sau capitol la o editură din Anexa 7c (UEFISCDI PD/TE)», domain
  «UEFISCDI științe sociale și economice» (the 70 SSCI categories), 6 indicators `UEF26SE_*` (articles 70 × AIS / N
  on Q1/Q2 article/review, 2016–2026 director PD and 2015–2026 mentor PD / director TE; declared books 60 / N and
  chapters 30 / N, windowed by the activity's year in the formula since activities are not filtered by the
  indicator's year range), reports «Eligibilitate PD 2026 — științe sociale și economice» (director P ≥ 30 ∧ ΣA ≥ 15,
  mentor P ≥ 100 ∧ ΣA ≥ 50, two verdicts) and «Eligibilitate TE 2026 — științe sociale și economice» (P ≥ 50 ∧
  ΣA ≥ 25); pinned by `Uefiscdi2026SocialEligibilityDefinitionTest` (real activity scoring) and
  `UefiscdiPublisherSupportTest`. **Prod (Adrian), after the image with H136 is deployed:** set
  — superseded: the data ships with `h137_uefiscdi_2026_roles_phd.js` (`H137`), one script for both —
  then select the two reports for FEAA / FSP / FSAS / FPSE. **Not built:** canonical bk/ch publications are not
  auto-counted (declared only); the "subject strictly social/economic" and accession-number conditions are stated,
  not checked. Slice 2 (humanities) as scoped below. — Original scope, 2026-10-01:
  Both 2026 packages (PD: PN-IV-RU-SC-PD-2026-1, TE: PN-IV-RU-SC-TE-2026-2, Anexa 2) have three families keyed to the
  13 competition domains of Anexa 1; `H135` built only «Științele naturii, exacte și inginerești» (domains 1–10).
  Not covered: **Științe sociale și economice** (domains 11–12) and **Științe umaniste** (13).
  **The rules.** Social/economic = points, "ca autor sau coautor" (no principal-author test): per article
  A_i = 70 × AIS / N (WoS SCIE/SSCI/AHCI, Q1/Q2 by AIS of the publication year, 2025–26 → JCR-2024, strictly
  article/review with an accession number, subject strictly social/economic); per book C_i = 60 / N and per chapter
  K_i = 30 / N, only at the 253 publishers of Anexa 7c (same list in both packages), with ISBN; N = authors.
  Thresholds: PD director P ≥ 30 and ΣA ≥ 15 (after PhD admission); PD mentor P ≥ 100 and ΣA ≥ 50 (2015–2026);
  TE director P ≥ 50 and ΣA ≥ 25 (2015–2026, after the PhD). Humanities = a CNCS-style table (books 100/50,
  edited volumes 50/25, articles 5.1 WoS-or-Scopus Q1/Q2 by AIS/SJR 40, 5.2 WoS incl. ESCI / Scopus / CNCS A-B 20,
  chapters 20/10; abroad vs Romania CNCS A-B; quality bar 30 or 10 library-catalogue entries, or open access at the
  publisher; CNCS domain identity; arts and 2024–26 volumes at 15/5): PD director ≥ 30 points with ≥ 20 from
  categories 1/3/5.1/6.1; PD mentor ≥ 100 with ≥ 40; TE director ≥ 40 with ≥ 20.
  **Scope — slice 1, social/economic (PD + TE, one report pair, serves FEAA/FSP/FSAS/FPSE):** (a) a `PD_WOS`
  indicator with formula `70 * AIS / N` on Q1/Q2 article/review 2015–2026 — needs `AIS` as a formula variable
  (the strategy already resolves the AIS placement; expose the value next to `Q`) and `N` (exists); (b) books and
  chapters as declared activities «Carte / Capitol (UEFISCDI, Anexa 7c)» with fields Editura, ISBN, N_autori,
  scored 60/N and 30/N when the publisher is on the 7c list (data file `data/uefiscdi/anexa7c_edituri.csv`,
  whole-name match with the normalisation of `PredatoryVenueService`; unmatched → 0 with a reason); (c) reports
  «Eligibilitate PD 2026 — științe sociale și economice» (director P ≥ 30 ∧ ΣA ≥ 15; mentor P ≥ 100 ∧ ΣA ≥ 50 as two
  verdicts) and «Eligibilitate TE 2026 — științe sociale și economice» (P ≥ 50 ∧ ΣA ≥ 25), authority UEFISCDI,
  one bar for every position (the page hides the selector, `H135`); domain = the social/economic SSCI categories
  (a new domain «UEFISCDI științe sociale și economice», from the SSCI category list); the "subject strictly
  social/economic" and accession-number conditions stated as approximations. Pinned by a definition test like
  `Uefiscdi2026EligibilityDefinitionTest`; loaded by one prod script in the `h135` style. Estimate: 1 session.
  **Slice 2, humanities (FLIT / arts, declared checklist):** the platform cannot know catalogue entries, open-access
  status or CNCS publisher categories; build it as declared activities per table category (1–6.2) with the points of
  the table, pre-filling only 5.1/5.2 from WoS/Scopus (AIS quartile from `PD_WOS`, SJR quartile from the Scopus
  ladder) and the CNCS journal list already loaded for FSP; two sums per report (total, international categories
  1/3/5.1/6.1) → PD director 30/20, PD mentor 100/40, TE director 40/20. Needs the CNCS publisher list
  (cncs-nrc.ro, categorii.Edituri 2020) loaded as data. Do only on request from a humanities faculty.
  **Out of scope:** PhD-date limits (8 / 12 years) and "after the PhD" windows — stated in descriptions as in `H135`.

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
  grows into. **Deploy step DONE 2026-10-01 (Adrian):** `scripts/ops/set-report-authority.sh` marked Eligibilitate PD,
  Eligibilitate PD 2026 and Eligibilitate Tinere Echipe as UEFISCDI in prod (previous values under
  `app_migrations/set-report-authority-v1`); the eleven standards stay CNATDCU. **Also 2026-10-01:** the CNFIS
  sheet's score from a chosen report is the position-effective total at the staff-list position (`a628da6e`);
  both CNFIS pages restyled with the app's components (`2968e018`). **Left as it was:** the delegated view of a supervisor lists all the reports of a researcher
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
  `scripts/ops/add-cnfis-activity-fields.sh`); written to the form after the publications; (4) `User.staffRecord`
  (employment type of the CNFIS staff sheet + from/to), filled by the head on the department roster page;
  "authors from the university" = co-authors with an account who are staff at the reference date; a
  co-author without a record is counted and NAMED on the page; (5) frozen copies (`cnfisSheetSnapshots`):
  the sheet as handed in, rows + left-out + patents + head + score, downloadable from what it holds; the
  person releases a copy unless `lockedByUnitSheetId` is set (slice 3 sets it when Anexa 6 is built).
  Decisions: the head fills the staff fields, the person sees them; the left-out sheet stays in the file; the
  person releases until Anexa 6 is built, then only the head. **At deploy:** run `add-cnfis-activity-fields.sh`.
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
  Anexa 6 lists a paper two members share ONCE (confirmed by Adrian 2026-09-30: duplicates are removed).
  **Templates:** all six of the 2025 edition (5.1, 5.2, 5.3, 6.1, 6.2, 6.3) downloaded 2026-09-30 from
  cnfis.ro/raportare-2025-… into `data/templates/` (NOT in git: `data/` is ignored; production reads them
  from the data volume at /app/data — copy them there; the tests use small copies under
  `src/test/resources/fixtures/templates/`). The template's own festival list (`Lista_festivaluri`) is EMPTY
  in the published file ("definitivată cu consultarea unui grup de experți"), so the platform's registry of
  artistic events (303 events, five arts domains, ranks INTERNATIONAL_TOP / INTERNATIONAL / NATIONAL) is the
  source of the level.
  **SLICE 5 (Anexa 5.1) BUILT 2026-09-30, not pushed.** A person's sheet follows their CNATDCU domain
  (`CnfisDomains`: arts 70–75; sport 76–77; humanities 63–69, 721, 751 — the guide's list). Anexa 5.1 comes
  from the declared activity "Participare eveniment artistic": the event's rank in the registry is the level
  of the form, the declared kind is the column group — the type gains the fields "Tip" (Proiect individual /
  de grup (2-4) / colectiv (5+) / Nominalizare individuală / Premiu individual) and
  "N_participanti_universitate" (`scripts/ops/add-cnfis-activity-fields.sh` now does both types). A
  performance without a kind, or at an event the registry does not rank, is left out and says so. Shown on
  the CNFIS page when the domain is artistic or performances exist; frozen with the sheet (`artsRows`);
  downloads: `/user/cnfis/{edition}/export-arts`, per frozen copy, and Anexa 6.1 per unit table (each
  performance once). Anexa 5.2 (sport) is fully declared and has no activity type yet — left for when the
  sport domain asks. **SLICE 4 (Anexa 5.3) BUILT 2026-09-30, not
  pushed.** Adrian cannot export CiteScore lists himself (Scopus sends him to "contact us"; the 2023 file came
  that way): the 2023 list serves every year until the others arrive. `CiteScoreQuartiles` reads every
  `CiteScore <year> per <month>.csv` in `cnfis.citescore.dir` (default `data/scopus`; Latin-1, the export is not
  UTF-8), best (lowest) quartile over a source's subject areas; a list year that is not loaded takes the
  nearest one and the row says so. Rows: an Article/Review in a journal with a Scopus id → CiteScore quartile
  of the edition's list year; a book (bk) / a chapter (ch) from the publication record, publisher and ISBN
  from the book fact or the venue; declared "Carte coordonată …" → edited volume, "Traducere …" →
  translation, "Carte sau capitol declarat …" → book/chapter by its "Tip". Critical editions have no source.
  The KVK link column stays empty (the person's). Shown when the domain is one of the guide's humanities
  codes; frozen with the sheet (`humanitiesRows`); downloads 5.3 live and per copy, 6.3 per unit table (each
  work once — the template says "Fără dubluri"). **To do when the 2021/2022 (and 2024, 2025) CiteScore
  lists arrive:** drop them into `data/scopus/` on the data volume, named as the 2023 one; nothing else. Source of the rules: the
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
  keep them on the server or on an encrypted volume, not on a laptop. Seen 2026-09-30 on the production data
  volume (`/app/data/scopus/`): the same full dump (483 MB, March 2026) and the incremental one — the inputs
  of the one-time bulk import (`scopus.data.file`), not read at runtime; deleting them there is Adrian's call.
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

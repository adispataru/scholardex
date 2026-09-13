# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

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

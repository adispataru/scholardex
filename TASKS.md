# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H105` External (non-UVT) accounts for candidates to any UVT position (abilitare, concurs) —
  the usual rich interface, minus the licensed RAW layer; full view for UVT staff. **RAISED 2026-09-11**
  (dean's request: Vlad Drăgoi, Arad, wants abilitare at UVT; widened 2026-09-11 to any candidate). Decision recorded: NO paid/subscription product (dropped 2026-09-11 — licensing
  makes it impractical); UVT staff evaluating an external candidate's file is legitimate institutional
  use of the Scopus/JCR licences, the candidate himself is not a licensed user.
  Identity: aai.rdi (we own it) — external candidates become REALM-LOCAL Keycloak users (username =
  email, email verified ON, temp password); the Google IdP, browser flow and client stay untouched. The
  app already auto-provisions RESEARCHER on first verified-email login, so a local user works TODAY as
  a plain researcher. Slices:
  - S1 — token marker: Keycloak group `external` + client protocol mapper emitting a `groups` (or
    `account_kind`) claim; `KeycloakOAuth2LoginSuccessHandler` stamps `User.accountKind`
    INSTITUTIONAL|EXTERNAL at provisioning (absent claim → INSTITUTIONAL, so UVT tokens are unchanged).
  - S2 — gating for EXTERNAL: workspace + own individual report only; global landing, org-unit and
    supervisor surfaces hidden (mostly falls out of having no department affiliation / group, but pin
    it in the security config + contract tests).
  - S3 — viewer-role filter on the DETAIL rendering, not on the scores: the run is identical, the
    response assembler drops licensed evidence fields for EXTERNAL viewers — metric values/ranks (AIS,
    JIF, JCR quartile tables), citing-document lists from Scopus/WoS edges, forum explorers and any
    browse/search beyond the user's own pubs. Kept: per-item category + points, criterion totals,
    threshold status, open-source evidence (CORE rank, DBLP match, SENSE tier, OpenAlex citing works).
    Rationale: categories/points are what UEFISCDI publishes and what the candidate must put on the
    fișă anyway; a point value cannot be reversed into an AIS/JIF or a citing document. Pre-condition:
    one-line confirmation from the UVT library (Elsevier + Clarivate contract holder) before the first
    external account is created — the institutional-use argument (UVT staff evaluating a UVT candidate)
    carries the weight, the raw-layer filter is the belt-and-braces.
  - S4 (optional) — admin "provisional report for these author ids" action so an external candidate
    can be pre-scored from declared Scopus/ORCID ids without the department-roster hack (H77 path is
    roster-only today).
  Immediate (no code): ask aai.rdi for the local user; create the profile via
  `POST /api/admin/researcher-profiles` with the same email + his Scopus ids/ORCID (OpenAlex
  A5005677757, ORCID 0000-0002-8673-9097); enqueue Scopus pubs + citations tasks; perspectiva d is
  his own data entry.

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

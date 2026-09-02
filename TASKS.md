# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H101` Fee-journal (APC) status must be time-aware (Florin, 2026-09-02 follow-up).
  `isFeeJournal(forumId)` is a per-forum boolean, but a journal's business model changes: IJCCC
  (Univ. Agora) was free open-access in 2013–2014 (SCPE-style) and moved to APC later — his two
  IJCCC papers from that era are declassified by TODAY'S status. Model: an `apcSince` year on the
  forum's fee fact; the formula gate compares the PUBLICATION year (same year-true pattern as WoS
  coverage). Data is the hard part — OpenAlex APC data is current-only; historical status needs
  web.archive.org traces, so realistically researcher-supplied claims with evidence (a small
  cousin of the H93 venue claims). PARKED until his announced follow-up message on the broader
  APC-declassification interpretation dispute (Springer/Elsevier Q1/Q2 APC journals growing yearly).

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

- [x] `H88` Production readiness / launch checklist (operational, not feature work). **CLOSED 2026-07-28 —
  the last open bullet (exposed-key deactivation) got Elsevier's written confirmation; every other bullet
  was already done and verified.**
  **RAISED + AUDITED 2026-07-25.** These existed only in session memory, in neither task file. Audited
  against production the same day; two were already done and my initial reading of a third was WRONG.
  - **Keycloak decision — DONE 2026-07-18 (`c8350488`).** OIDC-only through the Keycloak realm; `formLogin`
    and `DaoAuthenticationProvider` DELETED, not hidden. Prod confirms the design: `rdi-breakglass` exists
    with PLATFORM_ADMIN and no password.
  - **On-prem Scopus smoke test — DONE.** Superseded by real traffic: `scholardex-scopus-python` served
    `POST /v1/author-works` and `/v1/citations/by-eid` against live AU-ID queries, all 200, on 2026-07-25.
  - **Mongodump / dev admin password — DONE 2026-07-18, and my first read of it was wrong.** I reported
    "57 of 58 users still carry bcrypt hashes" as unresolved residue. They are RANDOM UUID hashes:
    `LocalPasswordScrambleRunner` (marker `local-password-scramble-v1`, applied 2026-07-18T18:55Z,
    `scrambledUsers: 56`) already neutralized every stored password for exactly this reason. Nothing
    guessable survives even if a password path were reintroduced.
  - **EXPOSED KEY — the old Elsevier key is in PUBLIC git history. Revoke it.** Found 2026-07-25 while
    confirming which key `.env` holds. `scopus.api.key=186f196685e39c011a1c1a0123630231` was committed in
    `application.properties` on 2025-12-12 (`74b0fa97`, "first push") and removed 2026-07-13 (`48f31efd`,
    when the key moved to the container env). `github.com/adispataru/scholardex` is **PUBLIC**, and the value
    is still reachable from history on `origin/main` plus five other remote branches — roughly seven months
    of public exposure, and retrievable right now. It is NOT the current key (different SHA-256), so the
    rotation did happen; what is missing is REVOCATION at dev.elsevier.com. Rewriting history is secondary
    and unreliable (clones, GitHub caches, archives) — revoking makes the string worthless, which is the
    actual fix. Also consider: any GHCR image built between those two dates baked the key into the jar.
    **2026-07-26: there is NO self-service revoke.** Confirmed by the user in the portal and against
    Elsevier's own docs — the developer portal documents key *settings*, not deletion, and publishes no
    support email. The only route is the Research Product APIs support hub contact form
    (`https://service.elsevier.com/app/contact/supporthub/researchproductsapis/`): open a ticket asking
    them to deactivate the key, stating it was published in a public repository and has already been
    rotated. Elsevier's API Service Agreement reserves their right to deactivate keys, so the ticket is
    asking for something they already do.
    **Partial containment, measured 2026-07-26:** prod carries exactly one Elsevier credential —
    `SCOPUS_API_KEY` in the `scholardex-scopus` secret — and **no InstToken anywhere** (no env var, secret
    key, or config in either deployment; the only mention in the repo is an optional, unset
    `PYBLIOMETRICS_INST_TOKEN` in a dev wrapper script). Entitlement is therefore IP-bound, so the exposed
    key used from an arbitrary address gets non-subscriber access, not UVT's full Scopus entitlement. That
    caps the damage; it does not remove it — the key still identifies UVT's account, consumes its quota,
    and works for anyone calling from a subscribing network. Watch the portal's usage stats for the old
    key: unexpected traffic is the signal it is being used.
    **DONE 2026-07-28 — Elsevier confirmed in writing.** Customer Service reply: "The API key has already
    been deactivated and will no longer be functional when used. However, it will still be visible on your
    'My API Key' page." The exposed string is now worthless; the key remaining visible in the portal is
    expected and harmless. No further action — history rewriting stays not-worth-it (the value is dead).
  - **Separate prod key — DONE 2026-07-25, verified.** Production now holds its own key
    (`sha256=fbf72819…`), distinct from the local `.env` (`0dbb2a29…`) and from the exposed one
    (`098dc67c…`) — three different values. Confirmed working: `ScopusSearch` 200 at 17:46 after both pods
    restarted at 17:34; no 401/403/429. A laptop compromise no longer reaches production.
  - **Remove `agent@dev.local` — DONE 2026-07-25, verified.** Gone from the prod user collection (57 users
    left); the reversible copy is at `app_migrations/remove-agent-dev-user-v1` (17:48:59Z). Remaining
    PLATFORM_ADMINs: `florin.spataru@e-uvt.ro` and `rdi-breakglass` (still passwordless, as designed).
  **History secret-scan (2026-07-25)**: swept every commit for credential-shaped literals in
  `*.properties`/`*.yml`/`*.yaml`. Exactly TWO values were ever committed — the Scopus key above, and
  `h14.wos.gov-ais.password=uefiscdi`, which is the password to UEFISCDI's publicly distributed WoS AIS
  archives (documented as such in `application.properties`), not a service credential. Everything else
  matching was a Helm reference to a k8s secret NAME, not a value. No Mongo/Postgres/Keycloak secret has
  ever been committed.
  **Decided AGAINST: removing `User.password` and the `PasswordEncoder` bean.** Scoped it and it is not the
  cheap cleanup I first called it — `PasswordEncoder` is threaded through SEVEN production services
  (`UserService`, `AdminUserService`, `GroupService`, `StaffImportService`, `ResearcherShellService`,
  `KeycloakOAuth2LoginSuccessHandler`, `LocalPasswordScrambleRunner`) and ~15 test classes, because every
  provisioning path defensively writes a random hash. With the scramble already applied and no login path,
  the security gain is nil and the blast radius is every user-creation path. Revisit only if the field
  causes a real problem.
  **Fixed in passing (`UserController`)**: `PUT /api/admin/users/{email}` wrote `request.password()`
  STRAIGHT THROUGH — unencoded, unlike `createUser` which hashes — so an admin-supplied password landed in
  Mongo as plaintext. Inert without a login path, but a real defect. It now ignores the field and carries
  the existing scrambled hash; pinned by a regression assertion on the captured save.

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

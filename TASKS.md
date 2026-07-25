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
  **SCOPED 2026-07-25** (`docs/tasks/active/h84-researcher-flagged-merges.md`). Both pairs re-verified in
  prod post-rebuild. S1 executor + re-apply pass + resurrection-guard alias (merge Florin's pairs via
  direct admin endpoint) → S2 admin queue UI → S3 researcher suggest/flag flow → S4 optional corpus-wide
  sweep. Durable side-table `scholardex.publication_merge_decisions` anchored on source-record refs;
  executor shared by live-approve and the rebuild re-apply (chained after rebuildFromEvidence).
  **S1 DONE locally 2026-07-25**: `PublicationMergeService` + static `PublicationMergeAliasRegistry`
  (canon-path resurrection guard, zero constructor churn) + `/admin/publications/merge` +
  `mergeRequests/{id}/approve|reject` endpoints; real-Mongo integration test covers live merge with
  edge dedupe, rebuild re-apply, and OpenAlex-replay resurrection guard. Prod rollout after deploy:
  two direct-merge curls (mOSAIC pair + SCPE pair), then Florin's refresh shows one entry each.
  **S2 DONE locally 2026-07-25**: `/admin/publication-merges` queue page (facade-assembled side-by-side
  compare from live facts, approve/reject/direct-merge forms, applied-vs-awaiting-re-apply state,
  sidebar "Merges" entry); verified live on agent-dev with a synthetic duplicate. With the UI in place,
  the prod merges can be done from the page instead of curls.
  **S3 DONE locally 2026-07-25**: researcher flow — `PublicationMergeWorkspaceFacade` (same-title ±1yr
  suggestions among OWN pubs, ownership-enforced flagging, richness-picked survivor), workspace
  endpoints merge-state/merge-requests, publications-tab banner + "merge requested" badges +
  detail-panel "Duplicate?" picker; requests land PENDING in the S2 admin queue. Bundle rebuilt.
  **LIVE IN PROD 2026-07-25.** Nine merges approved and applied; all seven of the current wave rebuilt off a
  single "rebuild now" on the last approval (a merge writes a BATCHLESS dirty marker, and
  `rebuildDirtyProjections` sweeps every outstanding marker into one full rebuild — so per-merge rebuilds are
  never needed). Verified: 13/13 markers REBUILT, 0 retired duplicates left in `publication_facts`, 0 dangling
  refs across authorship/source-links/citation edges, and Mongo 154,016 = Postgres `scholardex_publication_view`
  154,016. Report runs for the affected researchers still predate the epoch bump and need a refresh to show the
  corrected (lower — dedup merges citation lists) totals.
  **Admin request endpoint DONE 2026-07-25 (`2a3172af`)**: `POST /admin/publications/mergeRequests` queues a pair
  PENDING without applying it. Until it existed the only admin-side write was the unreviewed direct merge — the
  path the 2026-07-25 mis-merge took — and only a researcher could feed the queue. Idempotent, so a re-run can
  neither resurrect a rejected pair nor clobber a live one.
  **S4 RE-SCOPED 2026-07-25 after measuring it against prod.** The corpus-wide sweep as designed yields 1,398
  actionable candidates (from 10,434 raw same-title pairs: −1,868 generic ≤3-word titles, −2,602 pairs whose two
  sides carry DIFFERENT DOIs, i.e. genuinely distinct records) — but only **10 touch a UVT author at all** and
  only **9 double-counted in a live score**. A batch job producing 1,398 rows would need ranking, paging and
  bulk-reject purely to manage noise it creates itself. Re-scoped to a **UVT-authored sweep**: same grouping rule
  restricted to publications carrying a UVT author id, scheduled, writing PENDING rows through the new endpoint.
  ~10 rows today, grows with onboarding (57 of ~370 staff have profiles), structurally cannot flood the queue.
  Left undone by decision: preprint-vs-published pairs (3 for madalina.erascu) — folding an arXiv preprint into
  its published version changes a score and is a policy call, not a merge.

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

- [ ] `H88` Production readiness / launch checklist (operational, not feature work).
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

- [ ] `H89` Conference CORE-rank losses + non-finite metric root cause.
  **RENAMED + REFOCUSED 2026-07-25.** Opened for the RIS `Infinity` note; investigating it turned up a much
  larger, live scoring bug that now leads the entry.
  - **DBLP stream forums lose a conference's CORE rank — FIXED 2026-07-25 (`844d144f`).** DBLP files AAMAS
    proceedings under `conf/atal` (the ATAL workshops that BECAME AAMAS), so the minted stream forum is named
    "ATAL", CORE has no such entry, and a CORE-A conference fell through to the D default. Cost
    cosmin.bonchis 48 points across the Info_B indicators (`Info_B (A*, A)` 56 → 8 = 3 papers × 16) after the
    rebuild. **I first blamed the publication merges, which landed in the same refresh — wrong; the papers
    were correctly scored before and simply stopped being A-ranked.** Fix: when the assigned stream forum
    yields no CORE match, retry against `originalForumId` (preserved by H85) before the LNCS/ACM/D defaults,
    stamping `coreEvidenceVenue`. Not only AAMAS — IEEECLOUD, 3PGCIC, ISGTEUROPE, ICCCNT, LCTRTS, INCOS all
    show the same acronym-vs-CORE-name mismatch. **Needs a deploy + a report refresh to take effect.**
  - **Complementary "add the DBLP booktitle as an alias" — MEASURED AND DECLINED 2026-07-25.** My first
    suggestion was to name stream forums from the booktitle; that is WRONG and `DblpConferenceResolveService`
    says so explicitly — a volume title on a stream forum re-creates the "AINA Workshops" mint accident and
    even mis-triggers the scorer's workshop reduction. The user's correction (ADD an alias, never rewrite
    `name`/`nameNormalized`) was the right shape. Measured before building:
    1,082 stream forums (421 CORE-matchable) · 3,092 publications on them · 1,318 on unmatchable forums ·
    **1,086 carry `originalForumId` and are already covered by the fix above** · **232 do not — the
    alias-only population — of which exactly 1 is CONFIRMED by a UVT researcher.**
    That one is florin.fortis's 2025 paper on forum "BDC" (`conf/bdc`), and CORE has NO BDC entry — the
    nearest is BDCAT, a different conference, so an alias would either find nothing or attach a wrong A.
    Cost would be a new field across fact + projection + view + widening `resolveConferenceMatch` (the
    three-layer dual-path trap) plus a stricter match confidence. **Not worth it.** Revisit only if the
    confirmed-population count grows materially as more researchers onboard.
  - **RIS/`Infinity` — original symptom, still unexplained.** No non-finite values in prod today (0 across
    AIS 341k / RIS 95k / IF 340k rows). `ScientificProductionService:398` clamps a non-finite score to 0.0,
    so nothing renders ∞ — but a real metric silently reads 0. **549 publications have `author_count = 0`**,
    the divisor the guard's comment names; note the Info_B/C formulas use `max(N-2,1)`, which floors at 1 and
    CANNOT go infinite, so the offending bare-`N` formula is still unidentified.
  - **WoS ±999 sentinel ingested as a real metric — FIXED 2026-07-25.** `AbstractWosImportEventParser`
    rejected only the NEGATIVE sentinel (`parsed <= -999.0`), so `journalImpactFactor=999.999` rode in as a
    genuine Impact Factor. Root cause traced to the extracts themselves: `journals-SCIE-year-1998.json` (50
    rows) and `journals-SSCI-year-1998.json` (6) — an identical three-decimal value shared across a whole
    year, in both editions, is not data. Guard is now symmetric (`>= 999.0` too); the bound is safe for every
    metric the parser handles (highest real IF ~685 for CA-A Cancer Journal, AIS peaks ~108, RIS ~126) and
    all WoS parsing goes through this one helper, verified by grep. Two tests: the sentinel is dropped, and
    a genuinely high IF still ingests. **Prod data repair pending** —
    `scripts/ops/clear-wos-metric-sentinels.sh` nulls the stored values (matching what a re-ingest now
    produces, so the two converge) with a restorable copy in `app_migrations`; a projection rebuild and a
    refresh of any run scoring a 1998 journal are needed afterwards.
  - **Rebuild safety — user data is NOT deleted, but it CAN be orphaned (checked 2026-07-25).** Every
    deletion in `ScopusBigBangMigrationService`'s reset is scoped: the five unscoped `deleteAll()` calls are
    on `Scopus*FactRepository` (the source layer, re-ingested), canonical facts go only where
    `source ^SCOPUS` (`findCanonicalIdsBySource`), source-links and identity conflicts likewise. No user
    collection appears in the reset at all. The real exposure is quieter: canonical ids are deterministic
    hashes of identity inputs (titleNormalized + coverDate + creator), so a rebuild that shifts any of those
    re-mints the publication under a NEW id and leaves user rows keyed on the old one dangling — nothing is
    deleted, a confirmed claim just silently stops counting. Standing rate in prod: **1 of 423** authorship
    refs (~0.2%), 1 of 3,094 DBLP evidence refs, 0 actionable merge survivors (the 1 that dangles is a
    REJECTED incident decision, benign — `reapplyApproved` only reads APPROVED). The one orphaned claim
    (florin.spataru, "Decentralized and Fault Tolerant Cloud Service Orchestration") had already self-healed:
    the paper exists as `spub_71adaeb352fb9ecdbf97fd39` and carries its own CONFIRMED decision.
    `scripts/ops/check-user-data-integrity.sh` snapshots all of this and diffs before/after, exiting non-zero
    when any `*_dangling` count RISES. Read-only. Negative-control tested (exit 1 on a synthetic regression,
    0 on a clean run). **An auto-reconciler for orphaned claims was considered and NOT built** — 0.2% and
    self-healing does not justify it, and re-pointing a user's CONFIRMED claim by fuzzy identity match is the
    same shape of automated decision that caused the mis-merge. If the rate ever jumps (an identity-input
    change like the coverDate precedence class would do it), build it as a REVIEW QUEUE, not a silent fix.
  - **(superseded note) 56 forums carried `IF = 999.999`, all year 1998** — a source sentinel ingested
    as a real Impact Factor (Journal Of Sociology, Journal Of Porous Media, International Review Of
    Hydrobiology, …). Nothing clamps it because 999.999 is perfectly finite; any 1998 publication in those
    venues scores against it. Arguably worse than the bug this entry was opened for.

- [ ] `H50` Individual report export / read-only score-verification import.
  **STATUS (2026-06-30): mostly done — H62/H65 overtook most of the "remaining" list. The genuine gap is docx *import*
  verification (H50.6). Entry below refreshed.**
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Done since (via H62/H65): **docx export (H50.4)** is wired — `ReportExportFacade` renders any format the support declares, with the DOCX content-type + `TemplateDocxRenderer`. **Report-type coverage (H50.5) largely done** — bindings now exist for `informatica-2016`, `matematica-2016`, **`feaa-2024`**, **`fizica-ff`** (4 of the report types), each with a `ReportTypeImportSupport` that renders docx.
  Remaining: **docx *import*/verify (H50.6)** — the docx supports' `parse()` still throw `UnsupportedOperationException` (Fizica/Feaa), so score-verification upload is xlsx-only; implement docx parsing for the docx report types. Plus any report definitions still lacking a binding (the remainder of H50.5).
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

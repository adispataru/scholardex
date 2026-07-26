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
    **REQUESTED 2026-07-26** — deactivation asked of Elsevier. Open until they confirm; the key must be
    assumed live until then. Close this bullet only on their written confirmation, not on the request.
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
    **CORRECTION 2026-07-26 (see H90).** The premise above was wrong on the data. BDCAT is not "a different
    conference" — `conf/bdc` IS BDCAT's DBLP stream (the conference was Big Data Computing before the
    rename), and the paper's own evidence row already carries `conferenceName = "BDCAT"`, which CORE ranks C.
    No alias field was ever needed: the identity was in hand and being thrown away. Fixed in H90.
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

- [ ] `H90` Reviewer-reported conference mis-identification (florin.fortis, 2026-07-26).
  Fourteen flagged publications, checked one by one against prod. They split into three groups, and only
  one of them was a code defect.
  - **Already fixed by H89, report simply predates the deploy — CISIS ×5.** CORE holds TWO conferences under
    the acronym CISIS ("Complex, Intelligent and Software Intensive Systems", C in every edition, and
    "Computational Intelligence in Security for Information Systems", B→National), so the DBLP stream forum
    named "CISIS" is ambiguous by construction and resolves to neither — correctly. The pre-restamp forum
    ("Proceedings - 2014 8th International Conference on Complex, Intelligent and Software Intensive
    Systems, CISIS 2014") names which one it is, and the H89 `originalForumId` fallback reads it. Pinned
    with a verbatim-prod test; it passed on first run. **These need only a report refresh, no code.**
    63 CORE acronyms are held by more than one conference, so this shape is not rare.
  - **Real defect — DBLP's stream key can hide the conference's own acronym. FIXED (`H90`).**
    `resolveDblpConferenceTitle` composes `"<stream acronym> <conferenceName>"` to seed the acronym CORE
    keys on. That is right when `conferenceName` is a full title; when `conferenceName` is ITSELF an
    acronym that differs from the stream key it fabricates a string that matches nothing: BDCAT is filed
    under `conf/bdc`, so the composed name is `"BDC BDCAT"`, which neither contains nor token-overlaps
    CORE's "IEEE/ACM International Conference on Big Data Computing, Applications and Technologies" — the
    exact BDCAT acronym match scores NONE and a CORE-C conference falls to D. Same failure class as the
    `eb1b882d` CORE-qualifier bug: an exact acronym match sunk by name dissimilarity. Fix retries the
    evidence's own `conferenceName` alone after the composed attempt misses; it can only resolve via
    EXACT_ACRONYM_ONLY, which demands the whole name equal a CORE acronym AND a single CORE entry to hold
    it — so an ambiguous acronym (CISIS) still resolves to nothing, as it must.
    **Measured in prod: 337 evidence rows carry the mismatch; 58 already resolve on the stream acronym,
    41 are ambiguous and stay unresolved, 115 are recovered** (`IEEEANTS`→ANTS, `ERCIMDL`→TPDL,
    `EUROMICRO`→SEAA, `ICMCS2`→ICMCS, …). Side effect: AAMAS now resolves straight from its evidence and
    no longer depends on the pre-restamp forum surviving — its prod-shape test asserts that with a
    `never()` on the fallback lookup.
  - **Side effect of the retry, investigated 2026-07-26 after "A*+A went down" — CORRECT, not a break.**
    florin.fortis's `Info_B (A*, A)` fell **26.67 → 16** — and the 26.67 was itself only two days old.
    The per-run `indicatorScoresByIndicatorId` history (which, unlike the shared `userIndicatorResults`
    documents, is NOT overwritten in place and is therefore the only durable score timeline) reads:
    16 from 2026-07-23 through 07-24 14:03 → **+10.67 on 07-24 16:28** → flat at 26.67 → **−10.67 on
    07-26 14:26**. The rise and the fall are the same magnitude and the same two papers, so today's change
    returned the indicator to its long-standing baseline rather than cutting into it. The 07-24 jump is
    consistent with the DBLP sweep re-stamping both papers off the "Lecture Notes In Artificial
    Intelligence" volume forum (still preserved as their `originalForumId`, where they scored the LNCS C
    floor) onto the shared EUROPAR stream forum, which resolved them to full CORE-A.
    Arithmetic confirms the attribution exactly: Info_B divides by `max(N-2,1)`, and
    "Cloud Patterns" (N=5, divisor 3) contributed 8/3 = 2.667 while "Data Security Perspectives" (N=3,
    divisor 1) contributed 8/1 = 8.000 — **2.667 + 8.000 = 10.6667**, matching both the jump and the drop
    to four decimals. No third paper is involved. Cause: two Euro-Par **Workshops** papers ("Cloud Patterns
    for mOSAIC-Enabled Scientific Applications", "Data Security Perspectives in the Framework of Cloud
    Governance") had been scoring the full main-track CORE-A rank, 8 points each. The DBLP stream forum is
    named "EUROPAR" for EVERY Euro-Par paper, main track and workshops alike, so it cannot tell them apart;
    their own DBLP booktitles say `"Euro-Par Workshops (1)"` and `"Euro-Par Workshops"`. Before H90 the
    composed title `"EUROPAR Euro-Par Workshops (1)"` matched nothing and resolution fell through to the
    stream forum name → A/8. The retry now matches the bare booktitle via EXACT_ACRONYM_DECORATED (the
    split-decorated rule merges `euro`+`par` and treats `Workshops`/`(1)` as ignorable suffixes), and
    because THAT source title contains "Workshops" the existing `isWorkshopVariant` reduction fires → 4
    points, category C under the 2026 mapping. That is the standard's answer for a workshop of a CORE-A
    conference, and it is the documented policy ("absent per-paper proof of workshop status, the paper's own
    record is the default truth"). Pinned by two tests: the workshop shape scores 4/C, and the same stream
    forum with NO evidence still scores 8/A — the control that makes the first meaningful.
    **Prod scale: 55 evidence rows are newly workshop-detected this way** (`DEXA Workshops`,
    `ICPP Workshops`, `Business Process Management Workshops`, `EuroS&P Workshops`, `ASE Workshops`, …).
    Expect other CS researchers to lose points on workshop papers that were over-credited as main track.
  - **Conference/book-chapter DOUBLE COUNT — found on review request 2026-07-26, FIXED.** The reviewer
    asked whether any of his papers were counted twice, "especially conference vs book chapter". Five were.
    A ch/bk paper on a "Lecture Notes **on** …" Book-Series forum passed BOTH scorers: the conference
    scorer credited it in `Info_B_Conferințe 2026` (Perspectiva B) and the book scorer credited it again in
    `Info_D_i_2026` (Perspectiva D **and** Total). Cause: the same predicate written twice and drifted —
    `ComputerScienceConferenceScoringService.isLectureNotesSeries` matched `"lecture notes in "` OR
    `"lecture notes on "`, while `ComputerScienceBookService.isLectureNotesSeries` matched only
    `"lecture notes in "`. The book scorer's own comment states the guard exists precisely to stop this
    ("Excluding them here stops the same paper being counted as both a book chapter (perspective d) and a
    conference (perspective b)"), and its second clause could not compensate because these forums are typed
    `Book Series`, not `Conference Proceeding`. Fix: one shared `LectureNotesSeriesSupport`, called by both.
    **Prod scale: 22 publications** — 21 on "Lecture Notes on Data Engineering and Communications
    Technologies", 1 on "Lecture Notes on Multidisciplinary Industrial Engineering". Touching onboarded
    researchers: **florin.fortis 5 papers (20 points of inflation in Perspectiva D and Total)** and
    **alexandra.fortis 3**. Pinned by a cross-scorer test over four series-name variants asserting the paper
    is claimed by exactly one scorer; negative-controlled (restoring the narrow copy fails it on the "on"
    name). **Verified corpus-wide after the fix: across all 933 ch/bk publications, conference-admitted and
    book-admitted partition cleanly — 0 claimed by both.** The surface is closed structurally, not patched.
  - **Broadening the Lecture-Notes family — ASSESSED 2026-07-26, decided AGAINST.** The instinct is that a
    wider net is safer; here it is the reverse, because of which way the points move. A gated paper goes to
    the CONFERENCE scorer and takes the LNCS/Springer C floor (2 points); an ungated `ch` goes to the BOOK
    scorer and takes Springer's SENSE category B halved for a chapter (4 points). **Adding a series moves
    its chapters 4 → 2.** Measured: broadening to the usual Springer/IFIP conference families (LNICST, CCIS,
    AISC, IFIP Advances, Smart Innovation, Studies in Computational Intelligence) would move **56**
    publications down, of which **0** belong to an onboarded researcher; LNICST alone holds 12 publications
    and 0 ch/bk, so adding it is a literal no-op. All cost, no benefit. The name is a poor discriminator in
    principle too — "Lecture Notes in Physics", "Lecture Notes in Educational Technology" and "Studies in
    Computational Intelligence" each carry BOTH proceedings and monographs, so any series-level rule is
    wrong for one of them. The real discriminator is per-VOLUME (DBLP evidence naming the conference), the
    same lever the ECML-PKDD workshop volumes need. Revisit only when a researcher onboards with a chapter
    on one of these series. Sanity check on the other side: the 3 book-scored chapters that DO belong to
    onboarded researchers sit on genuine book series (Palgrave Studies in Digital Business, SpringerBriefs,
    Studies in Big Data) and are correctly scored as books.
  - **Not a defect — no evidence to reason from. MedFusion-LM (ECML-PKDD workshop volume), the two AINA
    volumes, AD-ZeroNAS.** All are 2025/2026 Springer chapters sitting on `Book Series` forums
    ("Communications in Computer and Information Science", "Lecture Notes on Data Engineering and
    Communications Technologies") with **no DBLP evidence row at all**. Nothing in our data says which
    conference the volume belongs to; the reviewer knew from outside knowledge. **DBLP evidence covers only
    6–20% of conference publications** (2025: 10 of 61; 2026: 1 of 16) — the local dump sweep is the
    bottleneck, not the matcher. **Action is an ops one: refresh the DBLP dump and re-run
    `/general/dblpLnChapterEnrichment` → `sweep()`, then a derive-only rebuild.** Two of these four are
    cosmetic anyway — the reviewer notes AD-ZeroNAS "can stay Springer, no point difference" (LNCS floor C
    == CISIS C), as with ISPDC and "Exploring Streaming…", where only the displayed venue name is ugly.
  - **The "refresh the dump to populate booktitle" idea — CHECKED 2026-07-26 AND WRONG ON BOTH HALVES.**
    (a) `booktitle` is null on all 3,094 evidence rows not because the dump lacks it but because
    `DblpConferenceResolveService.writeEvidence` never sets the field — the sweep DOES parse `<booktitle>`
    and passes it into the `conferenceName` slot. So **`conferenceName` already IS the dump's booktitle**
    and a refresh cannot change what is stored there. (The unused `booktitle` field makes
    `isDblpWorkshopVolume`'s `getBooktitle()` fallback dead code; harmless, worth deleting or filling.)
    (b) The dump is `2026.03.01` — four months old, not stale. Verified directly against DBLP:
    `conf/euromlsys/BabucF25` has `<booktitle>EuroMLSys</booktitle>`, **not** `EuroMLSys@EuroSys`, and the
    crossref'd proceedings record (`conf/euromlsys/2025`) names only "5th Workshop on Machine Learning and
    Systems" — DBLP records no EuroSys link anywhere. 58 evidence rows DO carry the `@` marker
    (`SecSE@ESORICS`, `MTD@CCS`, `SEAMS@ICSE`, `WORKS@SC`, …), so the mechanism works; EuroMLSys just is
    not filed that way. A workshop→parent map remains the only route to the 4-point workshop-of-EuroSys
    ladder, and it is curated reference data, not a matcher change.
  - **EuroMLSys reaches C anyway — FIXED via the ACM DOI prefix.** The 2026 amendment already floors
    CORE-unranked ACM venues to C (H85), which is the category the reviewer asked for; only detection was
    failing, because `isAcmOrEptcsVenue` reads the venue NAME and neither "EUROMLSYS" nor "Euromlsys 2025
    Proceedings of the 2025 5th Workshop on Machine Learning and Systems" contains "ACM" — though the DBLP
    proceedings record lists `<publisher>ACM</publisher>`. Added `DoiVenueSupport.isAcmPublished`
    (`10.1145`), consulted only inside the already-conference-gated floor: ACM registers journals and
    proceedings under the same prefix, so it says nothing about venue TYPE, but the type is settled by the
    time the floor runs. Stamps `acmEvidenceDoiPrefix` so the drilldown does not show an unexplained C next
    to a forum called EUROMLSYS. Still 2026-only — a negative-control test pins FV Info 2016 at D.
    **Prod scale: 701 publications carry a 10.1145 DOI, 272 already floor on a name, 429 are newly reachable
    — but the floor only fires when CORE ranks nothing, and most of the 429 are CORE-ranked (OOPSLA, ICSE,
    GECCO, CIKM). Approximate upper bound on actual D→C movement: 325.** Larger than the rest of H90 put
    together; it is the same policy, only detected properly, but worth knowing before the refresh.
  - **MedFusion-LM would need TWO things, and a dump refresh only supplies one.** DBLP does have it
    (`conf/pkdd/BabucF25`, venue "PKDD/ECML Workshops", year 2025 — note DBLP says 2025, our OpenAlex
    record says 2026), so a refresh would attach evidence. But CORE's matching entry has the acronym
    **"ECML PKDD" — with a space** — while the bare `ECML` entry stops at 2014, so `getClosestYear(2025)`
    returns null and the acronym candidates (PKDD, ECML) reach neither. Would still need a curated alias.
    Related measurement worth keeping: **480 of 2,270 CORE entries were last ranked before 2017**, so any
    recent paper at those venues silently yields `NO_CLOSEST_YEAR`.
  - **The other two Springer volumes cannot be fixed at all right now** — "Cognitively Inspired
    Preprocessing…" and "AD-ZeroNAS…" return **0 hits** from DBLP's live search today, not just in our
    dump. There is no evidence to import.
  - **UX — "other venue type" relabelled to "counted elsewhere".** Journals appear at the foot of a
    conference indicator's list, already collapsed behind a toggle; the reviewer's point is that the label
    does not say where they went, so a reader cannot tell whether they were dropped. Toggle now reads
    "… a different venue type, counted under the indicator for that venue".
  - Not an issue: "Benchmarking Database Systems…" (IETE TR) is correctly a journal; the reviewer confirms
    its DAIT/BNCOD workshop origin does not change the class.

- [ ] `H50` Individual report export / read-only score-verification import.
  **STATUS (2026-06-30): mostly done — H62/H65 overtook most of the "remaining" list. The genuine gap is docx *import*
  verification (H50.6). Entry below refreshed.**
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Done since (via H62/H65): **docx export (H50.4)** is wired — `ReportExportFacade` renders any format the support declares, with the DOCX content-type + `TemplateDocxRenderer`. **Report-type coverage (H50.5) largely done** — bindings now exist for `informatica-2016`, `matematica-2016`, **`feaa-2024`**, **`fizica-ff`** (4 of the report types), each with a `ReportTypeImportSupport` that renders docx.
  Remaining: **docx *import*/verify (H50.6)** — the docx supports' `parse()` still throw `UnsupportedOperationException` (Fizica/Feaa), so score-verification upload is xlsx-only; implement docx parsing for the docx report types. Plus any report definitions still lacking a binding (the remainder of H50.5).
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

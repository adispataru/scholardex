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

- [ ] `H93` Researcher-supplied venue claim (durable across rebuilds).
  **DECIDED 2026-07-26.** Origin: florin.fortis's `EuroMLSys@EuroSYS` suggestion. Traced through, it does not
  reduce to a smarter matcher — no source states that link (not DBLP: own stream, no `@`; not Crossref:
  container never says EuroSys; not OpenAlex), so it reduces to a maintained mapping. The durable answer is
  to let the person who actually knows supply it.
  **User decisions:** (1) claims land PENDING and need **admin approval**, like H84 merges — a claim raises
  the claimant's own score (AINA B = 4 vs the LNCS C floor = 2) and, because publications are shared, every
  co-author's too; (2) the researcher names **a FORUM**, and the DBLP-style `X@Y` string is stored only
  when that forum resolves to a CORE conference; (3) stored as its own field/collection, never overwriting
  the canonical venue; (4) re-applied after a full rebuild.
  **(2) revised 2026-07-26 (user).** My first cut had the researcher pick a CORE entry. Pointing at a forum
  is better on three counts. A forum is what a publication actually attaches to (`forumId`), so the claim
  applies directly instead of being translated. It covers venues **absent from CORE** — the open question I
  had parked — because a venue we do not hold can be proposed through the forum-submission flow that
  already exists (`UserDefinedForumFact` + `wizardSubmitterEmail` + `HasReviewFields`, admin-moderated), so
  there is still no way to invent a venue unreviewed. And the `X@Y` string stops being the claim's identity
  and becomes what it actually is: the way the **workshop-of** relationship is expressed, which is the only
  reason the half-points ladder fires. Non-CORE venue → forum stamp only, no evidence row, no ladder.
  **Care needed in the S3 picker:** forums are not homogeneous — the catalogue holds per-year proceedings
  volumes, DBLP `conf/X` stream forums AND series forums ("Lecture Notes on Data Engineering…"). Claiming a
  SERIES forum is the very state we are trying to fix, so the picker must rank stream/proceedings forums
  first and keep Book-Series ones out of easy reach. Note also the deliberate asymmetry with activities,
  whose picker searches CORE and not forums (an activity names a ranked conference; a publication names the
  venue it appeared in) — do not "unify" the two.
  **Design.** Separate spared collection `scholardex.publication_venue_claims` holding the human decision
  and its audit trail. Anchored on the DOI (external, stable) with titleNormalized+year as fallback, NOT on
  the canonical id alone — H89 measured canonical ids being re-minted at ~0.2%, which would orphan a claim
  silently.
  Applying an approved claim has two levels. Always: stamp the claimed `forumId` onto the publication,
  preserving the displaced one as `originalForumId` — the same `stampConferenceForum` the DBLP sweep uses,
  so the H85 ACM/LNCS fallbacks keep working off the pre-claim venue. Additionally, when the claimed forum
  carries a `conf/X` DBLP id (i.e. it IS a CORE-resolvable conference stream): write a
  `ScholardexPublicationDblpEvidence` row (`matchMethod=researcher-claim`) with `series=conf/<acronym>` and
  `conferenceName=<workshop>@<PARENT>` when the workshop flag is set — **human-supplied evidence in the
  shape DBLP would have provided**, which is literally the notation the reviewer proposed. That reuses
  `applyMatch` and the scorer's existing `isDblpWorkshopVolume` `X@Y` path, so **the scorers need no change
  at all** and there is no constructor churn across their many test call sites (the failure mode today's
  `UiMessageBundleService` addition demonstrated: 32 tests down on one new constructor arg).
  The re-apply pass runs immediately before `dblpConferenceResolveService.rebuildFromEvidence()` in
  `ScopusCanonicalMaterializationService`, re-resolving each claim's publication by DOI and refreshing both
  the stamp and the evidence row — so a rebuild that re-mints the publication still lands the claim.
  Slices, mirroring H84: **S1** model + service + re-apply hook + tests · **S2** admin approval queue
  (extend the existing Merges page rather than adding a second thing to watch) · **S3** researcher flow on
  the workspace publications tab, reusing `CoreConferenceLookupFacade` and the picker already built for
  activities.
  **S1 DONE locally 2026-07-27.** `PublicationVenueClaim` (spared collection, unique per publication,
  DOI + titleNormalized+year anchors) + `PublicationVenueClaimService` (request/approve/direct/reject,
  reject-after-approve REVERTS from the once-captured `Displaced`) + `AdminVenueClaimController`
  (`/admin/publications/venueClaim[s]`, queue path applies nothing) + re-apply chained into
  `ScopusCanonicalMaterializationService` AFTER `rebuildFromEvidence()` and the merge re-apply.
  **Two design corrections found while building, both recorded here over the original text:** the re-apply
  runs AFTER the DBLP re-link, not before (human writes last, or the auto-stamp overwrites the decision
  moments later), and the dedicated pass is smaller than designed — a CORE-conference claim's evidence row
  rides `rebuildFromEvidence()` for free, so the pass only re-anchors re-minted ids (by DOI) and re-stamps
  forum-only claims. Claim-beats-DBLP includes CLEARING a DBLP series on a non-conference claim (displaced,
  revertable) — leaving it would let the next rebuild re-stamp the machine's forum over the human's.
  Tests: 3 real-Mongo integration (EuroMLSys workshop arc + revert; claim-beats-DBLP + re-mint re-anchor;
  reject-not-unstamping-newer-forum), scorer acceptance (claim-shaped `EuroMLSys@EUROSYS` row scores C/4
  via the untouched X@Y path), 4 endpoint contract tests, materialization mocks updated (the constructor-arg
  trap, caught proactively this time).
  **S2 DONE locally 2026-07-27.** The venue-claims half of the `/admin/publication-merges` page (one review
  surface, per the recorded decision): `PublicationVenueClaimAdminFacade` (queue rows + flash-message
  wrappers with the same rebuild-now semantics as merges), view-controller actions
  (`/venue-claim`, `/venue-claims/{id}/approve|reject`), template sections (direct form, pending queue with
  approve/reject, decided list), and a **Revoke button on approved rows** — added when live verification
  surfaced the gap; unlike a merge, revoking a claim is cheap and exact, so the affordance belongs on the
  page. Verified live on agent-dev against the REAL EuroMLSys paper: queued PENDING via the endpoint,
  approved through the page form (forum stamped, displaced captured the machine's `conf/euromlsys`
  dump-doi evidence — claim-beats-DBLP observed live), then revoked through the page (forum and evidence
  restored byte-for-byte). 8 view contract tests incl. the revoke pin.
  **S3 DONE locally 2026-07-27.** Researcher flow on the workspace publications tab:
  `PublicationVenueClaimWorkspaceFacade` (ownership-enforced requestClaim — the ONLY thing between an
  arbitrary publication and the admin queue, unit-pinned; per-pub claim state for the panel; forum search
  RANKED conference-streams → proceedings → journals → **Book Series LAST**, the design caution made
  executable and unit-pinned), workspace endpoints (`venue-claim-state`, `venue-claims`, `forums/search`),
  and a "Venue greșit?" detail-panel section in `workspacePublications.js` (debounced search picker,
  workshop checkbox revealing the label field, PENDING/APPROVED/REJECTED states) — 14 `workspace.pubs.
  venueClaim.*` keys RO/EN, bundle rebuilt, lints green. Verified live on agent-dev: ownership refusal
  (400) on a foreign publication via API, and the full positive arc through the real UI — typed "eurosys",
  the conf/eurosys stream ranked first, picked it, workshop flag + label, submitted; claim landed PENDING
  with every field correct and the section re-rendered without a reload. **H93 feature-complete pending
  deploy**; after deploy, Florin can file the MedFusion-LM and EuroMLSys claims himself, closing his
  review's last item.
  Open question from the first cut — a claim naming a venue **absent from CORE** — is CLOSED by the
  forum-target revision above: such a venue is claimable, it simply gets the forum stamp without an
  evidence row or a workshop ladder, and a venue we do not hold at all goes through the existing
  admin-moderated forum submission first.

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

- [ ] `H95` Perspectives — a report-level grouping concept with logical verdicts over criteria.
  **RAISED 2026-07-28 (user + Florin Fortis; reframed same day from a composite-criterion draft to a
  proper higher-level concept — criteria stay exactly as they are.)** The standards' own grammar defines
  named threshold conditions and combines them at SECTION level with "cumulativ" (AND) and "una din
  următoarele condiții" (OR); the FV Info excel's verdict column does exactly that
  (`E9 = total≥32 AND topAB≥16`; `E21 = AND(E-B, E-C, E-D, sum≥116)`). Our model has the atoms —
  criteria with per-position thresholds — but no section layer, which is why FV Info shows independent
  rows ("criteria met 3/6") and the FEAA 2016 gates degenerated into sum-tricks and row spam. Florin's
  reading: Perspectiva B remains its three criteria (total, A*+A+B, A*+A) — they just BELONG to a
  Perspectiva whose verdict is a proposition over them.
  Mechanism: new `AbstractReport.Perspective {name, composition}` list on the report — a declarative
  AND/OR tree whose leaves reference criteria by index (and, for the Total verdict, earlier perspectives
  by index: `{all/any: [{criterion: i} | {perspective: j<this} | nested]}`). Deliberately NOT a formula
  engine: no MVEL over raw values; leaf truth = the per-position met booleans already derived from
  `criteriaScores` vs thresholds, so verdicts inherit Stage-1/S2 position-effective scores for free.
  Evaluation rules (pinned): a leaf criterion with no threshold for the position is SKIPPED (vacuously
  true — matches the excel: conf's B verdict has no A*+A gate); a perspective with no applicable leaf at
  a position is itself not applicable; validation rejects out-of-range refs and forward/self perspective
  references (ordering forbids cycles by construction).
  Display: the criteria rail GROUPS member criteria under their perspective heading (criteria render
  unchanged — nothing is nested away or hidden); the heading carries the DA/NU verdict per selected
  position; unbundled criteria render ungrouped as today. The summary "met" count becomes TOP-LEVEL
  units: perspectives + criteria not bundled in any perspective (decided 2026-07-28). Render-time only —
  no persisted-shape changes; run compare/org-unit roll-ups untouched (perspectives carry no score).
  - [x] **S1 — mechanism — DONE 2026-07-28 (`cd01dcbd`).** `Perspective {name, composition}` +
    `computePerspectiveVerdicts`/`bundledCriterionIndices`, `#eval-perspectives-data` payload, grouped
    rail with DA/NU chips, top-level met count; verified live with a temporary Scor perspective on
    FEAA 2026 (reverted). Pinned in tests: all/any skip semantics, earlier-perspective refs,
    effective-score leaves, malformed-tree isolation.
  - [x] **S2 — FEAA 2026 eligibility gates — DONE 2026-07-28 (`489de762`)**: new 2026 count indicators
    (articles AIS>0.15, AIS-nonzero count, Core/Info count, distinct-journal counts ×2 via
    DistinctForums; `FEEA_Grant_Any`/`_Director` reused — exclusion list already matches 2026) as plain
    criteria with per-position thresholds, bundled into perspectives: "Punctul 4" conf
    `any[art≥1, grant≥1]` / prof `any[a≥3, all[total≥3, dir≥2], all[a≥2, dir≥1], all[a≥1, dir≥2]]`;
    "Punctul 5" conf `all[arts≥3, ci≥1]` / prof `all[arts≥5, journals≥3, ci≥3, ciJournals≥2]`; plus
    "Scor" bundling C/P/S. Note: "din cele maxim 10 articole" read candidate-favorably (count over all
    articles; the candidate picks their 10). Local + seeds + prod script; verify via provisional
    CS-department sweep.
  - [x] **S3 — FV Info restructure per Florin — DONE 2026-07-28 (`01076381`)** (pure data, both fișe;
    the seed files were resnapshotted with scripts/h54-1-snapshot-precious.js after the hand-splice
    incident — seed edits go through that script from now on): perspective "Perspectiva B" over
    {total, Publicații de top A*+A+B, (2026: Publicații A*+A)}, perspectives C and D over their rows,
    and "Total (verdict)" = `all[persp B, persp C, persp D, criterion sum≥threshold]` mirroring E19–E22.
    Changelog entry — the summary met-denominator changes for restructured reports. Prod apply after
    deploy.
  - [x] **S4 — route legend for any-rooted perspectives — DONE 2026-07-29.** Perspectives whose root
    is `any` with ≥2 children (FEAA Punctul 4's rutele a–d) render a legend under the group header:
    one row per route with its own DA/NU chip (server-evaluated, same vacuity rules as the group
    verdict; inapplicable routes dim chipless), hover highlights member tiles. Routes may SHARE
    criteria (dir≥2 sits in rutele b and d), hence legend-over-flat-tiles rather than sub-groups.
    Optional `CompositionNode.label` ("Ruta a"…; i18n "Varianta {n}" fallback); rail also reordered —
    unbundled criteria (books cap) now render BELOW perspective groups. Labels in local data + seeds
    + the pending prod script (label backfill made idempotent).
    **Extended 2026-07-29 (florin's HABIL report):** all-root perspectives' EARLIER-PERSPECTIVE refs
    now emit legend rows too — FV Info's "Total — verdict" showed only its sum tile ("Total DA"
    inside a NU group read as a contradiction); it now lists Perspectiva B/C/D rows with their own
    DA/NU chips (row label = referenced perspective's name; hover highlights that perspective's
    tiles rail-wide). Criterion children of all-roots stay tiles-only; plain all-roots and the FEAA
    any-root routes are unchanged. Code-only — no data or prod-script changes.
  - [x] **S5 — FEAA 2026 standards-sweep gaps — DONE 2026-07-29** (found same day, full COMISIA 27
    conf+prof+abilitare read-through). Two REAL misses, fixed data-only (script
    `h95s5_feaa_p5_p8.js`, runs AFTER h95s2; local + seeds applied, prod pending):
    (1) Punctul 5 prof sub-condition — "din cele 3 articole Core/Info, minim unul cu AIS > 0,15":
    indicator `FEEA_Q5_CoreInfo_AIS015_2026` (`(M == 10 || M == 8) && S > 0.15 ? 1 : 0`, same
    ECONOMICS_JOURNAL_AIS/m2026/IY_OR_LATEST shape), criterion 14 PROF ≥ 1, 5th leaf of the
    "Punctul 5" perspective.
    (2) Punctul 8 prof — "publicarea cel puțin a unei cărți de specialitate": indicator
    `FEEA_Books_Count_2026` over FEAA_BOOK-scored items, `docType == "bk" && S > 0 ? 1 : 0` (any
    publisher tier; chapters/proceedings excluded; domain fit stays human judgment), criterion 15
    PROF ≥ 1, unbundled (its own top-level gate — renders below the groups).
    Verified live (provisional CS sweep, 19 scored): eva Prof 3/4 (Core/Info-AIS leaf 9≥1 DA;
    Punctul 8 NU — her 0.88 book points are chapters), Conf unchanged 3/3 (both gates
    PROF-only → inapplicable). docType binds the pub itself in the publications path
    (cited==citing), pinned with formula tests. Changelog entry added. Sweep data point: 0/19 CS
    researchers have an authored 'bk' (canon holds 968 books overall).
    **FEAA closeout decisions (user, 2026-07-29) + follow-through:** docx export deferred (H50.5
    covers it when wanted); H94 descriptions = nice-to-have, content only; books missing from
    Scopus/OpenAlex → researchers add them manually (user-defined publications feed the book
    scorer — no work needed). DONE same day: (a) the REAL CNATDCU A2 list (Panel 4, sourced from
    cnatdcu.ro A2_Panel41.xls, mirrored in data/standards/economie/) now backs the FEAA national
    book tier — `FeaaA2Publisher` collection + fixture reconcile
    (report-data/feaa-a2-national-publishers.csv, 106 rows: RO + foreign sections, ONU aggregate
    row split into member orgs), `FeaaNationalPublisherService` re-backed from the CNCSIS-register
    proxy (register untouched for SENSE); self-seeds on boot, no prod script. (b) FEAA 2016 domain
    fix (score-raising, approved — report unused): FEEA_P null domain (scored 0 on every real run)
    and FEEA_C's Economie-only restriction (dropped the residual M=6 tier) both → ALL; verified
    live, eva P 0 → 71.15. Prod: `feaa_2016_domain_fix.js --restart` after deploy.
    Everything else verified in place: P/C formulas + N_ro, 2026 M table with SSCI/SCIE-only gate
    (no ESCI), article/review docType gate, top-10 + per-journal-year cap w/ Core/Info exemption,
    ANY_COAUTHOR semi-self-citation exclusion, book tiers incl. ISI-proceedings 0.1 + 25%-of-P-min
    cap (0.3125/0.75), Anexa 1 fixture (51 intl publishers), grant program exclusions, conf gates,
    thresholds (conf 2.25/1.25/0.75, prof=abilitare 6/3/2; abilitare has NO Punctul 8 and is not a
    report position today). Documented candidate-favorable approximations (no action): books don't
    consume article slots; points 4a/5 count over ALL articles not "din cele 10"; IY_OR_LATEST skips
    the "AIS nenul at deposit" condition; citations FROM Anexa-1 books (=Q4) not counted (no book
    citation edges in canon); national publisher list = CNCSIS register until CNATDCU A2 sourced.

- [x] `H96` Projection rebuild must not lock the serving read model (shadow-build + atomic swap).
  **DONE 2026-07-29 (same day as raised).** Implemented exactly as scoped below: insert writers took a
  target-suffix, `executeFullReplacementWrite` = build-into-`__next` transaction (zero live locks;
  constraints up front for load dedup, secondary indexes/FKs/dependent matviews after the load) +
  short drop-and-rename swap tx with `lock_timeout='3s'` and 5-attempt backoff retry, then catalog
  names restored from the captured live catalog (nx<i>_ shadow names, 63-byte-safe). Pinned in
  `PostgresReportingProjectionServiceIntegrationTest`: two consecutive cycles → byte-identical
  index/constraint name lists, FKs survive, zero `__next` leftovers; plus a concurrent reader that
  must never error across two rebuilds. Live-verified on the full local dataset (150k pubs / 1.3M
  authorships): buildShadow=142s with ZERO live locks, swap=90ms on attempt 1; a 100ms-interval
  read-hammer across the whole rebuild saw max 217ms and 0 errors (the old path held readers for
  the entire ~10-min write phase). Rider done: the always-on CS_CONFERENCE_TRACE DEBUG line in
  application.properties commented out — prod logs return to INFO.
  **Deadlock hardening (2026-07-29, caught by the new reader test in CI, not locally):** a
  multi-table reader can lock-order-invert with the swap (reader holds A wants B, swap holds B
  wants A) and Postgres's deadlock detector killed the READER. Fix: the swap requests every live
  lock up front in one LOCK TABLE ... ACCESS EXCLUSIVE statement with lock_timeout=250ms —
  deliberately SHORTER than deadlock_timeout (1s default) — so any inversion resolves by the swap
  aborting and retrying (20 attempts, short backoff), never by a reader error. 7 consecutive local
  runs of the integration class green after the fix.
  Original scope, kept for the record:
  **RAISED 2026-07-29 (prod incident, same day).** The full Postgres projection rebuild
  (`ScholardexProjectionBuilderService.executeFullReplacementWrite`) runs ONE transaction that drops
  the secondary indexes (instant ACCESS EXCLUSIVE on all 10 `reporting_read` tables), TRUNCATEs,
  bulk-inserts (~10.5 min in prod: writePg=631s of total=743s for 156k pubs / 1.3M authorships /
  555k citations), recreates indexes, commits. Every reader of `reporting_read.*` queues on the
  locks for the whole write phase — observed live: 9 of 10 Hikari connections in
  `wait_event=Lock` behind the rebuild's INSERT, pool exhausted, every request and health check
  timing out at 30s; the app read as down for ~12 minutes. Trigger was routine (OpenAlex author
  resolve → projection rebuild on CarThread-1); it recurs on every full re-sync.
  Mechanism: keep the all-or-nothing swap semantics but move the heavy work off the live tables.
  Build phase writes into `<table>__next` copies (`CREATE TABLE (LIKE … INCLUDING ALL)`, drop
  secondary idx on the empty copies, bulk-insert via the existing batch writers with the target
  table parameterized, recreate secondary indexes + FKs over the full copies) — zero locks on live;
  a failure rolls back leaving live untouched. Swap phase is one short transaction: DROP live
  (children first) + RENAME `__next` into place for all 10 tables, guarded by
  `SET LOCAL lock_timeout='3s'` WITH RETRY (without it the swap queueing behind one slow reader
  would itself block all new readers — the outage in miniature). Lock window: ~10 min → ms.
  Pinned traps (from code/catalog, not hypothetical): (1) renaming a table does NOT rename its
  indexes/constraints and index names are schema-global — post-swap, strip the `__next` marker from
  PK/unique/FK names or the SECOND rebuild collides with leftovers; cycle-idempotency (two
  consecutive rebuilds) is the core test property, and it keeps `captureSecondaryIndexes()`
  (pg_indexes by name) + Flyway valid. (2) FKs exist INSIDE the swap set (`citation_fact` →
  `publication_view(id)`, V2 migration) and `CREATE TABLE (LIKE)` does NOT copy them — capture from
  catalog and recreate on the `__next` set against `__next` parents (they follow the rename), else
  the first swap silently drops integrity constraints. (3) leftover hygiene: build starts with
  `DROP TABLE IF EXISTS <t>__next` so a crash between build and swap self-heals.
  Untouched: `executeBatchRefreshWrite` (incremental upserts, short tx); read semantics (old data
  until the atomic swap — same as today); `synchronous_commit=off` + build-indexes-once stay.
  Costs/risks (accepted): ~2× projection disk during build (low GBs); theoretical one-off pgjdbc
  cached-plan hiccup at the swap instant (self-healing).
  Test: extend `PostgresReportingProjectionServiceIntegrationTest` (real-PG Testcontainers infra
  exists) — two consecutive rebuild cycles → identical catalog (index/constraint names, FK count),
  correct data after each; concurrent-reader thread asserting reads never block >~1s mid-rebuild.
  Live verify: hammer a report page locally while a full rebuild runs.
  Rider: flip prod logging DEBUG → INFO (`CS_CONFERENCE_TRACE` floods the logs; the incident took
  minutes longer to diagnose for it).

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
    admitted later? Still open regardless: A3 (book editorship — not in our data), A6 (ISI
    proceedings ≥3 pages, needs CPCI + a page-count rule), A7/A8 (Brevet.Tip
    Triadic/European/International vs National, and `N_autori` is a STRING field today).
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

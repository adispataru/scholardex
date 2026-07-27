# Done Tasks

Archived completed tasks moved from `TASKS.md` on 2026-03-03.

## H92 Springer volume→conference identification via Crossref (archived 2026-07-27)

Archived from `TASKS.md`. Live end to end: keyless CrossrefClient (hard timeout, warn-level failures),
sweep with count/dryRun/apply admin endpoints, volume titles stored on the shared evidence row
(`crossref-volume`), scorer consults them after everything DBLP knows. Prod apply: 2,413 candidates,
2,348 resolved (97%) in 8.5 min; verified movements exactly as the review predicted (AINA ×2 → B/4,
AD-ZeroNAS correctly CISIS). Two follow-on fixes shipped under the same flag: the CORE title index now
strips history qualifiers (`stripHistoryQualifier` shared between index build and match), and the
LNCS/ACM floors became MINIMUMS (`55cbc6c6`) after the sweep exposed identified-D papers undercutting
them — user ruling: always the best category possible; verified up-only (+1.00 flavia.micota, +0.14 ×2,
0 decreases). Two incidents recorded in the entry: the Jackson-2-JsonNode-on-Jackson-3-codecs decode
failure (`810e3c69`) and the boundary-lint javadoc rewording (`7a60312c`).
Residuals, dormant: 65 unresolved volume titles (2.7%); widening beyond Springer `10.1007/978` only on
evidence that other publishers use container-title[1] the same way.

- [x] `H92` Identify a Springer proceedings volume's conference from Crossref.
  **FOUND 2026-07-26 on a second pass over florin.fortis's review.** Every remaining item in that email is
  the same shape: the paper sits on a Springer SERIES forum ("Lecture Notes on Data Engineering and
  Communications Technologies", "Communications in Computer and Information Science"), the series name says
  nothing about the conference, and there is no DBLP evidence to name it — so the paper takes the LNCS C
  floor at 2 points instead of its conference's rank. I had recorded this as unfixable ("no evidence to
  reason from"). That was wrong: **Crossref carries the volume title**, and it names the conference exactly.
  Verified against the three DOIs in the email — `container-title[1]` is the volume, `[0]` the series:
    - `10.1007/978-3-032-23335-6_20` → "Advanced Information Networking and Applications" = **AINA**
    - `10.1007/978-3-032-19105-2_17` → "Machine Learning and Principles and Practice of Knowledge
      Discovery in Databases" = **ECML PKDD**
    - `10.1007/978-3-031-96099-4_3`  → "Complex, Intelligent and Software Intensive Systems" = **CISIS**
  Each is the reviewer's own answer, from a free public API. Two of the three would match CORE with the
  matcher we already have: the AINA and CISIS volume titles are substrings of their CORE names
  (NORMALIZED_CONTAINS), and the CISIS title picks the RIGHT one of the two same-acronym entries by itself.
  ECML PKDD likely will not — CORE stores it as "European Conference on Machine Learning and Principles and
  Practice of Knowledge Discovery in Database" (singular "Database", extra leading "European"), so it needs
  either a stemmed token-overlap path or an alias.
  **NOT available from what we already hold** — checked: `openalex.publication_facts` carries only
  `hostVenueName` (the series) and `hostVenueSourceType`="book series"; the canonical fact stores the series
  volume NUMBER ("299", "2842 CCIS"), never the volume title. So this is a genuinely new source, not a
  field we forgot to project.
  **Prod scale: 2,413 Springer-ISBN papers sit on a series forum with no DBLP evidence; 29 of them touch an
  onboarded researcher, across EIGHT people** — alexandra.fortis (7), florin.fortis (12), ioan.dragan (4),
  mircea.marin (3), sebastian.stefaniga (3), florin.spataru (2), marc.frincu (2), todor.ivascu (2). This is
  not a Florin-specific gap and it grows with every onboarding.
  Shape: a Crossref client (polite pool, DOI → `container-title`), a `volumeTitle` field carried through
  fact → projection → view (the three-layer dual-path trap — check BOTH canon paths), consulted by
  `resolveConferenceMatch` after DBLP evidence and before the LNCS floor, plus a backfill sweep. Same
  architecture as the DBLP evidence side-table, so that is the pattern to copy.
  Closes three of the four remaining point gaps in the review (MedFusion-LM, and the two AINA volumes, each
  2 → 4). **EuroMLSys is NOT covered** — its Crossref container is "Proceedings of the 5th Workshop on
  Machine Learning and Systems", which never mentions EuroSys; that one still needs the curated
  workshop→parent map (see H90).

## H91 Report-dashboard UX: collapsible indicators + localization (archived 2026-07-27)

Recorded directly (never had a TASKS.md entry; the id appears in commits/comments). Two slices, both
live: collapsible indicator sections in the individual report (`32a9e78d` — class-based flag so
selectCriterion's eager-load `hidden` handling is untouched; default stays expanded; verified in the
browser) and full localization of `static/js/individual-report-dashboard.js` (`a4a666d4` — 65
`report.dash.*` keys, shared `t()/tPlural()` exposed on window, bundle inlined via the shared
IndividualReportViewModelAssembler, untranslated-lint now covers the file by path). Also the supervisor
position picker + promotion-board deep link (`805fe637`).

## H90 Reviewer-reported conference mis-identification — florin.fortis review (archived 2026-07-27)

Archived from `TASKS.md`. Thirteen of the fourteen review items resolved as the reviewer described and
verified in his refreshed prod report: CISIS ×5 (H89 fallback), BDCAT (`5f83ac2f` conferenceName retry),
EuroMLSys (ACM-DOI floor, `fd93b94c`), Euro-Par workshop reductions (correct — return to baseline),
conference/book double count (`22b50e75` shared Lecture-Notes predicate, corpus-wide 0 double-claims),
and the "counted elsewhere" relabel. The fourteenth (MedFusion-LM at C/2 instead of C/4) is carried by
H93 on purpose — a naive ECML-PKDD alias would over-credit the workshop paper to A/8. Residual: two 2026
Springer chapters DBLP has never indexed (nothing to import until DBLP does).

- [x] `H90` Reviewer-reported conference mis-identification (florin.fortis, 2026-07-26).
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

## H89 Conference CORE-rank losses + WoS sentinel + rebuild safety (archived 2026-07-27)

Archived from `TASKS.md`. All four score-affecting findings fixed and verified in prod: the DBLP
stream-forum CORE-rank loss (originalForumId fallback + history-qualifier strip, `844d144f`/`eb1b882d`),
the WoS +999 sentinel (symmetric parser guard + prod repair, Q1-1998 cohort 2808→2719), and the
rebuild-safety audit (read-only integrity script, ~0.2% standing orphan rate, auto-reconciler declined).
Residuals, deliberately dormant: the original RIS `Infinity` bare-`N` formula was never identified (no
non-finite values exist in prod; the clamp holds), and ~480 of 2,270 CORE entries were last ranked
before 2017, so recent papers at those venues yield `NO_CLOSEST_YEAR` silently.

- [x] `H89` Conference CORE-rank losses + non-finite metric root cause.
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

## Deploy-debt audit (2026-07-25)

The three "code done, data/deploy pending" footnotes buried inside the H68 and H80 entries were verified
directly against the production databases and are all **discharged**. Recording the evidence here so the
question does not have to be re-derived:

- **H68 Slice 3 — percent-of-criterion caps.** `individualReports` in prod: both `FV Info 2016` and
  `FV Info 2026` carry `Perspectiva D.maxPercentOfTotal = {13: 10, 17: 10}`. The data change that the entry
  said "deploys AFTER code" is deployed.
- **H80 Slice B — OpenAlex source APC.** `openalex.source_facts` holds 18,575 documents in prod, matching the
  local rebuild's `apcSources=18575` exactly; 5,379 carry a fee. The "later data migration" happened.
- **H80 Slice C — CORE re-import.** Prod `coreConferenceRanking` yearly entries include `National` (473) and
  `National_Regional` (26), which only exist if the post-fix `parseRank` re-import ran — a Scopus/WoS rebuild
  does not re-import CORE reference data. Done.

Method note: prod rank lives per-year under `coreConferenceRanking.yearlyRankings.<year>.rank`, not a
top-level `rank` field — a top-level group-by reports `null` for all 2,270 documents and looks alarming.

## H87 i18n for the public, researcher and supervisor UI (RO/EN) (archived 2026-07-25)

Archived from `TASKS.md`. All four slices shipped (`c0ea5674`…`6f904415`). Public + researcher + supervisor UI is RO/EN with Romanian as the default and the preference saved on the user; reports/indicators stay Romanian by decision. 884 keys in both bundles. Three build-gating lints (`test-i18n-{helper,keys,untranslated}`) plus `verify-assets-fresh` now run in `gradle check`.

- [x] `H87` i18n for the public, researcher and supervisor UI (RO/EN).
  **SCOPED 2026-07-25.** Driver: the UI is English while the domain, the researchers and every message we
  send them are Romanian; the changelog + welcome banner shipped in Romanian, so the app now visibly mixes
  languages. Decision from the user: **UI only** — reports, indicators and standards text stay Romanian
  (they quote OM 3019/2025 and the FV templates; translating them would misrepresent the standard).
  Admin pages are out of scope (operator surface, English is fine).
  Surface inventory: ~26 Thymeleaf templates (root 4, user 4, supervisor 2, publications 2, forums 2,
  authors 2, rankings 1, universities 1, core 1, reports 2, shared 1, errors 4) + `fragments.html`
  (shell/nav/footer, the highest-leverage file) + ~113 user-visible strings across the 7 workspace JS
  modules (toasts, empty states, badges, wizard copy).
  **S1 DONE locally 2026-07-25**: `LocaleConfig` + `UserPreferenceLocaleResolver` (saved preference →
  cookie → Romanian; `?lang=` switch persists to `User.preferredLanguage` AND a cookie), `messages`
  (ro, the default bundle) + `messages_en`, a switcher in both shells, nav labels + the changelog page +
  the landing welcome converted. GOTCHA: the resolver takes `ObjectProvider<UserRepository>` — a hard
  dependency broke every `@WebMvcTest` slice (WebMvcConfigurer beans load, repositories do not). Pinned by
  a bundle-parity test (same keys + same `{0}` placeholders in both languages) and resolver unit tests.
  Slices:
  - **S1 infra**: `MessageSource` (UTF-8 `messages_ro`/`messages_en`), `LocaleResolver` persisting the
    choice per user (cookie + the existing WorkspacePreferences for logged-in users), a switcher in the
    shell header, `#{...}` in the two newest surfaces (landing welcome + changelog) as the pilot, and a
    test asserting both bundles have identical key sets (the classic drift bug).
  - **S2a DONE locally 2026-07-25**: shell chrome (skip link, nav aria, theme toggle, workspace switcher,
    panel labels, sign-in/out), landing page (title/meta/hero/CTAs/surface cards), and ALL error copy —
    which lived in `ErrorPageModelFactory`, not in templates, so that class now resolves through
    MessageSource (both construction sites inject it). Terminology set: Forums → "Forumuri", Workspace →
    "Spațiul meu". GOTCHA: `LocaleChangeInterceptor` does not run when no handler matched, so `?lang=` was
    dead on 404/error pages — the resolver now reads the `lang` parameter itself (read-only; persistence
    still happens via setLocale). Test copy assertions moved to the Romanian default; English coverage sits
    in `ErrorPageModelFactoryTest`, which asserts both bundles resolve.
  - **S2b DONE locally 2026-07-25**: publications list+detail, forums list, authors list+detail,
    universities detail, core ranking detail and the rankings hub (4 tabs: CORE/universities/events/WoS —
    filters, sort options, table headers with their tooltips, loading/empty states, page intros, tab
    labels). Shared `common.*` vocabulary (filters/sort/pagination/year/name/type/category) keeps the four
    filter blocks consistent. Untranslated by rule, as agreed: entity names, ISSN/eISSN/DOI, CORE ranks
    (A*/B/C/Unranked), quartiles, WoS/Scopus/DOAJ/ERIH, h-index — plus `th:text` design-time fallbacks,
    which are placeholders for dynamic data, not copy. Contract assertions that pinned markup shape
    (`<th scope="col" data-sort-key="name">…`) keep the shape and now expect the Romanian label.
  - **S3a DONE locally 2026-07-25** (plumbing + 2 of 4 user templates): CLDR plural rules in BOTH
    languages — `PluralRules.java` + the JS twin in `modules/shared/i18n.js`. Romanian needs three forms and
    the third takes a particle ("20 DE publicații"); the old `n !== 1 ? 's' : ''` pattern could not express
    it, and the S1 landing banner shipped with that defect (now fixed: keys are `.one/.few/.other`, selected
    server-side via `#{__${pluralKey}__(...)}`). Client copy comes from `window.appI18n`, INLINED into
    workspace.html by `UiMessageBundleService` (key list read from the base bundle, values resolved through
    MessageSource) — synchronous, so nothing races the lazy panels. Guardrails: parity test now requires
    complete plural families in both bundles; `scripts/test-i18n-helper.js` pins the JS categories against
    the Java table (0/20/101 boundaries) and the contract test pins "21 de noutăți" through the real
    template. GOTCHA (3rd time): a new controller constructor dep broke every `@WebMvcTest` for that
    controller until mocked.
  - **S3a REMAINING**: `user/individual-report-view.html` (29) and `user/individual-report-import.html`
    (45) — deliberately left for last since report copy borders on standards wording.
  - **S3b DONE locally 2026-07-25**: all 6 workspace JS modules converted to `t()`/`tPlural()` (~129
    strings; bundle now 204 keys). Every `${n} thing${n !== 1 ? 's' : ''}` became a 3-form family, verified
    live: "1 citare" / "5 citări" / "20 de citări". TRAPS handled: (1) `AGGREGATION_TYPES` were both the
    label AND the persisted `aggregationType` value — split into {value, key} so a Romanian UI does not
    start submitting Romanian venue types; (2) the aggregation render used `.map(t => …)`, which would have
    shadowed the imported translator — parameter renamed; (3) most strings live inside HTML template
    literals (placeholder/aria-label), not quoted JS, so a naive literal sweep misses them.
  - **S3 REPORT TEMPLATES DONE locally 2026-07-25**: `individual-report-view.html` +
    `individual-report-import.html`. The UI/standards line held: chrome translated ("Punctaj total",
    "Criterii îndeplinite", "Total din fișier"), while the standard's own labels stay Romanian in both
    locales — e.g. `report.import.perspectivaDActivities` is "Perspectiva D activities" in English.
    TRAPS: (1) blanket `>Word<` replacement collided with tags that ALREADY had `th:text` (report title,
    indicator name, criterion name, export label) → duplicate attribute = Thymeleaf parse failure; the
    dynamic expression wins and the literal fallback is localized inside it. (2) `report.view.noRun.other`
    tripped the plural-family guardrail because `.other` is a reserved CLDR suffix — renamed to
    `.forOtherResearcher` rather than weakening the check.
    NEW GUARDRAIL: `scripts/test-i18n-keys.js` (npm `test-i18n-keys`) statically verifies every `#{...}` in
    a template and every `t()`/`tPlural()` base in the JS resolves against the bundle — it covers markup
    that cannot be rendered without fixture state, like the report-import tables which need an upload.
    Currently 455 keys, all resolving.
  - **S4 supervisor pages** + a sweep for leftovers (a lint that fails on bare text nodes in the
    in-scope templates).
  Open decisions for the user: default locale (RO for uvt.ro users vs browser `Accept-Language`), and
  whether report/indicator NAMES stay Romanian inside an English UI (recommended: yes, they are the
  standard's own labels).

## H86 In-app changelog page ("Noutăți / What's new") (archived 2026-07-25)

Archived from `TASKS.md`. Changelog page live at `/changelog` (`38c85388`, `fa21c429`) with PLATFORM/REPORT scope tags alongside the scoring-impact flag, 12 backfilled entries, and the workspace "what's new" badge.

- [x] `H86` In-app changelog page ("Noutăți / What's new").
  **PAGE DONE locally 2026-07-25.** `/changelog` renders the committed
  `src/main/resources/changelog/changelog.json` (12 backfilled entries covering the H82–H85 wave), grouped
  by date newest-first, Romanian dates via an explicit `ro` locale, scoring-impact entries accented and
  chipped, ADMIN-audience entries + the maintenance footnote visible only to PLATFORM_ADMIN. Loader is
  fail-soft (bad/missing fixture → empty page, never a broken context) and the service test runs against
  the REAL fixture so a malformed entry fails the build. Nav link added to both the public header and the
  app shell topbar. REMAINING: the "what's new" badge — `ChangelogService.newSince(lastVisit, isAdmin)` is
  implemented and tested but not yet wired into the workspace (needs a model attribute + a badge in the
  workspace header); optional later: deep-link a report drilldown to the entry that changed an indicator.
  Driver (2026-07-25): the platform now changes fast enough that researchers see score movements without
  knowing why (UCC → C, SYNASC 10 → 20, percent caps, best-of rankings), and the only record is email.
  Proposed shape: entries live in a COMMITTED file (`src/main/resources/changelog/changelog.json`) so an
  entry ships in the same commit as the change it documents and cannot drift; loaded at startup like the
  standards/publisher lists. Entry = {date, title, body, audience RESEARCHER|ADMIN|ALL, scoringImpact
  flag, affects[] (report/indicator keys)}. Page at `/changelog` (researchers see RESEARCHER+ALL, admins
  see everything), newest first, scoring-impact entries visually distinct — that is the "why did my score
  change" answer. Workspace hook: reuse the existing lastVisit stamp + NudgeService for a "what's new"
  badge. Backfill entries for the whole H82–H85 wave. Later (optional): deep-link from a report drilldown
  to the entry that changed that indicator.

## H85 OM 2026 conference-list amendments: ACM/EPTCS → C; UCC Companion mislabeling (archived 2026-07-25)

Archived from `TASKS.md`. ACM/EPTCS → C floor for 2026 shipped, made durable via `originalForumId` preserved through DBLP conference resolution (`dc5a336a`) so the signal survives a full rebuild. Verified in prod: Florin's UCC 2012/2014 score C / quarter ACM / 2.0 with `acmEvidenceVenue` provenance; the 2011 IEEE-only paper stays D.

Closed task doc: `docs/tasks/closed/h85-om2026-acm-eptcs-c-floor.md`.

- [x] `H85` OM 2026 conference-list amendments: ACM/EPTCS → C; UCC Companion mislabeling.
  **SCOPED 2026-07-24** (`docs/tasks/active/h85-om2026-acm-eptcs-c-floor.md`).
  The 2026 OM amends the CORE list: "categoria C va include și lucrările publicate în ACM, EPTCS și LNCS
  care nu sunt în categoriile A*, A și B" — we implement only the LNCS→C floor (correct for 2016, whose
  amendment is LNCS-only). UCC is CORE-Unranked (2021/2023/2026), so the amendment decides: ACM-published
  from 2013+ (IEEE/ACM co-sponsorship; NOTE the DOI stays IEEE-branded, so detect via proceedings-name
  tokens/publisher, not DOI prefix) → C in the 2026 fișă, D in 2016. Implement as a 2026-scoped floor next
  to the LNCS special case (per-standard gating precedent: workshopCategory2026). Second facet: Florin's
  UCC entries display as "UCC Companion" though published in the MAIN volume — investigate on prod (Scopus
  venue assignment vs our forum merge); near-term remedy exists via admin bulk reassign-forum.
  **Slice A DONE 2026-07-24** (floor + 4 clones, deployed + prod data applied). **Slice B DONE** —
  Companion mislabel not present in current prod data (ask Florin where he sees it).
  **Slice C DONE locally 2026-07-24** — the sweep's conf/X re-stamp destroyed the ACM name signal
  ("UCC", empty publisher), so Slice A missed exactly Florin's papers; fix preserves the displaced raw
  proceedings forum as `originalForumId` (fact → view → Postgres V25 → ScoringPublication) and the floor
  consults it. Rollout: deploy, then admin "Full derived-data rebuild" backfills; then Florin's refresh
  should show UCC 2012/2014 = C (quarter ACM), UCC 2011 = D.

## H83 University rankings — QS + ARWU ingestion, best-of resolution, URAP back-catalog (archived 2026-07-25)

Archived from `TASKS.md`. S1–S4 all shipped: URAP 2010–2017 back-catalog, ARWU + QS ingestion, best-of rank resolution across the three sources, and the UNIVERSITY_NAME picker. Live in prod (`urap.rankings` 4,499 docs).

Closed task doc: `docs/tasks/closed/h83-university-rankings-best-of.md`.

- [x] `H83` University rankings — QS + ARWU ingestion, best-of resolution, URAP back-catalog.
  **SCOPED 2026-07-24** (`docs/tasks/active/h83-university-rankings-best-of.md`). OM 3019/2025 footnote *3
  (same in 2016): D(viii)/D(ix)/D(xi) score by the BEST position across QS/URAP/ARWU — we only have URAP
  (2018–2024). S1: scrape URAP 2010–2017 archives → same xlsx shape → re-run `/general/urap` (closest-year
  becomes exact for old visits). S2: generic `UniversityRanking{name, source, year→rank}` + loaders for
  ARWU (Kaggle 2003–2025 full back-catalog) and QS (2017–2022 + 2025/2026; older partial). S3: best-of
  lookup facade (min rank across sources, closest-year per source, provenance in scoringInfo) consumed by
  `UniversityRankScoringService` — bracket formulas untouched. S4 optional: UNIVERSITY_NAME autocomplete
  picker mirroring the CORE conference picker. Verification: Florin's Pisa / Aix-Marseille cases.

## H82 **DONE (2026-07-24, `39a1bbf6`)** Scopus re-sync re-enriches EXISTING publications (narrow-first) (archived 2026-07-24)

Archived from `TASKS.md`. Already marked done in place (`39a1bbf6`); archived here for consistency. Widening the re-enrichment field list beyond coverDate stays open for a future consumer.

- [x] `H82` **DONE (2026-07-24, `39a1bbf6`)** Scopus re-sync re-enriches EXISTING publications (narrow-first).
  The coverDate precedence fix (`069153f3`) promised "existing wrong dates heal on the next Scopus author
  sync" — false in practice: a FULL publication sync reports "Imported 0 items" because already-imported pubs
  are skipped before `ScholardexPublicationCanonicalizationService` runs, so the enrichment that would claim
  Scopus's coverDate/coverDisplayDate never touches them (verified in prod 2026-07-24 on the FGCS 2008→2009
  case; healed via the full derived-data rebuild). Shipped: FULL mode collects all seen eids and
  `ScopusExistingPublicationReenrichmentService` re-claims coverDate/coverDisplayDate on drifted canonical
  pubs (narrow field list by design — broad re-enrichment risks re-clobbering DBLP forums/admin fixes),
  dirty-marks with the pubs' own sourceBatchIds and pushes the per-batch partial projection rebuild.
  Widening the field list (title/authors/forum with clobber guards) stays open for a future consumer.

## H81 Informatică 2026 Fișă (xlsx export/import) (archived 2026-07-04)

Archived from `TASKS.md`. All four slices plus the live end-to-end export were completed on 2026-07-04 — the entry said "H81 fully done" but the checkbox was never flipped. Archived unchanged.

Closed task doc: `docs/tasks/closed/h81-informatica-2026-fisa-xlsx.md`.

- [x] `H81` Informatică 2026 Fișă (xlsx export/import). **SCOPED 2026-07-04**
  (`docs/tasks/active/h81-informatica-2026-fisa-xlsx.md`). A 2026 version of the `informatica-2016` xlsx Fișă, adapted
  from the 2016 template. Structure barely changes (FV Info 2026 has identical export roles); the deltas are the A*+A
  publication criterion (a `Centralizator` template formula) and a new perspective-d **director-project count**
  criterion (`Minim un proiect` as director, ≥50k EUR).
  - **Slice 1 — DONE 2026-07-04:** GenericActivity indicator `Info_D_Proiecte_Director` counts grants where
    `Rol != 'Membru' && budget >= 50000` (reuses Info_D_v's `B` pattern → no `Buget` field-type change, 2016 frozen).
    Added to FV Info 2026 only; live-verified florin count=1. Team-size/competition not in our data (self-declared).
  - **Slice 2 — DONE 2026-07-04:** `INDICATOR_TOTAL` scalar-cell export policy — `TemplateXlsxRenderer` 4-arg overload
    stamps a template cell with the run's per-role total (`snapshot.getTotals()`, already keyed by role for every report
    type). MANUAL cells untouched; missing total/sheet = warn-skip. Unit-tested; transfer suite green.
  - **Slice 3 — DONE 2026-07-04:** `report-templates/informatica-2026/template.xlsx` — `D-Perspectiva D!K24`
    "Număr proiecte ca director" (outside the points SUM, filled by the scalar cell) + `Centralizator` A*+A criterion
    (correct `J17+J18+K19+K20` subtotal; 2016's A*-only `E10` left frozen) + director-project `count>=1` criterion +
    an **Abilitare** block (rows 35–39: B 44/A*+A+B 28/A*+A 12, C 84/26, D 48, combined `AND` — no Total-points gate;
    references stable `D7`/`D11`/`D15`, doesn't touch Hirsch cells). **Perspectiva-B per-rank *Publicații de top* gates
    corrected to 2026** (CONF 16 / PROF 40 / HABIL 28) — aligns formulas with the sheet's own labels the 2016 template
    contradicted (`E10` checked 16 though `C10` said 40; `E8` gated lector though `C8` said "oricare"); prof A*+A subtotal
    fixed A*-only→A*+A.
  - **Slice 4 — DONE 2026-07-04:** `informatica-2026/binding.json` (+ `INDICATOR_TOTAL` scalar cell, quoted sheet name)
    + `Informatica2026ReportTypeImportSupport` (registry auto-discovered). FV Info 2026 already keyed `informatica-2026`
    (export was failing on the unregistered support — now resolves). Seed consistent. Unit-tested.
  - **Live E2E — DONE 2026-07-04:** booted `agent-dev`/8181, exported florin's FV Info 2026 Fișă (run
    `directorScore=1`) → HTTP 200 xlsx with `D-Perspectiva D!K24 = 1.0` (director count stamped end-to-end) + corrected
    perspectiva-B / A*+A / abilitare formulas intact; POI FormulaShifter correctly followed the B-Conferinte table
    expansion (>10 conf pubs) so the A*+A refs stayed semantically right. **H81 fully done.**

## H80 H79 production rollout (get the Informatică 2026 report live) (archived 2026-07-25)

Archived from `TASKS.md`. Slices A, B and C were all completed 2026-07-04. The three "deploy later" footnotes were VERIFIED AGAINST PROD on 2026-07-25 and are discharged — see the archive note below.

Closed task doc: `docs/tasks/closed/h80-h79-production-rollout.md`.

- [x] `H80` H79 production rollout (get the Informatică 2026 report live). **SCOPED 2026-07-04**
  (`docs/tasks/active/h80-h79-production-rollout.md`). H79 code is merged + verified locally; the ingest→project pipeline
  (`PipelineRebuildService`) already folds in DOAJ APC + the OPENALEX membership projection + the project projection —
  the **only code gap** is that the OpenAlex source-APC derivation is a standalone endpoint, not a pipeline step.
  - **Slice A (code) — DONE 2026-07-04:** shared `OpenAlexSourceApcAggregator`; `OpenAlexBulkImportService.importAll`
    folds the per-venue APC derivation into the works/citers stream (mirroring the `referenced` institution-id threading)
    → `openalex.source_facts` produced in-DAG before the stage-4 projection. Standalone service + endpoint kept for
    manual re-runs. `BulkImportResult` gained `apcSources`/`apcFeeJournals` (logged in the rebuild). Unit-tested; full
    suite green. No pipeline-wiring change (the DAG already calls `importAll`).
  - **Slice B (ops) — DONE 2026-07-04:** the local `scholardex` Mongo/PG **is** the prod DB, so the full rebuild
    (`rebuildAllDerived?confirmation=RESET&reingest=true`, caffeinated+daemonized, ~34 min, 0 errors) **was** the rollout.
    Fold produced `source_facts` in-DAG (`apcSources=18575 apcFeeJournals=2140` from cleared-0); projection emitted 2,140
    OPENALEX apc=true rows; MDPI *Electronics* → `isFeeJournal=true`; florin's Electronics zeroed in FV Info 2026 (2016
    unchanged). Deploy to stage/prod is a later **data migration** (gated on Informatică backlog + public-UI polish).
  - **Slice C — closed 2026-07-04. Informatică scoring backlog now clear.** **C1 posters/system-demos — DONE:** neither
    Scopus (`cp`) nor OpenAlex (`article`) distinguishes them (verified in the raw dump), so a strict title-prefix detector
    (`Poster:`/`Demo:`/`Demonstration:`) reuses the slice-6 reduced path (category A*/A/B→C, C→D + 6/4/2/1 pts + `topAB`
    exclusion), 2026-gated (2016 unchanged), no indicator/seed change, unit-tested. **C2 per-pub-year APC — DROPPED**
    (standard keys APC to "momentul depunerii dosarului" = current state). **C3 b↔c 20% compensation — NOT a platform
    feature** (`id_parA118` discretionary — a committee exception like perspectiva-a ethics). **C4 CORE national/regional
    → C (`id_parA81`) — DONE:** completeness check found `parseRank` collapsed National/Regional→D; now preserved as
    `Rank.National`/`National_Regional`, scorer remaps →C (2026)/D (2016), version-gated; live-validated (124 National +
    7 Regional re-imported). **No short-paper exclusion exists in the CS standard** (the `rezumate/abstract` list is a
    different domain). **Deploy step:** stage/prod migration needs a CORE re-import
    (`POST …/general/coreConference`) — a Scopus/WoS rebuild does not re-import CORE reference data.


## H79 Informatica 2026 report (CNATDCU standards update) (archived 2026-07-04)

Archived from `TASKS.md` — scoped code complete + verified (unit + live). Closed task doc:
`docs/tasks/closed/h79-informatica-2026-report.md` (full slice-by-slice record).

- [x] `H79` Informatica 2026 report. *(scoped 2026-07-03; code complete + verified 2026-07-04)*
  Principle held throughout: **version, never mutate** — `FV Info 2016` and its indicators are byte-stable; every 2026
  change is a parallel copy.
  Outcome (verified end-to-end against local Mongo/PG via the delegated report path):
  - **Slices 1–4** (prior session): report clone (asist/lect internal + conf/prof/HABIL); `Info_C_2026`
    (`ANY_COAUTHOR` citations); A\*+A prof/HABIL indicator + threshold; SENSE book top 16→12 (`Info_D_i_2026`).
  - **Slice 5 — fee-journal (APC) exclusion.** DOAJ APC capture (`DoajJournalFact.apc`, `V21` membership `apc` column,
    `feeJournal` formula var, `isFeeJournal`), 2026 journal indicators gated `!feeJournal`. **5d — OpenAlex Source APC
    ingest** (`OpenAlexSourceApcImportService`, offline from the works dumps) closes the DOAJ coverage gap: derives
    per-venue `is_oa && apc_usd>0` → `database='OPENALEX'` membership; `isFeeJournal` broadened to any `apc IS TRUE`.
    Catches gold-OA venues DOAJ misses (MDPI *Electronics* etc.; 177 forums OpenAlex-only). Live: florin's Electronics
    paper 2016 authorScore 2.0 → 2026 0.0.
  - **Slice 6 — 2026 conference-workshop category (`id_parA82`).** `Indicator.workshopCategory2026` + scorer threading:
    workshops relabel A\*/A/B→C, C→D (points 6/4/2/1 unchanged). `Info_B_Conferințe 2026` copy swapped in. **6d —
    category-based top eligibility**: `topAB` signal (`isTopAStarAB`) gates the 2026 `A*+A+B` indicators on category
    instead of `S>=4`, so a category-C workshop (6/4 pts) drops out of the top while journals/SENSE-C books are
    unaffected. Live: dana.petcu's Cluster Workshops paper B(2016)→C(2026), excluded from top, kept in all-conference.
  - **Deferred (not built):** production deploy sequence (DOAJ re-import → OpenAlex APC import → projection; FV Info 2026
    division visibility + director-signature projection); posters/system-demos (same `id_parA82` reduction);
    per-pub-year APC edition resolution; b↔c 20% compensation. Pre-existing `FEEA_P` seed duplicate flagged separately.

## H61 Citation exclusion "any co-author" mode (archived 2026-07-04)

Archived from `TASKS.md` — mechanism was done 2026-06-25; the only remaining item (re-point Informatică to
`ANY_COAUTHOR`) was completed by H79. Closed task doc: `docs/tasks/closed/h61-citation-coauthor-exclusion.md`.

- [x] `H61` Citation exclusion "any co-author" mode. *(mechanism 2026-06-25; Informatică re-point via H79; archived 2026-07-04)*
  - **Mechanism:** `SelfCitationPolicy {NONE, CANDIDATE_ONLY, ANY_COAUTHOR}` replaced the boolean in
    `IndicatorKind.Citations`; legacy codec maps `CITATIONS`/`CITATIONS_EXCLUDE_SELF`→`NONE`/`CANDIDATE_ONLY` +
    `CITATIONS_EXCLUDE_COAUTHORS`→`ANY_COAUTHOR` (no migration). Both filter sites (`computeCitationView` score +
    `CitationRowProjector` display) share `citationExclusionAuthorIds(policy, cited, candidateIds)`.
  - **Informatică re-point (via H79 slice 2):** `Info_C_2026` and `Info_C (A*, A, B) 2026` both use `ANY_COAUTHOR`,
    live in FV Info 2026 — verified 2026-07-04. Resolved the open per-cited-paper vs global-network question =
    per-cited-paper (the cited pub's full author set).
  - **Caveat shipped:** `ANY_COAUTHOR` under-excludes when co-authors aren't canonicalized to the same ids
    (documented in the enum).

## H20 Google Scholar (PoP) onboarding — DROPPED (2026-07-04)

- [x] `H20` Google Scholar (Publish-or-Perish) user-onboarding. *(dropped 2026-07-04 — not archived-as-done)*
  Removed from the active backlog as no longer relevant: OpenAlex + Scopus + DBLP (H66B/H73/H75) already provide the
  canonical publication + citation graph, so a separate Google Scholar / PoP ingestion path isn't needed. Recreate a
  fresh task if a Scholar-only coverage gap surfaces later.

## H60 Relative year specs (recent-window + latest-rankings) (archived 2026-06-30)

Archived from `TASKS.md` — mechanism built end-to-end (2026-06-25), Matematică re-pointed live, all re-score paths
covered, exit criteria met, tests green. Closed task doc: `docs/tasks/closed/h60-relative-year-specs.md`.

- [x] `H60` Relative year specs. *(completed 2026-06-25; archived 2026-06-30)*
  Goal: replace fixed absolute year ranges with self-rolling relative windows, anchored on a per-run `referenceYear`
  for deterministic replay.
  Outcome:
  - **Model:** `YearRangeSpec.PreviousNYears(n)` (article inclusion `[t-n..t-1]`) + `ScoreYearRangeSpec.LatestNRankings(n)`
    (the n most recent DB ranking-years ≤ refYear; empty without context = no stale fallback); `AllYears` caps at
    referenceYear when set. Legacy codec `PREV:n`/`LATEST:n`. `UserIndividualReportRun.referenceYear` set at creation.
  - **Threading:** thread-scoped `ScoringReferenceYearContext`; `ReportingLookupPort.getDistinctRankingYears()`
    (memoized DISTINCT `wos_metric_fact` years); build path wraps with the run's year, live/detail default to current.
  - **Inclusion enforced** in `ScientificProductionService` (AllYears = fast no-op); all re-score paths (xlsx export,
    H50 snapshot projectors, apply view, group reports) resolve via `currentOrCurrentYear()`.
  - **Matematică re-pointed live:** `Mate_S_recent.yearRangeSpec`→`PreviousNYears(7)`; `Mate_S`/`Mate_S_recent`/`Mate_C`
    `scoreYearRangeSpec`→`LatestNRankings(1)` (resolves to 2024 JCR — the standard's "latest list at submission").
  - **Shipped alongside:** `CitationPolicyMigrationRunner` (raw-Mongo startup self-heal for the H61 boolean→enum
    Citations shape — see memory record-component-change-breaks-mongo-deser).
  - **Carved-out follow-up resolved (2026-06-30, no-op):** the `Mate_C` SRI boundary — Ordin 6129/2016 Matematică
    specifies `≥ 0.5`, already implemented by the live count formula `S >= 0.5 ? 1 : 0` (`S` = citing journal's SRI
    under the RIS strategy). Nothing to change.
  - **Operational caveat:** the live instance must run the post-H60/H61 build (the migration runner self-heals on boot).

## H78 Researcher project workspace — import / search / link (archived 2026-06-30)

Archived from `TASKS.md` — all 4 slices shipped, unit + `@WebMvcTest` tested, and live-verified end-to-end (agent-dev).
Spun off from `H64` slice 4c; builds entirely on the H64 canonical project layer. Closed task doc:
`docs/tasks/closed/h78-researcher-project-workspace.md`.

- [x] `H78` Researcher project workspace. *(completed 2026-06-30)*
  Goal: a project-centric workspace flow on top of the canonical project layer — surface, import, link.
  Outcome (4 slices, all routing through one idempotent `ResearcherProjectService`):
  - **Slice 1 — director attribution + read-only "My projects":** `V20` adds an indexed `director_signature` column to
    `scholardex_project_view`, computed at projection time via `ProjectCanonicalizationService.signature` (now public)
    over the director's first+last name; `ScholardexProjectReadPort.findByDirectorSignature` matches by exact equality;
    `GET /user/workspace/projects/mine` surfaces the researcher's director projects (word-order/diacritic-insensitive);
    a read-only "Projects that may be yours" card. Homonym-safe (candidate list, never a silent link).
  - **Slice 2 — one-click import:** `importProject(email, projectId)` builds a pre-filled `Grant Cercetare` instance
    (config-aware — only declared fields: `Nume Proiect`←title, `Buget`←budget, `Rol`←director role inferred
    intl/national by funder) + `PROJECT_GRANT_ID`; idempotent on that reference (`EXISTS` returns the existing
    instance). `POST /activities/import-project/{projectId}`; an Import button per "My projects" row.
  - **Slice 3 — link existing + not-found:** `linkProject(email, instanceId, projectId)` sets the reference + back-fills
    only-blank declared fields (preserves the researcher's values), ownership-checked; `POST
    /activities/{instanceId}/link-project/{projectId}`; a quick "Link" affordance on unlinked project-supporting rows;
    the shared picker's empty state shows admin-deferred guidance (no researcher minting of canonical projects).
  - **Slice 4 — participant search-and-import:** `importProject(..., asParticipant)` sets `Rol`=`Membru`; endpoint
    `?role=participant`; a toolbar "Add a project you took part in" search picker. Participants have no name in the
    canonical data, so this search-driven path is how they self-serve (vs the director auto-surfacing).
  - **Decisions settled:** director match = projected signature column (projection-only re-run, no full rebuild);
    CORDIS escalation = admin-deferred (no researcher minting); auto-surfacing = director-only (no participant names in
    the data — participants self-serve via search). The H64 picker already attached `PROJECT_GRANT_ID` on create+edit,
    so the net-new value was surfacing + pre-fill + idempotency.
  - **Deploy step:** re-run the project projection once so existing `scholardex_project_view` rows get a
    `director_signature` (column is null until then; full-replacement projection, not a full rebuild).

## H65 Physics (Fizică / FF) report — DOCX export (archived 2026-06-30)

Archived from `TASKS.md` — engineering complete, all 4 slices shipped + tested against the real 21-table template.
Remaining work is per-deployment indicator *config* (data, not code). Closed task doc:
`docs/tasks/closed/h65-physics-report-export.md`.

- [x] `H65` Physics (Fizică/FF) report — DOCX export. *(completed 2026-06-30)*
  Goal: export the FV Fizică fišă (Ordin 6129/2016, Anexa 1; 21-table template).
  Outcome (4 slices):
  - **Slice 1 — Nef core + I/P:** new primitive `Nef` (effective author-count bracket: `n≤5→n; 5<n≤15→(n+5)/2;
    15<n≤75→(n+15)/3; n>75→(n+45)/4`), bound in `FormulaContext` (pub + activity paths) + declared in the variable
    contract → most indicators become config. I=ΣAISᵢ/Nefᵢ (table 17), P=ΣAISᵢ first-or-corresponding via H63
    `FIRST_OR_CORRESPONDING` (table 18).
  - **Slice 2 — A1–A6:** didactic blocks rendered as `STACKED_BLOCKS` (tables 7–12), grouped by activity name.
  - **Slice 3 — A7–A10:** patents (A7/A8, reuse `Brevet`), projects (A9 director count, A10 research-project € via the
    H64 `proj_budget` injection — `proj_budget != null ? proj_budget : Buget`, like CS Info_D_v) as `STACKED_BLOCKS`
    (tables 13–16); A subtotal = ΣA₁..A₁₀.
  - **Slice 4 — C + h + T + summary:** composite **T = A + P/2 + I/2 + C/20 + h/5**; T20 summary (table 20:
    Indicator|A|I|P|C|h|T) reads the `fizica-c` (C=Σcᵢ/Nefᵢ) + `fizica-h` (WoS Hirsch) indicator totals. The nested
    **C citation detail table (table 19)** — cited-pub slots `I./II./III.` each with citing sub-rows `1./2.` + merged
    cells — filled via a physics-specific POI post-pass (`Fizica2024ReportTypeImportSupport#fillCitationTable`), cell
    indices verified empirically against the rendered template; footer `C =` total. Limitation: 3 built-in cited × 2
    citing slots (representative sample, no row-group expansion yet).
  - **Reuse:** AIS strategy, FEAA/CNCSIS allowlist + editor-role patterns, Mate_C `{RIS, excludeSelf}` citation kind,
    the `TemplateBinding`/`TemplateDocxRenderer` DOCX infra. Render tests cover all detail tables + the summary.
  - **Per-deployment config remainder (data, not code):** the `Fizica_C` (citation/RIS kind + role `fizica-c`),
    `Fizica_h` (HIRSCH/WOS_VENUE), and A7–A10 indicators + Lector/Conf/Prof threshold rows are seeded per deployment.

## H64 Canonical projects (unification across sources) (archived 2026-06-29)

Archived from `TASKS.md` — engineering complete + live-verified end-to-end with real data. Researcher-facing
import/search/link spun off to `H78`. Closed task doc: `docs/tasks/closed/h64-canonical-projects.md`.

- [x] `H64` Canonical projects. *(completed 2026-06-29)*
  Goal: a canonical `ScholardexProject` researchers reference across project-scoring reports (physics A9/A10, FEAA, CS),
  unifying project identity + attribution; budget opportunistic.
  Outcome (4 slices):
  - **Source → canonical → projection:** brainmap is the only RO-national source (no budget); `scopus-python/
    brainmap_dump.py` (manual-search headful, harvest the results LIST) → `data/brainmap/uvt_projects.jsonl` (341 UVT) →
    `BrainmapProjectImportService` → `ProjectCanonicalizationService` (`ScholardexProjectFact`, merge by EU grant id
    else code; coordinator → canonical affiliation via a token-signature index) → `ProjectProjectionService` →
    Postgres `reporting_read.scholardex_project_view`. **Live: 341 projects, 315 coordinators resolved.**
  - **Reference + consume:** workspace picker on the shared `PROJECT_GRANT_ID` slot (`/api/entities/projects`);
    `ActivityBlockProjector` renders the canonical label in reports; `ActivityReportingService` injects `proj_*`
    (budget/funder/director) at scoring time (decouple-safe). CS `Granturi` reconciled to the shared `Grant Cercetare`
    activity (Option A); `Grant Cercetare` declares `PROJECT_GRANT_ID` (seed + runtime runner).
  - **Trusted budget (CORDIS/admin):** `UserDefinedProjectFact` (precious — never wiped) + admin CRUD + **live CORDIS
    XML fetch** (`/api/admin/projects/cordis/{id}`, public no-auth, auto-suggests UVT `ecContribution`; WebClient buffer
    raised to 16MB for large projects). **Live: 17/19 UVT CORDIS budgets entered (€2.3M); merged → 350 canonical.**
    CS `Info_D_v` opted into `proj_budget` with declared fallback (the only `Buget` consumer; A10/FEAA inherit the form
    when authored).
  - **Decisions settled:** budget = declared-only from brainmap (CORDIS/admin is the trusted source); lighter
    canonical+projection (no event stage); brainmap = offline credential-safe dump (no live dep).
  - **Spun off → `H78`:** researcher-facing import / search / link + director→researcher attribution.
  - **Carry-forward (config/ops, not code):** enter the remaining EU budgets as CORDIS data updates; physics A10 +
    FEAA budget indicators authored in the `proj_budget` form when H65 ships; `currency normalization + monetary
    eligibility thresholds` (RON↔EUR, grant ≥X) still recur across chimie/geografie/FSGC/drept/FSP/sport/FEAA →
    tracked in `H68`/per-report tasks.

## H63 OpenAlex enrichment — corresponding-author scoring surface (archived 2026-06-26)

Archived from `TASKS.md` on 2026-06-26 — the cross-cutting scoring-surface enabler is done + live-verified
(suite 2467/2467). Closed task doc: `docs/tasks/closed/h63-openalex-enrichment.md`. Deferred items tracked
separately (see below).

- [x] `H63` OpenAlex enrichment (corresponding author + ORCID). *(core completed 2026-06-26)*
  Goal: obtain corresponding-author identity (and ORCID / last-author) from OpenAlex and expose it to scoring —
  driver: physics `P = prim autor sau autor corespondent`; cross-cutting (chimie/biologie/geografie/fizica/FSP/sport).
  Outcome:
  - **Data layer — DONE via H73:** the bulk OpenAlex import captured `is_corresponding`; the canonical derivation
    writes `corresponding=true` authorship edges (`scholardex.authorship_facts.corresponding`, id-based to canonical
    authors). Coverage: 84,679 edges across 73,141 pubs (66.2% of OpenAlex-authored pubs).
  - **Scoring-surface enabler — DONE 2026-06-26 (for all papers):**
    - Expose: `ScholardexPublicationView.correspondingAuthorIds` denormalized from the edges by
      `ScholardexProjectionBuilderService.applyCorrespondingAuthors` (full build + batch refresh); persisted as
      `corresponding_author_ids TEXT[]` (Flyway `V18`) + read in both Postgres read ports (mirrors H67 citation split).
    - Role: `AuthorRole.FIRST_OR_CORRESPONDING` + `ReportingComputationSupport.calculatePublicationScore` branch
      (first author OR corresponding; empty corresponding → first-author fallback); codec
      `PUBLICATIONS_FIRST_OR_CORRESPONDING`; admin output type.
    - Live-verified: projection rebuild repopulated 73,141 pubs; maths provisional report — every person's
      `FIRST_OR_CORRESPONDING` ≥ `MAIN`, 7/13 gained corresponding-non-first pubs (Bogdan Sasu 19→32, Vizman 18→26).
  - **Deferred / separate:** physics **P** consumer (wire P to `FIRST_OR_CORRESPONDING`) → **H65**; the **last-author**
    role (biologie/FSP/sport; `author_position` captured in the source fact, not yet in canonical) → separate
    sub-thread; the **incremental DOI-keyed enrichment fetcher** for newly-added pubs (bulk corpus already enriched).

## H67 h-index (Hirsch) computation (archived 2026-06-26)

Archived from `TASKS.md` on 2026-06-26 — functionally complete + indicative-display ready, tested (suite
2466/2466), live-verified. Closed task doc: `docs/tasks/closed/h67-h-index.md`. Will be consumed by **H65**
(physics report `h` column).

- [x] `H67` h-index (Hirsch) computation. *(completed 2026-06-26)*
  Goal: compute the candidate's Hirsch index from our citation data + expose it as a scoring input (nothing computed
  it before). Method (validated 2026-06-22): source-attributed h by attributing each incoming citation to the citing
  paper's forum indexing (Scopus/WoS venue) — labeled **indicative** (accuracy tracks corpus completeness). Outcome
  by slice:
  - **S1 — DONE** (`7863b12`, Flyway `V15`): per-pub citation source-split (`graph/scopus/wos_citation_count` on
    `scholardex_publication_view`, `applyCitationSourceSplit`); live-verified totals.
  - **S2 — DONE** (`34e86c2`): shared `HIndexCalculator` (4 sources) + `HIndexBreakdown`; publications page shows
    H-Index / Scopus h / WoS h (indicative).
  - **S3 — DONE**: self-citation exclusion + total-citation-count via the graph-walk path.
  - **S4a/S4b mechanism — DONE** (`9b1b3bb` + the 2026-06-26 completion): `IndicatorKind.HIndex(source, excludeSelf)`
    + `ScoringStrategy.HIRSCH` + legacy `HINDEX_*` round-trip; `Indicator.isHIndexOutput()`; the detail branch
    computes h (fast S1 columns; `hIndexFromGraph` for WoS year-true-Core + excludeSelf). **Final gap closed
    2026-06-26:** `scoreReport` (report roll-up, shared with the H77 provisional path) + `buildIndicatorApplyView`
    (preview) now branch on `isHIndexOutput()` via a shared `computeHIndex` — h scores everywhere instead of 0.
    Live-verified on the maths department (HIndex `IndicatorKind` deserializes from Mongo cleanly; per-person h —
    Bogdan Sasu 21 / Adina 20 / Blaga 18 / …).
  - **Deferred (intentional):** activation (create `HINDEX_*` indicators on chimie/geografie/fizica reports via the
    admin form — a data action) and the **S4b hard ≥13/9 pass/fail gate** (computed WoS-Core h undercounts official
    per the H76/coverage gap; the istorie "h OR citations" gate needs H68). H65 (physics) will consume the `h` column.

## H77 Admin provisional scoring from declared source ids (archived 2026-06-26)

Archived from `TASKS.md` on 2026-06-26 — all four slices done, tested (suite 2464/2464), and live-verified.
Closed task doc: `docs/tasks/closed/h77-admin-provisional-scoring-declared-ids.md`. Memory:
non-finite-score-ris-projection.

- [x] `H77` Admin provisional scoring from declared source ids (unvalidated authorship). *(completed 2026-06-26)*
  Goal: let an **admin** run a **read-only** report that scores a researcher / a whole department from their
  **declared identity** (canonical authors resolved from the org roster by name + declared Scopus ids) even before
  the researcher validates their canonical author — clearly labelled provisional, never writing confirm/reject
  decisions. Outcome by slice:
  - **S1 — DONE.** `ProvisionalAuthorResolutionService`: roster (`department_affiliations`) → canonical author by
    diacritic-insensitive full-name match on `alternativeNames` + scopus-presence homonym disambiguation; dry-run
    endpoint `POST /admin/initialization/provisional/resolveDepartment`. Live: Matematică 13/15 RESOLVED.
  - **S2 — DONE.** `UserReportFacade.computeProvisionalReport(authorIds, reportId, refYear)` via an extracted
    `AuthorshipContext` + shared `scoreReport` loop; CONFIRMED (self) and DECLARED (provisional) converge — the
    resolved author ids drive both the publication set (`findAllPublicationsByAuthorsIn`, no
    `PublicationAuthorshipDecision`) AND self-citation/author-share. Score-neutral for the CONFIRMED path.
  - **S3 — DONE.** `Source.ADMIN_PROVISIONAL` + persisted `provisional` flag on `UserIndividualReportRun`;
    `buildAndSaveProvisionalRun`; `ProvisionalDepartmentReportService.run` (batch; AMBIGUOUS/UNRESOLVED flagged, not
    dropped).
  - **S4 — DONE.** `AdminProvisionalReportController` + `admin/provisional-report.html` (`/admin/provisional-report`,
    gated by `/admin/**` → `PLATFORM_ADMIN`): form → results table, provisional banner + per-person status.
  - **Live (Departamentul de Matematică × FV Matematică):** 13/15 scored; the H71 de-merge flowed through (Adina
    Sasu 568.89 / Bogdan Sasu 516.70, distinct); Casu/Comănescu flagged UNRESOLVED. Also exercised the H60 relative
    year specs end-to-end (`PreviousNYears(7)` window correctly dropped Birtea's 33 pre-2020 pubs of 42).
  - **Surfaced (spawned separately):** a RIS forum metric projected as `Infinity` poisoned indicator totals (∞);
    guarded at `ScientificProductionService.getScore` (non-finite per-pub score → 0); RIS-projection root cause is a
    separate task. **Deferred:** diacritic-surname resolution (unaccented name index), researcher-view provisional
    badge (admin-only surface), WoS/Google-Scholar declared-id resolution.

## H69 Scoring rework for the multi-source canonical layer (archived 2026-06-25)

Archived from `TASKS.md` on 2026-06-25 — all six threads resolved. Closed task doc:
`docs/tasks/closed/h69-thread2-citation-source-selector.md`. Memories: predatory-venue-gate,
reporting-lookup-primary-delegator, conference-dblp-core-resolution, openalex-venue-source-type.

- [x] `H69` Scoring rework for the multi-source canonical layer (after H66B Phase 4). *(completed 2026-06-25)*
  Goal was to rework scoring to fully consume the multi-source canonical signals (OpenAlex citation graph + ISSN
  venue identity; DBLP `conf/X` conference identity). Outcome by thread:
  - **(1) dispatch/routing — DONE.** Single-scorer dispatch confirmed + double-counts closed: the CS router
    dispatches by primary forum type (journals + conferences only, books → CS_SENSE); a `cp` in a Journal forum no
    longer also floors at conference D; LNCS/Springer-978 chapters excluded from the book scorer (only LNCS-*named*
    `ch` are conference candidates); Euro-Par et al. resolve via the local DBLP dump sweep; `cp` in an
    untyped/unknown forum scores 0. Commits `d580144`…`2e6fede`.
  - **(2) citation-driven criteria — SATISFIED (graph) + selector DEFERRED.** Both citation sites
    (`ReportScopedIndicatorScoringSupport.computeCitationView` score path + `CitationRowProjector` display path)
    already walk the in-corpus citation graph (`citation_facts` = OpenAlex `cited_by` ∪ Scopus) and score each
    citing pub — they never used `citedByCount`. The h-index consumes the per-source counts
    (`graph/scopus/wos_citation_count`). The only unbuilt clause, **"pick the citation source per domain"** (a
    `CitationSource` selector mirroring `HIndexSource` on the `Citations` kind), was deferred: no domain standard
    needs source-specific citation **counts** today, and source-attributed counts carry the same indicative
    undercount as the source h (gated on `H76` + corpus completeness, bundle with H67 S4b). Scope + design:
    `docs/tasks/closed/h69-thread2-citation-source-selector.md`.
  - **(3) forum dedup impact — DONE.** `ForumReconcileService` → `ScholardexForumBuilder.buildScopusForums` →
    `ScholardexForumDeduplicationService` merges ISSN/erihId clusters (safe-merge per H55), folding DBLP-minted and
    Scopus forums for the same conference so both score identically.
  - **(4) regression sweep — SWEPT CLEAN.** Full suite green across every domain scorer + the CS frozen-baseline
    parity test; shared-infra changes verified score-neutral. Also fixed one pre-existing `@WebMvcTest` mock gap
    (`RankingViewSecurityContractTest`, commit `6159896`).
  - **(5) aggregate (non-additive) HIndex indicator — DONE (= H67 S4a).** `IndicatorKind.HIndex` +
    `ScoringStrategy.HIRSCH` + persisted round-trip + HIRSCH reduce branched in
    `buildReportScopedIndicatorDetail`/`hIndexExcludingSelf` + admin form. Per-domain threshold activation = H67 S4b
    (still open under H67).
  - **(6) year/category-scoped WoS ranking reads — DONE.** Scaling-critical scoped read shipped under H67
    (`findForumCoreCollectionYears` driving the Hirsch year-true Core classification); this arc added
    `ReportingLookupPort.getForumRankings(forum, years, categories)` (Postgres pushes `year IN`/`category IN` into
    SQL, memoized, ISSN/name fallback, delegated through the `@Primary` facade) and migrated AIS/IF/RIS/Economics/
    CS-journal scoring onto it, keeping in-memory guards so scores are identical (full suite 2428/2428). CNFIS left
    on the full read (its `min(year, maxAvailableYear)` probe makes SQL year-scoping unsafe). Edition policy +
    "in Core when?" remain H67 S4b.
  - **Also shipped (CS scoring hardening, read-side, beyond the threads):** ESCI/AHCI journals recognized
    (no-JIF-quartile WoS editions floor at C as `SCOPUS+ESCI`/`AHCI`, year-true + carry-forward — fixed the
    `ReportingLookupFacade` delegation gap); SENSE book scale pinned to the standard (perspective d.i: A=16…) +
    anchored whole-word publisher matching; publication-year display; Scopus stray-colon DOI dedup; predatory-venue
    gate (WSEAS/IAENG/DAAAM + Beall's exact-match + allowlist, `data/predatory/`); CORE/SENSE quartile sentinels.
  - **Residual (NOT in H69, tracked elsewhere):** the citation-source selector (deferred, above); H67 S4b per-domain
    h/threshold activation; `H76` WoS CPCI onboarding (h/citation WoS accuracy depends on it).

## H71 + H62 (archived 2026-06-25)

Archived from `TASKS.md` on 2026-06-25 after a backlog-vs-implementation audit. Closed task docs:
`docs/tasks/closed/h71-cross-source-author-reconcile.md`, `h62-feaa-economics-report-export.md`.

- [x] `H71` Cross-source author dedup (fuzzy/over-split + cross-source name reconcile folded into V2). *(completed 2026-06-22, archived 2026-06-25)*
  Deliverable: the deferred V2 author reconcile is now built and wired. STRONG-tier rule (same surname +
  name-compatible given names + ≥1 shared affiliation + same-paper hard-block + adaptive co-author floor, split at
  block size ~40) lives in `CanonicalGraphBuilder.applyAuthorReconcile`, invoked from
  `CanonicalDerivationV2Service.writeAuthors` (enabled by default `core.canon.author-reconcile.enabled=true`,
  `dry-run=false`) inside `runFull`/`rebuildCanonicalV2`. Cannot-link guard prevents transitive chain merges.
  Tests: `AuthorReconcileV2Test` (merge, hard-block, chain-breaking, diacritics/initials, determinism). Live
  (2026-06-22): ~1,909 authors absorbed, 0 post-merge invariant violations, 13 cannot-link conflicts avoided, max
  component 7 (no runaway). Spot-checks: Daniel Vizman (6 OpenAlex ids folded), Călin Tatu correctly split.
  Residual (deferred, not blocking): S3 optional precision refinements per the plan doc.

- [x] `H62` FEAA Economics report — full-fišă DOCX export (bound to `6849fb3d97a94f22948f9430`, `feaa-2024`). *(archived 2026-06-25)*
  All three slices shipped: (1) articles (M/N/AIS/Pi) + citations + P/C/S=P+C summary with all-position thresholds
  (`Feaa2024ReportTypeImportSupport`, `feaa-2024/template.docx` + `binding.json`); (2) books/chapters tier scoring
  (`FeaaBookScoringService` coefficients 0.5/0.25/0.2/0.1 × `FeaaAnexa1PublisherService` allowlist from
  `report-data/feaa-anexa1-publishers.csv`, slots 7–10); (3) Core/Infoeconomics article count (M≥8, AIS>0 →
  `feaa-core-count`).

## H72-H75 OpenAlex-first ingestion + canonical engine V2

Archived from `TASKS.md` on 2026-06-22. The ingestion + canonical-derivation cluster converged: the V2 batch engine
(H75) became the real pipeline and absorbed/superseded H74 and the per-record canon of H73/H72. Closed task docs:
`docs/tasks/closed/h73-openalex-first-ingestion.md`, `h75-canonical-derivation-engine-v2.md`, `h75-rules-catalog.md`,
`h72-scopus-verified-entity-resolution.md`.

- [x] `H75` Canonical derivation engine V2 (batch ETL). *(completed 2026-06-22)*
  Deliverable: pure in-memory Load→Build→Write canonical derivation (`CanonicalGraphBuilder` +
  `CanonicalDerivationV2Service`) replacing the per-record V1 canon block in `runFull`/`rebuildAllDerived`. Builds
  affiliations (ROR backbone + 3-tier afid resolve), DOI-keyed pubs (field precedence + Decision-0 container-DOI
  blocklist), union-find authors (OpenAlex-keyed identity: ORCID > OpenAlex-id > Scopus, + positional bridge), and all
  edges (authorship, author/pub→author→affiliation, internal citations) as bulk `insertMany`.
  Evidence: full from-scratch `rebuildAllDerived` ~33 min (V2 canon ~4 min vs V1 ~90 min, ~19x) with projections clean
  on V2 output; deterministic counts (149,902 pubs / 371,196 authors / 512,200 citations / 1,284,984 authorships).
  Spot-checks: Marc Frîncu = one OpenAlex-keyed author (ORCID + Scopus AU-ID); UVT ROR-keyed with afid 60000434.
  Follow-ups shipped this arc: (a) **skip-smart `rebuildAllDerived`** — derive-only when source facts present
  (~11.5 min, no re-ingest), `reingest=true` forces full; (b) **OpenAlex venue→forum onboarding** restored in V2
  (forumId coverage 89,809→133,129); (c) projection-write perf Tier 1+2 (reWriteBatchedInserts + index drop/recreate,
  177→140s; the inserts are the wall, COPY/Tier-3 documented but not pursued).
  Residual (NOT done): delete the V1 canon services — blocked, the Tier-2 incremental path still uses them; and the
  fuzzy/over-split author reconcile is deferred to `H71`.

- [x] `H73` OpenAlex-first ingestion (UVT 1-hop corpus + ROR affiliation backbone). *(completed 2026-06-22, absorbed by H75)*
  Shipped: replayable OpenAlex bulk importer (works 11,656 + citers 105,766 → `openalex.*` source facts +
  `openalex.institution_facts`), ROR affiliation backbone (~21–24k referenced institutions, ROR-keyed `@Id`), Scopus
  afids resolving INTO the backbone via the 3-tier alias matcher (`ScopusAffiliationRorMatcher`), positional bridge
  retired, pub→author→affiliation + author→affiliation edges, internal UVT↔UVT citation edges. The canonical
  derivation of all of this is now owned by the V2 engine (H75). Residual note: malformed-DOI normalize cleanup.

- [x] `H74` Pipeline reorder (forums → institutions+affiliations → pubs+authors). *(completed 2026-06-22, absorbed by H75)*
  Fully realized as the V2 engine's in-memory build order; no separate work remained.

- [x] `H72` Scopus verified-tier entity resolution. *(completed 2026-06-21; mechanism superseded by H73/H75)*
  Slices 1–3 shipped + live-validated (affiliations 29,106→16,427; 405 over-split authors merged; UVT ROR-tagged).
  The noisy positional ROR bridge was replaced by H73's alias matcher; verified-tier affiliation resolution now lives
  in the V2 affiliation build.

## H70 Researcher Onboarding Wizard

Archived from `TASKS.md` on 2026-06-22 (shipped 2026-06-20, was left open). Closed task doc:
`docs/tasks/closed/h70-researcher-onboarding-wizard.md`.

- [x] `H70` Researcher onboarding wizard (+ de-tangle the publication-claim tool). *(completed 2026-06-20)*
  All 5 slices shipped + live-validated on a real researcher (ORCID→OpenAlex enrich, author match 18 pubs, auto-claim
  recommends all 18): S1 model/resolve/step-engine, S2 wizard shell + steps 1–3, S3 author-record matcher with
  confidence preview, S4 publication auto-claim, S5 claim-tool de-tangle (bulk idempotency + 409 `requiresOnboarding`
  routing to the wizard). Deferred: bug #1 (decision continuity across re-sync keyed on stable doi/eid) — largely
  mitigated by DOI-primary identity; pick up as a focused follow-up if it recurs.

## H52 Indicator / Scoring / Formula Flow V1

Archived from `TASKS.md` on 2026-06-04 after completing H52 v1. Closed task doc: `docs/tasks/closed/h52-indicator-scoring-v1.md`.

- [x] `H52` Indicator / scoring / formula flow — v1. *(completed 2026-06-04)*
  Goal: replace the legacy output-type/strategy cross-product, open-bag score extras, hot-path string MVEL evaluation, strategy dispatch ladder, and hardcoded year caps with typed indicator/scoring/formula infrastructure.
  Deliverable: typed `IndicatorKind`, `YearRangeSpec`, `ScoreYearRangeSpec`, and `Selector` value types; compile-cached and sandboxed formula evaluation with formula-hash cache identity; strategy registry; typed score multiplier path; indicator-save variable contract; migration and replay-shape guardrails.
  Exit criteria: v1 typed indicator shape live in Mongo, legacy indicator keys unset from persisted docs, hot-path `MVEL.eval(string, ...)` removed, `LAST_YEAR` removed from runtime scoring, undeclared formula variables rejected on save, and replay-shape guard green.
  Handover:
  - H52 is complete through Commit 4; task doc records live Mongo verification, backups, migration runner commands, final invariants, and the admin indicator form DTO follow-up.
  - Replay equality was intentionally shipped as a replay-shape gate rather than full numeric replay because full numeric replay depends on unstable upstream WoS/Scopus data.
  - Commit 4 UI surfaces now bind admin indicator create/edit through `AdminViewController.IndicatorForm`; `Indicator` legacy compat setters and `pending*` machinery are deleted.
  - `ComputerScienceConferenceScoringService` internals remain a separate follow-up; it already conforms to the new `ScoringService` interface.

## H53 Import Conflict Triage Policy And Deterministic Relink Handling

Archived from `TASKS.md` on 2026-05-30 after completing H53.1-H53.10. Closed task doc: `docs/tasks/closed/h53-import-conflict-triage.md`.

- [x] `H53` Import conflict triage policy and deterministic relink handling. *(completed 2026-05-30)*
  Goal: reduce future import conflict noise by reserving human review for ambiguous canonical identity matches while handling source-link relinks, edge evidence, and WoS fact conflicts deterministically.
  Deliverable: source-precedence policy for true identity/source links; accumulator-style handling for authorship, affiliation, publication-author-affiliation, and citation edges; per-run aggregate import metrics; admin conflict UI split into `Needs Review` and `Audit Only`; dirty-projection marking with quick rebuild action.
  Exit criteria: lower-precedence identity relinks skip without review; higher-precedence or newer equal-precedence identity relinks apply automatically and mark affected projections dirty; only ambiguous multi-candidate identity cases enter human triage; zero-candidate misses are counted only in import metrics; WoS fact conflicts never enter human triage.
  Handover:
  - Added shared source precedence handling for `SCOPUS < SCOPUS_JSON_BOOTSTRAP < SCOPUS_PYTHON_AUTHOR_WORKS = SCOPUS_PYTHON_CITATIONS_PUBLICATION`.
  - Deterministic identity relinks now auto-apply or skip by precedence/timestamp, emit aggregate import-run metrics, and mark affected projections dirty.
  - Edge source-link evidence now behaves as accumulator-style audit/metric noise rather than manual review work.
  - Needs Review is scoped to ambiguous multi-candidate identity conflicts; Audit Only summarizes deterministic metrics and legacy audit rows.
  - WoS fact winner decisions emit aggregate audit metrics and stay out of human triage.
  - Added retryable dirty-projection rebuild and idempotent legacy deterministic-conflict cleanup actions in the admin conflict surface.

## H49 Test Quality Remediation

Archived from `TASKS.md` on 2026-04-30 after full `H49` closeout.

- [x] `H49` Test quality remediation — PIT mutation gaps in `service` + `handlers`. *(completed 2026-04-30)*
  Goal: lift mutation coverage and test strength across `ro.uvt.pokedex.core.service.*` and `.handlers.*` by closing assertion gaps where line coverage already exists and adding tests where coverage is missing entirely.
  Baseline (PIT 1.20.4, 153 classes, 2026-04-28): line 67% (12230/18134), **mutation 31%** (3224/10259), **test strength 47%** (3224/6836). Per-package numbers in each subtask below.
  Reference: `docs/test-quality.md`; reports under `build/reports/pitest/` and `build/reports/jacoco/test/`.
  Tooling unblockers (apply once before subtask work to make the measurement reliable): bump `jacoco.toolVersion` to a Java 25-compatible release so branch coverage stops reporting 0% on Java 25 bytecode; add the Arcmutate Spring plugin to the `pitest` config to remove Spring-pattern equivalent-mutant noise.
  Exit criteria: each subtask records a per-package baseline and post-remediation mutation score; non-trivial packages reach at least 60% mutation coverage with at least 65% test strength; remaining surviving mutants are explicitly classified as equivalent or out-of-scope in the subtask handoff; JaCoCo branch report parses cleanly under Java 25 and the Arcmutate Spring plugin is wired into PIT runs.
  Note: `service.importing.model` (67% mutation), `service.integration` (3 mutations, 67%), and `service.model` (8 lines, suspected dead code) are intentionally excluded from this initiative; `service.model` should be triaged for deletion under a separate cleanup if confirmed unused.

  Subtasks:

  - [x] `H49.1` **`ro.uvt.pokedex.core.handlers`** — coverage gap (3 classes). *(completed 2026-04-28)*
    Baseline: line 12% (3/25), mutation 14% (1/7), test strength 100% (1/1).
    Scope hint: minimal tests today; the few assertions that exist are tight. This is a coverage gap, not an assertion gap — adding happy-path and error-path tests for `ApiAccessDeniedHandler`, `ApiAuthenticationEntryPoint`, and `CustomAccessDeniedHandler` should move all three numbers fast.
    Handover:
    - Added direct handler tests for `ApiAccessDeniedHandler` and `ApiAuthenticationEntryPoint`, asserting status, JSON content type, envelope fields, request path, and parseable timestamps.
    - `CustomAccessDeniedHandler` was already covered and unchanged.
    - Added PIT property overrides in `build.gradle` so package slices can be verified with `-PpitTargetClasses=...` and `-PpitTargetTests=...` while preserving the default H49 scope.
    - Focused verification passed: `./gradlew test --tests '*handlers*Test' -q`.
    - Targeted PIT verification passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.handlers.*' -PpitTargetTests='ro.uvt.pokedex.core.handlers.*' -q`.
    - Post-remediation result: line 100% (25/25), mutation 100% (7/7), test strength 100% (7/7); no surviving handler mutants.

  - [x] `H49.2` **`ro.uvt.pokedex.core.service`** — top-level services (3 classes). *(completed 2026-04-28)*
    Baseline: line 28% (55/194), mutation 18% (21/114), test strength 72% (21/29).
    Scope hint: `CacheService`, `CustomUserDetailsService`, `UserService`. Where tests exist they're strong (72% test strength); the gap is breadth. Add tests covering the untested code paths in each service rather than strengthening existing ones.
    Handover:
    - Added direct unit coverage for `CustomUserDetailsService` success/missing-user behavior.
    - Added `UserService` tests for user CRUD helpers, role parsing/validation, password-encoding create flow, lock/delete role updates, researcher-profile save/update/delete, missing-user errors, and normalized author-name matching.
    - Expanded `CacheServiceTest` for cache misses, normalized-title lookup guards, duplicate conference-title indexing, mutable forum/author/affiliation caches, clear/save flush operations, and university author-id resolution from group members.
    - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.CacheServiceTest' --tests 'ro.uvt.pokedex.core.service.CustomUserDetailsServiceTest' --tests 'ro.uvt.pokedex.core.service.UserServiceTest' -q`.
    - Targeted PIT verification passed with explicit classes/tests: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.CacheService,ro.uvt.pokedex.core.service.CustomUserDetailsService,ro.uvt.pokedex.core.service.UserService' -PpitTargetTests='ro.uvt.pokedex.core.service.CacheServiceTest,ro.uvt.pokedex.core.service.CustomUserDetailsServiceTest,ro.uvt.pokedex.core.service.UserServiceTest' -q`.
    - Post-remediation result: line 94% (183/194), mutation 93% (106/114), test strength 94% (106/113).
    - Per-class PIT result: `CacheService` line 89% (87/98), mutation 89% (40/45), test strength 89% (40/45); `CustomUserDetailsService` line/mutation/test strength 100%; `UserService` line 100% (93/93), mutation 96% (64/67), test strength 97% (64/66).
    - Remaining mutants are low-value edge cases: `CacheService` guards for impossible/private null or blank normalized-title keys and private conference-ranking-key fallback behavior; `UserService` role-removal equivalent behavior where desired roles are re-added, plus empty-name matching edge behavior. No production changes were needed.

  - [x] `H49.3` **`ro.uvt.pokedex.core.service.scopus`** — worst test-strength in the report (2 classes). *(completed 2026-04-28)*
    Baseline: line 63% (280/443), mutation 16% (39/237), test strength 30% (39/132).
    Scope hint: tests run a lot of code without verifying outcomes. Open the package report, list surviving mutants per class, and add output-level assertions (return values, side-effect verification, exception messages). This is where the highest payoff per assertion lives.
    Assessment: `SchedulerCorrelationSupport` is already near threshold; `ScopusUpdateScheduler` is the real blocker. The scheduler mixes queue polling, retry policy, request planning, citation-date computation, Python client calls, ingestion orchestration, exception mapping, metrics, and MDC in one large component. Prefer extracting package-private collaborators with direct tests over adding more reflection-heavy tests to the monolith.

    Sub-slices:

    - [x] `H49.3a` **Correlation support closeout.** *(completed 2026-04-28)*
      Scope: add the missing `SchedulerCorrelationSupport` assertions for blank/null normalization and restoring pre-existing MDC values.
      Exit criteria: correlation support reaches or exceeds the H49 mutation/test-strength threshold; any remaining mutants are classified.
      Handover:
      - Added direct assertions for null/blank/whitespace normalization to `unknown` or trimmed MDC values.
      - Added direct assertions that pre-existing MDC values are restored after the scheduler context closes.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest' -q`.
      - Targeted PIT verification passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupport' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest' -q`.
      - Post-remediation result: line 90% (19/21), mutation 90% (9/10), test strength 90% (9/10).
      - Remaining survivor: `restoreOrRemove` equality-check mutation is low-value/equivalent for observable MDC restoration behavior; covered contexts either restore the previous value or remove the absent key.

    - [x] `H49.3b` **Retry/failure policy extraction.** *(completed 2026-04-28)*
      Scope: extract package-private retry readiness/backoff/failure-decision logic from `ScopusUpdateScheduler` into a directly testable collaborator.
      Candidate surface: `isReadyForAttempt`, `computeBackoffSeconds`, max-attempt fallback, retryable-vs-terminal status/message/next-attempt behavior for publication and citation tasks.
      Exit criteria: retry/backoff/failure paths are covered without reflection; publication and citation failure handling mutants are mostly killed.
      Handover:
      - Extracted package-private `ScopusSchedulerRetryPolicy` for attempt readiness, exponential backoff, max-attempt fallback, and retryable-vs-terminal failure decisions.
      - Wired `ScopusUpdateScheduler` publication/citation readiness and failure handling through the policy while keeping scheduler-owned persistence, execution-date stamping, and logging in the scheduler.
      - Added direct `ScopusSchedulerRetryPolicyTest` coverage for missing/past/future/malformed retry timestamps, backoff progression/capping, retry scheduling, terminal failures, error-code/message propagation, and default max-attempt fallback.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted policy passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicy' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest' -q`.
      - Policy PIT result: line 100% (22/22), mutation 94% (17/18), test strength 94% (17/18). Remaining survivor is low-value/equivalent around blank `nextAttemptAt`: mutating the blank guard still falls through to parse failure and returns ready.
      - H49.3 scoped PIT after H49.3a-b: line 66% (301/456), mutation 24% (55/233), test strength 38% (55/146). Remaining package gap is still concentrated in `ScopusUpdateScheduler`; continue with H49.3c+ extractions.

    - [x] `H49.3c` **Integration exception mapping extraction.** *(completed 2026-04-28)*
      Scope: extract exception mapping from `ScopusUpdateScheduler` into a directly testable collaborator.
      Candidate surface: existing `IntegrationException`, HTTP 4xx/5xx, request failures, buffer limit, decoding failures, generic nested runtime exceptions, root-cause traversal guard.
      Exit criteria: mapping behavior is covered by direct tests and no longer depends on scheduler-private reflection.
      Handover:
      - Extracted package-private `ScopusIntegrationExceptionMapper` for scheduler runtime wrapping and Python integration exception classification.
      - Wired `ScopusUpdateScheduler` Python error paths and task failure handling through the mapper; removed scheduler-private exception-mapping helpers.
      - Added direct mapper tests for already-mapped exceptions, unexpected runtime persistence fallback, HTTP 500/400 classification, request failures, buffer limit, decoding failures, generic nested failures, blank root-cause details, and the finite cause traversal guard.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted mapper passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapper' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest' -q`.
      - Mapper PIT result: line 100% (36/36), mutation 97% (31/32), test strength 97% (31/32). Remaining survivor is low-value around the finite traversal guard boundary.
      - H49.3 scoped PIT after H49.3a-c: line 70% (321/458), mutation 33% (78/233), test strength 48% (78/163). Remaining package gap is still concentrated in `ScopusUpdateScheduler`; continue with publication/citation planning extractions.

    - [x] `H49.3d` **Publication sync planning extraction.** *(completed 2026-04-28)*
      Scope: extract publication date/request planning from `ScopusUpdateScheduler`.
      Candidate surface: `resolveFromDate`, `computeFromDate`, `parseCoverDate`, `buildRequest`, FULL/PERIOD/SINCE_LAST_UPDATE behavior, page-size/cursor/enrichment/format fields.
      Exit criteria: publication request/date mutants are covered through direct planner tests; scheduler tests only verify orchestration.
      Handover:
      - Extracted package-private `ScopusPublicationSyncPlanner` for publication `fromDate` resolution, cover-date parsing, fallback date calculation, and author-works request construction.
      - Wired `ScopusUpdateScheduler` publication task orchestration through the planner; scheduler now fetches author publications once for publication planning and keeps ingestion/persistence/canonical rebuild orchestration.
      - Removed the scheduler reflection test for `computeFromDate`; date/request behavior is covered by direct planner tests.
      - Removed duplicate request setters for `include_enrichment` and `format` because `AuthorWorksRequest` already defaults them, reducing equivalent PIT noise without changing the outgoing request.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted planner passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlanner' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest' -q`.
      - Planner PIT result: line 94% (33/35), mutation 95% (21/22), test strength 95% (21/22). Remaining survivor is low-value/equivalent around the null/blank cover-date guard; removing the guard still returns `Optional.empty()` via parse failure/catch for observable inputs.
      - H49.3 scoped PIT after H49.3a-d: line 72% (334/464), mutation 40% (93/231), test strength 55% (93/168). Remaining package gap is still concentrated in citation planning and scheduler orchestration; continue with H49.3e.

    - [x] `H49.3e` **Citation sync planning extraction.** *(completed 2026-04-28)*
      Scope: extract citation EID/date/request planning from `ScopusUpdateScheduler`.
      Candidate surface: `computeEidLastCitationDatesForAuthor`, FULL/PERIOD/default citation modes, missing author publications, missing cited/citing publication rows, malformed/blank cover dates, latest citing-date selection, citation request fields.
      Exit criteria: citation planning mutants are covered through direct planner tests; edge cases are explicit and no longer buried in scheduler integration tests.
      Handover:
      - Extracted package-private `ScopusCitationSyncPlanner` for cited/citing publication ID selection, per-EID last citation date calculation, FULL/PERIOD/default mode transformation, and `CitationsByEidRequest` construction.
      - Wired `ScopusUpdateScheduler` citation task orchestration through the planner; scheduler now owns projection reads, task persistence, Python calls, ingestion, and canonical rebuild orchestration only.
      - Fixed a production edge case in PERIOD citation mode: author publications with no previous citation date now cap to the period start instead of attempting `null.compareTo(...)`.
      - Removed a duplicate `includeEnrichment` setter because `CitationsByEidRequest` already defaults it to `true`, reducing equivalent PIT noise without changing the outgoing request.
      - Added direct planner tests for author publication ID selection, distinct citing ID selection, no-publication handling, latest valid citing date selection, missing cited/citing rows, blank/malformed dates, FULL/PERIOD/default mode behavior, and request defaults.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted planner passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlanner' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest' -q`.
      - Citation planner PIT result: line 100% (62/62), mutation 86% (25/29), test strength 86% (25/29). Remaining survivors are low-value/equivalent around empty input behavior, null-EID guard behavior, and PERIOD boundary equality where the output string is unchanged.
      - H49.3 scoped PIT after H49.3a-e: line 79% (372/472), mutation 49% (116/238), test strength 61% (116/189). Remaining package gap is concentrated in scheduler orchestration side effects; continue with H49.3f.

    - [x] `H49.3f` **Scheduler orchestration assertions.** *(completed 2026-04-28)*
      Scope: after extractions, tighten `ScopusUpdateSchedulerTest` around observable orchestration only.
      Candidate surface: pending queue counts, future-attempt skips, saved task snapshots for publication/citation success, no-publications citation completion, canonical rebuild calls, failure counters, and MDC context closure.
      Exit criteria: package-scoped PIT for `ro.uvt.pokedex.core.service.scopus` reaches at least 60% mutation coverage and 65% test strength, with remaining survivors classified in the H49.3 handover.
      Handover:
      - Added scheduler orchestration assertions that snapshot repository-save state at save time, so intermediate `IN_PROGRESS` saves and final task saves are observable despite in-place task mutation.
      - Strengthened publication success assertions for start/final task status, message, execution date, retry metadata reset, canonical rebuild call, external-call success metric, and processed-task success metric.
      - Strengthened citation success assertions for start/final task status, imported publication/link counts, retry metadata reset, canonical rebuild call, external-call success metric, and processed-task success metric.
      - Added no-publications citation completion coverage that verifies no Python call, no ingestion/canonical rebuild, final completion message, default max-attempt fallback, and projection-read short-circuiting.
      - Added publication retry-failure coverage for retryable Python integration errors, including scheduled retry status/message, next-attempt presence, error code/message, failure counters, and no downstream ingestion/canonical rebuild.
      - Added citation terminal-failure coverage for empty Python response, including terminal failure status/message, execution date, cleared next-attempt, error code/message, and failure counters.
      - Post-closeout micro-pass addressed the best-ROI survivors: publication empty-response terminal failure, publication max-attempt fallback, PERIOD end-year filtering, and author-works pagination cursor handling.
      - Medium-ROI pass added citation response edge-case coverage for null/empty citing lists, missing/blank citing EIDs, and valid-item-only ingestion, and removed an unused `text(JsonNode, ...)` helper from the scheduler.
      - Follow-up state-reset pass seeded stale retry/error fields into publication/citation success and citation terminal-failure tests, proving final saves clear stale `nextAttemptAt`, `lastErrorCode`, and `lastErrorMessage` where applicable.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Final H49.3 scoped PIT passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupport,ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicy,ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapper,ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlanner,ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlanner,ro.uvt.pokedex.core.service.scopus.ScopusUpdateScheduler' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest,ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest,ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest,ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest,ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest,ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Final H49.3 result: line 96% (447/465), mutation 85% (195/230), test strength 86% (195/227).
      - Remaining survivors are classified as low-value or equivalent: MDC/context-close/log-duration observability mutants; duplicate/guard fallthroughs in retry/date parsing; planner boundary cases where output is unchanged; and scheduler text-helper null/blank guard mutations that do not justify further monolith-specific tests now that the package exceeds the H49 threshold.

  - [x] `H49.4` **`ro.uvt.pokedex.core.service.importing`** — top-level importing (8 classes). *(completed 2026-04-28)*
    Baseline: line 41% (307/756), mutation 16% (52/316), test strength 40% (52/131).
    Scope hint: both kinds of gap — missing tests and weak assertions. Triage by class size; pick the largest two or three first.
    Handover:
    - Added `GroupServiceTest`: 31 tests covering early-exit, group field propagation, new/existing user handling, position parsing (including CS III/II/I order bug fixed), scopusId filtering, and CSV validation. Fixed `parsePosition` CS order bug and removed dead `memberIds == null` guard. Post-remediation: line 98%, mutation 91% (52/57), test strength 91%.
    - Added `CoreConferenceRankingServiceTest`: ~22 tests covering all supported years (2008–2023), rank parsing, existing conference update, malformed row, unreadable file, async delegation. Post-remediation: line 100%, mutation 81% (44/54), test strength 81%.
    - Added `URAPRankingServiceTest`: 16 tests using real XSSFWorkbook files covering early return, file filter, score fields, year extraction, existing university, malformed rank, null cells, string numeric cells, directory filtering, unreadable file, missing cell, numeric cell. Post-remediation: line 98%, mutation 81% (30/37), test strength 81%.
    - Added `ScopusDataServiceTest` extensions: `loadScopusDataIfEmptySync`, IO error paths, `createUploadBatchId` normalization, `normalizeOptionalValue` "null"/"n/a" literals, `readInt` string fallback and numeric paths, `readRequiredText` null/blank node cases, `readDataSize` scalar eid, `applyIngestionOutcome` error path, `extractCitationsFromJson` blank eid and NUMBER node paths, citation batch skipped/error count assertions. Post-remediation: line 79%, mutation 51% (68/134), test strength 69%. Remaining survivors: logging-only arithmetic and heartbeat mutations (equivalent), in-loop batch flush path (needs 1000+ citations, impractical), dead `splitSemicolon` method.
    - Added `AdminUserServiceTest`: 3 tests for count>0 skip, email/password on create, role assignment.
    - Added `ArtisticEventsServiceTest`: 2 tests for count>0 skip and happy-path smoke test (file exists in repo).
    - Added `CNCSISServiceTest`: 4 tests for count>0 skip, IO error, valid xlsx, null row skip.
    - Added `SenseRankingServiceTest`: 4 tests for IO error, valid ranking, invalid ranking (no setRanking), null row skip.
    - Full package PIT (all 8 classes): line 90% (679/753), mutation 68% (212/314), test strength 77% (212/275).

  - [x] `H49.5` **`ro.uvt.pokedex.core.service.importing.scopus`** — high coverage masking weak assertions (15 classes). *(completed 2026-04-29)*
    Baseline: line 81% (3127/3872), mutation 33% (705/2149), test strength 40% (705/1754).
    Scope hint: looks well-tested by line coverage but isn't. Focus exclusively on adding assertions to existing tests; very little new test scaffolding needed. Walk the surviving-mutants list class by class.
    Current narrowed slice (10 touched classes, 2026-04-29): line 82% (2895/3547), mutation 45% (867/1914), test strength 55% (867/1583). This is not the full package yet; it reflects the current assertion-strengthening pass around projection building, canonicalization, and fact/materialization flows.

    - [x] `H49.5a` **Projection builder rename + regression carryover.** *(completed 2026-04-29)*
      Scope: preserve and re-anchor projection builder coverage after the `ScopusProjectionBuilderService` -> `ScholardexProjectionBuilderService` rename.
      Handover:
      - Updated production wiring to inject `ScholardexProjectionBuilderService` from canonical materialization and application-layer callers.
      - Renamed the large projection-builder test suite to `ScholardexProjectionBuilderServiceTest` and kept the existing behavioral regression coverage attached to the renamed service.
      - Targeted verification passed as part of the `ro.uvt.pokedex.core.service.importing.scopus.*` test slice.

    - [x] `H49.5b` **Canonicalization assertion strengthening.** *(completed 2026-04-29)*
      Scope: tighten observable assertions in existing canonicalization tests instead of adding new scaffolding.
      Handover:
      - Strengthened `ScholardexAffiliationCanonicalizationServiceTest`, `ScholardexAuthorCanonicalizationServiceTest`, `ScholardexCitationCanonicalizationServiceTest`, and `ScholardexPublicationCanonicalizationServiceTest`.
      - Added stronger assertions around duplicate recovery, normalized fields, alias handling, source-link projection, and valid-edge retention so previously covered but weakly asserted branches now fail on behavioral drift.
      - Targeted verification passed as part of the `ro.uvt.pokedex.core.service.importing.scopus.*` test slice.

    - [x] `H49.5c` **Fact/materialization assertion strengthening.** *(completed 2026-04-29)*
      Scope: tighten facts, ingestion, and canonical materialization tests around saved state rather than broad new fixtures.
      Handover:
      - Strengthened `ScopusFactBuilderServiceTest`, `ScopusImportEventIngestionServiceTest`, `ScopusCanonicalMaterializationServiceTest`, `UserDefinedCanonicalizationServiceTest`, and `UserDefinedFactBuilderServiceTest`.
      - Added concrete assertions for persisted field values, normalized values, source batch/correlation metadata, forum/publication linkage, and downstream rebuild invocation.
      - Targeted verification passed as part of the `ro.uvt.pokedex.core.service.importing.scopus.*` test slice.

    - [x] `H49.5d` **Remaining survivor sweep.** *(completed 2026-04-29)*
      Scope: use the narrowed PIT survivor list to continue class-by-class assertion hardening before running the full package slice.
      Current evidence:
      - Focused tests passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.*' -q`.
      - Narrowed scoped PIT passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedFactBuilderService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionServiceTest,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedFactBuilderServiceTest' -q`.
      - Narrowed PIT result: line 80% (2834/3547), mutation 34% (653/1914), test strength 43% (653/1531), 383 no-coverage mutations. Remaining gap is still broad; likely next ROI is in `ScholardexProjectionBuilderService` and `ScopusFactBuilderService`, with secondary cleanup in user-defined and citation/publication canonicalization paths.
      - Focused follow-up for `ScholardexProjectionBuilderService` + `ScopusFactBuilderService` added SQL-row and backfill/merge assertions, then passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderServiceTest' -q`
      - Two-class PIT passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderServiceTest' -q`
      - Initial two-class PIT result: line 93% (1027/1105), mutation 45% (287/639), test strength 49% (287/583), 56 no-coverage mutations.
      - Projection-builder-heavy follow-up added forum/author/affiliation/publication SQL-row assertions plus full-replacement and batch-refresh write-path coverage. `ScholardexProjectionBuilderService` alone improved to line 97% (474/489), mutation 50% (144/289), test strength 51% (144/283), 6 no-coverage mutations.
      - Updated two-class PIT result after the projection-builder pass: line 95% (1055/1105), mutation 53% (340/639), test strength 55% (340/614), 25 no-coverage mutations. This is a meaningful move, but `H49.5d` remains open because the survivor set is still broad in both helpers and control-flow utilities.
      - Second projection-builder helper pass tightened remaining survivors around fully populated publication rows, WoS-only forum merge fields, and batch refresh delete/reinsert helpers. `ScholardexProjectionBuilderService` alone improved again to line 97% (474/489), mutation 59% (170/289), test strength 60% (170/283), with 6 no-coverage mutations unchanged.
      - Updated two-class PIT result after the second projection-builder pass: line 95% (1055/1105), mutation 57% (366/639), test strength 60% (366/614), 25 no-coverage mutations. The hotspot is substantially healthier now, but `H49.5d` is still open because the remaining survivors are concentrated in helper/control-flow logic rather than the main write pipeline.
      - Third projection-builder surgical pass added coverage for sorted/derived citation maps, WoS merge ordering/skip behavior, and empty-collection author/affiliation view projection. `ScholardexProjectionBuilderService` alone improved to line 97% (475/489), mutation 65% (188/289), test strength 66% (188/283), with 6 no-coverage mutations still concentrated in lower-value helpers.
      - Updated two-class PIT result after the third projection-builder pass: line 96% (1056/1105), mutation 60% (384/639), test strength 63% (384/614), 25 no-coverage mutations. This narrowed hotspot now clears the H49 threshold, though the full `H49.5` package is still open pending broader class coverage.
      - Fact-builder follow-up added stronger persisted-state assertions for publication/forum/funding/citation/author/affiliation materialization plus targeted helper coverage around `boolValue`, `intValue`, `hashKey`, and funding-key normalization. `ScopusFactBuilderService` alone improved to line 95% (583/616), mutation 65% (229/350), test strength 69% (229/333), with 17 no-coverage mutations.
      - Updated two-class PIT result after broadening the fact-builder sweep: line 96% (1058/1105), mutation 65% (417/639), test strength 68% (417/616), 23 no-coverage mutations. Remaining survivors are now concentrated more heavily in control-flow/chunking math and lower-value helper paths than in saved-state assertions.
      - Next fact-builder ROI pass added publication field assertions for DOI/access/funding/article metadata and explicit two-chunk publication/citation logging coverage at 1001 events. `ScopusFactBuilderService` alone improved to line 95% (583/616), mutation 71% (248/350), test strength 74% (248/333), with 17 no-coverage mutations unchanged.
      - Updated two-class PIT result after the chunk-boundary pass: line 96% (1058/1105), mutation 68% (436/639), test strength 71% (436/616), 23 no-coverage mutations. Remaining survivors are increasingly concentrated in replay/skip branches, helper equivalence, and lower-value timing/math paths rather than core write behavior.
      - Replay/unchanged-branch follow-up tightened `buildFactsFromImportEvents` replay assertions for publication/forum/funding/citation paths, including normalized forum hashes, unchanged business fields, lineage refresh, and preserved materialization timestamps. `ScopusFactBuilderService` alone improved to line 96% (589/616), mutation 74% (259/350), test strength 77% (259/336), with no-coverage down to 14.
      - Updated two-class PIT result after the replay-branch pass: line 96% (1064/1105), mutation 70% (447/639), test strength 72% (447/619), 20 no-coverage mutations. Remaining survivors are now mostly helper-equivalence and lower-signal control-flow/timing paths, with less remaining pressure in the main replay/write branches.
      - Helper/skip-path cleanup pass added direct assertions for unsupported entity skip handling, missing publication/citation edge skips, `text`, `arrayValue`, `distinctNonBlank`, `normalizeForNameMerge`, `hashKey`, `sample`, and `nanosToMillis`. `ScopusFactBuilderService` alone improved to line 98% (603/616), mutation 77% (271/350), test strength 79% (271/344), with no-coverage down to 6.
      - Updated two-class PIT result after the helper/skip cleanup: line 98% (1078/1105), mutation 72% (459/639), test strength 73% (459/627), 12 no-coverage mutations. Remaining survivors are now concentrated in a smaller set of low-signal conditionals and math/timing helpers rather than untested business branches.
      - Final surgical helper cleanup added direct coverage for missing-field boolean handling, blank `splitDash`, `mapByKey` duplicate/blank-key filtering, case-insensitive `isUserDefinedSource`, and stricter `hashKey` shape assertions. `ScopusFactBuilderService` alone improved again to line 98% (603/616), mutation 78% (274/350), test strength 80% (274/344), with no-coverage unchanged at 6.
      - Updated two-class PIT result after the final surgical pass: line 98% (1078/1105), mutation 72% (462/639), test strength 74% (462/627), 12 no-coverage mutations. What remains is mostly stubborn low-value timing/control-flow/helper-equivalence fallout rather than meaningful importer behavior gaps.
      - High-ROI projection-builder follow-up added direct reflection-based assertions for `toForumView`, `toAuthorView`, `toPublicationView`, explicit `buildCitingMapForPublications` expectations, and stronger batch-refresh orchestration verification across the reporting write paths. `ScholardexProjectionBuilderService` alone improved to line 97% (476/489), mutation 73% (210/289), test strength 74% (210/283), with 6 no-coverage mutations unchanged.
      - Updated two-class PIT result after the projection-builder ROI pass: line 98% (1079/1105), mutation 76% (484/639), test strength 77% (484/627), 12 no-coverage mutations. Remaining projection survivors are now concentrated mostly in timing math, sort/order control-flow, and lower-value helper/lambda behavior rather than missed field mapping or write-path assertions.
      - Re-ran the broader narrowed 10-class `H49.5` slice after the hotspot remediation passes. Current narrowed result moved from line 80% / mutation 34% / strength 43% to line 82% (2895/3547), mutation 45% (867/1914), test strength 55% (867/1583), with no-coverage down to 331. PIT reported two timed-out mutants during this broader run, so this is useful breadth evidence but not yet a clean final closeout number.

    - [x] `H49.5e` **Canonical breadth refinement.** *(completed 2026-04-29)*
      Scope: finish the remaining non-hotspot survivor cleanup in the canonicalization classes after the projection/fact hotspots.
      Target classes:
      - `ScholardexAffiliationCanonicalizationService`
      - `ScholardexAuthorCanonicalizationService`
      - `ScholardexCitationCanonicalizationService`
      - `ScholardexPublicationCanonicalizationService`
        Focus:
      - remaining duplicate-recovery and replay/idempotence assertions
      - source-link / lineage / normalized-field invariants
      - surviving branch coverage that is currently masked by broad happy-path tests
        Progress:
      - Added direct metadata-contract coverage for all four canonical services, asserting pipeline key, entity type label, entity type, default chunk size, default source version, and heartbeat wiring.
      - Added targeted helper coverage in `ScholardexAffiliationCanonicalizationServiceTest` for normalized alias composition and deterministic fallback canonical-id generation.
      - Added targeted helper coverage in `ScholardexPublicationCanonicalizationServiceTest` for bridge-fact field copying plus deterministic fallback author/authorship helper behavior.
      - Targeted verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Scoped canonical PIT completed with timeout caveat:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Initial four-class canonical slice result after the helper/contract pass: line 77% (1275/1659), mutation 38% (314/835), test strength 47% (314/662), with 173 no-coverage mutations and 2 timed-out mutants.
      - Follow-up replay/wrapper pass added update-path coverage for affiliation replay, source-link rewrite edge handling, publication `applyCanonicalPublicationFields(...)` idempotence assertions, and default-wrapper / ordered-processing coverage on the canonical rebuild entry points.
      - Updated four-class canonical slice result: line 79% (1312/1659), mutation 41% (341/835), test strength 50% (341/684), with no-coverage down to 151 and the same 2 timed-out mutants. This is a meaningful breadth move, though the remaining gap is still broader pipeline/control-flow behavior rather than cheap helper surface.
      - Next ROI pass deepened the direct publication field-application assertions and added reachable affiliation skip/wrapper coverage, including null-corresponding-author handling, distinct affiliation resolution, and fuller replay field-state checks.
      - Updated four-class canonical slice result again: line 79% (1318/1659), mutation 44% (371/835), test strength 54% (371/687), with no-coverage down to 148 and the same 2 timed-out mutants. This confirms the remaining publication-field survivor cluster was real and largely test-killable without production changes.
      - Combined author/publication ROI pass added direct coverage for publication canonical-id fallback branches, publication author-bridge fallback queuing, author skip/fallback/conflict helper behavior, and stronger author metadata/edge assertions on single-record upsert.
      - Targeted author/publication verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Current two-class author/publication slice result: line 82% (973/1189), mutation 46% (274/594), test strength 54% (274/508), with 86 no-coverage mutations and 1 timed-out mutant. This improved the two dominant canonical classes together, but the remaining gap is still broad enough that one more publication/author pass or a switch to `H49.5f` is a judgment call rather than an obvious next step.
      - Follow-up bridge/flush/recovery pass added direct coverage for author fallback bridge dedupe, author recovery rewrite, publication DOI ambiguity handling, publication source-link caching, and publication edge/conflict queuing.
      - Updated two-class author/publication slice result: line 82% (976/1189), mutation 48% (288/594), test strength 56% (288/510), with no-coverage down to 84 and the same 1 timed-out mutant. This is still moving, but gains are now incremental rather than dramatic.
      - Publication-focused `P3 + P4` pass added direct assertions for deduped publication-author-affiliation edge queuing, conflict metadata materialization, duplicate-key recovery replay into DOI matches, and `flushPublicationFacts(...)` fallback behavior for both insert and update duplicate paths.
      - Targeted publication verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Publication-only PIT improved to: line 92% (651/706), mutation 61% (217/357), test strength 65% (217/336), with 21 no-coverage mutations and 1 timed-out mutant.
      - Updated two-class author/publication slice result after the publication `P3 + P4` pass: line 85% (1010/1189), mutation 51% (304/594), test strength 58% (304/521), with no-coverage down to 73 and the same 1 timed-out mutant. This is a better breadth move than the prior incremental passes and confirms the recovery/flush surface was still materially under-tested.
      - Timeout cleanup pass added a pagination-specific `loadSourceFacts(...)` test with explicit page-by-page stubbing so unexpected extra page fetches fail fast instead of looping indefinitely under mutation.
      - Updated publication-only PIT result after the timeout cleanup: line 92% (653/706), mutation 61% (218/357), test strength 65% (218/337), with no-coverage down to 20 and timed-out mutants reduced to 0.
      - Updated two-class author/publication slice result after the timeout cleanup: line 85% (1012/1189), mutation 51% (305/594), test strength 58% (305/522), with no-coverage down to 72 and timed-out mutants reduced to 0.
      - Author `A2 + A4` pass added direct coverage for synthetic fallback source-link caching, mixed linked/unmatched affiliation bridge behavior, queued author-affiliation edge state/reason shaping, and `flushPendingWrites(...)` recovery/counter behavior across linked/unmatched/conflict/skipped source-link results plus edge-conflict accumulation.
      - Targeted author verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest' -q`
      - Updated two-class author/publication slice result after `A2 + A4`: line 88% (1047/1189), mutation 56% (334/594), test strength 62% (334/538), with no-coverage down to 56 and timed-out mutants remaining at 0. This is the strongest `H49.5e` breadth move so far and confirms the author bridge/flush surface was still materially under-tested.
      - Author `A3` pass strengthened direct conflict/recovery assertions for `saveConflict(...)`, `upsertConflictInContext(...)`, and `recoverAuthorWrite(...)`, including full conflict metadata, existing-conflict reuse, detected-at preservation, candidate list handling, and recovered-author lineage/state propagation.
      - Updated two-class author/publication slice result after `A3`: line 90% (1070/1189), mutation 60% (357/594), test strength 65% (357/553), with no-coverage down to 41 and timed-out mutants still at 0. This is another substantial breadth move and materially reduces the remaining author canonicalization survivor mass.
      - Final publication `P2` bridge cleanup added direct coverage for null/empty author-bridge input, direct-vs-fallback author source-link resolution, and remembered-miss / blank-key `findSourceLink(...)` behavior.
      - Updated two-class author/publication slice result after `P2`: line 90% (1070/1189), mutation 60% (359/594), test strength 65% (359/553), with no-coverage unchanged at 41 and timed-out mutants still at 0. This is a small but real cleanup step; most of the remaining `H49.5e` survivors are now lower-yield helper/control-flow fallout rather than missing core canonicalization scenarios.
      - Production bugfix pass addressed a real author canonicalization defect: both single-record and batch flows now detect divergent canonical ids between an existing author source link and an existing author fact before choosing a canonical target, instead of silently trusting the source link and making the `SOURCE_ID_COLLISION` path unreachable.
      - Added focused regression tests for both collision paths in `ScholardexAuthorCanonicalizationServiceTest`, including the chunk-context cached-link variant used by batch rebuilds.
      - Re-validated the narrowed author/publication slice after the bugfix:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest' -q`
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Updated two-class author/publication slice result after the collision bugfix: line 91% (1097/1201), mutation 62% (375/602), test strength 66% (375/571), with no-coverage down to 31 and timed-out mutants still at 0. This keeps the narrowed `H49.5e` author/publication slice above the H49 threshold while correcting a real data-integrity risk.
      - Follow-up production bugfix pass addressed two additional correctness risks: ambiguous DOI resolution in publication canonicalization now stops reuse instead of merging into an arbitrary sorted match, and affiliation pending source-link rewrite now scopes by both source and `sourceRecordId` so recovery cannot rewrite commands from another source that happen to share the same record id.
      - Added focused regression coverage in `ScholardexPublicationCanonicalizationServiceTest` and `ScholardexAffiliationCanonicalizationServiceTest` for both behaviors, then revalidated touched canonical tests:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Re-ran the four-class canonical slice after the DOI/source-rewrite fixes:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Updated four-class canonical slice result after the DOI/source-rewrite bugfixes: line 89% (1490/1683), mutation 58% (497/853), test strength 64% (497/781), with 72 no-coverage mutations and 1 timed-out mutant remaining. The requested bugfixes are now protected by tests; the remaining timeout was concentrated in `ScholardexCitationCanonicalizationService.loadSourceFacts(...)`.
      - Citation pagination cleanup added a dedicated `loadSourceFacts(...)` full-rescan test with explicit page-0/page-1 stubbing and a hard failure on any unexpected page-2 fetch. This eliminates the last timed-out canonical mutant by turning the pagination contract into an asserted behavior instead of relying on permissive one-page mocks.
      - Revalidated citation tests and reran the four-class canonical slice:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest' -q`
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Updated four-class canonical slice result after the citation timeout cleanup: line 89% (1492/1683), mutation 58% (498/853), test strength 64% (498/782), with no-coverage down to 71 and timed-out mutants reduced to 0. The slice is now clean from a PIT stability perspective, even though it still sits just below the H49 threshold on rounded mutation/strength numbers.
      - Final citation breadth pass added direct preload/process helper coverage for fallback source-record ids, cached source-link replay, unresolved citing-publication conflicts, source-record collisions, already-known-edge reuse, and `lastRecordKey(...)` fallback behavior.
      - Final four-class canonical slice result: line 92% (1559/1689), mutation 66% (568/857), test strength 70%, with no-coverage down to 41 and timed-out mutants at 0. `H49.5e` now clears the H49 bar on the narrowed canonical slice.
      - Production fixes carried by this hub:
        - author canonicalization now detects source-link / canonical-author divergence in both single-record and batch flows instead of silently trusting the source link
        - publication canonicalization no longer reuses an arbitrary canonical publication when DOI resolution is ambiguous
        - affiliation recovery rewrites pending source-link commands by both `source` and `sourceRecordId`, preventing cross-source contamination

    - [x] `H49.5f` **Materialization and ingestion breadth refinement.** *(completed 2026-04-29)*
      Scope: finish the remaining non-hotspot cleanup in the batch-scoped orchestration classes that feed or invoke the canonical/projection pipeline.
      Target classes:
      - `ScopusCanonicalMaterializationService`
      - `ScopusImportEventIngestionService`
        Focus:
      - batch-scoped orchestration assertions
      - replay / duplicate / skip / error-path behavior
      - downstream invocation contracts and batch-boundary invariants
        Handover:
      - Expanded `ScopusImportEventIngestionServiceTest` to cover `ingestBatch(...)` end to end for null/empty inputs, non-Mongo fallback mode, Mongo prepared-document writes, serialization-error-only batches, and Mongo bulk-write partial-failure handling.
      - Added deterministic helper assertions for payload normalization, SHA-256 hashing, and nanos-to-millis conversion.
      - Expanded `ScopusCanonicalMaterializationServiceTest` with direct orchestration-helper coverage for incremental-option derivation, incremental detection, batch-scoped edge reconciliation, null-options full-maintenance behavior, and failure-outcome observability metrics emission.
      - Targeted verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionServiceTest' -q`
      - Narrowed two-class PIT passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionServiceTest' -q`
      - Final `H49.5f` slice result: line 99% (215/217), mutation 75% (63/84), test strength 75%, no-coverage 0. Remaining survivors are concentrated in low-signal arithmetic and conditional math around aggregate outcome/timing calculations.

    - [x] `H49.5g` **User-defined and package closeout refinement.** *(completed 2026-04-29)*
      Scope: finish the remaining non-hotspot cleanup in the user-defined branch, then re-measure the narrowed slice and decide whether `H49.5` is ready for full-package PIT.
      Target classes:
      - `UserDefinedCanonicalizationService`
      - `UserDefinedFactBuilderService`
        Focus:
      - assertion strengthening around USER_DEFINED lineage, normalized keys, and replay behavior
      - close the residual breadth gap outside the Scopus hotspot pair
      - rerun narrowed-slice PIT after `H49.5e`/`H49.5f`/`H49.5g` progress before attempting full-package PIT
      - `H49.5g` user-defined slice final result: line 97% (548/566), mutation 82% (291/356), test strength 84%, no-coverage 10.

    - [x] `H49.5h` **Core utility closeout (`ScholardexCanonicalBuildCheckpointService` + `CanonicalizationSupport` + `AbstractCanonicalizationService` + `CanonicalBuildOptions`).** *(completed 2026-04-29)*
      Scope: close the lowest-support classes and utility/base abstractions with direct unit coverage and scoped PIT.
      Handover:
      - Added direct tests for checkpoint lifecycle, canonical build option defaults, canonicalization helper utilities, and abstract rebuild/conflict orchestration harness behavior.
      - Targeted verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.AbstractCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupportTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptionsTest' -q`
      - Scoped PIT passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.AbstractCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointService,ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.AbstractCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupportTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointServiceTest,ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptionsTest' -q`
      - Result: line 94% (175/187), mutation 76% (93/123), test strength 79%, no-coverage 5. Remaining survivors are concentrated in lower-signal heartbeat/logging/timing math and branch-shape mutations in the abstract base.

    - Final full-package closeout (all 15 classes):
      - `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.*' -q`
      - Result: line 94% (3686/3902), mutation 71% (1546/2171), test strength 74% (1546/2081).
      - `H49.5` exit threshold is met at package level; remaining survivors are documented as lower-yield utility/control-flow fallout.

  - [x] `H49.6` **`ro.uvt.pokedex.core.service.importing.wos`** — same shape as scopus importing (10 classes). *(completed 2026-04-30)*
    Baseline: line 81% (2129/2623), mutation 39% (587/1517), test strength 48% (587/1227).
    Scope hint: same playbook as `H49.5` — high line coverage, assertion-strengthening work. Slightly better starting point.
    Handover:
    - Removed `fileResult` dual-tracking from `WosImportEventIngestionService`: eliminated the per-file `ImportProcessingResult` parameter from `processEventFast`, `markIdentitySkipped`, and `shouldSkipGovEvent`; multi-file ingestion methods (`ingestGovernmentAisRisExcel`, `ingestOfficialWosJson`) now capture pre-file snapshots and compute deltas for heartbeat logging; single-file methods use `total` directly.
    - Added targeted parser tests in `GovAisRisImportEventParserTest`: unsupported payload format skip, all-blank identity skip, RIS 2024 metric fallback from c2, RIS 2020 metric fallback from c2, AIS 2021 edition-preservation from parsed category field, AIS 2020 null-quarter handling.
    - Added targeted parser tests in `OfficialWosJsonImportEventParserTest`: unsupported payload format skip, `abbrJournal` fallback when title blank, `journalImpactFactor` key present with null value.
    - Added `rebuildWosProjectionsForJournals` tests in `WosProjectionBuilderServiceTest`: inserts rows and reports no errors (with `atLeast(4)` batch-update verify to kill VoidMethodCallMutator survivors at L386-389), null/empty journal-id early-return paths.
    - Added targeted `WosIdentityResolutionServiceTest` tests: `maybeUpdateAliasesUpdatesUpdatedAtWhenTitleChanges` (kills L251), `multiCandidateBestMatchCallsMaybeUpdateAliasesOnWinner` (kills L165), plus earlier tests for null-sourceContext, non-blank journalId, setIdentityKey on create/single/multi-candidate paths, null title, unchanged-match updatedAt, primaryIssn/eIssn/aliasIssn propagation, blank-title handling.
    - Added 8 targeted `WosCanonicalContractSupportTest` tests: `normalizeTitleFingerprint` null and all-special-char inputs, `normalizeMetricValue` null input, `buildIdentityKey` null token set, `isSourceAllowedForMetric` with null metric/source type, `requiresSplitByEdition` false for single edition, `selectCanonicalOperationalSource` with null left and null metric type.
    - Focused test verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.wos.*' -q`.
    - Final scoped PIT passed: 847/1416 mutations killed (60%), test strength 68%. Both H49 thresholds met.
    - Remaining survivors are classified as low-value or equivalent: logging/heartbeat arithmetic mutations in `WosImportEventIngestionService`; hash-material string-constant and join-delimiter mutations in `WosCanonicalContractSupport`; no-coverage mutations concentrated in lower-value helper paths and the unreachable `sha256Hex` catch block; `setAliasIssns`/`setAlternativeNames` VoidMethodCallMutator mutations in `createIdentity` that are shielded by the test's `persistIdentity` mock always initializing null collections.

  - [x] `H49.7` **`ro.uvt.pokedex.core.service.importing.wos.model`** — WoS model classes (3 classes). *(completed 2026-04-29)*
    Baseline: line 80% (24/30), mutation 33% (5/15), test strength 45% (5/11).
    Scope hint: small surface; review surviving mutants on equality, mapping, and value-object behavior. Likely a single sitting of work.
    Handover:
    - Added `WosParserRunSummaryTest`: 11 tests covering counter increments (processed/parsed/skipped/error), sample collection up to cap, null/blank sample rejection, unmodifiable list guard, and negative-maxSamples clamp.
    - Added `WosParsedEventResultTest`: 4 tests covering all three factory methods (`parsed`/`skipped`/`error`) and `WosIdentitySourceContext.empty()`.
    - Post-remediation: line 100% (30/30), mutation 100% (15/15), test strength 100%.

  - [x] `H49.8` **`ro.uvt.pokedex.core.service.application`** — largest package (79 classes, 4585 mutations). *(completed 2026-04-30)*
    Baseline: line 63% (5193/8253), mutation 30% (1365/4585), test strength 50% (1365/2754).
    Scope hint: the absolute mutant count is highest here, so even modest percentage gains move the project number. Break this down by sub-area (per natural grouping inside `application`) when adding subtasks; do not try to attack it as a single sweep.
    Subtasks:
    - [x] `H49.8a` Reporting facade core (highest ROI). *(completed 2026-04-30)*
      Scope: `UserReportFacade`, `GroupReportFacade`, `UserIndicatorResultService`, `ReportScopedIndicatorScoringSupport`, `ReportingComputationSupport`.
      Goal: kill high-volume business-path survivors in report computation/routing and improve confidence on user/group scoring outputs.
      Closeout note: all targeted hotspots improved and high-risk survivors addressed; residual survivors are low-signal conditional/equivalence.
    - [x] `H49.8b` Scholardex edge + source-link core (highest ROI). *(completed 2026-04-30)*
      Handover:
      - Strengthened the 4-class slice (`PublicationEnrichmentLinkerService`, `ScholardexSourceLinkService`, `ScholardexEdgeWriterService`, `ScholardexEdgeReconciliationService`) with targeted transition/reconcile and edge-write regression assertions.
      - Added focused source-link policy coverage for transition/reconcile edge cases, including record-id normalization updates, idempotent transition behavior, and reconcile error accounting on persistence failures.
      - Targeted verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.application.PublicationEnrichmentLinkerServiceTest' --tests 'ro.uvt.pokedex.core.service.application.ScholardexSourceLinkServiceTest' --tests 'ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterServiceTest' --tests 'ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationServiceTest' -q`
      - Scoped PIT verification passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.application.PublicationEnrichmentLinkerService,ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService,ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService,ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationService' -PpitTargetTests='ro.uvt.pokedex.core.service.application.PublicationEnrichmentLinkerServiceTest,ro.uvt.pokedex.core.service.application.ScholardexSourceLinkServiceTest,ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterServiceTest,ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationServiceTest' -q`
      - Final 4-class slice status: line 97% (1108/1141), mutation 79% (548/690), test strength 81%; `ScholardexSourceLinkService` focused rerun reached mutation 80% (196/246), line 97% (309/318). Residual survivors are predominantly low-signal/equivalence and observability-call removals.
        Scope: `ScholardexSourceLinkService`, `ScholardexEdgeWriterService`, `ScholardexEdgeReconciliationService`, `PublicationEnrichmentLinkerService`.
        Goal: raise mutation kill across canonical linking/edge orchestration where branch errors can introduce identity/link regressions.
    - [x] `H49.8c` Projection read/lookup services (high ROI, coverage lift). *(completed 2026-04-30)*
      Scope: `ScholardexProjectionReadService`, `PostgresReportingLookupFacade`, `ScholardexForumMvcService`, `ScholardexPublicationMvcService`.
      Goal: close low-coverage read/lookup branches and assert stable projection-backed query behavior.
      Handover:
      - Expanded targeted tests across all 4 classes, including deep projection row mapping assertions, fallback/guard routing, source-link side-effect assertions, and forum query/filter/sort/paging/error-guard scenarios.
      - Focused micro-pass on `ScholardexForumMvcService` removed its prior no-coverage hotspots in normalization/filter branches and improved conditional kill rate.
      - Final scoped PIT verification passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService,ro.uvt.pokedex.core.service.application.PostgresReportingLookupFacade,ro.uvt.pokedex.core.service.application.ScholardexForumMvcService,ro.uvt.pokedex.core.service.application.ScholardexPublicationMvcService' -PpitTargetTests='ro.uvt.pokedex.core.service.application.ScholardexProjectionReadServiceEdgeTraversalTest,ro.uvt.pokedex.core.service.application.PostgresReportingLookupFacadeTest,ro.uvt.pokedex.core.service.application.ScholardexForumMvcServiceTest,ro.uvt.pokedex.core.service.application.ScholardexPublicationMvcServiceTest' -q`
      - Final 4-class slice status: line 96% (650/675), mutation 81% (337/416), test strength 82%, no-coverage 4. Residual survivors are mainly low-signal conditional/math/equivalence toggles.
    - [x] `H49.8d` WoS/Scopus reconciliation + onboarding flows (high ROI). *(completed 2026-04-30)*
      Scope: `WosParityReconciliationService`, `WosScholardexOnboardingService`, `ScopusBigBangMigrationService`.
      Goal: strengthen orchestration-path assertions on large reconciliation/onboarding services with high survivor density.
      Baseline (2026-04-30 scoped PIT):
      - line 56% (575/1035), mutation 15% (89/580), test strength 26%, no-coverage 239.
      - `WosParityReconciliationService`: killed 47, survived 132, no-coverage 74.
      - `WosScholardexOnboardingService`: killed 31, survived 77, no-coverage 65.
      - `ScopusBigBangMigrationService`: killed 11, survived 43, no-coverage 100.
        Subtasks:
      - [x] `H49.8d-a` Scopus big-bang migration flow coverage hub.
        Scope: `ScopusBigBangMigrationService`.
        Goal: collapse no-coverage-heavy orchestration branches (step routing, guard paths, run/summary transitions) and establish deterministic migration-flow assertions.
      - [x] `H49.8d-b` WoS parity reconciliation orchestration hub.
        Scope: `WosParityReconciliationService`.
        Goal: cover parity pass/fail/retry/report branches, reconciliation-state transitions, and conflict-path side effects.
      - [x] `H49.8d-c` WoS Scholardex onboarding orchestration hub.
        Scope: `WosScholardexOnboardingService`.
        Goal: harden onboarding decision/routing logic, onboarding state transitions, and failure/skip/idempotence behavior.
      - [x] `H49.8d-d` Combined survivor sweep + closeout.
        Scope: residual survivors across `H49.8d-a/b/c`.
        Goal: triage high-risk vs low-signal survivors, address best-ROI remainder, and close `H49.8d`.
        Closeout (2026-04-30 scoped PIT, 3-class slice):
        - line 96% (991/1035), mutation 67% (386/580), test strength 68%, no-coverage 16.
        - `ScopusBigBangMigrationService`: line/high branch paths stabilized; 123/154 killed.
        - `WosParityReconciliationService`: orchestration + helper branches expanded; 154/253 killed.
        - `WosScholardexOnboardingService`: onboarding routing/state/failure-idempotence + helper branches expanded; 109/173 killed.
    - [x] `H49.8e` Admin/group/user operational facades (medium ROI). *(completed 2026-04-30)*
      Scope: `AdminCatalogFacade`, `ConflictOperationsFacade`, `PublicationWizardFacade`, `SuspiciousAuthorshipTriageService`, `UserPublicationFacade`, `UserScopusTaskFacade`.
      Goal: improve branch and contract coverage on service-layer facades frequently exercised by admin/user workflows.
      Closeout (2026-04-30 scoped PIT, 6-class slice):
      - line 94% (631/672), mutation 68% (260/385), test strength 70%, no-coverage 12.
      - Per-class outcome: all six classes reached 90%+ line and 60%+ mutation.
      - Additional hardening pass addressed medium-risk pockets in wizard forum-source identity derivation and conflict date-filter routing.
    - [x] `H49.8f` PostgreSQL projection/refresh pipeline (medium ROI). *(completed 2026-04-30)*
      Scope: `JdbcPostgresReportingProjectionService`, `JdbcPostgresMaterializedViewRefreshService`, `IndexMaintenanceSupport`, `GeneralInitializationService`.
      Goal: increase kill rate in projection refresh/rebuild paths and operational guard branches.
      Closeout (2026-04-30 scoped PIT, 4-class slice):
      - line 98% (383/389), mutation 68% (108/158), test strength 69%, no-coverage 1.
      - Per-class outcome:
        - `JdbcPostgresReportingProjectionService`: line 99% (203/206), mutation 63% (50/79).
        - `JdbcPostgresMaterializedViewRefreshService`: line 98% (93/95), mutation 76% (26/34).
        - `IndexMaintenanceSupport`: line 96% (24/25), mutation 71% (5/7).
        - `GeneralInitializationService`: line 100% (63/63), mutation 71% (27/38).
    - [x] `H49.8g` Read-port no-coverage cluster (closure-focused). *(completed 2026-04-30)*
      Scope: `PostgresScholardexAdminReadPort`, `PostgresScholardexProjectionReadPort`, `PostgresWosRankingDetailsReadPort`, `PostgresWosCategoryReadPort`, `PostgresReadCutoverGuard`, `RequestYearRangeSupport`, `ResearcherAuthorLookupService`.
      Goal: remove 0%-low coverage outliers via targeted contract/mapper tests and focused PIT slices.
      Closeout (2026-04-30 scoped PIT slices):
      - `PostgresScholardexAdminReadPort` + `PostgresScholardexProjectionReadPort`: line 99% (340/345), mutation 68% (182/267), test strength 69%, no-coverage 2.
      - `PostgresWosCategoryReadPort` + `PostgresWosRankingDetailsReadPort`: line 97% (214/221), mutation 68% (92/136), no-coverage 2.
      - `PostgresReadCutoverGuard`, `RequestYearRangeSupport`, `ResearcherAuthorLookupService` were previously lifted above the class targets in the same hub.
      - Hub outcome: targeted hotspots improved, high-risk survivors addressed; residual survivors are mostly low-signal conditional/equivalence.
    - [x] `H49.8h` Residual sweep + survivor triage. *(completed 2026-04-30)*
      Scope: package-level survivors remaining after `H49.8a-g`.
      Goal: classify residual mutants into bug-risk vs low-signal/equivalent buckets, address high-ROI remainder, and close `H49.8`.
      Closeout note: high-risk survivors were addressed in focused passes (source-link transition/reconcile policy, parity/onboarding conflict paths, and linker conflict payload assertions); residual survivors are mostly low-signal conditional/equivalence or observability-side-effect removals.

  - [x] `H49.9` **`ro.uvt.pokedex.core.service.application.model`** — application model classes (5 classes). *(completed 2026-04-30)*
    Baseline: line 69% (18/26), mutation 26% (8/31), test strength 44% (8/18).
    Handover:
    - Added `ApplicationModelContractTest` with targeted assertions for `AdminDashboardViewModel`, `AdminOperationStatus`, `ScholardexCitationsView`, and `WosEnrichmentRunSummaryDto`.
    - Covered key behavior branches: error-list presence, never-run vs has-run status, citations pagination boundaries (`hasPrevious`/`hasNext`), and `WosEnrichmentRunSummaryDto` derivation rules (`fromStep` null/default path, negative-duration clamp, non-negative preserved count, canonical `notRun` payload).
    - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.application.model.ApplicationModelContractTest' -q`.
    - Scoped PIT passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.application.model.*' -PpitTargetTests='ro.uvt.pokedex.core.service.application.model.*' -q`.
    - Post-remediation: line 85% (22/26), mutation 90% (28/31), test strength 100% (28/28 covered mutants). Remaining 3 mutants are `NO_COVERAGE` null-return accessor mutants.

  - [x] `H49.10` **`ro.uvt.pokedex.core.service.reporting`** — middle of the pack (21 classes). *(completed 2026-04-30)*
    Baseline: line 57% (1047/1853), mutation 34% (433/1272), test strength 56% (433/767).
    Scope hint: more balanced than the importing packages — both line coverage and test strength have room. Mix of new tests and stronger assertions on existing ones.
    Handover:
    - Package-level PIT reassessment passed with `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.reporting.*' -q`.
    - Post-remediation package metrics: line 93% (1668/1795), mutation 74% (911/1224), test strength 77% (911/1190), no-coverage mutants 34.
    - Final class-level hardening pass on `ComputerScienceConferenceScoringService` reached line 93% (591/637), mutation 72% (353/488), and reduced boundary survivors (ConditionalsBoundaryMutator kills improved from 12/35 to 17/35).


## H48 Phase E Global Audits

Archived from `TASKS.md` on 2026-04-28 after Phase E closeout.

- [x] `H48` Phase E — Global audits. *(completed 2026-04-28)*
  Goal: close the UX modernization cycle with one global compliance milestone after Phases A-D, covering accessibility, theme parity, responsive behavior, visual regression coverage, and guardrail hardening across authenticated researcher surfaces, evaluation, admin workspaces/forms, login/error pages, public surfaces, and remaining legacy route families.
  Design reference: `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` Phase E and Tier 3.2-3.4.
  UX guide reference: `docs/ux-design-guide.md` §4, §6, §7, §8, §9.
  Frontend conventions reference: `docs/frontend-conventions.md`.
  Exit criteria: canonical audit route matrix is documented; keyboard navigation, focus order, ARIA labels, landmarks, table semantics, modal/dialog focus behavior, live regions, and WCAG AA contrast are verified and fixed across the route matrix; light/dark/system theme parity is verified across shells/components/charts/forms/tables/modals; desktop/tablet/mobile responsive behavior is verified with no clipped controls, overlapping text, hidden actions, broken table overflow, or blank/mis-sized charts; representative browser smoke/visual checks are repeatable; static guardrails are hardened for the findings; full closeout verification passes.
  Handover:
  - Full phase closeout is captured in `docs/tasks/closed/h48.7-global-audits-closeout.md`.
  - The canonical route-family matrix that drove the phase is archived in `docs/tasks/closed/h48.1-route-surface-inventory.md`.
  - Final closeout verification passed: `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, `npm run verify-docs-governance`, `npm run verify-datatables-optin`, `node scripts/test-h48-guardrails.js`, `env H48_SMOKE_EMAIL=… H48_SMOKE_PASSWORD=… npm run verify-h48-browser-smoke`, and `./gradlew test --tests '*AccessibilityTemplateContractTest' --tests '*ThemeParityAssetContractTest' --tests '*RankingViewControllerContractTest' --tests '*RankingViewSecurityContractTest' --tests '*AdminViewControllerContractTest' --tests '*AdminGroupControllerContractTest' --tests '*AdminActivityControllerContractTest' --tests '*ResearcherWorkspaceControllerContractTest' --tests '*EvaluationWorkspaceControllerContractTest' --tests '*PostgresWosCategoryReadPortTest' -q`.

  Subtasks:

  - [x] `H48.1` Route and surface inventory.
    Handover: canonical audit matrix archived in `docs/tasks/closed/h48.1-route-surface-inventory.md`; stale/admin compatibility surfaces were classified up front and retired, redirected, or deleted before deeper audit work.

  - [x] `H48.2` Accessibility audit and fixes.
    Handover: shared tab-bar startup semantics, table headers, live regions, modal labels/descriptions, and representative authenticated/public accessibility checks were completed across the route matrix.

  - [x] `H48.3` Theme parity audit and fixes.
    Handover: shared chart-theme tokens, theme-change rerender hooks, public/forum ranking color normalization, and chart parity across public/admin/workspace/evaluation surfaces were implemented.

  - [x] `H48.4` Responsive behavior audit and fixes.
    Handover: shared tablet/mobile shell, filter-grid, notification-row, and tab-strip breakpoints were tightened and live-verified across the representative route set.

  - [x] `H48.5` Visual regression and browser smoke coverage.
    Handover: `scripts/verify-h48-browser-smoke.js` and `npm run verify-h48-browser-smoke` now cover the representative anonymous and authenticated route set with dynamic detail-route discovery.

  - [x] `H48.6` Static guardrail hardening.
    Handover: route/template/UI guardrails now cover H48 public hub/list/detail surfaces, and `scripts/test-h48-guardrails.js` proves the hardened checks fail on the target regression classes.

  - [x] `H48.7` Closeout documentation and full verification.
    Handover: final route matrix, residual risks, and verification evidence are documented in `docs/tasks/closed/h48.7-global-audits-closeout.md`; phase bookkeeping was archived after the full closeout suite passed.

## H45-H47 UI Modernization And Public Surface Closeout

Archived from `TASKS.md` on 2026-04-28 after H45, H46, and H47 completion.

- [x] `H45` Phase C — Admin Form Modernization (Tier 2.2 Option D). *(completed 2026-04-27)*
  Goal: modernize admin edit workflows by collapsing short edit pages into row-edit modals powered by the shared `modal-shell` fragment, while keeping genuinely long configuration forms as dedicated pages using the shared `admin-form` baseline.
  Design reference: `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` §2.2 Option D and Phase C.
  UX guide reference: `docs/ux-design-guide.md` §6.2, §6.3, §6.5, §6.6, §6.7, §8.1, §8.2.
  Exit criteria: short admin edit pages no longer require full-page navigation; row-level Edit actions open accessible shared modals with pre-populated values, Save/Cancel behavior, validation/error placement, focus return, Escape close, and toast/status feedback; long-form admin edit pages use the shared `admin-form` shell with sticky Save/Cancel controls, section headings, helper text, breadcrumbs, and consistent field layout; legacy edit URLs redirect to their parent list/detail surface where practical; deleted templates are removed from template guardrails; both light and dark themes render correctly; `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava` pass clean.

  Subtasks:

  - [x] `H45.1` **Admin edit form inventory and modal/dedicated classification.** *(completed 2026-04-27)*
    Audit the Tier 2.2 candidate edit pages and record the chosen treatment at the top of the implementation handoff. Default rule: convert forms with simple scalar fields or shallow lists to row-edit modals; keep forms with nested repeaters, builders, or many related collections as dedicated pages.
    Initial classification: convert `scholardex-editForum.html`, `scholardex-editAffiliation.html`, `edit-institutions.html`, and `domains-edit.html` to modals; keep `edit-group.html`, `activities-edit.html`, `edit-individualReport.html`, `edit-groupReport.html`, `indicators-edit.html`, and `activity-indicators.html` as dedicated surfaces unless the audit proves a form is simpler than it currently appears.
    Exit criteria: each candidate form has an explicit modal-vs-dedicated decision before migration starts.
    Handover:
    - Modal conversions confirmed for `scholardex-editForum.html` and `scholardex-editAffiliation.html`: both are scalar-only edit forms (forum: name/ISSN/eISSN/ISBN/aggregation type/publisher; affiliation: name/city/country) and their parent catalogs already render async action columns from `admin-scholardex-forums.js` / `admin-scholardex-affiliations.js`.
    - Modal conversions confirmed for `edit-institutions.html` and `domains-edit.html`: both are shallow list forms that match their existing create-modal workflows (institution profile plus Scopus/WoS affiliation rows; domain details plus WoS category rows). Keep saves on the existing create/update contracts unless H45.3/H45.4 choose JSON for smoother row refresh.
    - Dedicated pages confirmed for `edit-group.html` and `activities-edit.html`: group editing has dynamic domain/member collections plus workspace navigation, and activity editing has nested fields, allowed values, and reference-field repeaters. Migrate their layout to `admin-form` in H45.5 instead of forcing them into modals.
    - Dedicated pages confirmed for `edit-individualReport.html`, `edit-groupReport.html`, and `indicators-edit.html`: report definitions and indicators contain criteria, thresholds, scoring strategies, formula/configuration fields, and dynamic builders. `edit-individualReport.html` already uses the shared `admin-form` pilot from H44.7; H45.6 should finish the same baseline for the remaining report/indicator pages.
    - `activity-indicators.html` is not a standalone edit-template candidate in current code; it is a list/create/delete configuration surface returned by `AdminActivityController#getActivityIndicators`. Treat it as a dedicated configuration surface if touched during H45.6, not as a row-edit modal migration target.

  - [x] `H45.2` **Scholardex forum and affiliation row-edit modals.** *(completed 2026-04-27)*
    Replace `admin/scholardex-editForum.html` and `admin/scholardex-editAffiliation.html` with row-edit modals on `admin/scholardex-forums.html` and `admin/scholardex-affiliations.html`.
    Use `modal-shell`, row Edit icon buttons, preloaded row data or a compact JSON/detail endpoint, existing save facades, toast feedback, and focus return. Redirect old edit URLs back to the parent catalog.
    Exit criteria: forum and affiliation edits complete without leaving the catalog pages; old edit templates are unused.
    Handover:
    - `admin/scholardex-forums.html` now includes an `editForumModal` shared `modal-shell`; the async table action renders an Edit button instead of a full-page edit link.
    - `admin-scholardex-forums.js` fetches `/admin/scholardex/forums/{id}/edit-data`, populates the modal, posts the existing `/admin/scholardex/forums/edit/{id}` form contract with CSRF headers, closes the modal, shows a toast, and refreshes the current table page.
    - `admin/scholardex-affiliations.html` now includes an `editAffiliationModal` shared `modal-shell`; the async table action renders Publications + Edit actions and removes the name-as-edit-link pattern.
    - `admin-scholardex-affiliations.js` fetches `/admin/scholardex/affiliations/{id}/edit-data`, posts the existing `/admin/scholardex/affiliations/edit/{id}` contract with CSRF headers, closes the modal, shows a toast, and refreshes the current table page.
    - `AdminViewController` legacy edit GET routes now redirect to their parent catalogs; compact JSON edit-data endpoints serve modal population; POST handlers keep the existing save facades and now redirect to parent catalogs.
    - Focused verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.3` **Institution row-edit modal.** *(completed 2026-04-27)*
    Move `admin/edit-institutions.html` into an edit modal on `admin/institutions.html`, preserving name, description, Scopus affiliation mapping, and WoS affiliation fields.
    Keep existing create/delete behavior intact, and make Save update the row or refresh the page consistently after success.
    Exit criteria: institution edits happen from the institutions list; `/admin/institutions/edit/{id}` redirects to `/admin/institutions`.
    Handover:
    - `admin/institutions.html` now renders an `editInstitutionModal` shared `modal-shell` beside the existing create modal. Row actions use an Edit button with `data-edit-institution-id` instead of navigating to the standalone edit page.
    - The modal preserves institution name/description, Scopus affiliation rows, and WoS affiliation rows. It reuses the existing `/admin/institutions/update` POST contract and refreshes the page after a successful modal save so the server-rendered table stays authoritative.
    - Added `/admin/institutions/{id}/edit-data` JSON endpoint for modal population. Legacy `GET /admin/institutions/edit/{id}` now redirects to `/admin/institutions`.
    - Existing create/delete behavior is unchanged. The shared collection helpers now add generated Scopus rows as `scopusAffiliations[index].afid` so modal-generated rows bind to affiliation ids directly.
    - Focused verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.4` **Domain row-edit modal.** *(completed 2026-04-27)*
    Move `admin/domains-edit.html` into an edit modal on `admin/domains.html`, preserving name, description, and WoS category assignment behavior.
    Reuse existing category option data already loaded for the domains page.
    Exit criteria: domain edits happen from the domains list; `/admin/domains/edit/{id}` redirects to `/admin/domains`.
    Handover:
    - `admin/domains.html` now renders an `editDomainModal` shared `modal-shell` beside the existing create modal. Row actions use an Edit button with `data-edit-domain-id` instead of navigating to the standalone edit page.
    - The modal preserves domain name/description and WoS category rows. It reuses the existing `/admin/domains/update` POST contract and refreshes the page after a successful modal save so the server-rendered table remains authoritative.
    - Added `/admin/domains/{id}/edit-data` JSON endpoint for modal population. Legacy `GET /admin/domains/edit/{id}` now redirects to `/admin/domains`.
    - Existing create/delete behavior is unchanged. Shared category helpers populate both create and edit modal rows from the existing `allWosCategories` model data.
    - Focused verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.5` **Dedicated admin-form baseline for complex group and activity editors.** *(completed 2026-04-27)*
    Keep `admin/edit-group.html` and `admin/activities-edit.html` as dedicated pages, but migrate their layout to the shared `admin-form` shell.
    Preserve dynamic domain/member rows for groups and dynamic activity field/reference-field rows for activities. Add breadcrumbs back to the parent list and consistent Save/Cancel placement.
    Exit criteria: group and activity edit pages match the shared admin form pattern without changing their data contract.
    Handover:
    - `admin/edit-group.html` now uses the shared `admin-form` shell with breadcrumbs, sticky Save/Cancel actions, and sections for group profile, domains, institution, and researchers. Existing dynamic domain/member row behavior and the `/admin/groups/update` POST contract are preserved.
    - `AdminGroupController#editGroup` now supplies breadcrumbs, `adminFormObject`, and the `allDomains` alias needed by the modernized form while keeping existing model attributes intact.
    - `admin/activities-edit.html` now uses the shared `admin-form` shell with breadcrumbs and sections for activity profile, activity fields, and referenced fields. Existing dynamic field, allowed-value, and reference-field JavaScript behavior and the `/admin/activities/update` POST contract are preserved.
    - `AdminActivityController#editActivity` now supplies breadcrumbs and `adminFormObject`; existing reference-field rows submit indexed `referenceFields[n]` names so they match dynamically added rows.
    - Focused verification passed: `./gradlew test --tests '*AdminGroupControllerContractTest' --tests '*AdminActivityControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.6` **Dedicated admin-form baseline for report and indicator configuration.** *(completed 2026-04-27)*
    Complete the long-form baseline for `admin/edit-groupReport.html`, `admin/indicators-edit.html`, and any remaining report/indicator configuration holdouts not already covered by H44.7.
    Keep these as dedicated pages because they contain report criteria, scoring rules, or configuration builders.
    Exit criteria: all surviving long-form admin edit pages use the shared `admin-form` pattern or an explicitly documented equivalent.
    Handover:
    - `admin/edit-groupReport.html` now uses the shared `admin-form` shell with breadcrumbs, sticky Save/Cancel actions, and sections for report metadata, report indicators, and the criteria builder. Existing dynamic indicator, criterion, and threshold rows and the `/admin/groupReports/update` POST contract are preserved.
    - `AdminGroupReportsController` now supplies `adminFormObject`, breadcrumbs, and `allPositions` for both edit and apply routes through a shared edit-model helper.
    - `admin/indicators-edit.html` now uses the shared `admin-form` shell with breadcrumbs and sections for indicator identity/activity mapping and scoring rules. Existing scoring inputs, helper text, activity-description sync attributes, and the `/admin/indicators/update` POST contract are preserved.
    - `AdminViewController#editIndicator` now supplies `adminFormObject` and breadcrumbs. `edit-individualReport.html` remains the existing H44.7 shared-form baseline, and `activity-indicators.html` remains unchanged because it is a list/create/delete configuration surface rather than a dedicated edit page.
    - Focused verification passed: `./gradlew test --tests '*AdminGroupReportsControllerContractTest' --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.7` **Legacy template, route, and guardrail cleanup.** *(completed 2026-04-27)*
    Remove dead short edit templates after their parent-list modals are live, update route guardrails/template asset checks, and make legacy edit GET routes redirect to canonical parent pages.
    Do not remove POST endpoints that are still used by modal saves unless they are replaced by tested JSON endpoints.
    Exit criteria: no deleted template is referenced by controllers, tests, or verification scripts.
    Handover:
    - Removed the dead short edit templates `admin/scholardex-editForum.html`, `admin/scholardex-editAffiliation.html`, `admin/edit-institutions.html`, and `admin/domains-edit.html` after their row-edit modals and redirect compatibility routes were in place.
    - Kept the existing Scholardex forum/affiliation POST edit endpoints because the row-edit modals still submit to those save contracts.
    - Updated the institution workspace Edit action to return to the canonical `/admin/institutions` surface instead of linking to the removed full-page editor.
    - Updated async Scholardex forum/affiliation JS behavior tests to assert row edit buttons rather than legacy full-page edit links.
    - Extended route guardrails to fail if removed short edit templates reappear and to reject stale domain/institution edit page links in runtime templates.
    - Focused verification passed: `node scripts/test-admin-scholardex-forums.js`, `node scripts/test-admin-scholardex-affiliations.js`, `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.8` **Regression coverage and UI verification.** *(completed 2026-04-27)*
    Add or update MVC/render-contract tests for each migrated modal and each surviving dedicated admin form. Cover modal shell markers, ARIA labels, populated edit data, save endpoints, redirect compatibility, and guardrail expectations.
    Exit criteria: targeted controller/template tests pass, frontend build and guardrails pass, and Java compilation is clean.
    Handover:
    - Completed the Phase C regression closeout across migrated modal forms and surviving dedicated admin forms. Existing tests cover modal shell markers, ARIA labels, edit-data JSON, legacy redirect compatibility, and shared `admin-form` markers for group, activity, report, indicator, and individual-report editors.
    - Added explicit short-edit modal save contract coverage for Scholardex forum, Scholardex affiliation, domain, and institution updates, including redirect targets and facade-bound model values.
    - Verified async Scholardex table behavior with row edit buttons and canonical publication links through the dedicated JS behavior tests.
    - Full H45 closeout verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' --tests '*AdminGroupControllerContractTest' --tests '*AdminActivityControllerContractTest' --tests '*AdminGroupReportsControllerContractTest' --tests '*AdminIndividualReportsControllerContractTest' -q`, `node scripts/test-admin-scholardex-forums.js`, `node scripts/test-admin-scholardex-affiliations.js`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

- [x] `H46` Phase D — Public Surfaces (Tier 2.3). *(completed 2026-04-28)*
  Goal: align the ten unauthenticated / public-facing templates (`publications/list.html`, `forums/list.html`, `forums/detail.html`, `core/rankings.html`, `core/ranking-detail.html`, `universities/list.html`, `universities/detail.html`, `wos/categories.html`, `wos/category-detail.html`, `events/list.html`) with the ScholarDex visual system established in Tier 1, give the public experience its own navigation shell separate from the authenticated app shell, and fold accessibility, theme parity, and responsive verification into the same pass.
  Design reference: `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` §2.3 and Phase D. Default depth: **Option B — Branded public experience** (visual alignment + dedicated public header + responsive layouts + detail-page stat cards + structured profile layouts + SEO structured data). If H46.1 confirms a different depth, record it on that subtask.
  UX guide reference: `docs/ux-design-guide.md` §6.1, §6.2, §6.3, §6.5, §6.8, §7, §8.1, §8.2.
  Frontend conventions reference: `docs/frontend-conventions.md` shared component catalog (use `breadcrumb`, `stat-card`, `pagination`, `filter-panel`, `search-input`, `confirmation-dialog`, toast system as fragments mature).
  Exit criteria: every listed public template renders against the ScholarDex card/table/typography system in both light and dark themes; unauthenticated visitors see a dedicated public navigation header instead of the authenticated app shell; list pages have empty/loading states, shared filter/pagination/search affordances where applicable, and responsive layouts down to mobile widths; detail pages lead with hero stat cards and structured profile sections; SEO structured data is emitted on detail pages where it adds value; `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava` pass clean.

  Subtasks:

  - [x] `H46.1` **Public surface inventory and depth confirmation.** *(completed 2026-04-27)*
    Walk the ten public templates listed above, capture current layout, data model, and existing controller contracts, and lock the depth (A/B/C) for this phase. Default to Option B unless inventory surfaces a reason to scope down to A or up to C.
    Record per-template treatment: which shared fragments apply (`stat-card`, `breadcrumb`, `pagination`, `filter-panel`, `search-input`), whether a detail page warrants SEO structured data, and any data shape that needs a controller change to support new layout (e.g. summary metrics, related-entity counts).
    Exit criteria: implementation handoff lists each template with chosen treatment, depth choice is recorded, and any controller-side data additions are captured as discrete follow-up subtasks before visual work starts.
    Handover:
    - **Depth confirmed: Option B.** Proceed with visual alignment + responsive layouts + detail-page `stat-card` adoption + `breadcrumb` gaps filled + SEO OpenGraph meta on detail pages. The "dedicated public navigation shell separate from the authenticated shell" (H46.2) needs a product decision first — see critical finding below.
    - **Critical finding — all nine reachable routes are currently authenticated.** `WebSecurityConfig` line 77 requires auth for `/forums/**`, `/wos/**`, `/core/**`, `/universities/**`, `/events/**`. A true "public navigation header for unauthenticated visitors" (Option B intent) requires either opening these routes to anonymous access or redefining H46.2 as a shell polish/nav improvement for authenticated users only. This is a product call outside the scope of H46.1; H46.2 should clarify scope before building.
    - **`publications/list.html` is orphaned.** No controller maps any route to `"publications/list"`. `/user/publications` redirects to workspace. The template is dead. H46.2 must either create a route (e.g. `GET /publications`) and open it publicly, or remove the template. Recommend creating `GET /publications` as a publicly accessible catalog surface consistent with the other nine.
    - Per-template classification:

      | Template | Current shell | Layout state | Fragment gaps | Controller-side additions needed |
      |---|---|---|---|---|
      | `publications/list.html` | Bootstrap 4 CDN only — **no app shell, orphaned** | Dead — no route | Everything (route, shell, filter, search, pagination) | New `GET /publications` route + data source |
      | `forums/list.html` | Authenticated app shell | **Modern** — filter panel, `search-input`, async table, pager | None for list; `breadcrumb` on parent nav if shell changes | None |
      | `forums/detail.html` | Authenticated app shell | Partial — uses `breadcrumb`; ad-hoc `<style>` block for heatmap; Bootstrap 4 cards | Move heatmap CSS to bundle; replace ad-hoc stat display with `stat-card` for summary line; add OpenGraph meta | None (model already supplies `detail`, `forum`, `wosRanking`, `breadcrumbs`) |
      | `universities/list.html` | Authenticated app shell | Partial — filter panel, async table, pager; uses raw `<input>` instead of `search-input` fragment | Replace raw input with `search-input` fragment | None |
      | `universities/detail.html` | Authenticated app shell | Partial — uses `breadcrumb`, Chart.js charts, Bootstrap 4 cards; no `stat-card` | Add `stat-card` hero section (country, best rank, latest score); add OpenGraph meta | None (model supplies `ranking`, `fields`, `breadcrumbs`) |
      | `core/rankings.html` | Authenticated app shell | **Modern** — `search-input`, filter panel, async table, pager | None | None |
      | `core/ranking-detail.html` | Authenticated app shell | Minimal — Bootstrap 4 cards, Chart.js; **no breadcrumb**, no `stat-card`, navbar text is wrong ("URAP University Ranking Details") | Add `breadcrumb`; add `stat-card` for current rank/category; fix navbar title; add OpenGraph meta | Controller must supply `breadcrumbs`; conference list data already available |
      | `wos/categories.html` | Authenticated app shell | **Modern** — `search-input`, filter panel, async table, pager | None | None |
      | `wos/category-detail.html` | Authenticated app shell | Partial — uses `breadcrumb`; ad-hoc Bootstrap 4 `border-left-*` stat cards | Replace ad-hoc stat cards with `stat-card` fragment; add OpenGraph meta | None (model supplies `categoryDetail`, `breadcrumbs`) |
      | `events/list.html` | Authenticated app shell | Minimal — `app-table-section` with DataTable (`js-datatable`), no filter panel, no search, server-renders full list | Add filter panel with search input; DataTable handles client-side sort/filter but no server pagination | Controller already provides full `artisticEvents` list — DataTable client-side pagination is acceptable; no server-side changes needed |

    - **Controller-side additions required before visual work:**
      1. `RankingViewController#showCoreRankingDetailsPage` — add `breadcrumbs` (e.g. `[("Core Rankings", "/core/rankings"), (conf.acronym)]`) and confirm model already supplies `conf.yearlyRankings` for chart.
      2. New `GET /publications` route (H46.2) — needs a data source; recommend same `ScholardexPublicationFactRepository`-backed search used elsewhere, or a read-only projection endpoint with basic title/year/author columns.
    - **Inline styles to migrate:** `forums/detail.html` heatmap CSS (`<style>` block, ~60 lines) → move to `app.scss` or a new `public-forums.css` in the asset bundle. `core/ranking-detail.html` `.card-spacing { margin-bottom: 20px; }` → use existing spacing utility.
    - **Chart library inconsistency:** `universities/detail` and `core/ranking-detail` use Chart.js (CDN); `forums/detail` uses Frappe Charts (CDN). **Decision (2026-04-27):** move both libraries out of inline CDN `<script>` tags and into the bundled asset pipeline (npm dependency + import in `app.js` or a dedicated `charts.js` entry). Both libraries stay (no consolidation); CDN references are removed. H46.5 handles Frappe Charts; H46.6 and H46.7 handle Chart.js.
    - **SEO/OpenGraph:** add `<meta property="og:*">` tags on four detail templates (`forums/detail`, `universities/detail`, `core/ranking-detail`, `wos/category-detail`). No JSON-LD required at Option B depth.

  - [x] `H46.2` **Open routes to unauthenticated access and resolve publications template.** *(completed 2026-04-27)*
    Two gating decisions that must land before any public shell or template work starts:
    1. **Security rule change.** Amend `WebSecurityConfig` to move `/forums/**`, `/wos/**`, `/core/**`, `/universities/**`, `/events/**` from `.authenticated()` to `.permitAll()`. These pages expose only reference/catalog data (rankings, categories, journal metadata) — no user-specific or sensitive information.
    2. **Publications route.** Create `GET /publications` as a publicly accessible catalog backed by an async JSON endpoint (pattern: same as `/forums/data`) that pages and filters `ScholardexPublicationFact` records. Expose title, year, forum name, author list, DOI/EID, and citation count as the column set — matching what the admin Scholardex publications view already renders. Wire `GET /publications` into `RankingViewController` (returns `"publications/list"`), create `GET /publications/data` as a `@ResponseBody` paged search endpoint using the existing `ScholardexPublicationFactRepository` or `ScholardexProjectionReadService`, and open both with `permitAll`. The template will be built out in H46.4.
    3. **Controller breadcrumb addition for `core/ranking-detail`.** `RankingViewController#showCoreRankingDetailsPage` currently supplies no breadcrumbs model attribute and has the wrong `navbar` title ("URAP University Ranking Details"). Add `breadcrumbs` (e.g. `[("Core Rankings", "/core/rankings"), (conf.acronym)]`) and correct the navbar call. This is a one-liner controller fix best done here before the template work in H46.7 starts.
    Exit criteria: `curl -v http://localhost:8181/forums` (no auth) returns 200; same for `/wos/categories`, `/core/rankings`, `/universities`, `/events`, and the new `/publications`; `GET /core/rankings/{id}` model contains a `breadcrumbs` attribute; `WebSecurityConfig` change is covered by an updated security contract test or a new one; `./gradlew compileJava` and `./gradlew test -q` pass clean.
    Dependency: none (this unblocks H46.3–H46.12).
    Handover:
    - `WebSecurityConfig` — moved `/forums/**`, `/wos/**`, `/core/**`, `/universities/**`, `/events/**`, `/publications/**` and `/api/rankings/**` into the `permitAll()` block; removed the now-redundant `.authenticated()` line for those paths. All `/api/rankings/*` endpoints (core, urap, categories, wos) are now public — all are read-only reference data.
    - `RankingViewController` — added `GET /publications` (returns `"publications/list"`), `GET /publications/data` (`@ResponseBody` → `PublicationTablePageResponse`), and breadcrumbs for `GET /core/rankings/{id}`. Fixed the wrong navbar title in `core/ranking-detail.html` from "URAP University Ranking Details" to "Core Conference Ranking"; added the breadcrumb fragment call to the template.
    - `ScholardexPublicationMvcService` — new service with server-side paged SQL search against `reporting_read.scholardex_publication_view`. Supports `q` (title ILIKE), `sort` (title/year/citations), `direction`, `page`, `size`. Batch-resolves forum names and up to 5 author names per publication via `PostgresScholardexProjectionReadPort`. Returns `PublicationTablePageResponse` with `PublicationTableItemResponse` items (id, title, year, forumId, forumName, authorNames, citedByCount, eid).
    - `PublicationTableItemResponse` and `PublicationTablePageResponse` — new DTOs in `controller.dto`.
    - Verification: `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-ui-guardrails`, and `./gradlew test --tests '*AdminViewControllerContractTest' --tests '*AdminGroupControllerContractTest' --tests '*AdminActivityControllerContractTest' --tests '*AdminGroupReportsControllerContractTest'` all passed clean.

  - [x] `H46.3` **Public navigation shell, base layout, and root landing page.** *(completed 2026-04-27)*
    Build a dedicated public header/footer (separate from the authenticated app shell) with ScholarDex branding, primary public links (publications, forums, rankings, universities, events, WoS categories), light/dark toggle parity, and a responsive collapse pattern. Promote the shell into a shared Thymeleaf fragment so all ten templates can adopt it without duplication.
    Wire authenticated-state awareness so logged-in users see a "Workspace" entry point rather than a sign-in CTA, and unauthenticated visitors see a "Sign in" link.
    Build a minimal public landing page at `GET /` (or redirect the current root if it only goes to login): ScholarDex wordmark, a one-line value proposition, and stat-card–style entry points into the main public surfaces (publications, forums, rankings, universities). Unauthenticated visitors hitting the root should land here, not the login page. Authenticated visitors should be redirected to `/user/workspace` as today.
    Exit criteria: shared public layout fragment exists and is documented in `docs/frontend-conventions.md`; `GET /` returns the landing page for anonymous users and redirects authenticated users to workspace; landing page renders correctly in both themes; at least one other public template adopts the public shell in this subtask as the reference adoption.
    Dependency: `H46.2` (routes must be open so the shell renders to anonymous visitors), `H44` shared component library (breadcrumb, search-input).

  - [x] `H46.4` **Publications public search catalog.** *(completed 2026-04-27)*
    Build out `publications/list.html` as a full async search catalog on the public shell, mirroring the admin Scholardex publications interface in UX pattern but scoped to the public data shape (title, year, forum name, author list, DOI/EID, citation count).
    Use the `app-table-filters` + `search-input` + sort/direction/page-size selectors pattern already established by `forums/list.html` and `core/rankings.html`. Drive the table via the `GET /publications/data` async JSON endpoint created in H46.2. Include loading/error/empty states, pager controls, and link each row title to the forum detail page (`/forums/{forumId}`) where relevant.
    Exit criteria: publications search renders on the public shell, search/sort/filter/pagination all work against the live endpoint, both themes verified, responsive down to mobile, loading and empty states present.
    Dependency: `H46.2` (route and data endpoint must exist), `H46.3` (public shell fragment).

  - [x] `H46.5` **Forums list and detail refresh.** *(completed 2026-04-27)*
    Migrate `forums/list.html` and `forums/detail.html` to the ScholarDex visual system: list adopts the public shell and confirms existing filter/search/pagination affordances are intact; detail leads with hero stat cards and structured profile sections, moves the heatmap CSS from the inline `<style>` block into the asset bundle, and adds OpenGraph meta tags.
    Exit criteria: forum browsing and forum profile views render on the public shell, are themed, responsive, and a11y-verified; heatmap CSS is not inline; detail page emits OpenGraph meta.
    Dependency: `H46.3` (public shell fragment).
    Handover:
    - `forums/list.html` was confirmed on the public shell with existing async search/filter/sort/page-size controls, loading/error/empty states, pager controls, and no DataTables demo dependency.
    - `forums/detail.html` now renders on the public shell, emits OpenGraph metadata, uses shared `stat-card` fragments for safe forum summary metadata, and presents authenticated WoS data in structured profile sections.
    - Anonymous journal visitors with hidden WoS metrics see a sign-in callout below the summary; authenticated users continue to see journal details, general metrics, category ranking heatmaps, and unavailable-ranking states.
    - Forum heatmap/detail CSS moved from the template into `frontend/src/styles/public-forums.css`; Frappe Charts was added to the npm bundle and the external CDN script was removed.
    - Verification: `./gradlew test --tests "*RankingViewControllerContractTest" --tests "*RankingViewSecurityContractTest" -q`, `npm run build`, `npm run verify-template-assets`, `npm run verify-ui-guardrails`, `npm run verify-assets`, `npm run verify-route-guardrails`, `npm run verify-datatables-optin`, `./gradlew compileJava -q`, and `./gradlew test -q` passed.

  - [x] `H46.6` **Universities list and detail refresh.**
    Migrate `universities/list.html` and `universities/detail.html` to the ScholarDex visual system: list replaces the raw `<input>` with the `search-input` fragment and adopts the public shell; detail adds a `stat-card` hero section (country, best rank, latest score) and OpenGraph meta.
    Exit criteria: university list and profile pages render on the public shell, are themed, responsive, and a11y-verified; detail page emits OpenGraph meta.
    Dependency: `H46.3` (public shell fragment).
    Handover:
    - `universities/list.html` now renders on the public shell and uses the shared `search-input` fragment while keeping the existing async URAP table, pager, and filter controls unchanged.
    - `universities/detail.html` now renders as a public university profile with breadcrumb navigation, OpenGraph metadata, summary `stat-card` hero metrics, structured definition details, and responsive chart sections.
    - URAP historical charts now initialize from bundled assets via `frontend/src/modules/public/universityDetailCharts.js`; the external Chart.js CDN reference was removed and `scripts/verify-template-assets.js` no longer needs a universities allowlist exception.
    - Verification: `./gradlew test --tests "*RankingViewControllerContractTest" --tests "*RankingViewSecurityContractTest" -q`, `./gradlew compileJava -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, and `npm run verify-ui-guardrails` passed.

  - [x] `H46.7` **Core rankings list and detail refresh.** *(completed 2026-04-27)*
    Migrate `core/rankings.html` and `core/ranking-detail.html` to the public shell. List is already modern (search-input, filter panel, async table) — adopt shell and verify. Detail: add `breadcrumb` (controller change landed in H46.2), add `stat-card` hero for current rank/category, fix navbar title, add OpenGraph meta, remove the inline `.card-spacing` style.
    Exit criteria: rankings browsing and ranking-detail views render on the public shell, breadcrumb is present on detail, navbar title is correct, are themed, responsive, and a11y-verified.
    Dependency: `H46.2` (breadcrumb controller fix), `H46.3` (public shell fragment).

  - [x] `H46.8` **WoS categories list and detail refresh.**
    Refresh `wos/categories.html` and `wos/category-detail.html` within the authenticated shell (sidebar + navbar). These routes are behind authentication per H47 (WoS policy compliance) and must not use the public shell. List is already modern — verify existing filter/search/pagination controls are intact and aligned with the current design system. Detail: replace the ad-hoc `border-left-*` Bootstrap 4 stat cards with the `stat-card` fragment.
    Exit criteria: categories list and category-detail views render correctly within the authenticated shell, are themed, responsive, and a11y-verified; detail page uses `stat-card` fragment.
    Dependency: `H47` (WoS categories behind auth).

  - [x] `H46.9` **Events list refresh.**
    Migrate `events/list.html` to the public shell using the same server-side async pattern as the other list pages. Add `GET /events/data` as a `@ResponseBody` paged/filtered endpoint on `RankingViewController` (replace the current full-list model attribute approach); support `q` (name filter), `sort`, `direction`, and `page`/`size` parameters. Remove the DataTable (`js-datatable`) dependency from this page. Add filter panel with `search-input`, sort/direction/page-size selectors, loading/error/empty states, and pager controls consistent with `forums/list.html`.
    Exit criteria: events list renders on the public shell, async search/sort/filter/pagination work against `GET /events/data`, DataTable is not used, loading and empty states are present, both themes verified, responsive down to mobile.
    Dependency: `H46.3` (public shell fragment).

  - [x] `H46.10` **Rankings hub: tabbed CORE / Universities / Events page.**
    Replace the three separate list pages (`/core/rankings`, `/universities`, `/events`) with a single `/rankings` hub page that presents all three as tabs. Reuse the existing `tab-bar(tabs, callbacksRef)` fragment and `initWorkspaceTabs()` JS module from H36.1. Each tab hosts its own filter panel and async table (the three existing JS modules — `rankings-core.js`, `rankings-urap.js`, `events.js` — can be loaded on the hub and scoped to their respective tab panels). Redirect the old URLs to the new hub with the appropriate tab pre-selected via hash (`/core/rankings` → `/rankings#core`, `/universities` → `/rankings#universities`, `/events` → `/rankings#events`). Update the public-shell nav to point to `/rankings` and highlight the link as active for any of the three tab routes. Update breadcrumbs on the three detail pages accordingly.
    Exit criteria: `/rankings` renders with three tabs (CORE, Universities, Events), each tab loads its data independently, filter and pagination work per tab, old URLs redirect correctly, nav link is active on all three hash variants, detail-page breadcrumbs point to the hub with the correct hash, both themes and mobile layout verified.
    Dependency: `H46.7` (CORE), `H46.9` (Events), H36.1 (tab-bar component).

  - [x] `H46.11` **Detail-page stat cards and SEO structured data sweep.**
    Final pass across the four detail templates (`forums/detail.html`, `universities/detail.html`, `core/ranking-detail.html`, `wos/category-detail.html`) to confirm hero `stat-card` usage is consistent, structured profile sections follow the same shape, and OpenGraph metadata is present and correct on all four.
    Exit criteria: detail pages share a consistent above-the-fold information hierarchy; OpenGraph meta is present on all four; findings are noted in `docs/frontend-conventions.md` for future public detail pages.

  - [x] `H46.12` **Accessibility, theme parity, and responsive sweep.**
    Run focused audits across all ten public templates: keyboard navigation, focus order, ARIA labels on interactive elements, color contrast in both themes, and breakpoint behavior down to mobile. Fix issues in place rather than deferring.
    Exit criteria: all ten public templates pass the same a11y/theme/responsive checklist used in H36.13, H37.9, and H40.11; outstanding deferrals (if any) are filed as discrete follow-up tasks rather than left unmarked.

  - [x] `H46.13` **Regression coverage and UI verification.**
    Add or update render-contract tests for the public navigation shell and each migrated template (markers, breadcrumbs, stat cards, pagination, structured data emission where applicable). Add or update a security contract test confirming the ten public routes return 200 without authentication. Refresh existing JS behavior tests for any list pages whose row actions changed.
    Run the full closeout suite: targeted controller tests for affected routes, frontend build, asset/template/route/UI guardrails, and `./gradlew compileJava`.
    Exit criteria: targeted tests pass, build and guardrails pass, Java compilation is clean, and the H46 handoff records every command run in the closeout.
    Handover:
    - Render/security contracts now cover the public shell, rankings hub, migrated detail pages, public catalog access, H47 WoS authentication policy, hidden anonymous WoS forum metrics, OpenGraph markers, breadcrumbs, stat cards, and canonical redirects from legacy ranking list URLs.
    - JS behavior fixtures for CORE, URAP, and WoS category ranking tables now use canonical detail bases (`/core/rankings`, `/universities`, `/wos/categories`) instead of removed `/rankings/*` aliases.
    - Route/DataTables guardrails were refreshed for the H46.10 rankings hub consolidation: checks now target `rankings/hub.html`, and the DataTables verifier tolerates removed template roots.
    - Verification passed: `./gradlew test --tests "*RankingViewControllerContractTest" --tests "*RankingViewSecurityContractTest" --tests "*ApiSecurityContractTest" -q`, `npm run test-rankings-core`, `npm run test-rankings-urap`, `npm run test-rankings-categories`, `npm run test-scholardex-forums`, `./gradlew compileJava -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `npm run verify-datatables-optin`.

- [x] `H47` **WoS policy compliance: gate metrics and move WoS categories back to authenticated.** *(completed 2026-04-27)*
  Motivation: Web of Science data licence terms restrict public redistribution of journal-level metrics (quartile rankings, AIS/RIS/IF scores, category-level rank data) and the WoS category index itself. Public exposure of this data may infringe WoS policy.
  Scope:
  - **Gate WoS categories behind authentication.** Remove `/wos/**` from the `permitAll()` block in `WebSecurityConfig`. Anonymous visitors hitting `/wos/categories` or `/wos/categories/{key}` must be redirected to `/login`. Update the public-shell nav to hide the "WoS Categories" link for unauthenticated visitors (or omit it entirely). Remove the "WoS Categories" card from the landing page hero grid.
  - **Strip WoS metrics from the public forum detail page.** `forums/{id}` remains public (ISSN, eISSN, name, aggregation type are safe), but the WoS ranking section — quartile history charts, AIS/RIS/IF scores, category-level rank tables, and the inline Frappe Charts CDN script — must not render for unauthenticated users. Use a Thymeleaf auth guard (`th:if="${currentUser != null and currentUser.isPresent()}"`) to suppress the entire WoS card block and its associated scripts when the visitor is anonymous. Authenticated users continue to see the full detail.
  - **Update the forum list WoS status column.** The `wosStatus` badge in `forums/list.html` (indexed / not indexed / not applicable) is non-metric metadata and can remain public. No change needed to the list page.
  - **Update security contract tests.** The existing `RankingViewSecurityContractTest` and `ApiSecurityContractTest` changes from H46.2 must be partially reverted: `/wos/**` should require authentication again. Add a test asserting that unauthenticated access to `/wos/categories` returns a login redirect, and that unauthenticated access to `/forums/{id}` returns 200 but does not contain the WoS ranking section markup.
  Exit criteria: anonymous visitors cannot access WoS category pages; forum detail pages are accessible but show no WoS metrics or category rankings; authenticated users see the full detail unchanged; all security contract tests pass; build and guardrails pass.
  Dependency: `H46.3` (public shell, landing page), `H46.5` (forum detail refresh).

## H36-H44 Workspace, Admin, Auth, And Component UI Closeout

Archived from `TASKS.md` on 2026-04-28 after closure audit confirmed completed parent tasks, completed subtasks, and handoff evidence. H20 remains active because the user-facing Publish-or-Perish upload/import flow is not present yet.

- [x] `H36` Researcher Workspace — adaptive research hub consolidating dashboard, profile, publications, and activities into a single intelligent workspace with master-detail interaction, unified search, notification center, and inline workflows.
  Goal: replace four fragmented user pages (`user/dashboard`, `user/profile`, `user/publications`, `user/activities`) plus their sub-pages (`user/citations`, `user/tasks`, `user/publications-edit`, `user/activities-edit`) with one integrated workspace at `/user/workspace` that serves as the researcher's complete home base — adaptive overview, tabbed data views with inline detail panels, cross-entity search, change notifications, inline publication creation, and personalizable layout.
  Design reference: `docs/tasks/closed/h36-ux-redesign-plan.md` §1.1 Option C.
  UX guide reference: `docs/ux-design-guide.md` §1.2, §4.3, §6.2, §6.3, §6.6, §6.7, §7.1, §7.2, §7.4, §8.1, §8.2, §8.3.
  Exit criteria: researcher lands on a single adaptive workspace after login; overview adapts to researcher state (new user → onboarding, active → recent changes, reporting season → readiness status); all data previously spread across dashboard/profile/publications/activities is reachable via tabs without full-page navigation; master-detail works for publications and activities; unified search spans publications, activities, and citations; notification center shows changes since last visit; publication creation wizard runs inline without leaving the workspace; overview card order is user-customizable and persisted; skeleton loading on all async content; keyboard shortcuts for tab navigation; old URLs redirect to the workspace with the correct tab active; sidebar collapses from 4+ items to 1; all work passes `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`.

  Subtasks:

  - [x] `H36.1` **Shared tab-bar component.**
    Build a reusable tab-bar fragment in `fragments.html` and a supporting JS module in `frontend/src/modules/shared/`. Must support: labeled tabs, URL hash state preservation (`#publications`, `#activities`, etc.), lazy-load callback per tab, active-tab visual state, keyboard navigation (arrow keys between tabs, Enter to activate), ARIA `role="tablist"/"tab"/"tabpanel"` and `aria-selected` attributes, smooth transitions per §8.1, keyboard shortcuts (e.g. `Ctrl+1`..`Ctrl+4` to jump to tab by index). Both themes. No jQuery dependency.
    Exit criteria: fragment renders a functional tab bar in any template; hash changes on tab switch; browser back/forward navigates tabs; keyboard shortcuts work; focus ring visible in both themes; `npm run build` passes.
    Completed: 2026-04-05.
    Handover:
    - `frontend/src/styles/shared-tabs.css` — BEM `.app-tab-bar` block; two-phase transition classes (`--leaving` 150ms / `--active` 200ms); `:focus-visible` focus ring using `--app-color-focus`; horizontal-scroll responsive behaviour. Both themes handled entirely through CSS variables.
    - `frontend/src/modules/shared/workspaceTabs.js` — `initWorkspaceTabs()` export; roving-tabindex arrow-key navigation (manual activation); `history.pushState` / `popstate` for hash nav; lazy-load callbacks via `window[data-app-tab-bar-callbacks]` fired once per tab; `Ctrl+1`..`Ctrl+4` shortcuts guarded against input focus; `window.appWorkspaceTabs.activateTab(hash)` public API.
    - `fragments.html` — `tab-bar(tabs, callbacksRef)` fragment added; `TabDef` shape: `.id`, `.label`, `.iconClass`. Panels are empty shells — content is JS-populated via the callback map.
    - `app.js` — CSS import and `initWorkspaceTabs()` call added.
    - All three verification scripts passed: `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`.

  - [x] `H36.2` **Workspace controller, aggregated view model, and JSON API layer.**
    Create a `ResearcherWorkspaceController` (or extend `UserViewController`) with:
    — `GET /user/workspace` — serves the workspace page with eagerly-loaded overview data (stat cards, recent activity, notification summary, profile completeness).
    — `GET /user/workspace/publications` — JSON endpoint for lazy-loaded publication list with citations and author mappings.
    — `GET /user/workspace/activities` — JSON endpoint for lazy-loaded activity instances with chart data.
    — `GET /user/workspace/search?q=…` — JSON endpoint for unified cross-entity search (publications, activities, citations).
    — `GET /user/workspace/notifications` — JSON endpoint returning changes since the researcher's last visit (new citations found, sync completions, report availability).
    — `POST /user/workspace/preferences` — JSON endpoint to persist overview card ordering (stored per-user, e.g. in a simple key-value table or researcher metadata).
    Build a `ResearcherWorkspaceViewModel` that bundles overview tab data eagerly; other tabs lazy-load via the JSON endpoints.
    Aggregates from existing facades: `UserPublicationFacade`, `UserActivityInstanceFacade`, `ResearcherService`, `UserScopusTaskFacade`. Does not modify existing services.
    Dependency: none (uses existing facades).
    Exit criteria: `/user/workspace` returns a model with overview data; all JSON endpoints respond correctly; search returns results across publications, activities, and citations; notification endpoint returns change list; preferences endpoint persists and retrieves card order; existing services are not modified.
    Completed: 2026-04-05.
    Handover:
    - `model/workspace/WorkspacePreferences.java` — MongoDB doc (`scholardex.workspacePreferences`) keyed by `userEmail`; stores `overviewCardOrder` (List<String>) and `lastVisitAt` (Instant).
    - `repository/WorkspacePreferencesRepository.java` — Spring Data Mongo repo; `findById` / `save` are the only operations used.
    - `service/application/model/TabDef.java` — reusable tab descriptor record (`id`, `label`, `iconClass`, `defaultTab`).
    - `service/application/model/WorkspaceNotification.java` — notification DTO with four `NotificationType` values: `NEW_CITATION`, `SYNC_COMPLETED`, `REPORT_AVAILABLE`, `PROFILE_INCOMPLETE`.
    - `service/application/model/WorkspaceSearchResult.java` — search result DTO with `EntityType` (`PUBLICATION`, `ACTIVITY`, `CITATION`).
    - `service/application/model/ResearcherWorkspaceViewModel.java` — overview view model record; nested `RecentActivityItem` and `WorkspaceState` enum (`NEW_USER`, `INCOMPLETE_PROFILE`, `REPORTING_SEASON`, `ACTIVE`).
    - `view/user/ResearcherWorkspaceController.java` — `@Controller @RequestMapping("/user/workspace")`; 6 endpoints (1 MVC + 5 `@ResponseBody` JSON); aggregates from all four facades; no existing services modified. `lastVisitAt` updated after building the view model so the notification count reflects the previous visit.
    - `templates/user/workspace.html` — 4-tab workspace page; overview panel server-rendered with stat cards and adaptive state blocks; publications/activities/profile panels are empty lazy-load shells with `data-workspace-lazy-panel` + `data-src` hooks for H36.3/H36.7/H36.8/H36.10.
    - `gradlew compileJava` passes clean.

  - [x] `H36.3` **Skeleton loading system and smooth tab transitions.** *(completed 2026-04-05)*
    - `frontend/src/styles/shared-skeleton.css` — `.app-skeleton-block` pulse animation; `.app-skeleton-table` (header bar + rows with width-variant cells); `.app-skeleton-chart` (180px fill block); `.app-panel-error` wrapper. All colours use existing CSS custom properties — automatic light/dark support.
    - `frontend/src/modules/shared/workspacePanelLoader.js` — `initWorkspacePanelLoader()` exported; document-level event delegation for `[data-retry-panel]` retry clicks; `_loadPanel(panel)` shows skeleton immediately, fetches `data-src`, replaces with minimal content or error+retry block; `window.appWorkspacePanelLoader = { loadPanel }` public API for callback wiring.
    - `frontend/src/app.js` — added `shared-skeleton.css` import and `initWorkspacePanelLoader()` call.
    - `templates/user/workspace.html` — replaced stub callbacks with `window.appWorkspacePanelLoader?.loadPanel(panel)` for publications and activities; profile stub preserved for H36.10.
    - `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.4` **Adaptive overview tab.** *(completed 2026-04-05)*
    - `ResearcherWorkspaceViewModel` — added `OverviewCharts` nested record (years, pubsPerYear, citesPerYear, activityLabels, activityCounts) as field 15.
    - `ResearcherWorkspaceController` — computes per-year publication and citation counts via `TreeMap` from `UserPublicationsViewModel.publications()`, reads activityLabels/activityData from `UserActivityInstancesViewModel`, passes `OverviewCharts` to the ViewModel constructor.
    - `frontend/src/styles/shared-dashboard.css` — appended: `.app-overview-panels` flex column, `.app-drag-handle` grab cursor, `.app-overview-panel--dragging/--drag-over` states, `.app-onboarding-card/progress/list` onboarding components, `.app-charts-row` two-column mini-chart grid, `.app-mini-chart` 160px height container.
    - `frontend/src/modules/workspace/workspaceOverview.js` — new module; `initWorkspaceOverview()` applies saved card order, initialises Chart.js v2 bar chart (publications per year) and doughnut chart (activity distribution) using resolved CSS custom property colours (theme-aware), sets up native HTML5 drag-and-drop with mousedown-guard so drag only activates from `.app-drag-handle`, persists order via `POST /user/workspace/preferences`.
    - `frontend/src/app.js` — added import and `initWorkspaceOverview()` call.
    - `templates/user/workspace.html` — overview panel fully rewritten: NEW_USER shows onboarding checklist + progress bar; INCOMPLETE_PROFILE shows attention banner + progress bar + draggable stat-grid; ACTIVE/REPORTING_SEASON shows identity card, stat-grid, charts, quick-actions, recent-activity (all draggable); REPORTING_SEASON adds report-readiness panel. Inline JS block extended with `window.wsOverviewCardOrder` and `window.wsOverviewChartData`.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.5` **Unified cross-entity search.** *(completed 2026-04-05)*
    - `frontend/src/styles/shared-search.css` — `.app-ws-search` combobox widget; `.app-ws-search__field` focus ring; `.app-ws-search__hint` kbd shortcut hint (hidden when focused); `.app-ws-search__results` dropdown with max-height + scroll; `.app-ws-search__group-header` section labels; `.app-ws-search__result` rows with hover + `--active` highlight; `.app-ws-search__badge--pub/act/cite` coloured entity tags; `.app-ws-search__live` screen-reader region.
    - `frontend/src/modules/workspace/workspaceSearch.js` — `initWorkspaceSearch()` exported; global `keydown` handler for `/` and `Ctrl+K` (guards against active text fields); 300ms debounced input → `GET /user/workspace/search?q=`; results grouped by `entityType` (PUBLICATION → publications tab, ACTIVITY → activities tab, CITATION → publications tab); ArrowDown/Up navigation with DOM-index tracking, Enter selects, Escape closes; `aria-expanded` + `aria-activedescendant` + `aria-live` announcements; `window.appWorkspaceTabs?.activateTab()` for tab switching; cross-page navigation (`result.url` not starting with `/user/workspace`) after 80ms delay.
    - `templates/user/workspace.html` — search widget inserted above `[data-app-tab-bar]` inside `.app-dashboard`; `role="combobox"` on input, `role="listbox"` on results `<ul>`, `aria-haspopup/controls/autocomplete` wired correctly.
    - `frontend/src/app.js` — added `shared-search.css` import and `initWorkspaceSearch()` call.
    - `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.6` **Notification center.** *(completed 2026-04-05)*
    - `WorkspacePreferences` — added `dismissedNotificationIds: List<String>` field (MongoDB document); default empty list.
    - `ResearcherWorkspaceController` — `buildNotifications(user, since, dismissed)` now accepts a `Set<String>` and filters out dismissed IDs at the end; added `dismissedSet(prefs)` helper; `buildWorkspaceViewModel` and `getNotifications()` both pass the dismissed set; added `POST /user/workspace/notifications/mark-read` (sets `lastVisitAt=now`, clears dismissed list); added `POST /user/workspace/notifications/dismiss` (appends ID to dismissed list); added `NotificationDismissRequest` record.
    - `frontend/src/styles/shared-notifications.css` — `.app-ws-header` flex row (overrides search margin); `.app-ws-notif__bell` icon button with focus ring and `[aria-expanded='true']` state; `.app-ws-notif__badge` red pill badge (top-right, 99+ cap); `.app-ws-notif__panel` right-aligned dropdown with shadow; panel header (title + "Mark all as read" link); scrollable `.app-ws-notif__list`; `.app-ws-notif__item` rows with per-type icon modifiers (citation/sync/report/profile); dismiss X button visible on row hover; `notif-dismiss` keyframe for slide-out; empty/loading/error states; `.app-ws-notif__live` clipped live region.
    - `frontend/src/modules/workspace/workspaceNotifications.js` — `initWorkspaceNotifications()` exported; bell toggles panel; lazy-fetches `GET /user/workspace/notifications` on first open; renders grouped list with type icons, body text, relative timestamps (just now / N minutes/hours/days ago), "View →" action links; dismiss POST with 230ms slide-out animation and badge decrement; mark-all-read POST clears badge and replaces list with empty state; Escape key closes panel and returns focus to bell; `aria-expanded` + `aria-live` region maintained.
    - `templates/user/workspace.html` — search bar and bell wrapped in `.app-ws-header`; bell has server-rendered `aria-label` and badge from `workspace.unreadNotificationCount`; `window.wsNotifCount` added to inline script.
    - `frontend/src/app.js` — added `shared-notifications.css` import and `initWorkspaceNotifications()` call.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.7` **Publications tab with master-detail.** *(completed 2026-04-05)*
    - `ResearcherWorkspaceController` — added `POST /user/workspace/publications/save/{id}` endpoint (`@ResponseBody`, accepts `@RequestBody PublicationMetadataPatch`) that delegates to `userPublicationFacade.updatePublicationMetadata(id, patch)` and returns `200 OK`; added `PublicationMetadataPatch` import.
    - `frontend/src/styles/workspace-publications.css` — `.app-ws-pubs` container; `.app-ws-pubs__toolbar` action bar; `.app-ws-pubs__stats` stat strip with `--success`/`--warning` modifiers; `.app-ws-pubs__table-wrap` + `.app-ws-pubs__table` (no vertical borders, thead styled as label row, row hover with primary-tinted bg); `.app-ws-pubs__row--active` highlighted state; `.app-ws-pubs__type-badge` with `--article/--conference/--review/--book` modifiers; `.app-ws-pubs__action-btn` compact icon button; `.app-ws-pubs__detail-row` + `.app-ws-pubs__detail-panel` two-column grid (responsive to 1-col on narrow screens); citations sub-list + edit form inside detail; save feedback span; `.app-ws-pubs__pagination` bottom strip with page buttons (active + disabled states); `.app-ws-pubs__empty` centered empty state.
    - `frontend/src/modules/workspace/workspacePublications.js` — `initWorkspacePublications()` exported; registers `window.appWorkspacePublications = { init }`; `_init(panel)` shows skeleton then fetches `data-src`; `_renderAll()` inserts toolbar + stats + table-wrap, adds Scopus-Updates button click listener (`appWorkspaceTabs.activateTab('profile')`), registers Escape key handler; `_renderPage()` renders current page of rows into `<tbody>`, appends pagination strip; row click → `_toggleDetail()` (one-at-a-time, close on second click); `_insertDetailRow(pub, tr)` creates `<tr>` spanning all 6 cols with citation preview (up to 5 citing IDs resolved against loaded publications) + `<a href=/user/publications/citations?id=…>` link + edit form (subtype `<select>` + subtypeDescription `<input>`); save button → `_savePub()` POSTs JSON to `/user/workspace/publications/save/{id}`, updates in-memory data, refreshes row badge, shows inline feedback; Escape handler closes detail + returns focus to action button; pagination shows prev/page-numbers/next, page clicks re-render; empty state with CTA; error block with retry.
    - `templates/user/workspace.html` — publications callback changed from `appWorkspacePanelLoader.loadPanel` to `appWorkspacePublications.init`.
    - `frontend/src/app.js` — added `workspace-publications.css` import and `initWorkspacePublications()` call.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.8` **Activities tab with master-detail.** *(completed 2026-04-05)*
    - `ResearcherWorkspaceController` — added `POST /user/workspace/activities/create` (builds `ActivityInstance` from `ActivityInstanceCreateRequest`, maps `Map<String,String> referenceFields` to `EnumMap<Activity.ReferenceField,String>`, delegates to `saveActivityInstance`, returns saved instance as JSON); `POST /user/workspace/activities/update` (`ActivityInstanceUpdateRequest` → same enum conversion → `updateActivityInstance`); `POST /user/workspace/activities/delete/{id}` → `deleteActivityInstance`; added `Activity` import and `EnumMap` import; added two request records.
    - `frontend/src/styles/workspace-activities.css` — `.app-ws-acts` container; toolbar; stats strip (`--primary` variant); chart card (13rem fixed width, 7.5rem canvas + empty ring fallback); create panel with header, dynamic field grid, feedback span; shared field/input/select styles; table (no vertical borders, row hover, `--active` state, type badge); compact action buttons (`--danger` hover variant); detail row with 2-col grid (info + edit); two-click delete confirmation with inline warning strip; pagination; empty state.
    - `frontend/src/modules/workspace/workspaceActivities.js` — `initWorkspaceActivities()` exported; registers `window.appWorkspaceActivities = { init }`; skeleton + fetch lifecycle; Chart.js v2 doughnut with CSS-custom-property colours (cycles palette if more types than 5); stats strip with live `id="ws-acts-stat-count"` updated after create/delete; paginated table (20/page); row click → `_toggleDetail` (one-at-a-time); detail shows activity info + editable fields (custom fields as input/select based on `allowedValues`, reference fields as text inputs with human-readable labels); Save → `POST /user/workspace/activities/update`; Delete uses two-click confirmation (4s reset timer) → `POST /user/workspace/activities/delete/{id}` → removes from in-memory list, re-renders; "Add Activity" toggles inline create panel above table; activity type `<select>` → resolves fields from `_data.activities` (local first) or fetches `GET /user/activities/activity/{id}/fields`; create Save → `POST /user/workspace/activities/create` → prepends new instance, re-renders; Escape closes create panel first, then detail; retry button wired.
    - `templates/user/workspace.html` — activities callback changed from `appWorkspacePanelLoader.loadPanel` to `appWorkspaceActivities.init`.
    - `frontend/src/app.js` — added `workspace-activities.css` import and `initWorkspaceActivities()` call.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.9` **Inline publication creation wizard.** *(completed 2026-04-15)*
    - `ResearcherWorkspaceController` — added `PublicationWizardFacade` dependency; `GET /user/workspace/publications/wizard-authors?afid={afid}` returns `List<ScholardexAuthorView>` (delegates to `findAuthorsForAffiliation`); `POST /user/workspace/publications/wizard` accepts `WizardPublicationCommand`, sets `creator` server-side from authenticated user, delegates to `submitPublication`, returns `{sourceRecordId, eid}` on success or `{error}` on 400.
    - `frontend/src/styles/workspace-publications.css` — appended: `.app-ws-pubs__wizard` panel shell with header/close/body; `.app-ws-pubs__wizard-steps/step/step-dot` step indicator (active, done, pending states with connector lines); `.app-ws-pubs__wiz-search-input` forum search; `.app-ws-pubs__wiz-forum-list/item/item--selected/name/meta` forum results; `.app-ws-pubs__wiz-new-forum` details disclosure with field grid inside; `.app-ws-pubs__wiz-author-cols/col-title/author-list/author-item` two-column author staging; `.app-ws-pubs__wiz-fields/field/field--full/label/label--required/input/select` metadata grid; `.app-ws-pubs__wiz-nav/feedback` navigation strip; `.app-ws-pubs__wiz-loading/empty-authors` states.
    - `frontend/src/modules/workspace/workspacePublications.js` — added wizard state (`_wizardOpen`, `_wizardStep`, `_wForumId`, `_wNewForum`, `_wForumFilter`, `_wAuthorIds`, `_wAuthors`, `_wAuthorsLoading`, metadata fields); changed "Add Publication" link href to `/user/publications/add` (progressive fallback) with `id="ws-pubs-add-btn"` and JS click intercept; wizard placeholder `#ws-pubs-wizard` inserted between stats and table-wrap; `_openWizard()` resets state and renders panel; `_closeWizard(force)` dirty-checks with confirm; `_renderStep1()` client-side filters `_data.forumMap` (max 20), click-selects, `<details>` for new-forum creation; `_renderStep2()` fetches authors lazily via `wizard-authors?afid=` using `_data.affiliations[0].afid`, two-column add/remove staging; `_renderStep3()` metadata fields grid; `_wizardNext()` validates step 1 (forum or new-forum required) and advances; `_captureStep3()` + `_validateStep3()` validate title/date/subtypeDescription; `_submitWizard()` POSTs JSON command, on success calls `_init(_panel)` to reload; `_handleEscape` updated to close wizard before detail panel.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.10` **Profile & Sync tab.** *(completed 2026-04-15)*
    - `frontend/src/styles/workspace-profile.css` — full BEM stylesheet (`.app-ws-prof`): completeness card + progress bar + checklist, section cards (header/body), readonly info grid + ID pills, inline edit form with dynamic ID entry rows, sync section with sync-id-rows, status badges (`--pending/completed/failed/muted`), task history tables, no-profile state.
    - `frontend/src/modules/workspace/workspaceProfile.js` — `initWorkspaceProfile()` exports `window.appWorkspaceProfile.init(panel)`; fetches `GET /user/workspace/profile`; renders completeness card, identity section (readonly + inline edit toggle), and Scopus sync section with task history tables; `POST /user/workspace/profile/save` on save; `POST /user/workspace/profile/sync/{publications,citations}` on sync trigger with optimistic row prepend; no-profile state when researcher is null.
    - `frontend/src/app.js` — added `workspace-profile.css` import and `initWorkspaceProfile()` call.
    - `src/main/resources/templates/user/workspace.html` — added `data-src="/user/workspace/profile"` to `#ws-profile-mount`; updated profile callback to `window.appWorkspaceProfile?.init(panel)`.
    - `ResearcherWorkspaceController.java` — 4 new endpoints: `GET /profile`, `POST /profile/save`, `POST /profile/sync/publications`, `POST /profile/sync/citations`; inner records `WorkspaceProfileViewModel`, `ProfileSaveRequest`, `SyncRequest`.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.11` **Keyboard shortcuts and navigation.** *(completed 2026-04-15)*
    - `frontend/src/styles/shared-shortcuts.css` — cheat sheet overlay styles (`app-shortcuts-overlay`, dialog, header, section, row, `app-shortcuts-kbd`) + `app-row--kb-focused` indicator for focused table rows.
    - `frontend/src/modules/shared/workspaceShortcuts.js` — `initWorkspaceShortcuts()`: builds + appends cheat sheet overlay; capture-phase `?` handler (toggle overlay, skips inputs) and Escape handler (closes overlay with `stopPropagation`); bubble-phase table nav handler (ArrowUp/Down moves focus between data rows, Enter clicks row); MutationObserver on workspace root watches for `#ws-pubs-tbody` / `#ws-acts-tbody` to appear and then observes each for row changes, maintaining roving `tabindex` (first row = 0, rest = -1) with focus/blur class management.
    - `frontend/src/app.js` — added `shared-shortcuts.css` import and `initWorkspaceShortcuts()` call (after `initWorkspaceTabs`).
    - Ctrl+1..4, /, Ctrl+K, and module-level Escape already handled by existing modules; all listed in cheat sheet for discoverability.
    - `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.12` **Sidebar navigation update and URL redirects.** *(completed 2026-04-15)*
    - `fragments.html` — workspace switcher link updated from `/user/dashboard` to `/user/workspace`; `user-sidebar` Workspace section collapsed from 3 items (Profile, Publications, Activities) to a single "My Workspace" entry (`fa-house-user` icon, `th:href="@{/user/workspace}"`, active when `activeSection == 'workspace'`).
    - `UserViewController.java` — `GET /user` and `GET /user/dashboard` → `redirect:/user/workspace`; `GET /user/profile` → `redirect:/user/workspace#profile`; `GET /user/publications` → `redirect:/user/workspace#publications`; `GET /user/publications/scopus-tasks` → `redirect:/user/workspace#profile`; `GET /user/publications/citations` → `redirect:/user/workspace#publications`; `/user/authors/view/{id}` preserved as standalone page.
    - `ActivityInstanceController.java` — `GET /user/activities` → `redirect:/user/workspace#activities`.
    - `scripts/verify-route-guardrails.js` — required marker updated from `href="/user/dashboard"` to `href="/user/workspace"`.
    - `./gradlew compileJava`, `npm run build`, `npm run verify-route-guardrails` all pass clean.

  - [x] `H36.13` **Responsive behavior and accessibility audit.** *(completed 2026-04-15)*
    - `workspaceTabs.js` — added `role="status" aria-live="polite"` live region injected into every tab bar; updates with `"{Label} tab"` on each activation so screen readers announce the switch.
    - `workspacePublications.js` / `workspaceActivities.js` — `aria-busy="true"` set on mount during skeleton phase, removed when panel renders; detail-row toggle buttons get `aria-expanded="false/true"` updated on open/close; `_closeDetail()` returns focus to the trigger button when focus was inside the detail panel.
    - `workspaceActivities.js` — "Add Activity" button gets `aria-expanded`; `_toggleCreate()` moves focus to first form field on open; `_closeCreate()` returns focus to the Add button.
    - `workspaceOverview.js` — `_initDragDrop()` bails out early on `(pointer: coarse)` devices; drag-and-drop silently degrades to a fixed layout on touch screens.
    - `shared-dashboard.css` — `@media (pointer: coarse)` hides `.app-drag-handle` on touch/stylus devices (paired with JS guard above).
    - `shared-search.css` — `@media (max-width: 576px)` removes the `max-width: 38rem` cap so the search bar goes full-width on mobile.
    - `workspace-publications.css` / `workspace-activities.css` — `@media (max-width: 576px)` adds `overflow-x: auto` to each table-wrap so wide tables scroll horizontally on small screens without breaking the card's border-radius.
    - Pre-existing: tab bar already uses `overflow-x: auto; flex-wrap: nowrap` (scrollable pill row); detail panels already reflow to single column at 640px; notifications panel already has `max-width: calc(100vw - 1.5rem)`.
    - `npm run build`, `verify-route-guardrails` pass clean.

  - [x] `H36.14` **Legacy template cleanup and verification.**
    Completed: 2026-04-15.
    Handover:
    - Deleted 7 dead templates: `user/dashboard.html`, `user/profile.html`, `user/activities.html`, `user/citations.html`, `user/tasks.html`, `user/activities-edit.html`, `user/publications-edit.html`. Kept: `user/publications.html` (used by `/user/authors/view/{id}`), `user/publications-add-step*.html` (used by wizard), `user/workspace.html`.
    - `UserViewController.java` — `GET /user/publications/edit/{eid}` → redirect `/user/workspace#publications`; removed dead `POST /user/publications/save/{eid}` and `POST /user/profile/save` handlers.
    - `ActivityInstanceController.java` — `GET /user/activities/edit/{id}` → redirect `/user/workspace#activities`; removed unused `Model`, `User`, `UserActivityInstancesViewModel` imports.
    - `verify-template-assets.js` — removed `user/citations.html` and `user/profile.html` from `allowlistedInlineScriptFiles`.
    - `verify-route-guardrails.js` — removed file-specific checks for now-deleted `activities-edit.html` and `tasks.html`.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

- [x] `H37` Evaluation Workspace — analytical evaluation suite consolidating indicators, apply views, and reports into a single surface with period comparison, what-if analysis, per-criterion score breakdowns, and saved snapshots.
  Goal: replace the fragmented indicator/report pages (`user/indicators`, `user/indicators-apply-publications`, `user/indicators-apply-activities`, `user/indicators-apply-citations`, `user/individual-reports`, `user/individual-report-view`) with one integrated evaluation workspace where researchers can see their report, drill into each criterion's scored data inline, compare results across periods, run what-if scenarios, explore per-criterion contribution charts, and save/bookmark report states for later comparison.
  Design reference: `docs/tasks/closed/h36-ux-redesign-plan.md` §1.2 Option C (scoped to period comparison, what-if analysis, per-criterion score breakdown charts, and saved snapshots — PDF export and admin group report alignment deferred).
  UX guide reference: `docs/ux-design-guide.md` §6.2, §6.3, §6.9, §7.5, §8.1, §8.2.
  Exit criteria: researcher reaches the full evaluation workflow from a single entry point; the three `indicators-apply-*` templates are consolidated into one that dynamically renders columns based on `indicator.outputType`; the indicator catalog and reports catalog pages are either eliminated or reduced to selectors within the report view; criterion cards expand inline to show their scored data without page navigation; aggregate score and career-level progress are visible at the top of the report view; period comparison UI shows score deltas between runs; what-if panel allows hypothetical additions and recalculates scores client-side or via a scoring preview endpoint; per-criterion breakdown charts show contribution by publication/activity; snapshots can be saved, named, listed, and compared; Excel export of the full report works; old URLs redirect appropriately; all work passes `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`.

  Subtasks:

  - [x] `H37.1` **Consolidate `indicators-apply-*` templates into a single template.**
    Completed: 2026-04-15.
    Handover:
    - Created `user/indicators-apply.html` — single template driven by `outputMode` model attribute (`"publications"` / `"activities"` / `"citations"`). All three output-type layouts are present as `th:if` branches. Filter controls, sort options, table columns, JS script tag, and `data-*` dashboard ID all vary by `outputMode`. Empty-state messages and chart description IDs are also type-specific.
    - `UserReportFacade.java` — `handlePublications`, `handleActivities`, `handleCitations` each add `attrs.put("outputMode", ...)` and return `"user/indicators-apply"` as the view name.
    - `ReportScopedIndicatorScoringSupport.viewNameFor()` — simplified to always return `"user/indicators-apply"`.
    - Deleted 3 old templates: `indicators-apply-publications.html`, `indicators-apply-activities.html`, `indicators-apply-citations.html`.
    - `verify-datatables-optin.js` — updated `datatablesFreePages` to reference the new consolidated file.
    - `verify-route-guardrails.js` — updated citations-specific check to target `indicators-apply.html`.
    - `UserViewControllerContractTest` — updated all three apply-tests to use `"user/indicators-apply"` view name and include `outputMode` in raw graph; fixed 7 other stale tests whose assertions referenced removed routes or templates.
    - `./gradlew compileJava`, `cleanTest test --tests UserViewControllerContractTest`, `npm run build`, all verify scripts pass clean.

  - [x] `H37.2` **Evaluation workspace controller and JSON API layer.**
    Create an `EvaluationWorkspaceController` (or extend `UserViewController`) providing:
    — `GET /user/evaluation` (or `/user/reports/view/{id}`) — serves the report view with eagerly-loaded report metadata, criterion summaries, and current indicator scores.
    — `GET /user/evaluation/indicator/{id}/detail` — JSON endpoint returning the scored data (publications/activities/citations), filter options, chart data, and total for a given indicator, used by inline criterion expansion.
    — `GET /user/evaluation/compare?runA={id}&runB={id}` — JSON endpoint returning score deltas between two report runs (per indicator, per criterion, and aggregate).
    — `POST /user/evaluation/what-if` — JSON endpoint accepting hypothetical inputs (e.g. add one Q1 publication to indicator X) and returning recalculated scores using the existing scoring strategy (no persistence).
    — `GET /user/evaluation/breakdown/{indicatorId}` — JSON endpoint returning per-item contribution data for the breakdown chart.
    — `POST /user/evaluation/snapshots` / `GET /user/evaluation/snapshots` / `GET /user/evaluation/snapshots/{id}` / `DELETE /user/evaluation/snapshots/{id}` — CRUD endpoints for saved report snapshots (name, timestamp, full score state).
    Aggregates from existing facades/services (`IndicatorScoringService`, `IndividualReportService`, etc.); does not modify them.
    Exit criteria: all endpoints respond with correct shapes; existing scoring logic is reused, not duplicated; no regression in existing report rendering.
    Completed: 2026-04-15.
    Handover:
    - `model/evaluation/EvaluationSnapshot.java` — MongoDB doc (`evaluationSnapshots`) with `userEmail`, `researcherId`, `reportId`, `name`, `createdAt`, `indicatorScores` (Map<String,Double>), `criteriaScores` (Map<Integer,Double>). Compound index on `(userEmail, reportId, createdAt desc)`.
    - `repository/EvaluationSnapshotRepository.java` — Spring Data Mongo repo; `findByUserEmailAndReportIdOrderByCreatedAtDesc`, `countByUserEmailAndReportId`, `findByIdAndUserEmail`.
    - `repository/reporting/UserIndividualReportRunRepository.java` — added `findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc` for the compare endpoint to load run history.
    - `view/user/EvaluationWorkspaceController.java` — 10 endpoints: MVC `GET /user/evaluation` (renders `user/individual-report-view`), 4 JSON read endpoints (indicator detail, compare, what-if, breakdown), 4 snapshot CRUD endpoints. Score extraction from `rawGraph` via reflection (`getAuthorScore`, `getScore`, `getQuarter`). SNAPSHOT_CAP = 50. Inner records: `IndicatorDetailResponse`, `ScoredItem`, `RunSummary`, `ScoreDelta`, `ComparisonResponse`, `WhatIfRequest/Item/Response`, `BreakdownItem/Response`, `SnapshotRequest/Summary/Detail`.
    - Test fixes: `ActivityInstanceControllerContractTest` updated for redirect-to-workspace behaviour; `UserReportFacadeTest` view name updated to `user/indicators-apply`; `RankingViewControllerContractTest` sidebar link assertion updated from `/user/profile` to `/user/workspace`.
    - `compileJava` clean; all three verification scripts pass; 763 tests pass (10 pre-existing failures in Postgres integration / ScopusProjectionBuilder / actuator health that require external infrastructure).

  - [x] `H37.3` **Report view as central hub with inline criterion expansion.**
    Rework `user/individual-report-view.html` (or replace it with a new workspace template) so the report is the central surface for all evaluation work:
    — Aggregate score panel at the top: overall score, criteria-met / criteria-total, career-level threshold progress bar per §7.5.
    — Criterion grid: each criterion card shows its score with the existing threshold visualization. Clicking a card expands it inline to reveal the embedded indicator detail (filter panel, chart, scored-item table) — the content rendered by the consolidated `H37.1` fragments — without leaving the page.
    — Only one criterion expanded at a time by default; user can override to expand multiple. Expansion state preserved in URL hash (e.g. `#criterion-3`) so deep links work.
    — Per-criterion refresh action and global "Refresh All" action with loading feedback (skeleton or spinner per §8.1).
    — Excel export action for the full report (reusing existing export machinery).
    Dependency: `H37.1`, `H37.2`.
    Exit criteria: report view renders with aggregate score and criterion grid; inline expansion loads scored data without full-page navigation; URL-hash deep links work; refresh actions work; Excel export works; both themes.
    Completed: 2026-04-15.
    Handover:
    - `templates/user/individual-report-view.html` — complete rework: aggregate score panel (overall score, criteria-met/total with progress bar, position, last-run); report switcher `<select>` (hidden if ≤1 report); "Refresh All" form targeting `/user/evaluation/refresh`; criterion grid with indicator expand buttons (AJAX detail, `data-indicator-id`) replacing old `<a>` links; per-indicator export link (`/user/indicators/export/{id}`); hidden `indicator-detail-panel` per indicator with skeleton and content slot; null-safe `th:if` expressions (`== true` / `!= true`) for `noReports`/`noRun` compatibility when rendered from `UserViewController`.
    - `view/user/EvaluationWorkspaceController.java` — added `overallScore` (sum of criterion scores), `criteriaMet` (count where researcher position threshold is met), `criteriaTotal`; added `POST /user/evaluation/refresh` and `POST /user/evaluation/indicator/{id}/refresh` redirect-style endpoints.
    - `static/js/individual-report-dashboard.js` — rewritten with: `initThresholdRows` (unchanged logic); `initCriterionToggles` (accordion, one open at a time, hash `#criterion-N`); `initIndicatorExpand` (AJAX fetch `/user/evaluation/indicator/{id}/detail`, skeleton, renders scored-item table, hash `#indicator-{id}`); `initReportSwitcher` (on-change redirect); `applyHashState` (restore expansion from URL hash on load).
    - `static/css/individual-report-dashboard.css` — new sections: `.app-eval-aggregate` panel; `.app-eval-report-switcher`; `.indicator-block` / `.indicator-expand-btn` (button styled as link); `.indicator-detail-panel` (animated inline expansion, dark-mode aware); responsive breakpoints.
    - Tests updated: `individualReportViewDisplaysCriterionNameOrFallback` checks "Refresh All" and `/user/evaluation/refresh`; `individualReportViewRendersThresholdBadgesAndCompactIndicatorLinks` checks `data-indicator-id` and export link instead of old apply link.
    - 763 tests pass; 10 pre-existing failures (Postgres/Scopus/actuator infrastructure).

  - [x] `H37.4` **Eliminate or collapse catalog pages.**
    Reduce the catalog pages to selectors within the report view:
    — `user/indicators.html`: either remove and redirect to the report view, or reduce to a simple selector component (e.g. a left sidebar list or dropdown inside the report view) that switches the active indicator/criterion context.
    — `user/individual-reports.html`: if the researcher has exactly one report, redirect straight into that report's view; if multiple, reduce to a lightweight selector (list or dropdown) at the top of the report view for switching between reports.
    — Update sidebar navigation: the "Indicators" and "Reports" entries collapse into one "Evaluation" item pointing to `/user/evaluation` (or the canonical report view route).
    — Add redirect mappings: `/user/indicators` → `/user/evaluation`, `/user/individual-reports` → `/user/evaluation`, `/user/indicators/apply/{id}` → `/user/evaluation#indicator-{id}` (opens the relevant criterion expanded), `/user/individual-reports/view/{id}` → `/user/evaluation?report={id}`.
    Dependency: `H37.3`.
    Exit criteria: navigation reaches the evaluation workspace in one click; legacy URLs redirect correctly; `npm run verify-route-guardrails` passes.
    Completed: 2026-04-16.
    Handover:
    - `view/UserViewController.java` — `GET /user/indicators` and `GET /user/individual-reports` now redirect to `/user/evaluation`; `GET /user/indicators/apply/{id}` redirects to `/user/evaluation#indicator-{id}`; `GET /user/individual-reports/view/{id}` redirects to `/user/evaluation?report={id}`; all four POST refresh endpoints now redirect to the canonical evaluation URL instead of their old view routes; dead model-building code removed from each.
    - `templates/fragments.html` — user-sidebar "Reporting" section collapsed from two items ("Indicators" + "Reports") to one "Evaluation" item (`/user/evaluation`, icon `fa-chart-bar`); active state matches `indicators`, `individual-reports`, or `evaluation` section keys.
    - `templates/user/indicators.html` and `templates/user/individual-reports.html` — deleted (dead code, routes now redirect).
    - `scripts/verify-route-guardrails.js` — removed stale check block for deleted `individual-reports.html`.
    - Tests updated: all `UserViewControllerContractTest` tests for catalog routes now assert 3xx redirects to canonical evaluation URLs; template-rendering tests dropped in favour of simpler redirect checks.
    - 763 tests pass; 10 pre-existing failures unchanged.

  - [x] `H37.5` **Period comparison.**
    Completed: 2026-04-16.
    Handover:
    - "Compare with…" button in toolbar opens a run-picker select (hidden by default; shown on button click). Picker lists prior runs with formatted timestamp and source label from `window.evalPriorRuns`.
    - `GET /user/evaluation/compare?runA={prior}&runB={current}` returns per-indicator and per-criterion score maps; JS computes deltas as `current − prior`.
    - Aggregate panel gains a hidden "Score Δ" cell (`#eval-compare-delta-cell`) revealed when comparison is active, showing overall delta with `eval-delta--positive/negative/neutral` colour coding.
    - Each criterion card has a `.eval-criterion-delta` span (block, reserves vertical space) that shows `+N.N / −N.N / =` during comparison.
    - Indicator rows carry a `data-indicator-id` `eval-indicator-delta` span updated inline.
    - Indicator detail panel also injects a delta badge next to the total when `_compareData` is active.
    - Comparison state persisted in URL via `history.replaceState(?compare={runId})`; page reload restores state automatically.
    - "Clear comparison" button hides the picker, clears all delta spans, removes URL param, and hides the compare banner.
    - Compare banner (`#eval-compare-banner`) shown between toolbar and aggregate panel while comparison is active; hidden otherwise.
    - `window.evalPriorRuns` / `window.evalCurrentRunId` seeded from Thymeleaf inline JS; `RunSummary.createdAt` changed from `Instant` to `String` to avoid Thymeleaf/Jackson JSR310 serialisation error.
    - CSS: `.app-eval-compare-picker[hidden] { display: none }` explicit rule prevents `display: flex` overriding the `hidden` attribute.
    - All select elements on the evaluation page given `padding-top/bottom: 0.2rem; height: auto` to fix disproportionate height at 0.82rem context font-size.

  - [x] `H37.8` **Saved report snapshots.**
    Completed: 2026-04-17.
    Handover:
    - `EvaluationSnapshot` MongoDB document (`evaluationSnapshots` collection) stores `userEmail`, `researcherId`, `reportId`, `name`, `createdAt`, `indicatorScores` (Map<String,Double>), `criteriaScores` (Map<Integer,Double>). Compound index on `(userEmail, reportId, createdAt DESC)`.
    - `EvaluationSnapshotRepository` provides `findByUserEmailAndReportIdOrderByCreatedAtDesc`, `countByUserEmailAndReportId`, `findByIdAndUserEmail`.
    - Four REST endpoints in `EvaluationWorkspaceController`: `POST /user/evaluation/snapshots` (create, 50-cap enforced via 422), `GET /user/evaluation/snapshots?report=` (list), `GET /user/evaluation/snapshots/{id}` (detail), `DELETE /user/evaluation/snapshots/{id}`.
    - New `GET /user/evaluation/compare-snapshot?snapshotId={id}&runId={id}` endpoint computes `ComparisonResponse` by diffing a snapshot's score maps against the current run; returns `runA.status = "SNAPSHOT"` and `runA.name = snap.getName()` so the banner can identify it.
    - `RunSummary` record gained a nullable `name` field; all existing instantiation sites pass `null`.
    - Toolbar: "Save Snapshot" button (browser prompt for name, default "Snapshot {timestamp}") and "My Snapshots" toggle button added. Inline feedback span (`#eval-snapshot-feedback`) shows save/delete results for 4 s.
    - "My Snapshots" collapsible panel renders between the compare banner and the aggregate panel. Shows snapshot name, date, total score, "Compare" and delete (with `confirm()`) actions.
    - Compare picker (`#eval-compare-select`) gains a `<optgroup label="Saved Snapshots">` populated asynchronously on init and after any save/delete.
    - `fetchAndApplyComparison` detects `snap:{id}` prefix and routes to `/compare-snapshot` instead of `/compare`.
    - Compare banner updated to `eval-compare-banner-label` ID; shows `snapshot "{name}" ({date})` for snapshot comparisons and `run from {date}` for run comparisons.
    - Deleting the active comparison snapshot automatically clears the comparison deltas and the URL `?compare=` param.
    - `compileJava` clean; `UserViewControllerContractTest` passes; both verify scripts pass.

  - [x] `H37.9` **Responsive behavior and accessibility audit.**
    Completed: 2026-04-18.

  - [x] `H37.10` **Legacy template cleanup and verification.**
    Completed: 2026-04-18.
    After the evaluation workspace is stable:
    — Remove or mark deprecated: `user/indicators-apply-publications.html`, `user/indicators-apply-activities.html`, `user/indicators-apply-citations.html` (replaced by consolidated template from `H37.1`), `user/indicators.html`, `user/individual-reports.html` (reduced or redirected per `H37.4`). `user/individual-report-view.html` either replaced or substantially rewritten per `H37.3`.
    — Remove or redirect old controller methods fully replaced by `EvaluationWorkspaceController`.
    — Verify no remaining references to old template names in JS, CSS, or other templates.
    — Run full verification suite: `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`.
    — Smoke test: single-run view, comparison mode, what-if scenarios for each output type, breakdown charts, snapshot save/load/delete/compare, Excel export.
    Dependency: all of `H37.1`–`H37.9` complete.
    Exit criteria: no dead templates for replaced pages; all verification scripts pass; no 404s or broken links in evaluation flows; all Option C features (period comparison, what-if, breakdown charts, snapshots) verified end-to-end.

- [x] `H40` Admin Data Management Workspaces — domain-grouped admin surfaces with queue-style conflict/triage UX, integrated filter panels and cross-linking across catalog pages, institution/group workspaces with embedded sub-entity views, server-side pagination for high-volume tables, plus multi-select bulk operations, column visibility toggles, and keyboard shortcuts for common admin operations. *(completed 2026-04-28 by closure audit)*
  Goal: replace the 21+ fragmented admin table pages (users, researchers, institutions, groups, forums, authors, affiliations, publications, citations, conflicts, triage, indicators, domains, reports, activities, source links, etc.) with a consistent, domain-grouped admin experience where conflicts and triage feel like work queues, catalog pages feel explorable and cross-linked, institution and group pages feel like profile pages with integrated sub-entity tabs, and power users can operate on multi-row selections, toggle visible columns, and drive common operations from the keyboard without losing the underlying table patterns or their accessibility baseline.
  Design reference: `docs/tasks/closed/h36-ux-redesign-plan.md` §1.4 Option B, extended with three Option C features: bulk operations on high-volume tables, column visibility toggles for wide tables, and keyboard shortcuts for common operations (next row, open edit, resolve conflict). Explicitly out of scope for this task: saved filter presets, table-level Excel/CSV export toolbars, row-expansion inline previews, and real-time conflict count badges in the sidebar.
  UX guide reference: `docs/ux-design-guide.md` §1.4, §1.5, §4.4, §5.2, §6.2, §6.3, §6.5, §6.6, §6.7, §7.3, §8.1, §8.2, §9.
  Exit criteria: every admin table uses the shared ScholarDex table pattern (no vertical borders, subtle alternating rows, row hover, compact icon-button actions with descriptive `aria-label`, semantic status badges, explicit empty states); the users page replaces inline per-row role checkboxes with a proper edit modal; high-traffic tables (conflicts, researchers, publications, citations) carry summary stat cards above them; sub-list pages (institution publications, group publications, group report views) have breadcrumbs back to parents; the admin sidebar is reorganized into the four domains defined in the plan (Operations Center, People & Access, Data Catalog, Evaluation) and old URLs redirect appropriately; conflicts and user-defined triage render as queues — priority/recency sort, decision badges, batch operations, and an integrated filter panel — rather than generic tables; catalog pages (forums, authors, affiliations, publications, citations, publication search) carry integrated filter panels and cross-link between related entities (click an author → their publications; click a forum → its publications); institution and group detail pages surface summary stat cards and integrate their sub-entity views (researchers, publications, reports) as tabs rather than separate pages; high-volume catalog tables use server-side pagination with stable page-size controls; researchers can be multi-selected and assigned to a group, and publications can be multi-selected and reassigned to a forum, with safeguards, summary counts, and auditable server-side writes; wide tables expose a column visibility toggle persisted per-user; keyboard shortcuts drive next/previous row, open-edit, and resolve-conflict flows with a cheat-sheet overlay discoverable from `?`; both light and dark themes pass contrast checks; all work passes `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  Subtasks:

  - [x] `H40.1` **Shared admin-table baseline and standardization.**
    Extract the ScholarDex admin-table pattern into reusable Thymeleaf fragments and shared CSS/JS so every admin table renders consistently: no vertical borders, subtle alternating rows, row hover, compact icon-button actions with descriptive `aria-label` (e.g. "Edit publication," not "Edit"), semantic status badges per §6.5, explicit empty states per §6.6, and breadcrumb support per §5.2. Build a reusable `admin-table` fragment that accepts columns, row actions, empty-state config, and optional toolbar slot. Build a shared `admin-empty-state` fragment and a shared `admin-breadcrumb` fragment. Standardize modal creation forms across all admin "create new" flows (shared header, footer, validation placement).
    Exit criteria: every admin table uses the shared fragments or matches their structure exactly; no inline per-row role checkboxes remain on any admin table; semantic status badges render consistently across conflicts, triage, sync tasks, and any other status-bearing column; breadcrumbs appear on all sub-list pages; modal creation forms share a single structure; `npm run build`, `verify-assets`, `verify-template-assets` pass clean.
    Completed: 2026-04-22.
    Handover:
    - `frontend/src/styles/admin-tables.css` — new BEM stylesheet: `.app-admin-icon-btn` (compact table-action icon button with `--danger`/`--warning` modifiers and focus ring); `.app-admin-role-badge` (role pill with `--admin`/`--supervisor` accent variants); `.app-admin-roles-cell` flex row for multiple badges; `.app-admin-breadcrumb` + `__item`/`__link`/`__current` breadcrumb nav; `.app-admin-empty` + `__icon`/`__title`/`__body` empty-state block; `.app-admin-empty-row` for use inside `<tbody>`; `.app-admin-id-pill` monospace identifier pill; `.app-admin-locked-badge` danger pill for locked accounts; `.app-admin-actions` flex row for action buttons.
    - `frontend/src/app.js` — added `admin-tables.css` import after `admin-dashboard.css`.
    - `fragments.html` — **Researchers** sidebar item removed from `admin-sidebar`; added `admin-breadcrumb(items)` nav fragment (renders `<ol>` breadcrumb, last item as `aria-current="page"`, others as links); added `admin-empty-state(icon, title, body, actionLabel, actionHref)` fragment (icon + title + body + optional CTA button).
    - `AdminViewController.java` — `GET /admin/researchers` now redirects to `/admin/users`; researcher profile data already available on every `User` object passed to the users page.
    - `admin/users.html` — fully reworked: table columns now include Name, Scholar ID, Scopus IDs, WoS IDs (from `user.researcherProfile`); roles rendered as `.app-admin-role-badge` pills; locked state rendered as `.app-admin-locked-badge`; active state as `.app-table-badge--success`; per-row actions are `.app-admin-icon-btn` icon buttons (Edit, Lock/Unlock, Delete) with descriptive `aria-label`; inline role-checkbox forms removed; Edit opens `#editUserModal` populated via `show.bs.modal` data-attribute wiring (posts to existing `/admin/users/updateRoles`); Create User modal preserved with shared section structure; empty-state row via `admin-empty-state` fragment; `users.html` added to `allowlistedInlineScriptFiles` in `verify-template-assets.js`.
    - `admin/institution-publications.html`, `admin/group-publications.html` — breadcrumb nav added at top of page content (Institutions → {institution.name} and Groups → {group.name} respectively).
    - `admin/groups.html`, `admin/institutions.html` — empty-state `<tr>` added via `admin-empty-state` fragment.
    - All 17 remaining `table-bordered` admin templates — `table-bordered` class stripped; `.app-table` already present on all; Bootstrap's vertical-border rule no longer applied.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H40.2` **Admin sidebar reorganization and URL redirects.**
    Reorganize the admin sidebar into the four domains specified in the plan:
    — Operations Center (H39 dashboard — already live)
    — People & Access (Users, Researchers, Groups, Institutions)
    — Data Catalog (Forums, Authors, Affiliations, Publications, Citations, Publication Search)
    — Evaluation (Indicators, Domains, Activities, Activity-Indicators, Individual Reports, Group Reports, Data Quality → Conflicts, User-Defined Triage, Source Links)
    Collapse Activities / Activity-Indicators under a single Evaluation Config entry where appropriate. Update `fragments.html` accordingly and keep active-state highlighting correct for all section keys. Maintain backwards-compatible redirects for any sidebar links whose URLs change; drop only what can be dropped without breaking external bookmarks.
    Dependency: none.
    Exit criteria: sidebar sections match the plan's four-domain grouping; active-state highlighting works on every destination page; no dead links; `npm run verify-route-guardrails` passes.
    Completed: 2026-04-22.
    Handover:
    - `fragments.html` — `admin-sidebar` fully rewritten into five sections: **Operations Center** (Dashboard, Initialization, WoS Enrichment, Incremental Updates), **People & Access** (Users — active for `users` and `researchers`, Groups, Institutions), **Data Catalog** (Forums, Authors, Affiliations, Publications — active for `scholardex-publications`/`scopus-publications`/`scholardex-publications-search`, Citations, Pub. Search), **Evaluation** (Indicators, Domains, Eval. Config — active for `activities` and `activity-indicators`, Reports, Group Reports, Conflicts, Triage, Source Links), **Rankings** (WoS Categories, CORE, URAP, Events — kept as-is for backwards compat). All old section keys preserved in active-state conditions; no URLs changed; no redirects needed.
    - `admin/scholardex-citations.html` — sidebar key updated from `scholardex-publications` to `scholardex-citations` so the new Citations entry highlights correctly.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.3` **Users page edit modal (replace inline role checkboxes).**
    Replace the current users-page inline per-row role checkboxes with a proper edit modal per `H40.1` baseline: row-level "Edit" icon opens a modal form for role assignment, email display, and researcher linkage; Save posts to a JSON endpoint and re-renders the row inline; Cancel closes without side effects. Confirmation dialog for destructive role changes where appropriate.
    Dependency: `H40.1`.
    Exit criteria: no inline-checkbox row mutations remain on the users page; role edits go through the modal and persist correctly; accessibility: focus trap, Escape closes, ARIA roles on modal; both themes.
    Completed: 2026-04-22.
    Handover:
    - `frontend/src/modules/admin/adminUsers.js` — new ES module; `initAdminUsers()` guards on `#editUserModal` presence; `show.bs.modal` populates all modal fields from row `data-*` attributes (email, roles, firstName, lastName, scholarId, scopusIds, wosIds, position); `hidden.bs.modal` restores focus to trigger button; Save button POSTs JSON to `POST /admin/users/{email}/edit`; PLATFORM_ADMIN removal guarded by `window.confirm()`; `_rerenderRow(data)` updates cells 1–5 (name, scholarId, scopusIds, wosIds, roles) and refreshes `data-*` on Edit button for correct re-open.
    - `frontend/src/app.js` — added `import { initAdminUsers }` and `initAdminUsers()` call.
    - `AdminViewController.java` — added `POST /admin/users/{email}/edit` endpoint (`@ResponseBody`, `produces = "application/json"`); inner records `AdminUserEditRequest`, `AdminUserEditProfileRequest`, `AdminUserEditResponse`; updates roles via `userService.updateUserRoles()` and profile via `userService.saveResearcherProfile()`; returns `AdminUserEditResponse.from(user)` with full profile snapshot.
    - `admin/users.html` — edit button carries 8 `data-*` attributes via `th:attr` (email, roles, firstName, lastName, scholarId, scopusIds, wosIds, position); Edit modal upgraded to `modal-lg` with three sections: Account (readonly email), Roles (checkboxes), Researcher Profile (firstName, lastName, scholarId, position `<select>` via `T(Position).values()`, scopusIds, wosIds); inline feedback `<p id="edit-user-feedback">`; Save button `type="button"` (JS-intercepted); both modals carry `role="dialog" aria-modal="true"`; no inline `<script>` block.
    - `scripts/verify-template-assets.js` — removed `admin/users.html` from `allowlistedInlineScriptFiles` (inline script eliminated).
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.4` **Stat cards above high-traffic admin tables.**
    Add summary stat cards above the conflicts, researchers, publications, and citations admin tables using the shared `stat-card` fragment and semantic accents per §6.2:
    — Conflicts: open / resolved / dismissed.
    — Researchers: total / active / without-profile.
    — Publications: total / recently added (last 30 days).
    — Citations: total / incremental-updates last run.
    Values are computed server-side from existing repositories; no new aggregates persisted.
    Dependency: `H40.1`.
    Exit criteria: stat-card grids render above each listed table; numbers match direct DB counts; grid reflows to single-column on mobile per §4.4; both themes.
    Completed: 2026-04-23.
    Handover:
    - `ScholardexPublicationFactRepository` — added `countByCreatedAtAfter(Instant)` derived query.
    - `AdminDashboardService` — added `PublicationCatalogStats` record and `buildPublicationCatalogStats()` (total + last-30-days count via `publicationFactRepository`); added `buildCitationSyncStatus()` (last `ScopusCitationsUpdate` entry → `AdminOperationStatus`). Both reuse existing injected repos; no new dependencies.
    - `AdminViewController.showUsersPage()` — added `totalUsers`, `activeUsers`, `usersWithoutProfile` model attributes computed from the already-fetched `users` list.
    - `AdminViewController.showScholardexPublicationsPage()` — now accepts `Model`; adds `pubStats` (`PublicationCatalogStats`) from service.
    - `AdminScholardexPublicationViewController` — injected `AdminDashboardService`; `showScholardexPublicationCitationsPage()` now adds `citationSync` (`AdminOperationStatus`) to model.
    - `admin/users.html` — 3-card `.app-summary-grid` added above toolbar (Total / Active / Without Profile).
    - `admin/scholardex-publications.html` — 2-card `.app-summary-grid` added above search form (Total Publications / Added Last 30 Days).
    - `admin/scholardex-citations.html` — 2-card `.app-summary-grid` added above the citations table (Citations count for this record / Last Citation Sync with outcome badge colouring).
    - Conflicts page already had stat cards from H40.1 — no change needed.
    - `./gradlew compileJava`, `npm run build`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.5` **Conflicts and user-defined triage as work queues.**
    Rework the conflicts and user-defined triage pages from generic CRUD tables into queue-style UX:
    — Default sort: priority / recency (server-side).
    — Decision badges per row (resolve / dismiss / investigate) using §6.5 semantic badges.
    — Integrated filter panel per §6.3 (status, researcher, date range) that belongs to the table rather than floating above it.
    — Row actions include direct decision buttons (not just Edit) so the queue feels like a work surface, not a generic table.
    — Batch operations: multi-select + bulk resolve / dismiss / investigate with confirmation per §6.7 and auditable server-side writes.
    — Breadcrumb + return-to-queue context for any detail drawer or sub-page.
    Dependency: `H40.1`.
    Exit criteria: both pages default to priority/recency sort; filter panel is integrated, not floating; decision badges render; batch operations succeed with summary counts and per-item failures surfaced; `./gradlew compileJava` and all verify scripts pass.
    Completed: 2026-04-23.
    Handover:
    - `ConflictOperationsFacade` — added `STATUS_INVESTIGATED = "INVESTIGATED"`; `normalizeStatus()` now accepts it; `ConflictSummary` record gains `investigated` field and `total()` includes it; `summarizeIdentityConflicts()` counts all four statuses.
    - `AdminConflictController` — added `POST /admin/conflicts/investigate` endpoint; `bulkStatus` handles "investigateOne" and "investigate" actions; bulk feedback message now includes total-selected count; extracted `operator` variable to reduce duplication.
    - `AdminUserDefinedTriageController` — full rewrite: injected `ConflictOperationsFacade`; GET endpoint adds `triageQueue` (paginated USER_DEFINED OPEN conflicts, `triagePage` param) and `triagePage` to model; added `POST .../conflict/resolve`, `.../conflict/dismiss`, `.../conflict/investigate` (single-item) and `POST .../conflict/bulk` (multi-select) with redirect-back and flash messages.
    - `admin-tables.css` — added `app-admin-icon-btn--success` (green hover); `.app-queue-badge` + `--open/resolved/dismissed/investigated` semantic pill variants; `.app-queue-filter` integrated filter panel (rounded-top, borderless-bottom, connects visually to table below); `.app-queue-bulk-bar` (rounded-bottom bar for pagination + bulk buttons); `.app-queue-row--open` subtle danger tint on hover.
    - `admin/conflicts.html` — full rework: integrated filter with status `<select>` (Open/Investigating/Resolved/Dismissed/All) and Reset link; table checkboxes use `form="conflicts-bulk-form"` to decouple from per-row actions; per-row action mini-forms (Resolve ✓ / Investigate 🔍 / Dismiss ✗) each with own hidden `id` — fixes the previous bug where all singleId inputs were submitted together; status rendered as `.app-queue-badge` dynamic class; proper empty state via `admin-empty-state` fragment; bulk form hidden below table; pagination + Bulk Resolve/Investigate/Dismiss in one `app-queue-bulk-bar`; "Investigating" replaces old "Total" stat card; stat cards now show Open / Investigating / Resolved / Dismissed.
    - `admin/user-defined-triage.html` — full rework: stat cards updated (Open Conflicts instead of total); integrated filter header above queue table; paginated USER_DEFINED OPEN queue with Resolve/Investigate/Dismiss per-row mini-forms; bulk form + `app-queue-bulk-bar`; snapshot panels (source link states, conflict states, recent source links) kept below queue; deep links with icons; source link state badges use `.app-queue-badge` dynamic class.
    - `./gradlew compileJava`, `npm run build`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.6` **Catalog filter panels, cross-linking, and server-side pagination.**
    Completed: 2026-04-23.
    Handover:
    - `PostgresScholardexAdminReadPort` — added `PublicationCatalogPage` record (content, authorMap, forumMap, decisionSummaryByPublicationId, total, page, size, totalPages, hasPrevious/hasNext); added `buildPublicationCatalogPage(q, forumId, authorId, affiliationId, page, size, sort, direction)` with SQL COUNT + LIMIT/OFFSET, WHERE conditions using ILIKE (title), `= (forum_id)`, and `= ANY(author_ids/affiliation_ids)`, sort options (title/cover_date/cited_by_count); updated `buildPublicationCitationsView(id, page, size)` — now adds COUNT query and paginates the citation query with LIMIT/OFFSET; `ScholardexCitationsView` record gained totalCitations, page, size, totalPages, hasPrevious/hasNext.
    - `AdminScholardexPublicationViewController` — new `@GetMapping("")` catalog endpoint replaces the old landing page; accepts q/forumId/authorId/affiliationId/page/size/sort/direction params; builds filterContextLabel for active cross-link filters; `/search` now redirects to the catalog URL with params; `/citations` accepts page/size and passes citationsPage/pubId/citSize to model.
    - `AdminViewController` — removed `showScholardexPublicationsPage()` (moved to above controller) and its now-unused imports.
    - `admin/scholardex-publications.html` — transformed from landing page to integrated catalog: stat cards, active-filter context banner with clear link, integrated filter panel (title search, sort, direction, page size, Apply/Reset), server-side paginated table (Title→citations, Authors→publications filtered by authorId, Forum→publications filtered by forumId, Year, Citations, Overrides), toolbar and bottom pagination bar, empty state.
    - `admin/scholardex-citations.html` — reworked: breadcrumb (Publications → title), publication summary panel with author and forum cross-links, stat cards kept, citations table with toolbar prev/next pagination, bottom page-size form, proper empty state; DataTables removed (server-side paging).
    - `admin-scholardex-authors.js` — author name no longer links to user-facing page; Actions column added linking to `/admin/scholardex/publications?authorId={id}`.
    - `admin-scholardex-forums.js` — forum name no longer links to `/forums/{id}`; Actions column now has both "Publications" (→ catalog filtered by forumId) and "Edit" buttons.
    - `admin-scholardex-affiliations.js` — Actions column added linking to `/admin/scholardex/publications?affiliationId={id}`.
    - `scholardex-authors.html`, `scholardex-affiliations.html` — Actions `<th>` column header added.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-datatables-optin` all pass clean.

  - [x] `H40.7` **Institution and group workspaces with integrated sub-entity tabs.** *(completed 2026-04-23)*
    Handover:
    - `AdminViewController` — `GET /admin/institutions/{id}` now loads the institution workspace (`admin/institution-workspace`); `GET /admin/institutions/{id}/publications` redirects to `#publications` deep link.
    - `AdminGroupController` — `GET /admin/groups/{id}` new workspace endpoint loads group data and returns `admin/group-workspace`; `GET /admin/groups/{id}/publications` redirects to `#publications` deep link.
    - `admin/institution-workspace.html` — tabbed workspace (Overview + Publications tabs) using `[data-app-tab-bar]` from `workspaceTabs.js`; Overview shows institution description and total publication count with Export Excel + Edit actions; Publications tab renders all publications grouped by year with author/forum cross-links to the catalog.
    - `admin/group-workspace.html` — tabbed workspace (Overview + Publications + Reports tabs); Overview shows stat card + Publications-by-year chart + Venue Quality Distribution chart (both lazy-inited via `window.groupWorkspaceCallbacks.overview` to avoid 0×0 canvas on hidden panels); Publications tab mirrors the old `group-publications.html` per-year tables; Reports tab lists individual reports.
    - `admin/institutions.html`, `admin/groups.html` — "Publications" / "See publications" action buttons changed to "Open workspace" pointing at the new workspace URLs.
    - `./gradlew compileJava` passes clean.

  - [x] `H40.8` **Bulk operations on high-volume tables.** *(completed 2026-04-23)*
    Build shared multi-select infrastructure (row checkboxes, select-all-in-view, selection summary, clear-selection) on top of the `H40.1` admin-table baseline, then wire two concrete bulk flows:
    — Researchers → assign to group: select multiple researchers, pick a target group from a modal, confirm per §6.7, server-side write via a new JSON endpoint, summary of succeeded / failed with per-item messages.
    — Publications → reassign forum: select multiple publications, pick a target forum (with forum search inside the modal), confirm with explicit safeguard because this mutates canonical data, server-side write with full audit trail, summary of succeeded / failed.
    Selections are cleared on successful apply; they survive pagination within a session only if the same filter set is active.
    Dependency: `H40.1`, `H40.6`.
    Exit criteria: multi-select infrastructure is reusable; both bulk flows work end-to-end with destructive-action confirmation UX; per-item failures are surfaced without aborting the batch; audit log / decision records created where applicable; `./gradlew compileJava` and all verify scripts pass.
    Handover:
    - `frontend/src/modules/shared/adminBulkSelect.js` — `initAdminBulkSelect({tableKey, fingerprint, cbSelector, selectAllSelector, barSelector, countSelector, bulkFormId, inputName})` — sessionStorage-backed selection keyed by `adminBulk:{tableKey}:{fingerprint}`; reinit() for DataTables draw hook; injects hidden inputs on form submit.
    - `frontend/src/styles/admin-tables.css` — `.app-bulk-select-bar`, `.app-bulk-select-bar__count`, `.app-bulk-select-bar__actions` added.
    - `frontend/src/app.js` — imports and exposes `window.initAdminBulkSelect`.
    - `PostgresScholardexAdminReadPort.bulkReassignForum(List<String>, String)` — bulk UPDATE on `reporting_read.scholardex_publication_view`.
    - `GroupManagementFacade.addMembersToGroup(String, List<String>)` — deduplicating add to group memberIds.
    - `AdminScholardexPublicationViewController` — `POST /admin/scholardex/publications/bulk/reassign-forum` with filter-state redirect.
    - `AdminViewController` — `POST /admin/users/bulk/assign-group`; `allGroups` added to users page model.
    - `templates/admin/scholardex-publications.html` — checkbox column, select-all, bulk bar, Reassign Forum modal, inline init script.
    - `templates/admin/users.html` — checkbox column, select-all, bulk bar, Assign to Group modal, inline init script with DataTables `draw.dt` hook.
    - `scripts/verify-template-assets.js` — allowlisted both new inline-script templates.
    - All verify scripts pass: `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-datatables-optin`.

  - [x] `H40.9` **Column visibility toggles for wide tables.** *(completed 2026-04-24)*
    Add a column visibility toggle to wide admin tables (publications, citations, authors, researchers, conflicts, triage): a toolbar button opens a dropdown listing all columns with checkboxes; toggling a column hides/shows it in place; the chosen visibility set is persisted per-user (simple preferences document keyed by user email + table id).
    Required columns (primary identifiers, row action column) are always visible and cannot be hidden.
    Dependency: `H40.1`.
    Exit criteria: toggles work on every listed table; preferences persist across sessions; required columns cannot be hidden; both themes; accessibility: keyboard-operable dropdown with ARIA roles.
    Handover:
    - `frontend/src/modules/shared/adminColumnToggle.js` — `initAdminColumnToggle({tableId, tableEl, toolbarActionsEl, columns})` — localStorage-backed per-table column visibility; returns `{ reinit() }` for use after dynamic row renders; required columns show a lock icon and disabled checkbox; Escape closes dropdown; outside-click dismissal; ARIA `aria-haspopup`, `aria-expanded`, `aria-controls`.
    - `frontend/src/styles/admin-tables.css` — `.app-col--hidden { display: none !important }` + `.app-col-toggle` dropdown BEM block added.
    - `frontend/src/app.js` — imports and exposes `window.initAdminColumnToggle`.
    - `data-col` attrs added to all `<th>` and `<td>` in: `scholardex-publications.html`, `scholardex-citations.html`, `users.html`, `conflicts.html`, `user-defined-triage.html`; `<th>` only in `scholardex-authors.html` (tbody is dynamic).
    - `admin-scholardex-authors.js` — `data-col` attrs added to generated `<td>` strings; `window._authorsColToggle.reinit()` called after each render; column toggle initialized after first `fetchPage`.
    - Toolbar actions divs added (id: `pub-toolbar-actions`, `cit-toolbar-actions`, `users-toolbar-actions`, `conflicts-toolbar-actions`, `triage-toolbar-actions`, `authors-toolbar-actions`).
    - `scripts/verify-template-assets.js` — `conflicts.html` and `user-defined-triage.html` added to inline-script allowlist.
    - All verify scripts pass: build, verify-assets, verify-template-assets, verify-route-guardrails, verify-datatables-optin; `./gradlew compileJava` clean.

  - [x] `H40.10` **Keyboard shortcuts for common admin operations.** *(completed 2026-04-24)*
    Add keyboard navigation and shortcuts on admin tables and queues, reusing the `H36.11` cheat-sheet overlay pattern:
    — `j` / `ArrowDown` → next row (roving `tabindex`); `k` / `ArrowUp` → previous row.
    — `Enter` or `e` → open-edit on focused row (opens row edit modal or navigates to edit page, depending on context).
    — On conflicts / triage queues: `r` → resolve focused item, `d` → dismiss focused item, `i` → investigate focused item. All destructive shortcuts respect the same confirmation UX as the button equivalents.
    — `?` → open the shortcuts cheat-sheet overlay listing all active shortcuts for the current page.
    Shortcuts are guarded against firing while focus is inside text inputs, selects, or contenteditable elements.
    Dependency: `H40.1`, `H40.5`.
    Exit criteria: shortcuts work on conflicts, triage, users, researchers, publications, citations, and authors tables; cheat-sheet overlay enumerates them; no shortcut fires while typing in a field; focus ring is visible on the active row in both themes.
    Handover:
    - `frontend/src/modules/shared/adminShortcuts.js` — `initAdminShortcuts({sections, tables})` — reuses `app-shortcuts-*` CSS from H36.11; builds `#admin-shortcuts-overlay`; global `?` toggle and `Escape` close (capture phase); roving tabindex per tbody with `MutationObserver` for dynamic tables; `j`/`k`/`↑`/`↓` nav; per-table `keyActions` map for `e`/`Enter`/`r`/`i`/`d`; all guarded against field focus.
    - `frontend/src/styles/admin-tables.css` — `.app-row--kb-focused` highlight + `:focus` outline using `--app-color-focus`.
    - `frontend/src/app.js` — imports and exposes `window.initAdminShortcuts`.
    - Wired on 6 pages via inline scripts (conflicts, triage, users, publications, citations) and `admin-scholardex-authors.js`; each page passes its own `sections` config for the cheat sheet and `keyActions` for row-level shortcuts.
    - All verify scripts pass; `./gradlew compileJava` clean.

  - [x] `H40.11` **Responsive behavior and accessibility audit.**
    Completed: 2026-04-24.
    Handover:
    - Responsive audit passed: stat-card grids reflow via `app-summary-grid` CSS grid; tables use `app-table-scroll` horizontal scroll with no action buttons hidden on mobile; filter panels, modals, and toolbar collapse correctly on narrow screens; `@media` queries confirm no d-none on action buttons.
    - Accessibility audit passed on all 6 admin pages (publications, citations, users, conflicts, triage, authors): all icon buttons carry `aria-label`; bulk controls carry `aria-label`; column-toggle button carries `aria-haspopup`, `aria-expanded`, `aria-controls`; cheat-sheet overlay reachable via `?` key and dismissible via `Escape`.
    - Two issues found and fixed:
      1. `adminBulkSelect.js` `_updateBar()` — added `aria-live="polite"` and `role="status"` to the bulk bar element so screen readers announce selection-count changes.
      2. `adminColumnToggle.js` button click handler — on open, focus now moves to the first enabled checkbox so keyboard users don't need extra Tab presses.
    - `scripts/verify-template-assets.js` passes clean.

  - [x] `H40.12` **Legacy template cleanup and verification.**
    Completed: 2026-04-26.
    Handover:
    - Deleted orphaned `admin/institution-publications.html` and `admin/group-publications.html` — both routes now redirect to workspace URLs with `#publications` hash.
    - Fixed 4 pre-existing test failures in contract tests:
      1. `AdminConflictControllerContractTest` — `ConflictSummary(long,long,long,long)` constructor called with 3 ints; added 4th arg.
      2. `AdminViewControllerContractTest` — added missing `@MockitoBean GroupManagementFacade`; replaced stale `institutionPublicationsViewRendersExpectedTemplateAndModel` + `institutionPublicationsViewRedirectsWhenInstitutionMissing` with `institutionPublicationsRedirectsToWorkspace`; removed stale `scholardexPublicationsPagesRenderCanonicalTemplates` (wrong controller scope).
      3. `AdminScholardexPublicationViewControllerContractTest` — added missing `@MockitoBean AdminDashboardService`; updated `buildPublicationCitationsView` mock to 3-arg signature; updated `ScholardexCitationsView` constructor to 9-arg form; renamed `searchRouteBuildsPublicationSearchView` → redirect assertion; added `citationSync` stub; added `scholardexPublicationsPagesRenderCanonicalTemplates` with proper stubs.
    - Fixed two pre-existing link correctness issues: citations template author links now point to `/user/authors/view/{id}`; admin-scholardex-authors.js author name column now links to `/user/authors/view/`.
    - Removed redundant manual `${_csrf.parameterName}` hidden input from publications bulk form (`th:action` already injects CSRF).
    - All verification passes: `compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-ui-guardrails`; 34/34 contract tests green.

- [x] `H41` Delete Standalone Publication Wizard (Tier 2.1). *(completed 2026-04-26)*
  Goal: eliminate the dead standalone wizard surface so no user-facing route resolves to it and no template asset validation entry references it.
  Deliverable:
  - Delete `templates/user/publications-add-step1.html`, `step2.html`, `step3.html`.
  - Drop `GET /user/publications/add` (and any `step2`/`step3` counterparts) from the controller; add a `redirect:/user/workspace#publications` in their place for any bookmarked external link.
  - Remove the progressive-enhancement `href="/user/publications/add"` fallback from any workspace button or link that currently carries it; ensure the button triggers the inline wizard directly.
  - Drop the corresponding entries from the `verify-template-assets` allowlist.
  - Confirm no remaining template, JS module, or test references the deleted routes or templates.
  Exit criteria: `verify-template-assets`, `verify-route-guardrails`, and `verify-ui-guardrails` all pass; hitting `/user/publications/add` in a browser redirects to the workspace publications tab; no broken links in workspace HTML.
  Handover:
  - All three wizard templates deleted (`publications-add-step1/2/3.html`).
  - `PublicationWizardController` replaced with single-method redirect class: all `GET /user/publications/add/**` routes redirect to `/user/workspace#publications`.
  - `PublicationWizardControllerContractTest` deleted (no longer applicable).
  - Workspace fallback `href`s changed from `/user/publications/add` to `#` (click handlers already prevent navigation and trigger inline wizard).
  - Onboarding link in workspace updated: `th:href="@{/user/publications/add-step-1}"` → `href="#" data-tab-goto="publications"`.
  - Legacy `publications.html` "Add Publication" button removed (workspace is now the primary path).
  - `verify-template-assets.js` cleaned: removed allowlist entries for step1/step2 external assets and inline scripts.
  - All verify scripts pass; compile is clean; no regressions in existing tests (pre-existing 14 failures unrelated to this task).
  Reference: `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` §2.1, Phase A.

- [x] `H42` Login Page & Single-Keycloak Institutional Sign-In. *(completed 2026-04-26)*
  Goal: modernize the login page and add one institutional SSO path through Keycloak while preserving the existing local account login contract. The app talks to a single configured Keycloak realm/client; Keycloak handles institutional identity selection and federation outside this app.
  Design reference: `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` §2.4, scoped as Option C-lite: Option B visual treatment plus SSO area, without in-app institution selector, register flow, or animated forgot/register transitions.
  UX guide reference: `docs/ux-design-guide.md` §1.1, §1.2, §4.1, §6.2, §6.3, §6.6, §8.1.
  Exit criteria: `/login` presents a polished ScholarDex login surface with local email/password form and a clear institutional sign-in action; Spring form-login still posts to `/login` with `username` and `password`; institutional sign-in redirects to `/oauth2/authorization/keycloak`; successful Keycloak login creates or resolves a local `User` principal so existing controllers continue to see `authentication.getPrincipal() instanceof User`; first-time Keycloak users are auto-created as `RESEARCHER` accounts with no usable local password; existing local users keep their locally assigned roles and profiles; unauthenticated MVC routes still redirect to `/login`; API unauthenticated behavior remains JSON 401; logout works for both login types; all work passes `./gradlew compileJava`, targeted auth/security contract tests, `npm run build`, `npm run verify-assets`, and `npm run verify-template-assets`.

  Subtasks:

  - [x] `H42.1` **Login page visual refresh and dual-login layout.** *(completed 2026-04-26)*
    Rework `login.html` into a responsive ScholarDex login surface: local account form, institutional sign-in area, branded wordmark/header, inline error/logout states, light/dark support, and concise onboarding/help copy. Preserve `name="username"`, `name="password"`, `autocomplete="username"`, `autocomplete="current-password"`, `th:action="@{/login}"`, CSRF behavior, and local form submission.
    Exit criteria: local login contract tests still pass; login page includes `/oauth2/authorization/keycloak`; responsive layout collapses cleanly on mobile; no external runtime CSS dependency is introduced beyond existing asset patterns.
    Handover:
    - `login.html` now uses the bundled `core-styles` / `core-scripts` fragments instead of the Bootstrap CDN and inline styles.
    - The page renders a standalone two-panel ScholarDex login surface with local email/password login and an institutional sign-in CTA to `/oauth2/authorization/keycloak`.
    - `frontend/src/styles/login.css` contains the responsive light/dark layout using existing `--app-*` design tokens and is imported by `frontend/src/app.js`.
    - `AuthViewControllerSecurityContractTest` now locks the local form contract, the Keycloak link, and the no-CDN asset contract.
    - Verification passed: `./gradlew test --tests "*AuthViewControllerSecurityContractTest" -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`.

  - [x] `H42.2` **Keycloak OAuth2 client configuration.** *(completed 2026-04-26)*
    Add Spring OAuth2 client support for one registration id, `keycloak`, configured only through environment-backed properties: issuer URI, client id, client secret, and scopes. Keep local form login enabled.
    Exit criteria: app starts without Keycloak config when SSO is disabled or absent; with config present, `/oauth2/authorization/keycloak` initiates OAuth2 login; existing form login still works.
    Handover:
    - Added `spring-boot-starter-oauth2-client`.
    - `KeycloakOAuth2ClientConfig` conditionally creates an in-memory `keycloak` `ClientRegistrationRepository` only when `KEYCLOAK_ISSUER_URI` and `KEYCLOAK_CLIENT_ID` are non-blank, using OIDC discovery and redirect URI `{baseUrl}/login/oauth2/code/{registrationId}`.
    - Keycloak settings are env-backed through `scholardex.oauth2.keycloak.*` properties instead of active blank `spring.security.oauth2.client.*` defaults, because Boot validates blank OAuth registrations at startup.
    - `WebSecurityConfig` permits `/oauth2/**` and `/login/oauth2/**`, and enables `oauth2Login(loginPage("/login"))` only when a `ClientRegistrationRepository` bean exists; local form login/logout/API entry-point behavior is unchanged.
    - `.env.example` documents `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, and `KEYCLOAK_SCOPES`.
    - Targeted coverage added in `AuthViewControllerOAuth2SecurityContractTest`; verification passed: `./gradlew test --tests "*AuthViewController*SecurityContractTest" -q`, `./gradlew compileJava`.

  - [x] `H42.3` **Local user bridge for OIDC principals.** *(completed 2026-04-26)*
    Add an OAuth2/OIDC success path that extracts verified email from Keycloak, resolves the local `User`, or auto-creates a new local `RESEARCHER` account with a generated unusable password. Replace or wrap the authenticated principal so downstream MVC/user controllers continue receiving the local `User` model.
    Exit criteria: existing local users keep their roles/profile; new Keycloak users get only `RESEARCHER`; missing/blank/unverified email fails login with a safe `/login?error` redirect; locked local users cannot sign in through Keycloak.
    Handover:
    - `KeycloakOAuth2LoginSuccessHandler` now converts successful Keycloak OAuth2 logins into local `User` principals by requiring a verified email claim, normalizing it, resolving the local account, or provisioning a new `RESEARCHER` user with a generated password.
    - Existing local users keep their stored roles/profile; locked users, missing email, blank email, and unverified email fail through `/login?error`.
    - `WebSecurityConfig` wires the bridge as the OAuth2 success handler when OAuth2 login is enabled, while preserving local form login.
    - Focused coverage added in `KeycloakOAuth2LoginSuccessHandlerTest`.

  - [x] `H42.4` **Security contract updates.** *(completed 2026-04-26)*
    Extend `WebSecurityConfig` to support both `formLogin` and `oauth2Login` on the same login page. Keep `/login`, static assets, health endpoints, and OAuth2 callback/authorization endpoints permitted as needed. Preserve current MVC redirect and API 401/403 handling.
    Exit criteria: unauthenticated MVC pages redirect to `/login`; unauthenticated `/api/**` returns JSON auth errors; logout invalidates local session for both auth methods; no existing admin/researcher authorization rules are weakened.
    Handover:
    - Existing `WebSecurityConfig` behavior remains unchanged: local form login and conditional Keycloak OAuth2 login share `/login`, OAuth2 callback/authorization endpoints are permitted, and API/MVC exception handling remains split between JSON auth errors and MVC redirects.
    - Contract tests now lock OAuth2 authorization redirect behavior, OAuth2 callback failure handling, POST-only logout, local form-login logout, and logout for the bridged Keycloak local `User` principal.
    - Existing API and MVC security contracts cover JSON 401/403 behavior and unauthorized admin/MVC redirects.

  - [x] `H42.5` **Auth regression tests and documentation notes.** *(completed 2026-04-26)*
    Add focused tests for local login preservation, login page SSO link, OAuth2 user provisioning, existing-user role preservation, locked-user rejection, and unknown first-time Keycloak user auto-creation. Document required Keycloak env vars in the relevant project config/docs surface.
    Exit criteria: targeted auth/security tests pass; compile and frontend verification pass; `.env.example` or equivalent config guidance includes the Keycloak variables without real secrets.
    Handover:
    - `AuthViewControllerSecurityContractTest` covers local form-login preservation, login-page Keycloak CTA, bundled login assets, and logout contracts.
    - `KeycloakOAuth2LoginSuccessHandlerTest` covers existing-user role/profile preservation, new verified-email `RESEARCHER` provisioning, generated local password secret use, email normalization, invalid email claims, locked-user rejection, and local `User` principal bridging.
    - `.env.example` and `docs/authentication.md` document the Keycloak environment variables, redirect URI, verified-email requirement, local-user bridge, ignored Keycloak roles/groups, and app-only logout behavior without real secrets.
    - Verification passed: `./gradlew test --tests "*Keycloak*OAuth2*" -q`, `./gradlew test --tests "*AuthViewController*SecurityContractTest" -q`, `./gradlew compileJava`, `./gradlew test -q`, `npm run verify-assets`, `npm run verify-template-assets`.

  Assumptions:
  - Single Keycloak registration id is `keycloak`.
  - Keycloak is the only institutional auth integration; no in-app institution selector.
  - First-time Keycloak users are auto-created as local `RESEARCHER` users.
  - Role elevation remains local/admin-managed; Keycloak roles are not mapped to `PLATFORM_ADMIN` or `SUPERVISOR` in this task.
  - Forgot password and self-registration remain out of scope.

- [x] `H43` Error Pages Option B — contextual recovery surfaces. *(completed 2026-04-26)*
  Goal: modernize `403`, `404`, `500`, generic error, and shared not-found pages per `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` §2.5 Option B.
  Deliverable: theme-aware error pages that keep authenticated users inside the ScholarDex app shell, provide clear recovery actions, and show context-specific guidance for permission, not-found, and server-error cases.
  Design reference: `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` §2.5 Option B; `docs/ux-design-guide.md` §7.6.
  Exit criteria: authenticated errors render with sidebar/navbar/footer; unauthenticated errors render as a standalone ScholarDex-centered surface; no error template loads Bootstrap/CDN scripts; 403 explains permission recovery, 404 offers search/browse suggestions, 500 includes retry action plus timestamp/request context where available; `shared/not-found.html` uses the same visual/error pattern; targeted MVC/template/asset checks pass.

  Subtasks:

  - [x] `H43.1` **Shared error-page presentation baseline.**
    Handover: Added a reusable error-page fragment and bundled error-page CSS/JS through the existing `/assets/app.*` pipeline; legacy Bootstrap/CDN and inline error styling were removed from runtime error templates.

  - [x] `H43.2` **Authenticated shell vs standalone rendering.**
    Handover: Error pages now render inside the app shell when a local `User` principal is present, and render as a standalone ScholarDex surface for unauthenticated sessions.

  - [x] `H43.3` **Context-specific Option B content.**
    Handover: 403 now gives permission guidance, 404 provides browse/search recovery links, 500 shows retry plus timestamp/request context, and generic errors use safe fallback copy.

  - [x] `H43.4` **Controller model metadata.**
    Handover: `CustomErrorController` and `MvcExceptionHandler` now populate consistent error metadata through `ErrorPageModelFactory`.

  - [x] `H43.5` **Shared not-found alignment.**
    Handover: `shared/not-found.html` now uses the shared error-page pattern for missing entity/detail flows.

  - [x] `H43.6` **Regression and guardrail coverage.**
    Handover: Focused MVC tests cover error routing, model metadata, authenticated shell rendering, standalone unauthenticated rendering, and exception-handler metadata; `verify-template-assets` now includes `templates/errors`.

- [x] `H38` User-Reviewed Publication Authorship Overlay. *(completed 2026-04-19)*
  Goal: let researchers confirm or reject authorship for imported publications so noisy Scopus links stop polluting reports, indicators, citations, and workspace views without deleting source data.
  Deliverable: a local authorship-decision layer on top of canonical imported publication links, with review UI, suspicious-publication triage, and reporting/read-model filtering that prefers user decisions over raw source linkage.
  Exit criteria: researchers can mark a publication as `CONFIRMED` or `REJECTED`; rejected publications no longer count toward user-facing reporting, indicators, citations, exports, and workspace lists; confirmed publications remain included even if later imports stay noisy; imported Scopus/DBLP lineage remains preserved and auditable; the system can surface a "needs review" queue for suspicious authorship links instead of requiring users to inspect all publications manually.
  Handover:
  - The full user-reviewed authorship overlay is now in place across workspace review, suspicious triage, bulk decisions, confirmed-only scoring/reporting, cache invalidation, and diagnostics without mutating raw imported lineage.
  - Researchers can review pending publications in one place, constrain review using confirmed affiliation scope, and rely on consistent downstream filtering across user-facing reports, indicators, citations, and exports.
  - Imported linkage and local override state remain distinguishable: the workspace now shows concise provenance on publication rows/details, and the admin publication search exposes compact per-publication override summaries for operational debugging.

  Subtasks:

  - [x] `H38.1` **Authorship decision persistence model.** *(completed 2026-04-16)*
    Add a dedicated persistence model for user-level publication authorship decisions keyed by user + publication, separate from imported Scopus/Scholardex facts.
    Deliverable: document/entity + repository storing `status` (`CONFIRMED` / `REJECTED`), timestamps, decision source, optional reason, and enough immutable context to audit later.
    Exit criteria: imported source facts remain untouched; user decisions can be created, updated, queried, and deleted independently; duplicate decisions per user/publication are prevented.
    Handover:
    - `PublicationAuthorshipDecision` now lives in `scholardex.publication_authorship_decisions` with a unique `userEmail + publicationId` compound index, `CONFIRMED` / `REJECTED` status, `USER_REVIEW` source, timestamps, optional reason, and a compact immutable audit snapshot.
    - `PublicationAuthorshipDecisionRepository` supports single-row lookup, per-user listing, subset lookup by publication ids, and delete-to-clear semantics so implicit pending remains represented by row absence.
    - `PublicationAuthorshipDecisionService` owns upsert/clear/query behavior, validates that the user and publication exist, captures publication/user/authorship snapshot data on write, and leaves imported `ScholardexPublicationFact` / `ScholardexAuthorshipFact` records untouched.
    - Targeted regression coverage now exists in `PublicationAuthorshipDecisionServiceTest` and `PublicationAuthorshipDecisionRepositoryTest`.

  - [x] `H38.2` **Effective-authorship read filtering.** *(completed 2026-04-16)*
    Introduce a publication-authorship overlay in the read/reporting path so user decisions are applied consistently before data reaches indicators, citations, exports, and workspace tabs.
    Deliverable: shared filtering support or projection/read-model layer that excludes locally rejected publications and preserves locally confirmed ones.
    Exit criteria: all user-facing publication/citation/report queries can consume an "effective publications for user" view; no scoring service needs ad-hoc reject logic embedded directly in its scoring rules.
    Handover:
    - `EffectiveAuthorshipReadService` now sits above `ScholardexProjectionReadService`, resolves the user’s raw publication set from canonical author ids, subtracts `REJECTED` publication ids, and re-includes `CONFIRMED` publication ids by direct canonical publication lookup.
    - `UserPublicationFacade` now uses the effective publication set for the main user publication view, and workspace citation drilldown now rejects access when the base publication is not effectively owned by the user.
    - `UserReportFacade` now uses the effective publication set for indicator apply, report-scoped individual report computation, and report-scoped indicator detail, so user-facing report/citation calculations no longer derive owned publications directly from author ids.
    - PostgreSQL reporting views, canonical publication/authorship facts, admin/group/export paths, and scoring rules remain unchanged in this slice; the overlay is applied only in the shared user-scoped read/report assembly layer.
    - Targeted regression coverage now exists in `EffectiveAuthorshipReadServiceTest`, `UserPublicationFacadeTest`, and `UserReportFacadeTest`.

  - [x] `H38.2a` **Confirmed-only scoring inputs.** *(completed 2026-04-16)*
    Make user-scoped scoring and evaluation authoritative by counting only explicitly confirmed publications, while keeping publication discovery broad enough for authorship review.
    Deliverable: a scoring-specific authorship read path and rewired user scoring surfaces that consume only `CONFIRMED` publications, plus a contextual warning when a user has zero confirmed publications.
    Scope:
    - add a scoring-specific read path above `ScholardexProjectionReadService` that returns only confirmed publications for a user
    - rewire user-scoped scoring/evaluation surfaces to use confirmed-only publications:
      - indicator apply
      - evaluation page / report-scoped computation
      - report refresh flows
      - user-scoped scoring exports
    - keep workspace/publication discovery on the broader imported/effective set so pending publications remain reviewable
    - show a warning on scoring/evaluation surfaces only when the user has zero confirmed publications, explaining that only confirmed publications are counted in scoring
    - keep this rule out of the scoring services themselves; filtering stays in the read/assembly layer
    Exit criteria: pending and rejected publications do not contribute to user-scoped scores, totals, charts, or scoring exports; workspace discovery still shows candidate publications for review; users with zero confirmed publications see a clear warning rather than silently misleading results.
    Handover:
    - `EffectiveAuthorshipReadService` now exposes a scoring-specific confirmed-only path via `findConfirmedPublicationsForScoring(...)` and `hasConfirmedPublicationsForScoring(...)`; pending publications no longer enter scoring inputs, while confirmed publications are still reloaded directly by `publicationId`.
    - `UserReportFacade` now uses confirmed-only publications for user-scoped scoring assembly: indicator apply, report-scoped computation, report-scoped detail, and the user scoring export methods; workspace discovery still uses the broader effective-authorship view.
    - Publication-based apply/evaluation surfaces now receive `confirmedPublicationScoringWarning` when the user has zero confirmed publications; activity-only scoring surfaces do not receive that warning.
    - `EvaluationWorkspaceController` now sets the report-level warning based on whether the selected report actually uses publication/citation scoring and whether the user has any confirmed publications for scoring.
    - Targeted regression coverage now exists in `EffectiveAuthorshipReadServiceTest`, `UserReportFacadeTest`, and `EvaluationWorkspaceControllerContractTest`.

  - [x] `H38.3` **Inline confirm/reject actions in researcher publication surfaces.** *(completed 2026-04-16)*
    Add authorship confirmation/rejection controls to the main user-facing publication views, starting with the workspace publications tab and any remaining publication detail/apply flows where authorship confusion is visible.
    Deliverable: UI actions `Confirm mine` / `Reject authorship`, optimistic feedback, and visible authorship state on affected rows/details.
    Exit criteria: a researcher can review and decide authorship from the normal publication workflow without admin intervention; state persists and reflects immediately in the same surface.
    Handover:
    - The workspace publications tab now uses a review-oriented publication list instead of the filtered effective-authorship set, so pending, confirmed, and rejected publications all remain visible for inline review.
    - `UserPublicationsViewModel` now carries `authorshipReviewStateByPublicationId`, and the workspace publications endpoint returns per-publication review state with `PENDING`, `CONFIRMED`, or `REJECTED`, plus optional reason and `updatedAt`.
    - `ResearcherWorkspaceController` now exposes workspace-only authorship decision endpoints: confirm, reject, and clear. All are authenticated and return a compact decision-state JSON response for in-place UI updates.
    - The workspace publications detail panel now includes an “Authorship” section with row-level status badges, one-click confirm, inline two-step reject confirmation, clear decision, and inline success/error feedback. Rejected rows stay visible in place.
    - Targeted regression coverage now exists in `UserPublicationFacadeTest`, `PublicationAuthorshipDecisionServiceTest`, and `ResearcherWorkspaceControllerContractTest`.

  - [x] `H38.4` **Suspicious-authorship triage queue.** *(completed 2026-04-16)*
    Create a targeted "needs review" queue so users are asked only about likely false positives instead of every imported paper.
    Deliverable: heuristics and/or rule-based flags for suspicious authorship links (name mismatch, affiliation mismatch, topic jump, low evidence overlap, etc.) plus a dedicated queue/list in the user workspace.
    Exit criteria: the queue is populated deterministically from explicit heuristics; each flagged publication explains why it was flagged; researchers can confirm/reject directly from the queue.
    Handover:
    - The workspace Publications tab now includes a built-in `Needs review` filter mode rather than a separate page. It is driven by `suspiciousAuthorshipByPublicationId` plus `suspiciousPendingCount` on `UserPublicationsViewModel`, so the queue stays inside the existing master-detail review flow.
    - `SuspiciousAuthorshipTriageService` computes deterministic pending-only suspicion flags from current canonical data using three explicit rules: `NAME_MISMATCH`, `NO_AFFILIATION_OVERLAP`, and `SECONDARY_ID_ONLY`. No suspicion state is persisted.
    - `UserPublicationFacade.buildWorkspacePublicationsView(...)` now enriches the workspace publication payload with suspicious-authorship metadata while leaving scoring and legacy publication surfaces unchanged.
    - The workspace UI now shows a review summary bar, `All` / `Needs review` filters, row-level `Needs review` badges, and a detail-panel explanation block listing the exact heuristic reasons. Confirming or rejecting an item while filtered removes it from the queue immediately and advances context to the next flagged row when possible.
    - Targeted regression coverage now exists in `SuspiciousAuthorshipTriageServiceTest`, `UserPublicationFacadeTest`, `ResearcherWorkspaceControllerContractTest`, and the updated `UserViewControllerContractTest`.

  - [x] `H38.5` **Bulk review workflow.** *(completed 2026-04-18)*
    Support efficient cleanup of polluted Scopus identities by allowing multi-select or repeated queue decisions without opening each publication individually.
    Deliverable: bulk confirm/reject actions with safeguards, summary counts, and undo/rollback-friendly handling where practical.
    Exit criteria: researchers can clear multiple false-positive publications in one operation; accidental mass rejection is guarded by confirmation UX and auditable persisted decisions.
    Handover:
    - The workspace publications experience now treats `Pending Review` as a first-class filter with dedicated pending, suspicious-pending, and recommended-pending summary counts on `UserPublicationsViewModel`, rather than limiting review acceleration to the suspicious queue only.
    - `PublicationAuthorshipDecisionService` now exposes best-effort bulk confirm/reject handling for pending publications only, reusing the existing per-publication decision path, preserving the affiliation-scope eligibility gate, and returning per-item success/failure results instead of one aggregate success state.
    - `ResearcherWorkspaceController` now exposes `POST /user/workspace/publications/authorship/bulk`, with request payload `{ publicationIds, action, reason? }` and response payloads that distinguish succeeded ids, failed ids with messages, and updated review states for successful rows.
    - The workspace publications frontend now supports pending-row selection, current-view select-all, bulk confirm/reject actions, mixed-result feedback, and explicit `Recommended accept` labeling for non-suspicious pending publications while preserving the existing single-item review flow and suspicious reason details.
    - Targeted regression coverage now exists in `PublicationAuthorshipDecisionServiceTest`, `UserPublicationFacadeTest`, `ResearcherWorkspaceControllerContractTest`, and `UserViewControllerContractTest`.

  - [x] `H38.6` **Indicator/report/export integration.** *(completed 2026-04-18)*
    Apply effective-authorship filtering to all user-facing reporting outputs that currently assume imported authorship is correct.
    Deliverable: indicator apply views, report computation, citation lists, workbook exports, and workspace summary counts all use the same effective-authorship layer.
    Exit criteria: rejecting a publication removes it from scores, totals, charts, and exports consistently; confirming a publication preserves inclusion consistently.
    Handover:
    - User-scoped reporting and export computation in `UserReportFacade` now consistently uses the confirmed-only/effective-authorship layer for indicator apply, report-scoped computation, citation detail assembly, indicator workbook export, and both CNFIS workbook export variants.
    - The remaining freshness gap is now closed: `PublicationAuthorshipDecisionService` invalidates user-scoped reporting caches after successful confirm, reject, clear, and successful bulk review mutations, so the next evaluation/detail read recomputes from the latest confirmed publication set instead of reusing stale persisted output.
    - `UserIndicatorResult.Mode.LATEST` rows and transient `UserIndividualReportRun` rows are now treated as disposable caches; durable `SNAPSHOT` indicator results and `EvaluationSnapshot` history remain untouched by authorship-decision invalidation.
    - No user-facing controller or export contract changed in this slice; existing endpoints continue to work, but their next read after an authorship decision is now fresh by construction.
    - Targeted regression coverage now exists in `PublicationAuthorshipDecisionServiceTest`, `UserIndicatorResultServiceTest`, `UserIndividualReportRunServiceTest`, and the existing `UserReportFacadeTest` confirmed-only scoring/export coverage remains in place.

  - [x] `H38.7` **Operational diagnostics and auditability.** *(completed 2026-04-19)*
    Make authorship overrides explainable for both users and maintainers.
    Deliverable: concise provenance on publication rows/details ("Imported from Scopus, locally rejected by user on {date}") and admin/debug visibility into decision state without losing raw source lineage.
    Exit criteria: support/debug flows can distinguish imported linkage from local override decisions; users can see the current authorship status and when it changed.
    Handover:
    - The workspace publications tab now renders concise provenance directly in both the row and the detail view: pending items show imported source lineage, while confirmed/rejected items show imported lineage plus the local decision outcome and decision date; stored decision reasons are also shown in the detail panel when present.
    - This slice keeps the user-facing provenance lightweight by deriving it from existing publication ids (`eid`, `wosId`, `googleScholarId`) plus the current authorship review state, without introducing a new persisted provenance model just for display.
    - The existing admin publication search page now exposes a compact `Authorship overrides` summary column per publication row, including total override count, confirmed/rejected split, and latest decision status/date.
    - `PublicationAuthorshipDecisionRepository` now supports publication-scoped decision lookup across users, and `PostgresScholardexAdminReadPort` aggregates that into `PublicationAuthorshipDecisionAdminSummary` for admin/debug read flows.
    - Targeted regression coverage now exists in `AdminScholardexPublicationViewControllerContractTest`, `PublicationAuthorshipDecisionRepositoryTest`, `ResearcherWorkspaceControllerContractTest`, and `UserPublicationFacadeTest`.

- [x] `H44` Phase B — Tier 3.1 Shared Component Library (Option C). *(completed 2026-04-28 by closure audit)*
  Goal: lock the ScholarDex design system before scaling Tier 2 (admin form modernization) and further UX work by building all missing shared fragments and JS utilities enumerated in the Tier 3.1 Option C decision, then documenting them in `docs/frontend-conventions.md` and migrating existing ad-hoc usages where the diff is small.
  Design reference: `docs/tasks/closed/h41-ux-redesign-plan-after-tier1.md` §3.1 Option C.
  UX guide reference: `docs/ux-design-guide.md` §6.2, §6.3, §6.5, §6.6, §6.7, §7.1, §8.1, §8.2.
  Exit criteria: all 10 fragments/components listed below are built, tested, and importable; `docs/frontend-conventions.md` documents each component's shape, variants, and usage contract; existing ad-hoc implementations are migrated to the shared components where the diff is ≤ a few lines per call site; `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava` all pass clean; both light and dark themes render correctly for every new component.

  Subtasks:

  - [x] `H44.1` **`confirmation-dialog` fragment and JS API.** *(completed 2026-04-26)*
    Extract the pattern used ad-hoc in conflicts (bulk resolve/dismiss), bulk select (publisher reassign, group assign), and two-click delete flows into a single reusable `confirmation-dialog(id, title, body, confirmLabel, tone)` Thymeleaf fragment backed by a `confirmationDialog.js` module.
    The JS API must expose `window.appConfirmDialog.open({ dialogId, onConfirm, onCancel })` so callers never manage modal lifecycle directly. The confirm button must use the `--danger` accent when `tone="danger"` and the default primary accent otherwise. Focus must be trapped inside the dialog; Escape cancels; ARIA `role="alertdialog"`, `aria-modal="true"`, `aria-labelledby`, `aria-describedby`.
    Exit criteria: fragment renders a functional confirmation dialog in any template; all existing ad-hoc confirm flows in `conflicts.html`, `user-defined-triage.html`, workspace delete, and bulk select are migrated to the shared fragment; `npm run build` and both verify-assets scripts pass.

  - [x] `H44.2` **Toast notification system.** *(completed 2026-04-26)*
    Build a `toastManager.js` module and supporting `shared-toasts.css` that provide an ephemeral feedback queue: `window.appToast.show({ message, tone, duration, actionLabel, onAction })`. Tones: `success`, `error`, `warning`, `info`. Default duration 4 s; `duration: 0` means sticky until dismissed. Queue renders as a fixed stack in the bottom-right corner (per §8.1), max 5 visible, older toasts pushed up. Each toast is dismissible by X button. ARIA `role="status"` for non-error tones, `role="alert"` for error; `aria-live="polite"` on the container. Both themes.
    Migrate at least the inline `#eval-snapshot-feedback` span and workspace publications save-feedback span to `appToast.show()`.
    Exit criteria: `appToast.show()` is usable from any page after `app.js` loads; toasts stack, auto-dismiss, and are keyboard/screen-reader accessible; migrated call sites no longer use inline feedback spans; `npm run build` passes.

  - [x] `H44.3` **`pagination` fragment (server-side and client-side variants).** *(completed 2026-04-26)*
    Promote the ad-hoc pagination patterns in the publications catalog, citations page, conflicts queue, and workspace tabs into a single `pagination(page, totalPages, baseUrl, pageSizeOptions, currentPageSize)` fragment for server-side contexts and a `clientPagination.js` utility (wraps existing workspace publications/activities pagination logic) for client-side contexts.
    Server-side fragment: prev/next buttons, current-page display, first/last buttons for jump, page-size `<select>` that posts/gets with `size=` param — all per §6.3. ARIA `role="navigation"`, `aria-label="Pagination"`. Both themes.
    Client-side utility: exported `initClientPagination({ data, pageSize, renderFn, container })` replacing the hand-rolled per-module pagination in `workspacePublications.js` and `workspaceActivities.js`.
    Exit criteria: server-side fragment renders and navigates correctly on publications catalog, citations, conflicts, and triage pages; client-side utility replaces duplicated paging code in workspace modules; `npm run build` and all verify scripts pass.

  - [x] `H44.4` **`filter-panel` fragment.** *(completed 2026-04-26)*
    Promote the integrated filter patterns from `.app-queue-filter` (conflicts/triage) and the publications catalog inline filter to a shared `filter-panel(formId, method, action, fields)` fragment. Fields are passed as a list of `FilterFieldDef` objects (name, label, type: `text|select|date`, options list for select). The panel has a visually connected header that folds into the table's top border (per §6.3), a Reset link that clears all fields and submits, and an Apply button that submits the form. ARIA `role="search"` on the form. Responsive: collapses to a stack on narrow screens.
    - Added `FilterFieldDef` / `FilterOptionDef`, shared `filter-panel(...)` Thymeleaf fragment, and `shared-filter-panel.css`.
    - Migrated admin conflicts and Scholardex publication catalog filter panels to the shared fragment while preserving hidden cross-link filters for publication catalog applies.
    Migrate conflicts, triage, and publications catalog filter blocks to the shared fragment.
    Exit criteria: shared fragment renders in all three migrated contexts; existing filter behavior is preserved; `npm run build` and all verify scripts pass.

  - [x] `H44.5` **`stat-card` fragment.** *(completed 2026-04-26)*
    Consolidate the ad-hoc `.app-summary-grid` / `stat-card` pattern (used across admin dashboard, conflicts, users, publications, citations, evaluation aggregate panel) into a single `stat-card(label, value, accent, contextLine, icon)` Thymeleaf fragment. Accent values: `primary`, `success`, `warning`, `danger`, `neutral`. Context line is optional secondary text (e.g. "last 30 days"). Icon is optional Font Awesome class. Cards compose into a grid via a `stat-card-grid(cards)` fragment that auto-reflows to single column on mobile (per §4.4).
    - Added `StatCardDef`, shared `stat-card(...)` and `stat-card-grid(cards)` fragments, and primary/neutral/icon styling on the existing summary-card foundation.
    - Migrated admin conflicts, users, Scholardex publications, and Scholardex citation detail stat grids to the shared grid fragment with render-contract assertions.
    Migrate all existing `.app-summary-grid` usages that are ≤ 5 cards with static values to the shared fragment.
    Exit criteria: fragment renders correctly on admin users, publications, citations, conflicts, and evaluation aggregate panels; single-column reflow works at 576 px; both themes; `npm run build` passes.

  - [x] `H44.6` **Generic `breadcrumb` fragment.** *(completed 2026-04-26)*
    Add a non-admin `breadcrumb(items)` fragment mirroring the existing `admin-breadcrumb` shape but using workspace-appropriate styling (lighter, no admin-sidebar assumption). Items are `BreadcrumbItem` objects with `label` and optional `href`. The last item renders as `aria-current="page"` without a link. Integrate with the existing `admin-breadcrumb` so both share the same CSS class structure and can be toggled by a `variant` param (`admin` vs `default`), or keep as two separate named fragments if the diff is trivial.
    Apply generic breadcrumbs to: workspace sub-flows that need back-navigation context (e.g. evaluation criterion deep links), public pages (Tier 2.3 prep).
    Exit criteria: fragment renders in at least two non-admin contexts; `npm run build` passes; screen reader announces breadcrumb correctly.
    - Added `BreadcrumbItem`, shared `breadcrumb(items, variant)` fragment, and `admin-breadcrumb(items)` wrapper using the same class structure.
    - Applied default breadcrumbs to forum detail, WoS category detail, and university detail; migrated Scholardex citation detail to the admin wrapper.

  - [x] `H44.7` **`admin-form` fragment.** *(completed 2026-04-26)*
    Build a `admin-form(id, action, method, title, sections, submitLabel, cancelHref)` Thymeleaf fragment providing the shared admin form shell: sticky header with title and Save/Cancel controls, `<section>` blocks with heading and helper text slot, consistent field layout (label above input, error message below), and CSRF token injection. Replaces the ad-hoc form layouts in the long-form admin edit pages (`edit-individualReport.html`, `edit-groupReport.html`, `indicators-edit.html`).
    CSS: `.app-admin-form` BEM block in `admin-tables.css` (or new `admin-forms.css`); sticky header uses `position: sticky; top: 0` with a white/dark background so it stays visible during scroll.
    Exit criteria: fragment is usable by at least one long-form admin edit page (apply to `edit-individualReport.html` as the pilot); Save/Cancel sticky behavior works; `npm run build` and `verify-template-assets` pass.
    - Added the shared `admin-form(...)` and `admin-form-section(...)` fragments with CSRF injection, sticky Save/Cancel controls, section helper text, and slotted body content.
    - Added `admin-forms.css`, imported it into the bundled frontend assets, and migrated `edit-individualReport.html` as the pilot long-form edit page.
    - Covered the pilot with a controller render test asserting the shared shell, sticky action header, cancel route, and section output.

  - [x] `H44.8` **`modal-shell` fragment.** *(completed 2026-04-26)*
    Build a `modal-shell(id, title, size, footerSlot)` Thymeleaf fragment providing a reusable Bootstrap modal wrapper with: `role="dialog"`, `aria-modal="true"`, `aria-labelledby` wired to title, focus trap (first focusable element on open, returns to trigger on close), Escape-key close via JS, and consistent header/body/footer layout. Wrap the existing `#editUserModal` in `users.html` and the bulk-action modals in publications/users as the pilot migration.
    The JS module (`modalShell.js` or integrated into `app.js`) must expose `window.appModal.open(id)` and `window.appModal.close(id)` as a thin wrapper above Bootstrap's modal API, adding the focus-trap and return-focus behavior that Bootstrap 5 does not guarantee consistently.
    Exit criteria: `modal-shell` renders three existing modals correctly; focus trap and return-focus work; Escape closes; `npm run build` and both verify-assets scripts pass.
    - Added shared `modal-shell(...)` with title wiring, close control, body/footer slots, and the `data-app-modal-shell` marker for JS behavior.
    - Added `modalShell.js` exposing `window.appModal.open(id)` / `window.appModal.close(id)`, including focus trap, Escape close, backdrop close, Bootstrap-style lifecycle events, and return-focus handling.
    - Migrated `#editUserModal`, `#assignGroupModal`, and `#reassignForumModal`; legacy modal handling now skips migrated shell modals.
    - Covered the users and publication catalog pilots with render-contract assertions for shell IDs, ARIA labels, and shell markers.

  - [x] `H44.9` **`search-input` fragment.** *(completed 2026-04-26)*
    Build a `search-input(id, name, placeholder, value, kbdHint, clearable)` fragment that produces a styled search input with optional `<kbd>` shortcut hint (hidden when input is focused, per `app-ws-search__hint` pattern) and optional clear (×) button. Mirrors the existing `app-ws-search__field` shape from the workspace but packaged as a standalone fragment usable outside the workspace (admin catalog filter search, public page search, evaluation page search). CSS goes into `shared-forms.css` (new file) or is added to `admin-tables.css`.
    Apply the fragment to: the publications catalog title-search field, the evaluation page (if a search input is present), and the workspace unified-search field (as a drop-in swap).
    Exit criteria: fragment renders in all three applied contexts; clear button empties the input and triggers form submit or `input` event; shortcut hint shows/hides correctly; both themes; `npm run build` passes.
    - Added shared `search-input(...)` with optional keyboard hint, clear button, accessible icon treatment, and bundled clear-button behavior via `searchInput.js`.
    - `filter-panel(...)` now renders text fields through `search-input(...)`, covering the Scholardex publications title search while preserving form submission.
    - Migrated the workspace unified search and public directory searches for forums, core rankings, and WoS categories while preserving existing input IDs for current JS modules.
    - Covered the migrated admin/public/workspace contexts with targeted render/template assertions.

  - [x] `H44.10` **Button group and icon-button conventions documentation.** *(completed 2026-04-26)*
    No new code required. Audit the existing `.app-admin-icon-btn` usages and the workspace action-button patterns and write up the button taxonomy in `docs/frontend-conventions.md`: icon-only action button, labeled action button, danger variant, disabled state, button group with divider. Include HTML snippet examples, ARIA requirements (every icon-only button must have `aria-label`), and tone-to-CSS-class mapping. Flag any existing call sites that are missing `aria-label` and fix them as part of this subtask.
    Exit criteria: button-group section added to `docs/frontend-conventions.md`; all icon-only admin buttons across all admin templates carry `aria-label`; `npm run verify-ui-guardrails` passes.
    - Added button taxonomy guidance to `docs/frontend-conventions.md` covering icon-only buttons, labeled actions, tone classes, disabled state, and action groups with snippets.
    - Audited admin icon-only controls; `.app-admin-icon-btn` call sites were already labeled, and repeated admin scroll-to-top anchors now carry `aria-label="Scroll to top"`.

  - [x] `H44.11` **`frontend-conventions.md` documentation pass and ad-hoc migration cleanup.** *(completed 2026-04-27)*
    With all components built, do a final pass:
    — Add a "Shared Components" section to `docs/frontend-conventions.md` documenting each of the 10 components: purpose, Thymeleaf fragment signature, JS API (if any), CSS block name, variants, and when to use vs. when not to use.
    — List the existing components that were already built before H44 (`tab-bar`, `admin-breadcrumb`, `admin-empty-state`, skeleton loader, shortcuts overlay, bulk-select, column-toggle) with the same documentation shape for completeness.
    — Identify any remaining ad-hoc usages of patterns now covered by shared components that were not migrated in H44.1–H44.9 and file them as known technical debt in a `docs/tasks/closed/h44.11-component-library-debt.md` note (do not attempt a full sweep in this subtask).
    — Run the full verification suite and confirm a clean pass.
    Exit criteria: `docs/frontend-conventions.md` "Shared Components" section is complete and accurate; `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-ui-guardrails`, and `./gradlew compileJava` all pass; debt note filed if any remaining ad-hoc usages were found.
    - Added the shared component catalog to `docs/frontend-conventions.md`, covering all H44 components plus the pre-existing tab bar, empty state, skeleton, shortcuts, bulk-select, and column-toggle utilities.
    - Filed remaining ad-hoc usages in `docs/tasks/closed/h44.11-component-library-debt.md` for future focused migrations.
    - Completed the H44 verification closeout with build, asset/template/route/UI guardrails, and Java compilation.

## H39 Admin Operations Center — DONE 2026-04-19

All 8 subtasks complete. Live admin dashboard replaces static placeholder: five stat cards (open conflicts, pending triage, researchers, publications, last sync) with real counts; three operation status cards (Initialization, WoS Enrichment, Incremental Updates) with live badges and "Run Now" action for WoS enrichment; confirmation dialog with full accessibility (focus trap, Escape, ARIA); recent-activity feed (up to 10 events, relative timestamps); quick-links toolbar above the stat grid; sidebar reorganized with "Operations Center" as the hub; responsive `@media (max-width: 576px)` breakpoints; all `aria-label` attributes evaluated by Thymeleaf; `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `compileJava` all pass clean.

Key files: `AdminDashboardService.java`, `AdminDashboardViewModel.java`, `AdminOperationStatus.java`, `AdminActivityEvent.java`, `AdminViewController.java`, `templates/admin/dashboard.html`, `frontend/src/styles/admin-dashboard.css`, `static/js/admin-dashboard.js`, `templates/fragments.html`.

## H37.10 Legacy template cleanup and verification — DONE 2026-04-18

Completed cleanup of dead routes and templates:
- Deleted orphaned `user/indicators-apply.html` (no controller ever returned it post-H37.1).
- Removed redirect shims from `UserViewController`: `GET /indicators`, `GET /indicators/apply/{id}`, `POST /indicators/apply/{id}/refresh`, `GET /individual-reports`, `GET /individual-reports/view/{id}`, `POST /individual-reports/view/{id}/refresh`, `POST /individual-reports/view/{id}/refresh-all-indicators`. The live Excel export (`GET /indicators/export/{id}`) was retained.
- Removed now-unused `UserIndicatorResultService` and `UserIndividualReportRunService` fields/imports from `UserViewController`.
- Fixed stale notification link in `ResearcherWorkspaceController`: `/user/individual-reports` → `/user/evaluation`.
- Removed `indicators-apply.html` entry from `verify-route-guardrails.js`.
- All verification scripts pass: `compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-ui-guardrails`.

## H37.6 What-if analysis — DROPPED 2026-04-18

Removed from backlog. The stub backend endpoints (`POST /what-if`, `GET /breakdown/{indicatorId}`) and associated records (`WhatIfRequest`, `WhatIfItem`, `WhatIfResponse`, `BreakdownItem`, `BreakdownResponse`) and the `computeHypotheticalItemScore` helper have been deleted from `EvaluationWorkspaceController`. No frontend code was written. `extractScoredItems` was kept — it is still used by the indicator detail endpoint.

## H37.7 Per-criterion score breakdown charts — DROPPED 2026-04-18

Removed from backlog together with H37.6. No code was written for this feature.

## H29 Admin Incremental Source Updates From Uploaded WoS And Scopus Files

Archived from `TASKS.md` on 2026-04-02 after backlog bookkeeping confirmed the remaining `H29.6` Scopus maintenance slices had already landed in code, tests, and durable docs.

- [x] `H29` Admin incremental source updates from uploaded WoS and Scopus files.
  Goal: add an operator page parallel to `/admin/initialization` for incremental source updates, starting with WoS and Scopus, where each run is driven by a single uploaded file instead of pre-staged data on disk.
  Deliverable: admin upload/update surface plus source-specific incremental ingest orchestration for WoS and Scopus, accepting one file per operation (`WoS JSON` or government Excel for WoS; `Scopus JSON` for Scopus) and routing the uploaded payload through the existing canonical maintenance pipeline needed for safe incremental updates.
  Exit criteria: operators can trigger incremental WoS and Scopus updates from the browser without relying on filesystem-resident import inputs; file validation and operator feedback are explicit; incremental runs remain replay-safe, scoped to the uploaded payload, and aligned with existing fact/canonical/projection maintenance contracts.
  Status: completed on 2026-04-02.
  Handover:
  - `/admin/incremental-updates` is now the dedicated operator surface for upload-driven WoS and Scopus maintenance, with explicit post-upload follow-up actions and admin-only guardrails.
  - WoS incremental maintenance is now fully lineage-scoped end to end: upload ingest, fact build, category enrichment, and projection refresh all stay tied to the stored `sourceType + sourceFile + sourceVersion` context.
  - Scopus incremental maintenance now follows a batch-scoped contract for upload-driven and scheduler-driven runs, with checkpoint resume disabled for incremental canonicalization and a non-destructive batch projection refresh path distinct from full rebuild semantics.
  - Durable operator/contributor guidance now treats incremental uploads and scheduler maintenance as first-class, replay-safe maintenance modes separate from `/admin/initialization`.
  - Archived contract source of truth: `docs/tasks/closed/h29.6a-incremental-vs-full-scopus-maintenance-contract.md`.
  Subtasks:
  - [x] `H29.1` Lock the admin incremental-update page and upload contract.
    Handover:
    - Implemented as dedicated admin MVC surface under `/admin/incremental-updates`, linked from `/admin/initialization` and the admin operations sidebar.
    - Contract locked with `POST /admin/incremental-updates/wos` and `POST /admin/incremental-updates/scopus`, multipart validation, explicit WoS `sourceType`, optional WoS `sourceVersion`, and admin-only MVC/security coverage.
  - [x] `H29.2` Implement incremental WoS upload orchestration.
    Handover:
    - `/admin/incremental-updates/wos` now runs synchronous uploaded-file WoS ingest plus fact-building, preserving the uploaded filename as `sourceFile` lineage and inferring/overriding `sourceVersion` with the same WoS rules as the existing pipeline.
    - Uploaded WoS runs intentionally skip checkpoint resume, category enrichment, projections, and WoS onboarding in this slice; targeted ingest/service/controller tests cover JSON, government Excel, replay/update semantics, and clear operator errors.
  - [x] `H29.2a` Add upload-scoped WoS post-upload maintenance on the incremental-updates page.
    Handover:
    - Successful WoS uploads now store the last upload lineage in session as `sourceType + sourceFile + sourceVersion`, render that context back on `/admin/incremental-updates`, and enable a dedicated WoS post-upload panel with upload-scoped “Enrich Category Rankings” and “Rebuild Projections” actions.
    - The new WoS follow-up service resolves the exact stored lineage through `WosImportEventRepository`, runs scoped category enrichment only for the uploaded WoS lineage, and rebuilds PostgreSQL WoS reporting rows only for the affected journals instead of truncating and rebuilding the full corpus.
    - Controller/security/service regression coverage now protects missing-session rejection, admin-only access, scoped enrichment updates, and scoped projection rewrites so the incremental page cannot drift back toward the unsafe global Initialization behavior.
  - [x] `H29.2b` Complete upload-scoped WoS fact building for incremental uploads.
    Handover:
    - The WoS incremental upload path now builds facts only for the uploaded lineage by parsing `sourceType + sourceFile + sourceVersion` through `WosImportEventParserOrchestrator.parseSourceLineage(...)` instead of `parseAllEvents()`.
    - `WosFactBuilderService` now exposes a scoped fact-build entrypoint for exact uploaded lineage processing, while the existing full-corpus fact-builder path remains unchanged for `/admin/initialization`.
    - Targeted WoS upload and fact-builder tests now guard the scoped entrypoint, including the invariant that incremental WoS uploads never widen back to full-corpus parser scans.
  - [x] `H29.2c` Tighten WoS incremental projection maintenance from journal scope to slice scope.
    Handover:
    - The WoS incremental projection follow-up now uses mixed scope: `wos_ranking_view` is refreshed per affected journal via upsert, while `wos_metric_fact`, `wos_category_fact`, and `wos_scoring_view` are deleted and reinserted only for slice keys touched by the uploaded lineage.
    - Incremental follow-up scope is now derived directly from lineage-owned Mongo facts for the stored `sourceType + sourceFile + sourceVersion`, rather than widening every reporting table to all rows on affected journals.
    - Targeted projection/follow-up tests now protect the mixed-scope contract, including the invariants that unrelated slice rows on the same journal are preserved, ranking rows are not deleted during partial refreshes, and Initialization keeps the existing full-corpus rebuild path.
  - [x] `H29.3` Implement incremental Scopus upload orchestration.
    Handover:
    - `/admin/incremental-updates/scopus` now runs synchronous Scopus upload ingest plus fact/canonical materialization via a dedicated upload service, using `SCOPUS_JSON_UPLOAD` as stable import-event lineage and stopping before projections, source-link reconciliation, edge reconciliation, and index maintenance.
    - `ScopusDataService` now supports upload-byte ingest for publications and citations, and its indexed-field readers now handle array-backed payload fields correctly so upload-driven parsing matches the existing Scopus JSON shape.
    - Replay hardening now covers deterministic reuse of existing source links, canonical edges, and open conflicts across repeated Scopus upload/scheduler runs, including authorship, publication-author-affiliation, author-affiliation, citation conflicts, and duplicate-key recovery for canonical author/affiliation writes.
    - Batch membership for unchanged Scopus facts is now refreshed to the current `sourceBatchId`, and scheduler-triggered Scopus canonical/projection maintenance now stays batch-scoped instead of falling back to full rescans.
  - [x] `H29.4` Add the shared admin page, operator feedback, and guardrails.
    Handover:
    - `/admin/incremental-updates` now distinguishes upload-driven incremental runs from broader initialization maintenance, with explicit source-level scope callouts, downstream-maintenance guidance, and clearer framing around success/error flash summaries.
    - Durable operator docs now include incremental uploads as a first-class admin surface in `docs/operational-playbook.md` and `docs/failure-triage.md`, pointing operators back to `/admin/initialization` for skipped downstream maintenance.
  - [x] `H29.5` Add targeted regression coverage for upload-driven incremental updates.
    Handover:
    - Focused MVC contract coverage now exists in `AdminIncrementalUpdatesControllerContractTest`, covering page framing, valid WoS/Scopus uploads, empty upload handling, invalid source/file validation, facade validation errors, and the Scopus post-upload follow-up actions.
    - Security coverage now exists in `AdminIncrementalUpdatesSecurityContractTest`, protecting the page, WoS/Scopus upload actions, and Scopus post-upload maintenance actions against non-admin access.
  - [x] `H29.5a` Add Scopus post-upload maintenance actions on the incremental-updates page.
    Handover:
    - `/admin/incremental-updates` now exposes batch-scoped Scopus projection rebuild, edge reconcile, and source-link repair follow-up actions for the stored upload batch instead of sending operators back to full initialization.
    - The Scopus incremental page copy distinguishes routine batch follow-up from broader recovery or full-rebuild work that still belongs on `/admin/initialization`.
  - [x] `H29.6` Extract non-destructive Scopus incremental maintenance flow from full-rebuild logic.
    Handover:
    - Scopus incremental upload and scheduler maintenance now run through shared batch-scoped canonical/materialization orchestration with `sourceBatchIdFilter` support and `useCheckpoint=false` for incremental paths.
    - `ScopusProjectionBuilderService` now separates full replacement rebuilds from batch refresh behavior, using non-destructive batch refresh rules for citations, authorships, and author-affiliation edges instead of leaking `TRUNCATE`-style semantics into incremental flows.
    - Durable docs and regression tests now lock the incremental-vs-full contract for Scopus so upload/scheduler batches preserve replay safety and cross-batch graph visibility while `/admin/initialization` keeps the explicit full-corpus path.
    Subtasks:
    - [x] `H29.6a` Lock the incremental-vs-full maintenance contract.
      Handover:
      - Archived contract lock: `docs/tasks/closed/h29.6a-incremental-vs-full-scopus-maintenance-contract.md`.
      - The repo now has an explicit decision boundary between full-rescan Scopus maintenance and non-destructive batch-scoped incremental maintenance, including the citation-projection rules enforced by later slices.
    - [x] `H29.6b` Extract batch-scoped canonical/materialization orchestration.
      Handover:
      - `ScopusCanonicalMaterializationService` now owns the shared batch-scoped orchestration for upload-driven and scheduler-driven Scopus maintenance, passing `sourceBatchIdFilter` through fact build, canonicalization, edge reconcile, and projection refresh.
      - Incremental Scopus canonicalization now explicitly disables checkpoint resume by forcing `useCheckpoint=false` whenever a batch-scoped run is active.
    - [x] `H29.6c` Make batch-scoped Scopus projection maintenance non-destructive.
      Handover:
      - `ScopusProjectionBuilderService.rebuildViewsForBatch(...)` now refreshes only the reconstructible batch neighborhood instead of truncating and rebuilding the full reporting corpus.
      - Citation refresh now preserves previously visible cross-batch citations when only one endpoint is in the affected batch scope, rather than deleting edges the batch cannot fully reconstruct.
    - [x] `H29.6d` Separate full-rebuild projection semantics from incremental graph refresh semantics.
      Handover:
      - Scopus projection code now has explicit full-rebuild replacement and batch-refresh paths (`executeFullReplacementWrite(...)` versus `executeBatchRefreshWrite(...)`) instead of sharing one destructive implementation.
      - Full rebuilds still use corpus-wide replacement semantics, while incremental maintenance refreshes citations, authorships, and author-affiliation rows only for affected scopes.
    - [x] `H29.6e` Add regression coverage for non-destructive incremental Scopus maintenance.
      Handover:
      - `ScopusCanonicalMaterializationServiceTest`, `ScopusProjectionBuilderServiceTest`, and `ScopusUpdateSchedulerTest` now protect the batch-scoped contract, checkpoint disabling, and non-destructive citation/edge refresh behavior.
      - Incremental Scopus tests now explicitly guard against `TRUNCATE` leakage and against losing valid citations when only one batch endpoint is affected.
    - [x] `H29.6f` Document Scopus incremental maintenance invariants for operators and contributors.
      Handover:
      - Durable Scopus incremental maintenance guidance now lives in `docs/workflows.md`, `docs/operational-playbook.md`, and `docs/failure-triage.md`.
      - Those docs now describe batch scope, replay safety, checkpoint disabling, non-destructive projection behavior, and the rule that `/admin/initialization` remains the explicit full-rebuild path.

## H30 Shared Shell And Foundation Migration To ScholarDex-Owned UI/UX

Archived from `TASKS.md` on 2026-04-02 after backlog bookkeeping aligned the established shell baseline with the completed H31-H35 wave history.

- [x] `H30` Shared shell and foundation migration to ScholarDex-owned UI/UX.
  Goal: migrate the shared shell off SB Admin 2 and Bootstrap 4-era shell conventions toward a ScholarDex-owned UI/UX system based on `docs/ux-design-guide.md`, with Bootstrap 5-compatible shared-shell implementation, repo-owned behavior, and support for both light and dark themes.
  Deliverable: shared shell cutover for sidebar/topbar/page-shell foundations, Bootstrap 5-compatible shell baseline, repo-owned visual tokens and theme hooks, and aligned shared fragments/templates/docs that preserve the current frontend asset contract and role-aware composition path.
  Exit criteria: authenticated shell work no longer treats SB Admin 2 or Bootstrap 4 shell patterns as acceptable steady state; touched shell behavior moves away from jQuery-driven Bootstrap 4 conventions; shell changes still route through shared fragments and `/assets/app.css` + `/assets/app.js`; touched docs and verification expectations reflect the new ScholarDex-owned shell baseline with light/dark theme support.
  Status: completed on 2026-04-02.
  Handover:
  - The authenticated shared shell now resolves through `fragments.html` plus repo-owned frontend assets, with shared sidebar, topbar, page-header, theme toggle, and page-shell layout owned by ScholarDex rather than an admin-template baseline.
  - Shell-level spacing, typography, surface treatment, bounded content layout, and theme-aware behavior are now part of the active authenticated baseline inherited by later frontend work.
  - Root theme state for migrated shell work is expressed through `data-bs-theme`, and later UI waves inherit that shell/theme contract rather than re-deciding it.
  - Durable contributor-facing shell guidance now lives in `docs/frontend-conventions.md`, `docs/ux-design-guide.md`, and `docs/quality-gates.md`.
  - Archived contract source of truth: `docs/tasks/closed/h30.1-shared-shell-visual-foundation-contract.md`.
  Subtasks:
  - [x] `H30.1` Lock the shared shell and visual-foundation contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h30.1-shared-shell-visual-foundation-contract.md`.
  - [x] `H30.2` Refresh shared sidebar structure and navigation treatment.
    Handover:
    - The authenticated sidebar now follows the shared ScholarDex-owned sidebar composition path with clearer grouping, active-state treatment, and repo-owned shell behavior.
  - [x] `H30.3` Refresh shared topbar and page-header orientation patterns.
    Handover:
    - The shared topbar and page-header now provide the active orientation model for authenticated pages, including the unified page title, toolbar rhythm, and workspace/theme controls.
  - [x] `H30.4` Establish baseline visual primitives for shared shell surfaces.
    Handover:
    - Shell-level visual primitives, theme tokens, typography, spacing, and bounded content layout now live in the frontend asset pipeline and act as the inherited shell baseline for later UI work.
  - [x] `H30.5` Align H30 docs, guardrails, and regression expectations.
    Handover:
    - Active frontend docs and targeted verification guidance now treat the shared ScholarDex shell as the baseline for later migrated frontend work.

## H35 Legacy Asset Extraction And Steady-State Shell/Copy Cleanup

Archived from `TASKS.md` on 2026-04-02 after H35.1-H35.6 completion.

- [x] `H35` Legacy asset extraction and steady-state shell/copy cleanup.
  Goal: remove the remaining SB Admin 2 and Bootstrap 4 dependency from the active ScholarDex frontend baseline, finish the shared shell/footer cleanup that still inherits legacy behavior, and purge migration-era implementation language from page-visible copy so the product surface reads as steady state rather than in-transition.
  Deliverable: migrated and explicitly targeted legacy pages no longer rely on SB Admin 2 stylesheet/script includes or Bootstrap 4 runtime imports as part of their steady-state contract; the shared footer and dark-mode shell background are corrected under the repo-owned theme system; and page-visible copy no longer references migration history, old Bootstrap patterns, or internal modernization language.
  Exit criteria: shared fragments and frontend assets no longer load SB Admin 2 or Bootstrap 4 for the modernized baseline; dark-mode page backgrounds and footer styling are governed by ScholarDex-owned theme tokens rather than legacy overrides; visible footer text is correct and encoding-safe; migration-era page copy is removed from touched surfaces; and docs/guardrails describe the post-H35 frontend baseline as the active steady state.
  Status: completed on 2026-04-02.
  Handover:
  - The authenticated ScholarDex baseline now resolves through shared fragments plus `/assets/app.css` and `/assets/app.js` without SB Admin 2 or Bootstrap 4 shell/runtime assets.
  - Authenticated DataTables-backed list surfaces now use the Bootstrap 5 DataTables integration and the shared app-owned initialization contract.
  - The shared footer, dark-shell background treatment, and authenticated shell surfaces now resolve through ScholarDex-owned theme tokens and shared footer markup.
  - Authenticated page-visible copy no longer leaks migration history or internal frontend-contract language.
  - Active frontend docs and authenticated template guardrails now describe and enforce the post-H35 baseline, while remaining Bootstrap-era debt is explicitly bounded to untouched non-authenticated or deferred legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h35.1-legacy-asset-extraction-contract.md`.
  Subtasks:
  - [x] `H35.1` Lock the legacy asset extraction and steady-state cleanup contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h35.1-legacy-asset-extraction-contract.md`.
  - [x] `H35.2` Extract shared authenticated shell assets off SB Admin 2 and Bootstrap 4.
    Handover:
    - Authenticated templates no longer include `/css/sb-admin-2.min.css`, and shared fragments no longer include `/js/sb-admin-2.min.js`.
    - `frontend/src/app.js` now resolves the authenticated baseline without Bootstrap 4 CSS/JS or `jquery.easing`, while repo-owned shared runtime code handles modal, collapse, tooltip, and scroll-to-top behavior.
    - Shared compatibility styling for authenticated pages now lives in the repo-owned frontend layer rather than SB Admin / Bootstrap 4 shell assets.
  - [x] `H35.3` Replace Bootstrap-4-era DataTables coupling on the active list baseline.
    Handover:
    - The authenticated bundle now uses `datatables.net-bs5` from `frontend/src/app.js`, and shared DataTables initialization lives in `frontend/src/modules/shared/tableEnhancer.js` instead of `/js/demo/datatables-demo.js`.
    - Authenticated `admin`, `user`, and `events` templates no longer include the legacy DataTables demo bootstrap script, and the opt-in guardrail now enforces the shared bundle contract instead of page-local script usage.
    - `admin/group-publications`, `admin/institution-publications`, and `admin/scholardex-citations` now render their DataTables surfaces through the ScholarDex `app-table` framing while preserving existing links and table behavior.
  - [x] `H35.4` Fix shared shell and footer steady-state behavior after asset extraction.
    Handover:
    - Shared fragments now render the footer through ScholarDex-owned `app-shell-footer` markup and no longer include `/css/footer-layout.css` in the authenticated asset contract.
    - Shell/footer layout and visual treatment now live in `frontend/src/styles/foundation.css`, including the content-wrapper flex column, footer surface tokens, and a theme-aware footer that reads coherently in both light and dark modes.
    - `app.footer.message` remains the configurable footer source, and authenticated shell backgrounds now use explicit repo-owned content-surface tokens instead of relying on legacy footer/shell CSS.
  - [x] `H35.5` Purge migration-era implementation copy from page-visible content.
    Handover:
    - Authenticated page intros, helper copy, and empty-state text now describe the page purpose directly instead of referencing migration history, old Bootstrap patterns, or internal frontend-contract language.
    - Admin workflow, dashboard, and operations pages keep their practical guidance while removing phrases such as “shared contract”, “builder contract”, “old Bootstrap 4”, and similar implementation framing.
    - User workspace/apply pages and the touched list pages now use plain task-oriented copy that remains specific to each page without leaking modernization history.
  - [x] `H35.6` Align docs, guardrails, and final frontend baseline expectations.
    Handover:
    - Active contributor docs now describe the post-H35 authenticated frontend baseline instead of the earlier post-H34 transition state.
    - Authenticated template guardrails now fail on reintroduction of `/css/sb-admin-2.min.css`, `/js/sb-admin-2.min.js`, and `/css/footer-layout.css`.
    - `H35` is archived from the active backlog, and its contract doc now lives under `docs/tasks/closed/`.

## H33 Dashboard, Summary, And Feedback Migration To ScholarDex-Owned UI/UX

Archived from `TASKS.md` on 2026-04-02 after final transition closeout.

- [x] `H33` Dashboard, summary, and feedback migration to ScholarDex-owned UI/UX.
  Goal: migrate dashboard-like and summary-heavy surfaces to ScholarDex-owned patterns that emphasize meaningful metrics, recent activity, empty states, and explicit user feedback while behaving consistently in both light and dark themes.
  Deliverable: refreshed dashboard/summary surfaces for the main user and admin entry views plus consistent feedback patterns for success/error/status messaging and action outcomes in touched UI flows, aligned with the Bootstrap 5-compatible and repo-owned shell/foundation established by `H30`.
  Exit criteria: primary dashboard or summary entry surfaces no longer read as placeholder shells; key summary cards, recent activity or attention blocks, and empty-state guidance are present where intended in both themes; touched flows provide clearer post-action feedback without introducing one-off notification patterns or extending Bootstrap 4-era behavior; and touched docs/tests/guardrails reflect the refreshed summary/feedback UX contract.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated shared, admin, and user summary-heavy surfaces now inherit the ScholarDex-owned dashboard, summary, and feedback baseline established by `H33`.
  - Chart framing, summary-card rhythm, action clustering, empty-state treatment, and explicit feedback/status surfaces are part of the steady state for migrated summary/workspace pages.
  - Bootstrap 4 / SB Admin summary-card and alert presentation are no longer acceptable on already-migrated H33 families; remaining debt is bounded to untouched legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h33.1-shared-dashboard-summary-feedback-migration-contract.md`.
  Subtasks:
  - [x] `H33.1` Lock the shared dashboard/summary/feedback migration contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h33.1-shared-dashboard-summary-feedback-migration-contract.md`.
  - [x] `H33.2` Establish the repo-owned shared dashboard, summary, and feedback foundation.
    Handover:
    - Shared dashboard, summary, and feedback primitives now live under `frontend/src/**` and `/assets/app.css`.
  - [x] `H33.3` Migrate primary shared and admin dashboard/summary surfaces.
    Handover:
    - Primary shared/admin dashboard and summary surfaces now use the shared ScholarDex summary contract instead of SB Admin card stacks and raw alert framing.
  - [x] `H33.4` Migrate primary user dashboard, workspace-summary, and feedback-heavy surfaces.
    Handover:
    - Primary user workspace-summary and feedback-heavy pages now share the same summary and feedback language as the admin dashboard family.
  - [x] `H33.5` Align `H33` docs, guardrails, and regression expectations.
    Handover:
    - Durable frontend docs and quality-gate guidance now treat the H33 summary/feedback baseline as part of the active post-H34 frontend contract.

## H32 Form And Workflow Migration To ScholarDex-Owned UX

Archived from `TASKS.md` on 2026-04-02 after final transition closeout.

- [x] `H32` Form and workflow migration to ScholarDex-owned UX.
  Goal: migrate form-heavy and multi-step user/admin workflows off Bootstrap 4-era interaction assumptions toward Bootstrap 5-compatible, ScholarDex-owned form and workflow patterns so inputs, validation, readonly states, and action structure become predictable and easier to use in both light and dark themes.
  Deliverable: updated form conventions across the highest-value create/edit/workflow pages, including label/input/help-text hierarchy, validation/error treatment, readonly presentation, action hierarchy, clearer multi-step workflow orientation, and replacement of touched Bootstrap 4 modal/tooltip/form assumptions with repo-owned or Bootstrap 5-compatible behavior.
  Exit criteria: touched form/workflow pages follow one consistent input and action pattern in both themes; validation and readonly behavior are clearer and more uniform; touched UI behavior no longer extends jQuery-driven Bootstrap 4 workflow conventions; and touched docs/tests/guardrails capture the updated workflow UX expectations.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated admin and user workflow surfaces now inherit the ScholarDex-owned form, modal, step-flow, and collection-builder baseline established by `H32`.
  - Labels, helper text, readonly treatment, action hierarchy, multi-step framing, and shared modal/workflow surfaces are part of the expected steady state for migrated workflows.
  - Bootstrap 4 modal, tooltip, collapse, and input-group presentation are no longer acceptable on already-migrated H32 families; remaining debt is bounded to untouched legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h32.1-shared-form-workflow-migration-contract.md`.
  Subtasks:
  - [x] `H32.1` Lock the shared form/workflow migration contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h32.1-shared-form-workflow-migration-contract.md`.
  - [x] `H32.2` Establish the repo-owned shared form and workflow foundation.
    Handover:
    - Shared form and workflow primitives now live under `frontend/src/**` and `/assets/app.css`.
  - [x] `H32.3` Migrate primary admin create/edit and modal-heavy workflows.
    Handover:
    - Primary admin create/edit and modal-driven flows now use the shared ScholarDex workflow contract instead of Bootstrap 4-era modal and input-group presentation.
  - [x] `H32.4` Migrate primary user multi-step and apply-style workflows.
    Handover:
    - Primary user multi-step and apply-style workflows now use the same shared step, help-text, action, and selection contract.
  - [x] `H32.5` Align H32 docs, guardrails, and regression expectations.
    Handover:
    - Durable frontend docs and quality-gate guidance now treat the H32 workflow baseline as part of the active post-H34 frontend contract.

## H31 Data-Heavy List And Table Migration To ScholarDex-Owned Patterns

Archived from `TASKS.md` on 2026-04-02 after final transition closeout.

- [x] `H31` Data-heavy list and table migration to ScholarDex-owned patterns.
  Goal: migrate ScholarDex’s table and list surfaces off Bootstrap 4-era assumptions toward Bootstrap 5-compatible, ScholarDex-owned list/table patterns that remain consistent, legible, and role-appropriate across shared, admin, and user views in both light and dark themes.
  Deliverable: standardized table/list treatment for titles, filtering, pagination, row states, status badges, identifier presentation, and empty-state behavior across the primary data-heavy pages, with touched surfaces converging on ScholarDex-owned patterns and retiring BS4 DataTables dependency where those surfaces are modernized.
  Exit criteria: the main list/table surfaces present consistent filtering, pagination, status, and empty-state behavior in both themes; legacy Bootstrap 4 full-grid/bordered styling is removed from touched views; touched list/table behavior no longer extends BS4 DataTables or Bootstrap 4-only markup assumptions; and touched tests/docs/guardrails reflect the standardized table UX contract.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated shared, admin, and user list/table surfaces now inherit the ScholarDex-owned table/list baseline established by `H31`.
  - Filter panels, toolbar metadata, responsive overflow, empty states, pager treatment, and table semantics are part of the steady state for migrated list/table pages.
  - Bootstrap 4 full-grid table presentation and BS4 DataTables styling are no longer acceptable on already-migrated H31 families; remaining debt is bounded to untouched legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h31.1-shared-list-table-migration-contract.md`.
  Subtasks:
  - [x] `H31.1` Lock the shared list/table migration contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h31.1-shared-list-table-migration-contract.md`.
  - [x] `H31.2` Establish the repo-owned shared table/list foundation.
    Handover:
    - Shared table and list primitives now live under `frontend/src/**` and `/assets/app.css`.
  - [x] `H31.3` Migrate primary shared and admin list/table surfaces.
    Handover:
    - Primary shared/admin list surfaces now use the shared ScholarDex list-table contract instead of Bootstrap 4-era table presentation.
  - [x] `H31.4` Migrate primary user list/table surfaces.
    Handover:
    - Primary user list surfaces now use the same shared list-table contract with aligned toolbar, state, and overflow behavior.
  - [x] `H31.5` Align H31 docs, guardrails, and regression expectations.
    Handover:
    - Durable frontend docs and quality-gate guidance now treat the H31 list-table baseline as part of the active post-H34 frontend contract.

## H34 Accessibility, Responsive Behavior, And Migration Consistency Closeout

Archived from `TASKS.md` on 2026-04-02 after H34.1-H34.8 completion.

- [x] `H34` Accessibility, responsive behavior, and migration consistency closeout.
  Goal: close the modernization wave by enforcing accessibility, responsive behavior, and cross-surface consistency expectations across the migrated ScholarDex-owned UI/UX system.
  Deliverable: targeted accessibility/responsive fixes, cross-theme consistency cleanup, and removal of stale Bootstrap 4/SB Admin remnants across the surfaces modernized by `H30`-`H33`, with aligned docs and verification expectations for the updated frontend baseline.
  Exit criteria: the modernized shell, table/list, form/workflow, and dashboard surfaces meet the repo’s intended accessibility and responsive expectations in both light and dark themes; keyboard/focus/state/empty-state/responsive regressions are addressed for the touched areas; stale Bootstrap 4/SB Admin remnants are removed from migrated surfaces; and active docs and relevant verification coverage describe the post-migration UX baseline without stale contradictions.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated shell, table/list, workflow, and summary families now inherit one ScholarDex-owned frontend baseline across shared, admin, and user surfaces.
  - Accessibility semantics, visible focus treatment, responsive behavior, and light/dark parity are part of the expected steady state for migrated surfaces rather than deferred polish work.
  - Bootstrap 4 and SB Admin presentation remnants were removed from already-migrated families, while remaining Bootstrap-era debt is intentionally bounded to untouched legacy pages.
  - Durable contributor-facing baseline docs now live in `docs/frontend-conventions.md`, `docs/ux-design-guide.md`, and `docs/quality-gates.md`.
  - Archived contract source of truth: `docs/tasks/closed/h34.1-accessibility-responsive-closeout-contract.md`.
  Subtasks:
  - [x] `H34.1` Lock the accessibility, responsive, and migration-closeout contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h34.1-accessibility-responsive-closeout-contract.md`.
  - [x] `H34.2` Close accessibility and focus/state gaps on migrated user table and report families.
    Handover:
    - Migrated user table and report families now use the shared table accessibility contract with clearer context, captioning, focus treatment, and corrected semantic markup.
  - [x] `H34.3` Close accessibility and focus/state gaps on migrated user workflow and workspace families.
    Handover:
    - Migrated user workflows and mixed workspaces now use clearer step semantics, live-status treatment, disclosure semantics, and workspace accessibility behavior.
  - [x] `H34.4` Close responsive and cross-theme consistency gaps on migrated shared and admin list/table families.
    Handover:
    - Migrated shared/admin list-table pages now share one responsive toolbar, filter, overflow, pager, and theme-consistent list contract.
  - [x] `H34.5` Close responsive and cross-theme consistency gaps on migrated admin workflow families.
    Handover:
    - Migrated admin workflow pages now share one responsive modal, form-grid, collection-row, builder-card, and helper/feedback contract.
  - [x] `H34.6` Close responsive and cross-theme consistency gaps on migrated dashboard, summary, and workspace families.
    Handover:
    - Migrated dashboard, summary, and mixed workspace pages now share one responsive summary-grid, dashboard-form, action-cluster, and report-detail contract.
  - [x] `H34.7` Remove remaining Bootstrap 4 and SB Admin presentation remnants from already-migrated families.
    Handover:
    - Already-migrated families no longer rely on Bootstrap 4 / SB Admin presentation classes as their visible contract, and remaining legacy debt is bounded to untouched pages.
  - [x] `H34.8` Align `H34` docs, guardrails, and final frontend baseline expectations.
    Handover:
    - Top-level frontend docs and quality-gate guidance now reflect the post-H34 steady state and H34 is archived from the active task flow.

## H28 Descriptive Runtime Naming Cleanup For Legacy `Hxx` Identifiers

Archived from `TASKS.md` on 2026-03-31 after H28.1-H28.6 completion.

- [x] `H28` Descriptive runtime naming cleanup for legacy `Hxx` identifiers.
  Goal: remove backlog-task ids from live runtime code and runtime-facing surfaces so classes, interfaces, metrics helpers, tests, logs, and admin UI labels use descriptive domain terminology instead of historical implementation-wave names.
  Deliverable: runtime renaming plan and implementation for the remaining `Hxx`-named live artifacts, centered on operational-status services and canonical observability helpers, with aligned tests, wiring, and visible admin/operator strings.
  Exit criteria: no live runtime class/interface/test/template/property/log label uses `Hxx` naming as its primary identifier where a descriptive domain name is available; runtime behavior and public routes remain unchanged; historical task/docs references remain archival only.
  Status: completed on 2026-03-31.
  Handover:
  - Operational-status runtime types were renamed to `PostgresOperationalStatus*`, with controller wiring and tests aligned to the descriptive names.
  - `H19CanonicalMetrics` was replaced by `CanonicalObservabilityMetrics` while preserving the existing `core.h19.*` meter ids.
  - Remaining runtime-facing H-task labels were removed from admin UI text, runtime logs, active config comments, and contributor-facing workflow/script/command names.
  - New workflow/script names are active under `.github/workflows/quality-gates.yml`, `.github/workflows/security-gates.yml`, and the descriptive `verify-*` command/script family in `package.json` and `scripts/`.
  - Runtime naming regression protection now lives in `scripts/verify-runtime-naming-guardrails.js` and is wired into `verify-quality-gates-baseline`.
  - Archived contract source of truth: `docs/tasks/closed/h28.1-descriptive-runtime-naming-contract.md`.
  - Closeout source of truth: `docs/tasks/closed/h28.6-runtime-naming-cleanup-closeout.md`.
  - Preserved non-goals remain intentionally unchanged: `core.h19.*` meter ids, `core.h22.*` / `H22_*` property-env contracts, HTTP routes, JSON wire contracts, and historical archival docs.
  Subtasks:
  - [x] `H28.1` Lock the descriptive runtime naming contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h28.1-descriptive-runtime-naming-contract.md`.
  - [x] `H28.2` Rename operational-status runtime types from task-coded to domain-coded names.
    Handover:
    - Runtime operational status now resolves through `PostgresOperationalStatusService` and `DefaultPostgresOperationalStatusService`.
  - [x] `H28.3` Rename canonical observability helpers from task-coded to domain-coded names.
    Handover:
    - Runtime canonical metrics helper now resolves through `CanonicalObservabilityMetrics`.
  - [x] `H28.4` Remove residual `Hxx` naming from runtime-facing strings and labels.
    Handover:
    - Active runtime strings/log labels now use descriptive domain wording rather than H-task labels.
  - [x] `H28.5` Remove residual `Hxx` naming from quality-gate workflows and guardrail scripts.
    Handover:
    - Live workflows, script filenames, and contributor-facing verification commands now use descriptive capability-based naming.
  - [x] `H28.6` Refresh verification and closeout documentation for the naming cleanup.
    Handover:
    - Closeout note: `docs/tasks/closed/h28.6-runtime-naming-cleanup-closeout.md`.

## H13 Workflow-Level Functional Confidence Suite

Archived from `TASKS.md` on 2026-03-30 after H13.1-H13.3 completion.

- [x] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical modern admin/user workflows across the current canonical architecture, centered on WoS admin initialization, user reporting/export behavior, and Postgres projection-readiness failure handling.
  Deliverable: focused workflow-level tests for the highest-value operational paths, using deterministic fixtures and asserting state transitions across controller -> orchestration -> persistence/read-model boundaries for both success and degraded scenarios.
  Exit criteria: the selected modern workflows under `/admin/initialization/wos/*`, `/user/individual-reports/view/{id}`, and Postgres projection/readiness handling are validated across success and failure paths, and regressions are caught before merge by repeatable automated checks.
  Status: completed on 2026-03-30.
  Handover:
  - WoS admin initialization happy-path workflow coverage now exists in `WosAdminInitializationWorkflowIntegrationTest`, covering `ingest -> build facts -> enrich category rankings -> rebuild projections` plus Mongo/Postgres read-state verification.
  - The exact WoS admin step routes are protected by admin-only security assertions in `AdminInitializationSecurityContractTest`.
  - User reporting/export happy-path workflow coverage now exists in `UserReportRefreshCnfisWorkflowIntegrationTest`, covering latest-run creation, `/refresh-all-indicators`, persisted snapshot/result updates, and downstream CNFIS workbook export continuity.
  - Projection-failure degraded workflow coverage now exists in `PostgresProjectionFailureOperationalWorkflowTest`, proving failed projection state surfaces as operator-visible `RED` status through the current Postgres operational/admin endpoints.
  - Consolidated failure precedence is locked in `DefaultH22OperationalStatusServiceTest`: projection failure keeps `overallState = RED` even when materialized-view refresh is `SUCCESS`.
  - H13 required no standalone task-doc artifacts; the source of truth is the archived task entry plus the workflow tests above.
  Subtasks:
  - [x] `H13.1` Admin WoS maintenance end-to-end flow.
    Handover:
    - Workflow coverage: `WosAdminInitializationWorkflowIntegrationTest`.
    - Supporting route/auth evidence: `AdminInitializationControllerContractTest`, `AdminInitializationSecurityContractTest`.
  - [x] `H13.2` User indicator refresh/export workflow.
    Handover:
    - Workflow coverage: `UserReportRefreshCnfisWorkflowIntegrationTest`.
    - Supporting route/auth evidence: `UserViewControllerContractTest`, `UserViewSecurityContractTest`.
  - [x] `H13.3` Failure-path workflow gate.
    Handover:
    - Degraded workflow coverage: `PostgresProjectionFailureOperationalWorkflowTest`.
    - Failure-precedence guardrail: `DefaultH22OperationalStatusServiceTest`.

## H21 User-Defined Source Onboarding Into Scholardex

Archived from `TASKS.md` on 2026-03-30 after H21.1-H21.6 closure audit.

- [x] `H21` User-defined source onboarding into Scholardex.
  Goal: support user-triggered non-Scopus/WoS/Scholar publication imports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: migrated in-place user publication wizard onboarding flow modeled as `USER_DEFINED` source events/facts with deterministic IDs, explicit review/moderation metadata, and integration with canonical Scholardex identity, source-link, conflict, and projection contracts.
  Exit criteria: the existing `/user/publications/add` wizard submits `USER_DEFINED` publication onboarding through the canonical Scholardex ingestion path; publication/forum/authorship/linked-affiliation lineage is deterministic and replay-safe; review/moderation state is explicit in metadata without requiring a separate admin approval workflow; imported records become visible through canonical Scholardex projections and existing user/admin operability surfaces.
  Status: completed on 2026-03-30.
  Handover:
  - The user publication wizard remains canonical and in-place at `GET /user/publications/add`.
  - Wizard submit now emits canonical `USER_DEFINED` import events with deterministic `USER_DEFINED:FORUM:*`, `USER_DEFINED:PUBLICATION:*`, and `USER_DEFINED:EID:*` identifiers.
  - USER_DEFINED source facts, canonicalization, source-link integration, and projection rebuild flow are implemented without a separate admin approval workflow.
  - Admin diagnostics and maintenance surfaces exist under `/admin/user-defined-triage` and `/admin/initialization/user-defined/*`.
  - Archived contract and closeout docs:
    - `docs/tasks/closed/h21.1-user-defined-wizard-onboarding-contract.md`
    - `docs/tasks/closed/h21.2-user-defined-wizard-submit-migration.md`
    - `docs/tasks/closed/h21.3-user-defined-facts-canonicalization.md`
    - `docs/tasks/closed/h21.4-user-defined-operability-admin-triage.md`
    - `docs/tasks/closed/h21.6-user-defined-onboarding-closeout.md`
  Subtasks:
  - [x] `H21.1` Lock the `USER_DEFINED` wizard-onboarding contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h21.1-user-defined-wizard-onboarding-contract.md`.
  - [x] `H21.2` Migrate wizard submission into first-class `USER_DEFINED` canonical ingest.
    Handover:
    - Runtime wizard submit path now ingests `USER_DEFINED` publication events through the canonical import-event pipeline.
  - [x] `H21.3` Align canonical linking, lineage, and review metadata for wizard-created entities.
    Handover:
    - USER_DEFINED source facts and canonicalization now propagate lineage and review metadata into Scholardex facts/source-links.
  - [x] `H21.4` Integrate operability and admin triage for `USER_DEFINED` onboarding.
    Handover:
    - USER_DEFINED triage and maintenance surfaces are live and source-filtered.
  - [x] `H21.5` Add regression and projection-visibility coverage for migrated wizard onboarding.
    Handover:
    - Regression coverage includes wizard submit, source-link alias normalization, USER_DEFINED fact building/canonicalization, triage, initialization, and operability metrics.
  - [x] `H21.6` Closeout docs and route/task handoff.
    Handover:
    - Closeout source of truth: `docs/tasks/closed/h21.6-user-defined-onboarding-closeout.md`.

## H27 Canonical Entity API Migration From Scopus Compatibility Routes

Archived from `TASKS.md` on 2026-03-30 after H27.1-H27.4 closure.

- [x] `H27` Canonical entity API migration from Scopus compatibility routes.
  Goal: replace the legacy public `/api/scopus/**` contract with a canonical source-agnostic entity API that matches the current Scholardex-backed runtime model.
  Deliverable: breaking public API migration from `/api/scopus/authors`, `/api/scopus/forums`, and `/api/scopus/affiliations` to canonical `/api/entities/authors`, `/api/entities/forums`, and `/api/entities/affiliations`, including aligned DTO naming, controller/service contract updates, and documentation/test refresh.
  Exit criteria: the public entity-read API no longer exposes Scopus-branded routes or `Scopus*` response contract names for Scholardex-backed author/forum/affiliation reads; canonical `/api/entities/**` endpoints are the only supported routes; docs, tests, and guardrails reflect the new contract explicitly.
  Status: completed on 2026-03-30.
  Handover:
  - Canonical public entity-read APIs are `GET /api/entities/authors`, `GET /api/entities/forums`, and `GET /api/entities/affiliations`.
  - Public Java/API response types for these APIs are `Scholardex*` rather than `Scopus*`, while the JSON wire shape remains unchanged.
  - Legacy `/api/scopus/authors|forums|affiliations` routes are removed with no redirect or alias compatibility window.
  - Positive contract/security coverage now targets `/api/entities/**`, and negative coverage locks the removed `/api/scopus/**` routes for both unauthenticated and authenticated access paths.
  - Contract source of truth: `docs/tasks/closed/h27.1-canonical-entity-api-contract.md`.
  - Closeout source of truth: `docs/tasks/closed/h27.3-entity-api-cutover-closeout.md`.
  Subtasks:
  - [x] `H27.1` Lock the canonical entity API contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h27.1-canonical-entity-api-contract.md`.
  - [x] `H27.2` Implement canonical entity routes and DTO renames.
    Handover:
    - Runtime entity-read APIs now resolve only through `/api/entities/**`.
    - Public `Scopus*` entity API DTO names were replaced with `Scholardex*`.
  - [x] `H27.3` Remove Scopus API compatibility routes and update public docs.
    Handover:
    - Closeout note: `docs/tasks/closed/h27.3-entity-api-cutover-closeout.md`.
    - Historical H17/H23 notes remain unchanged as historical evidence only.
  - [x] `H27.4` Refresh regression coverage and route guardrails for canonical entity APIs.
    Handover:
    - Removed-route behavior is protected in `ApiSecurityContractTest`.
    - Legacy route-mapping guardrail lives in `EntityApiRouteGuardrailTest`.

## H25 Uniform Entity Routes And Shared Read-View Consolidation

Archived from `TASKS.md` on 2026-03-13 after H25.1-H25.5 closure.

- [x] `H25` Uniform entity routes and shared read-view consolidation.
  Goal: eliminate duplicate MVC pages/routes for shared read surfaces across `/user/*` and `/admin/*`, and align navigation with canonical entity-based routes while keeping admin-only management tools separate.
  Deliverable: canonical authenticated MVC routes for shared entities (`/forums`, `/wos/categories`, `/core/rankings`, `/universities`, `/events`), trimmed `/user/*` routes for user-owned surfaces, removal of duplicate admin read views, and role-driven sidebar selection instead of hardcoded admin/user sidebar fragments per template.
  Exit criteria: shared entity reads resolve through one canonical route family regardless of role; duplicate admin read pages for forums/rankings/universities/events are removed; user-owned surfaces remain under `/user/*`; sidebar/navigation is selected by role at runtime rather than hardcoded per template; legacy duplicate read routes are removed and all callers/tests/docs are aligned to the new route model.
  Status: completed on 2026-03-13.
  Handover:
  - Shared authenticated MVC reads are canonical under `/forums`, `/wos/categories`, `/core/rankings`, `/universities`, and `/events`.
  - User-owned MVC routes are canonical under `/user/*`, with legacy aliases removed rather than redirected.
  - Sidebar selection is centralized through `fragments :: sidebar(activeSection)` with role-aware context instead of template-specific admin/user fragment selection.
  - Route ownership source of truth: `docs/tasks/closed/h25.1-canonical-route-ownership-contract.md`.
  - Steady-state route map: `docs/tasks/closed/h23.5-route-map-and-closeout.md`.
  Subtasks:
  - [x] `H25.1` Lock canonical route and ownership contract.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h25.1-canonical-route-ownership-contract.md`.
  - [x] `H25.2` Consolidate shared entity MVC routes and remove duplicate admin read pages.
    Handover:
    - Shared canonical route families now serve the consolidated read surfaces.
    - Duplicate admin read GET aliases under `/admin/rankings/*` are removed.
  - [x] `H25.3` Normalize remaining user-owned route families.
    Handover:
    - Canonical user routes include `/user/activities*`, `/user/individual-reports*`, `/user/publications/scopus-tasks`, `/user/tasks/scopus/update-publications`, `/user/tasks/scopus/update-citations`, and `/user/exports/cnfis`.
  - [x] `H25.4` Replace hardcoded admin/user sidebar composition with role-based layout selection.
    Handover:
    - Runtime templates use unified sidebar composition with role-aware selection instead of hardcoded `admin-sidebar` and `user-sidebar` bindings.
  - [x] `H25.5` Remove stale route debt and align verification/docs.
    Handover:
    - `/admin/scopus/**` MVC compatibility mappings are removed.
    - Route guardrails and route-map docs were updated to enforce canonical shared/user route families.

## H26 Canonical User Dashboard Route And Post-H25 Naming Cleanup

Archived from `TASKS.md` on 2026-03-13 after H26.1-H26.4 closure.

- [x] `H26` Canonical user dashboard route and post-H25 naming cleanup.
  Goal: finish the post-H25 cleanup by aligning the remaining runtime route contract, live template/view names, and active docs with the canonical MVC route model already adopted in H25.
  Deliverable: canonical `/user/dashboard` route with `/user` retained only as a compatibility redirect, renamed live MVC template/view names that match canonical entities/routes, and active docs/tests/guardrails updated to reflect the steady-state route model without stale pre-H25 naming.
  Exit criteria: `/user/dashboard` is the documented and implemented dashboard route; `/user` no longer serves as the primary route; live runtime template/view names no longer use stale `scholardex` or camelCase report/activity naming where canonical names now exist; active docs/tests/guardrails describe only current route families except where old routes are intentionally referenced as removal assertions.
  Status: completed on 2026-03-13.
  Handover:
  - `GET /user/dashboard` is canonical and `GET /user` is compatibility redirect-only.
  - Live shared/user view names are normalized to entity-aligned template families such as `forums/*`, `wos/*`, `core/*`, `universities/*`, `events/*`, `shared/not-found`, `user/activities*`, and `user/individual-reports*`.
  - Active docs and route guardrails now enforce canonical naming while reserving removed aliases for historical inventories and explicit removal assertions only.
  Subtasks:
  - [x] `H26.1` Canonicalize the dashboard route.
    Handover:
    - Canonical dashboard route is `GET /user/dashboard`; `GET /user` redirects there for compatibility.
  - [x] `H26.2` Rename live runtime views/templates to canonical entity names.
    Handover:
    - Runtime view-name/template families are aligned with canonical entity naming and no longer use stale `scholardex` or camelCase activity/report names.
  - [x] `H26.3` Clean up active route-documentation drift.
    Handover:
    - Active docs now describe canonical H25/H26 route families; legacy aliases remain only in historical inventory/closeout material.
  - [x] `H26.4` Tighten verification around canonical naming and aliases.
    Handover:
    - Route guardrails and MVC/security tests protect canonical `/user/dashboard` behavior, renamed view tokens, and removed-alias invariants.

## H24 PostgreSQL Cutover For `/api/rankings/wos`

Archived from `TASKS.md` on 2026-03-13 after H24.1-H24.5 closure.

- [x] `H24` PostgreSQL cutover for `/api/rankings/wos`.
  Goal: migrate the `/api/rankings/wos` search/paging API from Mongo-backed `WosRankingView` reads to the existing PostgreSQL reporting read model while preserving the public contract and current UI behavior.
  Deliverable: Postgres-backed query implementation for `/api/rankings/wos`, runtime cutover wiring, and targeted parity/regression coverage proving contract-equivalent behavior for paging, sorting, search, validation, and authentication.
  Exit criteria: `/api/rankings/wos` is served from PostgreSQL `reporting_read.wos_ranking_view`; request/response shape, sort semantics, search behavior, and auth contract remain stable; targeted parity/regression tests cover the cutover and protect against reintroduction of Mongo-backed reads for this API.
  Status: completed on 2026-03-13.
  Handover:
  - Public API route intentionally remains `GET /api/rankings/wos`.
  - Runtime query path is now `WosRankingApiController -> WosRankingQueryService -> PostgresWosRankingReadPort`.
  - Runtime storage authority for this API is `reporting_read.wos_ranking_view`; Mongo fallback is intentionally removed.
  - Contract source of truth: `docs/tasks/closed/h24.1-wos-rankings-postgres-query-contract.md`.
  - Closeout source of truth: `docs/tasks/closed/h24.5-wos-rankings-postgres-closeout.md`.
  Subtasks:
  - [x] `H24.1` Lock `/api/rankings/wos` Postgres query contract.
    Deliverable: implementation-ready contract for the SQL-backed `/api/rankings/wos` search path, including allowed sort fields, direction rules, query normalization, prefix-search behavior, paging semantics, and response-shape compatibility.
    Exit criteria: the Postgres implementation target is decision-locked and explicitly matches the current public API contract unless a change is intentionally recorded.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h24.1-wos-rankings-postgres-query-contract.md`.
  - [x] `H24.2` Implement PostgreSQL read port for WoS rankings API.
    Deliverable: dedicated Postgres query component for `/api/rankings/wos` backed by `reporting_read.wos_ranking_view`, returning the existing `WosRankingPageResponse`.
    Exit criteria: the read port supports current paging/sorting/search behavior and reads only from PostgreSQL for this API surface.
    Handover:
    - Runtime SQL adapter: `PostgresWosRankingReadPort`.
    - Existing read model reused from H22 projection state; no new schema migration required for H24.
  - [x] `H24.3` Cut over controller/service wiring for `/api/rankings/wos`.
    Deliverable: runtime wiring that routes `WosRankingApiController` through the new Postgres-backed query path and removes direct Mongo query dependency from the API service.
    Exit criteria: `/api/rankings/wos` no longer depends on `MongoTemplate`/Mongo query code at runtime, while the public route and response contract remain unchanged.
    Handover:
    - `WosRankingQueryService` now requires the Postgres read port and throws if it is unavailable, preventing silent Mongo fallback drift.
  - [x] `H24.4` Add parity and regression coverage for the API cutover.
    Deliverable: focused tests covering request validation, authenticated access, paging, allowed sorts, prefix search semantics, and representative parity between legacy Mongo behavior and the new Postgres path.
    Exit criteria: automated tests fail on contract drift or accidental reintroduction of Mongo-backed `/api/rankings/wos` reads.
    Handover:
    - SQL behavior tests: `PostgresWosRankingReadPortTest`.
    - Runtime cutover tests: `WosRankingQueryServiceTest`.
    - Controller/API contract tests: `WosRankingApiControllerContractTest`.
    - Cross-store parity tests: `WosRankingApiParityIntegrationTest`.
  - [x] `H24.5` Closeout docs and task handoff.
    Deliverable: backlog/docs/task notes updated to record `/api/rankings/wos` as PostgreSQL-backed while retaining the legacy API name intentionally.
    Exit criteria: the steady-state route/storage decision is documented clearly enough that future cleanup does not treat this API as still Mongo-backed.
    Handover:
    - Closeout source of truth: `docs/tasks/closed/h24.5-wos-rankings-postgres-closeout.md`.

## H22 Postgres Reporting Core + Mongo Ingest Baseline Migration

Archived from `TASKS.md` on 2026-03-13 after H22.1-H22.10 closure.

- [x] `H22` Postgres reporting core + Mongo ingest baseline migration.
  Goal: improve WoS scoring/reporting read and compute latency by moving reporting read models to PostgreSQL while keeping MongoDB as the ingestion/event/queue write model.
  Deliverable: architecture contract, SQL read schema, projection/sync pipeline, SQL query cutover for WoS scoring/reporting flows, and operability/rollback guardrails.
  Exit criteria: Mongo remains authoritative for raw import events/queues; WoS/scoring/report read models are served from PostgreSQL; SQL joins/materialized views back WoS scoring and citation-heavy report paths; parity and performance gates pass before full cutover.
  Status: completed on 2026-03-13.
  Subtasks:
  - [x] `H22.1` Architecture contract and bounded-context map.
    Status: completed on 2026-03-11.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h22.1-postgres-reporting-architecture-contract.md`.
    - Companion sequence flows: `docs/tasks/closed/h22.1-postgres-reporting-sequences.md`.
  - [x] `H22.2` PostgreSQL schema for WoS/scoring/reporting read core.
    Status: completed on 2026-03-11.
    Handover:
    - Schema contract: `docs/tasks/closed/h22.2-postgres-reporting-schema-contract.md`.
    - Flyway migrations: `V1__h22_2_create_pg_enums.sql`, `V2__h22_2_create_reporting_core_tables.sql`, `V3__h22_2_create_reporting_core_indexes.sql`.
    - Migration verification test: `PostgresReportingReadSchemaMigrationIntegrationTest`.
  - [x] `H22.3` Projection/sync pipeline from canonical Mongo to PostgreSQL.
    Status: completed on 2026-03-11.
    Handover:
    - Projection contract: `docs/tasks/closed/h22.3-postgres-projection-contract.md`.
    - Projection state migration: `V4__h22_3_projection_state_tables.sql`.
    - Projector service: `JdbcPostgresReportingProjectionService` + `PostgresReportingProjectionService`.
    - Verification tests: `PostgresReportingProjectionServiceIntegrationTest`, `JdbcPostgresReportingProjectionServiceTest`.
  - [x] `H22.4` Query-layer cutover to SQL-backed WoS scoring/report reads.
    Status: completed on 2026-03-11.
    Handover:
    - Cutover contract: `docs/tasks/closed/h22.4-query-layer-cutover-contract.md`.
    - Runtime switch/cutover guards: `ReportingReadStore`, `ReportingReadStoreSelector`, `PostgresReadCutoverGuard`.
    - Verification tests: `ReportingReadStoreRoutingTest`, `PostgresReportingLookupFacadeTest`, `ScholardexCutoverGuardrailTest`.
  - [x] `H22.5` Materialized views and refresh strategy for heavy reads.
    Status: completed on 2026-03-12.
    Handover:
    - Contract: `docs/tasks/closed/h22.5-materialized-views-refresh-contract.md`.
    - Migrations: `V5__h22_5_create_materialized_views.sql`, `V6__h22_5_mv_refresh_state_tables.sql`.
    - Refresh orchestration: `PostgresMaterializedViewRefreshService`, `JdbcPostgresMaterializedViewRefreshService`.
  - [x] `H22.6` Dual-read parity and performance gate.
    Status: completed on 2026-03-12.
    Handover:
    - Contract: `docs/tasks/closed/h22.6-dual-read-parity-performance-gate-contract.md`.
    - Migration/state tables: `V7__h22_6_dual_read_gate_tables.sql`.
    - Runtime gate service: `DualReadGateService`, `JdbcDualReadGateService`.
  - [x] `H22.7` Operationalization, rollback, and rebuild playbook.
    Status: completed on 2026-03-12.
    Handover:
    - Runbook: `docs/tasks/closed/h22.7-operational-rollback-rebuild-playbook.md`.
    - Ops status service: `H22OperationalStatusService`, `DefaultH22OperationalStatusService`.
  - [x] `H22.8` Post-integration layering and naming consistency.
    Status: completed on 2026-03-12.
    Handover:
    - Scholardex naming consistency for admin/read surfaces and associated wiring/tests.
  - [x] `H22.9` Transitional path and config hygiene after Postgres integration.
    Status: completed on 2026-03-13.
    Handover:
    - Runtime routing and config trimmed to Postgres-first operational mode for migrated H22 surfaces.
    - `/admin/initialization` wording/layout cleanup for H22 cards and operational status.
  - [x] `H22.10` H22 test-harness cleanup and deterministic gate baseline refresh.
    Status: completed on 2026-03-13.
    Handover:
    - Deterministic gate seed selection in `JdbcDualReadGateService`.
    - Focused harness baseline command: `./gradlew testH2210Baseline` and `npm run verify-h22-baseline`.

## H23 Scholardex UI Route Consolidation and Steady-State Naming Cleanup

Archived from `TASKS.md` on 2026-03-13 after H23.1-H23.5 closure.

- [x] `H23` Scholardex UI route consolidation and steady-state naming cleanup.
  Goal: reduce maintenance overhead and product-surface drift by consolidating MVC/UI routes around Scholardex-first forum navigation while retiring the split between Scopus forum pages and WoS ranking pages.
  Deliverable: canonical Scholardex forum routes/templates for public and admin UI, WoS-specific category pages, trimmed MVC compatibility redirects/helpers, and updated docs/guardrails that reflect the new steady-state navigation model.
  Exit criteria: covered MVC surfaces use the new canonical route families, legacy MVC paths are either redirected or clearly marked transitional, and tests/guardrails enforce the consolidated UI architecture.
  Status: completed on 2026-03-13.
  Handover:
  - Canonical public MVC routes are `/scholardex/forums`, `/scholardex/forums/{id}`, `/rankings/categories`, and `/rankings/categories/{key}`.
  - Canonical admin MVC routes are under `/admin/scholardex/**`; retained compatibility shims remain under `/admin/scopus/**`, `/admin/scopus/venues*`, `/rankings/wos`, and `/user/rankings/{id}`.
  - Historical note: as of H23 closeout, `/api/scopus/**` and `/api/rankings/wos` were retained as stable API namespaces; H27 later superseded the entity-read `/api/scopus/authors|forums|affiliations` contract with canonical `/api/entities/**`, while `/api/rankings/wos` remained unchanged.
  - New H23 paged category API: `/api/rankings/categories`.
  - Route map and closeout doc: `docs/tasks/closed/h23.5-route-map-and-closeout.md`.
  - H23 verification entrypoint: `npm run verify-h23-ui`.
  Subtasks:
  - [x] `H23.1` Inventory and classify transitional debt.
    Status: completed on 2026-03-13.
    Handover:
    - Debt inventory: `docs/tasks/closed/h23.1-transitional-debt-inventory.md`.
  - [x] `H23.2` Scholardex UI route consolidation.
    Status: completed on 2026-03-13.
    Handover:
    - Canonical forum/publication/affiliation/admin routes moved to Scholardex-first MVC families.
  - [x] `H23.3` Unified forum detail and UI naming normalization.
    Status: completed on 2026-03-13.
    Handover:
    - Canonical forum detail moved to `/scholardex/forums/{id}` with journal/conference/book branching.
  - [x] `H23.4` Route-aware guardrails and deterministic UI verification refresh.
    Status: completed on 2026-03-13.
    Handover:
    - Deterministic route/UI guardrails and paged WoS category coverage now live behind `npm run verify-h23-ui`.
  - [x] `H23.5` Docs, route map, and task closeout.
    Status: completed on 2026-03-13.
    Handover:
    - Route map, verification contract, and task closeout aligned to the shipped H23 route model.

## H11-H14 Recovery Wave

Archived from `TASKS.md` on 2026-03-06 after closure and cleanup.

- [x] `H11` Functional contract hardening and null-safety normalization.
  Status: completed on 2026-03-04.
  Notes: core nullable contracts normalized to deterministic behavior and guarded with regression checks.

- [x] `H12` External integration and import correctness uplift.
  Status: completed on 2026-03-04.
  Notes: importer/scheduler behavior hardened with deterministic error accounting and integration guardrails.

- [x] `H14` WoS Approach 3 implementation (immutable ingestion ledger + rebuildable views).
  Status: completed on 2026-03-06.
  Notes: H14.1-H14.16 resolved; H14.14 and H14.15 were explicitly dropped by decision.
  Highlights:
  - canonical WoS schema + identity + immutable import events + parser adapters + fact builders delivered,
  - IF source-policy enforced (`OFFICIAL_WOS_EXTRACT` only) while `IMPACT_FACTOR` remains operational,
  - projections/indexes/read-path/reporting cutover completed with cache-independent WoS lookup paths,
  - admin-triggered big-bang migration and parity reconciliation gates delivered,
  - residual H14 checks converted to automated tests (bundled SCIE/SSCI split, replay determinism, AIS/RIS/CNFIS parity stability).

## H15 CI Guardrail Realignment and Quality-Gate Restoration

Archived from `TASKS.md` on 2026-03-06 after closure and CI stabilization.

- [x] `H15` CI guardrail realignment and quality-gate restoration.
  Goal: restore trust in CI by aligning guardrail rules with the current post-H14 architecture and enforcing the complete guardrail set in GitHub workflows.
  Deliverable: updated guardrail scripts/workflows and a green full validation baseline (`verify-h09-baseline` + `gradlew check`) on compliant code.
  Exit criteria: CI fails only on real regressions (not stale policy checks), and required guardrails are consistently enforced on PR/push.
  Status: completed on 2026-03-06.
  Note: H15.1-H15.4 completed; guardrail scripts and quality workflows now align with post-H14 behavior and pass on rerun.
  Subtasks:
  - [x] `H15.1` Guardrail policy audit for stale assumptions.
    Deliverable: inventory of guardrails that still encode pre-H14 behavior (WoS cache and old CS dispatch assumptions).
    Exit criteria: each stale check has a documented intended replacement aligned with current architecture.
    Status: completed on 2026-03-06.
    Note: see `docs/tasks/closed/h15-guardrail-policy-audit.md` for stale/valid classification, source-of-truth mappings, and H15.2 decision-locked script updates.
    H15.2 handoff:
    - `verify-h06-persistence`: remove WoS ranking-cache/repository assertions for `CacheService`; keep edit/update canonical `findById` checks while allowing `buildCitationsView` `id/eid` compatibility fallback.
    - `verify-duplication-guardrails`: replace publication `bk/ch` delegation expectation with non-`ar/re/cp` empty-score policy; keep activity `Book/Book Series` delegation requirement unchanged.
  - [x] `H15.2` Guardrail script updates.
    Deliverable: update `verify-h06-persistence` and `verify-duplication-guardrails` to reflect current intended behavior.
    Exit criteria: scripts pass on compliant code and fail on true policy regressions.
    Status: completed on 2026-03-06.
    Note: script-only update completed in line with `docs/tasks/closed/h15-guardrail-policy-audit.md`; no runtime service code changed for this task.
  - [x] `H15.3` GitHub workflow enforcement completion.
    Deliverable: ensure quality workflows execute the full required guardrail set (including WoS parity baseline/integration checks) with failure artifacts.
    Exit criteria: PR/push pipelines consistently run and enforce the updated guardrails.
    Status: completed on 2026-03-06.
    Note: `h09-quality-gates.yml` guardrails job now runs a single explicit guardrail suite (`verify-architecture-boundaries`, `verify-h06-persistence`, `verify-h07-guardrails`, `verify-h08-baseline`, `verify-h12-integrations`, `verify-duplication-guardrails`, `verify-wos-parity-baseline`) with per-check CI logs and failure artifact upload.
  - [x] `H15.4` Full quality-gate recovery.
    Deliverable: restore green status for `npm run verify-h09-baseline` and `./gradlew check`.
    Exit criteria: both gates pass end-to-end and remain stable across reruns.
    Status: completed on 2026-03-06.
    Note: validated with repeated local runs of `npm run verify-h09-baseline` and `./gradlew check`; all checks passed consistently.

## H16 Java and Gradle Modernization Uplift

Archived from `TASKS.md` on 2026-03-06 after H16.1-H16.5 closure.

- [x] `H16` Java and Gradle modernization uplift.
  Goal: upgrade the runtime/build toolchain to newer Java + Gradle versions with deterministic local/CI behavior.
  Deliverable: aligned Java/Gradle versions, dependency/plugin compatibility fixes, and green baseline gates.
  Exit criteria: `java-smoke`, `quality-full`, and local `./gradlew check` pass on the upgraded toolchain without environment-specific hacks.
  Status: completed on 2026-03-06.
  Note: upgrade and validation evidence recorded in `docs/tasks/closed/h16-toolchain-modernization-matrix.md` (including H16.5 closeout evidence).
  Subtasks:
  - [x] `H16.1` Baseline and target matrix.
    Deliverable: documented current Java/Gradle/plugin/dependency versions and an explicit target upgrade matrix with compatibility notes.
    Exit criteria: upgrade scope and order are fixed, with rollback path and known risk hotspots identified.
    Status note (2026-03-06): completed in `docs/tasks/closed/h16-toolchain-modernization-matrix.md` with pinned target direction (Java 25, Gradle 9.1.x+, Spring Boot 4.0.x LTS-target line), compatibility ownership, and rollback guards.
  - [x] `H16.2` Gradle wrapper and build tooling bump.
    Deliverable: upgraded Gradle wrapper and required build script/property updates to match the target Java/toolchain baseline.
    Exit criteria: `./gradlew --version`, configuration phase, and core build lifecycle start cleanly on the new wrapper.
    Status note (2026-03-06): completed with wrapper `9.1.0`, Java toolchain/launchers moved to `25`, macOS wrapper guard updated for JDK 25, and dependency-management plugin bumped to `1.1.7` for Gradle 9 compatibility (`--version`, `help`, `compileJava` all pass).
  - [x] `H16.3` Plugin and dependency compatibility remediation.
    Deliverable: minimal set of plugin/dependency upgrades or config changes required to restore compile/test/check behavior.
    Exit criteria: no deprecated/broken build integrations remain on critical paths (`compileJava`, `test`, `check`).
    Status note (2026-03-06): completed by upgrading Spring Boot to `4.0.2`, adding Boot 4 test-slice modules (`spring-boot-webmvc-test`, `spring-boot-data-mongodb-test`), pinning Testcontainers to `1.19.7`, migrating security/health/error APIs to Boot 4/Security 7 namespaces, and updating affected tests (`@MockBean -> @MockitoBean`, Boot 4 test annotation imports, redirect expectations); `compileJava`, `test`, and `check` pass.
  - [x] `H16.4` CI parity and deterministic execution hardening.
    Deliverable: workflow and environment alignment updates so local and CI use the same Java/Gradle assumptions.
    Exit criteria: `java-smoke` and `quality-full` run with identical toolchain intent across local and CI.
    Status note (2026-03-06): completed by updating quality/security workflows to Temurin Java 25 and standardizing Java-job Gradle invocations to wrapper + `--no-daemon`; docs updated in `docs/tasks/closed/h09-ci-gates.md` and `docs/tasks/closed/h16-toolchain-modernization-matrix.md`.
  - [x] `H16.5` Validation and closeout evidence.
    Deliverable: run log + short closeout note capturing command results, residual risks, and follow-ups.
    Exit criteria: local `./gradlew check` and CI gates (`java-smoke`, `quality-full`) are green on the upgraded stack.
    Status note (2026-03-06): completed with evidence in `docs/tasks/closed/h16-toolchain-modernization-matrix.md` (H16.5 section); local validation set passed (`./gradlew --version`, `compileJava`, `test --tests "*CoreApplicationTests"`, `check`). CI gate confirmation is tracked as follow-up via PR workflow run.

## Vendor Asset Migration Tasks

Tracking migration from `/vendor/*` assets to bundled `/assets/*` assets.

- [x] `T01` Goal: Create task tracker and migration guardrails.
  Files/areas: `/TASKS.md`
  Automated checks: `./gradlew test`
  Done criteria: tracker exists with ordered, test-gated tasks.
  Notes: Completed.

- [x] `T02` Goal: Introduce frontend toolchain (npm + bundler) without switching templates yet.
  Files/areas: `/package.json`, lockfile, bundler config, `frontend/` source dir.
  Automated checks: `npm ci`, `npm run build`, `./gradlew test`
  Done criteria: deterministic assets generated under `src/main/resources/static/assets/`.
  Notes: Completed. `package.json` + lockfile present and install/build checks pass.

- [x] `T03` Goal: Wire baseline vendor equivalents into bundled entrypoints.
  Files/areas: `package.json`, frontend entrypoint files, build scripts.
  Automated checks: `npm run build`, `npm run verify-assets`, `./gradlew test`
  Done criteria: bundle contract includes Bootstrap, jQuery, DataTables, Chart.js, Font Awesome, jquery-easing.
  Notes: Completed with committed `app.css`/`app.js` and npm entrypoint definitions.

- [x] `T04` Goal: Add automated template asset-path validation.
  Files/areas: `scripts/verify-template-assets.js`, npm script wiring.
  Automated checks: `npm run verify-template-assets`, `./gradlew test`
  Done criteria: validator fails on reintroduced `/vendor/` usage.
  Notes: Completed.

- [x] `T05` Goal: Incremental migration batch A (shared pages/fragments).
  Files/areas: shared template patterns used by migrated pages.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: migrated batch has no direct `/vendor/` references.
  Notes: Completed.

- [x] `T06` Goal: Incremental migration batch B (admin pages).
  Files/areas: `src/main/resources/templates/admin/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: admin templates use bundled assets and no `/vendor/...` remains.
  Notes: Completed (excluding `*-bak.html` backups from strict validator).

- [x] `T07` Goal: Incremental migration batch C (user pages).
  Files/areas: `src/main/resources/templates/user/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: user templates no longer depend on `/vendor/...`.
  Notes: Completed.

- [x] `T08` Goal: Remove obsolete vendor tree and machine artifacts.
  Files/areas: `src/main/resources/static/vendor/**`, `.gitignore`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`, `rg -n '/vendor/' src/main/resources/templates`
  Done criteria: no production template refs to `/vendor/`; `.DS_Store` ignored.
  Notes: Completed.

- [x] `T09` Goal: Documentation and developer workflow finalization.
  Files/areas: `README.md`, `CONTRIBUTING.md`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: docs reflect reproducible frontend + backend verification commands.
  Notes: Completed.

- [x] `T10` Goal: Final regression gate and signoff.
  Files/areas: `TASKS.md` status updates.
  Automated checks: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: all checks green and tasks complete.
  Notes: Completed. Full gate passed: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew check`.

## H01 Duplicate and Drift Audit

Archived from `TASKS.md` on 2026-03-03 after H01 closure.

- [x] `H01` Duplicate code and drift audit.
  Goal: identify copy-paste clusters (backend, frontend, templates, scripts) and detect behavior drift between near-identical implementations.
  Deliverable: duplication inventory with risk ranking and consolidation candidates.
  Exit criteria: top high-risk duplicates have an agreed merge strategy and owners.
  Notes: Completed on 2026-03-03. C01/C03/C04 prioritized slices were executed and stabilized with regression guards and reintroduction checks.

### H01 Subtasks

- [x] `H01-S01` Inventory likely duplicate clusters.
  Deliverable: `docs/tasks/closed/h01-duplication-inventory.md`.
  Notes: Completed.

- [x] `H01-S03` Identify behavioral drift inside top clusters.
  Deliverable: `docs/tasks/closed/h01-drift-findings.md`.
  Notes: Completed on 2026-03-03. `C01`, `C03`, `C04`, `C05`, and `C06` analyzed with decisions/evidence; C04 closure slices completed (`D01/D02/D03/D04/D05/D06/D07` resolved for C04 scope).

- [x] `H01-S04` Prioritize by risk and blast radius.
  Deliverable: priority table in `docs/tasks/closed/h01-duplication-inventory.md`.
  Notes: Completed (`C01 (P0)` -> `C04 (P1)` -> `C06 (P2)`).

- [x] `H01-S05` Define consolidation strategy per priority cluster.
  Deliverable: `docs/tasks/closed/h01-consolidation-strategy.md`.
  Notes: Completed.

- [x] `H01-S06` Create regression guards before refactor.
  Deliverable: focused tests + coverage notes in `docs/tasks/closed/h01-regression-guards.md`.
  Notes: Completed.

- [x] `H01-S07` Execute first consolidation slice (small, high-value).
  Deliverable: C04 sub-cluster B consolidation slices.
  Notes: Completed on 2026-03-03. Factory fail-fast + CS dispatch alignment completed.

- [x] `H01-S08` Prevent reintroduction.
  Deliverable: CI/local duplication check command + contributor note.
  Notes: Completed on 2026-03-03. Added `npm run verify-duplication-guardrails`, wired into `./gradlew check`.

### H01 Cluster Closures

- [x] `C01` `CNFISScoringService` vs `CNFISScoringService2025`.
  Notes: Closed on 2026-03-03. Canonical spec in `docs/c01-cnfis-rule-spec.md`, edge-case tests expanded, no-behavior cleanup applied.

- [x] `C02` Admin template backups (`*-bak.html`) vs active templates.
  Notes: Resolved on 2026-03-03 by deleting `admin/researchers-bak.html`.

- [x] `C03` Admin rankings backup template pair.
  Notes: Resolved on 2026-03-03 by deleting `admin/rankings-view-bak.html`.

- [x] `C04` Reporting/scoring service family.
  Notes: Resolved on 2026-03-03 by slices 2-5 (shared category/subtype contracts, dispatch/factory alignment, metadata/logger cleanup).

## H02 First Subtask List (Planning Mode Seed)

Scope: `H02` Architecture boundaries and ownership.

- [x] `H02-S01` Map current runtime architecture and dependency directions.
  Goal: produce a factual map of layers/modules and how requests/flows travel through them.
  Inputs: package structure, controller/service/repository wiring, frontend template/script entrypoints.
  Deliverable: `docs/tasks/closed/h02-architecture-map.md` (current-state diagram + dependency table).
  Exit criteria: all major runtime paths (web -> service -> data and template/script flow) are represented.
  Status: completed on 2026-03-03.

- [x] `H02-S02` Define target boundaries and ownership zones.
  Goal: define what belongs in each layer/module and who owns cross-cutting areas.
  Inputs: `H02-S01` map + current drift/findings from H01.
  Deliverable: `docs/tasks/closed/h02-boundaries-and-ownership.md` (zones, responsibilities, ownership matrix).
  Exit criteria: each major package/area has a declared owner and allowed responsibilities.
  Status: completed on 2026-03-03.

- [x] `H02-S03` Specify allowed dependency rules.
  Goal: convert boundaries into explicit allow/deny dependency rules.
  Inputs: boundary definitions and known problematic couplings.
  Deliverable: dependency rule set in `docs/tasks/closed/h02-boundaries-and-ownership.md` (or `docs/tasks/closed/h02-dependency-rules.md`).
  Exit criteria: developers can decide placement/dependencies without ambiguity.
  Status: completed on 2026-03-03 (`docs/tasks/closed/h02-dependency-rules.md`).

- [x] `H02-S04` Identify and classify current boundary violations.
  Goal: detect concrete code locations that violate the declared dependency rules.
  Inputs: declared rules + current codebase scan.
  Deliverable: `docs/tasks/closed/h02-violations.md` with severity (`high|medium|low`) and rationale.
  Exit criteria: every violation has a file reference and a proposed remediation direction.
  Status: completed on 2026-03-03 (`docs/tasks/closed/h02-violations.md`).
  Note: V01 follow-up slice 4 completed (`AdminGroupController` export/CNFIS via `GroupExportFacade` and `GroupCnfisExportFacade`); tracked baseline pair is now at 73.9% repository-field reduction (`23 -> 6`), and AdminGroup repository debt is closed.
  Note: V02 baseline slice completed for the same pair (User/AdminGroup): direct controller imports of `core.service.reporting` removed; export/reporting coupling now facade-backed.
  Note: V02 AdminView verification slice completed: no direct `Z1 -> Z3` reporting-service coupling found in `AdminViewController`; transport-layer scan baseline is clean.
  Note: V03 focused AdminView slice delivered: institution publications/export data assembly and ranking compute/merge flows moved behind `AdminInstitutionReportFacade` and `RankingMaintenanceFacade`.
  Note: V03 final closure slice delivered: remaining transport assembly moved behind `AdminScopusFacade` and `ForumExportFacade` (`/admin/scopus/publications/search`, `/admin/scopus/publications/citations`, `/api/export`); V03 marked complete for current H02 scope.
  Note: V04 execution slice completed: reporting back-edge to `CacheService` removed via `ReportingLookupPort` + `CacheBackedReportingLookupFacade`; `service/reporting/**` now has zero `CacheService` references/imports.

- [x] `H02-S05` Define phased remediation plan for violations.
  Goal: prioritize fixes by blast radius and effort without blocking delivery.
  Inputs: violation inventory + ownership matrix.
  Deliverable: `docs/tasks/closed/h02-remediation-plan.md` with phased slices (`R1`, `R2`, ...).
  Exit criteria: top-priority violations have actionable implementation slices and sequencing.
  Status: completed on 2026-03-03 (`docs/tasks/closed/h02-remediation-plan.md` with `R1..R4`).

- [x] `H02-S06` Add lightweight enforcement in workflow.
  Goal: add practical checks/review guardrails so boundaries stay intact.
  Inputs: dependency rules + remediation strategy.
  Deliverable: checks and contributor guidance updates (`CONTRIBUTING.md`, optional scripts/CI rule).
  Exit criteria: at least one automated or checklist-based gate prevents new boundary violations.
  Status: completed on 2026-03-03.
  Note: added `npm run verify-architecture-boundaries` (`scripts/verify-architecture-boundaries.js`) to enforce: no new `Z1 -> Z4` controller repository imports (debt-aware allowlist), no `Z1 -> Z3` reporting imports in transport, and no `CacheService` usage in `service/reporting/**`.

- [x] `H02-S07` Close H02 with adoption notes.
  Goal: finalize architecture baseline and usage guidance for future tasks.
  Inputs: completed H02 artifacts and enforcement setup.
  Deliverable: H02 closeout note in `docs/tasks/closed/h02-boundaries-and-ownership.md` + `TASKS.md` status updates.
  Exit criteria: H02 can be treated as reference baseline for H03+ planning and implementation.
  Status: completed on 2026-03-03.
  Note: H02 is now the active architecture reference baseline; reopen H02 only for boundary-rule changes or newly detected violations.

## H03 Contract and Behavior Baseline

Archived from `TASKS.md` on 2026-03-03 after H03 closure.

- [x] `H03` Contract and behavior baseline.
  Goal: capture current expected behavior for key flows before refactors.
  Deliverable: minimal contract suite (controller/service integration + key UI/API flows).
  Exit criteria: high-impact flows have regression coverage and a known pass/fail baseline.
  Notes: Completed on 2026-03-03. H03 is now the default pre-refactor safety baseline for reporting/export/ranking flows.

### H03 Subtasks

- [x] `H03-S01` Identify and rank critical runtime flows for contract coverage.
  Deliverable: `docs/tasks/closed/h03-flow-priority-map.md`.
  Notes: Completed.

- [x] `H03-S02` Define contract schema for prioritized flows.
  Deliverable: `docs/tasks/closed/h03-contract-schema.md`.
  Notes: Completed.

- [x] `H03-S03` Capture reporting/service characterization contracts.
  Deliverable: `docs/tasks/closed/h03-reporting-contracts.md`.
  Notes: Completed.

- [x] `H03-S04` Add controller-level behavior characterization tests for top flows.
  Deliverable: controller contract tests for User/AdminGroup/AdminView/Export high-priority routes.
  Notes: Completed.

- [x] `H03-S05` Add facade/application contract tests for orchestration outputs.
  Deliverable: expanded characterization tests for `UserReportFacade`, `GroupCnfisExportFacade`, `RankingMaintenanceFacade`.
  Notes: Completed.

- [x] `H03-S06` Assemble and enforce H03 baseline gate.
  Deliverable: `npm run verify-h03-baseline` + `CONTRIBUTING.md` usage guidance.
  Notes: Completed.

- [x] `H03-S07` Close H03 with adoption notes and forward links to H04.
  Deliverable: H03 closeout/adoption note in `docs/tasks/closed/h03-reporting-contracts.md` and task archive updates.
  Notes: Completed on 2026-03-03.

## H04 Test Strategy and Pyramid Rebalance

Archived from `TASKS.md` on 2026-03-03 after H04 closure.

- [x] `H04` Test strategy and pyramid rebalance.
  Goal: reduce fragile end-to-end reliance and improve unit/integration signal quality.
  Deliverable: test taxonomy, gap matrix, and priority test additions.
  Exit criteria: each critical feature has at least one stable automated regression test.
  Notes: Completed on 2026-03-03. H04 is now the active testing playbook baseline for refactor safety.

### H04 Subtasks

- [x] `H04-S01` Build current test inventory and taxonomy map.
  Deliverable: `docs/tasks/closed/h04-test-inventory.md`.
  Notes: Completed.

- [x] `H04-S02` Define target pyramid and quality criteria.
  Deliverable: `docs/tasks/closed/h04-test-strategy.md`.
  Notes: Completed.

- [x] `H04-S03` Create risk-weighted gap matrix for critical flows.
  Deliverable: `docs/tasks/closed/h04-gap-matrix.md`.
  Notes: Completed.

- [x] `H04-S04` Add missing unit tests for scorer/support logic hotspots.
  Deliverable: focused unit coverage additions for `G01-G03`.
  Notes: Completed.

- [x] `H04-S05` Add integration/slice tests for cross-layer seams.
  Deliverable: targeted contract/slice coverage additions for `G04-G07`.
  Notes: Completed; `G08` deferred to S06 infrastructure policy and then partially resolved.

- [x] `H04-S06` Introduce reliability and runtime guardrails for test execution.
  Deliverable: `verify-h04-baseline`, `verify-h04-mongo-integration`, `docs/tasks/closed/h04-reliability-guardrails.md`.
  Notes: Completed; `G09` resolved and `G08` initial Testcontainers tranche implemented.

- [x] `H04-S07` Close H04 with adoption notes and handoff to H05.
  Deliverable: H04 closeout section in `docs/tasks/closed/h04-test-strategy.md` + task archive updates.
  Notes: Completed on 2026-03-03.

## H05 Frontend Structure and Asset Discipline

Archived from `TASKS.md` on 2026-03-03 after H05 closure.

- [x] `H05` Frontend structure and asset discipline.
  Goal: standardize JS/CSS/template patterns to avoid divergent implementations.
  Deliverable: frontend conventions (entrypoints, shared utilities, template composition patterns).
  Exit criteria: duplicated UI logic is centralized and new pages follow the same conventions.
  Notes: Completed on 2026-03-03. H05 baseline is active via `docs/tasks/closed/h05-frontend-map.md`, `docs/tasks/closed/h05-frontend-conventions.md`, shared frontend modules, and template guardrails.

### H05 Subtasks

- [x] `H05-S01` Build frontend structure map and duplication baseline.
  Deliverable: `docs/tasks/closed/h05-frontend-map.md`.
  Notes: Completed.

- [x] `H05-S02` Define frontend conventions and ownership rules.
  Deliverable: `docs/tasks/closed/h05-frontend-conventions.md`.
  Notes: Completed.

- [x] `H05-S03` Extract shared template composition primitives.
  Deliverable: shared core template fragments + migrated includes.
  Notes: Completed (`core-styles`/`core-scripts` fragments and template migrations).

- [x] `H05-S04` Introduce frontend utility modules for repeated JS behavior.
  Deliverable: shared modules under `frontend/src/modules/shared/**`.
  Notes: Completed (`domBehaviors.js`, `publicationSubtypeSync.js`, module-backed template behavior).

- [x] `H05-S05` Add guardrails for template/asset composition drift.
  Deliverable: hardened `scripts/verify-template-assets.js`.
  Notes: Completed (CDN allowlist enforcement, inline-script transitional allowlist, canonical datatables path check).

- [x] `H05-S06` Add focused frontend behavior regression checks.
  Deliverable: expanded frontend-facing controller contract tests.
  Notes: Completed (`UserViewControllerContractTest`, `AdminViewControllerContractTest`).

- [x] `H05-S07` Close H05 with adoption notes and handoff to H06.
  Deliverable: H05 closeout note in H05 docs + task archive updates.
  Notes: Completed on 2026-03-03.

## H06 Data and Persistence Consistency Review

Archived from `TASKS.md` on 2026-03-03 after H06 closure.

- [x] `H06` Data and persistence consistency review.
  Goal: verify entity design, migrations/data files, transaction boundaries, and query patterns for inconsistencies.
  Deliverable: persistence risk report and remediation plan.
  Exit criteria: integrity risks and performance hotspots are tracked with clear fixes.
  Notes: Completed on 2026-03-03. H06 is now the persistence baseline for future remediation and H07 planning.

### H06 Subtasks

- [x] `H06-S01` Build persistence architecture map and entity ownership baseline.
  Deliverable: `docs/tasks/closed/h06-persistence-map.md`.
  Notes: Completed.

- [x] `H06-S02` Inventory schema and data-shape drift risks.
  Deliverable: `docs/tasks/closed/h06-schema-drift-inventory.md`.
  Notes: Completed.

- [x] `H06-S03` Review query patterns and consistency semantics.
  Deliverable: `docs/tasks/closed/h06-query-consistency-findings.md`.
  Notes: Completed.

- [x] `H06-S04` Define canonical persistence contracts.
  Deliverable: `docs/tasks/closed/h06-persistence-contracts.md`.
  Notes: Completed.

- [x] `H06-S05` Add focused persistence regression tests for highest risks.
  Deliverable: targeted repository/service characterization tests + minimal consistency fixes.
  Notes: Completed on 2026-03-03 (`PersistenceYearSupport`, CNFIS year-filter hardening, ranking ISSN cache alignment, guard tests).

- [x] `H06-S06` Define phased remediation plan and guardrails.
  Deliverable: `docs/tasks/closed/h06-remediation-plan.md` + lightweight persistence verification.
  Notes: Completed on 2026-03-03. Added `npm run verify-h06-persistence`.

- [x] `H06-S07` Close H06 with adoption notes and handoff to H07.
  Deliverable: H06 closeout note + archive updates.
  Notes: Completed on 2026-03-03. Handoff direction: keep `R1 -> R4` order (`R1` citation uniqueness index/migration first) when resuming persistence remediation.

## H07 Error Handling, Validation, and Security Hardening

Archived from `TASKS.md` on 2026-03-04 after H07 closure.

- [x] `H07` Error handling, validation, and security hardening.
  Goal: unify input validation, exception mapping, auth/authz checks, and security defaults.
  Deliverable: standardized error/validation/security checklist with implementation gaps.
  Exit criteria: critical endpoints and forms comply with one consistent policy.
  Notes: Completed on 2026-03-04. H07 is now the security/validation/error baseline for H08+ planning and remediation sequencing.

### H07 Subtasks

- [x] `H07-S01` Build endpoint and trust-boundary security map.
  Deliverable: `docs/tasks/closed/h07-security-surface-map.md`.
  Notes: Completed.

- [x] `H07-S02` Inventory validation and binding drift risks.
  Deliverable: `docs/tasks/closed/h07-validation-drift-inventory.md`.
  Notes: Completed.

- [x] `H07-S03` Inventory exception/error handling consistency gaps.
  Deliverable: `docs/tasks/closed/h07-error-handling-findings.md`.
  Notes: Completed.

- [x] `H07-S04` Define canonical H07 contracts and policies.
  Deliverable: `docs/tasks/closed/h07-security-validation-contracts.md`.
  Notes: Completed.

- [x] `H07-S05` Add focused regression guards for highest H07 risks.
  Deliverable: targeted characterization tests for auth/validation/error paths + error boundary tests.
  Notes: Completed on 2026-03-04 (mixed unauthorized semantics, parse/role exception baselines, upload baseline, mutating-GET baseline, access-denied redirect and error template mappings).

- [x] `H07-S06` Define phased remediation plan and lightweight enforcement.
  Deliverable: `docs/tasks/closed/h07-remediation-plan.md` + `npm run verify-h07-guardrails`.
  Notes: Completed on 2026-03-04 (`R1..R4` remediation sequence and debt-aware guardrails).

- [x] `H07-S07` Close H07 with adoption notes and handoff to H08.
  Deliverable: H07 closeout/adoption note + archive updates.
  Notes: Completed on 2026-03-04. H08 handoff: keep H07 contracts (`C1..C10`) as fixed inputs; preserve guardrail command until remediation slices are executed.

## H08 Observability and Operability Foundation

Archived from `TASKS.md` on 2026-03-04 after H08 closure.

- [x] `H08` Observability and operability foundation.
  Goal: make failures diagnosable with structured logs, metrics, and health/readiness signals.
  Deliverable: minimum observability baseline and runbook starter.
  Exit criteria: common production failure modes are detectable and actionable.
  Notes: Completed on 2026-03-04. H08 baseline is active via H08 maps/findings/contracts, observability guardrails, and `verify-h08-baseline` enforcement command.

### H08 Subtasks

- [x] `H08-S01` Build observability surface map and signal inventory.
  Deliverable: `docs/tasks/closed/h08-observability-map.md`.
  Notes: Completed.

- [x] `H08-S02` Inventory logging and diagnostics drift risks.
  Deliverable: `docs/tasks/closed/h08-logging-drift-inventory.md`.
  Notes: Completed.

- [x] `H08-S03` Inventory health/readiness/operability gaps.
  Deliverable: `docs/tasks/closed/h08-operability-findings.md`.
  Notes: Completed.

- [x] `H08-S04` Define canonical observability and operability contracts.
  Deliverable: `docs/tasks/closed/h08-observability-contracts.md`.
  Notes: Completed.

- [x] `H08-S05` Add focused observability regression guards.
  Deliverable: `docs/tasks/closed/h08-regression-guards.md` + `npm run verify-h08-observability-guardrails`.
  Notes: Completed on 2026-03-04.

- [x] `H08-S06` Define phased remediation plan and lightweight enforcement.
  Deliverable: `docs/tasks/closed/h08-remediation-plan.md` + `npm run verify-h08-baseline`.
  Notes: Completed on 2026-03-04.

- [x] `H08-S07` Close H08 with adoption notes and handoff to H09.
  Deliverable: H08 closeout note + archive updates.
  Notes: Completed on 2026-03-04. H09 handoff: promote `verify-h08-baseline` into CI-required gates and keep remediation slices ordered `P0 -> P1 -> P2`.

## TASKS.md Archive Snapshot (2026-03-04)

# Project Recovery Tasks (High-Level)

Objective: turn the current feature bundle into a maintainable, testable, and evolvable product.

Done history moved to `TASKS-done.md`.

## Backlog

- `H01` completed and archived in `TASKS-done.md`.

- [x] `H02` Architecture boundaries and ownership.
  Goal: define module boundaries, responsibilities, and allowed dependencies between layers.
  Deliverable: lightweight architecture map and dependency rules.
  Exit criteria: new code placement rules are documented and enforceable in review.
  Status: completed on 2026-03-03.
  Note: architecture baseline and enforcement are active via `docs/architecture.md`, `docs/doc-governance.md`, and `npm run verify-architecture-boundaries`.

- [x] `H03` Contract and behavior baseline.
  Goal: capture current expected behavior for key flows before refactors.
  Deliverable: minimal contract suite (controller/service integration + key UI/API flows).
  Exit criteria: high-impact flows have regression coverage and a known pass/fail baseline.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H03-S01..S07 completion details and adoption guidance.

- [x] `H04` Test strategy and pyramid rebalance.
  Goal: reduce fragile end-to-end reliance and improve unit/integration signal quality.
  Deliverable: test taxonomy, gap matrix, and priority test additions.
  Exit criteria: each critical feature has at least one stable automated regression test.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H04-S01..S07 completion details and adoption guidance.

- [x] `H05` Frontend structure and asset discipline.
  Goal: standardize JS/CSS/template patterns to avoid divergent implementations.
  Deliverable: frontend conventions (entrypoints, shared utilities, template composition patterns).
  Exit criteria: duplicated UI logic is centralized and new pages follow the same conventions.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H05-S01..S07 completion details and adoption guidance.

- [x] `H06` Data and persistence consistency review.
  Goal: verify entity design, migrations/data files, transaction boundaries, and query patterns for inconsistencies.
  Deliverable: persistence risk report and remediation plan.
  Exit criteria: integrity risks and performance hotspots are tracked with clear fixes.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H06-S01..S07 completion details, guardrails, and H07 handoff guidance.

- [x] `H07` Error handling, validation, and security hardening.
  Goal: unify input validation, exception mapping, auth/authz checks, and security defaults.
  Deliverable: standardized error/validation/security checklist with implementation gaps.
  Exit criteria: critical endpoints and forms comply with one consistent policy.
  Status: completed on 2026-03-04.
  Note: archived in `TASKS-done.md` with H07-S01..S07 completion details, regression guards, and H08 handoff guidance.

- [x] `H08` Observability and operability foundation.
  Goal: make failures diagnosable with structured logs, metrics, and health/readiness signals.
  Deliverable: minimum observability baseline and runbook starter.
  Exit criteria: common production failure modes are detectable and actionable.
  Status: completed on 2026-03-04.
  Note: archived in `TASKS-done.md` with H08-S01..S07 completion details, guardrails, and H09 handoff guidance.

- [x] `H09` Build, CI, and quality gates.
  Goal: ensure every change passes reproducible checks and prevents regressions from merging.
  Deliverable: CI pipeline definition with lint/test/build/security gates.
  Exit criteria: required checks are automated and block broken changes.
  Status: completed on 2026-03-04.
  Note: CI hardening is enforced via `.github/workflows/h09-quality-gates.yml` (`guardrails`, `java-smoke`, `quality-full`) and `.github/workflows/h09-security-gates.yml` (`dependency-review`, `codeql-analysis`), with local parity command `npm run verify-h09-baseline`.

- [x] `H10` Documentation and contribution workflow.
  Goal: align README/CONTRIBUTING with actual architecture, setup, and delivery flow.
  Deliverable: contributor playbook for local dev, testing, and release hygiene.
  Exit criteria: a new contributor can run, test, and modify the project without tribal knowledge.
  Status: completed on 2026-03-04.
  Note: completed via `H10-S01..S08` with the durable top-level docs set (`docs/quality-gates.md`, `docs/failure-triage.md`, `docs/release-hygiene.md`, `docs/doc-governance.md`) and walkthrough validation evidence in `docs/tasks/closed/h10-validation-walkthrough.md`.

### H10 Subtasks (Planned)

- [x] `H10-S01` Documentation inventory and gap map.
  Goal: map current docs (`README`, `CONTRIBUTING`, `docs/*`) against actual workflows and guardrails.
  Deliverable: `docs/tasks/closed/h10-doc-inventory.md` with outdated/missing sections and owners.
  Exit criteria: all contributor-critical gaps are identified and prioritized.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-doc-inventory.md` with source coverage matrix, owner mapping, and prioritized closure order for `H10-S02..S08`.

- [x] `H10-S02` Local setup and runbook alignment.
  Goal: make first-run setup deterministic for new contributors.
  Deliverable: updated `README.md` with prerequisites, local run, config overrides, and troubleshooting.
  Exit criteria: a new contributor can boot the app and run smoke checks without tribal knowledge.
  Status: completed on 2026-03-04.
  Note: `README.md` now includes a deterministic first-run quickstart, explicit config override options, health endpoint contract, and local troubleshooting baseline aligned with H09 parity checks.

- [x] `H10-S03` Contributor workflow playbook.
  Goal: define one clear change workflow from branch creation to PR merge.
  Deliverable: updated `CONTRIBUTING.md` (branching, commit conventions, required local checks, PR expectations).
  Exit criteria: workflow is explicit and consistent with enforced CI gates.
  Status: completed on 2026-03-04.
  Note: `CONTRIBUTING.md` now defines an end-to-end contributor workflow and change-type verification matrix aligned with enforced H09 CI checks.

- [x] `H10-S04` Quality gate command matrix.
  Goal: document when to run each verification command (`H03`-`H09` baselines and guardrails).
  Deliverable: `docs/tasks/closed/h10-quality-gates-matrix.md` (`change type -> required commands`).
  Exit criteria: contributors can select required checks by change scope.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-quality-gates-matrix.md` and linked it from `CONTRIBUTING.md` as the canonical change-type command selector.

- [x] `H10-S05` Failure triage and debugging guide.
  Goal: reduce time-to-fix for common guardrail/CI failures.
  Deliverable: troubleshooting sections for architecture, persistence, security, observability, and CI jobs.
  Exit criteria: each required CI check has a `failure -> likely cause -> fix path`.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-failure-triage.md` with guardrail/build/security CI triage matrix and linked it from `CONTRIBUTING.md`.

- [x] `H10-S06` Release hygiene baseline.
  Goal: define minimal release-safe merge hygiene.
  Deliverable: PR checklist + merge/release checklist (risk notes, rollback notes, evidence commands).
  Exit criteria: release-affecting changes follow a documented checklist.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-release-hygiene.md` with PR/merge/evidence/rollback baseline and linked it from `CONTRIBUTING.md`.

- [x] `H10-S07` Docs governance and ownership.
  Goal: prevent documentation drift after H10 completion.
  Deliverable: docs ownership table, update triggers, and review cadence policy.
  Exit criteria: each key doc has an owner and mandatory update triggers.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-doc-governance.md` with ownership matrix, mandatory update triggers, and review cadence; linked policy from `CONTRIBUTING.md`.

- [x] `H10-S08` Validation and closure.
  Goal: verify the documentation workflow works in practice.
  Deliverable: one walkthrough by a fresh-contributor path plus fixes, then H10 closeout note in `TASKS.md`.
  Exit criteria: all H10 docs are updated, cross-linked, and validated with current commands.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-validation-walkthrough.md` with executed command evidence (`npm run verify-h09-baseline`, `./gradlew bootRun -m`) and successful outcomes.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.



`H01`-`H02` subtasks and closure details are archived in `TASKS-done.md`.

## Remediation Execution Backlog (Actionable)

Source set reviewed: `docs/tasks/closed/h02-remediation-plan.md`, `docs/tasks/closed/h06-remediation-plan.md`, `docs/tasks/closed/h07-remediation-plan.md`, `docs/tasks/closed/h08-remediation-plan.md` and linked findings/contracts inventories.

### P0 (High Priority)

- [x] `B01` H06-R1: Enforce citation pair uniqueness at DB level.
  Goal: close `Q-H06-02` with persistence-layer guarantees.
  Scope:
  - add compound unique index for citation (`citedId`, `citingId`);
  - implement one-time safe dedupe migration for existing duplicates;
  - keep app-level duplicate guard as defense in depth.
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R1`), `docs/tasks/closed/h06-query-consistency-findings.md`.
  Done criteria: duplicate citation writes are rejected by DB; migration is reproducible and documented.
  Status: completed on 2026-03-04.
  Note: added `CitationUniquenessMigrationService` + gated runner (`off|report|apply`) with keep-lowest-id dedupe and runtime unique index `uniq_cited_citing`; added unit + integration coverage.

- [x] `B02` H07-R1: Authorization scope and 401/403 semantics alignment.
  Goal: close `S-H07-01`, `E-H07-02`, `E-H07-04`.
  Scope:
  - explicitly scope privileged MVC/API routes;
  - enforce zone contract (MVC redirect-to-login, API 401 JSON; denied -> MVC 403 view/API 403 JSON).
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R1`), `docs/tasks/closed/h07-security-validation-contracts.md`.
  Done criteria: no privileged route depends only on `anyRequest().authenticated()`; behavior is consistent by zone.
  Status: completed on 2026-03-04.
  Note: added explicit `/admin/**`, `/api/admin/**`, `/api/export/**`, `/api/scrape/**` authority scoping and API-aware JSON `401/403` handlers; normalized `/user/**` unauthenticated flow to login redirect with filter-enabled security contract tests.

- [x] `B03` H08-P0: Logging hygiene and disclosure cleanup.
  Goal: close `L-H08-01`, `L-H08-04`, `L-H08-08`, `L-H08-05`, `O-H08-06`.
  Scope:
  - remove runtime `printStackTrace` and `System.out/System.err` in active paths;
  - fix logger owner drift (`ComputerScienceBookService`);
  - remove raw external payload logging in `ScopusService#parseToken`;
  - preserve endpoint behavior while improving diagnostics.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (`P0`), `docs/tasks/closed/h08-logging-drift-inventory.md`.
  Done criteria: H08 allowlists shrink accordingly; failures are logged with structured context.
  Status: completed on 2026-03-04.
  Note: replaced active runtime `printStackTrace` and targeted `System.out/System.err` in transport/service/importing/reporting paths; fixed `ComputerScienceBookService` logger owner drift; removed raw payload print in `ScopusService#parseToken`; tightened `verify-h08-observability-guardrails` allowlists.

### P1 (Medium-High Priority)

- [x] `B04` H06-R2: Complete year-parsing safety rollout.
  Goal: close remaining `Q-H06-03` paths under contract `C3`.
  Scope:
  - replace remaining raw year parsing in high-impact report/export/search/grouping flows with `PersistenceYearSupport`;
  - finalize policy for `ActivityInstance#getYear`.
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R2`), `docs/tasks/closed/h06-persistence-contracts.md`.
  Done criteria: no raw `substring(0,4)` year filtering/grouping remains in targeted high-impact flows.
  Status: completed on 2026-03-04.
  Note: rolled out helper-based year parsing across scoring/grouping/export hotspots; added `PersistenceYearSupport.extractYearString(...)` and `ActivityInstance#getYearOptional()`; expanded `verify-h06-persistence` to enforce no raw year parsing regression on remediated files.

- [x] `B05` H06-R3: Identity/order/dedupe consistency.
  Goal: close `Q-H06-04`, `Q-H06-06`, `Q-H06-07`.
  Scope:
  - normalize `id`/`eid`/`doi` lookup usage per contract;
  - enforce deterministic sorting for user-visible lists/exports;
  - remove author-aggregation duplicate amplification.
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R3`), `docs/tasks/closed/h06-query-consistency-findings.md`.
  Done criteria: stable ordering and deduped outputs are covered by tests.
  Status: completed on 2026-03-04.
  Note: user publication aggregation now dedupes by publication ID; deterministic publication/citation ordering contract applied across user/admin/group hotspots; user edit/save flow naming normalized to canonical DB `id`; `verify-h06-persistence` extended with `R3` guard checks.

- [x] `B06` H07-R2: Validation boundary hardening.
  Goal: close `V-H07-01`, `V-H07-02`, `V-H07-03`, `V-H07-06`.
  Scope:
  - DTO + `@Valid` rollout for top-risk write and import endpoints;
  - safe/bounded parsing for `start/end` and role conversion;
  - deterministic 4xx behavior for malformed input.
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R2`), `docs/tasks/closed/h07-validation-drift-inventory.md`.
  Done criteria: boundary validation enforced on targeted endpoints; invalid input no longer escapes as 5xx.
  Status: completed on 2026-03-04.
  Note: migrated `/api/admin/users` + `/api/admin/researchers` create/update to DTO + `@Valid`; replaced CNFIS start/end `Integer.parseInt` with bounded year-range validation returning `400`; added role allowlist validation in `/admin/users/create` with redirect+flash fallback; updated H07 guardrails and regression tests.

- [x] `B07` H07-R3: Centralized exception mapping and transport logging cleanup.
  Goal: close `E-H07-01`, `E-H07-03`, `E-H07-05`, `E-H07-06`, `E-H07-07`.
  Scope:
  - introduce `@ControllerAdvice` mappings for common failure classes;
  - remove catch-and-print/swallowed exceptions on transport paths;
  - align API/MVC error envelopes/views.
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R3`), `docs/tasks/closed/h07-error-handling-findings.md`.
  Done criteria: consistent mapped error behavior with structured diagnostics.
  Status: completed on 2026-03-04.
  Note: added split centralized exception mapping (`ApiExceptionHandler` + `MvcExceptionHandler`), switched `UserService.updateUser` to `Optional` with deterministic `404` in controller, tightened `/api/export` to deterministic failure behavior, and extended `verify-h07-guardrails` to block generic export swallow-catch regressions.

- [x] `B07A` H07 login flow practical standards alignment.
  Goal: align login flow with modern browser/password-manager and explicit form-login contracts.
  Scope:
  - login template semantic/autocomplete metadata;
  - explicit Spring form-login + logout endpoints/redirects;
  - security regression tests for login success/failure/logout;
  - H07 guardrail checks for login input naming/autocomplete contract.
  Inputs: login baseline plan (practical scope), `docs/tasks/closed/h07-security-validation-contracts.md`.
  Done criteria: deterministic login/logout contract + test/guardrail coverage.
  Status: completed on 2026-03-04.
  Note: `/login` GET/POST contract is explicit; invalid credentials redirect to `/login?error`, logout redirects to `/login?logout`; login template now uses `name=\"username\"/\"password\"` with `autocomplete=\"username\"/\"current-password\"`; guardrails enforce these attributes.

- [x] `B08` H08-P1: Correlation context propagation.
  Goal: close `L-H08-02`, `L-H08-06`, `L-H08-07`, `O-H08-07`.
  Scope:
  - add request correlation IDs for HTTP flows;
  - standardize scheduler context (`jobType`, `taskId`, phase);
  - ensure error logs include correlation context and align with H07 mappings.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (`P1`), `docs/tasks/closed/h08-observability-contracts.md`.
  Done criteria: request/job traces are diagnosable end-to-end.
  Status: completed on 2026-03-04.
  Note: implemented `X-Request-Id` adopt-and-propagate filter + request MDC (`requestId`, `route`, `userId`); added Scopus scheduler context helper and phase-aware MDC (`jobType`, `taskId`, `phase`) for batch/per-task logs; centralized exception handlers now include request correlation context; `verify-h08-observability-guardrails` extended with B08 checks.

- [x] `B09` H09 bootstrap: Promote local guardrails to required CI checks.
  Goal: operationalize H02/H06/H07/H08 enforcement in pipeline.
  Scope:
  - include `verify-architecture-boundaries`, `verify-h06-persistence`, `verify-h07-guardrails`, `verify-h08-baseline` as required CI checks;
  - document policy for tightening/allowlist shrink.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (H09 handoff), remediation guardrail docs.
  Done criteria: CI blocks merges on guardrail failure.
  Status: completed on 2026-03-04.
  Note: added GitHub Actions workflow `.github/workflows/h09-quality-gates.yml` with `guardrails` and `java-smoke` jobs plus failure artifact upload; documented Stage 1 soft rollout and Stage 2 required-check transition in `docs/tasks/closed/h09-ci-gates.md`; included H08 baseline handoff confirmation.

### P2 (Planned / Structural)

- [x] `B10` H06-R4: Persistence consistency cleanup and namespace hygiene.
  Goal: close `Q-H06-05`, `Q-H06-08`, `Q-H06-09`, `D-H06-03`.
  Scope:
  - text-search normalization policy rollout;
  - retire typo’d repo API (`findAllByeIssn`) via compatibility step;
  - forum export dedupe normalization (remove sentinel checks);
  - plan and execute collection naming migration (`schodardex` -> `scholardex`).
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R4`), `docs/tasks/closed/h06-schema-drift-inventory.md`.
  Done criteria: API naming and data-shape drift items have closed implementation path.
  Status: completed on 2026-03-04.
  Note: delivered case-insensitive admin title search normalization, forum export dedupe normalization (`issn -> eIssn -> sourceId`), and single-step task namespace cutover to `scholardex.tasks.*` with startup-gated migration runner (`off|report|apply`) and integration coverage.

- [x] `B10A` H06-R4 follow-up: remove `findAllByeIssn` compatibility alias.
  Goal: complete typo-method retirement after stabilization window.
  Scope:
  - remove deprecated `findAllByeIssn` from `RankingRepository`;
  - tighten `verify-h06-persistence` to zero allowlist for typo method.
  Inputs: `B10` compatibility bridge completion evidence.
  Done criteria: no `findAllByeIssn` references remain in codebase.
  Status: completed on 2026-03-04.
  Note: deprecated alias removed from `RankingRepository`; compatibility test scaffolding removed; `verify-h06-persistence` now enforces zero-allowlist for typo method usage.

- [x] `B11` H07-R4: CSRF, mutating-GET migration, and upload hardening.
  Goal: close `C3`, `C4`, `V-H07-04`.
  Scope:
  - re-enable CSRF for browser form flows with explicit exemptions only when justified;
  - migrate `delete/duplicate` mutating GET routes to safe verbs;
  - enforce upload size/type/schema validation in group import.
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R4`), `docs/tasks/closed/h07-security-validation-contracts.md`.
  Done criteria: browser mutation routes are CSRF-protected and non-GET; upload policy enforced.
  Status: completed on 2026-03-04.
  Note: CSRF is re-enabled for MVC flows with explicit `/api/**` exemption; mutating `delete/duplicate` GET routes were migrated to POST across targeted controllers/templates; group CSV import now enforces strict size/type/schema validation.

- [x] `B12` H08-P2: Actuator/metrics/readiness baseline implementation.
  Goal: close `O-H08-01`, `O-H08-02`, `O-H08-03`, `O-H08-04`, `O-H08-05`.
  Scope:
  - add actuator and explicit readiness/liveness policy;
  - add minimum metrics coverage for startup/scheduler/export/external dependency calls;
  - add async executor saturation/queue diagnostics;
  - define startup phase readiness semantics.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (`P2`), `docs/tasks/closed/h08-operability-findings.md`, `docs/tasks/closed/h08-observability-contracts.md`.
  Done criteria: production failure modes are machine-detectable via health and metrics endpoints.
  Status: completed on 2026-03-04.
  Note: actuator baseline and readiness/liveness groups are active, startup/external dependency health contributors are wired, scheduler/export/startup/external metrics are instrumented, async queue/rejection diagnostics are exposed, and H08 observability guardrails now assert P2 baseline wiring.

- [x] `B13` H02 residual V01 closure outside baseline pair.
  Goal: reduce remaining `Z1 -> Z4` controller repository debt in non-baseline controllers.
  Scope:
  - prioritize `AdminViewController` and smaller controllers still directly importing repositories;
  - migrate residual orchestration to Z2 facades while preserving behavior.
  Inputs: `docs/tasks/closed/h02-remediation-plan.md` (`R1 residual`), `docs/tasks/closed/h02-violations.md`.
  Done criteria: repository-import allowlist in `verify-architecture-boundaries` is materially reduced.
  Status: completed on 2026-03-04.
  Note: residual controllers were migrated to Z2 facades (`AdminCatalogFacade`, `UserRankingFacade`, `ActivityManagementFacade`, `GroupReportsManagementFacade`, `IndividualReportsManagementFacade`, `UrapRankingFacade`, `UserActivityInstanceFacade`, `PublicationWizardFacade`); controller/view repository imports are now zero and architecture allowlist is empty.


- [x] `H18` WoS ranking enrichment (computed fallback data + admin control page).
  Goal: enrich WoS ranking records with computed values for fields missing in import files, without overriding values explicitly provided by source files.
  Deliverable: enrichment flow that computes `rank`, `quartile`, and `quartileRank` per `category + edition`, plus an admin page to run/inspect enrichment.
  Exit criteria: for each `category + edition`, source-provided values are preserved; missing values are deterministically computed; admins can run and validate enrichment from a dedicated page.
  Status: archived from `TASKS.md` on 2026-03-13 after closing remaining subtasks based on existing implementation and regression coverage.
  Subtasks:
  - [x] `H18.1` Define enrichment computation contract.
    Deliverable: documented deterministic rules for `rank`, `quartile`, and `quartileRank` at `category + edition` scope, including tie handling and null/insufficient-data behavior.
    Exit criteria: rules are unambiguous and implementation-ready.
    Status: completed on 2026-03-08.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h18.1-wos-ranking-enrichment-contract.md`.
    - Canonical linkage amendment: `docs/tasks/closed/h17-scopus-canonical-contract.md` (H18.1 section).
    - Locked decisions: competition rank ties (`1,1,3`), position-bucket quartiles, source `quarter` precedence, missing metric value -> skip (non-conflict).
  - [x] `H18.2` Integrate enrichment into WoS ingestion/projection flow.
    Deliverable: service-level enrichment step that preserves source values and computes only missing fields.
    Exit criteria: persistence reflects "source if present, computed otherwise" for all three fields.
    Status: completed on 2026-03-08.
    Handover:
    - Canonical enrichment implementation: `WosFactBuilderService#enrichMissingCategoryRankingFields` computes missing `rank`, `quarter`, `quartileRank` while preserving source-provided fields.
    - Initialization order now includes explicit enrichment step before projections (`/admin/initialization/wos/enrichCategoryRankings`).
    - Big-bang flow executes enrichment between `build-facts` and `build-projections`.
  - [x] `H18.3` Add admin backend endpoints for enrichment operations.
    Deliverable: secured admin endpoints to trigger enrichment and retrieve summary results (processed, computed, preserved, failed).
    Exit criteria: authorized admins can execute enrichment and get deterministic run summaries.
    Status: completed on 2026-03-08.
    Handover:
    - New admin JSON endpoints: `POST /admin/initialization/wos/enrichment/run` and `GET /admin/initialization/wos/enrichment/summary`.
    - Deterministic summary DTO: `stepName`, `executed`, `startedAt`, `completedAt`, `processed`, `computed`, `preserved`, `failed`, `skipped`, `note`.
    - Locked mapping used in backend reporting: `computed=updated`, `failed=errors`, `preserved=processed-computed-failed`.
  - [x] `H18.4` Build dedicated admin page for WoS enrichment.
    Deliverable: admin UI page to start enrichment runs and review per-run outcome metrics.
    Exit criteria: page is accessible to admins only and supports operational verification.
    Status: completed on 2026-03-08.
    Handover:
    - Dedicated page endpoint: `GET /admin/initialization/wos/enrichment` with run action `POST /admin/initialization/wos/enrichment/runPage`.
    - Page shows latest deterministic enrichment metrics (`processed`, `computed`, `preserved`, `failed`, `skipped`) and links to JSON summary endpoint.
    - Initialization step 3 now exposes direct navigation to the dedicated enrichment page (`Open page`).
  - [x] `H18.5` Backfill historical WoS records.
    Deliverable: backfill-capable execution path for existing WoS category facts using the same enrichment contract as normal pipeline runs.
    Exit criteria: historical records are enriched according to the same contract, with idempotent rerun behavior.
    Status: completed on 2026-03-13.
    Handover:
    - Existing-data backfill is handled by `WosFactBuilderService#enrichMissingCategoryRankingFields`, which scans all persisted `WosCategoryFact` rows and updates only records still missing `rank`, `quarter`, or `quartileRank`.
    - The backfill-capable enrichment step is available both as a standalone admin action (`/admin/initialization/wos/enrichCategoryRankings`, `/admin/initialization/wos/enrichment/run`, `/admin/initialization/wos/enrichment/runPage`) and inside the WoS big-bang sequence between `build-facts` and `build-projections`.
    - Reruns are operationally idempotent because records with fully populated source/computed values are skipped and preserved by the same field-preservation rules.
  - [x] `H18.6` Add regression and integration test coverage.
    Deliverable: tests for preservation logic, computation correctness, and admin trigger flow.
    Exit criteria: automated tests cover success paths and key failure/edge cases.
    Status: completed on 2026-03-13.
    Handover:
    - Regression coverage for computation/preservation lives in `WosFactBuilderServiceTest` (computed rank/quarter/quartileRank, source-quarter preservation, missing-metric skip behavior, and category tuple handling).
    - Admin trigger coverage lives in `AdminInitializationControllerContractTest`, `AdminInitializationSecurityContractTest`, and `RankingMaintenanceFacadeTest` (redirect/API/page flow, authorization, and deterministic summary mapping).
    - Integration tests were intentionally not added in this closeout; the task is considered satisfied by the existing regression and controller/facade coverage until a broader workflow-level test slice is prioritized.


- [x] `H17` Scopus canonical import pipeline transition.
  Goal: replace direct Scopus document writes with a canonical ingestion pipeline aligned to WoS patterns (`events -> facts -> views`) while converging runtime publication reads to a derived `scholardex.publication` projection that merges Scopus, WoS, and Google Scholar enrichments.
  Deliverable: high-level migration to Scopus import events ledger, normalized Scopus facts layer, explicit cross-source field ownership contract, and merged projection views consumed by application/reporting flows.
  Exit criteria: Scopus ingest is replayable/idempotent from source events, source-specific facts remain authoritative, merged publication views are deterministic and lineage-backed, and guardrail checks protect against regressions and ownership drift.
  Assumption lock (2026-03-06): big-bang cutover for all Scopus entities; no historical data migration/backfill is required (clean-state bootstrap only).
  Amendment note (2026-03-06): H17.1 contract is extended to include cross-source ownership boundaries and derived merged-publication projection constraints (`scholardex.publication*`) without reopening H17.1 status.
  Amendment note (2026-03-07): WoS canonical fact semantics are split: journal score facts in `WosMetricFact` (`journalId + year + metricType`) and category ranking facts in `wos.category_facts` (`journalId + year + metricType + categoryNameCanonical + editionNormalized`); projections/read paths join score + ranking facts.
  Amendment note (2026-03-07): WoS category ranking facts now carry both `quarter + quartileRank` and `rank` where `rank` is category+edition rank (official JSON), while government data may provide only quarter.
  Amendment note (2026-03-07): WoS detail projection/read UX now includes `alternativeNames` + `alternativeIssns` and uses lightweight chart rendering for details visualizations.
  Subtasks:
  - [x] `H17.1` Canonical Scopus contract lock.
    Deliverable: `docs/tasks/closed/h17-scopus-canonical-contract.md` with canonical collections, required fields, identity keys, lineage fields, and source-policy rules for publications, citations, forums, authors, affiliations, and funding.
    Exit criteria: schema, identity, and source policy are decision-locked before implementation changes.
  - [x] `H17.2` Canonical storage and index baseline.
    Deliverable: canonical Mongo collection/index definitions for Scopus import events, normalized facts, Scopus read views, and merged `scholardex.publication*` projection/index prerequisites (lookup/sort/reporting keys) with idempotence-oriented unique constraints.
    Exit criteria: fresh environment creates canonical and merged-projection storage deterministically with required uniqueness/index coverage.
  - [x] `H17.3` Event ledger ingestion pipeline.
    Deliverable: ingestion paths write immutable Scopus import events (no direct entity writes) with deterministic metadata (`source`, `ingestedAt`, `batchId`, `correlationId`, `payloadHash`).
    Exit criteria: all Scopus import entrypoints produce events only.
  - [x] `H17.4` Deterministic fact builders (all entities).
    Deliverable: replayable transformation flow from events into normalized facts for publications, citations, forums, authors, affiliations, and funding with field-ownership safeguards that prevent Scopus builders from clobbering non-Scopus enrichments.
    Exit criteria: replaying identical event input yields identical Scopus fact state (idempotent/upsert-safe) and ownership boundaries are preserved.
  - [x] `H17.5` Projection views and query contracts.
    Deliverable: deterministic projection builders materialize `scopus.forum_search_view`, `scopus.author_search_view`, `scopus.affiliation_search_view`, and enriched `scholardex.publication_view`; runtime admin/API/reporting/scoring reads use projection-backed contracts with merged-publication lookup compatibility (`id` primary, plus `eid`/`wosId`/`googleScholarId`).
    Exit criteria: read flows are projection-backed, publication identity resolution normalizes to projection `id`, and WoS/Scholar enrichment persistence is projection-owned without Scopus field clobbering.
  - [x] `H17.6` Big-bang read/write cutover and legacy retirement.
    Deliverable: switch active Scopus write flows to canonical ingestion and publication-facing read flows to merged `scholardex.publication` projection; remove/disable legacy direct-write and direct-read Scopus document paths in runtime facades; centralize WoS/Scopus big-bang operations on dedicated admin initialization UI (`/admin/initialization`) with deterministic step actions and full-run orchestration.
    Exit criteria: no active runtime path writes legacy Scopus documents directly, publication reads no longer depend on legacy direct Scopus documents, and big-bang maintenance is executed from the dedicated initialization page (rankings page no longer exposes maintenance controls).
  - [x] `H17.7` Scheduler and task flow canonicalization.
    Deliverable: `ScopusPublicationUpdate` and `ScopusCitationsUpdate` execution publishes canonical events and triggers canonical transform/projection flow.
    Exit criteria: scheduled/manual Scopus updates are fully canonical and replay-safe.
  - [x] `H17.8` Guardrails and regression gates.
    Deliverable: guardrail checks that fail on legacy direct-write Scopus persistence and enforce canonical pipeline usage in CI.
    Exit criteria: CI blocks reintroduction of non-canonical Scopus persistence patterns.
  - [x] `H17.9` Validation and closeout evidence.
    Deliverable: run log + closeout notes capturing `./gradlew compileJava`, targeted Scopus tests, `./gradlew check`, and replay/idempotence verification evidence.
    Exit criteria: local and CI critical gates are green with canonical Scopus pipeline active.
  - [x] `H17.10` Cross-source merge policy and linker rules.
    Deliverable: production linker/merge implementation for `scholardex.publication_view` with exact-key resolution precedence (`id` -> `eid` -> `doiNormalized`), conflict quarantine persistence, NON-WOS exclusion, and migrated WoS enrichment call-sites (`UserReportFacade`, `GroupCnfisExportFacade`) that write through linker-owned lineage fields only.
    Exit criteria: enrichment writes are deterministic, ownership-safe, replay-safe, conflict-aware (quarantine/non-mutating), and no reporting/export flow bypasses linker service for WoS/Scholar-owned keys.

## H19 Multi-source Scholardex Identity and Ingestion Architecture

Archived from `TASKS.md` on 2026-03-11 after top-level closure and backlog cleanup.

- [x] `H19` Multi-source Scholardex identity and ingestion architecture.
  Goal: make Scholardex the canonical identity and link graph layer across publications, authors, forums, and affiliations, supporting four sources (`SCOPUS`, `WOS`, `GSCHOLAR`, `USER_DEFINED`) with deterministic lineage, linking, and runtime reads optimized for indicator computation.
  Deliverable: unified canonical contracts + storage models + ingestion/linking pipelines + immediate runtime cutover so all operational reads/writes resolve through Scholardex entities and canonical relationship edges, not source-specific silo models.
  Exit criteria: publication/author/forum/affiliation identity is source-agnostic and deterministic; WoS-first onboarding is complete; Scholar (Publish or Perish) and user-defined imports are supported; runtime paths are cut over to Scholardex; source-specific legacy identity paths are removed from runtime; citations are canonical-ID based across all sources; all entity conflict types are captured in generic conflict storage; source-to-canonical mapping is queryable and replay-stable; canonical publication-author linkage is queryable and deterministic; canonical author-affiliation linkage is queryable and deterministic; affiliation-side traversal for scoring/reporting is fast-path capable.
  Execution order override (locked): for remaining H19 implementation, complete citation migration first (`H19.9`) before finalizing Scopus runtime flow/data initialization and before closing runtime cutover (`H19.7`).
  Subtasks:
  - [x] `H19.1` Define canonical multi-source identity and ownership contract.
    Deliverable: locked contract for Scholardex entities (`publication`, `author`, `forum`, `affiliation`, `citation`) with per-source IDs, provenance/lineage fields, conflict rules, source-link mapping rules, and replay/idempotence semantics.
    Exit criteria: one contract document is implementation-ready and explicitly defines source ownership boundaries for Scopus/WoS/Scholar/User-defined.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h19.1-multisource-identity-contract.md`.
  - [x] `H19.2` Define canonical keying and merge policy for journal/forum identity.
    Deliverable: deterministic forum identity policy that links WoS journal identity and Scopus forum identity into Scholardex forum records, with normalization and collision handling rules.
    Exit criteria: deterministic link keys and conflict quarantine behavior are documented and testable.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h19.2-forum-keying-merge-contract.md`.
  - [x] `H19.3` Implement Scholardex publication identity model v2.
    Deliverable: publication model supporting source IDs (`eid`, `wosId`, `googleScholarId`, `userSourceId`) plus canonical `scholardexPublicationId` and lineage metadata, with canonical `authorIds` aligned to relationship-edge contracts.
    Exit criteria: all publication ingest/build paths can persist and resolve the new identity model without ambiguity, and publication author linkage is consistent with canonical authorship edges.
  - [x] `H19.4` Implement Scholardex author identity model v2 (researcher-linked).
    Deliverable: author model that supports multiple source author IDs (Scopus/WoS/Scholar/User) as source-identity canonical facts, with canonical `affiliationIds` aligned to relationship-edge contracts, researcher linkage maintained on the researcher side via `primaryScholardexAuthorId`, and deterministic merge rules.
    Exit criteria: author linking and lookup are source-agnostic and deterministic for scoring/reporting entrypoints, and author-affiliation linkage is consistent with canonical author-affiliation edges.
  - [x] `H19.5` Implement Scholardex affiliation identity model v2.
    Deliverable: affiliation model that supports multiple source affiliation IDs and alias resolution across Scopus/WoS/Scholar/User, with reverse-link query support via canonical edge/index contracts (no forum-style reverse arrays required).
    Exit criteria: affiliation linking resolves deterministically, deduplicates source aliases, and supports fast affiliation-side traversal for scoring/reporting entrypoints.
  - [x] `H19.6` Build WoS-first onboarding into Scholardex entities.
    Deliverable: WoS ingestion/linking pipeline that populates/links Scholardex publication/forum/author/affiliation identities using existing WoS canonical facts/views.
    Exit criteria: WoS-only journals/publications not present in Scopus are represented and queryable in Scholardex runtime reads.
  - [x] `H19.9` Canonical citation model and migration from EID-only citation path.
    Deliverable: `scholardex.citation_facts` design and implementation keyed by canonical publication IDs, with migration/cutover from source/EID-bound citation reads.
    Exit criteria: WoS-only and Scholar-only publications participate in citation edges without EID dependency.
    Status: completed (canonical citation facts + runtime citation read cutover).
  - [x] `H19.7` Immediate runtime cutover to Scholardex read/write paths.
    Deliverable: all runtime read/write entrypoints (user/admin/report/export/scoring lookups) use Scholardex canonical paths directly; source-silo runtime identity paths are removed.
    Exit criteria: no runtime dependency remains on legacy source-specific identity stores for publication/author/forum/affiliation/citation resolution; citation runtime paths resolve via canonical citation facts.
    Status: implementation largely complete for publication/author/forum/affiliation/citation; remaining closeout is decommission/validation hardening.
  - [x] `H19.10` Generic identity conflict model + admin operations.
    Deliverable: `scholardex.identity_conflicts` contract and implementation covering publication/forum/author/affiliation ambiguity, plus operational listing/resolve/clear flows.
    Exit criteria: ambiguous merges across all canonical entity types are captured and manageable through one generic conflict surface.
  - [x] `H19.11` Source-link ledger + replay/traceability integration.
    Deliverable: `scholardex.source_links` contract and implementation mapping `(entityType, source, sourceRecordId)` to canonical entity IDs with deterministic state transitions.
    Exit criteria: traceability/replay workflows can resolve source record to canonical entity deterministically in one query path.
  - [x] `H19.12` Canonical relationship-edge model for indicator runtime.
    Deliverable: authoritative `scholardex.authorship_facts` (`publication -> author`) and `scholardex.author_affiliation_facts` (`author -> affiliation`) with deterministic ids, lineage, idempotence, and conflict policy.
    Exit criteria: canonical edge writes/replays are deterministic, conflict-safe, and consistent with `publication_facts.authorIds` and `author_facts.affiliationIds`.
  - [x] `H19.13` Indicator/report query cutover to edge-backed traversals.
    Deliverable: scoring/report/export/user/admin query paths use canonical edge-backed traversals for publication-by-author and author-by-affiliation access, with performance parity/guardrail checks.
    Exit criteria: runtime indicator computation no longer depends on source-silo author/affiliation linkage paths and passes parity/performance gates.
  - [x] `H19.8` End-to-end validation, parity, and operability gates.
    Deliverable: workflow and integration tests covering implemented sources (`SCOPUS`, `WOS`, current manual/user wizard `USER_DEFINED` path), identity-link conflicts, replay/idempotence, and cutover regressions; observability metrics and failure triage hooks.
    Exit criteria: CI gates catch identity/linking regressions and operational dashboards expose source-level ingest/link health for implemented sources.
    Handover:
    - Validation/operability contract: `docs/tasks/closed/h19.8-validation-operability-gates.md`.

## H51 Mongo Unique-Index Integrity Sweep

Archived from `TASKS.md` on 2026-06-14 (with `H54`). Satisfied by `H54.2`.

- [x] `H51` Mongo unique-index integrity sweep and project-wide auto-index-creation enablement. *(completed 2026-06-09 via H54.2)*
  Goal: enable `spring.data.mongodb.auto-index-creation=true` project-wide without crashing startup on existing duplicate data.
  Deliverable: inventory of every `unique = true` Mongo index declared in the codebase (audit found 34 declared unique indexes across ~28 collections); per-collection duplicate audit and dedup policy; cleanup migration(s); the property flip; removal of the `ReportImportSessionIndexInitializer` shim. Tooling: `scripts/h51-unique-index-duplicate-audit.js`.
  Outcome: `auto-index-creation=true`, all declared indexes build at startup with no `DuplicateKeyException`/conflict, shim removed, audit reports 0 drift / 0 duplicates.

## H54 Ingestion Pipeline & Record-Keeping Rebuild

Archived from `TASKS.md` on 2026-06-14. Closed task doc: `docs/tasks/closed/h54-ingestion-pipeline-rebuild.md`.

- [x] `H54` Ingestion pipeline & record-keeping rebuild. *(completed 2026-06-13)*
  Goal: restructure ingestion around one principle — a thin human-authored layer is precious and backed up; everything else is a deterministic, rebuildable function of source files/APIs — with one writer per collection, natural-key upserts, enforced indexes, provenance, and rebuild-in-place of migrations.
  Exit criteria: every declared unique index created/matched at startup (subsumes `H51`); each derived collection has exactly one writer and a deterministic `rebuild()`; re-ingesting unchanged is a no-op and changed supersedes; full wipe + reimport reproduces counts and sampled scores, second rebuild byte-identical; audit reports zero drift/duplicates.
  Outcome (2026-06-13): H54.1 precious snapshot done; the full from-scratch rebuild exercised the exit criteria (counts reproduced, index audit clean, parity intact, ~2.3M accumulated junk docs purged). Subsumes `H51`. Determinism later confirmed byte-identical (2026-06-14, two clean rebuilds). A true full-wipe single entry point (`PipelineRebuildService.rebuildAllDerivedFromSource()` + `POST /admin/initialization/rebuildAllDerived`) was added 2026-06-14 so the rebuild no longer depends on per-source reset coverage being exhaustive.

## H55 Forum Identity Unification

Archived from `TASKS.md` on 2026-06-14. Closed task doc: `docs/tasks/closed/h55-forum-identity-unification.md`.

- [x] `H55` Forum identity unification (canonical forum id everywhere). *(completed 2026-06-13)*
  Goal: identify every journal/venue by its canonical Scholardex forum id across storage, projection, and display — eliminating the dual id scheme (raw Scopus forum id vs canonical id) that produces duplicate `/forums` rows.
  Outcome: H55.1–H55.6 complete — Scopus-forum canonicalization, publication `forumId` re-pointing, canonical-only `forum_view`, canonical-forum dedup (safe rule: shared primary ISSN OR abbreviation name match), primary-ISSN disambiguation. Source-ISSN cleanup done (ISO-3297 check-digit validation rejects typo ISSNs; SIAM eISSN misassignment corrected). Architectural fix: WoS canonical forum layer was immortal across the source-scoped admin resets — fixed by (1) WoS `resetCanonicalState` wiping `source=WOS` forums + FORUM/WOS links, (2) `runWosOnboarding` reading stage-3 `wos.journal_identity` instead of the stage-4 `wos_ranking_view` projection (removing a backwards dependency + ordering bug). Full rebuild verified: forum_view 0 dup-ISSN/0 dangling, 92,558 pubs on canonical ids, parity preserved.

## H56 buildFacts Pipeline Performance

Archived from `TASKS.md` on 2026-06-14. Closed task doc: `docs/tasks/closed/h56-buildfacts-performance.md`.

- [x] `H56` buildFacts pipeline performance. *(completed 2026-06-12)*
  Goal: cut the ~25–28 min Scopus `buildFacts` rebuild (publication + author canonicalization, ~87% edge writes).
  Outcome: 10 levers; the 5 root-caused defects shared one pattern — preload/no-op checks whose two sides were built by different code, silently degrading into per-record DB work (fixed via consumer-owned key-authority methods + permanent fallback telemetry). Plus bulk writes, content gates, and an opt-in stage-skip gate. End state: full rebuild ~28 min → ~5.5 min (−80%); no-change replay ~1s. Determinism + parity green.

## H57 Forum Canonicalization Merge Safety

Archived from `TASKS.md` on 2026-06-14 (documented inline; no separate task doc).

- [x] `H57` Forum canonicalization merge safety (no cross-journal eISSN bridges). *(completed 2026-06-14)*
  Goal: stop forum canonicalization from merging two distinct journals that share only an eISSN/alias (misassigned-eISSN source error), which caused wrong merges + order-sensitive ids.
  Outcome: extracted shared `ForumMergeSafetyRule` (dedup delegates to it); Layer 1 fold-time guard (don't merge across different primary print ISSNs unless names match → mint separate forum + `FORUM_CROSS_JOURNAL_ISSN` flag); Layer 2 token hygiene (drop a secondary token that is a different journal's primary print ISSN). Full rebuild verified: cross-journal bridges 8→2 (2 remaining are legit same-journal continuations), forum_view 0 dup-ISSN/0 dangling. Determinism later confirmed byte-identical across two clean rebuilds (2026-06-14). Note: SIAM-style eISSN==another-journal's-eISSN is handled by the curated correction (Layer 2 only catches eISSN==primary); FORUM_CROSS_JOURNAL_ISSN flags surface any future cases for operator review / config externalization.

## H58 Eliminate Redundant Edge Source Links

Archived from `TASKS.md` on 2026-06-14. Closed task doc: `docs/tasks/closed/h58-eliminate-redundant-edge-source-links.md`.

- [x] `H58` Eliminate redundant edge source links. *(completed 2026-06-14)*
  Goal: stop writing/storing the ~1.66M edge-type rows in `scholardex.source_links` (76% of source_links; ~22% of all derived docs) — they duplicate lineage + `linkState` already on the edge facts (`HasEdgeLineageFields`).
  Outcome: edge writer (6 methods) + pub/author canon no longer write or preload edge source links; `EDGE_RELINK_REJECTED` path removed (deterministic ids make relink impossible); reconciliation untouched (already read edge facts). Result: `source_links` 2,196,429 → 531,734 (zero edge-type rows), edge facts intact, conflicts + read-model parity unchanged, `scopus-buildFacts` ~28 → ~17 min, full suite green.
  Post-H58 caveat follow-ups (2026-06-14): determinism re-confirmed byte-identical (two clean rebuilds); author-canon edge-fact fallback made unconditional `false` (preload authoritative); true full-wipe rebuild entry point added (`rebuildAllDerivedFromSource` + `/rebuildAllDerived` endpoint, verified byte-identical to the manual chain); the H57 clean-build flag simplified away (edge-fact preload is authoritative, so fallback is unconditionally skipped). SIAM curated correction left as-is (documented one-off; externalize to config when a second case appears).

## H59 Delegated Researcher-Report Viewing (Admin + Supervisor)

Archived from `TASKS.md` on 2026-06-14. Closed task doc: `docs/tasks/closed/h59-delegated-researcher-report-viewing.md`.

- [x] `H59` Delegated researcher-report viewing for admins and supervisors. *(completed 2026-06-14)*
  Goal: let a `PLATFORM_ADMIN` or `SUPERVISOR` open the exact individual evaluation report a specific researcher sees (read-only by default), trigger an attributed refresh, and export it — reusing the already user-parameterized report engine unchanged.
  Outcome: shipped as four slices plus nav + picker + drilldown. `ResearcherAccessService` gate (admin = all; supervisor = researchers reachable from their supervised subtree via current department affiliation or group membership, group-only supervisors included); shared `/reports/researcher/**` controller (picker → read-only view → attributed refresh → export → indicator/citation drilldown); `findLatestRun` read-only resolver (view never mutates); `triggeredByEmail` provenance on `UserIndividualReportRun`. Fidelity/no-drift via three shared units used by both the researcher's own page and the delegated view: `IndividualReportViewModelAssembler`, `IndicatorDetailResponseAssembler`, and one `data-eval-api-base`-parameterized dashboard JS; export status via shared `ReportExportHttpStatus`. Nav entry (admin + user sidebars, role-gated); picker rebuilt to the `ux-design-guide.md` list/table pattern. Private named snapshots, run comparison, generic view-as, and group reports intentionally out of scope. Full suite green (2151 tests).
  Handover / deferred:
  - 0-score drilldown display fix (categorized publications the formula scored 0 are now shown, de-emphasised) is **publications-only**; citations/activities still filter `authorScore > 0`, and the H50 export (`UserReportFacade:581`) was intentionally left filtering by author score — revisit if parity is wanted.
  - Supervisor scope is unit-tested but never exercised in a live supervisor session (`agent-dev` always injects admin).
  - No single-indicator export link in the delegated drilldown (it targeted the principal's own data, so it is gated off).

## H66 / H66B Canonical Forum Registry + Entity-Oriented Multi-Source Builders

Archived from `TASKS.md` on 2026-06-19 after the multi-source ingest, prod cutover, and closeout completed.
Closed task docs: `docs/tasks/closed/h66-curated-allowlists.md`, `docs/tasks/closed/h66b-entity-oriented-builders.md`,
`docs/tasks/closed/h66b-incremental-two-tier-design.md`. From-scratch build procedure: `docs/rebuild-runbook.md`.

- [x] `H66` Canonical forum registry (multi-source identity + rankings + indexing). *(completed via H66B, 2026-06-19)*
  Goal: make the canonical forum a first-class, multi-source registry; retire the fuzzy ISSN/name resolver that
  silently scored ~39 forums to 0 by joining rankings/indexing on the stored `wosForumIds`/`scopusForumIds` FK.
  Outcome: Moves A (CiteScore/MJL/DOAJ/ERIH loaders), B (forum-keyed FK projection + scoring, the bug-killer),
  C (resolve-or-enrich + dedup) shipped; Move D (forums-first from the Scopus Source List) + Move E (book
  registry) + the by-source→by-entity re-architecture pivoted into H66B and completed there.

- [x] `H66B` Entity-oriented canonical builders + multi-source ingest (OpenAlex + DBLP). *(completed 2026-06-19)*
  Goal: reorganize the canonicalization layer from by-source to by-entity (ForumBuilder → RankingBuilder →
  PublicationBuilder → CitationBuilder, + BookBuilder), then add OpenAlex (Phase 4a) and DBLP (Phase 4b) as
  canonical sources, and cut over to a real prod database.
  Outcome:
  - **Phase 4a OpenAlex** — DOI-primary identity, OpenAlex publication source, corresponding authors, positional
    ORCID bridge, ORCID + fuzzy author reconcile (co-author-overlap tiering), full citation graph
    (incoming + outgoing, cited-by surfaced in projection/workspace), Stage-3 ISSN venue resolve.
  - **Phase 4b DBLP** — CS conference identity: per-paper keyless API + a corpus-matched dump sweep (stream the
    995MB dump once, match against our corpus by DOI + gated title; store nothing extra). Authoritative `conf/X`
    acronym wired into CORE scoring. Hardened the StAX reader (fixed-chunk, not line-based — the dump has
    multi-hundred-MB lines).
  - **Prod cutover** — fixed the Spring Boot 4 Mongo key (`spring.mongodb.uri`, not the inert legacy
    `spring.data.mongodb.uri`; default db `scholardex`), wiped the old throwaway `test` "prod", and rebuilt from
    scratch with the **self-contained** `POST /rebuildAllDerived?confirmation=RESET` (imports Source List +
    CiteScore + Books + MJL + DOAJ + ERIH from config, ingests Scopus + WoS, builds facts → forums-first
    canonical → projections). Result: **92,526 pubs / 69,933 forums** (2× the old 32,714, via the full Source
    List backbone) / projections populated / admin bootstrapped / health UP. DBLP sweep then added **853 conf/X
    forums + 2,256 conference papers**.
  - **Author dedup** — flipped `core.author-reconcile.fuzzy-apply=true` after a 30/30 STRONG spot-check and wired
    `forumReconcileService.reconcile()` into the tail of `rebuildAllDerivedFromSource()`; merged 951 duplicate
    authors live (216,258 → 215,307). OpenAlex polite-pool `mailto` set to `${admin.email}`.
  Key commits: `c30f8f5` (runbook), `1f8a77c` (author dedup + reconcile-in-rebuild), `e7c44ee` (mailto),
  `42474be`/`08ecb47` (closeout status). Full suite green (~2,280 tests).
  Handover / deferred:
  - **Scoring rework for the multi-source layer → `H69`** (conf/X acronym + multi-source forums need the scorer
    revisited, incl. the deferred miscoded-subtype dispatch); **researcher h-index / citation-network view → `H67`**.
  - **DBLP/Stage-3 refinements (deferred, none blocking):** DBLP↔Scopus conference-forum dedup (Tier-1 reconcile);
    fold the DBLP dump sweep into the full-rebuild path (still an admin trigger); DBLP rate-limit tuning; Stage-3
    warm-load if Tier-2 latency matters; optional lean dump-derived fast index. Pick these up under a new task if
    they become relevant.
  - **Known residue (low priority):** `user_defined.*` data-loss on full rebuild (chipped `task_cccc209c`, latent —
    no user data in the fresh db yet); corresponding-flag / reconcile-conflict read surface; ~6 seed-data orphan
    authorships. Decision-0 authorship-decision remap is **moot** (the wipe removed the old user-state).
  - **Memory gotchas captured:** Boot-4 Mongo key (`spring-boot-4-mongo-config-key`), DBLP long-line dump reader
    (`dblp-dump-long-lines`), `@WebMvcTest` mock completeness, verify-code-path-before-rebuild.

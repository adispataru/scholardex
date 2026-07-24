# H85 — OM 2026 conference-list amendments: ACM/EPTCS → C floor; UCC Companion mislabeling

**Status:** Scoped 2026-07-24. Consumer: Florin's UCC observation — IEEE-only until ~2012, IEEE/ACM after;
CORE lists UCC as **Unranked** (2021/2023/2026), so the OM amendment decides its class.

## The standards delta (verified in both texts)

- **2026 OM:** "categoria C va include și lucrările publicate în **ACM, EPTCS și LNCS** care nu sunt în
  categoriile A*, A și B".
- **2016:** the same amendment lists **LNCS only**; ACM/IEEE-indexed conferences fall under the D
  amendment ("indexate SCOPUS, IEEE, ACM, …").
- Today the scorer implements only the **LNCS → C** special case
  (`ComputerScienceConferenceScoringService` ~line 126: bestPoints==0 && LNCS forum → 2.0/C/Quarter.LNCS,
  before the D-tier default) — correct for 2016, incomplete for 2026.

## Slice A — ACM/EPTCS → C floor (2026-scoped) — DONE locally (2026-07-24, `7ab551c2`)

Implemented as scoped, with two findings worth recording: (1) the 2026 report's Total also consumed the
SHARED `Info_B`, so a FOURTH clone (`Info_B_2026`) was needed beyond the three activity indicators;
(2) giving the clones `workshopCategory2026` surfaced and fixed a latent 2026 mis-scoring — CORE-National
conferences are "categoria C" per the 2026 OM ("Workshop-urile/conferințele clasificate de CORE ca
naționale sau regionale sunt considerate de categorie C"), so SYNASC-style committee entries correctly
double (10 → 20) in the 2026 fișă while 2016 stays frozen. Prod: deploy first, then
`apply_acm_floor_prod.sh`. Original scope:

**Detection** (`isAcmForum` / `isEptcsForum`):
- ACM: whole-word "ACM" token in the forum/proceedings name (covers "IEEE/ACM …") OR publisher containing
  "Association for Computing Machinery"/"ACM". **NOT the DOI prefix** — Florin confirmed UCC's DOIs stay
  IEEE-branded (10.1109) even for ACM-format proceedings, and 10.1145 would miss exactly these venues.
- EPTCS: name containing "EPTCS" or "Electronic Proceedings in Theoretical Computer Science".
- Whole-word matching to avoid substring false positives; predatory/standard-excluded venues stay zeroed
  (gate runs first, as today).

**Placement:** a second floor block next to the LNCS special case, in BOTH the publication and activity
paths (an Editor Proceedings / PC-member entry at an ACM venue gets the same C): bestPoints==0 &&
2026-flagged && isAcm/Eptcs → 2.0 / Rank.C / a distinct quartile marker (new `WoSRanking.Quarter.ACM`
value — enum ADDITION is deserialization-safe for old run docs; deploy-first as usual) so the drilldown
shows the amendment as provenance, like "LNCS" does today.

**Gating — per-indicator flag, and the shared-indicator split it forces:**
- New `Indicator.acmEptcsCFloor2026` (Boolean), threaded like `workshopCategory2026`.
  **Form round-trip:** add to `IndicatorForm` + the edit template or every admin save wipes it (the
  maxPoints lesson).
- Set on the already-2026-only indicators: `Info_B_Conferințe 2026`, `Info_C_2026` (+ the 2026
  top-publications/top-citations variants if separate) — the amendment applies to the whole "lista CORE",
  so CITING-forum pricing (C perspective) inherits it too (ACM citing forum: 2 vs 1).
- **Split required** for the CS_CONFERENCE activity indicators shared by both reports: `Info_D_ii`,
  `Info_D_vi`, `Info_D_viii-b` → 2026 clones (precedent: D_xii_2026, D_v_2026). Swap IN PLACE in the 2026
  report's indicators array AND re-key `indicatorRolesByIndicatorId` + `blockByIndicatorId` (the D_v
  lesson). 2016 report keeps the originals untouched.

**Workshop interplay:** the floor sets the VENUE class; the 2026 workshop machinery then applies its own
reduction on top, mirroring how LNCS interacts today. Pin with a test (workshop@ACM-conference).

**Tests:** UCC-2013+ shape (CORE Unranked + "IEEE/ACM …" name → C under 2026 flag, D without);
UCC-2011 (IEEE-only name → D in both); EPTCS; predatory exclusion unaffected; whole-word negative case;
activity path (Editor Proceedings at ACM venue → C); workshop reduction on the floored class.

**Data/rollout:** flag + clones via the admin round-trip on local dev (hashes app-computed), seeds, prod
script AFTER deploy (new field is read only by new code). Optional dry-run: count CORE-unranked pubs on
ACM-named forums before/after for the changelog.

## Slice B — UCC Companion investigation — DONE (2026-07-24, prod read-only)

**Verdict: the mislabel does NOT exist in current prod data for Florin's publications.** Findings:
1. His three UCC papers (2012, 2014×2 — Event-Driven Multi-agent, Prometheus/IoT, Cloud Incident
   Management) all sit on the DBLP `conf/ucc` stream forum, displayed as "UCC", with per-paper DBLP
   evidence `conferenceName: "UCC"` (main volumes). No Companion anywhere on his records.
2. The only "UCC Companion" artifacts in prod: two Scopus VENUE records for the 2019 Companion volume
   (both genuinely named that at the source; correctly merged into one canonical forum) carrying ZERO
   papers, and three DBLP booktitles ("UCC Companion") on OTHER researchers' 2019/2021 papers that ARE
   genuinely Companion-volume papers per DBLP. No UCC 2016–2018 venues exist in our corpus at all.
3. Most likely explanations for what Florin saw: (a) dblp.org's own TOC (DBLP files some UCC main-track
   papers under Companion volumes — upstream quirk, outside our data), or (b) a PRE-SWEEP state of our
   UI — the June DBLP dump sweep re-stamped conference papers from raw proceedings names onto conf/X
   streams, which would have replaced any Companion-ish label he saw earlier.
4. Scoring nuance verified benign: "UCC Companion" booktitles carry no "@" workshop marker, so per the
   DBLP-first policy those (other researchers') papers score as UCC-main — candidate-favorable, no
   workshop reduction misfire.
**Action:** none in data. Reply to Florin asking WHERE he currently sees "UCC Companion" (screenshot/
link) — if it reproduces post-sweep we reopen; the admin bulk reassign-forum remains the remedy of
record if a concrete mislabeled paper surfaces.

## Slice C — durable detection via preserved originalForumId — DONE locally (2026-07-24)

**The false negative Slice A left open:** the June DBLP dump sweep re-stamps conference papers onto
`conf/X` stream forums (name = bare acronym "UCC", publisher EMPTY) — so `isAcmOrEptcsVenue` had nothing
to match for exactly the papers Florin flagged (his 2012/2014 UCC papers scored D with
`fallbackReason: NO_CLOSEST_YEAR` even after the Slice A deploy). A curated {stream → ACM-era start year}
registry was considered and rejected: the raw Scopus proceedings name ("Proceedings - 2013 **IEEE/ACM**
6th … UCC 2013") already carries the signal per-year — the re-stamp was simply destroying the link to it.

**Implementation (store both venues, consult either):**
- `ScholardexPublicationFact.originalForumId` — the forum id displaced by a DBLP-evidence re-stamp; set in
  `DblpConferenceResolveService.stampConferenceForum` (shared by `applyMatch` = API/dump path and
  `rebuildFromEvidence` = full-rebuild path — the only two `setForumId` re-stamp sites). Repeat stamp with
  the same stream forum is a no-op, so the capture survives idempotent re-runs; incremental refreshes never
  touch it (`isDblpStampedForum` guard keeps forumId on the stream, field not in the copy list).
- Projected: `ScholardexPublicationView.originalForumId` → Postgres `original_forum_id`
  (V25 migration, additive) → both mappers in `PostgresScholardexProjectionReadPort`
  (`mapPublicationView` + `mapScoringPublication`) → `ScoringPublication` record (new component; the old
  19-arg signature kept as a delegating constructor so existing call sites compile) →
  `ScoringPublicationReadModel.getOriginalForumId()` (defaulted null).
- Scorer: publication-path floor consults `resolveAcmEptcsSignalForum` — assigned forum first, then
  `lookupPort.getForum(originalForumId)`. Per-year originals make the era split exact (UCC 2011 IEEE-only
  stays D; 2012+ IEEE/ACM floors to C). When the signal came from the original venue, the drilldown gets
  `scoringInfo.acmEvidenceVenue` = the raw proceedings name. Activity path unchanged (no publication).
- No new `ReportingLookupPort` method (getForum already exists) — the dual-impl trap does not apply.

**Rollout:** deploy (V25 runs at boot) → admin "Full derived-data rebuild" (derive-only) — the canonical
replay resets forumId to the raw source venue, `rebuildFromEvidence` re-stamps and now captures
`originalForumId` corpus-wide, projections rebuild. No mongosh script. Verify: Florin's 2026 refresh shows
the 2012/2014 UCC papers as C / quarter ACM / acmEvidenceVenue "Proceedings - … IEEE/ACM …"; the 2011
Frîncu UCC paper stays D; the 2016 fișă unchanged.

**Caveat:** pubs whose only venue ever was the stream forum (no source-derived forum at re-stamp time)
keep `originalForumId = null` and today's behavior. The preserved link also serves future needs:
Companion-volume detection, SENSE publisher checks on re-stamped items, honest drilldown provenance.

## Out of scope

- A general IEEE/ACM dual-sponsorship registry — superseded by Slice C's preserved original venue (the
  data answers the era question per-year without curation).
- Retroactive re-ranking of the 2016 fișă (standard says D there; nothing to change).

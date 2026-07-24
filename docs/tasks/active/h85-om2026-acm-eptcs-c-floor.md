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

## Slice A — ACM/EPTCS → C floor (2026-scoped)

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

## Slice B — UCC Companion investigation (prod, read-only first)

Florin's UCC entries display as "UCC Companion" though published in the MAIN volume. Local canonical data
lacks his UCC records; on prod:
1. Find his UCC pubs → their canonical forum + the scopus/openalex source facts' venue strings.
2. Determine cause: Scopus's own venue assignment (Companion ISBN swallowing main-track papers — known
   upstream pattern) vs our forum merge collapsing main+Companion under one name.
3. Scoring stakes: "Companion" is workshop-shaped — check whether the workshop reduction fires on these.
4. Remedy: near-term via the existing admin **bulk reassign-forum** action (move the papers to the main
   proceedings forum); systemic only if OUR merge is at fault (then fix the merge naming, not the data).

## Out of scope

- A general IEEE/ACM dual-sponsorship registry — the name/publisher detection covers the family without
  curation; revisit only if false negatives surface.
- Retroactive re-ranking of the 2016 fișă (standard says D there; nothing to change).

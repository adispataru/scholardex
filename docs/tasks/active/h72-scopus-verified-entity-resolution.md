# H72 — Scopus verified-tier entity resolution (drop ad-hoc affiliations, merge over-split authors)

## Goal

Make the canonical author + affiliation layer actually *resolve* entities instead of relabeling Scopus 1:1, using
Scopus's **own** disambiguation tier — without waiting on OpenAlex, ORCID, or anyone filling in their profile. Two
opposite surgical operations:

- **Affiliations** — keep Scopus's *verified* institution profiles (`afid` starting `60…`), **drop the ad-hoc tier**
  (`1xxxxxxxx`, the raw-string ids Scopus minted when it couldn't match a profile).
- **Authors** — keep all AU-IDs (there is no noise tier to drop), but **merge the same person's over-split AU-IDs**,
  now made safe by the cleaned verified-affiliation signal.

This unblocks the original pain (OpenAlex co-author duplicates, the researcher scopus-id band-aid) and gives OpenAlex
ROR/ORCID a *clean* base to enrich later, instead of a fragmented one.

## Why now / problem

The canonical layer was designed as an entity-resolution layer — plural id-lists (`scopusAffiliationIds[]`,
`scopusAuthorIds[]`, `orcidIds[]`) prove the intent — but it regressed into a 1:1 relabel:

- `ScholardexAffiliationCanonicalizationService.buildCanonicalAffiliationId` derives the canonical id from the single
  scopus afid (`"scopus|" + scopusAffiliationId`), so two afids for one institution can *never* collapse.
- There is **no affiliation dedup pass at all** (authors, forums, wos-journals each have one; affiliations don't).
- The author reconcile pass exists but under-fires, so researchers end up with multiple AU-IDs — patched by storing
  scopus ids directly on the researcher profile (the band-aid to retire).

We exhaustively tested whether the fragmentation could be merged by name or co-occurrence and proved it **cannot be
done safely** (see Evidence). Scopus's verified tier is the one reliable signal we already have.

## Evidence (measured on the live `scholardex` db, 2026-06-20)

**Affiliations are 1:1, never merged.** 29,106 canonical = 29,106 scopus; **0** have >1 scopusId. One real
institution fragments badly — "West University of Timisoara" = **7 records** across language ("Universitatea de
Vest" / "West University of Timisoara"), word-order, city-suffix, truncation; e-Austria = 8.

**Name/co-occurrence merging is unsafe — both directions leak:**
- Exact normalized-name collapse = **7%**, mostly junk ("ltd" ×261, "independent researcher" ×52).
- Author co-occurrence (ad-hoc→dominant verified) maps **41%** of ad-hoc afids, but spot-checking the highest-impact
  maps showed **~70% false** — big multi-affiliation collaborations (FRIPON, iThemba→UNISA at 100%), hospital↔medical
  university, institute↔partner university (e-Austria→UVT). Dominance threshold does **not** filter these.
- Co-occurrence **+ name-token agreement** still leaks via shared *geographic*/*topic* tokens (Beijing-lab→Beijing
  University, Constanta-hospital→Constanta-university, e-Austria→UVT via "Timișoara"), and buries true cross-language
  cases. No internal combination reaches safe auto-merge — the root reason is that distinguishing "same institution,
  different label" from "different institutions, shared people" requires an *identity authority*, which Scopus's
  verified profile already is.

**Scopus's verified tier is clean and droppable:**
- 57% of affiliations are verified (`^60`, 16,616 distinct profiles); 43% are ad-hoc noise.
- Dropping ad-hoc: **29,106 → 16,616** institutions; the 98 "Timisoara" records collapse to ~7 *correct, distinct*
  institutions (UVT `60000434`, County Hospital `60091113`, e-Austria `60091123`, USAMV, ISIM, …) — fragmentation was
  **entirely** in the ad-hoc tier; the verified tier is one record per real institution.
- **Cost: ~0 for the people we evaluate.** 94% of authors keep ≥1 verified affiliation; only 5% lose all affiliation
  (external one-paper John-Does). Of 2,559 authors at verified UVT, exactly **1** would lose the UVT link.

**Authors have no droppable tier — they're over-split:** every author is a disambiguated AU-ID (no `1xxxxxxxx`
analogue). The failure is the inverse — one person split into an *old* + *new* AU-ID. At verified UVT alone, **52
same-name groups span 106 AU-IDs**, all real faculty: "Megan, Mihail" = `6701429404`+`55905877500`; "Cotăescu, Ion
I." = `7003320412`+`57201480665`; "Puta, Mircea" = `6603370343`+`57204409679`.

**The dependency:** the affiliation cut *enables* the author merge — "same name + same **verified** affiliation" is the
safe corroborator for the over-split case, and it was unusable while affiliations were fragmented. It matters
specifically because co-author-overlap alone is weak for old/new splits (the co-authors are themselves split and
change across a career).

## Non-goals

- **No corpus-wide author/affiliation dedup.** We resolve what the verified tier + subjects need; the long tail
  (5,420 single-author ad-hoc singletons, external co-authors) stays as-is — it doesn't affect any evaluation.
- **No OpenAlex/ROR in this task.** That's slice 3 / a follow-up; it enriches the clean base, it is not a prerequisite.
- **No name- or co-occurrence-based affiliation auto-merge.** Proven unsafe; explicitly excluded.

## Design

### Slice 1 — Verified-only affiliations — DONE (`4b7b76c`, full-rebuild validated 2026-06-20)
Live result after `POST /rebuildAllDerived?confirmation=RESET` (~36 min, from the 461M Scopus JSON):
affiliations **29,106 → 16,427**, all carrying a verified `60…` id (0 non-`60`); author→affiliation edges
279,639 → 247,572; pub→author→affiliation 756,002 → 710,106; verified UVT retains 2,327 authors. Slice-2
enabler confirmed: the over-split UVT authors (Megan/Cotăescu/Puta old+new AU-IDs) now share the single verified
UVT affiliation, and 20 same-name UVT groups / 41 authors are clean merge candidates. (16,427 < the ~16,616
estimate because a from-scratch rebuild reconstructs only from the JSON, dropping incrementally-added affiliations
e.g. staff-CSV.) **Caveat:** the from-scratch rebuild re-derives Scopus+WoS only — it does NOT re-run the OpenAlex
sync, so OpenAlex-owned pubs/authors (the source facts survive the wipe) must be re-synced to reappear in canonical.

Implementation detail (as shipped):
Drop the ad-hoc tier at canonicalization so it never becomes a canonical entity or an edge.
- `ScholardexAffiliationCanonicalizationService`: only mint a canonical affiliation for afids matching `^60`. Ad-hoc
  afids resolve to **no** canonical affiliation.
- Author + publication canonicalization (`ScholardexAuthorCanonicalizationService.upsertAuthorAffiliationEdges`,
  `target.setAffiliationIds(...)`, and the pub→author→affiliation edge writer in
  `ScholardexPublicationCanonicalizationService`): filter affiliation ids to verified before writing
  `affiliationIds` / `ScholardexAuthorAffiliationFact` / `ScholardexPublicationAuthorAffiliationFact`.
- Keep the raw ad-hoc affiliation *string* available where a paper byline needs to display it (do not resurrect it as
  an entity). Decide during build whether any display path actually needs it (likely not).
- **Verify:** projection + scoring still build; the 16,616-institution graph is intact; the 52 UVT collision groups
  now share a single verified affiliation id; re-run a full rebuild and confirm the counts are durable.

### Slice 2 — Merge over-split authors (verified-affiliation corroborated) — DONE (`3e5dc11`/`4aa3404`/`40cb8f3`, applied + live-validated 2026-06-21)
`reconcileByNameAndAffiliation`: clusters by name, merges subgroups sharing a verified affiliation + ≥1 co-author,
**hard-block relaxed** (over-split ids co-appear on a paper — the same person listed twice, verified on
Megan/Cotăescu). Two safety gates, both found necessary by the dry-run:
- **>20-author co-author exclusion** — a co-author from a mega-author paper doesn't count (HEP collaborations make
  everyone trivially co-author everyone). Killed the false-positive class at the root: Wang J. ×6 shared 96 → 0,
  Huang Y. ×5 shared 119 → 0; true over-splits keep their small-team overlap. 483 → 423 candidates.
- **Name-specificity guard** — auto-merge only distinctive names (≥2 multi-letter tokens) or differing display
  strings (a real variant); identical single-surname+initial names ("Wang, J.") go to `AUTHOR_OVERSPLIT_MERGE_REVIEW`.

Enabled via `core.author-reconcile.affiliation-apply=true` and wired into the reconcile chain (durable across
rebuild). Apply run: **405 groups merged, 22 → review**, author_facts 215,345 → 214,940. UVT subjects collapsed to
one canonical author each (Megan/Cotăescu/Puta — both Scopus ids on one record), retiring the per-researcher
scopus-id band-aid's need. 11 unit tests. Projection rebuilt from the merged canonical.

Implementation detail (original plan):
A reconcile pass (extend `AuthorReconcileService`) that merges AU-IDs which are:
`same normalized name` **AND** `share ≥1 verified affiliation id`, guarded by the existing **same-publication
hard-block** (two authors on one paper ⇒ different people) and co-author overlap as a secondary signal. Merge
mechanics already exist (pick survivor, fold id-lists/names/affiliations, repoint references, delete losers); this
adds the verified-affiliation corroborator that was missing.
- Dry-run first (mirror `core.author-reconcile.fuzzy-apply`): emit candidates as conflicts, inspect the 52 UVT groups,
  then enable.
- **Durability:** the merge must survive a full rebuild (canonical ids are re-minted 1:1 from source each rebuild) —
  wire into the reconcile pass / decision log like the existing author/forum reconcile.
- **Retire the band-aid:** once researchers resolve to one canonical author carrying all their scopus ids, remove the
  scopus-ids-on-researcher-profile workaround (or keep it as a confirmation seed, decided during build).

### Slice 3 — (later, separate) OpenAlex ROR/ORCID enrichment onto the clean base
Bridge ROR onto verified affiliations and ORCID onto authors via the DOI-linked sync, recovering the few relevant
institutions Scopus didn't verify and collapsing any cross-language residue — now matching one-to-one onto a clean
verified tier, not a fragmented one. Out of scope here; documented so the seam is intentional.

## Risks / watch-list

- **Scoring depends on affiliation?** Confirm scoring only needs the *subject's* (verified) affiliation, not the full
  author set, before dropping ad-hoc. (Expected: true — evaluation is per-researcher.)
- **Verified self-fragmentation** (one institution with >1 `60…` profile, e.g. post-merger/rename) is rare; leave as
  residual, handle in slice 3 via ROR if it bites.
- **Over-merge in slice 2:** two genuinely different same-name people at the same institution. Guarded by the
  same-publication hard-block + dry-run review of the UVT set before enabling.
- **Scopus-indexed signal** is routed via the **forum** (venue in the Scopus source list), not per-pub membership —
  independent of this cut, already supported by the forum work.

## Definition of done

- Ad-hoc affiliations no longer produce canonical entities/edges; affiliation graph ≈16.6k verified institutions;
  full-rebuild durable; projection/scoring green.
- Author over-split merge live (post dry-run), the 52 UVT groups collapse correctly, band-aid retired or downgraded to
  a seed.
- Plan for slice 3 (OpenAlex ROR/ORCID onto the clean base) recorded; no name/co-occurrence affiliation auto-merge
  shipped.

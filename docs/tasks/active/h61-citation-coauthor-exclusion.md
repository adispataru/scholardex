# H61 Citation exclusion: "any co-author" mode (Scopus all-authors self-citation)

**Status:** Planning
**Created:** 2026-06-15

## Purpose

Add a third citation-exclusion mode for the upcoming **Informatică** standard: exclude a citation when
the citing work shares **any** author with the *cited* publication — not only when the candidate
themself is on the citing paper. This is the Scopus "exclude self-citations of **all authors**" flavor,
as opposed to the current candidate-only exclusion (which matches Ordin 6129/2016 Matematică — see the
[math standard analysis in the H60 doc](h60-relative-year-specs.md) and `Mate_C`).

We support **two** modes today (`CITATIONS` = count everything; `CITATIONS_EXCLUDE_SELF` = candidate-only).
This task adds a third. There is no co-author exclusion mode at present.

## How exclusion works today (verified 2026-06-15)

- Model: `IndicatorKind.Citations(boolean excludeSelf, ScoringStrategy strategy)`
  ([IndicatorKind.java:33](../../../src/main/java/ro/uvt/pokedex/core/model/reporting/scoring/IndicatorKind.java)).
  Legacy string codec: `CITATIONS`→`excludeSelf=false`, `CITATIONS_EXCLUDE_SELF`→`excludeSelf=true`
  (`of()` / `toLegacy()`, lines 82-83 / 116-118). The legacy string is the persistence + admin-form +
  cache-fingerprint surface.
- **Comparison set is the candidate's own resolved author IDs** (the user's lookup keys → canonical IDs),
  applied at two sites that must stay in sync:
  - **score path** — `ReportScopedIndicatorScoringSupport.computeReportScopedCitations` filters
    `citing` against `researcherAuthorIds` via `sharesAnyAuthor(...)`
    ([lines 102-118](../../../src/main/java/ro/uvt/pokedex/core/service/application/ReportScopedIndicatorScoringSupport.java)).
    Callers that resolve `researcherAuthorIds` and feed it in: `UserReportFacade` (several),
    `IndividualReportComputer`, `GroupReportRunner`.
  - **display path** — `CitationRowProjector` filters `citing.getAuthors()` against `selfAuthorIds`
    via `hasOverlap(...)`
    ([line 128](../../../src/main/java/ro/uvt/pokedex/core/service/reporting/transfer/projection/CitationRowProjector.java)),
    where `selfAuthorIds = resolveSelfAuthorIds(userEmail)`.
- `calculateScientificImpactScore` does **not** filter — exclusion happens upstream, before the citing
  list is passed in. Both paths already iterate **per cited publication**.

## The only conceptual change

The exclusion set switches from *the candidate's IDs* to *the cited publication's full author set*:

| policy | exclude a citation iff the citing work's authors intersect… |
|---|---|
| `NONE` (`CITATIONS`) | — (count all) |
| `CANDIDATE_ONLY` (`CITATIONS_EXCLUDE_SELF`) | the **candidate's** resolved IDs *(today)* |
| `ANY_COAUTHOR` (`CITATIONS_EXCLUDE_COAUTHORS`) | the **cited publication's** author set (`cited.getAuthors()`) |

`ANY_COAUTHOR` ⊇ `CANDIDATE_ONLY` (the candidate is always among the cited paper's authors), so it is a
strict widening of the existing filter. Both filter sites already have the cited pub in scope
(`pub`/`cited`), so the per-cited author set is available without new plumbing.

## How it folds

1. **Model** — replace the boolean with an enum `SelfCitationPolicy { NONE, CANDIDATE_ONLY, ANY_COAUTHOR }`
   in `Citations`. Add the legacy token `CITATIONS_EXCLUDE_COAUTHORS` to `IndicatorKind.of()` and
   `toLegacy()`. Keep `CITATIONS` / `CITATIONS_EXCLUDE_SELF` mapping to `NONE` / `CANDIDATE_ONLY` for
   back-compat — **no data migration** for existing indicators (their stored string is unchanged).
2. **Indicator getters** — add `Indicator.getCitationExclusionPolicy()`; keep `isCitationsExcludeSelf()`
   returning `policy != NONE` so any back-compat reader still works.
3. **Filter sites** — in both the score path and the display path, choose the comparison set by policy:
   `ANY_COAUTHOR` → the cited pub's `getAuthors()`; `CANDIDATE_ONLY` → `researcherAuthorIds` (today's
   behavior). Keep the two sites byte-for-byte consistent (the recurring drift risk in this area).
4. **Admin UI** — add the option to the kind dropdown in `AdminViewController` (~line 255 list).
5. **Cache fingerprint** — changes automatically: the `toLegacy()` string is part of the
   `userIndicatorResults` fingerprint, so switching policy invalidates cached results. No extra work.
6. **Tests** — a citing paper that shares a *non-candidate* co-author of the cited paper is **excluded**
   under `ANY_COAUTHOR` but **kept** under `CANDIDATE_ONLY`; candidate-only and count-all behavior
   unchanged; score path and display path produce the same exclusion set.
7. **Apply to Informatică** — point the Informatică citation indicator(s) at `CITATIONS_EXCLUDE_COAUTHORS`
   once the mechanism lands and the standard text is confirmed.

## Open questions (confirm against the published Informatică standard)

- **Per-cited-paper vs. global co-author network.** Default assumption: Scopus "all authors" =
  per-cited-paper author overlap (exclude iff citing shares an author with *that* cited paper). The
  broader "any of the candidate's co-authors across all their papers" reading is unusual and almost
  certainly not intended — but verify against the actual standard before pointing indicators at it.
- **Identity-matching accuracy (real risk).** Co-author overlap is matched by canonical author ID.
  The candidate benefits from resolved lookup keys (Scopus/WoS/Scholardex); **co-authors and citing-side
  authors are far less likely to be canonicalized to the same IDs**, so ID-only overlap will *under-exclude*
  (miss co-author self-citations whose authors aren't linked). May need a name-normalized fallback match —
  net-new, and the main accuracy caveat for this mode. Flag in tests + docs.

## Exit criteria

- `SelfCitationPolicy` with three values; `CITATIONS_EXCLUDE_COAUTHORS` round-trips through
  `of()`/`toLegacy()`; existing `CITATIONS` / `CITATIONS_EXCLUDE_SELF` indicators unaffected (no migration).
- Both score and display paths apply the per-cited-paper author overlap under `ANY_COAUTHOR` and agree;
  unit-tested incl. the non-candidate-co-author case and the data-quality (unlinked author) caveat.
- Admin editor can select the new mode; cache invalidates on policy change.
- Informatică citation indicator(s) re-pointed once the standard semantics are confirmed.

## Dependencies

Builds on the H52 typed indicator infrastructure (`IndicatorKind`, legacy string codec, fingerprint).
Independent of [H60](h60-relative-year-specs.md) (year specs) — the two can land in either order; an
Informatică citation indicator would likely want both (this mode + `LatestNRankings`).

# Data-flow seam audit — fields available at one layer, dropped before they're used

Four parallel audits of the pipeline seams (ingest → source fact → canonical fact → PG
projection → read model → scorer/report/UI), prompted by the `forum.publisher` bug (captured
at import, never threaded through the merge engine → 0% populated → book SENSE scoring dead).
Status: ✅ done · ⏳ deferred · ❓ needs policy decision.

## Done this session
- ✅ `forum.publisher` threaded (Scopus+OpenAlex) → revived book SENSE. (bc799f0)
- ✅ `forum.isbn` threaded — exact twin of publisher, was 0% populated. (ba56697)
- ✅ OpenAlex enrichment captured on source fact + copied to canonical pub: `retracted`,
  `fwci`, `citationNormalizedPercentile`, `primaryTopic`, `biblio`. (bc799f0, ba56697)
  Wired in BOTH canon paths — `CanonicalGraphBuilder` (the V2/runFull path) and
  `applyOpenAlexFields` (on-demand).

## Deferred — read-side / cheap (no full rebuild; derive-only projection refresh)

1. ✅ **Widen `ScoringPublicationReadModel`** (chokepoint) — added scopus/wos/graph citation
   splits + affiliation_ids + open_access getters (defaulted on the interface); read port +
   `toScoringPublication` fill them. (844255a) Consumers (h-index, university-rank) can now use
   them — wiring those consumers is a further follow-up.
2. ✅ **Read port forum SELECT** now includes `forum_type`/`asjc`. (844255a)
3. **Project the new OpenAlex pub fields** to `scholardex_publication_view` + read model
   (`retracted`, `fwci`, `citationNormalizedPercentile`, `primaryTopic`, biblio) and wire the
   **retracted scoring gate** (currently only the Scopus `tb` subtype is gated; OpenAlex-only
   retracted papers score). Effort: M.
4. **Project forum identity arrays** — `dblpIds` (CS-conference identity), `wosForumIds`,
   `scopusForumIds` not in `forum_view`. The read port even fakes `scopusId = forum_id` (wrong
   for non-Scopus forums). Effort: M.
5. **Project author/affiliation identity arrays** — `orcidIds`/`openAlexAuthorIds`/`rorIds` never
   reach PG → cross-source provenance/dedup audit dies at projection. Effort: M.

## Deferred — ingest/canon (need a full re-ingest, so batch with a future rebuild)

6. **Scopus CiteScore quartile/percentile** — explicitly discarded at ingest
   (`ScopusDataService` ~line 252, "no domain uses them"). Biggest *journal-scoring* gap:
   Scopus-only venues have no quartile and fall to the bottom tier. The standard uses CiteScore
   percentile as the Scopus analog when JCR is absent. Effort: M (ingest + model + scoring).
7. Smaller ingest drops: **ERIH PLUS level (INT1/INT2/NAT)**, **DOAJ Seal/APC/subjects**,
   **DBLP record `type`** (authoritative conf-vs-journal), **WoS MJL publisher**, **DBLP `url`**
   (and the dead `ee` column on `ScholardexPublicationDblpEvidence`). Effort: S–M each.

## Needs a policy decision (scoring logic vs the Informatica-2016 standard)

These are correctness gaps where the data exists but the scorer ignores the standard's rule:
- ❓ **Poster/demo + blank-subtype are scored** — standard excludes posters/demos; `isResearchContribution`
  gives blank subtypes "benefit of the doubt".
- ❓ **Workshop adjustment halves points** instead of the standard's one-category downgrade; the
  reported `resolvedRank` still shows the pre-downgrade rank.
- ❓ **Perspective-c citations have no A\*/A/B forum-category gate** on the citing publication
  (footnote 2 requires it for senior grades).
- ❓ **Conference D-tier isn't a real indexing test** (any proceedings → D); no Scopus/IEEE/ACM/DBLP
  union check, and **no WSEAS/IAENG/DAAAM / arXiv-CoRR-only / Beall's exclusion** (standard mandates these).

## UI / export (data computed but shown to no one)
- The full `Score.scoringInfo` provenance map (matched acronym, resolved CORE year, source,
  fallback reason) is built on every score and surfaced nowhere — exactly the audit trail a
  verification committee needs. Only `workshopAdjusted` is read (one export projector).
- vol/issue/pages not in report lists/exports (only the pub detail page).

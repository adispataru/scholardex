# H69 thread (2) — citation-driven criteria / per-source citation selector

Scoping doc (2026-06-25). Parent: `H69` thread (2) in `TASKS.md`.

## What the thread asked for

> feed OpenAlex `cited_by` / the in-corpus citation graph into citation-count indicators and into **H67**
> (h-index); pick the citation source per domain.

## What is already true (correcting the backlog note)

The "feed the in-corpus citation graph into citation indicators" half is **already done** — and was already done
before this thread was written. The backlog's "still score off the legacy count" note is inaccurate:

- **Score path** — `ReportScopedIndicatorScoringSupport.computeCitationView` walks the in-corpus citation graph
  (`findAllCitationsByCitedIdIn` over `citation_facts`, which already unions OpenAlex `cited_by` + Scopus edges),
  resolves each citing publication, and scores it via `ScientificProductionService.calculateScientificImpactScore`.
  A `GENERIC_COUNT` citation indicator therefore counts graph edges; an AIS/quartile citation indicator weights each
  citing venue. `citedByCount` is **not** consulted here.
- **Display path** — `CitationRowProjector.project` does the same walk + per-citing scoring for the
  `citations-per-publication` tiles.
- **H67** — the h-index already consumes the per-source counts (`graph/scopus/wos_citation_count`) projected by
  `applyCitationSourceSplit`; the self-cit-excluded WoS-Core h walks the graph via `findForumCoreCollectionYears`.

So the only genuinely-unbuilt clause is **"pick the citation source per domain."**

## The actual gap

The `Citations` indicator kind (`IndicatorKind.Citations(boolean excludeSelf, ScoringStrategy)`) has **no source
dimension**. There is no way to express "count/score only citations whose **citing** venue is WoS-indexed" (or
Scopus-indexed) — every in-corpus edge is counted regardless of citing-venue indexing. By contrast `HIndex` already
carries `HIndexSource` (GRAPH / SCOPUS_VENUE / WOS_VENUE / SCHOLARDEX).

The classification infra needed already exists and is proven: a citing pub's source = its forum's
`scopusForumIds` / `wosForumIds` membership (`ScholardexForumFact`), the exact rule `applyCitationSourceSplit` +
`HIndexCalculator.extractorFor` use for the h-index.

## Design (when built)

Mirror `HIndexSource` onto the citation family:

1. **Model** — add a source to `Citations`, e.g. `Citations(CitationSource source, boolean excludeSelf, ScoringStrategy)`
   where `CitationSource ∈ {ALL (graph), SCOPUS_VENUE, WOS_VENUE}`. (`SCHOLARDEX` = source-reported totals does not
   apply to a per-edge graph walk; drop it for citations.) Default `ALL` preserves today's behaviour.
   - `IndicatorKind.of` / `toLegacy`: new legacy tokens `CITATIONS_SCOPUS[_EXCLUDE_SELF]`, `CITATIONS_WOS[_EXCLUDE_SELF]`;
     existing `CITATIONS` / `CITATIONS_EXCLUDE_SELF` → `ALL` (no data migration, fingerprint invalidates via `toLegacy`).
2. **Both filter sites** — in `computeCitationView` and `CitationRowProjector.project`, before scoring a cited pub's
   citing list, drop citing pubs whose forum is not indexed in the selected source. Both already iterate per citing pub
   and already resolve the citing forum (display) / collect forumIds (score), so the change is a single predicate fed by
   a `forumId → {scopus,wos}` flag map (one batched read, same as `applyCitationSourceSplit`). `ALL` ⊇ `SCOPUS_VENUE`,
   `WOS_VENUE`; the two sites must use the identical classification (shared helper).
3. **Admin editor** — expose the source on the citation-indicator option list (parallels the `HINDEX_*` options).
4. **Per-domain activation (separate slice)** — re-point the specific domain citation indicator(s) to a source. This
   is the analogue of **H67 S4b** and should not ship without a concrete standard that requires it.

Slices: **S1** model + codec + admin option (default ALL, zero behaviour change) · **S2** the two filter sites + shared
classifier + tests (ALL == today; SCOPUS/WOS subset) · **S3** per-domain re-point (gated on a real consumer).

## Why this is NOT obviously worth building now

- **No live consumer.** The standards assessment's citation needs are h-index (done, indicative) or all-citation
  counts (done). Source-*specific citation counts* aren't required by any currently-wired report. istorie's "GS ≥70
  citations" needs Google Scholar (`H20`, not started) — a source we don't hold, out of scope here.
- **Indicative-undercount caveat (hard).** Source-attributed counts undercount (~18% + the WoS conference-index gap,
  `H76`) exactly as the source-attributed h does. A *hard threshold* like "≥13 WoS citations" on indicative counts can
  wrongly fail a candidate — riskier than an indicative h displayed for context. Source-specific citation **gates**
  should wait on `H76` + corpus completeness, same as H67 S4b.

## Recommendation

S1+S2 are a clean, low-risk, score-neutral (default `ALL`) addition that finishes the thread's "pick the citation
source" clause and gives parity with `HIndexSource`. But with no consumer and the hard-gate caveat, **building it now
is speculative infra.** Options: (a) build S1+S2 as dormant infra (default ALL, no domain uses it yet); (b) mark thread
(2) effectively satisfied (graph already consumed) and defer the source selector until a domain standard needs it
(bundle with H67 S4b / after H76). Recommendation: **(b)** unless a specific domain that needs WoS/Scopus-only
citation counts is on deck.

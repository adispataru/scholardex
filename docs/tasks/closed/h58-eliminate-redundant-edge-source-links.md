# H58 — Eliminate redundant edge source links

**Status:** IMPLEMENTED + rebuild-verified 2026-06-14. Surfaced while assessing from-scratch build cost during H57.

**Outcome (full rebuild verified):** `scholardex.source_links` **2,196,429 → 531,734** (−1,664,695; remaining types are
AUTHOR/CITATION/PUBLICATION/FORUM/AFFILIATION only — zero edge-type links). Edge facts intact (authorship 657,623,
author-affiliation 271,077, pub-author-affiliation 735,994); conflicts unchanged (no new edge conflicts); read-model
parity unchanged (forum_view 32,714, 0 dup-ISSN, 0 dangling, 92,558 pubs). `scopus-buildFacts` ~17 min (down from
~28). Reconciliation needed no change — it already read edge facts, not edge source links. Single edge-fact write
path now drives the no-op skip; deterministic edge ids make a relink impossible (EDGE_CANONICAL_ID_MISMATCH guard
retained). The H57 clean-build edge lookup-skip is now moot for edges (no edge links to look up) and could be
simplified away. Full suite 2,130 green. Discovered: reconciliation/single-edge paths flow through the edge writer
which now writes facts only.

## Problem

`scholardex.source_links` is the single largest derived collection (~2.20M docs). **~1.66M of those (76%) are
edge-type links** — `AUTHORSHIP` (657,623), `AUTHOR_AFFILIATION` (271,077),
`PUBLICATION_AUTHOR_AFFILIATION` (735,994) — and they are **redundant scaffolding** that duplicates data
already stored on the edge facts.

Evidence:
- The edge fact already carries its own lineage + state. `ScholardexAuthorshipFact` (and the other two edge
  facts) `implements HasEdgeLineageFields` with `source, sourceRecordId, sourceEventId, sourceBatchId,
  sourceCorrelationId, linkState, linkReason` — the same fields the source link holds, plus the
  `(publicationId, authorId[, affiliationId])` canonical mapping the source link encodes as
  `canonicalEntityId`.
- The **read model never reads source links.** `ScholardexProjectionBuilderService` (which builds the Postgres
  `reporting_read.*` views the app serves) references source links **0 times**. No reporting/scoring/UI path
  touches `scholardex.source_links` at all.
- The **only** consumers of edge-type source links are build/maintenance internals:
  - the edge writer's own replay no-op-skip preload —
    `ScholardexPublicationCanonicalizationService.preloadSourceLinks(AUTHORSHIP|PUBLICATION_AUTHOR_AFFILIATION, …)`
    and `ScholardexAuthorCanonicalizationService` (`AUTHOR_AFFILIATION`, ~line 276);
  - `ScholardexEdgeReconciliationService` conflict detection (reads edge source-link state, opens
    `AUTHORSHIP`/`AUTHOR_AFFILIATION` ambiguous conflicts, ~lines 102–220);
  - count-only readers: `ScholardexOperabilityGaugeBinder` gauges, `AdminDashboardService`.

So edge source links exist only so the build can decide "unchanged → skip" on replay and so reconciliation can
detect edge conflicts — both of which can read the edge facts' own `linkState`/lineage instead (the edge facts
are already preloaded per chunk by natural key for the edge no-op-skip).

## Goal

Stop writing and storing edge-type source links. Drive the edge replay-skip and edge reconciliation from the
edge facts' own `linkState`/lineage. **Keep entity source links** (`PUBLICATION`, `AUTHOR`, `AFFILIATION`,
`FORUM`, `CITATION` — ~530k): those do real identity work (re-pointing, cross-source precedence, conflict
detection) and are not duplicated elsewhere.

## Relationship to the H57 clean-build fast path
H57 shipped a "clean-build fast path": when the canonical store is empty, publication canonicalization skips
the pointless per-edge source-link *existence checks* (`allowFallbackLookup=false`). That is a stopgap that
trims *lookups* on a from-scratch build, and it currently covers **publication canon only** (author canon still
performs them). **H58 obsoletes that lookup-skipping for edges entirely:** if edge source links are never
written, there are no edge source-link lookups *or* writes to skip, in either pub or author canon. When H58
lands, the edge-specific `cleanBuild`/`allowFallbackLookup` plumbing in `ScholardexPublicationCanonicalizationService`
(and the not-yet-extended author-canon equivalent) can be removed rather than extended. Net: don't invest in
broadening the clean-build lookup-skip to author canon — do H58 instead.

## Payoff
- **Build:** ~1.66M fewer source-link writes per build → meaningfully faster from-scratch (on top of the H57
  clean-build path, which only skips their existence *checks*).
- **Storage:** ~1.66M fewer docs (~22% of all derived data) permanently.
- **Correctness/maintenance:** removes a whole "two sides built by different code" drift class (the recurring
  H56/H57 root cause) — the edge fact becomes the single source of truth for an edge.

## Scope (files)
- `ScholardexEdgeWriterService` — the three `batchUpsert*Edges` methods currently build edge source-link
  commands and call `sourceLinkService.batchUpsertWithState(...)` for them. Remove the edge source-link
  command construction + write; the edge fact insert/`bulkOps` path already persists lineage + `linkState`.
- `ScholardexPublicationCanonicalizationService` — drop `preloadAuthorshipEdges`/`preloadPublicationAuthorAffiliationEdges`
  *source-link* preloads (keep the edge-**fact** preload by natural key, which already drives the no-op-skip);
  stop seeding edge keys into `sourceLinkCache`.
- `ScholardexAuthorCanonicalizationService` — same for `AUTHOR_AFFILIATION` edge source links (~line 276).
- `ScholardexEdgeReconciliationService` — re-base edge conflict detection on the edge facts' `linkState`/lineage
  instead of edge source links (~lines 102–220), preserving the same `AMBIGUOUS_*` conflict outcomes.
- `ScholardexSourceLinkService` — the `isEdgeType(...)` classifier (~line 862) can be retired once nothing
  produces edge links; keep the enum values for back-compat reads during migration.
- Observability/admin — `ScholardexOperabilityGaugeBinder`, `AdminDashboardService` source-link counts will
  drop ~1.66M; adjust any thresholds/expectations.
- Migration — existing ~1.66M edge source links become orphaned. Either a one-time
  `deleteMany({entityType: {$in: [AUTHORSHIP, AUTHOR_AFFILIATION, PUBLICATION_AUTHOR_AFFILIATION]}})` on
  `scholardex.source_links`, or rely on the next full rebuild (which would simply stop producing them).

## Design questions to resolve first
1. **No-op skip:** confirm the edge replay-skip can be driven purely by the edge fact (content hash / natural
   key + `linkState`) with no behavioural change — i.e. the no-change replay still returns ~1s (H56 invariant).
2. **State machine:** map the edge source-link state transitions (`UNMATCHED → LINKED`, `CONFLICT`, precedence)
   onto the edge fact's `linkState`. Edges are deterministically derived from publication facts, so cross-source
   precedence may be simpler than for entities — verify no edge needs independent precedence arbitration.
3. **Reconciliation conflicts:** ensure `ScholardexEdgeReconciliationService` still raises the same conflicts
   (count + reason + candidate ids) reading edge facts instead of edge source links.
4. **Determinism + parity:** rebuild-twice byte-identical (the H57 fingerprint harness), score/forum parity
   unchanged (read model doesn't use source links so this should be trivially true), conflict parity preserved.

## Exit criteria
- No `AUTHORSHIP`/`AUTHOR_AFFILIATION`/`PUBLICATION_AUTHOR_AFFILIATION` rows in `scholardex.source_links` after a
  rebuild; `scholardex.source_links` total ≈ 530k (entity links only).
- Edge no-op-skip intact: no-change replay still ~1s; edge facts unchanged across rebuild-twice.
- Edge reconciliation conflicts identical (count/reason/candidates) before vs after.
- Read-model parity unchanged (projections, scores, forum_view).
- Full from-scratch build time reduced (measure scopus-buildFacts vs the post-H57 baseline).

# H67 h-index (Hirsch) computation

**Status:** Planning
**Created:** 2026-06-16 (from the [standards capability assessment](../../standards-capability-assessment.md))

## Goal

Compute the candidate's **Hirsch index** from our citation data and expose it as a scoring/threshold input.
Currently nothing computes it (confirmed: no indicator/data in the live DB).

## Who needs it (cross-cutting)

- **Chimie**: WoS h-index, hard threshold ≥13 (Prof) / ≥9 (Conf).
- **Geografie**: Hirsch (WoS, **self-citations excluded**), ≥4/≥3/≥2/≥1 by position.
- **Fizica (H65)**: `h` column in the A/I/P/C/h/T summary.
- **Istorie (FLIT)**: alternative gate — **Google Scholar h ≥3 OR ≥70 citations**.

## Approach

- `h = max k such that k of the candidate's publications each have ≥ k citations`, computed over the
  candidate's confirmed publications using our per-publication citation data (`citedByCount` /
  `scholardex.citation_facts`).
- **Per-source nuance**: chimie wants WoS h; istorie wants Google Scholar h (→ depends on [H66] GS data);
  default Scopus/our-canonical otherwise. Make the citation source a parameter.
- **Self-citation exclusion** (geografie) — reuse the candidate/co-author exclusion logic already in the
  citation scoring path.
- **Aggregate, not per-item**: h is a single number over the whole corpus, unlike current per-publication
  indicators. Decide where it lives — a new aggregate-metric indicator kind, or computed in the report
  summary layer (like physics' T20 summary row).
- **Threshold**: per-position h thresholds via the criteria model; support the "h OR citation-count"
  fallback (istorie) — ties to [H68].

## Open decisions

- Aggregate-metric indicator kind vs report-summary computation.
- Citation source per domain (WoS / Scopus / Google Scholar) and how to represent "which h".
- Self-citation exclusion default; whether to also expose total-citation-count (for the istorie OR-gate).

## Method — source-attributed h off the citation graph (validated 2026-06-22)

The per-source h ("WoS h", "Scopus h") is computed by attributing each **incoming citation** to the indexing of the
forum the **citing** paper sits in: `citation_facts.citingPublicationId → publication_facts.forumId →
forum_facts.{scopusForumIds, wosForumIds}`. Per the candidate's pub:
- `scopusCitationCount` = # incoming citations whose citing forum is Scopus-indexed.
- `wosCitationCount` = # … WoS-indexed.
- `graphCitationCount` = # incoming citations total (internal graph), for a comparable graph-based total-h.
Then `h = max k such that k pubs each have ≥ k` over each count. The existing `citedByCount`-based h stays as the
"Scholardex h" (source-reported totals). Relationship to surface: Scholardex/graph-total h ≥ Scopus h, ≥ WoS h.

**Feasibility (current canonical data):** forums carry indexing (51,531 Scopus-indexed, 26,338 WoS-indexed); of
512,200 citation edges, **81% are classifiable as Scopus-venue, 74% as WoS-venue** (~18% from un-indexed/unresolved
venues). The citing pub is always in-corpus (internal-only graph).

**Validation against ground truth (Adrian Spătaru, real Scopus-h 5 / WoS-h 5, 27 pubs held):** computed Scholardex-h
**5** (exact), Scopus-venue h **4**, WoS-venue h **4** — off by one. A poorly-covered researcher (Florin Rosu, 5 pubs
held) computed 3/2 vs real 5/5. So accuracy tracks corpus completeness; label the metric **"Scholardex-computed
(indicative)"**, not the official index.

**The off-by-one has two causes, one fixable:**
1. *Inherent* — OpenAlex sees fewer citations than Scopus's own DB for some boundary papers.
2. *Fixable — the WoS conference-index gap.* Our `wosForumIds` come only from the WoS **journal** list (all 26,338
   WoS forums are journals; **0 conferences**). We never loaded the WoS **CPCI** (Conference Proceedings Citation
   Index). So WoS-indexed conferences are misclassified: e.g. **all ~19 SYNASC proceedings forums are
   `scopus=True, wos=False`**, so a SYNASC→paper citation is dropped from WoS-venue h (this is exactly Adrian's
   missing WoS citation). **1,014 conference forums are Scopus-indexed but not WoS-indexed.** Onboarding a WoS CPCI
   list (tag conference forums with `wosForumIds`, like the journal Master List) is the WoS accuracy lever — tracked
   as a **separate follow-up (`H76`)**, not a blocker for building the computation.

## Slices

- **S1 — per-pub citation source-split (projection). DONE + verified (commit `7863b12`, Flyway V15).** The Scopus
  projection build classifies each pub's incoming citations by the citing paper's forum indexing
  (`applyCitationSourceSplit`) and persists `graph/scopus/wos_citation_count` on `scholardex_publication_view`.
  **Live verify (2026-06-22):** corpus totals match the offline analysis exactly (418,462 Scopus-venue / 383,858
  WoS-venue / 512,200 graph citations); Adrian's SQL-computed h = scopus 4 / wos 4 / graph 5 / scholardex 5 over
  27 pubs (matches ground truth, off-by-one vs real 5/5 per the WoS-CPCI gap).
- **S2 — source-attributed h + surface.** Compute Scholardex-h / graph-total-h / Scopus-venue-h / WoS-venue-h over a
  researcher's pubs (extend the existing `computeHIndex`); surface on the workspace/profile, labeled indicative.
- **S3 — self-citation exclusion (geografie).** A variant excluding citations whose citing pub shares an author with
  the cited pub (via authorship edges); expose total-citation-count for the istorie OR-gate.
- **S4 — scoring/threshold wiring (ties to H68).** Per-position h thresholds + the "h OR citation-count" fallback.

## Relation

Sibling to [H66](h66-curated-allowlists.md) (GS source) and [H68](h68-criteria-extensions.md) (OR-gate,
thresholds). Unblocks the h requirement in chimie, geografie, fizica/H65, istorie. **H76** (WoS CPCI onboarding) is the
WoS-accuracy follow-up surfaced by the 2026-06-22 validation.

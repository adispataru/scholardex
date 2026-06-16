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

## Relation

Sibling to [H66](h66-curated-allowlists.md) (GS source) and [H68](h68-criteria-extensions.md) (OR-gate,
thresholds). Unblocks the h requirement in chimie, geografie, fizica/H65, istorie.

# H63 OpenAlex enrichment (corresponding author + ORCID)

**Status:** Planning (intent captured; design/details deferred)
**Created:** 2026-06-16

## Goal

Add OpenAlex as an enrichment source for our publications, primarily to obtain **corresponding-author**
and **last-author** information we don't have today, plus author **ORCID**s. Keyed by DOI. The immediate
driver is physics **P = "prim autor sau autor corespondent"** (Ordin 6129/2016, Anexa 1, Fizică), but the
data is broadly useful (author disambiguation, first-author confirmation, affiliations).

**Cross-cutting (standards capability assessment, 2026-06-16):** corresponding-author is required by
**chimie** (FICAP/FICAC), **biologie**, **geografie**, **fizica**, **FSP**, **sport** — and several of
those (**biologie, FSP, sport**) treat **last author** as a "principal author" too. OpenAlex's
`author_position` gives last-author; fold it into this task alongside `is_corresponding`. This makes H63 a
shared author-role enrichment, not a physics-only one.

## Why OpenAlex (investigation 2026-06-16)

`correspondingAuthors` is empty for all ~92.6k publication facts; our Scopus export is Search-API-shaped
and doesn't carry it. Sources compared:

| source | corresponding author? | match key | cost / limits |
|---|---|---|---|
| Scopus Search (current dump) | ✗ | — | — |
| Crossref | ✗ (first-author only via `sequence`) | name / ORCID | free, unthrottled |
| Scopus Abstract Retrieval (FULL `.correspondence`) | ✓ but **name-based** (surname+initials) | name | key; **5,000/week** |
| **OpenAlex** (`authorships[].is_corresponding`) | ✓ | **ORCID** + name | free; ~**100k/day, ~10/s** |

OpenAlex wins: ID-precise (gives the corresponding author's ORCID), free, ~100k/day (so the ~7k
UVT-paper backfill is minutes, not the ~1.5 weeks Scopus's 5k/week implies), keyed by DOI (which we
already have for most pubs), and lands as a *new* enrichment source without touching the Scopus
wrapper/dumper. Verified live: OpenAlex authorships expose `is_corresponding`, `author.orcid`,
`author_position`, `affiliations`/institutions.

## Scope (target)

- **~7,000 UVT-affiliated publications** (the candidates' own works; top affiliation `saff_dd26…`), NOT
  the ~92k (citing papers don't need corresponding author).
- One-time **backfill** (~minutes at OpenAlex limits, batched by DOI) + **ongoing enrichment** keyed by
  DOI for new/refreshed papers.

## What it unblocks / downstream

- Populate the existing `correspondingAuthors` slot (already wired model → fact → view → read ports).
- Expose corresponding author on `ScoringPublicationReadModel` (not currently exposed) so scoring can use it.
- Physics **P** upgrades from **first-author-only** (the interim) to **first OR corresponding author**.
  (The alphabetical-ordering exclusion in the standard stays a manual fišă adjustment.)
- Bonus, optional: ORCID-based author disambiguation, `author_position` (first-author confirmation),
  ROR/affiliations, OA status.

## Open questions / decisions (defer)

- **Matching**: ORCID-precise requires candidate ORCIDs — confirm whether the researcher profile stores
  ORCID; else fall back to name matching against the corresponding author's display name.
- **Coverage**: `is_corresponding` is only populated where OpenAlex's upstream metadata declares it —
  partial coverage expected (better for recent papers). Define the fallback (→ first-author) and surface
  "unknown" vs "not corresponding".
- **Integration shape**: new enrichment fetcher (batch backfill + DOI-keyed incremental) — a Java-side
  client vs. extending the python wrapper vs. a standalone enricher. Persistence path into the canonical
  facts. (TBD.)
- **Identity reconciliation**: map OpenAlex author/ORCID to our canonical author ids.
- Polite-pool etiquette (`mailto`), caching, refresh cadence.

## Dependencies / relation

Independent of the Scopus dumper/wrapper (additive source). Directly enables the physics-domain P
criterion (see the physics report work) and improves author identity quality generally.

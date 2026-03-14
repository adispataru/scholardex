# C01 Canonical CNFIS Rule Spec
Status: active indexed source-of-truth document.

Date: 2026-03-03  
Scope: active CNFIS 2025 scoring path only (`CNFISScoringService2025`)

## Purpose

Define the authoritative CNFIS scoring behavior contract used by the active export flow.

## Canonical Rules

### 1) Subtype resolution

- Effective subtype is resolved in this order:
  1. `publication.scopusSubtype` (preferred)
  2. `publication.subtype` (fallback)
- Both values are normalized with `trim().toLowerCase()`.

### 2) Article/review path (`ar` / `re`)

- Ranking source:
  - try by `forum.issn`
  - fallback to `forum.eIssn` when first lookup is empty
- Domain filter:
  - apply all categories for domain `ALL`
  - otherwise include only categories present in `domain.wosCategories`
- Quartile flags:
  - `Q1` -> `isiQ1`
  - `Q2` -> `isiQ2`
  - `Q3` -> `isiQ3`
  - `Q4` -> `isiQ4`
- Index flags:
  - category index containing `ESCI` -> `isiEmergingSourcesCitationIndex`
  - containing `AHCI` -> `isiArtsHumanities`
  - otherwise -> `erihPlus`

### 3) Proceedings rules (`cp` / `ch`)

- `cp`:
  - if forum publication name contains `IEEE` -> `ieeeProceedings = true`
  - else if WoS id is non-empty -> `isiProceedings = true`
- `ch`:
  - if forum publication name contains `Lecture Notes` and WoS id is non-empty -> `isiProceedings = true`

### 4) Category parsing resilience

- null category:
  - log warning and return empty index text
- blank category:
  - log warning and return empty index text
- missing delimiter (`-`) or delimiter with no suffix:
  - log warning and use the full normalized category as index text (best effort)
- valid delimiter:
  - use text after first delimiter as category index

### 5) Year handling

- Attempt publication year from `publication.coverDate` first 4 chars.
- If parsing fails:
  - log warning
  - use fallback year `LAST_YEAR` (currently `2023`)
- Any resolved year above `LAST_YEAR` is capped to `LAST_YEAR`.

### 6) Author counters

- `numarAutori`:
  - total `publication.authors.size()`
- `numarAutoriUniversitate`:
  - number of publication authors found in `cacheService.universityAuthorIds`

## Non-goals for this slice

- No legacy CNFIS path reintroduction.
- No scoring-rule redesign.
- No endpoint/export schema changes.

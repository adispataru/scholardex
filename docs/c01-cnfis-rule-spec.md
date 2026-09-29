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
  - use the last list of the edition (`CnfisEdition.lastListYear()`)
- The list year is a rule of the EDITION (`CnfisEdition.listYearFor`, H129): the list of the publication year,
  and for the last year of the window the list of the year before — CNFIS reports before the new list is
  public ("pentru articolele publicate în anul 2024 … lista JCR din 2023"). Edition 2025 = window 2021–2024,
  last list 2023; edition 2027 = window 2023–2026, last list 2025, provisional. Never newer than what is loaded
  (`ReportingLookupPort.maxAvailableYear()`).

> **H129 (2026-09-30) changed the rules below in this document; where they differ, this note wins.**
> Quartile = the better of the AIS and the impact-factor quartile over all SCIE/SSCI categories. Arts &
> Humanities and ESCI are reported in their own columns, without quartile, and a category the platform cannot
> read is reported as nothing (it used to set ERIH+). Reported document types: Article, Review, Proceedings
> Paper; a chapter counts as a proceedings paper only when its venue is a conference. ISI Proceedings = the
> venue is in the conference index (`wosCpciIndexed`), not "the paper has a WoS code"; IEEE = the publisher or
> the name of the venue. A publication without a DOI and a WoS code, or in none of the categories, gets no
> row and is listed with its reason on the sheet "Neincluse (platforma)". A download looks nothing up.

### 6) Author counters

- `numarAutori`:
  - total `publication.authors.size()`
- `numarAutoriUniversitate`:
  - number of publication authors found in `cacheService.universityAuthorIds`

## Non-goals for this slice

- No legacy CNFIS path reintroduction.
- No scoring-rule redesign.
- No endpoint/export schema changes.

# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H66` Canonical Forum registry (multi-source identity + rankings + indexing). **Reshaped from
  "curated allowlists" → forum-first; re-grounded against code (scope narrower than first thought).**
  Code check confirms the canonical forum is **already first-class and multi-source** (`ScholardexForumFact`
  already has `scopusForumIds`/`wosForumIds`/`googleScholarForumIds`/`userSourceForumIds` + ISSN/aliases),
  a **standalone forum-import path already exists** (`ScopusImportEntityType.FORUM` via the publisher-CSV
  ingestion), and the publication path is **already resolve-or-enrich** (`upsertForumFact` keys by
  `source_id`, blank-tolerant). So this is NOT a pipeline rewrite — it's three moves: **(A)** feed the new
  list sources (CiteScore/MJL/DOAJ/ERIH) into the existing FORUM import path and canonicalize by ISSN→name;
  **(B)** the bug-killer — fold WoS rankings/indexing (today a `journalId`-keyed sibling joined *fuzzily at
  scoring time*) **onto the forum by `wosForumIds` FK** + project onto the forum view, retiring the fuzzy
  resolver; **(C)** verify pub resolve-or-enrich + dedup cover the new id sources. Keep publication forum
  emission (long-tail venues need it) — it just becomes near-idempotent linking once seeded.
  Sources in hand: **Scopus CiteScore list** (`data/scopus/...csv`, 29,777 sources w/ Source ID + ISSN/eISSN
  + ASJC + type) — the clean FK for resolving Scopus pubs; **WoS MJL** (`data/wos/mjl/`, 24,123 journals,
  index membership + categories); **WoS metrics** (AIS/IF/RIS in Postgres); **DOAJ** (CSV); **ERIH+**
  (Typesense, pinned); CNCSIS/SENSE/CORE (DB). Still: ERIH+ pull, CNCS A/B/C tiers, embedded prestige lists
  (transcribe from `data/standards/`), vendor title-lists for "≥N DBs". MBL no longer downloadable
  (substitute SENSE/CNCSIS/admin). Planning doc at `docs/tasks/active/h66-curated-allowlists.md`.
  **Status (2026-06-17):** Moves A (CiteScore/MJL/DOAJ/ERIH loaders), B (forum-keyed FK projection +
  scoring, bug-killer), C (resolve-or-enrich confirm + dedup) **DONE**. Move D (forums-first from the Scopus
  Source List) partly done (A6 loader, forums-first ordering, publication resolve-and-link). A live
  multi-source rebuild proved the registry but surfaced that the pipeline is organized **by source**
  (`ScopusFactBuilder`/`WosOnboarding`), scattering forum-building across 5 services → ordering bugs + churn.
  **Pivoting the remaining Move D/E + the architecture cleanup into H66B.**

- [ ] `H66B` Entity-oriented canonical builders (re-architecture of H66's pipeline). Reorganize the
  canonicalization layer from **by-source** to **by-entity** in a clean dependency DAG: **ForumBuilder**
  (all forum sources → registry, dedup inside) → **RankingBuilder** (CiteScore + WoS scores + WoS
  score-only→quartile enrichment, forum-keyed) → **PublicationBuilder** (resolve venue against the registry;
  Scopus now, OpenAlex/DBLP/Google-Scholar later) → **CitationBuilder**; plus **BookBuilder** (books as a
  separate `book_facts` entity, not forums). Source parsers stay per-source (formats differ); only the entity
  layer is reorganized. Subsumes H66 Move D (D5 orchestration, D6 MJL coverage, D7 config) + Move E (book
  registry) + D2 (CiteScore-as-rankings). Verified against the 448-conflict 2026-06-17 baseline + the
  reconcile-audit harness. Planning doc at `docs/tasks/active/h66b-entity-oriented-builders.md`.

- [ ] `H67` h-index (Hirsch) computation (foundational, from the standards assessment).
  Goal: compute the candidate's Hirsch index from our citation data + expose it as a scoring/threshold input
  (nothing computes it today). Needed by chimie (≥13/9 WoS), geografie (Hirsch excl. self-cit), fizica (h
  column), istorie (GS h≥3 OR ≥70 citations). Aggregate metric over the corpus; citation source per domain
  (WoS/Scopus/GS); self-citation exclusion; per-position thresholds. Planning doc at
  `docs/tasks/active/h67-h-index.md`.

- [ ] `H68` Advanced criteria / threshold extensions (foundational, from the standards assessment).
  Goal: extend the criteria engine for recurring patterns — **post-PhD temporal anchor**, per-indicator/
  per-group **caps (plafoane)**, **best-of single-indicator assignment**, **count + point** mixed criteria,
  **Da/Nu** qualitative gates, cross-criterion compensation. Modest config-level extensions on the existing
  per-position threshold model. Consumers: FSGC, drept, FLIT, FAD, FSP, sport, fizica. Planning doc at
  `docs/tasks/active/h68-criteria-extensions.md`.

- [ ] `H65` Physics (Fizică/FF) report — DOCX export. **Postponed behind H63 + H64.**
  Goal: export the FV Fizică fišă (Ordin 6129/2016 Anexa 1; 21-table template). Scoped this session;
  implementation deferred until the data tasks land (P needs corresponding author from H63; A9/A10 need
  canonical project budget/attribution from H64).
  Notable: core new primitive `Nef` (effective author-count bracket, divisor for I/P/A1–A8); indicators
  I=ΣAIS/Nef, P=ΣAIS (first-author now → first-or-corresponding via H63), A1–A10 didactic (reuse `Grant
  Cercetare`/`Brevet`/`Proiect educational` activities + WoS Master Book List allowlist + editor role),
  A=ΣA_i, C=citation count, h=Hirsch (new), T=composite. Reuse AIS strategy, FEAA/CNCSIS allowlist pattern,
  Mate_C count, docx infra. Still to read: PDF p6–14 (Prof thresholds, C/h/T definitions, HEPP exception).
  Slices: (1) Nef core + I/P; (2) A1–A6; (3) A7–A10; (4) C+h+T+summary. Planning doc at
  `docs/tasks/active/h65-physics-report-export.md`.

- [ ] `H64` Canonical projects (unification across sources).
  Goal: a canonical `ScholardexProject` entity researchers reference across all project-scoring reports
  (physics A9/A10, FEAA, CS), unifying project identity + attribution. Primary value is unification (one
  trusted project + who led it), not budget.
  Notable: OpenAIRE has **no** RO national (UEFISCDI/PN-III) projects (verified); CORDIS has EU projects
  with per-partner budget (free bulk); **brainmap is the only RO-national source** (rich: code, programme,
  partners, **director person+role** — no budget). Sources: CORDIS bulk import + brainmap **offline dump
  generator** (NOT a live dependency; gentle pacing to avoid account lock; creds gitignored) + OpenAIRE
  (deferred, EU links). Threads a new Project entity through the existing pipeline (events → source facts →
  canonical facts → projections); partners tie to canonical `ScholardexAffiliationFact` (PIC / name+country);
  researcher↔project + project↔partner join facts.
  Open decisions first: budget semantics (org contribution vs led-team share — decides if budget ingest is
  worth it), full-pipeline vs lighter reference-import, brainmap mechanism (admin tool vs UEFISCDI export),
  **currency normalization + monetary eligibility thresholds** (RON↔EUR bnr.ro; grant ≥X thresholds recur
  across chimie/geografie/FSGC/drept/FSP/sport/FEAA per the standards assessment). Physics/FEAA ship NOW on
  the existing `Grant Cercetare`/`Buget` activity — this is independent. Planning doc at
  `docs/tasks/active/h64-canonical-projects.md`.

- [ ] `H63` OpenAlex enrichment (corresponding + last author + ORCID).
  Goal: add OpenAlex as an enrichment source (keyed by DOI) to obtain corresponding-author info we lack
  today (`authorships[].is_corresponding` + ORCID + `author_position` for **last-author**), plus author
  disambiguation. Driver: physics `P = prim autor sau autor corespondent`. **Cross-cutting** (standards
  assessment): corresponding-author needed by chimie/biologie/geografie/fizica/FSP/sport; **last-author as
  principal** needed by biologie + FSP + sport — fold last-author into this task.
  Notable: `correspondingAuthors` is empty for all ~92.6k facts (our Scopus export lacks it). Crossref
  has no corresponding author; Scopus Abstract Retrieval has it but name-based + 5k/week; OpenAlex has it
  ID-precise (ORCID), free, ~100k/day → ~7k UVT-paper backfill in minutes. Additive source — does NOT
  touch the Scopus dumper/wrapper.
  Deliverable: enrichment fetcher (backfill + DOI-keyed incremental) → populate `correspondingAuthors` →
  expose on `ScoringPublicationReadModel` → upgrade physics P from first-author-only to first-or-corresponding.
  Exit criteria: UVT pubs carry corresponding author where OpenAlex declares it (ORCID-matched), partial
  coverage handled with first-author fallback. Planning doc at `docs/tasks/active/h63-openalex-enrichment.md`.

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.

- [ ] `H62` FEAA Economics report — full-fišă DOCX export (bound to `6849fb3d97a94f22948f9430`).
  Goal: no-formula DOCX export for the FEAA verification fišă — articles (M/N/AIS/Pi), books/chapters
  slots 7–10 (Pi=coeff/N by publisher tier), citations (AIS/quartile/Cj), and a P/C/S=P+C summary with
  all-position thresholds. Same pattern as `matematica-2016`.
  Notable: scoring already exists — FEEA_P (`ECONOMICS_JOURNAL_AIS`, `M*(1-(N-1)*0.1)*S`) and FEEA_C
  (`AIS`, quartile points); `report.criteria` hold per-position thresholds; S=P+C (sum, no separate
  indicator). New work: surface M on publications + AIS/quartile on citations; book tier-scoring + Anexa 1
  publisher-list seed; Core/Infoeconomics article count. `Fisa-verificare_prof_conf` is the methodology
  annex (M table + publisher list), NOT a candidate fišă; the `Standarde-minimale-*` files are the fišă
  (identical structure, differ only by position thresholds).
  Slices: (1) articles+citations+summary using existing indicators [unblocked]; (2) books/chapters
  scoring; (3) Core-Econ count. Planning doc at `docs/tasks/active/h62-feaa-economics-report-export.md`.

- [ ] `H61` Citation exclusion "any co-author" mode (Scopus all-authors self-citation) for Informatică.
  Goal: add a third citation-exclusion mode — exclude a citation when the citing work shares **any** author
  with the *cited* publication (not only the candidate) — for the upcoming Informatică standard. Today we
  support only `CITATIONS` (count all) and `CITATIONS_EXCLUDE_SELF` (candidate-only, which matches Ordin
  6129/2016 Matematică).
  Notable: the only conceptual change is the comparison set — `CANDIDATE_ONLY` uses the candidate's resolved
  IDs (today), `ANY_COAUTHOR` uses the cited publication's full author set (`cited.getAuthors()`); both filter
  sites (`ReportScopedIndicatorScoringSupport` score path + `CitationRowProjector` display path) already
  iterate per cited pub. `ANY_COAUTHOR` ⊇ `CANDIDATE_ONLY`. Replace `Citations(boolean excludeSelf,…)` with a
  3-value `SelfCitationPolicy`; add legacy token `CITATIONS_EXCLUDE_COAUTHORS`; no data migration (existing
  strings unchanged); cache fingerprint invalidates automatically via `toLegacy()`.
  Deliverable: the enum + codec + getter, both filter sites switched by policy, admin-editor option, tests
  (non-candidate-co-author excluded under the new mode, kept under candidate-only), then re-point Informatică
  citation indicator(s).
  Exit criteria: round-trips through `of()`/`toLegacy()`; existing Citations indicators unaffected; both paths
  agree on the per-cited-paper overlap; unlinked-author identity-matching caveat covered. Planning doc at
  `docs/tasks/active/h61-citation-coauthor-exclusion.md`.

- [ ] `H60` Relative year specs (recent-window + latest-rankings) for indicator scoring.
  Goal: replace fixed absolute year ranges (which go stale yearly) with self-rolling relative windows — `YearRangeSpec.PreviousNYears(n)` (article inclusion, t‑n…t‑1) and `ScoreYearRangeSpec.LatestNRankings(n)` (the n most recent ranking list-years present in the DB, ≤ the run's referenceYear). Anchored on a `referenceYear` stored on `UserIndividualReportRun` for deterministic replay.
  Notable: `yearRangeSpec` is currently NOT enforced in scoring (dead config) — inclusion filtering is net-new; `LatestNRankings` resolves against the DB's ranking years (not the journal's own) so a journal dropped from the latest list is correctly excluded rather than falling back to a stale year.
  Deliverable: the two new sealed-type records + reference-year threading through the scoring chain (incl. the re-scoring export/detail path), enforced publication-year inclusion filtering, a cached "distinct ranking list-years" lookup, admin-editor support, and migration; then re-point the FV Matematică indicators (`Mate_S_recent`→PreviousNYears(7); scoreYearRanges→LatestNRankings(1)).
  Exit criteria: relative specs resolve deterministically from (referenceYear, DB ranking years), excluded-journal + boundary cases tested; inclusion filtering enforced without regressing AllYears indicators; a run stores referenceYear and replay is stable across later ranking imports; replay-shape guard green. Planning doc at `docs/tasks/active/h60-relative-year-specs.md`.

- [ ] `H50` Individual report export / read-only score-verification import.
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Remaining: docx exporter (H50.4) and docx import (H50.6); extend to the other report types (H50.5) — only `informatica-2016` has a binding today, so the other four definitions can't export/verify.
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

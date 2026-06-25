# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H67` h-index (Hirsch) computation (foundational, from the standards assessment).
  Goal: compute the candidate's Hirsch index from our citation data + expose it as a scoring/threshold input
  (nothing computes it today). Needed by chimie (≥13/9 WoS), geografie (Hirsch excl. self-cit), fizica (h
  column), istorie (GS h≥3 OR ≥70 citations). Aggregate metric over the corpus; citation source per domain
  (WoS/Scopus/GS); self-citation exclusion; per-position thresholds. Planning doc at
  `docs/tasks/active/h67-h-index.md`.
  **Method validated (2026-06-22):** source-attributed h by attributing each incoming citation to the citing paper's
  forum indexing (`citation_facts → citing pub forumId → forum scopus/wos ids`). 81%/74% of edges classifiable as
  Scopus/WoS-venue. Spot-check vs ground truth (Adrian Spătaru, real 5/5): Scholardex-h 5 exact, Scopus-venue h 4,
  WoS-venue h 4 (off-by-one; accuracy tracks corpus completeness → label "indicative"). Slices: S1 per-pub citation
  source-split in the projection · S2 source-attributed h + surface · S3 self-citation exclusion · S4 threshold wiring.
  WoS accuracy depends on `H76` (WoS conference index).

- [ ] `H76` WoS Conference Proceedings Citation Index (CPCI) onboarding. Our `wosForumIds` come only from the WoS
  **journal** Master List / JCR — all 26,338 WoS-indexed forums are journals, **0 are conferences**. So WoS-indexed
  conferences are misclassified as non-WoS (e.g. all ~19 SYNASC proceedings forums are `scopus=True, wos=False`;
  **1,014 conference forums are Scopus-indexed but not WoS-indexed**), which undercounts the **WoS h-index** (`H67`)
  and any WoS-conference scoring — material for CS (conference-heavy). Fix: acquire a WoS CPCI conference/proceedings
  list (a WoS export, like the journal Master List already onboarded) and onboard it into the forum registry, tagging
  conference forums with `wosForumIds` (reuse the WoS journal onboarding path). Surfaced by the `H67` validation
  (2026-06-22).
  **Sourcing reframed (2026-06-25): there is NO downloadable CPCI master list.** Clarivate curates *journals* as a
  list (MJL → our `data/wos/mjl/`); conferences are indexed per-*event* and only exist inside Core Collection
  **records** — which is why MJL/doc pages redirect. The list must be *derived* from records. UI-only route (the
  access we have): Core Collection search → Refine → *Web of Science Index* = CPCI-S + CPCI-SSH → **Analyze Results**
  by **Conference Title** (and **Source Title**) → Download the data table (field value + record count; no ISSN —
  Analyze omits it, and Records export is capped ~1,000/export). So the matcher is **title/acronym-based**, not ISSN:
  match the CPCI conference/source titles against our conference forums via the existing CORE normalized-title +
  DBLP `conf/X` acronym keys, tag matches `wos=true` (synthetic `wosForumIds`). Coverage is partial by construction
  (title matching + the export's scope) — acceptable, the consumer (WoS h/citation) is already labelled
  *indicative*. **MVP: UVT-affiliation-scoped CPCI roster first** (small, directly relevant: the conferences UVT
  publishes in), validate the pipeline, then a broad CPCI roster for wider citing-venue coverage. Implementation
  waits on the first CSV (build the matcher against the real export shape, not assumptions). Plan:
  `docs/tasks/active/h76-wos-cpci-onboarding.md`.
  **S1+S2 DONE (2026-06-25) — MVP applied live.** The user exported WoS **Records** (richer than Analyze: carries
  DOI/ISSN/ISBN), so the matcher became **DOI→our publication→forum** (exact) then ISSN/ISBN then conference-title
  containment — far higher precision than venue-name matching, because these are UVT's own corpus papers.
  `WosCpciOnboardingService` + `POST /admin/initialization/wos/cpci/{dryRun,apply}`. From 1,984 UVT proceedings
  records: 1,302 matched → **211 net-new conference forums tagged `wosCpciIndexed=true`** (a NEW boolean kept
  separate from `wosForumIds`, which is unique-indexed + joined as WoS journal ids; only `applyCitationSourceSplit`
  reads the flag → a CPCI-conference citation counts WoS-venue). Applied via a throwaway agent-dev `:8181` (schedulers
  off), idempotent, verified in Mongo. **Projection refresh DONE (2026-06-25):** full reporting rebuild (~6.8 min, via
  a controlled `:8181`, projection is manual-only so no `:8080` race) lifted `wos_citation_count` **383,580→393,489
  (+9,909)** across **+503 pubs** — the WoS-venue h-index now reflects CPCI.
  **Physics caveat:** `wosCpciIndexed` currently feeds ONLY the citation source-split (WoS h-index), NOT the forum
  membership / WoS-forum scoring reads. So **physics (FF) counting CPCI *papers* as WoS is not yet wired — that is
  `H65` work** (have the paper-count WoS-forum read honor the flag, or project a CPCI membership row).
  **S3 (broad citing coverage) DEFERRED until a WoS API key** — the UVT-scoped roster covers venues UVT publishes in;
  the *true* WoS citation graph (all citing venues) needs a programmatic Core-Collection pull, a hassle without an
  Expanded/Starter API key (UI Records export caps ~1,000/file). Revisit when an API key is available.

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
  **Scope update (2026-06-21):** the **bulk backfill is absorbed into `H73` slice 3** (the bulk OpenAlex importer) — the local
  `data/openalex/uvt_works.jsonl` already carries `authorships[].is_corresponding` + ORCID + `author_position`,
  so H73's bulk import populates corresponding/last-author at ingest. H63 shrinks to the **incremental DOI-keyed
  path for newly-added pubs** + the scoring-surface wiring (read model + physics P upgrade).

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.

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

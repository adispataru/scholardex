# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

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
  **MECHANISM DONE (2026-06-25):** `SelfCitationPolicy {NONE, CANDIDATE_ONLY, ANY_COAUTHOR}` replaces the boolean in
  `IndicatorKind.Citations`; legacy codec maps `CITATIONS`/`CITATIONS_EXCLUDE_SELF`→`NONE`/`CANDIDATE_ONLY` (no
  migration) + new `CITATIONS_EXCLUDE_COAUTHORS`→`ANY_COAUTHOR`; round-trips `of()`/`toLegacy()`. Both filter sites
  (`computeCitationView` score + `CitationRowProjector` display) call ONE shared public helper
  `ReportScopedIndicatorScoringSupport.citationExclusionAuthorIds(policy, cited, candidateIds)` → identical exclusion
  set by construction. `Indicator.getCitationExclusionPolicy()`; admin dropdown option added. Full suite 2437/2437.
  **DEFERRED: re-point Informatică** citation indicator(s) to `CITATIONS_EXCLUDE_COAUTHORS` — gated on the published
  standard text (per-cited-paper vs global co-author network; open question in the plan doc). **Caveat shipped:**
  `ANY_COAUTHOR` under-excludes when co-authors aren't canonicalized to the same ids (documented in the enum).

- [ ] `H60` Relative year specs (recent-window + latest-rankings) for indicator scoring.
  Goal: replace fixed absolute year ranges (which go stale yearly) with self-rolling relative windows — `YearRangeSpec.PreviousNYears(n)` (article inclusion, t‑n…t‑1) and `ScoreYearRangeSpec.LatestNRankings(n)` (the n most recent ranking list-years present in the DB, ≤ the run's referenceYear). Anchored on a `referenceYear` stored on `UserIndividualReportRun` for deterministic replay.
  Notable: `yearRangeSpec` is currently NOT enforced in scoring (dead config) — inclusion filtering is net-new; `LatestNRankings` resolves against the DB's ranking years (not the journal's own) so a journal dropped from the latest list is correctly excluded rather than falling back to a stale year.
  Deliverable: the two new sealed-type records + reference-year threading through the scoring chain (incl. the re-scoring export/detail path), enforced publication-year inclusion filtering, a cached "distinct ranking list-years" lookup, admin-editor support, and migration; then re-point the FV Matematică indicators (`Mate_S_recent`→PreviousNYears(7); scoreYearRanges→LatestNRankings(1)).
  Exit criteria: relative specs resolve deterministically from (referenceYear, DB ranking years), excluded-journal + boundary cases tested; inclusion filtering enforced without regressing AllYears indicators; a run stores referenceYear and replay is stable across later ranking imports; replay-shape guard green. Planning doc at `docs/tasks/active/h60-relative-year-specs.md`.
  **MECHANISM DONE (2026-06-25) — engine built end-to-end, suite 2437→2452:**
  - **Model:** `YearRangeSpec.PreviousNYears(n)` (`includes(pubYear, refYear)` = `[t-n..t-1]`) + `ScoreYearRangeSpec.LatestNRankings(n)` (context-aware `allowedYears(ResolutionContext{itemYear, referenceYear, availableRankingYears})`, the n most recent DB ranking-years ≤ refYear; empty without context = no stale fallback). `AllYears` caps at referenceYear when set (deterministic replay). Legacy codec `PREV:n`/`LATEST:n` (fingerprint distinguishes n). `UserIndividualReportRun.referenceYear` set at creation.
  - **Threading:** thread-scoped `ScoringReferenceYearContext` (chosen over threading `ScoringService.getScore` ×15 for minimal diff); `ReportingLookupPort.getDistinctRankingYears()` (Postgres DISTINCT `wos_metric_fact` years, memoized, delegated through the `@Primary` facade); `getAllowedYearsForPublication` builds the context. Build path wraps with the run's year; live apply/detail (`computeReportScopedIndividualReport`, `buildReportScopedIndicatorDetail`) default to the current year.
  - **Inclusion enforced:** `ScientificProductionService.calculateScientificProductionScore` drops pubs outside `yearRangeSpec` (AllYears fast no-op). **Live sweep: all 43 indicators are AllYears → score-neutral** (Matematică's Mate_S/Mate_C carry `scoreYearRangeSpec Absolute(2019,2023)`, the already-enforced score path, NOT a yearRangeSpec).
  - **Admin:** free-text year fields already round-trip via `parse()`; added `PREV:n`/`LATEST:n` help hints.
  - Tests: spec resolution/boundaries/codec, holder set/restore/nesting, inclusion via the GenericCount path.
  **MATEMATICĂ RE-POINTED (2026-06-25) — applied to live Mongo config:** `Mate_S_recent.yearRangeSpec`
  Absolute(2018,2025)→`PreviousNYears(7)`; `Mate_S`/`Mate_S_recent`/`Mate_C.scoreYearRangeSpec`
  Absolute(2019,2023)→`LatestNRankings(1)`. Verified live: `/admin/indicators` 200, the three render `PREV:7`/
  `LATEST:1`, deserialize cleanly. Impact: `LatestNRankings(1)` resolves to **[2024]** (latest `wos_metric_fact`
  year; coverage 22,245 journals ≥ 2023's 21,844 → complete list), so Mate_S/Mate_C now score against the 2024 JCR
  list instead of best-of-2019–2023 (the standard's "latest list at submission"). Stored runs keep cached scores
  (fingerprint changes via the new legacy string → future runs + live views re-score). **The `Mate_C` SRI-count
  formula fix is separate/config-only, out of H60.**
  **CRITICAL FIX shipped alongside (`033e684`):** the H61 boolean→enum change broke deserialization of all
  pre-H61 Citations indicators (Info_C/Mate_C/…) — green tests missed it; only booting surfaced it. Fixed by
  `CitationPolicyMigrationRunner` (raw-Mongo startup migration, self-healing). See memory
  record-component-change-breaks-mongo-deser. **Caveat:** the live `:8080` (pre-H61 code) mis-reads the migrated
  Citations shape until restarted on the new build.
  **Re-score paths covered (2026-06-25):** since Matematică now uses relative specs, every re-score path must
  resolve them — fixed centrally via `ScoringReferenceYearContext.currentOrCurrentYear()` (defaults to the current
  year when no run context is set), used by `getAllowedYearsForPublication` + the inclusion filter. Covers the
  per-indicator xlsx export, the H50 `ReportInstanceSnapshot` projectors (incl. `ROLE_JOURNAL_RECENT`), the apply
  view, and group reports without per-path wrapping; the run-build path still overrides with the stored year for
  replay. **H60 is now functionally complete** (only the out-of-scope `Mate_C` formula fix + the `:8080` restart
  caveat remain).

- [ ] `H50` Individual report export / read-only score-verification import.
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Remaining: docx exporter (H50.4) and docx import (H50.6); extend to the other report types (H50.5) — only `informatica-2016` has a binding today, so the other four definitions can't export/verify.
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

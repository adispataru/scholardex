# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H73` OpenAlex-first ingestion (UVT 1-hop corpus + ROR affiliation backbone). Ingest the locally-held
  OpenAlex UVT neighborhood + institution backbone into the canonical layer **from files already on disk**
  (`data/openalex/`, ~2.7 GB, git-ignored, regenerable from `scopus-python/openalex_fetch_{uvt,citations}.py`) —
  no further API fetching/spend. Have: `uvt_works.jsonl` (11,656 full pubs), `uvt_citing_works.jsonl` (105,766
  unique incoming citers — 4,404 are UVT, 101,362 external), reference DNA edges (321,810 edges / 203,721 unique
  targets, only 8,756 already held), institutions fixture (121,512; 121,511 ROR, 121,509 alias-bearing).
  **Key finding: 0/121,512 OpenAlex institutions carry a Scopus id** → no afid→ROR id-join; matching is name/alias-based.
  **Load-bearing decision (2026-06-21): OpenAlex-first affiliation backbone.** OpenAlex institutions are the **primary**
  source for `ScholardexAffiliationFact` — they derive the backbone (ROR-keyed `@Id`) **first**; Scopus affiliation
  facts then resolve **into** it (alias-match → add afid to `scopusAffiliationIds`) or mint their own if Scopus-only.
  ROR is canonical, afid secondary — inverts the rejected "Scopus spine + ROR tag". The model already supports it
  (`ScholardexAffiliationFact` has scopus/wos/googleScholar/user id slots + `rorIds` + `aliases`); direct precedent is
  **authors** (Scopus + OpenAlex resolve-or-mint into one `ScholardexAuthorFact`). **Delivers the affiliation merges
  afid-keying structurally blocked** (two afids → one ROR = original H72 goal), fixes the e-Austria/UVT cross-tag,
  collapses cross-language names under one ROR. Other decisions: **absorb the H63 corresponding/last-author/ORCID
  backfill** (bulk works carry `authorships[]`); **broad forums-first reorder stays H74** (H73 makes only the narrow
  "OpenAlex-institution canon before Scopus-affiliation canon" ordering change); **retire
  `ScholardexAffiliationRorBridgeService` + clear noisy positional `rorIds`**; **backbone scope = referenced-only**
  (works-seeded: the corpus references 24,296 institutions, 24,233 = 99.7% in-snapshot → backbone, vs 121k full;
  fixture stays on disk). Slices:
  **(1)** one replayable OpenAlex bulk importer — pass 1 reads `uvt_works` + `uvt_citing_works` → `openalex.*`
  source facts via `OpenAlexImportService` + H63 backfill + collects referenced institution ids; pass 2 reads
  `institutions/*.gz`, keeps only referenced ids → backbone `ScholardexAffiliationFact`s (ROR-keyed, aliases from
  `display_name_alternatives`); wired into `rebuildAllDerived`, backbone derived before Scopus affiliation canon;
  **(2)** Scopus affiliations resolve INTO the backbone via the 3-tier alias matcher (exact-alias → simplification →
  country-gated Jaccard≥0.8 + city) — plug afid into `scopusAffiliationIds` or mint Scopus-only; retire the positional
  bridge + clear noisy `rorIds`;
  **(3)** OpenAlex pub→author→affiliation edges (UVT works AND citers) — make bulk citers FULL (reverses slice-1 bare;
  ~295k citer authors, ~708k edges, ~doubles the graph), extend `OpenAlexCanonicalizationService` to emit
  author→affiliation + pub→author→affiliation edges resolving `institutionRors`→backbone; citer authors get FULL
  reconcile participation (no external flag, ~510k authors through the passes);
  **(4)** DNA edge layer — persist `referenced_works` IDs + materialize 17,130 internal UVT↔UVT edges (both ends
  already canonical), zero foreign minting. Out of scope: external hydration (194,965 bare IDs). Upstream of
  **H69**/**H67** (citation graph). Planning doc at `docs/tasks/active/h73-openalex-first-ingestion.md`.
  **Status (2026-06-21): slices 1 + 3 DONE + live-validated.** S1 (114s): works=11,656 w/ H63 corresponding on
  6,723, citers=105,766, backbone=24,232 ROR affiliations. S3 (citers full + pub→author→affiliation edges; bulk-mode
  canon ~30.6 min, was ~10h): 166,224 OpenAlex authors, 337,397 pub→author→affiliation + 247,743 author→affiliation
  edges → 21,032 ROR backbone institutions. Perf fixes: missing `orcidIds`/`openAlexAuthorIds` indexes +
  `doiNormalized` made non-unique (book-chapter container DOIs are legit) + in-memory author preload + batched
  affiliation inserts. **S3.5 DONE** (unit-tested): authorship edges batched (`batchUpsertAuthorshipEdges` +
  optional `correspondingKeys` set) — last per-record hot path closed; live timing TBD next bulk run. Follow-up:
  malformed-DOI normalize cleanup. **Next: slice 2** (Scopus afids resolve into the backbone).

- [ ] `H74` Pipeline reorder (forums → institutions+affiliations → pubs+authors). Structural change to the
  reconcile/rebuild chain ordering so canonicalization runs forums first, then institutions+affiliations (on the
  H73 ROR backbone), then publications+authors against the clean affiliation graph. Carved out of **H73** to isolate
  the chain-ordering risk; do after H73's additive import/ROR/DNA slices land and validate. Touches
  `ForumReconcileService` + the `rebuildAllDerived` step ordering. Planning doc TBD.

- [ ] `H70` Researcher onboarding wizard (+ de-tangle the publication-claim tool). Replace the confusing
  all-controls-at-once onboarding in the workspace **Profile & Sync** tab with a **resume-aware stepped modal**
  (Scopus ids → ORCID → confirm/deny affiliations → match Scholardex author record(s) → recommended bulk publication
  auto-claim), and **separate onboarding from ongoing claim management**. Root cause of today's confusion: a hidden
  gate (`requiresAffiliationScopeConfirmation`) welds affiliation setup to the claim tool. Login is **Keycloak SSO**;
  imports assume SSO. Decided: add `confirmedScholardexAuthorIds` (list) + keep `primaryScholardexAuthorId` as the
  designated one (multiple author records, non-destructive — no canonical merge from a user action); auto-claim
  confidence = **author-id + affiliation overlap only (ignore names — artefacts)**; **fix the claim-tool bugs**
  (re-sync orphaned decisions, bulk-rejects-if-any-decision, gate-throws-instead-of-guiding) as part of this. The
  staff import already resolves 51/55 by scopus id, so onboarding is mostly confirm + enrich + claim, not link.
  Planning doc at `docs/tasks/active/h70-researcher-onboarding-wizard.md`. Relates to **H20** (Google Scholar
  onboarding) and gates clean reporting before scoring (H69).
  **Status (2026-06-20): all 5 slices shipped + live-validated.** S1 model/resolve/step-engine (`14c24e5`);
  S2 wizard shell + steps 1–3 (`668c5c2`) + completeness-card/launcher fix (`e718df7`); S3 author-record matcher
  with confidence preview (`12dfe2c`); S4 publication auto-claim engine, terminal step (`e6084b4`); S5 claim-tool
  de-tangle — bulk idempotency + the gate now returns a 409 `requiresOnboarding` that routes to the wizard
  (`884dd1d`). Validated end-to-end on a real researcher (florin): ORCID→OpenAlex enrich, author match (18 pubs),
  auto-claim recommends all 18. **Deferred:** bug #1 (decision continuity across re-sync keyed on stable doi/eid,
  not the transient publicationId) — spans the decision model + lookups + filter, and DOI-primary identity keeps
  re-synced ids stable, largely mitigating it; pick up as a focused follow-up if it recurs.

- [ ] `H71` Cross-source author dedup for OpenAlex co-authors (affiliation-driven). Problem: the OpenAlex author
  resolver matches on **exact id keys only** (ORCID → OpenAlex author-id → else mint), so the same person splits into
  duplicates across sources — e.g. "Bogdan Tudor Tulbure" (OpenAlex, ORCID, no Scopus id) vs "Tulbure, Bogdan T."
  (Scopus, no ORCID), and OpenAlex itself splits a person across author-ids on preprint vs published versions. One
  researcher's by-ORCID sync brings co-author ORCIDs onto the **OpenAlex** records only, never onto the ORCID-less
  Scopus records, and the positional ORCID bridge only fires on DOI-**linked** pubs — so ORCID alone can't bridge.
  **Affiliation is the load-bearing signal.** Surfaced/amplified by the full-author fix (`2e4f0bf`), which mints all
  co-authors of OpenAlex-owned pubs.
  Plan: **(1) capture affiliation — DONE (`7261e08`)**: OpenAlex authorship institutions/raw-affiliation/country now
  persisted on `OpenAlexPublicationFact.AuthorRef` + accumulated onto `ScholardexAuthorFact.openAlexAffiliationNames`
  (mint + enrich). **Steps 2–3 SUPERSEDED by `H72`.** Investigation (2026-06-20) showed cross-source author dedup is
  gated on a clean *affiliation* graph, and the right first move is Scopus-internal (verified-tier cleanup), not
  OpenAlex name matching — see H72. The OpenAlex ROR/ORCID enrichment becomes H72 slice 3, onto a clean base.

- [ ] `H72` Scopus verified-tier entity resolution (drop ad-hoc affiliations, merge over-split authors). The canonical
  author/affiliation layer relabels Scopus 1:1 instead of resolving (29,106 affiliations, **0** ever merged; afid-keyed
  canonical ids structurally prevent merge; no affiliation dedup pass exists). Proven on live data that name/co-occurrence
  merging is unsafe (both directions leak — collaborations, hospital↔university, shared city/topic tokens, cross-language).
  The safe signal is Scopus's own **verified** tier (`afid ^60`, 57%, one record per real institution) vs the **ad-hoc**
  tier (`1xxxxxxxx`, 43% raw-string noise). **Slice 1:** mint canonical affiliations + edges only for verified afids, drop
  ad-hoc → 29,106 → 16,616 clean institutions, ~0 subject cost (1 UVT author of 2,559). **Slice 2:** merge same-person
  over-split AU-IDs (no droppable tier — Scopus over-splits old+new ids; 52 same-name groups / 106 AU-IDs at UVT) using
  *same name + shared verified affiliation* + same-pub hard-block; durable across rebuild; retire the scopus-id-on-profile
  band-aid. **Slice 3 (later):** OpenAlex ROR/ORCID enrichment onto the clean base (folds in H71 steps 2–3). Scopus-indexed
  signal routes via the forum (venue-in-Scopus-source), not per-pub. Plan + evidence:
  `docs/tasks/active/h72-scopus-verified-entity-resolution.md`.
  **Status (2026-06-21): slices 1–3 shipped + live-validated** (affiliations 29,106→16,427; 405 over-split authors
  merged; UVT ROR-tagged). **Slice-3 mechanism superseded by `H73`:** the positional `ScholardexAffiliationRorBridgeService`
  was noisy (cross-tagged e-Austria's ROR onto UVT) — H73 slice 2 replaces it with the OpenAlex-institution alias matcher
  and retires the bridge + clears the noisy `rorIds`. Ready to close out to `TASKS-done.md` once H73 slice 2 lands.

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

- [ ] `H69` Scoring rework for the multi-source canonical layer (after H66B Phase 4).
  Goal: rework the scoring services to fully consume the new multi-source signals now in the canonical layer —
  **OpenAlex** (citation graph, incoming `cited_by`, ISSN venue identity) and **DBLP** (CS conference identity +
  authoritative `conf/X` acronym, ~2,351 conferences resolved). The acronym already leads CORE matching
  (`ComputerScienceConferenceScoringService`, commit `64eed06`); the rest is unbuilt. Threads:
  **(1)** scorer **dispatch/routing review** — a DBLP-confirmed conference miscoded as a non-conference subtype
  (`ar`, or sitting on a proceedings-series forum) is routed to the wrong scorer; trust the `conf/X` forum / DBLP
  evidence to score it as a conference regardless of subtype, **without double-counting** (confirm how a pub is
  assigned to exactly one scorer first — the deferred half of "DBLP acronym wins").
  **(2)** citation-driven criteria — feed OpenAlex `cited_by` / the in-corpus citation graph into citation-count
  indicators and into **H67** (h-index); pick the citation source per domain.
  **(3)** forum **dedup** impact — ensure a conference resolved via DBLP `conf/X` and the same conference via a
  Scopus forum score identically (Tier-1 reconcile merges them).
  **(4)** **regression sweep** — re-score across domains after the match-all + acronym changes and confirm no
  score regressions vs the pre-Phase-4 baseline. Depends on H66B Phase 4 (done) + interacts with H67.

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

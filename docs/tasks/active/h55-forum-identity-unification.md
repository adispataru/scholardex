# H55 Forum identity unification (canonical forum id everywhere)

**Status:** Planning
**Created:** 2026-06-11

## Purpose

Make the canonical Scholardex forum the single identity for a journal/venue across the whole
system, so a forum is identified, stored, projected, and displayed exactly once — under its
canonical `sforum_…` id — instead of the current dual scheme (raw Scopus forum id for Scopus
forums, canonical id for WoS-derived forums).

## Why (the diagnosis that prompted this)

The `/forums` page shows duplicate entries for the same journal — e.g. *Computer Science Education*
(ISSN 0899-3408) appears twice, once `Journal` once `JOURNAL`, both "WoS indexed". Investigation:

- `/forums` reads the Postgres projection `reporting_read.scholardex_forum_view`
  (`PostgresScholardexProjectionReadPort.findAllForums`), not the Mongo canonical layer.
- That view is built from **two populations** (`ScholardexProjectionBuilderService`):
  1. one row per `scopus.forum_fact`, keyed by the raw Scopus `sourceId` (`toForumView(ScopusForumFact)`,
     ~14,954 rows), carrying Scopus's casing (`Journal`);
  2. one row per *WoS-only* canonical forum, keyed by `sforum_…` (`mergeWosOnlyForumViews`,
     ~25,701 rows), carrying the WoS default casing (`JOURNAL`, from `FORUM_DEFAULT_AGG`).
- `mergeWosOnlyForumViews` *does* dedup correctly on a clean full rebuild (it skips canonical forums
  that already carry a Scopus id). The duplicates come from **staleness**: the incremental path
  (`upsertForumRows`) never prunes, so when a forum migrates from WoS-only to Scopus-linked between
  rebuilds, the old `sforum_` row lingers next to the new Scopus-id row. The `Journal`/`JOURNAL`
  casing is just the fingerprint of the two build paths.
- The Mongo canonical layer is **correct** (one canonical forum per journal). The duplication is a
  stage-4 projection artifact.

The dual-key scheme exists because **the whole system identifies forums by Scopus id**:
- `ScopusFactBuilderService` sets publication `forumId = source_id` (Scopus forum id);
- `ScholardexPublicationCanonicalizationService` copies it verbatim (`fact.setForumId(scopusFact.getForumId())`),
  so canonical publications hold the raw Scopus forum id, not a canonical forum id;
- scoring/reporting resolve a publication's forum via `lookupPort.getForum(publication.getForumId())`
  against `scholardex_forum_view`.

## Key scoping findings (what makes this tractable, and what makes it a project)

- **Scoring services are INSULATED.** The ~15 journal-scoring services
  (`AISJournalScoringService`, `ImpactFactorJournalScoringService`, `EconomicsJournalScoringService`,
  `RISJournalScoringService`, `ComputerScience*`, `CNFIS*`, `CNCSIS*`, …) only call
  `lookupPort.getForum(forumId)` and then score off the returned forum's **ISSN/name** (rankings are
  looked up by ISSN, not by forum id). They need **no changes** as long as `publication.forumId` and
  `forum_view.id` stay consistent (both canonical).
- **There is NO Scopus→Scholardex forum canonicalization.** Forums become canonical *only* via WoS
  onboarding (`WosScholardexOnboardingService`). Of 25,701 canonical forums, 0 are Scopus-only; all
  are WoS-only (17,739) or WoS+Scopus (7,962). **6,991 Scopus forums have no canonical forum at all.**
  Projecting "canonical id only" today would make those 6,991 journals vanish from `/forums` and
  break forum resolution + scoring for every publication in them.

So the work is not a projection tweak — it is building the missing canonicalization so every forum
has a canonical id, then re-pointing references and re-deriving.

## Audit: is anything else left behind? (2026-06-11) — NO, forums only

Checked every derived/projected entity for the same dual-key/orphan pattern:

| Entity | Canonicalized | Orphans | Referenced by | Projection source | Verdict |
|---|---|---|---|---|---|
| Authors | 1:1 | 0 | canonical `sauth_` | `ScholardexAuthorFact` | single key ✓ |
| Affiliations | 1:1 | 0 | canonical `saff_` | `ScholardexAffiliationFact` | single key ✓ |
| Publications | yes | — | canonical `spub_` | `ScholardexPublicationFact` | single key ✓ |
| Citations | yes | — | canonical `spub_` (153,435/153,436) | `ScholardexCitationFact` | single key ✓ |
| Authorship / author-affiliation / pub-author-affiliation edges | yes | — | canonical `spub_`/`sauth_`/`saff_` | canonical facts | single key ✓ |
| **Forums** | WoS-only | **6,991** | **raw Scopus id** | `ScopusForumFact` + merge | **dual-key ✗** |

Counts: `scopus.author_facts` 216,470 = `scholardex.author_facts` 216,470 (0 orphans);
`scopus.affiliation_facts` 28,639 = `scholardex.affiliation_facts` 28,639 (0 orphans);
`scopus.forum_facts` 14,954 but only 7,963 linked into canonical forums → **6,991 orphans**.

**Forums are the only gap.** Every other entity has a dedicated canonicalization service that
canonicalizes every Scopus item 1:1 and is referenced/projected by canonical id. Forums never got a
`ScholardexForumCanonicalizationService`; they are canonicalized only as a side effect of WoS
onboarding. H55.1 supplies exactly the missing service that the other entities already have, so the
scope of H55 is complete and correctly bounded.

(Minor, unrelated: one stray `scholardex.citation_facts` row has `citedPublicationId = citingPublicationId = "p9"`
— a degenerate/test-cruft self-edge, 1 of 153,436; negligible, clean up opportunistically.)

## Target architecture

- A canonical Scholardex forum exists for **every** forum (Scopus, WoS, user-defined). Scopus-only
  forums get a canonical forum too.
- Canonical publications reference forums by **canonical forum id** (`forumId = sforum_…`), resolved
  at canonicalization time from the Scopus forum id via the FORUM source link.
- `scholardex_forum_view` is projected **only** from canonical Scholardex forums — one row per
  forum, keyed by `sforum_…`. The per-Scopus-forum population is removed.
- `lookupPort.getForum(canonicalForumId)` resolves against the canonical-keyed view; scoring is
  unchanged because it consumes the resolved forum's ISSN/name.
- Casing becomes a non-issue (one row per journal); optionally normalize `FORUM_DEFAULT_AGG` to
  `Journal` to match Scopus.

## Proposed slicing

- **H55.1** — Scopus-forum canonicalization. Build canonical Scholardex forums for all Scopus forums
  (extend WoS onboarding's forum-canonicalization, or add a `ScopusForumCanonicalizationService`),
  deterministically keyed and linked via FORUM source links. Backfill the 6,991 orphan Scopus
  forums. Order it before publication canonicalization in the build pipeline.
  **DONE (2026-06-11).** Implemented as `WosScholardexOnboardingService.runScopusForumCanonicalization`
  (chosen over a parallel service so the tested forum-identity helpers — `findCanonicalCandidates`,
  `buildCanonicalForumId`, `normalizedIssnSet`, `persistForumOrRecordConflict`, `openConflict`,
  `upsertLinkedSourceLink` — are reused, not duplicated). Symmetric to `runWosOnboarding`:
  - Scopus forums already folded into a canonical forum by WoS onboarding (their `sourceId` already
    in some canonical's `scopusForumIds`) are **not** re-merged — only a `FORUM/SCOPUS` source link is
    ensured (the resolution surface H55.2 needs). No mutation of existing WoS forum facts.
  - Orphans resolve by ISSN/name against existing canonical forums (deduping orphans that share an
    ISSN), else mint a new canonical forum via `mergeForumFromScopus` (additive: contributes the
    Scopus id + fills missing ISSN/name/agg, never clears prior values; id from ISSN, or name+agg when
    ISSN-less). All paths create the `FORUM/SCOPUS` source link.
  - Deterministic: Scopus forums processed sorted by `sourceId`; the source-link batch id is the fixed
    label `scopus-forum-canonicalization` (not a UUID) so rebuilds stay byte-identical.
  - Wired into `ScopusBigBangMigrationService` **before** publication canonicalization in all three
    build paths (`runBuildFactsStep`, `runIncrementalUploadBuildStep`, `runFull`). On a clean rebuild
    this now creates the canonical forum population scopus-first; WoS onboarding (later pipeline) folds
    WoS journals into the existing canonical forums by ISSN/name (convergent ids). Behaviour-additive:
    nothing consumes the new `FORUM/SCOPUS` links until H55.2, so publications still hold the raw
    Scopus forum id for now.
  - Tests: 3 new unit tests in `WosScholardexOnboardingServiceTest` (orphan create + link;
    already-linked link-only/no-save; two-orphan ISSN dedup → one canonical id). `ScopusBigBangMigration
    ServiceTest` updated for the new constructor dependency + forum step. Both classes green.
  - Verification still owed (deferred to H55.4): full-dataset rebuild proving the 6,991 orphans get
    canonical forums and that scopus-first + WoS-enrich reproduces the same canonical forum set.
- **H55.2** — Re-point publication `forumId` to the canonical forum id. In
  `ScholardexPublicationCanonicalizationService` (and `UserDefinedCanonicalizationService`), resolve
  the Scopus forum id → canonical forum id via the source link and store that. Handle the
  unresolved case explicitly (must not silently null the forum).
  **DONE (2026-06-11).**
  - `ScholardexPublicationCanonicalizationService.applyCanonicalPublicationFields` now stores
    `resolveCanonicalForumId(scopusFact.getSource(), scopusFact.getForumId(), context)` instead of the
    raw `scopusFact.getForumId()`. New helper resolves via the `FORUM` source-link cache (FORUM/source
    then FORUM/SCOPUS fallback, mirroring `resolveAffiliationSourceLink`).
  - **Critical:** `buildCanonicalPublicationId` (the `spub_` id) still receives the **raw** Scopus
    forum id — forumId feeds the id only in the title-only fallback branch, so re-keying it would
    change publication ids and break citations/edges. Only the *stored* `forumId` field is re-pointed;
    the id derivation is untouched. (Covered by the existing replay-idempotency test.)
  - Unresolved handling: blank → null (legitimate, no forum); already-`sforum_` → passthrough;
    non-blank unresolved → **keep the raw id** + `log.warn` (never silently null). H55.1 guarantees
    coverage so this should be 0; H55.4 must confirm.
  - FORUM source links are preloaded per chunk (`preloadSourceLinks(FORUM, …)` in
    `preloadChunkContext`) so resolution is a cache hit, not a per-publication DB round trip.
  - `UserDefinedCanonicalizationService` **needs no change** — it already canonicalizes forums
    (`canonicalizeForums`) and resolves publication `forumId` to `sforum_` ids.
  - Tests: 2 new (`…ResolvesRawScopusForumIdToCanonicalForumId`,
    `…KeepsRawForumIdWhenUnresolvedRatherThanNulling`); the existing `sforum_1` passthrough assertion
    still holds. Green.
  - **⚠ Must ship with H55.3.** After H55.2 alone, publications point at canonical `sforum_` ids but
    `scholardex_forum_view` still keys Scopus-linked forums by raw Scopus id (the canonical row is
    skipped by `mergeWosOnlyForumViews` when the forum has Scopus ids) → `lookupPort.getForum(canonical)`
    misses → forum resolution + scoring break in the gap. Do **not** run a rebuild/projection between
    H55.2 and H55.3.
- **H55.3** — Project `scholardex_forum_view` from canonical forums only; remove the
  per-Scopus-forum population and the now-redundant `mergeWosOnlyForumViews` special-casing. Ensure
  both full and incremental projection paths produce exactly one row per canonical forum.
  **DONE (2026-06-11).**
  - `ScholardexProjectionBuilderService`: `toForumView(ScopusForumFact)` + `mergeWosOnlyForumViews`
    are replaced by a single `buildCanonicalForumViews()` / `toCanonicalForumView(ScholardexForumFact)`
    that emits exactly one row per canonical forum, keyed by its `sforum_` id. The dual population
    (raw-Scopus-id rows + WoS-only-canonical merge) is gone.
  - **Full rebuild** truncates + inserts the canonical forum set (single key, no duplicates possible).
  - **Incremental/batch** path now refreshes the *whole* canonical forum set via
    `canonicalForumFactRepository.findAll()` and upserts it (forums are ~25k rows — cheap), instead of
    the per-Scopus-batch slice that never pruned. This directly kills the staleness that produced the
    duplicate `/forums` rows (the original diagnosis): there is no longer a second key to go stale.
  - The Scopus `forumFactRepository` (`ScopusForumFactRepository`) is no longer used by the projection
    builder. Left injected (unused) to avoid editing ~19 test constructor call sites in this slice;
    flagged for opportunistic removal.
  - Tests: migrated `ScholardexProjectionBuilderServiceTest` forum cases off the Scopus-forum path onto
    canonical facts (`toCanonicalForumView`, canonical `findAll`); the former skip-Scopus-linked test
    becomes `rebuildViewsProjectsEveryCanonicalForumOnceSortedByIdIncludingScopusLinked` (3 forums, one
    row each); batch test asserts forums refresh globally from canonical while author/affiliation/
    publication stay batch-scoped. Full class green; whole test tree compiles.
  - Coupling with H55.2 is now satisfied: publications carry canonical `forumId` (H55.2) and the view
    is canonical-keyed (H55.3), so `lookupPort.getForum(canonicalId)` resolves. Safe to rebuild now —
    H55.4 verifies end-to-end.
- **H55.4** — Full re-canonicalize + re-project; verify. Scoring parity: a sample of indicator
  scores before/after must match (the resolved forum is the same journal, so scores must not move).
  `/forums` shows one row per journal. No orphan rows.
  **DONE (2026-06-11) — ran on the local `test`/`core` DBs via `agent-dev` on :8181.**
  Sequence: `/scopus/buildFacts?useCheckpoint=false` (forum canon → publication re-pointing → all
  canonical, 0 errors) then `/scopus/buildProjections`.

  Results vs baseline:
  | Metric | Before | After |
  |---|---|---|
  | `scholardex.forum_facts` | 25,701 | **32,524** (+6,823 orphan canonicals) |
  | FORUM/SCOPUS source links | 0 | **14,954** (one per Scopus forum) |
  | `forum_view` rows | 40,655 (dual-key) | **32,524 (0 raw-Scopus-keyed)** |
  | publications on canonical `sforum_` id | 0 | **90,664 / 92,654 (97.8%)** |

  - Forum canon step: processed 14,954, imported 6,823, skipped 7,966 (already-linked, link-only),
    0 errors.
  - **Original complaint fixed:** *Computer Science Education* (0899-3408) now appears exactly once
    (`sforum_cd9d…`, agg `Journal`); the `Journal`/`JOURNAL` dual-key duplicate is gone.
  - **Score parity (engine-free proof):** of the 14,951 resolved FORUM/SCOPUS links, 10,681/10,682
    checkable ones map to a canonical forum **carrying the same journal ISSN** (4,269 Scopus forums
    have no ISSN → resolved by name; 1 mismatch is a *pre-existing* bad merge of forums 20954+14102,
    2 pubs, not caused by H55). So re-pointed publications resolve to the same journal → indicator
    scores cannot move.

  **Two issues surfaced (both PRE-EXISTING canonical-layer duplication, not regressions) — feed H55.5:**
  1. **1,990 publications (3 journals) left on raw forum ids.** Forums 13903 (Cellular and Molecular
     Life Sciences), 27545 (European Physical Journal C), 28540 (SIAM Journal on Computing) hit
     `AMBIGUOUS_ISSN_MATCH`: each Scopus forum's print+electronic ISSN matched **two** canonical
     forums → conflict opened, raw id kept (the fail-loud contract; not silently nulled).
  2. **49 ISSN groups / 98 `forum_view` rows are still duplicates** — the same journal split across two
     `sforum_` ids (full-name vs abbreviated-name, e.g. "Respiratory Research"/"RESP RES"), because
     `buildCanonicalForumId` keys on the full ISSN *set* and WoS rows differed in ISSN completeness
     (print-only vs print+electronic → different hash). H55.3 removed the dual-key duplicates but
     cannot collapse genuine duplicate canonical facts.

  - **Determinism:** not re-run end-to-end here (another ~20 min); structurally ensured (sorted
    processing order + fixed `scopus-forum-canonicalization` batch label) and covered by the H54.7
    determinism integration test. Re-run before close if desired.

- **H55.5 (NEW) — Canonical-forum deduplication. IMPLEMENTED + LIVE-VERIFIED 2026-06-11.**

  Live rebuild on the `test`/`core` DBs (`buildFacts?useCheckpoint=false` → `buildProjections`):
  - Dedup step: **75 clusters → 70 merged, 5 quarantined, 70 forums deleted, 0 errors.**
  - `scholardex.forum_facts` 32,524 → **32,454** (−70). Canonical primary-ISSN duplicate groups **49 → 0**;
    `scholardex_forum_view` duplicate-ISSN groups **0** (32,454 rows, 0 raw-Scopus-keyed).
  - **0 dangling `sforum_` publication references** (clean loser→winner re-pointing; losers had 0 pubs);
    `forum_id` dangling stays at exactly the 1,990 raw-id pubs (the 3 quarantined ambiguous journals),
    unchanged from H55.4.
  - **5 `FORUM_DEDUP_NAME_MISMATCH` conflicts OPEN on `/admin/conflicts`** (each 2 candidates ⇒ "needs
    review"): The BMJ/BRIT MED J, Eur Phys J C/Zeitschrift, Cell Mol Life Sci/Experientia, Int J COPD/
    …Chronic Obstructive Pulmonary Disease, SIAM J Comput/SIAM J Math Analysis.
  - **Score parity: byte-identical to H55.4** — ISSN-preservation 10,681/10,682 resolved links carry the
    same journal ISSN (4,269 no-ISSN name-resolved, 1 pre-existing mismatch). No publication's resolved
    journal moved ⇒ indicator scores cannot change.
  - Residual (by design): the 1,990 pubs on the 3 quarantined ambiguous journals stay on raw ids pending
    operator resolution of those conflicts (2 are continuations, 1 the SIAM data error).


  Delivered: `ScholardexForumDeduplicationService.deduplicateForums(batchId, correlationId)` — clusters
  canonical forums by shared normalised ISSN token (union-find), then per cluster applies the validated
  safe rule (**merge iff members share the primary print ISSN OR their names are an abbreviation/
  expansion match**). Merge picks a deterministic winner (most Scopus links → most ISSN tokens →
  smallest id), unions `scopusForumIds`/`wosForumIds`/`googleScholarForumIds`/`userSourceForumIds`,
  accumulates every distinct ISSN/eISSN as an **alias retaining its original hyphenated string**
  (de-duplicated by normalised form; winner's own primary/eISSN excluded), re-points loser source links
  (`FORUM/*` `canonicalEntityId`) and any publication `forumId` to the winner, then deletes the losers.
  Name-mismatch / eIssn-only clusters are **quarantined**: an `FORUM_DEDUP_NAME_MISMATCH` identity
  conflict is opened carrying the cluster's canonical ids as `candidateCanonicalIds` (≥2 ⇒ "needs
  review" ⇒ visible on `/admin/conflicts`, filterable `entityType=FORUM`).
  Wired into `ScopusBigBangMigrationService` as `scholardex-forum-dedup`, running **before**
  `scholardex-forum-canonicalization` in all three build paths so existing duplicate canonicals collapse
  before the canon resolves Scopus forums.
  Tests: `ScholardexForumDeduplicationServiceTest` (same-primary merge + reference re-point;
  abbreviation merge across an eISSN bridge; SIAM-style quarantine → conflict, no merge; singleton
  no-op); `ScopusBigBangMigrationServiceTest` + `PostgresReportingProjectionServiceIntegrationTest`
  updated for the new dependency / canonical forum seed. Full application + scopus suites green.
  **Expected on the live rebuild:** `/forums` duplicate ISSN groups 75 → ~5 (the 70 WoS-only
  zero-publication losers merge with no scoring impact); the ~5 quarantined clusters (incl. the 3 that
  block 1,990 pubs — 2 continuations + SIAM) surface on the conflicts page for human resolution; those
  1,990 pubs remain on raw ids until an operator resolves the conflicts (continuation handling is a
  later refinement). **Original scoping below.**


  **Problem (measured on the live `test` DB after H55.4).** Connected-component analysis over canonical
  forums sharing any normalised ISSN token (issn/eIssn/alias) finds **75 clusters, every one a pair
  (150 forums total)**. Each pair is the *same journal* split across two `sforum_` ids because
  `buildCanonicalForumId` hashes the full ISSN *set* and the two WoS ranking rows for the journal
  carried different ISSN completeness (e.g. `{1572-6657,0022-0728}` vs `{1572-6657,1873-2569}` →
  different hash). Canonical example: `Journal of Electroanalytical Chemistry` (full name, Scopus+WoS
  linked) vs `J ELECTROANAL CHEM` (abbreviation, WoS-only). Both are pre-existing WoS-onboarding
  artifacts; H55 only surfaced them.

  **What makes it tractable:**
  - 73/75 pairs: exactly one member is Scopus-linked (`scopusForumIds` non-empty) and the other is
    WoS-only. **The WoS-only members have 0 publications** (publications only ever point at the
    Scopus-linked member). So 73 merges need *no publication re-pointing*.
  - 2/75 pairs are "both-Scopus" — need explicit winner-selection.
  - The 3 `AMBIGUOUS_ISSN_MATCH` conflicts from H55.4 (forums 13903/27545/28540, 1,990 pubs) are the
    same phenomenon: the Scopus forum's ISSNs matched both members of a pair. Merging the pair makes
    the match unique → those forums canonicalize and their 1,990 pubs re-point.

  **Proposed approach** — a deterministic dedup pass (new `ScholardexForumDeduplicationService`, or a
  merge step folded into forum canonicalization), run before publication canonicalization:
  1. Compute clusters of canonical forums sharing any normalised ISSN token (union-find).
  2. Per multi-forum cluster pick a deterministic **winner**: prefer `scopusForumIds` non-empty; then
     most ISSN tokens; then lexicographically smallest id (order-independent for determinism).
  3. Merge losers → winner: union `scopusForumIds`/`wosForumIds`/`aliasIssns`; fold loser primary
     issn/eIssn into winner aliases; keep winner primary issn/eIssn; prefer the non-abbreviated name.
  4. Re-point references loser→winner: `scholardex.source_links` (FORUM/* `canonicalEntityId`, honour
     the `uniq_scholardex_source_link` key), `scholardex.publication_facts.forumId` (0 today, handle
     generally), then rebuild projections.
  5. Delete loser canonical forums.
  6. Re-derive + re-verify: forum canon (3 conflicts now resolve) → publication re-point → projections;
     assert 0 ISSN-duplicate `forum_view` groups, 0 unresolved forums, ISSN-preservation parity holds.

  **Durable prevention (decide in H55.5 design):** the recurrence root cause is `buildCanonicalForumId`
  keying on the full ISSN set, so the same journal hashes differently when ISSN completeness varies.
  Options: (a) keep the dedup pass as a converging safety net each rebuild; (b) re-key forum ids on a
  single normalised "primary" ISSN (durable, but re-keys all forums — high blast radius); (c) make
  onboarding/canonicalization always merge on *any* shared ISSN token (investigate why
  `findCanonicalCandidates` didn't merge these originally). Lean (a)+(c).

  **Risk:** low — losers are WoS-only with 0 publications; their only references are WoS source links
  (re-pointed) and one `forum_view` row (rebuilt). Merging changes loser ids, but nothing external
  depends on them. Determinism hinges on order-independent winner selection.

  **Clustering-safety dry-run (2026-06-11) — DO NOT merge on shared token alone.** Union-find over
  *any* shared ISSN token (issn/eIssn/alias) yields 75 pairs, but that scheme **over-merges different
  journals**: e.g. *SIAM J. Computing* `[0097-5397/1095-7111]` and *SIAM J. Mathematical Analysis*
  `[0036-1410/1095-7111]` cluster only because Math Analysis carries a **mislabeled eISSN `1095-7111`**
  (which actually belongs to Computing; its real eISSN `1095-7154` is in its aliases). This is also why
  Scopus forum 28540 is `AMBIGUOUS_ISSN_MATCH`. Comparison of clustering bases:
  - By **primary (print) ISSN**: 49 clusters, all size-2, no transitive chains, every one a confirmed
    same-journal full-name/abbreviation pair → **safe to auto-merge**.
  - By **any token**: 75 clusters; the extra 26 are bridged only by a shared eISSN/alias with a
    *different* primary ISSN. Of those: ~21 are still the same journal (abbreviation keyed on the
    electronic ISSN), 2 are continuations (*Experientia*→*Cell Mol Life Sci*, *Zeitschrift für Physik
    C*→*Eur Phys J C*), and ≥1 is genuinely different journals (SIAM, a data error).

  **Safe merge rule (validated): auto-merge a cluster iff members share the primary ISSN OR their names
  are an abbreviation/expansion match.** This auto-merges **70** clusters and routes **5** to manual
  review (eIssn-bridged + name mismatch): *The BMJ*/*BRIT MED J* (same, initialism), *Int J COPD*/
  *…Chronic Obstructive Pulmonary Disease* (same, acronym), the 2 continuations, and SIAM (genuinely
  different — must NOT merge; fix its eISSN). The genuine error lands in review, not auto-merge.
  Per-merge, accumulate all distinct ISSNs/eISSNs as aliases (the user-requested alias retention),
  exactly as names are retained. Name-mismatch/eIssn-only clusters stay as `AMBIGUOUS_ISSN_MATCH`
  conflicts for human resolution rather than being auto-collapsed.

  **Wide data-quality scan (2026-06-11) — full 32,524 canonical + 14,954 source forums.** Confirms the
  cross-journal merge hazard is essentially a single case and the data is cleaner than the SIAM example
  implied:
  - **Cross-journal token collisions are rare/benign.** Canonical: 7 colliding tokens — only SIAM is
    genuinely different journals; the rest are name-heuristic false flags (*BMJ* initialism, *COPD*
    acronym, *Ceramic Soc. Japan* jpn/japan) or continuations. Source: 11 — ~9 are journal
    **continuations** (renamed/translated titles legitimately sharing an ISSN across eras: Zeitschrift
    für Physik C→Eur Phys J C, Experientia→Cell Mol Life Sci, J Soviet Math→J Math Sci, Liebigs
    Annalen→Eur J Org Chem, Fresenius'→Anal Bioanal Chem, USSR Bulletin→Russian Chem Bull, …), plus
    SIAM and 1 empty-name proceedings. **All continuations differ on primary ISSN**, so the safe rule
    keeps them separate (manual review) — correct.
  - **Genuine source data errors (orthogonal to the merge):** 1 misassigned eISSN (SIAM Math Analysis
    holds `1095-7111`, which is Computing's eISSN; its own is `1095-7154`) — a *valid* ISSN on the
    *wrong* journal, invisible to validity checks; and ~8 check-digit-invalid ISSNs that are real typos
    (e.g. Radical Philosophy `0030-211X` → `0300-211X`). Worth a separate source-data cleanup/flag.
  - **`issn == eISSN` on 1,195 canonical forums** — benign normalization artifact (single-ISSN journals
    duplicated the value into both fields); not collisions. Optional cleanup: null `eIssn` when equal.

  Net: the validated safe rule introduces **no** risk of merging different journals across the full
  dataset. Continuations and the SIAM error are quarantined to the ~5-cluster manual-review queue.

- **H55.6 (NEW) — Primary-ISSN disambiguation in forum canonicalization. IMPLEMENTED + LIVE-VERIFIED
  2026-06-11.** Recovers the 1,990 publications H55.5 left on raw ids (3 journals: European Physical
  Journal C 1,987, Cell Mol Life Sci 2, SIAM J Computing 1), each ambiguous only because its Scopus
  forum shares an *eISSN* with a sibling/continuation/erroneous canonical.
  - Change: in `WosScholardexOnboardingService.upsertForumFromScopus`, when `findCanonicalCandidates`
    returns >1, narrow to the candidate(s) carrying the Scopus forum's **primary print ISSN**; if exactly
    one, link it; else fall through to the existing conflict. (Dry-run validated: 8/8 ambiguous forums
    resolve to the correct journal, 1,990/1,990 pubs recovered, 0 residual ambiguity, 0 wrong picks.)
  - **Bug found during live verify (the unit test masked it):** the disambiguated `link()` was silently
    rejected on re-runs because the prior ambiguous run had left a `CONFLICT`-state FORUM/SCOPUS source
    link, and `isTransitionAllowed(CONFLICT, LINKED)` is only true with `explicitReplayAttempt=true`,
    while `upsertLinkedSourceLink` passed `false`. Fix: `upsertLinkedSourceLink` now takes an
    `explicitReplayAttempt` flag — **forum** links pass `true` (a definitive resolution must override a
    stale conflict), **publication** links keep `false` (must not bypass their own collision quarantine).
  - Live verification (rebuild on `test`/`core`): "Unresolved canonical forum" warnings **1,990 → 0**;
    all 3 forum links now `LINKED` to the correct primary-ISSN canonical; **`pub_forum_id_raw` 1,990 →
    0**, all 92,654 publications on canonical ids; `pubs_forum_id_DANGLING` 1,990 → 0; ISSN-preservation
    parity 10,681 → **10,684** (the 3 now correct; the 1 residual mismatch is the unchanged pre-existing
    forum-20954 bad merge); `forum_view` duplicate-ISSN groups stay 0.
  - **Stale-conflict auto-close (DONE 2026-06-12).** `upsertForumFromScopus` now calls
    `resolveOpenForumAmbiguityConflict(sourceRecordId)` at all three link success points (already-folded,
    existing-link re-merge, and the post-disambiguation single-candidate path): it marks any open
    `AMBIGUOUS_ISSN_MATCH`/`AMBIGUOUS_NAME_AGG` FORUM/SCOPUS conflict `RESOLVED` (resolvedBy
    `scopus-forum-canonicalization`) so it stops showing on `/admin/conflicts`. Unit-tested. The 3
    pre-existing stale records were also closed one-time in the live DB (each verified `LINKED` first) →
    **0 open `AMBIGUOUS_ISSN_MATCH` forum conflicts.** The 5 `FORUM_DEDUP_NAME_MISMATCH` conflicts
    correctly remain open (the continuation/SIAM canonical pairs still exist as separate forums for
    human review).

- (Optional) normalize `FORUM_DEFAULT_AGG` casing.

## Risks / verification

- **Scoring regression is the top risk.** Re-pointing `forumId` must preserve forum resolution for
  every publication. Verify with score parity on a representative indicator set (before/after the
  re-derive) — not just row counts. Lean on the existing scoring-service tests + a new forum-resolution
  determinism check.
- **Unresolved forum ids**: a publication whose Scopus forum has no canonical forum must be handled
  (H55.1 should guarantee coverage; H55.2 must fail loud, not null silently).
- **Projection cutover**: removing the Scopus-id rows is a breaking change to `forum_view` — must
  ship together with H55.2 (publications re-pointed) or forum resolution breaks in the gap.
- Reuses the H54.7 determinism harness + `docs/rebuild-runbook.md`: rebuild twice → identical, and a
  before/after score sample.

## Alternative considered (and rejected for this task)

A targeted **prune-orphans** fix (keep the dual-key scheme, but make the projection prune stale forum
rows so the view is always one-row-per-journal) fixes the duplicates with no new subsystem and no
scoring risk. It was considered and set aside in favor of the structural single-canonical-key model;
it remains the fallback if H55 proves too costly.

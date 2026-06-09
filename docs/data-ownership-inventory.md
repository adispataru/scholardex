# Data Ownership & Classification Inventory

**Status:** Living reference
**Created:** 2026-06-09
**Origin:** H54.1 (ingestion pipeline & record-keeping rebuild)

## Headline finding: the dev Mongo is a shared, multi-app database

The local `test` Mongo instance is **not** owned solely by this (`core`) application. It
contains data for at least three independent applications plus legacy data from this
app's own earlier schema versions. Of ~95 collections present, **this app owns 62**
(declared via `@Document` / `MongoRepository<…>`); the rest belong to sibling apps or are
orphaned.

### Hard safety rule for all of H54 (and any wipe/rebuild work)

> **Wipe and rebuild operations must operate on an explicit allow-list of THIS app's
> owned collections. Never `db.dropDatabase()`, never drop by namespace prefix, never
> "wipe everything derived."**

Dropping by database or by prefix would destroy the curriculum app (`planuri.*`), the
skills/occupation taxonomy, and the exam system — none of which this codebase can
recreate. The `PipelineRebuildService` (H54.6) must hold a hardcoded owned-collection
allow-list and refuse anything outside it.

How ownership was determined: a collection is **owned** iff this repo declares it via an
`@Document` model that is reachable through a `MongoRepository<Entity, _>`. Verified there
is **zero** `MongoTemplate.getCollection("…")` access to any bare/foreign collection name,
so the repository map is authoritative.

---

## Tier 1 — OWNED, current, in use (53 collections)

### 1a. Precious — human/operationally authored, NOT reconstructable. **Back up.**

| Collection | Count | Notes |
|---|---|---|
| `indicators` | 42 | Indicator definitions. Core authored config. |
| `individualReports` | 5 | Report definitions. |
| `groupReports` | 1 | Report definitions. |
| `scholardex.groups` | 5 | Evaluation groups. |
| `groupIndividualReportRuns` | 2 | Group run config/state. |
| `institutions` | 1 | Seed/config. |
| `domains` | 9 | Seed/config. |
| `scholardex.users` | 101 | Auth accounts / researcher linkage. |
| `scholardex.departments` | 4 | Org structure. |
| `scholardex.department_affiliations` | 7 | Org structure. |
| `scholardex.org_divisions` | 3 | Org structure. |
| `scholardex.memberships` | 14 | Org structure. |
| `scholardex.division_report_selections` | 2 | Org reporting config. |
| `scholardex.department_report_hides` | 0 | Org reporting config. |
| `scholardex.workspacePreferences` | 5 | User preferences. |
| `scholardex.publication_authorship_decisions` | 71 | **Human authorship decisions.** Irreplaceable. |

### 1b. Reference — loaded from files in `data/`. Rebuildable via `general.init.*`. **Do not back up; retain the source files.**

| Collection | Count | Source (config key) |
|---|---|---|
| `coreConferenceRanking` | 2,234 | `data/core-conf` (`general.init.core-conference.folder`) |
| `senseRankings` | 798 | `data/sense/SENSE-rankings.xlsx` (`general.init.sense.file`) |
| `urap.rankings` | 3,562 | `data/urap-univ` (`general.init.urap.folder`) |
| `scholardex.cncsisList` | 209 | `data/cncsis/publisher_list.xlsx` (`general.init.cncsis.file`) |
| `scholardex.publication_dblp_evidence` | 365 | DBLP dump (`general.init.dblp.file`) |

### 1c. Derived — deterministic function of source files/APIs. **Do not back up; rebuild.**

Bibliometric pipeline (the H54 stages): `scopus.*_facts` (publication 92,694 / author
216,470 / affiliation 28,639 / citation 153,537 / forum 14,954 / funding 26,914),
`scopus.import_events` (currently absent — wiped 2026-06-08), `wos.*` (category 797,827 /
metric 596,601 / journal_identity 25,871 / import_events 684,493 / fact_conflicts 721,153
/ identity_conflicts 1 / build checkpoints), `scholardex.*` (source_links 2,183,437 /
authorship_facts 658,740 / publication_author_affiliation_facts 737,045 /
author_affiliation_facts 271,735 / author_facts 216,470 / citation_facts 153,431 /
publication_facts 92,652 / affiliation_facts 28,639 / forum_facts 25,701 /
identity_conflicts 3,112 / publication_link_conflicts / canonical_build_checkpoints /
projection_dirty_markers / import_run_metrics / tasks.*), `user_defined.*`,
`userIndicatorResults` (absent — wiped 2026-06-08), `activities` (18), `activityInstances`
(20).

**VERIFY items — RESOLVED 2026-06-09 (all precious; moved out of derived):**
- `scholardex.artisticEvent` (303) — admin-curated catalog (`AdminCatalogFacade` /
  `ArtisticEventsService`), not pipeline-derived. → **precious (config)**.
- `activities` (18) — admin-defined activity types (`ActivityManagementFacade` /
  `AdminCatalogFacade`). → **precious (config)**.
- `activityInstances` (20) — user-entered activity records (`UserActivityInstanceFacade`).
  → **precious (personal data)**.
- `userIndividualReportRuns` (26) — persisted historical runs users export/reference; a
  recomputation would not reproduce a past run's exact state. → **precious (personal data)**.

---

## Tier 2 — OWNED, declared but absent in DB (9)

`cnatdcu.editor`, `scholardex.grants`, `scholardex.import_run_metrics`,
`scholardex.projection_dirty_markers`, `scholardex.publication_link_conflicts`,
`scopus.import_events`, `userIndicatorResults`, `user_defined.forum_facts`,
`user_defined.publication_facts`. (The last three are expected — wiped or never exercised.
Will be (re)created on next write with `auto-index-creation` on.)

---

## Tier 3 — OWNED, legacy/orphaned from earlier schema. **Drop candidates (this app's own junk).**

A prior migration (the `ScopusBigBangMigrationService`) moved Scopus data from a flat
`scopus.{authors,publications,…}` schema to the current `scopus.*_facts` schema and left
the old collections behind. ~470k orphaned docs no current code reads:

| Collection | Count | |
|---|---|---|
| `scopus.authors` | 210,517 | superseded by `scopus.author_facts` |
| `scopus.citations` | 132,850 | superseded by `scopus.citation_facts` |
| `scopus.publications` | 79,968 | superseded by `scopus.publication_facts` |
| `scopus.funding` | 31,232 | superseded by `scopus.funding_facts` |
| `scopus.affiliations` | 28,066 | superseded by `scopus.affiliation_facts` |
| `scopus.forums` | 14,601 | superseded by `scopus.forum_facts` |
| `scopus.*_search_view`, `scopus.*_touch_queue` | 0 | old projection/queue scaffolding |
| `scopus.publications2025`, `scopus.citations2025` | 3, 3 | ad-hoc leftovers |
| `schodardex.tasks.scopusCitationsUpdate` | 1 | **typo namespace** (`schodardex` ≠ `scholardex`) |
| `schodardex.tasks.scopusPublicationUpdate` | 1 | typo namespace |
| `wos.rankings` | 2 | no model |
| `scholardex.researchers` | 94 | no repository; app uses `scholardex.users` — verify before drop |

These are safe to drop once confirmed unreferenced (the search confirms current code does
not read them), but treat as a deliberate cleanup, not part of pipeline rebuild.

---

## Tier 4 — FOREIGN (other apps sharing this dev DB). **NEVER touch.**

This codebase has no models, repositories, or loaders for any of these. They cannot be
rebuilt from here and must be excluded from every wipe/rebuild allow-list.

- **Curriculum app** (`planuri.*`): `planuri.student` (1,354), `planuri.subject` (2,192),
  `planuri.learningOutcome` (2,484), `planuri.teacher`, `planuri.plan`,
  `planuri.thesisAssignment`, `planuri.competence`, `planuri.faculty`, `planuri.department`,
  and ~30 more `planuri.*` collections.
- **Skills/occupation taxonomy** (ESCO/ISCO-style): `occupationSkillRelation` (129,008),
  `broaderRelationSkillPillar` (20,822), `skills` (13,939), `skillSkillRelation` (5,818),
  `broaderRelationOccPillar` (3,652), `occupations` (3,041), `skillGroup` (640),
  `iscoGroups` (619), `conceptSchemes`, `researchOccupation`, `aracisQualificationCompetences`,
  `romanianStudyFieldClassifications`, `learningOutcomeTaxonomyVerbs`, `skillsHierarchy`.
- **Exam/submission app**: `exam_attempts`, `submissions` (71), `best_step_submissions`,
  `steps` (1,771), `lessons` (279), `courses` (12), `questions`, `votes`, `polls`,
  `code_runs`.
- **Bare legacy/foreign**: `ranking` (22,094), `citation` (6,499), `researcher` (198),
  `user` (59), `publication` (21), `venue` (18), `groups` (2), `criteria` (9),
  `user_profiles` (4), `database_sequences`.

> Note: `RankingViewController` references the strings `"ranking"`, `"publication"`,
> `"citation"` — these are Thymeleaf **model attribute names**, not Mongo collections.
> Confirmed no collection access.

---

## Cleanup opportunities surfaced (not blockers)

- **`wos.fact_conflicts` (721,153 docs)** — owned and derived, but suspiciously large for a
  conflict/observability table. Review whether it accumulates without bound (same anti-pattern
  as the `scopus.import_events` duplication). Candidate for a TTL or rebuild-on-demand policy.
- **Legacy Scopus namespace (~470k docs, Tier 3)** — drop after confirming unreferenced.
- **Backups taken 2026-06-08** (`userIndicatorResults`, `scopus.import_events`, 658 MB under
  `data/backups/`) are point-in-time only; the source files are the authoritative backup.
  `data/backups/` is already git-ignored (covered by `data/*`).

## Precious-snapshot scope (for the H54.1 backup artifact)

Snapshot Tier 1a (+ any VERIFY items confirmed precious). That is a few hundred documents
total — small enough to dump to git-tracked JSON on change. Everything in 1b/1c/3 is
rebuildable or disposable; Tier 4 is out of scope entirely.

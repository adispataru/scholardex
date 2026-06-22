# H71 — Cross-source author reconcile in V2 (affiliation + co-author, adaptive)

Fold the deferred fuzzy/over-split + cross-source author dedup into the V2 in-memory author build
(`CanonicalGraphBuilder.buildAuthors`), as additional union-find edges. This is the one real quality residual from
the H75 V2 cutover: V2 today merges authors only by **ORCID**, **OpenAlex author-id**, and the **positional bridge**
(Scopus[i]↔OpenAlex[i] on a shared-DOI paper). Three split modes remain — see the dry-run.

## Why now / why in the build

The V1 `AuthorReconcileService` (730 lines) already encodes the heuristics, but it is the per-record Mongo OLTP the
H75 arc replaced. The heuristics are pure set-logic and drop into the existing union-find as extra edges: deterministic,
in-memory, fast, no Mongo round-trips, no V1 oracle needed. Affiliation is now **ROR-clean** (H73), so "shared
affiliation" is finally a trustworthy signal — it was the blocker that made V1 punt.

## The rule (STRONG tier — auto-merge)

Two author **nodes** (pre-merge union-find elements) merge iff ALL hold:
1. **Same surname** (from `displayName`: text before a comma, else the last token) and **name-compatible** given names
   (equal, or one is the leading initial of the other; two different full given names ⇒ different people).
2. **≥1 shared affiliation** (`saff_*` id). Node affiliation derives from OpenAlex authorship `institutionRors`
   (Scopus-only nodes carry none → they don't merge on affiliation; consistent with Mode 1 ≈ 0).
3. **Shared co-authors ≥ adaptive floor**: co-authors come from pubs with **≤20 authors** (mega-author papers are not
   an identity signal). Floor = **1 for rare surnames, 2 for common surnames**, split at **block size ≈ 40** (a
   "block" = all nodes sharing a surname). Tunable dials.
4. **Same-paper hard block clear**: the two nodes must NOT co-appear as distinct authors on any one publication
   (co-appearance ⇒ different people ⇒ never merge). Enforced both as a candidate filter and as a post-build invariant.

Mega-surname blocks (> 300 nodes) are skipped entirely (the common-name space needs finer blocking; out of scope for v1
— deferred to review tooling). The **MEDIUM tier** (name + affiliation-only, or name + co-author-only) is NOT
auto-merged — left for a future review queue.

## Dry-run evidence (2026-06-22, current canonical data, `/tmp/h71_tighten.py`)

369,070 authors; 132,253 surname blocks; 87 blocks / 81,183 authors skipped as too-common (>300).

| rule | merges | authors absorbed | cross-source / OA-internal | max component |
|---|---|---|---|---|
| flat co≥1 | 1,697 | 1,891 | 1,116 / 775 | 7 |
| flat co≥2 | 1,066 | 1,175 | 669 / 506 | 7 |
| **adaptive: block≤40 → co≥1, else co≥2 (chosen)** | **1,563** | **1,737** | 1,015 / 722 | 7 |
| adaptive: block≤40 → co≥1, else co≥3 | 1,482 | 1,644 | 952 / 692 | 7 |

- **Mode 1 (Scopus-only over-split) ≈ 0** — almost every real author also has an OpenAlex id, so their splits surface
  as cross-source/OA-internal, not Scopus-only. (The "106 UVT AU-IDs" of H72 are captured under cross-source.)
- **No runaway**: max merged component = 7 across every variant (the over-merge failure mode did not occur).
- Adaptive keeps the good rare-surname merges flat-co≥2 wrongly kills (Vizman, Boldea, Avram, mojibake/`null`
  artifacts) while still demanding ≥2 shared collaborators on common surnames (Jones, Anderson, Dai).
- Sample STRONG merges eyeballed high-precision: `Zeno Simon == Z. Simon`, `Daniel Vizman == D. Vizman`,
  `Diana Dăescu == Diana-Ionela Dăescu`, `Brigitta Szabó == Brigitta SzabÃ` (encoding dupe). Residual risk = single
  shared co-author on a common surname (cut by the adaptive floor).

## Validation (no V1 oracle)

- **Invariant**: no merged component contains two nodes that are distinct authors of the same publication (assert
  post-build; fail the build if violated).
- **No-runaway invariant**: max component size below a sane bound (e.g. ≤ 15); alert on a sudden giant component.
- **Determinism**: two derive-only runs produce identical author counts/ids (the V2 `CanonicalSnapshot` discipline).
- **Spot-checks**: `Daniel Vizman` collapses to one author; a known same-name-different-people pair does NOT; total
  author count drops by ≈ the dry-run's absorbed figure.

## Slices

- **S1 — candidate engine (pure, dry-run first). DONE (commit `cb01578`).** `CanonicalGraphBuilder.applyAuthorReconcile`
  assembles per-root signals in-memory (pub→roots for co-author + same-paper; root→affiliation from OpenAlex RORs),
  runs the adaptive rule + same-paper hard block, and (dry-run) logs candidates without applying. `AuthorReconcileSettings`
  (off by default; the 3-arg `buildAuthors` overload stays pure for tests). 6 unit tests green. **Live dry-run
  (2026-06-22):** `candidatePairs=2072 authorsAbsorbed=1919` with the adaptive default (commonThreshold=40, commonFloor=2)
  — matches the offline ~1,737 (slightly higher: Java folds diacritics properly, grouping `Mureşan` variants the
  ascii-only script split). Samples textbook-correct (initial↔full, middle-name, hyphen, diacritic, surname-only); no
  visible false merges. 79 common-name blocks / 76,686 authors deferred.
- **S2 — apply + invariants + cannot-link guard. DONE (commit `6d90223`).** Applied the unions in `buildAuthors`; edges
  re-point for free (V2 builds them post-merge). **Live with fail-fast first surfaced the transitivity trap**: 10
  same-paper violations — most were the *same person duplicated on one paper* (big-collaboration physics papers
  OpenAlex double-lists), but one was a genuine false-merge (`Carmen Tatu`/`Călin Tatu` chained by an ambiguous
  `C. Tatu`). Per the chosen path, replaced fail-fast with a **cannot-link-aware union**: build the same-paper conflict
  set among candidate roots and skip any union that would connect a conflicting pair (the post-merge invariant + a
  runaway bound stay as safety nets). **Live (2026-06-22, applied):** `authorsAbsorbed=1909 conflictsAvoided=13
  maxComponent=7 samePaperViolations=0`; author count 371,196 → 369,287. Spot-checks: `Daniel Vizman` collapsed
  (6 OpenAlex ids folded) while `Cornelia Vizman` stayed separate; `Călin Tatu`/`C. Tatu` stayed separate; 0 orphan
  authorship edges; Marc Frîncu intact. 8 unit tests incl. the transitive-chain-broken case + determinism. Default
  flipped ON (`core.canon.author-reconcile.enabled:true`).
- **S3 — tune / refine (optional, later).** Possible refinements: allow same-paper merge when names are *identical*
  (recover the duplicated-authorship cases the guard conservatively splits); the ambiguous-initial guard (don't bridge
  `C. Tatu` to a full given when >1 compatible full given exists in the block); evaluate the 79 skipped common-name
  blocks with finer (surname+initial) blocking. Not needed for correctness — current behavior is precision-safe.

## Out of scope (v1)

Mega-surname blocks (>300) and the MEDIUM-tier review queue; OpenAlex name-only authorships (not id-resolvable);
re-pointing researcher-profile/`syncedResearchers` links (V2 rebuilds those from canonical ids anyway).

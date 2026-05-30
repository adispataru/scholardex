# H53 Import Conflict Triage Policy

**Status:** Completed
**Created:** 2026-05-30
**Completed:** 2026-05-30

## Purpose

Future imports should produce a small, useful review queue. Human triage is reserved for cases where the importer found multiple plausible canonical identity targets and cannot safely choose. Deterministic lineage, precedence, and audit-only decisions should not create manual work.

## Current Evidence

The local `test` Mongo database showed:

- `scholardex.identity_conflicts`: 3,112 open conflicts, mostly relink conflicts from Scopus-derived imports.
- `wos.fact_conflicts`: 721,153 audit-style fact conflicts, almost all `latest-lineage` winner decisions.
- `wos.identity_conflicts`: 1 true ambiguous journal identity case.
- `scholardex.publication_link_conflicts`: 0.
- `reportImportSessions`: 0.

This indicates the current conflict surface mixes manual-review work with deterministic audit telemetry.

## Triage Policy

### Needs Review

Only ambiguous identity cases with multiple plausible canonical candidates enter human triage.

Reviewer actions are intentionally narrow:

- Pick one existing candidate canonical entity.
- Dismiss the item.

Resolution applies only to the exact source record. No generalized learned rule is created. A dismissed item suppresses future review only for the same source record and same candidate set.

### Deterministic Identity Relinks

True identity/source links use source precedence first, then import event timestamp for equal-precedence sources.

Source precedence:

```
SCOPUS < SCOPUS_JSON_BOOTSTRAP < SCOPUS_PYTHON_AUTHOR_WORKS = SCOPUS_PYTHON_CITATIONS_PUBLICATION
```

Rules:

- Higher-precedence incoming identity link relinks automatically.
- Equal-precedence incoming identity link relinks automatically only if its import event timestamp is newer.
- Lower-precedence incoming identity link is skipped without review.
- Auto-relinks are reported as aggregate import-run counts, not detailed triage rows.
- Affected entities are marked dirty for projection rebuild.

### Edge Evidence

Authorship, author-affiliation, publication-author-affiliation, and citation edges should not create relink review items.

Storage remains the current single canonical edge shape. New edge evidence updates the canonical edge only when the new source outranks the existing source. Lower-precedence or equal-precedence duplicate edge evidence is skipped without per-row audit.

### Unresolved Identities

Zero-candidate unresolved required identities are counted in import metrics only. They do not enter human triage.

Optional unresolved edges, such as missing citation endpoints, do not enter triage.

### WoS Fact Conflicts

WoS fact conflicts such as `latest-lineage` are audit-only. They never enter human triage because the importer already chooses a deterministic winner.

## Import Metrics

Keep aggregate import-run metrics indefinitely. At minimum, record counts by run, source, entity/edge type, and reason:

- auto-relinked identity links;
- skipped lower-precedence identity links;
- skipped duplicate/lower-precedence edge evidence;
- zero-candidate unresolved identities;
- deterministic WoS fact winner decisions.

Detailed rows are not required for deterministic outcomes unless a later implementation slice explicitly needs them for debugging.

Implementation note, 2026-05-30: added a durable `scholardex.import_run_metrics` aggregate keyed by run, source, entity/edge type, and reason. Deterministic source-link auto-relinks and precedence-kept skips now emit identity or edge aggregate metrics using `sourceBatchId`, falling back to correlation id or source event id when needed.

Implementation note, 2026-05-30: WoS fact-builder deterministic winner decisions now emit aggregate audit metrics with reasons such as `deterministic-wos-fact-winner-latest-lineage` and `deterministic-wos-fact-winner-source-precedence`. Source-precedence decisions remain rowless; latest-lineage conflict rows remain audit/debug data and do not enter the human review queue.

## Admin UI

Keep broad operational visibility, but separate it clearly:

- `Needs Review`: actionable ambiguous identity items only.
- `Audit Only`: deterministic counts and drill-down summaries for fact conflicts, skipped relinks, and import-run metrics.

The UI should provide a quick action to rebuild projections for entities marked dirty by import processing. If the rebuild fails, the import remains successful and the rebuild failure is shown separately with retry.

Implementation note, 2026-05-30: the admin conflict page now labels the actionable table as `Needs Review`, scopes its stat cards to ambiguous-candidate identity conflicts, and adds an `Audit Only` summary for import-run metric aggregates, WoS fact conflict audit rows, and legacy Scopus publication-link audit rows.

Implementation note, 2026-05-30: automatic identity relinks now create durable `scholardex.projection_dirty_markers` entries for both the previous and incoming canonical ids. The admin conflict page shows dirty and failed projection rebuild counts and provides a retryable rebuild action that marks markers rebuilt or `REBUILD_FAILED` without rolling back imports.

Implementation note, 2026-05-30: added an idempotent legacy cleanup action for deterministic Scholardex identity conflicts. It records aggregate counts under the `legacy-conflict-cleanup` run, deletes only non-ambiguous identity conflict rows, and leaves multi-candidate identity conflicts in `Needs Review`; WoS fact, WoS identity, and Scopus link audit stores remain preserved and summarized.

## Proposed Slicing

- **H53.1** — Conflict inventory and policy fixtures.
  Capture representative conflict fixtures from `test` Mongo for source-link relinks, edge relinks, zero-candidate misses, ambiguous identity matches, and WoS `latest-lineage` fact conflicts. Turn them into stable tests/contracts so later slices can prove which cases enter `Needs Review`, which become aggregate metrics, and which are audit-only.

- **H53.2** — Source precedence and lineage decision service.
  Introduce one shared policy component for import source precedence and equal-precedence timestamp comparison. It owns the ordered source list `SCOPUS < SCOPUS_JSON_BOOTSTRAP < SCOPUS_PYTHON_AUTHOR_WORKS = SCOPUS_PYTHON_CITATIONS_PUBLICATION` and exposes deterministic decisions: apply incoming, keep existing, or require review.

- **H53.3** — Deterministic identity source-link relinks.
  Wire the policy into true identity/source-link writes. Higher-precedence incoming links and newer equal-precedence links relink automatically; lower-precedence links skip without review. Auto-relinks and skips emit aggregate import-run metrics and mark affected canonical entities dirty.

- **H53.4** — Edge evidence noise reduction.
  Apply accumulator-style behavior to authorship, author-affiliation, publication-author-affiliation, and citation edges. Existing canonical edges stay single-row. Incoming edge evidence updates the edge only when source precedence outranks the existing lineage; lower/equal duplicate evidence is skipped without per-row audit or human review.

- **H53.5** — Ambiguous identity review queue semantics.
  Restrict `Needs Review` creation to ambiguous identity cases with multiple plausible canonical candidates. Add exact-source-record resolution and dismissal behavior: choosing a candidate applies only to that source record; dismissal suppresses the same source record only when the candidate set is unchanged.

- **H53.6** — Aggregate import-run metrics.
  Persist indefinite per-run aggregate counts by run, source, entity/edge type, and reason for auto-relinks, skipped lower-precedence identity links, skipped duplicate/lower-precedence edge evidence, zero-candidate misses, and deterministic WoS winner decisions. Do not persist deterministic row-level detail in this slice.

- **H53.7** — WoS conflict audit-only handling.
  Keep WoS fact conflicts out of human triage. Existing `latest-lineage` facts remain available as audit/debug data and summarized counts, while the admin review queue ignores them.

- **H53.8** — Admin conflict UI split.
  Rework the admin conflict surface into `Needs Review` and `Audit Only` sections. `Needs Review` shows actionable ambiguous identity cases and candidate/dismiss actions. `Audit Only` shows aggregate metrics and deterministic conflict summaries without presenting them as manual work.

- **H53.9** — Projection dirty marking and quick rebuild.
  When identity relinks apply automatically, mark affected projections dirty and surface a quick rebuild action. Import success remains independent from rebuild success; failed rebuilds show a retryable failure state without rolling back the import.

- **H53.10** — Existing conflict cleanup / migration path.
  Define and implement a one-time cleanup for existing conflict collections: move deterministic items out of `Needs Review`, preserve or summarize audit-only counts, and leave only ambiguous identity items actionable. This slice should be safe to run repeatedly in local/staging environments.

## Verification Plan

- Unit: precedence/timestamp decisions, ambiguous-vs-deterministic classification, exact candidate-set dismissal suppression.
- Service: source-link relink policy, edge write skip/update behavior, aggregate metric emission, dirty projection marking.
- Repository/integration: metrics persistence by run/source/reason; existing conflict cleanup idempotency against fixture data.
- UI contract: `Needs Review` excludes deterministic relinks, edge duplicates, zero-candidate misses, and WoS fact conflicts; `Audit Only` exposes aggregate summaries.
- Regression: current import paths still complete when deterministic outcomes are skipped or auto-applied.

## Exit Criteria

- Future imports no longer create manual-review rows for deterministic source-link relinks, edge duplicates, zero-candidate misses, or WoS `latest-lineage` fact conflicts.
- Ambiguous multi-candidate identity cases remain visible and actionable.
- Source precedence and equal-precedence timestamp rules are covered by tests.
- Per-run aggregate metrics explain skipped and auto-applied outcomes.
- Projection dirty marking and quick rebuild action are available from the import/admin surface.

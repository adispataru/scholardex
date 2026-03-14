# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Backlog

- [ ] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical user/admin workflows across the current canonical architecture (Mongo ingest/facts, WoS/Scopus initialization flows, PostgreSQL reporting projections, and user-facing report/export reads).
  Deliverable: focused workflow-level tests for the highest-value operational paths, using deterministic fixtures and asserting state transitions across controller -> orchestration -> persistence/read-model boundaries.
  Exit criteria: top workflows that now span canonical ingest, projection rebuilds, Postgres-backed reads, and user report/export surfaces are validated across success and failure paths, and regressions are caught before merge by repeatable automated checks.
  Subtasks:
  - [ ] `H13.1` Admin WoS maintenance end-to-end flow.
    Deliverable: deterministic workflow test for the modern admin WoS initialization path, covering canonical WoS steps (`ingest -> build facts/onboarding -> enrich category rankings -> build projections`) plus verification/readiness assertions for the resulting read models.
    Exit criteria: the workflow validates the current initialization surface under `/admin/initialization`, confirms step ordering and expected persistence/read-model effects, and asserts both authorized execution and unauthorized access behavior.
  - [ ] `H13.2` User indicator refresh/export workflow.
    Deliverable: deterministic workflow test covering a representative user reporting path across persisted indicator refresh, individual-report refresh, and workbook export using the current user surfaces and projection-backed read dependencies.
    Exit criteria: refresh actions persist/update the expected run/result state, downstream user-facing views resolve from the refreshed state without recomputation drift, and export response contracts remain stable for the selected workflow.
  - [ ] `H13.3` Failure-path workflow gate.
    Deliverable: at least one full workflow-level degraded scenario exercising a modern critical path failure, such as initialization/projection verification failure, Postgres reporting readiness mismatch, or user report refresh/export degradation.
    Exit criteria: the selected failure mode is reproducible with deterministic fixtures, produces explicit user/operator-visible behavior, and blocks regressions in the corresponding workflow.

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.

- [ ] `H21` User-defined source onboarding into Scholardex.
  Goal: support user-triggered non-Scopus/WoS/Scholar publication imports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: migrated in-place user publication wizard onboarding flow modeled as `USER_DEFINED` source events/facts with deterministic IDs, explicit review/moderation metadata, and integration with canonical Scholardex identity, source-link, conflict, and projection contracts.
  Exit criteria: the existing `/user/publications/add` wizard submits `USER_DEFINED` publication onboarding through the canonical Scholardex ingestion path; publication/forum/authorship/linked-affiliation lineage is deterministic and replay-safe; review/moderation state is explicit in metadata without requiring a separate admin approval workflow; imported records become visible through canonical Scholardex projections and existing user/admin operability surfaces.
  Dependency: execute after `H19.9` citation canonicalization to avoid EID-coupled citation gaps for user-only publications.
  Subtasks:
  - [ ] `H21.1` Lock the `USER_DEFINED` wizard-onboarding contract.
    Deliverable: implementation-ready contract for the migrated `/user/publications/add` flow covering source family naming, deterministic `sourceRecordId` and forum keying, lineage envelope, ownership boundaries, and review/moderation metadata semantics.
    Exit criteria: H21 is decision-locked as an in-place migration of the existing publication wizard, not a new route family or broader direct author/forum/affiliation onboarding project; `USER_DEFINED` publication/forum linkage rules explicitly align with H19.1 and H19.2 contracts.
  - [ ] `H21.2` Migrate wizard submission into first-class `USER_DEFINED` canonical ingest.
    Deliverable: backend migration of the existing wizard submission path so wizard-created publications and newly created forums are emitted and materialized as canonical `USER_DEFINED` onboarding records rather than legacy manual compatibility payloads.
    Exit criteria: the wizard still runs at `/user/publications/add`, but its submit path is explicitly modeled as `USER_DEFINED` canonical onboarding with deterministic replay-safe IDs and no hidden dependence on legacy source naming assumptions.
  - [ ] `H21.3` Align canonical linking, lineage, and review metadata for wizard-created entities.
    Deliverable: canonical publication/forum/authorship/linked-affiliation handling for wizard submissions with explicit review/moderation metadata carried in source event payloads, source links, or canonical facts as appropriate.
    Exit criteria: user-defined submissions preserve source ownership, link deterministically into Scholardex identity models, surface review/moderation state for operator triage, and do not require a separate admin approval UI/workflow in H21.
  - [ ] `H21.4` Integrate operability and admin triage for `USER_DEFINED` onboarding.
    Deliverable: ensure wizard-created `USER_DEFINED` records appear coherently in existing conflict/source-link/admin operability surfaces and log/metric triage paths.
    Exit criteria: operators can trace wizard submissions through canonical build/source-link lineage using the same admin and observability paths already used for other Scholardex sources; no parallel one-off wizard-only debugging path remains.
  - [ ] `H21.5` Add regression and projection-visibility coverage for migrated wizard onboarding.
    Deliverable: focused tests for wizard submission, duplicate replay/idempotence, deterministic forum/source ID generation, source-link/conflict behavior where applicable, and projection visibility after canonical materialization.
    Exit criteria: automated coverage protects the migrated in-place wizard contract and fails on regressions that reintroduce legacy manual-path behavior or break canonical projection visibility for `USER_DEFINED` submissions.
  - [ ] `H21.6` Closeout docs and route/task handoff.
    Deliverable: backlog/docs/task notes updated so the publication wizard is documented as the canonical in-place `USER_DEFINED` onboarding surface into Scholardex.
    Exit criteria: active docs no longer describe the wizard as a legacy manual exception path; H21 handoff is explicit about retained UI route, canonical source family, and no-admin-approval-workflow scope.

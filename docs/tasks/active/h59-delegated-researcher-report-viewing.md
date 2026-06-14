# H59 Delegated Researcher-Report Viewing (Admin + Supervisor)

**Status:** Planning
**Created:** 2026-06-14

## Purpose

Let a `PLATFORM_ADMIN` or `SUPERVISOR` open the **exact individual evaluation report a
specific researcher sees**, read-only by default, and trigger an **attributed refresh** of
that researcher's report. The numbers, drilldowns, and export must match what the researcher
sees in their own `/user/evaluation` workspace, because both are computed by the same engine
against the researcher's curated state (authorship decisions, confirmed publications,
affiliation filter).

## Why this is mostly a surface + trust problem

The computation layer is **already fully parameterized by `userEmail`** and never reads the
principal internally — `UserReportFacade`, `UserIndividualReportRunService`,
`UserIndicatorResultService`, and `ReportExportFacade` all accept a target email. The
principal→email binding lives only in `EvaluationWorkspaceController`. So "compute the report
another researcher sees" is solved at the engine level. What's missing is a controller, an
authorization gate, read-only-view safety, and refresh provenance.

The central tension: **refresh is not a read.** It overwrites the researcher's `LATEST` cache
rows, upserts their `SNAPSHOT` rows, and creates a new `UserIndividualReportRun` — the same
rows the researcher's own next page-load reads. Delegated refresh therefore writes into the
researcher's data space and is observable by the researcher. It is deterministic (a projection
of canonical data, so a supervisor's numbers equal the researcher's), but it bumps timestamps
and overwrites caches, which demands provenance.

## Decisions (locked 2026-06-14)

1. **Refresh rights:** supervisors **and** admins may refresh, with provenance recorded.
2. **Surface:** a dedicated route shared by both roles (`/reports/researcher/**`), not a
   group-workspace drilldown and not a generic impersonation/view-as capability.
3. **Supervisor scope authority:** **any org-hierarchy leadership relation** — a researcher is
   in scope when they are reachable from *any* division/department/group the supervisor leads.
   This is the union of:
   - (a) a *current* (`validTo == null`) `DepartmentAffiliation` under a department the
     supervisor heads (directly via `Department.headUserIds`, or via a division they head
     through `OrgDivision.headUserIds`), **and**
   - (b) a *current* (`validTo == null`) `Membership` in a group the supervisor supervises —
     explicitly via `Group.supervisorUserIds`, or implicitly via a headed department/division.

   Group-only supervisors (those who supervise a group but head no department/division) **are**
   in scope. Use any current affiliation/membership, not primary-only. This is exactly the
   supervised subtree `SupervisorWorkspaceService` already resolves.
4. **Privacy:** the live report + runs + export only. The researcher's private named
   `EvaluationSnapshot`s are **not** exposed, and no snapshot create/delete in this surface.

## Architecture

### Authorization — `ResearcherAccessService` (new)

Mirrors the existing `GroupAccessService` SpEL-bean pattern
(`@PreAuthorize("@researcherAccess.canView(#email, authentication)")`).

- `canView(researcherEmail, auth)`:
  - `PLATFORM_ADMIN` → `true`.
  - `SUPERVISOR` → researcher is reachable from the supervisor's **supervised subtree**
    (divisions/departments headed + groups supervised, as resolved by
    `SupervisorWorkspaceService`) via **either**:
    - a current `DepartmentAffiliation` (`validTo == null`) whose `departmentId` is in a headed
      department / a department under a headed division, **or**
    - a current `Membership` (`validTo == null`) whose `groupId` is in a supervised group.
  - else → `false`.
- `canRefresh(researcherEmail, auth)` — same rule today; kept as a separate method so refresh
  can diverge from view later without touching call sites.
- `findInScopeResearchers(auth)` — admin → all users with a researcher profile; supervisor →
  union of (current affiliates of headed departments) and (current members of supervised
  groups), deduplicated. **Must be scope-filtered so out-of-scope researcher identities never
  leak to a supervisor.**

### Surface — `ResearcherReportController` (new)

Route prefix `/reports/researcher/**`.

| Method | Path | Behavior |
|---|---|---|
| GET | `/reports/researcher` | In-scope researcher picker. |
| GET | `/reports/researcher/{email}` | Researcher's visible reports (`UserReportFacade.buildIndividualReportsListView(email)`). |
| GET | `/reports/researcher/{email}/report/{reportId}` | **Read-only** view of the latest *existing* run. |
| POST | `/reports/researcher/{email}/report/{reportId}/refresh` | Attributed refresh (`refreshRunWithAllIndicators` with actor). |
| GET | `/reports/researcher/{email}/report/{reportId}/export?format=XLSX\|DOCX` | Reuses `ReportExportFacade.exportRunOutcome(email, …)` (already validates run↔email). |

Per-row `@PreAuthorize("@researcherAccess.canView(#email, authentication)")` (and `canRefresh`
on the POST). `WebSecurityConfig`: add
`requestMatchers("/reports/researcher/**").hasAnyAuthority("PLATFORM_ADMIN","SUPERVISOR")`.

Stretch (not MVP): indicator-detail / citation drilldown via
`buildReportScopedIndicatorDetail(email, …)`.

### Read-only view safety

The researcher's own view path uses `getOrCreateLatestRun`, which **writes** a run when none
exists. The delegated view must **not** mutate on read. Add
`UserIndividualReportRunService.findLatestRun(email, reportId)` (read-only; wraps the existing
`findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc`). When empty, render "not
generated yet" + a Refresh button.

### Provenance for delegated refresh

Add `triggeredByEmail` to `UserIndividualReportRun`. Thread an `actorEmail` through
`refreshRun` / `refreshRunWithAllIndicators` / `buildAndSaveRun` (back-compat overload
defaulting `actorEmail = userEmail`). Self-refresh → actor == owner; delegated → actor ≠ owner,
surfaced in the UI as "Last refreshed by X on `<date>`".

## Out of scope (MVP)

- Impersonation / generic view-as.
- Editing the researcher's authorship decisions or any write into their curated state beyond
  the refresh itself.
- Access to the researcher's named `EvaluationSnapshot`s (create/list/delete).
- Run comparison (`/user/evaluation/compare*`).
- Group/aggregate reports (covered elsewhere).

## Known caveats of the chosen scope model

- **Multiple affiliations/memberships:** scope uses *any* current affiliation or membership,
  not primary-only. A researcher reachable by more than one path is in scope (deduplicate).
- **Subtree breadth follows headship:** a division head sees the whole division subtree
  (descendant departments and their groups), a department head sees that department's groups,
  and a group supervisor sees just that group — matching `SupervisorWorkspaceService`'s existing
  resolution. No path is privileged over another for visibility.

## Exit criteria

- An admin can open and refresh any researcher's individual report; numbers and export match
  the researcher's own `/user/evaluation` output for the same run.
- A supervisor can open/refresh a report **only** for researchers reachable from their
  supervised subtree (by current department affiliation **or** current group membership);
  out-of-scope access returns forbidden and out-of-scope researchers never appear in their
  picker. A group-only supervisor can see their group's members.
- The read-only view path never creates or mutates a run (verified: viewing a researcher with
  no existing run writes nothing).
- A delegated refresh records `triggeredByEmail` ≠ owner and surfaces the actor + timestamp;
  a self-refresh records actor == owner.
- The researcher's private named snapshots are not reachable through this surface.
- Tests: `ResearcherAccessService` unit (admin allow-all; supervisor in-scope via department
  affiliation allow; supervisor in-scope via group membership allow; group-only supervisor sees
  group member; out-of-scope deny; unaffiliated/non-member researcher deny); controller contract
  + security tests
  (authority rule, per-researcher scope, RESEARCHER role forbidden); provenance test; a
  no-write-on-view test.

## Implementation progress

- **Slice 1 — admin read-only delegated view (DONE):** `ResearcherAccessService` (admin branch),
  `ResearcherReportController` (`/reports/researcher` picker + `/{email}` read-only view),
  `IndividualReportViewModelAssembler` + `RunSummary` (shared assembly extracted from
  `EvaluationWorkspaceController`), `UserIndividualReportRunService.findLatestRun` (read-only, no
  create), security rule, delegated template gating. Verified no-write-on-view.
- **Slice 2 — supervisor scope (DONE):** `ResearcherAccessService` supervisor branch composes
  `SupervisorWorkspaceService.buildView` with current `DepartmentAffiliation`/`Membership` lookups
  (dept-affiliation OR group-membership within the led subtree; group-only supervisors included).
  Added `DepartmentAffiliationRepository.findByDepartmentIdInAndValidToIsNull`.
- **Slice 3 — delegated refresh + provenance (DONE):** `triggeredByEmail` on
  `UserIndividualReportRun` (+ `IndividualReportRunDto`), actor threaded through
  `refreshRunWithAllIndicators`/`buildAndSaveRun` (back-compat overload defaults actor = owner),
  `POST /reports/researcher/{email}/report/{reportId}/refresh` gated by `canRefresh`, delegated
  Refresh button + "Generate now" + "by &lt;actor&gt;" provenance line. Verified end-to-end under
  `agent-dev`.
- **Remaining:** Slice 4 — delegated export (`GET …/export`, reuse
  `ReportExportFacade.exportRunOutcome(email, …)`). Stretch — indicator/citation drilldown.

## Dependencies

None hard. Reuses the user-parameterized report engine as-is. Builds on the existing
supervisor/org-scope model (`SupervisorWorkspaceService`, `GroupAccessService`,
`DepartmentAffiliation`, `Department`/`OrgDivision` head lists).

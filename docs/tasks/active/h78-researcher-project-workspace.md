# H78 Researcher project workspace — import / search / link

**Status:** Scoped (2026-06-30) — spun off from `H64` slice 4c. Builds entirely on the H64 canonical project layer.
**Depends on:** `H64` ✅ (canonical `ScholardexProjectFact`, `scholardex_project_view`, `/api/entities/projects` picker,
`proj_*` scoring injection, `CordisProjectClient`).

## Goal
Give a researcher, in the workspace, a project-centric flow on top of the canonical project layer:
1. **Surface "my projects"** — projects where the researcher is the **director** (name match), read-only.
2. **One-click import** — turn a canonical project into a pre-filled `Grant Cercetare` activity instance.
3. **Link** an existing free-text activity to a canonical project (back-fill `PROJECT_GRANT_ID`).

## Already shipped by H64 (do NOT rebuild)
- **Activity CRUD:** `ActivityInstance` (Mongo) — `researcherId` (email), `fields` (Map), `referenceFields`
  (`Activity.ReferenceField`→String, incl. `PROJECT_GRANT_ID`). Endpoints on `ResearcherWorkspaceController`:
  `POST /user/workspace/activities/create|update`, `delete/{id}`; current researcher via `currentUser()` (principal →
  `User` → email).
- **Project picker:** `EntityProjectApiController` `/api/entities/projects` (`?q=`, paged) + `/{id}`; wired in
  `workspaceActivities.js` (`.js-project-picker-*`) on **both create and edit** forms, binding `PROJECT_GRANT_ID`.
  → "pick a project and attach it" (incl. the basic link-on-edit case) is **already working**.
- **Director data:** `ScholardexProjectFact.directorFirst/directorLast/directorRole` → `scholardex_project_view`
  (`director_first`, `director_last`, `director_role`). Read port `search()` does **not** filter by director yet.
- **Name matching primitive:** `ProjectCanonicalizationService.signature()` (stopword-stripped, sorted, normalized
  tokens — word-order/diacritic-insensitive) + `CanonicalizationSupport.normalizeName()`. Reuse verbatim.
- **CORDIS:** `CordisProjectClient.fetch(grantId)` + the H64 admin live-fetch / `UserDefinedProjectFact` path.

## Decisions settled (2026-06-30)
- **Director match = projected signature column.** Add `director_signature` to `scholardex_project_view`, computed at
  projection time via `signature(directorFirst + " " + directorLast)`, indexed; the read port queries by **exact
  signature**. Deterministic + reusable; needs a migration + a projection re-run (fast, projection-only — NOT a full
  rebuild).
- **CORDIS escalation = admin-deferred.** Researchers do **not** mint canonical projects. If a project isn't in the
  canonical layer, the researcher enters a free-text `Grant Cercetare` (today's behavior, unlinked) and/or it's flagged
  for admin import via the existing H64 admin CORDIS path. Project governance stays admin-only.
- **Director-only surfacing.** `ScholardexProjectFact` carries no participant **names** (only affiliation ids), so
  "my projects" matches the **director** only. "or participant" from the backlog is not supported by the data yet
  (revisit if brainmap detail/CORDIS partner-person data is later captured).

## Slices

### Slice 1 — Director attribution + "My projects" panel (read-only)
- **Migration** `Vxx__h78_director_signature.sql`: add `director_signature TEXT` + index to `scholardex_project_view`.
- **Projection:** `ProjectProjectionService` computes `director_signature = signature(directorFirst + " " + directorLast)`
  on project (null/blank director → null). Re-run the projection once (projection-only, ~341 rows).
- **Read port:** `ScholardexProjectReadPort#findByDirectorSignature(String signature)` (and Postgres impl) →
  `List<ScholardexProjectListItemResponse>` where `director_signature = ?`.
- **Service + endpoint:** compute the researcher's signature from `User.firstName + lastName`; `GET
  /user/workspace/projects/mine` → the matched projects. Empty when the researcher has no name or no director match.
- **UI:** a "My projects" panel in the workspace listing candidates (code — title — funder — years — director role),
  framed as *"projects that may be yours"* (homonym-safe; read-only — no auto-link). Each row has an **Import**
  action (→ slice 2).
- **Tests:** signature match (diacritics + name-order: "Ștefan Popescu" ↔ "Popescu Stefan"); read-port query; endpoint
  returns only the researcher's director projects; no-name researcher → empty.

### Slice 2 — Import a project as a `Grant Cercetare` activity (pre-fill + idempotency)
- **Endpoint** `POST /user/workspace/activities/import-project/{projectId}`: resolve the project (read port), build a
  `Grant Cercetare` `ActivityInstance` pre-filled — `Nume Proiect`←title, `Buget`←budget (if present), `Rol`←
  directorRole (default if absent), `referenceFields[PROJECT_GRANT_ID]`←projectId — and persist via the existing
  facade. Field-name mapping reads the activity definition (config-aware; skip a field gracefully if the activity
  lacks it).
- **Idempotency:** before create, check the researcher's existing `Grant Cercetare` instances for one already carrying
  this `PROJECT_GRANT_ID`; if found, **refuse + return the existing id** (no duplicate). Surfaced as a clear
  "already imported" message in the UI.
- **UI:** the Import action (from "my projects" or the picker) calls the endpoint, then refreshes the activities list;
  duplicate → toast linking the existing instance.
- **Tests:** pre-fill mapping (title/budget/role/reference); idempotency (second import of the same project → no new
  instance, returns existing); missing budget/role handled; activity-definition without a `Buget` field → skip.

### Slice 3 — Link existing free-text activity + admin-deferred not-found
- **Link:** the picker already back-fills `PROJECT_GRANT_ID` on the edit form. Add (a) a one-click "Link to project"
  affordance directly on an existing `Grant Cercetare` row (open picker, set the reference without re-typing fields),
  and (b) optional refresh of display fields from the canonical record on link. Reuse the slice-2 idempotency guard.
- **Not-found path:** when the picker returns nothing, show guidance — enter free-text (unlinked) now, or request admin
  import (link to / note the H64 admin CORDIS endpoint). **No researcher-side minting.**
- **Tests:** linking sets `PROJECT_GRANT_ID` on an existing instance; idempotency on re-link; not-found guidance shown.

## Risks
- **Homonym mis-attribution** on director→researcher match — mitigated by: read-only surfacing (candidate list, not a
  link), and import being an explicit per-project user action (confirm-by-clicking), never silent.
- **Duplicate activity instances** on re-import — mitigated by the `PROJECT_GRANT_ID` idempotency guard (slices 2+3).
- **Stale display fields** — pre-filled `Buget`/title are a snapshot; scoring already uses the live `proj_*` injection
  (H64), so the *score* stays correct even if the display field drifts. Note this in the UI copy.

## Files (anticipated)
- Migration: `src/main/resources/db/migration/Vxx__h78_director_signature.sql`
- `ProjectProjectionService` (compute `director_signature`); `ScholardexProjectReadPort` +
  `PostgresScholardexProjectReadPort` (`findByDirectorSignature`)
- `ResearcherWorkspaceController` (+ `projects/mine`, `activities/import-project/{id}`); a small
  `ResearcherProjectService` for the signature + import/idempotency logic
- `workspaceActivities.js` + workspace template (My-projects panel, Import/Link actions)
- Tests alongside each.

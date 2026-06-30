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
- **Director-only AUTO-surfacing; participants self-serve via search.** `ScholardexProjectFact` carries no participant
  **names** (only affiliation ids), so the **automatic** "My projects" panel matches the **director** only. Participants
  aren't auto-surfaced — instead they **search** for the project when adding a `Grant Cercetare` and link it themselves
  (the picker already supports this; see **Slice 4** for the participant-oriented search-and-link-with-pre-fill flow).

## Slices

### Slice 1 — Director attribution + "My projects" panel (read-only) — **DONE 2026-06-30**
Shipped: `V20__h78_1_project_director_signature.sql` (indexed `director_signature` column); `ProjectProjectionService`
computes it via the now-public `ProjectCanonicalizationService.signature(directorFirst+" "+directorLast)`;
`ScholardexProjectReadPort.findByDirectorSignature` (exact equality); `ResearcherProjectService.myProjects(profile)`
(researcher signature from firstName+lastName, empty-short-circuit for no name); `GET /user/workspace/projects/mine`;
read-only "Projects that may be yours" card at the top of the activities tab (lazy fetch, hidden when empty).
Tests green (service unit, read-port integration incl. order-insensitive + null-safe, both `@WebMvcTest` slices).
Live: endpoint 200 `[]` end-to-end. **Deploy step:** re-run the project projection once so existing rows get a
`director_signature` (column is null until then; full-replacement projection, not a full rebuild).


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

### Slice 2 — Import a project as a `Grant Cercetare` activity (pre-fill + idempotency) — **DONE 2026-06-30**
Shipped: `ResearcherProjectService.importProject(email, projectId)` (config-aware pre-fill — only writes fields the
activity declares: `Nume Proiect`←title, `Buget`←budget when present, `Rol`←inferred director role intl/national by
funder; sets `PROJECT_GRANT_ID`) with idempotency on the reference (`EXISTS` returns the existing instance, no
duplicate); `POST /user/workspace/activities/import-project/{projectId}` (200 CREATED/EXISTS, 404 PROJECT_NOT_FOUND,
409 ACTIVITY_NOT_CONFIGURED); an "Import" button per "My projects" row → posts → reloads. Target activity is
`core.projects.import-activity-name` (default `Grant Cercetare`). Tests: service unit (pre-fill, intl-vs-national role,
idempotency, missing-project, unconfigured-activity) + both `@WebMvcTest` slices. **Live (agent-dev, real project
`sproj_783b…`):** CREATED → EXISTS (same id) → 404; persisted instance verified — `Rol`=Director (proiect național)
(UEFISCDI), no `Buget` (null budget), `PROJECT_GRANT_ID` set; test instance cleaned up. The Import *button* visual
isn't browser-verified (agent-dev principal has no profile → the My-projects card never renders there), but the
endpoint+service path is fully exercised.


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

### Slice 3 — Link existing free-text activity + admin-deferred not-found — **DONE 2026-06-30**
Shipped: `ResearcherProjectService.linkProject(email, instanceId, projectId)` — sets `PROJECT_GRANT_ID` (re-link
overwrites) and **back-fills only blank** declared display fields from the canonical record (never clobbers the
researcher's own values); ownership-checked (`INSTANCE_NOT_FOUND` if not the researcher's). `POST
/user/workspace/activities/{instanceId}/link-project/{projectId}` (200 LINKED, 404 PROJECT/INSTANCE_NOT_FOUND).
Frontend: a quick **"Link"** action on project-supporting rows that aren't linked yet → focused inline picker →
selecting a project links in one click + reloads; the shared picker's not-found state now shows **admin-deferred
guidance** ("enter as free text, or ask an admin to import it" — no researcher minting). Tests: service unit
(blank-only backfill + preserve, not-owned, missing instance/project) + both `@WebMvcTest` slices. **Live (agent-dev):**
created a free-text Grant Cercetare (user title, blank Buget) → LINKED → reference set, `Buget` back-filled (370000),
`Nume Proiect` preserved; test data cleaned up.


- **Link:** the picker already back-fills `PROJECT_GRANT_ID` on the edit form. Add (a) a one-click "Link to project"
  affordance directly on an existing `Grant Cercetare` row (open picker, set the reference without re-typing fields),
  and (b) optional refresh of display fields from the canonical record on link. Reuse the slice-2 idempotency guard.
- **Not-found path:** when the picker returns nothing, show guidance — enter free-text (unlinked) now, or request admin
  import (link to / note the H64 admin CORDIS endpoint). **No researcher-side minting.**
- **Tests:** linking sets `PROJECT_GRANT_ID` on an existing instance; idempotency on re-link; not-found guidance shown.

### Slice 4 — Participant search-and-link when adding a `Grant Cercetare` — **DONE 2026-06-30**
Shipped: `importProject(email, projectId, asParticipant)` overload — same pre-fill/idempotency as slice 2 but `Rol`=
participant (`Membru`) instead of an inferred director role; `participantRole()` picks the "Membru" allowed value.
Endpoint gained `?role=participant` (default `director`). Frontend: a toolbar button **"Add a project you took part
in"** opens a search picker; selecting a project imports it as a participant `Grant Cercetare` (idempotent) and
reloads. Tests: service unit (participant → Membru) + both `@WebMvcTest` slices. **Live (agent-dev):** import
`?role=participant` → CREATED with `Rol=Membru`, title/Buget pre-filled, reference set; re-import → EXISTS (same id);
cleaned up.


Participants have no name in the canonical data, so they self-serve: while **adding** a `Grant Cercetare`, search the
canonical projects and link the one they participated in (vs the director's auto-surfaced "My projects" in slice 1).
- **Reuse:** the `/api/entities/projects` picker (already on the create form) + the slice-2 pre-fill + idempotency.
  Net-new is making project search a first-class step of the add flow and defaulting `Rol` to **participant** (e.g.
  `Membru`) rather than director — i.e. the search-driven counterpart of slice 2's surfacing-driven import.
- **Pre-fill on pick:** `Nume Proiect`←title, `Buget`←budget, `referenceFields[PROJECT_GRANT_ID]`←id, `Rol`←participant
  default (user-editable). Idempotency by `PROJECT_GRANT_ID` (slice-2 guard) still applies.
- **Tests:** picking a project on the add form pre-fills the fields + reference with participant role; idempotency
  holds; manual `Rol` override preserved.

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

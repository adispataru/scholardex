# QA Findings - Full App Surface - 2026-05-14

## Scope

- Target: `http://localhost:8080`
- Account: `florin.spataru@e-uvt.ro`
- Roles observed: `PLATFORM_ADMIN`, `SUPERVISOR`, `RESEARCHER`
- Test date: 2026-05-14
- Coverage: public catalog/ranking pages, authenticated researcher workspace endpoints, admin pages, admin APIs, supervisor route entry points, and unauthenticated access-control spot checks.

## Summary

Most public, researcher, and admin pages returned successfully. The prior public catalog fixes are visible in the browser for `/publications` and `/forums`: publication title markup now renders as plain text (`10^14`), missing ISSNs render as `—`, and blank catalog names use deterministic fallback labels.

The main remaining risks are admin/API defects: `/api/admin/researcher-profiles` still serializes password hashes and Spring Security account fields, the authenticated workspace switcher exposes a Supervisor Panel link that returns `404`, and the authenticated entity forum lookup API still returns raw `null-` ISSNs and blank names even though `/forums/data` is normalized.

## Findings

### High - Admin researcher profile API returns password hashes and security internals

**Evidence**

- `GET /api/admin/researcher-profiles` as `florin.spataru@e-uvt.ro` returned `200`.
- Response records include:
  - `password: "$2a$10$..."`
  - `authorities`
  - `authority`
  - `accountNonExpired`
  - `accountNonLocked`
  - `credentialsNonExpired`
  - `enabled`
  - `username`

`GET /api/admin/users` no longer exposes these fields, so this is a separate remaining admin API leak.

**Impact**

Password hashes and framework security flags are unnecessary in client responses. Any browser compromise, logging, proxy capture, or exported API output exposes credential material useful for offline attacks.

**Recommendation**

Return a dedicated response DTO from `AdminResearcherProfileController`, matching the sanitized admin-user response shape. Do not serialize `User` or Spring Security-derived fields directly.

### Medium - Supervisor workspace entry is visible but route returns 404

**Evidence**

- Authenticated header/workspace switcher renders `Supervisor Panel` with `href="/supervisor"` for the supplied account.
- `GET /supervisor` as the same authenticated user returned `404 Page not found`.
- `GET /api/supervisor` also returned `404`.

**Impact**

Users with `SUPERVISOR` role are offered a broken workspace entry. This is a visible navigation failure on every authenticated shell that renders the workspace switcher for supervisors.

**Recommendation**

Either implement the supervisor landing route/API or hide the Supervisor Panel switcher item until a working supervisor surface exists.

### Medium - Authenticated forum entity lookup still returns raw missing-data sentinels

**Evidence**

- `GET /forums/data` now renders the public forum table correctly.
- `GET /api/entities/forums` as an authenticated user still returned rows like:
  - `{"id":"139013","publicationName":"","issn":"","eIssn":"","aggregationType":"Conference Proceeding"}`
  - `{"id":"21101146192","publicationName":"#Help: ...","issn":"null-","eIssn":"","aggregationType":"Book"}`

**Impact**

Any admin/user autocomplete or reference picker that uses `/api/entities/forums` can still show blank labels or `null-` ISSN values. This keeps the same data quality/accessibility issue alive outside the public `/forums` table.

**Recommendation**

Apply the same display-name and identifier normalization used by the public forum catalog to `EntityForumApiController` responses.

### Low - Legacy WoS category route redirects to a non-existent rankings tab

**Evidence**

- `GET /wos/categories` returned `302 Location: /rankings#wos`.
- `/rankings` currently exposes tabs for `CORE`, `Universities`, and `Events`; no `wos` tab is present in the rendered markup.
- A guessed historical detail path, `GET /wos/categories/MATHEMATICS`, returned the app `404` page.

**Impact**

Users or bookmarks following the old WoS categories route land on the rankings page without an active/visible WoS section.

**Recommendation**

Update the redirect to an existing location, restore a WoS section, or retire the route with a clearer not-found/removed state.

## Checks Performed

### Public browser checks

- `/` loaded with public navigation.
- `/publications` loaded with `92,588 results`; first rows had non-empty publication links.
- `/forums` loaded with `40,632 results`; missing ISSNs rendered as `—`.
- `/authors` loaded with `216,304 results`; first rows had non-empty author links.
- `/rankings` loaded with CORE rankings; no browser console errors were observed on the checked public pages.

### Public/detail route checks

- `/publications/spub_4e5c2d1cd0ba384e97f5cfa2` -> `200`
- `/forums/21101146192` -> `200`
- `/authors/view/sauth_f6501b0f31f63ae70d734870` -> `200`
- `/core/rankings/HCOMP-AAAI%20Conference%20on%20Human%20Computation%20and%20Crowdsourcing` -> `200`
- `/universities/Harvard%20University` -> `200`

### Authenticated researcher checks

- `/user/workspace` -> `200`
- `/user/workspace/publications` -> `200` JSON payload
- `/user/workspace/activities` -> `200` JSON payload
- `/user/workspace/notifications` -> `200` empty JSON array
- `/user/evaluation` -> `200`

### Admin checks

- `/admin` -> `200`
- `/admin/users` -> `200`
- `/admin/institutions` -> `200`
- `/admin/groups` -> `200`
- `/admin/groupReports` -> `200`
- `/admin/individualReports` -> `200`
- `/admin/activities` -> `200`
- `/admin/indicators` -> `200`
- `/admin/domains` -> `200`
- `/admin/conflicts` -> `200`
- `/admin/user-defined-triage` -> `200`
- `/admin/initialization` -> `200`
- `/admin/incremental-updates` -> `200`
- `/admin/source-links` -> `200`
- `/admin/scholardex/forums` -> `200`
- `/admin/scholardex/publications` -> `200`
- `/admin/scholardex/authors` -> `200`
- `/admin/scholardex/affiliations` -> `200`
- `/admin/researchers` -> `302` to `/admin/users`

### API checks

- `/api/admin/users` -> `200`, sanitized user DTOs observed
- `/api/admin/researcher-profiles` -> `200`, finding above
- `/api/entities/authors` -> `200`
- `/api/entities/forums` -> `200`, finding above
- `/api/entities/affiliations` -> `200`
- `/api/rankings/core` -> `200`
- `/api/rankings/urap` -> `200`
- `/api/rankings/categories` -> `200`
- `/api/rankings/wos` -> `200` when authenticated
- `/actuator/health` -> `200` as admin

### Unauthenticated access-control spot checks

- `/admin`, `/admin/users`, `/user/workspace`, `/user/evaluation` -> `302` to `/login`
- `/api/admin/users` -> `401`
- `/api/admin/researcher-profiles` -> `401`
- `/api/entities/forums` -> `401`
- `/api/entities/affiliations` -> `401`
- `/api/rankings/wos` -> `401`
- `/api/entities/authors` -> `200`
- `/publications` -> `200`

## Notes

The in-app browser could inspect public pages, but direct browser login still hit an automation-specific issue when filling the `type=email` username field. Authenticated coverage was completed through the running app over HTTP using a successful `/login` session with the supplied credentials.

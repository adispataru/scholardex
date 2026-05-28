# QA Findings - Non-admin Surface - 2026-05-14

## Scope

- Target: `http://localhost:8080`
- Requested account: `florin.spataru@e-uvt.ro`
- Test date: 2026-05-14
- Browser note: the in-app browser could open and inspect public pages, but could not type into the login form's `type=email` field due to a browser automation limitation. Authenticated checks were completed through the same local app over HTTP using the successful `/login` session.

## Summary

The requested "non-admin" QA account is not a non-admin account in this environment. After login, it has `PLATFORM_ADMIN`, `SUPERVISOR`, and `RESEARCHER` roles, can switch to Admin and Supervisor workspaces, and can open `/admin` plus `/api/admin/users`. This blocks a clean non-admin-only access-control verdict with the provided credentials.

Authenticated researcher workspace endpoints did load successfully for the supplied user, and public catalog/ranking pages loaded. The main findings are role/scope contamination and several public catalog data/rendering defects.

## Findings

### Critical - Supplied non-admin QA account has admin and supervisor access

**Evidence**

- `GET /user/workspace` as `florin.spataru@e-uvt.ro` returned `200`.
- The workspace switcher rendered:
  - `/admin` - `Admin Panel`
  - `/supervisor` - `Supervisor Panel`
  - `/user/workspace`
- `GET /admin` returned `200` with `Admin Dashboard`.
- `GET /api/admin/users` returned `200` and included user records.
- The `/api/admin/users` payload shows the supplied user with roles: `PLATFORM_ADMIN`, `SUPERVISOR`, `RESEARCHER`.

**Impact**

This account cannot validate the true non-admin surface. It also means a QA pass using this account can miss authorization defects that would affect researcher-only users.

**Recommendation**

Provide or seed a researcher-only fixture account and re-run the access-control pass. If this account is expected to be researcher-only, remove the `PLATFORM_ADMIN` and `SUPERVISOR` roles from the fixture.

### High - Admin user API returns password hashes

**Evidence**

`GET /api/admin/users` returned user records containing `password` fields with BCrypt hashes, along with profile and role data.

**Impact**

Even for admin users, returning password hashes to the browser/API client is unnecessary exposure. Any client-side compromise, logging, or accidental export leaks credential material useful for offline attacks.

**Recommendation**

Remove `password` and other credential fields from all API response DTOs. Return explicit admin user DTOs rather than serialized security/domain user objects.

### Medium - Public forums catalog renders missing ISSN as `null-`

**Evidence**

`GET /forums/data?draw=1&start=0&length=5` returned items such as:

- `publicationName: "#Help: Digital Humanitarianism and the Remaking of International Order"`
- `issn: "null-"`

The `/forums` table then displays `null-` in the ISSN column.

**Impact**

Users see implementation/data sentinel values instead of a clean empty state. It makes the catalog look unreliable and complicates search/filter interpretation.

**Recommendation**

Normalize missing ISSN/eISSN values to empty/null in the data layer or response mapper, and render them as `-`/`—` consistently.

### Medium - Public catalog rows can have empty accessible link text

**Evidence**

The `/forums` first rows render blank forum names with links to `/forums/139013` and `/forums/21101046877`. The `/publications` page also has rows where the title is `—` but still links to a publication detail page.

**Impact**

Blank or placeholder-only links are poor for scanning and accessibility. Users cannot know what they are opening, and screen-reader users get weak link context.

**Recommendation**

Use a deterministic fallback label such as `Untitled forum {id}` / `Untitled publication {id}`, or suppress detail links until a displayable name/title exists.

### Low - Publication titles with markup render raw tags in table text

**Evidence**

The `/publications` catalog includes a title rendered in the accessibility tree as `τ→ μμμ at a rate of one out of 10 <sup>14</sup> tau decays?`.

**Impact**

Raw markup in visible/catalog text reads as data corruption and hurts accessibility.

**Recommendation**

Sanitize or transform source markup into plain text before rendering table cells, for example `10^14` or `10¹⁴`.

## Checks Performed

- Login form loaded at `/login`.
- Authenticated login via `/login` succeeded for `florin.spataru@e-uvt.ro`.
- Authenticated researcher routes:
  - `/user/workspace` -> `200`
  - `/user/workspace/publications` -> `200`, 21 publications, h-index 5, 79 citations
  - `/user/workspace/activities` -> `200`, 18 activity types, 17 activity instances
  - `/user/workspace/notifications` -> `200`, empty list
  - `/user/evaluation` -> `200`
- Public routes:
  - `/` -> `200`
  - `/publications` -> `200`
  - `/forums` -> `200`
  - `/rankings` -> `200`
- Unauthenticated access:
  - `/user/workspace`, `/user/workspace/publications`, `/user/evaluation`, `/admin` redirect to `/login`
  - `/api/admin/users` returns `401`
  - `/api/entities/forums` returns `401`

## Follow-up Needed

Re-run the access-control and browser interaction pass with a true researcher-only account. The supplied account is admin-capable, so non-admin authorization coverage is incomplete.

# Improvements

## Rankings Access Split (Done)

- Added shared read-only ranking pages under `/rankings/*`:
  - `/rankings/wos`
  - `/rankings/wos/{id}`
  - `/rankings/core`
  - `/rankings/core/{id}`
  - `/rankings/urap`
  - `/rankings/urap/{id}`
  - `/rankings/events`
- Kept ranking maintenance operations admin-only under `/admin/rankings/*`.
- Added explicit security matcher for shared rankings routes: `/rankings/**` must be authenticated.
- Kept backward compatibility by redirecting `/user/rankings/{id}` to `/rankings/wos/{id}`.
- Added user navigation links to ranking sections (WoS, CORE, URAP, Events).
- Removed dead event-detail link from shared events view.

## `/rankings/wos` Responsiveness At Large Scale (Planned)

Problem statement:
- `/rankings/wos` currently renders a very large dataset (23,389 rows) and then applies DataTables client-side.
- The table remains scrollable, but UI interactions (for example sidebar navigation) can block until DataTables initialization completes.

Chosen architecture:
- Move WoS table behavior to server-side pagination/filtering/sorting.
- Remove DataTables entirely from `/rankings/wos` (custom UI on top of paged API).
- Keep shared DataTables hardening as a separate follow-up for non-WoS pages.
- Keep URL and access behavior unchanged for ranking pages.

### Subtasks

1. [x] Baseline and success criteria
- Capture baseline metrics on `/rankings/wos`: first contentful paint, time-to-interactive, main-thread blocking time, and sidebar click responsiveness.
- Record baseline with dataset size around 23k rows in dev/staging.
- Set target SLOs:
  - Initial page interactive within 2 seconds on a standard dev laptop.
  - Sidebar interactions are never blocked by table enhancement.
  - No long task over 200ms caused by table initialization.

Baseline snapshot (5 runs, ~23,389 rows):
- `Total`: median `8,795 ms` (range `8,040`–`8,900`)
- `Scripting`: median `4,503 ms`
- `Rendering`: median `3,323 ms`
- `Painting`: median `433 ms`
- `Loading`: median `285 ms`
- `System`: median `135 ms`
- `Main thread [unattributed]`: median `8,236.8 ms`
- `Main thread localhost`: median `456.2 ms`
- `Transfer size (localhost)`: `31,809 kB`

Conclusion:
- Current behavior is far from target (`<= 2s` interactive).
- Dominant bottleneck is client-side main-thread work (consistent with large-table DataTables processing), not backend/network time.

2. [x] Server-side WoS data contract (defined)
- Endpoint: `GET /api/rankings/wos`
- Auth/security contract:
  - Accessible to authenticated roles: `PLATFORM_ADMIN`, `RESEARCHER`, `SUPERVISOR`
  - Unauthenticated requests return `401` JSON envelope (existing `/api/**` behavior)
  - Forbidden requests return `403` JSON envelope (existing `/api/**` behavior)
- Query params:
  - `page`: integer, default `0`, minimum `0`
  - `size`: integer, default `25`, allowed `1..100`
  - `sort`: string, default `name`, allowed `name|issn|eIssn`
  - `direction`: string, default `asc`, allowed `asc|desc`
  - `q`: optional string, trimmed, maximum length `100`; blank treated as absent
- Search semantics:
  - Case-insensitive contains-match over `name`, `issn`, `eIssn`, and any `alternativeIssns` value
- Response shape (`200`):
  - `items`: list of objects with `id`, `name`, `issn`, `eIssn`, `alternativeIssns`
  - pagination metadata: `page`, `size`, `totalItems`, `totalPages`
  - API remains UI-agnostic (no DataTables-specific fields like `draw`/`recordsTotal`)
- Validation/error behavior:
  - Invalid params (`page < 0`, `size` out of range, unknown `sort`, unknown `direction`, overlong `q`) return `400`
  - Error payload follows existing API envelope fields: `timestamp`, `status`, `error`, `path`, `message`
- Contract tests to implement:
  - Default request returns `200` and envelope keys
  - `page/size` paging metadata is consistent
  - Sorting works for all allowed columns and both directions
  - `q` matches by `name` and ISSN fields (`issn`, `eIssn`, `alternativeIssns`)
  - Invalid params return `400`
  - Unauthenticated request returns `401`; authenticated allowed roles return `200`

3. [x] Backend paging/query path
- Add service/repository support for efficient paging, sorting, and search.
- Ensure indexing strategy supports expected query paths (`name`, `issn`, `eIssn`).
- Keep `/rankings/wos` MVC route unchanged; only change how data is provided.
- Enforce auth contract: authenticated roles can read this API.

Outcome:
- Implemented `WosRankingQueryService` backed by `MongoTemplate` for server-side paging/sorting/search.
- Refactored `WosRankingApiController` to delegate query logic to the new service (contract unchanged).
- Added `@Indexed` fields on `WoSRanking`: `name`, `issn`, `eIssn`, `alternativeIssns`.
- Preserved Task 2 validation rules (`sort`, `direction`, `q`) and API response schema.
- Added/updated tests:
  - `WosRankingQueryServiceTest` (query behavior and invalid input handling)
  - `WosRankingApiControllerContractTest` (endpoint contract)
  - `ApiSecurityContractTest` (401 unauthenticated, 200 authenticated non-admin)

4. [x] `/rankings/wos` frontend incremental loading
- Replace full static table rendering with incremental fetch from `/api/rankings/wos`.
- Render first page only on initial load.
- Keep existing visible columns and detail links (`/rankings/wos/{id}`).
- Add loading, empty, and error states.
- Ensure sidebar/top-nav stays responsive while data loads.

Outcome:
- `/rankings/wos` no longer renders server-side table rows (`th:each` removed).
- DataTables bootstrap script was removed from this page.
- Added API-driven controls and states:
  - search: `#wos-search` (300ms debounce)
  - sort: `#wos-sort`
  - direction: `#wos-direction`
  - page size: `#wos-size`
  - loading/error/empty blocks: `#wos-loading`, `#wos-error`, `#wos-empty`
  - pagination controls: `#wos-prev`, `#wos-next`, `#wos-page-info`
  - total label: `#wos-total-info`
- Added client script `src/main/resources/static/js/rankings-wos.js`:
  - fetches `/api/rankings/wos` with `page,size,sort,direction,q`
  - renders rows incrementally and links to `/rankings/wos/{id}`
  - handles `200`, `400`, `401`, and generic errors
- Controller updated so `/rankings/wos` returns the shell view without loading full WoS dataset in model.
- MVC/security tests updated and passing for this contract.
- Added JS behavior tests (`scripts/test-rankings-wos.js`) covering:
  - default fetch params on load
  - control-change requests and page reset behavior
  - prev/next boundary handling
  - empty and error state rendering

5. [x] Shared DataTables bootstrap hardening (non-WoS follow-up)
- Replace global ID-prefix auto-init in `datatables-demo.js` with opt-in hooks only.
- Require explicit marker on tables (for example `.js-datatable`) instead of `[id^="dataTable"]`.
- Add safeguards:
  - Skip or alter enhancement above a configurable row threshold unless server-side mode is enabled.
  - Defer non-critical table init with `requestIdleCallback` and fallback to `setTimeout`.
- Preserve behavior for small tables.

Outcome:
- `datatables-demo.js` now initializes only `table.js-datatable` (no ID-prefix auto-init).
- Added non-blocking/deferred startup via `requestIdleCallback` (with `setTimeout` fallback).
- Added client-side safety guard:
  - skips DataTables initialization above default `2000` rendered rows unless `data-datatable-server=\"true\"`.
  - per-table threshold override supported via `data-datatable-max-rows`.
- Existing dataTable pages were migrated to explicit opt-in marker `.js-datatable`.
- Added guardrail script `scripts/verify-datatables-optin.js` and npm command `verify-datatables-optin`.

6. [x] WoS DataTables removal policy
- Disable DataTables entirely for `/rankings/wos`.
- Use custom controls + paged API loading as the only table behavior.
- Prevent DOM inflation by never rendering all 23k rows at once.

Outcome:
- `/rankings/wos` no longer includes `/js/demo/datatables-demo.js`.
- WoS table rendering is API-driven from `GET /api/rankings/wos` with incremental page loads.
- No DataTables server-side mode was introduced for WoS; page uses custom framework-free JS.
- `/admin/rankings/wos` now follows the same API-driven incremental loading approach (admin actions retained), also without DataTables.

7. [x] Backward compatibility and UX parity
- Preserve `/rankings/wos` URL and current access semantics.
- Preserve visible table schema and navigation behavior.
- Preserve keyboard accessibility and basic search.
- Keep admin-only operations unchanged under `/admin/rankings/*`.

Outcome:
- URL and access remain unchanged (`/rankings/wos` under authenticated shared rankings area).
- Forum/ISSN/eISSN/Alt. ISSN columns and detail links are preserved.
- Basic search is preserved via `#wos-search`.
- Admin-only operations remain under `/admin/rankings/*`.

8. [x] Contract and regression tests
- Backend API tests:
  - valid paging/sorting/search combinations
  - invalid params and bounds -> `400`
  - auth matrix: `RESEARCHER`, `SUPERVISOR`, `PLATFORM_ADMIN` allowed; unauthenticated denied
- MVC/UI tests:
  - `/rankings/wos` renders shell without requiring full dataset in model
  - sidebar links remain present
- JS/integration tests:
  - page remains interactive while table data loads
  - pagination/search issue expected API calls
  - WoS behavior validated without DataTables coupling

Outcome:
- Backend API/security tests implemented and passing:
  - `WosRankingApiControllerContractTest`
  - `WosRankingQueryServiceTest`
  - `ApiSecurityContractTest`
- MVC/view contract tests implemented and passing:
  - `RankingViewControllerContractTest`
  - `RankingViewSecurityContractTest`
- JS behavior tests implemented and passing:
  - `scripts/test-rankings-wos.js` via `npm run test-rankings-wos`

9. [ ] Performance verification and rollout
- Re-measure metrics and validate against SLOs.
- Add lightweight telemetry for table-load duration and API latency.
- Rollout stages:
  - Stage 1: enable on `/rankings/wos` in staging
  - Stage 2: production enablement
  - Stage 3: evaluate migration of other large tables

10. [x] Implementation documentation
- Document in this file after rollout:
  - final API contract
  - performance before/after numbers
  - migration notes for pages that relied on global DataTables auto-init

Outcome:
- Baseline and implemented architecture are documented in this file.
- WoS direction is now explicitly recorded as “no DataTables on `/rankings/wos`”.
- Remaining rollout/performance verification is tracked as Task 9.

Acceptance criteria:
- `/rankings/wos` remains responsive with large datasets (>=23k rows).
- Sidebar/navigation interactions are immediately usable after page load.
- WoS ranking data supports paging/sorting/search through server-side API.
- `/rankings/wos` uses custom incremental loading and does not use DataTables.

## Admin Scopus Forums Incremental Loading + Venues->Forums Shift (Done)

- Added paged read API for forums:
  - `GET /api/scopus/forums`
  - Params:
    - `page` (default `0`, min `0`)
    - `size` (default `25`, range `1..100`)
    - `sort` (`publicationName|issn|eIssn|aggregationType`, default `publicationName`)
    - `direction` (`asc|desc`, default `asc`)
    - `q` (optional, trimmed, max `100`)
  - Response envelope:
    - `items` (`id`, `publicationName`, `issn`, `eIssn`, `aggregationType`)
    - `page`, `size`, `totalItems`, `totalPages`
- API security:
  - `/api/scopus/forums/**` is available to all authenticated roles (`PLATFORM_ADMIN`, `RESEARCHER`, `SUPERVISOR`).
- Backend query path:
  - Implemented `ScopusForumQueryService` with `MongoTemplate` paging/sorting/search.
  - Added indexes on `Forum`: `publicationName`, `issn`, `eIssn`, `aggregationType`.
- Admin list page migrated to incremental loading:
  - Canonical route: `GET /admin/scopus/forums`
  - `admin/scopus-venues.html` now renders shell + controls only (no full dataset model, no DataTables).
  - New page script: `src/main/resources/static/js/admin-scopus-forums.js`.
- Route/UI shift to forums with compatibility:
  - Canonical edit routes:
    - `GET /admin/scopus/forums/edit/{id}`
    - `POST /admin/scopus/forums/edit/{id}`
  - Legacy compatibility redirects:
    - `GET /admin/scopus/venues` -> `/admin/scopus/forums`
    - `GET /admin/scopus/venues/edit/{id}` -> `/admin/scopus/forums/edit/{id}`
  - Legacy POST compatibility kept:
    - `POST /admin/scopus/venues/edit/{id}` delegates update and redirects to canonical forums edit route.
  - Sidebar link now points to `/admin/scopus/forums` with active section normalized to `scopus-forums`.
- DataTables guardrail alignment:
  - Guardrail script now enforces that `admin/scopus-venues.html` (API-driven page) does not include DataTables bootstrap.
- Tests added/updated:
  - `ScopusForumApiControllerContractTest`
  - `ScopusForumQueryServiceTest`
  - `ApiSecurityContractTest` (forums API auth matrix)
  - `AdminViewControllerContractTest` (forums shell + compatibility redirects/post route)
  - `scripts/test-admin-scopus-forums.js` (JS behavior contract)

## Admin Scopus Authors + Affiliations Incremental Loading (Done)

- Added paged read APIs:
  - `GET /api/scopus/authors`
    - params: `afid` (default `60000434`), `page`, `size`, `sort` (`name|id`), `direction`, `q`
    - search fields: `name`, `id`
    - response: `items` (`id`, `name`, `affiliations`) + paging envelope
  - `GET /api/scopus/affiliations`
    - params: `page`, `size`, `sort` (`name|afid|city|country`), `direction`, `q`
    - search fields: `name`, `afid`, `city`, `country`
    - response: `items` (`afid`, `name`, `city`, `country`) + paging envelope
- API security:
  - `/api/scopus/authors/**` and `/api/scopus/affiliations/**` are available to all authenticated roles (`PLATFORM_ADMIN`, `RESEARCHER`, `SUPERVISOR`).
- Backend query path:
  - Implemented `ScopusAuthorQueryService` and `ScopusAffiliationQueryService` with `MongoTemplate` paging/sorting/search.
  - Added indexes:
    - `Author`: `name`
    - `Affiliation`: `name`, `city`, `country`
- Admin list pages migrated to incremental loading:
  - `GET /admin/scopus/authors` now renders shell (no full authors model) and keeps `afid` default behavior via `defaultAfid`.
  - `GET /admin/scopus/affiliations` now renders shell (no full affiliations model).
  - Added scripts:
    - `src/main/resources/static/js/admin-scopus-authors.js`
    - `src/main/resources/static/js/admin-scopus-affiliations.js`
  - Both pages no longer include DataTables bootstrap.
- Edit author page adjustment:
  - `admin/scopus-editAuthor.html` keeps server-rendered table but removes DataTables include and `.js-datatable` usage.
- Guardrails:
  - `scripts/verify-datatables-optin.js` now enforces no DataTables bootstrap on:
    - `admin/scopus-authors.html`
    - `admin/scopus-affiliations.html`
    - `admin/scopus-editAuthor.html`
- Tests added/updated:
  - `ScopusAuthorApiControllerContractTest`
  - `ScopusAffiliationApiControllerContractTest`
  - `ScopusAuthorQueryServiceTest`
  - `ScopusAffiliationQueryServiceTest`
  - `ApiSecurityContractTest` (authors/affiliations API auth matrix)
  - `AdminViewControllerContractTest` (authors/affiliations shell + edit author no-DataTables check)
  - JS behavior tests:
    - `scripts/test-admin-scopus-authors.js`
    - `scripts/test-admin-scopus-affiliations.js`

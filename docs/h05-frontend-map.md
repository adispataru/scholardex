# H05-S01 Frontend Structure Map and Duplication Baseline

Date: 2026-03-03  
Scope: `frontend/src/**` + `src/main/resources/templates/**`

## 1. Method

- Enumerated template and frontend source structure.
- Measured shared fragment usage and asset include patterns.
- Measured inline script prevalence and repeated legacy script includes.
- Captured out-of-band pages not covered by current template asset checks.

## 2. Current Frontend Topology

### 2.1 Entry points and assets

- Frontend source:
  - `frontend/src/app.js` (single JS/CSS entrypoint)
- Built assets:
  - `src/main/resources/static/assets/app.css`
  - `src/main/resources/static/assets/app.js`
- Legacy runtime JS still used in templates:
  - `src/main/resources/static/js/sb-admin-2.min.js`
  - `src/main/resources/static/js/demo/datatables-demo.js`

### 2.2 Template surface

- Total HTML templates: `63`
- Admin templates: `38`
- User templates: `20`
- Other templates (errors/publications/fragments): `5`

## 3. Composition Baseline

### 3.1 Shared fragment adoption

- Templates using `fragments :: navbar/admin-sidebar/user-sidebar`: `58/63`
- Templates not using shared layout fragments:
  - `src/main/resources/templates/errors/error-403.html`
  - `src/main/resources/templates/errors/error-404.html`
  - `src/main/resources/templates/errors/error-500.html`
  - `src/main/resources/templates/publications/list.html`
  - `src/main/resources/templates/fragments.html` (fragment definition file)

### 3.2 Asset include consistency

- Admin + user templates with `/assets/app.css` and `/assets/app.js`: `58`
- Admin + user templates including `/js/sb-admin-2.min.js`: `58`
- Templates referencing `datatables-demo.js` variants: `46`
- Inline script blocks (`<script>` without `src`): `38`
  - admin: `22`
  - user: `16`
- `th:inline="javascript"` blocks: `23`

## 4. Drift and Duplication Hotspots

### C-H05-01: Asset stack duplication in templates (high)

- Repeated include trio appears across most pages:
  - `/assets/app.js`
  - `/js/sb-admin-2.min.js`
  - `/js/demo/datatables-demo.js`
- Evidence:
  - `/assets/app.js` occurrences: `58`
  - `/js/sb-admin-2.min.js` occurrences: `58`
  - datatables demo occurrences: `46`
- Risk:
  - inconsistent script order and page-specific script drift.
  - difficult migration to modular page-level JS.

### C-H05-02: Inline behavior logic spread across templates (high)

- Inline script blocks are widely distributed (`38` blocks).
- Many are form-specific dynamic UI snippets repeated across edit/add pages.
- Risk:
  - behavior drift across near-identical screens.
  - brittle template-level JS maintenance and low testability.

### C-H05-03: Legacy external CDN usage outside main app shell (medium-high)

- Pages still loading external CDN assets directly:
  - error pages (`error-403/404/500`)
  - `publications/list.html`
  - user publication add steps load jQuery UI from CDN
  - `admin/rankings-urap-details.html` loads Chart.js from CDN
- Risk:
  - inconsistent dependency source and runtime behavior.
  - bypasses bundled asset discipline and version control.

### C-H05-04: Datatables demo script naming inconsistency (medium)

- One user page uses different case/path:
  - `user/tasks.html` -> `/js/demo/dataTables-demo.js`
- Majority use:
  - `/js/demo/datatables-demo.js`
- Risk:
  - path inconsistency across environments/filesystems.
  - hidden breakage risk and style drift signal.

### C-H05-05: Verification coverage gap for non-admin/user templates (medium)

- `verify-template-assets` checks only:
  - `src/main/resources/templates/admin/**`
  - `src/main/resources/templates/user/**`
- Not checked:
  - `errors/**`, `publications/**`
- Risk:
  - style and asset discipline drift can re-enter through unguarded templates.

## 5. Priority Next-Look List (for H05-S02/S03/S04)

1. `C-H05-02` Inline script extraction baseline (admin/user edit+report pages first).
2. `C-H05-01` Shared script include policy and centralized template composition.
3. `C-H05-03` CDN dependency policy alignment (error/public pages + jQuery UI/Chart.js decisions).
4. `C-H05-05` Extend verifier scope or define explicit exception policy.
5. `C-H05-04` Datatables demo path normalization.

## 6. Open Decisions Seeded for H05-S02

- Keep or retire `sb-admin-2.min.js` for all pages vs selective usage.
- Standard policy for third-party CDN usage in templates (forbid vs allowlist).
- Whether `errors/**` and `publications/**` must adopt the same app shell as admin/user.
- Preferred structure for page-level JS modules beyond single `frontend/src/app.js`.

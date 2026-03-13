# H05-S02 Frontend Conventions and Ownership Rules

Date: 2026-03-03  
Inputs: `docs/h05-frontend-map.md`, `docs/h02-dependency-rules.md`, `docs/h04-reliability-guardrails.md`

## 1. Purpose

Define explicit frontend structure rules so template and JS changes are consistent, testable, and resistant to copy-paste drift.

## 2. Ownership Model

### 2.1 Zones

- `Z6-Template`: `src/main/resources/templates/**`
- `Z6-Frontend`: `frontend/src/**`
- `Z6-Static`: `src/main/resources/static/**` (generated assets + transitional legacy scripts)

### 2.2 Ownership Rules

- Template ownership is feature-area scoped:
  - admin pages: `templates/admin/**`
  - user pages: `templates/user/**`
  - shared composition: `templates/fragments.html`
- Shared JS/CSS behavior belongs in `frontend/src/**`, not duplicated inline in templates.
- Static `assets/app.css` and `assets/app.js` are build outputs and must remain the default app contract.

## 3. Composition Conventions

### 3.1 Mandatory layout contract (runtime pages)

Each runtime page under `templates/admin/**`, `templates/user/**`, `templates/rankings/**`, and `templates/scholardex/**` must:

- include `/assets/app.css`
- include `/assets/app.js`
- use `fragments :: navbar`
- use role-appropriate sidebar fragment (`admin-sidebar` or `user-sidebar`)

### 3.2 Template composition anti-patterns

Forbidden for new/edited pages:

- duplicating sidebar/navbar markup instead of fragments
- adding new `*-bak.html` templates in runtime directories
- page-specific copy-paste JS blocks for behavior that appears in multiple pages

## 4. JavaScript Conventions

### 4.1 Module structure

- Keep `frontend/src/app.js` as the bootstrap entrypoint.
- Add feature modules under:
  - `frontend/src/modules/admin/**`
  - `frontend/src/modules/user/**`
  - `frontend/src/modules/shared/**`
- Modules are initialized from `app.js` via DOM-presence checks (`data-*` hooks preferred).

### 4.2 Inline script policy

- Inline scripts are transitional debt only.
- For new or changed behavior:
  - move logic into `frontend/src/modules/**`
  - keep inline script only for minimal data bootstrapping when needed (`th:inline="javascript"` data payload), not behavior logic.

### 4.3 Legacy JS policy (`sb-admin`, datatables demo)

- `sb-admin-2.min.js` and `js/demo/datatables-demo.js` remain allowed as transitional dependencies.
- No new page may introduce additional legacy demo scripts.
- When touching a page already using them, prefer migration to shared modules instead of expanding legacy usage.

## 5. Third-Party Dependency Policy

### 5.1 Default rule

- Third-party UI dependencies should be bundled through `frontend/src/app.js` and npm dependencies.
- Direct CDN `<script>`/`<link>` includes are disallowed for new admin/user template changes.

### 5.2 Transitional allowlist (current state)

Allowed temporarily until dedicated migration slice:

- jQuery UI CDN includes in:
  - `user/publications-add-step1.html`
  - `user/publications-add-step2.html`
- Chart.js CDN include in:
  - `admin/rankings-urap-details.html`
- standalone error/public pages (`errors/**`, `publications/list.html`) may keep external CDN until brought under app-shell policy.

All new CDN includes outside this allowlist are prohibited.

## 6. Verification and Guardrails

- Existing mandatory checks:
- Existing mandatory checks:
  - `npm run verify-assets`
  - `npm run verify-template-assets`
  - `npm run verify-architecture-boundaries`
  - `./gradlew test --tests "*UserViewControllerContractTest" --tests "*AdminViewControllerContractTest"`
  - `npm run verify-h23-ui` when touching canonical H23 route-owned pages (`scholardex/**`, `rankings/categories`, admin Scholardex catalog pages)
- `verify-template-assets` guardrails enforce:
  - runtime template coverage for `admin/**`, `user/**`, `rankings/**`, and `scholardex/**`
  - no `*-bak.html` templates under runtime admin/user roots
  - core asset contract (`/assets/app.css` + `/assets/app.js` via direct include or shared fragments)
  - external CDN references only from explicit H05 transitional allowlist files
  - no new inline behavior scripts outside explicit transitional allowlist
  - canonical datatables demo path (`/js/demo/datatables-demo.js`)

## 7. Review Checklist (Frontend Changes)

1. Does the page preserve the app asset contract (`/assets/app.css`, `/assets/app.js`)?
2. Is shared layout built via fragments instead of duplicated markup?
3. Did the change avoid adding new inline behavior logic?
4. Are third-party dependencies bundled (or explicitly allowlisted transitional exceptions)?
5. If touching legacy scripts, did the change reduce rather than expand legacy usage?

## 8. Defaults Locked for H05

- `sb-admin-2.min.js` is transitional and allowed short-term, but migration target is shared module initialization.
- CDN usage is allowlist-only; no new CDN dependencies are allowed.
- `errors/**` and `publications/**` are in-scope for future shell/asset unification, but not forcibly migrated in S02.
- Feature JS structure target is modular (`modules/admin|user|shared`) with `app.js` bootstrap.

## 9. H05 Closeout and H06 Handoff

Status: H05 completed on 2026-03-03.

- Baseline established:
  - shared core template composition primitives are active (`core-styles`, `core-scripts`)
  - shared frontend utilities are active for repeated subtype and DOM behavior patterns
  - template drift guardrails are automated (`verify-template-assets`)
  - frontend-facing controller contract checks are in place for touched high-impact pages
- Ongoing contributor workflow for frontend changes:
  - `npm run build`
  - `npm run verify-assets`
  - `npm run verify-template-assets`
  - `npm run verify-h23-ui` for route-level changes on canonical Scholardex/category pages
  - `./gradlew test --tests "*UserViewControllerContractTest" --tests "*AdminViewControllerContractTest"`
- H06 handoff focus:
  - keep H05 guardrails unchanged while reviewing data/persistence consistency
  - treat any frontend/template behavior changes discovered during H06 as explicit contract updates (tests + docs).

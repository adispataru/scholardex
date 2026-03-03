# Vendor Asset Migration Tasks

Tracking migration from `/vendor/*` assets to bundled `/assets/*` assets.

- [x] `T01` Goal: Create task tracker and migration guardrails.
  Files/areas: `/TASKS.md`
  Automated checks: `./gradlew test`
  Done criteria: tracker exists with ordered, test-gated tasks.
  Notes: Completed.

- [x] `T02` Goal: Introduce frontend toolchain (npm + bundler) without switching templates yet.
  Files/areas: `/package.json`, lockfile, bundler config, `frontend/` source dir.
  Automated checks: `npm ci`, `npm run build`, `./gradlew test`
  Done criteria: deterministic assets generated under `src/main/resources/static/assets/`.
  Notes: Completed. `package.json` + lockfile present and install/build checks pass.

- [x] `T03` Goal: Wire baseline vendor equivalents into bundled entrypoints.
  Files/areas: `package.json`, frontend entrypoint files, build scripts.
  Automated checks: `npm run build`, `npm run verify-assets`, `./gradlew test`
  Done criteria: bundle contract includes Bootstrap, jQuery, DataTables, Chart.js, Font Awesome, jquery-easing.
  Notes: Completed with committed `app.css`/`app.js` and npm entrypoint definitions.

- [x] `T04` Goal: Add automated template asset-path validation.
  Files/areas: `scripts/verify-template-assets.js`, npm script wiring.
  Automated checks: `npm run verify-template-assets`, `./gradlew test`
  Done criteria: validator fails on reintroduced `/vendor/` usage.
  Notes: Completed.

- [x] `T05` Goal: Incremental migration batch A (shared pages/fragments).
  Files/areas: shared template patterns used by migrated pages.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: migrated batch has no direct `/vendor/` references.
  Notes: Completed.

- [x] `T06` Goal: Incremental migration batch B (admin pages).
  Files/areas: `src/main/resources/templates/admin/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: admin templates use bundled assets and no `/vendor/...` remains.
  Notes: Completed (excluding `*-bak.html` backups from strict validator).

- [x] `T07` Goal: Incremental migration batch C (user pages).
  Files/areas: `src/main/resources/templates/user/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: user templates no longer depend on `/vendor/...`.
  Notes: Completed.

- [x] `T08` Goal: Remove obsolete vendor tree and machine artifacts.
  Files/areas: `src/main/resources/static/vendor/**`, `.gitignore`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`, `rg -n '/vendor/' src/main/resources/templates`
  Done criteria: no production template refs to `/vendor/`; `.DS_Store` ignored.
  Notes: Completed.

- [x] `T09` Goal: Documentation and developer workflow finalization.
  Files/areas: `README.md`, `CONTRIBUTING.md`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: docs reflect reproducible frontend + backend verification commands.
  Notes: Completed.

- [x] `T10` Goal: Final regression gate and signoff.
  Files/areas: `TASKS.md` status updates.
  Automated checks: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: all checks green and tasks complete.
  Notes: Completed. Full gate passed: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew check`.

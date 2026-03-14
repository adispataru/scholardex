# Quality Gates

Status: active contributor validation baseline.

## Baseline Commands

Run the minimum baseline before merging changes:

```bash
npm run build
npm run verify-assets
npm run verify-template-assets
npm run verify-duplication-guardrails
npm run verify-architecture-boundaries
./gradlew test
```

## Focused Verification

- Docs tree changes: `npm run verify-docs-governance`
- Route and shared UI changes: `npm run verify-h25-route-guardrails`
- CI-sensitive changes: `npm run verify-h09-baseline`
- Persistence changes: `npm run verify-h06-persistence`
- Security/validation changes: `npm run verify-h07-guardrails`
- Observability changes: `npm run verify-h08-baseline`

## Rule

Choose the union of relevant command sets for the touched area. Keep task-specific validation evidence in `TASKS-done.md` and task docs, not here.

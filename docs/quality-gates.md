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
- Route and shared UI changes: `npm run verify-route-guardrails`
- CI-sensitive changes: `npm run verify-quality-gates-baseline`
- Runtime naming cleanup changes: `npm run verify-runtime-naming-guardrails`
- Persistence changes: `npm run verify-persistence-contracts`
- Security/validation changes: `npm run verify-security-validation-guardrails`
- Observability changes: `npm run verify-observability-baseline`

## Rule

Choose the union of relevant command sets for the touched area. Keep task-specific validation evidence in `TASKS-done.md` and task docs, not here.

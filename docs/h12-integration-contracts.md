# H12 Integration Contracts

Date: 2026-03-04  
Scope: Scopus scheduler/import paths and ranking importers (`RankingService`, `CoreConferenceRankingService`, `URAPRankingService`).

## Retry Matrix

Scopus scheduler tasks (`ScopusPublicationUpdate`, `ScopusCitationsUpdate`) now use bounded retries with persisted metadata:

- `attemptCount`
- `maxAttempts`
- `nextAttemptAt`
- `lastErrorCode`
- `lastErrorMessage`

Outcome rules:

- retryable failures (`EXTERNAL_TIMEOUT`, `EXTERNAL_5XX`) and `attemptCount < maxAttempts`:
  - status returns to `PENDING`
  - message starts with `RETRY_SCHEDULED: ...`
  - `nextAttemptAt` set using bounded backoff
- non-retryable failures (`VALIDATION_ERROR`, `EXTERNAL_BAD_PAYLOAD`, `PERSISTENCE_ERROR`) or exhausted retries:
  - status `FAILED`
  - `executionDate` set
  - message starts with `FAILED: ...`
- successful execution:
  - status `COMPLETED`
  - retry/error metadata cleared

## Partial Import Policy

H12 applies strict mapping + partial processing:

- malformed external rows/items are skipped,
- valid rows/items continue,
- deterministic summary counters are recorded and logged:
  - processed
  - imported
  - updated (where applicable)
  - skipped
  - errors
  - bounded `errorsSample`

Applied in:

- `ScopusDataService` publication/citation imports
- `RankingService` Excel batch imports
- `CoreConferenceRankingService` CSV batch imports
- `URAPRankingService` Excel batch imports

## Error Taxonomy

`IntegrationErrorCode`:

- `VALIDATION_ERROR`
- `EXTERNAL_TIMEOUT`
- `EXTERNAL_5XX`
- `EXTERNAL_BAD_PAYLOAD`
- `PERSISTENCE_ERROR`

Scheduler retry behavior is derived from this taxonomy (retryable vs terminal).

## Deterministic Scheduler Date Behavior

H12 removes baseline hacks and normalizes incremental logic:

- removed forced reimport override from `computeFromDate`
- removed constant citation date fallback
- cover-date parsing uses explicit optional parsing; invalid dates are excluded from cutoff computation instead of silently mapped to `now`

## Guardrail

`npm run verify-h12-integrations` validates:

- no forced full-reimport hacks in scheduler,
- retry/backoff metadata and logic markers exist,
- Scopus mapping hardening markers exist,
- no unsafe chained `rootNode.get(...).get(...)` remains in `ScopusDataService`,
- ranking importer summary accounting markers are present.

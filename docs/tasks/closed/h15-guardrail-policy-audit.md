# H15.1 Guardrail Policy Audit (Post-H14)

Date: 2026-03-06  
Status: historical archived audit note; retained for H15 closure traceability.

## Summary

This audit locks which failing guardrail checks are stale after H14 architecture changes and defines exact replacement checks for H15.2.

Scope audited:
- `scripts/verify-h06-persistence.js`
- `scripts/verify-duplication-guardrails.js`

No runtime code behavior is changed in H15.1.

## Current Architecture Truth (Locked)

1. WoS primary ranking/report lookup is projection/fact-backed, not `CacheService`-ranking-cache-backed.
2. CS combined publication scoring only scores `ar/re/cp`; `bk/ch` publication subtypes are ignored (empty score).
3. CS activity scoring still delegates `Book`/`Book Series` to `ComputerScienceBookService`.
4. User publication citation view supports compatibility lookup by `id` or `eid`; edit/update path uses canonical `id` only.

## Stale Check Inventory and Replacement Rules

| Script | Check (current) | Classification | Replacement rule (H15.2) | Risk if unchanged |
|---|---|---|---|---|
| `verify-h06-persistence` | Require `cacheRankingByIssnKey(r.getIssn(), r)` in `CacheService` | stale | Remove WoS ranking-cache prefill requirement entirely | False failures after H14 cache minimization |
| `verify-h06-persistence` | Require `cacheRankingByIssnKey(r.getEIssn(), r)` in `CacheService` | stale | Remove WoS ranking-cache prefill requirement entirely | False failures after H14 cache minimization |
| `verify-h06-persistence` | Require `rankingRepository.findAllByIssn(key)` miss path in `CacheService` | stale | Remove WoS ranking repository miss-path requirement entirely | Encodes retired pre-H14 architecture |
| `verify-h06-persistence` | Require `rankingRepository.findAllByEIssn(key)` miss path in `CacheService` | stale | Remove WoS ranking repository miss-path requirement entirely | Encodes retired pre-H14 architecture |
| `verify-h06-persistence` | Forbid any `findByEid(` in `UserPublicationFacade` | stale | Narrow to edit/update methods only; allow `buildCitationsView` compatibility fallback `findById(...).or(() -> findByEid(...))` | Blocks intended backward-compatible citation lookup |
| `verify-duplication-guardrails` | Require publication `bk/ch -> bookScoringService.getScore(publication, indicator)` in `ComputerScienceScoringService` | stale | Require publication switch handles only `ar/re/cp`; default returns empty score | Reintroduces wrong CS publication policy |

## Checks That Stay Valid

- `verify-duplication-guardrails`:
  - `ScoringFactoryService` must not return `null`.
  - Activity `Book`/`Book Series` delegation to `bookScoringService` stays required.
  - Shared `assets-contract` import checks stay required.
- `verify-h06-persistence`:
  - year parsing guardrails (`PersistenceYearSupport`) stay required.
  - typo/method namespace checks stay required (`findAllByEIssn` etc.).
  - canonical edit/update path in `UserPublicationFacade` stays required (`findById` in dedicated edit methods).

## Source-of-Truth Mapping (Rule -> Code -> Test)

| Policy | Enforcing code | Matching test |
|---|---|---|
| WoS reads are projection/fact-backed | `ProjectionBackedReportingLookupFacade` + `CacheBackedReportingLookupFacade` | `ProjectionBackedReportingLookupFacadeTest`, `CacheBackedReportingLookupFacadeTest` |
| CS publication `bk/ch` ignored | `ComputerScienceScoringService#getScore(Publication, Indicator)` | `ComputerScienceScoringServiceTest#bkSubtypeFallsBackToEmptyScore`, `#chSubtypeFallsBackToEmptyScore` |
| CS activity books delegated | `ComputerScienceScoringService#getScore(ActivityInstance, Indicator)` | `ComputerScienceScoringServiceTest#bookAggregationDelegatesToBookScoringService`, `#bookSeriesAggregationDelegatesToBookScoringService` |
| Citation lookup keeps `id/eid` compatibility | `UserPublicationFacade#buildCitationsView` | `UserPublicationFacadeTest#buildCitationsViewSortsCitationsDeterministically` |
| Edit/update uses canonical `id` | `UserPublicationFacade#findPublicationForEdit`, `#updatePublicationMetadata` | `UserPublicationFacadeTest#findPublicationForEditUsesCanonicalIdLookup` |

## H15.2 Handoff (Decision-Complete)

1. Update `verify-h06-persistence.js`:
- Remove the four WoS ranking-cache/repository assertions tied to `CacheService`.
- Replace broad `assertNotContains(userPublicationFacadeContent, 'findByEid('...)` with method-sliced assertions:
  - `findPublicationForEdit` must use `findById`.
  - `updatePublicationMetadata` must use `findById`.
  - `buildCitationsView` may include `findById(...).or(() -> findByEid(...))`.

2. Update `verify-duplication-guardrails.js`:
- Replace `bk/ch` publication delegation requirement with:
  - require publication switch includes `case "ar", "re"` and `case "cp"`;
  - require default branch returns `createEmptyScore()`;
  - forbid `case "bk", "ch" -> bookScoringService.getScore(publication, indicator)` in publication path.
- Keep activity `Book/Book Series` delegation assertion unchanged.

3. Do not change runtime services in H15.2 unless a check reveals actual drift from the locked source-of-truth mapping above.

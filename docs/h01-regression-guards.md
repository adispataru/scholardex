# H01-S06 Regression Guards

Date: 2026-03-03

## Scope

- `C01` CNFIS 2025 scoring behavior guards
- `C04` scoring-family dispatch/factory behavior guards
- `C06` asset/template contract guard commands

## Added Automated Tests

### C01

- File: `src/test/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService2025Test.java`
- Guards:
  - subtype fallback: `subtype=cp` is used when `scopusSubtype` is missing.
  - subtype precedence: `scopusSubtype` wins when both fields are present.
  - resilient category parsing: category without `-` still processed.
  - null/blank category input does not crash; processing continues safely.
  - proceedings behavior: `cp` non-IEEE with WoS id sets `isiProceedings`.
  - proceedings edge behavior: `cp` non-IEEE without WoS id does not set proceedings flags.
  - chapter proceedings behavior: `ch` with Lecture Notes + WoS id sets `isiProceedings`; without WoS id it does not.
  - malformed `coverDate` falls back safely and does not crash.
  - missing/blank subtype data does not crash and keeps subtype-specific flags unset.

### C04

- File: `src/test/java/ro/uvt/pokedex/core/service/reporting/ScoringCategorySupportTest.java`
- Guards:
  - shared category normalization and extraction behavior for null/blank/malformed/delimited values.
  - domain/index eligibility behavior (`ALL`, domain-specific, `SCIE/SSCI` checks).

- File: `src/test/java/ro/uvt/pokedex/core/service/reporting/CategoryDomainContractTest.java`
- Guards:
  - `AbstractForumScoringService` and `AbstractWoSForumScoringService` category-domain decisions remain aligned for valid and malformed inputs.

- File: `src/test/java/ro/uvt/pokedex/core/service/reporting/PublicationSubtypeSupportTest.java`
- Guards:
  - subtype resolution policy is explicit and consistent (`scopusSubtype` preferred, `subtype` fallback).

- File: `src/test/java/ro/uvt/pokedex/core/service/reporting/ScoringFactoryServiceTest.java`
- Guards:
  - known strategy mapping resolves expected service.
  - unmapped strategy fails fast with `IllegalArgumentException` (updated in H01-S07).
  - null strategy fails fast with `IllegalArgumentException` (updated in H01-S07).

- File: `src/test/java/ro/uvt/pokedex/core/service/reporting/ComputerScienceScoringServiceTest.java`
- Guards:
  - `bk` publication subtype delegates to `ComputerScienceBookService`.
  - `ch` publication subtype delegates to `ComputerScienceBookService`.
  - `Book` and `Book Series` activity forum types delegate to `ComputerScienceBookService`.
  - `scopusSubtype=cp` dispatches through conference scoring even when legacy `subtype` is null.
  - unknown subtype still returns safe empty score fallback.

## Command-Level Guards (C06)

- Existing script checks retained as regression gates:
  - `npm run verify-assets`
  - `npm run verify-template-assets`
- These enforce:
  - required built asset contract presence,
  - no forbidden `*-bak.html` templates,
  - required `/assets/app.css` and `/assets/app.js` references in runtime templates.

## Validation Run

- `./gradlew test --tests "*CNFISScoringService2025Test" --tests "*ScoringFactoryServiceTest" --tests "*ComputerScienceScoringServiceTest"`: passed.

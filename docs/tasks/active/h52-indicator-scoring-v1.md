# H52 Indicator / Scoring / Formula Flow — v1

**Status:** Design (no implementation yet)
**Created:** 2026-05-30

## Purpose

The current report → indicator → scoring → formula flow works but is held together by
two parallel enums, an open-bag `Map<String,Object>` for inter-service data, a string-typed
formula re-parsed by MVEL on every researcher iteration, a hand-written `if/else if` ladder
that maps strategies to services, and a hardcoded `LAST_YEAR = 2023`. Real users run real
reports against it. Touching anything is hostile to change.

v1 keeps the math, replaces the scaffolding. **Goal: every existing indicator continues to
produce the same numerical result; the next engineer to add an indicator can do it without
fear.**

Scope is deliberately narrow:

- Type-safe indicator kind hierarchy replacing the (OutputType × Strategy) enum cross-product.
- Per-strategy variable contract enforced at indicator-save time.
- Compile-once / cache-on-write MVEL formulas, with sandboxing.
- Decomposed `Score` and explicit `Provenance`.
- Strategy → service registry as a `Map`, not an `if/else` ladder.
- Year-range value types with absolute / item-year / all-years variants.
- `LAST_YEAR` derived from data, not hardcoded.
- Dead-code removal as part of the migration (see "Drop in v1" below).

Out of scope for this `Hxx`:

- Refactoring `ComputerScienceConferenceScoringService` (1,251 lines). Stays under the new
  `ScoringService` interface, untouched internally. Its own follow-up.
- Replacing MVEL with another expression language. MVEL stays; compile-cached and sandboxed.
- The `Domain` as a separate first-class taxonomy. It stays as an optional indicator filter;
  v1 wires the runtime semantics that the current code already implies.

## Ground-truth survey (data taken from local `test` Mongo on 2026‑05‑30)

| Collection | Count | Notes |
|---|---|---|
| `indicators` | 42 | 13 strategies, 8 output types in active use |
| `individualReports` | 5 (canonical) | `FV Info` deprecated in favor of `FV Info 2016` (renamed from `FV Info (Copy)` by user) |
| `userIndicatorResults` | 2,463 | Fingerprint cache; rekeyed by migration |
| `groupIndividualReportRuns` | 224 | Run history, most recent 2026‑04 |
| `userIndividualReportRuns` | 126 | Run history, most recent 2026‑05‑24 |

### Indicator dimensions, by usage

- **OutputType**: `PUBLICATIONS` (17), `GENERIC_ACTIVITIES` (9), `CITATIONS_EXCLUDE_SELF` (5),
  `ACTIVITY_FORUM` (5), `ACTIVITY_UNIVERSITY` (3), `ACTIVITY_EVENT` (1),
  `PUBLICATIONS_MAIN_AUTHOR` (1), `PUBLICATIONS_COAUTHOR` (1). `CITATIONS` (no exclude-self): 0.
- **Strategy**: 13 used, heaviest `GENERIC_ACTIVITY` (9), `CS_JOURNAL` (6). Eight strategies
  have ≤ 3 indicators.
- **Selector**: 30 `null` / 10 `ALL` / 2 `TOP_10`. `TOP_10` is the FEEA (Economics) framework.
- **`yearRange`**: 37/42 are `"*"`. 5 are explicit absolute ranges. **No formula uses the `IY`
  grammar in `yearRange`** — that branch is dead.
- **`scoreYearRange`**: 31/42 are `"IY"`. 7 are `"*"`. 4 explicit.
- **`domain` (`@DBRef`)**: 42/42 null. Field present, no production data uses it.
  Documented user intent: domain-as-filter, never enforced.

### Formula grammar in actual use (29 distinct strings)

- Numeric variables: `S`, `N`, `M`, plus activity-field names `Buget`, `B`, `X`,
  `N_autori`, `N_editori`, `N_luni`, `N_ani`.
- String variables: `Q` (quartile), `Rol`, `Tip`, `Nivel`.
- Operators: arithmetic, comparison, logical (`||`), ternary, assignment (`=`),
  statement separator (`;`).
- Functions: `max`. **`min` is never used in any real formula** — code path is dead.
- Method dispatch: `Q.contains("Q")` — exactly one formula uses it.
- Multi-statement formulas: three (`Info_D_v`, `Info_D_x`, `Info_D_ix`), all the same
  pattern: tier-then-multiply.

### Hidden contracts in `Score.extra`

`EconomicsJournalScoringService` writes `score.extra.put("M", multiplier)`. The `FEEA_P`
formula reads `M` back. The contract isn't declared anywhere — it lives in the open bag.
Two more candidates likely exist (e.g. `IF`); a code audit confirms all of them in v1.

## v1 model

### Indicator kind hierarchy

```java
sealed interface IndicatorKind permits
        IndicatorKind.Publications,
        IndicatorKind.Citations,
        IndicatorKind.Activity,
        IndicatorKind.GenericCount,
        IndicatorKind.GenericActivity {

    ScoringStrategy strategy();

    record Publications  (AuthorRole role,         ScoringStrategy strategy) implements IndicatorKind {}
    record Citations     (boolean excludeSelf,     ScoringStrategy strategy) implements IndicatorKind {}
    record Activity      (ActivityType type,       ScoringStrategy strategy) implements IndicatorKind {}
    record GenericCount  ()                                                  implements IndicatorKind {
        public ScoringStrategy strategy() { return ScoringStrategy.GENERIC_COUNT; }
    }
    record GenericActivity()                                                 implements IndicatorKind {
        public ScoringStrategy strategy() { return ScoringStrategy.GENERIC_ACTIVITY; }
    }
}

enum AuthorRole   { ALL, MAIN, CO }
enum ActivityType { FORUM, UNIVERSITY, EVENT }
```

Compatibility table (which strategies each kind allows) is enforced at save time:

| Kind | Permitted strategies |
|---|---|
| `Publications` | `CS_JOURNAL`, `CS`, `CS_CONFERENCE`, `CS_SENSE`, `RIS`, `AIS`, `IMPACT_FACTOR`, `ECONOMICS_JOURNAL_AIS`, `CNCSIS`, `GENERIC_COUNT` |
| `Citations` | `AIS`, `CS`, `IMPACT_FACTOR`, `RIS` |
| `Activity(FORUM)` | `CS_CONFERENCE`, `CS_JOURNAL`, `CNCSIS` |
| `Activity(UNIVERSITY)` | `UNI_RANKING` |
| `Activity(EVENT)` | `ART_EVENT` |
| `GenericCount` | (implicit) |
| `GenericActivity` | (implicit) |

Migration carries every existing indicator into exactly one cell of this table. Zero
indicators land outside it (verified against the data above).

### Year-range value types

```java
sealed interface YearRangeSpec {
    record AllYears() implements YearRangeSpec {}                  // "*"
    record Absolute(int from, int to) implements YearRangeSpec {}  // "2018->2025"
}

sealed interface ScoreYearRangeSpec {
    record AllYears() implements ScoreYearRangeSpec {}             // "*"
    record ItemYear() implements ScoreYearRangeSpec {}             // "IY"
    record Absolute(int from, int to) implements ScoreYearRangeSpec {}
}
```

Notes:
- The `IY±n` arithmetic the old code parses but no `yearRange` uses is **removed from
  `yearRange`**. `scoreYearRange` keeps the relative-window semantic (it's the dominant
  case, 31/42 indicators).
- `AllYears` in v1 resolves to "the data's max year" via `ReportingLookupPort.maxAvailableYear(strategy)`,
  not the literal `1990..LocalDate.now().year` expansion. Replays produce stable results.

### Selector

```java
sealed interface Selector {
    record All() implements Selector {}
    record TopN(int n) implements Selector {}     // n=10 today; field future-proofs
}
```

### `Score` decomposition

```java
record BaseScore(
        double score,
        Integer year,
        String coreRankingEquivalent,
        String quarter,
        Provenance provenance) {}

record FormulaContext(
        Map<String, Object> bindings,             // typed, declared per strategy
        Provenance provenance) {}

record Provenance(
        String source,                            // "wos.rankings", "core.conference", ...
        Map<String, Object> info) {}              // strategy-specific, but documented
```

- `errors`, `details`, raw `extra` go away.
- Strategies declare what they publish into `FormulaContext.bindings` — see "Variable
  contracts" below.

### Indicator persistence shape

```java
record Indicator(
        String id,
        String name,
        IndicatorKind kind,                       // replaces (outputType, scoringStrategy)
        String formula,                           // MVEL source (humans edit)
        String formulaHash,                       // SHA-256 of the parsed AST canonical form
        YearRangeSpec yearRange,
        ScoreYearRangeSpec scoreYearRange,
        Selector selector,
        DomainRef domain,                         // optional; null = applies to all
        ActivityRef activity) {}                  // present only for Activity kinds
```

`Indicator.outputType`, `Indicator.scoringStrategy`, and `Indicator.Type.CITATIONS` enum
value (unused) are removed by the migration.

### Variable contracts

Each `IndicatorKind` declares its formula variables. The validator rejects saves that
reference anything else.

**Universal (all kinds with a `BaseScore`):**
- `S` — `BaseScore.score`
- `Q` — `BaseScore.quarter` (Publications and Citations only)
- `N` — author count of the publication being scored (Publications and Citations only)

**Per scoring-strategy extra bindings** (audited 2026‑05‑30;
`EconomicsJournalScoringService.java:137` is the only writer to `Score.extra` in the
entire `service.reporting` package — confirmed by grep):

| Strategy | Extra bindings | Source |
|---|---|---|
| `ECONOMICS_JOURNAL_AIS` | `M` (category multiplier, int) | `EconomicsJournalScoringService:137` |
| `IMPACT_FACTOR` | none | publishes only `score`, `quarter`, `year` into `BaseScore` |
| `AIS`, `RIS` | none | same |
| `CS`, `CS_JOURNAL`, `CS_SENSE`, `CS_CONFERENCE` | none | same |
| `UNI_RANKING` | none | same |
| `CNCSIS` | none | same |
| `ART_EVENT` | none | same |
| `GENERIC_COUNT`, `GENERIC_ACTIVITY` | none | constant `S=1.0` only |

Validator wiring: declare `M` as a binding only on `ECONOMICS_JOURNAL_AIS`; reject any
non-Economics formula that references `M`. The three production IMPACT_FACTOR indicators
(`Psiho_I1_I2`, `Psiho_I5_I6`, `Psiho_I16_I17a`) all stay within the universal
`{S, Q, N}` contract — verified by inspection of their formula strings.

**Per ActivityType extra bindings:**
- `Activity` kinds bind every field declared on the corresponding `Activity` document by
  its exact name (`Buget`, `Rol`, `Tip`, `Nivel`, `N_ani`, `N_luni`, `N_autori`,
  `N_editori`, `Nume`, ...). String fields stay strings; numeric ones get parsed once at
  binding time.

**Locally-introduced names** (LHS of `=` in multi-statement formulas like
`B = Buget; X = ...; ...`) are tracked by the validator and exempted from the
"unbound variable" check.

### Formula pipeline

```
indicator save  →  parse + validate (variables in contract?) + sandboxed sniff →
   compile  →  persist source + AST hash  →  (cache)

per-item scoring  →  bind FormulaContext per strategy contract  →
   evaluate compiled MVEL  →  result
```

**Compile + cache:** `MVEL.compileExpression(source, parserContext)` at save time;
results stored on the `Indicator` document (the compiled object is `Serializable`, but
practically we store the source + hash and recompile on app start into an
`IndicatorFormulaCache : Map<indicatorId, Serializable>` Spring bean).

**Sandbox:** the MVEL `ParserContext` is configured to:
- deny access to `java.lang.System`, `java.lang.Runtime`, `java.lang.Class`, `java.lang.reflect.*`
- deny instantiation (`new …`)
- expose `Math.max` (the only function any real formula uses, via the existing
  `max` → `Math.max` substitution; that substitution moves into the parse step, no longer
  string-replaced at eval time)
- expose `String.contains` (the one method call any real formula uses)

**Cache identity for downstream consumers** (`userIndicatorResults.fingerprint`):
`indicator.id | kind.toString() | yearRange | scoreYearRange | selector | formulaHash | payload`.
The `formulaHash` is whitespace-stable; cosmetic edits don't invalidate the 2,463-row cache.

### Strategy → service registry

`ScoringFactoryService` becomes:

```java
@Service
@RequiredArgsConstructor
public class ScoringFactoryService {
    private final List<ScoringService> services;
    private Map<ScoringStrategy, ScoringService> byStrategy;

    @PostConstruct
    void index() {
        byStrategy = services.stream().collect(toMap(ScoringService::strategy, identity()));
        // Compile-time exhaustiveness check via a test, not via runtime asserts.
    }

    public ScoringService get(ScoringStrategy s) {
        ScoringService svc = byStrategy.get(s);
        if (svc == null) throw new IllegalArgumentException("Unsupported scoring strategy: " + s);
        return svc;
    }
}
```

Every `ScoringService` declares `ScoringStrategy strategy()` so registration is
declarative. A new strategy can't be silently broken on dispatch.

### `LAST_YEAR` removal

`ReportingLookupPort.maxAvailableYear(ScoringStrategy)` returns the highest year for which
that strategy has ranking data. The `LAST_YEAR = 2023` constants in `ScoringService`
and `ComputerScienceConferenceScoringService` are replaced with a call to this port.

## Drop in v1

These are confirmed unused by the data survey above and are removed during migration:

- `Indicator.outputType` field (replaced by `kind`).
- `Indicator.scoringStrategy` field (replaced by `kind.strategy()`).
- `Indicator.Type` enum (the entire type), and `Indicator.Strategy` enum (replaced by
  the top-level `ScoringStrategy` enum reused inside `IndicatorKind`).
- `Indicator.Type.CITATIONS` — no indicator references it; only `_EXCLUDE_SELF` is used.
- `Indicator.parseYearRange`'s `IY` grammar applied to `yearRange`.
- `min` MVEL rewrite path in `ActivityReportingService` and `ScientificProductionService`.
- `Score.errors`, `Score.details` (replaced by `Provenance`).
- `Score.extra` (replaced by typed per-strategy bindings; the `M` contract becomes explicit).
- `Indicator.domain` (`@DBRef`) **stays** as an optional filter (see "Domain as a filter"
  below).
- The duplicate indicator `Mate_S (copy)` (`_id` `682343657123387ec34394b1`) is deleted
  by the migration; `Mate_S` and `Mate_S_recent` remain (they differ in `yearRange`).

## Domain as a filter

Domain stays as an optional `Indicator` field. v1 wires the runtime semantics that the
current code already implies but doesn't enforce:

- Null `domain` → indicator applies to all publications (current behavior).
- Non-null `domain` → before scoring, drop publications whose WoS categories don't
  intersect `domain.wosCategories`. The pseudo-domain `Domain.name="ALL"` is no longer
  constructed ad-hoc in `VenueClassifier` / `IndividualReportComputer`; those callsites
  resolve a canonical `Domain.all()` singleton.
- The 42 existing indicators stay with `domain=null` — no migration churn for them.
- The indicator-edit UI gains a "Scope to domain" dropdown; the H10/H49 domain catalog
  feeds it.

## `IndicatorKind` adapters and the scoring path

Each kind owns one adapter that produces the `FormulaContext` for one item:

```java
sealed interface IndicatorKindAdapter<I, B> {
    BaseScore baseScore(I item, Indicator indicator);
    FormulaContext bindings(I item, B baseScore, Indicator indicator);
}
```

- `PublicationsAdapter` filters by `AuthorRole`, runs the strategy, binds `S`, `N`, `Q`,
  plus strategy-extras (`M` for Economics, etc.).
- `CitationsAdapter` extends the publications path with `excludeSelf` filtering on the
  citing-author set.
- `ActivityAdapter(FORUM)` looks up the forum from `referenceFields` and runs the strategy;
  binds activity fields.
- `ActivityAdapter(UNIVERSITY/EVENT)` analogous.
- `GenericCountAdapter` / `GenericActivityAdapter` short-circuit with constant base score
  and bind only `S=1.0`.

The existing `ReportingComputationSupport.is*Indicator(...)` helpers and the
`.toString().contains("ACTIVIT")` cancer disappear. Branching on kind is exhaustive via
the sealed hierarchy; the compiler enforces it.

## Test gate (regression equality)

Before the migration PR can merge:

1. Materialize all 42 production indicators in the v1 model on a test Mongo (the
   `dev-seed/groups-sample.csv` pipeline is reused).
2. Replay every `userIndicatorResults` row (2,463 rows) against the v1 pipeline.
3. Assert numerical equality on `totalScore` to within 1e‑9.
4. Documented exceptions (one indicator dropped, plus any whose strategy contract
   tightened) are listed in the PR description with rationale.

The replay harness is a new `@Tag("replay")` integration test, run locally and in CI.
Pass = green. Fail = not ready to merge.

## Migration shape

The migration is a single PR with several commits, in this order:

### Commit 1 — model + parallel reads

- Add `IndicatorKind`, `ScoringStrategy` (top-level), `Selector`, `YearRangeSpec`,
  `ScoreYearRangeSpec` types.
- Add `Indicator.kind`, `Indicator.formulaHash`, the new typed `yearRange`/`scoreYearRange`/`selector`
  fields. Keep the old `outputType`, `scoringStrategy`, string `yearRange`/`scoreYearRange`,
  string `selector` for one commit.
- Add `BaseScore`, `Provenance`, `FormulaContext` types and adapters.
- Indicator-load code reads the new fields if present, falls back to converting the old.
- Existing `Indicator.Type` / `Indicator.Strategy` enums become package-private and
  marked `@Deprecated`.

### Commit 2 — write-through migration script

- `OrgSeedRunner`-style runner (`@Profile("indicator-migration")` or a CLI flag) that:
  - Reads every indicator.
  - Computes `kind`, `yearRange`, `scoreYearRange`, `selector`, `formulaHash`.
  - Validates the formula under the new variable contract; aborts the migration on
    the first failure with the indicator id + reason. No partial migration.
  - Writes the new fields back. Old fields stay for now.
  - Deletes `Mate_S (copy)`.
- Run the replay test against the migrated DB.

### Commit 3 — switch reads, decommission old fields

- All scoring code reads only the v1 fields.
- The compatibility adapters in commit 1 are removed.
- Old fields are unset from every indicator (one Mongo `$unset` per field).
- `Indicator.Type` and `Indicator.Strategy` enums are removed.
- `Score.errors`, `Score.details`, `Score.extra` removed.
- `ScoringFactoryService` `if/else if` ladder replaced with the Map registry.
- `ReportingComputationSupport.is*Indicator(...)` deleted.
- `LAST_YEAR` constants deleted; callers route through `ReportingLookupPort.maxAvailableYear(...)`.
- The `min` rewrite path is deleted.
- The `Score.extra` "M" contract is replaced by the typed binding in
  `EconomicsJournalStrategy`.
- The `userIndicatorResults` re-fingerprint job runs on startup once (gated by a
  property), recomputes the cache identity for all 2,463 rows in the new format,
  preserves the cached values.

### Commit 4 — UI surfaces

- Indicator-edit form: kind dropdown drives strategy options; formula textarea shows the
  variable contract live; year-range becomes structured inputs (radio group: All / Absolute
  range / Item year) instead of a free-text "* / IY / 2018->2025" field.
- Indicator-list shows kind tag + strategy tag separately for filtering.

## Dependencies

- `H50.x` (individual report export/import) — already uses `Indicator` via DBRef. v1
  preserves the field-by-field contract; H50 read paths are unaffected.
- `H51` (Mongo unique-index integrity) — `Indicator` has no unique index today, but the
  migration adds a unique index on `(kind.toString(), name, formulaHash)` to catch future
  silent duplicates. `H51` and this work touch independent collections.
- `ComputerScienceConferenceScoringService` refactor — separate `Hxx`, scheduled after
  v1 lands.

## Open follow-ups (not v1)

- AST-based formula editor (autocomplete from the live variable contract).
- `tier(value, breakpoints, values)` helper to compress the three multi-statement
  formulas to one expression each. Lands only if a fourth multi-statement formula appears.
- Formula linting in CI (refuse to merge an indicator save that increases formula complexity
  without justification).
- Domain-as-filter UI catalog (depends on the H10/H49 domain work being current).

## Exit criteria

- Every existing indicator runs through the v1 pipeline and produces numerically identical
  results to the historical `userIndicatorResults` cache (per the test gate above).
- The pair `(Indicator.outputType, Indicator.scoringStrategy)` no longer appears anywhere
  in the codebase.
- `MVEL.eval(source, ...)` does not appear in any hot path; only `MVEL.executeExpression(compiled, ...)`.
- A new indicator with a formula referencing a variable not in its kind's contract is
  rejected at save time with a human-readable error pointing to the offending name.
- `LAST_YEAR` is grep-clean.
- No test in the suite is `@Disabled` because of this work.

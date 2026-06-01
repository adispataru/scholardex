# H52 Indicator / Scoring / Formula Flow — v1

**Status:** In progress — slices 1 and 2 of commit 1 shipped.
**Created:** 2026-05-30
**Last updated:** 2026-05-31

## Quick status (for the next person picking this up)

| Phase | Status | Notes |
|---|---|---|
| Pre-flight: view-layer audit | ✅ done | bounded to 2 templates |
| Pre-flight: Domain "ALL" backfill | ✅ done (+fixed Step 7 regression on all 9 domain docs) | |
| Pre-flight: perf baseline | ⏸️ deferred | dev seed has no real Scopus IDs |
| Commit 1 / slice 1 — new types in `model.reporting.scoring` | ✅ done | 43 tests in `LegacyMappingTest` |
| Commit 1 / slice 2 — `Indicator` fields + `@Version` + effective getters | ✅ done | 13 tests in `IndicatorEffectiveAccessorsTest`; templates needed hidden `version` input |
| Commit 1 / slice 3a — `@ExceptionHandler(OptimisticLockingFailureException)` scoped to `/admin/indicators*` | ✅ done | redirect + flash `errorMessage`; bubbles up for any other URI |
| Commit 1 / slice 3b — write-through migration runner (`@Profile("indicator-migration")`) | ✅ done | populates `kind`/`yearRangeSpec`/`scoreYearRangeSpec`/`selectorSpec`; deletes Mate_S copy `682343657123387ec34394b1`; idempotent on re-run |
| Commit 1 / slice 3c — replay smoke test on historical shapes | ✅ done | 2 tests in `IndicatorEffectiveAccessorsReplaySmokeTest`; iterates 21 prod combos × 2 yearRange × 3 scoreYearRange × 3 selector |
| Commit 1 / slice 4 — `FormulaContext` + `FormulaEvaluator` + compile cache + central `max`/`min` rewrite; wired into `ScientificProductionService` + `ActivityReportingService` | ✅ done | 19 tests across `FormulaContextTest` + `FormulaEvaluatorTest`; both legacy MVEL inline sites deleted; word-boundary rewrite is safer than the pre-v1 `String.replaceAll` |
| Commit 1 / slice 5 — `FormulaCanonicalizer` + `FormulaHasher` + Mongo `BeforeConvert` hash stamper + cache-key switch to hash | ✅ done | 17 tests; `Indicator.formulaHash` populated on every save automatically; runner now marks docs dirty when hash missing |
| Commit 1 / slice 6 — Map-registry dispatcher (`ScoringService.strategy()` + autowired `List` → `EnumMap`) replacing `if/else` ladder | ✅ done | 7 tests; 11 strategy services declare their `ScoringStrategy`; startup invariants reject duplicates and unclaimed strategies |
| Commit 1 / slice 7 — `FormulaSandbox` denylist scan over the canonical form, wired into both `FormulaEvaluator` (compile-cache miss) and `IndicatorFormulaHashStamper` (save time) | ✅ done | 11 tests; threat model is footgun prevention not adversarial — empty `ParserContext` doesn't actually block class access in MVEL |
| Commit 1 / slice 8a — `ScoringService.LAST_YEAR` interface constant removed; replaced with `ReportingLookupPort.maxAvailableYear()` default method | ✅ done | 4 production sites + 18 test files patched; landmine pinned (test stub returning 2099 un-capped a year filter — corrected to 2023) |
| Commit 1 / slice 8b — AST-based formula canonicalizer (`FormulaTokenizer` + normalize); closes the `S+1` vs `S + 1` hash gap | ✅ done | 13 new tests; migration runner now re-stamps stale slice-5 hashes; live verified end-to-end |
| Commit 1 / slice 9 — typed `Score.multiplier` replaces the `Score.extra["M"]` open-bag contract; dual-write preserves legacy readers and H50 round-trip | ✅ done | 1 added assertion; `BaseScore`/`Provenance` wrapper records intentionally deferred — they'd be unused-code without consumer migration which is Commit 3 territory |
| Commit 2 / slice 10 — replay-shape gate against the 2463-row `userIndicatorResults` cache | ✅ done | 57 rawGraph blobs (one per distinct fingerprint) snapshotted into `src/test/resources/h52/replay-fixture.json`, PII redacted; `H52ReplayShapeTest` asserts per-view shape invariants — tripwire for Commit 3 |
| Commit 3 — switch reads to typed slot, delete `Indicator.Type`/`Strategy`/`Score.extra`/etc. | ⏳ next | the deletion pass; tripwire from slice 10 must stay green |
| Commit 3 — switch reads, drop legacy fields | ⏳ later | |
| Commit 4 — UI surfaces | ⏳ later | |
| Replay-equality test gate against 2,463-row `userIndicatorResults` cache | ⏳ later | fixture path: `src/test/resources/h52/replay-fixture.json` |

Build at end of slice 10: **2078 tests, 0 failures.** Commit-2's replay-shape gate is in
place. 57 production rawGraph blobs (one per distinct fingerprint in the live
`userIndicatorResults` cache) are committed under `src/test/resources/h52/replay-fixture.json`
with PII redacted. The 8-test `H52ReplayShapeTest` deserializes every blob through current
Jackson and asserts per-view shape invariants — this is the tripwire that Commit 3's
decommission pass has to leave green.

Build at end of slice 9: **2070 tests, 0 failures.** `Score.multiplier` is now a typed
field; `EconomicsJournalScoringService` writes both it and the legacy `extra["M"]` so
historical scores and H50 imports continue to round-trip. The intermediate `ScoreResult`
builder in `AbstractForumScoringService` back-ports the typed slot in `createScore`, so
the final returned `Score` always has both populated when an Economics multiplier is
involved. Wrapper records (`BaseScore`/`Provenance`) deliberately deferred — adding them
without consumers would be the same unused-code window I avoided in slice 4.

Build at end of slice 8b: **2070 tests, 0 failures.** The text-level whitespace pass is
replaced by a real tokenizer; `S+1` and `S + 1` now produce the same canonical form and
hash. `LAST_YEAR=2023` is gone from the `ScoringService` interface — `ReportingLookupPort`
owns it as a default method now. **Live-verified end-to-end** against the `test` database:
booted on `agent-dev`, edited an indicator via the admin UI, saw the hash migrate from
the slice-5 form `ebb802f1…` to the slice-8b form `28ae4c46…` automatically through the
`BeforeConvert` stamper. The new hash matches an independent `shasum -a 256` of
`"S / Math.max(N - 2, 1)"` byte-for-byte.

Build at end of slice 7: **2058 tests, 0 failures.** Indicator formulas now go through
a denylist sandbox at two points: compile-cache miss (defense-in-depth at runtime) and
save time (`onBeforeConvert` in the stamper, surfaces as a 400 via `IllegalArgumentException`).
`System.*`, `Runtime.*`, `Class.forName`, reflection, inline imports, and fully-qualified
`java.io`/`java.nio`/`java.net` references are rejected with a message naming the trigger
token. Production formulas all pass; comment-hidden tokens are stripped before the scan
(documented test); whitespace-obfuscated bypass is explicit non-goal per the threat
model. Migration runner is still profile-gated and unrun against live data — that's the
slice-9 deploy step, behind a fresh `mongodump`.

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

The migration is a single PR with several commits, in this order.

### Pre-flight (before commit 1 lands on the branch) — **DONE 2026‑05‑31**

- ✅ **View layer audit.** Findings: indicator `outputType` / `scoringStrategy` reads are
  bounded to two Thymeleaf files only — `src/main/resources/templates/admin/indicators.html`
  (list columns, create-modal form binding, JS branching on `'ACTIVIT'`) and
  `src/main/resources/templates/admin/indicators-edit.html` (form binding). No view models
  or Java view layer read these fields. Commit 3's view-layer flip is two files of work.
- ✅ **`Domain "ALL"` exists** as `_id="ALL"`. **But pre-flight caught a Step 7 regression**:
  all 9 `domains` docs had `name=null` after the surrogate-key migration. Backfill applied:
  ```js
  db.domains.updateMany({name:{$exists:false}}, [{$set:{name:"$_id"}}])
  ```
  All 9 docs now satisfy `findByName(...)`. The ad-hoc `new Domain().setName("ALL")`
  callsites can resolve the singleton via `domainRepository.findByName("ALL")`.
- ⏸️ **Performance baseline deferred to slice 3+** because dev-seed researchers have
  fictional Scopus IDs that short-circuit scoring (verified earlier — every formula
  produces "No authors found"). Capture when real test data is available
  (`florin.spataru@e-uvt.ro` has 19 `userIndicatorResults` rows on local Mongo and is the
  natural baseline subject).

### Commit 1 — model + parallel reads

Split for incremental safety into two slices; both shipped 2026‑05‑31.

#### Slice 1 — new types (DONE)

Package: `ro.uvt.pokedex.core.model.reporting.scoring`.

Files added:
- `ScoringStrategy.java` — top-level enum mirroring `Indicator.Strategy`. Bidirectional
  `fromLegacy(Indicator.Strategy)` / `toLegacy()`.
- `AuthorRole.java` — `ALL | MAIN | CO`.
- `ActivityType.java` — `FORUM | UNIVERSITY | EVENT`.
- `IndicatorKind.java` — sealed interface with records `Publications(role, strategy)`,
  `Citations(excludeSelf, strategy)`, `Activity(type, strategy)`, `GenericCount()`,
  `GenericActivity()`. Has `fromLegacy(Indicator.Type, Indicator.Strategy)` encoding the
  full compatibility table from this doc and `toLegacy(): LegacyShape` for parallel-write.
- `YearRangeSpec.java` — sealed `AllYears | Absolute(from, to)`. `parse(String)` accepts
  `null` / `""` / `"*"` / `"a->b"` / `"a-b"`. Rejects `"IY"` and `"IY+n"` explicitly
  (only used in `scoreYearRange` in production).
- `ScoreYearRangeSpec.java` — sealed `AllYears | ItemYear | Absolute`. `parse(String)`
  accepts `null` → `ItemYear` (dominant production default), `"IY"`, `"*"`, `"a->b"`.
  Rejects `IY±n` arithmetic (unused in production).
- `Selector.java` — sealed `All | TopN(n)`. `fromLegacy(Indicator.Selector)` normalises
  `null` → `All`. `toLegacy()` returns `null` for `All` (preserves the legacy convention
  where `null` and `ALL` are equivalent).

Tests: `LegacyMappingTest.java` (43 cases). The 21 production `(Type, Strategy)`
combinations are enumerated explicitly and asserted to map cleanly. Two intentional
non-round-trip cases pinned in tests:
- `Indicator.Type.PUBLICATIONS + GENERIC_COUNT` ↔ `GenericCount()` ↔ back to
  `Type.PUBLICATIONS` (the `Type` direction is lossy by design).
- `Selector.ALL` ↔ `All()` ↔ back to `null` (the `ALL` direction is lossy because both
  are valid legacy representations of the same intent).

Subtle parser decision: `null` / blank `yearRange` defaults to `AllYears`, but `null` /
blank `scoreYearRange` defaults to `ItemYear`. Reflects the dominant production intent
per the data survey (37/42 yearRange="*", 31/42 scoreYearRange="IY").

#### Slice 2 — Indicator entity fields + effective getters (DONE)

**Mongo pre-step.** Backfilled `version=0` on all 42 production indicators:
```js
db.indicators.updateMany({version:{$exists:false}}, {$set:{version:0}})
```
*Why:* Adding `@Version` to an existing entity without backfilling is a landmine.
Spring Data treats `null` version as "insert as new"; the save then collides on `_id`
with `E11000 duplicate key error`. **Verified by accident** during slice 2 smoke testing.

Entity changes in `Indicator.java`:
- New typed fields: `IndicatorKind kind`, `String formulaHash`, `YearRangeSpec yearRangeSpec`,
  `ScoreYearRangeSpec scoreYearRangeSpec`, `Selector selectorSpec`.
- `@Version Long version` (Spring Data Mongo optimistic locking).
- Effective getters: `getEffectiveKind()`, `getEffectiveYearRange()`,
  `getEffectiveScoreYearRange()`, `getEffectiveSelector()`. Prefer the v1 field when
  populated; otherwise synthesise from the legacy field via slice‑1 converters. Hot-path
  code (slice 4+) calls these and stays agnostic about migration phase.
- Legacy fields marked `@Deprecated` with javadoc pointing to the v1 replacement:
  `outputType`, `scoringStrategy`, string `yearRange`, string `scoreYearRange`,
  `selector` (the field), `Indicator.Type` enum, `Indicator.Strategy` enum,
  `Indicator.Selector` enum.

**Naming-collision note:** `Indicator.Selector` (nested enum, legacy) coexists with the
new `Selector` field of type `ro.uvt.pokedex.core.model.reporting.scoring.Selector`. The
entity uses fully-qualified references for the scoring package type to disambiguate
without breaking imports.

Templates updated — **required** for save round-trip to work:
- `indicators-edit.html`: added hidden `<input type="hidden" name="version" th:value="${indicator.version}">`.
- `indicators.html` (create modal): same hidden input.

Without those, the @ModelAttribute Indicator arrives with `version=null` from the form
and Spring Data hits the same E11000 trap as the pre-step backfill catches.

Tests: `IndicatorEffectiveAccessorsTest.java` (13 cases). Verifies the "v1 wins; legacy
falls back; bare-indicator defaults match production intent" contract for every
effective getter.

End-to-end verification (live app, 2026‑05‑31):
- Boot clean; existing indicators load; scoring path on department roll-up still 200.
- Edit page renders hidden version input.
- POST with current version=0 → 302, version bumps to 1.
- POST with stale version=0 → **500 (OptimisticLockingFailureException)** — exactly the
  intended behavior, but UX polish needed (slice 3, see below).
- POST with current version=1 → 302, version bumps to 2.

**Build status after slice 2: 2007 tests, 0 failures** (was 1914 at start of H52;
slice 1 added 43, slice 2 added 13, plus other suite drift from upstream slices).

#### Slice 3 — graceful stale-write + migration runner + replay smoke (DONE)

Three deliverables landed; adapter types deferred to slice 4 (where they're wired into
the scoring path on the same commit — declaring them now creates an unused-code window
the build linters object to).

**3a. `@ExceptionHandler(OptimisticLockingFailureException.class)`** on
`AdminViewController`. Inspects `request.getRequestURI()` and only catches when the URI
starts with `/admin/indicators` — other admin resources (domains, divisions, etc.) still
bubble up the raw 500 until they grow their own handlers. Adds a flash `errorMessage`:
> This indicator was edited by someone else. Please reload and re-apply your changes.

Why request-URI scoping rather than putting the handler on a dedicated `IndicatorAdminController`?
Spreading the existing controller across multiple files for one handler is more disruption
than the slice budget allows; we're carrying that consolidation as a slice-6 cleanup item.

**3b. `IndicatorV1MigrationRunner`** at
`service/application/IndicatorV1MigrationRunner.java`. `@Profile("indicator-migration")`.

```bash
./gradlew bootRun --args='--spring.profiles.active=indicator-migration'
```

Contract:
- Loads every indicator (`findAll`).
- For each, if a v1 field is `null`, populates it from the corresponding `getEffective*`
  helper. Save is conditional — `dirty == true` only when something actually changed,
  so re-runs are no-ops and don't bump `version` gratuitously.
- Skips indicators where `getEffectiveKind()` returns null (both legacy fields unset).
  Logs them at WARN; no silent data loss.
- Deletes Mate_S copy at id `682343657123387ec34394b1` as the last step.
- Emits a one-line summary: `scanned`, `saved`, four `populated*` counters, `skippedNoLegacy`.

`formulaHash` deliberately not populated here. The slice-4 formula parser writes it; doing
it now would compute a hash against the raw string that the parser would immediately
invalidate.

**3c. `IndicatorEffectiveAccessorsReplaySmokeTest`** at
`src/test/java/ro/uvt/pokedex/core/model/reporting/IndicatorEffectiveAccessorsReplaySmokeTest.java`.

Iterates the cartesian product:
- 21 production `(Type, Strategy)` pairs (mirrored from `LegacyMappingTest` —
  intentionally duplicated, see test javadoc)
- `yearRange ∈ {"*", "2017->2025"}`
- `scoreYearRange ∈ {"IY", "2018->2024", null}`
- `selector ∈ {ALL, TOP_10, null}`

For each tuple builds a bare `Indicator`, sets only the legacy fields, then asserts all
four `getEffective*` helpers return non-null and don't throw. Plus a second test that
populates the v1 fields with *different* values from the legacy ones to prove
`getEffective*` returns the v1 fields verbatim (no accidental re-derivation).

This is the slice-3 bridge for what the design doc calls the "full historical replay" —
that one needs a fixture at `src/test/resources/h52/replay-fixture.json` plus reports +
researchers + assembler context, and lands with the slice-4 read switch. The smoke test
here covers the realistic failure surface (effective getter throws on a shape we already
shipped) without that scaffolding cost.

**Build status after slice 3: 2009 tests, 0 failures.** Slice 3 added 2 tests
(`IndicatorEffectiveAccessorsReplaySmokeTest`); migration runner is exercised through
its own dependency wiring at boot time (no unit test — its logic is `getEffective*`
calls that are already covered).

#### Slice 4 — FormulaEvaluator + FormulaContext + compile cache (DONE)

Centralized the formula-evaluation policy that the two pre-v1 call sites had implemented
twice with subtle drift:

| Concern | Pre-v1 `ScientificProductionService` | Pre-v1 `ActivityReportingService` | Slice 4 |
|---|---|---|---|
| `max` rewrite | yes | yes | yes (word-boundary, idempotent on `Math.max`) |
| `min` rewrite | **no** | yes | yes |
| Math binding | only on rewrite | only on rewrite | whenever final expression references `Math.` |
| Compile cache | none — `MVEL.eval` recompiles per call | none | one `ConcurrentMap<String,Serializable>` keyed on rewritten expression |
| Error handling | rethrows | catches `PropertyAccessException` → 0.0 | `eval` rethrows, `tryEval` → `OptionalDouble.empty()` (caller decides) |

**`FormulaContext`** (`service.reporting.formula.FormulaContext`) is the immutable
typed variable bag. Builder rejects null/blank keys, reserves the `Math` key (only the
evaluator may bind it), and exposes both an unmodifiable `variables()` view and a
package-private `mutableCopy()` used by the evaluator.

**`FormulaEvaluator`** (`service.reporting.formula.FormulaEvaluator`) is a Spring
`@Component`. Two entry points:
- `eval(rawFormula, ctx) → double` — propagates MVEL exceptions (scientific-production
  hot path; bad formula should fail the report).
- `tryEval(rawFormula, ctx) → OptionalDouble` — logs at WARN and returns empty
  (activity path; preserves the legacy 0.0 fallback at the call site).

**Caching:** keyed on the rewritten expression string. Slice 5 will swap to the persisted
`formulaHash` once the canonical-form hasher exists.

**Wired in two places:**
- `ScientificProductionService.getScore(...)` — replaces the inline `MVEL.eval(formula, variables, Double.class)`.
- `ActivityReportingService.calculateActivityScore(...)` — replaces the inline `try / catch (PropertyAccessException)` block; logs at the call site for the activity-id context the evaluator can't see.

**Mutable map gotcha caught during slice 4:** production formulas use the MVEL statement
separator with intermediate assignments (e.g. `B = Buget; X = B < 50000 ? 1 : 2; ...`).
MVEL needs a mutable `Map<String,Object>` to write those bindings back. The first draft
of the evaluator handed it the unmodifiable `variables()` view on the no-Math branch,
which threw `UnsupportedOperationException` on the first assignment. The evaluator now
*always* hands MVEL a fresh `mutableCopy()` — the per-call HashMap copy is trivial
compared to MVEL execution and matches what the pre-v1 inline impl was doing implicitly
(it built a fresh `HashMap` per call too).

**Test-construction landmines:**
- `ScientificProductionServiceTest` uses `@InjectMocks`. Mockito leaves un-mocked
  constructor params as `null`, so the new `FormulaEvaluator` dep was injected as null
  and 16 tests broke. Fix: `@Spy private FormulaEvaluator formulaEvaluator = new FormulaEvaluator();`
- `ActivityReportingServiceTest` + `ComputerScienceScoringPipelineParityTest` build the
  services directly with `new` and got `sed`-patched to pass `new FormulaEvaluator()`.

**Build status after slice 4: 2028 tests, 0 failures** (slice 4 added 19: 6 in
`FormulaContextTest`, 13 in `FormulaEvaluatorTest`). Compile-cache amortization is proven
by the `compileMissCount()` + `cacheSize()` assertions; the test that exercises 50
sequential evals sees exactly one compile-cache miss.

**Adapter types (`BaseScore`/`Provenance`/per-kind adapters) deliberately deferred to
slice 5**. They bleed into the strategy-service signatures, which is its own large
surface — bundling them with the evaluator/cache/rewrite work would have spread this
slice across too many files. The slice-5 work is bigger but more self-contained.

#### Slice 5 — canonical-form formula hashing (DONE)

Three new files, one save-time hook, one cache-key swap. Net effect: every
`Indicator.formulaHash` is now an SHA-256 of a canonical formula form that's stable
across cosmetic whitespace edits, `max`/`Math.max` style choices, and line comments —
but explicitly *not* stable across operator-precedence parens or literal-form changes.

**`FormulaCanonicalizer`** (`service.reporting.formula.FormulaCanonicalizer`): pure
function `canonicalize(rawFormula) → String`. Steps:
1. Apply the same `max`/`min` → `Math.*` rewrite as `FormulaEvaluator` (uses the same
   `rewrite` method so the two stay in lockstep).
2. Strip `// ...` line comments. Block comments deferred to the slice-6 AST pass —
   no production formula uses them.
3. Collapse whitespace runs to a single space, then trim — **but not inside quoted
   strings**, so `'Director General'` keeps its internal space.

What's deliberately not normalized (each documented as a separate test in
`FormulaCanonicalizerTest`):
- Parens around operator precedence (`3 + 3 * S` ≠ `(3 + 3) * S`).
- Numeric literal forms (`1` ≠ `1.0` — MVEL infers different types).
- Variable identifier case.

**`FormulaHasher`** (`service.reporting.formula.FormulaHasher`): SHA-256 over UTF-8
bytes of the canonical form, hex-encoded lowercase. Two entry points — `hash(rawFormula)`
for callers that haven't canonicalized, and `hashCanonical(canonical)` for the hot path
in `FormulaEvaluator` which canonicalizes once and reuses both.

**`IndicatorFormulaHashStamper`** (`service.reporting.formula.IndicatorFormulaHashStamper`):
extends `AbstractMongoEventListener<Indicator>`, overrides `onBeforeConvert`. Computes
the hash from `indicator.getFormula()` and writes it back if it changed. Why
`onBeforeConvert` rather than `onBeforeSave`: by `onBeforeSave` the BSON `Document` is
already built, so mutating the entity has no effect. `onBeforeConvert` still sees the
live entity. Blank/null formulas are skipped (no validation here — that's the controller's
job; this listener is purely the identity stamp).

**`FormulaEvaluator` cache-key swap**: was rewritten-expression-string; now the
canonical-form hash. Two formulas that differ only in whitespace share one compiled MVEL
expression, and the compile-cache identity matches the `userIndicatorResults` cache
identity that commit 3 will use.

**Mutual reuse note**: the canonicalizer calls `FormulaEvaluator.rewrite(...)` instead
of duplicating the regex. `FormulaEvaluator.eval(...)` calls both `rewrite` (for the
`usesMath` flag) and `canonicalize` (for the cache key). The double-rewrite is fine —
both are cheap regex passes — and keeps the rewrite policy single-sourced.

**Migration runner update**: now marks a doc dirty when `formulaHash` is null and the
formula is non-blank. The actual hash gets written by the stamper during `save(...)`,
not by the runner directly. So a fresh migration profile run will backfill `formulaHash`
across all 42 docs along with the kind/year/selector fields.

**Build status after slice 5: 2045 tests, 0 failures** (+17 from slice 4). New tests:
9 in `FormulaCanonicalizerTest`, 7 in `FormulaHasherTest`, 1 in `FormulaEvaluatorTest`
(`whitespaceVariantsShareCompiledExpression`).

**Carry-forward gotcha to a later slice**: the canonicalizer collapses but does not
delete whitespace, so `"S+1"` and `"S + 1"` still hash differently. This is intentional
— the text-level pass can't safely insert spaces without an AST (e.g. around unary
minus). An AST-based canonicalizer can fix this; until then, indicators authored without
spaces won't share their cache identity with the spaced version.

#### Slice 6 — Map-registry dispatcher (DONE)

Killed the 50-line `if/else` ladder in `ScoringFactoryService`. The replacement is an
`EnumMap<ScoringStrategy, ScoringService>` built at startup from the Spring-autowired
`List<ScoringService>`. Lookup is O(1).

**Interface change** — `ScoringService` grew an abstract method:
```java
ScoringStrategy strategy();
```
Every one of the 11 concrete strategy services now declares exactly one strategy:

| Bean | `strategy()` |
|---|---|
| `ComputerScienceConferenceScoringService` | `CS_CONFERENCE` |
| `ComputerScienceJournalScoringService` | `CS_JOURNAL` |
| `ComputerScienceScoringService` | `CS` |
| `ComputerScienceBookService` | `CS_SENSE` |
| `RISJournalScoringService` | `RIS` |
| `AISJournalScoringService` | `AIS` |
| `UniversityRankScoringService` | `UNI_RANKING` |
| `CNCSISPublisherListService` | `CNCSIS` |
| `ArtEventScoringService` | `ART_EVENT` |
| `ImpactFactorJournalScoringService` | `IMPACT_FACTOR` |
| `EconomicsJournalScoringService` | `ECONOMICS_JOURNAL_AIS` |

**Inline-handled strategies** — `GENERIC_COUNT` and `GENERIC_ACTIVITY` are deliberately
*not* registered. The pre-v1 code already handled them inline at
`ScientificProductionService` (counts pubs, never calls a strategy service) and
`ActivityReportingService` (returns 1.0 baseline). The factory tracks them in an
`INLINE_STRATEGIES` set; `verifyRegistry()` skips them in the must-be-registered check
and `getScoringService` rejects them with a clear "handled inline" message rather than
the generic "unsupported" one.

**Startup invariants** — both fire loudly:
- **Duplicate claim** (two beans return the same `ScoringStrategy`): rejected in the
  constructor with `IllegalStateException`, naming both classes and the strategy.
- **Missing strategy** (a `ScoringStrategy` enum value other than `GENERIC_*` has no
  bean): rejected in `@PostConstruct verifyRegistry()` with the strategy name in the
  message.

A null `strategy()` return is also caught at construction time.

**Both lookup overloads coexist** during the migration window:
- `getScoringService(Indicator.Strategy legacy)` — bridges via `ScoringStrategy.fromLegacy`
  for callers that still hold the legacy enum.
- `getScoringService(ScoringStrategy v1)` — direct lookup.

Both return the *same* bean for any production strategy (proven by `legacyEnumLookupBridgesToV1`).

**Adding a new strategy is the doc's promised three-step recipe:**
1. Add the enum value to `ScoringStrategy` (and to legacy `Indicator.Strategy` during
   the migration window).
2. Create a new `@Service` implementing `ScoringService` returning the new value from
   `strategy()`.
3. Done — no edit to `ScoringFactoryService`.

**Test fallout fixed inline:**
- Three test classes had stub `ScoringService` subclasses (`TestService`, `TestForumBase`,
  `TestWoSBase`) that didn't override the new abstract method. Patched to return
  `ScoringStrategy.CS` arbitrarily (these stubs are tested directly, not via the factory).
- `ScientificProductionServiceTest` had `getScoringService(any())` Mockito stubs that
  became ambiguous between the two overloads. Narrowed to `any(Indicator.Strategy.class)`.
- `ScoringFactoryServiceTest` itself was rewritten: the old test mocked 11 concrete
  service classes positionally; the new one uses anonymous `ScoringService` fakes that
  declare their own strategy. Covers the same production-parity ground plus the new
  duplicate/missing-bean invariants — net +2 tests.

**Build status after slice 6: 2047 tests, 0 failures.**

#### Slice 7 — denylist sandbox over the canonical form (DONE)

**Why a denylist and not a real MVEL sandbox.** Direct probe (see slice-7 commit
notes) confirmed that an empty {@code ParserContext} still resolves `java.lang`
classes — `System.getProperty`, `Runtime.getRuntime()`, `Class.forName(...)` all
execute. Building a real sandbox would require a custom `VariableResolverFactory`
that intercepts class lookups, which is multi-day work and not warranted at the
current threat model. Indicator formulas are authored by admins who already have
full Mongo write access; this is footgun prevention, not adversarial sandboxing.

**`FormulaSandbox`** (`service.reporting.formula.FormulaSandbox`): static
`assertSafe(canonical)`. Throws `FormulaSandboxException extends IllegalArgumentException`
when the canonical form contains any of:

| Token | Reason |
|---|---|
| `System.` | java.lang.System (System.exit, System.getProperty) |
| `Runtime.` / `Runtime(` | java.lang.Runtime |
| `ProcessBuilder` / `Process.` | process spawn |
| `Thread.` | thread control |
| `Class.forName` / `forName(` | reflective lookup |
| `ClassLoader` | classloader access |
| `.getClass(` | reflection chain |
| `getDeclaredField` / `getDeclaredMethod` / `setAccessible` | reflective member access |
| `ScriptEngine` | nested scripting |
| `java.io.` / `java.nio.` / `java.net.` / `java.lang.reflect` / `javax.script` | fully-qualified leaf packages |
| inline `import ...;` | MVEL inline imports |

`Math.` is allowed because `FormulaEvaluator`'s rewrite produces it from `max`/`min`
and the production formulas use only those two methods. No other class-level call is
known in the production catalog.

**Two integration points** — same check fires both at evaluation and at save:
1. **`FormulaEvaluator`** — runs `FormulaSandbox.assertSafe(canonical)` inside the
   `computeIfAbsent` cache-miss lambda, so an unsafe formula can never execute, even
   if it somehow appeared in the database through a path that bypassed the save hook.
   Runs once per unique formula (compile-cache amortizes).
2. **`IndicatorFormulaHashStamper`** — runs `assertSafe` inside `onBeforeConvert`
   right before computing the hash. `FormulaSandboxException` propagates; Spring
   surfaces it as the standard 400 path through the controller. The stamper logs
   the rejection at WARN with the indicator id/name first so we have audit context.

**Documented limits** — pinned as explicit tests so the threat-model boundary is
visible:
- `whitespaceObfuscationDoesNotBypass` — proves `System . exit ( 0 )` (with spaces
  preserved by canonicalization) is NOT rejected because the substring `System.` is
  not present. Exhaustive obfuscation defense is out of scope.
- `hiddenInLineCommentIsCanonicalizedOutAndAccepted` — the canonicalizer strips line
  comments before the sandbox runs, so a denied token *only* inside a comment is gone
  by the time the scan sees the text. Intended behavior, but documented so a future
  reader doesn't think the sandbox has a hole.

**Build status after slice 7: 2058 tests, 0 failures** (+11 in `FormulaSandboxTest`).

#### Slice 8a — `LAST_YEAR` constant removed from interface (DONE)

The hardcoded `Integer LAST_YEAR = 2023` had been hanging off the `ScoringService`
interface — wrong place for a mutable knob. Moved to `ReportingLookupPort` as a default
method:

```java
default int maxAvailableYear() { return 2023; }
```

Four production usage sites switched to `lookupPort.maxAvailableYear()`:
`CNFISScoringService2025`, `ImpactFactorJournalScoringService`,
`AbstractForumScoringService`, `ComputerScienceJournalScoringService`.

**Test fallout** — 18 test files needed the new method stubbed. Mockito mocks of an
interface return `0` for unstubbed `int` methods, not the default-method body. Patched
15 files with `@BeforeEach` running
`Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);`; 3 tests
with method-local `lookupPort` mocks got per-mock stubbing.

**Landmine pinned** — initial stub returned `2099` to neutralize the year cap. That
un-capped a conference-scoring assertion that expected score year = 2023 because the
pre-v1 LAST_YEAR cap had been firing. Corrected stubs to return 2023, preserving the
pre-v1 numeric behavior across the whole suite. Written into this doc as the textbook
"replacing a constant with a method that mocks default to 0 will change behavior in
non-obvious places" warning.

**Build:** 2058 → 2058, 0 failures.

#### Slice 8b — AST-based formula canonicalizer (DONE)

Replaces the text-level whitespace-collapse pass with a real tokenizer. The slice-5
gap (`"S+1"` and `"S + 1"` hashing differently because the collapser couldn't insert
spaces) is now closed.

**`FormulaTokenizer`** (`service.reporting.formula.FormulaTokenizer`): ~150-line
hand-rolled tokenizer over the MVEL subset that appears in production formulas:

- Identifiers with dot-chains (`Math.max`, `B`, `Buget`).
- Numeric literals incl. decimal + exponent (`1`, `1.0`, `1.5e2`).
- Single- and double-quoted strings, preserved verbatim — `'Director General'`
  keeps its internal space.
- Multi-char operators recognized first (`>=`, `<=`, `==`, `!=`, `&&`, `||`, `->`)
  so they don't get split into single chars.
- Single-char operator stop list `+ - * / % > < ! ? : =`.
- Grouping `(`, `)` and separators `,`, `;`.

Anything the tokenizer doesn't recognize gets emitted as a single-char `OP` token
rather than thrown — the `FormulaSandbox` still runs and catches the dangerous
identifier patterns at its layer.

**`normalize(tokens)`** — emits canonical text with rules:

| Boundary | Spacing |
|---|---|
| Inside `(` … `)` (against the parens) | none |
| Function call `ident(` | none |
| After `,` and `;` | one space |
| Before `,`, `;`, `)` | none |
| All other pairs | one space |

So `Math.max(S,1)` and `Math.max( S , 1 )` both render as `Math.max(S, 1)`.
`(S>1.0)?(3+3*S):(3+S)` renders as `(S > 1.0) ? (3 + 3 * S) : (3 + S)`.

**`FormulaCanonicalizer.canonicalize`** — pipeline now: rewrite max/min, strip line
comments, then tokenize + normalize. The old whitespace-collapse loop is gone.

**Why not the MVEL AST.** MVEL's compiled-expression tree is package-private and
walking it across versions is fragile. A 150-line tokenizer covers every production
formula and is easy to evolve. The doc carries this as the rationale so the next
engineer doesn't try to swap in a "real" parser without weighing the coupling cost.

**Hash invalidation handled at the runner.** Every existing `formulaHash` value
stamped under slice 5 disagrees with what slice 8b produces for the same formula.
`IndicatorV1MigrationRunner` got a new branch: if a stored hash mismatches the
freshly-computed one, mark the doc dirty so the `BeforeConvert` stamper re-stamps
it during the next migration sweep. Single migration profile run rehashes every
stale doc — no explicit "v5 → v8b" schema flag needed.

**Live verification** — booted on `agent-dev` profile, edited indicator `Info_C`
(formula `S/max(N-2, 1)`) without changing the formula. Observed:

- Stored hash transitioned `ebb802f1…` (slice 5) → `28ae4c46…` (slice 8b).
- `version` bumped `1 → 2`, confirming the BeforeConvert hook fired during save.
- Independent `shasum -a 256` of the literal string `"S / Math.max(N - 2, 1)"`
  produced the same byte sequence `28ae4c462ff8a095921ff7b4e0cb440861de2f09bd700f545bc5e889ea6e5ef7`.
- Independent shasum of `"S + 1"` confirmed that both `"S+1"` and `"S + 1"` would
  hash to `e068904804f2…` (vs slice 5's two different hashes for the same pair).

**Build status after slice 8b: 2070 tests, 0 failures** (+12 in `FormulaTokenizerTest`,
+1 in `FormulaCanonicalizerTest`, -1 net adjustment from consolidating the slice-5
"collapses but doesn't delete" test that's now invalid).

#### Slice 9 — typed `Score.multiplier` (DONE)

The only field of `Score.extra` ever populated in production is `"M"` — the
EconomicsJournal multiplier (1, 2, or 3 depending on category). Slice 9 promotes it
to a typed slot:

```java
private Integer multiplier;
```

**Dual-write keeps the slice tight.** `EconomicsJournalScoringService.computeEconomicsScore`
now does both:

```java
returnScore.setMultiplier(multiplier);
returnScore.getExtra().put("M", multiplier);   // ← legacy compat
```

So historical persisted scores keep round-tripping via `extra`, H50 import files
written before this slice still deserialize identically, and consumers that have
already migrated to the typed slot get it without ceremony. Commit 3 (slice 11+)
deletes the `extra` write.

**Intermediate-builder back-port.** `AbstractForumScoringService.ScoreResult` is the
accumulator used during the per-(year, category) tie-break. It only tracks `extra`,
not the typed multiplier, so the per-iteration Score's typed slot was getting lost
when `createScore(ScoreResult)` built the final value. Fix: `createScore` now reads
`r.extra.get("M")` and calls `s.setMultiplier(m)` if present. The final returned
Score always has both populated when an Economics multiplier is involved. The
intermediate builder doesn't need its own typed slot — the back-port at the seam is
enough.

**`EconomicsJournalScoringService.compareScoresByPointsAndMultiplier`** reads the
typed slot first via a new `readMultiplier(score)` helper, with `extra["M"]` as
fallback. The tie-break logic is unchanged.

**Consumers unchanged this slice.** `ScientificProductionService` and
`ActivityReportingService` still bind `M` into `FormulaContext` via `putAll(extra)`,
which continues to work because of dual-write. Commit 3 will switch those reads to
the typed slot once we drop the `extra` write.

**`BaseScore` and `Provenance` records intentionally not introduced.** The doc had
listed them as part of this slice but they'd be unused code at this point — same
"unused-code window" argument I made in slice 4 against introducing adapter types
without their wiring. Commit 3 (the read-switch) is the right window for those
typed wrappers, alongside the strategy-service signature change that consumes them.

**Build status after slice 9: 2070 tests, 0 failures** (+1 added assertion in
`EconomicsJournalScoringServiceTest.articleUsesEconomicsCategoryMultiplierTen` proving
the typed slot is populated identically to `extra["M"]`).

#### Slice 10 — replay-shape gate against the 2,463-row cache (DONE)

**Scope decision.** The doc had pitched Commit 2 as a "replay-equality" test against
2,463 cached scores, but didn't specify *how* equality would be checked. After staring
at the actual cache shape, three approaches presented themselves:

| Approach | Catches | Reproducibility | Verdict |
|---|---|---|---|
| **A. Shape gate**: snapshot N rawGraph blobs, deserialize, assert per-view shape invariants | Schema drift from Commit-3 deletions | Committed snapshots — runs anywhere | ✅ chosen |
| **B. Numeric replay**: re-run scoring against live data, compare to cached totals | Algorithmic regressions | Brittle — depends on upstream WoS/Scopus data not drifting | ❌ unreliable in CI |
| **C. Full snapshot equality**: capture every blob, re-serialize, byte-compare | Drift but also re-serialization noise | Tautological — same code path on both sides | ❌ low signal |

A wins because the realistic Commit-3 risk is *exactly* schema drift — deleting
`Indicator.Type` / `Indicator.Strategy` / `Score.extra` / `Score.errors` etc. could
break deserialization of older cached blobs in ways the current code path doesn't
exercise. The shape gate proves the cached JSON shape stays *parseable* under future
schema changes.

**Fixture build.** A `mongosh` aggregation captured one rawGraph per distinct
fingerprint — 57 blobs in total. That's complete coverage of every shape that exists
in production today (the 2,463 rows collapse to 57 unique fingerprints — see "top
fingerprints by row count" diagnostic above for the long-tail distribution). The
fixture lives at `src/test/resources/h52/replay-fixture.json` with metadata:
`schemaVersion`, `capturedAt`, `capturedFrom`, `fingerprintCount`, `entries[]`.

Re-generation procedure (committed in this doc for future captures):

```bash
mongosh test --quiet --eval '
  const docs = db.userIndicatorResults.aggregate([
    {$group: {_id: "$fingerprint", indicatorId: {$first: "$indicatorId"},
              viewName: {$first: "$viewName"}, rawGraph: {$first: "$rawGraph"}}},
    {$sort: {_id: 1}}
  ]).toArray();
  // … wrap with schemaVersion + capturedAt …
' > src/test/resources/h52/replay-fixture.json
```

**PII redaction.** 15 of the 57 blobs had a researcher email embedded inside their
rawGraph (`userEmail`, `researcherId`). Python regex pass replaced every email with
`redacted@test.local` before commit. Researcher-authored publication titles are
public scholarly metadata and left intact.

**Per-view shape invariants discovered during the slice.** The empty-results case
trapped the first test draft: 18 of 38 `user/indicators-apply` entries had only
`{indicator, total}` because the score lookup returned nothing. Per-view universal
keys (intersection across all entries of that view):

| viewName | Entries | Universal keys |
|---|---|---|
| `user/indicators-apply` | 38 | `indicator`, `total` |
| `user/indicators-apply-activities` | 14 | `activities`, `allQuarters`, `allValues`, `indicator`, `scores`, `total` |
| `user/indicators-apply-publications` | 3 | `allQuarters`, `allValues`, `forumMap`, `indicator`, `publications`, `scores`, `total` |
| `user/indicators-apply-citations` | 2 | `allQuarters`, `allValues`, `citationMap`, `forumMap`, `indicator`, `publications`, `scores`, `total`, `totalCit` |

The test now pins these per-view guarantees individually — the universal assertion
across all 57 entries is just `indicator` + `total`.

**Generic JsonNode over typed view-model classes.** Tests use Jackson's `JsonNode`
rather than binding to the view model. The goal is to prove the cached JSON shape
stays *parseable*; binding to view-model classes would couple this test to refactors
of those classes, which is the opposite of the tripwire contract.

**Build status after slice 10: 2078 tests, 0 failures** (+8 in `H52ReplayShapeTest`).

### Commit 2 — write-through migration script

- `OrgSeedRunner`-style runner (`@Profile("indicator-migration")` or a CLI flag) that:
  - Reads every indicator.
  - Computes `kind`, `yearRange`, `scoreYearRange`, `selector`, `formulaHash`.
  - Validates the formula under the new variable contract; aborts the migration on
    the first failure with the indicator id + reason. No partial migration.
  - Writes the new fields back using `$set` only — **never replacing `_id`**.
  - Deletes `Mate_S (copy)` (the explicit duplicate, by `_id` `682343657123387ec34394b1`).
  - If the `Domain "ALL"` singleton was absent in pre-flight, the runner creates it now
    as its first action.
- Run the replay equality test against the migrated DB (the 2,463-row gate from the
  Test gate section).

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
- **Wider `@DBRef` cleanup.** v1 leaves these `@DBRef` fields in place:
  `Indicator.domain`, `Indicator.activity`, `AbstractReport.indicators`,
  `AbstractReport.Criterion.thresholds`. The roast established `@DBRef` as an
  anti-pattern; the migration of `AbstractReport.indicators` (a `@DBRef List<Indicator>`
  that binds every report to its indicators) is its own multi-commit task. Track as a
  separate `Hxx` after v1 lands.
- **Logging consolidation.** Per-publication INFO logs across the WoS strategies
  (`IMPACT_FACTOR scoring resolved...`, `AIS scoring missing...`, etc.) become
  per-indicator structured summaries at DEBUG. v1 leaves the per-item logs in place to
  avoid muddying the replay-equality check; the cleanup ships in the same touch surface
  immediately after.

## Implementation gotchas already burned through

Recorded so the next slice doesn't re-discover them.

### Spring Data Mongo `@Version` on existing documents

Adding `@Version Long version` to an entity that has existing Mongo docs without a
`version` field is hostile. Spring Data treats a `null` version as "this is a new
insert", so the next `save()` of an existing-but-just-loaded doc collides on `_id`:

```
E11000 duplicate key error collection: test.indicators
index: _id_ dup key: { _id: ObjectId('...') }
```

**Two backfills are needed**, not one:

1. **Mongo data:** `db.X.updateMany({version:{$exists:false}}, {$set:{version:0}})` for
   every collection getting `@Version`. Without this, the first save of every
   pre-existing doc fails.
2. **Form binding:** every Thymeleaf form that posts an `@ModelAttribute` of the
   versioned entity must carry a `<input type="hidden" name="version" th:value="${entity.version}">`,
   even on the create modal where the value is null. Without it, the form arrives with
   `version=null` and Spring Data hits the same E11000.

The slice‑2 smoke test caught the form gap by accident; bake the smoke test into
every future slice that adds `@Version` to another entity.

### `Indicator.Selector` naming collision

The legacy entity has a nested `public static enum Selector` and v1 adds a field of type
`ro.uvt.pokedex.core.model.reporting.scoring.Selector`. They have the same simple name.
Use the fully-qualified name for the scoring-package type inside `Indicator.java` to keep
both addressable without an import war. (The field is named `selectorSpec` to make the
distinction visible to callers; the legacy field stays `selector`.)

### Sealed-interface persistence to Mongo

`IndicatorKind`, `YearRangeSpec`, `ScoreYearRangeSpec`, and `Selector` are sealed
interfaces. Spring Data Mongo serialises them with an `_class` discriminator field by
default — works, but produces verbose documents. We accept this in slices 1‑2; if the
shape gets ugly enough to matter, register custom `Converter<X, Document>` /
`Converter<Document, X>` pairs in `MongoCustomConversions`.

### Round-trip lossiness pinned in tests

Two intentional non-bijections:

- `IndicatorKind.GenericCount().toLegacy()` returns `(Type.PUBLICATIONS, Strategy.GENERIC_COUNT)`,
  not `(Type.GENERIC_ACTIVITIES, Strategy.GENERIC_COUNT)` — because `GENERIC_COUNT` indicators
  in production all have `outputType=PUBLICATIONS`. Lossy on the way back; documented in
  `LegacyMappingTest.everyProductionComboMapsToExactlyOneKind`.
- `Selector.All().toLegacy()` returns `null`, not `Indicator.Selector.ALL` — because the
  legacy field used `null` (30/42) and `ALL` (10/42) interchangeably; null is the more
  common representation. Pinned in `LegacyMappingTest.selectorRoundTrip` using
  `Selector.fromLegacy(promoted.toLegacy())` rather than direct identity comparison.

### Pre-flight #2 also fixed a Step 7 regression

The `Domain "ALL"` check uncovered that **all 9 domain docs in production had `name=null`**
after the surrogate-key migration in Step 7. We backfilled `name = _id` for every doc.
This unblocks H52's domain-as-filter plan, but it also means `GeneralInitializationService`'s
startup duplicate-create path (which was probably firing on every boot) has been silently
broken since Step 7 landed. **No follow-up task needed** — the backfill is the fix — but
mention it in the slice 3 commit message so the link is preserved.

## Exit criteria

- Every existing indicator runs through the v1 pipeline and produces numerically identical
  results to the historical `userIndicatorResults` cache (per the test gate above).
- The pair `(Indicator.outputType, Indicator.scoringStrategy)` no longer appears anywhere
  in the codebase.
- `MVEL.eval(source, ...)` does not appear in any hot path; only `MVEL.executeExpression(compiled, ...)`.
- A new indicator with a formula referencing a variable not in its kind's contract is
  rejected at save time with a human-readable error pointing to the offending name.
- `LAST_YEAR` is grep-clean across the whole codebase, including
  `ImpactFactorJournalScoringService.LAST_YEAR` (line 27) and
  `ComputerScienceConferenceScoringService.LAST_CORE_YEAR`.
- **No indicator `_id` is rewritten by the migration.** H50's
  `IndividualReport.indicatorRolesByIndicatorId` and `blockByIndicatorId` bindings remain
  intact; the migration only `$set`s new fields and `$unset`s decommissioned ones.
- **Historical run rendering survives the field drop.** Both
  `GroupIndividualReportRun` and `UserIndividualReportRun` re-render under the new
  indicator shape without referencing dropped fields. Verified by replaying every
  persisted run's view-model build (224 + 126 runs) and asserting no
  `NullPointerException` / `IllegalStateException` from indicator field access.
- **MVEL sandbox passes a hostile-formula test suite.** A new
  `MvelSandboxIndicatorFormulaTest` asserts that the following all fail at parse / compile
  time (not at eval time):
  - `Class.forName("java.lang.System")`
  - `"".getClass().forName("java.lang.Runtime")`
  - `System.exit(0)`
  - `@{...}` interpolation that reaches static classes
  - any `new java.io.*` construction
- **`Indicator` has an optimistic-locking `@Version` field** so the heavier validate +
  compile save path can't lose a concurrent edit silently.
- **A perf baseline + post-migration delta is captured.** A microbenchmark on
  `FV Info 2016` against the seeded researchers records before / after wall-clock per
  indicator; result is appended to this doc when v1 lands. Compile-cache gain ≥ 5×
  expected per formula.
- No test in the suite is `@Disabled` because of this work.

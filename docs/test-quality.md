# Test Quality Measurement

Status: active test-quality guidance.

Two complementary tools wired into the build:

- **JaCoCo** — line and branch coverage. Cheap, runs alongside `test`. Tells you what code your tests touch.
- **PIT** — mutation testing. Slower, run on demand. Tells you what code your tests *actually verify*.

Coverage on its own is easy to game (a test with no assertions can hit 100% of lines). Mutation score is the truth-teller: PIT rewrites your bytecode with small bugs and checks whether any test fails. A surviving mutant means a real gap.

## Quick Reference

| What | Command | Report |
| --- | --- | --- |
| Branch coverage | `./gradlew test` | `build/reports/jacoco/test/html/index.html` |
| Coverage XML (CI) | `./gradlew test` | `build/reports/jacoco/test/jacocoTestReport.xml` |
| Mutation score | `./gradlew pitest` | `build/reports/pitest/index.html` |
| Mutation XML (CI) | `./gradlew pitest` | `build/reports/pitest/mutations.xml` |

`jacocoTestReport` is wired as a `finalizedBy` on `test`, so a normal `./gradlew test` produces both the test results and the coverage report. PIT is intentionally **not** on the `check` lifecycle — it's expensive and meant to be run on demand.

## Scope

PIT is scoped to where mutation testing pays off most:

- `ro.uvt.pokedex.core.service.*`
- `ro.uvt.pokedex.core.handlers.*`

Wider scopes (repository, controllers, full project) are easy to enable by editing the `targetClasses` and `targetTests` lists in `build.gradle`. Expect runtime to grow roughly linearly with covered class count.

JaCoCo runs over the entire codebase — there's no per-package scoping by default, but you can add `excludes` to the `jacocoTestReport` block if you want to omit generated code, config classes, etc.

## How to Read the Reports

### JaCoCo (branch coverage)

Open `build/reports/jacoco/test/html/index.html`. Drill into a package, then a class. Two columns matter:

- **Missed Branches** — every `if`, ternary, `&&`/`||`, and `switch` arm is a branch. Missed branches are the most useful coverage signal.
- **Missed Lines** — coarser. Use this only as a quick scan.

Practical targets:

- 70%+ branch coverage on `service` and `handlers` is a reasonable floor for a project this size.
- 80%+ is a good ambition for newly-written code.
- 100% is rarely worth chasing — the last 10% is usually exception paths and Spring plumbing.

Ignore line coverage on DTOs, configuration classes, and Lombok-generated methods. They inflate numbers without revealing anything.

### PIT (mutation score)

Open `build/reports/pitest/index.html`. The summary table shows, per package:

- **Line Coverage** — same idea as JaCoCo's, computed by PIT.
- **Mutation Coverage** — % of mutants killed by your tests. **This is the metric.**
- **Test Strength** — % of mutants killed *out of mutants on lines that were executed*. Strips out the "you didn't run this code at all" cases. If line coverage is 80% and test strength is 60%, your tests run code without verifying it.

Practical targets:

- 60%+ mutation score is a respectable starting baseline.
- 75%+ is a strong suite.
- 90%+ is rare; usually only worth pursuing for genuinely critical code paths.

Drill into a class to see surviving mutants line-by-line. Each surviving mutant is either (a) a missing assertion, (b) equivalent code (the mutant is semantically identical, can be ignored — flag with `@DoNotMutate` or excludes), or (c) dead code.

## Tuning

A few knobs in `build.gradle`'s `pitest` block worth knowing:

- **`mutators = ['DEFAULTS']`** — change to `['STRONGER']` for a more aggressive set (boundary conditions, return values, etc.). Slower, sharper signal. Recommended after the first baseline.
- **`threads = 4`** — bump to match your machine's core count.
- **`outputFormats = ['HTML', 'XML']`** — XML is what CI tooling (Codecov, Sonar, custom dashboards) consumes.
- **`timestampedReports = false`** — keeps `build/reports/pitest/` overwriting in place; flip to `true` if you want history.

If Java 25 bytecode trips up either tool, bump `jacoco.toolVersion` and `pitestVersion` to the latest releases.

## Suggested Workflow

1. Run `./gradlew test` once to get the JaCoCo baseline. Look for packages under 50% branch coverage — those are the obvious gaps.
2. Run `./gradlew pitest` for the service + handlers baseline. Note the mutation score; pick one or two classes with the worst score and review surviving mutants.
3. Treat surviving mutants as test gap reports — write the assertion that would have killed them.
4. Re-run after each round of new tests; expect mutation score to climb faster than coverage once you start writing assertions on outputs rather than just calling methods.

## CI Integration (Optional)

Both tools emit XML alongside HTML. To gate builds, add coverage thresholds via `jacocoTestCoverageVerification` or PIT's `mutationThreshold` — not enabled by default to avoid surprising existing CI.

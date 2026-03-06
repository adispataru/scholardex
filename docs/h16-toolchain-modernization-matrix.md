# H16.1 Toolchain Baseline and Target Matrix

## 1. Current Baseline (as-is)

Locked from repository state and local evidence gathered on 2026-03-06.

| Surface | Current value | Evidence |
| --- | --- | --- |
| Java toolchain in build | 21 | `build.gradle` (`java.toolchain.languageVersion = 21`) |
| Java launcher for `test` and `bootRun` | 21 | `build.gradle` (`javaLauncher` toolchain set to 21) |
| Gradle wrapper | 8.7 | `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl=...gradle-8.7-bin.zip`) |
| Spring Boot Gradle plugin | 3.2.4 | `build.gradle` plugins block |
| Spring dependency-management plugin | 1.1.4 | `build.gradle` plugins block |
| Lombok | 1.18.38 | `build.gradle` `compileOnly` + `annotationProcessor` |
| Testcontainers | 1.19.7 (resolved) | `./gradlew -q dependencyInsight --dependency org.testcontainers:junit-jupiter --configuration testRuntimeClasspath` |
| MongoDB Java driver stack | `mongodb-driver-sync` 4.11.1 (resolved) | `./gradlew -q dependencyInsight --dependency org.mongodb:mongodb-driver-sync --configuration runtimeClasspath` |
| CI Java pins | 21 (Temurin) | `.github/workflows/h09-quality-gates.yml`, `.github/workflows/h09-security-gates.yml` |
| CI Gradle execution | Wrapper (`./gradlew`) | same workflows above |

Local toolchain runtime evidence:

- `./gradlew --version` reports `Gradle 8.7` on `JVM 21`.
- `./gradlew tasks --all` confirms `check`, `compileJava`, `test`, and `javaToolchains` tasks are present.

Local wrapper behavior note:

- `gradlew` contains a macOS-specific guard that prefers JDK 21 when a newer default JDK is active (commented as Gradle 8.7 compatibility protection). This must be revisited during H16.2 once wrapper/runtime are upgraded.

## 2. Target Baseline (to-be)

Locked direction for H16:

- Java: 25
- Gradle: 9.x
- Spring Boot: newer LTS target line (4.0.x)

Pinned target baseline for execution handoff:

| Surface | Target pin | Source-backed compatibility note |
| --- | --- | --- |
| Java runtime/toolchain | 25 | Gradle compatibility matrix lists Java 25 support beginning with Gradle 9.1.0. |
| Gradle wrapper | 9.1.x (minimum 9.1.0) | Gradle docs: Java 25 support for running Gradle starts at 9.1.0. |
| Spring Boot plugin line | 4.0.x target line | Spring Boot system requirements for 4.0.x align with Java 25 and Gradle 9.1.x. |
| Spring dependency-management plugin | 1.1.x (exact patch to align with chosen Boot 4.0.x) | Must match Spring Boot 4.0.x tested ecosystem in H16.2/H16.3. |
| CI Java setup | Temurin 25 | Must match local Java 25 target and avoid local/CI drift. |

Important constraint:

- Spring support policy defines extended enterprise support on major-version last minors. H16 now targets the newer 4.0.x line; last-minor enterprise support status must be rechecked at H16.3 if 4.1+ exists by then.

## 3. Compatibility Matrix

Status values: `compatible` / `needs-upgrade` / `unknown`.

| Component | Current | Target | Compatibility status | Blocking risk | Owner action |
| --- | --- | --- | --- | --- | --- |
| Gradle wrapper | 8.7 | 9.1.x | `needs-upgrade` | High: Java 25 cannot run Gradle 8.7. | H16.2: bump wrapper to 9.1.x+, validate `./gradlew --version`, `compileJava`, `check`. |
| Spring Boot Gradle plugin | 3.2.4 | 4.0.x | `needs-upgrade` | High: major framework upgrade with BOM and API churn risk. | H16.3: upgrade Boot plugin to latest 4.0.x patch and fix breakages. |
| Spring dependency-management plugin | 1.1.4 | 1.1.x aligned to Boot 4.0.x | `unknown` | Medium: plugin/Gradle 9 interaction may require patch bump or removal strategy. | H16.3: verify Boot 4.0.x guidance; pin tested patch (or simplify plugin usage) before merge. |
| Lombok | 1.18.38 | version validated for Java 25 | `unknown` | Medium: annotation processing/compiler compatibility on new JDK. | H16.3: confirm Java 25 compatibility from Lombok release notes; bump if needed; re-run compile/test gates. |
| Testcontainers | 1.19.7 | version validated for Java 25 + Boot 4.0.x | `unknown` | Medium: runtime/test failures in integration path. | H16.3: verify support policy/release notes; upgrade if needed; validate tests touching containers. |
| MongoDB driver stack (`spring-boot-starter-data-mongodb`) | 4.11.1 via Boot 3.2.4 | Boot 4.0.x managed version | `needs-upgrade` | Medium: transitive upgrades can change codec/runtime behavior. | H16.3: adopt Boot 4.0.x managed driver; run regression checks around persistence flows. |
| CI setup actions (`setup-java`, quality/security workflows) | Java 21 | Java 25 | `needs-upgrade` | High: CI/local mismatch will invalidate gate parity. | H16.4: change workflow Java version to 25 and verify `java-smoke` + `quality-full`. |

H16.1 close condition for unknowns:

- `unknown` entries are allowed only with explicit owner action (above) and concrete next validation step.

## 4. Execution Order for H16.2/H16.3

1. Wrapper/toolchain first:
   - Upgrade Gradle wrapper to 9.1.x+.
   - Move local toolchain declarations from Java 21 to Java 25.
   - Remove or adjust the macOS JDK-21 guard in `gradlew`.
2. Framework/plugin uplift second:
   - Upgrade Spring Boot from 3.2.4 to 4.0.x.
   - Align dependency-management plugin to a Boot-validated patch or simplify if no longer required.
3. Dependency remediations third:
   - Resolve Lombok/Testcontainers/Mongo driver compatibility fallout.
   - Keep diffs minimal and constrained to compatibility fixes.
4. CI parity hardening last:
   - Update workflow Java setup to 25.
   - Confirm `java-smoke`, `quality-full`, and local `./gradlew check` all pass with same assumptions.

## 5. Rollback and Guard Conditions

Rollback trigger conditions:

- `./gradlew compileJava` fails after toolchain/wrapper uplift.
- `./gradlew check` fails with deterministic new failures attributable to toolchain/framework uplift.
- `java-smoke` or `quality-full` fails in CI due solely to environment/toolchain divergence.

Fallback baseline:

- Java 21 + Gradle 8.7 + Spring Boot 3.2.4 + existing CI Java 21 workflow pins.

Guard requirements during H16.2/H16.3:

- Keep each uplift step isolated and reversible.
- Do not combine toolchain/framework upgrades with unrelated refactors.
- Require local evidence (`compileJava`, `test`, `check`) before CI rollout.

## 6. Open Questions (must be zero to close H16.1)

None blocking H16.1 closure.

Tracked follow-ups (owned in matrix, not blockers for H16.1):

- Confirm exact Lombok version compatibility with Java 25 before final H16.3 merge.
- Confirm exact Testcontainers version policy for Java 25 in this repo's test profile.
- Confirm final dependency-management plugin patch choice under Boot 4.0.x + Gradle 9.1.x.

## Evidence Log (H16.1)

Commands executed:

1. `./gradlew --version`
2. `./gradlew tasks --all` (verified `check`, `compileJava`, `test`, `javaToolchains`)
3. `./gradlew -q dependencyInsight --dependency org.testcontainers:junit-jupiter --configuration testRuntimeClasspath`
4. `./gradlew -q dependencyInsight --dependency org.mongodb:mongodb-driver-sync --configuration runtimeClasspath`
5. Static verification from:
   - `build.gradle`
   - `gradle/wrapper/gradle-wrapper.properties`
   - `.github/workflows/h09-quality-gates.yml`
   - `.github/workflows/h09-security-gates.yml`

## Compatibility References

- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
- Spring Boot system requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring support policy (LTS context for last minor lines): https://spring.io/support-policy
- Spring support policy updates note: https://spring.io/blog/2025/02/13/support-policy-updates/

## H16.2 Status Update (2026-03-06)

Operational baseline after H16.2 execution:

- Gradle wrapper upgraded to `9.1.0`.
- Java toolchain and `test`/`bootRun` launchers upgraded to `25`.
- `gradlew` macOS guard now prefers JDK 25 when available.
- `io.spring.dependency-management` plugin upgraded `1.1.4 -> 1.1.7` to restore Gradle 9 compile compatibility.
- Validation pass set:
  - `./gradlew --version`
  - `./gradlew help --stacktrace`
  - `./gradlew compileJava --stacktrace`

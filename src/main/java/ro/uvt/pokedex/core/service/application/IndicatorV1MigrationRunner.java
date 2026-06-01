package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.Selector;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.List;

/**
 * H52 slice 3 write-through migration. Iterates every {@link Indicator}, populates
 * the v1 typed fields ({@code kind}, {@code yearRangeSpec}, {@code scoreYearRangeSpec},
 * {@code selectorSpec}) from the legacy fields via {@code getEffective*} helpers, and
 * saves only when the document actually changed. Idempotent — a re-run is a no-op.
 *
 * <p>Also deletes the orphaned {@code Mate_S} copy at {@code 682343657123387ec34394b1}
 * (kept the renamed {@code FV Info 2016} canonical row per H52 design).</p>
 *
 * <p>Gated by the {@code indicator-migration} Spring profile so it does not run on
 * normal app boots:</p>
 *
 * <pre>./gradlew bootRun --args='--spring.profiles.active=indicator-migration'</pre>
 *
 * <p>Slice 5 update: {@code formulaHash} is now populated automatically by
 * {@code IndicatorFormulaHashStamper}'s {@code onBeforeConvert} hook on every save,
 * including the saves this runner triggers. The runner does not set it directly —
 * if a doc is otherwise clean (all v1 fields already populated) and the runner
 * doesn't save it, the hash will be stamped on the next admin-edited save instead.
 * To force a hash-only backfill across every doc, run the runner with a no-op
 * field tweak, or call {@code indicatorRepository.save} on each doc once.</p>
 */
@Component
@Profile("indicator-migration")
@RequiredArgsConstructor
@Slf4j
public class IndicatorV1MigrationRunner implements CommandLineRunner {

    private static final String MATE_S_COPY_ID = "682343657123387ec34394b1";

    private final IndicatorRepository indicatorRepository;

    @Override
    public void run(@NonNull String... args) {
        log.warn("================================================================");
        log.warn("H52 INDICATOR MIGRATION RUNNER ACTIVE");
        log.warn("Populating v1 typed fields on indicators collection.");
        log.warn("This profile must NEVER run in production without a fresh backup.");
        log.warn("================================================================");

        List<Indicator> all = indicatorRepository.findAll();
        log.info("H52: loaded {} indicators", all.size());

        int populatedKind = 0;
        int populatedYear = 0;
        int populatedScoreYear = 0;
        int populatedSelector = 0;
        int saved = 0;
        int skippedNoLegacy = 0;

        for (Indicator ind : all) {
            boolean dirty = false;

            if (ind.getKind() == null) {
                IndicatorKind effective = ind.getEffectiveKind();
                if (effective == null) {
                    log.warn("H52: skipping {} ({}): no legacy outputType/scoringStrategy, can't synthesise kind",
                            ind.getId(), ind.getName());
                    skippedNoLegacy++;
                    continue;
                }
                ind.setKind(effective);
                populatedKind++;
                dirty = true;
            }

            if (ind.getYearRangeSpec() == null) {
                YearRangeSpec yrs = ind.getEffectiveYearRange();
                if (yrs != null) {
                    ind.setYearRangeSpec(yrs);
                    populatedYear++;
                    dirty = true;
                }
            }

            if (ind.getScoreYearRangeSpec() == null) {
                ScoreYearRangeSpec syrs = ind.getEffectiveScoreYearRange();
                if (syrs != null) {
                    ind.setScoreYearRangeSpec(syrs);
                    populatedScoreYear++;
                    dirty = true;
                }
            }

            if (ind.getSelectorSpec() == null) {
                Selector sel = ind.getEffectiveSelector();
                if (sel != null) {
                    ind.setSelectorSpec(sel);
                    populatedSelector++;
                    dirty = true;
                }
            }

            // H52 slice 5 + 8b: ensure every doc with a formula gets its formulaHash
            // stamped. The IndicatorFormulaHashStamper BeforeConvert listener does the
            // actual computation; here we just mark the doc dirty so it goes through save.
            //
            // Slice 8b extension: also re-stamp when the stored hash disagrees with
            // what the *current* canonicalizer produces. The slice-8b tokenizer changed
            // the canonical form (added space normalization), so every hash stamped
            // under slice 5 is now stale. The mismatch detection lets one migration
            // sweep re-stamp them all without writing an explicit "v1 → v2" flag.
            if (ind.getFormula() != null && !ind.getFormula().isBlank()) {
                if (ind.getFormulaHash() == null) {
                    dirty = true;
                } else {
                    String expected = ro.uvt.pokedex.core.service.reporting.formula.FormulaHasher
                            .hash(ind.getFormula());
                    if (!expected.equals(ind.getFormulaHash())) {
                        log.info("H52: formulaHash mismatch on indicator {} ({}); re-stamping (stored={}, expected={})",
                                ind.getId(), ind.getName(),
                                ind.getFormulaHash().substring(0, 12) + "…",
                                expected.substring(0, 12) + "…");
                        dirty = true;
                    }
                }
            }

            if (dirty) {
                indicatorRepository.save(ind);
                saved++;
                log.debug("H52: migrated indicator {} ({})", ind.getId(), ind.getName());
            }
        }

        log.info("H52 migration summary: scanned={}, saved={}, kind+={}, yearRange+={}, " +
                        "scoreYearRange+={}, selector+={}, skippedNoLegacy={}",
                all.size(), saved, populatedKind, populatedYear,
                populatedScoreYear, populatedSelector, skippedNoLegacy);

        // Mate_S copy deletion. The canonical row was renamed to "FV Info 2016"; this
        // id is the duplicate flagged during H52 pre-flight #1.
        indicatorRepository.findById(MATE_S_COPY_ID).ifPresentOrElse(
                doc -> {
                    log.warn("H52: deleting Mate_S copy id={} name='{}'", doc.getId(), doc.getName());
                    indicatorRepository.deleteById(MATE_S_COPY_ID);
                },
                () -> log.info("H52: Mate_S copy id={} already absent — nothing to delete", MATE_S_COPY_ID)
        );

        log.warn("H52 INDICATOR MIGRATION RUNNER COMPLETE");
    }
}

package ro.uvt.pokedex.core.service.reporting;

import java.util.function.Supplier;

/**
 * H60: a thread-scoped carrier for the run's {@code referenceYear} — the anchor that relative score-year specs
 * ({@code ScoreYearRangeSpec.LatestNRankings}) resolve against. The report computation (build + export/replay) wraps
 * its scoring in {@link #with(Integer, Supplier)}; the forum scorers read {@link #current()} when building the
 * resolution context. Chosen over threading the value through {@code ScoringService.getScore} (~15 impls) to keep the
 * diff small; the value is set/cleared in a try/finally so it never leaks across runs on a pooled thread.
 *
 * <p>{@link #current()} is empty outside a wrapped computation (ad-hoc scoring, unit tests) — relative specs then fall
 * back to their legacy no-context behaviour, exactly as before H60.</p>
 */
public final class ScoringReferenceYearContext {

    private static final ThreadLocal<Integer> REFERENCE_YEAR = new ThreadLocal<>();

    private ScoringReferenceYearContext() {
    }

    /** Runs {@code body} with {@code referenceYear} in scope, restoring the previous value afterwards. */
    public static <T> T with(Integer referenceYear, Supplier<T> body) {
        Integer previous = REFERENCE_YEAR.get();
        if (referenceYear != null) {
            REFERENCE_YEAR.set(referenceYear);
        } else {
            REFERENCE_YEAR.remove();
        }
        try {
            return body.get();
        } finally {
            if (previous != null) {
                REFERENCE_YEAR.set(previous);
            } else {
                REFERENCE_YEAR.remove();
            }
        }
    }

    /** The reference year in scope, or null when none is set (the legacy path). */
    public static Integer current() {
        return REFERENCE_YEAR.get();
    }

    /**
     * H60: the reference year for resolving relative specs — the run's year when a computation set it, else the
     * current year. This is the single default that makes relative specs resolve correctly on EVERY re-score path
     * (report view, indicator detail, xlsx/docx export, H50 snapshot, …) without each having to wrap explicitly; the
     * run-build path still overrides with the stored {@code referenceYear} so replay stays deterministic.
     */
    public static int currentOrCurrentYear() {
        Integer current = REFERENCE_YEAR.get();
        return current != null ? current : java.time.LocalDate.now().getYear();
    }
}

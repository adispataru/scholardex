package ro.uvt.pokedex.core.service.reporting;

import java.util.function.Supplier;

/**
 * H137: a thread-scoped carrier for facts about the researcher being scored that a standard's windows depend on
 * — today the year of the first PhD ({@code ResearcherProfile.phdAwardYear}), the anchor of
 * {@code YearRangeSpec.AfterPhdAward} ("după obținerea titlului de doctor") and of the {@code An_doctorat}
 * activity variable. Same shape and reason as {@link ScoringReferenceYearContext}: set/cleared in a try/finally
 * by the per-user computation entry points, read by the filters, empty for provisional subjects and unit tests
 * (then the windows fall back to their plain ranges).
 */
public final class ScoringSubjectContext {

    private static final ThreadLocal<Integer> PHD_AWARD_YEAR = new ThreadLocal<>();

    private ScoringSubjectContext() {
    }

    /** Runs {@code body} with the subject's PhD award year in scope (null = unknown), restoring the previous one. */
    public static <T> T withPhdAwardYear(Integer phdAwardYear, Supplier<T> body) {
        Integer previous = PHD_AWARD_YEAR.get();
        if (phdAwardYear != null) {
            PHD_AWARD_YEAR.set(phdAwardYear);
        } else {
            PHD_AWARD_YEAR.remove();
        }
        try {
            return body.get();
        } finally {
            if (previous != null) {
                PHD_AWARD_YEAR.set(previous);
            } else {
                PHD_AWARD_YEAR.remove();
            }
        }
    }

    /** The PhD award year of the subject in scope, or null when unknown or outside a wrapped computation. */
    public static Integer phdAwardYear() {
        return PHD_AWARD_YEAR.get();
    }
}

package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;

/**
 * The multiplication coefficient {@code m} of OM 3.019/2025, Comisia 25, definition [6], bound on publication
 * formulas as {@code Coef_m}:
 * <ul>
 *   <li>2 — published abroad, with international peer review, in a language of international circulation
 *       (English, French, German, Italian, Spanish);</li>
 *   <li>1,5 — written in such a language, but not published abroad;</li>
 *   <li>1 — everything else.</li>
 * </ul>
 *
 * <p>The platform holds neither the language of a publication nor the country of its publisher yet, so until a
 * {@link Resolver} is registered every publication gets <b>1</b>, marked {@link #NOT_DETERMINED}. That is the
 * lowest value the annex allows: the score it produces can only go up once the data exists.</p>
 *
 * <p>Static registry rather than a constructor dependency, like {@link PredatoryVenueSupport}: the variable is
 * bound in {@code ScientificProductionService}, whose constructor is spelled out by hand in several tests.</p>
 */
public final class PublicationCoefficientSupport {

    /** {@code scoringInfo} key carrying the basis of the coefficient applied to an item. */
    public static final String BASIS_KEY = "coefM";
    /** No language/country data for the item: the coefficient is the floor, 1. */
    public static final String NOT_DETERMINED = "NOT_DETERMINED";

    /** The coefficient of one publication and what it rests on. */
    public record Coefficient(double value, String basis) {
        public static Coefficient notDetermined() {
            return new Coefficient(1.0, NOT_DETERMINED);
        }
    }

    /** Supplies the coefficient from language and place of publication. */
    public interface Resolver {
        Coefficient resolve(ScoringPublicationReadModel publication);
    }

    private static volatile Resolver resolver;

    private PublicationCoefficientSupport() {
    }

    public static void register(Resolver registered) {
        resolver = registered;
    }

    public static Coefficient coefficientFor(ScoringPublicationReadModel publication) {
        Resolver current = resolver;
        if (current == null || publication == null) {
            return Coefficient.notDetermined();
        }
        Coefficient resolved = current.resolve(publication);
        if (resolved == null || !(resolved.value() >= 1.0) || resolved.value() > 2.0) {
            return Coefficient.notDetermined();
        }
        return resolved;
    }
}

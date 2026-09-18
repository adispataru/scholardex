package ro.uvt.pokedex.core.service.issn;

import ro.uvt.pokedex.core.model.issn.IssnVerification;

import java.util.Optional;

/**
 * Static seam between the scorers and {@link IssnVerificationService} — the same wiring as
 * {@code PredatoryVenueSupport}: scorers are constructed by hand in dozens of tests, so a registered lookup avoids
 * constructor churn. No registry (unit tests, startup) = "nothing known", which scores like an unverified ISSN.
 */
public final class IssnRegistrySupport {

    /** Supplied by the verification service at startup. */
    public interface Registry {
        Optional<IssnVerification> find(String normalizedIssn);
    }

    private static volatile Registry registry;

    private IssnRegistrySupport() {
    }

    public static void register(Registry r) {
        registry = r;
    }

    public static Optional<IssnVerification> find(String normalizedIssn) {
        Registry r = registry;
        return r == null || normalizedIssn == null ? Optional.empty() : r.find(normalizedIssn);
    }
}

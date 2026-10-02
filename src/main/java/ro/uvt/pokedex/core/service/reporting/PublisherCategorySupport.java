package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.activities.PublisherClaim;

import java.util.Map;
import java.util.Optional;

/**
 * H143 — the publisher category of a declared book, read by the activity scorer without a new constructor argument
 * (the static-registry pattern of {@link UefiscdiPublisherSupport}): {@link PublisherCategoryService} registers itself
 * at startup; until then, and in tests that do not register one, nothing is listed.
 */
public final class PublisherCategorySupport {

    /** What a list says about a publisher: the category, where it comes from, and a readable detail. */
    public record Classification(String category, String basis, String detail) {
    }

    /**
     * What a declared book counts as: the category formulas read (null when nothing counts), its basis — LIST,
     * WOS_MASTER_BOOK_LIST, CNCS, INTERNATIONAL_LIST, APPROVED_CLAIM, or, without a category, NOT_LISTED,
     * LISTED_NOT_COUNTED (a CNCS C for Music) and NO_PUBLISHER — and what the lists said.
     */
    public record Outcome(String category, String basis, String detail, Classification listed) {
    }

    public interface Classifier {
        Optional<Classification> classify(PublisherRules rules, String publisher);

        /** A listed publisher named inside a free text (an imported item), or empty. */
        default Optional<String> findIn(String text) {
            return Optional.empty();
        }
    }

    private static final Classifier NONE = (rules, publisher) -> Optional.empty();
    private static volatile Classifier classifier = NONE;

    private PublisherCategorySupport() {
    }

    public static void register(Classifier newClassifier) {
        classifier = newClassifier == null ? NONE : newClassifier;
    }

    public static void reset() {
        classifier = NONE;
    }

    /** What the lists say about a publisher, under a standard's rules. */
    public static Optional<Classification> classify(PublisherRules rules, String publisher) {
        if (rules == null || publisher == null || publisher.isBlank()) {
            return Optional.empty();
        }
        return classifier.classify(rules, publisher.trim());
    }

    /**
     * A publisher of the CNCS lists or of the international list named inside a free text — the line of a fișă
     * ("…, București: Editura Muzicală, 2019") — so an imported book carries its publisher without typing it.
     */
    public static Optional<String> findIn(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return classifier.findIn(text);
    }

    /** The category a declared book counts as: the best of the listed one and an approved request. */
    public static Outcome outcome(PublisherRules rules, String publisher, PublisherClaim claim, Map<String, String> fields) {
        Optional<Classification> listed = classify(rules, publisher);
        String listedCategory = listed.map(Classification::category).filter(rules::counts).orElse(null);
        Optional<String> claimed = approvedClaimCategory(rules, claim, fields);
        String best = rules.best(listedCategory, claimed.orElse(null));
        if (best == null) {
            String basis = listed.isPresent() ? "LISTED_NOT_COUNTED"
                    : (publisher == null || publisher.isBlank() ? "NO_PUBLISHER" : "NOT_LISTED");
            return new Outcome(null, basis, listed.map(Classification::detail).orElse(null), listed.orElse(null));
        }
        if (best.equals(listedCategory)) {
            Classification c = listed.get();
            return new Outcome(best, c.basis(), c.detail(), c);
        }
        return new Outcome(best, "APPROVED_CLAIM", claim.getRequested(), listed.orElse(null));
    }

    /**
     * The category an approved request grants: only while it is APPROVED and the record still asks for what was
     * approved — changing the request sends it back to the head.
     */
    public static Optional<String> approvedClaimCategory(PublisherRules rules, PublisherClaim claim, Map<String, String> fields) {
        if (claim == null || claim.getStatus() != PublisherClaim.Status.APPROVED || claim.getRequested() == null) {
            return Optional.empty();
        }
        String current = fields == null ? null : fields.get(PublisherRules.FIELD_CLAIM);
        if (current == null || !claim.getRequested().equals(current.trim())) {
            return Optional.empty();
        }
        return rules.claimCategory(claim.getRequested());
    }
}

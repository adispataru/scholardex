package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.service.reporting.PublisherRules;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * H143 — keeps a declared book's request for a publisher category in step with its fields: asking (or asking for
 * something else, or with other evidence) sends the request to a head as PENDING; clearing the field withdraws it.
 * Saving the record unchanged changes nothing, so an approved request stays approved.
 */
public final class PublisherClaimSupport {

    private PublisherClaimSupport() {
    }

    /** Brings the request in line with the record; true when it changed. {@code actor}: who saved the record. */
    public static boolean reconcile(ActivityInstance instance, String actor) {
        Map<String, String> fields = instance.getFields() == null ? Map.of() : instance.getFields();
        String requested = trimToNull(fields.get(PublisherRules.FIELD_CLAIM));
        String evidence = trimToNull(fields.get(PublisherRules.FIELD_CLAIM_EVIDENCE));
        PublisherClaim claim = instance.getPublisherClaim();
        if (requested == null) {
            if (claim == null || claim.getStatus() == null) {
                return false;
            }
            claim.getHistory().add(PublisherClaim.Event.of(PublisherClaim.Action.WITHDRAWN, actor, null));
            claim.setStatus(null);
            claim.setRequested(null);
            claim.setEvidence(null);
            clearDecision(claim);
            return true;
        }
        if (claim != null && claim.getStatus() != null && requested.equals(claim.getRequested())
                && Objects.equals(evidence, claim.getEvidence())) {
            return false;
        }
        if (claim == null) {
            claim = new PublisherClaim();
            instance.setPublisherClaim(claim);
        }
        claim.setStatus(PublisherClaim.Status.PENDING);
        claim.setRequested(requested);
        claim.setEvidence(evidence);
        claim.setRequestedAt(Instant.now());
        clearDecision(claim);
        claim.getHistory().add(PublisherClaim.Event.of(PublisherClaim.Action.REQUESTED, actor, requested));
        return true;
    }

    private static void clearDecision(PublisherClaim claim) {
        claim.setDecidedBy(null);
        claim.setDecidedAt(null);
        claim.setDecisionNote(null);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

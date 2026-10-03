package ro.uvt.pokedex.core.model.activities;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H143 — a researcher's request to classify the publisher of a declared book where no list can decide it (holdings in
 * WorldCat libraries, the complementary route of Comisia 28, a foreign publisher equivalent to CNCS A or B). What is
 * asked for lives in the record's own fields ({@code Incadrare_solicitata}, {@code Dovada_incadrarii}); this keeps
 * what was asked, the decision of a head of the researcher's department or faculty, and its history. A request counts
 * only while it is APPROVED and the record still asks for what was approved.
 */
@Data
public class PublisherClaim {

    public enum Status { PENDING, APPROVED, REJECTED }

    public enum Action { REQUESTED, APPROVED, REJECTED, WITHDRAWN }

    /** Null once withdrawn (the record no longer asks for a category). */
    private Status status;
    /** The option asked for, as written in the record when it was asked. */
    private String requested;
    /** The evidence given with it. */
    private String evidence;
    private Instant requestedAt;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    /**
     * H145 — a fingerprint of the record's facts the request concerns (its type, its date, every field but the
     * request's own two, its references): a decision holds only while they stay the same — a book approved as A2 that
     * becomes another book, or a chapter that becomes a book, goes back to the head.
     */
    private String facts;
    private List<Event> history = new ArrayList<>();

    /** The request's own fields (also {@code PublisherRules.FIELD_CLAIM}/{@code FIELD_CLAIM_EVIDENCE}). */
    public static final String REQUEST_FIELD = "Incadrare_solicitata";
    public static final String EVIDENCE_FIELD = "Dovada_incadrarii";

    /** H145 — the fingerprint of the facts of a record, as {@link #facts} stores it. */
    public static String factsOf(ActivityInstance instance) {
        StringBuilder text = new StringBuilder();
        text.append(instance.getActivity() == null ? "" : instance.getActivity().getId()).append('|')
                .append(instance.getDate() == null ? "" : instance.getDate()).append('|');
        if (instance.getFields() != null) {
            new java.util.TreeMap<>(instance.getFields()).forEach((k, v) -> {
                if (!REQUEST_FIELD.equals(k) && !EVIDENCE_FIELD.equals(k) && v != null && !v.isBlank()) {
                    text.append(k).append('=').append(v.trim()).append(';');
                }
            });
        }
        text.append('|');
        if (instance.getReferenceFields() != null) {
            java.util.Map<String, String> refs = new java.util.TreeMap<>();
            instance.getReferenceFields().forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    refs.put(k.name(), v.trim());
                }
            });
            refs.forEach((k, v) -> text.append(k).append('=').append(v).append(';'));
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * H145 — the record's request while it concerns the record as it is now; null when its facts changed since it was
     * made or decided (a request stamped before H145 counts until the startup stamp gives it its facts).
     */
    public static PublisherClaim inForce(ActivityInstance instance) {
        PublisherClaim claim = instance == null ? null : instance.getPublisherClaim();
        if (claim == null) {
            return null;
        }
        return claim.getFacts() == null || claim.getFacts().equals(factsOf(instance)) ? claim : null;
    }

    @Data
    public static class Event {
        private Instant at;
        private String by;
        private Action action;
        private String note;

        public static Event of(Action action, String by, String note) {
            Event event = new Event();
            event.setAt(Instant.now());
            event.setBy(by);
            event.setAction(action);
            event.setNote(note);
            return event;
        }
    }
}

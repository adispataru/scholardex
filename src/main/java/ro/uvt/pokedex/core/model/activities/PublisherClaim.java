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
    private List<Event> history = new ArrayList<>();

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

package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H145 — whether a publication a researcher added through the wizard counts. It does once its DOI resolves at Crossref
 * to the same work (title, year, the researcher among the authors, the venue), or once a head of the researcher's
 * department or faculty approved it, and only while the entry still states what was verified ({@link #facts}: title,
 * type, date, venue, authors, DOI, volume, pages). A publication another source holds as well (Scopus, Web of Science,
 * OpenAlex) needs no review. Durable across full rebuilds (SPARED): keyed by the wizard entry's source record id, which
 * is deterministic, and fingerprinted on the entry as submitted, not on canonical ids a rebuild may re-mint.
 */
@Data
@Document(collection = "scholardex.wizard_publication_reviews")
public class WizardPublicationReview {

    public enum Status { PENDING, VERIFIED, APPROVED, REJECTED }

    /** The wizard entry's source record id ({@code USER_DEFINED:PUBLICATION:…}). */
    @Id
    private String id;
    /** The canonical publication when last seen, for the head's page. */
    private String publicationId;
    private String submitterEmail;
    private Status status;
    /** The fingerprint of the entry's facts the status is about. */
    private String facts;
    /** What did not match at Crossref, for the head. */
    private String verificationNote;
    /** {@code crossref} or the head who decided. */
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private Instant createdAt;
    private List<Event> history = new ArrayList<>();

    @Data
    public static class Event {
        private Instant at;
        private String by;
        private Status status;
        private String note;

        public static Event of(Status status, String by, String note) {
            Event event = new Event();
            event.setAt(Instant.now());
            event.setBy(by);
            event.setStatus(status);
            event.setNote(note);
            return event;
        }
    }
}

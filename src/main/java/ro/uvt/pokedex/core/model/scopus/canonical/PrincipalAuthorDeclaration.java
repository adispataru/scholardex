package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A researcher's statement that they are a PRINCIPAL author of a publication in a way no data source shows:
 * as corresponding author, or with a contribution equal to the first author's. The standards count such a
 * publication like a first-author one (OM 3.019/2025, Comisia 28: "autor principal" cases (c) and (e)).
 *
 * <p>The statement counts only once a head of the researcher's department or faculty, or a platform admin,
 * has approved it against the article. Everything that happened to it is kept in {@link #history}.</p>
 *
 * <p>A side collection that is never rebuilt. One document per researcher and publication; the publication is
 * also remembered by DOI, title and year, so the statement still finds it after a rebuild gave it a new id.</p>
 */
@Data
@Document(collection = "scholardex.principal_author_declarations")
@CompoundIndex(name = "uniq_principal_author_declaration", def = "{'userEmail': 1, 'publicationId': 1}", unique = true)
public class PrincipalAuthorDeclaration {

    public enum Status { PENDING, APPROVED, REJECTED, WITHDRAWN }

    public enum Kind {
        /** "autorul corespondent menționat în publicație" */
        CORRESPONDING_AUTHOR,
        /** "se precizează explicit în cadrul publicației că autorul are o contribuție egală cu primul autor" */
        EQUAL_CONTRIBUTION
    }

    public enum Action { DECLARED, APPROVED, REJECTED, WITHDRAWN, REVOKED }

    @Id
    private String id;
    private Status status;
    private Kind kind;

    /** The researcher the statement is about (and who made it). */
    private String userEmail;

    private String publicationId;
    private String doiNormalized;
    private String titleNormalized;
    private Integer year;
    /** As shown to the person who decides. */
    private String publicationTitle;

    /** Where the article says it: "footnote on the first page", "Author contributions, p. 12", … */
    private String evidence;
    private String evidenceUrl;

    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;

    private List<Event> history = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

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

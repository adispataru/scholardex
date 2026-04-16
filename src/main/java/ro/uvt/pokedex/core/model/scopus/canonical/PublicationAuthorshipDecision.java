package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.publication_authorship_decisions")
@CompoundIndex(
        name = "uniq_publication_authorship_decision_user_publication",
        def = "{'userEmail': 1, 'publicationId': 1}",
        unique = true
)
public class PublicationAuthorshipDecision {
    @Id
    private String id;
    private String userEmail;
    private String researcherId;
    private String publicationId;
    private Status status;
    private DecisionSource decisionSource;
    private String reason;
    private Snapshot snapshot;
    private Instant createdAt;
    private Instant updatedAt;

    public enum Status {
        CONFIRMED,
        REJECTED
    }

    public enum DecisionSource {
        USER_REVIEW
    }

    @Data
    public static class Snapshot {
        private PublicationSnapshot publication = new PublicationSnapshot();
        private UserSnapshot user = new UserSnapshot();
        private List<String> linkedAuthorIds = new ArrayList<>();
    }

    @Data
    public static class PublicationSnapshot {
        private String title;
        private String eid;
        private String doi;
    }

    @Data
    public static class UserSnapshot {
        private String researcherId;
        private String primaryScholardexAuthorId;
        private String firstName;
        private String lastName;
    }
}

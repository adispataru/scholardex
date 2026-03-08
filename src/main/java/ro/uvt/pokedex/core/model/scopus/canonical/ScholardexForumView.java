package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scholardex.forum_view")
public class ScholardexForumView {
    @Id
    private String id;
    private String publicationName;
    private String issn;
    private String eIssn;
    private String aggregationType;
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;
}

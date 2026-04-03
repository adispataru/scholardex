package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;

import java.time.Instant;

@Data
public class ScholardexForumView {
    private String id;
    private String publicationName;
    private String issn;
    private String eIssn;
    private String isbn;
    private String aggregationType;
    private String publisher;
    private String scopusId;
    private boolean approved;
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;
}

package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    /** H66 A1: C-scalar — normalized venue kind (journal/book-series/conference/trade). */
    private String forumType;
    /** H66 A1: C-scalar — Scopus ASJC subject codes (snapshot). */
    private List<String> asjc = new ArrayList<>();
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;
}

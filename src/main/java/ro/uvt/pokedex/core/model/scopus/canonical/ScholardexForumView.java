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
    /** Multi-type: the distinct per-source venue kinds; {@link #aggregationType} is the most-specific primary. */
    private List<String> aggregationTypes = new ArrayList<>();
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;

    /**
     * Whether this venue is classified as {@code type} by ANY source (multi-type). Falls back to the primary
     * {@link #aggregationType} when the multi-type set is empty (e.g. activity-built or pre-migration forums).
     */
    public boolean hasAggregationType(String type) {
        if (type == null) {
            return false;
        }
        if (aggregationTypes != null && !aggregationTypes.isEmpty()) {
            for (String t : aggregationTypes) {
                if (type.equalsIgnoreCase(t)) {
                    return true;
                }
            }
            return false;
        }
        return type.equalsIgnoreCase(aggregationType);
    }
}

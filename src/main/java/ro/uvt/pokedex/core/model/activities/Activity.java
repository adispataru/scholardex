package ro.uvt.pokedex.core.model.activities;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Document(collection = "activities")
public class Activity {
    @Id
    private String id;
    protected String name;
    private List<Field> fields;
    private List<ReferenceField> referenceFields;
    /**
     * H145 — a researcher holds at most one record of this type (a Google Scholar profile: its citations and h-index
     * are counted once). A second one is refused when saved; scoring counts only the best of any that exist.
     */
    private Boolean singlePerResearcher;

    public boolean isSingle() {
        return Boolean.TRUE.equals(singlePerResearcher);
    }

    /**
     * H145 — a record of this type declares a publication (an article, a book, a chapter, a coordinated volume, a
     * course): when the same publication is in the researcher's list (same DOI, or same title within a year), it counts
     * once — from the list, or from the record when a head approved the record's category.
     */
    private Boolean publicationRecord;

    public boolean isPublication() {
        return Boolean.TRUE.equals(publicationRecord);
    }

    @Data
    public static class Field {
        public String name;
        public List<String> allowedValues;
        public boolean number = false;
    }

    public static enum ReferenceField {
        FORUM_NAME,
        FORUM_ISSN,
        FORUM_EISSN,
        FORUM_ISBN,
        FORUM_PUBLISHER,
        PROJECT_GRANT_ID,
        UNIVERSITY_NAME,
        EVENT_NAME,
        /** H144 — the registries experts rank: a conference, an organisation (body, institution, media), an award. */
        CONFERENCE_NAME,
        ORGANIZATION_NAME,
        AWARD_NAME
    }
}

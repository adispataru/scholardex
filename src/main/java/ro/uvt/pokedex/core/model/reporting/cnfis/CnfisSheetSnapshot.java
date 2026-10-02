package ro.uvt.pokedex.core.model.reporting.cnfis;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H129 — a FROZEN Anexa 5: the sheet exactly as the person handed it in for one edition, kept so a later
 * change of the data does not alter the record, and so the head's Anexa 6 is the sum of what was handed in.
 * The same idea as the evaluation snapshots, with the shape of the sheet instead of scores.
 * <p>
 * The person may delete it as long as no institutional table was built from it; once one was,
 * {@link #lockedByUnitSheetId} names it and only the head may release the sheet.
 */
@Data
@Document(collection = "cnfisSheetSnapshots")
@CompoundIndex(name = "idx_cnfis_snapshot_user_edition_created", def = "{'userEmail': 1, 'reportingYear': 1, 'createdAt': -1}")
public class CnfisSheetSnapshot {

    @Id
    private String id;
    private String userEmail;
    private String displayName;
    private int reportingYear;
    private Instant createdAt;

    private CnfisSheetHeader header;
    private Double cnatdcuScore;

    private List<Row> rows = new ArrayList<>();
    private List<LeftOut> leftOut = new ArrayList<>();
    private List<Patent> patents = new ArrayList<>();
    /** Anexa 5.1 — the artistic performances, for a person of an artistic domain. */
    private List<ArtsRow> artsRows = new ArrayList<>();
    /** Anexa 4.1 (H142) — the citations of artistic works, the whole career: absent on snapshots frozen before it existed. */
    private List<CitationRow> artsCitationRows = new ArrayList<>();
    /** Anexa 5.2 (H129, sport): absent on snapshots frozen before it existed. */
    private List<SportRow> sportRows = new ArrayList<>();
    /** Anexa 5.3 — Scopus articles, books, edited volumes, chapters, critical editions, translations (humanities). */
    private List<HumanitiesRow> humanitiesRows = new ArrayList<>();

    private String lockedByUnitSheetId;

    /**
     * True when the HEAD generated this copy from live data for a member who had frozen none: it stands in
     * for the person's sheet in the institutional table until the person freezes their own, and is marked
     * as such wherever it shows.
     */
    private boolean provisional;
    private String createdBy;

    /** One row of the form: the publication, as it was, and its classification. */
    @Data
    public static class Row {
        private String publicationId;
        private String title;
        private String doi;
        private String wosCode;
        private String year;
        private String forumId;
        private int authorCount;
        private CNFISReport2025 classification;
    }

    @Data
    public static class LeftOut {
        private String publicationId;
        private String title;
        private String year;
        private String venue;
        private String doi;
        private String reason;
    }

    /** One row of Anexa 5.1, from the declared activity "Participare eveniment artistic". */
    @Data
    public static class ArtsRow {
        private String activityInstanceId;
        private String year;
        private String work;
        private String event;
        /** NATIONAL, INTERNATIONAL, INTERNATIONAL_TOP — the rank of the event in the registry. */
        private String level;
        /** INDIVIDUAL, GROUP, COLLECTIVE, NOMINATION, PRIZE — the declared kind of the work. */
        private String kind;
        private int universityParticipants;
    }

    /** One row of Anexa 4.1, from the declared activity "Citare sau cronică a unei creații artistice (CNFIS 4.1)". */
    @Data
    public static class CitationRow {
        private String activityInstanceId;
        /** The year the cited work was made (column B). */
        private String workYear;
        /** The work, as the form identifies it: its name, the event, the place, the date (column C). */
        private String work;
        /** The publication, its issue and the year of the citation (column D). */
        private String citation;
        private String citationYear;
    }

    /** One row of Anexa 5.2, from the declared activity "Performanță sportivă (CNFIS 5.2)". */
    @Data
    public static class SportRow {
        private String activityInstanceId;
        private String year;
        private String activity;
        private String championship;
        /** UNIVERSITY, NATIONAL, EUROPEAN, INTERNATIONAL_ROMANIA, WORLD — the level of the championship. */
        private String level;
        /** PLACE_1 … PLACE_6, PLACES_4_6, PLACES_7_8 — the place obtained. */
        private String place;
        /** NATIONAL, EUROPEAN, WORLD — a record set, or null. */
        private String record;
        private int universityParticipants;
    }

    /** One row of Anexa 5.3. */
    @Data
    public static class HumanitiesRow {
        /** the publication, or the declared activity, the row came from */
        private String sourceId;
        private String year;
        /** the journal, the book, the volume the chapter is in */
        private String containerTitle;
        private String publisher;
        private String isbn;
        private String issnOnline;
        private String issnPrint;
        private String doi;
        /** the article or the chapter; empty for a book */
        private String itemTitle;
        /** SCOPUS_Q1..SCOPUS_Q4, BOOK, EDITED_VOLUME, CHAPTER, CRITICAL_EDITION, TRANSLATION */
        private String category;
        private Integer listYear;
        private String classifiedBy;
        private Integer pages;
        private int authorCount;
        private int universityAuthorCount;
    }

    /** A patent, from the declared activity "Brevet". */
    @Data
    public static class Patent {
        private String activityInstanceId;
        private String year;
        private String title;
        private String code;
        private String office;
        private String type;
        private int authorCount;
        private int universityAuthorCount;
    }
}

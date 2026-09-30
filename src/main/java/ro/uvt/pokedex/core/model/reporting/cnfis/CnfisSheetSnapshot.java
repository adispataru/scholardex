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

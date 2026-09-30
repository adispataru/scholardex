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

    private String lockedByUnitSheetId;

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

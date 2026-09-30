package ro.uvt.pokedex.core.model.reporting.cnfis;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H129 — what a person fills in at the head of their Anexa 5 for one edition: the CNATDCU domain they report
 * in (it decides which sheets apply), where the CNATDCU score comes from, the unmet criterion, and the three
 * Hirsch values (from signed print screens of Google Scholar, Web of Science and Scopus — typed in, the
 * platform's own values are for orientation).
 */
@Data
@Document(collection = "cnfisSheets")
@CompoundIndex(name = "uniq_cnfis_sheet_user_edition", def = "{'userEmail': 1, 'reportingYear': 1}", unique = true)
public class CnfisSheetHeader {

    @Id
    private String id;
    private String userEmail;
    private int reportingYear;

    private String domainCode;
    private String domainName;

    /** The CNATDCU report whose latest run supplies the score; null = the score is typed in. */
    private String scoreReportId;
    private Double scoreTyped;
    private String unmetCriterion;

    private Integer hirschGoogleScholar;
    private Integer hirschWebOfScience;
    private Integer hirschScopus;

    private Instant updatedAt;
}

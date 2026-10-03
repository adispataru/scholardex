package ro.uvt.pokedex.core.model.reporting.cnfis;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H129 — what a person fills in at the head of their Anexa 5 for one edition: the CNATDCU domain they report
 * in (it decides which sheets apply) and the CNATDCU report whose run gives the score. H145: the score, the unmet
 * criteria and the Hirsch values are derived, never typed (documents saved before keep their typed values, unread).
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

    /** The CNATDCU report whose latest run supplies the score and the unmet criteria; null = no score. */
    private String scoreReportId;

    private Instant updatedAt;
}

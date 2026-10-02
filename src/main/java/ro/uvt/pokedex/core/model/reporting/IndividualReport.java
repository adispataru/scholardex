package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.Institution;

import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@Document(collection = "individualReports")
public class IndividualReport extends AbstractReport {
    @DBRef
    private Institution individualAffiliation = null;

    /**
     * H129: whose rules the report applies. Absent on the reports stored before the field existed — read it
     * through {@link #effectiveAuthority()}.
     */
    private ReportAuthority authority;

    /** {@link #authority}, or CNATDCU for a report that names none (all but three of them are). */
    public ReportAuthority effectiveAuthority() {
        return authority != null ? authority : ReportAuthority.CNATDCU;
    }

    /**
     * H137: the competition's limit on the age of the first PhD, in years before {@link #competitionDeadline}
     * (PD 2026 director: 8; TE 2026 director: 12; null = no limit, e.g. the mentor). Checked on the page against
     * the researcher's {@code phdAwardYear}; parental-leave exclusions stay manual.
     */
    private Integer phdLimitYears;

    /** H137: the submission deadline the PhD-age limit is measured at (both 2026 competitions: 2026-07-30). */
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
    private java.time.LocalDate competitionDeadline;

    /**
     * H137: whether a researcher with this PhD award year is within the limit at the deadline, by whole years
     * (award year + limit ≥ deadline year — the award date is not held, so the year is the granularity). Null
     * when the report sets no limit or the year is unknown.
     */
    public Boolean withinPhdLimit(Integer phdAwardYear) {
        if (phdLimitYears == null || competitionDeadline == null || phdAwardYear == null) {
            return null;
        }
        return phdAwardYear + phdLimitYears >= competitionDeadline.getYear();
    }

    /** Binds this report to a registered {@code ReportTypeImportSupport} (H50). Null = export disabled. */
    private String reportTypeKey;

    /** When true, users may upload a filled file to run the read-only score verification (H50.3). */
    private boolean importEnabled = false;

    /** Maps {@code Indicator.id} → binding roleKey for the {@link #reportTypeKey} support. */
    private Map<String, String> indicatorRolesByIndicatorId = new HashMap<>();

    /**
     * For STACKED_BLOCKS roles, this maps {@code Indicator.id} → {@code BindingBlock.activityName}.
     * Reverse direction (indicator-keyed) so multiple indicators can feed the same block — e.g.
     * several PhD-committee indicators all populating the "Comisii doctorat" block.
     */
    private Map<String, String> blockByIndicatorId = new HashMap<>();
}

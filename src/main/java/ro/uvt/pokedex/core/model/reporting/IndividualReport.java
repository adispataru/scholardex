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

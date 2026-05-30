package ro.uvt.pokedex.core.model.org;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A positive selection: an {@link OrgDivision} head has chosen this {@link
 * ro.uvt.pokedex.core.model.reporting.IndividualReport} as one of the reports that apply
 * to their division. A report becomes visible to users only via such a selection.
 *
 * <p>Department heads can further hide reports for their subdivision via
 * {@link DepartmentReportHide}, but they cannot add new ones — adding belongs to the
 * division head.
 */
@Data
@Document(collection = "scholardex.division_report_selections")
@CompoundIndexes({
        @CompoundIndex(name = "division_report_unique", def = "{'divisionId': 1, 'reportId': 1}", unique = true)
})
public class DivisionReportSelection {
    @Id
    private String id;

    @Indexed
    private String divisionId;

    @Indexed
    private String reportId;

    /** Email of the head who selected the report. */
    private String selectedBy;

    private Instant selectedAt;
}

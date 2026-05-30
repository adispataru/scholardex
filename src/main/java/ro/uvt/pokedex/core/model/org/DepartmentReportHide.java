package ro.uvt.pokedex.core.model.org;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A subtractive override: a {@link Department} head has hidden a report that the parent
 * {@link OrgDivision} would otherwise expose to this department's groups.
 */
@Data
@Document(collection = "scholardex.department_report_hides")
@CompoundIndexes({
        @CompoundIndex(name = "department_report_unique", def = "{'departmentId': 1, 'reportId': 1}", unique = true)
})
public class DepartmentReportHide {
    @Id
    private String id;

    @Indexed
    private String departmentId;

    @Indexed
    private String reportId;

    /** Email of the head who hid the report. */
    private String hiddenBy;

    private Instant hiddenAt;
}

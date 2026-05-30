package ro.uvt.pokedex.core.model.org;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Joint-appointment-aware link between a user and a department. Time-bounded so historical
 * reports can ask "who was in this department during the report window".
 */
@Data
@Document(collection = "scholardex.department_affiliations")
@CompoundIndexes({
        @CompoundIndex(name = "user_dept_current", def = "{'userId': 1, 'departmentId': 1, 'validTo': 1}"),
        @CompoundIndex(name = "dept_current", def = "{'departmentId': 1, 'validTo': 1}")
})
public class DepartmentAffiliation {
    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private String departmentId;

    /** Exactly one current affiliation per user should carry primary=true. Enforced in service layer. */
    private boolean primary;

    private LocalDate validFrom;

    /** Null = current. */
    private LocalDate validTo;

    private Instant createdAt;
}

package ro.uvt.pokedex.core.model.org;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.departments")
public class Department {
    @Id
    private String id;

    @Indexed
    private String divisionId;

    /** Denormalized from {@link OrgDivision#getInstitutionId()}. Rebuilt on write; never trusted from client input. */
    @Indexed
    private String institutionId;

    private String name;

    /** User identifiers (currently email; will become surrogate user id). Implicit supervisors of all groups in this department. */
    private List<String> headUserIds = new ArrayList<>();

    private List<String> domainIds = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}

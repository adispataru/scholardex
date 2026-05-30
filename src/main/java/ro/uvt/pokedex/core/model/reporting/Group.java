package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.groups")
public class Group {
    @Id
    private String id;
    private String name;
    private String description;

    /** Departments this group belongs to. ≥1 required for new groups; joint labs span multiple. */
    private List<String> departmentIds = new ArrayList<>();

    /** Denormalized from the parent {@code Department}s — must be a single institution. Rebuilt on write. */
    @Indexed
    private String institutionId;

    private List<String> domainIds = new ArrayList<>();

    /** Group-level supervisors. Implicit supervision also comes from Department.headUserIds and OrgDivision.headUserIds. */
    private List<String> supervisorUserIds = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}

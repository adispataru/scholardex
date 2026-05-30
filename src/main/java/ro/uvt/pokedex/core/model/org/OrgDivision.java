package ro.uvt.pokedex.core.model.org;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.org_divisions")
public class OrgDivision {
    @Id
    private String id;

    @Indexed
    private String institutionId;

    private DivisionType type;
    private String name;
    private String shortName;

    /** User identifiers (currently email; will become surrogate user id). Implicit supervisors of all descendant departments and groups. */
    private List<String> headUserIds = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}

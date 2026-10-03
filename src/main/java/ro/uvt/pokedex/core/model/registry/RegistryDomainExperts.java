package ro.uvt.pokedex.core.model.registry;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H142 slice 3, H144 — who ranks the registries' entries of one domain ("Muzică", "Științe ale educației", …), in
 * every registry: the heads of the departments that answer for it (a department's directors and the heads of its
 * faculty) and the experts an admin names. A researcher of those departments proposes into this domain.
 */
@Data
@Document(collection = "scholardex.registry_domain_experts")
public class RegistryDomainExperts {
    /** The domain, as the registries write it ({@link RegistryItem#getDomainId()}). */
    @Id
    private String domain;
    private List<String> departmentIds = new ArrayList<>();
    private List<String> expertEmails = new ArrayList<>();
    private String updatedBy;
    private Instant updatedAt;
}

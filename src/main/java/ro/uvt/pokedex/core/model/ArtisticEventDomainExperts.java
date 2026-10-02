package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H142 slice 3 — who ranks the artistic events of one domain ("Muzică", "Teatru şi artele spectacolului", …): the heads
 * of the departments that answer for it (a department's directors and the heads of its faculty) and the experts an
 * admin names. Experts also see the proposals of the researchers of those departments.
 */
@Data
@Document(collection = "scholardex.artistic_event_domain_experts")
public class ArtisticEventDomainExperts {
    /** The domain, as the registry writes it ({@link ArtisticEvent#getDomainId()}). */
    @Id
    private String domain;
    private List<String> departmentIds = new ArrayList<>();
    private List<String> expertEmails = new ArrayList<>();
    private String updatedBy;
    private Instant updatedAt;
}

package ro.uvt.pokedex.core.model.tasks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * H66B Phase 4a — on-demand OpenAlex author sync task. Mirrors {@link ScopusPublicationUpdate}. Keyed on the
 * researcher's ORCID; carries the canonical author id (captured at creation) so the canonicalization can attach
 * an OPENALEX authorship edge making synced works visible in the researcher's workspace.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Document(collection = "scholardex.tasks.openAlexAuthorUpdate")
public class OpenAlexAuthorUpdate extends Task {
    @Id
    private String id;
    /** Normalized bare ORCID driving the {@code author.orcid:} filter. */
    private String orcid;
    /** Canonical Scholardex author id of the syncing researcher (their {@code primaryScholardexAuthorId}). */
    private String researcherAuthorId;
}

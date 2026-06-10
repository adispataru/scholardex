package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;

import java.time.Instant;

/**
 * Sanctioned write surface for {@code scholardex.author_facts} (H54.5c).
 *
 * <p>Author canonicalization is grandfathered (bulk builder), so the only secondary mutator is the
 * user manual edit relocated out of {@code ScholardexProjectionReadService}; this writer exposes
 * just that. Stamps {@code source}/{@code sourceRecordId}/{@code updatedAt} (not batch/correlation,
 * which are preserved), persists, and upserts the AUTHOR source link with null batch/correlation/event.
 */
@Service
public class ScholardexAuthorWriter {

    private final ScholardexAuthorFactRepository repository;
    private final ScholardexSourceLinkService sourceLinkService;

    public ScholardexAuthorWriter(
            ScholardexAuthorFactRepository repository,
            ScholardexSourceLinkService sourceLinkService) {
        this.repository = repository;
        this.sourceLinkService = sourceLinkService;
    }

    public ScholardexAuthorFact applyManualEdit(
            ScholardexAuthorFact fact, String source, String sourceRecordId, String sourceLinkReason) {
        fact.setSource(source);
        fact.setSourceRecordId(sourceRecordId);
        fact.setUpdatedAt(Instant.now());
        ScholardexAuthorFact saved = repository.save(fact);
        if (sourceRecordId != null) {
            sourceLinkService.link(
                    ScholardexEntityType.AUTHOR, source, sourceRecordId, fact.getId(),
                    sourceLinkReason, null, null, null, false);
        }
        return saved;
    }
}

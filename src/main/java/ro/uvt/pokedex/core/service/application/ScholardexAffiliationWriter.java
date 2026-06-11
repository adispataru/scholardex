package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;

import java.time.Instant;

import ro.uvt.pokedex.core.service.importing.BuilderVersion;

/**
 * Sanctioned write surface for {@code scholardex.affiliation_facts} (H54.5c).
 *
 * <p>Affiliation canonicalization is grandfathered (bulk builder), so the only secondary mutator is
 * the user manual edit relocated out of {@code ScholardexProjectionReadService}; this writer exposes
 * just that. Stamps {@code source}/{@code sourceRecordId}/{@code updatedAt} (not batch/correlation,
 * which are preserved), persists, and upserts the AFFILIATION source link with null batch/correlation/event.
 */
@Service
public class ScholardexAffiliationWriter {

    private final ScholardexAffiliationFactRepository repository;
    private final ScholardexSourceLinkService sourceLinkService;

    public ScholardexAffiliationWriter(
            ScholardexAffiliationFactRepository repository,
            ScholardexSourceLinkService sourceLinkService) {
        this.repository = repository;
        this.sourceLinkService = sourceLinkService;
    }

    public ScholardexAffiliationFact applyManualEdit(
            ScholardexAffiliationFact fact, String source, String sourceRecordId, String sourceLinkReason) {
        fact.setSource(source);
        fact.setSourceRecordId(sourceRecordId);
        fact.setUpdatedAt(Instant.now());
        fact.setBuilderVersion(BuilderVersion.SCHOLARDEX_AFFILIATION);
        ScholardexAffiliationFact saved = repository.save(fact);
        if (sourceRecordId != null) {
            sourceLinkService.link(
                    ScholardexEntityType.AFFILIATION, source, sourceRecordId, fact.getId(),
                    sourceLinkReason, null, null, null, false);
        }
        return saved;
    }
}

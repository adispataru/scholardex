package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.time.Instant;

/**
 * Sanctioned write surface for secondary {@code scholardex.publication_facts} mutators (H54.5a).
 *
 * <p>The four Scopus bulk canonicalization builders are grandfathered and keep their own optimized
 * write paths; the ad-hoc single-record mutators (user-defined canonicalization, enrichment linking,
 * and — later — manual edits) route their persistence through here so provenance stamping and the
 * source-link upsert happen consistently in one place. This is also the future chokepoint for
 * dirty-marker emission and {@code builderVersion} stamping (H54.6) without touching every caller.
 *
 * <p>Callers set the fact's content (including its own {@code sourceEventId} where they manage it)
 * before calling; the writer stamps the uniform provenance fields + {@code updatedAt}, persists, and
 * upserts the PUBLICATION source link.
 */
@Service
public class ScholardexPublicationWriter {

    private final ScholardexPublicationFactRepository repository;
    private final ScholardexSourceLinkService sourceLinkService;

    public ScholardexPublicationWriter(
            ScholardexPublicationFactRepository repository,
            ScholardexSourceLinkService sourceLinkService) {
        this.repository = repository;
        this.sourceLinkService = sourceLinkService;
    }

    /**
     * Stamp uniform provenance + {@code updatedAt}, persist the fact, and upsert the PUBLICATION
     * source link (skipped when source/sourceRecordId are blank, preserving prior linker behavior).
     *
     * @return the persisted fact
     */
    public ScholardexPublicationFact upsertAndLinkSource(
            ScholardexPublicationFact fact, CanonicalWriteProvenance provenance, String sourceLinkReason) {
        fact.setSource(provenance.source());
        fact.setSourceRecordId(provenance.sourceRecordId());
        fact.setSourceBatchId(provenance.sourceBatchId());
        fact.setSourceCorrelationId(provenance.sourceCorrelationId());
        fact.setUpdatedAt(Instant.now());

        ScholardexPublicationFact saved = repository.save(fact);

        // The canonical id is assigned by the caller before persisting; link by the fact's own id
        // (as the prior inline write paths did) rather than the save return value.
        if (isNotBlank(provenance.source()) && isNotBlank(provenance.sourceRecordId())) {
            sourceLinkService.link(
                    ScholardexEntityType.PUBLICATION,
                    provenance.source(),
                    provenance.sourceRecordId(),
                    fact.getId(),
                    sourceLinkReason,
                    provenance.sourceEventId(),
                    provenance.sourceBatchId(),
                    provenance.sourceCorrelationId(),
                    false
            );
        }
        return saved;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}

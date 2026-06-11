package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.time.Instant;

import ro.uvt.pokedex.core.service.importing.BuilderVersion;

/**
 * Sanctioned write surface for secondary {@code scholardex.forum_facts} mutators (H54.5b).
 *
 * <p>Used by the single-record forum mutators that stamp fact provenance — the user-defined
 * canonicalization forum path now, and the manual forum-edit path in 5c. The WoS forum onboarding
 * builder is intentionally NOT routed here: it does not stamp the fact's `source`/`sourceRecordId`/
 * `sourceBatchId`/`sourceCorrelationId` (it tracks provenance via the source link only), so forcing
 * it through this writer would silently add fields it never wrote. Mirrors
 * {@link ScholardexPublicationWriter}; future chokepoint for dirty-markers + {@code builderVersion}.
 */
@Service
public class ScholardexForumWriter {

    private final ScholardexForumFactRepository repository;
    private final ScholardexSourceLinkService sourceLinkService;

    public ScholardexForumWriter(
            ScholardexForumFactRepository repository,
            ScholardexSourceLinkService sourceLinkService) {
        this.repository = repository;
        this.sourceLinkService = sourceLinkService;
    }

    /**
     * Stamp uniform provenance + {@code updatedAt}, persist the fact, and upsert the FORUM source
     * link (skipped when source/sourceRecordId are blank). The fact's content (including its own
     * {@code sourceEventId} where the caller manages it) must be set before calling. Links by the
     * fact's own id, as the prior inline paths did. Returns the persisted fact.
     */
    public ScholardexForumFact upsertAndLinkSource(
            ScholardexForumFact fact, CanonicalWriteProvenance provenance, String sourceLinkReason) {
        fact.setSource(provenance.source());
        fact.setSourceRecordId(provenance.sourceRecordId());
        fact.setSourceBatchId(provenance.sourceBatchId());
        fact.setSourceCorrelationId(provenance.sourceCorrelationId());
        fact.setUpdatedAt(Instant.now());
        fact.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);

        ScholardexForumFact saved = repository.save(fact);

        if (isNotBlank(provenance.source()) && isNotBlank(provenance.sourceRecordId())) {
            sourceLinkService.link(
                    ScholardexEntityType.FORUM,
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

    /**
     * Apply a user manual edit (H54.5c): stamp {@code source}/{@code sourceRecordId}/{@code updatedAt}
     * only — deliberately NOT {@code sourceBatchId}/{@code sourceCorrelationId}, which are preserved
     * on the edited fact — persist, and upsert the FORUM source link with null batch/correlation/event
     * (matching the prior manual-edit path that lived in {@code ScholardexProjectionReadService}).
     */
    public ScholardexForumFact applyManualEdit(
            ScholardexForumFact fact, String source, String sourceRecordId, String sourceLinkReason) {
        fact.setSource(source);
        fact.setSourceRecordId(sourceRecordId);
        fact.setUpdatedAt(Instant.now());
        fact.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
        ScholardexForumFact saved = repository.save(fact);
        if (sourceRecordId != null) {
            sourceLinkService.link(
                    ScholardexEntityType.FORUM, source, sourceRecordId, fact.getId(),
                    sourceLinkReason, null, null, null, false);
        }
        return saved;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}

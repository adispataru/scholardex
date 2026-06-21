package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.service.importing.BuilderVersion;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.HasEdgeLineageFields;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ScholardexEdgeWriterService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexEdgeWriterService.class);

    private static final String STATUS_OPEN = "OPEN";
    public static final String REASON_EDGE_CANONICAL_ID_MISMATCH = "EDGE_CANONICAL_ID_MISMATCH";

    private final ScholardexAuthorshipFactRepository authorshipFactRepository;
    private final ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    private final ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final MongoTemplate mongoTemplate;

    public EdgeWriteResult upsertAuthorshipEdge(EdgeWriteCommand command) {
        return upsertAuthorshipEdge(command, null);
    }

    /**
     * As {@link #upsertAuthorshipEdge(EdgeWriteCommand)}, but stamps the corresponding-author flag when
     * {@code corresponding} is non-null (H66B Phase 4a). Passing {@code null} preserves the existing flag value,
     * so callers that don't know corresponding-status leave it untouched.
     */
    public EdgeWriteResult upsertAuthorshipEdge(EdgeWriteCommand command, Boolean corresponding) {
        if (isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
            return EdgeWriteResult.invalid("missing-authorship-key");
        }
        ScholardexAuthorshipFact edge = authorshipFactRepository
                .findByPublicationIdAndAuthorIdAndSource(command.leftId(), command.rightId(), command.source())
                .orElseGet(ScholardexAuthorshipFact::new);
        if (corresponding != null) {
            edge.setCorresponding(corresponding);
        }
        boolean created = edge.getId() == null;
        String deterministicId = buildAuthorshipId(command.leftId(), command.rightId(), command.source());
        if (created) {
            edge.setId(deterministicId);
        } else if (!isBlank(edge.getId()) && !edge.getId().equals(deterministicId)) {
            openEdgeConflict(
                    ScholardexEntityType.AUTHORSHIP,
                    command.source(),
                    command.sourceRecordId(),
                    REASON_EDGE_CANONICAL_ID_MISMATCH,
                    List.of(edge.getId(), deterministicId),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId()
            );
        }

        Instant now = Instant.now();
        if (edge.getCreatedAt() == null) {
            edge.setCreatedAt(now);
        }
        edge.setPublicationId(command.leftId());
        edge.setAuthorId(command.rightId());
        applyLineage(edge, command, now);
        edge.setBuilderVersion(BuilderVersion.SCHOLARDEX_EDGE);
        authorshipFactRepository.save(edge);
        // H58: no separate edge source link — the edge fact above is the single source of truth.
        return EdgeWriteResult.accepted(edge.getId(), created);
    }

    /**
     * Remove a stale authorship edge. The sanctioned single surface for authorship-edge deletion
     * (H54.5b): edge reconciliation routes its deletes here instead of the repository directly, so
     * all authorship-edge mutations flow through this writer.
     */
    public void removeAuthorshipEdge(ScholardexAuthorshipFact edge) {
        if (edge != null) {
            authorshipFactRepository.delete(edge);
        }
    }

    /** Remove a stale author-affiliation edge (sanctioned single surface for deletion, H54.5b). */
    public void removeAuthorAffiliationEdge(ScholardexAuthorAffiliationFact edge) {
        if (edge != null) {
            authorAffiliationFactRepository.delete(edge);
        }
    }

    public BatchEdgeWriteResult batchUpsertAuthorshipEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexAuthorshipFact> preloadedByNaturalKey,
            boolean allowFallbackLookup
    ) {
        return batchUpsertAuthorshipEdges(commands, null, preloadedByNaturalKey, allowFallbackLookup);
    }

    /**
     * H73 slice 3 (S3.5): batch authorship-edge upsert that also stamps the corresponding-author flag — an edge is
     * marked {@code corresponding=true} when its {@code publicationId|authorId} is in {@code correspondingKeys}
     * (null = leave the flag untouched, as the no-flag overload does). Lets the bulk OpenAlex canon batch authorship
     * edges instead of one find+save per edge while preserving the H66B corresponding-author signal.
     */
    public BatchEdgeWriteResult batchUpsertAuthorshipEdges(
            List<EdgeWriteCommand> commands,
            java.util.Set<String> correspondingKeys,
            java.util.Map<String, ScholardexAuthorshipFact> preloadedByNaturalKey,
            boolean allowFallbackLookup
    ) {
        // H58: edges no longer carry a separate source link. The edge fact itself holds the lineage +
        // linkState (HasEdgeLineageFields) and drives the no-op skip; the edge id is deterministic, so a
        // relink to a different canonical id is impossible (the EDGE_CANONICAL_ID_MISMATCH guard remains).
        if (commands == null || commands.isEmpty()) {
            return new BatchEdgeWriteResult(0, 0, 0, 0, 0);
        }
        java.util.Map<String, ScholardexAuthorshipFact> working = new java.util.LinkedHashMap<>();
        if (preloadedByNaturalKey != null) {
            working.putAll(preloadedByNaturalKey);
        }
        java.util.List<ScholardexAuthorshipFact> pendingInserts = new java.util.ArrayList<>();
        java.util.Map<String, EdgeWriteCommand> pendingUpdateCommandsByEdgeId = new java.util.LinkedHashMap<>();

        int accepted = 0;
        int rejected = 0;
        int createdCount = 0;
        int updatedCount = 0;
        int conflicts = 0;
        int edgeFallbackLookups = 0;
        long edgeFallbackNanos = 0L;

        for (EdgeWriteCommand command : commands) {
            if (isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
                rejected++;
                continue;
            }
            String key = edgeNaturalKey(command.leftId(), command.rightId(), command.source());
            ScholardexAuthorshipFact edge = working.get(key);
            if (edge == null && allowFallbackLookup) {
                edgeFallbackLookups++;
                long lookupStartedAt = System.nanoTime();
                edge = authorshipFactRepository
                        .findByPublicationIdAndAuthorIdAndSource(command.leftId(), command.rightId(), command.source())
                        .orElse(null);
                edgeFallbackNanos += System.nanoTime() - lookupStartedAt;
                if (edge != null) {
                    working.put(key, edge);
                }
            }
            boolean created = edge == null || edge.getId() == null;
            String deterministicId = buildAuthorshipId(command.leftId(), command.rightId(), command.source());
            if (edge == null) {
                edge = new ScholardexAuthorshipFact();
            }
            if (created) {
                edge.setId(deterministicId);
            } else if (!isBlank(edge.getId()) && !edge.getId().equals(deterministicId)) {
                openEdgeConflict(
                        ScholardexEntityType.AUTHORSHIP,
                        command.source(),
                        command.sourceRecordId(),
                        REASON_EDGE_CANONICAL_ID_MISMATCH,
                        List.of(edge.getId(), deterministicId),
                        command.sourceEventId(),
                        command.sourceBatchId(),
                        command.sourceCorrelationId()
                );
                conflicts++;
            }

            Instant now = Instant.now();
            if (edge.getCreatedAt() == null) {
                edge.setCreatedAt(now);
            }
            edge.setPublicationId(command.leftId());
            edge.setAuthorId(command.rightId());
            if (correspondingKeys != null && correspondingKeys.contains(command.leftId() + "|" + command.rightId())) {
                edge.setCorresponding(true);
            }
            boolean lineageChanged = created || isLineageChanged(edge, command);
            if (lineageChanged) {
                applyLineage(edge, command, now);
            }

            working.put(key, edge);
            if (created) {
                pendingInserts.add(edge);
            } else if (lineageChanged) {
                pendingUpdateCommandsByEdgeId.put(edge.getId(), command);
            }

            accepted++;
            if (created) {
                createdCount++;
            } else if (lineageChanged) {
                updatedCount++;
            }
        }
        if (!pendingInserts.isEmpty()) {
            pendingInserts.forEach(e -> e.setBuilderVersion(BuilderVersion.SCHOLARDEX_EDGE));
            authorshipFactRepository.insert(pendingInserts);
        }
        if (!pendingUpdateCommandsByEdgeId.isEmpty()) {
            BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, ScholardexAuthorshipFact.class);
            for (java.util.Map.Entry<String, EdgeWriteCommand> entry : pendingUpdateCommandsByEdgeId.entrySet()) {
                String edgeId = entry.getKey();
                EdgeWriteCommand command = entry.getValue();
                Query query = Query.query(Criteria.where("_id").is(edgeId));
                Update update = new Update()
                        .set("sourceRecordId", command.sourceRecordId())
                        .set("sourceEventId", command.sourceEventId())
                        .set("sourceBatchId", command.sourceBatchId())
                        .set("sourceCorrelationId", command.sourceCorrelationId())
                        .set("linkState", command.linkState())
                        .set("linkReason", command.linkReason())
                        .set("updatedAt", Instant.now())
                        .set("builderVersion", BuilderVersion.SCHOLARDEX_EDGE);
                bulkOps.updateOne(query, update);
            }
            bulkOps.execute();
        }

        if (edgeFallbackLookups > 0) {
            log.info("Authorship edge batch cache efficiency: commands={} edgeFallbackLookups={} edgeFallbackMs={}",
                    commands.size(), edgeFallbackLookups, edgeFallbackNanos / 1_000_000L);
        }
        return new BatchEdgeWriteResult(
                accepted,
                rejected,
                createdCount,
                updatedCount,
                conflicts
        );
    }

    public EdgeWriteResult upsertAuthorAffiliationEdge(EdgeWriteCommand command) {
        if (isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
            return EdgeWriteResult.invalid("missing-author-affiliation-key");
        }
        ScholardexAuthorAffiliationFact edge = authorAffiliationFactRepository
                .findByAuthorIdAndAffiliationIdAndSource(command.leftId(), command.rightId(), command.source())
                .orElseGet(ScholardexAuthorAffiliationFact::new);
        boolean created = edge.getId() == null;
        String deterministicId = buildAuthorAffiliationId(command.leftId(), command.rightId(), command.source());
        if (created) {
            edge.setId(deterministicId);
        } else if (!isBlank(edge.getId()) && !edge.getId().equals(deterministicId)) {
            openEdgeConflict(
                    ScholardexEntityType.AUTHOR_AFFILIATION,
                    command.source(),
                    command.sourceRecordId(),
                    REASON_EDGE_CANONICAL_ID_MISMATCH,
                    List.of(edge.getId(), deterministicId),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId()
            );
        }

        Instant now = Instant.now();
        if (edge.getCreatedAt() == null) {
            edge.setCreatedAt(now);
        }
        edge.setAuthorId(command.leftId());
        edge.setAffiliationId(command.rightId());
        applyLineage(edge, command, now);
        edge.setBuilderVersion(BuilderVersion.SCHOLARDEX_EDGE);
        authorAffiliationFactRepository.save(edge);
        // H58: no separate edge source link — the edge fact above is the single source of truth.
        return EdgeWriteResult.accepted(edge.getId(), created);
    }

    public EdgeWriteResult upsertPublicationAuthorAffiliationEdge(EdgeWriteCommand command) {
        if (isBlank(command.publicationId()) || isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
            return EdgeWriteResult.invalid("missing-publication-author-affiliation-key");
        }
        ScholardexPublicationAuthorAffiliationFact edge = publicationAuthorAffiliationFactRepository
                .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource(
                        command.publicationId(),
                        command.leftId(),
                        command.rightId(),
                        command.source()
                )
                .orElseGet(ScholardexPublicationAuthorAffiliationFact::new);
        boolean created = edge.getId() == null;
        String deterministicId = buildPublicationAuthorAffiliationId(
                command.publicationId(),
                command.leftId(),
                command.rightId(),
                command.source()
        );
        if (created) {
            edge.setId(deterministicId);
        } else if (!isBlank(edge.getId()) && !edge.getId().equals(deterministicId)) {
            openEdgeConflict(
                    ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION,
                    command.source(),
                    command.sourceRecordId(),
                    REASON_EDGE_CANONICAL_ID_MISMATCH,
                    List.of(edge.getId(), deterministicId),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId()
            );
        }

        Instant now = Instant.now();
        if (edge.getCreatedAt() == null) {
            edge.setCreatedAt(now);
        }
        edge.setPublicationId(command.publicationId());
        edge.setAuthorId(command.leftId());
        edge.setAffiliationId(command.rightId());
        applyLineage(edge, command, now);
        edge.setBuilderVersion(BuilderVersion.SCHOLARDEX_EDGE);
        publicationAuthorAffiliationFactRepository.save(edge);
        // H58: no separate edge source link — the edge fact above is the single source of truth.
        return EdgeWriteResult.accepted(edge.getId(), created);
    }

    public BatchEdgeWriteResult batchUpsertAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexAuthorAffiliationFact> preloadedByNaturalKey
    ) {
        return batchUpsertAuthorAffiliationEdges(commands, preloadedByNaturalKey, true);
    }

    public BatchEdgeWriteResult batchUpsertAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexAuthorAffiliationFact> preloadedByNaturalKey,
            boolean allowFallbackLookup
    ) {
        // H58: edge fact is the single source of truth; no separate edge source link is written.
        if (commands == null || commands.isEmpty()) {
            return new BatchEdgeWriteResult(0, 0, 0, 0, 0);
        }
        java.util.Map<String, ScholardexAuthorAffiliationFact> working = new java.util.LinkedHashMap<>();
        if (preloadedByNaturalKey != null) {
            working.putAll(preloadedByNaturalKey);
        }
        java.util.Map<String, ScholardexAuthorAffiliationFact> pendingSaves = new java.util.LinkedHashMap<>();

        int accepted = 0;
        int rejected = 0;
        int createdCount = 0;
        int updatedCount = 0;
        int conflicts = 0;
        int edgeFallbackLookups = 0;
        long edgeFallbackNanos = 0L;

        for (EdgeWriteCommand command : commands) {
            if (isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
                rejected++;
                continue;
            }
            String key = edgeNaturalKey(command.leftId(), command.rightId(), command.source());
            ScholardexAuthorAffiliationFact edge = working.get(key);
            if (edge == null && allowFallbackLookup) {
                edgeFallbackLookups++;
                long lookupStartedAt = System.nanoTime();
                edge = authorAffiliationFactRepository
                        .findByAuthorIdAndAffiliationIdAndSource(command.leftId(), command.rightId(), command.source())
                        .orElse(null);
                edgeFallbackNanos += System.nanoTime() - lookupStartedAt;
                if (edge != null) {
                    working.put(key, edge);
                }
            }
            boolean created = edge == null || edge.getId() == null;
            String deterministicId = buildAuthorAffiliationId(command.leftId(), command.rightId(), command.source());
            if (edge == null) {
                edge = new ScholardexAuthorAffiliationFact();
            }
            if (created) {
                edge.setId(deterministicId);
            } else if (!isBlank(edge.getId()) && !edge.getId().equals(deterministicId)) {
                openEdgeConflict(
                        ScholardexEntityType.AUTHOR_AFFILIATION,
                        command.source(),
                        command.sourceRecordId(),
                        REASON_EDGE_CANONICAL_ID_MISMATCH,
                        List.of(edge.getId(), deterministicId),
                        command.sourceEventId(),
                        command.sourceBatchId(),
                        command.sourceCorrelationId()
                );
                conflicts++;
            }

            Instant now = Instant.now();
            if (edge.getCreatedAt() == null) {
                edge.setCreatedAt(now);
            }
            edge.setAuthorId(command.leftId());
            edge.setAffiliationId(command.rightId());
            // H56: mirror the authorship batch — only re-write an edge whose lineage actually changed.
            // Unconditional applyLineage+save re-wrote every author-affiliation edge (and bumped its
            // updatedAt) on each replay, ~271k per-doc writes per rebuild for nothing.
            boolean lineageChanged = created || isLineageChanged(edge, command);
            if (lineageChanged) {
                applyLineage(edge, command, now);
            }

            working.put(key, edge);
            if (lineageChanged) {
                pendingSaves.put(key, edge);
            }

            accepted++;
            if (created) {
                createdCount++;
            } else if (lineageChanged) {
                updatedCount++;
            }
        }

        if (!pendingSaves.isEmpty()) {
            pendingSaves.values().forEach(e -> e.setBuilderVersion(BuilderVersion.SCHOLARDEX_EDGE));
            authorAffiliationFactRepository.saveAll(pendingSaves.values());
        }

        if (edgeFallbackLookups > 0) {
            log.info("Author-affiliation edge batch cache efficiency: commands={} edgeFallbackLookups={} edgeFallbackMs={} pendingSaves={}",
                    commands.size(), edgeFallbackLookups, edgeFallbackNanos / 1_000_000L, pendingSaves.size());
        }
        return new BatchEdgeWriteResult(
                accepted,
                rejected,
                createdCount,
                updatedCount,
                conflicts
        );
    }

    public BatchEdgeWriteResult batchUpsertPublicationAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexPublicationAuthorAffiliationFact> preloadedByNaturalKey
    ) {
        return batchUpsertPublicationAuthorAffiliationEdges(commands, preloadedByNaturalKey, true);
    }

    public BatchEdgeWriteResult batchUpsertPublicationAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexPublicationAuthorAffiliationFact> preloadedByNaturalKey,
            boolean allowFallbackLookup
    ) {
        // H58: edge fact is the single source of truth; no separate edge source link is written.
        if (commands == null || commands.isEmpty()) {
            return new BatchEdgeWriteResult(0, 0, 0, 0, 0);
        }
        java.util.Map<String, ScholardexPublicationAuthorAffiliationFact> working = new java.util.LinkedHashMap<>();
        if (preloadedByNaturalKey != null) {
            working.putAll(preloadedByNaturalKey);
        }
        java.util.List<ScholardexPublicationAuthorAffiliationFact> pendingInserts = new java.util.ArrayList<>();
        java.util.Map<String, EdgeWriteCommand> pendingUpdateCommandsByEdgeId = new java.util.LinkedHashMap<>();

        int accepted = 0;
        int rejected = 0;
        int createdCount = 0;
        int updatedCount = 0;
        int conflicts = 0;

        for (EdgeWriteCommand command : commands) {
            if (isBlank(command.publicationId()) || isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
                rejected++;
                continue;
            }
            String key = publicationAuthorAffiliationNaturalKey(
                    command.publicationId(), command.leftId(), command.rightId(), command.source()
            );
            ScholardexPublicationAuthorAffiliationFact edge = working.get(key);
            if (edge == null && allowFallbackLookup) {
                edge = publicationAuthorAffiliationFactRepository
                        .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource(
                                command.publicationId(), command.leftId(), command.rightId(), command.source()
                        )
                        .orElse(null);
                if (edge != null) {
                    working.put(key, edge);
                }
            }
            boolean created = edge == null || edge.getId() == null;
            String deterministicId = buildPublicationAuthorAffiliationId(
                    command.publicationId(), command.leftId(), command.rightId(), command.source()
            );
            if (edge == null) {
                edge = new ScholardexPublicationAuthorAffiliationFact();
            }
            if (created) {
                edge.setId(deterministicId);
            } else if (!isBlank(edge.getId()) && !edge.getId().equals(deterministicId)) {
                openEdgeConflict(
                        ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION,
                        command.source(),
                        command.sourceRecordId(),
                        REASON_EDGE_CANONICAL_ID_MISMATCH,
                        List.of(edge.getId(), deterministicId),
                        command.sourceEventId(),
                        command.sourceBatchId(),
                        command.sourceCorrelationId()
                );
                conflicts++;
            }

            Instant now = Instant.now();
            if (edge.getCreatedAt() == null) {
                edge.setCreatedAt(now);
            }
            edge.setPublicationId(command.publicationId());
            edge.setAuthorId(command.leftId());
            edge.setAffiliationId(command.rightId());
            boolean lineageChanged = created || isLineageChanged(edge, command);
            if (lineageChanged) {
                applyLineage(edge, command, now);
            }
            working.put(key, edge);
            if (created) {
                pendingInserts.add(edge);
            } else if (lineageChanged) {
                pendingUpdateCommandsByEdgeId.put(edge.getId(), command);
            }

            accepted++;
            if (created) {
                createdCount++;
            } else if (lineageChanged) {
                updatedCount++;
            }
        }
        if (!pendingInserts.isEmpty()) {
            pendingInserts.forEach(e -> e.setBuilderVersion(BuilderVersion.SCHOLARDEX_EDGE));
            publicationAuthorAffiliationFactRepository.insert(pendingInserts);
        }
        if (!pendingUpdateCommandsByEdgeId.isEmpty()) {
            BulkOperations bulkOps = mongoTemplate.bulkOps(
                    BulkOperations.BulkMode.UNORDERED,
                    ScholardexPublicationAuthorAffiliationFact.class
            );
            for (java.util.Map.Entry<String, EdgeWriteCommand> entry : pendingUpdateCommandsByEdgeId.entrySet()) {
                String edgeId = entry.getKey();
                EdgeWriteCommand command = entry.getValue();
                Query query = Query.query(Criteria.where("_id").is(edgeId));
                Update update = new Update()
                        .set("sourceRecordId", command.sourceRecordId())
                        .set("sourceEventId", command.sourceEventId())
                        .set("sourceBatchId", command.sourceBatchId())
                        .set("sourceCorrelationId", command.sourceCorrelationId())
                        .set("linkState", command.linkState())
                        .set("linkReason", command.linkReason())
                        .set("updatedAt", Instant.now())
                        .set("builderVersion", BuilderVersion.SCHOLARDEX_EDGE);
                bulkOps.updateOne(query, update);
            }
            bulkOps.execute();
        }
        return new BatchEdgeWriteResult(
                accepted,
                rejected,
                createdCount,
                updatedCount,
                conflicts
        );
    }

    public String buildAuthorshipId(String publicationId, String authorId, String source) {
        return "sae_" + shortHash(publicationId + "|" + authorId + "|" + source);
    }

    public String buildAuthorAffiliationId(String authorId, String affiliationId, String source) {
        return "saae_" + shortHash(authorId + "|" + affiliationId + "|" + source);
    }

    public String buildPublicationAuthorAffiliationId(String publicationId, String authorId, String affiliationId, String source) {
        return "spaaf_" + shortHash(publicationId + "|" + authorId + "|" + affiliationId + "|" + source);
    }

    private void applyLineage(HasEdgeLineageFields edge, EdgeWriteCommand command, Instant now) {
        edge.setSource(command.source());
        edge.setSourceRecordId(command.sourceRecordId());
        edge.setSourceEventId(command.sourceEventId());
        edge.setSourceBatchId(command.sourceBatchId());
        edge.setSourceCorrelationId(command.sourceCorrelationId());
        edge.setLinkState(command.linkState());
        edge.setLinkReason(command.linkReason());
        edge.setUpdatedAt(now);
    }

    private boolean isLineageChanged(HasEdgeLineageFields edge, EdgeWriteCommand command) {
        return !Objects.equals(edge.getSourceRecordId(), command.sourceRecordId())
                || !Objects.equals(edge.getSourceEventId(), command.sourceEventId())
                || !Objects.equals(edge.getSourceBatchId(), command.sourceBatchId())
                || !Objects.equals(edge.getSourceCorrelationId(), command.sourceCorrelationId())
                || !Objects.equals(edge.getLinkState(), command.linkState())
                || !Objects.equals(edge.getLinkReason(), command.linkReason());
    }

    private void openEdgeConflict(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reasonCode,
            List<String> candidates,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId
    ) {
        String normalizedSource = normalize(source);
        String normalizedRecordId = normalize(sourceRecordId);
        if (normalizedSource == null || normalizedRecordId == null) {
            return;
        }
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        entityType, normalizedSource, normalizedRecordId, reasonCode, STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(entityType);
        conflict.setIncomingSource(normalizedSource);
        conflict.setIncomingSourceRecordId(normalizedRecordId);
        conflict.setReasonCode(reasonCode);
        conflict.setStatus(STATUS_OPEN);
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        if (candidates != null) {
            deduped.addAll(candidates);
        }
        conflict.setCandidateCanonicalIds(new ArrayList<>(deduped));
        conflict.setSourceEventId(normalize(sourceEventId));
        conflict.setSourceBatchId(normalize(sourceBatchId));
        conflict.setSourceCorrelationId(normalize(sourceCorrelationId));
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        identityConflictRepository.save(conflict);
        CanonicalObservabilityMetrics.recordConflictCreated(entityType.name(), normalizedSource, reasonCode);
    }

    private String shortHash(String value) {
        // H56: byte-identical, ~10x faster shared implementation (per-edge deterministic ids run
        // ~1.2M times per rebuild).
        return ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash(value);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * H56: the natural key this writer's batch methods use for their working/preload maps. Preloaders
     * MUST seed their maps with exactly this key — a different normalization (e.g. lowercasing) makes
     * every lookup miss and silently degrades the batch into per-command repository reads.
     */
    public String authorAffiliationEdgeNaturalKey(String authorId, String affiliationId, String source) {
        return edgeNaturalKey(authorId, affiliationId, source);
    }

    private String edgeNaturalKey(String authorId, String affiliationId, String source) {
        return normalize(authorId) + "|" + normalize(affiliationId) + "|" + normalize(source);
    }

    private String publicationAuthorAffiliationNaturalKey(String publicationId, String authorId, String affiliationId, String source) {
        return normalize(publicationId) + "|" + normalize(authorId) + "|" + normalize(affiliationId) + "|" + normalize(source);
    }

    public record EdgeWriteCommand(
            String publicationId,
            String leftId,
            String rightId,
            String source,
            String sourceRecordId,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            String linkState,
            String linkReason,
            boolean explicitReplayAttempt
    ) {
        public EdgeWriteCommand(
                String leftId,
                String rightId,
                String source,
                String sourceRecordId,
                String sourceEventId,
                String sourceBatchId,
                String sourceCorrelationId,
                String linkState,
                String linkReason,
                boolean explicitReplayAttempt
        ) {
            this(
                    null,
                    leftId,
                    rightId,
                    source,
                    sourceRecordId,
                    sourceEventId,
                    sourceBatchId,
                    sourceCorrelationId,
                    linkState,
                    linkReason,
                    explicitReplayAttempt
            );
        }
    }

    public record EdgeWriteResult(boolean accepted, String canonicalEdgeId, boolean created, String reason) {
        static EdgeWriteResult accepted(String canonicalEdgeId, boolean created) {
            return new EdgeWriteResult(true, canonicalEdgeId, created, null);
        }

        static EdgeWriteResult invalid(String reason) {
            return new EdgeWriteResult(false, null, false, reason);
        }
    }

    public record BatchEdgeWriteResult(
            int accepted,
            int rejected,
            int created,
            int updated,
            int conflicts
    ) {
    }
}

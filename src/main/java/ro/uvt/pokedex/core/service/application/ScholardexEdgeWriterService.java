package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ScholardexEdgeWriterService {

    private static final String STATUS_OPEN = "OPEN";
    public static final String REASON_EDGE_RELINK_REJECTED = "EDGE_RELINK_REJECTED";
    public static final String REASON_EDGE_CANONICAL_ID_MISMATCH = "EDGE_CANONICAL_ID_MISMATCH";

    private final ScholardexAuthorshipFactRepository authorshipFactRepository;
    private final ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    private final ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final MongoTemplate mongoTemplate;

    public EdgeWriteResult upsertAuthorshipEdge(EdgeWriteCommand command) {
        if (isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
            return EdgeWriteResult.invalid("missing-authorship-key");
        }
        ScholardexAuthorshipFact edge = authorshipFactRepository
                .findByPublicationIdAndAuthorIdAndSource(command.leftId(), command.rightId(), command.source())
                .orElseGet(ScholardexAuthorshipFact::new);
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
        authorshipFactRepository.save(edge);

        ScholardexSourceLinkService.SourceLinkWriteResult sourceLinkResult = writeSourceLink(
                ScholardexEntityType.AUTHORSHIP,
                command,
                edge.getId()
        );
        if (!sourceLinkResult.accepted()) {
            openEdgeConflict(
                    ScholardexEntityType.AUTHORSHIP,
                    command.source(),
                    command.sourceRecordId(),
                    REASON_EDGE_RELINK_REJECTED,
                    List.of(edge.getId()),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId()
            );
        }
        return EdgeWriteResult.accepted(edge.getId(), created);
    }

    public BatchEdgeWriteResult batchUpsertAuthorshipEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexAuthorshipFact> preloadedByNaturalKey,
            java.util.Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks,
            boolean allowFallbackLookup
    ) {
        if (commands == null || commands.isEmpty()) {
            return new BatchEdgeWriteResult(0, 0, 0, 0, 0);
        }
        java.util.Map<String, ScholardexAuthorshipFact> working = new java.util.LinkedHashMap<>();
        if (preloadedByNaturalKey != null) {
            working.putAll(preloadedByNaturalKey);
        }
        java.util.List<ScholardexAuthorshipFact> pendingInserts = new java.util.ArrayList<>();
        java.util.Map<String, EdgeWriteCommand> pendingUpdateCommandsByEdgeId = new java.util.LinkedHashMap<>();
        java.util.List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = new java.util.ArrayList<>();

        int accepted = 0;
        int rejected = 0;
        int createdCount = 0;
        int updatedCount = 0;
        int conflicts = 0;

        for (EdgeWriteCommand command : commands) {
            if (isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
                rejected++;
                continue;
            }
            String key = edgeNaturalKey(command.leftId(), command.rightId(), command.source());
            ScholardexAuthorshipFact edge = working.get(key);
            if (edge == null && allowFallbackLookup) {
                edge = authorshipFactRepository
                        .findByPublicationIdAndAuthorIdAndSource(command.leftId(), command.rightId(), command.source())
                        .orElse(null);
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
            boolean lineageChanged = created || isAuthorshipLineageChanged(edge, command);
            if (lineageChanged) {
                applyLineage(edge, command, now);
            }

            working.put(key, edge);
            if (created) {
                pendingInserts.add(edge);
            } else if (lineageChanged) {
                pendingUpdateCommandsByEdgeId.put(edge.getId(), command);
            }
            linkCommands.add(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                    ScholardexEntityType.AUTHORSHIP,
                    command.source(),
                    command.sourceRecordId(),
                    edge.getId(),
                    command.linkState(),
                    command.linkReason(),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId(),
                    command.explicitReplayAttempt()
            ));

            accepted++;
            if (created) {
                createdCount++;
            } else if (lineageChanged) {
                updatedCount++;
            }
        }
        if (!pendingInserts.isEmpty()) {
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
                        .set("updatedAt", Instant.now());
                bulkOps.updateOne(query, update);
            }
            bulkOps.execute();
        }

        ScholardexSourceLinkService.BatchWriteResult sourceLinkResults =
                sourceLinkService.batchUpsertWithState(linkCommands, preloadedSourceLinks, false);
        if (sourceLinkResults.rejectedCount() > 0) {
            for (ScholardexSourceLinkService.SourceLinkBatchItemResult item : sourceLinkResults.results()) {
                if (item.accepted()) {
                    continue;
                }
                openEdgeConflict(
                        ScholardexEntityType.AUTHORSHIP,
                        item.command().source(),
                        item.command().sourceRecordId(),
                        REASON_EDGE_RELINK_REJECTED,
                        item.command().canonicalEntityId() == null ? List.of() : List.of(item.command().canonicalEntityId()),
                        item.command().sourceEventId(),
                        item.command().sourceBatchId(),
                        item.command().sourceCorrelationId()
                );
                conflicts++;
            }
        }

        return new BatchEdgeWriteResult(
                accepted,
                rejected + (int) sourceLinkResults.rejectedCount(),
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
        edge.setSource(command.source());
        edge.setSourceRecordId(command.sourceRecordId());
        edge.setSourceEventId(command.sourceEventId());
        edge.setSourceBatchId(command.sourceBatchId());
        edge.setSourceCorrelationId(command.sourceCorrelationId());
        edge.setLinkState(command.linkState());
        edge.setLinkReason(command.linkReason());
        edge.setUpdatedAt(now);
        authorAffiliationFactRepository.save(edge);

        ScholardexSourceLinkService.SourceLinkWriteResult sourceLinkResult = writeSourceLink(
                ScholardexEntityType.AUTHOR_AFFILIATION,
                command,
                edge.getId()
        );
        if (!sourceLinkResult.accepted()) {
            openEdgeConflict(
                    ScholardexEntityType.AUTHOR_AFFILIATION,
                    command.source(),
                    command.sourceRecordId(),
                    REASON_EDGE_RELINK_REJECTED,
                    List.of(edge.getId()),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId()
            );
        }
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
        edge.setSource(command.source());
        edge.setSourceRecordId(command.sourceRecordId());
        edge.setSourceEventId(command.sourceEventId());
        edge.setSourceBatchId(command.sourceBatchId());
        edge.setSourceCorrelationId(command.sourceCorrelationId());
        edge.setLinkState(command.linkState());
        edge.setLinkReason(command.linkReason());
        edge.setUpdatedAt(now);
        publicationAuthorAffiliationFactRepository.save(edge);

        ScholardexSourceLinkService.SourceLinkWriteResult sourceLinkResult = writeSourceLink(
                ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION,
                command,
                edge.getId()
        );
        if (!sourceLinkResult.accepted()) {
            openEdgeConflict(
                    ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION,
                    command.source(),
                    command.sourceRecordId(),
                    REASON_EDGE_RELINK_REJECTED,
                    List.of(edge.getId()),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId()
            );
        }
        return EdgeWriteResult.accepted(edge.getId(), created);
    }

    public BatchEdgeWriteResult batchUpsertAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexAuthorAffiliationFact> preloadedByNaturalKey,
            java.util.Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks
    ) {
        return batchUpsertAuthorAffiliationEdges(commands, preloadedByNaturalKey, preloadedSourceLinks, true);
    }

    public BatchEdgeWriteResult batchUpsertAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexAuthorAffiliationFact> preloadedByNaturalKey,
            java.util.Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks,
            boolean allowFallbackLookup
    ) {
        if (commands == null || commands.isEmpty()) {
            return new BatchEdgeWriteResult(0, 0, 0, 0, 0);
        }
        java.util.Map<String, ScholardexAuthorAffiliationFact> working = new java.util.LinkedHashMap<>();
        if (preloadedByNaturalKey != null) {
            working.putAll(preloadedByNaturalKey);
        }
        java.util.Map<String, ScholardexAuthorAffiliationFact> pendingSaves = new java.util.LinkedHashMap<>();
        java.util.List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = new java.util.ArrayList<>();

        int accepted = 0;
        int rejected = 0;
        int createdCount = 0;
        int updatedCount = 0;
        int conflicts = 0;

        for (EdgeWriteCommand command : commands) {
            if (isBlank(command.leftId()) || isBlank(command.rightId()) || isBlank(command.source())) {
                rejected++;
                continue;
            }
            String key = edgeNaturalKey(command.leftId(), command.rightId(), command.source());
            ScholardexAuthorAffiliationFact edge = working.get(key);
            if (edge == null && allowFallbackLookup) {
                edge = authorAffiliationFactRepository
                        .findByAuthorIdAndAffiliationIdAndSource(command.leftId(), command.rightId(), command.source())
                        .orElse(null);
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
            edge.setSource(command.source());
            edge.setSourceRecordId(command.sourceRecordId());
            edge.setSourceEventId(command.sourceEventId());
            edge.setSourceBatchId(command.sourceBatchId());
            edge.setSourceCorrelationId(command.sourceCorrelationId());
            edge.setLinkState(command.linkState());
            edge.setLinkReason(command.linkReason());
            edge.setUpdatedAt(now);

            working.put(key, edge);
            pendingSaves.put(key, edge);
            linkCommands.add(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                    ScholardexEntityType.AUTHOR_AFFILIATION,
                    command.source(),
                    command.sourceRecordId(),
                    edge.getId(),
                    command.linkState(),
                    command.linkReason(),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId(),
                    command.explicitReplayAttempt()
            ));

            accepted++;
            if (created) {
                createdCount++;
            } else {
                updatedCount++;
            }
        }

        if (!pendingSaves.isEmpty()) {
            authorAffiliationFactRepository.saveAll(pendingSaves.values());
        }

        ScholardexSourceLinkService.BatchWriteResult sourceLinkResults =
                sourceLinkService.batchUpsertWithState(linkCommands, preloadedSourceLinks, false);
        if (sourceLinkResults.rejectedCount() > 0) {
            for (ScholardexSourceLinkService.SourceLinkBatchItemResult item : sourceLinkResults.results()) {
                if (item.accepted()) {
                    continue;
                }
                openEdgeConflict(
                        ScholardexEntityType.AUTHOR_AFFILIATION,
                        item.command().source(),
                        item.command().sourceRecordId(),
                        REASON_EDGE_RELINK_REJECTED,
                        item.command().canonicalEntityId() == null ? List.of() : List.of(item.command().canonicalEntityId()),
                        item.command().sourceEventId(),
                        item.command().sourceBatchId(),
                        item.command().sourceCorrelationId()
                );
                conflicts++;
            }
        }

        return new BatchEdgeWriteResult(
                accepted,
                rejected + (int) sourceLinkResults.rejectedCount(),
                createdCount,
                updatedCount,
                conflicts
        );
    }

    public BatchEdgeWriteResult batchUpsertPublicationAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexPublicationAuthorAffiliationFact> preloadedByNaturalKey,
            java.util.Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks
    ) {
        return batchUpsertPublicationAuthorAffiliationEdges(commands, preloadedByNaturalKey, preloadedSourceLinks, true);
    }

    public BatchEdgeWriteResult batchUpsertPublicationAuthorAffiliationEdges(
            List<EdgeWriteCommand> commands,
            java.util.Map<String, ScholardexPublicationAuthorAffiliationFact> preloadedByNaturalKey,
            java.util.Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks,
            boolean allowFallbackLookup
    ) {
        if (commands == null || commands.isEmpty()) {
            return new BatchEdgeWriteResult(0, 0, 0, 0, 0);
        }
        java.util.Map<String, ScholardexPublicationAuthorAffiliationFact> working = new java.util.LinkedHashMap<>();
        if (preloadedByNaturalKey != null) {
            working.putAll(preloadedByNaturalKey);
        }
        java.util.List<ScholardexPublicationAuthorAffiliationFact> pendingInserts = new java.util.ArrayList<>();
        java.util.Map<String, EdgeWriteCommand> pendingUpdateCommandsByEdgeId = new java.util.LinkedHashMap<>();
        java.util.List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = new java.util.ArrayList<>();

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
            boolean lineageChanged = created || isPublicationAuthorAffiliationLineageChanged(edge, command);
            if (lineageChanged) {
                edge.setSource(command.source());
                edge.setSourceRecordId(command.sourceRecordId());
                edge.setSourceEventId(command.sourceEventId());
                edge.setSourceBatchId(command.sourceBatchId());
                edge.setSourceCorrelationId(command.sourceCorrelationId());
                edge.setLinkState(command.linkState());
                edge.setLinkReason(command.linkReason());
                edge.setUpdatedAt(now);
            }
            working.put(key, edge);
            if (created) {
                pendingInserts.add(edge);
            } else if (lineageChanged) {
                pendingUpdateCommandsByEdgeId.put(edge.getId(), command);
            }

            linkCommands.add(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                    ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION,
                    command.source(),
                    command.sourceRecordId(),
                    edge.getId(),
                    command.linkState(),
                    command.linkReason(),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId(),
                    command.explicitReplayAttempt()
            ));

            accepted++;
            if (created) {
                createdCount++;
            } else if (lineageChanged) {
                updatedCount++;
            }
        }
        if (!pendingInserts.isEmpty()) {
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
                        .set("updatedAt", Instant.now());
                bulkOps.updateOne(query, update);
            }
            bulkOps.execute();
        }
        ScholardexSourceLinkService.BatchWriteResult sourceLinkResults =
                sourceLinkService.batchUpsertWithState(linkCommands, preloadedSourceLinks, false);
        if (sourceLinkResults.rejectedCount() > 0) {
            for (ScholardexSourceLinkService.SourceLinkBatchItemResult item : sourceLinkResults.results()) {
                if (item.accepted()) {
                    continue;
                }
                openEdgeConflict(
                        ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION,
                        item.command().source(),
                        item.command().sourceRecordId(),
                        REASON_EDGE_RELINK_REJECTED,
                        item.command().canonicalEntityId() == null ? List.of() : List.of(item.command().canonicalEntityId()),
                        item.command().sourceEventId(),
                        item.command().sourceBatchId(),
                        item.command().sourceCorrelationId()
                );
                conflicts++;
            }
        }
        return new BatchEdgeWriteResult(
                accepted,
                rejected + (int) sourceLinkResults.rejectedCount(),
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

    private ScholardexSourceLinkService.SourceLinkWriteResult writeSourceLink(
            ScholardexEntityType entityType,
            EdgeWriteCommand command,
            String canonicalEdgeId
    ) {
        if (ScholardexSourceLinkService.STATE_LINKED.equals(command.linkState())) {
            return sourceLinkService.link(
                    entityType,
                    command.source(),
                    command.sourceRecordId(),
                    canonicalEdgeId,
                    command.linkReason(),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId(),
                    command.explicitReplayAttempt()
            );
        }
        if (ScholardexSourceLinkService.STATE_UNMATCHED.equals(command.linkState())) {
            return sourceLinkService.markUnmatched(
                    entityType,
                    command.source(),
                    command.sourceRecordId(),
                    canonicalEdgeId,
                    command.linkReason(),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId(),
                    command.explicitReplayAttempt()
            );
        }
        if (ScholardexSourceLinkService.STATE_CONFLICT.equals(command.linkState())) {
            return sourceLinkService.markConflict(
                    entityType,
                    command.source(),
                    command.sourceRecordId(),
                    command.linkReason(),
                    command.sourceEventId(),
                    command.sourceBatchId(),
                    command.sourceCorrelationId(),
                    command.explicitReplayAttempt()
            );
        }
        return sourceLinkService.markSkipped(
                entityType,
                command.source(),
                command.sourceRecordId(),
                command.linkReason(),
                command.sourceEventId(),
                command.sourceBatchId(),
                command.sourceCorrelationId(),
                command.explicitReplayAttempt()
        );
    }

    private void applyLineage(ScholardexAuthorshipFact edge, EdgeWriteCommand command, Instant now) {
        edge.setSource(command.source());
        edge.setSourceRecordId(command.sourceRecordId());
        edge.setSourceEventId(command.sourceEventId());
        edge.setSourceBatchId(command.sourceBatchId());
        edge.setSourceCorrelationId(command.sourceCorrelationId());
        edge.setLinkState(command.linkState());
        edge.setLinkReason(command.linkReason());
        edge.setUpdatedAt(now);
    }

    private boolean isAuthorshipLineageChanged(ScholardexAuthorshipFact edge, EdgeWriteCommand command) {
        return !Objects.equals(edge.getSourceRecordId(), command.sourceRecordId())
                || !Objects.equals(edge.getSourceEventId(), command.sourceEventId())
                || !Objects.equals(edge.getSourceBatchId(), command.sourceBatchId())
                || !Objects.equals(edge.getSourceCorrelationId(), command.sourceCorrelationId())
                || !Objects.equals(edge.getLinkState(), command.linkState())
                || !Objects.equals(edge.getLinkReason(), command.linkReason());
    }

    private boolean isPublicationAuthorAffiliationLineageChanged(
            ScholardexPublicationAuthorAffiliationFact edge,
            EdgeWriteCommand command
    ) {
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
        H19CanonicalMetrics.recordConflictCreated(entityType.name(), normalizedSource, reasonCode);
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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

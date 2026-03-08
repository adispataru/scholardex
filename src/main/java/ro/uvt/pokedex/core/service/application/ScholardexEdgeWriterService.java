package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScholardexEdgeWriterService {

    private static final String STATUS_OPEN = "OPEN";
    public static final String REASON_EDGE_RELINK_REJECTED = "EDGE_RELINK_REJECTED";
    public static final String REASON_EDGE_CANONICAL_ID_MISMATCH = "EDGE_CANONICAL_ID_MISMATCH";

    private final ScholardexAuthorshipFactRepository authorshipFactRepository;
    private final ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository identityConflictRepository;

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

    public String buildAuthorshipId(String publicationId, String authorId, String source) {
        return "sae_" + shortHash(publicationId + "|" + authorId + "|" + source);
    }

    public String buildAuthorAffiliationId(String authorId, String affiliationId, String source) {
        return "saae_" + shortHash(authorId + "|" + affiliationId + "|" + source);
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

    public record EdgeWriteCommand(
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
    }

    public record EdgeWriteResult(boolean accepted, String canonicalEdgeId, boolean created, String reason) {
        static EdgeWriteResult accepted(String canonicalEdgeId, boolean created) {
            return new EdgeWriteResult(true, canonicalEdgeId, created, null);
        }

        static EdgeWriteResult invalid(String reason) {
            return new EdgeWriteResult(false, null, false, reason);
        }
    }
}


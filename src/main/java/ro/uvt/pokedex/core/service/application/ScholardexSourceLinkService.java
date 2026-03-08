package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScholardexSourceLinkService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexSourceLinkService.class);

    public static final String STATE_LINKED = "LINKED";
    public static final String STATE_CONFLICT = "CONFLICT";
    public static final String STATE_UNMATCHED = "UNMATCHED";
    public static final String STATE_SKIPPED = "SKIPPED";
    private static final String STATUS_OPEN = "OPEN";
    private static final String REASON_RELINK_REJECTED = "SOURCE_LINK_RELINK_REJECTED";

    private static final Map<String, String> SOURCE_ALIASES = Map.ofEntries(
            Map.entry("WOSEXTRACTOR", "WOS"),
            Map.entry("OFFICIAL_WOS_EXTRACT", "WOS"),
            Map.entry("WOS_EXTRACTOR", "WOS"),
            Map.entry("GOOGLE_SCHOLAR", "GSCHOLAR"),
            Map.entry("GOOGLESCHOLAR", "GSCHOLAR"),
            Map.entry("SCHOLAR", "GSCHOLAR"),
            Map.entry("USER_PUBLICATION_WIZARD", "USER_DEFINED")
    );

    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;

    public Optional<ScholardexSourceLink> findByKey(ScholardexEntityType entityType, String source, String sourceRecordId) {
        String normalizedRecordId = normalize(sourceRecordId);
        if (entityType == null || normalizedRecordId == null) {
            return Optional.empty();
        }
        String normalizedSource = normalizeSource(source);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> normalized = sourceLinkRepository
                    .findByEntityTypeAndSourceAndSourceRecordId(entityType, normalizedSource, normalizedRecordId);
            if (normalized.isPresent()) {
                return normalized;
            }
        }
        String rawSource = normalize(source);
        if (rawSource != null && (normalizedSource == null || !normalizedSource.equals(rawSource))) {
            return sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(entityType, rawSource, normalizedRecordId);
        }
        return Optional.empty();
    }

    public List<ScholardexSourceLink> findByCanonical(ScholardexEntityType entityType, String canonicalEntityId) {
        if (entityType == null || normalize(canonicalEntityId) == null) {
            return List.of();
        }
        return sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(entityType, canonicalEntityId.trim());
    }

    public List<ScholardexSourceLink> findByEntityTypeAndSourceRecordId(ScholardexEntityType entityType, String sourceRecordId) {
        if (entityType == null || normalize(sourceRecordId) == null) {
            return List.of();
        }
        return sourceLinkRepository.findByEntityTypeAndSourceRecordId(entityType, sourceRecordId.trim());
    }

    public Page<ScholardexSourceLink> findPaged(
            Integer page,
            Integer size,
            String entityType,
            String source,
            String linkState,
            Instant from,
            Instant to
    ) {
        Pageable pageable = PageRequest.of(normalizePage(page), normalizeSize(size), Sort.by(Sort.Direction.DESC, "updatedAt"));
        ScholardexEntityType parsedEntityType = parseEntityType(entityType);
        String normalizedSource = normalizeFilter(source);
        String normalizedState = normalizeFilter(linkState);
        Instant detectedFrom = from == null ? Instant.EPOCH : from;
        Instant detectedTo = to == null ? Instant.parse("9999-12-31T23:59:59Z") : to;

        if (parsedEntityType == null) {
            return sourceLinkRepository
                    .findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                            normalizedSource, normalizedState, detectedFrom, detectedTo, pageable
                    );
        }
        return sourceLinkRepository
                .findAllByEntityTypeAndSourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                        parsedEntityType, normalizedSource, normalizedState, detectedFrom, detectedTo, pageable
                );
    }

    public SourceLinkWriteResult link(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalEntityId,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            boolean explicitReplayAttempt
    ) {
        return upsertWithState(
                entityType,
                source,
                sourceRecordId,
                canonicalEntityId,
                STATE_LINKED,
                reason,
                sourceEventId,
                sourceBatchId,
                sourceCorrelationId,
                explicitReplayAttempt
        );
    }

    public SourceLinkWriteResult markConflict(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            boolean explicitReplayAttempt
    ) {
        return upsertWithState(
                entityType, source, sourceRecordId, null, STATE_CONFLICT, reason,
                sourceEventId, sourceBatchId, sourceCorrelationId, explicitReplayAttempt
        );
    }

    public SourceLinkWriteResult markUnmatched(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String fallbackCanonicalEntityId,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            boolean explicitReplayAttempt
    ) {
        return upsertWithState(
                entityType, source, sourceRecordId, fallbackCanonicalEntityId, STATE_UNMATCHED, reason,
                sourceEventId, sourceBatchId, sourceCorrelationId, explicitReplayAttempt
        );
    }

    public SourceLinkWriteResult markSkipped(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            boolean explicitReplayAttempt
    ) {
        return upsertWithState(
                entityType, source, sourceRecordId, null, STATE_SKIPPED, reason,
                sourceEventId, sourceBatchId, sourceCorrelationId, explicitReplayAttempt
        );
    }

    public SourceLinkWriteResult upsertWithState(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalEntityId,
            String targetState,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            boolean explicitReplayAttempt
    ) {
        String normalizedState = normalizeState(targetState);
        String normalizedSource = normalizeSource(source);
        String normalizedRecordId = normalize(sourceRecordId);
        String normalizedCanonicalId = normalize(canonicalEntityId);
        if (entityType == null || normalizedState == null || normalizedSource == null || normalizedRecordId == null) {
            H19CanonicalMetrics.recordSourceLinkTransition(
                    entityType == null ? null : entityType.name(),
                    null,
                    normalizedState,
                    "rejected"
            );
            return SourceLinkWriteResult.rejected("invalid-source-link-key");
        }
        if (STATE_LINKED.equals(normalizedState) && normalizedCanonicalId == null) {
            H19CanonicalMetrics.recordSourceLinkTransition(entityType.name(), null, normalizedState, "rejected");
            return SourceLinkWriteResult.rejected("linked-requires-canonical-id");
        }

        ScholardexSourceLink existing = findByKey(entityType, normalizedSource, normalizedRecordId).orElse(null);
        String existingState = normalizeState(existing == null ? null : existing.getLinkState());
        String existingCanonicalId = normalize(existing == null ? null : existing.getCanonicalEntityId());

        if (!isTransitionAllowed(existingState, normalizedState, explicitReplayAttempt)) {
            H19CanonicalMetrics.recordSourceLinkTransition(entityType.name(), existingState, normalizedState, "rejected");
            return SourceLinkWriteResult.rejected("invalid-state-transition:" + existingState + "->" + normalizedState);
        }
        if (STATE_LINKED.equals(existingState) && STATE_LINKED.equals(normalizedState)
                && existingCanonicalId != null && normalizedCanonicalId != null
                && !existingCanonicalId.equals(normalizedCanonicalId)) {
            openRelinkConflict(entityType, normalizedSource, normalizedRecordId, sourceEventId, sourceBatchId, sourceCorrelationId, existingCanonicalId, normalizedCanonicalId);
            H19CanonicalMetrics.recordSourceLinkTransition(entityType.name(), existingState, normalizedState, "rejected");
            return SourceLinkWriteResult.rejected("linked-canonical-id-immutable");
        }

        Instant now = Instant.now();
        ScholardexSourceLink target = existing == null ? new ScholardexSourceLink() : existing;
        target.setEntityType(entityType);
        target.setSource(normalizedSource);
        target.setSourceRecordId(normalizedRecordId);
        target.setCanonicalEntityId(STATE_LINKED.equals(normalizedState) || STATE_UNMATCHED.equals(normalizedState)
                ? normalizedCanonicalId : null);
        target.setLinkState(normalizedState);
        target.setLinkReason(normalize(reason));
        target.setSourceEventId(normalize(sourceEventId));
        target.setSourceBatchId(normalize(sourceBatchId));
        target.setSourceCorrelationId(normalize(sourceCorrelationId));
        if (target.getLinkedAt() == null) {
            target.setLinkedAt(now);
        }
        target.setUpdatedAt(now);
        sourceLinkRepository.save(target);
        H19CanonicalMetrics.recordSourceLinkTransition(entityType.name(), existingState, normalizedState, "accepted");
        return SourceLinkWriteResult.accepted(target);
    }

    public ReplayEligibilitySummary replayEligibilitySummary() {
        long unmatched = sourceLinkRepository.countByLinkState(STATE_UNMATCHED);
        long conflict = sourceLinkRepository.countByLinkState(STATE_CONFLICT);
        long skipped = sourceLinkRepository.countByLinkState(STATE_SKIPPED);
        return new ReplayEligibilitySummary(unmatched, conflict, skipped);
    }

    public ImportRepairSummary reconcileLinks() {
        long startedAtNanos = System.nanoTime();
        List<ScholardexSourceLink> all = sourceLinkRepository.findAll();
        long updated = 0L;
        long skipped = 0L;
        long errors = 0L;
        for (ScholardexSourceLink link : all) {
            try {
                String desiredSource = normalizeSource(link.getSource());
                String desiredState = normalizeState(link.getLinkState());
                String desiredRecordId = normalize(link.getSourceRecordId());
                String desiredCanonical = normalize(link.getCanonicalEntityId());
                boolean changed = false;
                if (desiredSource != null && !desiredSource.equals(link.getSource())) {
                    link.setSource(desiredSource);
                    changed = true;
                }
                if (desiredState == null) {
                    desiredState = STATE_SKIPPED;
                    link.setLinkState(desiredState);
                    changed = true;
                }
                if (desiredRecordId != null && !desiredRecordId.equals(link.getSourceRecordId())) {
                    link.setSourceRecordId(desiredRecordId);
                    changed = true;
                }
                if (STATE_LINKED.equals(desiredState) && desiredCanonical == null) {
                    link.setLinkState(STATE_UNMATCHED);
                    link.setLinkReason("reconcile-missing-linked-canonical");
                    changed = true;
                }
                if (!STATE_LINKED.equals(desiredState) && !STATE_UNMATCHED.equals(desiredState) && desiredCanonical != null) {
                    link.setCanonicalEntityId(null);
                    changed = true;
                }
                if (link.getLinkedAt() == null) {
                    link.setLinkedAt(Instant.now());
                    changed = true;
                }
                if (changed) {
                    link.setUpdatedAt(Instant.now());
                    sourceLinkRepository.save(link);
                    updated++;
                } else {
                    skipped++;
                }
            } catch (Exception ex) {
                errors++;
            }
        }
        String outcome = errors > 0 ? "failure" : "success";
        H19CanonicalMetrics.recordReconcileRun("source-links", outcome, System.nanoTime() - startedAtNanos);
        log.info("H19_TRIAGE source_link_reconcile runId={} batchId={} correlationId={} entity=SOURCE_LINK source=ALL outcome={} updated={} skipped={} errors={}",
                java.util.UUID.randomUUID().toString(),
                "N/A",
                "N/A",
                outcome,
                updated,
                skipped,
                errors);
        return new ImportRepairSummary(updated, skipped, errors);
    }

    public String normalizeSource(String source) {
        String token = normalize(source);
        if (token == null) {
            return null;
        }
        String upper = token.toUpperCase(Locale.ROOT);
        return SOURCE_ALIASES.getOrDefault(upper, upper);
    }

    private boolean isTransitionAllowed(String current, String next, boolean explicitReplayAttempt) {
        if (current == null || current.equals(next)) {
            return true;
        }
        if (STATE_UNMATCHED.equals(current)) {
            return STATE_LINKED.equals(next) || STATE_CONFLICT.equals(next) || STATE_SKIPPED.equals(next);
        }
        if (STATE_SKIPPED.equals(current)) {
            if (!explicitReplayAttempt) {
                return false;
            }
            return STATE_UNMATCHED.equals(next) || STATE_LINKED.equals(next);
        }
        if (STATE_CONFLICT.equals(current)) {
            return explicitReplayAttempt && STATE_LINKED.equals(next);
        }
        if (STATE_LINKED.equals(current)) {
            return STATE_LINKED.equals(next);
        }
        return false;
    }

    private void openRelinkConflict(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            String currentCanonicalId,
            String nextCanonicalId
    ) {
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        entityType, source, sourceRecordId, REASON_RELINK_REJECTED, STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(entityType);
        conflict.setIncomingSource(source);
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(REASON_RELINK_REJECTED);
        conflict.setStatus(STATUS_OPEN);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (currentCanonicalId != null) {
            candidates.add(currentCanonicalId);
        }
        if (nextCanonicalId != null) {
            candidates.add(nextCanonicalId);
        }
        conflict.setCandidateCanonicalIds(new ArrayList<>(candidates));
        conflict.setSourceEventId(normalize(sourceEventId));
        conflict.setSourceBatchId(normalize(sourceBatchId));
        conflict.setSourceCorrelationId(normalize(sourceCorrelationId));
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        identityConflictRepository.save(conflict);
        H19CanonicalMetrics.recordConflictCreated(entityType.name(), source, REASON_RELINK_REJECTED);
    }

    private ScholardexEntityType parseEntityType(String value) {
        String token = normalize(value);
        if (token == null) {
            return null;
        }
        try {
            return ScholardexEntityType.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeFilter(String value) {
        String normalized = normalize(value);
        return normalized == null ? "" : normalized;
    }

    private String normalizeState(String state) {
        String token = normalize(state);
        if (token == null) {
            return null;
        }
        String upper = token.toUpperCase(Locale.ROOT);
        if (STATE_LINKED.equals(upper) || STATE_CONFLICT.equals(upper) || STATE_UNMATCHED.equals(upper) || STATE_SKIPPED.equals(upper)) {
            return upper;
        }
        return null;
    }

    private int normalizePage(Integer page) {
        if (page == null || page < 0) {
            return 0;
        }
        return page;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 200);
    }

    public record SourceLinkWriteResult(boolean accepted, String reason, ScholardexSourceLink link) {
        static SourceLinkWriteResult accepted(ScholardexSourceLink link) {
            return new SourceLinkWriteResult(true, null, link);
        }

        static SourceLinkWriteResult rejected(String reason) {
            return new SourceLinkWriteResult(false, reason, null);
        }
    }

    public record ReplayEligibilitySummary(long unmatched, long conflict, long skipped) {
        public long total() {
            return unmatched + conflict + skipped;
        }
    }

    public record ImportRepairSummary(long updated, long skipped, long errors) {
    }
}

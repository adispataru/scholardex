package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H66B M1c — records canonical identity conflicts, extracted from {@code WosScholardexOnboardingService} so
 * the forthcoming {@code ForumMergeEngine} (and the WoS/Scopus onboarding paths) share one conflict-writing
 * collaborator instead of inlining it. Two operations: open/refresh an idempotent identity conflict
 * (de-duped by entity+source+record+reason+OPEN), and stamp a CONFLICT source link. Behavior is identical to
 * the extracted originals.
 */
@Service
@RequiredArgsConstructor
public class ConflictRecorder {

    static final String STATUS_OPEN = "OPEN";

    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexSourceLinkService sourceLinkService;

    /** Open (or refresh) an OPEN identity conflict for an incoming source record; idempotent per reason. */
    public void openConflict(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reasonCode,
            List<String> candidateIds,
            String batchId,
            String correlationId
    ) {
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        entityType, source, sourceRecordId, reasonCode, STATUS_OPEN)
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(entityType);
        conflict.setIncomingSource(source);
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reasonCode);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidateIds == null ? List.of() : new ArrayList<>(candidateIds));
        conflict.setSourceBatchId(batchId);
        conflict.setSourceCorrelationId(correlationId);
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        identityConflictRepository.save(conflict);
        CanonicalObservabilityMetrics.recordConflictCreated(entityType.name(), source, reasonCode);
    }

    /** Stamp a CONFLICT-state source link for an incoming record (no canonical target). */
    public void markConflictLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reason,
            String batchId,
            String correlationId
    ) {
        sourceLinkService.markConflict(entityType, source, sourceRecordId, reason, null, batchId, correlationId, false);
    }
}

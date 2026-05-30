package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.repository.importing.ImportRunMetricRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConflictOperationsFacade {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_DISMISSED = "DISMISSED";
    private static final String STATUS_INVESTIGATED = "INVESTIGATED";

    private final ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    private final WosIdentityConflictRepository wosIdentityConflictRepository;
    private final WosFactConflictRepository wosFactConflictRepository;
    private final PublicationLinkConflictRepository publicationLinkConflictRepository;
    private final ImportRunMetricRepository importRunMetricRepository;
    private final ImportRunMetricService importRunMetricService;
    private final ScholardexProjectionDirtyService projectionDirtyService;

    public Page<ScholardexIdentityConflict> findIdentityConflicts(
            Integer page,
            Integer size,
            String entityType,
            String incomingSource,
            String reasonCode,
            String status,
            Instant detectedFrom,
            Instant detectedTo
    ) {
        Pageable pageable = PageRequest.of(normalizePage(page), normalizeSize(size), Sort.by(Sort.Direction.DESC, "detectedAt"));
        String sourceFilter = normalizeFilter(incomingSource);
        String reasonFilter = normalizeFilter(reasonCode);
        String statusFilter = normalizeFilter(status);
        Instant from = detectedFrom == null ? Instant.EPOCH : detectedFrom;
        Instant to = detectedTo == null ? Instant.parse("9999-12-31T23:59:59Z") : detectedTo;
        if (from.isAfter(to)) {
            Instant swap = from;
            from = to;
            to = swap;
        }
        ScholardexEntityType entity = parseEntityType(entityType);
        if (entity == null) {
            return scholardexIdentityConflictRepository
                    .findAllByIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                            sourceFilter, reasonFilter, statusFilter, from, to, pageable
                    );
        }
        return scholardexIdentityConflictRepository
                .findAllByEntityTypeAndIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                        entity, sourceFilter, reasonFilter, statusFilter, from, to, pageable
                );
    }

    public Page<ScholardexIdentityConflict> findNeedsReviewIdentityConflicts(
            Integer page,
            Integer size,
            String entityType,
            String incomingSource,
            String reasonCode,
            String status,
            Instant detectedFrom,
            Instant detectedTo
    ) {
        Pageable pageable = PageRequest.of(normalizePage(page), normalizeSize(size), Sort.by(Sort.Direction.DESC, "detectedAt"));
        String sourceFilter = normalizeFilter(incomingSource);
        String reasonFilter = normalizeFilter(reasonCode);
        String statusFilter = normalizeFilter(status);
        Instant from = detectedFrom == null ? Instant.EPOCH : detectedFrom;
        Instant to = detectedTo == null ? Instant.parse("9999-12-31T23:59:59Z") : detectedTo;
        if (from.isAfter(to)) {
            Instant swap = from;
            from = to;
            to = swap;
        }
        ScholardexEntityType entity = parseEntityType(entityType);
        if (entity == null) {
            return scholardexIdentityConflictRepository
                    .findNeedsReviewByFilters(sourceFilter, reasonFilter, statusFilter, from, to, pageable);
        }
        return scholardexIdentityConflictRepository
                .findNeedsReviewByEntityTypeAndFilters(entity, sourceFilter, reasonFilter, statusFilter, from, to, pageable);
    }

    public long updateConflictStatus(String id, String requestedStatus, String resolvedBy) {
        String nextStatus = normalizeStatus(requestedStatus);
        return updateConflictStatusNormalized(id, nextStatus, resolvedBy);
    }

    public long bulkUpdateConflictStatus(List<String> ids, String requestedStatus, String resolvedBy) {
        String nextStatus = normalizeStatus(requestedStatus);
        if (nextStatus == null || ids == null || ids.isEmpty()) {
            return 0L;
        }
        long updated = 0L;
        for (String id : ids) {
            updated += updateConflictStatusNormalized(id, nextStatus, resolvedBy);
        }
        return updated;
    }

    public long clearOpenIdentityConflicts() {
        List<ScholardexIdentityConflict> all = scholardexIdentityConflictRepository.findAll();
        List<ScholardexIdentityConflict> toDelete = all.stream()
                .filter(c -> STATUS_OPEN.equalsIgnoreCase(normalizeFilter(c.getStatus())))
                .toList();
        if (toDelete.isEmpty()) {
            return 0L;
        }
        scholardexIdentityConflictRepository.deleteAll(toDelete);
        return toDelete.size();
    }

    public ConflictSummary summarizeIdentityConflicts() {
        long open = scholardexIdentityConflictRepository.countByStatus(STATUS_OPEN);
        long resolved = scholardexIdentityConflictRepository.countByStatus(STATUS_RESOLVED);
        long dismissed = scholardexIdentityConflictRepository.countByStatus(STATUS_DISMISSED);
        long investigated = scholardexIdentityConflictRepository.countByStatus(STATUS_INVESTIGATED);
        return new ConflictSummary(open, resolved, dismissed, investigated);
    }

    public ConflictSummary summarizeNeedsReviewIdentityConflicts() {
        long open = scholardexIdentityConflictRepository.countNeedsReviewByStatus(STATUS_OPEN);
        long resolved = scholardexIdentityConflictRepository.countNeedsReviewByStatus(STATUS_RESOLVED);
        long dismissed = scholardexIdentityConflictRepository.countNeedsReviewByStatus(STATUS_DISMISSED);
        long investigated = scholardexIdentityConflictRepository.countNeedsReviewByStatus(STATUS_INVESTIGATED);
        return new ConflictSummary(open, resolved, dismissed, investigated);
    }

    public AuditOnlySummary summarizeAuditOnlyConflicts() {
        return new AuditOnlySummary(
                importRunMetricRepository.count(),
                scholardexIdentityConflictRepository.countAuditOnlyDeterministic(),
                wosFactConflictRepository.count(),
                publicationLinkConflictRepository.count()
        );
    }

    public ScholardexProjectionDirtyService.ProjectionDirtySummary summarizeDirtyProjections() {
        return projectionDirtyService.summarizeDirtyProjections();
    }

    public ScholardexProjectionDirtyService.ProjectionRebuildResult rebuildDirtyProjections() {
        return projectionDirtyService.rebuildDirtyProjections();
    }

    public LegacyConflictCleanupSummary cleanupDeterministicLegacyConflicts() {
        List<ScholardexIdentityConflict> all = scholardexIdentityConflictRepository.findAll();
        List<ScholardexIdentityConflict> deterministic = new ArrayList<>();
        Map<CleanupMetricKey, Long> metricCounts = new LinkedHashMap<>();
        long retainedNeedsReview = 0L;

        for (ScholardexIdentityConflict conflict : all) {
            if (isNeedsReview(conflict)) {
                retainedNeedsReview++;
                continue;
            }
            deterministic.add(conflict);
            CleanupMetricKey key = cleanupMetricKey(conflict);
            metricCounts.merge(key, 1L, Long::sum);
        }

        if (!deterministic.isEmpty()) {
            for (Map.Entry<CleanupMetricKey, Long> entry : metricCounts.entrySet()) {
                CleanupMetricKey key = entry.getKey();
                importRunMetricService.record(
                        "legacy-conflict-cleanup",
                        key.source(),
                        key.entityType(),
                        key.reason(),
                        entry.getValue()
                );
            }
            scholardexIdentityConflictRepository.deleteAll(deterministic);
        }

        return new LegacyConflictCleanupSummary(
                deterministic.size(),
                retainedNeedsReview,
                metricCounts.size(),
                wosFactConflictRepository.count(),
                wosIdentityConflictRepository.count(),
                publicationLinkConflictRepository.count()
        );
    }

    public long clearWosIdentityConflicts() {
        long count = wosIdentityConflictRepository.count();
        wosIdentityConflictRepository.deleteAll();
        return count;
    }

    public long clearWosFactConflicts() {
        long count = wosFactConflictRepository.count();
        wosFactConflictRepository.deleteAll();
        return count;
    }

    public long clearScopusLinkConflicts() {
        long count = publicationLinkConflictRepository.count();
        publicationLinkConflictRepository.deleteAll();
        return count;
    }

    private int normalizePage(Integer page) {
        if (page == null || page < 0) {
            return 0;
        }
        return page;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String normalizeFilter(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private ScholardexEntityType parseEntityType(String value) {
        String token = normalizeFilter(value);
        if (token.isBlank()) {
            return null;
        }
        try {
            return ScholardexEntityType.valueOf(token.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalizeStatus(String requestedStatus) {
        String token = normalizeFilter(requestedStatus).toUpperCase();
        if (STATUS_RESOLVED.equals(token) || STATUS_DISMISSED.equals(token) || STATUS_INVESTIGATED.equals(token)) {
            return token;
        }
        return null;
    }

    private long updateConflictStatusNormalized(String id, String normalizedStatus, String resolvedBy) {
        if (normalizedStatus == null || id == null || id.isBlank()) {
            return 0L;
        }
        return scholardexIdentityConflictRepository.findByIdAndStatus(id.trim(), STATUS_OPEN)
                .map(conflict -> {
                    conflict.setStatus(normalizedStatus);
                    conflict.setResolvedAt(Instant.now());
                    conflict.setResolvedBy(normalizeFilter(resolvedBy));
                    scholardexIdentityConflictRepository.save(conflict);
                    return 1L;
                })
                .orElse(0L);
    }

    private boolean isNeedsReview(ScholardexIdentityConflict conflict) {
        return conflict != null
                && conflict.getCandidateCanonicalIds() != null
                && conflict.getCandidateCanonicalIds().size() > 1;
    }

    private CleanupMetricKey cleanupMetricKey(ScholardexIdentityConflict conflict) {
        return new CleanupMetricKey(
                normalizeMetricValue(conflict == null ? null : conflict.getIncomingSource(), "UNKNOWN_SOURCE"),
                conflict == null || conflict.getEntityType() == null ? "UNKNOWN_ENTITY" : conflict.getEntityType().name(),
                "legacy-deterministic-identity-conflict:" + normalizeMetricValue(
                        conflict == null ? null : conflict.getReasonCode(),
                        "UNKNOWN_REASON"
                )
        );
    }

    private String normalizeMetricValue(String value, String fallback) {
        String normalized = normalizeFilter(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    public record ConflictSummary(long open, long resolved, long dismissed, long investigated) {
        public long total() {
            return open + resolved + dismissed + investigated;
        }
    }

    public record AuditOnlySummary(
            long importRunMetricAggregates,
            long scholardexDeterministicIdentityConflictRows,
            long wosFactConflictRows,
            long scopusPublicationLinkConflictRows
    ) {
        public long total() {
            return importRunMetricAggregates
                    + scholardexDeterministicIdentityConflictRows
                    + wosFactConflictRows
                    + scopusPublicationLinkConflictRows;
        }
    }

    public record LegacyConflictCleanupSummary(
            long deletedDeterministicIdentityConflicts,
            long retainedNeedsReviewIdentityConflicts,
            long metricAggregatesRecorded,
            long wosFactConflictRowsPreserved,
            long wosIdentityConflictRowsPreserved,
            long scopusPublicationLinkConflictRowsPreserved
    ) {
    }

    private record CleanupMetricKey(String source, String entityType, String reason) {
    }
}

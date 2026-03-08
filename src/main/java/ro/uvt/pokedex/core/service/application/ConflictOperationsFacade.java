package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConflictOperationsFacade {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_DISMISSED = "DISMISSED";

    private final ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    private final WosIdentityConflictRepository wosIdentityConflictRepository;
    private final WosFactConflictRepository wosFactConflictRepository;
    private final PublicationLinkConflictRepository publicationLinkConflictRepository;

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

    public long updateConflictStatus(String id, String requestedStatus, String resolvedBy) {
        String nextStatus = normalizeStatus(requestedStatus);
        if (nextStatus == null || id == null || id.isBlank()) {
            return 0L;
        }
        return scholardexIdentityConflictRepository.findByIdAndStatus(id.trim(), STATUS_OPEN)
                .map(conflict -> {
                    conflict.setStatus(nextStatus);
                    conflict.setResolvedAt(Instant.now());
                    conflict.setResolvedBy(normalizeFilter(resolvedBy));
                    scholardexIdentityConflictRepository.save(conflict);
                    return 1L;
                })
                .orElse(0L);
    }

    public long bulkUpdateConflictStatus(List<String> ids, String requestedStatus, String resolvedBy) {
        String nextStatus = normalizeStatus(requestedStatus);
        if (nextStatus == null || ids == null || ids.isEmpty()) {
            return 0L;
        }
        long updated = 0L;
        for (String id : ids) {
            updated += updateConflictStatus(id, nextStatus, resolvedBy);
        }
        return updated;
    }

    public long clearOpenIdentityConflicts() {
        List<ScholardexIdentityConflict> all = scholardexIdentityConflictRepository.findAll();
        long deleted = all.stream()
                .filter(c -> STATUS_OPEN.equalsIgnoreCase(normalizeFilter(c.getStatus())))
                .count();
        if (deleted == 0L) {
            return 0L;
        }
        List<ScholardexIdentityConflict> toDelete = all.stream()
                .filter(c -> STATUS_OPEN.equalsIgnoreCase(normalizeFilter(c.getStatus())))
                .toList();
        scholardexIdentityConflictRepository.deleteAll(toDelete);
        return deleted;
    }

    public ConflictSummary summarizeIdentityConflicts() {
        long open = scholardexIdentityConflictRepository.countByStatus(STATUS_OPEN);
        long resolved = scholardexIdentityConflictRepository.countByStatus(STATUS_RESOLVED);
        long dismissed = scholardexIdentityConflictRepository.countByStatus(STATUS_DISMISSED);
        return new ConflictSummary(open, resolved, dismissed);
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
        if (STATUS_RESOLVED.equals(token) || STATUS_DISMISSED.equals(token)) {
            return token;
        }
        return null;
    }

    public record ConflictSummary(long open, long resolved, long dismissed) {
        public long total() {
            return open + resolved + dismissed;
        }
    }
}

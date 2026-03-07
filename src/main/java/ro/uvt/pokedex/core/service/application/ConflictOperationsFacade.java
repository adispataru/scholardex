package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationLinkConflict;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;

@Service
@RequiredArgsConstructor
public class ConflictOperationsFacade {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final WosIdentityConflictRepository wosIdentityConflictRepository;
    private final WosFactConflictRepository wosFactConflictRepository;
    private final PublicationLinkConflictRepository publicationLinkConflictRepository;

    public Page<WosIdentityConflict> findWosIdentityConflicts(
            Integer page,
            Integer size,
            String sourceVersion,
            String sourceFile,
            String conflictType
    ) {
        Pageable pageable = PageRequest.of(normalizePage(page), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "conflictDetectedAt"));
        return wosIdentityConflictRepository
                .findAllBySourceVersionContainingIgnoreCaseAndSourceFileContainingIgnoreCaseAndConflictTypeContainingIgnoreCase(
                        normalizeFilter(sourceVersion),
                        normalizeFilter(sourceFile),
                        normalizeFilter(conflictType),
                        pageable
                );
    }

    public Page<WosFactConflict> findWosFactConflicts(
            Integer page,
            Integer size,
            String sourceVersion,
            String factType,
            String conflictReason
    ) {
        Pageable pageable = PageRequest.of(normalizePage(page), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "detectedAt"));
        String factTypeFilter = normalizeFilter(factType);
        String conflictReasonFilter = normalizeFilter(conflictReason);
        String sourceVersionFilter = normalizeFilter(sourceVersion);
        if (sourceVersionFilter.isBlank()) {
            return wosFactConflictRepository
                    .findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(
                            factTypeFilter,
                            conflictReasonFilter,
                            pageable
                    );
        }
        return wosFactConflictRepository
                .findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndWinnerSourceVersionContainingIgnoreCaseOrFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndLoserSourceVersionContainingIgnoreCase(
                        factTypeFilter,
                        conflictReasonFilter,
                        sourceVersionFilter,
                        factTypeFilter,
                        conflictReasonFilter,
                        sourceVersionFilter,
                        pageable
                );
    }

    public Page<PublicationLinkConflict> findScopusLinkConflicts(
            Integer page,
            Integer size,
            String enrichmentSource,
            String keyType,
            String conflictReason
    ) {
        Pageable pageable = PageRequest.of(normalizePage(page), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "detectedAt"));
        return publicationLinkConflictRepository
                .findAllByEnrichmentSourceContainingIgnoreCaseAndKeyTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(
                        normalizeFilter(enrichmentSource),
                        normalizeFilter(keyType),
                        normalizeFilter(conflictReason),
                        pageable
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
}

package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a source record id to its canonical entity id via the source-link index (H54.5c).
 *
 * <p>Extracted from {@code ScholardexProjectionReadService} so the read paths and the relocated
 * manual-edit write path ({@code ScholardexManualEditService}) share one resolution implementation.
 */
@Component
public class ScholardexCanonicalIdResolver {

    private final ScholardexSourceLinkService sourceLinkService;

    public ScholardexCanonicalIdResolver(ScholardexSourceLinkService sourceLinkService) {
        this.sourceLinkService = sourceLinkService;
    }

    public Optional<String> resolveCanonicalId(ScholardexEntityType entityType, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        List<ScholardexSourceLink> mapped = sourceLinkService.findByEntityTypeAndSourceRecordId(entityType, candidate);
        return mapped.stream()
                .map(ScholardexSourceLink::getCanonicalEntityId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst();
    }

    public List<String> resolveCanonicalIds(ScholardexEntityType entityType, Collection<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalizedCandidates = new LinkedHashSet<>();
        for (String id : candidateIds) {
            String normalized = normalizeBlank(id);
            if (normalized != null) {
                normalizedCandidates.add(normalized);
            }
        }
        if (normalizedCandidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>(normalizedCandidates);
        List<ScholardexSourceLink> mapped = sourceLinkService.findByEntityTypeAndSourceRecordIds(entityType, normalizedCandidates);
        mapped.stream()
                .map(ScholardexSourceLink::getCanonicalEntityId)
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .forEach(resolved::add);
        return new ArrayList<>(resolved);
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

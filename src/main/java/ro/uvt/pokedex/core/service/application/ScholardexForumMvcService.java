package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumPageResponse;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScholardexForumMvcService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final Pattern ISSN_PATTERN = Pattern.compile("([0-9Xx]{4})-?([0-9Xx]{4})");

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final WosRankingViewRepository wosRankingViewRepository;

    public ScholardexForumPageResponse search(int page, int size, String sort, String direction, String q, String wos) {
        String normalizedSort = normalizeSort(sort);
        boolean ascending = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);
        String normalizedWosFilter = normalizeWosFilter(wos);

        List<ScholardexForumListItemResponse> rows = scholardexProjectionReadService.findAllForums().stream()
                .map(this::toListItem)
                .filter(item -> matchesQuery(item, normalizedQuery))
                .filter(item -> matchesWos(item, normalizedWosFilter))
                .sorted(buildComparator(normalizedSort, ascending))
                .toList();

        long totalItems = rows.size();
        int totalPages = (int) Math.ceil(totalItems / (double) size);
        int safePage = Math.max(0, page);
        int fromIndex = Math.min(safePage * size, rows.size());
        int toIndex = Math.min(fromIndex + size, rows.size());
        List<ScholardexForumListItemResponse> items = fromIndex >= toIndex
                ? List.of()
                : new ArrayList<>(rows.subList(fromIndex, toIndex));

        return new ScholardexForumPageResponse(items, safePage, size, totalItems, totalPages);
    }

    private ScholardexForumListItemResponse toListItem(Forum forum) {
        String wosJournalId = resolveWosJournalId(forum);
        return new ScholardexForumListItemResponse(
                forum.getId(),
                forum.getPublicationName(),
                forum.getIssn(),
                forum.getEIssn(),
                forum.getAggregationType(),
                resolveWosStatus(forum, wosJournalId),
                wosJournalId
        );
    }

    private String resolveWosStatus(Forum forum, String wosJournalId) {
        String aggregationType = normalize(forum.getAggregationType());
        if (aggregationType.contains("journal")) {
            return wosJournalId == null ? "not_indexed" : "indexed";
        }
        return "not_applicable";
    }

    private String resolveWosJournalId(Forum forum) {
        for (String issn : extractIssnCandidates(forum.getIssn(), forum.getEIssn())) {
            Optional<WosRankingView> byIssnNorm = wosRankingViewRepository.findFirstByIssnNorm(issn);
            if (byIssnNorm.isPresent() && notBlank(byIssnNorm.get().getId())) {
                return byIssnNorm.get().getId();
            }
            Optional<WosRankingView> byEIssnNorm = wosRankingViewRepository.findFirstByeIssnNorm(issn);
            if (byEIssnNorm.isPresent() && notBlank(byEIssnNorm.get().getId())) {
                return byEIssnNorm.get().getId();
            }
            Optional<WosRankingView> byAltIssnNorm = wosRankingViewRepository.findFirstByAlternativeIssnsNormContains(issn);
            if (byAltIssnNorm.isPresent() && notBlank(byAltIssnNorm.get().getId())) {
                return byAltIssnNorm.get().getId();
            }
        }
        return null;
    }

    private LinkedHashSet<String> extractIssnCandidates(String... values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (!notBlank(value)) {
                continue;
            }
            Matcher matcher = ISSN_PATTERN.matcher(value);
            while (matcher.find()) {
                out.add((matcher.group(1) + matcher.group(2)).toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private boolean matchesQuery(ScholardexForumListItemResponse item, String query) {
        if (query == null) {
            return true;
        }
        return normalize(item.publicationName()).contains(query)
                || normalize(item.issn()).contains(query)
                || normalize(item.eIssn()).contains(query)
                || normalize(item.aggregationType()).contains(query)
                || normalize(displayWosStatus(item.wosStatus())).contains(query);
    }

    private boolean matchesWos(ScholardexForumListItemResponse item, String wos) {
        if ("all".equals(wos)) {
            return true;
        }
        return item.wosStatus().equals(wos);
    }

    private Comparator<ScholardexForumListItemResponse> buildComparator(String sort, boolean ascending) {
        Comparator<ScholardexForumListItemResponse> comparator = switch (sort) {
            case "publicationName" -> Comparator.comparing(
                    item -> normalize(item.publicationName()),
                    Comparator.naturalOrder()
            );
            case "issn" -> Comparator.comparing(item -> normalize(item.issn()), Comparator.naturalOrder());
            case "eIssn" -> Comparator.comparing(item -> normalize(item.eIssn()), Comparator.naturalOrder());
            case "aggregationType" -> Comparator.comparing(item -> normalize(item.aggregationType()), Comparator.naturalOrder());
            case "wosStatus" -> Comparator.comparing(item -> normalize(item.wosStatus()), Comparator.naturalOrder());
            default -> throw new IllegalArgumentException("Invalid sort parameter. Allowed: publicationName, issn, eIssn, aggregationType, wosStatus.");
        };
        comparator = comparator.thenComparing(item -> normalize(item.id()), Comparator.naturalOrder());
        return ascending ? comparator : comparator.reversed();
    }

    private String normalizeSort(String sort) {
        return sort == null || sort.isBlank() ? "publicationName" : sort.trim();
    }

    private boolean normalizeDirection(String direction) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("asc") && !normalized.equals("desc")) {
            throw new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc.");
        }
        return normalized.equals("asc");
    }

    private String normalizeQuery(String q) {
        if (q == null) {
            return null;
        }
        String normalized = q.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Invalid q parameter. Maximum length is " + MAX_QUERY_LENGTH + ".");
        }
        return normalized;
    }

    private String normalizeWosFilter(String wos) {
        if (wos == null || wos.isBlank()) {
            return "all";
        }
        String normalized = wos.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("all") && !normalized.equals("indexed")
                && !normalized.equals("not_indexed") && !normalized.equals("not_applicable")) {
            throw new IllegalArgumentException("Invalid wos parameter. Allowed: all, indexed, not_indexed, not_applicable.");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String displayWosStatus(String status) {
        return switch (status) {
            case "indexed" -> "WoS indexed";
            case "not_indexed" -> "Not indexed by WoS";
            case "not_applicable" -> "Not applicable";
            default -> status;
        };
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

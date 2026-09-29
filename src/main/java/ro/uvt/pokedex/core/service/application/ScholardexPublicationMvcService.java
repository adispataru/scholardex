package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.PublicationTableItemResponse;
import ro.uvt.pokedex.core.controller.dto.PublicationTablePageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel.AuthorRef;
import ro.uvt.pokedex.core.utils.ScopusLinks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScholardexPublicationMvcService {

    private static final int MAX_AUTHOR_NAMES = 5;
    private static final Pattern SUP_TAG_PATTERN = Pattern.compile("(?i)<\\s*sup\\s*>(.*?)<\\s*/\\s*sup\\s*>");
    private static final Pattern SUB_TAG_PATTERN = Pattern.compile("(?i)<\\s*sub\\s*>(.*?)<\\s*/\\s*sub\\s*>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SCRIPT_MARKER_SPACING_PATTERN = Pattern.compile("\\s+([\\^_])");

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PostgresScholardexProjectionReadPort projectionReadPort;

    public PublicationTablePageResponse search(int page, int size, String sort, String direction, String q) {
        return search(page, size, sort, direction, q, null);
    }

    /**
     * H119: with {@code onlyAuthorIds} the list holds only publications by one of those authors, carries no
     * citation count and cannot be ordered by it — the public view. {@code null} is the unrestricted list.
     */
    public PublicationTablePageResponse search(int page, int size, String sort, String direction, String q,
                                               Set<String> onlyAuthorIds) {
        boolean publicView = onlyAuthorIds != null;
        int safeSize = (size == 50 || size == 100) ? size : 25;
        String safeSort = switch (sort != null ? sort : "") {
            case "year" -> "cover_date";
            case "citations" -> publicView ? "title" : "cited_by_count";
            default -> "title";
        };
        String safeDir = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";

        if (publicView && onlyAuthorIds.isEmpty()) {
            return new PublicationTablePageResponse(List.of(), 0, safeSize, 0L, 1);
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> conditions = new ArrayList<>();
        if (q != null && !q.isBlank() && q.length() <= 200) {
            conditions.add("title ILIKE :q");
            params.addValue("q", "%" + q.trim() + "%");
        }
        if (publicView) {
            conditions.add("author_ids && ARRAY[:onlyAuthorIds]::text[]");
            params.addValue("onlyAuthorIds", onlyAuthorIds);
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

        Long total = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.scholardex_publication_view " + where, params, Long.class);
        long totalCount = total == null ? 0L : total;
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / safeSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        params.addValue("limit", safeSize);
        params.addValue("offset", (long) safePage * safeSize);
        List<ScholardexPublicationView> publications = namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view " + where
                        + " ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset",
                params,
                this::mapMinimal);

        Set<String> authorIds = new HashSet<>();
        Set<String> forumIds = new HashSet<>();
        for (ScholardexPublicationView pub : publications) {
            authorIds.addAll(pub.getAuthors());
            if (pub.getForum() != null && !pub.getForum().isBlank()) forumIds.add(pub.getForum());
        }

        Map<String, String> authorNameById = projectionReadPort.findAuthorsByIdIn(authorIds).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, ScholardexAuthorView::getName, (a, b) -> a));
        Map<String, String> forumNameById = projectionReadPort.findForumsByIdIn(forumIds).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, ScholardexForumView::getPublicationName, (a, b) -> a));

        List<PublicationTableItemResponse> items = publications.stream()
                .map(pub -> toItem(pub, authorNameById, forumNameById, !publicView))
                .collect(Collectors.toCollection(ArrayList::new));

        return new PublicationTablePageResponse(items, safePage, safeSize, totalCount, totalPages);
    }

    public Optional<ScholardexPublicationDetailViewModel> findDetail(String id) {
        return findDetail(id, null);
    }

    /** H119: with {@code onlyAuthorIds}, a publication none of those authors wrote is reported as not found. */
    public Optional<ScholardexPublicationDetailViewModel> findDetail(String id, Set<String> onlyAuthorIds) {
        Optional<ScholardexPublicationView> pubOpt = projectionReadPort.findPublicationByAnyId(id);
        if (pubOpt.isEmpty()) return Optional.empty();

        ScholardexPublicationView pub = pubOpt.get();
        if (onlyAuthorIds != null && pub.getAuthors().stream().noneMatch(onlyAuthorIds::contains)) {
            return Optional.empty();
        }

        Map<String, String> authorNameById = projectionReadPort.findAuthorsByIdIn(pub.getAuthors()).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, ScholardexAuthorView::getName, (a, b) -> a));

        List<AuthorRef> authors = pub.getAuthors().stream()
                .map(aid -> new AuthorRef(aid, authorNameById.getOrDefault(aid, aid)))
                .toList();

        String forumName = null;
        if (pub.getForum() != null && !pub.getForum().isBlank()) {
            forumName = projectionReadPort.findForumsByIdIn(Set.of(pub.getForum())).stream()
                    .findFirst()
                    .map(ScholardexForumView::getPublicationName)
                    .orElse(null);
        }

        return Optional.of(new ScholardexPublicationDetailViewModel(pub, authors, forumName, publicationYear(pub.getCoverDate())));
    }

    private PublicationTableItemResponse toItem(
            ScholardexPublicationView pub,
            Map<String, String> authorNameById,
            Map<String, String> forumNameById,
            boolean withCitations) {

        List<String> authorNames = pub.getAuthors().stream()
                .limit(MAX_AUTHOR_NAMES)
                .map(id -> authorNameById.getOrDefault(id, id))
                .toList();

        return new PublicationTableItemResponse(
                pub.getId(),
                displayTitle(pub.getTitle(), "Untitled publication " + pub.getId()),
                publicationYear(pub.getCoverDate()),
                pub.getForum(),
                displayForumName(pub.getForum(), forumNameById),
                authorNames,
                withCitations ? pub.getCitedbyCount() : null,
                pub.getEid(),
                ScopusLinks.recordUrl(pub.getEid())
        );
    }

    private ScholardexPublicationView mapMinimal(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId(rs.getString("id"));
        pub.setEid(rs.getString("eid"));
        pub.setTitle(rs.getString("title"));
        pub.setCoverDate(rs.getString("cover_date"));
        pub.setForum(rs.getString("forum_id"));
        Integer citedByCount = rs.getObject("cited_by_count", Integer.class);
        pub.setCitedbyCount(citedByCount == null ? 0 : citedByCount);
        pub.setAuthors(toStringList(rs.getArray("author_ids")));
        return pub;
    }

    private String publicationYear(String coverDate) {
        return coverDate != null && coverDate.length() >= 4
                ? coverDate.substring(0, 4)
                : coverDate;
    }

    private String displayForumName(String forumId, Map<String, String> forumNameById) {
        if (forumId == null || forumId.isBlank()) {
            return "";
        }
        return displayName(forumNameById.get(forumId), "Untitled forum " + forumId);
    }

    private String displayTitle(String value, String fallback) {
        String text = displayName(value, fallback);
        text = SUP_TAG_PATTERN.matcher(text).replaceAll("^$1");
        text = SUB_TAG_PATTERN.matcher(text).replaceAll("_$1");
        text = HTML_TAG_PATTERN.matcher(text).replaceAll(" ");
        text = WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
        return SCRIPT_MARKER_SPACING_PATTERN.matcher(text).replaceAll("$1");
    }

    private String displayName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private List<String> toStringList(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object[] arr = (Object[]) array.getArray();
        List<String> result = new ArrayList<>(arr.length);
        for (Object o : arr) {
            if (o != null) result.add(o.toString());
        }
        return result;
    }
}

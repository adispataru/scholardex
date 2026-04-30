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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScholardexPublicationMvcService {

    private static final int MAX_AUTHOR_NAMES = 5;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PostgresScholardexProjectionReadPort projectionReadPort;

    public PublicationTablePageResponse search(int page, int size, String sort, String direction, String q) {
        int safeSize = (size == 50 || size == 100) ? size : 25;
        String safeSort = switch (sort != null ? sort : "") {
            case "year" -> "cover_date";
            case "citations" -> "cited_by_count";
            default -> "title";
        };
        String safeDir = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = "";
        if (q != null && !q.isBlank() && q.length() <= 200) {
            where = "WHERE title ILIKE :q";
            params.addValue("q", "%" + q.trim() + "%");
        }

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
                .map(pub -> toItem(pub, authorNameById, forumNameById))
                .collect(Collectors.toCollection(ArrayList::new));

        return new PublicationTablePageResponse(items, safePage, safeSize, totalCount, totalPages);
    }

    public Optional<ScholardexPublicationDetailViewModel> findDetail(String id) {
        Optional<ScholardexPublicationView> pubOpt = projectionReadPort.findPublicationByAnyId(id);
        if (pubOpt.isEmpty()) return Optional.empty();

        ScholardexPublicationView pub = pubOpt.get();

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
            Map<String, String> forumNameById) {

        List<String> authorNames = pub.getAuthors().stream()
                .limit(MAX_AUTHOR_NAMES)
                .map(id -> authorNameById.getOrDefault(id, id))
                .toList();

        return new PublicationTableItemResponse(
                pub.getId(),
                pub.getTitle(),
                publicationYear(pub.getCoverDate()),
                pub.getForum(),
                pub.getForum() != null ? forumNameById.getOrDefault(pub.getForum(), "") : "",
                authorNames,
                pub.getCitedbyCount(),
                pub.getEid()
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
